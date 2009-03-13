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
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewRoot;
import android.view.Window;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.view.WindowManager.LayoutParams;

/*
 * Implementation notes:
 * - The zoom controls are displayed in their own window.
 *   (Easier for the client and better performance)
 * - This window is not touchable, and by default is not focusable.
 * - To make the buttons clickable, it attaches a OnTouchListener to the owner
 *   view and does the hit detection locally.
 * - When it is focusable, it forwards uninteresting events to the owner view's
 *   view hierarchy.
 */
/**
 * The {@link ZoomButtonsController} handles showing and hiding the zoom
 * controls relative to an owner view. It also gives the client access to the
 * zoom controls container, allowing for additional accessory buttons to be
 * shown in the zoom controls window.
 * <p>
 * Typical usage involves the client using the {@link GestureDetector} to
 * forward events from
 * {@link GestureDetector.OnDoubleTapListener#onDoubleTapEvent(MotionEvent)} to
 * {@link #handleDoubleTapEvent(MotionEvent)}. Also, whenever the owner cannot
 * be zoomed further, the client should update
 * {@link #setZoomInEnabled(boolean)} and {@link #setZoomOutEnabled(boolean)}.
 * <p>
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

    private static final int ZOOM_CONTROLS_TOUCH_PADDING = 20;
    private int mTouchPaddingScaledSq;

    private Context mContext;
    private WindowManager mWindowManager;

    /**
     * The view that is being zoomed by this zoom controller.
     */
    private View mOwnerView;

    /**
     * The location of the owner view on the screen. This is recalculated
     * each time the zoom controller is shown.
     */
    private int[] mOwnerViewRawLocation = new int[2];

    /**
     * The container that is added as a window.
     */
    private FrameLayout mContainer;
    private LayoutParams mContainerLayoutParams;
    private int[] mContainerRawLocation = new int[2];

    private ZoomControls mControls;

    /**
     * The view (or null) that should receive touch events. This will get set if
     * the touch down hits the container. It will be reset on the touch up.
     */
    private View mTouchTargetView;
    /**
     * The {@link #mTouchTargetView}'s location in window, set on touch down.
     */
    private int[] mTouchTargetWindowLocation = new int[2];
    /**
     * If the zoom controller is dismissed but the user is still in a touch
     * interaction, we set this to true. This will ignore all touch events until
     * up/cancel, and then set the owner's touch listener to null.
     */
    private boolean mReleaseTouchListenerOnUp;

    /**
     * Whether we are currently in the double-tap gesture, with the second tap
     * still being performed (i.e., we're waiting for the second tap's touch up).
     */
    private boolean mIsSecondTapDown;

    /** Whether the container has been added to the window manager. */
    private boolean mIsVisible;

    private Rect mTempRect = new Rect();
    private int[] mTempIntArray = new int[2];
    
    private OnZoomListener mCallback;

    /**
     * In 1.0, the ZoomControls were to be added to the UI by the client of
     * WebView, MapView, etc. We didn't want apps to break, so we return a dummy
     * view in place now.
     */
    private InvisibleView mDummyZoomControls;
    
    /**
     * When showing the zoom, we add the view as a new window. However, there is
     * logic that needs to know the size of the zoom which is determined after
     * it's laid out. Therefore, we must post this logic onto the UI thread so
     * it will be exceuted AFTER the layout. This is the logic.
     */
    private Runnable mPostedVisibleInitializer;

    private IntentFilter mConfigurationChangedFilter =
            new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);

    /**
     * Needed to reposition the zoom controls after configuration changes.
     */
    private BroadcastReceiver mConfigurationChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mIsVisible) return;

            mHandler.removeMessages(MSG_POST_CONFIGURATION_CHANGED);
            mHandler.sendEmptyMessage(MSG_POST_CONFIGURATION_CHANGED);
        }
    };

    /**
     * The setting name that tracks whether we've shown the zoom tutorial.
     */
    private static final String SETTING_NAME_SHOWN_TUTORIAL = "shown_zoom_tutorial";
    private static Dialog sTutorialDialog;

    /** When configuration changes, this is called after the UI thread is idle. */
    private static final int MSG_POST_CONFIGURATION_CHANGED = 2;
    /** Used to delay the zoom controller dismissal. */
    private static final int MSG_DISMISS_ZOOM_CONTROLS = 3;
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
                LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp.height = LayoutParams.WRAP_CONTENT;
        lp.width = LayoutParams.FILL_PARENT;
        lp.type = LayoutParams.TYPE_APPLICATION_PANEL;
        lp.format = PixelFormat.TRANSPARENT;
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

    private void dismissControlsDelayed(int delay) {
        mHandler.removeMessages(MSG_DISMISS_ZOOM_CONTROLS);
        mHandler.sendEmptyMessageDelayed(MSG_DISMISS_ZOOM_CONTROLS, delay);
    }

    /**
     * Should be called by the client for each event belonging to the second tap
     * (the down, move, up, and/or cancel events).
     *
     * @param event The event belonging to the second tap.
     * @return Whether the event was consumed.
     */
    public boolean handleDoubleTapEvent(MotionEvent event) {
        int action = event.getAction();

        if (action == MotionEvent.ACTION_DOWN) {
            int x = (int) event.getX();
            int y = (int) event.getY();

            /*
             * This class will consume all events in the second tap (down,
             * move(s), up). But, the owner already got the second tap's down,
             * so cancel that. Do this before setVisible, since that call
             * will set us as a touch listener.
             */
            MotionEvent cancelEvent = MotionEvent.obtain(event.getDownTime(),
                    SystemClock.elapsedRealtime(),
                    MotionEvent.ACTION_CANCEL, 0, 0, 0);
            mOwnerView.dispatchTouchEvent(cancelEvent);
            cancelEvent.recycle();

            setVisible(true);
            centerPoint(x, y);
            mIsSecondTapDown = true;
        }

        return true;
    }

    private void refreshPositioningVariables() {
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

    /**
     * Centers the point (in owner view's coordinates).
     */
    private void centerPoint(int x, int y) {
        if (mCallback != null) {
            mCallback.onCenter(x, y);
        }
    }

    /* This will only be called when the container has focus. */
    private boolean onContainerKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (isInterestingKey(keyCode)) {
            
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                setVisible(false);
            } else {
                dismissControlsDelayed(ZOOM_CONTROLS_TIMEOUT);
            }
            
            // Let the container handle the key
            return false;
            
        } else {
            
            ViewRoot viewRoot = getOwnerViewRoot();
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
    
    private ViewRoot getOwnerViewRoot() {
        View rootViewOfOwner = mOwnerView.getRootView();
        if (rootViewOfOwner == null) {
            return null;
        }
        
        ViewParent parentOfRootView = rootViewOfOwner.getParent();
        if (parentOfRootView instanceof ViewRoot) {
            return (ViewRoot) parentOfRootView;
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

        // Consume all events during the second-tap interaction (down, move, up/cancel)
        boolean consumeEvent = mIsSecondTapDown;
        if ((action == MotionEvent.ACTION_UP) || (action == MotionEvent.ACTION_CANCEL)) {
            // The second tap can no longer be down
            mIsSecondTapDown = false;
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
            if (containerEvent.getX() < 0) {
                containerEvent.offsetLocation(-containerEvent.getX(), 0);
            }
            if (containerEvent.getY() < 0) {
                containerEvent.offsetLocation(0, -containerEvent.getY());
            }
            boolean retValue = targetView.dispatchTouchEvent(containerEvent);
            containerEvent.recycle();
            return retValue || consumeEvent;

        } else {
            return consumeEvent;
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
            
            int distanceX = Math.min(Math.abs(frame.left - containerCoordsX),
                    Math.abs(containerCoordsX - frame.right));
            int distanceY = Math.min(Math.abs(frame.top - containerCoordsY),
                    Math.abs(containerCoordsY - frame.bottom));
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

    /*
     * This is static so Activities can call this instead of the Views
     * (Activities usually do not have a reference to the ZoomButtonsController
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
        
        // TODO: remove this code, but to hit the weekend build, just never show
        if (true) return;
        
        ContentResolver cr = context.getContentResolver();
        if (Settings.System.getInt(cr, SETTING_NAME_SHOWN_TUTORIAL, 0) == 1) {
            return;
        }

        SharedPreferences sp = context.getSharedPreferences("_zoom", Context.MODE_PRIVATE);
        if (sp.getInt(SETTING_NAME_SHOWN_TUTORIAL, 0) == 1) {
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
    }

    /** @hide Should only be used by Android platform apps */
    public static void finishZoomTutorial(Context context, boolean userNotified) {
        if (sTutorialDialog == null) return;

        sTutorialDialog.dismiss();
        sTutorialDialog = null;

        // Record that they have seen the tutorial
        if (userNotified) {
            try {
                Settings.System.putInt(context.getContentResolver(), SETTING_NAME_SHOWN_TUTORIAL,
                        1);
            } catch (SecurityException e) {
                /*
                 * The app does not have permission to clear this global flag, make
                 * sure the user does not see the message when he comes back to this
                 * same app at least.
                 */
                SharedPreferences sp = context.getSharedPreferences("_zoom", Context.MODE_PRIVATE);
                sp.edit().putInt(SETTING_NAME_SHOWN_TUTORIAL, 1).commit();
            }
        }
    }

    /** @hide Should only be used by Android platform apps */
    public void finishZoomTutorial() {
        finishZoomTutorial(mContext, true);
    }

    /** @hide Should only be used only be WebView and MapView */
    public View getDummyZoomControls() {
        if (mDummyZoomControls == null) {
            mDummyZoomControls = new InvisibleView(mContext);
        }
        return mDummyZoomControls;
    }
    
    /**
     * Interface that will be called when the user performs an interaction that
     * triggers some action, for example zooming.
     */
    public interface OnZoomListener {
        /**
         * Called when the given point should be centered. The point will be in
         * owner view coordinates.
         * 
         * @param x The x of the point.
         * @param y The y of the point.
         */
        void onCenter(int x, int y);
        
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

    /**
     * An InvisibleView is an invisible, zero-sized View for backwards
     * compatibility
     */
    private final class InvisibleView extends View {

        private InvisibleView(Context context) {
            super(context);
            setVisibility(GONE);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(0, 0);
        }

        @Override
        public void draw(Canvas canvas) {
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
        }
    }

}
