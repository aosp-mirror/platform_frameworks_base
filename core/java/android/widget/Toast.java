/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UnsupportedAppUsage;
import android.app.INotificationManager;
import android.app.ITransientNotification;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A toast is a view containing a quick little message for the user.  The toast class
 * helps you create and show those.
 * {@more}
 *
 * <p>
 * When the view is shown to the user, appears as a floating view over the
 * application.  It will never receive focus.  The user will probably be in the
 * middle of typing something else.  The idea is to be as unobtrusive as
 * possible, while still showing the user the information you want them to see.
 * Two examples are the volume control, and the brief message saying that your
 * settings have been saved.
 * <p>
 * The easiest way to use this class is to call one of the static methods that constructs
 * everything you need and returns a new Toast object.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about creating Toast notifications, read the
 * <a href="{@docRoot}guide/topics/ui/notifiers/toasts.html">Toast Notifications</a> developer
 * guide.</p>
 * </div>
 */
public class Toast {
    static final String TAG = "Toast";
    static final boolean localLOGV = false;

    /** @hide */
    @IntDef(prefix = { "LENGTH_" }, value = {
            LENGTH_SHORT,
            LENGTH_LONG
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Duration {}

    /**
     * Show the view or text notification for a short period of time.  This time
     * could be user-definable.  This is the default.
     * @see #setDuration
     */
    public static final int LENGTH_SHORT = 0;

    /**
     * Show the view or text notification for a long period of time.  This time
     * could be user-definable.
     * @see #setDuration
     */
    public static final int LENGTH_LONG = 1;

    final Context mContext;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    final TN mTN;
    @UnsupportedAppUsage
    int mDuration;
    View mNextView;

    /**
     * Construct an empty Toast object.  You must call {@link #setView} before you
     * can call {@link #show}.
     *
     * @param context  The context to use.  Usually your {@link android.app.Application}
     *                 or {@link android.app.Activity} object.
     */
    public Toast(Context context) {
        this(context, null);
    }

    /**
     * Constructs an empty Toast object.  If looper is null, Looper.myLooper() is used.
     * @hide
     */
    public Toast(@NonNull Context context, @Nullable Looper looper) {
        mContext = context;
        mTN = new TN(context.getPackageName(), looper);
        mTN.mY = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.toast_y_offset);
        mTN.mGravity = context.getResources().getInteger(
                com.android.internal.R.integer.config_toastDefaultGravity);
    }

    /**
     * Show the view for the specified duration.
     */
    public void show() {
        if (mNextView == null) {
            throw new RuntimeException("setView must have been called");
        }

        INotificationManager service = getService();
        String pkg = mContext.getOpPackageName();
        TN tn = mTN;
        tn.mNextView = mNextView;
        final int displayId = mContext.getDisplayId();

        try {
            service.enqueueToast(pkg, tn, mDuration, displayId);
        } catch (RemoteException e) {
            // Empty
        }
    }

    /**
     * Close the view if it's showing, or don't show it if it isn't showing yet.
     * You do not normally have to call this.  Normally view will disappear on its own
     * after the appropriate duration.
     */
    public void cancel() {
        mTN.cancel();
    }

    /**
     * Set the view to show.
     * @see #getView
     */
    public void setView(View view) {
        mNextView = view;
    }

    /**
     * Return the view.
     * @see #setView
     */
    public View getView() {
        return mNextView;
    }

    /**
     * Set how long to show the view for.
     * @see #LENGTH_SHORT
     * @see #LENGTH_LONG
     */
    public void setDuration(@Duration int duration) {
        mDuration = duration;
        mTN.mDuration = duration;
    }

    /**
     * Return the duration.
     * @see #setDuration
     */
    @Duration
    public int getDuration() {
        return mDuration;
    }

    /**
     * Set the margins of the view.
     *
     * @param horizontalMargin The horizontal margin, in percentage of the
     *        container width, between the container's edges and the
     *        notification
     * @param verticalMargin The vertical margin, in percentage of the
     *        container height, between the container's edges and the
     *        notification
     */
    public void setMargin(float horizontalMargin, float verticalMargin) {
        mTN.mHorizontalMargin = horizontalMargin;
        mTN.mVerticalMargin = verticalMargin;
    }

    /**
     * Return the horizontal margin.
     */
    public float getHorizontalMargin() {
        return mTN.mHorizontalMargin;
    }

    /**
     * Return the vertical margin.
     */
    public float getVerticalMargin() {
        return mTN.mVerticalMargin;
    }

    /**
     * Set the location at which the notification should appear on the screen.
     * @see android.view.Gravity
     * @see #getGravity
     */
    public void setGravity(int gravity, int xOffset, int yOffset) {
        mTN.mGravity = gravity;
        mTN.mX = xOffset;
        mTN.mY = yOffset;
    }

     /**
     * Get the location at which the notification should appear on the screen.
     * @see android.view.Gravity
     * @see #getGravity
     */
    public int getGravity() {
        return mTN.mGravity;
    }

    /**
     * Return the X offset in pixels to apply to the gravity's location.
     */
    public int getXOffset() {
        return mTN.mX;
    }

    /**
     * Return the Y offset in pixels to apply to the gravity's location.
     */
    public int getYOffset() {
        return mTN.mY;
    }

    /**
     * Gets the LayoutParams for the Toast window.
     * @hide
     */
    @UnsupportedAppUsage
    public WindowManager.LayoutParams getWindowParams() {
        return mTN.mParams;
    }

    /**
     * Make a standard toast that just contains a text view.
     *
     * @param context  The context to use.  Usually your {@link android.app.Application}
     *                 or {@link android.app.Activity} object.
     * @param text     The text to show.  Can be formatted text.
     * @param duration How long to display the message.  Either {@link #LENGTH_SHORT} or
     *                 {@link #LENGTH_LONG}
     *
     */
    public static Toast makeText(Context context, CharSequence text, @Duration int duration) {
        return makeText(context, null, text, duration);
    }

    /**
     * Make a standard toast to display using the specified looper.
     * If looper is null, Looper.myLooper() is used.
     * @hide
     */
    public static Toast makeText(@NonNull Context context, @Nullable Looper looper,
            @NonNull CharSequence text, @Duration int duration) {
        Toast result = new Toast(context, looper);

        LayoutInflater inflate = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflate.inflate(com.android.internal.R.layout.transient_notification, null);
        TextView tv = (TextView)v.findViewById(com.android.internal.R.id.message);
        tv.setText(text);

        result.mNextView = v;
        result.mDuration = duration;

        return result;
    }

    /**
     * Make a standard toast that just contains a text view with the text from a resource.
     *
     * @param context  The context to use.  Usually your {@link android.app.Application}
     *                 or {@link android.app.Activity} object.
     * @param resId    The resource id of the string resource to use.  Can be formatted text.
     * @param duration How long to display the message.  Either {@link #LENGTH_SHORT} or
     *                 {@link #LENGTH_LONG}
     *
     * @throws Resources.NotFoundException if the resource can't be found.
     */
    public static Toast makeText(Context context, @StringRes int resId, @Duration int duration)
                                throws Resources.NotFoundException {
        return makeText(context, context.getResources().getText(resId), duration);
    }

    /**
     * Update the text in a Toast that was previously created using one of the makeText() methods.
     * @param resId The new text for the Toast.
     */
    public void setText(@StringRes int resId) {
        setText(mContext.getText(resId));
    }

    /**
     * Update the text in a Toast that was previously created using one of the makeText() methods.
     * @param s The new text for the Toast.
     */
    public void setText(CharSequence s) {
        if (mNextView == null) {
            throw new RuntimeException("This Toast was not created with Toast.makeText()");
        }
        TextView tv = mNextView.findViewById(com.android.internal.R.id.message);
        if (tv == null) {
            throw new RuntimeException("This Toast was not created with Toast.makeText()");
        }
        tv.setText(s);
    }

    // =======================================================================================
    // All the gunk below is the interaction with the Notification Service, which handles
    // the proper ordering of these system-wide.
    // =======================================================================================

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private static INotificationManager sService;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    static private INotificationManager getService() {
        if (sService != null) {
            return sService;
        }
        sService = INotificationManager.Stub.asInterface(ServiceManager.getService("notification"));
        return sService;
    }

    private static class TN extends ITransientNotification.Stub {
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();

        private static final int SHOW = 0;
        private static final int HIDE = 1;
        private static final int CANCEL = 2;
        final Handler mHandler;

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        int mGravity;
        int mX;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        int mY;
        float mHorizontalMargin;
        float mVerticalMargin;


        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        View mView;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        View mNextView;
        int mDuration;

        WindowManager mWM;

        String mPackageName;

        static final long SHORT_DURATION_TIMEOUT = 4000;
        static final long LONG_DURATION_TIMEOUT = 7000;

        TN(String packageName, @Nullable Looper looper) {
            // XXX This should be changed to use a Dialog, with a Theme.Toast
            // defined that sets up the layout params appropriately.
            final WindowManager.LayoutParams params = mParams;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.format = PixelFormat.TRANSLUCENT;
            params.windowAnimations = com.android.internal.R.style.Animation_Toast;
            params.type = WindowManager.LayoutParams.TYPE_TOAST;
            params.setTitle("Toast");
            params.flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

            mPackageName = packageName;

            if (looper == null) {
                // Use Looper.myLooper() if looper is not specified.
                looper = Looper.myLooper();
                if (looper == null) {
                    throw new RuntimeException(
                            "Can't toast on a thread that has not called Looper.prepare()");
                }
            }
            mHandler = new Handler(looper, null) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case SHOW: {
                            IBinder token = (IBinder) msg.obj;
                            handleShow(token);
                            break;
                        }
                        case HIDE: {
                            handleHide();
                            // Don't do this in handleHide() because it is also invoked by
                            // handleShow()
                            mNextView = null;
                            break;
                        }
                        case CANCEL: {
                            handleHide();
                            // Don't do this in handleHide() because it is also invoked by
                            // handleShow()
                            mNextView = null;
                            try {
                                getService().cancelToast(mPackageName, TN.this);
                            } catch (RemoteException e) {
                            }
                            break;
                        }
                    }
                }
            };
        }

        /**
         * schedule handleShow into the right thread
         */
        @Override
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public void show(IBinder windowToken) {
            if (localLOGV) Log.v(TAG, "SHOW: " + this);
            mHandler.obtainMessage(SHOW, windowToken).sendToTarget();
        }

        /**
         * schedule handleHide into the right thread
         */
        @Override
        public void hide() {
            if (localLOGV) Log.v(TAG, "HIDE: " + this);
            mHandler.obtainMessage(HIDE).sendToTarget();
        }

        public void cancel() {
            if (localLOGV) Log.v(TAG, "CANCEL: " + this);
            mHandler.obtainMessage(CANCEL).sendToTarget();
        }

        public void handleShow(IBinder windowToken) {
            if (localLOGV) Log.v(TAG, "HANDLE SHOW: " + this + " mView=" + mView
                    + " mNextView=" + mNextView);
            // If a cancel/hide is pending - no need to show - at this point
            // the window token is already invalid and no need to do any work.
            if (mHandler.hasMessages(CANCEL) || mHandler.hasMessages(HIDE)) {
                return;
            }
            if (mView != mNextView) {
                // remove the old view if necessary
                handleHide();
                mView = mNextView;
                Context context = mView.getContext().getApplicationContext();
                String packageName = mView.getContext().getOpPackageName();
                if (context == null) {
                    context = mView.getContext();
                }
                mWM = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
                // We can resolve the Gravity here by using the Locale for getting
                // the layout direction
                final Configuration config = mView.getContext().getResources().getConfiguration();
                final int gravity = Gravity.getAbsoluteGravity(mGravity, config.getLayoutDirection());
                mParams.gravity = gravity;
                if ((gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.FILL_HORIZONTAL) {
                    mParams.horizontalWeight = 1.0f;
                }
                if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.FILL_VERTICAL) {
                    mParams.verticalWeight = 1.0f;
                }
                mParams.x = mX;
                mParams.y = mY;
                mParams.verticalMargin = mVerticalMargin;
                mParams.horizontalMargin = mHorizontalMargin;
                mParams.packageName = packageName;
                mParams.hideTimeoutMilliseconds = mDuration ==
                    Toast.LENGTH_LONG ? LONG_DURATION_TIMEOUT : SHORT_DURATION_TIMEOUT;
                mParams.token = windowToken;
                if (mView.getParent() != null) {
                    if (localLOGV) Log.v(TAG, "REMOVE! " + mView + " in " + this);
                    mWM.removeView(mView);
                }
                if (localLOGV) Log.v(TAG, "ADD! " + mView + " in " + this);
                // Since the notification manager service cancels the token right
                // after it notifies us to cancel the toast there is an inherent
                // race and we may attempt to add a window after the token has been
                // invalidated. Let us hedge against that.
                try {
                    mWM.addView(mView, mParams);
                    trySendAccessibilityEvent();
                } catch (WindowManager.BadTokenException e) {
                    /* ignore */
                }
            }
        }

        private void trySendAccessibilityEvent() {
            AccessibilityManager accessibilityManager =
                    AccessibilityManager.getInstance(mView.getContext());
            if (!accessibilityManager.isEnabled()) {
                return;
            }
            // treat toasts as notifications since they are used to
            // announce a transient piece of information to the user
            AccessibilityEvent event = AccessibilityEvent.obtain(
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
            event.setClassName(getClass().getName());
            event.setPackageName(mView.getContext().getPackageName());
            mView.dispatchPopulateAccessibilityEvent(event);
            accessibilityManager.sendAccessibilityEvent(event);
        }

        @UnsupportedAppUsage
        public void handleHide() {
            if (localLOGV) Log.v(TAG, "HANDLE HIDE: " + this + " mView=" + mView);
            if (mView != null) {
                // note: checking parent() just to make sure the view has
                // been added...  i have seen cases where we get here when
                // the view isn't yet added, so let's try not to crash.
                if (mView.getParent() != null) {
                    if (localLOGV) Log.v(TAG, "REMOVE! " + mView + " in " + this);
                    mWM.removeViewImmediate(mView);
                }


                // Now that we've removed the view it's safe for the server to release
                // the resources.
                try {
                    getService().finishToken(mPackageName, this);
                } catch (RemoteException e) {
                }

                mView = null;
            }
        }
    }
}
