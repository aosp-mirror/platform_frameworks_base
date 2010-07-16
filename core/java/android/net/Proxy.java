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

import org.apache.harmony.luni.platform.INetworkSystem;
import org.apache.harmony.luni.platform.Platform;
import org.apache.http.HttpHost;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

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

    static final private INetworkSystem NETIMPL = Platform.getNetworkSystem();

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

    /**
     * Returns the preferred proxy to be used by clients. This is a wrapper
     * around {@link android.net.Proxy#getHost()}. Currently no proxy will
     * be returned for localhost or if the active network is Wi-Fi.
     *
     * @param context the context which will be passed to
     * {@link android.net.Proxy#getHost()}
     * @param url the target URL for the request
     * @note Calling this method requires permission
     * android.permission.ACCESS_NETWORK_STATE
     * @return The preferred proxy to be used by clients, or null if there
     * is no proxy.
     *
     * {@hide}
     */
    static final public HttpHost getPreferredHttpHost(Context context,
            String url) {
        if (!isLocalHost(url) && !isNetworkWifi(context)) {
            final String proxyHost = Proxy.getHost(context);
            if (proxyHost != null) {
                return new HttpHost(proxyHost, Proxy.getPort(context), "http");
            }
        }

        return null;
    }

    static final private boolean isLocalHost(String url) {
        if (url == null) {
            return false;
        }

        try {
            final URI uri = URI.create(url);
            final String host = uri.getHost();
            if (host != null) {
                if (host.equalsIgnoreCase("localhost")) {
                    return true;
                }
                if (InetAddress.getByAddress(NETIMPL.ipStringToByteArray(host))
                        .isLoopbackAddress()) {
                    return true;
                }
            }
        } catch (UnknownHostException uex) {
            // Ignore (INetworkSystem.ipStringToByteArray)
        } catch (IllegalArgumentException iex) {
            // Ignore (URI.create)
        }

        return false;
    }

    static final private boolean isNetworkWifi(Context context) {
        if (context == null) {
            return false;
        }

        final ConnectivityManager connectivity = (ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null) {
            final NetworkInfo info = connectivity.getActiveNetworkInfo();
            if (info != null &&
                    info.getType() == ConnectivityManager.TYPE_WIFI) {
                return true;
            }
        }

        return false;
    }
};
