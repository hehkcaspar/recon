"""Setup and immediate-apply Settings fixtures for the Recon Android reference set."""

from ui import Frame, Sheet, switch


CAMERA_MODES = ("ZSL (zero shutter lag)", "EXT (HDR/night auto)")
QUALITIES = ("Low", "Standard", "High")
DELAYS = ("Off", *(f"{seconds}s" for seconds in range(1, 11)))
LONG_FOLDER = ("North Shore field recordings", "November survey")
PROVIDER = "content://com.android.externalstorage.documents"
LONG_TREE = ("/tree/primary%3ADocuments%2FRecon%2FNorth",
             "%20Shore%20field%20recordings%20November%20survey")


def _lines(f, x, baseline, lines, size, step, color=None, weight=400, anchor="start"):
    for i, line in enumerate(lines):
        f.txt(x, baseline + i * step, line, size, color, weight, anchor)


def _setup_frame(state, h=800, dark=False):
    f = Frame("setup-" + state, h=h, dark=dark)
    c = f.c
    camera = state in ("camera", "denied", "blocked")
    if camera:
        f.button("Recon settings", f.w - 136, 32, 120, "text")

    if state == "lost":
        heading = ("Reconnect your folder",)
        message = ("Recon needs access to your output", "folder before you can capture again.")
        action = "Choose folder"
    elif state == "blocked":
        heading = ("Allow camera access", "in Android Settings")
        message = ("Open Android Settings and select", "Apps › Recon › Permissions › Camera.",
                   "Allow access, then return to Recon.")
        action = None
    elif camera:
        heading = ("Camera access is required",) if state == "camera" else ("Camera access was denied",)
        message = ("Allow Recon to use the camera", "to capture photos and videos.")
        action = "Grant camera permission"
    else:
        heading = ("Choose an output folder",)
        message = ("Recon saves your bundles in this folder.",
                   "You can change it later in Recon settings.")
        action = "Choose folder"

    # One bounded, centered group. Keep the app-settings action above the group.
    center = f.w / 2
    block_height = 56 + 24 + 32 * len(heading) + 12 + 22 * len(message)
    if action:
        block_height += 24 + 48
    if state == "lost":
        block_height += 96
    top = 88 if camera else 24
    y = top + (f.h - f.bottom - top - block_height) // 2
    f.box(center - 28, y, 56, 56, c["tonal"], 18)
    glyph = "camera" if camera else "folder_open"
    f.icon(glyph, center - 14, y + 14, 28, c["onTonal"])
    y += 80
    _lines(f, center, y + 25, heading, 24, 32, weight=500, anchor="middle")
    y += len(heading) * 32 + 12
    _lines(f, center, y + 16, message, 16, 22, c["muted"], anchor="middle")
    y += len(message) * 22
    if state == "lost":
        f.box(24, y + 16, f.w - 48, 76, c["surface"], 12)
        f.icon("folder", 40, y + 42, 24, c["muted"])
        f.txt(80, y + 37, "Previous folder", 12, c["muted"], 500)
        _lines(f, 80, y + 58, LONG_FOLDER, 15, 19)
        y += 96
    if action:
        width = 216 if camera else 160
        f.button(action, center - width / 2, y + 24, width)
    return f


def _menu(f, row_top, row_height, right, choices, value, width):
    # Material popups keep the list visible within the system insets and take focus.
    f.clear_hits()
    height = 16 + 48 * len(choices)
    edge = f.h - f.bottom - 8
    y = row_top + row_height
    if y + height > edge:
        y = max(32, min(edge - height, row_top - height))
    x = right - width
    c = f.c
    f.box(x, y + 2, width, height, c["line"], 6)
    f.box(x, y, width, height, c["high"], 6)
    for i, option in enumerate(choices):
        top = y + 8 + i * 48
        if option == value:
            f.box(x + 4, top, width - 8, 48, c["tonal"], 4)
            f.icon("check", right - 36, top + 12, 24, c["onTonal"])
        f.txt(x + 16, top + 30, option, 16, c["onTonal"] if option == value else c["ink"])
        f.hit(option, x, top, width, 48)


def _settings_frame(name, *, w=360, h=800, dark=False, long_folder=False,
                    individual=True, stitched=True, scroll=None, menu=None, bottom=24):
    f = Frame("settings-" + name, w=w, h=h, dark=dark, bottom=bottom)
    c = f.c
    f.bar("Settings")
    left = max(16, (w - 560) // 2)
    right = w - left
    top, edge = 88, h - bottom
    locked = individual != stitched

    # Natural list order and row heights are shared by all scroll and popup states.
    sizes = dict(folder=156 if long_folder else 116, output=40, individual=56,
                 stitched=56, quality=52, lock_note=36 if locked else 0,
                 capture=44, sound=56, mode=64, deletion=44, confirm=56, undo=64,
                 about=44, tutorial=56, version=56)
    offsets = {}
    cursor = 0
    for key, height in sizes.items():
        offsets[key] = cursor
        cursor += height
    max_scroll = max(0, cursor + 24 - (edge - top))
    offset = max_scroll if scroll == "bottom" else min(max_scroll, offsets.get(scroll, 0))
    pos = {key: top + 8 + y - offset for key, y in offsets.items()}

    def visible(key):
        return pos[key] < edge and pos[key] + sizes[key] > top

    def hit(key, label):
        if pos[key] >= top and pos[key] + sizes[key] <= edge:
            f.hit(label, left, pos[key], right - left, sizes[key])

    def button(label, x, y, width, kind):
        first = len(f.targets)
        f.button(label, x, y, width, kind)
        if y < top or y + 48 > edge:
            del f.targets[first:]

    def section(key, title):
        if visible(key):
            y = pos[key]
            f.box(left, y + 4, right - left, 1, c["line"])
            f.txt(left, y + 32, title, 14, c["primary"], 600)

    def toggle(key, labels, checked, disabled=False):
        if visible(key):
            mid = pos[key] + sizes[key] / 2
            _lines(f, left, mid + 6 - 10 * (len(labels) - 1), labels, 16, 20)
            switch(f, right, mid, checked, disabled)
            if not disabled:
                hit(key, " ".join(labels) + (", on" if checked else ", off"))

    def choice(key, labels, value, disabled=False):
        if visible(key):
            mid = pos[key] + sizes[key] / 2
            inset = 16 if key == "quality" else 0
            color = c["muted"] if disabled else c["primary"]
            _lines(f, left + inset, mid + 5 - 10 * (len(labels) - 1), labels,
                   14 if inset else 16, 20, c["muted"] if disabled else c["ink"])
            f.txt(right - 28, mid + 5, value, 14, color, 500, "end")
            f.icon("expand_more", right - 24, mid - 12, 24, color)
            if not disabled:
                hit(key, " ".join(labels) + ", " + value)

    # Clip only the scrollable column. The app bar, popup and system chrome stay fixed.
    clip_id = "list-clip-" + name
    f.body.append(f'<defs><clipPath id="{clip_id}"><rect x="0" y="{top}" '
                  f'width="{w}" height="{edge-top}"/></clipPath></defs><g clip-path="url(#{clip_id})">')
    if visible("folder"):
        y = pos["folder"]
        f.txt(left, y + 24, "Output folder", 14, c["primary"], 600)
        folder_name = LONG_FOLDER if long_folder else ("Recon",)
        _lines(f, left, y + 51, folder_name, 16, 21, weight=500)
        button("Change", right - 88, y + 8, 88, "outline")
        path = (PROVIDER, *LONG_TREE) if long_folder else (PROVIDER, "/tree/primary%3ADocuments%2FRecon")
        _lines(f, left, y + (97 if long_folder else 76), path, 12, 17, c["muted"])

    section("output", "Bundle output")
    toggle("individual", ("Individual files in subfolder",), individual, individual and not stitched)
    toggle("stitched", ("Vertical stitched image",), stitched, stitched and not individual)
    choice("quality", ("Stitch quality",), "Standard", disabled=not stitched)
    if locked and visible("lock_note"):
        f.icon("lock", left, pos["lock_note"] + 6, 16, c["muted"])
        f.txt(left + 24, pos["lock_note"] + 20, "At least one output is required.", 14, c["muted"])

    section("capture", "Capture")
    toggle("sound", ("Shutter sound",), True)
    choice("mode", ("Camera mode",), CAMERA_MODES[0])
    section("deletion", "Bundle deletion")
    confirm = ("Confirm before deleting a bundle",) if w > 500 else ("Confirm before deleting", "a bundle")
    undo = ("Undo window for bundle deletion",) if w > 500 else ("Undo window for", "bundle deletion")
    toggle("confirm", confirm, True)
    choice("undo", undo, "5s")
    section("about", "Help and information")
    if visible("tutorial"):
        y = pos["tutorial"]
        f.txt(left, y + 34, "Gesture tutorial", 16)
        button("Show", right - 72, y + 4, 72, "text")
    if visible("version"):
        y = pos["version"]
        f.txt(left, y + 34, "App version", 16)
        f.txt(right, y + 34, "5.3", 14, c["muted"], anchor="end")
    f.body.append("</g>")

    if menu:
        options, selected, width = {
            "quality": (QUALITIES, "Standard", 168),
            "mode": (CAMERA_MODES, CAMERA_MODES[0], 232),
            "undo": (DELAYS, "5s", 128),
        }[menu]
        _menu(f, pos[menu], sizes[menu], right, options, selected, width)
    return f


def _large_text():
    # A scrolled 320dp window at 2×. Reflow the dependent choice below its label.
    f = Frame("settings-small-2x", w=320, h=576, bottom=48)
    c = f.c
    f.iconbutton("back", "Back", 4, 32)
    f.txt(64, 73, "Settings", 44)
    f.txt(16, 129, "Bundle output", 28, c["primary"], 600)
    for y, labels in ((144, ("Individual files", "in subfolder")),
                      (248, ("Vertical", "stitched image"))):
        _lines(f, 16, y + 39, labels, 32, 38)
        switch(f, 304, y + 52, True)
        f.hit(" ".join(labels) + ", on", 16, y, 288, 104)
    f.txt(32, 394, "Stitch quality", 28)
    f.txt(32, 441, "Standard", 28, c["primary"], 500)
    f.icon("expand_more", 278, 417, 24, c["primary"])
    f.hit("Stitch quality, Standard", 32, 352, 272, 108)
    f.box(16, 476, 288, 1, c["line"])
    f.txt(16, 516, "Capture", 28, c["primary"], 600)
    return f


def sheets():
    setup = Sheet("setup", "Setup",
                  "Choose where bundles are saved, then allow camera access to start capturing.")
    setup.row(
        (_setup_frame("first"), "First launch",
         "In Android’s picker, choose Use this folder,\nthen allow Recon access."),
        (_setup_frame("cancelled", h=576), "Picker cancelled",
         "Back in the picker returns here.\nChoose folder starts the picker again."),
        (_setup_frame("lost", h=576, dark=True), "Folder access lost",
         "After a grant, Recon should recheck persisted\nread/write access, even for the same folder."),
    )
    setup.row(
        (_setup_frame("camera", dark=True), "Camera permission",
         "After folder setup, Android asks for camera\naccess. Allowing it opens capture."),
        (_setup_frame("denied", h=576, dark=True), "Permission denied",
         "Grant camera permission retries the request.\nRecon settings opens the app’s preferences."),
        (_setup_frame("blocked", h=576, dark=True), "Android stops prompting",
         "On return from Android Settings, Recon should\nrecheck Camera access before entering capture."),
    )

    settings = Sheet("settings", "Settings",
                     "Changes apply immediately. Back returns to capture, or closes an open menu first.")
    settings.row(
        (_settings_frame("phone"), "Phone",
         "Change opens the Android folder picker.\nCancelling preserves the current folder."),
        (_settings_frame("cover-folder", h=576, long_folder=True), "Cover · folder changed",
         "After the grant, Settings shows the updated\nfolder name and full tree URI."),
        (_settings_frame("cover-scrolled", h=576, long_folder=True, scroll="bottom"), "Cover · scrolled",
         "The app bar stays fixed. Show returns to\ncapture and replays the gesture tutorial."),
    )
    settings.row(
        (_settings_frame("individual-only", h=576, dark=True, stitched=False), "Individual files only",
         "The last enabled output is locked. Turn on\nstitching to change the quality."),
        (_settings_frame("stitched-only", h=576, dark=True, individual=False), "Stitched image only",
         "The stitched output is locked. Individual\nfiles and stitch quality remain editable."),
    )
    settings.row(
        (_settings_frame("quality-menu", menu="quality"), "Stitch quality",
         "The check marks the current quality.\nSelect a value to apply it and close."),
        (_settings_frame("mode-menu", h=576, dark=True, scroll="capture", menu="mode"), "Camera mode",
         "The camera applies the selected mode\nwhen you return to capture."),
        (_settings_frame("undo-menu", menu="undo"), "Deletion Undo window",
         "Off skips Undo after any confirmation.\nChoose 1 to 10 seconds to allow recovery."),
    )
    settings.row(
        (_settings_frame("inner-wide", w=960, h=720, dark=True), "Expanded · 960 × 720",
         "Preferences share a centered 560dp column.\nScroll for deletion, tutorial and version."),
        (_large_text(), "Small window · 2× text",
         "Labels wrap and the value moves below.\nScroll to reach the remaining preferences."),
    )
    return [setup, settings]
