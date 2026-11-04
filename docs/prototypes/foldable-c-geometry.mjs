// Generate and validate the current Fold8 / Fold8 Ultra C geometry reference.
// The hand-authored JSON source is the only numeric truth. This file adds
// derived capacities, provenance and cross-artifact checks; it is not an input
// to any SVG generator.
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { C_GEOMETRY_SOURCE, QUEUE_PROFILES, deriveQueueCapacity, queueItemStart } from './queue-geometry.mjs';

const root = fileURLToPath(new URL('.', import.meta.url));
const destination = join(root, 'foldable-c-geometry.json');
const check = process.argv.includes('--check');
const generatedOutputs = [
  'foldable-c-geometry.json',
  'recon-capture-fold8-c.svg',
  'recon-capture-fold8-ultra-c.svg',
  'recon-capture-fold8-ultra-c-upright.svg',
  'recon-capture-fold8-ultra-c-cw90.svg',
  'recon-capture-foldable-c-gesture-guide.svg',
];
const sourceInputs = [
  'foldable-c-geometry-source.json',
  'queue-geometry.mjs',
  'queue_geometry.py',
  'foldable-c-study.py',
  'ultra-c-study.py',
  'ultra-c-cw90-study.py',
  'recon-capture-fold8-inner.svg',
  'recon-capture-fold8-ultra-inner.svg',
  'recon-capture-design-system.svg',
];

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const hashFiles = async names => Object.fromEntries(await Promise.all(names.map(async name => [
  name, sha256(await readFile(join(root, name))),
])));
const nearly = (a, b) => Math.abs(Number(a) - Number(b)) <= 0.001;
const fail = message => { throw new Error(message); };

function assertBox(actual, expected, label) {
  for (const key of ['x', 'y', 'width', 'height']) {
    if (!nearly(actual[key], expected[key])) fail(`${label} ${key}: ${actual[key]} != ${expected[key]}`);
  }
}

function attr(tag, name) {
  const match = tag.match(new RegExp(`\\b${name}="([^"]+)"`));
  return match ? Number(match[1]) : NaN;
}

function boxFromTag(tag) {
  return { x: attr(tag, 'x'), y: attr(tag, 'y'), width: attr(tag, 'width'), height: attr(tag, 'height') };
}

function useTag(block, href) {
  const match = block.match(new RegExp(`<use[^>]*href="#${href}"[^>]*/?>`));
  if (!match) fail(`Missing ${href}`);
  return match[0];
}

function queueBlock(frame) {
  const match = frame.match(/<g[^>]*data-role="queue"[^>]*>[\s\S]*?<\/g>/);
  if (!match) fail('Missing queue group');
  return match[0];
}

function controlBlock(frame) {
  const match = frame.match(/<g[^>]*data-role="main-controls"[^>]*>[\s\S]*?<\/g>/);
  if (!match) fail('Missing main-controls group');
  return match[0];
}

function targetRect(queue) {
  const match = queue.match(/<rect[^>]*data-role="tap-target"[^>]*\/>/) ?? queue.match(/<rect[^>]*\/>/);
  if (!match) fail('Missing queue endpoint target');
  return boxFromTag(match[0]);
}

function frameFor(matrix, x, y) {
  const match = matrix.match(new RegExp(`<g transform="translate\\(${x} ${y}\\)">([\\s\\S]*?)<\\/g><path`));
  if (!match) fail(`Missing matrix frame translate(${x} ${y})`);
  return match[1];
}

function validateSourcePreviews(sourceSvg, deviceName) {
  const previews = [...sourceSvg.matchAll(/<use[^>]*data-role="preview"[^>]*\/>/g)].map(match => boxFromTag(match[0]));
  if (previews.length !== 8) fail(`${deviceName} source fixture has ${previews.length} previews, expected 8`);
  const device = C_GEOMETRY_SOURCE.devices[deviceName];
  for (const [index, preview] of previews.entries()) {
    const mode = index >= 4 ? 'video' : 'photo';
    const pose = index % 2 === 0 ? 'upright' : 'cw90';
    assertBox(preview, device[pose].previews[mode], `${deviceName} source ${pose} ${mode} preview`);
  }
}

function validateBalance(deviceName, poseName) {
  const pose = C_GEOMETRY_SOURCE.devices[deviceName][poseName];
  if (!pose.visualBalance) return;
  const queue = pose.queue.bounds.photo;
  const controls = pose.controlBounds.photo;
  const actual = [
    queue.x,
    controls.x - (queue.x + queue.width),
    pose.viewport.width - (controls.x + controls.width),
  ];
  const expected = pose.visualBalance.outerMargins;
  if (actual.some((value, index) => !nearly(value, expected[index]))) {
    fail(`${deviceName} ${poseName} outer-margin drift: ${actual.join('/')} != ${expected.join('/')}`);
  }
  if (Math.max(...actual) / Math.min(...actual) > 1.1) fail(`${deviceName} ${poseName} outer margins are no longer approximately 1:1:1`);
}

function validateFrame(frame, deviceName, poseName, mode, camera, expected) {
  const preview = expected.previews[mode];
  const previewTag = frame.match(/<use[^>]*data-role="preview"[^>]*\/>/);
  if (!previewTag) fail(`Missing ${deviceName} ${poseName} ${mode} preview`);
  assertBox(boxFromTag(previewTag[0]), preview, `${deviceName} ${poseName} ${mode} preview`);

  const queue = queueBlock(frame);
  const queueExpected = expected.queue.bounds[mode];
  assertBox(targetRect(queue), { ...queueExpected, width: 56, height: 56 }, `${deviceName} ${poseName} ${mode} queue target`);
  const controls = controlBlock(frame);
  const control = expected.controlBounds[mode];
  const zoomHref = camera === 'front' ? 'shared-zoomSingle' : 'shared-zoomChips';
  assertBox(boxFromTag(useTag(controls, zoomHref)), control.zoom[camera], `${deviceName} ${poseName} ${mode} ${camera} zoom`);
  const shutterHref = mode === 'video' ? 'shared-shutterVideoIdle' : 'shared-shutterPhoto';
  assertBox(boxFromTag(useTag(controls, shutterHref)), control.shutter, `${deviceName} ${poseName} ${mode} shutter`);
  const auxHref = mode === 'video' ? 'shared-iconMic' : camera === 'front' ? 'shared-iconFlashOff' : 'shared-iconFlashAuto';
  assertBox(boxFromTag(useTag(controls, auxHref)), control.aux, `${deviceName} ${poseName} ${mode} auxiliary control`);
  assertBox(boxFromTag(useTag(controls, 'shared-iconLens')), { ...control.aux, x: control.aux.x + 144 }, `${deviceName} ${poseName} ${mode} lens`);
  if (!nearly(control.shutter.y - control.zoom[camera].y - control.zoom[camera].height, 12)) {
    fail(`${deviceName} ${poseName} ${mode} ${camera} zoom-to-shutter gap is not 12dp`);
  }
  if (expected.queue.axis === 'horizontal') {
    const queueCenterY = queueExpected.y + queueExpected.height / 2;
    const shutterCenterY = control.shutter.y + control.shutter.height / 2;
    if (!nearly(queueCenterY, shutterCenterY)) fail(`${deviceName} ${poseName} ${mode} y-center mismatch`);
  } else {
    const queueCenterX = queueExpected.x + queueExpected.width / 2;
    const shutterCenterX = control.shutter.x + control.shutter.width / 2;
    if (!nearly(queueCenterX, shutterCenterX)) fail(`${deviceName} ${poseName} ${mode} x-center mismatch`);
  }
}

function validateMatrix(matrix, deviceName, expected, rightX) {
  for (const [row, mode, camera] of [
    [0, 'photo', 'rear'], [2, 'photo', 'front'], [4, 'video', 'rear'], [6, 'video', 'front'],
  ]) {
    const rowY = 210 + (row / 2) * 1094;
    validateFrame(frameFor(matrix, 32, rowY), deviceName, 'upright', mode, camera, expected.upright);
    validateFrame(frameFor(matrix, rightX, rowY), deviceName, 'cw90', mode, camera, expected.cw90);
  }
}

function validateFocused(svg, deviceName, poseName, mode = 'photo') {
  const expected = C_GEOMETRY_SOURCE.devices[deviceName][poseName];
  validateFrame(svg, deviceName, poseName, mode, 'rear', expected);
}

function validateGuideSchematic(svg, poseName) {
  const expected = C_GEOMETRY_SOURCE.devices.Fold8[poseName];
  const match = svg.match(new RegExp(`<g[^>]*data-role="screen-schematic"[^>]*>[\\s\\S]*?<\\/g>`));
  if (!match) fail(`Gesture guide is missing the ${poseName} screen schematic`);
  const schematic = match[0];
  const previewTag = schematic.match(/<use[^>]*data-role="preview"[^>]*\/>/);
  if (!previewTag) fail(`Gesture guide is missing the ${poseName} preview schematic`);
  assertBox(boxFromTag(previewTag[0]), expected.previews.photo, `Gesture guide ${poseName} preview`);
  const queue = queueBlock(schematic);
  const queueBounds = expected.queue.bounds.photo;
  assertBox(targetRect(queue), { ...queueBounds, width: 56, height: 56 }, `Gesture guide ${poseName} queue target`);
  const zoom = useTag(schematic, 'shared-zoomChips');
  assertBox(boxFromTag(zoom), expected.controlBounds.photo.zoom.rear, `Gesture guide ${poseName} zoom`);
  assertBox(boxFromTag(useTag(schematic, 'shared-shutterPhoto')), expected.controlBounds.photo.shutter, `Gesture guide ${poseName} shutter`);
}

function validateGestureGuide(svg) {
  const fold8 = C_GEOMETRY_SOURCE.devices.Fold8;
  const upright = fold8.upright;
  const cw90 = fold8.cw90;
  const expectedLabels = [
    `Preview x${upright.previews.photo.x}–${upright.previews.photo.x + upright.previews.photo.width}`,
    `queue x${upright.queue.bounds.photo.x}–${upright.queue.bounds.photo.x + upright.queue.bounds.photo.width}, y${upright.queue.bounds.photo.y}–${upright.queue.bounds.photo.y + upright.queue.bounds.photo.height}`,
    `Preview x${cw90.previews.photo.x}–${cw90.previews.photo.x + cw90.previews.photo.width}, y${cw90.previews.photo.y}–${cw90.previews.photo.y + cw90.previews.photo.height}`,
    `queue x${cw90.queue.bounds.photo.x}–${cw90.queue.bounds.photo.x + cw90.queue.bounds.photo.width}, y${cw90.queue.bounds.photo.y}–${cw90.queue.bounds.photo.y + cw90.queue.bounds.photo.height}`,
  ];
  for (const label of expectedLabels) if (!svg.includes(label)) fail(`Gesture guide lost coordinate label: ${label}`);
  for (const token of ['↓ discard', '↑ save/commit', 'item → delete', 'hold+drag', 'gap ↔ split/remove', 'Queue ownership', 'Accessibility']) {
    if (!svg.includes(token)) fail(`Gesture guide lost interaction annotation: ${token}`);
  }
  validateGuideSchematic(svg, 'upright');
  const cw90Schematic = [...svg.matchAll(/<g[^>]*data-role="screen-schematic"[^>]*>[\s\S]*?<\/g>/g)].map(match => match[0]);
  if (!cw90Schematic || cw90Schematic.length < 2) fail('Gesture guide is missing its CW90 screen schematic');
  const second = cw90Schematic[1];
  const expected = C_GEOMETRY_SOURCE.devices.Fold8.cw90;
  assertBox(boxFromTag(second.match(/<use[^>]*data-role="preview"[^>]*\/>/)?.[0] ?? ''), expected.previews.photo, 'Gesture guide cw90 preview');
  assertBox(targetRect(queueBlock(second)), { ...expected.queue.bounds.photo, width: 56, height: 56 }, 'Gesture guide cw90 queue target');
  assertBox(boxFromTag(useTag(second, 'shared-zoomChips')), expected.controlBounds.photo.zoom.rear, 'Gesture guide cw90 zoom');
  assertBox(boxFromTag(useTag(second, 'shared-shutterPhoto')), expected.controlBounds.photo.shutter, 'Gesture guide cw90 shutter');
}

const capacity = (axis, bounds, endpointMargins = { start: 0, end: 0 }, profileName = 'standard56') => {
  const primaryAxis = axis === 'horizontal' ? bounds.width : bounds.height;
  const result = deriveQueueCapacity(primaryAxis, endpointMargins.start, endpointMargins.end, profileName);
  return {
    profile: result.profile,
    availableAxis: result.availableAxis,
    startMargin: result.startMargin,
    endMargin: result.endMargin,
    laneLength: result.laneLength,
    visibleCount: result.visibleCount,
    endpointMediaGap: result.endpointMediaGap,
    itemStart: queueItemStart(result.edgeTarget, result.endpointMediaGap),
    media: result.media,
    edgeTarget: result.edgeTarget,
    handlerWidth: QUEUE_PROFILES[profileName].handlerWidth,
    handler: result.handler,
    strip: result.strip,
    gap: result.gap,
  };
};

const devices = Object.fromEntries(Object.entries(C_GEOMETRY_SOURCE.devices).map(([deviceName, device]) => [
  deviceName,
  Object.fromEntries(Object.entries(device).map(([poseName, pose]) => [
    poseName,
    {
      ...pose,
      queue: {
        axis: pose.queue.axis,
        margins: pose.queue.placementMargins ?? pose.queue.endpointMargins,
        endpointMargins: pose.queue.endpointMargins,
        placementMargins: pose.queue.placementMargins ?? null,
        modes: Object.fromEntries(Object.entries(pose.queue.bounds).map(([mode, bounds]) => [mode, {
          bounds,
          capacity: capacity(pose.queue.axis, bounds, pose.queue.endpointMargins),
        }])),
      },
    },
  ])),
]));

const hashes = {
  sourceInputs: await hashFiles(sourceInputs),
  generators: await hashFiles(['foldable-c-geometry.mjs', 'queue-geometry.mjs', 'queue_geometry.py', 'foldable-c-study.py', 'ultra-c-study.py', 'ultra-c-cw90-study.py']),
};

const data = {
  status: 'current-static-design',
  design: 'C',
  generatedBy: 'foldable-c-geometry.mjs',
  sourceOfTruth: 'foldable-c-geometry-source.json',
  sourceGraph: {
    sourceInputs,
    generatedOutputs,
    generators: ['foldable-c-geometry.mjs', 'queue-geometry.mjs', 'queue_geometry.py', 'foldable-c-study.py', 'ultra-c-study.py', 'ultra-c-cw90-study.py'],
    archivedButRequiredFixtures: ['recon-capture-fold8-inner.svg', 'recon-capture-fold8-ultra-inner.svg'],
  },
  provenance: hashes,
  runtimeBoundary: 'Static SVG geometry is not Android device acceptance; runtime uses WindowMetrics, density, insets, hinge/separator bounds and camera stream metadata.',
  principles: [
    'preview remains physically stable across poses',
    'queue is separated from the shutter group and follows pose axis',
    'endpoint safety margins are chosen before capacity',
    'visible count N is a fit outcome, never a product token',
    'CW90 outer margins seek approximately 1:1:1 visual balance',
  ],
  profiles: Object.fromEntries(Object.entries(QUEUE_PROFILES).map(([name, value]) => [name, value])),
  devices,
};

const fold8Matrix = await readFile(join(root, 'recon-capture-fold8-c.svg'), 'utf8');
const ultraMatrix = await readFile(join(root, 'recon-capture-fold8-ultra-c.svg'), 'utf8');
validateSourcePreviews(await readFile(join(root, 'recon-capture-fold8-inner.svg'), 'utf8'), 'Fold8');
validateSourcePreviews(await readFile(join(root, 'recon-capture-fold8-ultra-inner.svg'), 'utf8'), 'Fold8 Ultra');
validateBalance('Fold8', 'cw90');
validateBalance('Fold8 Ultra', 'cw90');
validateMatrix(fold8Matrix, 'Fold8', C_GEOMETRY_SOURCE.devices.Fold8, 1176);
validateMatrix(ultraMatrix, 'Fold8 Ultra', C_GEOMETRY_SOURCE.devices['Fold8 Ultra'], 1104);
validateFocused(await readFile(join(root, 'recon-capture-fold8-ultra-c-upright.svg'), 'utf8'), 'Fold8 Ultra', 'upright');
validateFocused(await readFile(join(root, 'recon-capture-fold8-ultra-c-cw90.svg'), 'utf8'), 'Fold8 Ultra', 'cw90');
validateGestureGuide(await readFile(join(root, 'recon-capture-foldable-c-gesture-guide.svg'), 'utf8'));

const output = JSON.stringify(data, null, 2) + '\n';
const current = await readFile(destination, 'utf8').catch(() => '');
if (check) {
  if (current !== output) fail(`Stale generated geometry: ${destination}`);
} else {
  await writeFile(destination, output);
  console.log(`Generated ${destination}`);
}
