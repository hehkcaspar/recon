"""Generate standalone sheets and per-screen hit bounds using the Python standard library."""
import argparse
from hashlib import sha256
import importlib
import json
import re
import xml.etree.ElementTree as ET
from ui import ORDER, ROOT

MODULE = dict(components="components", bundles="library", setup="setup_settings",
              settings="setup_settings", localsend="localsend")
MODULE["bundle-actions"] = "library"


def publish(path, value, check):
    if path.is_file() and path.read_text() == value:
        return
    if check:
        raise SystemExit(f"Stale or missing: {path.name}")
    path.parent.mkdir(exist_ok=True)
    path.write_text(value)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--verify-renders", action="store_true", help="Also verify reviewed SVG/PNG hashes")
    parser.add_argument("sheets", nargs="*")
    args = parser.parse_args()
    selected = args.sheets or ORDER
    if set(selected)-set(ORDER):
        parser.error("Unknown sheet")
    for module in dict.fromkeys(MODULE[slug] for slug in selected):
        for sheet in importlib.import_module(module).sheets():
            if sheet.slug not in selected:
                continue
            svg, frames = sheet.render()
            tree = ET.fromstring(svg)
            ids = [e.attrib["id"] for e in tree.iter() if "id" in e.attrib]
            assert len(ids) == len(set(ids)), sheet.slug
            for ref in re.findall(r"url\(#([^)]+)\)", svg):
                assert ref in ids, ref
            for e in tree.iter():
                if "href" in e.attrib:
                    href = e.attrib["href"]
                    assert href.startswith("#") and href[1:] in ids, href
            publish(ROOT / (sheet.slug+".svg"), svg, args.check)
            bounds = json.dumps({"units":"logical dp at 1x", "frames":frames}, indent=2)+"\n"
            publish(ROOT / "bounds" / (sheet.slug+".json"), bounds, args.check)
            if args.verify_renders:
                manifest = json.loads((ROOT/"review/manifest.json").read_text())["files"][sheet.slug]
                png = (ROOT/"review"/(sheet.slug+".png")).read_bytes()
                if manifest["overflow"] or manifest["fonts"] != ["Roboto"] or manifest["filePngSha256"] != manifest["pngSha256"]:
                    raise ValueError(f"Renderer reported a problem: {sheet.slug}")
                if sha256(svg.encode()).hexdigest() != manifest["svgSha256"] or sha256(png).hexdigest() != manifest["pngSha256"]:
                    raise ValueError(f"Render hashes differ: {sheet.slug}")
            print(f"{sheet.slug}: {len(frames)} frames, {sum(len(f['targets']) for f in frames)} targets")


if __name__ == "__main__":
    main()
