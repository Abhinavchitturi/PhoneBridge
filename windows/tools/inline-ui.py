#!/usr/bin/env python3
"""Inlines ui/common.css and ui/common.js into one of the app's pages, the way the app does (WebHost.Page),
so it can be opened in any browser to look at the design without Windows:

    python3 tools/inline-ui.py index.html connected > /tmp/pb.html   # then open /tmp/pb.html
    python3 tools/inline-ui.py call.html waiting > /tmp/call.html

The optional second argument picks the page's demo state (each page lists its own)."""
import pathlib, sys

ui = pathlib.Path(__file__).resolve().parent.parent / "src" / "PhoneBridge" / "ui"
page = (ui / sys.argv[1]).read_text()
page = page.replace('<link rel="stylesheet" href="common.css">', "<style>" + (ui / "common.css").read_text() + "</style>")
demo = sys.argv[2] if len(sys.argv) > 2 else "default"
page = page.replace('<script src="common.js"></script>',
                    f"<script>window.__demo = {demo!r};</script><script>" + (ui / "common.js").read_text() + "</script>")
sys.stdout.write(page)
