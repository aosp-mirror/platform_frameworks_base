/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.view;

import android.view.animation.Interpolator;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Used by {@link InsetsAnimationControlImpl}
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public interface InsetsAnimationSpec {
    /**
     * @param hasZeroInsetsIme whether IME has no insets (floating, fullscreen or non-overlapping).
     * @return Duration of animation in {@link java.util.concurrent.TimeUnit#MILLISECONDS}
     */
    long getDurationMs(boolean hasZeroInsetsIme);
    /**
     * @param hasZeroInsetsIme whether IME has no insets (floating, fullscreen or non-overlapping).
     * @return The interpolator used for the animation
     */
    Interpolator getInsetsInterpolator(boolean hasZeroInsetsIme);
}
