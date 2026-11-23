#!/usr/bin/env python3
"""Exercise the real gateway on a disposable branch and warehouse stack."""

import http.client
import json
import math
import os
import statistics
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from urllib.parse import urlsplit
from uuid import uuid4


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def command(port, method, path, payload=None, key=None):
    headers = {"Accept": "application/json"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    if key is not None:
        headers["Idempotency-Key"] = key

    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=16)
    try:
        start = time.monotonic()
        connection.request(method, "/api/v1" + path, json.dumps(payload) if payload is not None else None, headers)
        response = connection.getresponse()
        body = json.load(response)
        elapsed_ms = (time.monotonic() - start) * 1000
        return response.status, body, elapsed_ms
    finally:
        connection.close()


def event(response):
    fields = {}
    while True:
        raw = response.readline(4096)
        require(bool(raw), "SSE connection closed before delivering its next event")
        line = raw.decode("utf-8").rstrip("\r\n")
        if not line:
            if fields:
                return fields
            continue
        if line.startswith(":"):
            continue
        key, _, value = line.partition(":")
        fields[key] = value.lstrip()


def connect_sse(stack, port):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=12)
    stack.callback(connection.close)
    connection.request("GET", "/api/v1/events", headers={"Accept": "text/event-stream"})
    response = connection.getresponse()
    stack.callback(response.close)
    require(response.status == 200, "SSE connection failed")
    require("text/event-stream" in response.getheader("Content-Type", ""), "Expected an SSE stream")
    require(event(response) == {"event": "connected", "data": "connected"}, "SSE handshake is missing")
    return response


def stock(port):
    status, products, _ = command(port, "GET", "/products")
    require(status == 200, "Could not read authoritative inventory")
    return next(product for product in products if product["sku"] == "ITEM-007")


def main():
    require(os.getenv("THOGAMURA_VERIFY_DISPOSABLE") == "1", "Select a fresh disposable stack before running the load check")
    origin = urlsplit(os.getenv("THOGAMURA_GATEWAY_URL", "http://127.0.0.1:8080"))
    require(origin.scheme == "http" and origin.hostname in {"127.0.0.1", "localhost"} and
            not any((origin.path not in ("", "/"), origin.username, origin.query, origin.fragment)),
            "Use the loopback HTTP endpoint on the disposable branch")
    port = origin.port or 80
    status, health, _ = command(port, "GET", "/network-status")
    require(status == 200 and health["tlsCommand"]["status"] == "OPERATIONAL", "Validated warehouse TLS is required")
    require(health["nioFeed"]["connected"], "The gateway NIO feed is disconnected")

    with ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(lambda _: command(port, "GET", "/ping"), range(48)))
        require(all(code == 200 and body.get("status") == "PONG" for code, body, _ in results), "Some command probes failed")
        latencies = sorted(elapsed for _, _, elapsed in results)
        p95 = latencies[math.ceil(0.95 * len(latencies)) - 1]
        print(f"TLS command probe: 48/48 PONG at concurrency 12, p50={statistics.median(latencies):.1f} ms, p95={p95:.1f} ms")

        baseline = stock(port)
        require(baseline["onHand"] == 10 and baseline["available"] >= 8, "Start from a fresh ITEM-007 fixture")
        quantity = 8

        with ExitStack() as subscriptions:
            clients = [connect_sse(subscriptions, port) for _ in range(12)]

            def reserve(key):
                return key, command(port, "POST", "/requests", {"sku": "ITEM-007", "quantity": quantity}, key)

            attempts = list(pool.map(reserve, (str(uuid4()), str(uuid4()))))
            require(sorted(result[0] for _, result in attempts) == [201, 409], "Expected one winner and one shortage")
            winner, receipt = next((key, result[1]) for key, result in attempts if result[0] == 201)
            for hint in pool.map(event, clients):
                require(hint.get("event") == "stock-changed" and hint.get("data", "").isascii() and
                        hint["data"].isdigit() and len(hint) == 2, "Expected only the numeric change hint")
            print("SSE fanout: 12/12 independent HTTP streams received version-only change hints")

        require(reserve(winner)[1][:2] == (201, receipt), "Winning receipt did not replay")
        require(stock(port)["reserved"] == baseline["reserved"] + quantity, "A replay changed reserved stock")
        require(command(port, "POST", f"/requests/{winner}/pick")[0] == 200, "Picking failed")

        def dispatch(_):
            return command(port, "POST", f"/requests/{winner}/dispatch")

        responses = list(pool.map(dispatch, range(12)))
        require(all(code == 200 and body == responses[0][1] for code, body, _ in responses), "Dispatch replay failed")
        updated = stock(port)
        require(updated["onHand"] == baseline["onHand"] - quantity and
                updated["reserved"] == baseline["reserved"], "Stock changed more than once during dispatch")
        status, movements, _ = command(port, "GET", "/movements")
        matching = [movement for movement in movements if movement["stockRequestId"] == winner]
        require(status == 200 and len(matching) == 1 and matching[0]["quantityDelta"] == -quantity,
                "Expected one dispatch movement for the winning request")
        print("Concurrent stock: one of two 8-unit reservations won, and 12 dispatch attempts produced one movement")


if __name__ == "__main__":
    main()
