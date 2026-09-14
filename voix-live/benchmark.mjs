import { writeFileSync } from 'node:fs';

const BASE = 'https://swc2-target-speaker-extraction.hf.space';

function wav(seconds, baseHz = 220) {
  const sr = 16000;
  const n = Math.max(1, Math.round(seconds * sr));
  const buf = new ArrayBuffer(44 + n * 2);
  const v = new DataView(buf);
  const text = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  text(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); text(8, 'WAVE'); text(12, 'fmt ');
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, sr, true); v.setUint32(28, sr * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  text(36, 'data'); v.setUint32(40, n * 2, true);
  for (let i = 0; i < n; i++) {
    const t = i / sr;
    const env = 0.4 + 0.6 * Math.max(0, Math.sin(2 * Math.PI * 3.1 * t));
    const s = 0.18 * env * (Math.sin(2 * Math.PI * baseHz * t) + 0.45 * Math.sin(2 * Math.PI * baseHz * 2.03 * t));
    v.setInt16(44 + i * 2, Math.max(-32767, Math.min(32767, Math.round(s * 32767))), true);
  }
  return new Blob([buf], { type: 'audio/wav' });
}

async function upload(blob, name) {
  const fd = new FormData();
  fd.append('files', blob, name);
  const r = await fetch(BASE + '/upload', { method: 'POST', body: fd, signal: AbortSignal.timeout(60000) });
  if (!r.ok) throw new Error(`upload ${r.status}: ${await r.text()}`);
  const paths = await r.json();
  return paths[0];
}

function fileData(path) { return { path, meta: { _type: 'gradio.FileData' } }; }

async function callEndpoint(endpoint, mixPath, refPath) {
  const start = performance.now();
  const p = await fetch(`${BASE}/call/${endpoint}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ data: [fileData(mixPath), fileData(refPath), 'mix', 'iter_model'] }),
    signal: AbortSignal.timeout(60000)
  });
  if (!p.ok) throw new Error(`${endpoint} submit ${p.status}: ${await p.text()}`);
  const { event_id } = await p.json();
  if (!event_id) throw new Error(`${endpoint}: no event_id`);
  const s = await fetch(`${BASE}/call/${endpoint}/${event_id}`, { signal: AbortSignal.timeout(300000) });
  if (!s.ok) throw new Error(`${endpoint} stream ${s.status}: ${await s.text()}`);
  const body = await s.text();
  const complete = body.includes('event: complete');
  if (!complete) throw new Error(`${endpoint}: incomplete SSE: ${body.slice(-500)}`);
  return { ms: Math.round(performance.now() - start), endpoint };
}

async function infer(mixPath, refPath) {
  let last;
  for (const endpoint of ['gradio_TSE', 'predict']) {
    try { return await callEndpoint(endpoint, mixPath, refPath); }
    catch (e) { last = e; console.log('endpoint failed', endpoint, e.message); }
  }
  throw last;
}

const report = { at: new Date().toISOString(), base: BASE, cases: [] };
console.log('Uploading 10 s enrollment once…');
const refUploadStart = performance.now();
const refPath = await upload(wav(10, 205), 'reference.wav');
report.referenceUploadMs = Math.round(performance.now() - refUploadStart);

for (const seconds of [1, 2, 4]) {
  console.log(`Benchmark ${seconds}s…`);
  const up0 = performance.now();
  const mixPath = await upload(wav(seconds, 245), `mix-${seconds}s.wav`);
  const uploadMs = Math.round(performance.now() - up0);
  const out = await infer(mixPath, refPath);
  const item = { seconds, uploadMs, inferenceMs: out.ms, endpoint: out.endpoint, realtimeFactor: Number((out.ms / 1000 / seconds).toFixed(2)) };
  report.cases.push(item);
  console.log(JSON.stringify(item));
}

report.liveCloudFeasible = report.cases.every(x => x.realtimeFactor < 0.75);
writeFileSync('/tmp/voix-live-benchmark.json', JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
