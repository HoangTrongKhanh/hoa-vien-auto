#!/usr/bin/env python3
"""Kết nối adb (trong Termux) tới chính máy qua Gỡ lỗi không dây.

Cổng của Gỡ lỗi không dây đổi mỗi lần bật lại (có khi tự đổi), adb của Termux không hỗ trợ mDNS và ứng dụng
không đọc được danh sách cổng của hệ thống; dò cổng thì máy Samsung chặn khi dò dồn dập. Nên:
  1. thử cổng lần trước (lưu trong .cong_adb);
  2. hỏi qua mDNS dịch vụ "_adb-tls-connect._tcp" mà Gỡ lỗi không dây tự quảng bá (kèm số cổng).
In ra serial (127.0.0.1:<cổng>) nếu kết nối được, thoát mã 1 nếu không.
"""
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

CACHE = Path(__file__).resolve().parent / ".cong_adb"
SERVICE = "_adb-tls-connect._tcp.local"
MDNS_GROUP, MDNS_PORT = "224.0.0.251", 5353


# ---------------------------------------------------------------- mDNS

def _qname(name):
    return b"".join(bytes([len(p)]) + p.encode() for p in name.split(".")) + b"\0"


def _read_name(buf, off):
    labels, end = [], None
    for _ in range(64):                          # chống vòng lặp con trỏ lỗi
        n = buf[off]
        if n == 0:
            off += 1
            break
        if n & 0xC0 == 0xC0:                     # tên nén: con trỏ tới chỗ khác trong gói
            end = off + 2 if end is None else end
            off = ((n & 0x3F) << 8) | buf[off + 1]
            continue
        labels.append(buf[off + 1:off + 1 + n].decode(errors="replace"))
        off += 1 + n
    return ".".join(labels), (end if end is not None else off)


def _srv_ports(buf):
    """Các cổng trong bản ghi SRV của dịch vụ adb-tls-connect trong một gói mDNS."""
    _, _, qd, an, ns, ar = struct.unpack_from(">HHHHHH", buf, 0)
    off = 12
    for _ in range(qd):
        off = _read_name(buf, off)[1] + 4
    ports = []
    for _ in range(an + ns + ar):
        name, off = _read_name(buf, off)
        rtype, _, _, rdlen = struct.unpack_from(">HHIH", buf, off)
        off += 10
        if rtype == 33 and "_adb-tls-connect._tcp" in name:      # SRV: ưu tiên, trọng số, cổng, máy
            ports.append(struct.unpack_from(">H", buf, off + 4)[0])
        off += rdlen
    return ports


def _local_ip():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect((MDNS_GROUP, MDNS_PORT))      # không gửi gì, chỉ để biết card mạng đi ra (Wi-Fi)
        return s.getsockname()[0]


def _ask(sock, query, timeout):
    found = []
    sock.settimeout(0.3)
    sock.sendto(query, (MDNS_GROUP, MDNS_PORT))
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            data, _ = sock.recvfrom(9000)
        except socket.timeout:
            continue
        try:
            found += [p for p in _srv_ports(data) if p not in found]
        except (IndexError, struct.error):
            pass
    return found


def mdns_ports(timeout=2.5):
    """Hỏi mDNS cổng của Gỡ lỗi không dây. Thử kiểu unicast (trả lời gửi thẳng về cổng của mình) trước,
    không được thì nghe chung trên cổng 5353 như adb trên máy tính."""
    header = struct.pack(">HHHHHH", 0, 0, 1, 0, 0, 0) + _qname(SERVICE)
    try:
        ip = _local_ip()
    except OSError:
        ip = "0.0.0.0"
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
        if ip != "0.0.0.0":
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
        s.bind(("", 0))
        ports = _ask(s, header + struct.pack(">HH", 12, 0x8001), timeout)    # PTR, IN, xin trả lời unicast
    if ports:
        return ports
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if hasattr(socket, "SO_REUSEPORT"):
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            s.bind(("", MDNS_PORT))
            s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                         socket.inet_aton(MDNS_GROUP) + socket.inet_aton(ip))
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
            if ip != "0.0.0.0":
                s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
            return _ask(s, header + struct.pack(">HH", 12, 1), timeout)
    except OSError as e:
        print(f"Không nghe được mDNS trên cổng 5353: {e}", file=sys.stderr)
        return []


# ---------------------------------------------------------------- adb

def port_open(port):
    with socket.socket() as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def try_connect(port):
    serial = f"127.0.0.1:{port}"
    try:
        out = subprocess.run(["adb", "connect", serial], capture_output=True, text=True, timeout=8).stdout
    except subprocess.TimeoutExpired:
        return None
    if "connected to" in out:
        state = subprocess.run(["adb", "-s", serial, "get-state"], capture_output=True, text=True).stdout
        if state.strip() == "device":
            return serial
    subprocess.run(["adb", "disconnect", serial], capture_output=True)
    return None


def connect():
    """Kết nối adb tới chính máy. Trả về serial (127.0.0.1:<cổng>) hoặc None."""
    try:
        cached = int(CACHE.read_text().strip())
    except (OSError, ValueError):
        cached = None
    tried = []
    for source in ("nhớ", "mdns"):
        if source == "nhớ":
            ports = [cached] if cached and port_open(cached) else []
        else:
            ports = [p for p in mdns_ports() if p not in tried]
        for port in ports:
            tried.append(port)
            serial = try_connect(port)
            if serial:
                CACHE.write_text(str(port))
                return serial
    print(f"Không tìm được cổng Gỡ lỗi không dây (đã thử: {tried}). Kiểm tra Gỡ lỗi không dây đang bật.",
          file=sys.stderr)
    return None


def main():
    serial = connect()
    if serial:
        print(serial)
    return 0 if serial else 1


if __name__ == "__main__":
    sys.exit(main())
