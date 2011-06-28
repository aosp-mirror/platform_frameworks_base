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
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import com.android.systemui.R;

public class NotificationPanel extends RelativeLayout implements StatusBarPanel,
        View.OnClickListener {
    static final String TAG = "Tablet/NotificationPanel";
    static final boolean DEBUG = false;

    final static int PANEL_FADE_DURATION = 150;

    boolean mShowing;
    int mNotificationCount = 0;
    NotificationPanelTitle mTitleArea;
    View mSettingsButton;
    View mNotificationButton;
    View mNotificationScroller;
    ViewGroup mContentFrame;
    Rect mContentArea = new Rect();
    View mSettingsView;
    ViewGroup mContentParent;

    // amount to slide mContentParent down by when mContentFrame is missing
    float mContentFrameMissingTranslation;

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
        mTitleArea = (NotificationPanelTitle) findViewById(R.id.title_area);
        mTitleArea.setPanel(this);

        mSettingsButton = findViewById(R.id.settings_button);
        mNotificationButton = findViewById(R.id.notification_button);

        mNotificationScroller = findViewById(R.id.notification_scroller);
        mContentFrame = (ViewGroup)findViewById(R.id.content_frame);
        mContentFrameMissingTranslation =
            mContentFrame.getBackground().getMinimumHeight() + 10;

        mShowing = false;

        setContentFrameVisible(mNotificationCount > 0, false);
    }

    public void show(boolean show, boolean animate) {
        if (show && !mShowing) {
            setContentFrameVisible(mSettingsView != null || mNotificationCount > 0, false);
        }

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
        if (vis != View.VISIBLE) {
            if (mSettingsView != null) removeSettingsView();
            mNotificationScroller.setVisibility(View.VISIBLE);
            mNotificationScroller.setAlpha(1f);
            mNotificationScroller.scrollTo(0, 0);
            updatePanelModeButtons();
        }
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // Ignore hover events outside of this panel bounds since such events
        // generate spurious accessibility events with the panel content when
        // tapping outside of it, thus confusing the user.
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        if (x >= 0 && x < getWidth() && y >= 0 && y < getHeight()) {
            return super.dispatchHoverEvent(event);
        }
        return true;
    }

    /*
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        if (DEBUG) Slog.d(TAG, String.format("PANEL: onLayout: (%d, %d, %d, %d)", l, t, r, b));
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        if (DEBUG) {
            Slog.d(TAG, String.format("PANEL: onSizeChanged: (%d -> %d, %d -> %d)",
                        oldw, w, oldh, h));
        }
    }
    */

    public void onClick(View v) {
        if (v == mTitleArea) {
            swapPanels();
        }
    }

    public void setNotificationCount(int n) {
//        Slog.d(TAG, "notificationCount=" + n);
        if (!mShowing) {
            // just do it, already
            setContentFrameVisible(n > 0, false);
        } else if (mSettingsView == null) {
            // we're looking at the notifications; time to maybe make some changes
            if ((mNotificationCount > 0) != (n > 0)) {
                setContentFrameVisible(n > 0, true);
            }
        }
        mNotificationCount = n;
    }

    public void setContentFrameVisible(final boolean showing, boolean animate) {
        if (!animate) {
            mContentFrame.setVisibility(showing ? View.VISIBLE : View.GONE);
            mContentFrame.setAlpha(1f);
            // the translation will be patched up when the window is slid into place
            return;
        }

        if (showing) {
            mContentFrame.setVisibility(View.VISIBLE);
        }
        AnimatorSet set = new AnimatorSet();
        set.play(ObjectAnimator.ofFloat(
                mContentFrame, "alpha",
                showing ? 0f : 1f,
                showing ? 1f : 0f))
            .with(ObjectAnimator.ofFloat(
                mContentParent, "translationY",
                showing ? mContentFrameMissingTranslation : 0f,
                showing ? 0f : mContentFrameMissingTranslation))
              ;

        set.setDuration(200);
        if (!showing) {
            set.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator _a) {
                    mContentFrame.setVisibility(View.GONE);
                    mContentFrame.setAlpha(1f);
                }
            });
        }
        set.start();
    }

    public void swapPanels() {
        final View toShow, toHide;
        if (mSettingsView == null) {
            addSettingsView();
            toShow = mSettingsView;
            toHide = mNotificationScroller;
        } else {
            toShow = mNotificationScroller;
            toHide = mSettingsView;
        }
        Animator a = ObjectAnimator.ofFloat(toHide, "alpha", 1f, 0f)
                .setDuration(PANEL_FADE_DURATION);
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator _a) {
                toHide.setVisibility(View.GONE);
                if (toShow != null) {
                    if (mNotificationCount == 0) {
                        // show the frame for settings, hide for notifications
                        setContentFrameVisible(toShow == mSettingsView, true);
                    }

                    toShow.setVisibility(View.VISIBLE);
                    if (toShow == mSettingsView || mNotificationCount > 0) {
                        ObjectAnimator.ofFloat(toShow, "alpha", 0f, 1f)
                                .setDuration(PANEL_FADE_DURATION)
                                .start();
                    }

                    if (toHide == mSettingsView) {
                        removeSettingsView();
                    }
                }
                updatePanelModeButtons();
            }
        });
        a.start();
    }
 
    public void updatePanelModeButtons() {
        final boolean settingsVisible = (mSettingsView != null);
        mSettingsButton.setVisibility(!settingsVisible ? View.VISIBLE : View.INVISIBLE);
        mNotificationButton.setVisibility(settingsVisible ? View.VISIBLE : View.INVISIBLE);
    }

    public boolean isInContentArea(int x, int y) {
        mContentArea.left = mTitleArea.getLeft() + mTitleArea.getPaddingLeft();
        mContentArea.top = mTitleArea.getTop() + mTitleArea.getPaddingTop() 
            + (int)mContentParent.getTranslationY(); // account for any adjustment
        mContentArea.right = mTitleArea.getRight() - mTitleArea.getPaddingRight();

        View theBottom = (mContentFrame.getVisibility() == View.VISIBLE)
            ? mContentFrame : mTitleArea;
        mContentArea.bottom = theBottom.getBottom() - theBottom.getPaddingBottom();

        offsetDescendantRectToMyCoords(mContentParent, mContentArea);
        return mContentArea.contains(x, y);
    }

    void removeSettingsView() {
        if (mSettingsView != null) {
            mContentFrame.removeView(mSettingsView);
            mSettingsView = null;
        }
    }

    // NB: it will be invisible until you show it
    void addSettingsView() {
        LayoutInflater infl = LayoutInflater.from(getContext());
        mSettingsView = infl.inflate(R.layout.status_bar_settings_view, mContentFrame, false);
        mSettingsView.setVisibility(View.GONE);
        mContentFrame.addView(mSettingsView);
    }

    private class Choreographer implements Animator.AnimatorListener {
        boolean mVisible;
        int mPanelHeight;
        AnimatorSet mContentAnim;

        // should group this into a multi-property animation
        final static int OPEN_DURATION = 136;
        final static int CLOSE_DURATION = 250;

        // the panel will start to appear this many px from the end
        final int HYPERSPACE_OFFRAMP = 100;

        Choreographer() {
        }

        void createAnimation(boolean appearing) {
            // mVisible: previous state; appearing: new state
            
            View root = findViewById(R.id.panel_root);
            Animator bgAnim = ObjectAnimator.ofInt(root.getBackground(), "alpha",
                    mVisible ? 255 : 0, appearing ? 255 : 0);

            float start, end;

            // 0: on-screen
            // height: off-screen
            float y = mContentParent.getTranslationY();
            if (appearing) {
                // we want to go from near-the-top to the top, unless we're half-open in the right
                // general vicinity
                end = 0;
                if (mNotificationCount == 0) {
                    end += mContentFrameMissingTranslation;
                }
                start = HYPERSPACE_OFFRAMP+end;
            } else {
                start = y;
                end = y + HYPERSPACE_OFFRAMP;
            }

            Animator posAnim = ObjectAnimator.ofFloat(mContentParent, "translationY",
                    start, end);
            posAnim.setInterpolator(appearing
                    ? new android.view.animation.DecelerateInterpolator(1.0f)
                    : new android.view.animation.AccelerateInterpolator(1.0f));

            if (mContentAnim != null && mContentAnim.isRunning()) {
                mContentAnim.cancel();
            }

            Animator fadeAnim = ObjectAnimator.ofFloat(mContentParent, "alpha",
                                mContentParent.getAlpha(), appearing ? 1.0f : 0.0f);
            fadeAnim.setInterpolator(appearing
                    ? new android.view.animation.AccelerateInterpolator(2.0f)
                    : new android.view.animation.DecelerateInterpolator(2.0f));

            mContentAnim = new AnimatorSet();
            mContentAnim
                .play(fadeAnim)
                .with(bgAnim)
                .with(posAnim)
                ;
            mContentAnim.setDuration((DEBUG?10:1)*(appearing ? OPEN_DURATION : CLOSE_DURATION));
            mContentAnim.addListener(this);
        }

        void startAnimation(boolean appearing) {
            if (DEBUG) Slog.d(TAG, "startAnimation(appearing=" + appearing + ")");

            createAnimation(appearing);

            mContentParent.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            mContentAnim.start();

            mVisible = appearing;
        }

        public void onAnimationCancel(Animator animation) {
            if (DEBUG) Slog.d(TAG, "onAnimationCancel");
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

