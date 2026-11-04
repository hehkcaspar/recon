// Reference data only. Display geometry comes from the linked captures.
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('.', import.meta.url));
const font = (await readFile(root+'assets/Roboto.ttf')).toString('base64');
const rows = [
  ['Fold8','Cover',1248,1972,420,'Community / 2026-09-22'],
  ['Fold8','Unfold',2448,1848,420,'Community / 2026-09-22'],
  ['Fold8 Ultra','Cover',1080,2520,480,'Community / 2026-09-23'],
  ['Fold8 Ultra','Unfold',2256,2504,480,'Community / 2026-09-23'],
  ['Pixel 9 Pro Fold AVD','Cover',1080,2424,390,'Local AVD / 2026-11-03'],
  ['Pixel 9 Pro Fold AVD','Unfold',2076,2152,390,'Local AVD / 2026-11-03']
];
const esc = s => String(s).replaceAll('&','&amp;').replaceAll('<','&lt;');
const text = (x,y,t,size=18,fill='#ddd',weight=400) =>
  `<text x="${x}" y="${y}" font-size="${size}" fill="${fill}" font-weight="${weight}">${esc(t)}</text>`;
const number = n => Number.isInteger(n) ? String(n) : n.toFixed(4);
const rect = (x,y,w,h,fill) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${fill}"/>`;
let body = text(40,58,'Recon / Foldable display measurements',32,'#fff',700);
body += text(40,94,'Reference sheet • community device captures + locally measured Pixel AVD • recorded 2026-11-03');
body += text(40,128,'Dimensions below are full active display-window bounds in the recorded orientation. Read system insets separately.',17,'#bbb');
const columns = [56,340,480,730,870,1160];
body += rect(40,160,1600,52,'#26303b');
for(const [i,name] of ['Device','State','Window px','Density dpi','Window dp','Evidence / capture date'].entries())
  body += text(columns[i],193,name,18,'#fff',700);
for (const [i,[device,state,w,h,dpi,evidence]] of rows.entries()) {
  const y = 212+i*72;
  body += rect(40,y,1600,72,i%2?'#202020':'#181818');
  const values = [device,state,`${w} × ${h}`,dpi,`${number(w*160/dpi)} × ${number(h*160/dpi)}`,evidence];
  for(const [k,v] of values.entries())body += text(columns[k],y+43,v,k===0?19:18,k===4?'#fff':'#ddd',k===4?700:400);
}
body += text(40,686,'Precision and scope',23,'#fff',700);
for(const [i,line] of [
  'dp = px × 160 / densityDpi. Pixel dimensions and density are recorded values; recurring dp decimals are rounded to four places.',
  'Use densityDpi, not the panel’s physical PPI. Fold8’s rounded JSON density 2.63 is represented here by 420 / 160 = 2.625.',
  'Samsung captures: Android 17 / One UI 9.0; SM-F971N (Fold8), SM-F976U (Ultra). Community measurements, independently unreplicated here.',
  'Pixel capture: recon-fold-api36, pixel_9_pro_fold profile, API 36 AOSP/Google APIs. This is the local AVD configuration.',
  'Unfolded records: Fold8 is wide at rotation 0; Ultra and Pixel are tall at rotation 0. Width/height order follows each capture.'
].entries())body+=text(40,728+i*34,line,17);
body += text(40,930,'Wide-window calculation / design discussion',23,'#fff',700);
body += text(40,964,'Uniform full-height portrait frame, 80dp utility strip. These derived remainders exclude system-inset deductions.',17);
body += rect(40,988,1600,44,'#26303b');
for(const [x,v] of [[56,'Inner screen'],[480,'Wide view dp'],[870,'3:4 frame / right space'],[1240,'9:16 frame / right space']])
  body+=text(x,1017,v,17,'#fff',700);
for(const [i,r] of rows.filter(r=>r[1]==='Unfold').entries()){
  const width=Math.max(r[2],r[3])*160/r[4],height=Math.min(r[2],r[3])*160/r[4],y=1032+i*48;
  body+=rect(40,y,1600,48,i%2?'#202020':'#181818');
  body+=text(56,y+31,r[0]);
  body+=text(480,y+31,`${number(width)} × ${number(height)}`);
  body+=text(870,y+31,`${number(height*.75)} / ${number(width-80-height*.75)}`);
  body+=text(1240,y+31,`${number(height*9/16)} / ${number(width-80-height*9/16)}`);
}
body+=text(40,1212,'Historical calibration reference: approximately 400dp usable control-region width; not a product breakpoint. Recalculate after actual drawing/gesture insets.',17,'#edb96b');
body+=text(40,1260,'Sources and local evidence',23,'#fff',700);
const links=[
 ['Fold8 original captures • SM-F971N','https://github.com/easyhooon/windowinsets.info/tree/main/measurements/galaxy-fold/galaxy-z-fold8/recapture-2026-09-22'],
 ['Ultra original captures • SM-F976U','https://github.com/easyhooon/windowinsets.info/tree/main/measurements/galaxy-fold/galaxy-z-fold8-ultra'],
 ['Pixel AVD • local capture commands and values are embedded in this SVG metadata',null]
];
for(const [i,[label,url]] of links.entries()){
 const t=text(40,1298+i*32,label,17,url?'#91bdf6':'#ccc');
 body+=url?`<a href="${url}">${t}</a>`:t;
}
const evidence = await Promise.all(['pixel-fold-opened.txt','pixel-fold-closed.txt'].map(async name => {
  const all=await readFile(root+'measurements/'+name,'utf8');
  const useful=all.split('\n').filter(l=>/^(google\/|Committed state|Physical size|Physical density)|init=|overrideConfig=/.test(l));
  return {name,observedAt:'2026-11-03',command:"adb -s emulator-5580 shell 'getprop ro.build.fingerprint; cmd device_state state; wm size; wm density; dumpsys window displays'",lines:useful};
}));
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1680" height="1420" viewBox="0 0 1680 1420">
<title>Recon foldable display measurement reference</title>
<metadata>${esc(JSON.stringify({rows,pixelAvdEvidence:evidence}))}</metadata>
<style>@font-face{font-family:Roboto;src:url("data:font/ttf;base64,${font}") format("truetype");font-weight:100 900;}text{font-family:Roboto,Arial,sans-serif}</style>
${rect(0,0,1680,1420,'#111')}${body}</svg>\n`;
const destination=root+'recon-capture-display-metrics.svg';
if(process.argv.includes('--check')){
  if(await readFile(destination,'utf8')!==svg)throw Error('Stale display reference SVG');
}else await writeFile(destination,svg);
