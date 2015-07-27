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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.View;

import static com.android.layoutlib.bridge.util.ReflectionUtils.getMethod;
import static com.android.layoutlib.bridge.util.ReflectionUtils.invoke;

/**
 * Utility class for working with the design support lib.
 */
public class DesignLibUtil {

    private static final String PKG_PREFIX = "android.support.design.widget.";
    public static final String CN_COORDINATOR_LAYOUT = PKG_PREFIX + "CoordinatorLayout";
    public static final String CN_APPBAR_LAYOUT = PKG_PREFIX + "AppBarLayout";
    public static final String CN_COLLAPSING_TOOLBAR_LAYOUT =
            PKG_PREFIX + "CollapsingToolbarLayout";
    public static final String CN_TOOLBAR = "android.support.v7.widget.Toolbar";
    public static final int SCROLL_AXIS_VERTICAL = 1 << 1;

    /**
     * Tries to set the title of a view. This is used to set the title in a
     * CollapsingToolbarLayout.
     * <p/>
     * Any exceptions thrown during the process are logged in {@link Bridge#getLog()}
     */
    public static void setTitle(@NonNull View view, @Nullable String title) {
        if (title == null) {
            return;
        }
        try {
            invoke(getMethod(view.getClass(), "setTitle", CharSequence.class), view, title);
        } catch (ReflectionException e) {
            Bridge.getLog().warning(LayoutLog.TAG_INFO,
                    "Error occurred while trying to set title.", e);
        }
    }
}
