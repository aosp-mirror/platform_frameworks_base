/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.pip.phone;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.wm.shell.R;

/**
 * Container layout wraps single action image view drawn in PiP menu and can restrict the size of
 * action image view (see pip_menu_action.xml).
 */
public class PipMenuActionView extends FrameLayout {
    private ImageView mImageView;

    public PipMenuActionView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mImageView = findViewById(R.id.image);
    }

    /** pass through to internal {@link #mImageView} */
    public void setImageDrawable(Drawable drawable) {
        mImageView.setImageDrawable(drawable);
    }
}
