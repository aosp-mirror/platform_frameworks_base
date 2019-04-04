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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.PatternMatcher;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.Slog;
import android.webkit.IWebViewUpdateService;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewProviderResponse;

import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Private service to wait for the updatable WebView to be ready for use.
 * @hide
 */
public class WebViewUpdateService extends SystemService {

    private static final String TAG = "WebViewUpdateService";

    private BroadcastReceiver mWebViewUpdatedReceiver;
    private WebViewUpdateServiceImpl mImpl;

    static final int PACKAGE_CHANGED = 0;
    static final int PACKAGE_ADDED = 1;
    static final int PACKAGE_ADDED_REPLACED = 2;
    static final int PACKAGE_REMOVED = 3;

    public WebViewUpdateService(Context context) {
        super(context);
        mImpl = new WebViewUpdateServiceImpl(context, SystemImpl.getInstance());
    }

    @Override
    public void onStart() {
        mWebViewUpdatedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                    switch (intent.getAction()) {
                        case Intent.ACTION_PACKAGE_REMOVED:
                            // When a package is replaced we will receive two intents, one
                            // representing the removal of the old package and one representing the
                            // addition of the new package.
                            // In the case where we receive an intent to remove the old version of
                            // the package that is being replaced we early-out here so that we don't
                            // run the update-logic twice.
                            if (intent.getExtras().getBoolean(Intent.EXTRA_REPLACING)) return;
                            mImpl.packageStateChanged(packageNameFromIntent(intent),
                                    PACKAGE_REMOVED, userId);
                            break;
                        case Intent.ACTION_PACKAGE_CHANGED:
                            // Ensure that we only heed PACKAGE_CHANGED intents if they change an
                            // entire package, not just a component
                            if (entirePackageChanged(intent)) {
                                mImpl.packageStateChanged(packageNameFromIntent(intent),
                                        PACKAGE_CHANGED, userId);
                            }
                            break;
                        case Intent.ACTION_PACKAGE_ADDED:
                            mImpl.packageStateChanged(packageNameFromIntent(intent),
                                    (intent.getExtras().getBoolean(Intent.EXTRA_REPLACING)
                                     ? PACKAGE_ADDED_REPLACED : PACKAGE_ADDED), userId);
                            break;
                        case Intent.ACTION_USER_STARTED:
                            mImpl.handleNewUser(userId);
                            break;
                        case Intent.ACTION_USER_REMOVED:
                            mImpl.handleUserRemoved(userId);
                            break;
                    }
                }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        // Make sure we only receive intents for WebView packages from our config file.
        for (WebViewProviderInfo provider : mImpl.getWebViewPackages()) {
            filter.addDataSchemeSpecificPart(provider.packageName, PatternMatcher.PATTERN_LITERAL);
        }

        getContext().registerReceiverAsUser(mWebViewUpdatedReceiver, UserHandle.ALL, filter,
                null /* broadcast permission */, null /* handler */);

        IntentFilter userAddedFilter = new IntentFilter();
        userAddedFilter.addAction(Intent.ACTION_USER_STARTED);
        userAddedFilter.addAction(Intent.ACTION_USER_REMOVED);
        getContext().registerReceiverAsUser(mWebViewUpdatedReceiver, UserHandle.ALL,
                userAddedFilter, null /* broadcast permission */, null /* handler */);

        publishBinderService("webviewupdate", new BinderService(), true /*allowIsolated*/);
    }

    public void prepareWebViewInSystemServer() {
        mImpl.prepareWebViewInSystemServer();
    }

    private static String packageNameFromIntent(Intent intent) {
        return intent.getDataString().substring("package:".length());
    }

    /**
     * Returns whether the entire package from an ACTION_PACKAGE_CHANGED intent was changed (rather
     * than just one of its components).
     * @hide
     */
    public static boolean entirePackageChanged(Intent intent) {
        String[] componentList =
            intent.getStringArrayExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
        return Arrays.asList(componentList).contains(
                intent.getDataString().substring("package:".length()));
    }

    private class BinderService extends IWebViewUpdateService.Stub {

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            (new WebViewUpdateServiceShellCommand(this)).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }


        /**
         * The shared relro process calls this to notify us that it's done trying to create a relro
         * file. This method gets called even if the relro creation has failed or the process
         * crashed.
         */
        @Override // Binder call
        public void notifyRelroCreationCompleted() {
            // Verify that the caller is either the shared relro process (nominal case) or the
            // system server (only in the case the relro process crashes and we get here via the
            // crashHandler).
            if (Binder.getCallingUid() != Process.SHARED_RELRO_UID &&
                    Binder.getCallingUid() != Process.SYSTEM_UID) {
                return;
            }

            long callingId = Binder.clearCallingIdentity();
            try {
                WebViewUpdateService.this.mImpl.notifyRelroCreationCompleted();
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }
        }

        /**
         * WebViewFactory calls this to block WebView loading until the relro file is created.
         * Returns the WebView provider for which we create relro files.
         */
        @Override // Binder call
        public WebViewProviderResponse waitForAndGetProvider() {
            // The WebViewUpdateService depends on the prepareWebViewInSystemServer call, which
            // happens later (during the PHASE_ACTIVITY_MANAGER_READY) in SystemServer.java. If
            // another service there tries to bring up a WebView in the between, the wait below
            // would deadlock without the check below.
            if (Binder.getCallingPid() == Process.myPid()) {
                throw new IllegalStateException("Cannot create a WebView from the SystemServer");
            }

            return WebViewUpdateService.this.mImpl.waitForAndGetProvider();
        }

        /**
         * This is called from DeveloperSettings when the user changes WebView provider.
         */
        @Override // Binder call
        public String changeProviderAndSetting(String newProvider) {
            if (getContext().checkCallingPermission(
                        android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg = "Permission Denial: changeProviderAndSetting() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + android.Manifest.permission.WRITE_SECURE_SETTINGS;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }

            long callingId = Binder.clearCallingIdentity();
            try {
                return WebViewUpdateService.this.mImpl.changeProviderAndSetting(
                        newProvider);
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }
        }

        @Override // Binder call
        public WebViewProviderInfo[] getValidWebViewPackages() {
            return WebViewUpdateService.this.mImpl.getValidWebViewPackages();
        }

        @Override // Binder call
        public WebViewProviderInfo[] getAllWebViewPackages() {
            return WebViewUpdateService.this.mImpl.getWebViewPackages();
        }

        @Override // Binder call
        public String getCurrentWebViewPackageName() {
            PackageInfo pi = WebViewUpdateService.this.mImpl.getCurrentWebViewPackage();
            return pi == null ? null : pi.packageName;
        }

        @Override // Binder call
        public PackageInfo getCurrentWebViewPackage() {
            return WebViewUpdateService.this.mImpl.getCurrentWebViewPackage();
        }

        @Override // Binder call
        public boolean isMultiProcessEnabled() {
            return WebViewUpdateService.this.mImpl.isMultiProcessEnabled();
        }

        @Override // Binder call
        public void enableMultiProcess(boolean enable) {
            if (getContext().checkCallingPermission(
                        android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg = "Permission Denial: enableMultiProcess() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + android.Manifest.permission.WRITE_SECURE_SETTINGS;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }

            long callingId = Binder.clearCallingIdentity();
            try {
                WebViewUpdateService.this.mImpl.enableMultiProcess(enable);
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;
            WebViewUpdateService.this.mImpl.dumpState(pw);
        }
    }
}
