/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.tuner;

import android.content.Context;
import android.graphics.drawable.Drawable;
import androidx.preference.CheckBoxPreference;
import android.util.TypedValue;

import com.android.systemui.statusbar.ScalingDrawableWrapper;

public class SelectablePreference extends CheckBoxPreference {
    private final int mSize;

    public SelectablePreference(Context context) {
        super(context);
        setWidgetLayoutResource(com.android.systemui.R.layout.preference_widget_radiobutton);
        setSelectable(true);
        mSize = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32,
                context.getResources().getDisplayMetrics());
    }

    @Override
    public void setIcon(Drawable icon) {
        super.setIcon(new ScalingDrawableWrapper(icon,
                mSize / (float) icon.getIntrinsicWidth()));
    }

    @Override
    public String toString() {
        return "";
    }
}
