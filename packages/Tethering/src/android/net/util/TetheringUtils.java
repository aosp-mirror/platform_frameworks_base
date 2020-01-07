/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.net.util;

import java.io.FileDescriptor;
import java.net.SocketException;

/**
 * Native methods for tethering utilization.
 *
 * {@hide}
 */
public class TetheringUtils {

    /**
     * Offload management process need to know conntrack rules to support NAT, but it may not have
     * permission to create netlink netfilter sockets. Create two netlink netfilter sockets and
     * share them with offload management process.
     */
    public static native boolean configOffload();

    /**
     * Configures a socket for receiving ICMPv6 router solicitations and sending advertisements.
     * @param fd the socket's {@link FileDescriptor}.
     * @param ifIndex the interface index.
     */
    public static native void setupRaSocket(FileDescriptor fd, int ifIndex)
            throws SocketException;
}
