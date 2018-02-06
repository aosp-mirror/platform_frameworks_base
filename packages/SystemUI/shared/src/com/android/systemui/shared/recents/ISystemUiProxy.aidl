/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.shared.recents;

import android.graphics.Rect;
import com.android.systemui.shared.system.GraphicBufferCompat;

/**
 * Temporary callbacks into SystemUI.
 */
interface ISystemUiProxy {

    /**
     * Proxies SurfaceControl.screenshotToBuffer().
     */
    GraphicBufferCompat screenshot(in Rect sourceCrop, int width, int height, int minLayer,
            int maxLayer, boolean useIdentityTransform, int rotation);

    /**
     * Begins screen pinning on the provided {@param taskId}.
     */
    void startScreenPinning(int taskId);

    /**
     * Called when the overview service has started the recents animation.
     */
    void onRecentsAnimationStarted();

    /**
     * Specifies the text to be shown for onboarding the new swipe-up gesture to access recents.
     */
    void setRecentsOnboardingText(CharSequence text);
}
