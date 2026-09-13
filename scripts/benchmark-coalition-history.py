#!/usr/bin/env python3
"""Run #31's isolated full pipeline; bound aggregate process RSS plus new container memory."""
import json
import os
from pathlib import Path
import signal
import subprocess
import time

LIMIT = 6_000_000_000
SECONDS = 1800
ROOT = Path(__file__).resolve().parent.parent


def containers():
    return set(subprocess.check_output(["docker", "ps", "-q"], text=True).split())


def memory(group, initial):
    total = 0
    for path in Path("/proc").glob("[0-9]*/statm"):
        try:
            if os.getpgid(int(path.parent.name)) == group:
                total += int(path.read_text().split()[1]) * os.sysconf("SC_PAGE_SIZE")
        except (ProcessLookupError, PermissionError, FileNotFoundError):
            pass
    for container in containers() - initial:
        try:
            pid = subprocess.check_output(["docker", "inspect", "-f", "{{.State.Pid}}", container], text=True).strip()
            cgroup = Path(f"/proc/{pid}/cgroup").read_text().strip().split("::", 1)[1]
            total += int((Path("/sys/fs/cgroup") / cgroup.lstrip("/") / "memory.current").read_text())
        except (subprocess.CalledProcessError, FileNotFoundError):
            # A container can exit between listing and reading its memory.
            pass
    return total


def main():
    os.chdir(ROOT)
    initial = containers()
    start = time.monotonic()
    peak = 0
    stopped = None
    environment = os.environ.copy()
    environment["MAVEN_OPTS"] = "-Xmx512m"
    command = ["./mvnw", "-q", "-Dskip.installnodenpm", "-Dskip.npm", "-DargLine=-Xmx4g",
               "-Dcoalition.benchmark=true", "-Dit.test=PublicationIT#benchmarkTheFullPipelineOnTheSelectedHost",
               "failsafe:integration-test", "failsafe:verify"]
    with Path("target/coalition-benchmark.log").open("w") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=environment, start_new_session=True)
        while process.poll() is None:
            used = memory(process.pid, initial)
            peak = max(peak, used)
            if used > LIMIT or time.monotonic() - start > SECONDS:
                stopped = "memory_limit" if used > LIMIT else "time_limit"
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                break
            time.sleep(1)
        code = process.wait()
    report = {"command": command, "host": os.uname().nodename, "secondsIncludingTestStartup": time.monotonic() - start,
              "sampledPeakAggregateBytes": peak, "memoryLimitBytes": LIMIT, "samplingSeconds": 1,
              "memoryMethod": "sum of benchmark process-group RSS and memory.current of containers started during the benchmark; shared pages may be counted twice",
              "stopped": stopped, "exitCode": code}
    Path("target/coalition-benchmark-host.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    raise SystemExit(code if stopped is None else 1)


if __name__ == "__main__":
    main()
