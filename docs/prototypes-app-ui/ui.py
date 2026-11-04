"""Shared SVG primitives. A Frame is an app window in dp, a Sheet arranges unscaled frames."""
from base64 import b64encode
from html import escape
import json
from pathlib import Path
import re
from textwrap import wrap

ROOT = Path(__file__).resolve().parent
FONT = ROOT.parent / "prototypes/assets/Roboto.ttf"
ICONS = json.loads((ROOT / "material-icons.json").read_text())
LIGHT = dict(bg="#FFFBFE", surface="#F7F2FA", high="#ECE6F0", ink="#1D1B20",
             muted="#49454F", line="#CAC4D0", primary="#6750A4", onPrimary="#FFFFFF",
             tonal="#E8DEF8", onTonal="#1D192B", error="#B3261E", onError="#FFFFFF",
             errorBg="#F9DEDC", success="#2E693D", onSuccess="#FFFFFF")
DARK = dict(bg="#141218", surface="#211F26", high="#302D38", ink="#E6E0E9",
            muted="#CAC4D0", line="#49454F", primary="#D0BCFF", onPrimary="#381E72",
            tonal="#4A4458", onTonal="#E8DEF8", error="#FFB4AB", onError="#690005",
            errorBg="#512320", success="#316F40", onSuccess="#FFFFFF")
ORDER = ["components", "bundles", "bundle-actions", "setup", "settings", "localsend"]


def box(x, y, w, h, fill, r=0, stroke=None):
    edge = f' stroke="{stroke}"' if stroke else ""
    return f'<rect x="{x:g}" y="{y:g}" width="{w:g}" height="{h:g}" rx="{r:g}" fill="{fill}"{edge}/>'


def label(x, y, value, size, color, weight=400, anchor="start"):
    return (f'<text x="{x:g}" y="{y:g}" fill="{color}" font-size="{size:g}" '
            f'font-weight="{weight}" text-anchor="{anchor}">{escape(str(value))}</text>')


def use(ref, x, y, w, h, color=None):
    tint = f' color="{color}"' if color else ""
    return f'<use href="#{ref}" x="{x:g}" y="{y:g}" width="{w:g}" height="{h:g}"{tint}/>'


class Frame:
    def __init__(self, id, w=360, h=800, dark=False, pane=None, bottom=24):
        self.id, self.w, self.h, self.dark, self.bottom = id, w, h, dark, bottom
        self.pane = pane or (0, 0, w, h)
        self.hinge = None
        self.c = DARK if dark else LIGHT
        self.body, self.targets, self.symbols = [], [], {}
        self.box(0, 0, w, h, self.c["bg"])
        # System indicators are fixture chrome, never app-owned actions.
        self.txt(16, 17, "9:41", 12, weight=600)
        self.icon("wifi", w-48, 5, 14)
        self.box(w-27, 7, 14, 8, self.c["ink"], 2)
        self.box(w-13, 9, 2, 4, self.c["ink"], 1)

    def box(self, x, y, w, h, fill, r=0, stroke=None):
        self.body.append(box(x, y, w, h, fill, r, stroke))

    def txt(self, x, y, value, size=14, color=None, weight=400, anchor="start"):
        self.body.append(label(x, y, value, size, color or self.c["ink"], weight, anchor))

    def icon(self, name, x, y, size=24, color=None):
        if name not in ICONS:
            raise KeyError(name)
        self.body.append(use("ic-"+name, x, y, size, size, color or self.c["ink"]))

    def hit(self, name, x, y, w, h):
        self.targets.append(dict(label=name, x=x, y=y, w=w, h=h))

    def clear_hits(self):
        self.targets.clear()

    def button(self, value, x, y, w, kind="filled", enabled=True):
        c = self.c
        surface, ink = {"filled": (c["primary"], c["onPrimary"]),
                        "tonal": (c["tonal"], c["onTonal"]),
                        "text": ("none", c["primary"]),
                        "outline": ("none", c["primary"])}[kind]
        if not enabled:
            surface, ink = c["high"], c["muted"]
        self.box(x, y+4, w, 40, surface, 20, c["muted"] if kind=="outline" else None)
        self.txt(x+w/2, y+29, value, 14, ink, 550, "middle")
        if enabled:
            self.hit(value, x, y, w, 48)

    def iconbutton(self, name, action, x, y):
        self.icon(name, x+12, y+12, 24)
        self.hit(action, x, y, 48, 48)

    def bar(self, title, leading="back", trailing=None):
        x, y, w, _ = self.pane
        if leading:
            self.iconbutton(leading, "Back" if leading=="back" else "Clear selection", x+4, y+32)
        self.txt(x+(64 if leading else 16), y+64, title, 22)
        if trailing:
            self.iconbutton(*trailing, x+w-52, y+32)

    def nav(self):
        px, _, pw, _ = self.pane
        center = px + pw/2
        if self.bottom == 48:
            color = self.c["muted"]
            chrome = (f'<path d="M{center-96} {self.h-24}l12 -8v16Z" fill="{color}"/>'
                      f'<circle cx="{center}" cy="{self.h-24}" r="7" fill="{color}"/>'
                      +box(center+80,self.h-31,14,14,color,2))
        else:
            chrome = box(center-40, self.h-11, 80, 4, self.c["muted"], 2)
        return chrome + (box(*self.hinge,"#15131A") if self.hinge else "")

    def geometry(self):
        x, y, w, h = self.pane
        for i, a in enumerate(self.targets):
            l,t = a["x"],a["y"]
            r,b = l+a["w"],t+a["h"]
            if min(a["w"],a["h"]) < 48 or l<x or t<y+24 or r>x+w or b>y+h-self.bottom:
                raise ValueError(f"Unsafe target in {self.id}: {a} pane {self.pane}")
            for o in self.targets[:i]:
                if l<o["x"]+o["w"] and o["x"]<r and t<o["y"]+o["h"] and o["y"]<b:
                    raise ValueError(f"Overlapping targets in {self.id}: {o} / {a}")
        if self.hinge:
            hx, hy, hw, hh = self.hinge
            if x < hx+hw and x+w > hx and y < hy+hh and y+h > hy:
                raise ValueError(f"Active pane crosses the hinge in {self.id}")
        return dict(id=self.id, window=[self.w,self.h], pane=self.pane, bottomInset=self.bottom,
                    **({"hinge":self.hinge} if self.hinge else {}),
                    theme="dark" if self.dark else "light", targets=self.targets)


class Sheet:
    def __init__(self, slug, title, subtitle):
        self.slug, self.title, self.subtitle = slug, title, subtitle
        self.rows = []

    def row(self, *items):
        self.rows.append(items)

    def render(self):
        pad, gap = 40, 32
        width = max(840, max(sum(f.w for f,_,_ in row)+gap*(len(row)-1) for row in self.rows)+2*pad)
        index = ORDER.index(self.slug)+1
        parts = [label(pad, 44, "RECON   /   ANDROID", 15, "#504863", 700),
                 label(width-pad, 44, f"DESIGN REFERENCE    /    {index:02d}", 13, "#504863", 600, "end"),
                 label(pad, 105, self.title, 48, "#211C2C", 650)]
        yy = 140
        for line in wrap(self.subtitle, width=max(50,int((width-2*pad)/9))):
            parts.append(label(pad, yy, line, 17, "#554F60"))
            yy += 24
        yy += 22
        records, symbols = [], {}
        for row in self.rows:
            xx, captions = pad, []
            parts.append(box(pad, yy, width-2*pad, 1, "#CCC6D4"))
            for f, title, caption in row:
                item = f.geometry()
                item["sheetOrigin"] = [xx, yy+54]
                records.append(item)
                symbols.update(f.symbols)
                parts += [label(xx, yy+32, title, 17, "#302939", 650),
                          f'<svg id="frame-{f.id}" x="{xx}" y="{yy+54}" width="{f.w}" height="{f.h}" overflow="hidden">',
                          *f.body, f.nav(), '</svg>',
                          box(xx-.5, yy+53.5, f.w+1, f.h+1, "none", 0, "#B7AFBF")]
                lines = [s for para in caption.splitlines() for s in wrap(para,width=max(35,int(f.w/7.5)))]
                for i,line in enumerate(lines):
                    parts.append(label(xx, yy+f.h+81+i*19,line,13,"#554F60"))
                captions.append(f.h+len(lines)*19)
                xx += f.w+gap
            yy += max(captions)+111
        content = "\n".join(parts)
        custom = "\n".join(symbols.values())
        icon_names = set(re.findall(r'href="#ic-([\w]+)"',content+custom))
        glyphs = []
        for name in sorted(icon_names):
            paths = "".join(f'<path d="{d}" fill="currentColor"/>' for d in ICONS[name])
            glyphs.append(f'<symbol id="ic-{name}" viewBox="0 0 24 24">{paths}</symbol>')
        font = b64encode(FONT.read_bytes()).decode()
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{yy}" viewBox="0 0 {width} {yy}">\n'
               f'<title>{escape(self.title)} — Recon Android</title>\n<defs>{"".join(glyphs)}{custom}</defs>\n'
               f'<style>@font-face{{font-family:Roboto;src:url(data:font/ttf;base64,{font});font-weight:100 900}}'
               'text{font-family:Roboto,Arial,sans-serif}</style>\n'
               +box(0,0,width,yy,"#ECE9F0")+'\n'+content+'\n</svg>\n')
        return svg, records


def switch(f, right, center, checked, locked=False):
    c = f.c
    x = right - 52
    track = c["high"] if locked or not checked else c["primary"]
    knob = c["onPrimary"] if checked and not locked else c["muted"]
    f.box(x,center-16,52,32,track,16,c["muted"] if not checked else None)
    size = 24 if checked else 16
    left = x+(24 if checked else 8)
    f.box(left,center-size/2,size,size,knob,size/2)
    if checked:
        f.icon("lock" if locked else "check", left+4,center-8,16,c["bg"] if locked else track)
