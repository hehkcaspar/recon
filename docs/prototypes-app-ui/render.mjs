// node render.mjs [components bundles bundle-actions setup settings localsend]
// Playwright Core drives a fresh headless Chrome over a private pipe.
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, rmdir, unlink, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = fileURLToPath(new URL('.', import.meta.url));
const review = join(root, 'review');
const order = ['components', 'bundles', 'bundle-actions', 'setup', 'settings', 'localsend'];
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

function playwright() {
  const require = createRequire(import.meta.url);
  const bundled = join(homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node');
  return require(require.resolve('playwright-core', { paths: [root, bundled] }));
}

// This function runs inside the standalone SVG document.
async function inspect() {
  const svg = document.documentElement;
  if (!(svg instanceof SVGSVGElement) || document.querySelector('parsererror')) {
    throw new Error('Expected a well-formed SVG document');
  }
  const bounds = svg.getBoundingClientRect();
  if (!svg.hasAttribute('width') || !svg.hasAttribute('height') ||
      ![bounds.width, bounds.height].every(n => Number.isInteger(n) && n > 0) ||
      bounds.x !== 0 || bounds.y !== 0 || devicePixelRatio !== 1) {
    throw new Error('Expected positive integral 1x dimensions and a marginless SVG');
  }
  const fonts = [...document.fonts];
  await Promise.all(fonts.map(font => font.load()));
  await document.fonts.ready;
  const box = svg.getBBox();
  if (!box.width || !box.height) throw new Error('SVG has no measurable artwork');

  // Chrome reports the union of the children for a nested SVG's client rect.
  // Measure its declared viewport in the parent's coordinates instead.
  const viewports = new Map([[svg, bounds]]);
  const clips = new Map();
  for (const frame of svg.querySelectorAll('svg')) {
    const probe = document.createElementNS(svg.namespaceURI, 'rect');
    for (const attr of ['x', 'y', 'width', 'height', 'transform']) {
      if (frame.hasAttribute(attr)) probe.setAttribute(attr, frame.getAttribute(attr));
    }
    frame.before(probe);
    viewports.set(frame, probe.getBoundingClientRect());
    probe.remove();
    // Scrolling lists and mid-swipe rows intentionally crop their contents.
    if (!frame.id && frame.closest('svg[id^="frame-"]') && frame.getAttribute('overflow') === 'hidden') {
      clips.set(frame, viewports.get(frame));
    }
  }
  for (const group of svg.querySelectorAll('g[clip-path]')) {
    const id = /^url\(#([^()]+)\)$/.exec(group.getAttribute('clip-path'))?.[1];
    const clip = document.getElementById(id);
    if (clip?.children.length !== 1 || clip.firstElementChild.localName !== 'rect' ||
        clip.hasAttribute('transform') || clip.getAttribute('clipPathUnits') === 'objectBoundingBox') continue;
    const probe = clip.firstElementChild.cloneNode(false);
    group.append(probe);
    clips.set(group, probe.getBoundingClientRect());
    probe.remove();
  }
  const overflow = [];
  for (const text of svg.querySelectorAll('text')) {
    if (text.closest('defs, symbol') || !text.checkVisibility()) continue;
    let rect = text.getBoundingClientRect();
    if (!rect.width || !rect.height) continue;
    for (let frame = text.parentElement; frame; frame = frame.parentElement) {
      const clip = clips.get(frame);
      if (clip) {
        rect = { left: Math.max(rect.left, clip.left), top: Math.max(rect.top, clip.top),
                 right: Math.min(rect.right, clip.right), bottom: Math.min(rect.bottom, clip.bottom) };
        if (rect.left >= rect.right || rect.top >= rect.bottom) break;
      }
      const limit = viewports.get(frame);
      if (!limit) continue;
      const excess = Math.max(limit.left - rect.left, limit.top - rect.top,
                              rect.right - limit.right, rect.bottom - limit.bottom);
      if (excess > 0.5) {
        overflow.push({ frame: frame.id || 'sheet', text: text.textContent, excessPx: +excess.toFixed(2) });
        break;
      }
    }
  }
  return { width: bounds.width, height: bounds.height, fonts: fonts.map(f => f.family).sort(), overflow };
}

async function capturePng(page, checked) {
  // Capture beyond the small physical window. Capturing the resized desktop
  // surface can repeat compositor tiles at the bottom of tall sheets on macOS.
  const session = await page.context().newCDPSession(page);
  await session.send('Emulation.setDefaultBackgroundColorOverride', { color: { r: 255, g: 255, b: 255, a: 1 } });
  const { data } = await session.send('Page.captureScreenshot', {
    format: 'png', fromSurface: true, captureBeyondViewport: true,
    clip: { x: 0, y: 0, width: checked.width, height: checked.height, scale: 1 },
  });
  return Buffer.from(data, 'base64');
}

async function render(browser, slug) {
  const name = slug + '.svg';
  const source = await readFile(join(root, name));
  const url = 'https://recon.invalid/' + name;
  const page = await browser.newPage({ deviceScaleFactor: 1, colorScheme: 'light', reducedMotion: 'reduce' });
  try {
    // Serve the exact hashed bytes. No web server, sibling assets, or mutable file reads.
    const external = new Set();
    await page.route('**/*', route => {
      if (route.request().url() === url) {
        return route.fulfill({ contentType: 'image/svg+xml', body: source,
          headers: { 'Content-Security-Policy': "script-src 'none'; object-src 'none'" } });
      }
      external.add(route.request().url());
      return route.abort();
    });
    await page.goto(url, { waitUntil: 'load' });
    const checked = await page.evaluate(inspect);
    if (external.size) throw new Error('External SVG resources: ' + [...external].join(', '));
    const png = await capturePng(page, checked);
    if (png.readUInt32BE(16) !== checked.width || png.readUInt32BE(20) !== checked.height) {
      throw new Error('Raster dimensions differ from SVG');
    }
    const painted = await page.evaluate(async encoded => {
      const bytes = Uint8Array.from(atob(encoded), c => c.charCodeAt(0));
      const raster = await createImageBitmap(new Blob([bytes], { type: 'image/png' }));
      const ctx = new OffscreenCanvas(raster.width, raster.height).getContext('2d', { willReadFrequently: true });
      ctx.drawImage(raster, 0, 0);
      for (let y = 0; y < raster.height; y += 32) {
        const { data } = ctx.getImageData(0, y, raster.width, Math.min(32, raster.height - y));
        if (data.some(channel => channel !== 255)) return true;
      }
      return false;
    }, png.toString('base64'));
    if (!painted) throw new Error('Raster is blank white');
    await page.unroute('**/*');
    const localUrl = pathToFileURL(join(root, name)).href;
    await page.route('**/*', route => {
      if (route.request().url() === localUrl) return route.continue();
      external.add(route.request().url());
      return route.abort();
    });
    await page.goto(localUrl);
    const localMetrics = await page.evaluate(inspect);
    const localPng = await capturePng(page, localMetrics);
    if (external.size || !png.equals(localPng)) throw new Error('Local-file render differs from the standalone document');
    const entry = { ...checked, filePngSha256: sha256(localPng), chrome: browser.version(), svgSha256: sha256(source), pngSha256: sha256(png) };
    await publish(slug, png, entry);
    console.log(slug + ': ' + checked.width + ' × ' + checked.height + ', ' + checked.overflow.length + ' text overflow(s)');
    for (const issue of checked.overflow) console.error(' ', issue);
    return checked.overflow.length === 0;
  } finally {
    await page.close();
  }
}

async function writeChanged(path, bytes) {
  const existing = await readOptional(path);
  if (existing?.equals(bytes)) return;
  const temp = path + '.' + randomUUID() + '.tmp';
  try {
    await writeFile(temp, bytes);
    await rename(temp, path);
  } finally {
    await unlink(temp).catch(error => { if (error.code !== 'ENOENT') throw error; });
  }
}

async function readOptional(path) {
  try { return await readFile(path); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
}

// Only serialize publication so parallel per-sheet renders cannot lose one another's entries.
async function publish(slug, png, entry) {
  await mkdir(review, { recursive: true });
  const lock = join(review, '.render-lock');
  const deadline = Date.now() + 15000;
  while (true) {
    try { await mkdir(lock); break; }
    catch (error) {
      if (error.code !== 'EEXIST') throw error;
      if (Date.now() >= deadline) throw new Error('Timed out waiting for ' + lock + '. Check for a stopped renderer.');
      await delay(50);
    }
  }
  try {
    const path = join(review, 'manifest.json');
    const previous = await readOptional(path);
    const files = previous ? JSON.parse(previous).files : {};
    files[slug] = entry;
    const manifest = { files: Object.fromEntries(Object.keys(files).sort().map(key => [key, files[key]])) };
    await writeChanged(join(review, slug + '.png'), png);
    await writeChanged(path, Buffer.from(JSON.stringify(manifest, null, 2) + '\n'));
  } finally {
    await rmdir(lock);
  }
}

async function main() {
  const requested = [...new Set(process.argv.slice(2))];
  for (const slug of requested) {
    if (!order.includes(slug)) throw new Error('Unknown slug ' + slug + '. Choose from ' + order.join(', '));
  }
  const browser = await playwright().chromium.launch({
    channel: 'chrome', executablePath: process.env.CHROME_BIN || undefined, headless: true,
    chromiumSandbox: true, args: ['--disable-gpu', '--force-color-profile=srgb', '--hide-scrollbars'],
  });
  try {
    for (const slug of requested.length ? requested : order) {
      try { if (!await render(browser, slug)) process.exitCode = 1; }
      catch (error) { console.error(slug + ': ' + error.message); process.exitCode = 1; }
    }
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
