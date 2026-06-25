package com.xixijiuguan.mysillytavernxixi

object NodeMobileNative {
    @Volatile private var loaded = false
    @Volatile private var errorMessage = ""

    init {
        try {
            System.loadLibrary("native-lib")
            loaded = true
        } catch (t: Throwable) {
            loaded = false
            errorMessage = t.message ?: t.toString()
        }
    }

    @JvmStatic external fun startNodeWithArguments(arguments: Array<String>): Int

    fun isAvailable(): Boolean = loaded
    fun lastError(): String = errorMessage

    fun start(arguments: Array<String>): Int {
        if (!loaded) throw IllegalStateException("内置 Node.js 未加载：$errorMessage")
        return startNodeWithArguments(arguments)
    }
}
