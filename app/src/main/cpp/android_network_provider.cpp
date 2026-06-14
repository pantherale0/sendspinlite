#include "android_network_provider.h"

namespace sendspin_jni {

bool AndroidNetworkProvider::is_network_ready() {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().is_network_ready == nullptr) {
        // Fail open: assume the network is available so connection attempts proceed.
        return true;
    }
    return env->CallBooleanMethod(handle_->callbacks(), handle_->methods().is_network_ready) ==
           JNI_TRUE;
}

}  // namespace sendspin_jni
