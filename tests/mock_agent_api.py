"""Offline API used to prove that the real ARM64 Codex runs tools on Android."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import time

LOG = Path(__file__).resolve().parents[1] / 'build/mock-agent-requests.jsonl'
COMMAND = 'printf ACodexT-working > acodext-validation.txt'

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        body = json.dumps({'data': [{'id': 'gpt-5.4', 'object': 'model'}]}).encode()
        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_POST(self):
        try:
            raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            body = json.loads(raw)
            with LOG.open('a') as log:
                log.write(json.dumps({'path': self.path, 'model': body.get('model'), 'tools': body.get('tools'), 'input': body.get('input'), 'messages': body.get('messages')}) + '\n')
            if self.path.endswith('/chat/completions'):
                done = any(m.get('role') == 'tool' for m in body.get('messages', []))
                if done:
                    events = [{'choices': [{'delta': {'content': 'DONE'}, 'index': 0}]}, {'choices': [{'delta': {}, 'finish_reason': 'stop', 'index': 0}]}]
                else:
                    tools = [t.get('function', {}) for t in body.get('tools', [])]
                    tool = next(t for t in tools if t.get('name') in ['exec_command','shell','shell_command'])
                    name = tool['name']; fields = tool.get('parameters', {}).get('properties', {})
                    if 'input' in fields:
                        args = {'input': COMMAND}
                    elif name == 'shell':
                        args = {'command': ['bash', '-c', COMMAND]}
                    elif 'command' in fields:
                        args = {'command': COMMAND}
                    else:
                        args = {'cmd': COMMAND}
                    events = [{'choices': [{'delta': {'tool_calls': [{'index': 0, 'id': 'call-prove', 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(args)}}]}, 'index': 0}]}, {'choices': [{'delta': {}, 'finish_reason': 'tool_calls', 'index': 0}]}]
                content = ''.join('data: ' + json.dumps(e) + '\n\n' for e in events) + 'data: [DONE]\n\n'
            elif self.path.endswith('/responses'):
                inputs = body.get('input', [])
                done = any(i.get('type') in ['function_call_output','custom_tool_call_output'] for i in inputs) if isinstance(inputs,list) else False
                if done:
                    item = {'id': 'msg-done', 'type': 'message', 'role': 'assistant', 'status': 'completed', 'content': [{'type': 'output_text', 'text': 'DONE', 'annotations': []}]}
                else:
                    tool = next(t for t in body.get('tools', []) if t.get('name') in ['exec_command','shell','shell_command'])
                    name = tool['name']; fields = tool.get('parameters',{}).get('properties',{})
                    args = {'command': ['bash','-c',COMMAND]} if name == 'shell' else {'command': COMMAND} if 'command' in fields else {'cmd': COMMAND}
                    item = {'id': 'fc-prove', 'type': 'function_call', 'name': name, 'call_id': 'call-prove', 'arguments': json.dumps(args), 'status': 'completed'}
                response = {'id': 'resp-offline', 'object': 'response', 'created_at': int(time.time()), 'model': body.get('model'), 'status': 'completed', 'output': [item], 'usage': {'input_tokens': 12, 'output_tokens': 4, 'total_tokens': 16, 'input_tokens_details': {'cached_tokens': 0}, 'output_tokens_details': {'reasoning_tokens': 0}}}
                events = [dict(type='response.created', response=dict(response,status='in_progress',output=[])), dict(type='response.output_item.added', output_index=0, item=item), dict(type='response.output_item.done', output_index=0, item=item), dict(type='response.completed', response=response)]
                content = ''.join('event: ' + e['type'] + '\ndata: ' + json.dumps(e) + '\n\n' for e in events)
            else:
                self.send_error(404); return
            encoded = content.encode()
            self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.send_header('Content-Length', str(len(encoded))); self.end_headers(); self.wfile.write(encoded)
        except Exception as e:
            with LOG.open('a') as log: log.write(json.dumps({'error':str(e),'path':self.path})+'\n')
            self.send_error(500)

if __name__ == '__main__':
    server = ThreadingHTTPServer(('127.0.0.1',18890), Handler)
    print('Mock API listening on 127.0.0.1:18890',flush=True)
    server.serve_forever()
