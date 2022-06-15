/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.inputmethodservice;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.content.res.Resources;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

/**
 * Helper class that takes care of Configuration change behavior of {@link InputMethodService}.
 * Note: this class is public for testing only. Never call any of it's methods for development
 * of IMEs.
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class ImsConfigurationTracker {

    /**
     * A constant value that represents {@link Configuration} has changed from the last time
     * {@link InputMethodService#onConfigurationChanged(Configuration)} was called.
     */
    private static final int CONFIG_CHANGED = -1;

    @Nullable
    private Configuration mLastKnownConfig = null;
    private int mHandledConfigChanges = 0;
    private boolean mInitialized = false;

    /**
     * Called from {@link InputMethodService.InputMethodImpl
     * #initializeInternal(IBinder, int, IInputMethodPrivilegedOperations, int)} ()}
     * @param handledConfigChanges Configuration changes declared handled by IME
     * {@link android.R.styleable#InputMethod_configChanges}.
     */
    @MainThread
    public void onInitialize(int handledConfigChanges) {
        Preconditions.checkState(!mInitialized, "onInitialize can be called only once.");
        mInitialized = true;
        mHandledConfigChanges = handledConfigChanges;
    }

    /**
     * Called from {@link InputMethodService.InputMethodImpl#onBindInput()}
     */
    @MainThread
    public void onBindInput(@Nullable Resources resources) {
        Preconditions.checkState(mInitialized,
                "onBindInput can be called only after onInitialize().");
        if (mLastKnownConfig == null && resources != null) {
            mLastKnownConfig = new Configuration(resources.getConfiguration());
        }
    }

    /**
     * Dynamically set handled configChanges.
     * Note: this method is public for testing only.
     */
    public void setHandledConfigChanges(int configChanges) {
        mHandledConfigChanges = configChanges;
    }

    /**
     * Called from {@link InputMethodService.InputMethodImpl#onConfigurationChanged(Configuration)}}
     */
    @MainThread
    public void onConfigurationChanged(@NonNull Configuration newConfig,
            @NonNull Runnable resetStateForNewConfigurationRunner) {
        if (!mInitialized) {
            return;
        }
        final int diff = mLastKnownConfig != null
                ? mLastKnownConfig.diffPublicOnly(newConfig) : CONFIG_CHANGED;
        // If the new config is the same as the config this Service is already running with,
        // then don't bother calling resetStateForNewConfiguration.
        final int unhandledDiff = (diff & ~mHandledConfigChanges);
        if (unhandledDiff != 0) {
            resetStateForNewConfigurationRunner.run();
        }
        if (diff != 0) {
            mLastKnownConfig = new Configuration(newConfig);
        }
    }
}
