/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <errno.h>
#include <error.h>
#include <hidl/HidlSupport.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <linux/netfilter/nfnetlink.h>
#include <linux/netlink.h>
#include <sys/socket.h>
#include <android-base/unique_fd.h>
#include <android/hardware/tetheroffload/config/1.0/IOffloadConfig.h>

#define LOG_TAG "OffloadHardwareInterface"
#include <utils/Log.h>

namespace android {

using hardware::hidl_handle;
using hardware::hidl_string;
using hardware::tetheroffload::config::V1_0::IOffloadConfig;

namespace {

inline const sockaddr * asSockaddr(const sockaddr_nl *nladdr) {
    return reinterpret_cast<const sockaddr *>(nladdr);
}

int conntrackSocket(unsigned groups) {
    base::unique_fd s(socket(AF_NETLINK, SOCK_DGRAM, NETLINK_NETFILTER));
    if (s.get() < 0) return -errno;

    const struct sockaddr_nl bind_addr = {
        .nl_family = AF_NETLINK,
        .nl_pad = 0,
        .nl_pid = 0,
        .nl_groups = groups,
    };
    if (bind(s.get(), asSockaddr(&bind_addr), sizeof(bind_addr)) != 0) {
        return -errno;
    }

    const struct sockaddr_nl kernel_addr = {
        .nl_family = AF_NETLINK,
        .nl_pad = 0,
        .nl_pid = 0,
        .nl_groups = groups,
    };
    if (connect(s.get(), asSockaddr(&kernel_addr), sizeof(kernel_addr)) != 0) {
        return -errno;
    }

    return s.release();
}

// Return a hidl_handle that owns the file descriptor owned by fd, and will
// auto-close it (otherwise there would be double-close problems).
//
// Rely upon the compiler to eliminate the constexprs used for clarity.
hidl_handle handleFromFileDescriptor(base::unique_fd fd) {
    hidl_handle h;

    static constexpr int kNumFds = 1;
    static constexpr int kNumInts = 0;
    native_handle_t *nh = native_handle_create(kNumFds, kNumInts);
    nh->data[0] = fd.release();

    static constexpr bool kTakeOwnership = true;
    h.setTo(nh, kTakeOwnership);

    return h;
}

}  // namespace

static jboolean android_server_connectivity_tethering_OffloadHardwareInterface_configOffload(
        JNIEnv* /* env */) {
    sp<IOffloadConfig> configInterface = IOffloadConfig::getService();
    if (configInterface.get() == nullptr) {
        ALOGD("Could not find IOffloadConfig service.");
        return false;
    }

    // Per the IConfigOffload definition:
    //
    // fd1   A file descriptor bound to the following netlink groups
    //       (NF_NETLINK_CONNTRACK_NEW | NF_NETLINK_CONNTRACK_DESTROY).
    //
    // fd2   A file descriptor bound to the following netlink groups
    //       (NF_NETLINK_CONNTRACK_UPDATE | NF_NETLINK_CONNTRACK_DESTROY).
    base::unique_fd
            fd1(conntrackSocket(NF_NETLINK_CONNTRACK_NEW | NF_NETLINK_CONNTRACK_DESTROY)),
            fd2(conntrackSocket(NF_NETLINK_CONNTRACK_UPDATE | NF_NETLINK_CONNTRACK_DESTROY));
    if (fd1.get() < 0 || fd2.get() < 0) {
        ALOGE("Unable to create conntrack handles: %d/%s", errno, strerror(errno));
        return false;
    }

    hidl_handle h1(handleFromFileDescriptor(std::move(fd1))),
                h2(handleFromFileDescriptor(std::move(fd2)));

    bool rval(false);
    hidl_string msg;
    const auto status = configInterface->setHandles(h1, h2,
            [&rval, &msg](bool success, const hidl_string& errMsg) {
                rval = success;
                msg = errMsg;
            });
    if (!status.isOk() || !rval) {
        ALOGE("IOffloadConfig::setHandles() error: '%s' / '%s'",
              status.description().c_str(), msg.c_str());
        // If status is somehow not ok, make sure rval captures this too.
        rval = false;
    }

    return rval;
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "configOffload", "()Z",
      (void*) android_server_connectivity_tethering_OffloadHardwareInterface_configOffload },
};

int register_android_server_connectivity_tethering_OffloadHardwareInterface(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
            "com/android/server/connectivity/tethering/OffloadHardwareInterface",
            gMethods, NELEM(gMethods));
}

}; // namespace android
