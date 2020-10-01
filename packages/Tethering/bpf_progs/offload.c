/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <linux/if.h>
#include <linux/ip.h>
#include <linux/ipv6.h>
#include <linux/pkt_cls.h>
#include <linux/tcp.h>

#include "bpf_helpers.h"
#include "bpf_net_helpers.h"
#include "netdbpf/bpf_shared.h"

DEFINE_BPF_MAP_GRW(tether_ingress_map, HASH, TetherIngressKey, TetherIngressValue, 64,
                   AID_NETWORK_STACK)

// Tethering stats, indexed by upstream interface.
DEFINE_BPF_MAP_GRW(tether_stats_map, HASH, uint32_t, TetherStatsValue, 16, AID_NETWORK_STACK)

// Tethering data limit, indexed by upstream interface.
// (tethering allowed when stats[iif].rxBytes + stats[iif].txBytes < limit[iif])
DEFINE_BPF_MAP_GRW(tether_limit_map, HASH, uint32_t, uint64_t, 16, AID_NETWORK_STACK)

static inline __always_inline int do_forward(struct __sk_buff* skb, bool is_ethernet) {
    int l2_header_size = is_ethernet ? sizeof(struct ethhdr) : 0;
    void* data = (void*)(long)skb->data;
    const void* data_end = (void*)(long)skb->data_end;
    struct ethhdr* eth = is_ethernet ? data : NULL;  // used iff is_ethernet
    struct ipv6hdr* ip6 = is_ethernet ? (void*)(eth + 1) : data;

    // Must be meta-ethernet IPv6 frame
    if (skb->protocol != htons(ETH_P_IPV6)) return TC_ACT_OK;

    // Must have (ethernet and) ipv6 header
    if (data + l2_header_size + sizeof(*ip6) > data_end) return TC_ACT_OK;

    // Ethertype - if present - must be IPv6
    if (is_ethernet && (eth->h_proto != htons(ETH_P_IPV6))) return TC_ACT_OK;

    // IP version must be 6
    if (ip6->version != 6) return TC_ACT_OK;

    // Cannot decrement during forward if already zero or would be zero,
    // Let the kernel's stack handle these cases and generate appropriate ICMP errors.
    if (ip6->hop_limit <= 1) return TC_ACT_OK;

    // Protect against forwarding packets sourced from ::1 or fe80::/64 or other weirdness.
    __be32 src32 = ip6->saddr.s6_addr32[0];
    if (src32 != htonl(0x0064ff9b) &&                        // 64:ff9b:/32 incl. XLAT464 WKP
        (src32 & htonl(0xe0000000)) != htonl(0x20000000))    // 2000::/3 Global Unicast
        return TC_ACT_OK;

    TetherIngressKey k = {
            .iif = skb->ifindex,
            .neigh6 = ip6->daddr,
    };

    TetherIngressValue* v = bpf_tether_ingress_map_lookup_elem(&k);

    // If we don't find any offload information then simply let the core stack handle it...
    if (!v) return TC_ACT_OK;

    uint32_t stat_and_limit_k = skb->ifindex;

    TetherStatsValue* stat_v = bpf_tether_stats_map_lookup_elem(&stat_and_limit_k);

    // If we don't have anywhere to put stats, then abort...
    if (!stat_v) return TC_ACT_OK;

    uint64_t* limit_v = bpf_tether_limit_map_lookup_elem(&stat_and_limit_k);

    // If we don't have a limit, then abort...
    if (!limit_v) return TC_ACT_OK;

    // Required IPv6 minimum mtu is 1280, below that not clear what we should do, abort...
    const int pmtu = v->pmtu;
    if (pmtu < IPV6_MIN_MTU) return TC_ACT_OK;

    // Approximate handling of TCP/IPv6 overhead for incoming LRO/GRO packets: default
    // outbound path mtu of 1500 is not necessarily correct, but worst case we simply
    // undercount, which is still better then not accounting for this overhead at all.
    // Note: this really shouldn't be device/path mtu at all, but rather should be
    // derived from this particular connection's mss (ie. from gro segment size).
    // This would require a much newer kernel with newer ebpf accessors.
    // (This is also blindly assuming 12 bytes of tcp timestamp option in tcp header)
    uint64_t packets = 1;
    uint64_t bytes = skb->len;
    if (bytes > pmtu) {
        const int tcp_overhead = sizeof(struct ipv6hdr) + sizeof(struct tcphdr) + 12;
        const int mss = pmtu - tcp_overhead;
        const uint64_t payload = bytes - tcp_overhead;
        packets = (payload + mss - 1) / mss;
        bytes = tcp_overhead * packets + payload;
    }

    // Are we past the limit?  If so, then abort...
    // Note: will not overflow since u64 is 936 years even at 5Gbps.
    // Do not drop here.  Offload is just that, whenever we fail to handle
    // a packet we let the core stack deal with things.
    // (The core stack needs to handle limits correctly anyway,
    // since we don't offload all traffic in both directions)
    if (stat_v->rxBytes + stat_v->txBytes + bytes > *limit_v) return TC_ACT_OK;

    if (!is_ethernet) {
        is_ethernet = true;
        l2_header_size = sizeof(struct ethhdr);
        // Try to inject an ethernet header, and simply return if we fail
        if (bpf_skb_change_head(skb, l2_header_size, /*flags*/ 0)) {
            __sync_fetch_and_add(&stat_v->rxErrors, 1);
            return TC_ACT_OK;
        }

        // bpf_skb_change_head() invalidates all pointers - reload them
        data = (void*)(long)skb->data;
        data_end = (void*)(long)skb->data_end;
        eth = data;
        ip6 = (void*)(eth + 1);

        // I do not believe this can ever happen, but keep the verifier happy...
        if (data + l2_header_size + sizeof(*ip6) > data_end) {
            __sync_fetch_and_add(&stat_v->rxErrors, 1);
            return TC_ACT_SHOT;
        }
    };

    // CHECKSUM_COMPLETE is a 16-bit one's complement sum,
    // thus corrections for it need to be done in 16-byte chunks at even offsets.
    // IPv6 nexthdr is at offset 6, while hop limit is at offset 7
    uint8_t old_hl = ip6->hop_limit;
    --ip6->hop_limit;
    uint8_t new_hl = ip6->hop_limit;

    // bpf_csum_update() always succeeds if the skb is CHECKSUM_COMPLETE and returns an error
    // (-ENOTSUPP) if it isn't.
    bpf_csum_update(skb, 0xFFFF - ntohs(old_hl) + ntohs(new_hl));

    __sync_fetch_and_add(&stat_v->rxPackets, packets);
    __sync_fetch_and_add(&stat_v->rxBytes, bytes);

    // Overwrite any mac header with the new one
    *eth = v->macHeader;

    // Redirect to forwarded interface.
    //
    // Note that bpf_redirect() cannot fail unless you pass invalid flags.
    // The redirect actually happens after the ebpf program has already terminated,
    // and can fail for example for mtu reasons at that point in time, but there's nothing
    // we can do about it here.
    return bpf_redirect(v->oif, 0 /* this is effectively BPF_F_EGRESS */);
}

SEC("schedcls/ingress/tether_ether")
int sched_cls_ingress_tether_ether(struct __sk_buff* skb) {
    return do_forward(skb, true);
}

// Note: section names must be unique to prevent programs from appending to each other,
// so instead the bpf loader will strip everything past the final $ symbol when actually
// pinning the program into the filesystem.
//
// bpf_skb_change_head() is only present on 4.14+ and 2 trivial kernel patches are needed:
//   ANDROID: net: bpf: Allow TC programs to call BPF_FUNC_skb_change_head
//   ANDROID: net: bpf: permit redirect from ingress L3 to egress L2 devices at near max mtu
// (the first of those has already been upstreamed)
//
// 5.4 kernel support was only added to Android Common Kernel in R,
// and thus a 5.4 kernel always supports this.
//
// Hence, this mandatory (must load successfully) implementation for 5.4+ kernels:
DEFINE_BPF_PROG_KVER("schedcls/ingress/tether_rawip$5_4", AID_ROOT, AID_ROOT,
                     sched_cls_ingress_tether_rawip_5_4, KVER(5, 4, 0))
(struct __sk_buff* skb) {
    return do_forward(skb, false);
}

// and this identical optional (may fail to load) implementation for [4.14..5.4) patched kernels:
DEFINE_OPTIONAL_BPF_PROG_KVER_RANGE("schedcls/ingress/tether_rawip$4_14", AID_ROOT, AID_ROOT,
                                    sched_cls_ingress_tether_rawip_4_14, KVER(4, 14, 0),
                                    KVER(5, 4, 0))
(struct __sk_buff* skb) {
    return do_forward(skb, false);
}

// and define a no-op stub for [4.9,4.14) and unpatched [4.14,5.4) kernels.
// (if the above real 4.14+ program loaded successfully, then bpfloader will have already pinned
// it at the same location this one would be pinned at and will thus skip loading this stub)
DEFINE_BPF_PROG_KVER_RANGE("schedcls/ingress/tether_rawip$stub", AID_ROOT, AID_ROOT,
                           sched_cls_ingress_tether_rawip_stub, KVER_NONE, KVER(5, 4, 0))
(struct __sk_buff* skb) {
    return TC_ACT_OK;
}

LICENSE("Apache 2.0");
CRITICAL("netd");
