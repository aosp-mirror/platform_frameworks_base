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
    @Nullable
    @GuardedBy("mProxyLock")
    public ProxyInfo mGlobalProxy = null;
    @Nullable
    @GuardedBy("mProxyLock")
    public volatile ProxyInfo mDefaultProxy = null;
    @GuardedBy("mProxyLock")
    public boolean mDefaultProxyDisabled = false;

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

    @Nullable
    public ProxyInfo getDefaultProxy() {
        // This information is already available as a world read/writable jvm property.
        synchronized (mProxyLock) {
            final ProxyInfo ret = mGlobalProxy;
            if ((ret == null) && !mDefaultProxyDisabled) return mDefaultProxy;
            return ret;
        }
    }

    @Nullable
    public ProxyInfo getGlobalProxy() {
        // This information is already available as a world read/writable jvm property.
        synchronized (mProxyLock) {
            return mGlobalProxy;
        }
    }

    public boolean setCurrentProxyScriptUrl(@NonNull final ProxyInfo proxy) {
        return mPacManager.setCurrentProxyScriptUrl(proxy);
    }

    // TODO : make the argument NonNull final
    public void sendProxyBroadcast(@Nullable ProxyInfo proxy) {
        if (proxy == null) proxy = new ProxyInfo("", 0, "");
        if (setCurrentProxyScriptUrl(proxy)) return;
        if (DBG) Slog.d(TAG, "sending Proxy Broadcast for " + proxy);
        Intent intent = new Intent(Proxy.PROXY_CHANGE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING |
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(Proxy.EXTRA_PROXY_INFO, proxy);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void setGlobalProxy(@Nullable ProxyInfo proxyProperties) {
        synchronized (mProxyLock) {
            if (proxyProperties == mGlobalProxy) return;
            if (proxyProperties != null && proxyProperties.equals(mGlobalProxy)) return;
            if (mGlobalProxy != null && mGlobalProxy.equals(proxyProperties)) return;

            String host = "";
            int port = 0;
            String exclList = "";
            String pacFileUrl = "";
            if (proxyProperties != null && (!TextUtils.isEmpty(proxyProperties.getHost()) ||
                    !Uri.EMPTY.equals(proxyProperties.getPacFileUrl()))) {
                if (!proxyProperties.isValid()) {
                    if (DBG) Slog.d(TAG, "Invalid proxy properties, ignoring: " + proxyProperties);
                    return;
                }
                mGlobalProxy = new ProxyInfo(proxyProperties);
                host = mGlobalProxy.getHost();
                port = mGlobalProxy.getPort();
                exclList = mGlobalProxy.getExclusionListAsString();
                if (!Uri.EMPTY.equals(proxyProperties.getPacFileUrl())) {
                    pacFileUrl = proxyProperties.getPacFileUrl().toString();
                }
            } else {
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

            if (mGlobalProxy == null) {
                proxyProperties = mDefaultProxy;
            }
            sendProxyBroadcast(proxyProperties);
        }
    }
}
