/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.userswitcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.IdRes;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;

/**
 * A view that forms the header of the notification panel. This view will ensure that any
 * status icons that are displayed are tinted accordingly to the current theme.
 */
public class CarStatusBarHeader extends LinearLayout {
    public CarStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        // Set the light/dark theming on the header status UI to match the current theme.
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = colorForeground == Color.WHITE ? 0f : 1f;
        Rect tintArea = new Rect(0, 0, 0, 0);

        applyDarkness(R.id.clock, tintArea, intensity, colorForeground);
    }

    private void applyDarkness(@IdRes int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkIconDispatcher.DarkReceiver) {
            ((DarkIconDispatcher.DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
    }
}
