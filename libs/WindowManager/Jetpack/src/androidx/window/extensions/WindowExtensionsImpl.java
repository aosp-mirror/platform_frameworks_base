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

import android.app.ActivityThread;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.window.extensions.embedding.ActivityEmbeddingComponent;
import androidx.window.extensions.embedding.SplitController;
import androidx.window.extensions.layout.WindowLayoutComponent;
import androidx.window.extensions.layout.WindowLayoutComponentImpl;

/**
 * The reference implementation of {@link WindowExtensions} that implements the initial API version.
 */
public class WindowExtensionsImpl implements WindowExtensions {

    private final Object mLock = new Object();
    private volatile WindowLayoutComponent mWindowLayoutComponent;
    private volatile SplitController mSplitController;

    @Override
    public int getVendorApiLevel() {
        return 1;
    }

    @Override
    public boolean isWindowLayoutComponentAvailable() {
        return true;
    }

    @Override
    public WindowLayoutComponent getWindowLayoutComponent() {
        if (mWindowLayoutComponent == null) {
            synchronized (mLock) {
                if (mWindowLayoutComponent == null) {
                    Context context = ActivityThread.currentApplication();
                    mWindowLayoutComponent = new WindowLayoutComponentImpl(context);
                }
            }
        }
        return mWindowLayoutComponent;
    }

    /**
     * Returns {@code true} if {@link ActivityEmbeddingComponent} is present on the device,
     * {@code false} otherwise. If the component is not available the developer will receive a
     * single callback with empty data or default values where possible.
     */
    @Override
    public boolean isEmbeddingComponentAvailable() {
        return true;
    }

    /**
     * Returns the OEM implementation of {@link ActivityEmbeddingComponent} if it is supported on
     * the device. The implementation must match the API level reported in
     * {@link androidx.window.extensions.WindowExtensions}. An
     * {@link UnsupportedOperationException} will be thrown if the device does not support
     * Activity Embedding. Use
     * {@link WindowExtensions#isEmbeddingComponentAvailable()} to determine if
     * {@link ActivityEmbeddingComponent} is present.
     * @return the OEM implementation of {@link ActivityEmbeddingComponent}
     */
    @NonNull
    public ActivityEmbeddingComponent getActivityEmbeddingComponent() {
        if (mSplitController == null) {
            synchronized (mLock) {
                if (mSplitController == null) {
                    mSplitController = new SplitController();
                }
            }
        }
        return mSplitController;
    }
}
