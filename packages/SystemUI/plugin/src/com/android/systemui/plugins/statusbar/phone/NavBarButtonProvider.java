/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins.statusbar.phone;

import android.annotation.Nullable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.annotations.ProvidesInterface;

@ProvidesInterface(action = NavBarButtonProvider.ACTION, version = NavBarButtonProvider.VERSION)
public interface NavBarButtonProvider extends Plugin {

    public static final String ACTION = "com.android.systemui.action.PLUGIN_NAV_BUTTON";

    public static final int VERSION = 2;

    /**
     * Returns a view in the nav bar.  If the id is set "back", "home", "recent_apps", "menu",
     * or "ime_switcher", it is expected to implement ButtonInterface.
     */
    public View createView(String spec, ViewGroup parent);

    /**
     * Interface for button actions.
     */
    interface ButtonInterface {

        void setImageDrawable(@Nullable Drawable drawable);

        void abortCurrentGesture();

        void setVertical(boolean vertical);

        default void setCarMode(boolean carMode) {
        }

        void setDarkIntensity(float intensity);
    }
}
