"""Build the archived B alternative Ultra sheet from the published A screens.

This generator is retained only for historical evidence. It is not a current
layout source; design C never imports it. Its small extra symbols are frozen
locally so current C token renames cannot rewrite the B study.
"""
from copy import deepcopy
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
NS = 'http://www.w3.org/2000/svg'
ET.register_namespace('', NS)
def tag(name):
    return '{'+NS+'}'+name

def elem(parent, name, **attrs):
    return ET.SubElement(parent, tag(name), {k:str(v) for k,v in attrs.items()})

def label(parent, x, y, value, size=16, color='#ccc'):
    elem(parent,'text',x=x,y=y,fill=color,**{'font-size':size}).text=value

def use(parent, symbol, x, y, w, h):
    return elem(parent,'use',href='#'+symbol,x=x,y=y,width=w,height=h)

def face_viewer(node):
    cx=float(node.get('x'))+float(node.get('width'))/2
    cy=float(node.get('y'))+float(node.get('height'))/2
    node.set('transform',f'rotate(90 {cx} {cy})')

base=ET.parse(ROOT/'recon-capture-fold8-ultra-inner.svg').getroot()
out=ET.Element(tag('svg'),width='2040',height='5480',viewBox='0 0 2040 5480')
elem(out,'title').text='Fold8 Ultra • fixed capture controls • comparison B'
defs=deepcopy(base.find(tag('defs')))
out.extend([defs,deepcopy(base.find(tag('style')))])
symbols={n.get('id'):n for n in defs if n.get('id')}

# B is an archived study. Keep the three small symbols it needs here so a
# current C token-sheet rename cannot silently rewrite the historical source.
LEGACY_EXTRA_SYMBOLS = {
    'iconCheck': '<symbol xmlns="http://www.w3.org/2000/svg" id="iconCheck" viewBox="0 0 48 48"><g class="nativeIcon" transform="translate(12 12)"><path d="M0 0h24v24H0z" fill="none"/><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></g></symbol>',
    'iconClose': '<symbol xmlns="http://www.w3.org/2000/svg" id="iconClose" viewBox="0 0 48 48"><g class="nativeIcon" transform="translate(12 12)"><path d="M0 0h24v24H0z" fill="none"/><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></g></symbol>',
    'queueDenseDivider': '<symbol xmlns="http://www.w3.org/2000/svg" id="queueDenseDivider" viewBox="0 0 4 64"><rect width="4" height="64" rx="2" fill="#fff" fill-opacity=".78"/></symbol>',
}

def extra_symbol(name):
    # These particular native glyphs and the divider have no linked dependencies.
    node=ET.fromstring(LEGACY_EXTRA_SYMBOLS[name])
    node.set('id','study-'+name)
    for g in node.iter():
        if g.get('class')=='nativeIcon':g.set('class','shared-nativeIcon')
    defs.append(node)

for name in ['iconCheck','iconClose','queueDenseDivider']:
    extra_symbol(name)
for name,start,end in [('commit','#2E7D32',0),('discard','#B26A00',1)]:
    grad=elem(defs,'linearGradient',id='study-'+name,x1=end,x2=1-end,y1=0,y2=0)
    elem(grad,'stop',offset=0,**{'stop-color':start})
    elem(grad,'stop',offset=1,**{'stop-color':start,'stop-opacity':0})

# Keep the rail surface, selected circle and hit regions in their CW positions.
# Counter-rotate only the numerals around the center of their individual slots.
zoom=deepcopy(symbols['shared-zoomChips'])
zoom.set('id','study-zoom')
for t in zoom.findall(tag('text')):
    t.set('transform',f"rotate(90 {t.get('x')} 20)")
defs.append(zoom)

def tray(parent, dense, state='idle'):
    w,h=(312,64) if dense else (360,72)
    size=48 if dense else 56
    if state=='split':
        items=[(69,'Photo'),(123,'Video'),(195,'Voice')]
        use(parent,'study-queueDenseDivider',181,0,4,h)
    else:
        tiles=symbols['shared-queueDenseIdle' if dense else 'shared-queueThumbs']
        items=[(int(i.get('x')),i.get('href').split('Thumb')[-1]) for i in tiles if 'Thumb' in i.get('href','')]
    handle = 'shared-queueEdgeHandle' if dense else 'shared-queueEdgeHandleStandard'
    handle_h = 28 if dense else 36
    for x,medium in items:
        face_viewer(use(parent,'shared-queue'+('Dense' if dense else '')+'Thumb'+medium,x,8,size,size))
    for x,action in [(0,'commit'),(w-48,'discard')]:
        elem(parent,'rect',x=x,y=(h-48)//2,width=48,height=48,fill='transparent',**{'data-role':'tap-target'})
        if state==action:
            elem(parent,'rect',x=x if x==0 else w-96,y=0,width=96,height=h,fill=f'url(#study-{action})')
            face_viewer(use(parent,'study-'+('iconCheck' if x==0 else 'iconClose'),x,(h-48)//2,48,48))
        else:
            use(parent,handle,x+21,(h-handle_h)//2,6,handle_h)

def fixed_group(upright, cw):
    drawing=deepcopy(upright)
    # The modal selector, camera image, utility icons and system gesture bar belong to the host.
    def first_zoom(nodes):
        return next(i for i,n in enumerate(nodes) if n.get('href','').startswith('#shared-zoom'))
    start=first_zoom(drawing)
    for n in list(drawing)[start:-1]:drawing.remove(n)
    controls=elem(drawing,'g',transform='matrix(0 -1 1 0 0 864)')
    for n in list(cw)[first_zoom(cw):-1]:
        if n.get('data-role')=='queue':
            local=elem(controls,'g',transform=f"translate({n.get('x')} {n.get('y')})")
            tray(local,'Dense' in n.get('href'))
        else:
            copy=deepcopy(n)
            if n.get('href')=='#shared-zoomChips':copy.set('href','#study-zoom')
            else:face_viewer(copy)
            controls.append(copy)
    return drawing

def board(drawing,x,y,title):
    label(out,x,y-16,title,19,'#fff')
    drawing.set('transform',f'translate({x} {y})')
    out.append(drawing)

frames=base.findall(tag('g'))
assert len(frames)==8
elem(out,'rect',width=2040,height=5480,fill='#202020')
label(out,32,52,'Fold8 Ultra   /   Capture controls fixed to the chassis',32,'#fff')
label(out,32,88,'A and B share the same frame, utility strip and top modes. In B the CW90 capture controls keep their physical positions.')
board(deepcopy(frames[0]),32,160,'A   /   Current upright   ·   Rear PHOTO')
board(fixed_group(frames[0],frames[1]),1048,160,'B   /   Proposed upright   ·   Rear PHOTO')
board(deepcopy(frames[1]),80,1128,'A + B   /   CW90   ·   Rear PHOTO')
label(out,1048,1172,'B   /   Vertical queue in the upright grip',25,'#fff')
for i,(state,name) in enumerate([('idle','Idle'),('commit','Commit ↑'),('discard','Discard ↓'),('split','Split')]):
    x=1088+i*222
    label(out,x,1248,name,17,'#fff')
    group=elem(out,'g',transform=f'translate({x} 1600) rotate(-90)')
    tray(group,True,state)
for i,line in enumerate([
    'Bottom handle ↑ commits the queue. Top handle ↓ discards it.',
    'Oldest at the bottom. Scroll vertically or hold a tile and drag to reorder.',
    'Pull a tile right to delete it. Pull a gap right to split or left to rejoin.',
    'Handle hit area stays 48 × 48. A drag keeps the owner selected at touch-down.',
    'Cancel an unfinished drag on rotation. Keep the existing confirmation and Undo policy.',
    'Preview swipes and the mode selector keep their existing directions.'
]):
    label(out,1048,1672+i*38,line)
for i,name in enumerate(['Front PHOTO','Rear VIDEO','Front VIDEO'],1):
    y=2256+(i-1)*1080
    board(fixed_group(frames[i*2],frames[i*2+1]),32,y,'B   /   Upright   ·   '+name)
    board(deepcopy(frames[i*2+1]),1096,y,'A + B   /   CW90   ·   '+name)
dest=ROOT/'recon-capture-fold8-ultra-fixed-dock.svg'
data=ET.tostring(out,encoding='utf-8')+b'\n'
if '--check' in sys.argv:
    if dest.read_bytes()!=data:raise SystemExit('Regenerate '+dest.name)
else:dest.write_bytes(data)
