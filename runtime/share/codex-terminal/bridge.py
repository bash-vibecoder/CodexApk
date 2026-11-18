"""Local Responses → Chat Completions bridge with streaming text and function tools.

Only runs for a profile explicitly configured for Chat Completions. It binds to
loopback, requires a per-process random bearer token, and never logs credentials.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hmac
import json
import time
import urllib.error
import urllib.request
import uuid

def uid(prefix):
    return prefix + '_' + uuid.uuid4().hex

def text_content(content):
    if isinstance(content, str):
        return content
    chunks = []
    for c in content or []:
        if c.get('type') in ['input_text', 'output_text', 'text']:
            chunks.append({'type': 'text', 'text': c.get('text', '')})
        elif c.get('type') == 'input_image':
            chunks.append({'type': 'image_url', 'image_url': {'url': c['image_url']}})
    if all(c['type'] == 'text' for c in chunks):
        return '\n'.join(c['text'] for c in chunks)
    return chunks

def to_chat(body):
    messages = []
    if body.get('instructions'):
        messages.append({'role': 'system', 'content': body['instructions']})
    inputs = body.get('input', [])
    if isinstance(inputs, str):
        inputs = [{'role': 'user', 'content': inputs}]
    for item in inputs:
        kind = item.get('type', 'message')
        if kind == 'message':
            role = item.get('role', 'user')
            if role == 'developer':
                role = 'system'
            messages.append({'role': role, 'content': text_content(item.get('content', ''))})
        elif kind in ['function_call', 'custom_tool_call']:
            arguments = item.get('arguments')
            if kind == 'custom_tool_call':
                arguments = json.dumps({'input': item.get('input', '')})
            call = {'id': item['call_id'], 'type': 'function', 'function': {'name': item['name'], 'arguments': arguments or '{}'}}
            if messages and messages[-1]['role'] == 'assistant' and 'tool_calls' in messages[-1]:
                messages[-1]['tool_calls'].append(call)
            else:
                messages.append({'role': 'assistant', 'content': None, 'tool_calls': [call]})
        elif kind in ['function_call_output', 'custom_tool_call_output']:
            output = item.get('output', '')
            if not isinstance(output, str):
                output = json.dumps(output)
            messages.append({'role': 'tool', 'tool_call_id': item['call_id'], 'content': output})
    tools, custom = [], set()
    for tool in body.get('tools', []):
        if tool.get('type') == 'function':
            tools.append({'type': 'function', 'function': {k: tool[k] for k in ['name', 'description', 'parameters'] if k in tool}})
        elif tool.get('type') == 'custom':
            custom.add(tool['name'])
            tools.append({'type': 'function', 'function': {'name': tool['name'], 'description': tool.get('description', '') + '\nPut the complete tool input in the input field.', 'parameters': {'type': 'object', 'properties': {'input': {'type': 'string'}}, 'required': ['input'], 'additionalProperties': False}}})
    result = {'model': body['model'], 'messages': messages, 'stream': True, 'stream_options': {'include_usage': True}}
    if tools:
        result['tools'] = tools
        result['tool_choice'] = 'auto'
        result['parallel_tool_calls'] = body.get('parallel_tool_calls', False)
    if body.get('max_output_tokens'):
        result['max_tokens'] = body['max_output_tokens']
    return result, custom

def make_server(profile, secret):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *_):
            pass

        def error(self, status, message):
            data = json.dumps({'error': {'message': message, 'type': 'provider_error'}}).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + secret):
                self.error(401, 'Invalid local bridge credential')
                return
            if self.path not in ['/v1/responses', '/responses']:
                self.error(404, 'This compatibility bridge supports /responses only')
                return
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 32 * 1024 * 1024:
                    self.error(413, 'Request is too large')
                    return
                body = json.loads(self.rfile.read(length))
                payload, custom = to_chat(body)
                request = urllib.request.Request(profile['base_url'].rstrip('/') + '/chat/completions', data=json.dumps(payload).encode(), headers={'Authorization': 'Bearer ' + profile['token'], 'Content-Type': 'application/json', 'Accept': 'text/event-stream', 'User-Agent': 'CodexTerminal/1.0'})
                # Never forward the credential to a redirect destination.
                class NoRedirect(urllib.request.HTTPRedirectHandler):
                    def redirect_request(self, *_args, **_kwargs):
                        return None
                upstream = urllib.request.build_opener(NoRedirect()).open(request, timeout=180)
            except urllib.error.HTTPError as e:
                self.error(e.code, f'Chat Completions provider returned HTTP {e.code}. Check URL, model, token, and balance.')
                return
            except Exception:
                self.error(502, 'Could not connect to the Chat Completions provider')
                return
            try:
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.send_header('Cache-Control', 'no-cache')
                self.send_header('Connection', 'close')
                self.end_headers()
                self.close_connection = True
                sequence = 0
                def emit(kind, **fields):
                    nonlocal sequence
                    event = {'type': kind, 'sequence_number': sequence, **fields}
                    sequence += 1
                    self.wfile.write(('event: ' + kind + '\ndata: ' + json.dumps(event, ensure_ascii=False) + '\n\n').encode())
                    self.wfile.flush()
                response_id = uid('resp')
                response = {'id': response_id, 'object': 'response', 'created_at': int(time.time()), 'status': 'in_progress', 'model': body['model'], 'output': []}
                emit('response.created', response=response)
                emit('response.in_progress', response=response)
                text = ''
                message = None
                message_index = None
                calls = {}
                output = []
                usage = {}
                def consume(chunk):
                    nonlocal message, text, message_index, usage
                    if chunk.get('error'):
                        raise ValueError('Upstream stream failed')
                    usage = chunk.get('usage') or usage
                    for choice in chunk.get('choices', []):
                        delta = choice.get('delta') or choice.get('message') or {}
                        content = delta.get('content')
                        if content:
                            if message is None:
                                message_index = len(output)
                                message = {'type': 'message', 'id': uid('msg'), 'role': 'assistant', 'status': 'in_progress', 'content': []}
                                output.append(message)
                                emit('response.output_item.added', output_index=message_index, item=dict(message))
                                emit('response.content_part.added', item_id=message['id'], output_index=message_index, content_index=0, part={'type': 'output_text', 'text': '', 'annotations': []})
                            text += content
                            emit('response.output_text.delta', item_id=message['id'], output_index=message_index, content_index=0, delta=content)
                        for call in delta.get('tool_calls', []):
                            index = call.get('index', 0)
                            entry = calls.setdefault(index, {'id': '', 'name': '', 'arguments': ''})
                            if call.get('id'):
                                entry['id'] = call['id']
                            function = call.get('function', {})
                            entry['name'] += function.get('name', '')
                            entry['arguments'] += function.get('arguments', '')
                with upstream:
                    if 'text/event-stream' in upstream.headers.get('Content-Type', ''):
                        event_data = []
                        for raw in upstream:
                            line = raw.decode('utf-8').rstrip('\r\n')
                            if line.startswith('data:'):
                                event_data.append(line[5:].lstrip())
                            elif not line and event_data:
                                data = '\n'.join(event_data)
                                event_data = []
                                if data == '[DONE]':
                                    break
                                consume(json.loads(data))
                    else:
                        consume(json.load(upstream))
                if message:
                    part = {'type': 'output_text', 'text': text, 'annotations': []}
                    message.update(status='completed', content=[part])
                    emit('response.output_text.done', item_id=message['id'], output_index=message_index, content_index=0, text=text)
                    emit('response.content_part.done', item_id=message['id'], output_index=message_index, content_index=0, part=part)
                    emit('response.output_item.done', output_index=message_index, item=message)
                for entry in calls.values():
                    index = len(output)
                    call_id = entry['id'] or uid('call')
                    is_custom = entry['name'] in custom
                    item = {'id': uid('fc'), 'type': 'custom_tool_call' if is_custom else 'function_call', 'call_id': call_id, 'name': entry['name'], 'status': 'completed'}
                    if is_custom:
                        try:
                            value = json.loads(entry['arguments'])['input']
                        except (KeyError, ValueError):
                            raise ValueError('Provider returned invalid custom tool input')
                        item['input'] = value
                    else:
                        item['arguments'] = entry['arguments']
                    empty = dict(item)
                    empty['input' if is_custom else 'arguments'] = ''
                    empty['status'] = 'in_progress'
                    emit('response.output_item.added', output_index=index, item=empty)
                    if not is_custom:
                        emit('response.function_call_arguments.delta', item_id=item['id'], output_index=index, delta=item['arguments'])
                        emit('response.function_call_arguments.done', item_id=item['id'], output_index=index, arguments=item['arguments'])
                    emit('response.output_item.done', output_index=index, item=item)
                    output.append(item)
                response.update(status='completed', output=output, usage={'input_tokens': usage.get('prompt_tokens', 0), 'output_tokens': usage.get('completion_tokens', 0), 'total_tokens': usage.get('total_tokens', 0), 'input_tokens_details': {'cached_tokens': 0}, 'output_tokens_details': {'reasoning_tokens': 0}})
                emit('response.completed', response=response)
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception:
                try:
                    emit('response.failed', response={'id': response_id, 'object': 'response', 'status': 'failed', 'error': {'code': 'provider_error', 'message': 'The provider stream ended unexpectedly or returned invalid data.'}, 'output': []})
                except (BrokenPipeError, ConnectionResetError):
                    pass
    return ThreadingHTTPServer(('127.0.0.1', 0), Handler)
