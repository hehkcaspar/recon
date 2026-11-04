"""LocalSend sender states over the Bundles selection that opened the sheet."""

from library import library_frame
from ui import Frame, Sheet


RECEIVER = ("Field station", "MacBook Air", "8e21b74a")
# Measured at 16sp Roboto 550 in the cover's 228dp alias column.
SECOND_PEER = ("North Shore Field Station –\nAcoustic Monitoring and\nCalibration Workstation",
               "Pixel Tablet", "57a90d3c")
# The two selected rows contain five files in 0006 and four photos in 0005.
BUNDLE_ID = "2026-11-02-s-0006"
TOTAL_FILES = 9
TOTAL_MB = 24.0


def _modal(frame_id, height, w=360, h=800, dark=False):
    f = library_frame(frame_id, w=w, h=h, dark=dark, selected=(0, 1))
    f.clear_hits()
    f.body.append(f'<rect width="{f.w}" height="{f.h}" fill="#000" opacity=".40"/>')

    # Center a bounded sheet on the flat inner display. Its surface continues
    # behind the gesture bar, while the footer stays above the system inset.
    width = min(f.w, 528)
    left = (f.w - width) // 2
    top = f.h - f.bottom - height
    f.box(left, top, width, height + f.bottom, f.c["surface"], 28)
    f.box(left, top + 28, width, height + f.bottom - 28, f.c["surface"])
    f.box(left + width // 2 - 16, top + 12, 32, 4, f.c["line"], 2)

    x = left + 24
    f.icon("send", x, top + 32, 16, f.c["primary"])
    f.txt(x + 24, top + 45, "LocalSend", 13, f.c["muted"], 550)
    return f, x, top, width - 48


def _footer_y(f: Frame):
    return f.h - f.bottom - 16 - 48


def _waiting(f, x, y, message):
    f.body.append(
        f'<circle cx="{x + 10}" cy="{y + 10}" r="8" fill="none" '
        f'stroke="{f.c["primary"]}" stroke-width="3" stroke-linecap="round" '
        'stroke-dasharray="34 17"/>'
    )
    f.txt(x + 32, y + 15, message, 15)


def _peer(f, x, y, width, peer, pick=False):
    alias, model, fingerprint = peer
    lines = (alias if pick else f"To {alias}").splitlines()
    row_height = 64 + 20 * (len(lines) - 1)
    center_y = y + row_height // 2
    f.box(x, center_y - 20, 40, 40, f.c["high"], 12)
    f.icon("devices", x + 8, center_y - 12, 24, f.c["muted"])
    for i, line in enumerate(lines):
        f.txt(x + 52, y + 28 + 20 * i, line, 16, weight=550)
    f.txt(x + 52, y + row_height - 16, f"{model} · {fingerprint}", 13, f.c["muted"])
    if pick:
        f.icon("chevron_right", x + width - 24, center_y - 12, 24, f.c["muted"])
        f.hit(f"Send 2 bundles to {' '.join(lines)}, {model}, {fingerprint}", x, y, width, row_height)


def _discovery(state, height, **window):
    f, x, y, width = _modal(f"localsend-{state}", height, **window)
    f.txt(x, y + 82, "Send 2 bundles", 24, weight=500)
    if state == "timeout":
        f.txt(x, y + 124, "No devices found", 18, weight=550)
        f.txt(x, y + 151, "Connect both devices to the same Wi-Fi", 15, f.c["muted"])
        f.txt(x, y + 173, "and open LocalSend on the receiver.", 15, f.c["muted"])
        f.button("Close", x, _footer_y(f), 88, "text")
        f.button("Try again", x + width - 120, _footer_y(f), 120, "tonal")
    else:
        f.txt(x, y + 108, "Choose a device on the same Wi-Fi.", 15, f.c["muted"])
        if state == "searching":
            _waiting(f, x, y + 140, "Looking for devices…")
        else:
            _peer(f, x, y + 128, width, RECEIVER, pick=True)
            f.box(x, y + 192, width, 1, f.c["line"])
            _peer(f, x, y + 193, width, SECOND_PEER, pick=True)
        f.button("Close", x + width - 88, _footer_y(f), 88, "text")
    return f


def _sending(frame_id, negotiating=False, **window):
    f, x, y, width = _modal(frame_id, 304 if negotiating else 396, **window)
    f.txt(x, y + 82, "Sending 2 bundles", 24, weight=500)
    _peer(f, x, y + 92, width, RECEIVER)
    f.box(x, y + 166, width, 1, f.c["line"])
    if negotiating:
        _waiting(f, x, y + 192, "Negotiating with receiver…")
    else:
        sent_mb = 9.6
        f.txt(x, y + 201, f"3 of {TOTAL_FILES} files sent", 16, weight=550)
        f.txt(x + width, y + 201, f"{sent_mb:.1f} / {TOTAL_MB:.1f} MB", 14,
              f.c["muted"], anchor="end")
        f.box(x, y + 215, width, 6, f.c["high"], 3)
        f.box(x, y + 215, width * sent_mb / TOTAL_MB, 6, f.c["primary"], 3)
        f.txt(x, y + 253, "Current file", 13, f.c["muted"], 550)
        folder = f"{BUNDLE_ID}/videos/"
        name = f"{BUNDLE_ID}-v-001.mp4"
        lines = [folder + name] if width >= 450 else [folder, name]
        for i, line in enumerate(lines):
            f.body.append(
                f'<text x="{x}" y="{y + 276 + 20 * i}" font-size="14" '
                f'style="font-family:monospace" fill="{f.c["ink"]}">{line}</text>'
            )
    f.button("Cancel transfer", x, _footer_y(f), width, "outline")
    return f


def _outcome(state, title, detail, icon, badge, ink, dark=False):
    f, x, y, width = _modal(f"localsend-{state}", 384, dark=dark)
    f.box(x, y + 67, 48, 48, f.c[badge], 24)
    f.icon(icon, x + 10, y + 77, 28, f.c[ink])
    f.txt(x, y + 151, title, 24, weight=500)
    for i, line in enumerate(detail):
        f.txt(x, y + 179 + 22 * i, line, 15, f.c["muted"])
    f.box(x, y + 224, width, 1, f.c["line"])
    _peer(f, x, y + 232, width, RECEIVER)
    f.button("Done", x, _footer_y(f), width, "tonal")
    return f


def _short_landscape():
    f,x,y,width = _modal("localsend-short-landscape",288,w=800,h=360)
    f.txt(x,y+78,"Sending 2 bundles",22,weight=500)
    f.txt(x,y+102,"Field station · MacBook Air · 8e21b74a",14,f.c["muted"])
    f.txt(x,y+140,"3 of 9 files sent",16,weight=550)
    f.txt(x+width,y+140,"9.6 / 24.0 MB",14,f.c["muted"],anchor="end")
    f.box(x,y+153,width,6,f.c["high"],3)
    f.box(x,y+153,width*.4,6,f.c["primary"],3)
    f.txt(x,y+198,f"{BUNDLE_ID}/videos/{BUNDLE_ID}-v-001.mp4",14)
    f.button("Cancel transfer",x,_footer_y(f),width,"outline")
    return f


def sheets():
    sheet = Sheet("localsend", "LocalSend", "Send selected bundles to a device on the same Wi-Fi network.")
    sheet.row(
        (_discovery("searching", 254), "Discovery",
         "Sending starts when a device is chosen."),
        (_discovery("peers", 386, h=576), "Choose a receiver · cover",
         "The full alias wraps above its model and\nfingerprint. Tap the row to send."),
        (_discovery("timeout", 264, h=576, dark=True), "Discovery timeout · cover",
         "After 12 seconds without a device,\nTry again restarts discovery."),
    )
    sheet.row(
        (_sending("localsend-negotiation", negotiating=True), "Negotiation",
         "Both bundles share one transfer request."),
        (_sending("localsend-progress-inner", w=960, h=720), "File transfer · inner",
         "The bar tracks bytes across both bundles.\nThe path identifies the current file."),
    )
    sheet.row(
        (_outcome("success", "2 bundles sent", [f"{TOTAL_FILES} files · {TOTAL_MB:.1f} MB"],
                  "check", "success", "onSuccess"), "Sent",
         "Done returns to Bundles."),
        (_outcome("already-received", "Already received", ["The receiver already has both bundles."],
                  "info", "high", "muted", dark=True), "Already received",
         "The receiver already has the selection."),
        (_outcome("failure", "Send failed", ["On Field station, tap Done on the previous",
                  "transfer. Then send the bundles again."], "error", "errorBg", "error"),
         "Receiver busy", "The receiver still has a transfer open."),
    )
    sheet.row(
        (_sending("localsend-progress-cover", h=576, dark=True), "Cancel transfer · cover",
         "Cancel transfer stops the send. Dismissing\nthe sheet with Back also stops it."),
        (library_frame("localsend-return", h=576, dark=True, selected=()), "Back in Bundles",
         "Close, Done and Cancel transfer dismiss\nthe sheet and clear the selection."),
    )
    sheet.row((_short_landscape(),"Phone rotated · 800 × 360",
               "Same receiver, current file and byte count. Cancel stays above navigation."),
              (_sending("localsend-near-square", w=960,h=864,dark=True),
               "Near-square inner · 960 × 864","The transfer survives resizing. The modal remains 528dp wide."))
    return [sheet]
