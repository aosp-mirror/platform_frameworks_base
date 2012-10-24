/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.util.AttributeSet;
import android.widget.CompoundButton;

import com.android.systemui.statusbar.policy.AutoRotateController;

public class RotationToggle extends CompoundButton
        implements AutoRotateController.RotationLockCallbacks {
    private AutoRotateController mRotater;

    public RotationToggle(Context context) {
        super(context);
    }

    public RotationToggle(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RotationToggle(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mRotater = new AutoRotateController(getContext(), this, this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mRotater != null) {
            mRotater.release();
            mRotater = null;
        }
    }

    @Override
    public void setRotationLockControlVisibility(boolean show) {
        setVisibility(show ? VISIBLE : GONE);
    }
}
