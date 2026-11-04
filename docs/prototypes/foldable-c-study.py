"""Build the complete Fold8 / Fold8 Ultra C-composition study sheets.

The source inner sheets remain the geometry authority for preview, utility
icons and camera capability fixtures. C only reflows the controls and queue:
upright uses a vertical standard queue in the side rail; CW90 uses a
horizontal standard queue in the lower blank band.
"""
from copy import deepcopy
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

from queue_geometry import C_DEVICES, QUEUE_PROFILES, derive_queue_capacity, queue_item_start, visible_symbols

ROOT = Path(__file__).resolve().parent
NS = 'http://www.w3.org/2000/svg'
ET.register_namespace('', NS)


def tag(name):
    return '{' + NS + '}' + name


def elem(parent, name, **attrs):
    return ET.SubElement(parent, tag(name), {k: str(v) for k, v in attrs.items()})


def label(parent, x, y, value, size=16, color='#ccc', weight=None):
    attrs = {'x': x, 'y': y, 'fill': color, 'font-size': size}
    if weight:
        attrs['font-weight'] = weight
    node = elem(parent, 'text', **attrs)
    node.text = value
    return node


def use(parent, symbol, x=None, y=None, w=None, h=None, **attrs):
    w = attrs.pop('width', w)
    h = attrs.pop('height', h)
    return elem(parent, 'use', href='#' + symbol, x=x, y=y, width=w, height=h, **attrs)


CONTROL_PREFIXES = (
    '#shared-zoom', '#shared-iconFlash', '#shared-iconMic',
    '#shared-shutter', '#shared-iconLens',
)


def is_control(child):
    href = child.get('href', '')
    return href.startswith(CONTROL_PREFIXES)


def remove_c_content(frame):
    for child in list(frame):
        if child.get('data-role') == 'queue' or is_control(child):
            frame.remove(child)


PROFILE = QUEUE_PROFILES['standard56']
HANDLER_WIDTH = PROFILE['handler_width']


def visible_queue_symbols(lane_span):
    return [f'shared-queueThumb{kind}' for kind in visible_symbols(
        derive_queue_capacity(lane_span, 0, 0)['visible_count'])
    ]


def vertical_queue(parent, x, top, lane_span):
    capacity = derive_queue_capacity(lane_span, 0, 0)
    target = tile = PROFILE['media']
    gap = capacity['gap']
    first = top + queue_item_start(capacity)
    end = first + capacity['visible_count'] * tile + max(0, capacity['visible_count'] - 1) * gap + capacity['endpoint_media_gap']
    for y in (top, end):
        elem(parent, 'rect', x=x, y=y, width=target, height=target,
             fill='transparent', **{'data-role': 'tap-target'})
        use(parent, 'shared-queueEdgeHandleStandard', x + (target - HANDLER_WIDTH) / 2,
            y + (target - PROFILE['handler']) / 2, HANDLER_WIDTH, PROFILE['handler'],
            transform=f'rotate(90 {x + target / 2} {y + target / 2})')
    for i, symbol in enumerate(visible_queue_symbols(lane_span)):
        use(parent, symbol, x, first + i * (tile + gap), tile, tile)


def horizontal_queue(parent, x, y, lane_span):
    capacity = derive_queue_capacity(lane_span, 0, 0)
    target = tile = PROFILE['media']
    gap = capacity['gap']
    first = x + queue_item_start(capacity)
    for handle_x in (x, x + lane_span - target):
        elem(parent, 'rect', x=handle_x, y=y, width=target, height=target,
             fill='transparent', **{'data-role': 'tap-target'})
        use(parent, 'shared-queueEdgeHandleStandard', handle_x + (target - HANDLER_WIDTH) / 2,
            y + (target - PROFILE['handler']) / 2, HANDLER_WIDTH, PROFILE['handler'])
    for i, symbol in enumerate(visible_queue_symbols(lane_span)):
        use(parent, symbol, first + i * (tile + gap), y, tile, tile)


def state_kind(group_index):
    return {
        0: ('PHOTO', 'rear', 'Photo'),
        2: ('PHOTO', 'front', 'Photo'),
        4: ('VIDEO', 'rear', 'Video'),
        6: ('VIDEO', 'front', 'Video'),
    }[group_index]


def source_group(root, index):
    return deepcopy(root.findall(tag('g'))[index])


def direct_href(frame, prefix):
    for child in frame:
        if child.get('href', '').startswith(prefix):
            return child
    return None


def source_center(frame):
    shutter = direct_href(frame, '#shared-shutter')
    if shutter is None:
        raise ValueError('Missing shutter in source group')
    return float(shutter.get('x')) + float(shutter.get('width')) / 2


def add_controls(frame, device_name, kind, camera, pose):
    controls = elem(frame, 'g', **{'data-role': 'main-controls'})
    geometry = C_DEVICES[device_name][pose]['controlBounds'][kind.lower()]
    zoom = geometry['zoom'][camera]
    aux = geometry['aux']
    shutter = geometry['shutter']
    if camera == 'front':
        use(controls, 'shared-zoomSingle', **zoom)
    else:
        use(controls, 'shared-zoomChips', **zoom)

    if kind == 'VIDEO':
        use(controls, 'shared-iconMic', **aux)
        shutter_symbol = 'shared-shutterVideoIdle'
    else:
        use(controls, 'shared-iconFlashOff' if camera == 'front' else 'shared-iconFlashAuto',
            **aux,
            **({'opacity': '.38'} if camera == 'front' else {}))
        shutter_symbol = 'shared-shutterPhoto'
    use(controls, shutter_symbol, **shutter)
    use(controls, 'shared-iconLens', aux['x'] + 144, aux['y'], 48, 48)


def build_frame(base_root, index, pose, ultra):
    frame = source_group(base_root, index + (0 if pose == 'upright' else 1))
    remove_c_content(frame)

    kind, camera, _ = state_kind(index)
    device_name = 'Fold8 Ultra' if ultra else 'Fold8'
    pose_geometry = C_DEVICES[device_name][pose]
    queue_geometry = pose_geometry['queue']['bounds'][kind.lower()]

    # The CW90 top strip is centered in the 80dp black band. The upright
    # side-rail mode row is already emitted by the shared responsive generator.
    if pose == 'cw90':
        mode = direct_href(frame, '#shared-modeStrip')
        if mode is not None:
            mode.set('y', '20')

    add_controls(frame, device_name, kind, camera, pose)
    queue = elem(frame, 'g', **{
        'data-role': 'queue',
        'aria-label': 'Vertical media queue' if pose == 'upright' else 'Horizontal media queue',
    })
    if pose == 'upright':
        vertical_queue(queue, queue_geometry['x'], queue_geometry['y'], queue_geometry['height'])
    else:
        horizontal_queue(queue, queue_geometry['x'], queue_geometry['y'], queue_geometry['width'])
    return frame


def add_frame_caption(out, x, y, text):
    label(out, x, y, text, 17, '#fff', '600')


def build_sheet(ultra):
    source_name = 'recon-capture-fold8-ultra-inner.svg' if ultra else 'recon-capture-fold8-inner.svg'
    out_name = 'recon-capture-fold8-ultra-c.svg' if ultra else 'recon-capture-fold8-c.svg'
    base = ET.parse(ROOT / source_name).getroot()
    page_width = 2040
    page_height = 4554
    out = ET.Element(tag('svg'), width=str(page_width), height=str(page_height),
                     viewBox=f'0 0 {page_width} {page_height}')
    elem(out, 'title').text = 'Fold8 Ultra C composition matrix' if ultra else 'Fold8 C composition matrix'
    desc = elem(out, 'desc')
    desc.text = ('Complete C study for rear/front PHOTO and VIDEO in upright and CW90 poses. '
                 'Preview geometry is inherited from the corresponding inner fixture; controls and queue are reflowed.')
    defs = deepcopy(base.find(tag('defs')))
    out.append(defs)
    style = base.find(tag('style'))
    if style is not None:
        out.append(deepcopy(style))

    elem(out, 'rect', width=page_width, height=page_height, fill='#202020')
    product = 'Fold8 Ultra' if ultra else 'Fold8'
    label(out, 32, 48, f'Recon / {product} unfolded · C composition', 32, '#fff')
    label(out, 32, 82, 'Complete PHOTO/VIDEO · rear/front · upright/CW90 matrix', 18)
    label(out, 32, 112, 'Preview geometry is preserved from the source inner fixture. C reflows only capture controls and queue.', 16)

    left_x = 32
    right_x = 1104 if ultra else 1176
    row_y = 210
    row_step = 1094
    for row, index in enumerate((0, 2, 4, 6)):
        kind, camera, _ = state_kind(index)
        caption = f'{kind} · {"rear" if camera == "rear" else "front"}'
        add_frame_caption(out, left_x, row_y - 16, f'{caption} · C upright')
        add_frame_caption(out, right_x, row_y - 16, f'{caption} · C CW90')

        upright = build_frame(base, index, 'upright', ultra)
        upright.set('transform', f'translate({left_x} {row_y})')
        out.append(upright)
        cw90 = build_frame(base, index, 'cw90', ultra)
        cw90.set('transform', f'translate({right_x} {row_y})')
        out.append(cw90)

        # Amber corner marks sit outside app viewports and preserve the pose
        # reading convention used by the primary inner sheets.
        elem(out, 'path', d=f'M{left_x + 10} {row_y - 4}H{left_x - 4}v14',
             fill='none', stroke='#edb96b', **{'stroke-width': 2})
        elem(out, 'path', d=f'M{right_x + 10} {row_y - 4}H{right_x - 4}v14',
             fill='none', stroke='#edb96b', **{'stroke-width': 2})
        row_y += row_step

    label(out, 32, 4508, 'C tokens: 56dp media + 56dp edge targets + 6×36dp standard handlers · zoom-to-shutter gap 12dp · queue axis follows pose.', 15)
    destination = ROOT / out_name
    data = ET.tostring(out, encoding='utf-8') + b'\n'
    if '--check' in sys.argv:
        if destination.read_bytes() != data:
            raise SystemExit('Regenerate ' + destination.name)
    else:
        destination.write_bytes(data)
    return destination


def add_arrow(parent, x1, y1, x2, y2, color='#66a9ff', width=2):
    elem(parent, 'path', d=f'M{x1} {y1}L{x2} {y2}', fill='none', stroke=color,
         **{'stroke-width': width})
    dx, dy = x2 - x1, y2 - y1
    length = max((dx * dx + dy * dy) ** 0.5, 1)
    ux, uy = dx / length, dy / length
    px, py = -uy, ux
    size = 9
    points = [
        (x2, y2),
        (x2 - ux * size + px * size * .55, y2 - uy * size + py * size * .55),
        (x2 - ux * size - px * size * .55, y2 - uy * size - py * size * .55),
    ]
    elem(parent, 'path', d='M' + 'L'.join(f'{x},{y}' for x, y in points) + 'Z',
         fill=color, stroke='none')


def add_double_arrow(parent, x1, y1, x2, y2, color='#66a9ff', width=2):
    add_arrow(parent, x1, y1, x2, y2, color, width)
    add_arrow(parent, x2, y2, x1, y1, color, width)


def guide_queue(parent, x, y, vertical, lane_span):
    capacity = derive_queue_capacity(lane_span, 0, 0)
    target = tile = PROFILE['media']
    gap = capacity['gap']
    if vertical:
        first = y + queue_item_start(capacity)
        end = first + capacity['visible_count'] * tile + max(0, capacity['visible_count'] - 1) * gap + capacity['endpoint_media_gap']
        for i, symbol in enumerate(visible_queue_symbols(lane_span)):
            use(parent, symbol, x, first + (tile + gap) * i, tile, tile)
        elem(parent, 'rect', x=x, y=y, width=target, height=target,
             fill='none', stroke='#888', **{'stroke-width': 1, 'stroke-dasharray': '4 4'})
        elem(parent, 'rect', x=x, y=end, width=target, height=target,
             fill='none', stroke='#888', **{'stroke-width': 1, 'stroke-dasharray': '4 4'})
        use(parent, 'shared-queueEdgeHandleStandard', x + (target - HANDLER_WIDTH) / 2,
            y + (target - PROFILE['handler']) / 2, HANDLER_WIDTH, PROFILE['handler'],
            transform=f'rotate(90 {x + target / 2} {y + target / 2})')
        use(parent, 'shared-queueEdgeHandleStandard', x + (target - HANDLER_WIDTH) / 2,
            end + (target - PROFILE['handler']) / 2, HANDLER_WIDTH, PROFILE['handler'],
            transform=f'rotate(90 {x + target / 2} {end + target / 2})')
    else:
        first = x + queue_item_start(capacity)
        for i, symbol in enumerate(visible_queue_symbols(lane_span)):
            use(parent, symbol, first + (tile + gap) * i, y, tile, tile)
        elem(parent, 'rect', x=x, y=y, width=target, height=target,
             fill='none', stroke='#888', **{'stroke-width': 1, 'stroke-dasharray': '4 4'})
        end = x + lane_span - target
        elem(parent, 'rect', x=end, y=y, width=target, height=target,
             fill='none', stroke='#888', **{'stroke-width': 1, 'stroke-dasharray': '4 4'})
        use(parent, 'shared-queueEdgeHandleStandard', x + (target - HANDLER_WIDTH) / 2,
            y + (target - PROFILE['handler']) / 2, HANDLER_WIDTH, PROFILE['handler'])
        use(parent, 'shared-queueEdgeHandleStandard', end + (target - HANDLER_WIDTH) / 2,
            y + (target - PROFILE['handler']) / 2, HANDLER_WIDTH, PROFILE['handler'])


def screen_schematic(parent, x, y, scale, pose):
    """Draw a scaled Fold8 C viewport with the actual local coordinates."""
    geometry = C_DEVICES['Fold8'][pose]
    preview = geometry['previews']['photo']
    queue_bounds = geometry['queue']['bounds']['photo']
    controls = geometry['controlBounds']['photo']
    width, height = geometry['viewport']['width'], geometry['viewport']['height']
    screen = elem(parent, 'g', transform=f'translate({x} {y}) scale({scale})',
                  **{'data-role': 'screen-schematic'})
    elem(screen, 'rect', width=width, height=height, fill='#080808', stroke='#888',
         **{'stroke-width': 2})
    if pose == 'upright':
        use(screen, 'shared-scenePortraitRear', **preview,
            **{'data-role': 'preview'})
        use(screen, 'shared-iconSettings', 16, 656, 48, 48)
        use(screen, 'shared-iconLibrary', 16, 16, 48, 48)
        use(screen, 'shared-modeStripPhoto', 665, 28, 250, 40)
        use(screen, 'shared-zoomChips', **controls['zoom']['rear'])
        use(screen, 'shared-iconFlashAuto', **controls['aux'])
        use(screen, 'shared-shutterPhoto', **controls['shutter'])
        use(screen, 'shared-iconLens', controls['aux']['x'] + 144, controls['aux']['y'], 48, 48)
        queue = elem(screen, 'g', **{'data-role': 'queue'})
        guide_queue(queue, queue_bounds['x'], queue_bounds['y'], True, queue_bounds['height'])
    else:
        use(screen, 'shared-sceneLandscapeRear', **preview,
            **{'data-role': 'preview'})
        use(screen, 'shared-iconSettings', 16, 16, 48, 48)
        use(screen, 'shared-iconLibrary', 656, 16, 48, 48)
        use(screen, 'shared-modeStripPhoto', 235, 20, 250, 40)
        use(screen, 'shared-zoomChips', **controls['zoom']['rear'])
        use(screen, 'shared-iconFlashAuto', **controls['aux'])
        use(screen, 'shared-shutterPhoto', **controls['shutter'])
        use(screen, 'shared-iconLens', controls['aux']['x'] + 144, controls['aux']['y'], 48, 48)
        queue = elem(screen, 'g', **{'data-role': 'queue'})
        guide_queue(queue, queue_bounds['x'], queue_bounds['y'], False, queue_bounds['width'])
    return screen


def build_gesture_guide():
    base = ET.parse(ROOT / 'recon-capture-fold8-ultra-inner.svg').getroot()
    out = ET.Element(tag('svg'), width='1400', height='1640', viewBox='0 0 1400 1640')
    elem(out, 'title').text = 'Fold8 C composition gesture guide · rear PHOTO exemplar'
    elem(out, 'desc').text = ('Fold8 reference coordinates for the C queue axis in upright and clockwise-90 poses. '
                              'Use the Fold8 Ultra C matrix for its different viewport bounds.')
    defs = deepcopy(base.find(tag('defs')))
    out.append(defs)
    style = base.find(tag('style'))
    if style is not None:
        out.append(deepcopy(style))
    elem(out, 'rect', width=1400, height=1640, fill='#202020')
    label(out, 32, 50, 'Fold8 C · gesture operation guide · rear PHOTO exemplar', 32, '#fff')
    label(out, 32, 84, 'Reference coordinates are shown inside the screen; gesture labels stay outside the app viewport.', 18)
    label(out, 32, 108, 'Fold8 Ultra keeps the same C semantics, but its actual bounds are documented in the Ultra C matrix.', 14, '#aaa')

    panels = [(32, 'Upright · vertical queue · rear PHOTO exemplar'),
              (720, 'CW90 · horizontal queue · rear PHOTO exemplar')]
    for px, title in panels:
        elem(out, 'rect', x=px, y=128, width=648, height=660, rx=12, fill='#161616', stroke='#555')
        label(out, px + 24, 166, title, 22, '#fff', '600')

    # These are scaled but coordinate-faithful Fold8 app viewports, not abstract
    # previews. The queue and main-control positions match the C matrix locals.
    screen_schematic(out, 92, 190, .55, 'upright')
    # Rich gesture callouts are anchored to the scaled queue, not to the panel
    # edge, so the operation labels remain tied to the actual controls.
    add_arrow(out, 526, 220, 526, 241, '#d59a32')
    add_arrow(out, 526, 500, 526, 476, '#2e9d63')
    add_arrow(out, 542, 326, 576, 326, '#66a9ff')
    add_double_arrow(out, 505, 398, 505, 364, '#66a9ff')
    add_arrow(out, 570, 342, 542, 342, '#66a9ff')
    label(out, 56, 608, 'Gestures: ↓ discard · ↑ save/commit · item → delete · hold+drag ↕ reorder · gap ↔ split/remove', 12, '#c9e3ff')
    label(out, 56, 634, 'Screen 960 × 720 · schematic scale 0.55', 14, '#fff', '600')
    upright = C_DEVICES['Fold8']['upright']
    cw90 = C_DEVICES['Fold8']['cw90']
    upright_preview = upright['previews']['photo']
    upright_queue = upright['queue']['bounds']['photo']
    upright_control = upright['controlBounds']['photo']
    cw90_preview = cw90['previews']['photo']
    cw90_queue = cw90['queue']['bounds']['photo']
    cw90_control = cw90['controlBounds']['photo']
    label(out, 56, 658, f"Preview x{upright_preview['x']}–{upright_preview['x'] + upright_preview['width']} · queue x{upright_queue['x']}–{upright_queue['x'] + upright_queue['width']}, y{upright_queue['y']}–{upright_queue['y'] + upright_queue['height']} · main-control center x{upright_control['shutter']['x'] + upright_control['shutter']['width'] / 2:g}", 14)
    label(out, 56, 682, 'Queue axis vertical; top/bottom handle directions follow the queue axis.', 14, '#f0bf67')
    label(out, 56, 706, f"Queue ends {upright_control['shutter']['y'] - (upright_queue['y'] + upright_queue['height']):g}dp above shutter top; queue centerline and control centerline share x{upright_control['shutter']['x'] + upright_control['shutter']['width'] / 2:g}.", 14, '#aaa')
    video_preview = upright['previews']['video']
    video_queue = upright['queue']['bounds']['video']
    video_control = upright['controlBounds']['video']
    label(out, 56, 730, f"VIDEO upright differs: preview x{video_preview['x']}–{video_preview['x'] + video_preview['width']}, queue x{video_queue['x']}–{video_queue['x'] + video_queue['width']}, shutter x{video_control['shutter']['x']}–{video_control['shutter']['x'] + video_control['shutter']['width']}, center x{video_control['shutter']['x'] + video_control['shutter']['width'] / 2:g}.", 12, '#aaa')

    screen_schematic(out, 864, 190, .5, 'cw90')
    add_arrow(out, 846, 614, 881, 614, '#2e9d63')
    add_arrow(out, 1128, 614, 1095, 614, '#d59a32')
    add_arrow(out, 988, 628, 988, 650, '#66a9ff')
    add_double_arrow(out, 900, 590, 1064, 590, '#66a9ff')
    add_arrow(out, 1003, 646, 1003, 628, '#66a9ff')
    label(out, 744, 684, 'Gestures: left → save/commit · right ← discard · item ↓ delete · hold+drag ↔ reorder · gap ↕ split/remove', 12, '#c9e3ff')
    label(out, 744, 708, 'Screen 720 × 960 · schematic scale 0.50', 14, '#fff', '600')
    label(out, 744, 732, f"Preview x{cw90_preview['x']}–{cw90_preview['x'] + cw90_preview['width']}, y{cw90_preview['y']}–{cw90_preview['y'] + cw90_preview['height']} · queue x{cw90_queue['x']}–{cw90_queue['x'] + cw90_queue['width']}, y{cw90_queue['y']}–{cw90_queue['y'] + cw90_queue['height']} · centers share y{cw90_queue['y'] + cw90_queue['height'] / 2:g}", 14)
    label(out, 744, 756, f"Queue→zoom/flash start = {cw90_control['zoom']['rear']['x'] - (cw90_queue['x'] + cw90_queue['width']):g}dp · queue→shutter left = {cw90_control['shutter']['x'] - (cw90_queue['x'] + cw90_queue['width']):g}dp", 14, '#aaa')

    label(out, 32, 812, 'Common preview and control gestures', 24, '#fff', '600')
    common = [
        ('Preview', 'Tap = focus marker · pinch = zoom · horizontal swipe = one modality step.'),
        ('Mode row', 'Tap VIDEO / PHOTO / VOICE directly; labels remain horizontal in both poses.'),
        ('Queue ownership', 'A handle owns the gesture from initial contact; media below a handle cannot steal it.'),
        ('Mutation gate', 'Commit/discard requires 96dp net inward travel and intentional release; velocity alone never mutates.'),
        ('Cancel', 'Reverse below the gate, add a pointer, leave the safe corridor, interrupt lifecycle or lose ownership = restore.'),
        ('Recording', 'Queue mutation and mode/library/settings actions lock; zoom and Stop remain available.'),
        ('Accessibility', 'Expose Save queue, Discard queue, Remove item, Move earlier/later and Split/Remove split as actions.'),
    ]
    for i, (title, body) in enumerate(common):
        y = 858 + i * 46
        label(out, 48, y, title, 16, '#fff', '600')
        label(out, 210, y, body, 16)

    label(out, 32, 1248, 'C design rule', 22, '#fff', '600')
    label(out, 32, 1286, 'Stable preview · separated capture · reachable queue.', 20, '#fff')
    label(out, 32, 1324, 'The queue changes axis with the pose, not its 56dp media scale, 56dp edge targets,', 16)
    label(out, 32, 1352, '6 × 36dp standard handlers, chronological order or semantic actions.', 16)
    label(out, 32, 1412, 'This is a static design guide; runtime pointer arbitration and device reach remain validation work.', 14, '#aaa')

    destination = ROOT / 'recon-capture-foldable-c-gesture-guide.svg'
    data = ET.tostring(out, encoding='utf-8') + b'\n'
    if '--check' in sys.argv:
        if destination.read_bytes() != data:
            raise SystemExit('Regenerate ' + destination.name)
    else:
        destination.write_bytes(data)
    return destination


if __name__ == '__main__':
    build_sheet(False)
    build_sheet(True)
    build_gesture_guide()
