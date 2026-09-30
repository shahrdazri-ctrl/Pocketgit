#!/usr/bin/env python3
"""Capture the real CLI demo to asciicast v2 and render it to a terminal GIF.

Documentation-only tooling: Python 3, Pillow, a POSIX PTY, and DejaVu Sans Mono.
No application output is synthesized; every terminal line comes from demo.sh.
"""

import argparse
import codecs
import errno
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import struct
import termios
import textwrap
import time

from PIL import Image, ImageDraw, ImageFont


def capture(jar, script, destination, pause):
    width, height = 94, 26
    header = {
        "version": 2,
        "width": width,
        "height": height,
        "timestamp": int(time.time()),
        "title": "PocketGit: snapshots, branches, checkout, restore, verify",
        "command": "sh examples/demo.sh",
        "env": {"SHELL": "/bin/sh", "TERM": "xterm-256color"},
    }
    decoder = codecs.getincrementaldecoder("utf-8")()
    started = time.monotonic()
    child, descriptor = pty.fork()
    if child == 0:
        os.environ["POCKETGIT_DEMO_PAUSE"] = str(pause)
        os.environ["TERM"] = "xterm-256color"
        os.execv("/bin/sh", ["sh", str(script), str(jar)])
    fcntl.ioctl(descriptor, termios.TIOCSWINSZ, struct.pack("HHHH", height, width, 0, 0))
    events = []
    with destination.open("w", encoding="utf-8", newline="\n") as recording:
        recording.write(json.dumps(header, ensure_ascii=False) + "\n")
        while True:
            if not select.select([descriptor], [], [], 1)[0]:
                continue
            try:
                chunk = os.read(descriptor, 65536)
            except OSError as failure:
                if failure.errno == errno.EIO:
                    break
                raise
            if not chunk:
                break
            output = decoder.decode(chunk)
            if output:
                event = [round(time.monotonic() - started, 6), "o", output]
                events.append(event)
                recording.write(json.dumps(event, ensure_ascii=False) + "\n")
    os.close(descriptor)
    _, status = os.waitpid(child, 0)
    if os.waitstatus_to_exitcode(status) != 0:
        raise RuntimeError("demo failed; recording retained for diagnosis")
    return events, time.monotonic() - started, width, height


def render(events, duration, destination, columns, rows, font_path):
    font = ImageFont.truetype(str(font_path), 17)
    title_font = ImageFont.truetype(str(font_path), 18)
    cell = font.getlength("M")
    line_height, padding, top = 23, 24, 76
    size = (int(cell * columns + padding * 2), top + rows * line_height + 27)
    frames, cursor, text = [], 0, ""
    fps = 4
    # Last output receives a short readable hold, included in the GIF duration.
    frame_count = int((duration + 1.5) * fps) + 1
    for index in range(frame_count):
        elapsed = index / fps
        while cursor < len(events) and events[cursor][0] <= elapsed:
            text += events[cursor][2]
            cursor += 1
        display = text.replace("\r\n", "\n").replace("\r", "")
        lines = []
        for line in display.split("\n"):
            lines.extend(textwrap.wrap(line, columns, replace_whitespace=False,
                                       drop_whitespace=False, break_on_hyphens=False) or [""])
        visible = lines[-rows:]
        frame = Image.new("RGB", size, "#11151c")
        draw = ImageDraw.Draw(frame)
        draw.rectangle((0, 0, size[0], 55), fill="#202733")
        for x, color in zip((25, 47, 69), ("#ed6a5f", "#f4bf50", "#62c554")):
            draw.ellipse((x, 20, x + 11, 31), fill=color)
        draw.text((103, 15), "PocketGit  |  snapshots → branches → restore",
                  font=title_font, fill="#e5edf6")
        for row, line in enumerate(visible):
            color = "#cbd5e1"
            if line.startswith("$ "):
                color = "#93c5fd"
            elif line.startswith(("+", "✓", "Repository OK.", "Demo completed")):
                color = "#86efac"
            elif line.startswith("-"):
                color = "#fca5a5"
            draw.text((padding, top + row * line_height), line, font=font, fill=color)
        draw.rectangle((padding, size[1] - 11, size[0] - padding, size[1] - 8), fill="#253247")
        progress = min(elapsed / duration, 1)
        draw.rectangle((padding, size[1] - 11,
                        padding + (size[0] - padding * 2) * progress, size[1] - 8), fill="#60a5fa")
        frames.append(frame.convert("P", palette=Image.Palette.ADAPTIVE, colors=64))
    frames[0].save(destination, save_all=True, append_images=frames[1:],
                   duration=1000 // fps, loop=0, optimize=True, disposal=2)


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=root / "target/pocketgit.jar")
    parser.add_argument("--output-dir", type=Path, default=root / "docs/screenshots")
    parser.add_argument("--pause", type=float, default=0.7)
    parser.add_argument("--font", type=Path,
                        default=Path("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"))
    args = parser.parse_args()
    if not args.jar.is_file() or not args.font.is_file():
        parser.error("the packaged JAR and --font file must exist")
    if not 0 <= args.pause <= 5:
        parser.error("--pause must be between 0 and 5 seconds")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    events, duration, width, height = capture(args.jar.resolve(), root / "examples/demo.sh",
                                             args.output_dir / "demo.cast", args.pause)
    render(events, duration, args.output_dir / "demo.gif", width, height, args.font)
    print(f"Captured {len(events)} actual terminal events in {duration:.2f}s; "
          f"rendered {args.output_dir / 'demo.gif'}")


if __name__ == "__main__":
    main()
