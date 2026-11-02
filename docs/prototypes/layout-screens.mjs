// Authoritative screen geometry. SVGs are generated; shared shapes stay in the design system.
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
const root=fileURLToPath(new URL('.',import.meta.url));
const check=process.argv.includes('--check');
const font=(await readFile(join(root,'assets/Roboto.ttf'))).toString('base64');
const records=[];
const customScenes=new Map();
const n=v=>+v.toFixed(4);
const esc=s=>s.replaceAll('&','&amp;').replaceAll('<','&lt;');
const use=(id,x,y,w,h,extra='')=>`<use href="#shared-${id}" x="${n(x)}" y="${n(y)}" width="${n(w)}" height="${n(h)}" ${extra}/>`;
const text=(x,y,s,size=16,color='#ccc')=>`<text x="${x}" y="${y}" font-size="${size}" fill="${color}">${esc(s)}</text>`;
const rect=(x,y,w,h,fill)=>`<rect x="${n(x)}" y="${n(y)}" width="${n(w)}" height="${n(h)}" fill="${fill}"/>`;
const fit=(b,r)=>{const w=Math.min(b.w,b.h*r),h=w/r;return {x:b.x+(b.w-w)/2,y:b.y+(b.h-h)/2,w,h};};
const compactHeader=()=>use('topBarCompact',0,12,360,72);
const targetsOverlap=(a,b)=>a.x<b.x+b.w-.001&&b.x<a.x+a.w-.001&&a.y<b.y+b.h-.001&&b.y<a.y+a.h-.001;
function screen({id,w,h,tall=true,front=false,video=false,shell='bottom',hand='center',queue='idle',compact=false,overlay=false,aspect=null}){
 const qWidth=compact?312:360;
 const qX=compact?24:hand==='right'?w-24-qWidth:hand==='left'?24:(w-qWidth)/2;
 const qY=h-144,shutterY=qY-92,modeY=28,zoomY=shutterY-52;
 const cx=qX+qWidth/2;
 const frameRatio=aspect??(video?(tall?9/16:16/9):(tall?3/4:4/3));
 const verticalZoom=shell==='side'&&frameRatio<1&&!compact;
 let box;
 if(overlay){const top=w/frameRatio>h-96?24:72;box={x:0,y:top,w,h:h-24-top};}
 else if(compact)box={x:24,y:92,w:w-48,h:zoomY-108};
 else if(shell==='side')box={x:hand==='left'?408:(verticalZoom?96:24),y:84,w:w-432-(verticalZoom?72:0),h:h-108};
 else box={x:24,y:84,w:w-48,h:zoomY-100};
 const ratio=frameRatio,p=fit(box,ratio);
 if(overlay&&p.w===w)p.y=box.y; // Full-width compact frames align below the top bar, instead of floating in unused space.
 let out=rect(0,0,w,h,'#080808');
 const targets=[];
 const control=(symbol,x,y,sw=48,sh=48)=>{targets.push({symbol,x,y,w:sw,h:sh});out+=use(symbol,x,y,sw,sh);};
 if(compact){out+=compactHeader();targets.push({symbol:'settings',x:4,y:24,w:48,h:48},{symbol:'library',x:308,y:24,w:48,h:48});}
 else {control('iconSettings',24,24);control('iconLibrary',w-72,24);}
 let scene;
 if(Math.abs(ratio-.75)<1e-8||Math.abs(ratio-4/3)<1e-8)scene=`scene${ratio<1?'Portrait':'Landscape'}${front?'Front':'Rear'}`;
 else if(Math.abs(ratio-9/16)<1e-8||Math.abs(ratio-16/9)<1e-8)scene=`sceneVideo${ratio<1?'Portrait':'Landscape'}${front?'Front':'Rear'}`;
 if(scene)out+=use(scene,p.x,p.y,p.w,p.h,'data-role="preview"');
 else {const key=`test-scene-${String(n(ratio)).replace('.','-')}`,sw=300,sh=sw/ratio;
 customScenes.set(key,`<symbol id="${key}" viewBox="0 0 ${sw} ${sh}">${rect(0,0,sw,sh,'#9eb8c0')}${rect(0,sh*.58,sw,sh*.42,'#b8784d')}<path d="M0 ${sh*.58}H300M150 0V${sh}" stroke="#fff" stroke-opacity=".4"/><circle cx="230" cy="${sh*.26}" r="32" fill="#f0c66b"/><rect x="30" y="${sh*.16}" width="72" height="${Math.min(130,sh*.3)}" fill="#456274" stroke="#fff" stroke-width="2"/><text x="66" y="${sh*.3}" text-anchor="middle" fill="#fff" font-size="14">UP ↑</text><path d="M170 ${sh-25}l40 -80 35 45 20 -30 35 65Z" fill="#5b7e4a"/><path d="M4 20V4H20M280 4H296V20M4 ${sh-20}V${sh-4}H20M280 ${sh-4}H296V${sh-20}" stroke="#fff" stroke-width="2" fill="none"/></symbol>`);
 out+=`<use href="#${key}" x="${n(p.x)}" y="${n(p.y)}" width="${n(p.w)}" height="${n(p.h)}" data-role="preview"/>`;}
 // In the very tall cover frame the header overlaps: redraw it on its own top scrim.
 if(overlay&&p.y<72){out+=rect(0,24,w,48,'#000').replace('/>',' fill-opacity=".72"/>');out+=compactHeader(video);}

 if(!video)out+=use('focusIndicator',p.x+p.w*.56-40,(overlay?Math.min(p.y+p.h*.30,zoomY-52):p.y+p.h*.52)-40,80,80);
 if(overlay)out+=`<defs><linearGradient id="${id}-scrim" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#000" stop-opacity="0"/><stop offset=".32" stop-color="#000" stop-opacity=".72"/><stop offset="1" stop-color="#000" stop-opacity=".85"/></linearGradient></defs><rect x="0" y="${zoomY-48}" width="${w}" height="${h-24-(zoomY-48)}" fill="url(#${id}-scrim)"/>`;
 // Less-frequent mode selection stays in the top bar; preview swipe is the quick alternative.
 out+=use(video?'modeStripVideo':'modeStripPhoto',w/2-125,modeY,250,40);
 for(let k=0;k<3;k++)targets.push({symbol:'mode',x:w/2-125+k*250/3,y:modeY-4,w:250/3,h:48});
 // Frame orientation determines rail suitability, independently of window orientation.
 const zx=verticalZoom?(hand==='left'?w-68:28):cx-110;
 const zy=verticalZoom?p.y+p.h/2-110:zoomY;
 if(front){const x=verticalZoom?zx:cx-20,y=verticalZoom?zy+90:zoomY;out+=use('zoomSingle',x,y,40,40);targets.push({symbol:'zoom1x',x:x-4,y:y-4,w:48,h:48});}
 else {out+=use(verticalZoom?'zoomVertical':'zoomChips',zx,zy,verticalZoom?40:220,verticalZoom?220:40);for(let k=0;k<4;k++)targets.push({symbol:'zoom',x:verticalZoom?zx-4:zx+k*55+3.5,y:verticalZoom?zy+k*55+3.5:zy-4,w:48,h:48});}
 control(video?'iconMic':front?'iconFlashOff':'iconFlashAuto',cx-120,shutterY+16);
 // Front flash off is unavailable in this capability fixture; visible but not an active target.
 if(front&&!video){out=out.replace(/(<use href="#shared-iconFlashOff"[^>]*)(\/>)$/, '$1 opacity=".38"$2');targets.pop();}
 control(video?'shutterVideoIdle':'shutterPhoto',cx-40,shutterY,80,80);
 control('iconLens',cx+72,shutterY+16);
 out+=use(compact?(queue==='split'?'queueCompactSplit':'queueCompact'):(queue==='split'?'queueSplit':'queueStrip'),qX,qY,qWidth,72,'data-role="queue"');
 out+=`<rect x="${w/2-50}" y="${h-12}" width="100" height="4" rx="2" fill="#fff" fill-opacity=".45"/>`;
 const record={id,w,h,compact,overlay,shell,hand,frame:ratio===1?'square':ratio<1?'tall':'wide',camera:front?'front':'rear',zoomAxis:verticalZoom?'vertical':'horizontal',mode:video?'video':'photo',preview:Object.fromEntries(Object.entries(p).map(([k,v])=>[k,n(v)])),ratio,queue:{x:qX,y:qY,w:qWidth,h:72},safe:{top:24,bottom:24,side:24},travel:48,targets};
 for(const t of targets){if(t.x<0||t.y<24||t.x+t.w>w||t.y+t.h>h-24)throw Error(`Unsafe target ${id} ${t.symbol}`);if(!overlay&&targetsOverlap(t,p))throw Error(`Preview/control overlap ${id} ${t.symbol}`);}
 for(let i=0;i<targets.length;i++)for(let j=i+1;j<targets.length;j++)if(targetsOverlap(targets[i],targets[j]))throw Error(`Overlapping targets ${id}`);
 if(qX<24||qX+qWidth>w-24||qY+72+48>h-24)throw Error(`Unsafe queue ${id}`);
 if(Math.abs(p.w/p.h-ratio)>1e-8)throw Error(`Distorted frame ${id}`);
 records.push(record);
 return out;
}
function document(w,h,title,body){return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">\n<title>${esc(title)}</title>\n<!-- GENERATED by layout-screens.mjs; refresh shared symbols with build-standalone.mjs. -->\n<style>@font-face{font-family:Roboto;src:url("data:font/ttf;base64,${font}") format("truetype");font-weight:100 900;}text{font-family:Roboto,Arial,sans-serif}</style>\n${rect(0,0,w,h,'#202020')}\n${body}\n</svg>\n`;}
async function save(name,content){const path=join(root,name),old=await readFile(path,'utf8').catch(()=> '');
 // Compare unbundled authored geometry so --check also works after bundling.
 const strip=s=>s.replace(/<!-- BEGIN GENERATED SHARED DEFINITIONS:[\s\S]*?<!-- END GENERATED SHARED DEFINITIONS -->\n/g,'');
 if(strip(old)===content)return;
 if(check)throw Error(`Stale generated layout: ${name}`);await writeFile(path,content);console.log(`Generated ${name}`);}
for(const [name,h] of [['phone',800],['cover',576]])await save(`recon-capture-${name}.svg`,document(360,h,`Recon ${name} — clean app viewport`,screen({id:name,w:360,h,compact:true,overlay:true})));
async function matrix(ultra){
 const name=ultra?'Fold8 Ultra':'Fold8',W=2040;
 const modes=ultra?[false,true]:[false];
 const H=ultra?4300:2180;
 let body=text(32,42,`Recon / ${name} inner display`,28,'#fff');
 body+=text(32,72,'Window shape and upright camera frame are independent. 1 unit = 1 dp-like unit. Labels are outside app viewports.');
 body+=text(32,100,ultra?'Near-square geometry study; mapping below follows the supplied pose examples, not verified Ultra camera metadata.':'Rear frame directions corrected from the supplied Samsung screenshots. Front uses the same pose as a layout fixture.');
 let y=132;
 for(const video of modes){
  for(const wide of [true,false]){
   const w=wide?960:(ultra?864:720),h=wide?(ultra?864:720):960;
   for(const front of [false,true]){
    const x=32+(front?1024:0)+(960-w)/2;
    const id=`${ultra?'ultra':'fold8'}-${wide?'wide':'tall'}-${front?'front':'rear'}-${video?'video':'photo'}`;
    const config={id,w,h,tall:wide,front,video,shell:wide?'side':'bottom',hand:wide?'right':'center'};
    body+=`<g transform="translate(${x} ${y})">${screen(config)}</g>`;
    body+=text(x,y+h+28,`${wide?'Wide':'Tall'} window ${w} × ${h} / ${wide?'tall':'wide'} upright ${video?(wide?'9:16':'16:9'):(wide?'3:4':'4:3')} / ${front?'front':'rear'} / ${video?'VIDEO':'PHOTO'}`,16,'#fff');
    body+=text(x,y+h+52,front?'Front capability fixture: 1×, rear flash unavailable; mirroring not specified.':ultra?'Representative pose; metadata determines the actual frame direction.':'Frame direction follows the supplied rear-camera screenshot.',14);
   }
   y+=h+100;
  }
 }
 body+=text(32,y+8,'24-unit illustrative safe insets; 48-unit downward queue runway above bottom inset. No explanations inside app views.',15);
 body+=text(32,y+34,'Dock choice fits frame + controls, not width > height. Tall-frame examples use a right dock; left-hand equivalent is in layout checks.',15);
 body+=text(32,y+60,'Rotation continues recording. Fold/unfold finalizes once. These SVGs specify intended behavior; they do not execute it.',15);
 await save(`recon-capture-${ultra?'fold8-ultra':'fold8'}-inner.svg`,document(W,Math.max(H,y+92),`${name} capture layouts`,body));
}
await matrix(false);await matrix(true);
// Separate engineering/gesture fixtures, not permanent labels or guides in product screens.
let body=text(32,42,'Layout checks / independent frame direction, handedness and hinge',28,'#fff');
body+=text(32,74,'Counterexamples prevent hardcoding window-wide → camera-wide, or its inverse. Same component sizes and gesture runway.');
const cases=[
 {id:'wide-wide',w:960,h:720,tall:false,shell:'side',hand:'right',label:'Wide window / wide frame — transform exception'},
 {id:'wide-left',w:960,h:720,tall:true,shell:'side',hand:'left',queue:'split',label:'Wide window / tall frame — left-hand dock and split queue'},
 {id:'tall-tall',w:720,h:960,tall:true,label:'Tall window / tall frame — transform exception'},
 {id:'near-square-tall',w:864,h:960,tall:true,shell:'side',hand:'right',label:'Near-square / tall frame — side dock despite tall window'},
];
for(let i=0;i<cases.length;i++){const c=cases[i],x=32+(i%2)*1024,y=i<2?110:930;body+=`<g transform="translate(${x} ${y})">${screen(c)}</g>`+text(x,y+c.h+28,c.label,17,'#fff');}
body+=text(32,1980,'Separating vertical hinge / choose one complete usable pane',24,'#fff');
body+=text(32,2010,'The whole capture screen fits one pane. A real separating feature supplies its bounds; a flat crease does not force a split.');
body+=rect(32,2040,740,800,'#080808')+rect(392,2040,20,800,'#555');
body+=`<g transform="translate(412 2040)">${screen({id:'hinge-right-pane',w:360,h:800,compact:true})}</g>`;
body+=text(32,2870,'360-unit inactive pane / 20-unit separating hinge / 360-unit active pane',16,'#fff');
body+=text(840,2110,'Safe-area and gesture contract',22,'#fff');
for(const [i,s] of [
 'Insets here are fixtures, not Android constants.',
 'Use safeDrawing for visible controls and safeGestures for drags.',
 'Queue edge targets begin inside the usable region; OS Back keeps its edges.',
 '72-unit queue + 48-unit travel + actual bottom inset are allocated first.',
 'Preview fits the remaining bounds uniformly; controls never shrink.',
 'Long text grows overlay height; it never pushes controls into system UI.',
 'Left-hand layout moves the dock; text, media and commit direction stay unchanged.',
 'A flat, nonseparating crease can share the preview; no button sits on a separator.',
 'Below minimum usable space: scroll controls / use explicit actions; do not clip.',
 'One-handed reach is a prototype hypothesis until tested while holding a device.'
].entries())body+=text(840,2154+i*34,s,15);
body+=text(32,2960,'Feedback on the actual short-cover layout',24,'#fff');
body+=text(32,2990,'Overlay host x16 / y92 / width328. Large messages grow above zoom; Stop and queue travel remain reachable.');
for(const [i,banner] of ['bannerUndo','bannerErrorLarge'].entries()){
 const x=32+i*424;body+=`<g transform="translate(${x} 3020)">${screen({id:`cover-feedback-${i}`,w:360,h:576,compact:true,overlay:true})}${use(banner,16,92,328,i?160:48)}</g>`;
 body+=text(x,3624,i?'2× text / stacked dismiss action':'Undo / compact message',17,'#fff');
}
await save('recon-capture-layout-checks.svg',document(2040,3670,'Recon layout validation fixtures',body));
// Common output ratios are fixtures, not a new in-app aspect selector.
let ratiosBody=text(32,42,'Common framing / phone, cover and inner-window layout',28,'#fff');
ratiosBody+=text(32,72,'4:3 family, 16:9 family, square and variable Full-style output. Selected output crop is distinct from extra preview-fitting crop.');
ratiosBody+=text(32,100,'Controls can overlay a complete fitted frame; use actual stream metadata. These states do not add a ratio-selector feature.');
const ratioCases=[
 {label:'PHOTO / 3:4 upright',ratio:.75},
 {label:'PHOTO / 9:16 upright',ratio:9/16},
 {label:'VIDEO / 16:9 family — tall and wide upright frames',ratio:9/16,video:true},
 {label:'PHOTO / 1:1 square',ratio:1},
 {label:'PHOTO / Full-style variable ratio',ratio:9/20,full:true},
];
for(let i=0;i<ratioCases.length;i++){
 const c=ratioCases[i],y=148+i*940;
 ratiosBody+=text(32,y,c.label,22,'#fff');
 const specs=[{id:`ratio-${i}-phone`,w:360,h:800,compact:true,overlay:true,aspect:c.ratio},
 {id:`ratio-${i}-cover`,w:360,h:576,compact:true,overlay:true,aspect:c.full?5/8:c.ratio},
 {id:`ratio-${i}-inner`,w:960,h:720,shell:'side',hand:'right',aspect:c.full?4/3:c.video?16/9:c.ratio}];
 for(let k=0;k<specs.length;k++){const x=[32,424,848][k],spec=specs[k];
 ratiosBody+=`<g transform="translate(${x} ${y+28})">${screen({...spec,video:!!c.video})}</g>`;
 ratiosBody+=text(x,y+spec.h+54,`${['Phone','Cover','Inner'][k]} ${spec.w} × ${spec.h} / frame ratio ${n(spec.aspect)}`,14,'#fff');}
 if(i===1||i===2)ratiosBody+=text(424,y+660,'Short cover: 297 × 528 full frame; width-fit would crop.',14);
 if(c.full)ratiosBody+=text(424,y+660,'Full varies by selected output: 9:20 / 5:8 / 4:3 examples.',14);
}
ratiosBody+=text(32,4880,'Wide 4:3 and 16:9 upright frames appear in the inner matrices. A square frame does not swap axes on rotation.',16);
ratiosBody+=text(32,4910,'Samsung documents 3:4, 9:16, 1:1 and Full; Pixel documents full-image 4:3 and wide-crop 16:9. Sources in camera-layout-implementation.md.',14);
await save('recon-capture-aspect-ratios.svg',document(2040,4960,'Recon common aspect ratio designs',`<defs>${[...customScenes.values()].join('')}</defs>${ratiosBody}`));
await save('layout-geometry.json',JSON.stringify({units:'logical dp-like; insets illustrative',screens:records},null,2)+'\n');
// Check nested queue ownership too: screen-level targets alone miss expanded split gaps.
const canonical=await readFile(join(root,'recon-capture-design-system.svg'),'utf8');
const symbols=new Map([...canonical.matchAll(/<symbol\b[^>]*id="([^"]+)"[^>]*>([\s\S]*?)<\/symbol>/g)].map(m=>[m[1],m[2]]));
const attrs=tag=>Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map(m=>[m[1],m[2]]));
function queueBounds(id,ox=0,oy=0){
 const result=[];
 if(/^queueThumb(Photo|Video|Voice)$/.test(id))return [{x:ox,y:oy,w:56,h:56,kind:'item'}];
 const source=symbols.get(id);if(!source)throw Error(`Missing queue symbol ${id}`);
 for(const match of source.matchAll(/<(use|rect)\b[^>]*\/>/g)){
  const a=attrs(match[0]),x=ox+(+a.x||0),y=oy+(+a.y||0);
  if(match[1]==='use' && a.href.startsWith('#queue'))result.push(...queueBounds(a.href.slice(1),x,y));
  else if(match[1]==='rect'&&a['data-role']==='tap-target')result.push({x,y,w:+a.width,h:+a.height,kind:'edge'});
 }
 return result;
}
for(const id of ['queueStrip','queueSplit','queueCompact','queueCompactSplit']){
 const bounds=queueBounds(id);
 for(const a of bounds.filter(b=>b.kind==='edge'))for(const b of bounds.filter(b=>b.kind==='item'))if(targetsOverlap(a,b))throw Error(`Queue edge/item overlap: ${id}`);
}
console.log(`${records.length} screen geometries validated: ratios, target separation, bounds, queue travel and nested split ownership.`);
