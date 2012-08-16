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
import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.policy.FixedSizeDrawable;

public class PhoneStatusBarView extends PanelBar {
    private static final String TAG = "PhoneStatusBarView";
    PhoneStatusBar mBar;
    int mScrimColor;
    PanelView mFadingPanel = null;
    PanelView mNotificationPanel, mSettingsPanel;

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setBar(PhoneStatusBar bar) {
        mBar = bar;
    }

    @Override
    public void onAttachedToWindow() {
        Resources res = getContext().getResources();
        mScrimColor = res.getColor(R.color.notification_panel_scrim_color);
    }

    @Override
    public void addPanel(PanelView pv) {
        super.addPanel(pv);
        if (pv.getId() == R.id.notification_panel) {
            mNotificationPanel = pv;
        } else if (pv.getId() == R.id.settings_panel){
            mSettingsPanel = pv;
        }
    }

    @Override
    public boolean panelsEnabled() {
        return ((mBar.mDisabled & StatusBarManager.DISABLE_EXPAND) == 0);
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
    public PanelView selectPanelForTouchX(float x) {
        // We split the status bar into thirds: the left 2/3 are for notifications, and the 
        // right 1/3 for quick settings. If you pull the status bar down a second time you'll
        // toggle panels no matter where you pull it down.
        final float w = (float) getMeasuredWidth();
        final float f = x / w;
        if (f > 0.67f && mSettingsPanel.getExpandedFraction() != 1.0f
                || mNotificationPanel.getExpandedFraction() == 1.0f) {
            return mSettingsPanel;
        }
        return mNotificationPanel;
    }

    @Override
    public void onPanelPeeked() {
        super.onPanelPeeked();
        mBar.makeExpandedVisible(true);
        if (mFadingPanel == null) {
            mFadingPanel = mTouchingPanel;
        }
    }

    @Override
    public void onAllPanelsCollapsed() {
        super.onAllPanelsCollapsed();
        mBar.makeExpandedInvisible();
        mFadingPanel = null;
    }

    @Override
    public void onPanelFullyOpened(PanelView openPanel) {
        mFadingPanel = openPanel;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return mBar.interceptTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mBar.interceptTouchEvent(event) || super.onInterceptTouchEvent(event);
    }

    @Override
    public void panelExpansionChanged(PanelView pv, float frac) {
        super.panelExpansionChanged(pv, frac);

        if (mFadingPanel == pv 
                && mScrimColor != 0 && ActivityManager.isHighEndGfx(mBar.mDisplay)) {
            // woo, special effects
            final float k = (float)(1f-0.5f*(1f-Math.cos(3.14159f * Math.pow(1f-frac, 2.2f))));
            // attenuate background color alpha by k
            final int color = (int) ((float)(mScrimColor >>> 24) * k) << 24 | (mScrimColor & 0xFFFFFF);
            mBar.mStatusBarWindow.setBackgroundColor(color);
        }

        mBar.updateCarrierLabelVisibility(false);
    }


}
