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

package android.content.res;

import android.annotation.ColorRes;
import android.annotation.DrawableRes;
import android.content.Context;
import android.graphics.drawable.Drawable;

import java.lang.ref.WeakReference;

/**
 * Version of resources generated for apps targeting <26.
 * @hide
 */
public class CompatResources extends Resources {

    private WeakReference<Context> mContext;

    public CompatResources(ClassLoader cls) {
        super(cls);
        mContext = new WeakReference<>(null);
    }

    /**
     * @hide
     */
    public void setContext(Context context) {
        mContext = new WeakReference<>(context);
    }

    @Override
    public Drawable getDrawable(@DrawableRes int id) throws NotFoundException {
        return getDrawable(id, getTheme());
    }

    @Override
    public Drawable getDrawableForDensity(@DrawableRes int id, int density)
            throws NotFoundException {
        return getDrawableForDensity(id, density, getTheme());
    }

    @Override
    public int getColor(@ColorRes int id) throws NotFoundException {
        return getColor(id, getTheme());
    }

    @Override
    public ColorStateList getColorStateList(@ColorRes int id) throws NotFoundException {
        return getColorStateList(id, getTheme());
    }

    private Theme getTheme() {
        Context c = mContext.get();
        return c != null ? c.getTheme() : null;
    }
}
