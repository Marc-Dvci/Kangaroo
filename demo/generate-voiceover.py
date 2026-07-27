#!/usr/bin/env python3
"""Generate the demo voice-over with Microsoft Edge's neural text-to-speech.

Reads the blockquoted lines out of ``narration.md`` and writes one MP3 per beat into the web
resources, plus a ``manifest.json`` holding each clip's measured duration. The demo driver reads
that manifest so the visual choreography is paced by the audio that actually exists rather than by
durations typed into a script and never checked again.

    python demo/generate-voiceover.py                 # generate anything missing
    python demo/generate-voiceover.py --force         # regenerate everything
    python demo/generate-voiceover.py --voice en-GB-RyanNeural
    python demo/generate-voiceover.py --list-voices

The only dependency is ``edge-tts``, and it is only needed to *build* the narration. The generated
MP3s are committed, so recording the demo needs neither Python nor a network connection.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NARRATION = ROOT / "demo" / "narration.md"
OUT_DIR = ROOT / "src" / "main" / "resources" / "web" / "demo" / "speech"

# A British male voice at a slightly measured pace. This is clinical material for an international
# audience: the pauses matter more than the energy, and -8% is the difference between "presenting"
# and "pitching".
DEFAULT_VOICE = "en-GB-RyanNeural"
DEFAULT_RATE = "-8%"
DEFAULT_PITCH = "-2Hz"


def parse_beats(path: Path) -> list[dict]:
    """Pull ``## Beat N · slug · ~S s`` headings and the blockquote under each one."""
    if not path.exists():
        sys.exit(f"narration file not found: {path}")

    beats: list[dict] = []
    current: dict | None = None

    heading = re.compile(r"^##\s+Beat\s+(\d+)\s*·\s*([^·]+?)\s*·\s*~?(\d+)\s*s", re.I)

    for line in path.read_text(encoding="utf-8").splitlines():
        m = heading.match(line.strip())
        if m:
            if current:
                beats.append(current)
            current = {
                "index": int(m.group(1)),
                "slug": re.sub(r"[^a-z0-9]+", "-", m.group(2).strip().lower()).strip("-"),
                "target_seconds": int(m.group(3)),
                "lines": [],
            }
            continue
        if current is not None and line.lstrip().startswith(">"):
            current["lines"].append(line.lstrip()[1:].strip())

    if current:
        beats.append(current)

    for b in beats:
        # Join, then collapse whitespace: the markdown wraps mid-sentence and the synthesiser would
        # otherwise hear the line breaks as pauses.
        b["text"] = re.sub(r"\s+", " ", " ".join(b["lines"])).strip()
        del b["lines"]

    if not beats:
        sys.exit("no beats found — headings must look like '## Beat 1 · problem · ~15 s'")
    return beats


def mp3_duration_seconds(path: Path) -> float:
    """Duration of a CBR MPEG audio file, by walking its frame headers.

    Written out rather than pulled from a library because this script already has exactly one
    dependency and adding an audio-metadata package to read one number would be a poor trade.
    """
    BITRATES_V1_L3 = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0]
    BITRATES_V2_L3 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0]
    RATES_V1 = [44100, 48000, 32000, 0]
    RATES_V2 = [22050, 24000, 16000, 0]
    RATES_V25 = [11025, 12000, 8000, 0]

    data = path.read_bytes()
    i = 0

    # Skip an ID3v2 tag if present.
    if data[:3] == b"ID3" and len(data) > 10:
        size = struct.unpack(">I", b"\x00" + data[6:9].rjust(3, b"\x00"))[0]
        size = ((data[6] & 0x7F) << 21) | ((data[7] & 0x7F) << 14) | ((data[8] & 0x7F) << 7) | (data[9] & 0x7F)
        i = 10 + size

    duration = 0.0
    while i + 4 <= len(data):
        if data[i] != 0xFF or (data[i + 1] & 0xE0) != 0xE0:
            i += 1
            continue

        version_bits = (data[i + 1] >> 3) & 0x03
        layer_bits = (data[i + 1] >> 1) & 0x03
        bitrate_index = (data[i + 2] >> 4) & 0x0F
        rate_index = (data[i + 2] >> 2) & 0x03
        padding = (data[i + 2] >> 1) & 0x01

        if layer_bits != 0x01 or bitrate_index in (0, 15) or rate_index == 3:
            i += 1
            continue

        if version_bits == 3:        # MPEG 1
            bitrate = BITRATES_V1_L3[bitrate_index] * 1000
            rate = RATES_V1[rate_index]
            samples = 1152
        elif version_bits == 2:      # MPEG 2
            bitrate = BITRATES_V2_L3[bitrate_index] * 1000
            rate = RATES_V2[rate_index]
            samples = 576
        elif version_bits == 0:      # MPEG 2.5
            bitrate = BITRATES_V2_L3[bitrate_index] * 1000
            rate = RATES_V25[rate_index]
            samples = 576
        else:
            i += 1
            continue

        if bitrate == 0 or rate == 0:
            i += 1
            continue

        frame_length = (samples // 8 * bitrate) // rate + padding
        if frame_length <= 4:
            i += 1
            continue

        duration += samples / rate
        i += frame_length

    return round(duration, 3)


async def synthesise(beat: dict, out: Path, voice: str, rate: str, pitch: str) -> None:
    import edge_tts

    communicate = edge_tts.Communicate(beat["text"], voice, rate=rate, pitch=pitch)
    await communicate.save(str(out))


async def list_voices() -> None:
    import edge_tts

    voices = await edge_tts.list_voices()
    for v in sorted(voices, key=lambda v: v["ShortName"]):
        if v["Locale"].startswith("en-"):
            print(f"{v['ShortName']:<38} {v['Gender']:<8}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--voice", default=DEFAULT_VOICE)
    parser.add_argument("--rate", default=DEFAULT_RATE)
    parser.add_argument("--pitch", default=DEFAULT_PITCH)
    parser.add_argument("--force", action="store_true", help="regenerate clips that already exist")
    parser.add_argument("--list-voices", action="store_true")
    args = parser.parse_args()

    if args.list_voices:
        asyncio.run(list_voices())
        return 0

    try:
        import edge_tts  # noqa: F401
    except ImportError:
        print("edge-tts is not installed.  pip install edge-tts", file=sys.stderr)
        return 2

    beats = parse_beats(NARRATION)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    print(f"voice {args.voice}   rate {args.rate}   pitch {args.pitch}")
    print(f"out   {OUT_DIR}")
    print()

    manifest = []
    total = 0.0
    target_total = 0

    for beat in beats:
        name = f"{beat['index']:02d}-{beat['slug']}.mp3"
        out = OUT_DIR / name

        if out.exists() and not args.force:
            print(f"  keep      {name}")
        else:
            asyncio.run(synthesise(beat, out, args.voice, args.rate, args.pitch))
            print(f"  generated {name}")

        seconds = mp3_duration_seconds(out)
        total += seconds
        target_total += beat["target_seconds"]

        drift = seconds - beat["target_seconds"]
        flag = "  <-- off target" if abs(drift) > 4 else ""
        print(f"            {seconds:5.1f} s  (target {beat['target_seconds']:>2} s,"
              f" {drift:+.1f}){flag}")

        manifest.append({
            "file": name,
            "index": beat["index"],
            "slug": beat["slug"],
            "seconds": seconds,
            "text": beat["text"],
        })

    (OUT_DIR / "manifest.json").write_text(
        json.dumps({"voice": args.voice, "total_seconds": round(total, 2), "beats": manifest},
                   indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8")

    print()
    print(f"  total {total:.1f} s  (script target {target_total} s)")
    if total < 90 or total > 120:
        print(f"  WARNING: the contest asks for 90-120 s and this is {total:.0f} s.")
        print("           Trim or extend narration.md, then re-run with --force.")
    else:
        print("  within the 90-120 s the contest asks for.")
    print()
    print(f"  wrote {OUT_DIR / 'manifest.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
