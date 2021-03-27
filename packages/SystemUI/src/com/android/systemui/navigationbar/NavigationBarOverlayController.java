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

package com.android.systemui.navigationbar;

import android.annotation.ColorInt;
import android.content.Context;
import android.view.View;

import com.android.systemui.dagger.SysUISingleton;

import java.util.function.Consumer;

import javax.inject.Inject;

/** Contains logic that deals with showing buttons with navigation bar. */
@SysUISingleton
public class NavigationBarOverlayController {

    protected final Context mContext;

    @Inject
    public NavigationBarOverlayController(Context context) {
        mContext = context;
    }

    public Context getContext() {
        return mContext;
    }

    public boolean isNavigationBarOverlayEnabled() {
        return false;
    }

    /**
     * Initialize the controller with visibility change callback and light/dark icon color.
     */
    public void init(Consumer<Boolean> visibilityChangeCallback, @ColorInt int lightIconColor,
            @ColorInt int darkIconColor) {}

    /**
     * Set whether the view can be shown.
     */
    public void setCanShow(boolean canShow) {}

    /**
     * Set the buttons visibility.
     */
    public void setButtonState(boolean visible, boolean force) {}

    /**
     * Register necessary listeners, called when NavigationBarView is attached to window.
     */
    public void registerListeners() {}

    /**
     * Unregister listeners, called when navigationBarView is detached from window.
     */
    public void unregisterListeners() {}

    /**
     * Set the dark intensity for all drawables.
     */
    public void setDarkIntensity(float darkIntensity) {}

    /**
     * Return the current view.
     */
    public View getCurrentView() {
        return null;
    }

    /**
     * Return the visibility of the view.
     */
    public boolean isVisible() {
        return false;
    }
}
