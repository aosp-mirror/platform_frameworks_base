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

import android.net.TetheringRequestParcel;

import java.io.FileDescriptor;
import java.net.SocketException;
import java.util.Objects;

/**
 * Native methods for tethering utilization.
 *
 * {@hide}
 */
public class TetheringUtils {
    /**
     * Configures a socket for receiving ICMPv6 router solicitations and sending advertisements.
     * @param fd the socket's {@link FileDescriptor}.
     * @param ifIndex the interface index.
     */
    public static native void setupRaSocket(FileDescriptor fd, int ifIndex)
            throws SocketException;

    /**
     * Read s as an unsigned 16-bit integer.
     */
    public static int uint16(short s) {
        return s & 0xffff;
    }

    /** Check whether two TetheringRequestParcels are the same. */
    public static boolean isTetheringRequestEquals(final TetheringRequestParcel request,
            final TetheringRequestParcel otherRequest) {
        if (request == otherRequest) return true;

        return request != null && otherRequest != null
                && request.tetheringType == otherRequest.tetheringType
                && Objects.equals(request.localIPv4Address, otherRequest.localIPv4Address)
                && Objects.equals(request.staticClientAddress, otherRequest.staticClientAddress)
                && request.exemptFromEntitlementCheck == otherRequest.exemptFromEntitlementCheck
                && request.showProvisioningUi == otherRequest.showProvisioningUi;
    }
}
