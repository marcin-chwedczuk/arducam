#!/usr/bin/env python3
"""Render the '# IMAGE w h YUYV n' block of an arducam serial log to PNG.

Usage: render_yuyv.py LOG OUT_PREFIX [scale] [--yvyu]
--yvyu: treat the data as Y0 V0 Y1 U0 (U and V swapped).
--cw: rotate the output 90 degrees clockwise.
Writes OUT_PREFIX-color.png and OUT_PREFIX-gray.png (Y only).
Lines may carry Arduino IDE timestamps ("11:55:09.366 -> ").
"""
import re
import struct
import sys
import zlib


def read_image(path):
    data, w, h, inside = bytearray(), 0, 0, False
    with open(path, encoding="ascii", errors="replace") as f:
        for line in f:
            line = re.sub(r"^\d\d:\d\d:\d\d\.\d+ -> ", "", line).strip()
            if line.startswith("# IMAGE"):
                _, _, w, h, _fmt, _n = line.split()
                w, h, inside = int(w), int(h), True
                data.clear()
            elif line.startswith("# END IMAGE"):
                break
            elif inside and line and not line.startswith("#"):
                data.extend(int(x, 16) for x in line.rstrip(".").split())
    if len(data) != w * h * 2:
        print(f"warning: got {len(data)} bytes, expected {w * h * 2}")
        data.extend(b"\0" * (w * h * 2 - len(data)))
    return w, h, data


def clamp(v):
    return 0 if v < 0 else 255 if v > 255 else int(v)


def yuyv_to_rgb(w, h, d, swap_uv=False):
    rows_c, rows_g = [], []
    for y in range(h):
        rc, rg = bytearray(), bytearray()
        base = y * w * 2
        for x in range(0, w, 2):
            y0, u, y1, v = d[base + 2 * x: base + 2 * x + 4]
            if swap_uv:
                u, v = v, u
            for yy in (y0, y1):
                # BT.601 full range (COM15 = 0xC0)
                rc += bytes((clamp(yy + 1.402 * (v - 128)),
                             clamp(yy - 0.344 * (u - 128) - 0.714 * (v - 128)),
                             clamp(yy + 1.772 * (u - 128))))
                rg.append(yy)
        rows_c.append(rc)
        rows_g.append(rg)
    return rows_c, rows_g


def rotate_cw(rows, w, h, ch):
    """Rotate 90 degrees clockwise: new[r][c] = old[h-1-c][r]."""
    out = []
    for r in range(w):
        row = bytearray()
        for c in range(h):
            row += rows[h - 1 - c][r * ch:(r + 1) * ch]
        out.append(row)
    return out


def write_png(path, rows, w, h, channels, scale):
    raw = bytearray()
    for row in rows:
        big = bytearray()
        for x in range(w):
            big += row[x * channels:(x + 1) * channels] * scale
        for _ in range(scale):
            raw += b"\0" + big
    def chunk(t, b):
        return (struct.pack(">I", len(b)) + t + b +
                struct.pack(">I", zlib.crc32(t + b) & 0xffffffff))
    color_type = 2 if channels == 3 else 0
    png = (b"\x89PNG\r\n\x1a\n" +
           chunk(b"IHDR", struct.pack(">IIBBBBB", w * scale, h * scale,
                                      8, color_type, 0, 0, 0)) +
           chunk(b"IDAT", zlib.compress(bytes(raw), 9)) +
           chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    swap = "--yvyu" in sys.argv
    log, prefix = args[0], args[1]
    scale = int(args[2]) if len(args) > 2 else 4
    w, h, d = read_image(log)
    rows_c, rows_g = yuyv_to_rgb(w, h, d, swap)
    if "--cw" in sys.argv:
        rows_c = rotate_cw(rows_c, w, h, 3)
        rows_g = rotate_cw(rows_g, w, h, 1)
        w, h = h, w
    write_png(prefix + "-color.png", rows_c, w, h, 3, scale)
    write_png(prefix + "-gray.png", rows_g, w, h, 1, scale)
    print(f"{w}x{h} -> {prefix}-color.png, {prefix}-gray.png")


if __name__ == "__main__":
    main()
