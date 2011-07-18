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
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.view.WindowManager.LayoutParams;

/*
 * Implementation notes:
 * - The zoom controls are displayed in their own window.
 *   (Easier for the client and better performance)
 * - This window is never touchable, and by default is not focusable.
 *   Its rect is quite big (fills horizontally) but has empty space between the
 *   edges and center.  Touches there should be given to the owner.  Instead of
 *   having the window touchable and dispatching these empty touch events to the
 *   owner, we set the window to not touchable and steal events from owner
 *   via onTouchListener.
 * - To make the buttons clickable, it attaches an OnTouchListener to the owner
 *   view and does the hit detection locally (attaches when visible, detaches when invisible).
 * - When it is focusable, it forwards uninteresting events to the owner view's
 *   view hierarchy.
 */
/**
 * The {@link ZoomButtonsController} handles showing and hiding the zoom
 * controls and positioning it relative to an owner view. It also gives the
 * client access to the zoom controls container, allowing for additional
 * accessory buttons to be shown in the zoom controls window.
 * <p>
 * Typically, clients should call {@link #setVisible(boolean) setVisible(true)}
 * on a touch down or move (no need to call {@link #setVisible(boolean)
 * setVisible(false)} since it will time out on its own). Also, whenever the
 * owner cannot be zoomed further, the client should update
 * {@link #setZoomInEnabled(boolean)} and {@link #setZoomOutEnabled(boolean)}.
 * <p>
 * If you are using this with a custom View, please call
 * {@link #setVisible(boolean) setVisible(false)} from
 * {@link View#onDetachedFromWindow} and from {@link View#onVisibilityChanged}
 * when <code>visibility != View.VISIBLE</code>.
 *
 */
public class ZoomButtonsController implements View.OnTouchListener {

    private static final String TAG = "ZoomButtonsController";

    private static final int ZOOM_CONTROLS_TIMEOUT =
            (int) ViewConfiguration.getZoomControlsTimeout();

    private static final int ZOOM_CONTROLS_TOUCH_PADDING = 20;
    private int mTouchPaddingScaledSq;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private boolean mAutoDismissControls = true;

    /**
     * The view that is being zoomed by this zoom controller.
     */
    private final View mOwnerView;

    /**
     * The location of the owner view on the screen. This is recalculated
     * each time the zoom controller is shown.
     */
    private final int[] mOwnerViewRawLocation = new int[2];

    /**
     * The container that is added as a window.
     */
    private final FrameLayout mContainer;
    private LayoutParams mContainerLayoutParams;
    private final int[] mContainerRawLocation = new int[2];

    private ZoomControls mControls;

    /**
     * The view (or null) that should receive touch events. This will get set if
     * the touch down hits the container. It will be reset on the touch up.
     */
    private View mTouchTargetView;
    /**
     * The {@link #mTouchTargetView}'s location in window, set on touch down.
     */
    private final int[] mTouchTargetWindowLocation = new int[2];

    /**
     * If the zoom controller is dismissed but the user is still in a touch
     * interaction, we set this to true. This will ignore all touch events until
     * up/cancel, and then set the owner's touch listener to null.
     * <p>
     * Otherwise, the owner view would get mismatched events (i.e., touch move
     * even though it never got the touch down.)
     */
    private boolean mReleaseTouchListenerOnUp;

    /** Whether the container has been added to the window manager. */
    private boolean mIsVisible;

    private final Rect mTempRect = new Rect();
    private final int[] mTempIntArray = new int[2];

    private OnZoomListener mCallback;

    /**
     * When showing the zoom, we add the view as a new window. However, there is
     * logic that needs to know the size of the zoom which is determined after
     * it's laid out. Therefore, we must post this logic onto the UI thread so
     * it will be exceuted AFTER the layout. This is the logic.
     */
    private Runnable mPostedVisibleInitializer;

    private final IntentFilter mConfigurationChangedFilter =
            new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);

    /**
     * Needed to reposition the zoom controls after configuration changes.
     */
    private final BroadcastReceiver mConfigurationChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mIsVisible) return;

            mHandler.removeMessages(MSG_POST_CONFIGURATION_CHANGED);
            mHandler.sendEmptyMessage(MSG_POST_CONFIGURATION_CHANGED);
        }
    };

    /** When configuration changes, this is called after the UI thread is idle. */
    private static final int MSG_POST_CONFIGURATION_CHANGED = 2;
    /** Used to delay the zoom controller dismissal. */
    private static final int MSG_DISMISS_ZOOM_CONTROLS = 3;
    /**
     * If setVisible(true) is called and the owner view's window token is null,
     * we delay the setVisible(true) call until it is not null.
     */
    private static final int MSG_POST_SET_VISIBLE = 4;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_POST_CONFIGURATION_CHANGED:
                    onPostConfigurationChanged();
                    break;

                case MSG_DISMISS_ZOOM_CONTROLS:
                    setVisible(false);
                    break;

                case MSG_POST_SET_VISIBLE:
                    if (mOwnerView.getWindowToken() == null) {
                        // Doh, it is still null, just ignore the set visible call
                        Log.e(TAG,
                                "Cannot make the zoom controller visible if the owner view is " +
                                "not attached to a window.");
                    } else {
                        setVisible(true);
                    }
                    break;
            }

        }
    };

    /**
     * Constructor for the {@link ZoomButtonsController}.
     *
     * @param ownerView The view that is being zoomed by the zoom controls. The
     *            zoom controls will be displayed aligned with this view.
     */
    public ZoomButtonsController(View ownerView) {
        mContext = ownerView.getContext();
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mOwnerView = ownerView;

        mTouchPaddingScaledSq = (int)
                (ZOOM_CONTROLS_TOUCH_PADDING * mContext.getResources().getDisplayMetrics().density);
        mTouchPaddingScaledSq *= mTouchPaddingScaledSq;

        mContainer = createContainer();
    }

    /**
     * Whether to enable the zoom in control.
     *
     * @param enabled Whether to enable the zoom in control.
     */
    public void setZoomInEnabled(boolean enabled) {
        mControls.setIsZoomInEnabled(enabled);
    }

    /**
     * Whether to enable the zoom out control.
     *
     * @param enabled Whether to enable the zoom out control.
     */
    public void setZoomOutEnabled(boolean enabled) {
        mControls.setIsZoomOutEnabled(enabled);
    }

    /**
     * Sets the delay between zoom callbacks as the user holds a zoom button.
     *
     * @param speed The delay in milliseconds between zoom callbacks.
     */
    public void setZoomSpeed(long speed) {
        mControls.setZoomSpeed(speed);
    }

    private FrameLayout createContainer() {
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        // Controls are positioned BOTTOM | CENTER with respect to the owner view.
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.flags = LayoutParams.FLAG_NOT_TOUCHABLE |
                LayoutParams.FLAG_NOT_FOCUSABLE |
                LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        lp.height = LayoutParams.WRAP_CONTENT;
        lp.width = LayoutParams.MATCH_PARENT;
        lp.type = LayoutParams.TYPE_APPLICATION_PANEL;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.windowAnimations = com.android.internal.R.style.Animation_ZoomButtons;
        mContainerLayoutParams = lp;

        FrameLayout container = new Container(mContext);
        container.setLayoutParams(lp);
        container.setMeasureAllChildren(true);

        LayoutInflater inflater = (LayoutInflater) mContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(com.android.internal.R.layout.zoom_container, container);

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

        return container;
    }

    /**
     * Sets the {@link OnZoomListener} listener that receives callbacks to zoom.
     *
     * @param listener The listener that will be told to zoom.
     */
    public void setOnZoomListener(OnZoomListener listener) {
        mCallback = listener;
    }

    /**
     * Sets whether the zoom controls should be focusable. If the controls are
     * focusable, then trackball and arrow key interactions are possible.
     * Otherwise, only touch interactions are possible.
     *
     * @param focusable Whether the zoom controls should be focusable.
     */
    public void setFocusable(boolean focusable) {
        int oldFlags = mContainerLayoutParams.flags;
        if (focusable) {
            mContainerLayoutParams.flags &= ~LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            mContainerLayoutParams.flags |= LayoutParams.FLAG_NOT_FOCUSABLE;
        }

        if ((mContainerLayoutParams.flags != oldFlags) && mIsVisible) {
            mWindowManager.updateViewLayout(mContainer, mContainerLayoutParams);
        }
    }

    /**
     * Whether the zoom controls will be automatically dismissed after showing.
     *
     * @return Whether the zoom controls will be auto dismissed after showing.
     */
    public boolean isAutoDismissed() {
        return mAutoDismissControls;
    }

    /**
     * Sets whether the zoom controls will be automatically dismissed after
     * showing.
     */
    public void setAutoDismissed(boolean autoDismiss) {
        if (mAutoDismissControls == autoDismiss) return;
        mAutoDismissControls = autoDismiss;
    }

    /**
     * Whether the zoom controls are visible to the user.
     *
     * @return Whether the zoom controls are visible to the user.
     */
    public boolean isVisible() {
        return mIsVisible;
    }

    /**
     * Sets whether the zoom controls should be visible to the user.
     *
     * @param visible Whether the zoom controls should be visible to the user.
     */
    public void setVisible(boolean visible) {

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
     * Gets the container that is the parent of the zoom controls.
     * <p>
     * The client can add other views to this container to link them with the
     * zoom controls.
     *
     * @return The container of the zoom controls. It will be a layout that
     *         respects the gravity of a child's layout parameters.
     */
    public ViewGroup getContainer() {
        return mContainer;
    }

    /**
     * Gets the view for the zoom controls.
     *
     * @return The zoom controls view.
     */
    public View getZoomControls() {
        return mControls;
    }

    private void dismissControlsDelayed(int delay) {
        if (mAutoDismissControls) {
            mHandler.removeMessages(MSG_DISMISS_ZOOM_CONTROLS);
            mHandler.sendEmptyMessageDelayed(MSG_DISMISS_ZOOM_CONTROLS, delay);
        }
    }

    private void refreshPositioningVariables() {
        // if the mOwnerView is detached from window then skip.
        if (mOwnerView.getWindowToken() == null) return;

        // Position the zoom controls on the bottom of the owner view.
        int ownerHeight = mOwnerView.getHeight();
        int ownerWidth = mOwnerView.getWidth();
        // The gap between the top of the owner and the top of the container
        int containerOwnerYOffset = ownerHeight - mContainer.getHeight();

        // Calculate the owner view's bounds
        mOwnerView.getLocationOnScreen(mOwnerViewRawLocation);
        mContainerRawLocation[0] = mOwnerViewRawLocation[0];
        mContainerRawLocation[1] = mOwnerViewRawLocation[1] + containerOwnerYOffset;

        int[] ownerViewWindowLoc = mTempIntArray;
        mOwnerView.getLocationInWindow(ownerViewWindowLoc);

        // lp.x and lp.y should be relative to the owner's window top-left
        mContainerLayoutParams.x = ownerViewWindowLoc[0];
        mContainerLayoutParams.width = ownerWidth;
        mContainerLayoutParams.y = ownerViewWindowLoc[1] + containerOwnerYOffset;
        if (mIsVisible) {
            mWindowManager.updateViewLayout(mContainer, mContainerLayoutParams);
        }

    }

    /* This will only be called when the container has focus. */
    private boolean onContainerKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (isInterestingKey(keyCode)) {

            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    if (mOwnerView != null) {
                        KeyEvent.DispatcherState ds = mOwnerView.getKeyDispatcherState();
                        if (ds != null) {
                            ds.startTracking(event, this);
                        }
                    }
                    return true;
                } else if (event.getAction() == KeyEvent.ACTION_UP
                        && event.isTracking() && !event.isCanceled()) {
                    setVisible(false);
                    return true;
                }
                
            } else {
                dismissControlsDelayed(ZOOM_CONTROLS_TIMEOUT);
            }

            // Let the container handle the key
            return false;

        } else {

            ViewRootImpl viewRoot = getOwnerViewRootImpl();
            if (viewRoot != null) {
                viewRoot.dispatchKey(event);
            }

            // We gave the key to the owner, don't let the container handle this key
            return true;
        }
    }

    private boolean isInterestingKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return false;
        }
    }

    private ViewRootImpl getOwnerViewRootImpl() {
        View rootViewOfOwner = mOwnerView.getRootView();
        if (rootViewOfOwner == null) {
            return null;
        }

        ViewParent parentOfRootView = rootViewOfOwner.getParent();
        if (parentOfRootView instanceof ViewRootImpl) {
            return (ViewRootImpl) parentOfRootView;
        } else {
            return null;
        }
    }

    /**
     * @hide The ZoomButtonsController implements the OnTouchListener, but this
     *       does not need to be shown in its public API.
     */
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getAction();

        if (event.getPointerCount() > 1) {
            // ZoomButtonsController doesn't handle mutitouch. Give up control.
            return false;
        }

        if (mReleaseTouchListenerOnUp) {
            // The controls were dismissed but we need to throw away all events until the up
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mOwnerView.setOnTouchListener(null);
                setTouchTargetView(null);
                mReleaseTouchListenerOnUp = false;
            }

            // Eat this event
            return true;
        }

        dismissControlsDelayed(ZOOM_CONTROLS_TIMEOUT);

        View targetView = mTouchTargetView;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                targetView = findViewForTouch((int) event.getRawX(), (int) event.getRawY());
                setTouchTargetView(targetView);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setTouchTargetView(null);
                break;
        }

        if (targetView != null) {
            // The upperleft corner of the target view in raw coordinates
            int targetViewRawX = mContainerRawLocation[0] + mTouchTargetWindowLocation[0];
            int targetViewRawY = mContainerRawLocation[1] + mTouchTargetWindowLocation[1];

            MotionEvent containerEvent = MotionEvent.obtain(event);
            // Convert the motion event into the target view's coordinates (from
            // owner view's coordinates)
            containerEvent.offsetLocation(mOwnerViewRawLocation[0] - targetViewRawX,
                    mOwnerViewRawLocation[1] - targetViewRawY);
            /* Disallow negative coordinates (which can occur due to
             * ZOOM_CONTROLS_TOUCH_PADDING) */
            // These are floats because we need to potentially offset away this exact amount
            float containerX = containerEvent.getX();
            float containerY = containerEvent.getY();
            if (containerX < 0 && containerX > -ZOOM_CONTROLS_TOUCH_PADDING) {
                containerEvent.offsetLocation(-containerX, 0);
            }
            if (containerY < 0 && containerY > -ZOOM_CONTROLS_TOUCH_PADDING) {
                containerEvent.offsetLocation(0, -containerY);
            }
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
            view.getLocationInWindow(mTouchTargetWindowLocation);
        }
    }

    /**
     * Returns the View that should receive a touch at the given coordinates.
     *
     * @param rawX The raw X.
     * @param rawY The raw Y.
     * @return The view that should receive the touches, or null if there is not one.
     */
    private View findViewForTouch(int rawX, int rawY) {
        // Reverse order so the child drawn on top gets first dibs.
        int containerCoordsX = rawX - mContainerRawLocation[0];
        int containerCoordsY = rawY - mContainerRawLocation[1];
        Rect frame = mTempRect;

        View closestChild = null;
        int closestChildDistanceSq = Integer.MAX_VALUE;

        for (int i = mContainer.getChildCount() - 1; i >= 0; i--) {
            View child = mContainer.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }

            child.getHitRect(frame);
            if (frame.contains(containerCoordsX, containerCoordsY)) {
                return child;
            }

            int distanceX;
            if (containerCoordsX >= frame.left && containerCoordsX <= frame.right) {
                distanceX = 0;
            } else {
                distanceX = Math.min(Math.abs(frame.left - containerCoordsX),
                    Math.abs(containerCoordsX - frame.right));
            }
            int distanceY;
            if (containerCoordsY >= frame.top && containerCoordsY <= frame.bottom) {
                distanceY = 0;
            } else {
                distanceY = Math.min(Math.abs(frame.top - containerCoordsY),
                        Math.abs(containerCoordsY - frame.bottom));
            }
            int distanceSq = distanceX * distanceX + distanceY * distanceY;

            if ((distanceSq < mTouchPaddingScaledSq) &&
                    (distanceSq < closestChildDistanceSq)) {
                closestChild = child;
                closestChildDistanceSq = distanceSq;
            }
        }

        return closestChild;
    }

    private void onPostConfigurationChanged() {
        dismissControlsDelayed(ZOOM_CONTROLS_TIMEOUT);
        refreshPositioningVariables();
    }

    /**
     * Interface that will be called when the user performs an interaction that
     * triggers some action, for example zooming.
     */
    public interface OnZoomListener {

        /**
         * Called when the zoom controls' visibility changes.
         *
         * @param visible Whether the zoom controls are visible.
         */
        void onVisibilityChanged(boolean visible);

        /**
         * Called when the owner view needs to be zoomed.
         *
         * @param zoomIn The direction of the zoom: true to zoom in, false to zoom out.
         */
        void onZoom(boolean zoomIn);
    }

    private class Container extends FrameLayout {
        public Container(Context context) {
            super(context);
        }

        /*
         * Need to override this to intercept the key events. Otherwise, we
         * would attach a key listener to the container but its superclass
         * ViewGroup gives it to the focused View instead of calling the key
         * listener, and so we wouldn't get the events.
         */
        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return onContainerKey(event) ? true : super.dispatchKeyEvent(event);
        }
    }

}
