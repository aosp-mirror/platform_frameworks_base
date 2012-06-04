/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.animation.LayoutTransition;
import android.app.ActivityOptions;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.widget.FrameLayout;
import com.android.internal.widget.multiwaveview.MultiWaveView;
import com.android.internal.widget.multiwaveview.MultiWaveView.OnTriggerListener;
import com.android.systemui.R;
import com.android.systemui.recent.StatusBarTouchProxy;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.tablet.StatusBarPanel;
import com.android.systemui.statusbar.tablet.TabletStatusBar;

public class SearchPanelView extends FrameLayout implements
        StatusBarPanel, ActivityOptions.OnAnimationStartedListener {
    private static final int SEARCH_PANEL_HOLD_DURATION = 0;
    static final String TAG = "SearchPanelView";
    static final boolean DEBUG = TabletStatusBar.DEBUG || PhoneStatusBar.DEBUG || false;
    private static final String ASSIST_ICON_METADATA_NAME =
            "com.android.systemui.action_assist_icon";
    private final Context mContext;
    private BaseStatusBar mBar;
    private StatusBarTouchProxy mStatusBarTouchProxy;

    private boolean mShowing;
    private View mSearchTargetsContainer;
    private MultiWaveView mMultiWaveView;

    public SearchPanelView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SearchPanelView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mSearchManager = (SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE);
        if (mSearchManager == null) {
            Slog.w(TAG, "Search manager not available");
        }
    }

    private SearchManager mSearchManager;

    // This code should be the same as that used in LockScreen.java
    public boolean isAssistantAvailable() {
        Intent intent = getAssistIntent();
        return intent == null ? false
                : mContext.getPackageManager().queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY).size() > 0;
    }

    private Intent getAssistIntent() {
        Intent intent = null;
        SearchManager searchManager = getSearchManager();
        if (searchManager != null) {
            ComponentName globalSearchActivity = searchManager.getGlobalSearchActivity();
            if (globalSearchActivity != null) {
                intent = new Intent(Intent.ACTION_ASSIST);
                intent.setPackage(globalSearchActivity.getPackageName());
            } else {
                Slog.w(TAG, "No global search activity");
            }
        } else {
            Slog.w(TAG, "No SearchManager");
        }
        return intent;
    }

    private SearchManager getSearchManager() {
        if (mSearchManager == null) {
            mSearchManager = (SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE);
        }
        return mSearchManager;
    }

    private void startAssistActivity() {
        // Close Recent Apps if needed
        mBar.animateCollapse(CommandQueue.FLAG_EXCLUDE_SEARCH_PANEL);
        // Launch Assist
        Intent intent = getAssistIntent();
        if (intent == null) return;
        try {
            ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                    R.anim.search_launch_enter, R.anim.search_launch_exit,
                    getHandler(), this);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent, opts.toBundle());
        } catch (ActivityNotFoundException e) {
            Slog.w(TAG, "Activity not found for " + intent.getAction());
            onAnimationStarted();
        }
    }

    class MultiWaveTriggerListener implements MultiWaveView.OnTriggerListener {
        boolean mWaitingForLaunch;

        public void onGrabbed(View v, int handle) {
        }

        public void onReleased(View v, int handle) {
        }

        public void onGrabbedStateChange(View v, int handle) {
            if (!mWaitingForLaunch && OnTriggerListener.NO_HANDLE == handle) {
                mBar.hideSearchPanel();
            }
        }

        public void onTrigger(View v, final int target) {
            final int resId = mMultiWaveView.getResourceIdForTarget(target);
            switch (resId) {
                case com.android.internal.R.drawable.ic_lockscreen_search:
                    mWaitingForLaunch = true;
                    startAssistActivity();
                    vibrate();
                    break;
            }
        }

        public void onFinishFinalAnimation() {
        }
    }
    final MultiWaveTriggerListener mMultiWaveViewListener = new MultiWaveTriggerListener();

    @Override
    public void onAnimationStarted() {
        postDelayed(new Runnable() {
            public void run() {
                mMultiWaveViewListener.mWaitingForLaunch = false;
                mBar.hideSearchPanel();
            }
        }, SEARCH_PANEL_HOLD_DURATION);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mSearchTargetsContainer = findViewById(R.id.search_panel_container);
        mStatusBarTouchProxy = (StatusBarTouchProxy) findViewById(R.id.status_bar_touch_proxy);
        // TODO: fetch views
        mMultiWaveView = (MultiWaveView) findViewById(R.id.multi_wave_view);
        mMultiWaveView.setOnTriggerListener(mMultiWaveViewListener);
        SearchManager searchManager = getSearchManager();
        if (searchManager != null) {
            ComponentName component = searchManager.getGlobalSearchActivity();
            if (component != null) {
                if (!mMultiWaveView.replaceTargetDrawablesIfPresent(component,
                        ASSIST_ICON_METADATA_NAME,
                        com.android.internal.R.drawable.ic_lockscreen_search)) {
                    Slog.w(TAG, "Couldn't grab icon from component " + component);
                }
            } else {
                Slog.w(TAG, "No search icon specified in component " + component);
            }
        } else {
            Slog.w(TAG, "No SearchManager");
        }
    }

    private boolean pointInside(int x, int y, View v) {
        final int l = v.getLeft();
        final int r = v.getRight();
        final int t = v.getTop();
        final int b = v.getBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    public boolean isInContentArea(int x, int y) {
        if (pointInside(x, y, mSearchTargetsContainer)) {
            return true;
        } else if (mStatusBarTouchProxy != null &&
                pointInside(x, y, mStatusBarTouchProxy)) {
            return true;
        } else {
            return false;
        }
    }

    private final OnPreDrawListener mPreDrawListener = new ViewTreeObserver.OnPreDrawListener() {
        public boolean onPreDraw() {
            getViewTreeObserver().removeOnPreDrawListener(this);
            mMultiWaveView.resumeAnimations();
            return false;
        }
    };

    private void vibrate() {
        Context context = getContext();
        if (Settings.System.getInt(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) {
            Resources res = context.getResources();
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.vibrate(res.getInteger(R.integer.config_search_panel_view_vibration_duration));
        }
    }

    public void show(final boolean show, boolean animate) {
        if (!show) {
            final LayoutTransition transitioner = animate ? createLayoutTransitioner() : null;
            ((ViewGroup) mSearchTargetsContainer).setLayoutTransition(transitioner);
        }
        mShowing = show;
        if (show) {
            if (getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
                // Don't start the animation until we've created the layer, which is done
                // right before we are drawn
                mMultiWaveView.suspendAnimations();
                getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
                vibrate();
            }
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
        } else {
            setVisibility(View.INVISIBLE);
        }
    }

    public void hide(boolean animate) {
        if (mBar != null) {
            // This will indirectly cause show(false, ...) to get called
            mBar.animateCollapse(CommandQueue.FLAG_EXCLUDE_NONE);
        } else {
            setVisibility(View.INVISIBLE);
        }
    }

    /**
     * We need to be aligned at the bottom.  LinearLayout can't do this, so instead,
     * let LinearLayout do all the hard work, and then shift everything down to the bottom.
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // setPanelHeight(mSearchTargetsContainer.getHeight());
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

    /**
     * Whether the panel is showing, or, if it's animating, whether it will be
     * when the animation is done.
     */
    public boolean isShowing() {
        return mShowing;
    }

    public void setBar(BaseStatusBar bar) {
        mBar = bar;
    }

    public void setStatusBarView(final View statusBarView) {
        if (mStatusBarTouchProxy != null) {
            mStatusBarTouchProxy.setStatusBar(statusBarView);
//            mMultiWaveView.setOnTouchListener(new OnTouchListener() {
//                public boolean onTouch(View v, MotionEvent event) {
//                    return statusBarView.onTouchEvent(event);
//                }
//            });
        }
    }

    private LayoutTransition createLayoutTransitioner() {
        LayoutTransition transitioner = new LayoutTransition();
        transitioner.setDuration(200);
        transitioner.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        transitioner.setAnimator(LayoutTransition.DISAPPEARING, null);
        return transitioner;
    }
}
