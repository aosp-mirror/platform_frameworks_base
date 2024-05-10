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
package com.android.server.flags;

import static android.Manifest.permission.SYNC_FLAGS;
import static android.Manifest.permission.WRITE_FLAGS;

import android.content.Context;
import android.content.pm.PackageManager;
import android.flags.FeatureFlags;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

/**
 * A service that manages syncing {@link android.flags.FeatureFlags} across processes.
 *
 * This service holds flags stable for at least the lifetime of a process, meaning that if
 * a process comes online with a flag set to true, any other process that connects here and
 * tries to read the same flag will also receive the flag as true. The flag will remain stable
 * until either all of the interested processes have died, or the device restarts.
 *
 * TODO(279054964): Add to dumpsys
 * @hide
 */
public class FeatureFlagsService extends SystemService {

    static final String TAG = "FeatureFlagsService";
    private final FlagOverrideStore mFlagStore;
    private final FlagsShellCommand mShellCommand;

    /**
     * Initializes the system service.
     *
     * @param context The system server context.
     */
    public FeatureFlagsService(Context context) {
        super(context);
        mFlagStore = new FlagOverrideStore(
                new GlobalSettingsProxy(context.getContentResolver()));
        mShellCommand = new FlagsShellCommand(mFlagStore);
    }

    @Override
    public void onStart() {
        Slog.d(TAG, "Started Feature Flag Service");
        FeatureFlagsBinder service = new FeatureFlagsBinder(
                mFlagStore, mShellCommand, new PermissionsChecker(getContext()));
        publishBinderService(
                Context.FEATURE_FLAGS_SERVICE, service);
        publishLocalService(FeatureFlags.class, new FeatureFlags(service));
    }

    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);

        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            // Immediately sync our core flags so that they get locked in. We don't want third-party
            // apps to override them, and syncing immediately is the easiest way to prevent that.
            FeatureFlags.getInstance().sync();
        }
    }

    /**
     * Delegate for checking flag permissions.
     */
    @VisibleForTesting
    public static class PermissionsChecker {
        private final Context mContext;

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public PermissionsChecker(Context context) {
            mContext = context;
        }

        /**
         * Ensures that the caller has {@link SYNC_FLAGS} permission.
         */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void assertSyncPermission() {
            if (mContext.checkCallingOrSelfPermission(SYNC_FLAGS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Non-core flag queried. Requires SYNC_FLAGS permission!");
            }
        }

        /**
         * Ensures that the caller has {@link WRITE_FLAGS} permission.
         */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void assertWritePermission() {
            if (mContext.checkCallingPermission(WRITE_FLAGS) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires WRITE_FLAGS permission!");
            }
        }
    }
}
