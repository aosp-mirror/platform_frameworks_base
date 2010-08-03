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
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemProperties;
import android.provider.Settings;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.Assert;

import org.apache.http.HttpHost;

/**
 * A convenience class for accessing the user and default proxy
 * settings.
 */
public final class Proxy {

    // Set to true to enable extra debugging.
    private static final boolean DEBUG = false;

    public static final String PROXY_CHANGE_ACTION =
        "android.intent.action.PROXY_CHANGE";

    private static ReadWriteLock sProxyInfoLock = new ReentrantReadWriteLock();

    private static SettingsObserver sGlobalProxyChangedObserver = null;

    private static ProxySpec sGlobalProxySpec = null;

    // Hostname / IP REGEX validation
    // Matches blank input, ips, and domain names
    private static final String NAME_IP_REGEX =
        "[a-zA-Z0-9]+(\\-[a-zA-Z0-9]+)*(\\.[a-zA-Z0-9]+(\\-[a-zA-Z0-9]+)*)*";

    private static final String HOSTNAME_REGEXP = "^$|^" + NAME_IP_REGEX + "$";

    private static final Pattern HOSTNAME_PATTERN;

    private static final String EXCLLIST_REGEXP = "$|^(.?" + NAME_IP_REGEX
        + ")+(,(.?" + NAME_IP_REGEX + "))*$";

    private static final Pattern EXCLLIST_PATTERN;

    static {
        HOSTNAME_PATTERN = Pattern.compile(HOSTNAME_REGEXP);
        EXCLLIST_PATTERN = Pattern.compile(EXCLLIST_REGEXP);
    }

    private static class ProxySpec {
        String[] exclusionList = null;
        InetSocketAddress proxyAddress = null;
        public ProxySpec() { };
    }

    private static boolean isURLInExclusionListReadLocked(String url, String[] exclusionList) {
        if (exclusionList == null) {
            return false;
        }
        Uri u = Uri.parse(url);
        String urlDomain = u.getHost();
        // If the domain is defined as ".android.com" or "android.com", we wish to match
        // http://android.com as well as http://xxx.android.com , but not
        // http://myandroid.com . This code works out the logic.
        for (String excludedDomain : exclusionList) {
            String dotDomain = "." + excludedDomain;
            if (urlDomain.equals(excludedDomain)) {
                return true;
            }
            if (urlDomain.endsWith(dotDomain)) {
                return true;
            }
        }
        // No match
        return false;
    }

    private static String parseHost(String proxySpec) {
        int i = proxySpec.indexOf(':');
        if (i == -1) {
            if (DEBUG) {
                Assert.assertTrue(proxySpec.length() == 0);
            }
            return null;
        }
        return proxySpec.substring(0, i);
    }

    private static int parsePort(String proxySpec) {
        int i = proxySpec.indexOf(':');
        if (i == -1) {
            if (DEBUG) {
                Assert.assertTrue(proxySpec.length() == 0);
            }
            return -1;
        }
        if (DEBUG) {
            Assert.assertTrue(i < proxySpec.length());
        }
        return Integer.parseInt(proxySpec.substring(i+1));
    }

    /**
     * Return the proxy object to be used for the URL given as parameter.
     * @param ctx A Context used to get the settings for the proxy host.
     * @param url A URL to be accessed. Used to evaluate exclusion list.
     * @return Proxy (java.net) object containing the host name. If the
     *         user did not set a hostname it returns the default host.
     *         A null value means that no host is to be used.
     * {@hide}
     */
    public static final java.net.Proxy getProxy(Context ctx, String url) {
        sProxyInfoLock.readLock().lock();
        try {
            if (sGlobalProxyChangedObserver == null) {
                registerContentObserversReadLocked(ctx);
                parseGlobalProxyInfoReadLocked(ctx);
            }
            if (sGlobalProxySpec != null) {
                // Proxy defined - Apply exclusion rules
                if (isURLInExclusionListReadLocked(url, sGlobalProxySpec.exclusionList)) {
                    // Return no proxy
                    return java.net.Proxy.NO_PROXY;
                }
                java.net.Proxy retProxy =
                    new java.net.Proxy(java.net.Proxy.Type.HTTP, sGlobalProxySpec.proxyAddress);
                sProxyInfoLock.readLock().unlock();
                if (isLocalHost(url)) {
                    return java.net.Proxy.NO_PROXY;
                }
                sProxyInfoLock.readLock().lock();
                return retProxy;
            } else {
                // If network is WiFi, return no proxy.
                // Otherwise, return the Mobile Operator proxy.
                if (!isNetworkWifi(ctx)) {
                    java.net.Proxy retProxy = getDefaultProxy(url);
                    sProxyInfoLock.readLock().unlock();
                    if (isLocalHost(url)) {
                        return java.net.Proxy.NO_PROXY;
                    }
                    sProxyInfoLock.readLock().lock();
                    return retProxy;
                } else {
                    return java.net.Proxy.NO_PROXY;
                }
            }
        } finally {
            sProxyInfoLock.readLock().unlock();
        }
    }

    // TODO: deprecate this function
    /**
     * Return the proxy host set by the user.
     * @param ctx A Context used to get the settings for the proxy host.
     * @return String containing the host name. If the user did not set a host
     *         name it returns the default host. A null value means that no
     *         host is to be used.
     */
    public static final String getHost(Context ctx) {
        sProxyInfoLock.readLock().lock();
        try {
            if (sGlobalProxyChangedObserver == null) {
                registerContentObserversReadLocked(ctx);
                parseGlobalProxyInfoReadLocked(ctx);
            }
            if (sGlobalProxySpec != null) {
                InetSocketAddress sa = sGlobalProxySpec.proxyAddress;
                return sa.getHostName();
            }
            return getDefaultHost();
        } finally {
            sProxyInfoLock.readLock().unlock();
        }
    }

    // TODO: deprecate this function
    /**
     * Return the proxy port set by the user.
     * @param ctx A Context used to get the settings for the proxy port.
     * @return The port number to use or -1 if no proxy is to be used.
     */
    public static final int getPort(Context ctx) {
        sProxyInfoLock.readLock().lock();
        try {
            if (sGlobalProxyChangedObserver == null) {
                registerContentObserversReadLocked(ctx);
                parseGlobalProxyInfoReadLocked(ctx);
            }
            if (sGlobalProxySpec != null) {
                InetSocketAddress sa = sGlobalProxySpec.proxyAddress;
                return sa.getPort();
            }
            return getDefaultPort();
        } finally {
            sProxyInfoLock.readLock().unlock();
        }
    }

    // TODO: deprecate this function
    /**
     * Return the default proxy host specified by the carrier.
     * @return String containing the host name or null if there is no proxy for
     * this carrier.
     */
    public static final String getDefaultHost() {
        String host = SystemProperties.get("net.gprs.http-proxy");
        if (host != null) {
            Uri u = Uri.parse(host);
            host = u.getHost();
            return host;
        } else {
            return null;
        }
    }

    // TODO: deprecate this function
    /**
     * Return the default proxy port specified by the carrier.
     * @return The port number to be used with the proxy host or -1 if there is
     * no proxy for this carrier.
     */
    public static final int getDefaultPort() {
        String host = SystemProperties.get("net.gprs.http-proxy");
        if (host != null) {
            Uri u = Uri.parse(host);
            return u.getPort();
        } else {
            return -1;
        }
    }

    private static final java.net.Proxy getDefaultProxy(String url) {
        // TODO: This will go away when information is collected from ConnectivityManager...
        // There are broadcast of network proxies, so they are parse manually.
        String host = SystemProperties.get("net.gprs.http-proxy");
        if (host != null) {
            Uri u = Uri.parse(host);
            return new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new InetSocketAddress(u.getHost(), u.getPort()));
        } else {
            return java.net.Proxy.NO_PROXY;
        }
    }

    // TODO: remove this function / deprecate
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
     * {@hide}
     */
    public static final HttpHost getPreferredHttpHost(Context context,
            String url) {
        java.net.Proxy prefProxy = getProxy(context, url);
        if (prefProxy.equals(java.net.Proxy.NO_PROXY)) {
            return null;
        } else {
            InetSocketAddress sa = (InetSocketAddress)prefProxy.address();
            return new HttpHost(sa.getHostName(), sa.getPort(), "http");
        }
    }

    private static final boolean isLocalHost(String url) {
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
                if (InetAddress.getByName(host).isLoopbackAddress()) {
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

    private static final boolean isNetworkWifi(Context context) {
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

    private static class SettingsObserver extends ContentObserver {

        private Context mContext;

        SettingsObserver(Context ctx) {
            super(new Handler());
            mContext = ctx;
        }

        @Override
        public void onChange(boolean selfChange) {
            sProxyInfoLock.readLock().lock();
            parseGlobalProxyInfoReadLocked(mContext);
            sProxyInfoLock.readLock().unlock();
        }
    }

    private static final void registerContentObserversReadLocked(Context ctx) {
        Uri uriGlobalProxy = Settings.Secure.getUriFor(Settings.Secure.HTTP_PROXY);
        Uri uriGlobalExclList =
            Settings.Secure.getUriFor(Settings.Secure.HTTP_PROXY_EXCLUSION_LIST);

        // No lock upgrading (from read to write) allowed
        sProxyInfoLock.readLock().unlock();
        sProxyInfoLock.writeLock().lock();
        sGlobalProxyChangedObserver = new SettingsObserver(ctx);
        // Downgrading locks (from write to read) is allowed
        sProxyInfoLock.readLock().lock();
        sProxyInfoLock.writeLock().unlock();
        ctx.getContentResolver().registerContentObserver(uriGlobalProxy, false,
                sGlobalProxyChangedObserver);
        ctx.getContentResolver().registerContentObserver(uriGlobalExclList, false,
                sGlobalProxyChangedObserver);
    }

    private static final void parseGlobalProxyInfoReadLocked(Context ctx) {
        ContentResolver contentResolver = ctx.getContentResolver();
        String proxyHost =  Settings.Secure.getString(
                contentResolver,
                Settings.Secure.HTTP_PROXY);
        if (proxyHost == null) {
            return;
        }
        String exclusionListSpec = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.HTTP_PROXY_EXCLUSION_LIST);
        String host = parseHost(proxyHost);
        int port = parsePort(proxyHost);
        if (proxyHost != null) {
            sGlobalProxySpec = new ProxySpec();
            sGlobalProxySpec.proxyAddress = new InetSocketAddress(host, port);
            if (exclusionListSpec != null) {
                String[] exclusionListEntries = exclusionListSpec.toLowerCase().split(",");
                String[] processedEntries = new String[exclusionListEntries.length];
                for (int i = 0; i < exclusionListEntries.length; i++) {
                    String entry = exclusionListEntries[i].trim();
                    if (entry.startsWith(".")) {
                        entry = entry.substring(1);
                    }
                    processedEntries[i] = entry;
                }
                sProxyInfoLock.readLock().unlock();
                sProxyInfoLock.writeLock().lock();
                sGlobalProxySpec.exclusionList = processedEntries;
            } else {
                sProxyInfoLock.readLock().unlock();
                sProxyInfoLock.writeLock().lock();
                sGlobalProxySpec.exclusionList = null;
            }
        } else {
            sProxyInfoLock.readLock().unlock();
            sProxyInfoLock.writeLock().lock();
            sGlobalProxySpec = null;
        }
        sProxyInfoLock.readLock().lock();
        sProxyInfoLock.writeLock().unlock();
    }

    /**
     * Validate syntax of hostname, port and exclusion list entries
     * {@hide}
     */
    public static void validate(String hostname, String port, String exclList) {
        Matcher match = HOSTNAME_PATTERN.matcher(hostname);
        Matcher listMatch = EXCLLIST_PATTERN.matcher(exclList);

        if (!match.matches()) {
            throw new IllegalArgumentException();
        }

        if (!listMatch.matches()) {
            throw new IllegalArgumentException();
        }

        if (hostname.length() > 0 && port.length() == 0) {
            throw new IllegalArgumentException();
        }

        if (port.length() > 0) {
            if (hostname.length() == 0) {
                throw new IllegalArgumentException();
            }
            int portVal = -1;
            try {
                portVal = Integer.parseInt(port);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException();
            }
            if (portVal <= 0 || portVal > 0xFFFF) {
                throw new IllegalArgumentException();
            }
        }
    }
}
