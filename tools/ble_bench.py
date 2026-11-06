#!/usr/bin/env python3
"""Bench check for Libre2 Key's Bluetooth hand-off, without the watch.

Acts like AmazCGM: finds the phone by its advert, connects, enables
notifications, writes GET, reassembles the frames, checks the CRC32 against
the advert, prints the JSON and writes ACK.

    pip install bleak
    python3 tools/ble_bench.py

Make the phone visible first: open Libre2 Key or tap "Make visible to watch".
"""
import asyncio
import sys
import zlib

from bleak import BleakClient, BleakScanner

COMPANY_ID = 0xFFFF
MAGIC = b"AMZCGM\x01"
SERVICE = "0000ac10-0000-1000-8000-00805f9b34fb"
CONTROL = "0000ac11-0000-1000-8000-00805f9b34fb"
DATA = "0000ac12-0000-1000-8000-00805f9b34fb"


async def main() -> int:
    found = {}

    def on_advert(device, adv):
        # Primary advert: name AMZCGM + service UUID + service data [version][crc LE].
        if (adv.local_name or "").upper().startswith("AMZCGM"):
            found.setdefault("dev", device)
            found.setdefault("via", "name")
            found.setdefault("crc", None)
        sd = adv.service_data.get(SERVICE)
        if sd and len(sd) >= 5 and sd[0] == 1:
            found.setdefault("dev", device)
            found["crc"] = int.from_bytes(sd[1:5], "little")
            found["via"] = "service data"
            return
        # Scan response: manufacturer data AMZCGM + version + crc LE.
        payload = adv.manufacturer_data.get(COMPANY_ID)
        if payload and payload.startswith(MAGIC) and len(payload) >= len(MAGIC) + 4:
            found.setdefault("dev", device)
            found["crc"] = int.from_bytes(payload[len(MAGIC):len(MAGIC) + 4], "little")
            found["via"] = "manufacturer data"

    print("Scanning 15 s for Libre2 Key…")
    async with BleakScanner(on_advert):
        for _ in range(150):
            if "dev" in found:
                break
            await asyncio.sleep(0.1)
    if "dev" not in found:
        print("Not found. Is Libre2 Key visible?")
        return 1
    dev, crc = found["dev"], found["crc"]
    print(f"Found {dev.address} via {found['via']}, advert CRC {'unknown' if crc is None else f'{crc:08x}'}")

    parts, done = {}, asyncio.Event()

    def on_frame(_, data: bytearray):
        if len(data) < 2:
            return
        seq, total = data[0], data[1]
        parts.setdefault(seq, bytes(data[2:]))
        print(f"  frame {seq + 1}/{total} ({len(data)} bytes)")
        if len(parts) == total:
            done.set()

    async with BleakClient(dev) as client:
        print(f"Connected, MTU {client.mtu_size}")
        await client.start_notify(DATA, on_frame)
        await client.write_gatt_char(CONTROL, b"GET", response=True)
        try:
            await asyncio.wait_for(done.wait(), 10)
        except asyncio.TimeoutError:
            print(f"Timed out with {len(parts)} frames")
            return 1
        body = b"".join(parts[i] for i in range(len(parts)))
        got = zlib.crc32(body) & 0xFFFFFFFF
        await client.write_gatt_char(CONTROL, b"ACK", response=True)
    ok = crc is None or got == crc
    print(f"{len(body)} bytes, CRC {got:08x} {'OK' if ok else 'MISMATCH'}")
    print(body.decode("utf-8"))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
