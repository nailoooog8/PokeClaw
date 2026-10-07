# Minimal MCP Streamable HTTP test server for PokeClaw e2e test.
# Usage: python scripts/mcp_test_server.py [port]   (default 8765)
# Client: McpStreamableHttpClient (plain application/json responses OK)

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

START_TIME = time.time()

TOOLS = [
    {
        "name": "echo",
        "description": "Echo back the given message (PokeClaw e2e test tool)",
        "inputSchema": {
            "type": "object",
            "properties": {
                "message": {"type": "string", "description": "Text to echo back"}
            },
            "required": ["message"],
        },
    },
    {
        "name": "server_uptime",
        "description": "Return test server uptime in seconds (no arguments)",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("[mcp-test] %s" % (fmt % args), flush=True)

    def _send_json(self, obj, status=200, session=None):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        if session:
            self.send_header("Mcp-Session-Id", session)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.send_response(405)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        try:
            req = json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            self._send_json({"jsonrpc": "2.0", "id": None,
                             "error": {"code": -32700, "message": "parse error"}})
            return

        method = req.get("method", "")
        req_id = req.get("id")
        is_notification = req_id is None

        if method == "initialize":
            result = {
                "protocolVersion": req.get("params", {}).get("protocolVersion", "2025-03-26"),
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "pokeclaw-e2e-test", "version": "0.1.0"},
            }
            self._send_json({"jsonrpc": "2.0", "id": req_id, "result": result},
                            session="test-session-001")
        elif method == "notifications/initialized":
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif method == "tools/list":
            result = {"tools": TOOLS}
            self._send_json({"jsonrpc": "2.0", "id": req_id, "result": result})
        elif method == "tools/call":
            params = req.get("params", {})
            name = params.get("name")
            args = params.get("arguments", {}) or {}
            if name == "echo":
                text = "echo: %s" % args.get("message", "")
            elif name == "server_uptime":
                text = "uptime: %.1fs" % (time.time() - START_TIME)
            else:
                self._send_json({"jsonrpc": "2.0", "id": req_id, "result": {
                    "content": [{"type": "text", "text": "unknown tool"}],
                    "isError": True}})
                return
            self._send_json({"jsonrpc": "2.0", "id": req_id, "result": {
                "content": [{"type": "text", "text": text}], "isError": False}})
        else:
            if is_notification:
                self.send_response(202)
                self.send_header("Content-Length", "0")
                self.end_headers()
            else:
                self._send_json({"jsonrpc": "2.0", "id": req_id,
                                 "error": {"code": -32601, "message": "method not found: %s" % method}})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print("[mcp-test] listening on 127.0.0.1:%d/mcp" % port, flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
