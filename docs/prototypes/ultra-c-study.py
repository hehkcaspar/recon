"""Build the current Fold8 Ultra upright C composition fixture.

This focused sheet is not the complete device matrix. It reuses the archived
A preview/source geometry only so the C composition can be opened alone.
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


def label(parent, x, y, value, size=16, color='#ccc'):
    node = elem(parent, 'text', x=x, y=y, fill=color, **{'font-size': size})
    node.text = value
    return node


def use(parent, symbol, x=None, y=None, w=None, h=None, **attrs):
    w = attrs.pop('width', w)
    h = attrs.pop('height', h)
    return elem(parent, 'use', href='#' + symbol, x=x, y=y, width=w, height=h, **attrs)


def add_arrow(parent, x1, y1, x2, y2, color='#66a9ff', width=2):
    elem(parent, 'path', d=f'M{x1} {y1}L{x2} {y2}', fill='none', stroke=color,
         **{'stroke-width': width, 'stroke-linecap': 'round'})
    dx, dy = x2 - x1, y2 - y1
    length = max((dx * dx + dy * dy) ** 0.5, 1)
    ux, uy = dx / length, dy / length
    px, py = -uy, ux
    size = 9
    points = [(x2, y2),
              (x2 - ux * size + px * size * .55, y2 - uy * size + py * size * .55),
              (x2 - ux * size - px * size * .55, y2 - uy * size - py * size * .55)]
    elem(parent, 'path', d='M' + 'L'.join(f'{x},{y}' for x, y in points) + 'Z',
         fill=color, stroke='none')


def add_double_arrow(parent, x1, y1, x2, y2, color='#66a9ff', width=2):
    add_arrow(parent, x1, y1, x2, y2, color, width)
    add_arrow(parent, x2, y2, x1, y1, color, width)


PROFILE = QUEUE_PROFILES['standard56']
HANDLER_WIDTH = PROFILE['handler_width']
GEOMETRY = C_DEVICES['Fold8 Ultra']['upright']
CAPACITY = derive_queue_capacity(GEOMETRY['queue']['bounds']['photo']['height'], 0, 0)


def vertical_queue(parent, x=None, top=None, lane_span=None):
    """Draw a standard vertical queue with 56dp edge targets and 56dp tiles."""
    queue_bounds = GEOMETRY['queue']['bounds']['photo']
    x = queue_bounds['x'] if x is None else x
    top = queue_bounds['y'] if top is None else top
    lane_span = queue_bounds['height'] if lane_span is None else lane_span
    capacity = derive_queue_capacity(lane_span, 0, 0)
    target = tile = PROFILE['media']
    tile_gap = capacity['gap']
    handle_y = top
    first_tile_y = top + queue_item_start(capacity)
    end = first_tile_y + capacity['visible_count'] * tile + max(0, capacity['visible_count'] - 1) * tile_gap + capacity['endpoint_media_gap']

    # The long-axis handles own the initial drag. Reuse the shared standard
    # handler and rotate it so its visible pill follows the vertical queue axis.
    for y in (handle_y, end):
        elem(parent, 'rect', x=x, y=y, width=target, height=target,
             fill='transparent', **{'data-role': 'tap-target'})
        use(parent, 'shared-queueEdgeHandleStandard', x + (target - HANDLER_WIDTH) / 2,
            y + (target - PROFILE['handler']) / 2, HANDLER_WIDTH, PROFILE['handler'],
            transform=f'rotate(90 {x + target / 2} {y + target / 2})')

    for i, kind in enumerate(visible_symbols(capacity['visible_count'])):
        symbol = 'shared-queueThumb' + kind
        use(parent, symbol, x, first_tile_y + i * (tile + tile_gap), tile, tile)


base = ET.parse(ROOT / 'recon-capture-fold8-ultra-inner.svg').getroot()
out = ET.Element(tag('svg'), width='1400', height='1120', viewBox='0 0 1400 1120')
elem(out, 'title').text = 'Fold8 Ultra upright • C layout sketch'
defs = deepcopy(base.find(tag('defs')))
out.append(defs)
style = base.find(tag('style'))
if style is not None:
    out.append(deepcopy(style))

elem(out, 'rect', width=1400, height=1120, fill='#202020')
label(out, 32, 52, 'Recon / Fold8 Ultra unfolded', 32, '#fff')
label(out, 32, 88, 'C sketch · upright only · rear PHOTO · queue separated from the main control group', 18)
label(out, 32, 116, 'The clean app viewport is 1:1 at 960 × 864. Captions and design notes stay outside it.', 16)

frame = deepcopy(base.findall(tag('g'))[0])
# Keep the original preview, utility strip and modality. C controls are rebuilt
# from the shared source below; the archived horizontal queue is removed.
for child in list(frame):
    href = child.get('href', '')
    if child.get('data-role') == 'queue' or href.startswith(('#shared-zoom', '#shared-iconFlash', '#shared-shutter', '#shared-iconLens')):
        frame.remove(child)

control = GEOMETRY['controlBounds']['photo']
controls = elem(frame, 'g', **{'data-role': 'main-controls'})
use(controls, 'shared-zoomChips', **control['zoom']['rear'])
use(controls, 'shared-iconFlashAuto', **control['aux'])
use(controls, 'shared-shutterPhoto', **control['shutter'])
use(controls, 'shared-iconLens', control['aux']['x'] + 144, control['aux']['y'], 48, 48)

queue = elem(frame, 'g', **{'data-role': 'queue', 'aria-label': 'Vertical media queue'})
vertical_queue(queue)

label(out, 32, 148, 'C / Upright · Rear PHOTO', 19, '#fff')
frame.set('transform', 'translate(32 160)')
out.append(frame)
# Amber corner mark keeps the same physical-pose reading convention as the
# existing inner sheets.
elem(out, 'path', d='M42 156H28v14', fill='none', stroke='#edb96b', **{'stroke-width': 2})

# Gesture arrows use output coordinates: the viewport is translated by (32,160).
# They terminate on the actual target/gap edges rather than on the annotation rail.
add_arrow(out, 876, 315, 876, 340, '#d59a32')       # top handle: discard
add_arrow(out, 876, 810, 876, 768, '#2e9d63')       # bottom handle: save
add_arrow(out, 904, 492, 944, 492, '#66a9ff')       # item: delete outward
add_double_arrow(out, 824, 560, 824, 528, '#66a9ff')  # reorder on queue axis
add_arrow(out, 944, 520, 904, 520, '#66a9ff')       # gap: split/remove

label(out, 1040, 182, 'C 方案试稿', 25, '#fff')
label(out, 1040, 224, '正位右侧 rail', 18, '#fff')
notes = [
    '• modality 顶部保持；',
    '• queue 独立竖排在中间 rail；',
    f'• 本图容量结果：{CAPACITY["visible_count"]} × 56dp standard thumbnails；',
    '• handler 56 × 56dp / visible 36dp；',
    '• zoom 在主控组上方；',
    '• flash / shutter / lens 右下水平居中；',
    '• 上方 handle 下滑丢弃，下方 handle 上滑保存；',
]
for i, note in enumerate(notes):
    label(out, 1040, 274 + i * 30, note, 14)

label(out, 1040, 514, 'Gestures', 20, '#fff')
for i, note in enumerate([
    '↓ discard · ↑ save / commit',
    'item 横向拖动删除；',
    'hold + drag ↕ reorder；',
    'gap 横向 split / remove；',
]):
    label(out, 1040, 548 + i * 28, note, 14)

label(out, 1040, 674, 'Capacity / follow-up', 20, '#fff')
for i, note in enumerate([
    'visible count follows measured lane；',
    '右下主控是否易于单手触达？',
    '纵向 scroll 与 edge action 是否清晰？',
    'standard / compact follows rectangle fit。',
]):
    label(out, 1040, 708 + i * 28, note, 14)

label(out, 1040, 838, 'Geometry', 20, '#fff')
for i, note in enumerate([
    'preview: x80 · 648 × 864',
    'queue: x816 · y180–608 · derived standard56 count',
    'controls: zoom y640–680; shutter y692–772',
    'queue target 56dp · visible handler 36dp',
]):
    label(out, 1040, 872 + i * 28, note, 14)

destination = ROOT / 'recon-capture-fold8-ultra-c-upright.svg'
data = ET.tostring(out, encoding='utf-8') + b'\n'
if '--check' in sys.argv:
    if destination.read_bytes() != data:
        raise SystemExit('Regenerate ' + destination.name)
else:
    destination.write_bytes(data)
