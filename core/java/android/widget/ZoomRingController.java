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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;

// TODO: make sure no px values exist, only dip (scale if necessary from Viewconfiguration)

/**
 * TODO: Docs
 * @hide
 */
public class ZoomRingController implements ZoomRing.OnZoomRingCallback,
        View.OnTouchListener, View.OnKeyListener {
    
    private static final int SHOW_TUTORIAL_TOAST_DELAY = 1000;

    private static final int ZOOM_RING_RECENTERING_DURATION = 500;

    private static final String TAG = "ZoomRing";

    public static final boolean USE_OLD_ZOOM = false; 
    public static boolean useOldZoom(Context context) {
        return Settings.System.getInt(context.getContentResolver(), "zoom", 1) == 0;
    }
    
    private static final int ZOOM_CONTROLS_TIMEOUT =
            (int) ViewConfiguration.getZoomControlsTimeout();
    
    // TODO: move these to ViewConfiguration or re-use existing ones
    // TODO: scale px values based on latest from ViewConfiguration
    private static final int SECOND_TAP_TIMEOUT = 500;
    private static final int ZOOM_RING_DISMISS_DELAY = SECOND_TAP_TIMEOUT / 2;
    private static final int SECOND_TAP_SLOP = 70;  
    private static final int SECOND_TAP_MOVE_SLOP = 15; 
    private static final int MAX_PAN_GAP = 30;
    
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
    private Panner mPanner = new Panner();
    
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

    // TODO: need a better way to persist this value, becuase right now this
    // requires the WRITE_SETTINGS perimssion which the app may not have
//    private Runnable mShowTutorialToast = new Runnable() {
//        public void run() {
//            if (Settings.System.getInt(mContext.getContentResolver(),
//                    SETTING_NAME_SHOWN_TOAST, 0) == 1) {
//                return;
//            }
//            try {
//                Settings.System.putInt(mContext.getContentResolver(), SETTING_NAME_SHOWN_TOAST, 1);
//            } catch (SecurityException e) {
//                // The app does not have permission to clear this flag, oh well!
//            }
//            
//            Toast.makeText(mContext,
//                    com.android.internal.R.string.tutorial_double_tap_to_zoom_message,
//                    Toast.LENGTH_LONG).show();
//        }
//    };

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
            }
        
        }  
    };
    
    public ZoomRingController(Context context, View ownerView) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        
        mOwnerView = ownerView;
        
        mZoomRing = new ZoomRing(context);
        mZoomRing.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        mZoomRing.setCallback(this);
        
        createPanningArrows();

        mContainer = new FrameLayout(context);
        mContainer.setMeasureAllChildren(true);
        mContainer.setOnTouchListener(this);
        
        mContainer.addView(mZoomRing);
        mContainer.addView(mPanningArrows);
        mContainer.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        
        mContainerLayoutParams = new LayoutParams();
        mContainerLayoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        mContainerLayoutParams.flags = LayoutParams.FLAG_NOT_TOUCH_MODAL |
                LayoutParams.FLAG_NOT_FOCUSABLE |
                LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mContainerLayoutParams.height = LayoutParams.WRAP_CONTENT;
        mContainerLayoutParams.width = LayoutParams.WRAP_CONTENT;
        mContainerLayoutParams.type = LayoutParams.TYPE_APPLICATION_PANEL;
        mContainerLayoutParams.format = PixelFormat.TRANSLUCENT;
        // TODO: make a new animation for this
        mContainerLayoutParams.windowAnimations = com.android.internal.R.style.Animation_Dialog;
        
        mScroller = new Scroller(context, new DecelerateInterpolator());
        
        mViewConfig = ViewConfiguration.get(context);
        
//        mHandler.postDelayed(mShowTutorialToast, SHOW_TUTORIAL_TOAST_DELAY);
    }

    private void createPanningArrows() {
        // TODO: style
        mPanningArrows = new ImageView(mContext);
        mPanningArrows.setImageResource(com.android.internal.R.drawable.zoom_ring_arrows);
        mPanningArrows.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        mPanningArrows.setVisibility(View.GONE);
        
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
    
    public void setCallback(OnZoomListener callback) {
        mCallback = callback;
    }
    
    public void setThumbAngle(float angle) {
        mZoomRing.setThumbAngle((int) (angle * ZoomRing.RADIAN_INT_MULTIPLIER));
    }

    public void setResetThumbAutomatically(boolean resetThumbAutomatically) {
        mZoomRing.setResetThumbAutomatically(resetThumbAutomatically);
    }
    
    public boolean isVisible() {
        return mIsZoomRingVisible;
    }

    public void setVisible(boolean visible) {

        if (useOldZoom(mContext)) return;
        
        if (visible) {
            dismissZoomRingDelayed(ZOOM_CONTROLS_TIMEOUT);
        } else {
            mPanner.stop();
        }
        
        if (mIsZoomRingVisible == visible) {
            return;
        }

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
                    }
                };                
            }
            
            mHandler.post(mPostedVisibleInitializer);
            
            // Handle configuration changes when visible
            mContext.registerReceiver(mConfigurationChangedReceiver, mConfigurationChangedFilter);
            
            // Steal key events from the owner
            mOwnerView.setOnKeyListener(this);
            
        } else {
            // Don't want to steal any more keys
            mOwnerView.setOnKeyListener(null);
            
            // No longer care about configuration changes
            mContext.unregisterReceiver(mConfigurationChangedReceiver);
            
            mWindowManager.removeView(mContainer);
        }
        
        mIsZoomRingVisible = visible;

        if (mCallback != null) {
            mCallback.onVisibilityChanged(visible);
        }
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
        int action = event.getAction();
        
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
                            mZoomRing.setTapDragMode(false, (int) event.getX(), (int) event.getY());
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
    
    // MOVE ALL THIS TO GESTURE DETECTOR
//    public boolean onTouch(View v, MotionEvent event) {
//        int action = event.getAction();
//
//        if (mListenForInvocation) {
//            switch (mTouchMode) {
//                case TOUCH_MODE_IDLE: {
//                    if (action == MotionEvent.ACTION_DOWN) {
//                        setFirstTap(event);
//                    }
//                    break;
//                }
//                
//                case TOUCH_MODE_WAITING_FOR_SECOND_TAP: {
//                    switch (action) {
//                        case MotionEvent.ACTION_DOWN:
//                            if (isSecondTapWithinSlop(event)) {
//                                handleDoubleTapEvent(event);                                
//                            } else {
//                                setFirstTap(event);
//                            }
//                            break;
//                            
//                        case MotionEvent.ACTION_MOVE:
//                            int deltaX = (int) event.getX() - mFirstTapX;
//                            if (deltaX < -SECOND_TAP_MOVE_SLOP ||
//                                    deltaX > SECOND_TAP_MOVE_SLOP) {
//                                mTouchMode = TOUCH_MODE_IDLE;
//                            } else {
//                                int deltaY = (int) event.getY() - mFirstTapY;
//                                if (deltaY < -SECOND_TAP_MOVE_SLOP ||
//                                        deltaY > SECOND_TAP_MOVE_SLOP) {
//                                    mTouchMode = TOUCH_MODE_IDLE;
//                                }
//                            }
//                            break;
//                    }
//                    break;
//                }
//                
//                case TOUCH_MODE_WAITING_FOR_TAP_DRAG_MOVEMENT:
//                case TOUCH_MODE_FORWARDING_FOR_TAP_DRAG: {
//                    handleDoubleTapEvent(event);
//                    break;
//                }
//            }
//            
//            if (action == MotionEvent.ACTION_CANCEL) {
//                mTouchMode = TOUCH_MODE_IDLE;
//            }
//        }
//        
//        return false;
//    }
//    
//    private void setFirstTap(MotionEvent event) {
//        mFirstTapTime = event.getEventTime();
//        mFirstTapX = (int) event.getX();
//        mFirstTapY = (int) event.getY();
//        mTouchMode = TOUCH_MODE_WAITING_FOR_SECOND_TAP;
//    }
//    
//    private boolean isSecondTapWithinSlop(MotionEvent event) {
//        return mFirstTapTime + SECOND_TAP_TIMEOUT > event.getEventTime() && 
//            Math.abs((int) event.getX() - mFirstTapX) < SECOND_TAP_SLOP &&
//            Math.abs((int) event.getY() - mFirstTapY) < SECOND_TAP_SLOP;
//    }
    
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
    
    public void onZoomRingMovingStarted() {
        mHandler.removeMessages(MSG_DISMISS_ZOOM_RING);
        mScroller.abortAnimation();
        setPanningArrowsVisible(true); 
    }
    
    private void setPanningArrowsVisible(boolean visible) {
        mPanningArrows.startAnimation(visible ? mPanningArrowsEnterAnimation
                : mPanningArrowsExitAnimation);
        mPanningArrows.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
    
    public boolean onZoomRingMoved(int deltaX, int deltaY) {
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
        int leftGap = newZoomRingX - ownerBounds.left;
        if (leftGap < MAX_PAN_GAP) {
            mPanner.setHorizontalStrength(-getStrengthFromGap(leftGap));
        } else {
            int rightGap = ownerBounds.right - (lp.x + mZoomRingWidth + zoomRingLeft);
            if (rightGap < MAX_PAN_GAP) {
                mPanner.setHorizontalStrength(getStrengthFromGap(rightGap));
            } else {
                mPanner.setHorizontalStrength(0);
            }
        }
        
        int topGap = newZoomRingY - ownerBounds.top;
        if (topGap < MAX_PAN_GAP) {
            mPanner.setVerticalStrength(-getStrengthFromGap(topGap));
        } else {
            int bottomGap = ownerBounds.bottom - (lp.y + mZoomRingHeight + zoomRingTop);
            if (bottomGap < MAX_PAN_GAP) {
                mPanner.setVerticalStrength(getStrengthFromGap(bottomGap));
            } else {
                mPanner.setVerticalStrength(0);
            }
        }
        
        return true;
    }
    
    public void onZoomRingMovingStopped() {
        dismissZoomRingDelayed(ZOOM_CONTROLS_TIMEOUT);
        mPanner.stop();
        setPanningArrowsVisible(false); 
    }
    
    private int getStrengthFromGap(int gap) {
        return gap > MAX_PAN_GAP ? 0 :
            (MAX_PAN_GAP - gap) * 100 / MAX_PAN_GAP;
    }
    
    public void onZoomRingThumbDraggingStarted(int startAngle) {
        mHandler.removeMessages(MSG_DISMISS_ZOOM_RING);
        if (mCallback != null) {
            mCallback.onBeginDrag((float) startAngle / ZoomRing.RADIAN_INT_MULTIPLIER);
        }
    }
    
    public boolean onZoomRingThumbDragged(int numLevels, int dragAmount, int startAngle,
            int curAngle) {
        if (mCallback != null) {
            int deltaZoomLevel = -numLevels;
            int globalZoomCenterX = mContainerLayoutParams.x + mZoomRing.getLeft() +
                    mZoomRingWidth / 2;
            int globalZoomCenterY = mContainerLayoutParams.y + mZoomRing.getTop() +
                    mZoomRingHeight / 2;
            
            return mCallback.onDragZoom(deltaZoomLevel, globalZoomCenterX - mOwnerViewBounds.left,
                    globalZoomCenterY - mOwnerViewBounds.top,
                    (float) startAngle / ZoomRing.RADIAN_INT_MULTIPLIER,
                    (float) curAngle / ZoomRing.RADIAN_INT_MULTIPLIER);
        }
        
        return false;
    }
    
    public void onZoomRingThumbDraggingStopped(int endAngle) {
        dismissZoomRingDelayed(ZOOM_CONTROLS_TIMEOUT);
        if (mCallback != null) {
            mCallback.onEndDrag((float) endAngle / ZoomRing.RADIAN_INT_MULTIPLIER);
        }
    }

    public void onZoomRingDismissed() {
        dismissZoomRingDelayed(ZOOM_RING_DISMISS_DELAY);        
    }

    public boolean onTouch(View v, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            // If the user touches outside of the zoom ring, dismiss the zoom ring
            dismissZoomRingDelayed(ZOOM_RING_DISMISS_DELAY);
            return true;
        }
        
        return false;
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
            int panAmount = getPanAmount(mStartTime, mPreviousCallbackTime, curTime);
            mPreviousCallbackTime = curTime;
            
            if (firstRun) {
                mStartTime = curTime;
            } else {
                int panX = panAmount * mHorizontalStrength / 100;
                int panY = panAmount * mVerticalStrength / 100;
                
                if (mCallback != null) {
                    mCallback.onPan(panX, panY);
                }
            }
            
            mUiHandler.postDelayed(this, RUN_DELAY);
        }
        
        // TODO make setter for this value so zoom clients can have different pan rates, if they want
        private static final int PAN_VELOCITY_PX_S = 30;
        private int getPanAmount(long startTime, long previousTime, long currentTime) {
            return (int) ((currentTime - previousTime) * PAN_VELOCITY_PX_S / 100);
        }
    }
    
    public interface OnZoomListener {
        void onBeginDrag(float startAngle);
        boolean onDragZoom(int deltaZoomLevel, int centerX, int centerY, float startAngle,
                float curAngle);
        void onEndDrag(float endAngle);
        void onSimpleZoom(boolean deltaZoomLevel);
        boolean onPan(int deltaX, int deltaY);
        void onCenter(int x, int y);
        void onVisibilityChanged(boolean visible);
    }
}
