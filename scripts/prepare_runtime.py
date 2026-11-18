#!/usr/bin/env python3
"""Bundle verified ARM64 Termux packages under this app's independent prefix."""
import concurrent.futures
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.tools'
OLD = b'/data/data/com.termux/files'
NEW = b'/data/data/com.acodext/root'
REPO = 'https://packages.termux.dev/apt/termux-main/'
BOOTSTRAP = 'https://github.com/termux/termux-packages/releases/download/bootstrap-2025.03.28-r1%2Bapt-android-7/bootstrap-aarch64.zip'
BOOTSTRAP_HASH = 'c8d702b6f742935001c37cda81b8ac69504a95d5cf28f2899532dd8cd4b057eb'

def fetch(url, dest, digest=None):
    dest.parent.mkdir(parents=True, exist_ok=True)
    if not dest.exists() or (digest and hashlib.sha256(dest.read_bytes()).hexdigest() != digest):
        tmp = dest.with_suffix(dest.suffix + '.part')
        subprocess.run(['curl', '-fsSL', '--retry', '4', '--max-time', '300', url, '-o', str(tmp)], check=True)
        if digest and hashlib.sha256(tmp.read_bytes()).hexdigest() != digest:
            raise ValueError('Checksum mismatch: ' + url)
        tmp.replace(dest)
    return dest

def parse_packages(data):
    packages = {}
    for block in data.split('\n\n'):
        d = {}
        for line in block.splitlines():
            if ': ' in line and not line.startswith(' '):
                key, value = line.split(': ', 1)
                d[key] = value
        if 'Package' in d:
            packages[d['Package']] = d
    return packages

def dependencies(d):
    for group in (d.get('Pre-Depends', '') + ',' + d.get('Depends', '')).split(','):
        name = re.split(r'[ (]', group.strip().split('|')[0])[0]
        if name:
            yield name

def relocate(data):
    assert len(OLD) == len(NEW)
    return data.replace(OLD, NEW).replace(b'/data/user/0/com.termux/files', b'/data/user/0/com.acodext/root')

def main():
    CACHE.mkdir(exist_ok=True)
    assert len(OLD) == len(NEW)
    lock_path = ROOT / 'runtime-lock.json'
    if lock_path.exists():
        lock = json.loads(lock_path.read_text())
        chosen = lock['packages']
    else:
        index_path = fetch(REPO + 'dists/stable/main/binary-aarch64/Packages.gz', CACHE / 'Packages.gz')
        index = parse_packages(gzip.decompress(index_path.read_bytes()).decode())
        chosen = {}
        def add(name):
            if name in chosen:
                return
            chosen[name] = index[name]
            for dep in dependencies(index[name]):
                add(dep)
        for name in ['codex', 'git', 'ripgrep', 'python', 'bash', 'coreutils', 'curl', 'diffutils', 'findutils', 'grep', 'sed', 'gawk', 'patch', 'tar', 'gzip', 'xz-utils', 'nano', 'openssh']:
            add(name)
        lock = {'bootstrap_url': BOOTSTRAP, 'bootstrap_sha256': BOOTSTRAP_HASH, 'repository': REPO, 'packages': chosen}
        lock_path.write_text(json.dumps(lock, indent=2) + '\n')
    bootstrap = fetch(BOOTSTRAP, CACHE / 'bootstrap-aarch64.zip', BOOTSTRAP_HASH)
    print('Fetching', len(chosen), 'verified packages', flush=True)
    def download(d):
        return fetch(REPO + d['Filename'], CACHE / 'debs' / Path(d['Filename']).name, d['SHA256'])
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
        list(executor.map(download, chosen.values()))
    stage = CACHE / 'runtime'
    if stage.exists():
        shutil.rmtree(stage)
    prefix = stage / 'usr'
    prefix.mkdir(parents=True)
    links = {}
    with zipfile.ZipFile(bootstrap) as z:
        for item in z.infolist():
            if item.filename == 'SYMLINKS.txt':
                for line in z.read(item).decode().splitlines():
                    target, path = line.split('←')
                    links['usr/' + path] = target.replace(OLD.decode(), NEW.decode())
            elif not item.is_dir():
                p = prefix / item.filename
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(relocate(z.read(item)))
                os.chmod(p, 0o700 if item.filename.startswith(('bin/', 'libexec/', 'lib/apt/')) else 0o600)
    # Overlay complete dependency closure; never install into the device's Termux directory.
    for name, d in chosen.items():
        archive = CACHE / 'debs' / Path(d['Filename']).name
        member = next(x for x in subprocess.check_output(['ar', 't', str(archive)], text=True).splitlines() if x.startswith('data.tar'))
        data = subprocess.check_output(['ar', 'p', str(archive), member])
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:*') as tar:
            for item in tar:
                path = item.name.removeprefix('./')
                base = 'data/data/com.termux/files/'
                if not path.startswith(base):
                    continue
                rel = path[len(base):]
                if '..' in Path(rel).parts:
                    raise ValueError('Unsafe package path')
                dest = stage / rel
                if item.isdir():
                    dest.mkdir(parents=True, exist_ok=True)
                elif item.issym():
                    links[rel] = item.linkname.replace(OLD.decode(), NEW.decode())
                    if dest.exists():
                        dest.unlink()
                elif item.isfile():
                    links.pop(rel, None)
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(relocate(tar.extractfile(item).read()))
                    os.chmod(dest, 0o700 if item.mode & 0o111 else 0o600)
                elif item.islnk():
                    # Termux packages normally use symlinks. Copy a hardlinked file if present.
                    src = item.linkname.removeprefix('./').removeprefix(base)
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(stage / src, dest)
    # Keep the official binary accessible while a small Python wrapper loads the selected profile.
    (prefix / 'bin/codex').rename(prefix / 'bin/codex-native')
    for file in (ROOT / 'runtime').rglob('*'):
        if file.is_file() and '__pycache__' not in file.parts and file.suffix != '.pyc':
            rel = file.relative_to(ROOT / 'runtime')
            dest = prefix / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(file, dest)
            os.chmod(dest, 0o700 if str(rel).startswith('bin/') else 0o600)
            links.pop('usr/' + str(rel), None)
    licenses = prefix / 'share/codex-terminal/licenses'
    licenses.mkdir(parents=True, exist_ok=True)
    for source in (ROOT / 'LICENSES').glob('*.txt'):
        shutil.copyfile(source, licenses / source.name)
    versions = {n: d['Version'] for n, d in chosen.items()}
    meta = prefix / 'share/codex-terminal/packages.json'
    meta.parent.mkdir(parents=True, exist_ok=True)
    meta.write_text(json.dumps(versions))
    # Remove apt's bootstrap entry point: its archives target com.termux. Our pkg relocates archives.
    for n in ['apt', 'apt-get', 'dpkg']:
        p = prefix / 'bin' / n
        if p.exists():
            p.rename(p.with_name(n + '-upstream'))
    for n in ['apt', 'apt-get']:
        links['usr/bin/' + n] = 'pkg'
    # Java extracts regular files and recreates symlinks afterwards.
    output = ROOT / 'app/src/main/assets/runtime.zip'
    temporary = output.with_suffix('.zip.tmp')
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for file in sorted(stage.rglob('*')):
            if file.is_file():
                z.write(file, str(file.relative_to(stage)))
        z.writestr('SYMLINKS.json', json.dumps(links))
    temporary.replace(output)
    print('Runtime:', output, output.stat().st_size, 'bytes; Codex', versions['codex'], flush=True)

if __name__ == '__main__':
    main()
