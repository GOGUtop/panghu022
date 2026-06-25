/*
 * 狗骨酒馆 V4 账号数据同步服务
 * 作用：不打包整个 SillyTavern，只读写服务器现有 SillyTavern/data/<账号>/ 目录。
 * 启动：
 *   cd server
 *   npm install
 *   TAVERN_DATA_DIR=/home/www/SillyTavern/data SYNC_TOKEN=xixi npm start
 */
const express = require('express');
const cors = require('cors');
const multer = require('multer');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 5762);
const API_PREFIX = process.env.API_PREFIX || '/xixi-api';
const DATA_DIR = path.resolve(process.env.TAVERN_DATA_DIR || '/home/www/SillyTavern/data');
const SYNC_TOKEN = process.env.SYNC_TOKEN || 'xixi';
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 200 * 1024 * 1024 } });

const app = express();
app.use(cors());
app.use(express.json({ limit: '50mb' }));

function safeName(name) {
  if (!name || typeof name !== 'string') throw new Error('bad account');
  if (name.includes('..') || name.includes('/') || name.includes('\\')) throw new Error('bad account path');
  return name;
}
function safeRel(rel) {
  if (!rel || typeof rel !== 'string') throw new Error('bad path');
  const clean = rel.replace(/\\/g, '/').replace(/^\/+/, '');
  if (clean.includes('..')) throw new Error('bad relative path');
  return clean;
}
function accountDir(account) {
  return path.join(DATA_DIR, safeName(account));
}
function requireToken(req, res, next) {
  const got = String(req.headers['x-xixi-token'] || req.query.token || req.body?.token || '');
  if (got !== SYNC_TOKEN) return res.status(401).json({ ok: false, error: 'bad token' });
  next();
}
function walk(dir, base = dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    const st = fs.statSync(full);
    if (st.isDirectory()) walk(full, base, out);
    else out.push({ path: path.relative(base, full).replace(/\\/g, '/'), size: st.size, mtime: st.mtimeMs });
  }
  return out;
}
function hashFile(file) {
  const h = crypto.createHash('sha1');
  h.update(fs.readFileSync(file));
  return h.digest('hex');
}

app.get(`${API_PREFIX}/status`, (req, res) => {
  res.json({ ok: true, dataDir: DATA_DIR, api: API_PREFIX, time: Date.now() });
});

app.get(`${API_PREFIX}/accounts`, requireToken, (req, res) => {
  if (!fs.existsSync(DATA_DIR)) return res.json({ ok: true, accounts: [] });
  const accounts = fs.readdirSync(DATA_DIR)
    .filter(n => fs.statSync(path.join(DATA_DIR, n)).isDirectory())
    .sort();
  res.json({ ok: true, accounts });
});

app.post(`${API_PREFIX}/login`, requireToken, (req, res) => {
  const account = safeName(req.body.account || '');
  // 第一版先用统一同步密码 SYNC_TOKEN。之后可以改成读取每个账号自己的密码文件。
  if (!fs.existsSync(accountDir(account))) return res.status(404).json({ ok: false, error: 'account not found' });
  res.json({ ok: true, account });
});

app.get(`${API_PREFIX}/manifest`, requireToken, (req, res) => {
  const account = safeName(req.query.account || '');
  const dir = accountDir(account);
  if (!fs.existsSync(dir)) return res.status(404).json({ ok: false, error: 'account not found' });
  const files = walk(dir).map(f => ({ ...f, sha1: hashFile(path.join(dir, f.path)) }));
  res.json({ ok: true, account, files });
});

app.get(`${API_PREFIX}/file`, requireToken, (req, res) => {
  const account = safeName(req.query.account || '');
  const rel = safeRel(req.query.path || '');
  const file = path.join(accountDir(account), rel);
  if (!file.startsWith(accountDir(account))) return res.status(403).end();
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) return res.status(404).end();
  res.download(file);
});

app.post(`${API_PREFIX}/upload-file`, requireToken, upload.single('file'), (req, res) => {
  const account = safeName(req.body.account || '');
  const rel = safeRel(req.body.path || '');
  if (!req.file) return res.status(400).json({ ok: false, error: 'no file' });
  const base = accountDir(account);
  const dest = path.join(base, rel);
  if (!dest.startsWith(base)) return res.status(403).json({ ok: false, error: 'bad path' });
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  if (fs.existsSync(dest)) {
    const bak = dest + `.bak.${Date.now()}`;
    fs.copyFileSync(dest, bak);
  }
  fs.writeFileSync(dest, req.file.buffer);
  res.json({ ok: true, path: rel, size: req.file.size });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`狗骨酒馆同步服务启动：http://0.0.0.0:${PORT}${API_PREFIX}`);
  console.log(`读取账号目录：${DATA_DIR}`);
});
