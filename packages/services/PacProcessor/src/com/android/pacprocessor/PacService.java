/**
 * Copyright (c) 2013, The Android Open Source Project
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
package com.android.pacprocessor;

import android.app.Service;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import com.android.net.IProxyService;

import java.net.MalformedURLException;
import java.net.URL;

public class PacService extends Service {
    private static final String TAG = "PacService";
    private static final boolean sUseWebViewPacProcessor = Resources.getSystem().getBoolean(
            com.android.internal.R.bool.config_useWebViewPacProcessor);

    private final LibpacInterface mLibpac = sUseWebViewPacProcessor
            ? PacWebView.getInstance()
            : PacNative.getInstance();

    private ProxyServiceStub mStub = new ProxyServiceStub();

    @Override
    public void onCreate() {
        super.onCreate();
        mLibpac.startPacSupport();
    }

    @Override
    public void onDestroy() {
        mLibpac.stopPacSupport();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mStub;
    }

    private class ProxyServiceStub extends IProxyService.Stub {
        @Override
        public String resolvePacFile(String host, String url) throws RemoteException {
            try {
                if (host == null) {
                    throw new IllegalArgumentException("The host must not be null");
                }
                if (url == null) {
                    throw new IllegalArgumentException("The URL must not be null");
                }
                // Check for characters that could be used for an injection attack.
                new URL(url);
                for (char c : host.toCharArray()) {
                    if (!Character.isLetterOrDigit(c) && (c != '.') && (c != '-')) {
                        throw new IllegalArgumentException("Invalid host was passed");
                    }
                }
                return mLibpac.makeProxyRequest(url, host);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Invalid URL was passed");
            }
        }

        @Override
        public void setPacFile(String script) throws RemoteException {
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                Log.e(TAG, "Only system user is allowed to call setPacFile");
                throw new SecurityException();
            }
            mLibpac.setCurrentProxyScript(script);
        }

        @Override
        public void startPacSystem() throws RemoteException {
            //TODO: remove
        }

        @Override
        public void stopPacSystem() throws RemoteException {
            //TODO: remove
        }
    }
}
