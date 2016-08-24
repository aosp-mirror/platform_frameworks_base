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

package com.android.layoutlib.bridge.bars;

import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.resources.Density;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Navigation Bar for the Theme Editor preview.
 *
 * For small bars, it is identical to {@link NavigationBar}.
 * But wide bars from {@link NavigationBar} are too wide for the Theme Editor preview.
 * To solve that problem, {@link ThemePreviewNavigationBar} use the layout for small bars,
 * and have no padding on the sides. That way, they have a similar look as the true ones,
 * and they fit in the Theme Editor preview.
 */
public class ThemePreviewNavigationBar extends NavigationBar {
    private static final int PADDING_WIDTH_SW600 = 0;

    @SuppressWarnings("unused")
    public ThemePreviewNavigationBar(Context context, AttributeSet attrs) {
        super((BridgeContext) context,
                Density.getEnum(((BridgeContext) context).getMetrics().densityDpi),
                LinearLayout.HORIZONTAL, // In this mode, it doesn't need to be render vertically
                ((BridgeContext) context).getConfiguration().getLayoutDirection() ==
                        View.LAYOUT_DIRECTION_RTL,
                (context.getApplicationInfo().flags & ApplicationInfo.FLAG_SUPPORTS_RTL) != 0,
                0, LAYOUT_XML);
    }

    @Override
    protected int getSidePadding(float sw) {
        if (sw >= 600) {
            return PADDING_WIDTH_SW600;
        }
        return super.getSidePadding(sw);
    }
}
