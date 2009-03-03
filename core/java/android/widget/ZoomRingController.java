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

package android.widget;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;

// TODO: make sure no px values exist, only dip (scale if necessary from Viewconfiguration)

/**
 * TODO: Docs
 *
 * If you are using this with a custom View, please call
 * {@link #setVisible(boolean) setVisible(false)} from the
 * {@link View#onDetachedFromWindow}.
 *
 * @hide
 */
public class ZoomRingController implements ZoomRing.OnZoomRingCallback,
        View.OnTouchListener, View.OnKeyListener {

    private static final int ZOOM_RING_RADIUS_INSET = 24;

    private static final int ZOOM_RING_RECENTERING_DURATION = 500;

    private static final String TAG = "ZoomRing";

    public static final boolean USE_OLD_ZOOM = false;
    static int getZoomType(Context context) {
        return Settings.System.getInt(context.getContentResolver(), "zoom", 1);
    }
    public static boolean useOldZoom(Context context) {
        return getZoomType(context) == 0;
    }
    private static boolean useThisZoom(Context context) {
        return getZoomType(context) == 1;
    }

    private static final int ZOOM_CONTROLS_TIMEOUT =
            (int) ViewConfiguration.getZoomControlsTimeout();

    // TODO: move these to ViewConfiguration or re-use existing ones
    // TODO: scale px values based on latest from ViewConfiguration
    private static final int SECOND_TAP_TIMEOUT = 500;
    private static final int ZOOM_RING_DISMISS_DELAY = SECOND_TAP_TIMEOUT / 2;
    // TODO: view config?  at least scaled
    private static final int MAX_PAN_GAP = 20;
    private static final int MAX_INITIATE_PAN_GAP = 10;
    // TODO view config
    private static final int INITIATE_PAN_DELAY = 300;

    private static final String SETTING_NAME_SHOWN_TOAST = "shown_zoom_ring_toast";

    private Context mContext;
    private WindowManager mWindowManager;

    /**
     * The view that is being zoomed by this zoom ring.
     */
    private View mOwnerView;

    /**
     * The bounds of the owner view in global coordinates. This is recalculated
     * each time the zoom ring is shown.
     */
    private Rect mOwnerViewBounds = new Rect();

    /**
     * The container that is added as a window.
     */
    private FrameLayout mContainer;
    private LayoutParams mContainerLayoutParams;

    /**
     * The view (or null) that should receive touch events. This will get set if
     * the touch down hits the container. It will be reset on the touch up.
     */
    private View mTouchTargetView;
    /**
     * The {@link #mTouchTargetView}'s location in window, set on touch down.
     */
    private int[] mTouchTargetLocationInWindow = new int[2];
    /**
     * If the zoom ring is dismissed but the user is still in a touch
     * interaction, we set this to true. This will ignore all touch events until
     * up/cancel, and then set the owner's touch listener to null.
     */
    private boolean mReleaseTouchListenerOnUp;


    /*
     * Tap-drag is an interaction where the user first taps and then (quickly)
     * does the clockwise or counter-clockwise drag. In reality, this is: (down,
     * up, down, move in circles, up). This differs from the usual events of:
     * (down, up, down, up, down, move in circles, up). While the only
     * difference is the omission of an (up, down), for power-users this is a
     * pretty big improvement as it now only requires them to focus on the
     * screen once (for the first tap down) instead of twice (for the first tap
     * down and then to grab the thumb).
     */
    private int mTapDragStartX;
    private int mTapDragStartY;

    private static final int TOUCH_MODE_IDLE = 0;
    private static final int TOUCH_MODE_WAITING_FOR_SECOND_TAP = 1;
    private static final int TOUCH_MODE_WAITING_FOR_TAP_DRAG_MOVEMENT = 2;
    private static final int TOUCH_MODE_FORWARDING_FOR_TAP_DRAG = 3;
    private int mTouchMode;

    private boolean mIsZoomRingVisible;

    private ZoomRing mZoomRing;
    private int mZoomRingWidth;
    private int mZoomRingHeight;

    /** Invokes panning of owner view if the zoom ring is touching an edge. */
    private Panner mPanner;
    private long mTouchingEdgeStartTime;
    private boolean mPanningEnabledForThisInteraction;

    /**
     * When the finger moves the zoom ring to an edge, this is the horizontal
     * accumulator for how much the finger has moved off of its original touch
     * point on the zoom ring (OOB = out-of-bounds). If < 0, the finger has
     * moved that many px to the left of its original touch point on the ring.
     */
    private int mMovingZoomRingOobX;
    /** Vertical accumulator, see {@link #mMovingZoomRingOobX} */
    private int mMovingZoomRingOobY;

    private ImageView mPanningArrows;
    private Animation mPanningArrowsEnterAnimation;
    private Animation mPanningArrowsExitAnimation;

    private Rect mTempRect = new Rect();

    private OnZoomListener mCallback;

    private ViewConfiguration mViewConfig;

    /**
     * When the zoom ring is centered on screen, this will be the x value used
     * for the container's layout params.
     */
    private int mCenteredContainerX = Integer.MIN_VALUE;

    /**
     * When the zoom ring is centered on screen, this will be the y value used
     * for the container's layout params.
     */
    private int mCenteredContainerY = Integer.MIN_VALUE;

    /**
     * Scroller used to re-center the zoom ring if the user had dragged it to a
     * corner and then double-taps any point on the owner view (the owner view
     * will center the double-tapped point, but we should re-center the zoom
     * ring).
     * <p>
     * The (x,y) of the scroller is the (x,y) of the container's layout params.
     */
    private Scroller mScroller;

    /**
     * When showing the zoom ring, we add the view as a new window. However,
     * there is logic that needs to know the size of the zoom ring which is
     * determined after it's laid out. Therefore, we must post this logic onto
     * the UI thread so it will be exceuted AFTER the layout. This is the logic.
     */
    private Runnable mPostedVisibleInitializer;

    /**
     * Only touch from the main thread.
     */
    private static Dialog sTutorialDialog;
    private static long sTutorialShowTime;
    private static final int TUTORIAL_MIN_DISPLAY_TIME = 2000;

    private IntentFilter mConfigurationChangedFilter =
            new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);

    private BroadcastReceiver mConfigurationChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mIsZoomRingVisible) return;

            mHandler.removeMessages(MSG_POST_CONFIGURATION_CHANGED);
            mHandler.sendEmptyMessage(MSG_POST_CONFIGURATION_CHANGED);
        }
    };

    /** Keeps the scroller going (or starts it). */
    private static final int MSG_SCROLLER_TICK = 1;
    /** When configuration changes, this is called after the UI thread is idle. */
    private static final int MSG_POST_CONFIGURATION_CHANGED = 2;
    /** Used to delay the zoom ring dismissal. */
    private static final int MSG_DISMISS_ZOOM_RING = 3;

    /**
     * If setVisible(true) is called and the owner view's window token is null,
     * we delay the setVisible(true) call until it is not null.
     */
    private static final int MSG_POST_SET_VISIBLE = 4;
    
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SCROLLER_TICK:
                    onScrollerTick();
                    break;

                case MSG_POST_CONFIGURATION_CHANGED:
                    onPostConfigurationChanged();
                    break;

                case MSG_DISMISS_ZOOM_RING:
                    setVisible(false);
                    break;
                    
                case MSG_POST_SET_VISIBLE:
                    if (mOwnerView.getWindowToken() == null) {
                        // Doh, it is still null, throw an exception
                        throw new IllegalArgumentException(
                                "Cannot make the zoom ring visible if the owner view is " +
                                "not attached to a window.");
                    }
                    setVisible(true);
                    break;
            }

        }
    };

    public ZoomRingController(Context context, View ownerView) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        mPanner = new Panner();
        mOwnerView = ownerView;

        mZoomRing = new ZoomRing(context);
        mZoomRing.setId(com.android.internal.R.id.zoomControls);
        mZoomRing.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        mZoomRing.setCallback(this);

        createPanningArrows();

        mContainerLayoutParams = new LayoutParams();
        mContainerLayoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        mContainerLayoutParams.flags = LayoutParams.FLAG_NOT_TOUCHABLE |
                LayoutParams.FLAG_NOT_FOCUSABLE |
                LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mContainerLayoutParams.height = LayoutParams.WRAP_CONTENT;
        mContainerLayoutParams.width = LayoutParams.WRAP_CONTENT;
        mContainerLayoutParams.type = LayoutParams.TYPE_APPLICATION_PANEL;
        mContainerLayoutParams.format = PixelFormat.TRANSPARENT;
        // TODO: make a new animation for this
        mContainerLayoutParams.windowAnimations = com.android.internal.R.style.Animation_Dialog;

        mContainer = new FrameLayout(context);
        mContainer.setLayoutParams(mContainerLayoutParams);
        mContainer.setMeasureAllChildren(true);

        mContainer.addView(mZoomRing);
        mContainer.addView(mPanningArrows);

        mScroller = new Scroller(context, new DecelerateInterpolator());

        mViewConfig = ViewConfiguration.get(context);
    }

    private void createPanningArrows() {
        // TODO: style
        mPanningArrows = new ImageView(mContext);
        mPanningArrows.setImageResource(com.android.internal.R.drawable.zoom_ring_arrows);
        mPanningArrows.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        mPanningArrows.setVisibility(View.INVISIBLE);

        mPanningArrowsEnterAnimation = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.fade_in);
        mPanningArrowsExitAnimation = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.fade_out);
    }

    /**
     * Sets the angle (in radians) a user must travel in order for the client to
     * get a callback. Once there is a callback, the accumulator resets. For
     * example, if you set this to PI/6, it will give a callback every time the
     * user moves PI/6 amount on the ring.
     *
     * @param callbackThreshold The angle for the callback threshold, in radians
     */
    public void setZoomCallbackThreshold(float callbackThreshold) {
        mZoomRing.setCallbackThreshold((int) (callbackThreshold * ZoomRing.RADIAN_INT_MULTIPLIER));
    }

    /**
     * Sets a drawable for the zoom ring track.
     *
     * @param drawable The drawable to use for the track.
     * @hide Need a better way of doing this, but this one-off for browser so it
     *       can have its final look for the usability study
     */
    public void setZoomRingTrack(int drawable) {
        mZoomRing.setBackgroundResource(drawable);
    }

    public void setCallback(OnZoomListener callback) {
        mCallback = callback;
    }

    public void setThumbAngle(float angle) {
        mZoomRing.setThumbAngle((int) (angle * ZoomRing.RADIAN_INT_MULTIPLIER));
    }

    public void setThumbAngleAnimated(float angle) {
        mZoomRing.setThumbAngleAnimated((int) (angle * ZoomRing.RADIAN_INT_MULTIPLIER), 0);
    }

    public void setResetThumbAutomatically(boolean resetThumbAutomatically) {
        mZoomRing.setResetThumbAutomatically(resetThumbAutomatically);
    }

    public void setVibration(boolean vibrate) {
        mZoomRing.setVibration(vibrate);
    }
    
    public void setThumbVisible(boolean thumbVisible) {
        mZoomRing.setThumbVisible(thumbVisible);
    }
    
    public void setThumbClockwiseBound(float angle) {
        mZoomRing.setThumbClockwiseBound(angle >= 0 ?
                (int) (angle * ZoomRing.RADIAN_INT_MULTIPLIER) :
                Integer.MIN_VALUE);
    }

    public void setThumbCounterclockwiseBound(float angle) {
        mZoomRing.setThumbCounterclockwiseBound(angle >= 0 ?
                (int) (angle * ZoomRing.RADIAN_INT_MULTIPLIER) :
                Integer.MIN_VALUE);
    }

    public boolean isVisible() {
        return mIsZoomRingVisible;
    }

    public void setVisible(boolean visible) {

        if (!useThisZoom(mContext)) return;
        
        if (visible) {
            if (mOwnerView.getWindowToken() == null) {
                /*
                 * We need a window token to show ourselves, maybe the owner's
                 * window hasn't been created yet but it will have been by the
                 * time the looper is idle, so post the setVisible(true) call.
                 */
                if (!mHandler.hasMessages(MSG_POST_SET_VISIBLE)) {
                    mHandler.sendEmptyMessage(MSG_POST_SET_VISIBLE);
                }
                return;
            }
            
            dismissZoomRingDelayed(ZOOM_CONTROLS_TIMEOUT);
        } else {
            mPanner.stop();
        }

        if (mIsZoomRingVisible == visible) {
            return;
        }
        mIsZoomRingVisible = visible;

        if (visible) {
            if (mContainerLayoutParams.token == null) {
                mContainerLayoutParams.token = mOwnerView.getWindowToken();
            }

            mWindowManager.addView(mContainer, mContainerLayoutParams);

            if (mPostedVisibleInitializer == null) {
                mPostedVisibleInitializer = new Runnable() {
                    public void run() {
                        refreshPositioningVariables();
                        resetZoomRing();

                        // TODO: remove this 'update' and just center zoom ring before the
                        // 'add', but need to make sure we have the width and height (which
                        // probably can only be retrieved after it's measured, which happens
                        // after it's added).
                        mWindowManager.updateViewLayout(mContainer, mContainerLayoutParams);

                        if (mCallback != null) {
                            mCallback.onVisibilityChanged(true);
                        }
                    }
                };
            }

            mPanningArrows.setAnimation(null);

            mHandler.post(mPostedVisibleInitializer);

            // Handle configuration changes when visible
            mContext.registerReceiver(mConfigurationChangedReceiver, mConfigurationChangedFilter);

            // Steal key/touches events from the owner
            mOwnerView.setOnKeyListener(this);
            mOwnerView.setOnTouchListener(this);
            mReleaseTouchListenerOnUp = false;

        } else {
            // Don't want to steal any more keys/touches
            mOwnerView.setOnKeyListener(null);
            if (mTouchTargetView != null) {
                // We are still stealing the touch events for this touch
                // sequence, so release the touch listener later
                mReleaseTouchListenerOnUp = true;
            } else {
                mOwnerView.setOnTouchListener(null);
            }

            // No longer care about configuration changes
            mContext.unregisterReceiver(mConfigurationChangedReceiver);

            mWindowManager.removeView(mContainer);
            mHandler.removeCallbacks(mPostedVisibleInitializer);

            if (mCallback != null) {
                mCallback.onVisibilityChanged(false);
            }
        }

    }

    /**
     * TODO: docs
     *
     * Notes:
     * - Touch dispatching is different.  Only direct children who are clickable are eligble for touch events.
     * - Please ensure you set your View to INVISIBLE not GONE when hiding it.
     *
     * @return
     */
    public FrameLayout getContainer() {
        return mContainer;
    }

    public int getZoomRingId() {
        return mZoomRing.getId();
    }

    private void dismissZoomRingDelayed(int delay) {
        mHandler.removeMessages(MSG_DISMISS_ZOOM_RING);
        mHandler.sendEmptyMessageDelayed(MSG_DISMISS_ZOOM_RING, delay);
    }

    private void resetZoomRing() {
        mScroller.abortAnimation();

        mContainerLayoutParams.x = mCenteredContainerX;
        mContainerLayoutParams.y = mCenteredContainerY;

        // Reset the thumb
        mZoomRing.resetThumbAngle();
    }

    /**
     * Should be called by the client for each event belonging to the second tap
     * (the down, move, up, and cancel events).
     *
     * @param event The event belonging to the second tap.
     * @return Whether the event was consumed.
     */
    public boolean handleDoubleTapEvent(MotionEvent event) {
        if (!useThisZoom(mContext)) return false;
        
        int action = event.getAction();

        // TODO: make sure this works well with the
        // ownerView.setOnTouchListener(this) instead of window receiving
        // touches
        if (action == MotionEvent.ACTION_DOWN) {
            mTouchMode = TOUCH_MODE_WAITING_FOR_TAP_DRAG_MOVEMENT;
            int x = (int) event.getX();
            int y = (int) event.getY();

            refreshPositioningVariables();
            setVisible(true);
            centerPoint(x, y);
            ensureZoomRingIsCentered();

            // Tap drag mode stuff
            mTapDragStartX = x;
            mTapDragStartY = y;

        } else if (action == MotionEvent.ACTION_CANCEL) {
            mTouchMode = TOUCH_MODE_IDLE;

        } else { // action is move or up
            switch (mTouchMode) {
                case TOUCH_MODE_WAITING_FOR_TAP_DRAG_MOVEMENT: {
                    switch (action) {
                        case MotionEvent.ACTION_MOVE:
                            int x = (int) event.getX();
                            int y = (int) event.getY();
                            if (Math.abs(x - mTapDragStartX) > mViewConfig.getScaledTouchSlop() ||
                                    Math.abs(y - mTapDragStartY) >
                                    mViewConfig.getScaledTouchSlop()) {
                                mZoomRing.setTapDragMode(true, x, y);
                                mTouchMode = TOUCH_MODE_FORWARDING_FOR_TAP_DRAG;
                                setTouchTargetView(mZoomRing);
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            mTouchMode = TOUCH_MODE_IDLE;
                            break;
                    }
                    break;
                }

                case TOUCH_MODE_FORWARDING_FOR_TAP_DRAG: {
                    switch (action) {
                        case MotionEvent.ACTION_MOVE:
                            giveTouchToZoomRing(event);
                            return true;

                        case MotionEvent.ACTION_UP:
                            mTouchMode = TOUCH_MODE_IDLE;

                            /*
                             * This is a power-user feature that only shows the
                             * zoom while the user is performing the tap-drag.
                             * That means once it is released, the zoom ring
                             * should disappear.
                             */
                            mZoomRing.setTapDragMode(false, (int) event.getX(), (int) event.getY());
                            dismissZoomRingDelayed(0);
                            break;
                    }
                    break;
                }
            }
        }

        return true;
    }

    private void ensureZoomRingIsCentered() {
        LayoutParams lp = mContainerLayoutParams;

        if (lp.x != mCenteredContainerX || lp.y != mCenteredContainerY) {
            int width = mContainer.getWidth();
            int height = mContainer.getHeight();
            mScroller.startScroll(lp.x, lp.y, mCenteredContainerX - lp.x,
                    mCenteredContainerY - lp.y, ZOOM_RING_RECENTERING_DURATION);
            mHandler.sendEmptyMessage(MSG_SCROLLER_TICK);
        }
    }

    private void refreshPositioningVariables() {
        mZoomRingWidth = mZoomRing.getWidth();
        mZoomRingHeight = mZoomRing.getHeight();

        // Calculate the owner view's bounds
        mOwnerView.getGlobalVisibleRect(mOwnerViewBounds);

        // Get the center
        Gravity.apply(Gravity.CENTER, mContainer.getWidth(), mContainer.getHeight(),
                mOwnerViewBounds, mTempRect);
        mCenteredContainerX = mTempRect.left;
        mCenteredContainerY = mTempRect.top;
    }

    /**
     * Centers the point (in owner view's coordinates).
     */
    private void centerPoint(int x, int y) {
        if (mCallback != null) {
            mCallback.onCenter(x, y);
        }
    }

    private void giveTouchToZoomRing(MotionEvent event) {
        int rawX = (int) event.getRawX();
        int rawY = (int) event.getRawY();
        int x = rawX - mContainerLayoutParams.x - mZoomRing.getLeft();
        int y = rawY - mContainerLayoutParams.y - mZoomRing.getTop();
        mZoomRing.handleTouch(event.getAction(), event.getEventTime(), x, y, rawX, rawY);
    }

    public void onZoomRingSetMovableHintVisible(boolean visible) {
        setPanningArrowsVisible(visible);
    }

    public void onUserInteractionStarted() {
        mHandler.removeMessages(MSG_DISMISS_ZOOM_RING);
    }

    public void onUserInteractionStopped() {
        dismissZoomRingDelayed(ZOOM_CONTROLS_TIMEOUT);
    }

    public void onZoomRingMovingStarted() {
        mScroller.abortAnimation();
        mTouchingEdgeStartTime = 0;
        mMovingZoomRingOobX = 0;
        mMovingZoomRingOobY = 0;
        if (mCallback != null) {
            mCallback.onBeginPan();
        }
    }

    private void setPanningArrowsVisible(boolean visible) {
        mPanningArrows.startAnimation(visible ? mPanningArrowsEnterAnimation
                : mPanningArrowsExitAnimation);
        mPanningArrows.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    public boolean onZoomRingMoved(int deltaX, int deltaY, int rawX, int rawY) {

        if (mMovingZoomRingOobX != 0) {
            /*
             * The finger has moved off the point where it originally touched
             * the zidget.
             */
            boolean wasOobLeft = mMovingZoomRingOobX < 0;
            mMovingZoomRingOobX += deltaX;
            if ((wasOobLeft && mMovingZoomRingOobX > 0) ||
                    (!wasOobLeft && mMovingZoomRingOobX < 0)) {
                /*
                 * Woot, the finger is back on the original point. Infact, it
                 * went PAST its original point, so take the amount it passed
                 * and use that as the delta to move the zoom ring.
                 */ 
                deltaX = mMovingZoomRingOobX;
                // No longer out-of-bounds, reset
                mMovingZoomRingOobX = 0;
            } else {
                // The finger is still not back, eat this movement
                deltaX = 0;
            }
        }
        
        if (mMovingZoomRingOobY != 0) {
            // See above for comments
            boolean wasOobUp = mMovingZoomRingOobY < 0;
            mMovingZoomRingOobY += deltaY;
            if ((wasOobUp && mMovingZoomRingOobY > 0) || (!wasOobUp && mMovingZoomRingOobY < 0)) {
                deltaY = mMovingZoomRingOobY;
                mMovingZoomRingOobY = 0;
            } else {
                deltaY = 0;
            }
        }
        
        WindowManager.LayoutParams lp = mContainerLayoutParams;
        Rect ownerBounds = mOwnerViewBounds;

        int zoomRingLeft = mZoomRing.getLeft();
        int zoomRingTop = mZoomRing.getTop();

        int newX = lp.x + deltaX;
        int newZoomRingX = newX + zoomRingLeft;
        newZoomRingX = (newZoomRingX <= ownerBounds.left) ? ownerBounds.left :
                (newZoomRingX + mZoomRingWidth > ownerBounds.right) ?
                        ownerBounds.right - mZoomRingWidth : newZoomRingX;
        lp.x = newZoomRingX - zoomRingLeft;

        int newY = lp.y + deltaY;
        int newZoomRingY = newY + zoomRingTop;
        newZoomRingY = (newZoomRingY <= ownerBounds.top) ? ownerBounds.top :
            (newZoomRingY + mZoomRingHeight > ownerBounds.bottom) ?
                    ownerBounds.bottom - mZoomRingHeight : newZoomRingY;
        lp.y = newZoomRingY - zoomRingTop;

        mWindowManager.updateViewLayout(mContainer, lp);

        // Check for pan
        boolean horizontalPanning = true;
        int leftGap = newZoomRingX - ownerBounds.left;
        if (leftGap < MAX_PAN_GAP) {
            if (leftGap == 0 && deltaX != 0 && mMovingZoomRingOobX == 0) {
                // Future moves in this direction should be accumulated in mMovingZoomRingOobX
                mMovingZoomRingOobX = deltaX / Math.abs(deltaX);
            }
            if (shouldPan(leftGap)) {
                mPanner.setHorizontalStrength(-getStrengthFromGap(leftGap));
            }
        } else {
            int rightGap = ownerBounds.right - (lp.x + mZoomRingWidth + zoomRingLeft);
            if (rightGap < MAX_PAN_GAP) {
                if (rightGap == 0 && deltaX != 0 && mMovingZoomRingOobX == 0) {
                    mMovingZoomRingOobX = deltaX / Math.abs(deltaX);
                }
                if (shouldPan(rightGap)) {
                    mPanner.setHorizontalStrength(getStrengthFromGap(rightGap));
                }
            } else {
                mPanner.setHorizontalStrength(0);
                horizontalPanning = false;
            }
        }

        int topGap = newZoomRingY - ownerBounds.top;
        if (topGap < MAX_PAN_GAP) {
            if (topGap == 0 && deltaY != 0 && mMovingZoomRingOobY == 0) {
                mMovingZoomRingOobY = deltaY / Math.abs(deltaY);
            }
            if (shouldPan(topGap)) {
                mPanner.setVerticalStrength(-getStrengthFromGap(topGap));
            }
        } else {
            int bottomGap = ownerBounds.bottom - (lp.y + mZoomRingHeight + zoomRingTop);
            if (bottomGap < MAX_PAN_GAP) {
                if (bottomGap == 0 && deltaY != 0 && mMovingZoomRingOobY == 0) {
                    mMovingZoomRingOobY = deltaY / Math.abs(deltaY);
                }
                if (shouldPan(bottomGap)) {
                    mPanner.setVerticalStrength(getStrengthFromGap(bottomGap));
                }
            } else {
                mPanner.setVerticalStrength(0);
                if (!horizontalPanning) {
                    // Neither are panning, reset any timer to start pan mode
                    mTouchingEdgeStartTime = 0;
                    mPanningEnabledForThisInteraction = false;
                    mPanner.stop();
                }
            }
        }

        return true;
    }

    private boolean shouldPan(int gap) {
        if (mPanningEnabledForThisInteraction) return true;

        if (gap < MAX_INITIATE_PAN_GAP) {
            long time = SystemClock.elapsedRealtime();
            if (mTouchingEdgeStartTime != 0 &&
                    mTouchingEdgeStartTime + INITIATE_PAN_DELAY < time) {
                mPanningEnabledForThisInteraction = true;
                return true;
            } else if (mTouchingEdgeStartTime == 0) {
                mTouchingEdgeStartTime = time;
            } else {
            }
        } else {
            // Moved away from the initiate pan gap, so reset the timer
            mTouchingEdgeStartTime = 0;
        }
        return false;
    }

    public void onZoomRingMovingStopped() {
        mPanner.stop();
        setPanningArrowsVisible(false);
        if (mCallback != null) {
            mCallback.onEndPan();
        }
    }

    private int getStrengthFromGap(int gap) {
        return gap > MAX_PAN_GAP ? 0 :
            (MAX_PAN_GAP - gap) * 100 / MAX_PAN_GAP;
    }

    public void onZoomRingThumbDraggingStarted() {
        if (mCallback != null) {
            mCallback.onBeginDrag();
        }
    }

    public boolean onZoomRingThumbDragged(int numLevels, int startAngle, int curAngle) {
        if (mCallback != null) {
            int deltaZoomLevel = -numLevels;
            int globalZoomCenterX = mContainerLayoutParams.x + mZoomRing.getLeft() +
                    mZoomRingWidth / 2;
            int globalZoomCenterY = mContainerLayoutParams.y + mZoomRing.getTop() +
                    mZoomRingHeight / 2;

            return mCallback.onDragZoom(deltaZoomLevel,
                    globalZoomCenterX - mOwnerViewBounds.left,
                    globalZoomCenterY - mOwnerViewBounds.top,
                    (float) startAngle / ZoomRing.RADIAN_INT_MULTIPLIER,
                    (float) curAngle / ZoomRing.RADIAN_INT_MULTIPLIER);
        }

        return false;
    }

    public void onZoomRingThumbDraggingStopped() {
        if (mCallback != null) {
            mCallback.onEndDrag();
        }
    }

    public void onZoomRingDismissed(boolean dismissImmediately) {
        if (dismissImmediately) {
            mHandler.removeMessages(MSG_DISMISS_ZOOM_RING);
            setVisible(false);
        } else {
            dismissZoomRingDelayed(ZOOM_RING_DISMISS_DELAY);
        }
    }

    public void onRingDown(int tickAngle, int touchAngle) {
    }

    public boolean onTouch(View v, MotionEvent event) {
        if (sTutorialDialog != null && sTutorialDialog.isShowing() &&
                SystemClock.elapsedRealtime() - sTutorialShowTime >= TUTORIAL_MIN_DISPLAY_TIME) {
            finishZoomTutorial();
        }

        int action = event.getAction();

        if (mReleaseTouchListenerOnUp) {
            // The ring was dismissed but we need to throw away all events until the up
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mOwnerView.setOnTouchListener(null);
                setTouchTargetView(null);
                mReleaseTouchListenerOnUp = false;
            }

            // Eat this event
            return true;
        }

        View targetView = mTouchTargetView;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                targetView = getViewForTouch((int) event.getRawX(), (int) event.getRawY());
                setTouchTargetView(targetView);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setTouchTargetView(null);
                break;
        }

        if (targetView != null) {
            // The upperleft corner of the target view in raw coordinates
            int targetViewRawX = mContainerLayoutParams.x + mTouchTargetLocationInWindow[0];
            int targetViewRawY = mContainerLayoutParams.y + mTouchTargetLocationInWindow[1];

            MotionEvent containerEvent = MotionEvent.obtain(event);
            // Convert the motion event into the target view's coordinates (from
            // owner view's coordinates)
            containerEvent.offsetLocation(mOwnerViewBounds.left - targetViewRawX,
                    mOwnerViewBounds.top - targetViewRawY);
            boolean retValue = targetView.dispatchTouchEvent(containerEvent);
            containerEvent.recycle();
            return retValue;

        } else {
            if (action == MotionEvent.ACTION_DOWN) {
                dismissZoomRingDelayed(ZOOM_RING_DISMISS_DELAY);
            }

            return false;
        }
    }

    private void setTouchTargetView(View view) {
        mTouchTargetView = view;
        if (view != null) {
            view.getLocationInWindow(mTouchTargetLocationInWindow);
        }
    }

    /**
     * Returns the View that should receive a touch at the given coordinates.
     *
     * @param rawX The raw X.
     * @param rawY The raw Y.
     * @return The view that should receive the touches, or null if there is not one.
     */
    private View getViewForTouch(int rawX, int rawY) {
        // Check to see if it is touching the ring
        int containerCenterX = mContainerLayoutParams.x + mContainer.getWidth() / 2;
        int containerCenterY = mContainerLayoutParams.y + mContainer.getHeight() / 2;
        int distanceFromCenterX = rawX - containerCenterX;
        int distanceFromCenterY = rawY - containerCenterY;
        int zoomRingRadius = mZoomRingWidth / 2 - ZOOM_RING_RADIUS_INSET;
        if (distanceFromCenterX * distanceFromCenterX +
                distanceFromCenterY * distanceFromCenterY <=
                zoomRingRadius * zoomRingRadius) {
            return mZoomRing;
        }

        // Check to see if it is touching any other clickable View.
        // Reverse order so the child drawn on top gets first dibs.
        int containerCoordsX = rawX - mContainerLayoutParams.x;
        int containerCoordsY = rawY - mContainerLayoutParams.y;
        Rect frame = mTempRect;
        for (int i = mContainer.getChildCount() - 1; i >= 0; i--) {
            View child = mContainer.getChildAt(i);
            if (child == mZoomRing || child.getVisibility() != View.VISIBLE ||
                    !child.isClickable()) {
                continue;
            }

            child.getHitRect(frame);
            if (frame.contains(containerCoordsX, containerCoordsY)) {
                return child;
            }
        }

        return null;
    }

    /** Steals key events from the owner view. */
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Eat these
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // Keep the zoom alive a little longer
                dismissZoomRingDelayed(ZOOM_CONTROLS_TIMEOUT);
                // They started zooming, hide the thumb arrows
                mZoomRing.setThumbArrowsVisible(false);

                if (mCallback != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    mCallback.onSimpleZoom(keyCode == KeyEvent.KEYCODE_DPAD_UP);
                }

                return true;
        }

        return false;
    }

    private void onScrollerTick() {
        if (!mScroller.computeScrollOffset() || !mIsZoomRingVisible) return;

        mContainerLayoutParams.x = mScroller.getCurrX();
        mContainerLayoutParams.y = mScroller.getCurrY();
        mWindowManager.updateViewLayout(mContainer, mContainerLayoutParams);

        mHandler.sendEmptyMessage(MSG_SCROLLER_TICK);
    }

    private void onPostConfigurationChanged() {
        dismissZoomRingDelayed(ZOOM_CONTROLS_TIMEOUT);
        refreshPositioningVariables();
        ensureZoomRingIsCentered();
    }

    /*
     * This is static so Activities can call this instead of the Views
     * (Activities usually do not have a reference to the ZoomRingController
     * instance.)
     */
    /**
     * Shows a "tutorial" (some text) to the user teaching her the new zoom
     * invocation method. Must call from the main thread.
     * <p>
     * It checks the global system setting to ensure this has not been seen
     * before. Furthermore, if the application does not have privilege to write
     * to the system settings, it will store this bit locally in a shared
     * preference.
     *
     * @hide This should only be used by our main apps--browser, maps, and
     *       gallery
     */
    public static void showZoomTutorialOnce(Context context) {
        ContentResolver cr = context.getContentResolver();
        if (Settings.System.getInt(cr, SETTING_NAME_SHOWN_TOAST, 0) == 1) {
            return;
        }

        SharedPreferences sp = context.getSharedPreferences("_zoom", Context.MODE_PRIVATE);
        if (sp.getInt(SETTING_NAME_SHOWN_TOAST, 0) == 1) {
            return;
        }

        if (sTutorialDialog != null && sTutorialDialog.isShowing()) {
            sTutorialDialog.dismiss();
        }

        LayoutInflater layoutInflater =
                (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        TextView textView = (TextView) layoutInflater.inflate(
                com.android.internal.R.layout.alert_dialog_simple_text, null)
                .findViewById(android.R.id.text1);
        textView.setText(com.android.internal.R.string.tutorial_double_tap_to_zoom_message_short);
        
        sTutorialDialog = new AlertDialog.Builder(context)
                .setView(textView)
                .setIcon(0)
                .create();

        Window window = sTutorialDialog.getWindow();
        window.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND |
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);

        sTutorialDialog.show();
        sTutorialShowTime = SystemClock.elapsedRealtime();
    }

    public static void finishZoomTutorial(Context context, boolean userNotified) {
        if (sTutorialDialog == null) return;

        sTutorialDialog.dismiss();
        sTutorialDialog = null;

        // Record that they have seen the tutorial
        if (userNotified) {
            try {
                Settings.System.putInt(context.getContentResolver(), SETTING_NAME_SHOWN_TOAST, 1);
            } catch (SecurityException e) {
                /*
                 * The app does not have permission to clear this global flag, make
                 * sure the user does not see the message when he comes back to this
                 * same app at least.
                 */
                SharedPreferences sp = context.getSharedPreferences("_zoom", Context.MODE_PRIVATE);
                sp.edit().putInt(SETTING_NAME_SHOWN_TOAST, 1).commit();
            }
        }
    }

    public void finishZoomTutorial() {
        finishZoomTutorial(mContext, true);
    }

    public void setPannerStartVelocity(float startVelocity) {
        mPanner.mStartVelocity = startVelocity;
    }

    public void setPannerAcceleration(float acceleration) {
        mPanner.mAcceleration = acceleration;
    }

    public void setPannerMaxVelocity(float maxVelocity) {
        mPanner.mMaxVelocity = maxVelocity;
    }

    public void setPannerStartAcceleratingDuration(int duration) {
        mPanner.mStartAcceleratingDuration = duration;
    }

    private class Panner implements Runnable {
        private static final int RUN_DELAY = 15;
        private static final float STOP_SLOWDOWN = 0.8f;

        private final Handler mUiHandler = new Handler();

        private int mVerticalStrength;
        private int mHorizontalStrength;

        private boolean mStopping;

        /** The time this current pan started. */
        private long mStartTime;

        /** The time of the last callback to pan the map/browser/etc. */
        private long mPreviousCallbackTime;

        // TODO Adjust to be DPI safe
        private float mStartVelocity = 135;
        private float mAcceleration = 160;
        private float mMaxVelocity = 1000;
        private int mStartAcceleratingDuration = 700;
        private float mVelocity;

        /** -100 (full left) to 0 (none) to 100 (full right) */
        public void setHorizontalStrength(int horizontalStrength) {
            if (mHorizontalStrength == 0 && mVerticalStrength == 0 && horizontalStrength != 0) {
                start();
            } else if (mVerticalStrength == 0 && horizontalStrength == 0) {
                stop();
            }

            mHorizontalStrength = horizontalStrength;
            mStopping = false;
        }

        /** -100 (full up) to 0 (none) to 100 (full down) */
        public void setVerticalStrength(int verticalStrength) {
            if (mHorizontalStrength == 0 && mVerticalStrength == 0 && verticalStrength != 0) {
                start();
            } else if (mHorizontalStrength == 0 && verticalStrength == 0) {
                stop();
            }

            mVerticalStrength = verticalStrength;
            mStopping = false;
        }

        private void start() {
            mUiHandler.post(this);
            mPreviousCallbackTime = 0;
            mStartTime = 0;
        }

        public void stop() {
            mStopping = true;
        }

        public void run() {
            if (mStopping) {
                mHorizontalStrength *= STOP_SLOWDOWN;
                mVerticalStrength *= STOP_SLOWDOWN;
            }

            if (mHorizontalStrength == 0 && mVerticalStrength == 0) {
                return;
            }

            boolean firstRun = mPreviousCallbackTime == 0;
            long curTime = SystemClock.elapsedRealtime();
            int panAmount = getPanAmount(mPreviousCallbackTime, curTime);
            mPreviousCallbackTime = curTime;

            if (firstRun) {
                mStartTime = curTime;
                mVelocity = mStartVelocity;
            } else {
                int panX = panAmount * mHorizontalStrength / 100;
                int panY = panAmount * mVerticalStrength / 100;

                if (mCallback != null) {
                    mCallback.onPan(panX, panY);
                }
            }

            mUiHandler.postDelayed(this, RUN_DELAY);
        }

        private int getPanAmount(long previousTime, long currentTime) {
            if (mVelocity > mMaxVelocity) {
                mVelocity = mMaxVelocity;
            } else if (mVelocity < mMaxVelocity) {
                // See if it's time to add in some acceleration
                if (currentTime - mStartTime > mStartAcceleratingDuration) {
                    mVelocity += (currentTime - previousTime) * mAcceleration / 1000;
                }
            }

            return (int) ((currentTime - previousTime) * mVelocity) / 1000;
        }

    }

    public interface OnZoomListener {
        void onBeginDrag();
        boolean onDragZoom(int deltaZoomLevel, int centerX, int centerY, float startAngle,
                float curAngle);
        void onEndDrag();
        void onSimpleZoom(boolean deltaZoomLevel);
        void onBeginPan();
        boolean onPan(int deltaX, int deltaY);
        void onEndPan();
        void onCenter(int x, int y);
        void onVisibilityChanged(boolean visible);
    }
}
