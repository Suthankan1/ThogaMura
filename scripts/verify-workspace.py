#!/usr/bin/env python3
"""Exercise the protected workflow against a fresh disposable TLS-backed gateway."""

import http.client
import json
import os
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlencode, urlsplit
from uuid import uuid4


def check(condition, description):
    if not condition:
        raise RuntimeError(description)


class Client:
    def __init__(self, port):
        self.port = port
        self.cookie = ""
        self.csrf = ""

    def request(self, method, path, expected, data=None, key=None, csrf=True, version="v2"):
        headers = {"Accept": "application/json"}
        if self.cookie:
            headers["Cookie"] = self.cookie
        if csrf and self.csrf:
            headers["X-Workspace-CSRF"] = self.csrf
        if key:
            headers["Idempotency-Key"] = key
        if data is not None:
            headers["Content-Type"] = "application/json"
        if path == "/session":
            headers["X-Workspace-Client"] = "browser"
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=16)
        try:
            connection.request(method, f"/api/{version}{path}", json.dumps(data) if data is not None else None, headers)
            response = connection.getresponse()
            set_cookie = response.getheader("Set-Cookie")
            if set_cookie and method == "POST" and path == "/session":
                check("HttpOnly" in set_cookie and "SameSite=Strict" in set_cookie, "Session cookie attributes missing")
                self.cookie = set_cookie.split(";", 1)[0]
            body = json.load(response) if response.status != 204 else None
            check(response.status in (expected if isinstance(expected, tuple) else (expected,)),
                  f"{method} {path}: expected {expected}, received {response.status} ({(body or {}).get('code')})")
            return response.status, body
        finally:
            connection.close()

    def login(self, name, password, role, branch):
        _, session = self.request("POST", "/session", 200, {"name": name, "password": password})
        check((session["name"], session["role"], session["branch"]) == (name, role, branch), "Account scope mismatch")
        self.csrf = session["csrf"]

    def stock(self, sku):
        _, page = self.request("GET", "/products?" + urlencode({"search": sku}), 200)
        return next(row for row in page["items"] if row["sku"] == sku)

    def pages(self, path, key):
        cursor = ""
        rows = []
        while True:
            sep = "&" if "?" in path else "?"
            _, page = self.request("GET", path + sep + urlencode({"limit": 1, "cursor": cursor}), 200)
            check(len(page["items"]) <= 1, "Page limit ignored")
            rows.extend(page["items"])
            next_cursor = page["nextCursor"]
            if next_cursor is None:
                break
            check(next_cursor != cursor and len(rows) <= 100, "Cursor failed to advance")
            cursor = next_cursor
        check(len({row[key] for row in rows}) == len(rows), "Duplicate row seen during traversal")
        return rows


def main():
    check(os.getenv("THOGAMURA_VERIFY_DISPOSABLE") == "1", "Use a fresh disposable protected stack")
    target = urlsplit(os.getenv("THOGAMURA_GATEWAY_URL", "http://127.0.0.1:8080"))
    check(target.scheme == "http" and target.hostname in {"127.0.0.1", "localhost"} and
          target.path in {"", "/"} and not any((target.username, target.query, target.fragment)), "Use a loopback gateway")
    password = os.environ["THOGAMURA_VERIFY_WORKSPACE_PASSWORD"]
    port = target.port or 80
    guest, north, south, picker, supervisor = (Client(port) for _ in range(5))
    guest.request("GET", "/products", 401)
    guest.request("GET", "/products", 403, version="v1")
    guest.request("POST", "/session", 401, {"name": "north", "password": password + "-invalid"})
    for client, name, role, branch in [
        (north, "north", "OPERATOR", "north"),
        (south, "south", "OPERATOR", "south"),
        (picker, "picker", "PICKER", "warehouse"),
        (supervisor, "supervisor", "SUPERVISOR", "warehouse"),
    ]:
        client.login(name, password, role, branch)
    sku = "ITEM-007"
    stock = north.stock(sku)
    check(stock["onHand"] == 10 and stock["reserved"] == 2, "Start from a fresh fixture")
    check(len(north.pages("/products", "sku")) >= 6, "Expected inventory beyond its first page")
    north.request("GET", "/products?cursor=invalid", 400)
    north.request("GET", "/products?limit=25", 400)

    reserve = {"sku": sku, "quantity": 2}
    request_id = str(uuid4())
    north.request("POST", "/requests", 403, reserve, request_id, csrf=False)
    _, first = north.request("POST", "/requests", 201, reserve, request_id)
    check(north.request("POST", "/requests", 201, reserve, request_id)[1] == first, "Reservation replay differed")
    south.request("POST", "/requests", 409, reserve, request_id)
    path = "/requests/" + request_id
    _, found = north.request("GET", path, 200)
    check(found["branchId"] == "north" and "T" in found["createdAt"], "Direct lookup result is malformed")
    south.request("GET", path, 404)
    north.request("GET", "/requests/a0000001-0000-4000-8000-000000000001", 404)
    south.request("POST", path + "/cancel", 404, key=str(uuid4()))
    north.request("POST", path + "/pick", 403, key=str(uuid4()))
    picker.request("GET", path, 200)
    cancel_key = str(uuid4())
    _, cancelled = north.request("POST", path + "/cancel", 200, key=cancel_key)
    check(cancelled["status"] == "CANCELLED", "Cancellation did not change state")
    check(north.request("POST", path + "/cancel", 200, key=cancel_key)[1] == cancelled, "Cancellation receipt differed")
    check(north.stock(sku) == stock, "Cancellation did not restore available balance exactly")
    picker.request("POST", path + "/pick", 409, key=str(uuid4()))
    print("Access and cancellation: signed session, CSRF, cross-branch denial, legacy isolation and cancellation replay passed")

    receipt = {"sku": sku, "quantity": 5, "note": "Synthetic inbound reference"}
    picker.request("POST", "/receiving", 403, receipt, str(uuid4()))
    north.request("GET", "/movements", 403)
    receiving_key = str(uuid4())
    with ThreadPoolExecutor(max_workers=8) as pool:
        outcomes = list(pool.map(lambda _: supervisor.request("POST", "/receiving", 201, receipt, receiving_key), range(8)))
    check(all(result == outcomes[0] for result in outcomes), "Concurrent receipt replay differed")
    supervisor.request("POST", "/receiving", 409, {**receipt, "quantity": 6}, receiving_key)
    supervisor.request("POST", "/receiving", 400, {**receipt, "quantity": 2147483647}, str(uuid4()))
    after_receipt = north.stock(sku)
    check(after_receipt["onHand"] == stock["onHand"] + 5 and after_receipt["reserved"] == stock["reserved"],
          "Receiving changed stock more than once or modified reservations")

    next_id = str(uuid4())
    south_id = str(uuid4())
    north.request("POST", "/requests", 201, reserve, next_id)
    south.request("POST", "/requests", 201, {**reserve, "quantity": 1}, south_id)
    next_path = "/requests/" + next_id
    with ThreadPoolExecutor(max_workers=2) as pool:
        cancel = pool.submit(north.request, "POST", next_path + "/cancel", (200, 409), key=str(uuid4()))
        pick = pool.submit(picker.request, "POST", next_path + "/pick", (200, 409), key=str(uuid4()))
        outcomes = sorted([cancel.result()[0], pick.result()[0]])
    check(outcomes == [200, 409], "Cancel vs pick did not have exactly one winner")
    south_path = "/requests/" + south_id
    picker.request("POST", south_path + "/pick", 200, key=str(uuid4()))
    dispatch = str(uuid4())
    picker.request("POST", south_path + "/dispatch", 200, key=dispatch)
    picker.request("POST", south_path + "/dispatch", 200, key=dispatch)
    supervisor.request("POST", south_path + "/cancel", 409, key=str(uuid4()))

    scoped = north.pages("/requests", "requestId")
    check({row["requestId"] for row in scoped} == {request_id, next_id}, "Branch page leaked or skipped a request")
    _, page = north.request("GET", "/requests?limit=1", 200)
    south.request("GET", "/requests?" + urlencode({"cursor": page["nextCursor"]}), 400)
    north.request("GET", "/requests?" + urlencode({"cursor": page["nextCursor"], "status": "CANCELLED"}), 400)
    check(len(picker.pages("/requests", "requestId")) == 3, "Warehouse picker did not see both branches")
    ledger = supervisor.pages("/movements", "id")
    check(sorted(row["reason"] for row in ledger) == ["DISPATCH", "RECEIVE"], "Expected one receiving and one dispatch movement")
    receiving = next(row for row in ledger if row["reason"] == "RECEIVE")
    check(receiving["quantityDelta"] == 5 and receiving["recordedBy"] == "supervisor" and
          receiving["receivingCommandId"] == receiving_key, "Receiving movement audit data is incorrect")
    supervisor.request("GET", "/suppliers/availability?sku=ITEM-007", 200)
    north.request("DELETE", "/session", 204)
    north.request("GET", "/session", 401)
    print("Receiving and reads: eight concurrent identical receipts, one movement, cancel/pick race, cursor traversal and logout passed")


if __name__ == "__main__":
    main()
