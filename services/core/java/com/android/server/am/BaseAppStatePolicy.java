/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManager.RESTRICTION_LEVEL_UNKNOWN;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.app.ActivityManager.RestrictionLevel;
import android.os.PowerExemptionManager.ReasonCode;
import android.provider.DeviceConfig;

import com.android.server.am.BaseAppStateTracker.Injector;

import java.io.PrintWriter;

/**
 * Base class to track the policy for certain state of the app.
 *
 * @param <T> A class derived from BaseAppStateTracker.
 */
public abstract class BaseAppStatePolicy<T extends BaseAppStateTracker> {

    protected final Injector<?> mInjector;
    protected final T mTracker;

    /**
     * The key to the device config, on whether or not we should enable the tracker.
     */
    protected final @NonNull String mKeyTrackerEnabled;

    /**
     * The default settings on whether or not we should enable the tracker.
     */
    protected final boolean mDefaultTrackerEnabled;

    /**
     * Whether or not we should enable the tracker.
     */
    volatile boolean mTrackerEnabled;

    BaseAppStatePolicy(@NonNull Injector<?> injector, @NonNull T tracker,
            @NonNull String keyTrackerEnabled, boolean defaultTrackerEnabled) {
        mInjector = injector;
        mTracker = tracker;
        mKeyTrackerEnabled = keyTrackerEnabled;
        mDefaultTrackerEnabled = defaultTrackerEnabled;
    }

    void updateTrackerEnabled() {
        final boolean enabled = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                mKeyTrackerEnabled, mDefaultTrackerEnabled);
        if (enabled != mTrackerEnabled) {
            mTrackerEnabled = enabled;
            onTrackerEnabled(enabled);
        }
    }

    /**
     * Called when the tracker enable flag flips.
     */
    public abstract void onTrackerEnabled(boolean enabled);

    /**
     * Called when a device config property in the activity manager namespace
     * has changed.
     */
    public void onPropertiesChanged(@NonNull String name) {
        if (mKeyTrackerEnabled.equals(name)) {
            updateTrackerEnabled();
        }
    }

    /**
     * @return The proposed background restriction policy for the given package/uid,
     *         the returned level should be capped at {@code maxLevel} (exclusive).
     */
    public @RestrictionLevel int getProposedRestrictionLevel(String packageName, int uid,
            @RestrictionLevel int maxLevel) {
        return RESTRICTION_LEVEL_UNKNOWN;
    }

    /**
     * Called when the system is ready to rock.
     */
    public void onSystemReady() {
        updateTrackerEnabled();
    }

    /**
     * @return If this tracker is enabled or not.
     */
    public boolean isEnabled() {
        return mTrackerEnabled;
    }

    /**
     * @return If the given UID should be exempted.
     *
     * <p>
     * Note: Call it with caution as it'll try to acquire locks in other services.
     * </p>
     */
    @CallSuper
    @ReasonCode
    public int shouldExemptUid(int uid) {
        return mTracker.mAppRestrictionController.getBackgroundRestrictionExemptionReason(uid);
    }

    /**
     * Dump to the given printer writer.
     */
    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print(mKeyTrackerEnabled);
        pw.print('=');
        pw.println(mTrackerEnabled);
    }
}
