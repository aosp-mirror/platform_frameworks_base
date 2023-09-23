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

import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
 * The reference implementation of {@link WindowExtensions} that implements the initial API version.
 */
public class WindowExtensionsImpl implements WindowExtensions {

    private final Object mLock = new Object();
    private volatile DeviceStateManagerFoldingFeatureProducer mFoldingFeatureProducer;
    private volatile WindowLayoutComponentImpl mWindowLayoutComponent;
    private volatile SplitController mSplitController;
    private volatile WindowAreaComponent mWindowAreaComponent;

    // TODO(b/241126279) Introduce constants to better version functionality
    @Override
    public int getVendorApiLevel() {
        return 4;
    }

    @NonNull
    private Application getApplication() {
        return Objects.requireNonNull(ActivityThread.currentApplication());
    }

    @NonNull
    private DeviceStateManagerFoldingFeatureProducer getFoldingFeatureProducer() {
        if (mFoldingFeatureProducer == null) {
            synchronized (mLock) {
                if (mFoldingFeatureProducer == null) {
                    Context context = getApplication();
                    RawFoldingFeatureProducer foldingFeatureProducer =
                            new RawFoldingFeatureProducer(context);
                    mFoldingFeatureProducer =
                            new DeviceStateManagerFoldingFeatureProducer(context,
                                    foldingFeatureProducer);
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
                    Context context = getApplication();
                    DeviceStateManagerFoldingFeatureProducer producer =
                            getFoldingFeatureProducer();
                    mWindowLayoutComponent = new WindowLayoutComponentImpl(context, producer);
                }
            }
        }
        return mWindowLayoutComponent;
    }

    /**
     * Returns a reference implementation of {@link WindowLayoutComponent} if available,
     * {@code null} otherwise. The implementation must match the API level reported in
     * {@link WindowExtensions#getWindowLayoutComponent()}.
     * @return {@link WindowLayoutComponent} OEM implementation
     */
    @Override
    public WindowLayoutComponent getWindowLayoutComponent() {
        return getWindowLayoutComponentImpl();
    }

    /**
     * Returns a reference implementation of {@link ActivityEmbeddingComponent} if available,
     * {@code null} otherwise. The implementation must match the API level reported in
     * {@link WindowExtensions#getWindowLayoutComponent()}.
     * @return {@link ActivityEmbeddingComponent} OEM implementation.
     */
    @Nullable
    public ActivityEmbeddingComponent getActivityEmbeddingComponent() {
        if (mSplitController == null) {
            if (!ActivityTaskManager.supportsMultiWindow(getApplication())) {
                // Disable AE for device that doesn't support multi window.
                return null;
            }
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
     * Returns a reference implementation of {@link WindowAreaComponent} if available,
     * {@code null} otherwise. The implementation must match the API level reported in
     * {@link WindowExtensions#getWindowAreaComponent()}.
     * @return {@link WindowAreaComponent} OEM implementation.
     */
    public WindowAreaComponent getWindowAreaComponent() {
        if (mWindowAreaComponent == null) {
            synchronized (mLock) {
                if (mWindowAreaComponent == null) {
                    Context context = ActivityThread.currentApplication();
                    mWindowAreaComponent =
                            new WindowAreaComponentImpl(context);
                }
            }
        }
        return mWindowAreaComponent;
    }
}
