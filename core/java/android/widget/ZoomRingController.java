/*
 * Copyright (C) 2009 The Android Open Source Project
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
import android.util.DisplayMetrics;
import android.view.GestureDetector;
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

/**
 * A controller to simplify the use of the zoom ring widget.   
 * <p>
 * If you are using this with a custom View, please call
 * {@link #setVisible(boolean) setVisible(false)} from the
 * {@link View#onDetachedFromWindow}.
 *
 * @hide
 */
public class ZoomRingController implements ZoomRing.OnZoomRingCallback,
        View.OnTouchListener, View.OnKeyListener {

    // Temporary methods for different zoom types
    static int getZoomType(Context context) {
        return Settings.System.getInt(context.getContentResolver(), "zoom", 1);
    }
    public static boolean useOldZoom(Context context) {
        return getZoomType(context) == 0;
    }
    private static boolean useThisZoom(Context context) {
        return getZoomType(context) == 1;
    }

    /** The duration for the animation to re-center the zoom ring. */
    private static final int RECENTERING_DURATION = 500;

    /** The inactivity timeout for the zoom ring. */
    private static final int INACTIVITY_TIMEOUT =
            (int) ViewConfiguration.getZoomControlsTimeout();
    
    /**
     * The delay when the user taps outside to dismiss the zoom ring. This is
     * because the user can do a second-tap to recenter the owner view instead
     * of dismissing the zoom ring.
     */
    private static final int OUTSIDE_TAP_DISMISS_DELAY =
            ViewConfiguration.getDoubleTapTimeout() / 2;
    
    /**
     * When the zoom ring is on the edge, this is the delay before we actually
     * start panning the owner.
     * @see #mInitiatePanGap
     */
    private static final int INITIATE_PAN_DELAY = 300;

    /**
     * While already panning, if the zoom ring remains this close to an edge,
     * the owner will continue to be panned.
     */
    private int mPanGap;
    
    /** To begin a pan, the zoom ring must be this close to an edge. */
    private int mInitiatePanGap;

    /** Initialized from ViewConfiguration. */
    private int mScaledTouchSlop;

    /**
     * The setting name that tracks whether we've shown the zoom ring toast.
     */
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
    /** The X where the tap-drag started. */
    private int mTapDragStartX;
    /** The Y where the tap-drag started. */
    private int mTapDragStartY;

    /** The controller is idle */
    private static final int TOUCH_MODE_IDLE = 0;
    /**
     * In the middle of a second-tap interaction, waiting for either an up-touch
     * or the user to start dragging to go into tap-drag mode.
     */
    private static final int TOUCH_MODE_WAITING_FOR_TAP_DRAG_MOVEMENT = 2;
    /** In the middle of a tap-drag. */
    private static final int TOUCH_MODE_FORWARDING_FOR_TAP_DRAG = 3;
    private int mTouchMode;

    /** Whether the zoom ring is visible. */
    private boolean mIsZoomRingVisible;

    private ZoomRing mZoomRing;
    /** Cached width of the zoom ring. */
    private int mZoomRingWidth;
    /** Cached height of the zoom ring. */
    private int mZoomRingHeight;

    /** Invokes panning of owner view if the zoom ring is touching an edge. */
    private Panner mPanner;
    /** The time when the zoom ring first touched the edge. */
    private long mTouchingEdgeStartTime;
    /** Whether the user has already initiated the panning. */
    private boolean mPanningInitiated;

    /**
     * When the finger moves the zoom ring to an edge, this is the horizontal
     * accumulator for how much the finger has moved off of its original touch
     * point on the zoom ring (OOB = out-of-bounds). If < 0, the finger has
     * moved that many px to the left of its original touch point on the ring.
     */
    private int mMovingZoomRingOobX;
    /** Vertical accumulator, see {@link #mMovingZoomRingOobX} */
    private int mMovingZoomRingOobY;

    /** Arrows that hint that the zoom ring is movable. */
    private ImageView mPanningArrows;
    /** The animation shown when the panning arrows are being shown. */
    private Animation mPanningArrowsEnterAnimation;
    /** The animation shown when the panning arrows are being hidden. */
    private Animation mPanningArrowsExitAnimation;

    /**
     * Temporary rectangle, only use from the UI thread (and ideally don't rely
     * on it being unused across many method calls.)
     */
    private Rect mTempRect = new Rect();

    private OnZoomListener mCallback;

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

    /** Listens for configuration changes so we can make sure we're still in a reasonable state. */
    private BroadcastReceiver mConfigurationChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mIsZoomRingVisible) return;

            mHandler.removeMessages(MSG_POST_CONFIGURATION_CHANGED);
            mHandler.sendEmptyMessage(MSG_POST_CONFIGURATION_CHANGED);
        }
    };

    /** Keeps the scroller going (or starts it). */
    private static final int MSG_SCROLLER_STEP = 1;
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
                case MSG_SCROLLER_STEP:
                    onScrollerStep();
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
        mContainerLayoutParams.windowAnimations = com.android.internal.R.style.Animation_ZoomRing;

        mContainer = new FrameLayout(context);
        mContainer.setLayoutParams(mContainerLayoutParams);
        mContainer.setMeasureAllChildren(true);

        mContainer.addView(mZoomRing);
        mContainer.addView(mPanningArrows);

        mScroller = new Scroller(context, new DecelerateInterpolator());

        ViewConfiguration vc = ViewConfiguration.get(context);
        mScaledTouchSlop = vc.getScaledTouchSlop();
        
        float density = context.getResources().getDisplayMetrics().density;
        mPanGap = (int) (20 * density);
        mInitiatePanGap = (int) (10 * density);
    }

    private void createPanningArrows() {
        mPanningArrows = new ImageView(mContext);
        mPanningArrows.setImageDrawable(mZoomRing.getPanningArrowsDrawable());
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
     * Sets the angle (in radians) between ticks. This is also the angle a user
     * must move the thumb in order for the client to get a callback. Once there
     * is a callback, the accumulator resets. For example, if you set this to
     * PI/6, it will give a callback every time the user moves PI/6 amount on
     * the ring.
     * 
     * @param angle The angle for the callback threshold, in radians
     */
    public void setTickDelta(float angle) {
        mZoomRing.setTickDelta((int) (angle * ZoomRing.RADIAN_INT_MULTIPLIER));
    }

    /**
     * Sets a drawable for the zoom ring track.
     *
     * @param drawable The drawable to use for the track.
     * @hide Need a better way of doing this, but this one-off for browser so it
     *       can have its final look for the usability study
     */
    public void setTrackDrawable(int drawable) {
        mZoomRing.setBackgroundResource(drawable);
    }
    
    /**
     * Sets the callback for the zoom ring controller.
     * 
     * @param callback The callback.
     */
    public void setCallback(OnZoomListener callback) {
        mCallback = callback;
    }

    public void setVibration(boolean vibrate) {
        mZoomRing.setVibration(vibrate);
    }
    
    public void setThumbAngle(float angle) {
        mZoomRing.setThumbAngle((int) (angle * ZoomRing.RADIAN_INT_MULTIPLIER));
    }

    public void setThumbAngleAnimated(float angle) {
        mZoomRing.setThumbAngleAnimated((int) (angle * ZoomRing.RADIAN_INT_MULTIPLIER), 0);
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
            
            dismissZoomRingDelayed(INACTIVITY_TIMEOUT);
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
                        refreshContainerLayout();

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

    private void refreshContainerLayout() {
        if (mIsZoomRingVisible) {
            mWindowManager.updateViewLayout(mContainer, mContainerLayoutParams);
        }
    }

    /**
     * Returns the container of the zoom ring widget. The client can add views
     * here to be shown alongside the zoom ring.  See {@link #getZoomRingId()}.
     * <p>
     * Notes:
     * <ul>
     * <li> The controller dispatches touch events differently than the regular view
     * framework.
     * <li> Please ensure you set your view to INVISIBLE not GONE when hiding it.
     * </ul>
     * 
     * @return The layout used by the container.
     */
    public FrameLayout getContainer() {
        return mContainer;
    }

    /**
     * Returns the id of the zoom ring widget.
     * 
     * @return The id of the zoom ring widget.
     */
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
     * <p>
     * In most cases, the client can use a {@link GestureDetector} and forward events from
     * {@link GestureDetector.OnDoubleTapListener#onDoubleTapEvent(MotionEvent)}.
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
                            if (Math.abs(x - mTapDragStartX) > mScaledTouchSlop ||
                                    Math.abs(y - mTapDragStartY) >
                                    mScaledTouchSlop) {
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
                    mCenteredContainerY - lp.y, RECENTERING_DURATION);
            mHandler.sendEmptyMessage(MSG_SCROLLER_STEP);
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

    /** @hide */
    public void onZoomRingSetMovableHintVisible(boolean visible) {
        setPanningArrowsVisible(visible);
    }

    /** @hide */
    public void onUserInteractionStarted() {
        mHandler.removeMessages(MSG_DISMISS_ZOOM_RING);
    }

    /** @hide */
    public void onUserInteractionStopped() {
        dismissZoomRingDelayed(INACTIVITY_TIMEOUT);
    }

    /** @hide */
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

    /** @hide */
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

        refreshContainerLayout();
        
        // Check for pan
        boolean horizontalPanning = true;
        int leftGap = newZoomRingX - ownerBounds.left;
        if (leftGap < mPanGap) {
            if (leftGap == 0 && deltaX != 0 && mMovingZoomRingOobX == 0) {
                // Future moves in this direction should be accumulated in mMovingZoomRingOobX
                mMovingZoomRingOobX = deltaX / Math.abs(deltaX);
            }
            if (shouldPan(leftGap)) {
                mPanner.setHorizontalStrength(-getStrengthFromGap(leftGap));
            }
        } else {
            int rightGap = ownerBounds.right - (lp.x + mZoomRingWidth + zoomRingLeft);
            if (rightGap < mPanGap) {
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
        if (topGap < mPanGap) {
            if (topGap == 0 && deltaY != 0 && mMovingZoomRingOobY == 0) {
                mMovingZoomRingOobY = deltaY / Math.abs(deltaY);
            }
            if (shouldPan(topGap)) {
                mPanner.setVerticalStrength(-getStrengthFromGap(topGap));
            }
        } else {
            int bottomGap = ownerBounds.bottom - (lp.y + mZoomRingHeight + zoomRingTop);
            if (bottomGap < mPanGap) {
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
                    mPanningInitiated = false;
                    mPanner.stop();
                }
            }
        }

        return true;
    }

    private boolean shouldPan(int gap) {
        if (mPanningInitiated) return true;

        if (gap < mInitiatePanGap) {
            long time = SystemClock.elapsedRealtime();
            if (mTouchingEdgeStartTime != 0 &&
                    mTouchingEdgeStartTime + INITIATE_PAN_DELAY < time) {
                mPanningInitiated = true;
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

    /** @hide */
    public void onZoomRingMovingStopped() {
        mPanner.stop();
        setPanningArrowsVisible(false);
        if (mCallback != null) {
            mCallback.onEndPan();
        }
    }

    private int getStrengthFromGap(int gap) {
        return gap > mPanGap ? 0 :
            (mPanGap - gap) * 100 / mPanGap;
    }

    /** @hide */
    public void onZoomRingThumbDraggingStarted() {
        if (mCallback != null) {
            mCallback.onBeginDrag();
        }
    }

    /** @hide */
    public boolean onZoomRingThumbDragged(int numLevels, int startAngle, int curAngle) {
        if (mCallback != null) {
            int deltaZoomLevel = -numLevels;

            return mCallback.onDragZoom(deltaZoomLevel,
                    getZoomRingCenterXInOwnerCoordinates(),
                    getZoomRingCenterYInOwnerCoordinates(),
                    (float) startAngle / ZoomRing.RADIAN_INT_MULTIPLIER,
                    (float) curAngle / ZoomRing.RADIAN_INT_MULTIPLIER);
        }

        return false;
    }

    private int getZoomRingCenterXInOwnerCoordinates() {
        int globalZoomCenterX = mContainerLayoutParams.x + mZoomRing.getLeft() +
                mZoomRingWidth / 2;
        return globalZoomCenterX - mOwnerViewBounds.left;
    }

    private int getZoomRingCenterYInOwnerCoordinates() {
        int globalZoomCenterY = mContainerLayoutParams.y + mZoomRing.getTop() +
                mZoomRingHeight / 2;
        return globalZoomCenterY - mOwnerViewBounds.top;
    }

    /** @hide */
    public void onZoomRingThumbDraggingStopped() {
        if (mCallback != null) {
            mCallback.onEndDrag();
        }
    }

    /** @hide */
    public void onZoomRingDismissed() {
        mHandler.removeMessages(MSG_DISMISS_ZOOM_RING);
        setVisible(false);
    }

    /** @hide */
    public void onRingDown(int tickAngle, int touchAngle) {
    }

    /** @hide */
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
//            dismissZoomRingDelayed(ZOOM_CONTROLS_TIMEOUT);
            if (action == MotionEvent.ACTION_DOWN) {
                dismissZoomRingDelayed(OUTSIDE_TAP_DISMISS_DELAY);
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
        int zoomRingRadius = mZoomRing.getTrackOuterRadius();
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

    /**
     * Steals key events from the owner view.
     * 
     * @hide
     */
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Eat these
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // Keep the zoom alive a little longer
                dismissZoomRingDelayed(INACTIVITY_TIMEOUT);
                // They started zooming, hide the thumb arrows
                mZoomRing.setThumbArrowsVisible(false);

                if (mCallback != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    mCallback.onSimpleZoom(keyCode == KeyEvent.KEYCODE_DPAD_UP,
                            getZoomRingCenterXInOwnerCoordinates(),
                            getZoomRingCenterYInOwnerCoordinates());
                }

                return true;
        }

        return false;
    }

    private void onScrollerStep() {
        if (!mScroller.computeScrollOffset() || !mIsZoomRingVisible) return;

        mContainerLayoutParams.x = mScroller.getCurrX();
        mContainerLayoutParams.y = mScroller.getCurrY();
        refreshContainerLayout();

        mHandler.sendEmptyMessage(MSG_SCROLLER_STEP);
    }

    private void onPostConfigurationChanged() {
        dismissZoomRingDelayed(INACTIVITY_TIMEOUT);
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

    /** @hide Should only be used by Android platform apps */
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

    /** @hide Should only be used by Android platform apps */
    public void finishZoomTutorial() {
        finishZoomTutorial(mContext, true);
    }

    /**
     * Sets the initial velocity of a pan.
     * 
     * @param startVelocity The initial velocity to move the owner view, in
     *            pixels per second.
     */
    public void setPannerStartVelocity(float startVelocity) {
        mPanner.mStartVelocity = startVelocity;
    }

    /**
     * Sets the accelartion of the pan.
     *
     * @param acceleration The acceleration, in pixels per second squared.
     */
    public void setPannerAcceleration(float acceleration) {
        mPanner.mAcceleration = acceleration;
    }

    /**
     * Sets the maximum velocity of a pan.
     * 
     * @param maxVelocity The max velocity to move the owner view, in pixels per
     *            second.
     */
    public void setPannerMaxVelocity(float maxVelocity) {
        mPanner.mMaxVelocity = maxVelocity;
    }

    /**
     * Sets the duration before acceleration will be applied.
     * 
     * @param duration The duration, in milliseconds.
     */
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

    /**
     * Interface used to inform the client of zoom events that the user
     * triggers.
     */
    public interface OnZoomListener {
        /**
         * Called when the user begins dragging the thumb on the zoom ring.
         */
        void onBeginDrag();

        /**
         * Called when the user drags the thumb and passes a tick causing a
         * zoom.
         * 
         * @param deltaZoomLevel The number of levels to be zoomed. Positive to
         *            zoom in, negative to zoom out.
         * @param centerX The point about which to zoom. The zoom should pin
         *            this point, leaving it at the same coordinate. This is
         *            relative to the owner view's upper-left.
         * @param centerY The point about which to zoom. The zoom should pin
         *            this point, leaving it at the same coordinate. This is
         *            relative to the owner view's upper-left.
         * @param startAngle The angle where the user started dragging the thumb.
         * @param curAngle The current angle of the thumb.
         * @return Whether the owner was zoomed.
         */
        boolean onDragZoom(int deltaZoomLevel, int centerX, int centerY, float startAngle,
                float curAngle);
        
        /**
         * Called when the user releases the thumb.
         */
        void onEndDrag();

        /**
         * Called when the user zooms via some other mechanism, for example
         * arrow keys or a trackball.
         * 
         * @param zoomIn Whether to zoom in (true) or out (false).
         * @param centerX See {@link #onDragZoom(int, int, int, float, float)}.
         * @param centerY See {@link #onDragZoom(int, int, int, float, float)}.
         */
        void onSimpleZoom(boolean zoomIn, int centerX, int centerY);

        /**
         * Called when the user begins moving the zoom ring in order to pan the
         * owner.
         */
        void onBeginPan();
        
        /**
         * Called when the owner should pan as a result of the user moving the zoom ring.
         * 
         * @param deltaX The amount to pan horizontally.
         * @param deltaY The amount to pan vertically.
         * @return Whether the owner was panned.
         */
        boolean onPan(int deltaX, int deltaY);
        
        /**
         * Called when the user releases the zoom ring.
         */
        void onEndPan();
        
        /**
         * Called when the client should center the owner on the given point.
         * 
         * @param x The x to center on, relative to the owner view's upper-left.
         * @param y The y to center on, relative to the owner view's upper-left.
         */
        void onCenter(int x, int y);
        
        /**
         * Called when the zoom ring's visibility changes.
         * 
         * @param visible Whether the zoom ring is visible (true) or not (false).
         */
        void onVisibilityChanged(boolean visible);
    }
}
