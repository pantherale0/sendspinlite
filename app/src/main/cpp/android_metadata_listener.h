// MetadataRoleListener implementation that forwards track metadata to Kotlin.

#pragma once

#include "jni_bridge.h"
#include "sendspin/metadata_role.h"

namespace sendspin_jni {

class AndroidMetadataListener : public sendspin::MetadataRoleListener {
public:
    explicit AndroidMetadataListener(ClientHandle* handle) : handle_(handle) {}

    void on_metadata(const sendspin::ServerMetadataStateObject& metadata) override;
    void on_metadata_clear() override;

private:
    ClientHandle* handle_;
};

}  // namespace sendspin_jni
