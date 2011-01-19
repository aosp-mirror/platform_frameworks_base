/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Region;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.ServiceManager;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.HapticFeedbackConstants;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.widget.RemoteViews.RemoteView;

import com.android.systemui.R;

public class EventHole extends View implements ViewTreeObserver.OnComputeInternalInsetsListener {
    private static final String TAG = "StatusBar.EventHole";

    private boolean mWindowVis;
    private int[] mLoc = new int[2];

    public EventHole(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EventHole(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mWindowVis = visibility == View.VISIBLE;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
    }

    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
        final boolean visible = isShown() && mWindowVis && getWidth() > 0 && getHeight() > 0;
        final int[] loc = mLoc;
        getLocationInWindow(loc);
        final int l = loc[0];
        final int r = l + getWidth();
        final int t = loc[1];
        final int b = t + getHeight();
        
        View top = this;
        while (top.getParent() instanceof View) {
            top = (View)top.getParent();
        }

        if (visible) {
            info.setTouchableInsets(
                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            info.touchableRegion.set(0, 0, top.getWidth(), top.getHeight());
            info.touchableRegion.op(l, t, r, b, Region.Op.DIFFERENCE);
        } else {
            info.setTouchableInsets(
                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
        }
    }
}

