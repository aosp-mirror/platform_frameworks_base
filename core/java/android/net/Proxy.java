/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import junit.framework.Assert;

/**
 * A convenience class for accessing the user and default proxy
 * settings.
 */
final public class Proxy {

    // Set to true to enable extra debugging.
    static final private boolean DEBUG = false;

    static final public String PROXY_CHANGE_ACTION =
        "android.intent.action.PROXY_CHANGE";

    /**
     * Return the proxy host set by the user.
     * @param ctx A Context used to get the settings for the proxy host.
     * @return String containing the host name. If the user did not set a host
     *         name it returns the default host. A null value means that no
     *         host is to be used.
     */
    static final public String getHost(Context ctx) {
        ContentResolver contentResolver = ctx.getContentResolver();
        Assert.assertNotNull(contentResolver);
        String host = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.HTTP_PROXY);
        if (host != null) {
            int i = host.indexOf(':');
            if (i == -1) {
                if (DEBUG) {
                    Assert.assertTrue(host.length() == 0);
                }
                return null;
            }
            return host.substring(0, i);
        }
        return getDefaultHost();
    }

    /**
     * Return the proxy port set by the user.
     * @param ctx A Context used to get the settings for the proxy port.
     * @return The port number to use or -1 if no proxy is to be used.
     */
    static final public int getPort(Context ctx) {
        ContentResolver contentResolver = ctx.getContentResolver();
        Assert.assertNotNull(contentResolver);
        String host = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.HTTP_PROXY);
        if (host != null) {
            int i = host.indexOf(':');
            if (i == -1) {
                if (DEBUG) {
                    Assert.assertTrue(host.length() == 0);
                }
                return -1;
            }
            if (DEBUG) {
                Assert.assertTrue(i < host.length());
            }
            return Integer.parseInt(host.substring(i+1));
        }
        return getDefaultPort();
    }

    /**
     * Return the default proxy host specified by the carrier.
     * @return String containing the host name or null if there is no proxy for
     * this carrier.
     */
    static final public String getDefaultHost() {
        String host = SystemProperties.get("net.gprs.http-proxy");
        if (host != null) {
            Uri u = Uri.parse(host);
            host = u.getHost();
            return host;
        } else {
            return null;
        }
    }

    /**
     * Return the default proxy port specified by the carrier.
     * @return The port number to be used with the proxy host or -1 if there is
     * no proxy for this carrier.
     */
    static final public int getDefaultPort() {
        String host = SystemProperties.get("net.gprs.http-proxy");
        if (host != null) {
            Uri u = Uri.parse(host);
            return u.getPort();
        } else {
            return -1;
        }
    }

};
