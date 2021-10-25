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

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.app.ActivityManager.RestrictionLevel;
import android.os.UserHandle;

import com.android.server.am.BaseAppStateTracker.Injector;

/**
 * Base class to track the policy for certain state of the app.
 *
 * @param <T> A class derived from BaseAppStateTracker.
 */
public abstract class BaseAppStatePolicy<T extends BaseAppStateTracker> {

    protected final Injector<?> mInjector;
    protected final T mTracker;

    BaseAppStatePolicy(@NonNull Injector<?> injector, @NonNull T tracker) {
        mInjector = injector;
        mTracker = tracker;
    }

    /**
     * Called when a device config property in the activity manager namespace
     * has changed.
     */
    public abstract void onPropertiesChanged(@NonNull String name);

    /**
     * @return The proposed background restriction policy for the givenp package/uid.
     */
    public abstract @RestrictionLevel int getProposedRestrictionLevel(String packageName, int uid);

    /**
     * Called when the system is ready to rock.
     */
    public abstract void onSystemReady();

    /**
     * @return If this policy is enabled or not.
     */
    public abstract boolean isEnabled();

    /**
     * @return If the given UID should be exempted.
     *
     * <p>
     * Note: Call it with caution as it'll try to acquire locks in other services.
     * </p>
     */
    @CallSuper
    public boolean shouldExemptUid(int uid) {
        if (UserHandle.isCore(uid)) {
            return true;
        }
        if (mInjector.getDeviceIdleInternal().isAppOnWhitelist(UserHandle.getAppId(uid))) {
            return true;
        }
        return false;
    }
}
