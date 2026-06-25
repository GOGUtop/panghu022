# 狗骨酒馆 · GitHub 编译版 V4

这个包是给 GitHub Actions 编译 APK 用的版本。上传到 GitHub 后，不需要在电脑 Android Studio 里编译，直接在 Actions 里生成 APK。

## 一、怎么上传到 GitHub 编译

1. 新建一个 GitHub 仓库。
2. 把这个压缩包解压后的所有文件上传到仓库根目录。
3. 打开仓库的 **Actions** 页面。
4. 选择 **Build Debug APK**。
5. 点 **Run workflow**。
6. 编译完成后，在页面底部 **Artifacts** 下载 `狗骨酒馆-debug-apk`。

## 二、这版和昨天复杂版的区别

这版保留你原来的狗洞、键盘优化、小狗球、小说化阅读器，同时加入 GitHub 编译配置。

接下来要做的正式方向是：

```text
APP 内置 SillyTavern 本体
APP 内置 Node
打开 APP 自动启动本地 SillyTavern
输入服务器账号密码
服务器验证账号密码
APP 拉取该账号 data 文件
写入手机本地 SillyTavern/data/账号名/
WebView 打开 127.0.0.1:8000
游玩过程中监听 data 变化
变更实时上传到服务器对应账号文件夹
```

## 三、服务器账号同步接口

`server/` 目录里放了一个第一版账号数据接口：

```bash
cd server
npm install
TAVERN_DATA_DIR=/home/www/SillyTavern/data SYNC_TOKEN=xixi PORT=5762 npm start
```

接口默认是：

```text
http://服务器IP:5762/xixi-api
```

它不会压缩整个 SillyTavern，只会读写：

```text
/home/www/SillyTavern/data/<账号名>/
```

第一版默认统一同步密码是 `xixi`，后面可以改成每个账号一个密码。

## 四、重要说明

最新版 SillyTavern 对 Node 版本要求比较高。这个工程现在已经接入了 Node Mobile 的构建链路，但 Android 内置 Node 跑最新版 SillyTavern 可能还需要继续换 Node 20+ 方案。

所以这版主要目标是：

```text
先让 GitHub 能稳定编译 APK
先把账号云同步服务搭好
再继续升级 APP 内置 SillyTavern + Node20 本地运行
```

