// SendspinNetworkProvider implementation that defers the readiness check to Kotlin.

#pragma once

#include "jni_bridge.h"

namespace sendspin_jni {

class AndroidNetworkProvider : public sendspin::SendspinNetworkProvider {
public:
    explicit AndroidNetworkProvider(ClientHandle* handle) : handle_(handle) {}

    bool is_network_ready() override;

private:
    ClientHandle* handle_;
};

}  // namespace sendspin_jni
