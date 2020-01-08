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
#include <nativehelper/ScopedUtfChars.h>
#include <linux/netfilter/nfnetlink.h>
#include <linux/netlink.h>
#include <net/if.h>
#include <netinet/icmp6.h>
#include <sys/socket.h>
#include <android-base/unique_fd.h>
#include <android/hardware/tetheroffload/config/1.0/IOffloadConfig.h>

#define LOG_TAG "TetheringUtils"
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

static jboolean android_net_util_configOffload(
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

static void android_net_util_setupRaSocket(JNIEnv *env, jobject clazz, jobject javaFd,
        jint ifIndex)
{
    static const int kLinkLocalHopLimit = 255;

    int fd = jniGetFDFromFileDescriptor(env, javaFd);

    // Set an ICMPv6 filter that only passes Router Solicitations.
    struct icmp6_filter rs_only;
    ICMP6_FILTER_SETBLOCKALL(&rs_only);
    ICMP6_FILTER_SETPASS(ND_ROUTER_SOLICIT, &rs_only);
    socklen_t len = sizeof(rs_only);
    if (setsockopt(fd, IPPROTO_ICMPV6, ICMP6_FILTER, &rs_only, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(ICMP6_FILTER): %s", strerror(errno));
        return;
    }

    // Most/all of the rest of these options can be set via Java code, but
    // because we're here on account of setting an icmp6_filter go ahead
    // and do it all natively for now.

    // Set the multicast hoplimit to 255 (link-local only).
    int hops = kLinkLocalHopLimit;
    len = sizeof(hops);
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, &hops, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(IPV6_MULTICAST_HOPS): %s", strerror(errno));
        return;
    }

    // Set the unicast hoplimit to 255 (link-local only).
    hops = kLinkLocalHopLimit;
    len = sizeof(hops);
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &hops, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(IPV6_UNICAST_HOPS): %s", strerror(errno));
        return;
    }

    // Explicitly disable multicast loopback.
    int off = 0;
    len = sizeof(off);
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_MULTICAST_LOOP, &off, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(IPV6_MULTICAST_LOOP): %s", strerror(errno));
        return;
    }

    // Specify the IPv6 interface to use for outbound multicast.
    len = sizeof(ifIndex);
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_MULTICAST_IF, &ifIndex, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(IPV6_MULTICAST_IF): %s", strerror(errno));
        return;
    }

    // Additional options to be considered:
    //     - IPV6_TCLASS
    //     - IPV6_RECVPKTINFO
    //     - IPV6_RECVHOPLIMIT

    // Bind to [::].
    const struct sockaddr_in6 sin6 = {
            .sin6_family = AF_INET6,
            .sin6_port = 0,
            .sin6_flowinfo = 0,
            .sin6_addr = IN6ADDR_ANY_INIT,
            .sin6_scope_id = 0,
    };
    auto sa = reinterpret_cast<const struct sockaddr *>(&sin6);
    len = sizeof(sin6);
    if (bind(fd, sa, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "bind(IN6ADDR_ANY): %s", strerror(errno));
        return;
    }

    // Join the all-routers multicast group, ff02::2%index.
    struct ipv6_mreq all_rtrs = {
        .ipv6mr_multiaddr = {{{0xff,2,0,0,0,0,0,0,0,0,0,0,0,0,0,2}}},
        .ipv6mr_interface = ifIndex,
    };
    len = sizeof(all_rtrs);
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_JOIN_GROUP, &all_rtrs, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(IPV6_JOIN_GROUP): %s", strerror(errno));
        return;
    }
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "configOffload", "()Z", (void*) android_net_util_configOffload },
    { "setupRaSocket", "(Ljava/io/FileDescriptor;I)V", (void*) android_net_util_setupRaSocket },
};

int register_android_net_util_TetheringUtils(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
            "android/net/util/TetheringUtils",
            gMethods, NELEM(gMethods));
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed");
        return JNI_ERR;
    }

    if (register_android_net_util_TetheringUtils(env) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

}; // namespace android
