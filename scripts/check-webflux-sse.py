#!/usr/bin/env python3
"""Exercise two replies on one HTTP/1.1 connection against the WebFlux demo."""

import argparse
import concurrent.futures
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
import time


def check_pair(port, conversation, timeout, diagnose):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        connection.connect()
        socket = connection.sock
        for number, word in enumerate(("one", "two"), start=1):
            body = json.dumps({"conversationId": conversation, "text": word})
            connection.request("POST", "/chat/stream", body, {"Content-Type": "application/json"})
            response = connection.getresponse()
            if response.status != 200 or not response.chunked or connection.sock is not socket:
                raise RuntimeError(f"Expected chunked HTTP 200 on the same connection: {response.status}")
            received = bytearray()
            try:
                while chunk := response.read1(8192):
                    received.extend(chunk)
            except TimeoutError:
                # read1 returns the done event before it asks for the final HTTP chunk.
                # Capture the JVM while the client still holds the stalled exchange open.
                diagnose(conversation, number, received)
                raise
            events = [json.loads(line[5:]) for line in received.splitlines() if line.startswith(b"data:")]
            text = "".join(event["delta"] for event in events if event["type"] == "text")
            if not events or events[-1]["type"] != "done" or f"message {number} of" not in text:
                raise RuntimeError(f"Unexpected reply {number} of {conversation}: {events}")
    finally:
        connection.close()


def run(args, server, port, directory, jcmd):
    lock = threading.Lock()
    failed = threading.Event()

    def diagnose(conversation, number, received):
        with lock:
            if failed.is_set():
                return
            failed.set()
            (directory / "response.txt").write_bytes(received)
            print(f"Timed out in {conversation}, reply {number}. Diagnostics in {directory}", flush=True)
            for name, command in (("threads", "Thread.print"), ("histogram", "GC.class_histogram")):
                with (directory / f"{name}.txt").open("w") as output:
                    try:
                        subprocess.run([jcmd, str(server.pid), command], stdout=output,
                                       stderr=subprocess.STDOUT, timeout=30, check=True)
                    except (OSError, subprocess.SubprocessError) as error:
                        print(error, file=output)

    def client(worker):
        for pair in range(args.pairs):
            if failed.is_set():
                return
            check_pair(port, f"sse-{worker}-{pair}", args.timeout, diagnose)

    with concurrent.futures.ThreadPoolExecutor(args.clients) as pool:
        list(pool.map(client, range(args.clients)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path, help="Executable spring-chat-sample-webflux jar")
    parser.add_argument("--pairs", type=int, default=10, help="Request pairs per client")
    parser.add_argument("--clients", type=int, default=4)
    parser.add_argument("--timeout", type=float, default=10, help="Seconds to wait for a chunk")
    args = parser.parse_args()
    if min(args.clients, args.pairs, args.timeout) <= 0:
        parser.error("clients, pairs and timeout must be positive")
    java = Path(shutil.which("java") or "java").resolve()
    jcmd = java.with_name("jcmd")
    if not os.access(jcmd, os.X_OK):
        parser.error("Run with a JDK that has jcmd next to java")
    directory = Path(tempfile.mkdtemp(prefix="spring-chat-sse-"))
    log = directory / "server.log"
    print(f"Server log: {log}", flush=True)
    with log.open("w") as output:
        server = subprocess.Popen([java, "-jar", args.jar, "--server.address=127.0.0.1",
                                   "--server.port=0", "--spring.ai.model.chat=demo"],
                                  stdout=output, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 60
        while server.poll() is None and time.monotonic() < deadline:
            match = re.search(r"Netty started on port (\d+)", log.read_text())
            if match:
                break
            time.sleep(0.2)
        else:
            raise RuntimeError(f"WebFlux demo did not start; see {log}")
        run(args, server, int(match[1]), directory, jcmd)
        print(f"{args.clients * args.pairs * 2} replies completed ({args.clients} clients)")
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()


if __name__ == "__main__":
    main()
