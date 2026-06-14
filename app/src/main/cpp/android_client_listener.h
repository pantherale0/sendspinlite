// SendspinClientListener implementation: group updates, time sync, high-performance networking.

#pragma once

#include "jni_bridge.h"

namespace sendspin_jni {

class AndroidClientListener : public sendspin::SendspinClientListener {
public:
    explicit AndroidClientListener(ClientHandle* handle) : handle_(handle) {}

    void on_group_update(const sendspin::GroupUpdateObject& group) override;
    void on_time_sync_updated(float error) override;
    void on_request_high_performance() override;
    void on_release_high_performance() override;

private:
    ClientHandle* handle_;
};

}  // namespace sendspin_jni
