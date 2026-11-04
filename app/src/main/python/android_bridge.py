import asyncio
import logging

import threading
from collections import deque
from proxy import tg_ws_proxy as core
from proxy.config import proxy_config
import proxy.config as config

_lock = threading.RLock()
_stop = threading.Event()
_thread = None
_state = 'stopped'
_error = ''
_lines = deque(maxlen=250)

class LogSink(logging.Handler):
    def emit(self, record):
        message = record.getMessage()
        if 'secret' in message.lower() or 'tg://' in message:
            return
        with _lock:
            _lines.append(self.format(record))

_handler = LogSink()
_handler.setFormatter(logging.Formatter('%(asctime)s  %(message)s', '%H:%M:%S'))
logging.getLogger().addHandler(_handler)

def configure_routes(mode):
    routes = {
        'dc4': {4: '149.154.167.220'},
        'original': {2: '149.154.167.220', 4: '149.154.167.220'},
        'direct': {},
    }
    if mode not in routes:
        raise ValueError('Unknown routing mode')
    proxy_config.dc_redirects = routes[mode].copy()


def start(port, secret, verbose, reconnect=True, routing_mode='dc4'):
    global _thread, _state, _error
    with _lock:
        if _thread and _thread.is_alive():
            return
        configure_routes(str(routing_mode))
        _stop.clear()
        _error = ''
        _state = 'starting'
        proxy_config.host = '127.0.0.1'
        proxy_config.port = int(port)
        proxy_config.secret = str(secret)
        logging.getLogger().setLevel(logging.DEBUG if verbose else logging.INFO)
        _thread = threading.Thread(target=_worker, args=(bool(reconnect),), daemon=True)
        _thread.start()

def _worker(reconnect):
    global _state, _error
    while not _stop.is_set():
        try:
            asyncio.run(_serve())
        except Exception as exc:
            with _lock:
                was_running = _state == 'running'
                _error = str(exc)
                _lines.append('Ошибка: ' + _error)
            if reconnect and was_running and not _stop.wait(2):
                with _lock:
                    _state = 'starting'
                    _error = ''
                continue
        finally:
            config._refresh_stop.set()
        break
    with _lock:
        _state = 'error' if _error and not _stop.is_set() else 'stopped'

async def _serve():
    global _state
    event = asyncio.Event()
    task = asyncio.create_task(core._run(event))
    try:
        while not task.done():
            if _stop.is_set():
                event.set()
                break
            server = getattr(core, '_server_instance', None)
            if server is not None and server.is_serving():
                with _lock:
                    _state = 'running'
            await asyncio.sleep(0.1)
        await task
    finally:
        if not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

def stop():
    global _state
    with _lock:
        if _thread and _thread.is_alive():
            _state = 'stopping'
        _stop.set()

def status():
    with _lock:
        return _state

def error():
    with _lock:
        return _error

def logs():
    with _lock:
        return '\n'.join(_lines)

def clear_logs():
    with _lock:
        _lines.clear()
