/**
 * Copyright (C) 2018 The LineageOS project
 * Copyright (C) 2019 The LineageOS project
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

package com.android.systemui.statusbar;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;

public class CustomStatusBarItem {

    public interface Manager {
        public void addDarkReceiver(DarkReceiver darkReceiver);
        public void addVisibilityReceiver(VisibilityReceiver visibilityReceiver);
    }

    public interface DarkReceiver {
        public void onDarkChanged(Rect area, float darkIntensity, int tint);
        public void setFillColors(int darkColor, int lightColor);
    }

    public interface VisibilityReceiver {
        public void onVisibilityChanged(boolean isVisible);
    }

    // Locate parent CustomStatusBarItem.Manager
    public static Manager findManager(View v) {
        ViewParent parent = v.getParent();
        if (parent == null) {
            return null;
        } else if (parent instanceof Manager) {
            return (Manager) parent;
        } else if (parent instanceof View) {
            return findManager((View) parent);
        } else {
            return null;
        }
    }
}
