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

package com.android.wm.shell.sizecompatui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;

/** Button to restart the size compat activity. */
public class SizeCompatRestartButton extends FrameLayout implements View.OnClickListener,
        View.OnLongClickListener {

    private SizeCompatUILayout mLayout;
    private ImageButton mRestartButton;
    @VisibleForTesting
    PopupWindow mShowingHint;
    private WindowManager.LayoutParams mWinParams;

    public SizeCompatRestartButton(@NonNull Context context) {
        super(context);
    }

    public SizeCompatRestartButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public SizeCompatRestartButton(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SizeCompatRestartButton(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    void inject(SizeCompatUILayout layout) {
        mLayout = layout;
        mWinParams = layout.getWindowLayoutParams();
    }

    void remove() {
        dismissHint();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mRestartButton = findViewById(R.id.size_compat_restart_button);
        final ColorStateList color = ColorStateList.valueOf(Color.LTGRAY);
        final GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(color);
        mRestartButton.setBackground(new RippleDrawable(color, null /* content */, mask));
        mRestartButton.setOnClickListener(this);
        mRestartButton.setOnLongClickListener(this);
    }

    @Override
    public void onClick(View v) {
        mLayout.onRestartButtonClicked();
    }

    @Override
    public boolean onLongClick(View v) {
        showHint();
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mLayout.mShouldShowHint) {
            mLayout.mShouldShowHint = false;
            showHint();
        }
    }

    @Override
    public void setVisibility(@Visibility int visibility) {
        if (visibility == View.GONE && mShowingHint != null) {
            // Also dismiss the popup.
            dismissHint();
        }
        super.setVisibility(visibility);
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        final int gravity = SizeCompatUILayout.getGravity(layoutDirection);
        if (mWinParams.gravity != gravity) {
            mWinParams.gravity = gravity;
            getContext().getSystemService(WindowManager.class).updateViewLayout(this,
                    mWinParams);
        }
        super.setLayoutDirection(layoutDirection);
    }

    void showHint() {
        if (mShowingHint != null) {
            return;
        }

        // TODO: popup is not attached to the button surface. Need to handle this differently for
        // non-fullscreen task.
        final View popupView = LayoutInflater.from(getContext()).inflate(
                R.layout.size_compat_mode_hint, null);
        final PopupWindow popupWindow = new PopupWindow(popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        popupWindow.setWindowLayoutType(mWinParams.type);
        popupWindow.setElevation(getResources().getDimension(R.dimen.bubble_elevation));
        popupWindow.setAnimationStyle(android.R.style.Animation_InputMethod);
        popupWindow.setClippingEnabled(false);
        popupWindow.setOnDismissListener(() -> mShowingHint = null);
        mShowingHint = popupWindow;

        final Button gotItButton = popupView.findViewById(R.id.got_it);
        gotItButton.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.LTGRAY),
                null /* content */, null /* mask */));
        gotItButton.setOnClickListener(view -> dismissHint());
        popupWindow.showAtLocation(mRestartButton, mWinParams.gravity, mLayout.mPopupOffsetX,
                mLayout.mPopupOffsetY);
    }

    void dismissHint() {
        if (mShowingHint != null) {
            mShowingHint.dismiss();
            mShowingHint = null;
        }
    }
}
