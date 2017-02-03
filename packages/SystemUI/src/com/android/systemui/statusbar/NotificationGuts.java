/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.NotificationMenuRowProvider;
import com.android.systemui.plugins.statusbar.NotificationMenuRowProvider.GutsContent;
import com.android.systemui.statusbar.stack.StackStateAnimator;

import java.util.Set;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class NotificationGuts extends FrameLayout
        implements NotificationMenuRowProvider.GutsInteractionListener {
    private static final String TAG = "NotificationGuts";
    private static final long CLOSE_GUTS_DELAY = 8000;

    private Drawable mBackground;
    private int mClipTopAmount;
    private int mClipBottomAmount;
    private int mActualHeight;
    private boolean mExposed;

    private Handler mHandler;
    private Runnable mFalsingCheck;
    private boolean mNeedsFalsingProtection;
    private OnGutsClosedListener mListener;

    private GutsContent mGutsContent;

    public interface OnGutsClosedListener {
        public void onGutsClosed(NotificationGuts guts);
    }

    public NotificationGuts(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        mHandler = new Handler();
        mFalsingCheck = new Runnable() {
            @Override
            public void run() {
                if (mNeedsFalsingProtection && mExposed) {
                    closeControls(-1 /* x */, -1 /* y */, false /* save */);
                }
            }
        };
        final TypedArray ta = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.Theme, 0, 0);
        ta.recycle();
    }

    public NotificationGuts(Context context) {
        this(context, null);

    }

    public void setGutsContent(GutsContent content) {
        mGutsContent = content;
        removeAllViews();
        addView(mGutsContent.getContentView());
    }

    public void resetFalsingCheck() {
        mHandler.removeCallbacks(mFalsingCheck);
        if (mNeedsFalsingProtection && mExposed) {
            mHandler.postDelayed(mFalsingCheck, CLOSE_GUTS_DELAY);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        draw(canvas, mBackground);
    }

    private void draw(Canvas canvas, Drawable drawable) {
        int top = mClipTopAmount;
        int bottom = mActualHeight - mClipBottomAmount;
        if (drawable != null && top < bottom) {
            drawable.setBounds(0, top, getWidth(), bottom);
            drawable.draw(canvas);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBackground = mContext.getDrawable(R.drawable.notification_guts_bg);
        if (mBackground != null) {
            mBackground.setCallback(this);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mBackground;
    }

    @Override
    protected void drawableStateChanged() {
        drawableStateChanged(mBackground);
    }

    private void drawableStateChanged(Drawable d) {
        if (d != null && d.isStateful()) {
            d.setState(getDrawableState());
        }
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (mBackground != null) {
            mBackground.setHotspot(x, y);
        }
    }

    interface OnSettingsClickListener {
        void onClick(View v, int appUid);
    }

    public void closeControls(int x, int y, boolean save) {
        if (getWindowToken() == null) {
            if (mListener != null) {
                mListener.onGutsClosed(this);
            }
            return;
        }
        if (mGutsContent == null || !mGutsContent.handleCloseControls(save)) {
            animateClose(x, y);
        }
        setExposed(false, mNeedsFalsingProtection);
        if (mListener != null) {
            mListener.onGutsClosed(this);
        }
    }

    private void animateClose(int x, int y) {
        if (x == -1 || y == -1) {
            x = (getLeft() + getRight()) / 2;
            y = (getTop() + getHeight() / 2);
        }
        final double horz = Math.max(getWidth() - x, x);
        final double vert = Math.max(getHeight() - y, y);
        final float r = (float) Math.hypot(horz, vert);
        final Animator a = ViewAnimationUtils.createCircularReveal(this,
                x, y, r, 0);
        a.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        a.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                setVisibility(View.GONE);
            }
        });
        a.start();
    }

    public void setActualHeight(int actualHeight) {
        mActualHeight = actualHeight;
        invalidate();
    }

    public int getActualHeight() {
        return mActualHeight;
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        invalidate();
    }

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        invalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {
        // Prevents this view from creating a layer when alpha is animating.
        return false;
    }

    public void setClosedListener(OnGutsClosedListener listener) {
        mListener = listener;
    }

    public void setExposed(boolean exposed, boolean needsFalsingProtection) {
        mExposed = exposed;
        mNeedsFalsingProtection = needsFalsingProtection;
        if (mExposed && mNeedsFalsingProtection) {
            resetFalsingCheck();
        } else {
            mHandler.removeCallbacks(mFalsingCheck);
        }
    }

    public boolean isExposed() {
        return mExposed;
    }

    @Override
    public void onInteraction(View view) {
        resetFalsingCheck();
    }

    @Override
    public void closeGuts(View view) {
        closeControls(-1 /* x */, -1 /* y */, true /* notify */);
    }
}
