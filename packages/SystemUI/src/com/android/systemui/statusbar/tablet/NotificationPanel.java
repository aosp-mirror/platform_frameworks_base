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
import android.animation.AnimatorSet;
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
import android.widget.RelativeLayout;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.systemui.R;

public class NotificationPanel extends RelativeLayout implements StatusBarPanel,
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
    View mScrim, mGlow;
    ViewGroup mContentParent;

    Choreographer mChoreo = new Choreographer();

    public NotificationPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationPanel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();

        setWillNotDraw(false);

        mContentParent = (ViewGroup)findViewById(R.id.content_parent);
        mContentParent.bringToFront();
        mTitleArea = findViewById(R.id.title_area);
        mTitleArea.setOnClickListener(this);

        mScrim = findViewById(R.id.scrim);
        mGlow = findViewById(R.id.glow);

        mSettingsButton = (ImageView)findViewById(R.id.settings_button);
        mNotificationButton = (ImageView)findViewById(R.id.notification_button);

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
        mChoreo.setPanelHeight(mContentParent.getHeight());
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mContentArea = null;
    }

    public void onClick(View v) {
        if (v == mTitleArea) {
            if (mSettingsView == null) {
                switchToSettingsMode();
            } else {
                switchToNotificationMode();
            }
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
        int mPanelHeight;
        AnimatorSet mContentAnim;

        // should group this into a multi-property animation
        final int OPEN_DURATION = 136;
        final int CLOSE_DURATION = 250;

        // the panel will start to appear this many px from the end
        final int HYPERSPACE_OFFRAMP = 30;

        Choreographer() {
        }

        void createAnimation(boolean appearing) {
            Animator bgAnim = ObjectAnimator.ofFloat(mScrim,
                    "alpha", mScrim.getAlpha(), appearing ? 1 : 0);

            float start, end;

            // 0: on-screen
            // height: off-screen
            float y = mContentParent.getTranslationY();
            if (appearing) {
                // we want to go from near-the-top to the top, unless we're half-open in the right
                // general vicinity
                start = (y < HYPERSPACE_OFFRAMP)
                    ? y
                    : HYPERSPACE_OFFRAMP;
                end = 0;
            } else {
                start = y;
                end = y + HYPERSPACE_OFFRAMP;
            }
            Animator posAnim = ObjectAnimator.ofFloat(mContentParent, "translationY", start, end);
            posAnim.setInterpolator(appearing
                    ? new android.view.animation.DecelerateInterpolator(2.0f)
                    : new android.view.animation.AccelerateInterpolator(2.0f));

            Animator glowAnim = ObjectAnimator.ofFloat(mGlow, "alpha",
                    mGlow.getAlpha(), appearing ? 1.0f : 0.0f);
            glowAnim.setInterpolator(appearing
                    ? new android.view.animation.AccelerateInterpolator(1.0f)
                    : new android.view.animation.DecelerateInterpolator(1.0f));

            mContentAnim = new AnimatorSet();
            mContentAnim
                .play(ObjectAnimator.ofFloat(mContentParent, "alpha", mContentParent.getAlpha(),
                                                                      appearing ? 1.0f : 0.0f))
                .with(glowAnim)
                .with(bgAnim)
                .with(posAnim)
                ;
            mContentAnim.setDuration(appearing ? OPEN_DURATION : CLOSE_DURATION);
            mContentAnim.addListener(this);
        }

        void startAnimation(boolean appearing) {
            if (DEBUG) Slog.d(TAG, "startAnimation(appearing=" + appearing + ")");

            createAnimation(appearing);

            mContentParent.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            mContentAnim.start();

            mVisible = appearing;
        }

        void jumpTo(boolean appearing) {
//            setBgAlpha(appearing ? 255 : 0);
            mContentParent.setTranslationY(appearing ? 0 : mPanelHeight);
        }

        public void setPanelHeight(int h) {
            if (DEBUG) Slog.d(TAG, "panelHeight=" + h);
            mPanelHeight = h;
            if (mPanelHeight == 0) {
                // fully closed, no animation necessary
            } else if (mVisible) {
                if (DEBUG) {
                    Slog.d(TAG, "panelHeight not zero but trying to open; scheduling an anim"
                            + " to open fully");
                }
                startAnimation(true);
            }
        }

        public void onAnimationCancel(Animator animation) {
            if (DEBUG) Slog.d(TAG, "onAnimationCancel");
            // force this to zero so we close the window
            mVisible = false;
        }

        public void onAnimationEnd(Animator animation) {
            if (DEBUG) Slog.d(TAG, "onAnimationEnd");
            if (! mVisible) {
                setVisibility(View.GONE);
            }
            mContentParent.setLayerType(View.LAYER_TYPE_NONE, null);
            mContentAnim = null;
        }

        public void onAnimationRepeat(Animator animation) {
        }

        public void onAnimationStart(Animator animation) {
        }
    }
}

