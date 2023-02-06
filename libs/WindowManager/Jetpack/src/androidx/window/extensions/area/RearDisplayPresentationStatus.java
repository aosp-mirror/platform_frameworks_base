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

package androidx.window.extensions.area;

import android.util.DisplayMetrics;

import androidx.annotation.NonNull;

/**
 * Class that provides information around the current status of a window area feature. Contains
 * the current {@link WindowAreaComponent.WindowAreaStatus} value corresponding to the
 * rear display presentation feature, as well as the {@link DisplayMetrics} for the rear facing
 * display.
 */
class RearDisplayPresentationStatus implements ExtensionWindowAreaStatus {

    @WindowAreaComponent.WindowAreaStatus
    private final int mWindowAreaStatus;

    @NonNull
    private final DisplayMetrics mDisplayMetrics;

    RearDisplayPresentationStatus(@WindowAreaComponent.WindowAreaStatus int status,
            @NonNull DisplayMetrics displayMetrics) {
        mWindowAreaStatus = status;
        mDisplayMetrics = displayMetrics;
    }

    /**
     * Returns the {@link androidx.window.extensions.area.WindowAreaComponent.WindowAreaStatus}
     * value that relates to the current status of a feature.
     */
    @Override
    @WindowAreaComponent.WindowAreaStatus
    public int getWindowAreaStatus() {
        return mWindowAreaStatus;
    }

    /**
     * Returns the {@link DisplayMetrics} that corresponds to the window area that a feature
     * interacts with. This is converted to size class information provided to developers.
     */
    @Override
    @NonNull
    public DisplayMetrics getWindowAreaDisplayMetrics() {
        return mDisplayMetrics;
    }
}
