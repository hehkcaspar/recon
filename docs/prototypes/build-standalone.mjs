// Keep the design system authoritative while making each delivered SVG portable.
// Generated definitions/font data must be refreshed after shared component edits.
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const root = fileURLToPath(new URL('.', import.meta.url));
const check = process.argv.includes('--check');
const canonical = 'recon-capture-design-system.svg';
const font = (await readFile(join(root, 'assets/Roboto.ttf'))).toString('base64');
const fontURL = `data:font/ttf;base64,${font}`;
const embedFont = text => text.replace(/src:url\("(?:assets\/Roboto\.ttf|data:font\/ttf;base64,[^"]+)"\)/g, `src:url("${fontURL}")`);
const design = await readFile(join(root, canonical), 'utf8');
const defs = design.match(/<defs>([\s\S]*?)<\/defs>/)?.[1];
if (!defs) throw new Error('Missing canonical definitions');
const nodes = new Map([...defs.matchAll(/<(symbol|linearGradient|radialGradient|clipPath|mask|filter|pattern)\b[^>]*\bid="([^"]+)"[^>]*>[\s\S]*?<\/\1>/g)]
  .map(match => [match[2], match[0]]));
const refs = text => [...text.matchAll(/href="#([^"]+)"|url\(#([^)]+)\)/g)].map(match => match[1] || match[2]);
const css = defs.match(/<style>([\s\S]*?)<\/style>/)?.[1]
  .replace(/@font-face\s*\{[^}]*\}/g, '')
  .replace(/[ \t]+$/gm, '')
  .replace(/\.([A-Za-z][\w-]*)/g, '.shared-$1');
if (!css) throw new Error('Missing canonical component styles');
const scoped = text => text
  .replace(/\bid="([^"]+)"/g, 'id="shared-$1"')
  .replace(/href="#([^"]+)"/g, 'href="#shared-$1"')
  .replace(/url\(#([^)]+)\)/g, 'url(#shared-$1)')
  .replace(/class="([^"]+)"/g, (_, classes) => `class="${classes.split(/\s+/).map(name => 'shared-' + name).join(' ')}"`);
const begin = '<!-- BEGIN GENERATED SHARED DEFINITIONS: run node docs/prototypes/build-standalone.mjs -->';
const end = '<!-- END GENERATED SHARED DEFINITIONS -->';
let changed = 0;
async function save(name, previous, next) {
  if (previous === next) return;
  changed++;
  if (check) console.error(`Needs regeneration: ${name}`);
  else { await writeFile(join(root, name), next); console.log(`Bundled ${name}`); }
}
await save(canonical, design, embedFont(design));
let phone;
for (const kind of ['phone', 'cover', 'fold8-inner', 'fold8-ultra-inner']) {
  const name = `recon-capture-${kind}.svg`;
  const previous = await readFile(join(root, name), 'utf8');
  let text = previous;
  const start = text.indexOf(begin);
  if (start !== -1) {
    const finish = text.indexOf(end, start);
    if (finish === -1) throw new Error(`Unclosed generated block: ${name}`);
    text = text.slice(0, start) + text.slice(finish + end.length + 1);
  }
  const needed = new Set();
  function include(id) {
    if (needed.has(id)) return;
    if (!nodes.has(id)) throw new Error(`Unknown shared definition ${id} in ${name}`);
    needed.add(id);
    for (const dependency of refs(nodes.get(id))) include(dependency);
  }
  text = text.replace(/href="(?:recon-capture-design-system\.svg#|#shared-)([^"]+)"/g, (_, id) => {
    include(id);
    return `href="#shared-${id}"`;
  });
  // Canonical definition order is stable; each used symbol is embedded only once.
  const content = [...nodes].filter(([id]) => needed.has(id)).map(([, node]) => scoped(node)).join('\n');
  const block = `${begin}\n<defs>\n<style>${css}</style>\n${content}\n</defs>\n${end}\n`;
  text = text.replace(/(<svg\b[^>]*>\n)/, '$1' + block);
  const bundled = embedFont(text);
  await save(name, previous, bundled);
  if (kind === 'phone') phone = bundled;
}
// The annotated sheet embeds the exact clean artboard at 1:1. Never maintain a
// second copy of the phone layout or let explanatory material consume UI space.
const annotatedName = 'recon-capture-phone-annotated.svg';
const annotated = `<svg xmlns="http://www.w3.org/2000/svg" width="960" height="920" viewBox="0 0 960 920">
<!-- GENERATED: node docs/prototypes/build-standalone.mjs. Edit the clean phone SVG or this sheet template. -->
<title>Recon phone — external interaction annotations</title>
<desc>The unchanged 360 by 800 app viewport is placed at 0,0. All explanatory callouts are outside it.</desc>
<rect width="960" height="920" fill="#181818"/>
${phone.trim()}
<g font-family="Roboto,Arial,sans-serif" fill="#fff">
  <text x="440" y="34" font-size="22" font-weight="700">Phone / PHOTO idle</text>
  <text x="24" y="836" font-size="14" fill="#bdbdbd">360 × 800 logical units · artboard at 1:1</text>
  <g fill="none" stroke="#777" stroke-width="1">
    <path d="M372 316H408V156H424"/>
    <path d="M372 654H414V328H424"/>
    <path d="M372 742H420V456H424"/>
  </g>
  <g font-size="18" font-weight="700">
    <text x="440" y="160">Preview gestures</text>
    <text x="440" y="332">Capture controls</text>
    <text x="440" y="460">Queue gestures</text>
    <text x="440" y="728">Pixel-reference boundary</text>
  </g>
  <g font-size="15" fill="#ccc">
    <text x="440" y="192">Swipe right → VIDEO · swipe left → VOICE</text>
    <text x="440" y="220">Tap to focus · pinch to zoom</text>
    <text x="440" y="248">Complete 3:4 frame; no stretch or crop.</text>
    <text x="440" y="364">Flash · shutter · switch camera</text>
    <text x="440" y="392">12-unit gaps: zoom → shutter → queue.</text>
    <text x="440" y="492">Left edge → swipe right to commit</text>
    <text x="440" y="520">Right edge → swipe left to discard</text>
    <text x="440" y="548">Thumbnail ↓ pull down to delete one item</text>
    <text x="440" y="576">Long-press + drag ↔ to reorder</text>
    <text x="440" y="604">Gap ↓ split · gap ↑ remove divider</text>
    <text x="440" y="632">Active colored swiper replaces its edge handle.</text>
    <text x="440" y="760">Notes and leader lines never enter the app viewport.</text>
    <text x="440" y="788">Safe insets: top 24 · bottom 22 (illustrative).</text>
    <text x="440" y="816">Scene text/grid are camera test content, not app UI.</text>
    <text x="440" y="844">State variants and gesture diagrams: design-system sheet.</text>
  </g>
</g>
</svg>
`;
const oldAnnotated = await readFile(join(root, annotatedName), 'utf8').catch(error => {
  if (error.code === 'ENOENT') return '';
  throw error;
});
await save(annotatedName, oldAnnotated, annotated);
if (check && changed) process.exitCode = 1;
else console.log(check ? 'All standalone SVGs are current.' : 'Standalone SVGs ready; rerender before accepting changes.');
