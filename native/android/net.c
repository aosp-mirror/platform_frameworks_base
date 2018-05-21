/*
 * Copyright (C) 2015 The Android Open Source Project
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


#include <android/multinetwork.h>
#include <errno.h>
#include <NetdClient.h>    // the functions that communicate with netd
#include <resolv_netid.h>  // android_getaddrinfofornet()
#include <stdlib.h>
#include <sys/limits.h>


static int getnetidfromhandle(net_handle_t handle, unsigned *netid) {
    static const uint32_t k32BitMask = 0xffffffff;
    // This value MUST be kept in sync with the corresponding value in
    // the android.net.Network#getNetworkHandle() implementation.
    static const uint32_t kHandleMagic = 0xcafed00d;

    // Check for minimum acceptable version of the API in the low bits.
    if (handle != NETWORK_UNSPECIFIED &&
        (handle & k32BitMask) != kHandleMagic) {
        return 0;
    }

    if (netid != NULL) {
        *netid = ((handle >> (CHAR_BIT * sizeof(k32BitMask))) & k32BitMask);
    }
    return 1;
}


int android_setsocknetwork(net_handle_t network, int fd) {
    unsigned netid;
    if (!getnetidfromhandle(network, &netid)) {
        errno = EINVAL;
        return -1;
    }

    int rval = setNetworkForSocket(netid, fd);
    if (rval < 0) {
        errno = -rval;
        rval = -1;
    }
    return rval;
}

int android_setprocnetwork(net_handle_t network) {
    unsigned netid;
    if (!getnetidfromhandle(network, &netid)) {
        errno = EINVAL;
        return -1;
    }

    int rval = setNetworkForProcess(netid);
    if (rval < 0) {
        errno = -rval;
        rval = -1;
    }
    return rval;
}

int android_getaddrinfofornetwork(net_handle_t network,
        const char *node, const char *service,
        const struct addrinfo *hints, struct addrinfo **res) {
    unsigned netid;
    if (!getnetidfromhandle(network, &netid)) {
        errno = EINVAL;
        return EAI_SYSTEM;
    }

    return android_getaddrinfofornet(node, service, hints, netid, 0, res);
}
