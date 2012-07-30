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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.RelativeLayout;

import com.android.systemui.ExpandHelper;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NotificationRowLayout;

public class NotificationPanel extends RelativeLayout implements StatusBarPanel,
        View.OnClickListener {
    private ExpandHelper mExpandHelper;
    private NotificationRowLayout latestItems;

    static final String TAG = "Tablet/NotificationPanel";
    static final boolean DEBUG = false;

    final static int PANEL_FADE_DURATION = 150;

    boolean mShowing;
    boolean mHasClearableNotifications = false;
    int mNotificationCount = 0;
    NotificationPanelTitle mTitleArea;
    View mSettingsButton;
    View mNotificationButton;
    View mNotificationScroller;
    ViewGroup mContentFrame;
    Rect mContentArea = new Rect();
    View mSettingsView;
    ViewGroup mContentParent;
    TabletStatusBar mBar;
    View mClearButton;
    static Interpolator sAccelerateInterpolator = new AccelerateInterpolator();
    static Interpolator sDecelerateInterpolator = new DecelerateInterpolator();

    // amount to slide mContentParent down by when mContentFrame is missing
    float mContentFrameMissingTranslation;

    Choreographer mChoreo = new Choreographer();

    public NotificationPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationPanel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setBar(TabletStatusBar b) {
        mBar = b;
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
        mContentFrameMissingTranslation = 0; // not needed with current assets

        // the "X" that appears in place of the clock when the panel is showing notifications
        mClearButton = findViewById(R.id.clear_all_button);
        mClearButton.setOnClickListener(mClearButtonListener);

        mShowing = false;
    }

    @Override
    protected void onAttachedToWindow () {
        super.onAttachedToWindow();
        latestItems = (NotificationRowLayout) findViewById(R.id.content);
        int minHeight = getResources().getDimensionPixelSize(R.dimen.notification_row_min_height);
        int maxHeight = getResources().getDimensionPixelSize(R.dimen.notification_row_max_height);
        mExpandHelper = new ExpandHelper(mContext, latestItems, minHeight, maxHeight);
        mExpandHelper.setEventSource(this);
        mExpandHelper.setGravity(Gravity.BOTTOM);
    }

    private View.OnClickListener mClearButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            mBar.clearAll();
        }
    };

    public View getClearButton() {
        return mClearButton;
    }

    public void show(boolean show, boolean animate) {
        if (animate) {
            if (mShowing != show) {
                mShowing = show;
                if (show) {
                    setVisibility(View.VISIBLE);
                    // Don't start the animation until we've created the layer, which is done
                    // right before we are drawn
                    mContentParent.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                    getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
                } else {
                    mChoreo.startAnimation(show);
                }
            }
        } else {
            mShowing = show;
            setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * This is used only when we've created a hardware layer and are waiting until it's
     * been created in order to start the appearing animation.
     */
    private ViewTreeObserver.OnPreDrawListener mPreDrawListener =
            new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            getViewTreeObserver().removeOnPreDrawListener(this);
            mChoreo.startAnimation(true);
            return false;
        }
    };

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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
    final int keyCode = event.getKeyCode();
        switch (keyCode) {
            // We exclusively handle the back key by hiding this panel.
            case KeyEvent.KEYCODE_BACK: {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    mBar.animateCollapse();
                }
                return true;
            }
            // We react to the home key but let the system handle it.
            case KeyEvent.KEYCODE_HOME: {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    mBar.animateCollapse();
                }
            } break;
        }
        return super.dispatchKeyEvent(event);
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
        if (mSettingsButton.isEnabled() && v == mTitleArea) {
            swapPanels();
        }
    }

    public void setNotificationCount(int n) {
        mNotificationCount = n;
    }

    public void setContentFrameVisible(final boolean showing, boolean animate) {
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
                updateClearButton();
                updatePanelModeButtons();
            }
        });
        a.start();
    }
 
    public void updateClearButton() {
        if (mBar != null) {
            final boolean showX 
                = (isShowing()
                        && mHasClearableNotifications
                        && mNotificationScroller.getVisibility() == View.VISIBLE);
            getClearButton().setVisibility(showX ? View.VISIBLE : View.INVISIBLE);
        }
    }

    public void setClearable(boolean clearable) {
        mHasClearableNotifications = clearable;
    }

    public void updatePanelModeButtons() {
        final boolean settingsVisible = (mSettingsView != null);
        mSettingsButton.setVisibility(!settingsVisible && mSettingsButton.isEnabled() ? View.VISIBLE : View.GONE);
        mNotificationButton.setVisibility(settingsVisible ? View.VISIBLE : View.GONE);
    }

    public boolean isInContentArea(int x, int y) {
        mContentArea.left = mContentFrame.getLeft() + mContentFrame.getPaddingLeft();
        mContentArea.top = mContentFrame.getTop() + mContentFrame.getPaddingTop()
            + (int)mContentParent.getTranslationY(); // account for any adjustment
        mContentArea.right = mContentFrame.getRight() - mContentFrame.getPaddingRight();
        mContentArea.bottom = mContentFrame.getBottom() - mContentFrame.getPaddingBottom();

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
        mSettingsView = infl.inflate(R.layout.system_bar_settings_view, mContentFrame, false);
        mSettingsView.setVisibility(View.GONE);
        mContentFrame.addView(mSettingsView);
    }

    private class Choreographer implements Animator.AnimatorListener {
        boolean mVisible;
        int mPanelHeight;
        AnimatorSet mContentAnim;

        // should group this into a multi-property animation
        final static int OPEN_DURATION = 250;
        final static int CLOSE_DURATION = 250;

        // the panel will start to appear this many px from the end
        final int HYPERSPACE_OFFRAMP = 200;

        Choreographer() {
        }

        void createAnimation(boolean appearing) {
            // mVisible: previous state; appearing: new state
            
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
            posAnim.setInterpolator(appearing ? sDecelerateInterpolator : sAccelerateInterpolator);

            if (mContentAnim != null && mContentAnim.isRunning()) {
                mContentAnim.cancel();
            }

            Animator fadeAnim = ObjectAnimator.ofFloat(mContentParent, "alpha",
                    appearing ? 1.0f : 0.0f);
            fadeAnim.setInterpolator(appearing ? sAccelerateInterpolator : sDecelerateInterpolator);

            mContentAnim = new AnimatorSet();
            mContentAnim
                .play(fadeAnim)
                .with(posAnim)
                ;
            mContentAnim.setDuration((DEBUG?10:1)*(appearing ? OPEN_DURATION : CLOSE_DURATION));
            mContentAnim.addListener(this);
        }

        void startAnimation(boolean appearing) {
            if (DEBUG) Slog.d(TAG, "startAnimation(appearing=" + appearing + ")");

            createAnimation(appearing);
            mContentAnim.start();

            mVisible = appearing;

            // we want to start disappearing promptly
            if (!mVisible) updateClearButton();
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

            // we want to show the X lazily
            if (mVisible) updateClearButton();
        }

        public void onAnimationRepeat(Animator animation) {
        }

        public void onAnimationStart(Animator animation) {
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        MotionEvent cancellation = MotionEvent.obtain(ev);
        cancellation.setAction(MotionEvent.ACTION_CANCEL);

        boolean intercept = mExpandHelper.onInterceptTouchEvent(ev) ||
                super.onInterceptTouchEvent(ev);
        if (intercept) {
            latestItems.onInterceptTouchEvent(cancellation);
        }
        return intercept;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean handled = mExpandHelper.onTouchEvent(ev) ||
                super.onTouchEvent(ev);
        return handled;
    }

    public void setSettingsEnabled(boolean settingsEnabled) {
        if (mSettingsButton != null) {
            mSettingsButton.setEnabled(settingsEnabled);
            mSettingsButton.setVisibility(settingsEnabled ? View.VISIBLE : View.GONE);
        }
    }
}

