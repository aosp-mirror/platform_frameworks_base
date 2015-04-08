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
package android.databinding.testapp.vo;

import android.databinding.Bindable;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

public class ImageViewBindingObject extends BindingAdapterBindingObject {
    @Bindable
    private int mTint;

    @Bindable
    private Drawable mSrc;

    @Bindable
    private PorterDuff.Mode mTintMode = PorterDuff.Mode.DARKEN;

    public int getTint() {
        return mTint;
    }

    public Drawable getSrc() {
        return mSrc;
    }

    public PorterDuff.Mode getTintMode() {
        return mTintMode;
    }

    public void changeValues() {
        mTint = 0xFF111111;
        mSrc = new ColorDrawable(0xFF00FF00);
        mTintMode = PorterDuff.Mode.LIGHTEN;
        notifyChange();
    }
}
