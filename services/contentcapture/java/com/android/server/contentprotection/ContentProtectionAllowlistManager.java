/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.contentprotection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.UserHandle;
import android.service.contentcapture.IContentProtectionAllowlistCallback;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.server.contentcapture.ContentCaptureManagerService;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages whether the content protection is enabled for an app using a allowlist.
 *
 * @hide
 */
public class ContentProtectionAllowlistManager {

    private static final String TAG = "ContentProtectionAllowlistManager";

    @NonNull private final ContentCaptureManagerService mContentCaptureManagerService;

    @NonNull private final Handler mHandler;

    private final long mTimeoutMs;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @NonNull
    final PackageMonitor mPackageMonitor;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @NonNull
    final IContentProtectionAllowlistCallback mAllowlistCallback;

    private final Object mHandlerToken = new Object();

    private final Object mLock = new Object();

    // Used outside of the handler
    private boolean mStarted;

    // Used inside the handler
    @Nullable private Instant mUpdatePendingUntil;

    @NonNull
    @GuardedBy("mLock")
    private Set<String> mAllowedPackages = Set.of();

    public ContentProtectionAllowlistManager(
            @NonNull ContentCaptureManagerService contentCaptureManagerService,
            @NonNull Handler handler,
            long timeoutMs) {
        mContentCaptureManagerService = contentCaptureManagerService;
        mHandler = handler;
        mTimeoutMs = timeoutMs;
        mPackageMonitor = createPackageMonitor();
        mAllowlistCallback = createAllowlistCallback();
    }

    /** Starts the manager. */
    public void start(long delayMs) {
        if (mStarted) {
            return;
        }
        mStarted = true;
        mHandler.postDelayed(this::handleInitialUpdate, mHandlerToken, delayMs);
        // PackageMonitor will be registered inside handleInitialUpdate to respect the initial delay
    }

    /** Stops the manager. */
    public void stop() {
        try {
            mPackageMonitor.unregister();
        } catch (IllegalStateException ex) {
            // Swallow, throws if not registered
        }
        mHandler.removeCallbacksAndMessages(mHandlerToken);
        mUpdatePendingUntil = null;
        mStarted = false;
    }

    /** Returns true if the package is allowed. */
    public boolean isAllowed(@NonNull String packageName) {
        Set<String> allowedPackages;
        synchronized (mLock) {
            allowedPackages = mAllowedPackages;
        }
        return allowedPackages.contains(packageName);
    }

    private void handleUpdateAllowlistResponse(@NonNull List<String> packages) {
        synchronized (mLock) {
            mAllowedPackages = packages.stream().collect(Collectors.toUnmodifiableSet());
        }
        mUpdatePendingUntil = null;
    }

    private void handleInitialUpdate() {
        handlePackagesChanged();

        // Initial update done, start listening to package updates now
        mPackageMonitor.register(
                mContentCaptureManagerService.getContext(), UserHandle.ALL, mHandler);
    }

    private void handlePackagesChanged() {
        /**
         * PackageMonitor callback can be invoked more than once in a matter of milliseconds on the
         * same monitor instance for the same package (eg: b/295969873). This check acts both as a
         * simple generic rate limit and as a mitigation for this quirk.
         */
        if (mUpdatePendingUntil != null && Instant.now().isBefore(mUpdatePendingUntil)) {
            return;
        }

        RemoteContentProtectionService remoteContentProtectionService =
                mContentCaptureManagerService.createRemoteContentProtectionService();
        if (remoteContentProtectionService == null) {
            return;
        }

        // If there are any pending updates queued already, they can be removed immediately
        mHandler.removeCallbacksAndMessages(mHandlerToken);
        mUpdatePendingUntil = Instant.now().plusMillis(mTimeoutMs);

        try {
            remoteContentProtectionService.onUpdateAllowlistRequest(mAllowlistCallback);
        } catch (Exception ex) {
            Slog.e(TAG, "Failed to call remote service", ex);
        }
    }

    /** @hide */
    @NonNull
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected PackageMonitor createPackageMonitor() {
        return new ContentProtectionPackageMonitor();
    }

    /** @hide */
    @NonNull
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected IContentProtectionAllowlistCallback createAllowlistCallback() {
        return new ContentProtectionAllowlistCallback();
    }

    private final class ContentProtectionPackageMonitor extends PackageMonitor {

        // This callback might be invoked multiple times, for more info refer to the comment above
        @Override
        public void onSomePackagesChanged() {
            handlePackagesChanged();
        }
    }

    private final class ContentProtectionAllowlistCallback
            extends IContentProtectionAllowlistCallback.Stub {

        @Override
        public void setAllowlist(List<String> packages) {
            mHandler.post(() -> handleUpdateAllowlistResponse(packages));
        }
    }
}
