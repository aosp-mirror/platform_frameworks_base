/**
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Proxy;
import android.net.ProxyInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.Objects;

/**
 * A class to handle proxy for ConnectivityService.
 *
 * @hide
 */
public class ProxyTracker {
    private static final String TAG = ProxyTracker.class.getSimpleName();
    private static final boolean DBG = true;

    @NonNull
    private final Context mContext;

    // TODO : make this private and import as much managing logic from ConnectivityService as
    // possible
    @NonNull
    public final Object mProxyLock = new Object();
    // The global proxy is the proxy that is set device-wide, overriding any network-specific
    // proxy. Note however that proxies are hints ; the system does not enforce their use. Hence
    // this value is only for querying.
    @Nullable
    @GuardedBy("mProxyLock")
    public ProxyInfo mGlobalProxy = null;
    // The default proxy is the proxy that applies to no particular network if the global proxy
    // is not set. Individual networks have their own settings that override this. This member
    // is set through setDefaultProxy, which is called when the default network changes proxies
    // in its LinkProperties, or when ConnectivityService switches to a new default network, or
    // when PacManager resolves the proxy.
    @Nullable
    @GuardedBy("mProxyLock")
    public volatile ProxyInfo mDefaultProxy = null;
    // Whether the default proxy is disabled. TODO : make this mDefaultProxyEnabled
    @GuardedBy("mProxyLock")
    public boolean mDefaultProxyDisabled = false;

    // The object responsible for Proxy Auto Configuration (PAC).
    @NonNull
    private final PacManager mPacManager;

    public ProxyTracker(@NonNull final Context context,
            @NonNull final Handler connectivityServiceInternalHandler, final int pacChangedEvent) {
        mContext = context;
        mPacManager = new PacManager(context, connectivityServiceInternalHandler, pacChangedEvent);
    }

    // Convert empty ProxyInfo's to null as null-checks are used to determine if proxies are present
    // (e.g. if mGlobalProxy==null fall back to network-specific proxy, if network-specific
    // proxy is null then there is no proxy in place).
    @Nullable
    private static ProxyInfo canonicalizeProxyInfo(@Nullable final ProxyInfo proxy) {
        if (proxy != null && TextUtils.isEmpty(proxy.getHost())
                && (proxy.getPacFileUrl() == null || Uri.EMPTY.equals(proxy.getPacFileUrl()))) {
            return null;
        }
        return proxy;
    }

    // ProxyInfo equality functions with a couple modifications over ProxyInfo.equals() to make it
    // better for determining if a new proxy broadcast is necessary:
    // 1. Canonicalize empty ProxyInfos to null so an empty proxy compares equal to null so as to
    //    avoid unnecessary broadcasts.
    // 2. Make sure all parts of the ProxyInfo's compare true, including the host when a PAC URL
    //    is in place.  This is important so legacy PAC resolver (see com.android.proxyhandler)
    //    changes aren't missed.  The legacy PAC resolver pretends to be a simple HTTP proxy but
    //    actually uses the PAC to resolve; this results in ProxyInfo's with PAC URL, host and port
    //    all set.
    public static boolean proxyInfoEqual(@Nullable final ProxyInfo a, @Nullable final ProxyInfo b) {
        final ProxyInfo pa = canonicalizeProxyInfo(a);
        final ProxyInfo pb = canonicalizeProxyInfo(b);
        // ProxyInfo.equals() doesn't check hosts when PAC URLs are present, but we need to check
        // hosts even when PAC URLs are present to account for the legacy PAC resolver.
        return Objects.equals(pa, pb) && (pa == null || Objects.equals(pa.getHost(), pb.getHost()));
    }

    /**
     * Gets the default system-wide proxy.
     *
     * This will return the global proxy if set, otherwise the default proxy if in use. Note
     * that this is not necessarily the proxy that any given process should use, as the right
     * proxy for a process is the proxy for the network this process will use, which may be
     * different from this value. This value is simply the default in case there is no proxy set
     * in the network that will be used by a specific process.
     * @return The default system-wide proxy or null if none.
     */
    @Nullable
    public ProxyInfo getDefaultProxy() {
        // This information is already available as a world read/writable jvm property.
        synchronized (mProxyLock) {
            final ProxyInfo ret = mGlobalProxy;
            if ((ret == null) && !mDefaultProxyDisabled) return mDefaultProxy;
            return ret;
        }
    }

    /**
     * Gets the global proxy.
     *
     * @return The global proxy or null if none.
     */
    @Nullable
    public ProxyInfo getGlobalProxy() {
        // This information is already available as a world read/writable jvm property.
        synchronized (mProxyLock) {
            return mGlobalProxy;
        }
    }

    /**
     * Read the global proxy settings and cache them in memory.
     */
    public void loadGlobalProxy() {
        ContentResolver res = mContext.getContentResolver();
        String host = Settings.Global.getString(res, Settings.Global.GLOBAL_HTTP_PROXY_HOST);
        int port = Settings.Global.getInt(res, Settings.Global.GLOBAL_HTTP_PROXY_PORT, 0);
        String exclList = Settings.Global.getString(res,
                Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST);
        String pacFileUrl = Settings.Global.getString(res, Settings.Global.GLOBAL_HTTP_PROXY_PAC);
        if (!TextUtils.isEmpty(host) || !TextUtils.isEmpty(pacFileUrl)) {
            ProxyInfo proxyProperties;
            if (!TextUtils.isEmpty(pacFileUrl)) {
                proxyProperties = new ProxyInfo(pacFileUrl);
            } else {
                proxyProperties = new ProxyInfo(host, port, exclList);
            }
            if (!proxyProperties.isValid()) {
                if (DBG) Slog.d(TAG, "Invalid proxy properties, ignoring: " + proxyProperties);
                return;
            }

            synchronized (mProxyLock) {
                mGlobalProxy = proxyProperties;
            }
        }
        loadDeprecatedGlobalHttpProxy();
        // TODO : shouldn't this function call mPacManager.setCurrentProxyScriptUrl ?
    }

    /**
     * Read the global proxy from the deprecated Settings.Global.HTTP_PROXY setting and apply it.
     */
    public void loadDeprecatedGlobalHttpProxy() {
        final String proxy = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.HTTP_PROXY);
        if (!TextUtils.isEmpty(proxy)) {
            String data[] = proxy.split(":");
            if (data.length == 0) {
                return;
            }

            final String proxyHost = data[0];
            int proxyPort = 8080;
            if (data.length > 1) {
                try {
                    proxyPort = Integer.parseInt(data[1]);
                } catch (NumberFormatException e) {
                    return;
                }
            }
            final ProxyInfo p = new ProxyInfo(proxyHost, proxyPort, "");
            setGlobalProxy(p);
        }
    }

    /**
     * Sends the system broadcast informing apps about a new proxy configuration.
     *
     * Confusingly this method also sets the PAC file URL. TODO : separate this, it has nothing
     * to do in a "sendProxyBroadcast" method.
     * @param proxyInfo the proxy spec, or null for no proxy.
     */
    // TODO : make the argument NonNull final and the method private
    public void sendProxyBroadcast(@Nullable ProxyInfo proxyInfo) {
        if (proxyInfo == null) proxyInfo = new ProxyInfo("", 0, "");
        if (mPacManager.setCurrentProxyScriptUrl(proxyInfo)) return;
        if (DBG) Slog.d(TAG, "sending Proxy Broadcast for " + proxyInfo);
        Intent intent = new Intent(Proxy.PROXY_CHANGE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING |
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(Proxy.EXTRA_PROXY_INFO, proxyInfo);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Sets the global proxy in memory. Also writes the values to the global settings of the device.
     *
     * @param proxyInfo the proxy spec, or null for no proxy.
     */
    public void setGlobalProxy(@Nullable ProxyInfo proxyInfo) {
        synchronized (mProxyLock) {
            // ProxyInfo#equals is not commutative :( and is public API, so it can't be fixed.
            if (proxyInfo == mGlobalProxy) return;
            if (proxyInfo != null && proxyInfo.equals(mGlobalProxy)) return;
            if (mGlobalProxy != null && mGlobalProxy.equals(proxyInfo)) return;

            final String host;
            final int port;
            final String exclList;
            final String pacFileUrl;
            if (proxyInfo != null && (!TextUtils.isEmpty(proxyInfo.getHost()) ||
                    !Uri.EMPTY.equals(proxyInfo.getPacFileUrl()))) {
                if (!proxyInfo.isValid()) {
                    if (DBG) Slog.d(TAG, "Invalid proxy properties, ignoring: " + proxyInfo);
                    return;
                }
                mGlobalProxy = new ProxyInfo(proxyInfo);
                host = mGlobalProxy.getHost();
                port = mGlobalProxy.getPort();
                exclList = mGlobalProxy.getExclusionListAsString();
                pacFileUrl = Uri.EMPTY.equals(proxyInfo.getPacFileUrl())
                        ? "" : proxyInfo.getPacFileUrl().toString();
            } else {
                host = "";
                port = 0;
                exclList = "";
                pacFileUrl = "";
                mGlobalProxy = null;
            }
            final ContentResolver res = mContext.getContentResolver();
            final long token = Binder.clearCallingIdentity();
            try {
                Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_HOST, host);
                Settings.Global.putInt(res, Settings.Global.GLOBAL_HTTP_PROXY_PORT, port);
                Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST,
                        exclList);
                Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_PAC, pacFileUrl);
            } finally {
                Binder.restoreCallingIdentity(token);
            }

            sendProxyBroadcast(mGlobalProxy == null ? mDefaultProxy : proxyInfo);
        }
    }

    /**
     * Sets the default proxy for the device.
     *
     * The default proxy is the proxy used for networks that do not have a specific proxy.
     * @param proxyInfo the proxy spec, or null for no proxy.
     */
    public void setDefaultProxy(@Nullable ProxyInfo proxyInfo) {
        synchronized (mProxyLock) {
            if (mDefaultProxy != null && mDefaultProxy.equals(proxyInfo)) {
                return;
            }
            if (mDefaultProxy == proxyInfo) return; // catches repeated nulls
            if (proxyInfo != null &&  !proxyInfo.isValid()) {
                if (DBG) Slog.d(TAG, "Invalid proxy properties, ignoring: " + proxyInfo);
                return;
            }

            // This call could be coming from the PacManager, containing the port of the local
            // proxy. If this new proxy matches the global proxy then copy this proxy to the
            // global (to get the correct local port), and send a broadcast.
            // TODO: Switch PacManager to have its own message to send back rather than
            // reusing EVENT_HAS_CHANGED_PROXY and this call to handleApplyDefaultProxy.
            if ((mGlobalProxy != null) && (proxyInfo != null)
                    && (!Uri.EMPTY.equals(proxyInfo.getPacFileUrl()))
                    && proxyInfo.getPacFileUrl().equals(mGlobalProxy.getPacFileUrl())) {
                mGlobalProxy = proxyInfo;
                sendProxyBroadcast(mGlobalProxy);
                return;
            }
            mDefaultProxy = proxyInfo;

            if (mGlobalProxy != null) return;
            if (!mDefaultProxyDisabled) {
                sendProxyBroadcast(proxyInfo);
            }
        }
    }
}
