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

import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.window.extensions.area.WindowAreaComponent;
import androidx.window.extensions.embedding.ActivityEmbeddingComponent;
import androidx.window.extensions.layout.WindowLayoutComponent;

/**
 * Provides the OEM implementation of {@link WindowExtensions}.
 */
public class WindowExtensionsProvider {

    private static volatile WindowExtensions sWindowExtensions;

    /**
     * Returns the OEM implementation of {@link WindowExtensions}. This method is implemented in
     * the library provided on the device and overwrites one in the Jetpack library included in
     * apps.
     * @return the OEM implementation of {@link WindowExtensions}
     */
    @NonNull
    public static WindowExtensions getWindowExtensions() {
        if (sWindowExtensions == null) {
            synchronized (WindowExtensionsProvider.class) {
                if (sWindowExtensions == null) {
                    sWindowExtensions = WindowManager.hasWindowExtensionsEnabled()
                            ? new WindowExtensionsImpl()
                            : new DisabledWindowExtensions();
                }
            }
        }
        return sWindowExtensions;
    }

    /**
     * The stub version to return when the WindowManager Extensions is disabled
     * @see WindowManager#hasWindowExtensionsEnabled
     */
    private static class DisabledWindowExtensions implements WindowExtensions {
        @Override
        public int getVendorApiLevel() {
            return 0;
        }

        @Nullable
        @Override
        public WindowLayoutComponent getWindowLayoutComponent() {
            return null;
        }

        @Nullable
        @Override
        public ActivityEmbeddingComponent getActivityEmbeddingComponent() {
            return null;
        }

        @Nullable
        @Override
        public WindowAreaComponent getWindowAreaComponent() {
            return null;
        }
    }
}
