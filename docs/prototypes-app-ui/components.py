"""Component specimens; measurements and role names are external to product frames."""
from library import DATE, dialog, library_frame, menu, progress, row, thumb
from ui import Frame, Sheet, switch


class Sample(Frame):
    def __init__(self, name, h, dark=False, w=360):
        super().__init__(name, w, h, dark)
        self.body = self.body[:1]

    def nav(self):
        return ""


def palette(dark):
    f = Sample("palette-dark" if dark else "palette-light", 286, dark)
    pairs = [("Canvas", "bg", "ink"), ("Surface", "surface", "ink"),
             ("Tonal", "tonal", "onTonal"), ("Primary", "primary", "onPrimary"),
             ("Selection", "success", "onSuccess"), ("Error", "errorBg", "error")]
    for i, (name, bg, fg) in enumerate(pairs):
        x, y = 16 + (i % 2)*168, 16 + (i//2)*84
        f.box(x,y,160,76,f.c[bg],12,f.c["line"] if i==0 else None)
        f.txt(x+14,y+33,name,17,f.c[fg],600)
        f.txt(x+14,y+57,f.c[bg],13,f.c[fg])
    return f


def sheets():
    s=Sheet("components","Android components",
            "Material roles, measured controls and interaction states shared by Recon’s utility screens.")
    s.row((palette(False),"01   Light roles","Foreground and background are paired. Body text uses on-surface."),
          (palette(True),"02   Dark roles","Fixed reference colors. Android maps semantic roles to the active system palette."))
    bars=Sample("bars",304)
    bars.bar("Bundles","back",("folder_open","Open output folder"))
    bars.box(0,112,360,64,bars.c["tonal"])
    bars.iconbutton("close","Clear selection",4,120)
    bars.txt(64,152,"2 selected",22)
    bars.iconbutton("send","Send selected bundles",308,120)
    bars.button("Choose folder",16,216,144)
    bars.button("Cancel",174,216,86,"outline")
    bars.button("Done",268,216,76,"text")
    field=Sample("settings-rows",304,True)
    field.txt(16,46,"Bundle output",14,field.c["primary"],600)
    for i,(value,on,locked) in enumerate([("Individual files in subfolder",True,True),
                                           ("Vertical stitched image",False,False)]):
        cy=88+60*i
        field.txt(16,cy+6,value,16)
        switch(field,344,cy,on,locked)
        if not locked: field.hit(value,16,cy-28,328,56)
    field.txt(32,217,"Stitch quality",16,field.c["muted"])
    field.txt(295,217,"Standard",14,field.c["muted"],anchor="end")
    field.icon("expand_more",304,199,24,field.c["muted"])
    field.txt(16,267,"At least one output is required.",13,field.c["muted"])
    s.row((bars,"03   App bars and actions","64dp bars · 24dp glyph in 48dp slot · 40dp button surface in 48dp target."),
          (field,"04   Preferences","16dp side gutters · 52 × 32dp switch · one accessible action per editable row."))
    rows=Sample("bundle-rows",368)
    for i,state in enumerate(["idle","selected","pending","processing"]):
        row(rows,0,24+i*80,360,i,state)
    media=Sample("media-feedback",368,True)
    for i,k in enumerate(["photos","video","voice","stitch"]):
        thumb(media,k,16+86*i,24)
    media.txt(16,113,"Sending files",18,weight=550)
    media.txt(16,139,"3 of 9 files",14,media.c["muted"])
    media.box(16,159,328,6,media.c["high"],3)
    media.box(16,159,131,6,media.c["primary"],3)
    progress(media,31,208)
    media.txt(60,214,"Looking for devices…",15)
    media.box(16,256,328,72,media.c["errorBg"],12)
    media.txt(32,288,"Couldn’t load bundles.",15)
    media.txt(32,309,"Check the output folder.",13,media.c["muted"])
    media.txt(300,299,"Dismiss",14,media.c["error"],550,"middle")
    media.hit("Dismiss",261,268,72,48)
    s.row((rows,"05   Library rows","80dp rows · 56 × 48dp media tile · 80dp selection reveal · 48dp trailing target."),
          (media,"06   Media, progress and feedback","Progress represents bytes. File counts and actionable feedback remain readable."))
    m=library_frame("modal-menu",h=576);menu(m)
    d=library_frame("modal-dialog",h=576);dialog(d)
    transfer=library_frame("modal-sheet",h=576,selected=(0,1))
    transfer.clear_hits()
    transfer.body.append('<rect width="360" height="576" fill="#000" opacity=".4"/>')
    c=transfer.c
    transfer.box(0,286,360,290,c["surface"],28)
    transfer.box(0,314,360,262,c["surface"])
    transfer.box(164,298,32,4,c["line"],2)
    transfer.icon("send",24,318,16,c["primary"])
    transfer.txt(48,331,"LocalSend",13,c["muted"],550)
    transfer.txt(24,368,"Send 2 bundles",24,weight=500)
    transfer.txt(24,394,"Choose a device on the same Wi-Fi.",15,c["muted"])
    progress(transfer,36,446)
    transfer.txt(60,451,"Looking for devices…",15)
    transfer.button("Close",248,496,88,"text")
    s.row((m,"07   Anchored menu","48dp command rows. Back dismisses and restores focus to the invoking row."),
          (d,"08   Confirmation","Destructive action names the affected bundle and its Undo policy."),
          (transfer,"09   Modal sheet","Only modal controls receive input. Use the LocalSend sheet for each exact state."))
    grouped=Sample("wide-media-rows",208,w=560)
    row(grouped,0,24,560,0)
    row(grouped,0,104,560,4)
    s.row((grouped,"10   Expanded thumbnail groups",
           "Three 56 × 48dp slots with 4dp gaps. Titles align even when one asset is available."))
    return [s]
