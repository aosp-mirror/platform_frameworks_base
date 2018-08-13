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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

public abstract class PanelBar extends FrameLayout {
    public static final boolean DEBUG = false;
    public static final String TAG = PanelBar.class.getSimpleName();
    private static final boolean SPEW = false;
    private boolean mBouncerShowing;
    private boolean mExpanded;
    protected float mPanelFraction;

    public static final void LOG(String fmt, Object... args) {
        if (!DEBUG) return;
        Log.v(TAG, String.format(fmt, args));
    }

    public static final int STATE_CLOSED = 0;
    public static final int STATE_OPENING = 1;
    public static final int STATE_OPEN = 2;

    PanelView mPanel;
    private int mState = STATE_CLOSED;
    private boolean mTracking;

    public void go(int state) {
        if (DEBUG) LOG("go state: %d -> %d", mState, state);
        mState = state;
    }

    public int getState() {
        return mState;
    }

    public PanelBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    public void setPanel(PanelView pv) {
        mPanel = pv;
        pv.setBar(this);
    }

    public void setBouncerShowing(boolean showing) {
        mBouncerShowing = showing;
        int important = showing ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : IMPORTANT_FOR_ACCESSIBILITY_AUTO;

        setImportantForAccessibility(important);
        updateVisibility();

        if (mPanel != null) mPanel.setImportantForAccessibility(important);
    }

    public float getExpansionFraction() {
        return mPanelFraction;
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    private void updateVisibility() {
        mPanel.setVisibility(mExpanded || mBouncerShowing ? VISIBLE : INVISIBLE);
    }

    public boolean panelEnabled() {
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Allow subclasses to implement enable/disable semantics
        if (!panelEnabled()) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                Log.v(TAG, String.format("onTouch: all panels disabled, ignoring touch at (%d,%d)",
                        (int) event.getX(), (int) event.getY()));
            }
            return false;
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            final PanelView panel = mPanel;
            if (panel == null) {
                // panel is not there, so we'll eat the gesture
                Log.v(TAG, String.format("onTouch: no panel for touch at (%d,%d)",
                        (int) event.getX(), (int) event.getY()));
                return true;
            }
            boolean enabled = panel.isEnabled();
            if (DEBUG) LOG("PanelBar.onTouch: state=%d ACTION_DOWN: panel %s %s", mState, panel,
                    (enabled ? "" : " (disabled)"));
            if (!enabled) {
                // panel is disabled, so we'll eat the gesture
                Log.v(TAG, String.format(
                        "onTouch: panel (%s) is disabled, ignoring touch at (%d,%d)",
                        panel, (int) event.getX(), (int) event.getY()));
                return true;
            }
        }
        return mPanel == null || mPanel.onTouchEvent(event);
    }

    public abstract void panelScrimMinFractionChanged(float minFraction);

    /**
     * @param frac the fraction from the expansion in [0, 1]
     * @param expanded whether the panel is currently expanded; this is independent from the
     *                 fraction as the panel also might be expanded if the fraction is 0
     */
    public void panelExpansionChanged(float frac, boolean expanded) {
        boolean fullyClosed = true;
        boolean fullyOpened = false;
        if (SPEW) LOG("panelExpansionChanged: start state=%d", mState);
        PanelView pv = mPanel;
        mExpanded = expanded;
        mPanelFraction = frac;
        updateVisibility();
        // adjust any other panels that may be partially visible
        if (expanded) {
            if (mState == STATE_CLOSED) {
                go(STATE_OPENING);
                onPanelPeeked();
            }
            fullyClosed = false;
            final float thisFrac = pv.getExpandedFraction();
            if (SPEW) LOG("panelExpansionChanged:  -> %s: f=%.1f", pv.getName(), thisFrac);
            fullyOpened = thisFrac >= 1f;
        }
        if (fullyOpened && !mTracking) {
            go(STATE_OPEN);
            onPanelFullyOpened();
        } else if (fullyClosed && !mTracking && mState != STATE_CLOSED) {
            go(STATE_CLOSED);
            onPanelCollapsed();
        }

        if (SPEW) LOG("panelExpansionChanged: end state=%d [%s%s ]", mState,
                fullyOpened?" fullyOpened":"", fullyClosed?" fullyClosed":"");
    }

    public void collapsePanel(boolean animate, boolean delayed, float speedUpFactor) {
        boolean waiting = false;
        PanelView pv = mPanel;
        if (animate && !pv.isFullyCollapsed()) {
            pv.collapse(delayed, speedUpFactor);
            waiting = true;
        } else {
            pv.resetViews();
            pv.setExpandedFraction(0); // just in case
            pv.cancelPeek();
        }
        if (DEBUG) LOG("collapsePanel: animate=%s waiting=%s", animate, waiting);
        if (!waiting && mState != STATE_CLOSED) {
            // it's possible that nothing animated, so we replicate the termination
            // conditions of panelExpansionChanged here
            go(STATE_CLOSED);
            onPanelCollapsed();
        }
    }

    public void onPanelPeeked() {
        if (DEBUG) LOG("onPanelPeeked");
    }

    public boolean isClosed() {
        return mState == STATE_CLOSED;
    }

    public void onPanelCollapsed() {
        if (DEBUG) LOG("onPanelCollapsed");
    }

    public void onPanelFullyOpened() {
        if (DEBUG) LOG("onPanelFullyOpened");
    }

    public void onTrackingStarted() {
        mTracking = true;
    }

    public void onTrackingStopped(boolean expand) {
        mTracking = false;
    }

    public void onExpandingFinished() {
        if (DEBUG) LOG("onExpandingFinished");
    }

    public void onClosingFinished() {

    }
}
