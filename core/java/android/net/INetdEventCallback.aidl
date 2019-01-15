/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net;

/** {@hide} */
oneway interface INetdEventCallback {

    // Possible addNetdEventCallback callers.
    const int CALLBACK_CALLER_CONNECTIVITY_SERVICE = 0;
    const int CALLBACK_CALLER_DEVICE_POLICY = 1;
    const int CALLBACK_CALLER_NETWORK_WATCHLIST = 2;

    /**
     * Reports a single DNS lookup function call.
     * This method must not block or perform long-running operations.
     *
     * @param netId the ID of the network the lookup was performed on.
     * @param eventType one of the EVENT_* constants in {@link INetdEventListener}.
     * @param returnCode the return value of the query, may vary based on {@code eventType}. See
     *        {@code getaddrinfo()}, {@code gethostbyaddr()} and {@code gethostbyname()} section in
     *        bionic/libc/include/netdb.h.
     * @param hostname the name that was looked up.
     * @param ipAddresses (possibly a subset of) the IP addresses returned.
     *        At most {@link #DNS_REPORTED_IP_ADDRESSES_LIMIT} addresses are logged.
     * @param ipAddressesCount the number of IP addresses returned. May be different from the length
     *        of ipAddresses if there were too many addresses to log.
     * @param timestamp the timestamp at which the query was reported by netd.
     * @param uid the UID of the application that performed the query.
     */
    void onDnsEvent(int netId, int eventType, int returnCode, String hostname,
            in String[] ipAddresses, int ipAddressesCount, long timestamp, int uid);

    /**
     * Represents adding or removing a NAT64 prefix.
     * This method must not block or perform long-running operations.
     *
     * @param netId the ID of the network the prefix was performed on.
     * @param added true if the NAT64 prefix was added, or false if the NAT64 prefix was removed.
     *        There is only one prefix at a time for each netId. If a prefix is added, it replaces
     *        the previous-added prefix.
     * @param prefixString the detected NAT64 prefix as a string literal.
     * @param prefixLength the prefix length associated with this NAT64 prefix.
     */
    void onNat64PrefixEvent(int netId, boolean added, @utf8InCpp String prefixString,
            int prefixLength);

    /**
     * Represents a private DNS validation success or failure.
     * This method must not block or perform long-running operations.
     *
     * @param netId the ID of the network the validation was performed on.
     * @param ipAddress the IP address for which validation was performed.
     * @param hostname the hostname for which validation was performed.
     * @param validated whether or not validation was successful.
     */
    void onPrivateDnsValidationEvent(int netId, String ipAddress, String hostname,
            boolean validated);

    /**
     * Reports a single connect library call.
     * This method must not block or perform long-running operations.
     *
     * @param ipAddr destination IP address.
     * @param port destination port number.
     * @param timestamp the timestamp at which the call was reported by netd.
     * @param uid the UID of the application that performed the connection.
     */
    void onConnectEvent(String ipAddr, int port, long timestamp, int uid);
}
