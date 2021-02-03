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

import android.app.ActivityClient;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import com.android.wm.shell.R;

/** Button to restart the size compat activity. */
class SizeCompatRestartButton extends ImageButton implements View.OnClickListener,
        View.OnLongClickListener {
    private static final String TAG = "SizeCompatRestartButton";

    final WindowManager.LayoutParams mWinParams;
    final boolean mShouldShowHint;
    final int mDisplayId;
    final int mPopupOffsetX;
    final int mPopupOffsetY;

    private IBinder mLastActivityToken;
    private PopupWindow mShowingHint;

    SizeCompatRestartButton(Context context, int displayId, boolean hasShownHint) {
        super(context);
        mDisplayId = displayId;
        mShouldShowHint = !hasShownHint;
        final Drawable drawable = context.getDrawable(R.drawable.size_compat_restart_button);
        setImageDrawable(drawable);
        setContentDescription(context.getString(R.string.restart_button_description));

        final int drawableW = drawable.getIntrinsicWidth();
        final int drawableH = drawable.getIntrinsicHeight();
        mPopupOffsetX = drawableW / 2;
        mPopupOffsetY = drawableH * 2;

        final ColorStateList color = ColorStateList.valueOf(Color.LTGRAY);
        final GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(color);
        setBackground(new RippleDrawable(color, null /* content */, mask));
        setOnClickListener(this);
        setOnLongClickListener(this);

        mWinParams = new WindowManager.LayoutParams();
        mWinParams.gravity = getGravity(getResources().getConfiguration().getLayoutDirection());
        mWinParams.width = drawableW * 2;
        mWinParams.height = drawableH * 2;
        mWinParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        mWinParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        mWinParams.format = PixelFormat.TRANSLUCENT;
        mWinParams.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        mWinParams.setTitle(SizeCompatRestartButton.class.getSimpleName()
                + context.getDisplayId());
    }

    void updateLastTargetActivity(IBinder activityToken) {
        mLastActivityToken = activityToken;
    }

    /** @return {@code false} if the target display is invalid. */
    boolean show() {
        try {
            getContext().getSystemService(WindowManager.class).addView(this, mWinParams);
        } catch (WindowManager.InvalidDisplayException e) {
            // The target display may have been removed when the callback has just arrived.
            Log.w(TAG, "Cannot show on display " + getContext().getDisplayId(), e);
            return false;
        }
        return true;
    }

    void remove() {
        if (mShowingHint != null) {
            mShowingHint.dismiss();
        }
        getContext().getSystemService(WindowManager.class).removeViewImmediate(this);
    }

    @Override
    public void onClick(View v) {
        ActivityClient.getInstance().restartActivityProcessIfVisible(mLastActivityToken);
    }

    @Override
    public boolean onLongClick(View v) {
        showHint();
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mShouldShowHint) {
            showHint();
        }
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        final int gravity = getGravity(layoutDirection);
        if (mWinParams.gravity != gravity) {
            mWinParams.gravity = gravity;
            if (mShowingHint != null) {
                mShowingHint.dismiss();
                showHint();
            }
            getContext().getSystemService(WindowManager.class).updateViewLayout(this,
                    mWinParams);
        }
        super.setLayoutDirection(layoutDirection);
    }

    void showHint() {
        if (mShowingHint != null) {
            return;
        }

        final View popupView = LayoutInflater.from(getContext()).inflate(
                R.layout.size_compat_mode_hint, null /* root */);
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
        gotItButton.setOnClickListener(view -> popupWindow.dismiss());
        popupWindow.showAtLocation(this, mWinParams.gravity, mPopupOffsetX, mPopupOffsetY);
    }

    private static int getGravity(int layoutDirection) {
        return Gravity.BOTTOM
                | (layoutDirection == View.LAYOUT_DIRECTION_RTL ? Gravity.START : Gravity.END);
    }
}
