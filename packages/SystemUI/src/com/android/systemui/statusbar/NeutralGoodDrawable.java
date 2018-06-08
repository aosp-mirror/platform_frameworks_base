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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.Resources.Theme;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import com.android.settingslib.Utils;
import com.android.systemui.R;

/**
 * NeutralGoodDrawable implements a drawable that will load 2 underlying drawable resources, one
 * with each the DualToneDarkTheme and DualToneLightTheme, choosing which one based on what
 * DarkIconDispatcher tells us about darkness
 */
public class NeutralGoodDrawable extends LayerDrawable {

    public static NeutralGoodDrawable create(Context context, int resId) {
        int dualToneLightTheme = Utils.getThemeAttr(context, R.attr.lightIconTheme);
        int dualToneDarkTheme = Utils.getThemeAttr(context, R.attr.darkIconTheme);
        ContextThemeWrapper light = new ContextThemeWrapper(context, dualToneLightTheme);
        ContextThemeWrapper dark = new ContextThemeWrapper(context, dualToneDarkTheme);

        return create(light, dark, resId);
    }

    /**
     * For the on-the-go young entrepreneurial who wants to cache contexts
     * @param light - a context using the R.attr.lightIconTheme
     * @param dark - a context using the R.attr.darkIconTheme
     * @param resId - the resId for our drawable
     */
    public static NeutralGoodDrawable create(Context light, Context dark, int resId) {
        return new NeutralGoodDrawable(
                new Drawable[] {
                        light.getDrawable(resId).mutate(),
                        dark.getDrawable(resId).mutate() });
    }

    protected NeutralGoodDrawable(Drawable []drawables) {
        super(drawables);

        for (int i = 0; i < drawables.length; i++) {
            setLayerGravity(i, Gravity.CENTER);
        }

        mutate();
        setDarkIntensity(0);
    }

    public void setDarkIntensity(float intensity) {

        getDrawable(0).setAlpha((int) ((1 - intensity) * 255f));
        getDrawable(1).setAlpha((int) (intensity * 255f));

        invalidateSelf();
    }
}
