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
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Log;
import android.webkit.PacProcessor;

import com.android.internal.annotations.GuardedBy;
import com.android.net.IProxyService;

import java.net.MalformedURLException;
import java.net.URL;

public class PacService extends Service {
    private static final String TAG = "PacService";

    private final Object mLock = new Object();

    // Webkit PacProcessor cannot be instantiated before the user is unlocked, so this field is
    // initialized lazily.
    @GuardedBy("mLock")
    private PacProcessor mPacProcessor;

    // Stores PAC script when setPacFile is called before mPacProcessor is available. In case the
    // script was already fed to the PacProcessor, it should be null.
    @GuardedBy("mLock")
    private String mPendingScript;

    private ProxyServiceStub mStub = new ProxyServiceStub();

    @Override
    public void onCreate() {
        super.onCreate();

        synchronized (mLock) {
            checkPacProcessorLocked();
        }
    }

    /**
     * Initializes PacProcessor if it hasn't been initialized yet and if the system user is
     * unlocked, e.g. after the user has entered their PIN after a reboot.
     * Returns whether PacProcessor is available.
     */
    private boolean checkPacProcessorLocked() {
        if (mPacProcessor != null) {
            return true;
        }
        UserManager um = getSystemService(UserManager.class);
        if (um.isUserUnlocked()) {
            mPacProcessor = PacProcessor.getInstance();
            return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
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

                synchronized (mLock) {
                    if (checkPacProcessorLocked()) {
                        // Apply pending script in case it was set before processor was ready.
                        if (mPendingScript != null) {
                            if (!mPacProcessor.setProxyScript(mPendingScript)) {
                                Log.e(TAG, "Unable to parse proxy script.");
                            }
                            mPendingScript = null;
                        }
                        return mPacProcessor.findProxyForUrl(url);
                    } else {
                        Log.e(TAG, "PacProcessor isn't ready during early boot,"
                                + " request will be direct");
                        return null;
                    }
                }
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
            synchronized (mLock) {
                if (checkPacProcessorLocked()) {
                    if (!mPacProcessor.setProxyScript(script)) {
                        Log.e(TAG, "Unable to parse proxy script.");
                    }
                } else {
                    Log.d(TAG, "PAC processor isn't ready, saving script for later.");
                    mPendingScript = script;
                }
            }
        }
    }
}
