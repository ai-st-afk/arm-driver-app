#!/usr/bin/env python3
"""Tiny 1C mock for local gateway integration tests."""

from http.server import BaseHTTPRequestHandler, HTTPServer
import argparse
import sys
import xml.etree.ElementTree as ET


class Handler(BaseHTTPRequestHandler):
    expected_token = ""

    def do_POST(self):
        if self.path != "/prtr_driver/events":
            self.send_error(404)
            return

        if self.expected_token:
            token = self.headers.get("X-Auth-Token", "")
            if token != self.expected_token:
                self.send_response(401)
                self.send_header("Content-Type", "application/xml; charset=utf-8")
                self.end_headers()
                self.wfile.write(
                    (
                        '<?xml version="1.0" encoding="UTF-8"?>'
                        '<Результат статус="error" ошибка="неверный X-Auth-Token"/>'
                    ).encode("utf-8")
                )
                return

        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)

        try:
            root = ET.fromstring(body)
        except ET.ParseError as exc:
            self.send_response(400)
            self.send_header("Content-Type", "application/xml; charset=utf-8")
            self.end_headers()
            payload = (
                '<?xml version="1.0" encoding="UTF-8"?>'
                f'<Результат статус="error" ошибка="некорректный XML: {exc}"/>'
            )
            self.wfile.write(payload.encode("utf-8"))
            return

        result = ET.Element("Результат", {"статус": "ok"})
        for event in root.findall("Событие"):
            event_id = event.findtext("Идентификатор", default="")
            attrs = {"ид": event_id, "принято": "true"}
            ET.SubElement(result, "Событие", attrs)

        payload = ET.tostring(result, encoding="utf-8", xml_declaration=True)
        self.send_response(200)
        self.send_header("Content-Type", "application/xml; charset=utf-8")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--addr", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18081)
    parser.add_argument("--token", default="")
    args = parser.parse_args()

    Handler.expected_token = args.token
    server = HTTPServer((args.addr, args.port), Handler)
    print(f"mock 1C listening on http://{args.addr}:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
