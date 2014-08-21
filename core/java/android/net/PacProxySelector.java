/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.net.IProxyService;
import com.google.android.collect.Lists;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

/**
 * @hide
 */
public class PacProxySelector extends ProxySelector {
    private static final String TAG = "PacProxySelector";
    public static final String PROXY_SERVICE = "com.android.net.IProxyService";
    private static final String SOCKS = "SOCKS ";
    private static final String PROXY = "PROXY ";

    private IProxyService mProxyService;
    private final List<Proxy> mDefaultList;

    public PacProxySelector() {
        mProxyService = IProxyService.Stub.asInterface(
                ServiceManager.getService(PROXY_SERVICE));
        if (mProxyService == null) {
            // Added because of b10267814 where mako is restarting.
            Log.e(TAG, "PacManager: no proxy service");
        }
        mDefaultList = Lists.newArrayList(java.net.Proxy.NO_PROXY);
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (mProxyService == null) {
            mProxyService = IProxyService.Stub.asInterface(
                    ServiceManager.getService(PROXY_SERVICE));
        }
        if (mProxyService == null) {
            Log.e(TAG, "select: no proxy service return NO_PROXY");
            return Lists.newArrayList(java.net.Proxy.NO_PROXY);
        }
        String response = null;
        String urlString;
        try {
            urlString = uri.toURL().toString();
        } catch (MalformedURLException e) {
            urlString = uri.getHost();
        }
        try {
            response = mProxyService.resolvePacFile(uri.getHost(), urlString);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        if (response == null) {
            return mDefaultList;
        }

        return parseResponse(response);
    }

    private static List<Proxy> parseResponse(String response) {
        String[] split = response.split(";");
        List<Proxy> ret = Lists.newArrayList();
        for (String s : split) {
            String trimmed = s.trim();
            if (trimmed.equals("DIRECT")) {
                ret.add(java.net.Proxy.NO_PROXY);
            } else if (trimmed.startsWith(PROXY)) {
                Proxy proxy = proxyFromHostPort(Type.HTTP, trimmed.substring(PROXY.length()));
                if (proxy != null) {
                    ret.add(proxy);
                }
            } else if (trimmed.startsWith(SOCKS)) {
                Proxy proxy = proxyFromHostPort(Type.SOCKS, trimmed.substring(SOCKS.length()));
                if (proxy != null) {
                    ret.add(proxy);
                }
            }
        }
        if (ret.size() == 0) {
            ret.add(java.net.Proxy.NO_PROXY);
        }
        return ret;
    }

    private static Proxy proxyFromHostPort(Proxy.Type type, String hostPortString) {
        try {
            String[] hostPort = hostPortString.split(":");
            String host = hostPort[0];
            int port = Integer.parseInt(hostPort[1]);
            return new Proxy(type, InetSocketAddress.createUnresolved(host, port));
        } catch (NumberFormatException|ArrayIndexOutOfBoundsException e) {
            Log.d(TAG, "Unable to parse proxy " + hostPortString + " " + e);
            return null;
        }
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, IOException failure) {

    }

}
