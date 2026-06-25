'use strict';

const fs = require('fs');
const path = require('path');

const root = process.argv[2];
const port = process.argv[3] || '8000';

function log(msg) {
  try { console.log('[狗骨本地酒馆] ' + msg); } catch (_) {}
}

if (!root || !fs.existsSync(root)) {
  console.error('Local SillyTavern root not found: ' + root);
  process.exit(2);
}

const serverFile = path.join(root, 'server.js');
if (!fs.existsSync(serverFile)) {
  console.error('server.js not found in: ' + root);
  process.exit(3);
}

process.chdir(root);
process.env.NODE_ENV = process.env.NODE_ENV || 'production';
process.env.PORT = port;
process.env.SILLYTAVERN_PORT = port;
process.env.HOST = '127.0.0.1';
process.env.SILLYTAVERN_HOST = '127.0.0.1';

// 尽量把 SillyTavern 锁在手机本机，只给 APP WebView 使用。
process.argv = [process.argv[0] || 'node', serverFile, '--host', '127.0.0.1', '--port', String(port)];

log('root=' + root);
log('port=' + port);
log('requiring ' + serverFile);

try {
  require(serverFile);
} catch (err) {
  console.error('[狗骨本地酒馆] start failed:', err && err.stack ? err.stack : err);
  process.exit(4);
}
