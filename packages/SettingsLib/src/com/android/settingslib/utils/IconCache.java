/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import androidx.annotation.VisibleForTesting;
import androidx.collection.ArrayMap;


/**
 * Icon cache to avoid multiple loads on the same icon.
 */
public class IconCache {
    private final Context mContext;
    @VisibleForTesting
    final ArrayMap<Icon, Drawable> mMap = new ArrayMap<>();

    public IconCache(Context context) {
        mContext = context;
    }

    public Drawable getIcon(Icon icon) {
        if (icon == null) {
            return null;
        }
        Drawable drawable = mMap.get(icon);
        if (drawable == null) {
            drawable = icon.loadDrawable(mContext);
            updateIcon(icon, drawable);
        }
        return drawable;
    }

    public void updateIcon(Icon icon, Drawable drawable) {
        mMap.put(icon, drawable);
    }
}
