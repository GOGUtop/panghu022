// 狗骨酒馆 xixi-sync 示例服务端，需要在服务器上运行。
// 安装：npm i express multer archiver adm-zip
// 启动：node xixi_sync_server_example_express.js
// 注意把 TAVERN_ROOT 改成你的 SillyTavern 根目录。

const express = require('express');
const multer = require('multer');
const archiver = require('archiver');
const AdmZip = require('adm-zip');
const fs = require('fs');
const path = require('path');

const app = express();
const upload = multer({ dest: '/tmp/xixi-upload' });
const PORT = 5709;
const TAVERN_ROOT = process.env.TAVERN_ROOT || '/www/wwwroot/SillyTavern';
const MOBILE_BUNDLE = process.env.MOBILE_BUNDLE || '/www/wwwroot/xixi-sync/sillytavern_mobile_bundle.zip';
const SYNC_TOKEN = process.env.SYNC_TOKEN || '改成你自己的密码';

function checkToken(req, res, next) {
  const token = req.query.token || req.headers['x-xixi-token'] || req.body?.token;
  // 测试阶段你可以先注释这一段；正式用一定要开。
  // if (token !== SYNC_TOKEN) return res.status(403).json({ error: 'bad token' });
  next();
}

function zipDir(sourceDir, outPath) {
  return new Promise((resolve, reject) => {
    const output = fs.createWriteStream(outPath);
    const archive = archiver('zip', { zlib: { level: 6 } });
    output.on('close', resolve);
    archive.on('error', reject);
    archive.pipe(output);
    archive.directory(sourceDir, false);
    archive.finalize();
  });
}

app.get('/xixi-sync/accounts', checkToken, (req, res) => {
  const dataDir = path.join(TAVERN_ROOT, 'data');
  const accounts = fs.existsSync(dataDir)
    ? fs.readdirSync(dataDir).filter(x => fs.statSync(path.join(dataDir, x)).isDirectory())
    : [];
  res.json({ accounts });
});

app.get('/xixi-sync/bundle.zip', checkToken, (req, res) => {
  if (!fs.existsSync(MOBILE_BUNDLE)) return res.status(404).json({ error: 'bundle not found', path: MOBILE_BUNDLE });
  res.download(MOBILE_BUNDLE, 'sillytavern_mobile_bundle.zip');
});

app.get('/xixi-sync/export', checkToken, async (req, res) => {
  const account = String(req.query.account || '').replace(/[\\/]/g, '');
  if (!account) return res.status(400).json({ error: 'missing account' });
  const accountDir = path.join(TAVERN_ROOT, 'data', account);
  if (!fs.existsSync(accountDir)) return res.status(404).json({ error: 'account not found' });
  const out = `/tmp/xixi-account-${account}-${Date.now()}.zip`;
  await zipDir(accountDir, out);
  res.download(out, `${account}.zip`, () => { try { fs.unlinkSync(out); } catch(e){} });
});

app.post('/xixi-sync/upload', checkToken, upload.single('file'), (req, res) => {
  const account = String(req.body.account || '').replace(/[\\/]/g, '');
  if (!account || !req.file) return res.status(400).json({ error: 'missing account or file' });
  const accountDir = path.join(TAVERN_ROOT, 'data', account);
  const backupDir = path.join(TAVERN_ROOT, 'xixi_backups', `${account}-${Date.now()}`);
  fs.mkdirSync(path.dirname(backupDir), { recursive: true });
  if (fs.existsSync(accountDir)) fs.cpSync(accountDir, backupDir, { recursive: true });
  fs.mkdirSync(accountDir, { recursive: true });
  const zip = new AdmZip(req.file.path);
  zip.extractAllTo(accountDir, true);
  try { fs.unlinkSync(req.file.path); } catch(e){}
  res.json({ ok: true, account, backup: backupDir });
});

app.listen(PORT, () => console.log(`xixi-sync running: http://127.0.0.1:${PORT}/xixi-sync`));
