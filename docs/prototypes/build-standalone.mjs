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
  await save(name, previous, embedFont(text));
}
if (check && changed) process.exitCode = 1;
else console.log(check ? 'All standalone SVGs are current.' : 'Standalone SVGs ready; rerender before accepting changes.');
