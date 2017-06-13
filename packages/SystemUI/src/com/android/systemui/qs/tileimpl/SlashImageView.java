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

package com.android.systemui.qs.tileimpl;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.plugins.qs.QSTile.SlashState;
import com.android.systemui.qs.SlashDrawable;

public class SlashImageView extends ImageView {

    @VisibleForTesting
    protected SlashDrawable mSlash;

    public SlashImageView(Context context) {
        super(context);
    }

    private void ensureSlashDrawable() {
        if (mSlash == null) {
            mSlash = new SlashDrawable(getDrawable());
            super.setImageDrawable(mSlash);
        }
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        if (drawable == null) {
            mSlash = null;
            super.setImageDrawable(null);
        } else if (mSlash == null) {
            super.setImageDrawable(drawable);
        } else {
            mSlash.setDrawable(drawable);
        }
    }

    public void setState(SlashState slashState) {
        ensureSlashDrawable();
        mSlash.setRotation(slashState.rotation);
        mSlash.setSlashed(slashState.isSlashed);
    }
}
