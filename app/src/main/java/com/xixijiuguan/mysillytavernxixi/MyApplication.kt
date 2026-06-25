package com.xixijiuguan.mysillytavernxixi

import android.app.Application
import com.tencent.smtt.export.external.TbsCoreSettings
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsListener

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 【关键1】允许非WiFi下载X5内核（约40MB）
        QbSdk.setDownloadWithoutWifi(true)

        // 【关键2】强制初始化设置
        val map = HashMap<String, Any>()
        map[TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER] = true
        map[TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE] = true
        map[TbsCoreSettings.TBS_SETTINGS_USE_PRIVATE_CLASSLOADER] = true
        QbSdk.initTbsSettings(map)

        // 【关键3】监听内核下载状态
        QbSdk.setTbsListener(object : TbsListener {
            override fun onDownloadFinish(stateCode: Int) {
                android.util.Log.i("X5Init", "X5内核下载完成: $stateCode")
            }
            override fun onInstallFinish(stateCode: Int) {
                android.util.Log.i("X5Init", "X5内核安装完成: $stateCode")
            }
            override fun onDownloadProgress(progress: Int) {
                android.util.Log.i("X5Init", "X5内核下载进度: $progress%")
            }
        })

        QbSdk.initX5Environment(this, object : QbSdk.PreInitCallback {
            override fun onCoreInitFinished() {
                android.util.Log.i("X5Init", "X5内核初始化完成")
            }
            override fun onViewInitFinished(success: Boolean) {
                android.util.Log.i("X5Init", "X5加载${if (success) "成功" else "失败,首次需联网下载约40MB"}")
            }
        })
    }
}