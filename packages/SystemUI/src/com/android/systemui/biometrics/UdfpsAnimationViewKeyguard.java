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

package com.android.systemui.biometrics;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.plugins.statusbar.StatusBarStateController;

/**
 * Class that coordinates non-HBM animations during keyguard authentication.
 */
public class UdfpsAnimationViewKeyguard extends UdfpsAnimationView {
    @Nullable private UdfpsAnimationKeyguard mAnimation;

    public UdfpsAnimationViewKeyguard(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    void setStatusBarStateController(@NonNull StatusBarStateController statusBarStateController) {
        if (mAnimation == null) {
            mAnimation = new UdfpsAnimationKeyguard(getContext(), statusBarStateController);
            mAnimation.setAnimationView(this);
        }
    }

    @Nullable
    @Override
    protected UdfpsAnimation getUdfpsAnimation() {
        return mAnimation;
    }
}
