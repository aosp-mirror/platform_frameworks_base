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

package com.android.systemui.dreams;

import android.view.View;

/**
 * A collection of interfaces related to hosting an overlay.
 */
public abstract class OverlayHost {
    /**
     * An interface for the callback from the overlay provider to indicate when the overlay is
     * ready.
     */
    public interface CreationCallback {
        /**
         * Called to inform the overlay view is ready to be placed within the visual space.
         * @param view The view representing the overlay.
         * @param layoutParams The parameters to create the view with.
         */
        void onCreated(View view, OverlayHostView.LayoutParams layoutParams);
    }

    /**
     * An interface for the callback from the overlay provider to signal interactions in the
     * overlay.
     */
    public interface InteractionCallback {
        /**
         * Called to signal the calling overlay would like to exit the dream.
         */
        void onExit();
    }
}
