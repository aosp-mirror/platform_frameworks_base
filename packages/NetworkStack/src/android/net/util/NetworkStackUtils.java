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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.provider.DeviceConfig;
import android.util.SparseArray;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.List;
import java.util.function.Predicate;

/**
 * Collection of utilities for the network stack.
 */
public class NetworkStackUtils {
    // TODO: Refer to DeviceConfig definition.
    public static final String NAMESPACE_CONNECTIVITY = "connectivity";

    /**
     * A list of captive portal detection specifications used in addition to the fallback URLs.
     * Each spec has the format url@@/@@statusCodeRegex@@/@@contentRegex. Specs are separated
     * by "@@,@@".
     */
    public static final String CAPTIVE_PORTAL_FALLBACK_PROBE_SPECS =
            "captive_portal_fallback_probe_specs";

    /**
     * A comma separated list of URLs used for captive portal detection in addition to the
     * fallback HTTP url associated with the CAPTIVE_PORTAL_FALLBACK_URL settings.
     */
    public static final String CAPTIVE_PORTAL_OTHER_FALLBACK_URLS =
            "captive_portal_other_fallback_urls";

    /**
     * Which User-Agent string to use in the header of the captive portal detection probes.
     * The User-Agent field is unset when this setting has no value (HttpUrlConnection default).
     */
    public static final String CAPTIVE_PORTAL_USER_AGENT = "captive_portal_user_agent";

    /**
     * Whether to use HTTPS for network validation. This is enabled by default and the setting
     * needs to be set to 0 to disable it. This setting is a misnomer because captive portals
     * don't actually use HTTPS, but it's consistent with the other settings.
     */
    public static final String CAPTIVE_PORTAL_USE_HTTPS = "captive_portal_use_https";

    /**
     * The URL used for HTTPS captive portal detection upon a new connection.
     * A 204 response code from the server is used for validation.
     */
    public static final String CAPTIVE_PORTAL_HTTPS_URL = "captive_portal_https_url";

    /**
     * The URL used for HTTP captive portal detection upon a new connection.
     * A 204 response code from the server is used for validation.
     */
    public static final String CAPTIVE_PORTAL_HTTP_URL = "captive_portal_http_url";

    /**
     * The URL used for fallback HTTP captive portal detection when previous HTTP
     * and HTTPS captive portal detection attemps did not return a conclusive answer.
     */
    public static final String CAPTIVE_PORTAL_FALLBACK_URL = "captive_portal_fallback_url";

    /**
     * What to do when connecting a network that presents a captive portal.
     * Must be one of the CAPTIVE_PORTAL_MODE_* constants above.
     *
     * The default for this setting is CAPTIVE_PORTAL_MODE_PROMPT.
     */
    public static final String CAPTIVE_PORTAL_MODE = "captive_portal_mode";

    /**
     * Don't attempt to detect captive portals.
     */
    public static final int CAPTIVE_PORTAL_MODE_IGNORE = 0;

    /**
     * When detecting a captive portal, display a notification that
     * prompts the user to sign in.
     */
    public static final int CAPTIVE_PORTAL_MODE_PROMPT = 1;

    /**
     * When detecting a captive portal, immediately disconnect from the
     * network and do not reconnect to that network in the future.
     */
    public static final int CAPTIVE_PORTAL_MODE_AVOID = 2;

    static {
        System.loadLibrary("networkstackutilsjni");
    }

    /**
     * @return True if the array is null or 0-length.
     */
    public static <T> boolean isEmpty(T[] array) {
        return array == null || array.length == 0;
    }

    /**
     * Close a socket, ignoring any exception while closing.
     */
    public static void closeSocketQuietly(FileDescriptor fd) {
        try {
            SocketUtils.closeSocket(fd);
        } catch (IOException ignored) {
        }
    }

    /**
     * Returns an int array from the given Integer list.
     */
    public static int[] convertToIntArray(@NonNull List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    /**
     * Returns a long array from the given long list.
     */
    public static long[] convertToLongArray(@NonNull List<Long> list) {
        long[] array = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    /**
     * @return True if there exists at least one element in the sparse array for which
     * condition {@code predicate}
     */
    public static <T> boolean any(SparseArray<T> array, Predicate<T> predicate) {
        for (int i = 0; i < array.size(); ++i) {
            if (predicate.test(array.valueAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Look up the value of a property for a particular namespace from {@link DeviceConfig}.
     * @param namespace The namespace containing the property to look up.
     * @param name The name of the property to look up.
     * @param defaultValue The value to return if the property does not exist or has no valid value.
     * @return the corresponding value, or defaultValue if none exists.
     */
    @Nullable
    public static String getDeviceConfigProperty(@NonNull String namespace, @NonNull String name,
            @Nullable String defaultValue) {
        String value = DeviceConfig.getProperty(namespace, name);
        return value != null ? value : defaultValue;
    }

    /**
     * Look up the value of a property for a particular namespace from {@link DeviceConfig}.
     * @param namespace The namespace containing the property to look up.
     * @param name The name of the property to look up.
     * @param defaultValue The value to return if the property does not exist or has no non-null
     *                     value.
     * @return the corresponding value, or defaultValue if none exists.
     */
    public static int getDeviceConfigPropertyInt(@NonNull String namespace, @NonNull String name,
            int defaultValue) {
        String value = getDeviceConfigProperty(namespace, name, null /* defaultValue */);
        try {
            return (value != null) ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Attaches a socket filter that accepts DHCP packets to the given socket.
     */
    public static native void attachDhcpFilter(FileDescriptor fd) throws SocketException;

    /**
     * Attaches a socket filter that accepts ICMPv6 router advertisements to the given socket.
     * @param fd the socket's {@link FileDescriptor}.
     * @param packetType the hardware address type, one of ARPHRD_*.
     */
    public static native void attachRaFilter(FileDescriptor fd, int packetType)
            throws SocketException;

    /**
     * Attaches a socket filter that accepts L2-L4 signaling traffic required for IP connectivity.
     *
     * This includes: all ARP, ICMPv6 RS/RA/NS/NA messages, and DHCPv4 exchanges.
     *
     * @param fd the socket's {@link FileDescriptor}.
     * @param packetType the hardware address type, one of ARPHRD_*.
     */
    public static native void attachControlPacketFilter(FileDescriptor fd, int packetType)
            throws SocketException;

    /**
     * Add an entry into the ARP cache.
     */
    public static void addArpEntry(Inet4Address ipv4Addr, android.net.MacAddress ethAddr,
            String ifname, FileDescriptor fd) throws IOException {
        addArpEntry(ethAddr.toByteArray(), ipv4Addr.getAddress(), ifname, fd);
    }

    private static native void addArpEntry(byte[] ethAddr, byte[] netAddr, String ifname,
            FileDescriptor fd) throws IOException;

    /**
     * Return IP address and port in a string format.
     */
    public static String addressAndPortToString(InetAddress address, int port) {
        return String.format(
                (address instanceof Inet6Address) ? "[%s]:%d" : "%s:%d",
                        address.getHostAddress(), port);
    }
}
