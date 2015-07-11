/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.layoutlib.bridge.android.support;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.util.ReflectionUtils.ReflectionException;

import android.annotation.Nullable;
import android.view.View;

import static android.view.Gravity.END;
import static android.view.Gravity.LEFT;
import static android.view.Gravity.RIGHT;
import static android.view.Gravity.START;
import static com.android.layoutlib.bridge.util.ReflectionUtils.getCause;
import static com.android.layoutlib.bridge.util.ReflectionUtils.getMethod;
import static com.android.layoutlib.bridge.util.ReflectionUtils.invoke;

public class DrawerLayoutUtil {

    public static final String CN_DRAWER_LAYOUT = "android.support.v4.widget.DrawerLayout";

    public static void openDrawer(View drawerLayout, @Nullable String drawerGravity) {
        int gravity = -1;
        if ("left".equals(drawerGravity)) {
            gravity = LEFT;
        } else if ("right".equals(drawerGravity)) {
            gravity = RIGHT;
        } else if ("start".equals(drawerGravity)) {
            gravity = START;
        } else if ("end".equals(drawerGravity)) {
            gravity = END;
        }
        if (gravity > 0) {
            openDrawer(drawerLayout, gravity);
        }
    }

    private static void openDrawer(View drawerLayout, int gravity) {
        try {
            invoke(getMethod(drawerLayout.getClass(), "openDrawer", int.class), drawerLayout,
                    gravity);
        } catch (ReflectionException e) {
            Bridge.getLog().error(LayoutLog.TAG_BROKEN, "Unable to open navigation drawer",
                    getCause(e), null);
        }
    }
}
