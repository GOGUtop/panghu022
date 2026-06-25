狗骨酒馆 - 内置 Node 假本地 V2

这版已经把 Android 工程改成“APP 内置 Node.js Mobile + 本地 SillyTavern 启动器”的结构：

1. Android Studio 构建时会自动下载 nodejs-mobile-v0.3.3-android.zip
2. 自动解压 libnode.so / node.h 到 app/libnode
3. 用 CMake 编译 native-lib.cpp，把 libnode 打进 APK
4. APP 内新增：
   - 下载 / 更新 SillyTavern 本体
   - 选择账号并下载账号数据
   - 启动 APP 内置 Node 酒馆
   - 打开 http://127.0.0.1:8000/
   - 每 15 秒上传一次本地账号数据
   - 手动上传 / 清理本地酒馆 / 设置同步接口

重要：
- 如果 Android Studio 不能访问 GitHub，自动下载 Node 会失败。
- 这种情况下请手动下载：
  https://github.com/JaneaSystems/nodejs-mobile/releases/download/nodejs-mobile-v0.3.3/nodejs-mobile-v0.3.3-android.zip
- 解压后把里面的 bin/ 和 include/ 放到：
  app/libnode/
- 必须存在：
  app/libnode/bin/arm64-v8a/libnode.so
  app/libnode/bin/armeabi-v7a/libnode.so
  app/libnode/bin/x86_64/libnode.so
  app/libnode/include/node/node.h

服务器同步接口默认：
http://aaa.xixisillytavern.top:8000/xixi-sync

需要服务器提供：
GET  /xixi-sync/accounts?token=密码
GET  /xixi-sync/bundle.zip?token=密码
GET  /xixi-sync/export?account=账号名&token=密码
POST /xixi-sync/upload?account=账号名&token=密码

bundle.zip 解压后建议结构：
local_tavern/SillyTavern/server.js
local_tavern/SillyTavern/package.json
local_tavern/SillyTavern/src/
local_tavern/SillyTavern/public/
local_tavern/SillyTavern/default/
local_tavern/SillyTavern/data/

注意：SillyTavern 是否能在 Node.js 12 上完整跑，取决于你服务器上的 SillyTavern 版本和依赖。
nodejs-mobile 官方最后的 Android 版本是 Node 12.19.0，所以如果你的 SillyTavern 版本要求 Node 18/20，可能需要做降级包或换成自编译新版 libnode。
