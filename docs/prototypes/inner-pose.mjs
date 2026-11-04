// pose is the clockwise turn of the unfolded chassis as seen by the user.
// Device names are fixture labels. The renderer supplies the active stream ratio separately.
import { QUEUE_PROFILES, queueItemStart, deriveQueueCapacity } from './queue-geometry.mjs';
export function turnRect(r, width, height, pose) {
  const {x, y, w, h} = r;
  switch (pose) {
    case 0: return {...r};
    case 90: return {x: height-y-h, y: x, w: h, h: w};
    case 180: return {x: width-x-w, y: height-y-h, w, h};
    case 270: return {x: y, y: width-x-w, w: h, h: w};
    default: throw Error('Expected a quarter turn');
  }
}

export function innerPose(w, h, pose, uprightRatio) {
  if (pose !== 0 && pose !== 90) throw Error('Draw only upright and clockwise reference poses');
  const side = pose === 0;
  const width = side ? w : h, height = side ? h : w;
  // Distances in chassis coordinates: 24 outside + 48 target + 8 to the frame.
  const utilityWidth = 80;

  const physicalRatio = side ? uprightRatio : 1/uprightRatio;
  const fw = Math.min(height*physicalRatio, width-utilityWidth-216);
  const fh = fw/physicalRatio;
  const primaryWidth = width-utilityWidth-fw;
  const physical = {x: utilityWidth, y: (height-fh)/2, w: fw, h: fh};
  const turn = r => turnRect(r, width, height, pose);
  const preview = turn(physical);
  const utilityX = 24;
  const library = turn({x:utilityX, y:24, w:48, h:48});
  const settings = turn({x:utilityX, y:height-72, w:48, h:48});
  const libraryVisual = turn({x:16, y:16, w:48, h:48});
  const settingsVisual = turn({x:16, y:height-64, w:48, h:48});
  const railCenter = width-primaryWidth/2;
  // All three rows share one axis. On a shallow primary band use the existing
  // dense shutter and queue tokens while preserving the 48dp zoom targets.
  const dense = !side && primaryWidth < 264;
  const shutterSize = dense ? 64 : 80;
  const queueH = dense ? 64 : 72;
  const queueW = side ? Math.min(360, primaryWidth-48) : dense ? 312 : 360;
  const shutterX = side ? railCenter : w/2;
  const queueX = shutterX-queueW/2;
  const travel = side ? 48 : 24;
  const dockEnd = h-24;
  const queueY = dockEnd-travel-queueH;
  const shutterY = queueY-(dense ? 8 : 12)-shutterSize;
  const zoomX = shutterX-96, zoomY = shutterY-(dense ? 44 : 52);
  const modeWidth = side ? Math.min(250, primaryWidth-24) : 250;
  const modeX = shutterX-modeWidth/2;
  const auxY = shutterY+(shutterSize-48)/2;
  return {pose, side, dense, physical, preview, library, settings, libraryVisual, settingsVisual, railCenter, queueW, queueX, queueY,
    travel, shutterX, shutterY, zoomX, zoomY, modeX, modeWidth, auxY,
    flashX: shutterX-96, lensX: shutterX+48};
}

// Crop the empty space around each shared label; preserve its authored glyph size and weight.
export function innerModes(use, symbol, x, y, width) {
  const slot = width/3;
  return [0,1,2].map(i => {
    const sourceX = (i+.5)*250/3-slot/2;
    return `<svg x="${x+i*slot}" y="${y}" width="${slot}" height="40" viewBox="${sourceX} 0 ${slot} 40">${use(symbol,0,0,250,40)}</svg>`;
  }).join('');
}

// Start of a scrollable tray. The last partial tile fades beneath the fixed right handle.
export function innerQueue(use, id, x, y, width, profileName='standard56') {
  const profile=QUEUE_PROFILES[profileName];
  const capacity=deriveQueueCapacity(width,0,0,profileName);
  const itemStart=queueItemStart(profile.edgeTarget,capacity.endpointMediaGap);
  const mask = id+'-tray-mask', fade = id+'-tray-fade';
  const types = ['Photo','Video','Voice'];
  const tiles = Array.from({length:capacity.visibleCount}, (_,i) =>
    use('queueThumb'+types[i%types.length], itemStart+i*(profile.media+capacity.gap), profile.verticalPadding, profile.media, profile.media)).join('');
  const targetY=profile.verticalPadding, handlerY=(profile.strip-profile.handler)/2;
  const handles = [0,width-profile.edgeTarget].map(start =>
    `<rect x="${start}" y="${targetY}" width="${profile.edgeTarget}" height="${profile.edgeTarget}" fill="transparent" data-role="tap-target"/>`+
    use(profileName==='compact48'?'queueEdgeHandle':'queueEdgeHandleStandard',start+(profile.edgeTarget-6)/2,handlerY,6,profile.handler)).join('');
  return `<svg x="${x}" y="${y}" width="${width}" height="${profile.strip}" data-role="queue">
    <defs><linearGradient id="${fade}" gradientUnits="userSpaceOnUse" x1="${width-56}" x2="${width-16}"><stop stop-color="white"/><stop offset="1" stop-color="white" stop-opacity="0"/></linearGradient>
    <mask id="${mask}" maskUnits="userSpaceOnUse" x="0" y="0" width="${width}" height="${profile.strip}"><rect width="${width}" height="${profile.strip}" fill="url(#${fade})"/></mask></defs>
    <g mask="url(#${mask})">${tiles}</g>${handles}</svg>`;
}
