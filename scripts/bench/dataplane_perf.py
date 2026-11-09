#!/usr/bin/env python3
"""
Dataplane streaming throughput through Cresco agents (Python client, pycrescolib 1.4+).

A SENDING client and a RECEIVING client stream over the wsapi dataplane:

    sender --wss--> wsapi /api/dataplane --> agent dataplane (broker) [--> broker bridge (TLS) --> other agent]
           --> wsapi --wss--> receiver

With --rx-host/--rx-port on another agent's wsapi, every byte also crosses the broker bridge between the two agents:
the cross-node path, whose TLS is the agent's (the JDK's, or the FIPS modules in security_profile=production).

Measures: send rate, receive throughput (msg/s and MB/s), one-way latency (one process, one clock), loss.

Credentials and trust (every wsapi verifies its certificate): --fingerprint (ws.fingerprint) or --ca-file
(ws.crt.pem); --key (service key), --user/--password, --token, or --cert/--cert-key (a client certificate: the only
credential of the production profile). The receiver uses --rx-fingerprint when given, else --fingerprint.

Usage:
  dataplane_perf.py --host H --port 8282 --fingerprint FP --cert admin.pem --cert-key admin.key
                    [--rx-host H2 --rx-port P2 --rx-fingerprint FP2] [--messages N] [--size BYTES] [--mode binary|text]
Prints a line "BENCH {json}" with the results.
"""
import argparse
import json
import struct
import sys
import threading
import time
import uuid

from pycrescolib.clientlib import clientlib

HDR = struct.Struct("!Qd")  # seq (uint64), send_time (double)


def build_stream_config(ident_id: str) -> str:
    return json.dumps({
        "ident_key": "stream_name",
        "ident_id": ident_id,
        "io_type_key": "type",
        "output_id": "output",
        "input_id": "output",
    })


class Receiver:
    """Counts messages/bytes and records per-message one-way latency."""
    def __init__(self):
        self.count = 0
        self.bytes = 0
        self.first_ts = None
        self.last_ts = None
        self.latencies = []
        self._lock = threading.Lock()

    def _record(self, payload: bytes):
        now = time.perf_counter()
        with self._lock:
            if self.first_ts is None:
                self.first_ts = now
            self.last_ts = now
            self.count += 1
            self.bytes += len(payload)
            if len(payload) >= HDR.size:
                try:
                    _seq, send_t = HDR.unpack(payload[:HDR.size])
                    self.latencies.append(now - send_t)
                except struct.error:
                    pass

    def text_cb(self, message):
        # text frames arrive as str; our text payload is latin-1 encoded bytes
        self._record(message.encode("latin-1") if isinstance(message, str) else message)

    def binary_cb(self, data):
        self._record(data)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=8282)
    ap.add_argument("--key", default=None, help="service key")
    ap.add_argument("--user", default=None)
    ap.add_argument("--password", default=None)
    ap.add_argument("--token", default=None)
    ap.add_argument("--cert", default=None, help="client certificate (PEM)")
    ap.add_argument("--cert-key", default=None, help="its private key (PEM)")
    ap.add_argument("--fingerprint", default=None)
    ap.add_argument("--ca-file", default=None)
    ap.add_argument("--rx-host", default=None)
    ap.add_argument("--rx-port", type=int, default=None)
    ap.add_argument("--rx-fingerprint", default=None)
    ap.add_argument("--stream", default=None, help="stream name (a user's must be u:<user>:...)")
    ap.add_argument("--messages", type=int, default=2000)
    ap.add_argument("--size", type=int, default=1024, help="payload bytes per message")
    ap.add_argument("--mode", choices=["text", "binary"], default="binary")
    ap.add_argument("--settle", type=float, default=10.0, help="max seconds to wait for delivery")
    args = ap.parse_args()

    ident = args.stream or ("perf-" + uuid.uuid4().hex[:8])
    cfg = build_stream_config(ident)
    size = max(args.size, HDR.size)

    print(f"# dataplane perf: ident={ident} mode={args.mode} messages={args.messages} size={size}B")

    # Two independent client connections: a receiver and a sender.
    def client(host, port, fp):
        return clientlib(host, port, args.key, fingerprint=fp, ca_file=args.ca_file, user=args.user, password=args.password,
                         token=args.token, client_cert=args.cert, client_key=args.cert_key)

    rx_client = client(args.rx_host or args.host, args.rx_port or args.port, args.rx_fingerprint or args.fingerprint)
    tx_client = client(args.host, args.port, args.fingerprint)
    if not (rx_client.connect() and tx_client.connect()):
        print("ERROR: control-socket connect failed")
        return 2

    rx = Receiver()
    rx_dp = rx_client.get_dataplane(cfg, rx.text_cb, rx.binary_cb)
    if not rx_dp.connect():
        print("ERROR: receiver dataplane failed to activate")
        return 2
    tx_dp = tx_client.get_dataplane(cfg)  # sender: no callback (ignores its own echo)
    if not tx_dp.connect():
        print("ERROR: sender dataplane failed to activate")
        return 2
    time.sleep(1.0)  # let listeners register on the broker

    pad = size - HDR.size
    filler = b"\x00" * pad

    t0 = time.perf_counter()
    for seq in range(args.messages):
        payload = HDR.pack(seq, time.perf_counter()) + filler
        if args.mode == "binary":
            tx_dp.send_binary(payload)
        else:
            tx_dp.send(payload.decode("latin-1"))
    send_elapsed = time.perf_counter() - t0

    # wait for delivery to settle
    deadline = time.perf_counter() + args.settle
    while rx.count < args.messages and time.perf_counter() < deadline:
        time.sleep(0.05)

    recv_elapsed = (rx.last_ts - rx.first_ts) if (rx.first_ts and rx.last_ts and rx.count > 1) else send_elapsed
    total_mb = (rx.bytes / 1_048_576) if rx.bytes else 0.0
    lat = sorted(rx.latencies)

    def pct(p):
        if not lat:
            return float("nan")
        return lat[min(len(lat) - 1, int(p * len(lat)))] * 1000.0

    print("\n=== RESULTS ===")
    print(f"sent:            {args.messages} msgs in {send_elapsed:.3f}s -> "
          f"{args.messages/send_elapsed:,.0f} msg/s send-side")
    print(f"received:        {rx.count}/{args.messages} msgs, {total_mb:.2f} MB")
    if rx.count:
        print(f"recv throughput: {rx.count/recv_elapsed:,.0f} msg/s, {total_mb/recv_elapsed:.2f} MB/s "
              f"(over {recv_elapsed:.3f}s)")
    if lat:
        print(f"latency (1-way): mean={sum(lat)/len(lat)*1000:.2f}ms "
              f"p50={pct(0.50):.2f}ms p99={pct(0.99):.2f}ms max={max(lat)*1000:.2f}ms")
    loss = args.messages - rx.count
    if loss:
        print(f"NOTE: {loss} messages not received within {args.settle}s "
              f"(dataplane is best-effort pub/sub, not guaranteed delivery)")

    res = {"messages": args.messages, "size": size, "mode": args.mode, "received": rx.count, "send_s": round(send_elapsed, 3),
           "recv_mib_s": round(total_mb / recv_elapsed, 1) if rx.count else 0.0, "recv_msg_s": round(rx.count / recv_elapsed) if rx.count else 0,
           "p50_ms": round(pct(0.50), 2), "p99_ms": round(pct(0.99), 2), "cross_node": bool(args.rx_host or args.rx_port)}
    print("BENCH " + json.dumps(res))
    tx_client.close()
    rx_client.close()
    return 0 if rx.count == args.messages else 1


if __name__ == "__main__":
    sys.exit(main())
