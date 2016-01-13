/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.view.View;

import com.android.systemui.statusbar.phone.PhoneStatusBar;

/**
 * A helper to fade views in and out.
 */
public class CrossFadeHelper {
    public static final long ANIMATION_DURATION_LENGTH = 210;

    public static void fadeOut(final View view, final Runnable endRunnable) {
        view.animate().cancel();
        view.animate()
                .alpha(0f)
                .setDuration(ANIMATION_DURATION_LENGTH)
                .setInterpolator(PhoneStatusBar.ALPHA_OUT)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (endRunnable != null) {
                            endRunnable.run();
                        }
                        view.setVisibility(View.INVISIBLE);
                    }
                });
        if (view.hasOverlappingRendering()) {
            view.animate().withLayer();
        }

    }

    public static void fadeIn(final View view) {
        view.animate().cancel();
        if (view.getVisibility() == View.INVISIBLE) {
            view.setAlpha(0.0f);
            view.setVisibility(View.VISIBLE);
        }
        view.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_LENGTH)
                .setInterpolator(PhoneStatusBar.ALPHA_IN)
                .withEndAction(null);
        if (view.hasOverlappingRendering()) {
            view.animate().withLayer();
        }
    }
}
