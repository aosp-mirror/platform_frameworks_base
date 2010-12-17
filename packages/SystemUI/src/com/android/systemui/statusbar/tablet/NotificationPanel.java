/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

public class NotificationPanel extends LinearLayout implements StatusBarPanel,
        View.OnClickListener {
    static final String TAG = "Tablet/NotificationPanel";
    static final boolean DEBUG = false;

    boolean mShowing;
    View mTitleArea;
    View mSettingsButton;
    View mNotificationButton;
    View mNotificationScroller;
    View mNotificationGlow;
    ViewGroup mContentFrame;
    Rect mContentArea;
    View mSettingsView;
    ViewGroup mContentParent;

    Choreographer mChoreo = new Choreographer();
    int mStatusBarHeight;
    Drawable mBgDrawable;
    Drawable mGlowDrawable;

    public NotificationPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationPanel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final Resources res = context.getResources();

        mStatusBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mBgDrawable = res.getDrawable(R.drawable.notify_panel_bg_protect);
        mGlowDrawable = res.getDrawable(R.drawable.notify_glow_back);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();

        setWillNotDraw(false);

        mContentParent = (ViewGroup)findViewById(R.id.content_parent);
        mTitleArea = findViewById(R.id.title_area);

        mSettingsButton = (ImageView)findViewById(R.id.settings_button);
        mSettingsButton.setOnClickListener(this);
        mNotificationButton = (ImageView)findViewById(R.id.notification_button);
        mNotificationButton.setOnClickListener(this);

        mNotificationScroller = findViewById(R.id.notification_scroller);
        mNotificationGlow = findViewById(R.id.notification_glow);
        mContentFrame = (ViewGroup)findViewById(R.id.content_frame);
    }

    public void show(boolean show, boolean animate) {
        if (animate) {
            if (mShowing != show) {
                mShowing = show;
                if (show) {
                    setVisibility(View.VISIBLE);
                }
                mChoreo.startAnimation(show);
            }
        } else {
            mShowing = show;
            setVisibility(show ? View.VISIBLE : View.GONE);
            mChoreo.jumpTo(show);
        }
    }

    /**
     * Whether the panel is showing, or, if it's animating, whether it will be
     * when the animation is done.
     */
    public boolean isShowing() {
        return mShowing;
    }

    @Override
    public void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        // when we hide, put back the notifications
        if (!isShown()) {
            switchToNotificationMode();
            mNotificationScroller.scrollTo(0, 0);
        }
    }

    /**
     * We need to be aligned at the bottom.  LinearLayout can't do this, so instead,
     * let LinearLayout do all the hard work, and then shift everything down to the bottom.
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // We know that none of our children are GONE, so don't worry about skipping GONE views.
        final int N = getChildCount();
        if (N == 0) {
            return;
        }
        final int allocatedBottom = getChildAt(N-1).getBottom();
        final int shift = b - allocatedBottom - getPaddingBottom();
        if (shift <= 0) {
            return;
        }
        for (int i=0; i<N; i++) {
            final View c = getChildAt(i);
            c.layout(c.getLeft(), c.getTop() + shift, c.getRight(), c.getBottom() + shift);
        }

        mChoreo.setPanelHeight(mContentParent.getHeight());
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mContentArea = null;
        mBgDrawable.setBounds(0, 0, w, h-mStatusBarHeight);
    }

    @Override
    public void onDraw(Canvas canvas) {
        int saveCount;
        final int w = getWidth();
        final int h = getHeight();

        super.onDraw(canvas);

        // Background protection
        mBgDrawable.draw(canvas);

        // The panel glow (behind status bar)

        saveCount = canvas.save();
        canvas.clipRect(0, 0, w, h-mStatusBarHeight);
        mGlowDrawable.draw(canvas);
        canvas.restoreToCount(saveCount);
    }

    public void onClick(View v) {
        if (v == mSettingsButton) {
            switchToSettingsMode();
        } else if (v == mNotificationButton) {
            switchToNotificationMode();
        }
    }

    public void switchToSettingsMode() {
        removeSettingsView();
        addSettingsView();
        mSettingsButton.setVisibility(View.INVISIBLE);
        mNotificationScroller.setVisibility(View.GONE);
        mNotificationButton.setVisibility(View.VISIBLE);
    }

    public void switchToNotificationMode() {
        removeSettingsView();
        mSettingsButton.setVisibility(View.VISIBLE);
        mNotificationScroller.setVisibility(View.VISIBLE);
        mNotificationButton.setVisibility(View.INVISIBLE);
    }

    public boolean isInContentArea(int x, int y) {
        if (mContentArea == null) {
            mContentArea = new Rect(mContentFrame.getLeft(),
                    mTitleArea.getTop(),
                    mContentFrame.getRight(),
                    mContentFrame.getBottom());
            offsetDescendantRectToMyCoords(mContentParent, mContentArea);
        }
        return mContentArea.contains(x, y);
    }

    void removeSettingsView() {
        if (mSettingsView != null) {
            mContentFrame.removeView(mSettingsView);
            mSettingsView = null;
        }
    }

    void addSettingsView() {
        LayoutInflater infl = LayoutInflater.from(getContext());
        mSettingsView = infl.inflate(R.layout.status_bar_settings_view, mContentFrame, false);
        mContentFrame.addView(mSettingsView, mContentFrame.indexOfChild(mNotificationGlow));
    }

    private class Choreographer implements Animator.AnimatorListener {
        boolean mVisible;
        int mBgAlpha;
        ValueAnimator mBgAnim;
        int mPanelHeight;
        int mPanelBottom;
        ValueAnimator mPositionAnim;

        // should group this into a multi-property animation
        final int OPEN_DURATION = 200;

        Choreographer() {
        }

        void createAnimation(boolean visible) {
            mBgAnim = ObjectAnimator.ofInt(this, "bgAlpha", mBgAlpha, visible ? 255 : 0)
                    .setDuration(OPEN_DURATION);
            mBgAnim.addListener(this);

            mPositionAnim = ObjectAnimator.ofInt(this, "panelBottom", mPanelBottom,
                        visible ? mPanelHeight : 0)
                    .setDuration(OPEN_DURATION);
        }

        void startAnimation(boolean visible) {
            if (DEBUG) Slog.d(TAG, "startAnimation(visible=" + visible + ")");
            if (mBgAnim != null && mVisible != visible) {
                mBgAnim.reverse();
                mPositionAnim.reverse();
            } else {
                createAnimation(visible);
                mBgAnim.start();
                mPositionAnim.start();
            }
            mVisible = visible;
        }

        void jumpTo(boolean visible) {
            setBgAlpha(visible ? 255 : 0);
            setPanelBottom(visible ? mPanelHeight : 0);
        }

        public void setBgAlpha(int alpha) {
            mBgAlpha = alpha;
            mBgDrawable.setAlpha((int)(alpha));
            invalidate();
        }

        // 0 is closed, the height of the panel is open
        public void setPanelBottom(int y) {
            mPanelBottom = y;
            int translationY = mPanelHeight - y;
            mContentParent.setTranslationY(translationY);

            final int glowXOffset = 100;
            final int glowYOffset = 100;
            int glowX = mContentParent.getLeft() - glowXOffset;
            int glowY = mContentParent.getTop() - glowYOffset + translationY;
            mGlowDrawable.setBounds(glowX, glowY, glowX + mGlowDrawable.getIntrinsicWidth(),
                    glowY + mGlowDrawable.getIntrinsicHeight());

            float alpha;
            if (mPanelBottom > glowYOffset) {
                alpha = 1;
            } else {
                alpha = ((float)mPanelBottom) / glowYOffset;
            }
            mContentParent.setAlpha(alpha);
            mGlowDrawable.setAlpha((int)(255 * alpha));

            if (false) {
                Slog.d(TAG, "mPanelBottom=" + mPanelBottom + " translationY=" + translationY
                        + " alpha=" + alpha + " glowY=" + glowY);
            }
        }

        public void setPanelHeight(int h) {
            mPanelHeight = h;
            if (mPanelBottom == 0) {
                // fully closed, no animation necessary
                setPanelBottom(0);
            } else if (mVisible) {
                if (DEBUG) {
                    Slog.d(TAG, "panelHeight not zero but trying to open; scheduling an anim to open fully");
                }
                startAnimation(true);
            }
        }

        public void onAnimationCancel(Animator animation) {
            if (DEBUG) Slog.d(TAG, "onAnimationCancel mBgAlpha=" + mBgAlpha);
            // force this to zero so we close the window
            mBgAlpha = 0;
        }

        public void onAnimationEnd(Animator animation) {
            if (DEBUG) Slog.d(TAG, "onAnimationEnd mBgAlpha=" + mBgAlpha);
            if (mBgAlpha == 0) {
                setVisibility(View.GONE);
            }
            mBgAnim = null;
        }

        public void onAnimationRepeat(Animator animation) {
        }

        public void onAnimationStart(Animator animation) {
        }
    }
}

