// ControllerRoleListener implementation that forwards server controller state to Kotlin.

#pragma once

#include "jni_bridge.h"
#include "sendspin/controller_role.h"

namespace sendspin_jni {

class AndroidControllerListener : public sendspin::ControllerRoleListener {
public:
    explicit AndroidControllerListener(ClientHandle* handle) : handle_(handle) {}

    void on_controller_state(const sendspin::ServerStateControllerObject& state) override;
    void on_controller_state_clear() override;

private:
    ClientHandle* handle_;
};

}  // namespace sendspin_jni
