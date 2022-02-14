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

package com.android.systemui.statusbar.notification;

import android.view.View;

import androidx.annotation.Nullable;

/**
 * Used to let views that have an alpha not apply the HARDWARE layer type directly, and instead
 * delegate that to specific children.  This is useful if we want to fake not having overlapping
 * rendering to avoid layer trashing, when fading out a view that is also changing.
 */
public interface NotificationFadeAware {
    /**
     * Calls {@link View#setLayerType} with {@link View#LAYER_TYPE_HARDWARE} if faded and
     * {@link View#LAYER_TYPE_NONE} otherwise.
     */
    static void setLayerTypeForFaded(@Nullable View view, boolean faded) {
        if (view != null) {
            int newLayerType = faded ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
            view.setLayerType(newLayerType, null);
        }
    }

    /**
     * Used like {@link View#setLayerType} with {@link View#LAYER_TYPE_HARDWARE} or
     * {@link View#LAYER_TYPE_NONE} except that instead of necessarily affecting this view
     * specifically, this may delegate the call to child views.
     *
     * When set to <code>true</code>, the view has two possible paths:
     *  1. If a hardware layer is required to ensure correct appearance of this view, then
     *    set that layer type.
     *  2. Otherwise, delegate this call to children, who might make that call for themselves.
     *
     * When set to <code>false</code>, the view should undo the above, typically by calling
     *  {@link View#setLayerType} with {@link View#LAYER_TYPE_NONE} on itself and children, and
     *  delegating to this method on children where implemented.
     *
     * When this delegates to {@link View#setLayerType} on this view or a subview, `null` will be
     * passed for the `paint` argument of that call.
     */
    void setNotificationFaded(boolean faded);

    /**
     * Interface for the top level notification view that fades and optimizes that through deep
     * awareness of individual components.
     */
    interface FadeOptimizedNotification extends NotificationFadeAware {
        /** Top-level feature switch */
        boolean FADE_LAYER_OPTIMIZATION_ENABLED = true;

        /** Determine if the notification is currently faded. */
        boolean isNotificationFaded();
    }
}
