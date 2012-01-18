/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <arpa/inet.h>
#include <linux/socket.h>

#include <binder/Parcel.h>

namespace android {

bool canSerializeSockaddr(const struct sockaddr_storage* addr) {
    switch (addr->ss_family) {
        case AF_INET:
        case AF_INET6:
            return true;
        default:
            return false;
    }
}

void serializeSockaddr(Parcel* p, const struct sockaddr_storage* addr) {
    switch (addr->ss_family) {
        case AF_INET: {
            const struct sockaddr_in* s =
                reinterpret_cast<const struct sockaddr_in*>(addr);
            p->writeInt32(AF_INET);
            p->writeInt32(ntohl(s->sin_addr.s_addr));
            p->writeInt32(static_cast<int32_t>(ntohs(s->sin_port)));
        } break;

        case AF_INET6: {
            const struct sockaddr_in6* s =
                reinterpret_cast<const struct sockaddr_in6*>(addr);
            const int32_t* a =
                reinterpret_cast<const int32_t*>(s->sin6_addr.s6_addr);
            p->writeInt32(AF_INET6);
            p->writeInt32(ntohl(a[0]));
            p->writeInt32(ntohl(a[1]));
            p->writeInt32(ntohl(a[2]));
            p->writeInt32(ntohl(a[3]));
            p->writeInt32(static_cast<int32_t>(ntohs(s->sin6_port)));
            p->writeInt32(ntohl(s->sin6_flowinfo));
            p->writeInt32(ntohl(s->sin6_scope_id));
        } break;
    }
}

void deserializeSockaddr(const Parcel* p, struct sockaddr_storage* addr) {
    memset(addr, 0, sizeof(addr));

    addr->ss_family = p->readInt32();
    switch(addr->ss_family) {
        case AF_INET: {
            struct sockaddr_in* s =
                reinterpret_cast<struct sockaddr_in*>(addr);
            s->sin_addr.s_addr = htonl(p->readInt32());
            s->sin_port = htons(static_cast<uint16_t>(p->readInt32()));
        } break;

        case AF_INET6: {
            struct sockaddr_in6* s =
                reinterpret_cast<struct sockaddr_in6*>(addr);
            int32_t* a = reinterpret_cast<int32_t*>(s->sin6_addr.s6_addr);

            a[0] = htonl(p->readInt32());
            a[1] = htonl(p->readInt32());
            a[2] = htonl(p->readInt32());
            a[3] = htonl(p->readInt32());
            s->sin6_port = htons(static_cast<uint16_t>(p->readInt32()));
            s->sin6_flowinfo = htonl(p->readInt32());
            s->sin6_scope_id = htonl(p->readInt32());
        } break;
    }
}

}  // namespace android
