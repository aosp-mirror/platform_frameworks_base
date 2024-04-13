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

package com.android.systemui.ambient.touch.dagger;

import android.content.res.Resources;

import com.android.systemui.ambient.touch.ShadeTouchHandler;
import com.android.systemui.ambient.touch.TouchHandler;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;

import javax.inject.Named;

/**
 * Dependencies for swipe down to notification over dream.
 */
@Module
public abstract class ShadeModule {
    /**
     * The height, defined in pixels, of the gesture initiation region at the top of the screen for
     * swiping down notifications.
     */
    public static final String NOTIFICATION_SHADE_GESTURE_INITIATION_HEIGHT =
            "notification_shade_gesture_initiation_height";

    /**
     * Provides {@link ShadeTouchHandler} to handle notification swipe down over dream.
     */
    @Binds
    @IntoSet
    public abstract TouchHandler providesNotificationShadeTouchHandler(
            ShadeTouchHandler touchHandler);

    /**
     * Provides the height of the gesture area for notification swipe down.
     */
    @Provides
    @Named(NOTIFICATION_SHADE_GESTURE_INITIATION_HEIGHT)
    public static int providesNotificationShadeGestureRegionHeight(@Main Resources resources) {
        return resources.getDimensionPixelSize(R.dimen.dream_overlay_status_bar_height);
    }

}
