// Dependency-free SVG evidence renderer. Run from any directory with Node + Chrome.
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir, mkdtemp } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';

const root = fileURLToPath(new URL('.', import.meta.url));
const chrome = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const files = ['design-system', 'phone', 'cover', 'fold8-inner', 'fold8-ultra-inner']
  .map(name => `recon-capture-${name}.svg`);
const allowed = new Set([...files, 'assets/Roboto.ttf']);
const sources = new Map(await Promise.all(files.map(async name => [name, await readFile(join(root, name), 'utf8')])));
const ids = new Map();
for (const [name, text] of sources) {
  const all = [...text.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]);
  if (new Set(all).size !== all.length) throw new Error(`Duplicate SVG ID in ${name}`);
  ids.set(name, new Set(all));
}
let references = 0;
for (const [name, text] of sources) {
  for (const [, href] of text.matchAll(/\bhref="([^"]+)"/g)) {
    const [target, id] = href.split('#');
    if (!id || !ids.get(target || name)?.has(id)) throw new Error(`Unresolved ${name}: ${href}`);
    references++;
  }
}

const fontServed = new Set();
const server = createServer(async (req, res) => {
  const name = decodeURIComponent(new URL(req.url, 'http://localhost').pathname.slice(1));
  if (!allowed.has(name)) { res.writeHead(404).end(); return; }
  try {
    const content = await readFile(join(root, name));
    res.setHeader('Content-Type', name.endsWith('.ttf') ? 'font/ttf' : 'image/svg+xml');
    res.setHeader('Cache-Control', 'no-store');
    if (name.endsWith('.ttf')) fontServed.add(name);
    res.end(content);
  } catch (error) { res.writeHead(500).end(String(error)); }
});
await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
const port = server.address().port;
const output = join(root, 'review');
const profile = await mkdtemp(join(tmpdir(), 'recon-svg-render-'));
await mkdir(output, { recursive: true });
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const manifest = { generatedAt: new Date().toISOString(), renderer: chrome, referencesValidated: references, files: {}, assets: {} };
try {
  for (const [name, svg] of sources) {
    const [, width, height] = svg.match(/<svg[^>]*\bwidth="(\d+)"[^>]*\bheight="(\d+)"/);
    const screenshot = join(output, name.replace('.svg', '.png'));
    await new Promise((resolve, reject) => {
      const proc = spawn(chrome, ['--headless', '--disable-gpu', '--no-first-run', '--disable-background-networking', '--disable-component-update', '--disable-sync', '--disable-extensions', '--no-default-browser-check', `--user-data-dir=${profile}`, '--force-device-scale-factor=1', `--screenshot=${screenshot}`, `--window-size=${width},${height}`, '--hide-scrollbars', '--virtual-time-budget=2000', `http://127.0.0.1:${port}/${name}`], { stdio: ['ignore', 'ignore', 'pipe'] });
      let stderr = '';
      proc.stderr.on('data', chunk => { stderr += chunk; });
      proc.once('error', reject);
      proc.once('exit', code => code === 0 ? resolve() : reject(new Error(stderr)));
    });
    const png = await readFile(screenshot);
    if (png.readUInt32BE(16) !== +width || png.readUInt32BE(20) !== +height) throw new Error(`Wrong raster dimensions: ${name}`);
    manifest.files[name] = { width: +width, height: +height, svgSha256: sha(svg), pngSha256: sha(png) };
    console.log(`Rendered ${name}: ${width} × ${height}`);
  }
  if (!fontServed.has('assets/Roboto.ttf')) throw new Error('Roboto was not requested; check font loading before acceptance.');
  for (const asset of ['Roboto.ttf', 'Roboto-OFL.txt', 'Material-Icons-LICENSE.txt']) manifest.assets[asset] = sha(await readFile(join(root, 'assets', asset)));
  await writeFile(join(output, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  console.log(`Validated ${references} references; evidence saved in ${output}`);
} finally { server.close(); }
