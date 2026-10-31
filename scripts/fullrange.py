#!/usr/bin/env python3
"""Mark Latitude clips recorded before the fix as full range, in place.

    scripts/fullrange.py clip.mp4 [more.mp4 ...]

The phone writes full-range YUV but older clips say "limited", so Resolve adds ~17 % contrast.
Flips the same two flags the app now flips itself (see FullRange.kt): the 'colr' box and
video_full_range_flag in the HEVC SPS. Picture data is untouched; already fixed files are skipped.
"""
import struct
import sys


def boxes(buf, start, end):
    off = start
    while off + 8 <= end:
        size, typ = struct.unpack(">I4s", buf[off:off + 8])
        if size == 1:
            size = struct.unpack(">Q", buf[off + 8:off + 16])[0]
        elif size == 0:
            size = end - off
        if size < 8 or off + size > end:
            return
        yield typ.decode("latin1"), off, off + size
        off += size


def sample_entry(buf, start, end):
    for typ, s, e in boxes(buf, start, end):
        if typ in ("hvc1", "hev1"):
            return s, e
        inner = {"trak": 8, "mdia": 8, "minf": 8, "stbl": 8, "stsd": 16}.get(typ)
        if inner:
            found = sample_entry(buf, s + inner, e)
            if found:
                return found
    return None


def range_flag(buf, start, end, prim, trc, mat):
    """Absolute bit position of video_full_range_flag, found by its VUI signature."""
    rbsp, origin, zeros = [], [], 0
    for i in range(start + 2, end):
        b = buf[i]
        if zeros >= 2 and b == 3:
            zeros = 0
            continue
        zeros = zeros + 1 if b == 0 else 0
        rbsp.append(b)
        origin.append(i)
    bit = lambda p: rbsp[p // 8] >> (7 - p % 8) & 1
    bits = lambda p, n: int("".join(str(bit(p + k)) for k in range(n)), 2)
    matches = [p for p in range(len(rbsp) * 8 - 30)
               if bit(p) and bit(p + 5) and bits(p + 6, 8) == prim and bits(p + 14, 8) == trc and bits(p + 22, 8) == mat]
    if len(matches) != 1:
        return None
    flag = matches[0] + 4
    return origin[flag // 8] * 8 + flag % 8


def fix(path):
    with open(path, "r+b") as f:
        data = f.read()
        moov = next(((s, e) for t, s, e in boxes(data, 0, len(data)) if t == "moov"), None)
        if not moov:
            return "no moov"
        buf = bytearray(data[moov[0]:moov[1]])
        entry = sample_entry(buf, 8, len(buf))
        if not entry:
            return "no HEVC track"
        kids = {t: (s, e) for t, s, e in boxes(buf, entry[0] + 86, entry[1])}
        if "colr" not in kids or "hvcC" not in kids:
            return "no colr/hvcC"
        c = kids["colr"][0] + 8
        if buf[c:c + 4] != b"nclx":
            return "colr not nclx"
        prim, trc, mat = struct.unpack(">HHH", buf[c + 4:c + 10])
        if buf[c + 10] & 0x80:
            return "already full range"
        off = kids["hvcC"][0] + 8 + 22
        arrays, off = buf[off], off + 1
        flag = None
        for _ in range(arrays):
            nal_type, count = buf[off] & 0x3F, struct.unpack(">H", buf[off + 1:off + 3])[0]
            off += 3
            for _ in range(count):
                length = struct.unpack(">H", buf[off:off + 2])[0]
                if nal_type == 33:
                    flag = range_flag(buf, off + 2, off + 2 + length, prim, trc, mat)
                off += 2 + length
        if flag is None:
            return "range flag not found"
        buf[c + 10] |= 0x80
        buf[flag // 8] |= 0x80 >> (flag % 8)
        f.seek(moov[0])
        f.write(buf)
    return "fixed"


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for p in sys.argv[1:]:
        print(f"{p}: {fix(p)}")
