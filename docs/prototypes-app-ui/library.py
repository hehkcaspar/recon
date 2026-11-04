"""Bundle list, contextual commands and transfer underlay share the same fixture."""
from ui import Frame, Sheet, box, label, use

DATA = [
    ("0006", "3 photos · 1 video", "1 voice note", "mix"),
    ("0005", "4 photos", "", "photos"),
    ("0004", "2 videos", "", "video"),
    ("0003", "1 voice note", "", "voice"),
    ("0002", "Stitched image only", "", "stitch"),
    ("0001", "2 photos", "", "photos"),
]
DATE = "2026-11-02-s-"

# Artwork stands in for the thumbnail pixels produced by Android.
ART = {
    "photos": '<rect width="56" height="48" rx="7" fill="#006C68"/><circle cx="41" cy="12" r="6" fill="#F5D187"/><path d="M0 36L14 17l14 14 10-9 18 18v8H0Z" fill="#78CEC0"/><path d="M0 42l16-11 13 6 13-7 14 7v11H0Z" fill="#154F54"/>',
    "video": '<rect width="56" height="48" rx="7" fill="#483656"/><path d="M0 33L22 12l12 15 10-8 12 14v15H0Z" fill="#AB7A87"/><circle cx="28" cy="24" r="11" fill="#312A3E"/><path d="M25 18v12l9-6Z" fill="#fff"/>',
    "voice": '<rect width="56" height="48" rx="7" fill="#253954"/><path d="M9 22v4m5-9v14m5-19v25m5-20v14m5-11v8m5-16v25m5-21v16m5-12v8" fill="none" stroke="#B8CCF0" stroke-width="2.5" stroke-linecap="round"/>',
    "stitch": '<rect width="56" height="48" rx="7" fill="#E1CDAE"/><rect x="16" y="4" width="24" height="40" rx="2" fill="#FFF8E9"/><path d="M19 8h18v10H19Zm0 14h18v8H19Zm0 12h18v6H19Z" fill="#7E9680"/>',
}
ART["sunset"] = '<rect width="56" height="48" fill="#E6BC99"/><circle cx="16" cy="15" r="7" fill="#FFF2C8"/><path d="M0 30l16-7 11 5 14-10 15 13v17H0Z" fill="#8D7078"/><path d="M0 37l18-6 22 5 16-4v16H0Z" fill="#515573"/>'
ART["sky"] = '<rect width="56" height="48" fill="#A4C6D1"/><path d="M0 38L20 13l12 13 9-8 15 21v9H0Z" fill="#446978"/><path d="M13 23l7-10 8 9-7-2-3 4Z" fill="#E7F0ED"/>'
ART["mix"] = '<rect width="56" height="48" rx="7" fill="#C2D1DD"/><path d="M0 33l16-19 13 16 12-13 15 16v15H0Z" fill="#849AA6"/><circle cx="41" cy="11" r="5" fill="#FCF1CA"/><rect x="24" y="24" width="32" height="24" rx="5" fill="#2E5157"/><path d="M31 36h3m3-5v10m5-13v14m5-12v11" stroke="#9DD5CA" stroke-width="2" stroke-linecap="round"/>'
def thumb(f, kind, x, y):
    key = "tile-"+kind
    f.symbols["tile-clip"] = '<clipPath id="tile-clip"><rect width="56" height="48" rx="7"/></clipPath>'
    f.symbols[key] = f'<symbol id="{key}" viewBox="0 0 56 48"><g clip-path="url(#tile-clip)">{ART[kind]}</g></symbol>'
    f.body.append(use(key,x,y,56,48))


WIDE_THUMBNAILS = (
    ("photos","video","voice"), ("photos","sunset","sky"), ("video","video"),
    ("voice",), ("stitch",), ("photos","sky"),
)


def thumbnail_group(f, index, x, y, expanded):
    if not expanded:
        thumb(f,DATA[index][3],x,y)
        return 56
    # Keep title columns aligned even when only one media asset is available.
    for i,member in enumerate(WIDE_THUMBNAILS[index]):
        thumb(f,member,x+i*60,y)
    return 176


def row(f, x, y, w, index, state="idle", add_hits=True, bundle_id=None):
    suffix, counts, extra, kind = DATA[index]
    bundle_id = bundle_id or DATE+suffix
    c = f.c
    f.box(x,y,w,80,c["bg"])
    if state=="pending":
        f.icon("delete", x+16,y+25,24,c["muted"])
        f.txt(x+56,y+32,bundle_id,15,weight=550)
        f.txt(x+56,y+55,"Deleting in 4 seconds",14,c["muted"])
        f.button("Undo",x+w-80,y+16,72,"tonal")
        f.targets[-1]["label"] = "Undo deletion of "+bundle_id
    elif state=="processing":
        progress(f,x+30,y+38)
        title_x = x+(208 if w>=520 else 88)
        f.txt(title_x,y+32,DATE+suffix,15,weight=550)
        f.txt(title_x,y+55,"Processing…",14,c["muted"])
    else:
        dx = {"selected":80,"pullRight":40,"pullLeft":-96,"idle":0}[state]
        if dx>0:
            f.box(x,y,dx,80,c["success"])
            f.icon("check_circle",x+dx/2-12,y+28,24,c["onSuccess"])
            if state=="selected" and add_hits:
                f.hit("Deselect "+bundle_id,x,y,dx,80)
        if dx<0:
            f.box(x+w+dx,y,-dx,80,c["errorBg"])
            f.icon("delete",x+w-50,y+27,26,c["error"])
        # A moving row is clipped to the local list viewport.
        f.body.append(f'<svg x="{x}" y="{y}" width="{w}" height="80" overflow="hidden">')
        f.box(max(0,dx),0,w-abs(dx),80,c["bg"])
        tx=dx+16
        title_x = tx + thumbnail_group(f,index,tx,16,w>=520) + 16
        menu_x = w-48+min(0,dx)
        available = menu_x - title_x - 8
        title = bundle_id if len(bundle_id)*7.7 <= available else bundle_id[:4]+"…"+bundle_id.rsplit("-",1)[-1]
        f.txt(title_x,32,title,15,weight=600)
        f.txt(title_x,52,counts,13,c["muted"])
        if extra:
            f.txt(title_x,68,extra,13,c["muted"])
        f.icon("more",menu_x+12,28,24,c["muted"])
        f.body.append('</svg>')
        if state in ("idle","selected") and add_hits:
            f.hit("Actions for "+bundle_id,x+menu_x,y+16,48,48)
            start = max(24, dx)
            f.hit("Swipe "+bundle_id, x+start, y, menu_x-start, 80)
    f.box(x+16,y+79,w-32,1,c["line"])


def progress(f,x,y):
    f.body.append(f'<circle cx="{x}" cy="{y}" r="12" fill="none" stroke="{f.c["line"]}" stroke-width="3"/>')
    f.body.append(f'<path d="M{x-12} {y}a12 12 0 0 1 12 -12" fill="none" stroke="{f.c["primary"]}" stroke-width="3" stroke-linecap="round"/>')


def library_frame(id,w=360,h=800,dark=False,selected=(),pane=None,bottom=24,
                  state="list",focus="idle",skip_first=False,first_id=None):
    f=Frame(id,w,h,dark,pane,bottom)
    px,py,pw,ph=f.pane
    width=min(560,pw)
    x=px+(pw-width)/2
    c=f.c
    if selected:
        f.box(px,py+24,pw,64,c["tonal"])
        f.bar(f"{len(selected)} selected","close",("send","Send selected bundles"))
    else:
        f.bar("Bundles","back",("folder_open","Open output folder"))
    if state in ("empty","load","error"):
        cy=py+ph/2-34
        if state=="load":
            progress(f,px+pw/2,cy)
        else:
            f.box(px+pw/2-32,cy-24,64,56,c["tonal"],18)
            f.icon("folder_open" if state=="empty" else "error",px+pw/2-14,cy-10,28,c["onTonal"])
        heading,detail={"empty":("No bundles yet","Save a capture queue to see it here."),
                        "error":("Couldn’t load bundles","Return to capture and reopen Bundles."),
                        "load":("Loading bundles…","")}[state]
        f.txt(px+pw/2,cy+67,heading,22,anchor="middle")
        f.txt(px+pw/2,cy+94,detail,14,c["muted"],anchor="middle")
        return f
    f.txt(x+16,py+118,"Monday, November 2",14,c["primary"],600)
    for slot,i in enumerate(range(1 if skip_first else 0,len(DATA))):
        y=py+136+80*slot
        if y>=py+ph-bottom: break
        s=focus if i==0 and focus!="idle" else "selected" if i in selected else "idle"
        cropped=y+80>py+ph-bottom
        if cropped:
            f.body.append(f'<svg x="{x}" y="{y}" width="{width}" height="{py+ph-bottom-y}" overflow="hidden">')
            row(f,0,0,width,i,s,False,first_id if i==0 else None)
            f.body.append('</svg>')
        else:
            row(f,x,y,width,i,s,bundle_id=first_id if i==0 else None)
    return f


def dim(f):
    f.clear_hits()
    f.body.append(box(0,0,f.w,f.h,"#17131D").replace('/>',' opacity=".42"/>'))


def dialog(f,undo=True):
    dim(f)
    x=(f.w-312)/2; y=(f.h-216)/2; c=f.c
    f.box(x,y,312,216,c["surface"],26)
    f.txt(x+24,y+42,"Delete bundle?",24)
    f.txt(x+24,y+77,DATE+"0006",16,weight=600)
    f.txt(x+24,y+105,"Deletes its photos, video and voice note.",14,c["muted"])
    f.txt(x+24,y+128,"You have 5 seconds to undo." if undo else "This cannot be undone.",14,c["muted"])
    f.button("Cancel",x+128,y+152,76,"text")
    f.box(x+212,y+156,80,40,c["error"],20)
    f.txt(x+252,y+181,"Delete",14,c["onError"],550,"middle")
    f.hit("Delete bundle",x+212,y+152,80,48)


def menu(f,selected=False):
    f.clear_hits()
    # Anchor below trailing row action. Outside tap / Back dismiss the menu.
    x=f.w-172;y=204; c=f.c
    f.box(x,y,156,56 if selected else 104,c["high"],8,c["line"])
    for i,(icon,action) in enumerate([("check","Deselect")] if selected else [("check","Select"),("delete","Delete")]):
        f.icon(icon,x+12,y+16+i*48,24,c["error"] if action=="Delete" else c["ink"])
        f.txt(x+52,y+34+i*48,action,16)
        f.hit(action,x,y+4+i*48,156,48)


def sheets():
    s=Sheet("bundles","Bundles","Browse saved work, identify its media, and act on one bundle at a time.")
    s.row((library_frame("phone"),"01   Library","Phone   ·   360 × 800   ·   Light"),
          (library_frame("cover",h=576,dark=True),"02   Cover","Short viewport   ·   360 × 576   ·   Dark"),
          (library_frame("processing",focus="processing"),"03   Processing","The top row becomes 0006 in the library at left."))
    s.row(*[(library_frame(key,h=576,state=key),title,caption) for key,title,caption in
            [("load","04   Loading","Initial folder scan"),("empty","05   Empty","Configured folder, no saved bundles"),("error","06   Load failed","Keep Back and system folder access available")]])
    s.row((library_frame("wide",960,720),"07   Unfolded","960 × 720   ·   Library column capped at 560dp"))
    s.row((library_frame("tall",720,960,dark=True),"08   Rotated inner","720 × 960   ·   Same list order and commands"))
    hinged=library_frame("hinge-selected",840,720,pane=(432,0,408,720),selected=(0,1))
    hinged.hinge=(408,0,24,720)
    s.row((hinged,"09   Separating hinge","One complete list and its swipe targets occupy the active right pane. Two bundles remain selected."))
    a=Sheet("bundle-actions","Bundle actions","A right swipe selects. A left swipe on a selected row returns it to rest.")
    def cover(id,**kw):return library_frame(id,h=576,**kw)
    a.row((cover("start"),"01   Start","Start on the row, inside system Back insets."),
          (cover("reveal",focus="pullRight"),"02   Drag right","40dp of the 80dp reveal. Release to select."),
          (cover("selected",selected=(0,)),"03   Selected","80dp resting reveal. Tap the check to deselect."))
    a.row((cover("reverse",focus="pullRight",selected=(0,)),"04   Deselect","Drag selected row left toward rest. Release."),
          (cover("cancel"),"05   Cancel from idle","An interrupted idle drag restores the idle row."),
          (cover("multi",selected=(0,1)),"06   Two selected","Tap Send to open one LocalSend transfer."))
    m=cover("menu");menu(m)
    d=cover("confirm");dialog(d)
    a.row((m,"07   Direct actions","More opens Select or Delete. Back dismisses."),
          (cover("swipe-delete",focus="pullLeft"),"08   Drag left","Only an unselected row reveals deletion."),
          (d,"09   Confirm","Confirmation on, Undo set to 5 seconds."))
    no=cover("permanent",dark=True);dialog(no,False)
    a.row((cover("pending",focus="pending"),"10   Undo available","Undo restores 0006 to its original position."),
          (no,"11   Undo off","Confirmation on. Delete removes immediately."),
          (cover("removed",skip_first=True),"12   Removed","After expiry, or immediately with Undo off."))
    picked = cover("selected-menu",selected=(0,))
    menu(picked,True)
    a.row((picked,"13   Selected row menu","Deselect is available by tap. A selected row cannot be deleted."),
          (cover("restored"),"14   Undo completed","0006 returns in place. Its row actions are available again."),
          (cover("cancel-selected",selected=(0,)),"15   Cancel from selection","Interrupt a leftward deselect drag. The check and selection count persist."))
    narrow = library_frame("long-id-selected",w=328,h=576,selected=(0,),first_id=DATE+"10027")
    a.row((narrow,"16   Long selected ID","Middle ellipsis preserves the sequence number. The accessible action names the full ID."),
          (cover("send-return"),"17   Transfer dismissed","Close the transfer sheet. Both bundles remain and selection clears."))
    return [s,a]
