#!/usr/bin/env node
// Regenerates the built-in rules table in README.md from regl-spec/catalog.json.
//   node scripts/sync-readme.mjs          rewrite README.md
//   node scripts/sync-readme.mjs --check  exit 1 if README.md is out of date (used by CI)
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const readmePath = join(root, 'README.md');
const catalog = JSON.parse(readFileSync(join(root, 'regl-spec/catalog.json'), 'utf8'));

const BEGIN = '<!-- rules-table:begin (generated from regl-spec/catalog.json by scripts/sync-readme.mjs; do not edit) -->';
const END = '<!-- rules-table:end -->';

const table = [
  '| Rule | Target Type | Parameter Type | Description |',
  '| :--- | :--- | :--- | :--- |',
  ...catalog.map((r) =>
    `| \`${r.id}\` | \`${r.targetType}\` | ${r.parameterType ? `\`${r.parameterType}\`` : '*none*'} | ${r.description} |`),
].join('\n');

const readme = readFileSync(readmePath, 'utf8');
const start = readme.indexOf(BEGIN);
const end = readme.indexOf(END);
if (start < 0 || end < start) {
  console.error('README.md is missing the rules-table markers');
  process.exit(1);
}
const next = `${readme.slice(0, start)}${BEGIN}\n${table}\n${readme.slice(end)}`;

if (process.argv.includes('--check')) {
  if (next !== readme) {
    console.error('README.md rules table is out of date; run: node scripts/sync-readme.mjs');
    process.exit(1);
  }
  console.log(`ok  README rules table matches the catalog (${catalog.length} rules)`);
} else {
  writeFileSync(readmePath, next);
  console.log(`README.md rules table written (${catalog.length} rules)`);
}
