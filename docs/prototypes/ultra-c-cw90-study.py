"""Build the Fold8 Ultra upright-C continuation in the CW90 pose."""
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
GEOMETRY = C_DEVICES['Fold8 Ultra']['cw90']
CAPACITY = derive_queue_capacity(GEOMETRY['queue']['bounds']['photo']['width'], 0, 0)


def horizontal_queue(parent, x=None, y=None, lane_span=None):
    """Standard queue in the lower blank band, left of controls."""
    queue_bounds = GEOMETRY['queue']['bounds']['photo']
    x = queue_bounds['x'] if x is None else x
    y = queue_bounds['y'] if y is None else y
    lane_span = queue_bounds['width'] if lane_span is None else lane_span
    capacity = derive_queue_capacity(lane_span, 0, 0)
    target = tile = PROFILE['media']
    gap = capacity['gap']
    first = x + queue_item_start(capacity)

    handle_positions = (x, x + lane_span - target)
    for handle_x in handle_positions:
        elem(parent, 'rect', x=handle_x, y=y, width=target, height=target,
             fill='transparent', **{'data-role': 'tap-target'})
        use(parent, 'shared-queueEdgeHandleStandard', handle_x + (target - HANDLER_WIDTH) / 2,
            y + (target - PROFILE['handler']) / 2, HANDLER_WIDTH, PROFILE['handler'])

    for i, kind in enumerate(visible_symbols(capacity['visible_count'])):
        use(parent, 'shared-queueThumb' + kind, first + i * (tile + gap), y, tile, tile)


base = ET.parse(ROOT / 'recon-capture-fold8-ultra-inner.svg').getroot()
out = ET.Element(tag('svg'), width='1400', height='1120', viewBox='0 0 1400 1120')
elem(out, 'title').text = 'Fold8 Ultra CW90 • C layout sketch'
defs = deepcopy(base.find(tag('defs')))
out.append(defs)
style = base.find(tag('style'))
if style is not None:
    out.append(deepcopy(style))

elem(out, 'rect', width=1400, height=1120, fill='#202020')
label(out, 32, 52, 'Recon / Fold8 Ultra unfolded', 32, '#fff')
label(out, 32, 88, 'C sketch · clockwise 90° · rear PHOTO · bottom queue left / main controls lower-right', 18)
label(out, 32, 116, 'The clean app viewport is 1:1 at 864 × 960. Captions and design notes stay outside it.', 16)

frame = deepcopy(base.findall(tag('g'))[1])
for child in list(frame):
    href = child.get('href', '')
    if child.get('data-role') == 'queue' or href in {
        '#shared-zoomChips', '#shared-iconFlashAuto', '#shared-shutterDensePhoto', '#shared-iconLens'
    }:
        frame.remove(child)
    elif child.get('data-role') == 'preview':
        child.set('x', '0')
        child.set('y', '80')
        child.set('width', '864')
        child.set('height', '648')
    elif child.get('href') == '#shared-modeStripPhoto':
        child.set('y', '20')

controls = elem(frame, 'g', **{'data-role': 'main-controls'})
control = GEOMETRY['controlBounds']['photo']
use(controls, 'shared-zoomChips', **control['zoom']['rear'])
use(controls, 'shared-iconFlashAuto', **control['aux'])
use(controls, 'shared-shutterPhoto', **control['shutter'])
use(controls, 'shared-iconLens', control['aux']['x'] + 144, control['aux']['y'], 48, 48)

queue = elem(frame, 'g', **{'data-role': 'queue', 'aria-label': 'Horizontal media queue'})
horizontal_queue(queue)

label(out, 32, 148, 'C / Clockwise 90° · Rear PHOTO', 19, '#fff')
frame.set('transform', 'translate(32 160)')
out.append(frame)
elem(out, 'path', d='M882 156H896v14', fill='none', stroke='#edb96b', **{'stroke-width': 2})

# Gesture arrows use output coordinates: the viewport is translated by (32,160).
# Queue and control bounds remain the Ultra CW90 values shown in the notes.
add_arrow(out, 80, 1008, 116, 1008, '#2e9d63')       # left handle: save
add_arrow(out, 580, 1008, 544, 1008, '#d59a32')      # right handle: discard
add_arrow(out, 330, 1036, 330, 1058, '#66a9ff')      # item: delete downward
add_double_arrow(out, 212, 950, 494, 950, '#66a9ff') # reorder on queue axis
add_arrow(out, 421, 1060, 421, 1036, '#66a9ff')      # gap: split/remove

label(out, 1040, 182, 'C CW90 试稿', 25, '#fff')
label(out, 1040, 224, '底部左侧 queue / 右下主控', 18, '#fff')
notes = [
    '• queue 在预览下方的黑色空白区；',
    f'• 靠左，本图容量结果为 {CAPACITY["visible_count"]} × 56dp standard thumbnails；',
    '• handler 56 × 56dp / visible 36dp；',
    '• 主控靠右，位于同一底部横向带；',
    '• queue → zoom/flash = 80dp；',
    '• long-edge balance ≈ 84 / 80 / 80；',
    '• 沿用 C 的主控 / queue 分离。',
]
for i, note in enumerate(notes):
    label(out, 1040, 274 + i * 30, note, 14)

label(out, 1040, 488, 'Gestures', 20, '#fff')
for i, note in enumerate([
    'left → save / commit · right ← discard',
    'item ↓ delete；',
    'hold + drag ↔ reorder；',
    'gap ↕ split / remove；',
]):
    label(out, 1040, 522 + i * 28, note, 14)

label(out, 1040, 650, 'Geometry', 20, '#fff')
for i, note in enumerate([
    'preview: x0 · y80 · 864 × 648',
    'queue: x84–512 · y820–876 · 5 × 56dp',
    'controls: zoom x592–784; shutter x648–728',
    'queue target 56dp · visible handler 36dp',
    'queue/shutter center y848 · queue→shutter = 136dp',
]):
    label(out, 1040, 684 + i * 28, note, 14)

destination = ROOT / 'recon-capture-fold8-ultra-c-cw90.svg'
data = ET.tostring(out, encoding='utf-8') + b'\n'
if '--check' in sys.argv:
    if destination.read_bytes() != data:
        raise SystemExit('Regenerate ' + destination.name)
else:
    destination.write_bytes(data)
