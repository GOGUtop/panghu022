狗骨酒馆 - 假本地方案说明

这版已经在安卓工程里加入“假本地酒馆中心”：
1. 启动后可进入“假本地酒馆 / 同步中心”
2. 可配置服务器同步接口，例如：http://你的域名/xixi-sync
3. 可从服务器下载 bundle.zip 到手机并解压
4. 可获取服务器账号列表，选择账号后下载对应账号 data zip
5. 已预留 APP 内置 Node 启动逻辑：启动 http://127.0.0.1:8000/
6. 已预留实时同步：每 15 秒把手机本地 data 打包上传到服务器 /upload

重要：
- 这版先把 APP 端下载、解压、选择账号、同步中心、上传接口全部接好。
- 真正“APP 自带 Node”还需要把 Android 可执行的 Node 放进工程，例如 nodejs-mobile 或 Android node 二进制。
- 代码会寻找这些位置：
  files/local_node/node
  files/local_tavern/node/bin/node
  files/local_tavern/node/node
  files/local_tavern/SillyTavern/node
  files/local_tavern/SillyTavern/node/bin/node

服务器接口约定：
GET  /xixi-sync/accounts
返回：{"accounts":["default-user","user2"]}

GET  /xixi-sync/bundle.zip
返回：sillytavern_mobile_bundle.zip
建议 zip 内结构为 SillyTavern/package.json、SillyTavern/server.js、SillyTavern/src、SillyTavern/public、SillyTavern/default、SillyTavern/plugins 等。

GET  /xixi-sync/export?account=账号名
返回：该账号 data 压缩包。解压后最好直接包含 data/账号名/... 或者账号相关目录。

POST /xixi-sync/upload
multipart/form-data：
account=账号名
file=data.zip
服务器收到后先备份旧 data，再解压覆盖/合并。

安全建议：
- /upload 必须加同步密码或 token，否则别人能覆盖你的酒馆数据。
- 第一次不要全量覆盖服务器，先备份旧数据。
- 不建议自动同步 secrets.json、config.yaml。
