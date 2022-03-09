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

package androidx.window.common;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Wrapper for both Extension and Sidecar versions of DisplayFeature. */
public interface DisplayFeature {
    /** Returns the type of the feature. */
    int getType();

    /** Returns the state of the feature, or {@code null} if the feature has no state. */
    @Nullable
    @State
    Integer getState();

    /** Returns the bounds of the feature. */
    @NonNull
    Rect getRect();

    /**
     * A common state to represent a FLAT hinge. This is needed because the definitions in Sidecar
     * and Extensions do not match exactly.
     */
    int COMMON_STATE_FLAT = 3;
    /**
     * A common state to represent a HALF_OPENED hinge. This is needed because the definitions in
     * Sidecar and Extensions do not match exactly.
     */
    int COMMON_STATE_HALF_OPENED = 2;

    /**
     * The possible states for a folding hinge.
     */
    @IntDef({COMMON_STATE_FLAT, COMMON_STATE_HALF_OPENED})
    @Retention(RetentionPolicy.SOURCE)
    @interface State {}

}
