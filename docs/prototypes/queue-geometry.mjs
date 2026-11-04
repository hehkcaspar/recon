// Shared queue geometry math. Profile tokens come from the hand-authored C
// source so JavaScript and Python generators cannot silently diverge.

import { readFileSync } from 'node:fs';

const source = JSON.parse(readFileSync(new URL('./foldable-c-geometry-source.json', import.meta.url), 'utf8'));

export const C_GEOMETRY_SOURCE = source;
export const QUEUE_PROFILES = Object.freeze(Object.fromEntries(
  Object.entries(source.profiles).map(([name, value]) => [name, Object.freeze({ name, ...value })]),
));

export function deriveQueueCapacity(availableAxis, startMargin, endMargin, profileName, gapOverride) {
  const profile = QUEUE_PROFILES[profileName];
  if (!profile) throw new Error(`Unknown queue profile: ${profileName}`);
  const gap = gapOverride ?? profile.gap;
  const laneLength = Math.max(0, availableAxis - startMargin - endMargin);
  const visibleCount = Math.max(0, Math.floor(
    (laneLength - 2 * profile.edgeTarget + gap) / (profile.media + gap),
  ));
  const endpointMediaGap = visibleCount === 0
    ? 0
    : (laneLength - 2 * profile.edgeTarget - visibleCount * profile.media
      - (visibleCount - 1) * gap) / 2;
  return {
    profile: profile.name,
    availableAxis,
    startMargin,
    endMargin,
    laneLength,
    visibleCount,
    endpointMediaGap,
    media: profile.media,
    edgeTarget: profile.edgeTarget,
    handler: profile.handler,
    strip: profile.strip,
    gap,
  };
}

export function queueItemStart(edgeTarget, endpointMediaGap) {
  return edgeTarget + endpointMediaGap;
}
