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

import static android.view.Display.INVALID_DISPLAY;

import android.content.Context;
import android.content.res.Resources;
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
import android.os.SystemProperties;
import android.util.Log;
import android.util.MathUtils;
import android.util.StatsLog;
import android.view.Gravity;
import android.view.ISystemGestureExclusionListener;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.system.PinnedStackListenerForwarder.PinnedStackListener;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.WindowManagerWrapper;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

/**
 * Utility class to handle edge swipes for back gesture
 */
public class EdgeBackGestureHandler implements DisplayListener {

    private static final String TAG = "EdgeBackGestureHandler";
    private static final int MAX_LONG_PRESS_TIMEOUT = SystemProperties.getInt(
            "gestures.back_timeout", 250);

    private final PinnedStackListener mImeChangedListener = new PinnedStackListener() {
        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            // No need to thread jump, assignments are atomic
            mImeHeight = imeVisible ? imeHeight : 0;
            // TODO: Probably cancel any existing gesture
        }
    };

    private ISystemGestureExclusionListener mGestureExclusionListener =
            new ISystemGestureExclusionListener.Stub() {
                @Override
                public void onSystemGestureExclusionChanged(int displayId,
                        Region systemGestureExclusion, Region unrestrictedOrNull) {
                    if (displayId == mDisplayId) {
                        mMainExecutor.execute(() -> {
                            mExcludeRegion.set(systemGestureExclusion);
                            mUnrestrictedExcludeRegion.set(unrestrictedOrNull != null
                                    ? unrestrictedOrNull : systemGestureExclusion);
                        });
                    }
                }
            };

    private final Context mContext;
    private final OverviewProxyService mOverviewProxyService;

    private final Point mDisplaySize = new Point();
    private final int mDisplayId;

    private final Executor mMainExecutor;

    private final Region mExcludeRegion = new Region();
    private final Region mUnrestrictedExcludeRegion = new Region();

    // The edge width where touch down is allowed
    private int mEdgeWidth;
    // The slop to distinguish between horizontal and vertical motion
    private final float mTouchSlop;
    // Duration after which we consider the event as longpress.
    private final int mLongPressTimeout;
    // The threshold where the touch needs to be at most, such that the arrow is displayed above the
    // finger, otherwise it will be below
    private final int mMinArrowPosition;
    // The amount by which the arrow is shifted to avoid the finger
    private final int mFingerOffset;


    private final int mNavBarHeight;

    private final PointF mDownPoint = new PointF();
    private boolean mThresholdCrossed = false;
    private boolean mAllowGesture = false;
    private boolean mInRejectedExclusion = false;
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
    private final Rect mSamplingRect = new Rect();
    private RegionSamplingHelper mRegionSamplingHelper;
    private int mLeftInset;
    private int mRightInset;
    private int mSysUiFlags;

    public EdgeBackGestureHandler(Context context, OverviewProxyService overviewProxyService,
            SysUiState sysUiFlagContainer) {
        final Resources res = context.getResources();
        mContext = context;
        mDisplayId = context.getDisplayId();
        mMainExecutor = context.getMainExecutor();
        mWm = context.getSystemService(WindowManager.class);
        mOverviewProxyService = overviewProxyService;

        // Reduce the default touch slop to ensure that we can intercept the gesture
        // before the app starts to react to it.
        // TODO(b/130352502) Tune this value and extract into a constant
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop() * 0.75f;
        mLongPressTimeout = Math.min(MAX_LONG_PRESS_TIMEOUT,
                ViewConfiguration.getLongPressTimeout());

        mNavBarHeight = res.getDimensionPixelSize(R.dimen.navigation_bar_frame_height);
        mMinArrowPosition = res.getDimensionPixelSize(R.dimen.navigation_edge_arrow_min_y);
        mFingerOffset = res.getDimensionPixelSize(R.dimen.navigation_edge_finger_offset);
        updateCurrentUserResources(res);
        sysUiFlagContainer.addCallback(sysUiFlags -> mSysUiFlags = sysUiFlags);
    }

    public void updateCurrentUserResources(Resources res) {
        mEdgeWidth = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_backGestureInset);
    }

    /**
     * @see NavigationBarView#onAttachedToWindow()
     */
    public void onNavBarAttached() {
        mIsAttached = true;
        updateIsEnabled();
    }

    /**
     * @see NavigationBarView#onDetachedFromWindow()
     */
    public void onNavBarDetached() {
        mIsAttached = false;
        updateIsEnabled();
    }

    public void onNavigationModeChanged(int mode, Context currentUserContext) {
        mIsGesturalModeEnabled = QuickStepContract.isGesturalMode(mode);
        updateIsEnabled();
        updateCurrentUserResources(currentUserContext.getResources());
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
            mRegionSamplingHelper.stop();
            mRegionSamplingHelper = null;
        }

        if (!mIsEnabled) {
            WindowManagerWrapper.getInstance().removePinnedStackListener(mImeChangedListener);
            mContext.getSystemService(DisplayManager.class).unregisterDisplayListener(this);

            try {
                WindowManagerGlobal.getWindowManagerService()
                        .unregisterSystemGestureExclusionListener(
                                mGestureExclusionListener, mDisplayId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to unregister window manager callbacks", e);
            }

        } else {
            updateDisplaySize();
            mContext.getSystemService(DisplayManager.class).registerDisplayListener(this,
                    mContext.getMainThreadHandler());

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
            mInputEventReceiver = new SysUiInputEventReceiver(
                    mInputMonitor.getInputChannel(), Looper.getMainLooper());

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
            mEdgePanelLp.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
            mEdgePanelLp.setTitle(TAG + mDisplayId);
            mEdgePanelLp.accessibilityTitle = mContext.getString(R.string.nav_bar_edge_panel);
            mEdgePanelLp.windowAnimations = 0;
            mEdgePanel.setLayoutParams(mEdgePanelLp);
            mWm.addView(mEdgePanel, mEdgePanelLp);
            mRegionSamplingHelper = new RegionSamplingHelper(mEdgePanel,
                    new RegionSamplingHelper.SamplingCallback() {
                        @Override
                        public void onRegionDarknessChanged(boolean isRegionDark) {
                            mEdgePanel.setIsDark(!isRegionDark, true /* animate */);
                        }

                        @Override
                        public Rect getSampledRegion(View sampledView) {
                            return mSamplingRect;
                        }
                    });
            mRegionSamplingHelper.setWindowVisible(true);
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

        if (x > mEdgeWidth + mLeftInset && x < (mDisplaySize.x - mEdgeWidth - mRightInset)) {
            return false;
        }
        boolean isInExcludedRegion = mExcludeRegion.contains(x, y);
        if (isInExcludedRegion) {
            mOverviewProxyService.notifyBackAction(false /* completed */, -1, -1,
                    false /* isButton */, !mIsOnLeftEdge);
            StatsLog.write(StatsLog.BACK_GESTURE_REPORTED_REPORTED,
                    StatsLog.BACK_GESTURE__TYPE__INCOMPLETE_EXCLUDED, y,
                    mIsOnLeftEdge ? StatsLog.BACK_GESTURE__X_LOCATION__LEFT :
                            StatsLog.BACK_GESTURE__X_LOCATION__RIGHT);
        } else {
            mInRejectedExclusion = mUnrestrictedExcludeRegion.contains(x, y);
        }
        return !isInExcludedRegion;
    }

    private void cancelGesture(MotionEvent ev) {
        // Send action cancel to reset all the touch events
        mAllowGesture = false;
        mInRejectedExclusion = false;
        MotionEvent cancelEv = MotionEvent.obtain(ev);
        cancelEv.setAction(MotionEvent.ACTION_CANCEL);
        mEdgePanel.handleTouch(cancelEv);
        cancelEv.recycle();
    }

    private void onMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // Verify if this is in within the touch region and we aren't in immersive mode, and
            // either the bouncer is showing or the notification panel is hidden
            mIsOnLeftEdge = ev.getX() <= mEdgeWidth + mLeftInset;
            mInRejectedExclusion = false;
            mAllowGesture = !QuickStepContract.isBackGestureDisabled(mSysUiFlags)
                    && isWithinTouchRegion((int) ev.getX(), (int) ev.getY());
            if (mAllowGesture) {
                mEdgePanelLp.gravity = mIsOnLeftEdge
                        ? (Gravity.LEFT | Gravity.TOP)
                        : (Gravity.RIGHT | Gravity.TOP);
                mEdgePanel.setIsLeftPanel(mIsOnLeftEdge);
                mEdgePanel.handleTouch(ev);
                updateEdgePanelPosition(ev.getY());
                mWm.updateViewLayout(mEdgePanel, mEdgePanelLp);
                mRegionSamplingHelper.start(mSamplingRect);

                mDownPoint.set(ev.getX(), ev.getY());
                mThresholdCrossed = false;
            }
        } else if (mAllowGesture) {
            if (!mThresholdCrossed) {
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    // We do not support multi touch for back gesture
                    cancelGesture(ev);
                    return;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if ((ev.getEventTime() - ev.getDownTime()) > mLongPressTimeout) {
                        cancelGesture(ev);
                        return;
                    }
                    float dx = Math.abs(ev.getX() - mDownPoint.x);
                    float dy = Math.abs(ev.getY() - mDownPoint.y);
                    if (dy > dx && dy > mTouchSlop) {
                        cancelGesture(ev);
                        return;

                    } else if (dx > dy && dx > mTouchSlop) {
                        mThresholdCrossed = true;
                        // Capture inputs
                        mInputMonitor.pilferPointers();
                    }
                }

            }

            // forward touch
            mEdgePanel.handleTouch(ev);

            boolean isUp = action == MotionEvent.ACTION_UP;
            if (isUp) {
                boolean performAction = mEdgePanel.shouldTriggerBack();
                if (performAction) {
                    // Perform back
                    sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
                    sendEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK);
                }
                mOverviewProxyService.notifyBackAction(performAction, (int) mDownPoint.x,
                        (int) mDownPoint.y, false /* isButton */, !mIsOnLeftEdge);
                int backtype = performAction ? (mInRejectedExclusion
                        ? StatsLog.BACK_GESTURE__TYPE__COMPLETED_REJECTED :
                                StatsLog.BACK_GESTURE__TYPE__COMPLETED) :
                                        StatsLog.BACK_GESTURE__TYPE__INCOMPLETE;
                StatsLog.write(StatsLog.BACK_GESTURE_REPORTED_REPORTED, backtype,
                        (int) mDownPoint.y, mIsOnLeftEdge
                                ? StatsLog.BACK_GESTURE__X_LOCATION__LEFT :
                                StatsLog.BACK_GESTURE__X_LOCATION__RIGHT);
            }
            if (isUp || action == MotionEvent.ACTION_CANCEL) {
                mRegionSamplingHelper.stop();
            } else {
                updateSamplingRect();
                mRegionSamplingHelper.updateSamplingRect();
            }
        }
    }

    private void updateEdgePanelPosition(float touchY) {
        float position = touchY - mFingerOffset;
        position = Math.max(position, mMinArrowPosition);
        position = (position - mEdgePanelLp.height / 2.0f);
        mEdgePanelLp.y = MathUtils.constrain((int) position, 0, mDisplaySize.y);
        updateSamplingRect();
    }

    private void updateSamplingRect() {
        int top = mEdgePanelLp.y;
        int left = mIsOnLeftEdge ? mLeftInset : mDisplaySize.x - mRightInset - mEdgePanelLp.width;
        int right = left + mEdgePanelLp.width;
        int bottom = top + mEdgePanelLp.height;
        mSamplingRect.set(left, top, right, bottom);
        mEdgePanel.adjustRectToBoundingBox(mSamplingRect);
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
                .getDisplay(mDisplayId)
                .getRealSize(mDisplaySize);
    }

    private void sendEvent(int action, int code) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action, code, 0 /* repeat */,
                0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);

        // Bubble controller will give us a valid display id if it should get the back event
        BubbleController bubbleController = Dependency.get(BubbleController.class);
        int bubbleDisplayId = bubbleController.getExpandedDisplayId(mContext);
        if (code == KeyEvent.KEYCODE_BACK && bubbleDisplayId != INVALID_DISPLAY) {
            ev.setDisplayId(bubbleDisplayId);
        }
        InputManager.getInstance().injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    public void setInsets(int leftInset, int rightInset) {
        mLeftInset = leftInset;
        mRightInset = rightInset;
    }

    public void dump(PrintWriter pw) {
        pw.println("EdgeBackGestureHandler:");
        pw.println("  mIsEnabled=" + mIsEnabled);
        pw.println("  mAllowGesture=" + mAllowGesture);
        pw.println("  mInRejectedExclusion" + mInRejectedExclusion);
        pw.println("  mExcludeRegion=" + mExcludeRegion);
        pw.println("  mUnrestrictedExcludeRegion=" + mUnrestrictedExcludeRegion);
        pw.println("  mImeHeight=" + mImeHeight);
        pw.println("  mIsAttached=" + mIsAttached);
        pw.println("  mEdgeWidth=" + mEdgeWidth);
    }

    class SysUiInputEventReceiver extends InputEventReceiver {
        SysUiInputEventReceiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        public void onInputEvent(InputEvent event) {
            EdgeBackGestureHandler.this.onInputEvent(event);
            finishInputEvent(event, true);
        }
    }
}
