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

package com.android.egg.neko;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Icon;

import com.android.egg.R;

public class Food {
    private final int mType;

    private static int[] sIcons;
    private static String[] sNames;

    public Food(int type) {
        mType = type;
    }

    public Icon getIcon(Context context) {
        if (sIcons == null) {
            TypedArray icons = context.getResources().obtainTypedArray(R.array.food_icons);
            sIcons = new int[icons.length()];
            for (int i = 0; i < sIcons.length; i++) {
                sIcons[i] = icons.getResourceId(i, 0);
            }
            icons.recycle();
        }
        return Icon.createWithResource(context, sIcons[mType]);
    }

    public String getName(Context context) {
        if (sNames == null) {
            sNames = context.getResources().getStringArray(R.array.food_names);
        }
        return sNames[mType];
    }

    public long getInterval(Context context) {
        return context.getResources().getIntArray(R.array.food_intervals)[mType];
    }

    public int getType() {
        return mType;
    }
}
