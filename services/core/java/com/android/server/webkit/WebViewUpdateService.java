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
import android.util.Log;
import android.webkit.IWebViewUpdateService;
import android.webkit.WebViewFactory;

/**
 * Private service to wait for the updatable WebView to be ready for use.
 * @hide
 */
public class WebViewUpdateService extends IWebViewUpdateService.Stub {

    private static final String TAG = "WebViewUpdateService";

    private boolean mRelroReady32Bit = false;
    private boolean mRelroReady64Bit = false;

    private BroadcastReceiver mWebViewUpdatedReceiver;

    public WebViewUpdateService(Context context) {
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
        context.registerReceiver(mWebViewUpdatedReceiver, filter);
    }

    /**
     * The shared relro process calls this to notify us that it's done trying to create a relro
     * file.
     */
    public void notifyRelroCreationCompleted(boolean is64Bit, boolean success) {
        // Verify that the caller is the shared relro process.
        if (Binder.getCallingUid() != Process.SHARED_RELRO_UID) {
            return;
        }

        synchronized (this) {
            if (is64Bit) {
                mRelroReady64Bit = true;
            } else {
                mRelroReady32Bit = true;
            }
            this.notifyAll();
        }
    }

    /**
     * WebViewFactory calls this to block WebView loading until the relro file is created.
     */
    public void waitForRelroCreationCompleted(boolean is64Bit) {
        synchronized (this) {
            if (is64Bit) {
                while (!mRelroReady64Bit) {
                    try {
                        this.wait();
                    } catch (InterruptedException e) {}
                }
            } else {
                while (!mRelroReady32Bit) {
                    try {
                        this.wait();
                    } catch (InterruptedException e) {}
                }
            }
        }
    }

    private void onWebViewUpdateInstalled() {
        Log.d(TAG, "WebView Package updated!");

        synchronized (this) {
            mRelroReady32Bit = false;
            mRelroReady64Bit = false;
        }
        WebViewFactory.prepareWebViewInSystemServer();
    }
}
