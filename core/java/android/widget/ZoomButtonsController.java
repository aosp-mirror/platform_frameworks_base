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
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.view.WindowManager.LayoutParams;

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
public class ZoomButtonsController implements View.OnTouchListener {

    private static final String TAG = "ZoomButtonsController";

    private static final int ZOOM_CONTROLS_TIMEOUT =
            (int) ViewConfiguration.getZoomControlsTimeout();

    // TODO: scaled to density
    private static final int ZOOM_CONTROLS_TOUCH_PADDING = 20;
    
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
    private int[] mContainerLocation = new int[2];

    private ZoomControls mControls;
    
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

    private boolean mIsVisible;

    private Rect mTempRect = new Rect();

    private OnZoomListener mCallback;

    /**
     * When showing the zoom, we add the view as a new window. However, there is
     * logic that needs to know the size of the zoom which is determined after
     * it's laid out. Therefore, we must post this logic onto the UI thread so
     * it will be exceuted AFTER the layout. This is the logic.
     */
    private Runnable mPostedVisibleInitializer;

    private IntentFilter mConfigurationChangedFilter =
            new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);

    private BroadcastReceiver mConfigurationChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mIsVisible) return;

            mHandler.removeMessages(MSG_POST_CONFIGURATION_CHANGED);
            mHandler.sendEmptyMessage(MSG_POST_CONFIGURATION_CHANGED);
        }
    };

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

    public ZoomButtonsController(Context context, View ownerView) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mOwnerView = ownerView;

        mContainer = createContainer();
    }

    private FrameLayout createContainer() {
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER;
        lp.flags = LayoutParams.FLAG_NOT_TOUCHABLE |
                LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp.height = LayoutParams.WRAP_CONTENT;
        lp.width = LayoutParams.FILL_PARENT;
        lp.type = LayoutParams.TYPE_APPLICATION_PANEL;
        lp.format = PixelFormat.TRANSPARENT;
        // TODO: make a new animation for this
        lp.windowAnimations = com.android.internal.R.style.Animation_InputMethodFancy;
        mContainerLayoutParams = lp;
        
        FrameLayout container = new FrameLayout(mContext);
        container.setLayoutParams(lp);
        container.setMeasureAllChildren(true);
        
        LayoutInflater inflater = (LayoutInflater) mContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(com.android.internal.R.layout.zoom_magnify, container);
        
        mControls = (ZoomControls) container.findViewById(com.android.internal.R.id.zoomControls);
        mControls.setOnZoomInClickListener(new OnClickListener() {
            public void onClick(View v) {
                dismissControlsDelayed(ZOOM_CONTROLS_TIMEOUT);
                if (mCallback != null) mCallback.onZoom(true);
            }
        });
        mControls.setOnZoomOutClickListener(new OnClickListener() {
            public void onClick(View v) {
                dismissControlsDelayed(ZOOM_CONTROLS_TIMEOUT);
                if (mCallback != null) mCallback.onZoom(false);
            }
        });

        View overview = container.findViewById(com.android.internal.R.id.zoomMagnify);
        overview.setVisibility(View.GONE);
        overview.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                dismissControlsDelayed(ZOOM_CONTROLS_TIMEOUT);
                if (mCallback != null) mCallback.onOverview();
            }
        });
        
        return container;
    }
    
    public void setCallback(OnZoomListener callback) {
        mCallback = callback;
    }

    public void setFocusable(boolean focusable) {
        if (focusable) {
            mContainerLayoutParams.flags &= ~LayoutParams.FLAG_NOT_FOCUSABLE; 
        } else {
            mContainerLayoutParams.flags |= LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        
        if (mIsVisible) {
            mWindowManager.updateViewLayout(mContainer, mContainerLayoutParams);
        }
    }
    
    public void setOverviewVisible(boolean visible) {
        mContainer.findViewById(com.android.internal.R.id.zoomMagnify)
                .setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public boolean isVisible() {
        return mIsVisible;
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
            
            dismissControlsDelayed(ZOOM_CONTROLS_TIMEOUT);
        }

        if (mIsVisible == visible) {
            return;
        }
        mIsVisible = visible;

        if (visible) {
            if (mContainerLayoutParams.token == null) {
                mContainerLayoutParams.token = mOwnerView.getWindowToken();
            }

            mWindowManager.addView(mContainer, mContainerLayoutParams);

            if (mPostedVisibleInitializer == null) {
                mPostedVisibleInitializer = new Runnable() {
                    public void run() {
                        refreshPositioningVariables();

                        if (mCallback != null) {
                            mCallback.onVisibilityChanged(true);
                        }
                    }
                };
            }

            mHandler.post(mPostedVisibleInitializer);

            // Handle configuration changes when visible
            mContext.registerReceiver(mConfigurationChangedReceiver, mConfigurationChangedFilter);

            // Steal touches events from the owner
            mOwnerView.setOnTouchListener(this);
            mReleaseTouchListenerOnUp = false;

        } else {
            // Don't want to steal any more touches
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
     * - Please ensure you set your View to INVISIBLE not GONE when hiding it.
     *
     * @return TODO
     */
    public FrameLayout getContainer() {
        return mContainer;
    }

    public int getZoomRingId() {
        return mControls.getId();
    }

    private void dismissControlsDelayed(int delay) {
        mHandler.removeMessages(MSG_DISMISS_ZOOM_RING);
        mHandler.sendEmptyMessageDelayed(MSG_DISMISS_ZOOM_RING, delay);
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

        if (action == MotionEvent.ACTION_DOWN) {
            int x = (int) event.getX();
            int y = (int) event.getY();

            setVisible(true);
            centerPoint(x, y);
        }

        return true;
    }

    private void refreshPositioningVariables() {
        // Calculate the owner view's bounds
        mOwnerView.getGlobalVisibleRect(mOwnerViewBounds);
        mContainer.getLocationOnScreen(mContainerLocation);
    }

    /**
     * Centers the point (in owner view's coordinates).
     */
    private void centerPoint(int x, int y) {
        if (mCallback != null) {
            mCallback.onCenter(x, y);
        }
    }

    public boolean onTouch(View v, MotionEvent event) {
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

        // TODO: optimize this (it ends up removing message and queuing another)
        dismissControlsDelayed(ZOOM_CONTROLS_TIMEOUT);
        
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
            int targetViewRawX = mContainerLocation[0] + mTouchTargetLocationInWindow[0];
            int targetViewRawY = mContainerLocation[1] + mTouchTargetLocationInWindow[1];

            MotionEvent containerEvent = MotionEvent.obtain(event);
            // Convert the motion event into the target view's coordinates (from
            // owner view's coordinates)
            containerEvent.offsetLocation(mOwnerViewBounds.left - targetViewRawX,
                    mOwnerViewBounds.top - targetViewRawY);
            boolean retValue = targetView.dispatchTouchEvent(containerEvent);
            containerEvent.recycle();
            return retValue;

        } else {
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
        // Reverse order so the child drawn on top gets first dibs.
        int containerCoordsX = rawX - mContainerLocation[0];
        int containerCoordsY = rawY - mContainerLocation[1];
        Rect frame = mTempRect;
        for (int i = mContainer.getChildCount() - 1; i >= 0; i--) {
            View child = mContainer.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }

            child.getHitRect(frame);
            // Expand the touch region
            frame.top -= ZOOM_CONTROLS_TOUCH_PADDING;
            if (frame.contains(containerCoordsX, containerCoordsY)) {
                return child;
            }
        }

        return null;
    }

    private void onPostConfigurationChanged() {
        dismissControlsDelayed(ZOOM_CONTROLS_TIMEOUT);
        refreshPositioningVariables();
    }

    public static boolean useThisZoom(Context context) {
        return ZoomRingController.getZoomType(context) == 2;
    }
    
    public interface OnZoomListener {
        void onCenter(int x, int y);
        void onVisibilityChanged(boolean visible);
        void onZoom(boolean zoomIn);
        void onOverview();
    }
}
