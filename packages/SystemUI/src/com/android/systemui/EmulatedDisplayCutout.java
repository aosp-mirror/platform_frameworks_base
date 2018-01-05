/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import java.util.Collections;
import java.util.List;

/**
 * Emulates a display cutout by drawing its shape in an overlay as supplied by
 * {@link DisplayCutout}.
 */
public class EmulatedDisplayCutout extends SystemUI implements ConfigurationListener {
    private View mOverlay;
    private boolean mAttached;
    private WindowManager mWindowManager;

    @Override
    public void start() {
        Dependency.get(ConfigurationController.class).addCallback(this);

        mWindowManager = mContext.getSystemService(WindowManager.class);
        updateAttached();
    }

    @Override
    public void onOverlayChanged() {
        updateAttached();
    }

    private void updateAttached() {
        boolean shouldAttach = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout);
        setAttached(shouldAttach);
    }

    private void setAttached(boolean attached) {
        if (attached && !mAttached) {
            if (mOverlay == null) {
                mOverlay = new CutoutView(mContext);
                mOverlay.setLayoutParams(getLayoutParams());
            }
            mWindowManager.addView(mOverlay, mOverlay.getLayoutParams());
            mAttached = true;
        } else if (!attached && mAttached) {
            mWindowManager.removeView(mOverlay);
            mAttached = false;
        }
    }

    private WindowManager.LayoutParams getLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_SLIPPERY
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
        lp.flags2 |= WindowManager.LayoutParams.FLAG2_LAYOUT_IN_DISPLAY_CUTOUT_AREA;
        lp.setTitle("EmulatedDisplayCutout");
        lp.gravity = Gravity.TOP;
        return lp;
    }

    private static class CutoutView extends View {
        private final Paint mPaint = new Paint();
        private final Path mBounds = new Path();

        CutoutView(Context context) {
            super(context);
        }

        @Override
        public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            mBounds.reset();
            if (insets.getDisplayCutout() != null) {
                insets.getDisplayCutout().getBounds().getBoundaryPath(mBounds);
            }
            invalidate();
            return insets.consumeDisplayCutout();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!mBounds.isEmpty()) {
                mPaint.setColor(Color.BLACK);
                mPaint.setStyle(Paint.Style.FILL);

                canvas.drawPath(mBounds, mPaint);
            }
        }
    }
}
