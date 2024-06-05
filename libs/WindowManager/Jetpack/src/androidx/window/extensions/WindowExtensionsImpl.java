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

package androidx.window.extensions;

import static android.view.WindowManager.ACTIVITY_EMBEDDING_GUARD_WITH_ANDROID_15;
import static android.view.WindowManager.ENABLE_ACTIVITY_EMBEDDING_FOR_ANDROID_15;

import android.app.ActivityThread;
import android.app.Application;
import android.app.compat.CompatChanges;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;
import androidx.window.common.RawFoldingFeatureProducer;
import androidx.window.extensions.area.WindowAreaComponent;
import androidx.window.extensions.area.WindowAreaComponentImpl;
import androidx.window.extensions.embedding.ActivityEmbeddingComponent;
import androidx.window.extensions.embedding.SplitController;
import androidx.window.extensions.layout.WindowLayoutComponent;
import androidx.window.extensions.layout.WindowLayoutComponentImpl;

import java.util.Objects;


/**
 * The reference implementation of {@link WindowExtensions} that implements the latest WindowManager
 * Extensions APIs.
 */
class WindowExtensionsImpl implements WindowExtensions {

    private static final String TAG = "WindowExtensionsImpl";

    /**
     * The min version of the WM Extensions that must be supported in the current platform version.
     */
    @VisibleForTesting
    static final int EXTENSIONS_VERSION_CURRENT_PLATFORM = 6;

    private final Object mLock = new Object();
    private volatile DeviceStateManagerFoldingFeatureProducer mFoldingFeatureProducer;
    private volatile WindowLayoutComponentImpl mWindowLayoutComponent;
    private volatile SplitController mSplitController;
    private volatile WindowAreaComponent mWindowAreaComponent;

    private final int mVersion = EXTENSIONS_VERSION_CURRENT_PLATFORM;
    private final boolean mIsActivityEmbeddingEnabled;

    WindowExtensionsImpl() {
        mIsActivityEmbeddingEnabled = isActivityEmbeddingEnabled();
        Log.i(TAG, "Initializing Window Extensions, vendor API level=" + mVersion
                + ", activity embedding enabled=" + mIsActivityEmbeddingEnabled);
    }

    // TODO(b/241126279) Introduce constants to better version functionality
    @Override
    public int getVendorApiLevel() {
        return mVersion;
    }

    @NonNull
    private Application getApplication() {
        return Objects.requireNonNull(ActivityThread.currentApplication());
    }

    @NonNull
    private DeviceStateManager getDeviceStateManager() {
        return Objects.requireNonNull(getApplication().getSystemService(DeviceStateManager.class));
    }

    @NonNull
    private DeviceStateManagerFoldingFeatureProducer getFoldingFeatureProducer() {
        if (mFoldingFeatureProducer == null) {
            synchronized (mLock) {
                if (mFoldingFeatureProducer == null) {
                    final Context context = getApplication();
                    final RawFoldingFeatureProducer foldingFeatureProducer =
                            new RawFoldingFeatureProducer(context);
                    mFoldingFeatureProducer =
                            new DeviceStateManagerFoldingFeatureProducer(context,
                                    foldingFeatureProducer, getDeviceStateManager());
                }
            }
        }
        return mFoldingFeatureProducer;
    }

    @NonNull
    private WindowLayoutComponentImpl getWindowLayoutComponentImpl() {
        if (mWindowLayoutComponent == null) {
            synchronized (mLock) {
                if (mWindowLayoutComponent == null) {
                    final Context context = getApplication();
                    final DeviceStateManagerFoldingFeatureProducer producer =
                            getFoldingFeatureProducer();
                    mWindowLayoutComponent = new WindowLayoutComponentImpl(context, producer);
                }
            }
        }
        return mWindowLayoutComponent;
    }

    /**
     * Returns a reference implementation of the latest {@link WindowLayoutComponent}.
     *
     * The implementation must match the API level reported in
     * {@link WindowExtensions#getVendorApiLevel()}.
     *
     * @return {@link WindowLayoutComponent} OEM implementation
     */
    @NonNull
    @Override
    public WindowLayoutComponent getWindowLayoutComponent() {
        return getWindowLayoutComponentImpl();
    }

    /**
     * Returns a reference implementation of the latest {@link ActivityEmbeddingComponent} if the
     * device supports this feature, {@code null} otherwise.
     *
     * The implementation must match the API level reported in
     * {@link WindowExtensions#getVendorApiLevel()}.
     *
     * @return {@link ActivityEmbeddingComponent} OEM implementation.
     */
    @Nullable
    @Override
    public ActivityEmbeddingComponent getActivityEmbeddingComponent() {
        if (!mIsActivityEmbeddingEnabled) {
            return null;
        }
        if (mSplitController == null) {
            synchronized (mLock) {
                if (mSplitController == null) {
                    mSplitController = new SplitController(
                            getWindowLayoutComponentImpl(),
                            getFoldingFeatureProducer()
                    );
                }
            }
        }
        return mSplitController;
    }

    /**
     * Returns a reference implementation of the latest {@link WindowAreaComponent}
     *
     * The implementation must match the API level reported in
     * {@link WindowExtensions#getVendorApiLevel()}.
     *
     * @return {@link WindowAreaComponent} OEM implementation.
     */
    @Nullable
    @Override
    public WindowAreaComponent getWindowAreaComponent() {
        if (mWindowAreaComponent == null) {
            synchronized (mLock) {
                if (mWindowAreaComponent == null) {
                    final Context context = getApplication();
                    mWindowAreaComponent = new WindowAreaComponentImpl(context);
                }
            }
        }
        return mWindowAreaComponent;
    }

    @VisibleForTesting
    static boolean isActivityEmbeddingEnabled() {
        if (!ACTIVITY_EMBEDDING_GUARD_WITH_ANDROID_15) {
            // Device enables it for all apps without targetSDK check.
            // This must be true for all large screen devices.
            return true;
        }
        // Use compat framework to guard the feature with targetSDK 15.
        return CompatChanges.isChangeEnabled(ENABLE_ACTIVITY_EMBEDDING_FOR_ANDROID_15);
    }
}
