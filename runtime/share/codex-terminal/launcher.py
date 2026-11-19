import json
import hashlib
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import tempfile
import threading

def read_profiles():
    path = Path(os.environ['HOME']) / '.config/codex-terminal/providers.json'
    try:
        return json.loads(path.read_text())
    except FileNotFoundError:
        raise ValueError('Add a provider in ACodexT → Providers first.')

def configure(profile, root, base_url):
    """Each provider gets independent Codex state; credentials stay out of config.toml."""
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    config = root / 'config.toml'
    if not config.exists():
        config.write_text('approval_policy = "on-request"\nsandbox_mode = "danger-full-access"\ncheck_for_update_on_startup = false\nweb_search = "disabled"\n\n[features]\ncode_mode = false\nresponses_websockets = false\nresponses_websockets_v2 = false\n')
        config.chmod(0o600)
    # CLI overrides preserve user customizations such as MCP servers and session history.
    return [
        '-c', 'model_provider="site"',
        '-c', 'model_providers.site.name=' + json.dumps(profile['name']),
        '-c', 'model_providers.site.base_url=' + json.dumps(base_url),
        '-c', 'model_providers.site.env_key="CODEX_SITE_TOKEN"',
        '-c', 'model_providers.site.wire_api="responses"',
        '-c', 'model_providers.site.requires_openai_auth=false',
        '-c', 'model_providers.site.supports_websockets=false',
        '-c', 'model=' + json.dumps(profile['model']),
        '-c', 'model_reasoning_effort="max"',
    ]

def project_session_state_path(codex_home, cwd, state_home=None):
    project = os.path.realpath(cwd)
    key = hashlib.sha256(os.fsencode(project)).hexdigest()
    if state_home is None:
        folder = Path(codex_home) / 'acodext-project-sessions'
    else:
        profile_id = Path(codex_home).name
        folder = Path(state_home) / '.config/codex-terminal/last-sessions' / profile_id
    return folder / (key + '.json')

def latest_session_id(codex_home, cwd):
    sessions = Path(codex_home) / 'sessions'
    if not sessions.is_dir():
        return None
    project = os.path.realpath(cwd)
    candidates = []
    try:
        for path in sessions.rglob('rollout-*.jsonl'):
            try:
                candidates.append((path.stat().st_mtime_ns, path))
            except OSError:
                continue
    except OSError:
        return None
    for _, path in sorted(candidates, reverse=True, key=lambda item: item[0]):
        try:
            with path.open('r', encoding='utf-8') as stream:
                header = json.loads(stream.readline())
            payload = header.get('payload', {})
            session_cwd = payload.get('cwd')
            session_id = payload.get('id') or payload.get('session_id')
            if (header.get('type') == 'session_meta' and session_cwd
                    and os.path.realpath(session_cwd) == project
                    and isinstance(session_id, str)
                    and len(session_id) == 36
                    and all(ch in '0123456789abcdefABCDEF-' for ch in session_id)):
                return session_id
        except (OSError, ValueError, TypeError):
            continue
    return None

def remembered_session_id(codex_home, cwd, state_home=None):
    path = project_session_state_path(codex_home, cwd, state_home)
    try:
        state = json.loads(path.read_text(encoding='utf-8'))
        if (state.get('cwd') == os.path.realpath(cwd)
                and isinstance(state.get('session_id'), str)
                and len(state['session_id']) == 36):
            return state['session_id']
    except (OSError, ValueError, TypeError):
        pass
    return None

def session_id_for_resume(codex_home, cwd, state_home=None):
    session_id = remembered_session_id(codex_home, cwd, state_home)
    if session_id:
        return session_id
    session_id = latest_session_id(codex_home, cwd)
    remember_session_id(codex_home, cwd, session_id, state_home)
    return session_id

def remember_session_id(codex_home, cwd, session_id, state_home=None):
    if not session_id:
        return
    path = project_session_state_path(codex_home, cwd, state_home)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    app_uid = os.environ.get('ACODEXT_APP_UID')
    app_gid = os.environ.get('ACODEXT_APP_GID')
    if os.geteuid() == 0 and app_uid and app_gid:
        os.chown(path.parent, int(app_uid), int(app_gid))
    fd, temporary = tempfile.mkstemp(prefix='.last-session-', dir=str(path.parent))
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump({'cwd': os.path.realpath(cwd), 'session_id': session_id}, stream)
            stream.write('\n')
        os.chmod(temporary, 0o600)
        if os.geteuid() == 0 and app_uid and app_gid:
            os.chown(temporary, int(app_uid), int(app_gid))
        os.replace(temporary, path)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass

def main():
    argv = sys.argv[1:]
    prefix = Path(__file__).resolve().parents[2]
    native = prefix / 'bin/codex-native'
    if any(arg in ['--help', '-h', '--version', '-V', 'completion'] for arg in argv):
        os.execv(str(native), ['codex'] + argv)
    try:
        data = read_profiles()
        selected = os.environ.get('CODEX_PROFILE', data.get('active', ''))
        if '--site' in argv:
            pos = argv.index('--site')
            if pos + 1 >= len(argv):
                raise ValueError('Usage: codex --site NAME [-m MODEL]')
            selected = argv[pos + 1]
            del argv[pos:pos + 2]
        profiles = data.get('profiles', [])
        if '--sites' in argv:
            for p in profiles:
                print(f"{p['name']}  {p['model']}  {p['base_url']}")
            return 0
        profile = next((p for p in profiles if selected in [p['id'], p['name']]), None)
        if not profile:
            raise ValueError('Select a provider in the app, or use codex --site NAME.')
        root = Path(os.environ['HOME']) / '.codex-sites' / profile['id']
        env = dict(os.environ, CODEX_HOME=str(root), CODEX_SITE_TOKEN=profile['token'])
        cwd = os.getcwd()
        state_home = Path(os.environ['HOME'])
        if len(argv) >= 2 and argv[0] == 'resume' and argv[1] == '--last':
            session_id = session_id_for_resume(root, cwd, state_home)
            if session_id:
                argv[1] = session_id
        base_url = profile['base_url']
        server = None
        if profile.get('protocol') == 'chat':
            from bridge import make_server
            secret = secrets.token_urlsafe(32)
            server = make_server(profile, secret)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            base_url = f'http://127.0.0.1:{server.server_port}/v1'
            env['CODEX_SITE_TOKEN'] = secret
        args = [str(native)] + configure(profile, root, base_url) + argv
        child = subprocess.Popen(args, env=env)
        # Both parent and child share the terminal process group. Let the child handle Ctrl-C.
        previous = signal.signal(signal.SIGINT, lambda *_: None)
        try:
            return child.wait()
        finally:
            signal.signal(signal.SIGINT, previous)
            if child.poll() is None:
                child.terminate()
                child.wait()
            if not argv or argv[0] != 'exec':
                remember_session_id(root, cwd, latest_session_id(root, cwd), state_home)
            if server:
                server.shutdown()
                server.server_close()
    except (ValueError, KeyError, OSError) as e:
        print('ACodexT:', str(e), file=sys.stderr)
        return 1

if __name__ == '__main__':
    raise SystemExit(main())
