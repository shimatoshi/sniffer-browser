"""標準ライブラリだけで書いた最小のWebSocketクライアント（CDP接続用）。

以前は websocket-client(pip) に依存していたが、Termuxのpython更新でパッケージが
消えると gobie が黙って壊れるため自前化した。websocket-client と同じ名前の
API（create_connection / send / recv / close と例外クラス）だけを提供するので、
`import miniws as websocket` で差し替えられる。

対応範囲: ws://（CDPはlocalhostのadb forwardなのでTLS不要）、テキスト/バイナリ受信、
断片化フレームの結合、ping→pong応答、close。Originヘッダは送らない
（Chrome 111+ はOrigin付きのCDP接続を拒否するため）。
"""
import base64
import os
import socket
import struct
from urllib.parse import urlparse


class WebSocketException(Exception):
    pass


class WebSocketConnectionClosedException(WebSocketException):
    pass


class WebSocketTimeoutException(WebSocketException, TimeoutError):
    pass


class WebSocket:
    def __init__(self, sock, buf=b""):
        self.sock = sock
        self._buf = buf

    def _read(self, n):
        while len(self._buf) < n:
            try:
                chunk = self.sock.recv(max(65536, n - len(self._buf)))
            except socket.timeout as e:
                raise WebSocketTimeoutException(str(e)) from e
            if not chunk:
                raise WebSocketConnectionClosedException("connection closed")
            self._buf += chunk
        data, self._buf = self._buf[:n], self._buf[n:]
        return data

    def _send_frame(self, opcode, payload):
        n = len(payload)
        head = bytes([0x80 | opcode])
        if n < 126:
            head += bytes([0x80 | n])
        elif n < 65536:
            head += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            head += bytes([0x80 | 127]) + struct.pack(">Q", n)
        mask = os.urandom(4)
        # クライアント→サーバはマスク必須。整数XORでまとめて掛ける（バイト単位ループより速い）
        if n:
            key = (mask * (n // 4 + 1))[:n]
            payload = (int.from_bytes(payload, "big") ^ int.from_bytes(key, "big")).to_bytes(n, "big")
        try:
            self.sock.sendall(head + mask + payload)
        except socket.timeout as e:
            raise WebSocketTimeoutException(str(e)) from e
        except OSError as e:
            raise WebSocketConnectionClosedException(str(e)) from e

    def send(self, data):
        if isinstance(data, str):
            self._send_frame(0x1, data.encode("utf-8"))
        else:
            self._send_frame(0x2, bytes(data))

    def _recv_frame(self):
        b0, b1 = self._read(2)
        fin, opcode = b0 & 0x80, b0 & 0x0F
        n = b1 & 0x7F
        if n == 126:
            n = struct.unpack(">H", self._read(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._read(8))[0]
        mask = self._read(4) if b1 & 0x80 else None
        payload = self._read(n)
        if mask:
            key = (mask * (n // 4 + 1))[:n]
            payload = (int.from_bytes(payload, "big") ^ int.from_bytes(key, "big")).to_bytes(n, "big")
        return fin, opcode, payload

    def recv(self):
        """次のテキスト(str)/バイナリ(bytes)メッセージを返す。制御フレームは内部で処理する"""
        parts, first_op = [], None
        while True:
            fin, opcode, payload = self._recv_frame()
            if opcode == 0x8:  # close
                try:
                    self._send_frame(0x8, payload[:2])
                except WebSocketException:
                    pass
                raise WebSocketConnectionClosedException("closed by server")
            if opcode == 0x9:  # ping
                self._send_frame(0xA, payload)
                continue
            if opcode == 0xA:  # pong
                continue
            if opcode != 0x0:
                first_op = opcode
            parts.append(payload)
            if fin:
                data = b"".join(parts)
                return data.decode("utf-8") if first_op == 0x1 else data

    def settimeout(self, timeout):
        self.sock.settimeout(timeout)

    def close(self):
        try:
            self._send_frame(0x8, struct.pack(">H", 1000))
        except WebSocketException:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


def create_connection(url, timeout=None, **_ignored):
    """websocket-client互換の入口。max_size/suppress_origin等の追加引数は受け取って無視する"""
    u = urlparse(url)
    if u.scheme != "ws":
        raise WebSocketException(f"unsupported scheme: {u.scheme}")
    host, port = u.hostname, u.port or 80
    path = (u.path or "/") + (f"?{u.query}" if u.query else "")
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
    except socket.timeout as e:
        raise WebSocketTimeoutException(str(e)) from e
    key = base64.b64encode(os.urandom(16)).decode()
    req = (f"GET {path} HTTP/1.1\r\n"
           f"Host: {host}:{port}\r\n"
           "Upgrade: websocket\r\n"
           "Connection: Upgrade\r\n"
           f"Sec-WebSocket-Key: {key}\r\n"
           "Sec-WebSocket-Version: 13\r\n\r\n")
    sock.sendall(req.encode())
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = sock.recv(4096)
        if not chunk:
            sock.close()
            raise WebSocketConnectionClosedException("closed during handshake")
        resp += chunk
    head, rest = resp.split(b"\r\n\r\n", 1)
    status = head.split(b"\r\n", 1)[0]
    if b" 101 " not in status + b" ":
        sock.close()
        raise WebSocketException(f"handshake failed: {status.decode(errors='replace')}")
    return WebSocket(sock, rest)
