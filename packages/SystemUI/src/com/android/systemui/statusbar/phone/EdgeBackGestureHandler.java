/**
 * Copyright (C) 2019 The Android Open Source Project
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
import android.content.pm.ParceledListSlice;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.MathUtils;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.IPinnedStackController;
import android.view.IPinnedStackListener;
import android.view.ISystemGestureExclusionListener;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputMonitor;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.systemui.R;
import com.android.systemui.shared.system.InputChannelCompat.InputEventReceiver;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.WindowManagerWrapper;

import java.util.concurrent.Executor;

/**
 * Utility class to handle edge swipes for back gesture
 */
public class EdgeBackGestureHandler implements DisplayListener {

    private static final String TAG = "EdgeBackGestureHandler";

    private final IPinnedStackListener.Stub mImeChangedListener = new IPinnedStackListener.Stub() {
        @Override
        public void onListenerRegistered(IPinnedStackController controller) {
        }

        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            // No need to thread jump, assignments are atomic
            mImeHeight = imeVisible ? imeHeight : 0;
            // TODO: Probably cancel any existing gesture
        }

        @Override
        public void onShelfVisibilityChanged(boolean shelfVisible, int shelfHeight) {
        }

        @Override
        public void onMinimizedStateChanged(boolean isMinimized) {
        }

        @Override
        public void onMovementBoundsChanged(Rect insetBounds, Rect normalBounds,
                Rect animatingBounds, boolean fromImeAdjustment, boolean fromShelfAdjustment,
                int displayRotation) {
        }

        @Override
        public void onActionsChanged(ParceledListSlice actions) {
        }
    };

    private ISystemGestureExclusionListener mGestureExclusionListener =
            new ISystemGestureExclusionListener.Stub() {
                @Override
                public void onSystemGestureExclusionChanged(int displayId,
                        Region systemGestureExclusion) {
                    if (displayId == mDisplayId) {
                        mMainExecutor.execute(() -> mExcludeRegion.set(systemGestureExclusion));
                    }
                }
            };

    private final Context mContext;

    private final Point mDisplaySize = new Point();
    private final int mDisplayId;

    private final Executor mMainExecutor;

    private final Region mExcludeRegion = new Region();
    // The edge width where touch down is allowed
    private final int mEdgeWidth;
    // The slop to distinguish between horizontal and vertical motion
    private final float mTouchSlop;
    // Minimum distance to move so that is can be considerd as a back swipe
    private final float mSwipeThreshold;

    private final int mNavBarHeight;

    private final PointF mDownPoint = new PointF();
    private boolean mThresholdCrossed = false;
    private boolean mIgnoreThisGesture = false;
    private boolean mIsOnLeftEdge;

    private int mImeHeight = 0;

    private boolean mIsAttached;
    private boolean mIsGesturalModeEnabled;
    private boolean mIsEnabled;

    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;

    private final WindowManager mWm;

    private NavigationBarEdgePanel mEdgePanel;
    private WindowManager.LayoutParams mEdgePanelLp;

    public EdgeBackGestureHandler(Context context) {
        mContext = context;
        mDisplayId = context.getDisplayId();
        mMainExecutor = context.getMainExecutor();
        mWm = context.getSystemService(WindowManager.class);

        mEdgeWidth = QuickStepContract.getEdgeSensitivityWidth(context);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mSwipeThreshold = context.getResources()
                .getDimension(R.dimen.navigation_edge_action_drag_threshold);

        mNavBarHeight = context.getResources().getDimensionPixelSize(R.dimen.navigation_bar_height);
    }

    /**
     * @see NavigationBarView#onAttachedToWindow()
     */
    public void onNavBarAttached() {
        mIsAttached = true;
        onOverlaysChanged();
    }

    /**
     * @see NavigationBarView#onDetachedFromWindow()
     */
    public void onNavBarDetached() {
        mIsAttached = false;
        updateIsEnabled();
    }

    /**
     * Called when system overlays has changed
     */
    public void onOverlaysChanged() {
        mIsGesturalModeEnabled = QuickStepContract.isGesturalMode(mContext);
        updateIsEnabled();
    }

    private void disposeInputChannel() {
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
    }

    private void updateIsEnabled() {
        boolean isEnabled = mIsAttached && mIsGesturalModeEnabled;
        if (isEnabled == mIsEnabled) {
            return;
        }
        mIsEnabled = isEnabled;
        disposeInputChannel();

        if (mEdgePanel != null) {
            mWm.removeView(mEdgePanel);
            mEdgePanel = null;
        }

        if (!mIsEnabled) {
            WindowManagerWrapper.getInstance().removePinnedStackListener(mImeChangedListener);

            try {
                WindowManagerGlobal.getWindowManagerService()
                        .unregisterSystemGestureExclusionListener(
                                mGestureExclusionListener, mDisplayId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to unregister window manager callbacks", e);
            }

        } else {
            updateDisplaySize();

            try {
                WindowManagerWrapper.getInstance().addPinnedStackListener(mImeChangedListener);
                WindowManagerGlobal.getWindowManagerService()
                        .registerSystemGestureExclusionListener(
                                mGestureExclusionListener, mDisplayId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to register window manager callbacks", e);
            }

            // Register input event receiver
            mInputMonitor = InputManager.getInstance().monitorGestureInput(
                    "edge-swipe", mDisplayId);
            mInputEventReceiver = new InputEventReceiver(mInputMonitor.getInputChannel(),
                    Looper.getMainLooper(), Choreographer.getMainThreadInstance(),
                    this::onInputEvent);

            // Add a nav bar panel window
            mEdgePanel = new NavigationBarEdgePanel(mContext);
            mEdgePanelLp = new WindowManager.LayoutParams(
                    mContext.getResources()
                            .getDimensionPixelSize(R.dimen.navigation_edge_panel_width),
                    mContext.getResources()
                            .getDimensionPixelSize(R.dimen.navigation_edge_panel_height),
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            mEdgePanelLp.setTitle(TAG + mDisplayId);
            mEdgePanelLp.accessibilityTitle = mContext.getString(R.string.nav_bar_edge_panel);
            mEdgePanelLp.windowAnimations = 0;
            mEdgePanel.setLayoutParams(mEdgePanelLp);
            mWm.addView(mEdgePanel, mEdgePanelLp);
        }
    }

    private void onInputEvent(InputEvent ev) {
        if (ev instanceof MotionEvent) {
            onMotionEvent((MotionEvent) ev);
        }
    }

    private boolean isWithinTouchRegion(int x, int y) {
        if (y > (mDisplaySize.y - Math.max(mImeHeight, mNavBarHeight))) {
            return false;
        }

        if (x > mEdgeWidth && x < (mDisplaySize.x - mEdgeWidth)) {
            return false;
        }
        return !mExcludeRegion.contains(x, y);
    }

    private void onMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            // Verify if this is in within the touch region
            mIgnoreThisGesture = !isWithinTouchRegion((int) ev.getX(), (int) ev.getY());
            if (!mIgnoreThisGesture) {
                mIsOnLeftEdge = ev.getX() < mEdgeWidth;
                mEdgePanelLp.gravity = mIsOnLeftEdge
                        ? (Gravity.LEFT | Gravity.TOP)
                        : (Gravity.RIGHT | Gravity.TOP);
                mEdgePanel.setIsLeftPanel(mIsOnLeftEdge);
                mEdgePanelLp.y = MathUtils.constrain(
                        (int) (ev.getY() - mEdgePanelLp.height / 2),
                        0, mDisplaySize.y);
                mWm.updateViewLayout(mEdgePanel, mEdgePanelLp);

                mDownPoint.set(ev.getX(), ev.getY());
                mThresholdCrossed = false;
                mEdgePanel.handleTouch(ev);
            }
        } else if (!mIgnoreThisGesture) {
            if (!mThresholdCrossed && ev.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = Math.abs(ev.getX() - mDownPoint.x);
                float dy = Math.abs(ev.getY() - mDownPoint.y);
                if (dy > dx && dy > mTouchSlop) {
                    // Send action cancel to reset all the touch events
                    mIgnoreThisGesture = true;
                    MotionEvent cancelEv = MotionEvent.obtain(ev);
                    cancelEv.setAction(MotionEvent.ACTION_CANCEL);
                    mEdgePanel.handleTouch(cancelEv);
                    cancelEv.recycle();
                    return;

                } else if (dx > dy && dx > mTouchSlop) {
                    mThresholdCrossed = true;
                    // Capture inputs
                    mInputMonitor.pilferPointers();
                }
            }

            // forward touch
            mEdgePanel.handleTouch(ev);

            if (ev.getAction() == MotionEvent.ACTION_UP) {
                float xDiff = ev.getX() - mDownPoint.x;
                boolean exceedsThreshold = mIsOnLeftEdge
                        ? (xDiff > mSwipeThreshold) : (-xDiff > mSwipeThreshold);
                if (exceedsThreshold && Math.abs(xDiff) > Math.abs(ev.getY() - mDownPoint.y)) {
                    // Perform back
                    sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
                    sendEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK);
                }
            }
        }
    }

    @Override
    public void onDisplayAdded(int displayId) { }

    @Override
    public void onDisplayRemoved(int displayId) { }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId == mDisplayId) {
            updateDisplaySize();
        }
    }

    private void updateDisplaySize() {
        mContext.getSystemService(DisplayManager.class)
                .getDisplay(mDisplayId).getRealSize(mDisplaySize);
    }

    private void sendEvent(int action, int code) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action, code, 0 /* repeat */,
                0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        InputManager.getInstance().injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }
}
