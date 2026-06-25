#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "node.h"

#define LOG_TAG "XiXiNodeMobile"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jint JNICALL
Java_com_xixijiuguan_mysillytavernxixi_NodeMobileNative_startNodeWithArguments(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray arguments) {
    int argc = env->GetArrayLength(arguments);
    std::vector<std::string> args;
    std::vector<char*> argv;
    args.reserve(argc);
    argv.reserve(argc);

    for (int i = 0; i < argc; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *raw = env->GetStringUTFChars(jstr, nullptr);
        args.emplace_back(raw ? raw : "");
        env->ReleaseStringUTFChars(jstr, raw);
        env->DeleteLocalRef(jstr);
    }

    for (auto &s : args) argv.push_back(const_cast<char*>(s.c_str()));

    LOGI("Starting embedded Node.js with %d args", argc);
    int code = node::Start(argc, argv.data());
    LOGI("Embedded Node.js exited with code %d", code);
    return code;
}
