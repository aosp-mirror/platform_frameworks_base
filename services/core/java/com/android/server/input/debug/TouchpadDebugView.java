/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.input.debug;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Objects;

public class TouchpadDebugView extends LinearLayout {
    /**
     * Input device ID for the touchpad that this debug view is displaying.
     */
    private final int mTouchpadId;

    @NonNull
    private final WindowManager mWindowManager;

    @NonNull
    private final WindowManager.LayoutParams mWindowLayoutParams;

    private final int mTouchSlop;

    private float mTouchDownX;
    private float mTouchDownY;
    private int mScreenWidth;
    private int mScreenHeight;
    private int mWindowLocationBeforeDragX;
    private int mWindowLocationBeforeDragY;

    public TouchpadDebugView(Context context, int touchpadId) {
        super(context);
        mTouchpadId = touchpadId;
        mWindowManager =
                Objects.requireNonNull(getContext().getSystemService(WindowManager.class));
        init(context);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        // TODO(b/360137366): Use the hardware properties to initialise layout parameters.
        mWindowLayoutParams = new WindowManager.LayoutParams();
        mWindowLayoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        mWindowLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mWindowLayoutParams.privateFlags |=
                WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        mWindowLayoutParams.setFitInsetsTypes(0);
        mWindowLayoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWindowLayoutParams.format = PixelFormat.TRANSLUCENT;
        mWindowLayoutParams.setTitle("TouchpadDebugView - display " + mContext.getDisplayId());

        mWindowLayoutParams.x = 40;
        mWindowLayoutParams.y = 100;
        mWindowLayoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        mWindowLayoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        mWindowLayoutParams.gravity = Gravity.TOP | Gravity.LEFT;
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setBackgroundColor(Color.TRANSPARENT);

        // TODO(b/286551975): Replace this content with the touchpad debug view.
        TextView textView1 = new TextView(context);
        textView1.setBackgroundColor(Color.parseColor("#FFFF0000"));
        textView1.setTextSize(20);
        textView1.setText("Touchpad Debug View 1");
        textView1.setGravity(Gravity.CENTER);
        textView1.setTextColor(Color.WHITE);
        textView1.setLayoutParams(new LayoutParams(1000, 200));

        TextView textView2 = new TextView(context);
        textView2.setBackgroundColor(Color.BLUE);
        textView2.setTextSize(20);
        textView2.setText("Touchpad Debug View 2");
        textView2.setGravity(Gravity.CENTER);
        textView2.setTextColor(Color.WHITE);
        textView2.setLayoutParams(new LayoutParams(1000, 200));

        addView(textView1);
        addView(textView2);

        updateScreenDimensions();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float deltaX;
        float deltaY;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mWindowLocationBeforeDragX = mWindowLayoutParams.x;
                mWindowLocationBeforeDragY = mWindowLayoutParams.y;
                mTouchDownX = event.getRawX() - mWindowLocationBeforeDragX;
                mTouchDownY = event.getRawY() - mWindowLocationBeforeDragY;
                return true;

            case MotionEvent.ACTION_MOVE:
                deltaX = event.getRawX() - mWindowLayoutParams.x - mTouchDownX;
                deltaY = event.getRawY() - mWindowLayoutParams.y - mTouchDownY;
                Slog.d("TouchpadDebugView", "Slop = " + mTouchSlop);
                if (isSlopExceeded(deltaX, deltaY)) {
                    Slog.d("TouchpadDebugView", "Slop exceeded");
                    mWindowLayoutParams.x =
                            Math.max(0, Math.min((int) (event.getRawX() - mTouchDownX),
                                    mScreenWidth - this.getWidth()));
                    mWindowLayoutParams.y =
                            Math.max(0, Math.min((int) (event.getRawY() - mTouchDownY),
                                    mScreenHeight - this.getHeight()));

                    Slog.d("TouchpadDebugView", "New position X: "
                            + mWindowLayoutParams.x + ", Y: " + mWindowLayoutParams.y);

                    mWindowManager.updateViewLayout(this, mWindowLayoutParams);
                }
                return true;

            case MotionEvent.ACTION_UP:
                deltaX = event.getRawX() - mWindowLayoutParams.x - mTouchDownX;
                deltaY = event.getRawY() - mWindowLayoutParams.y - mTouchDownY;
                if (!isSlopExceeded(deltaX, deltaY)) {
                    performClick();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                // Move the window back to the original position
                mWindowLayoutParams.x = mWindowLocationBeforeDragX;
                mWindowLayoutParams.y = mWindowLocationBeforeDragY;
                mWindowManager.updateViewLayout(this, mWindowLayoutParams);
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        Slog.d("TouchpadDebugView", "You clicked me!");
        return true;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreenDimensions();

        // Adjust view position to stay within screen bounds after rotation
        mWindowLayoutParams.x =
                Math.max(0, Math.min(mWindowLayoutParams.x, mScreenWidth - getWidth()));
        mWindowLayoutParams.y =
                Math.max(0, Math.min(mWindowLayoutParams.y, mScreenHeight - getHeight()));
        mWindowManager.updateViewLayout(this, mWindowLayoutParams);
    }

    private boolean isSlopExceeded(float deltaX, float deltaY) {
        return deltaX * deltaX + deltaY * deltaY >= mTouchSlop * mTouchSlop;
    }

    private void updateScreenDimensions() {
        Rect windowBounds =
                mWindowManager.getCurrentWindowMetrics().getBounds();
        mScreenWidth = windowBounds.width();
        mScreenHeight = windowBounds.height();
    }

    public int getTouchpadId() {
        return mTouchpadId;
    }

    public WindowManager.LayoutParams getWindowLayoutParams() {
        return mWindowLayoutParams;
    }
}
