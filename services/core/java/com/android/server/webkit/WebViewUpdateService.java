/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.webkit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Process;
import android.util.Slog;
import android.webkit.IWebViewUpdateService;
import android.webkit.WebViewFactory;

import com.android.server.SystemService;

/**
 * Private service to wait for the updatable WebView to be ready for use.
 * @hide
 */
public class WebViewUpdateService extends SystemService {

    private static final String TAG = "WebViewUpdateService";
    private static final int WAIT_TIMEOUT_MS = 5000; // Same as KEY_DISPATCHING_TIMEOUT.

    private boolean mRelroReady32Bit = false;
    private boolean mRelroReady64Bit = false;

    private BroadcastReceiver mWebViewUpdatedReceiver;

    public WebViewUpdateService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mWebViewUpdatedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String webviewPackage = "package:" + WebViewFactory.getWebViewPackageName();
                    if (webviewPackage.equals(intent.getDataString())) {
                        onWebViewUpdateInstalled();
                    }
                }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        getContext().registerReceiver(mWebViewUpdatedReceiver, filter);

        publishBinderService("webviewupdate", new BinderService());
    }

    private void onWebViewUpdateInstalled() {
        Slog.d(TAG, "WebView Package updated!");

        synchronized (this) {
            mRelroReady32Bit = false;
            mRelroReady64Bit = false;
        }
        WebViewFactory.onWebViewUpdateInstalled();
    }

    private class BinderService extends IWebViewUpdateService.Stub {

        /**
         * The shared relro process calls this to notify us that it's done trying to create a relro
         * file. This method gets called even if the relro creation has failed or the process
         * crashed.
         */
        @Override // Binder call
        public void notifyRelroCreationCompleted(boolean is64Bit, boolean success) {
            // Verify that the caller is either the shared relro process (nominal case) or the
            // system server (only in the case the relro process crashes and we get here via the
            // crashHandler).
            if (Binder.getCallingUid() != Process.SHARED_RELRO_UID &&
                    Binder.getCallingUid() != Process.SYSTEM_UID) {
                return;
            }

            synchronized (WebViewUpdateService.this) {
                if (is64Bit) {
                    mRelroReady64Bit = true;
                } else {
                    mRelroReady32Bit = true;
                }
                WebViewUpdateService.this.notifyAll();
            }
        }

        /**
         * WebViewFactory calls this to block WebView loading until the relro file is created.
         */
        @Override // Binder call
        public void waitForRelroCreationCompleted(boolean is64Bit) {
            // The WebViewUpdateService depends on the prepareWebViewInSystemServer call, which
            // happens later (during the PHASE_ACTIVITY_MANAGER_READY) in SystemServer.java. If
            // another service there tries to bring up a WebView in the between, the wait below
            // would deadlock without the check below.
            if (Binder.getCallingPid() == Process.myPid()) {
                throw new IllegalStateException("Cannot create a WebView from the SystemServer");
            }

            final long NS_PER_MS = 1000000;
            final long timeoutTimeMs = System.nanoTime() / NS_PER_MS + WAIT_TIMEOUT_MS;
            boolean relroReady = (is64Bit ? mRelroReady64Bit : mRelroReady32Bit);
            synchronized (WebViewUpdateService.this) {
                while (!relroReady) {
                    final long timeNowMs = System.nanoTime() / NS_PER_MS;
                    if (timeNowMs >= timeoutTimeMs) break;
                    try {
                        WebViewUpdateService.this.wait(timeoutTimeMs - timeNowMs);
                    } catch (InterruptedException e) {}
                    relroReady = (is64Bit ? mRelroReady64Bit : mRelroReady32Bit);
                }
            }
            if (!relroReady) Slog.w(TAG, "creating relro file timed out");
        }
    }

}
