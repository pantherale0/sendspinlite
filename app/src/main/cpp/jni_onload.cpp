// JNI_OnLoad entry point and per-thread JNIEnv attachment helper.

#include "jni_bridge.h"

namespace sendspin_jni {

JavaVM* g_vm = nullptr;

namespace {

// Detaches a native thread from the JVM when the thread exits. sendspin-cpp creates
// its own threads (e.g. the sync task that invokes on_audio_write); attaching them
// without detaching on exit aborts the process, so we rely on this thread_local guard.
struct ThreadEnvGuard {
    JNIEnv* env = nullptr;
    bool attached = false;
    ~ThreadEnvGuard() {
        if (attached && g_vm != nullptr) {
            g_vm->DetachCurrentThread();
        }
    }
};

thread_local ThreadEnvGuard t_guard;

}  // namespace

JNIEnv* GetEnv() {
    if (g_vm == nullptr) {
        return nullptr;
    }
    if (t_guard.env != nullptr) {
        return t_guard.env;
    }
    JNIEnv* env = nullptr;
    const jint status = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) {
        t_guard.env = env;
        return env;
    }
    if (status == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            t_guard.env = env;
            t_guard.attached = true;
            return env;
        }
    }
    return nullptr;
}

}  // namespace sendspin_jni

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    sendspin_jni::g_vm = vm;
    return JNI_VERSION_1_6;
}
