// Authoritative screen geometry. SVGs are generated; shared shapes stay in the design system.
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { innerPose, innerModes, innerQueue, turnRect } from './inner-pose.mjs';
import { deriveQueueCapacity, QUEUE_PROFILES } from './queue-geometry.mjs';
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
// Runtime contract: reserve asymmetric endpoint safety margins first, then fit
// the queue into the remaining lane. The shared module is the executable source
// for profile tokens and N/P; this screen generator records its outcome.
const queueFit=(availableWidth,startMargin,endMargin,profileName)=>
 deriveQueueCapacity(availableWidth,startMargin,endMargin,profileName);
const compactHeader=()=>use('topBarCompact',0,12,360,72);
const targetsOverlap=(a,b)=>a.x<b.x+b.w-.001&&b.x<a.x+a.w-.001&&a.y<b.y+b.h-.001&&b.y<a.y+a.h-.001;
// `compact` is a viewport fixture flag (for the 360dp cover/phone shell), not
// the queue-size profile. The profile is selected by `dense` and recorded with
// the explicit standard56/compact48 names below.
function screen({id,w,h,tall=true,front=false,mode='photo',recording=false,feedback=null,shell='bottom',hand='center',queue='idle',compact=false,overlay=false,aspect=null,bottomInset=24,pose=null}){
 const video=mode==='video',voice=mode==='voice';
 if(recording&&!video&&!voice)throw Error('PHOTO cannot record');
 const frameRatio=aspect??(video?(tall?9/16:16/9):(tall?3/4:4/3));
 const inner=pose===null?null:innerPose(w,h,pose,frameRatio);
 const dense=inner?.dense??(shell!=='side'&&h-24-bottomInset<600);
 const qWidth=inner?.queueW??(compact||dense?312:360);
 const qX=inner?.queueX??(compact?24:hand==='right'?w-24-qWidth:hand==='left'?24:(w-qWidth)/2);
 const profileName=dense?'compact48':'standard56';
 const profile=QUEUE_PROFILES[profileName];
 const qHeight=profile.strip,shutterSize=dense?64:80,queueGap=dense?8:12;
 const queueMargins={start:qX,end:w-(qX+qWidth)};
 const queueFitResult=queueFit(w,queueMargins.start,queueMargins.end,profileName);
 const travel=inner?.travel??(shell==='side'?48:24);
 const qY=inner?.queueY??(h-bottomInset-travel-qHeight);
 const shutterY=inner?.shutterY??(qY-queueGap-shutterSize), modeY=28;
 const zoomY=inner?.zoomY??(shutterY-52), cx=inner?.shutterX??(qX+qWidth/2);
 const verticalZoom=!inner&&shell==='side'&&frameRatio<1&&!compact&&!voice;
 let box;
 if(inner)box=inner.preview;
 else if(overlay){const top=w/frameRatio>h-96?24:72;box={x:0,y:top,w,h:h-bottomInset-top};}
 else if(compact)box={x:24,y:92,w:w-48,h:zoomY-108};
 else if(shell==='side')box={x:hand==='left'?408:(verticalZoom?96:24),y:84,w:w-432-(verticalZoom?72:0),h:h-bottomInset-84};
 else box={x:24,y:84,w:w-48,h:zoomY-100};
 const ratio=frameRatio,p=inner?.preview??fit(box,ratio);
 if(overlay&&p.w===w){
  // Center a short frame above the controls when it fits there; tall frames retain the full-width overlay composition.
  const freeHeight=zoomY-16-box.y;
  p.y=p.h<=freeHeight?box.y+(freeHeight-p.h)/2:box.y;
 }
 let out=rect(0,0,w,h,'#080808');
 const targets=[];
 const control=(symbol,x,y,sw=48,sh=48)=>{targets.push({symbol,x,y,w:sw,h:sh});out+=use(symbol,x,y,sw,sh);};
 if(recording)out+='<g opacity=".38">';
 if(compact){out+=compactHeader();targets.push({symbol:'settings',x:4,y:24,w:48,h:48},{symbol:'library',x:308,y:24,w:48,h:48});}
 else if(inner){
  for(const [symbol,hit,visual] of [['iconSettings',inner.settings,inner.settingsVisual],['iconLibrary',inner.library,inner.libraryVisual]]){
   out+=use(symbol,visual.x,visual.y,48,48);
   targets.push({symbol,...hit});
  }
 }
 else {control('iconSettings',24,24);control('iconLibrary',w-72,24);}
 if(recording){out+='</g>';targets.length=0;}
 let scene;
 if(!voice){
 if(Math.abs(ratio-.75)<1e-8||Math.abs(ratio-4/3)<1e-8)scene=`scene${ratio<1?'Portrait':'Landscape'}${front?'Front':'Rear'}`;
 else if(Math.abs(ratio-9/16)<1e-8||Math.abs(ratio-16/9)<1e-8)scene=`sceneVideo${ratio<1?'Portrait':'Landscape'}${front?'Front':'Rear'}`;
 if(scene)out+=use(scene,p.x,p.y,p.w,p.h,'data-role="preview"');
 else {const key=`test-scene-${String(n(ratio)).replace('.','-')}`,sw=300,sh=sw/ratio;
 customScenes.set(key,`<symbol id="${key}" viewBox="0 0 ${sw} ${sh}">${rect(0,0,sw,sh,'#9eb8c0')}${rect(0,sh*.58,sw,sh*.42,'#b8784d')}<path d="M0 ${sh*.58}H300M150 0V${sh}" stroke="#fff" stroke-opacity=".4"/><circle cx="230" cy="${sh*.26}" r="32" fill="#f0c66b"/><rect x="30" y="${sh*.16}" width="72" height="${Math.min(130,sh*.3)}" fill="#456274" stroke="#fff" stroke-width="2"/><text x="66" y="${sh*.3}" text-anchor="middle" fill="#fff" font-size="14">UP ↑</text><path d="M170 ${sh-25}l40 -80 35 45 20 -30 35 65Z" fill="#5b7e4a"/><path d="M4 20V4H20M280 4H296V20M4 ${sh-20}V${sh-4}H20M280 ${sh-4}H296V${sh-20}" stroke="#fff" stroke-width="2" fill="none"/></symbol>`);
 out+=`<use href="#${key}" x="${n(p.x)}" y="${n(p.y)}" width="${n(p.w)}" height="${n(p.h)}" data-role="preview"/>`;}
 // In the very tall cover frame the header overlaps: redraw it on its own top scrim.
 if(overlay&&p.y<72){out+=rect(0,24,w,48,'#000').replace('/>',' fill-opacity=".72"/>');if(recording)out+='<g opacity=".38">';out+=compactHeader();if(recording)out+='</g>';}

 } // camera content; VOICE has a quiet canvas, no camera frame or focus marker
 if(!inner&&!video&&!voice)out+=use('focusIndicator',p.x+p.w*.56-40,(overlay?Math.min(p.y+p.h*.30,zoomY-52):p.y+p.h*.52)-40,80,80);
 if(overlay&&!voice)out+=`<defs><linearGradient id="${id}-scrim" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#000" stop-opacity="0"/><stop offset=".32" stop-color="#000" stop-opacity=".72"/><stop offset="1" stop-color="#000" stop-opacity=".85"/></linearGradient></defs><rect x="0" y="${zoomY-48}" width="${w}" height="${h-bottomInset-(zoomY-48)}" fill="url(#${id}-scrim)"/>`;
 // Less-frequent mode selection stays in the top bar; preview swipe is the quick alternative.
 const modeSymbol='modeStrip'+(voice?'Voice':video?'Video':'Photo')+(recording?'Locked':'');
 const modeX=inner?.modeX??(w/2-125), modeWidth=inner?.modeWidth??250;
 out+=modeWidth===250?use(modeSymbol,modeX,modeY,modeWidth,40):innerModes(use,modeSymbol,modeX,modeY,modeWidth);
 if(!recording)for(let k=0;k<3;k++)targets.push({symbol:'mode',x:modeX+k*modeWidth/3,y:modeY-4,w:modeWidth/3,h:48});
 const zx=inner?.zoomX??(verticalZoom?(hand==='left'?w-68:28):cx-96);
 const zy=verticalZoom?p.y+p.h/2-96:zoomY;
 if(!voice){
 if(front){const x=verticalZoom?zx:zx+(inner?76:52),y=verticalZoom?zy+100:zoomY;out+=use('zoomSingle',x,y,40,40);targets.push({symbol:'zoom1x',x:x-4,y:y-4,w:48,h:48});}
 else {out+=use(verticalZoom?'zoomVertical':'zoomChips',zx,zy,verticalZoom?40:192,verticalZoom?192:40);for(let k=0;k<4;k++)targets.push({symbol:'zoom',x:verticalZoom?zx-4:zx+k*48,y:verticalZoom?zy+k*48:zy-4,w:48,h:48});}
 if(recording)out+='<g opacity=".38">';
 control(video?'iconMic':front?'iconFlashOff':'iconFlashAuto',inner?.flashX??(cx-120),inner?.auxY??(shutterY+(shutterSize-48)/2));
 if(recording){out+='</g>';targets.pop();}
 // Front flash off is unavailable in this capability fixture; visible but not an active target.
 if(front&&!video){out=out.replace(/(<use href="#shared-iconFlashOff"[^>]*)(\/>)$/, '$1 opacity=".38"$2');targets.pop();}
 }
 control((dense?'shutterDense':'shutter')+(voice?(recording?'VoiceRecording':'VoiceIdle'):video?(recording?'VideoRecording':'VideoIdle'):'Photo'),cx-shutterSize/2,shutterY,shutterSize,shutterSize);
 if(!voice){if(recording)out+='<g opacity=".38">';control('iconLens',inner?.lensX??(cx+72),inner?.auxY??(shutterY+(shutterSize-48)/2));if(recording){out+='</g>';targets.pop();}}
 const statusBounds=[];
 if(recording&&video){const timer={x:p.x+p.w/2-75,y:Math.max(84,p.y+12),w:150,h:32};out+=use('recordingPill',timer.x,timer.y,timer.w,timer.h);statusBounds.push(timer);}
 if(voice){
  const vx=inner?box.x+box.w/2:shell==='side'?box.x+box.w/2:w/2,vy=inner?box.y+box.h/2:(84+(shell==='side'?h-bottomInset:zoomY-16))/2;
  out+=use(recording?'voiceWaveform':'voiceWaveformIdle',vx-150,vy-60,300,120);
  if(recording){out+=`<circle cx="${vx-62}" cy="${vy-80}" r="5" fill="#EF5350"/>`+text(vx-46,vy-74,'Recording…',16,'#fff');out+=`<text x="${vx}" y="${vy+84}" text-anchor="middle" font-size="22" fill="#fff">0:12</text>`;}
  else out+=`<text x="${vx}" y="${vy+84}" text-anchor="middle" font-size="16" fill="#ccc">Tap shutter to record voice</text>`;
  statusBounds.push({x:vx-150,y:vy-88,w:300,h:176});
 }
 const queueSymbol=inner?(inner.side?'innerQueue':dense?'queueDenseIdle':'queueStrip'):dense?(queue==='deleteArmed'?'queueDenseDeleteArmed':queue.startsWith('long-')?'queueDenseLong'+queue.slice(5):queue==='split'?'queueDenseSplit':'queueDenseIdle'):queue.startsWith('long-')?(compact?'queueStandard312Long':'queueLong')+queue.slice(5):(compact?(queue==='split'?'queueStandard312Split':'queueStandard312'):(queue==='split'?'queueSplit':'queueStrip'));
 if(recording)out+='<g opacity=".25">';
 out+=queueSymbol==='innerQueue'?innerQueue(use,id,qX,qY,qWidth,profileName):use(queueSymbol,qX,qY,qWidth,queue==='deleteArmed'?qHeight+48:qHeight,'data-role="queue"');
 if(recording)out+='</g>';
 let feedbackBounds=null;
 if(feedback){
  feedbackBounds={x:16,y:recording?128:92,w:328,h:feedback.endsWith('Large')?160:48};out+=use(feedback,...Object.values(feedbackBounds));
  // This fixture uses the large banner's real 144x48 Dismiss action, which remains usable during recording.
  if(feedback.endsWith('Large'))targets.push({symbol:'feedbackDismiss',x:feedbackBounds.x+184,y:feedbackBounds.y+112,w:144,h:48});
 }
 if(bottomInset===48)out+=`<path d="M${w/2-70} ${h-30}l-10 6 10 6Z" fill="#888"/><circle cx="${w/2}" cy="${h-24}" r="7" fill="none" stroke="#888" stroke-width="2"/><rect x="${w/2+62}" y="${h-31}" width="14" height="14" fill="#888"/>`;
 else out+=`<rect x="${w/2-50}" y="${h-12}" width="100" height="4" rx="2" fill="#fff" fill-opacity=".45"/>`;
 const record={id,w,h,...(inner?{pose,physicalPreview:voice?null:inner.physical,utilitySlots:{settings:inner.settingsVisual,library:inner.libraryVisual}}:{}),compact,overlay,shell,hand,frame:voice?null:ratio===1?'square':ratio<1?'tall':'wide',camera:voice?null:front?'front':'rear',zoomAxis:voice?null:verticalZoom?'vertical':'horizontal',mode,recording,statusBounds,feedback:feedbackBounds,preview:voice?null:Object.fromEntries(Object.entries(p).map(([k,v])=>[k,n(v)])),ratio:voice?null:ratio,queue:{x:qX,y:qY,w:qWidth,h:qHeight,symbol:queueSymbol,profile:profileName,visibleCount:queueFitResult.visibleCount,outerMargins:{start:n(queueMargins.start),end:n(queueMargins.end)},endpointMediaGap:n(queueFitResult.endpointMediaGap),locked:recording},profile:profileName,shutterSize,safe:{top:24,bottom:bottomInset,side:24},travel,targets};
 if(inner&&[...targets,...(!voice?[p]:[])].some(t=>targetsOverlap(t,{x:qX,y:qY,w:qWidth,h:qHeight})))throw Error(`Queue overlap ${id}`);
 for(const t of targets){if(t.w<48||t.h<48)throw Error(`Undersized target ${id} ${t.symbol}`);if(t.x<0||t.y<24||t.x+t.w>w||t.y+t.h>h-bottomInset)throw Error(`Unsafe target ${id} ${t.symbol}`);if(!overlay&&!voice&&targetsOverlap(t,p))throw Error(`Preview/control overlap ${id} ${t.symbol}`);}
 for(let i=0;i<targets.length;i++)for(let j=i+1;j<targets.length;j++)if(targetsOverlap(targets[i],targets[j]))throw Error(`Overlapping targets ${id}`);
 if(qX<24||qX+qWidth>w-24||qY+qHeight+travel>h-bottomInset)throw Error(`Unsafe queue ${id}`);
 if(qY+8+(dense?48:56)+32>h-bottomInset)throw Error(`Unsafe bottommost delete start ${id}`);
 if(qY+qHeight+24>h-bottomInset)throw Error(`Unsafe split endpoint ${id}`);
 if(Math.abs(p.w/p.h-ratio)>1e-8)throw Error(`Distorted frame ${id}`);
 for(const b of [...statusBounds,...(feedbackBounds?[feedbackBounds]:[])]){
  if(b.x<0||b.y<24||b.x+b.w>w||b.y+b.h>qY)throw Error(`Unsafe status/feedback ${id}`);
  for(const t of targets)if(!(b===feedbackBounds&&t.symbol==='feedbackDismiss')&&targetsOverlap(b,t))throw Error(`Status/control overlap ${id}`);
 }
 if(feedbackBounds&&statusBounds.some(b=>targetsOverlap(b,feedbackBounds)))throw Error(`Feedback hides recording state ${id}`);
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
 const name=ultra?'Fold8 Ultra':'Fold8';
 const baseW=960,baseH=ultra?864:720,W=2040;
 let body=text(32,44,`Recon / ${name} unfolded`,32,'#fff');
 body+=text(32,78,'Follow the amber corner of the same device. Each board is 1:1. All direction labels and corner marks are outside the app.',18);
 body+=text(32,106,ultra?'Near-square geometry study. Front-camera and Ultra poses are representative fixtures.':'Rear PHOTO follows the supplied Samsung references. Front-camera pose and lens capabilities are representative fixtures.');
 let y=154;
 for(const mode of ['photo','video'])for(const lens of ['rear','front']){
  body+=text(32,y,`${mode.toUpperCase()}   /   ${lens==='rear'?'Rear camera':'Front camera'}`,24,'#fff');y+=56;
  for(const [i,pose] of [0,90].entries()){
   const side=pose%180===0,w=side?baseW:baseH,h=side?baseH:baseW;
   const x=32+(i%2)*1024+(960-w)/2,top=y;
   const direction=['Upright','Clockwise 90°'][i];
   const id=`${ultra?'ultra':'fold8'}-p${pose}-${lens}-${mode}`;
   body+=text(x,top-16,direction,17,'#fff');
   body+=`<g transform="translate(${x} ${top})">${screen({id,w,h,pose,tall:side,front:lens==='front',mode,shell:side?'side':'bottom',hand:side?'right':'center'})}</g>`;
   const right=pose===90;
   const mx=x+(right?w+4:-4),my=top-4;
   body+=`<path d="M${mx+(right?-14:14)} ${my}H${mx}v14" fill="none" stroke="#edb96b" stroke-width="2"/>`;
   if(i%2===1)y+=baseW+78;
  }
 }
 body+=text(32,y-12,'Opposite grips mirror placement. Keep labels upright and the queue in reading order. Rotation continues recording. Fold or unfold finalizes the take.',16);
 await save(`recon-capture-${ultra?'fold8-ultra':'fold8'}-inner.svg`,document(W,y+24,`${name} physical rotation study`,body));
}
await matrix(false);await matrix(true);
// Separate engineering/gesture fixtures, not permanent labels or guides in product screens.
let body=text(32,42,'Generic responsive layout checks / frame direction, handedness and hinge',28,'#fff');
body+=text(32,74,'Generic fallback references only; Fold8/Fold8 Ultra C composition is authoritative in the C matrices. Counterexamples prevent hardcoding window-wide → camera-wide, or its inverse.');
const cases=[
 {id:'wide-wide',w:960,h:720,tall:false,shell:'side',hand:'right',label:'Wide window / wide frame — transform exception'},
 {id:'wide-left',w:960,h:720,tall:true,shell:'side',hand:'left',queue:'split',label:'Resized window / left-hand dock and split queue (free window)'},
 {id:'tall-tall',w:720,h:960,tall:true,label:'Tall window / tall frame — transform exception'},
 {id:'near-square-tall',w:864,h:960,tall:true,label:'Near-square / tall frame — bottom dock gives the larger fitted frame'},
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
 'Bottom dock: 72/64dp queue + 24dp travel + actual bottom inset.',
 'Preview fits uniformly; compact48 visuals shrink, targets stay at least 48dp.',
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
body+=text(32,3720,'Long queue / same cover layout, fixed handles above fading media',24,'#fff');
body+=text(32,3752,'All four states are clean 360 × 576 app views. Explanatory captions stay outside the viewport.');
for(const [i,state] of ['Start','Middle','End','Split'].entries()){
 const x=32+i*488;body+=`<g transform="translate(${x} 3784)">${screen({id:`cover-queue-${state.toLowerCase()}`,w:360,h:576,compact:true,overlay:true,queue:`long-${state}`})}</g>`;
 body+=text(x,4390,`${state} / compact48 · 48dp edge targets`,16,'#fff');
}
body+=text(32,4478,'Bottom anchoring / short windows, active deletion and larger system insets',24,'#fff');
body+=text(32,4510,'Usable height = full window height minus top/bottom insets. compact48 is the measured-space fallback; freeze profile during any active gesture.');
for(const [i,c] of [
 {id:'short-480',h:480,label:'360 × 480 / compact48 / 24dp bottom inset'},
 {id:'short-640',h:640,label:'360 × 640 / compact48 / 24dp bottom inset'},
 {id:'cover-delete',h:576,queue:'deleteArmed',label:'Cover / delete armed / 32dp display travel'},
 {id:'cover-nav48',h:576,bottomInset:48,label:'Cover / illustrative 48dp button-nav inset'}
].entries()){
 const x=32+i*488;body+=`<g transform="translate(${x} 4540)">${screen({w:360,compact:true,overlay:true,...c})}</g>`+text(x,4540+c.h+28,c.label,15,'#fff');
}
await save('recon-capture-layout-checks.svg',document(2040,5240,'Recon layout validation fixtures',body));
// Common output ratios are fixtures, not a new in-app aspect selector.
let ratiosBody=text(32,42,'Generic framing reference / phone, cover and inner-window layout',28,'#fff');
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
 {id:`ratio-${i}-inner`,w:960,h:720,shell:'side',hand:'right',pose:0,aspect:c.full?4/3:c.video?16/9:c.ratio}];
 for(let k=0;k<specs.length;k++){const x=[32,424,848][k],spec=specs[k];
 ratiosBody+=`<g transform="translate(${x} ${y+28})">${screen({...spec,mode:c.video?'video':'photo'})}</g>`;
 ratiosBody+=text(x,y+spec.h+54,`${['Phone','Cover','Inner'][k]} ${spec.w} × ${spec.h} / frame ratio ${n(spec.aspect)}`,14,'#fff');}
 if(i===1||i===2)ratiosBody+=text(424,y+660,'Short cover: 297 × 528 full frame; width-fit would crop.',14);
 if(c.full)ratiosBody+=text(424,y+660,'Full varies by selected output: 9:20 / 5:8 / 4:3 examples.',14);
}
ratiosBody+=text(32,4880,'Wide 4:3 and 16:9 upright frames appear in the inner matrices. A square frame does not swap axes on rotation.',16);
ratiosBody+=text(32,4910,'Samsung documents 3:4, 9:16, 1:1 and Full; Pixel documents full-image 4:3 and wide-crop 16:9. Sources in camera-layout-implementation.md.',14);
await save('recon-capture-aspect-ratios.svg',document(2040,4960,'Recon common aspect ratio designs',`<defs>${[...customScenes.values()].join('')}</defs>${ratiosBody}`));
// Real window states share the same screen() geometry as idle capture; annotations stay outside.
let statesBody=text(32,42,'Generic capture-state reference / fixed anchors through mode and recording changes',28,'#fff');
statesBody+=text(32,74,'Generic PHONE / COVER / WIDE INNER reference; Fold8/Fold8 Ultra C composition is in the current C matrices. Recording locks navigation, modes, side controls and queue. Zoom and Stop remain available.');
for(const [row,state] of [{mode:'video',recording:true,label:'VIDEO recording'},{mode:'voice',label:'VOICE idle'},{mode:'voice',recording:true,label:'VOICE recording'}].entries()){
 const y=132+row*920;
 for(const [col,fixture] of [{name:'phone',w:360,h:800,compact:true,overlay:true},{name:'cover',w:360,h:576,compact:true,overlay:true},{name:'inner',w:960,h:720,shell:'side',hand:'right',pose:0}].entries()){
  const x=[32,424,848][col];
  statesBody+=`<g transform="translate(${x} ${y})">${screen({...fixture,...state,id:`state-${fixture.name}-${row}`})}</g>`;
  statesBody+=text(x,y+fixture.h+28,`${fixture.name} / ${state.label}`,16,'#fff');
 }
}
statesBody+=text(32,2932,'Recording feedback and rotated inner window',24,'#fff');
statesBody+=text(32,2964,'Timer has its own host; error starts below it. Stop stays reachable. Rotation preserves the take; fold/unfold finalizes.');
statesBody+=`<g transform="translate(32 2996)">${screen({id:'recording-cover-error',w:360,h:576,compact:true,overlay:true,mode:'video',recording:true,feedback:'bannerPriorCaptureErrorLarge'})}</g>`;
statesBody+=text(32,3600,'Cover / VIDEO recording + 2× error text',15,'#fff');
statesBody+=`<g transform="translate(488 2996)">${screen({id:'recording-inner-tall',w:720,h:960,tall:false,pose:90,mode:'video',recording:true})}</g>`;
statesBody+=text(488,3984,'Tall inner / wide 16:9 VIDEO recording',16,'#fff');
await save('recon-capture-recording-states.svg',document(2040,4040,'Recon recording and voice window states',statesBody));
await save('layout-geometry.json',JSON.stringify({units:'logical dp-like; insets illustrative',screens:records},null,2)+'\n');
// Check nested queue ownership too: screen-level targets alone miss expanded split gaps.
const canonical=await readFile(join(root,'recon-capture-design-system.svg'),'utf8');
const symbols=new Map([...canonical.matchAll(/<symbol\b[^>]*id="([^"]+)"[^>]*>([\s\S]*?)<\/symbol>/g)].map(m=>[m[1],m[2]]));
for(const {id,queue} of records){
 if(queue.symbol==='innerQueue'){
  const profile=QUEUE_PROFILES[queue.profile];
  if(!profile||queue.h!==profile.strip||queue.w-2*profile.edgeTarget<profile.media)throw Error('Narrow tray cannot expose a full tile: '+id);
  continue;
 }
 const match=canonical.match(new RegExp('<symbol id="'+queue.symbol+'" viewBox="0 0 ([0-9]+) ([0-9]+)"'));
 if(!match||+match[1]!==queue.w)throw Error(`Scaled queue width ${id}`);
 if(+match[2]!==queue.h&&queue.symbol!=='queueDenseDeleteArmed')throw Error(`Scaled queue height ${id}`);
}
const attrs=tag=>Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map(m=>[m[1],m[2]]));
function queueBounds(id,ox=0,oy=0){
 const result=[];
 if(/^queue(Dense)?Thumb(Photo|Video|Voice)$/.test(id)){const size=id.includes('Dense')?48:56;return [{x:ox,y:oy,w:size,h:size,kind:'item'}];}
 const source=symbols.get(id);if(!source)throw Error(`Missing queue symbol ${id}`);
 for(const match of source.matchAll(/<(use|rect)\b[^>]*\/>/g)){
  const a=attrs(match[0]),x=ox+(+a.x||0),y=oy+(+a.y||0);
  if(match[1]==='use' && a.href.startsWith('#queue'))result.push(...queueBounds(a.href.slice(1),x,y));
  else if(match[1]==='rect'&&a['data-role']==='tap-target')result.push({x,y,w:+a.width,h:+a.height,kind:'edge'});
 }
 return result;
}
for(const id of ['queueStrip','queueSplit','queueStandard312','queueStandard312Split','queueDenseIdle','queueDenseSplit']){
 const bounds=queueBounds(id);
 for(const a of bounds.filter(b=>b.kind==='edge'))for(const b of bounds.filter(b=>b.kind==='item'))if(targetsOverlap(a,b))throw Error(`Queue edge/item overlap: ${id}`);
}
// Protect the grip reference: the queue stays below the shutter on the same axis.
for(const r of records.filter(r=>r.pose!==undefined)){
 const shutter=r.targets.find(t=>t.symbol.startsWith('shutter'));
 const center=shutter.x+shutter.w/2;
 const zoom=r.targets.filter(t=>t.symbol.startsWith('zoom'));
 if(Math.abs(r.queue.x+r.queue.w/2-center)>.001 || r.queue.y<shutter.y+shutter.h ||
    zoom.some(t=>t.y+t.h>shutter.y))throw Error(`Capture rows split in ${r.id}`);
 if(zoom.length===4 && Math.abs(zoom[0].x+96-center)>.001)throw Error(`Zoom off axis in ${r.id}`);
}
for(const base of records.filter(r=>r.pose===0&&r.id.includes('-p0-'))){
 const slots=base.utilitySlots;
 if(slots.library.x+24!==40||slots.library.y+24!==40||slots.settings.x+24!==40||base.h-slots.settings.y-24!==40)throw Error('Asymmetric utility glyphs in '+base.id);
 const shutter=base.targets.find(t=>t.symbol.startsWith('shutter'));
 const frameEnd=base.preview.x+base.preview.w;
 if(shutter.x+shutter.w/2!==(base.w+frameEnd)/2)throw Error('Dock is not centered in available space: '+base.id);
 if(base.preview.x!==80||base.preview.y!==0||base.preview.h!==base.h||base.targets.filter(t=>['iconLibrary','iconSettings'].includes(t.symbol)).some(t=>t.x!==24 || Math.min(t.y,base.h-t.y-t.h)!==24))throw Error(`Inconsistent physical utility edge in ${base.id}`);
 const rotated=records.find(r=>r.id===base.id.replace('-p0-','-p90-'));
 for(const key of ['preview','iconLibrary','iconSettings']){
  const bounds=record=>key==='preview'?record.preview:record.targets.find(t=>t.symbol===key);
  const mapped=turnRect(bounds(rotated),rotated.w,rotated.h,270);
  if(['x','y','w','h'].some(k=>Math.abs(mapped[k]-bounds(base)[k])>.001))throw Error(`Physical ${key} moved in ${rotated.id}`);
 }
}
// Cross-state anchors are part of the contract, not separate hand-positioned specimens.
for(const rear of records.filter(r=>r.id.includes('-rear-'))){
 const front=records.find(r=>r.id===rear.id.replace('-rear-','-front-'));if(!front)continue;
 const common=rear.targets.filter(t=>t.symbol==='zoom')[rear.zoomAxis==='vertical'?2:1];
 const single=front.targets.find(t=>t.symbol==='zoom1x');
 const anchorX=rear.pose===undefined?common.x+common.w/2:rear.targets.find(t=>t.symbol.startsWith('shutter')).x+rear.shutterSize/2;
 if(anchorX!==single.x+single.w/2||common.y+common.h/2!==single.y+single.h/2)throw Error(`1x moved across lens change ${rear.id}`);
}
for(const r of records.filter(r=>r.id.startsWith('state-'))){
 const fixture=r.id.split('-')[1],base=records.find(b=>b.id===(fixture==='inner'?(r.mode==='video'?'fold8-p0-rear-video':'fold8-p0-rear-photo'):fixture));
 const shutter=r.targets.find(t=>t.symbol.startsWith('shutter')),baseShutter=base.targets.find(t=>t.symbol.startsWith('shutter'));
 if(shutter.x!==baseShutter.x||shutter.y!==baseShutter.y||r.queue.x!==base.queue.x||r.queue.y!==base.queue.y)throw Error(`Mode moved capture anchors ${r.id}`);
 if(r.mode==='voice'&&(r.preview!==null||r.targets.some(t=>t.symbol.startsWith('zoom')||t.symbol==='iconLens')))throw Error(`VOICE exposes camera UI ${r.id}`);
 if(r.recording&&(!r.queue.locked||r.targets.some(t=>!t.symbol.startsWith('zoom')&&!t.symbol.includes('Recording')&&t.symbol!=='feedbackDismiss')))throw Error(`Recording unlocks mutation/navigation ${r.id}`);
}
console.log(`${records.length} screen geometries validated: ratios, target separation, bounds, queue travel and nested split ownership.`);

// Overflow allows visual overlap, but edge ownership has priority on pointer-down.
// Once selected, that owner lasts for the whole sequence (runtime acceptance still required).
for(const [prefix,w] of [['queueLong',360],['queueStandard312Long',312],['queueDenseLong',312]]){
 for(const state of ['Start','Middle','End','Split','Commit','Discard']){
  const id=prefix+state,source=symbols.get(id),bounds=queueBounds(id),dense=prefix.includes('Dense'),edgeY=8,size=dense?48:56;
  const edgeSize=dense?48:56;
  const edges=bounds.filter(b=>b.kind==='edge'),items=bounds.filter(b=>b.kind==='item');
  if(edges.length!==2||!edges.some(b=>b.x===0&&b.y===edgeY&&b.w===edgeSize&&b.h===edgeSize)||!edges.some(b=>b.x===w-edgeSize&&b.y===edgeY&&b.w===edgeSize&&b.h===edgeSize))throw Error(`Overflow edge target changed: ${id}`);
  if(!source.startsWith('<use href="#'+prefix))throw Error(`Overflow media must paint before handles: ${id}`);
  const media=symbols.get(prefix+(['Commit','Discard'].includes(state)?'Middle':state)+'Media');
  if(!media.includes(`mask="url(#${dense?'queueStandard312Long':prefix}Mask)"`))throw Error(`Overflow mask missing: ${id}`);
  const contains=(b,x,y)=>x>=b.x&&x<b.x+b.w&&y>=b.y&&y<b.y+b.h;
  const owner=(x,y)=>edges.some(b=>contains(b,x,y))?'edge':items.some(b=>contains(b,x,y))?'item':'gap';
  for(const edge of edges)for(const item of items){
   if(!targetsOverlap(edge,item))continue;
   const x=(Math.max(edge.x,item.x)+Math.min(edge.x+48,item.x+size))/2;
   if(owner(x,36)!=='edge')throw Error(`Overflow thumbnail stole handle: ${id}`);
  }
  if(state==='Start'&&items[0].x!==56)throw Error(`First item inaccessible: ${id}`);
  if(state==='End'&&items.at(-1).x+size!==w-56)throw Error(`Last item inaccessible: ${id}`);
  if(state==='Split'&&items[3].x-(items[2].x+size)!==24)throw Error(`Overflow split gap changed: ${id}`);
 }
}
console.log('18 overflow states validated: invariant edge zones, media masks, endpoint access, split spacing and initial-owner priority.');
