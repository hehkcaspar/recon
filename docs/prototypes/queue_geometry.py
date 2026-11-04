"""Shared queue math and C geometry source for the static SVG generators.

The JSON file is the hand-authored source for both JavaScript and Python. This
module only maps its camelCase profile fields to Python names and implements
the shared fit formula; it does not own a second set of profile constants.
"""

import json
from math import floor
from pathlib import Path

SOURCE = json.loads((Path(__file__).resolve().parent / "foldable-c-geometry-source.json").read_text())
C_GEOMETRY_SOURCE = SOURCE
C_DEVICES = SOURCE["devices"]
QUEUE_PROFILES = {
    name: {
        "media": value["media"],
        "edge_target": value["edgeTarget"],
        "handler_width": value["handlerWidth"],
        "handler": value["handler"],
        "strip": value["strip"],
        "vertical_padding": value["verticalPadding"],
        "gap": value["gap"],
    }
    for name, value in SOURCE["profiles"].items()
}


def derive_queue_capacity(available_axis, start_margin, end_margin,
                          profile_name="standard56", gap_override=None):
    profile = QUEUE_PROFILES[profile_name]
    gap = profile["gap"] if gap_override is None else gap_override
    lane_length = max(0, available_axis - start_margin - end_margin)
    visible_count = max(0, floor(
        (lane_length - 2 * profile["edge_target"] + gap)
        / (profile["media"] + gap)
    ))
    endpoint_media_gap = 0 if visible_count == 0 else (
        lane_length - 2 * profile["edge_target"]
        - visible_count * profile["media"]
        - (visible_count - 1) * gap
    ) / 2
    return {
        "profile": profile_name,
        "available_axis": available_axis,
        "start_margin": start_margin,
        "end_margin": end_margin,
        "lane_length": lane_length,
        "visible_count": visible_count,
        "endpoint_media_gap": endpoint_media_gap,
        "gap": gap,
        **profile,
    }


def queue_item_start(capacity):
    return capacity["edge_target"] + capacity["endpoint_media_gap"]


def visible_symbols(count):
    symbols = ("Photo", "Video", "Voice")
    return [symbols[index % len(symbols)] for index in range(count)]
