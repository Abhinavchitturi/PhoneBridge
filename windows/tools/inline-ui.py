#!/usr/bin/env python3
"""Inlines a page's local stylesheets and scripts (ui/common.css, ui/common.js, ui/themes.js, …) the way the app does
(WebHost.Page), so it can be opened in any browser to look at the design without Windows:

    python3 tools/inline-ui.py index.html connected > preview/index.html     # then open it
    python3 tools/inline-ui.py phone.html photos > preview/phone.html
    python3 tools/inline-ui.py call.html waiting > preview/call.html

The optional second argument picks the page's demo state (each page lists its own); a third picks a theme
(midnight, batman, spiderman, ironman, thor, matrix)."""
import pathlib
import re
import sys

ui = pathlib.Path(__file__).resolve().parent.parent / "src" / "PhoneBridge" / "ui"
page = (ui / sys.argv[1]).read_text()
demo = sys.argv[2] if len(sys.argv) > 2 else "default"
theme = sys.argv[3] if len(sys.argv) > 3 else "midnight"
page = re.sub(r'<link rel="stylesheet" href="([a-z]+\.css)">', lambda m: "<style>" + (ui / m.group(1)).read_text() + "</style>", page)
first = True


def script(m):
    global first
    pre = ""
    if first:
        pre = f"<script>window.__demo = {demo!r}; window.__theme = {theme!r};</script>"
        first = False
    return pre + "<script>" + (ui / m.group(1)).read_text() + "</script>"


page = re.sub(r'<script src="([a-z]+\.js)"></script>', script, page)
sys.stdout.write(page)
