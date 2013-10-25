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

package com.android.systemui.statusbar.phone;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.util.AttributeSet;
import android.util.EventLog;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import com.android.systemui.EventLogTags;
import com.android.systemui.R;

public class PhoneStatusBarView extends PanelBar {
    private static final String TAG = "PhoneStatusBarView";
    private static final boolean DEBUG = PhoneStatusBar.DEBUG;
    private static final boolean DEBUG_GESTURES = true;

    PhoneStatusBar mBar;
    int mScrimColor;
    float mSettingsPanelDragzoneFrac;
    float mSettingsPanelDragzoneMin;

    boolean mFullWidthNotifications;
    PanelView mFadingPanel = null;
    PanelView mLastFullyOpenedPanel = null;
    PanelView mNotificationPanel, mSettingsPanel;
    private boolean mShouldFade;
    private final PhoneStatusBarTransitions mBarTransitions;

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        Resources res = getContext().getResources();
        mScrimColor = res.getColor(R.color.notification_panel_scrim_color);
        mSettingsPanelDragzoneMin = res.getDimension(R.dimen.settings_panel_dragzone_min);
        try {
            mSettingsPanelDragzoneFrac = res.getFraction(R.dimen.settings_panel_dragzone_fraction, 1, 1);
        } catch (NotFoundException ex) {
            mSettingsPanelDragzoneFrac = 0f;
        }
        mFullWidthNotifications = mSettingsPanelDragzoneFrac <= 0f;
        mBarTransitions = new PhoneStatusBarTransitions(this);
    }

    public BarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    public void setBar(PhoneStatusBar bar) {
        mBar = bar;
    }

    public boolean hasFullWidthNotifications() {
        return mFullWidthNotifications;
    }

    @Override
    public void onAttachedToWindow() {
        for (PanelView pv : mPanels) {
            pv.setRubberbandingEnabled(!mFullWidthNotifications);
        }
        mBarTransitions.init();
    }

    @Override
    public void addPanel(PanelView pv) {
        super.addPanel(pv);
        if (pv.getId() == R.id.notification_panel) {
            mNotificationPanel = pv;
        } else if (pv.getId() == R.id.settings_panel){
            mSettingsPanel = pv;
        }
        pv.setRubberbandingEnabled(!mFullWidthNotifications);
    }

    @Override
    public boolean panelsEnabled() {
        return mBar.panelsEnabled();
    }

    @Override
    public boolean onRequestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEvent(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public PanelView selectPanelForTouch(MotionEvent touch) {
        final float x = touch.getX();
        final boolean isLayoutRtl = isLayoutRtl();

        if (mFullWidthNotifications) {
            // No double swiping. If either panel is open, nothing else can be pulled down.
            return ((mSettingsPanel == null ? 0 : mSettingsPanel.getExpandedHeight())
                        + mNotificationPanel.getExpandedHeight() > 0)
                    ? null
                    : mNotificationPanel;
        }

        // We split the status bar into thirds: the left 2/3 are for notifications, and the
        // right 1/3 for quick settings. If you pull the status bar down a second time you'll
        // toggle panels no matter where you pull it down.

        final float w = getMeasuredWidth();
        float region = (w * mSettingsPanelDragzoneFrac);

        if (DEBUG) {
            Log.v(TAG, String.format(
                "w=%.1f frac=%.3f region=%.1f min=%.1f x=%.1f w-x=%.1f",
                w, mSettingsPanelDragzoneFrac, region, mSettingsPanelDragzoneMin, x, (w-x)));
        }

        if (region < mSettingsPanelDragzoneMin) region = mSettingsPanelDragzoneMin;

        final boolean showSettings = isLayoutRtl ? (x < region) : (w - region < x);
        return showSettings ? mSettingsPanel : mNotificationPanel;
    }

    @Override
    public void onPanelPeeked() {
        super.onPanelPeeked();
        mBar.makeExpandedVisible();
    }

    @Override
    public void startOpeningPanel(PanelView panel) {
        super.startOpeningPanel(panel);
        // we only want to start fading if this is the "first" or "last" panel,
        // which is kind of tricky to determine
        mShouldFade = (mFadingPanel == null || mFadingPanel.isFullyExpanded());
        if (DEBUG) {
            Log.v(TAG, "start opening: " + panel + " shouldfade=" + mShouldFade);
        }
        mFadingPanel = panel;
    }

    @Override
    public void onAllPanelsCollapsed() {
        super.onAllPanelsCollapsed();
        // give animations time to settle
        mBar.makeExpandedInvisibleSoon();
        mFadingPanel = null;
        mLastFullyOpenedPanel = null;
        if (mScrimColor != 0 && ActivityManager.isHighEndGfx()) {
            mBar.mStatusBarWindow.setBackgroundColor(0);
        }
    }

    @Override
    public void onPanelFullyOpened(PanelView openPanel) {
        super.onPanelFullyOpened(openPanel);
        if (openPanel != mLastFullyOpenedPanel) {
            openPanel.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
        mFadingPanel = openPanel;
        mLastFullyOpenedPanel = openPanel;
        mShouldFade = true; // now you own the fade, mister
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean barConsumedEvent = mBar.interceptTouchEvent(event);

        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_PANELBAR_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY(),
                        barConsumedEvent ? 1 : 0);
            }
        }

        return barConsumedEvent || super.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mBar.interceptTouchEvent(event) || super.onInterceptTouchEvent(event);
    }

    @Override
    public void panelExpansionChanged(PanelView panel, float frac) {
        super.panelExpansionChanged(panel, frac);

        if (DEBUG) {
            Log.v(TAG, "panelExpansionChanged: f=" + frac);
        }

        if (panel == mFadingPanel && mScrimColor != 0 && ActivityManager.isHighEndGfx()) {
            if (mShouldFade) {
                frac = mPanelExpandedFractionSum; // don't judge me
                // let's start this 20% of the way down the screen
                frac = frac * 1.2f - 0.2f;
                if (frac <= 0) {
                    mBar.mStatusBarWindow.setBackgroundColor(0);
                } else {
                    // woo, special effects
                    final float k = (float)(1f-0.5f*(1f-Math.cos(3.14159f * Math.pow(1f-frac, 2f))));
                    // attenuate background color alpha by k
                    final int color = (int) ((mScrimColor >>> 24) * k) << 24 | (mScrimColor & 0xFFFFFF);
                    mBar.mStatusBarWindow.setBackgroundColor(color);
                }
            }
        }

        // fade out the panel as it gets buried into the status bar to avoid overdrawing the
        // status bar on the last frame of a close animation
        final int H = mBar.getStatusBarHeight();
        final float ph = panel.getExpandedHeight() + panel.getPaddingBottom();
        float alpha = 1f;
        if (ph < 2*H) {
            if (ph < H) alpha = 0f;
            else alpha = (ph - H) / H;
            alpha = alpha * alpha; // get there faster
        }
        if (panel.getAlpha() != alpha) {
            panel.setAlpha(alpha);
        }

        mBar.animateHeadsUp(mNotificationPanel == panel, mPanelExpandedFractionSum);

        mBar.updateCarrierLabelVisibility(false);
    }
}
