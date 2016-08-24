/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.tv.pip;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;

import com.android.systemui.R;
import com.android.systemui.recents.misc.SystemServicesProxy;

import static android.view.Gravity.CENTER_HORIZONTAL;
import static android.view.Gravity.TOP;
import static android.view.View.MeasureSpec.UNSPECIFIED;
import static com.android.systemui.tv.pip.PipManager.STATE_PIP_OVERLAY;
import static com.android.systemui.tv.pip.PipManager.STATE_PIP_RECENTS;
import static com.android.systemui.tv.pip.PipManager.STATE_PIP_RECENTS_FOCUSED;

public class PipRecentsOverlayManager {
    private static final String TAG = "PipRecentsOverlayManager";

    public interface Callback {
        void onClosed();
        void onBackPressed();
        void onRecentsFocused();
    }

    private final PipManager mPipManager = PipManager.getInstance();
    private final WindowManager mWindowManager;
    private final SystemServicesProxy mSystemServicesProxy;
    private View mOverlayView;
    private PipRecentsControlsView mPipControlsView;
    private View mRecentsView;
    private boolean mTalkBackEnabled;

    private LayoutParams mPipRecentsControlsViewLayoutParams;
    private LayoutParams mPipRecentsControlsViewFocusedLayoutParams;

    private boolean mHasFocusableInRecents;
    private boolean mIsPipRecentsOverlayShown;
    private boolean mIsRecentsShown;
    private boolean mIsPipFocusedInRecent;
    private Callback mCallback;
    private PipRecentsControlsView.Listener mPipControlsViewListener =
            new PipRecentsControlsView.Listener() {
                @Override
                public void onClosed() {
                    if (mCallback != null) {
                        mCallback.onClosed();
                    }
                }

                @Override
                public void onBackPressed() {
                    if (mCallback != null) {
                        mCallback.onBackPressed();
                    }
                }
            };

    PipRecentsOverlayManager(Context context) {
        mWindowManager = (WindowManager) context.getSystemService(WindowManager.class);
        mSystemServicesProxy = SystemServicesProxy.getInstance(context);
        initViews(context);
    }

    private void initViews(Context context) {
        LayoutInflater inflater = (LayoutInflater) context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mOverlayView = inflater.inflate(R.layout.tv_pip_recents_overlay, null);
        mPipControlsView = (PipRecentsControlsView) mOverlayView.findViewById(R.id.pip_controls);
        mRecentsView = mOverlayView.findViewById(R.id.recents);
        mRecentsView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    clearFocus();
                }
            }
        });

        mOverlayView.measure(UNSPECIFIED, UNSPECIFIED);
        mPipRecentsControlsViewLayoutParams = new WindowManager.LayoutParams(
                mOverlayView.getMeasuredWidth(), mOverlayView.getMeasuredHeight(),
                LayoutParams.TYPE_SYSTEM_DIALOG,
                LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        mPipRecentsControlsViewLayoutParams.gravity = TOP | CENTER_HORIZONTAL;
        mPipRecentsControlsViewFocusedLayoutParams = new WindowManager.LayoutParams(
                mOverlayView.getMeasuredWidth(), mOverlayView.getMeasuredHeight(),
                LayoutParams.TYPE_SYSTEM_DIALOG,
                0,
                PixelFormat.TRANSLUCENT);
        mPipRecentsControlsViewFocusedLayoutParams.gravity = TOP | CENTER_HORIZONTAL;
    }

    /**
     * Add Recents overlay view.
     * This is expected to be called after the PIP animation is over.
     */
    void addPipRecentsOverlayView() {
        if (mIsPipRecentsOverlayShown) {
            return;
        }
        mTalkBackEnabled = mSystemServicesProxy.isTouchExplorationEnabled();
        mRecentsView.setVisibility(mTalkBackEnabled ? View.VISIBLE : View.GONE);
        mIsPipRecentsOverlayShown = true;
        mIsPipFocusedInRecent = true;
        mWindowManager.addView(mOverlayView, mPipRecentsControlsViewFocusedLayoutParams);
    }

    /**
     * Remove Recents overlay view.
     * This should be called when Recents or PIP is closed.
     */
    public void removePipRecentsOverlayView() {
        if (!mIsPipRecentsOverlayShown) {
            return;
        }
        mWindowManager.removeView(mOverlayView);
        // Resets the controls view when its removed.
        // If not, changing focus in reset will be show animation when Recents is resumed.
        mPipControlsView.reset();
        mIsPipRecentsOverlayShown = false;
    }

    /**
     * Request focus to the PIP Recents overlay.
     * This should be called only by {@link com.android.systemui.recents.tv.RecentsTvActivity}.
     * @param hasFocusableInRecents {@code true} if Recents can have focus. (i.e. Has a recent task)
     */
    public void requestFocus(boolean hasFocusableInRecents) {
        mHasFocusableInRecents = hasFocusableInRecents;
        if (!mIsPipRecentsOverlayShown || !mIsRecentsShown || mIsPipFocusedInRecent
                || !mPipManager.isPipShown()) {
            return;
        }
        mIsPipFocusedInRecent = true;
        mPipControlsView.startFocusGainAnimation();
        mWindowManager.updateViewLayout(mOverlayView, mPipRecentsControlsViewFocusedLayoutParams);
        mPipManager.resizePinnedStack(STATE_PIP_RECENTS_FOCUSED);
        if (mTalkBackEnabled) {
            mPipControlsView.requestFocus();
            mPipControlsView.sendAccessibilityEvent(
                    AccessibilityEvent.TYPE_VIEW_FOCUSED);
        }
    }

    /**
     * Request focus to the PIP Recents overlay.
     */
    public void clearFocus() {
        if (!mIsPipRecentsOverlayShown || !mIsRecentsShown || !mIsPipFocusedInRecent
                || !mPipManager.isPipShown() || !mHasFocusableInRecents) {
            return;
        }
        mIsPipFocusedInRecent = false;
        mPipControlsView.startFocusLossAnimation();
        mWindowManager.updateViewLayout(mOverlayView, mPipRecentsControlsViewLayoutParams);
        mPipManager.resizePinnedStack(STATE_PIP_RECENTS);
        if (mCallback != null) {
            mCallback.onRecentsFocused();
        }
    }

    public void setCallback(Callback listener) {
        mCallback = listener;
        mPipControlsView.setListener(mCallback != null ? mPipControlsViewListener : null);
    }

    /**
     * Called when Recents is resumed.
     * PIPed activity will be resized accordingly and overlay will show available buttons.
     */
    public void onRecentsResumed() {
        if (!mPipManager.isPipShown()) {
            return;
        }
        mIsRecentsShown = true;
        mIsPipFocusedInRecent = true;
        mPipManager.resizePinnedStack(STATE_PIP_RECENTS_FOCUSED);
        // Overlay view will be added after the resize animation ends, if any.
    }

    /**
     * Called when Recents is paused.
     * PIPed activity will be resized accordingly and overlay will hide available buttons.
     */
    public void onRecentsPaused() {
        mIsRecentsShown = false;
        mIsPipFocusedInRecent = false;
        removePipRecentsOverlayView();

        if (mPipManager.isPipShown()) {
            mPipManager.resizePinnedStack(STATE_PIP_OVERLAY);
        }
    }

    /**
     * Returns {@code true} if recents is shown.
     */
    boolean isRecentsShown() {
        return mIsRecentsShown;
    }

    /**
     * Updates the PIP per configuration changed.
     */
    void onConfigurationChanged(Context context) {
        if (mIsRecentsShown) {
            Log.w(TAG, "Configuration is changed while Recents is shown");
        }
        initViews(context);
    }
}
