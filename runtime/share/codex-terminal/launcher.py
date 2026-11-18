import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
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
            if server:
                server.shutdown()
                server.server_close()
    except (ValueError, KeyError, OSError) as e:
        print('ACodexT:', str(e), file=sys.stderr)
        return 1

if __name__ == '__main__':
    raise SystemExit(main())
