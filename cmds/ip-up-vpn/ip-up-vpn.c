/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <linux/if.h>
#include <linux/route.h>

#define LOG_TAG "ip-up-vpn"
#include <cutils/log.h>

#define DIR "/data/misc/vpn/"

static const char *env(const char *name) {
    const char *value = getenv(name);
    return value ? value : "";
}

static int set_address(struct sockaddr *sa, const char *address) {
    sa->sa_family = AF_INET;
    errno = EINVAL;
    return inet_pton(AF_INET, address, &((struct sockaddr_in *)sa)->sin_addr);
}

/*
 * The primary goal is to create a file with VPN parameters. Currently they
 * are interface, addresses, routes, DNS servers, and search domains. Each
 * parameter occupies one line in the file, and it can be an empty string or
 * space-separated values. The order and the format must be consistent with
 * com.android.server.connectivity.Vpn. Here is an example.
 *
 *   ppp0
 *   192.168.1.100/24
 *   0.0.0.0/0
 *   192.168.1.1 192.168.1.2
 *   example.org
 *
 * The secondary goal is to unify the outcome of VPN. The current baseline
 * is to have an interface configured with the given address and netmask
 * and maybe add a host route to protect the tunnel. PPP-based VPN already
 * does this, but others might not. Routes, DNS servers, and search domains
 * are handled by the framework since they can be overridden by the users.
 */
int main(int argc, char **argv)
{
    FILE *state = fopen(DIR ".tmp", "wb");
    if (!state) {
        ALOGE("Cannot create state: %s", strerror(errno));
        return 1;
    }

    if (argc >= 6) {
        /* Invoked by pppd. */
        fprintf(state, "%s\n", argv[1]);
        fprintf(state, "%s/32\n", argv[4]);
        fprintf(state, "0.0.0.0/0\n");
        fprintf(state, "%s %s\n", env("DNS1"), env("DNS2"));
        fprintf(state, "\n");
    } else if (argc == 2) {
        /* Invoked by racoon. */
        const char *interface = env("INTERFACE");
        const char *address = env("INTERNAL_ADDR4");
        const char *routes = env("SPLIT_INCLUDE_CIDR");

        int s = socket(AF_INET, SOCK_DGRAM, 0);
        struct rtentry rt;
        struct ifreq ifr;

        memset(&rt, 0, sizeof(rt));
        memset(&ifr, 0, sizeof(ifr));

        /* Remove the old host route. There could be more than one. */
        rt.rt_flags |= RTF_UP | RTF_HOST;
        if (set_address(&rt.rt_dst, env("REMOTE_ADDR"))) {
            while (!ioctl(s, SIOCDELRT, &rt));
        }
        if (errno != ESRCH) {
            ALOGE("Cannot remove host route: %s", strerror(errno));
            return 1;
        }

        /* Create a new host route. */
        rt.rt_flags |= RTF_GATEWAY;
        if (!set_address(&rt.rt_gateway, argv[1]) ||
                (ioctl(s, SIOCADDRT, &rt) && errno != EEXIST)) {
            ALOGE("Cannot create host route: %s", strerror(errno));
            return 1;
        }

        /* Bring up the interface. */
        ifr.ifr_flags = IFF_UP;
        strncpy(ifr.ifr_name, interface, IFNAMSIZ);
        if (ioctl(s, SIOCSIFFLAGS, &ifr)) {
            ALOGE("Cannot bring up %s: %s", interface, strerror(errno));
            return 1;
        }

        /* Set the address. */
        if (!set_address(&ifr.ifr_addr, address) ||
                ioctl(s, SIOCSIFADDR, &ifr)) {
            ALOGE("Cannot set address: %s", strerror(errno));
            return 1;
        }

        /* Set the netmask. */
        if (set_address(&ifr.ifr_netmask, env("INTERNAL_NETMASK4"))) {
            if (ioctl(s, SIOCSIFNETMASK, &ifr)) {
                ALOGE("Cannot set netmask: %s", strerror(errno));
                return 1;
            }
        }

        /* TODO: Send few packets to trigger phase 2? */

        fprintf(state, "%s\n", interface);
        fprintf(state, "%s/%s\n", address, env("INTERNAL_CIDR4"));
        fprintf(state, "%s\n", routes[0] ? routes : "0.0.0.0/0");
        fprintf(state, "%s\n", env("INTERNAL_DNS4_LIST"));
        fprintf(state, "%s\n", env("DEFAULT_DOMAIN"));
    } else {
        ALOGE("Cannot parse parameters");
        return 1;
    }

    fclose(state);
    if (chmod(DIR ".tmp", 0444) || rename(DIR ".tmp", DIR "state")) {
        ALOGE("Cannot write state: %s", strerror(errno));
        return 1;
    }
    return 0;
}
