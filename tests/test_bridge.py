import importlib.util
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading
import unittest
from unittest.mock import ANY
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BRIDGE_PATH = Path(__file__).parents[1] / 'runtime/share/codex-terminal/bridge.py'
spec = importlib.util.spec_from_file_location('codex_bridge', BRIDGE_PATH)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class BridgeConversionTest(unittest.TestCase):
    def test_preserves_text_images_and_function_results(self):
        payload, custom = bridge.to_chat({
            'model': 'model-a',
            'instructions': 'Follow instructions.',
            'input': [
                {'role': 'user', 'content': [
                    {'type': 'input_text', 'text': 'Inspect this'},
                    {'type': 'input_image', 'image_url': 'data:image/png;base64,aGVsbG8='},
                ]},
                {'type': 'function_call', 'call_id': 'call-1', 'name': 'read_file', 'arguments': '{"path":"a"}'},
                {'type': 'function_call_output', 'call_id': 'call-1', 'output': 'contents'},
            ],
            'tools': [{'type': 'function', 'name': 'read_file', 'parameters': {'type': 'object'}}],
        })
        self.assertEqual(payload['messages'][0], {'role': 'system', 'content': 'Follow instructions.'})
        self.assertEqual(payload['messages'][1]['content'][1]['image_url']['url'], 'data:image/png;base64,aGVsbG8=')
        self.assertEqual(payload['messages'][2]['tool_calls'][0]['id'], 'call-1')
        self.assertEqual(payload['messages'][3]['tool_call_id'], 'call-1')
        self.assertEqual(custom, set())

    def test_custom_tool_is_represented_as_a_function(self):
        payload, custom = bridge.to_chat({
            'model': 'model-a', 'input': 'Inspect the project',
            'tools': [{'type': 'custom', 'name': 'exec_command', 'description': 'Run a command'}],
        })
        self.assertEqual(payload['tools'][0]['function']['name'], 'exec_command')
        self.assertEqual(custom, {'exec_command'})


class FakeChatProvider(BaseHTTPRequestHandler):
    requests = []

    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.__class__.requests.append((self.path, self.headers.get('Authorization'), body))
        events = [
            {'choices': [{'delta': {'content': 'Ready.'}, 'index': 0}]},
            {'choices': [{'delta': {'tool_calls': [{'index': 0, 'id': 'call-work', 'type': 'function', 'function': {'name': 'exec_command', 'arguments': '{"input":"pwd"}'}}]}, 'index': 0}]},
            {'choices': [{'delta': {}, 'finish_reason': 'tool_calls', 'index': 0}], 'usage': {'prompt_tokens': 12, 'completion_tokens': 5, 'total_tokens': 17}},
        ]
        data = ''.join('data: ' + json.dumps(event) + '\n\n' for event in events) + 'data: [DONE]\n\n'
        encoded = data.encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Content-Length', str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


class BridgeStreamTest(unittest.TestCase):
    def setUp(self):
        FakeChatProvider.requests = []
        self.provider = ThreadingHTTPServer(('127.0.0.1', 0), FakeChatProvider)
        threading.Thread(target=self.provider.serve_forever, daemon=True).start()
        profile = {'base_url': f'http://127.0.0.1:{self.provider.server_port}/v1', 'token': 'upstream-secret'}
        self.secret = 'local-session-secret'
        self.bridge_server = bridge.make_server(profile, self.secret)
        threading.Thread(target=self.bridge_server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.bridge_server.shutdown()
        self.bridge_server.server_close()
        self.provider.shutdown()
        self.provider.server_close()

    def test_authenticates_locally_and_streams_response_events(self):
        body = {
            'model': 'model-a', 'instructions': 'Use available tools.',
            'input': [{'role': 'user', 'content': 'Check the path.'}],
            'tools': [{'type': 'custom', 'name': 'exec_command', 'description': 'Run a shell command'}],
        }
        request = Request(f'http://127.0.0.1:{self.bridge_server.server_port}/v1/responses', data=json.dumps(body).encode(), headers={'Authorization': 'Bearer ' + self.secret, 'Content-Type': 'application/json'})
        with urlopen(request, timeout=5) as response:
            events = []
            for chunk in response.read().decode().split('\n\n'):
                if chunk.startswith('event: '):
                    kind, data = chunk.split('\n', 1)
                    events.append((kind[7:], json.loads(data[6:])))
        self.assertEqual(FakeChatProvider.requests[0][0], '/v1/chat/completions')
        self.assertEqual(FakeChatProvider.requests[0][1], 'Bearer upstream-secret')
        self.assertEqual(FakeChatProvider.requests[0][2]['model'], 'model-a')
        self.assertIn(('response.output_text.delta', ANY), events)
        tool = next(item for kind, item in events if kind == 'response.output_item.done' and item['item']['type'] == 'custom_tool_call')
        self.assertEqual(tool['item']['input'], 'pwd')
        self.assertEqual(events[-1][0], 'response.completed')
        self.assertEqual(events[-1][1]['response']['usage']['input_tokens'], 12)

    def test_rejects_requests_without_local_session_token(self):
        request = Request(f'http://127.0.0.1:{self.bridge_server.server_port}/v1/responses', data=b'{}', headers={'Authorization': 'Bearer wrong'})
        with self.assertRaises(HTTPError) as error:
            urlopen(request, timeout=5)
        self.assertEqual(error.exception.code, 401)


if __name__ == '__main__':
    unittest.main()
