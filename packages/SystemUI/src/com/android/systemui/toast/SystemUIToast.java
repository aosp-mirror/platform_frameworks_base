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

package com.android.systemui.toast;

import android.animation.Animator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.view.View;
import android.widget.ToastPresenter;

import com.android.internal.R;
import com.android.systemui.plugins.ToastPlugin;

/**
 * SystemUI TextToast that can be customized by ToastPlugins. Should never instantiate this class
 * directly. Instead, use {@link ToastFactory#createToast}.
 */
public class SystemUIToast implements ToastPlugin.Toast {
    final Context mContext;
    final CharSequence mText;
    final ToastPlugin.Toast mPluginToast;

    final int mDefaultGravity;
    final int mDefaultY;
    final int mDefaultX = 0;
    final int mDefaultHorizontalMargin = 0;
    final int mDefaultVerticalMargin = 0;

    SystemUIToast(Context context, CharSequence text) {
        this(context, text, null);
    }

    SystemUIToast(Context context, CharSequence text, ToastPlugin.Toast pluginToast) {
        mContext = context;
        mText = text;
        mPluginToast = pluginToast;

        mDefaultGravity = context.getResources().getInteger(R.integer.config_toastDefaultGravity);
        mDefaultY = context.getResources().getDimensionPixelSize(R.dimen.toast_y_offset);
    }

    @Override
    @NonNull
    public Integer getGravity() {
        if (isPluginToast() && mPluginToast.getGravity() != null) {
            return mPluginToast.getGravity();
        }
        return mDefaultGravity;
    }

    @Override
    @NonNull
    public Integer getXOffset() {
        if (isPluginToast() && mPluginToast.getXOffset() != null) {
            return mPluginToast.getXOffset();
        }
        return mDefaultX;
    }

    @Override
    @NonNull
    public Integer getYOffset() {
        if (isPluginToast() && mPluginToast.getYOffset() != null) {
            return mPluginToast.getYOffset();
        }
        return mDefaultY;
    }

    @Override
    @NonNull
    public Integer getHorizontalMargin() {
        if (isPluginToast() && mPluginToast.getHorizontalMargin() != null) {
            return mPluginToast.getHorizontalMargin();
        }
        return mDefaultHorizontalMargin;
    }

    @Override
    @NonNull
    public Integer getVerticalMargin() {
        if (isPluginToast() && mPluginToast.getVerticalMargin() != null) {
            return mPluginToast.getVerticalMargin();
        }
        return mDefaultVerticalMargin;
    }

    @Override
    @NonNull
    public View getView() {
        if (isPluginToast() && mPluginToast.getView() != null) {
            return mPluginToast.getView();
        }
        return ToastPresenter.getTextToastView(mContext, mText);
    }

    @Override
    @Nullable
    public Animator getInAnimation() {
        if (isPluginToast() && mPluginToast.getInAnimation() != null) {
            return mPluginToast.getInAnimation();
        }
        return null;
    }

    @Override
    @Nullable
    public Animator getOutAnimation() {
        if (isPluginToast() && mPluginToast.getOutAnimation() != null) {
            return mPluginToast.getOutAnimation();
        }
        return null;
    }

    /**
     * Whether this toast has a custom animation.
     */
    public boolean hasCustomAnimation() {
        return getInAnimation() != null || getOutAnimation() != null;
    }

    private boolean isPluginToast() {
        return mPluginToast != null;
    }
}
