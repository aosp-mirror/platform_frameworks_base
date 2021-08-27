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

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.INotificationManager;
import android.app.ITransientNotification;
import android.app.ITransientNotificationCallback;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.IAccessibilityManager;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

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
 * <p>
 * Note that
 * <a href="{@docRoot}reference/com/google/android/material/snackbar/Snackbar">Snackbars</a> are
 * preferred for brief messages while the app is in the foreground.
 * <p>
 * Note that toasts being sent from the background are rate limited, so avoid sending such toasts
 * in quick succession.
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

    /**
     * Text toasts will be rendered by SystemUI instead of in-app, so apps can't circumvent
     * background custom toast restrictions.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long CHANGE_TEXT_TOASTS_IN_THE_SYSTEM = 147798919L;


    private final Binder mToken;
    private final Context mContext;
    private final Handler mHandler;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    final TN mTN;
    @UnsupportedAppUsage
    int mDuration;

    /**
     * This is also passed to {@link TN} object, where it's also accessed with itself as its own
     * lock.
     */
    @GuardedBy("mCallbacks")
    private final List<Callback> mCallbacks;

    /**
     * View to be displayed, in case this is a custom toast (e.g. not created with {@link
     * #makeText(Context, int, int)} or its variants).
     */
    @Nullable
    private View mNextView;

    /**
     * Text to be shown, in case this is NOT a custom toast (e.g. created with {@link
     * #makeText(Context, int, int)} or its variants).
     */
    @Nullable
    private CharSequence mText;

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
        mToken = new Binder();
        looper = getLooper(looper);
        mHandler = new Handler(looper);
        mCallbacks = new ArrayList<>();
        mTN = new TN(context, context.getPackageName(), mToken,
                mCallbacks, looper);
        mTN.mY = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.toast_y_offset);
        mTN.mGravity = context.getResources().getInteger(
                com.android.internal.R.integer.config_toastDefaultGravity);
    }

    private Looper getLooper(@Nullable Looper looper) {
        if (looper != null) {
            return looper;
        }
        return checkNotNull(Looper.myLooper(),
                "Can't toast on a thread that has not called Looper.prepare()");
    }

    /**
     * Show the view for the specified duration.
     */
    public void show() {
        if (Compatibility.isChangeEnabled(CHANGE_TEXT_TOASTS_IN_THE_SYSTEM)) {
            checkState(mNextView != null || mText != null, "You must either set a text or a view");
        } else {
            if (mNextView == null) {
                throw new RuntimeException("setView must have been called");
            }
        }

        INotificationManager service = getService();
        String pkg = mContext.getOpPackageName();
        TN tn = mTN;
        tn.mNextView = mNextView;
        final int displayId = mContext.getDisplayId();

        try {
            if (Compatibility.isChangeEnabled(CHANGE_TEXT_TOASTS_IN_THE_SYSTEM)) {
                if (mNextView != null) {
                    // It's a custom toast
                    service.enqueueToast(pkg, mToken, tn, mDuration, displayId);
                } else {
                    // It's a text toast
                    ITransientNotificationCallback callback =
                            new CallbackBinder(mCallbacks, mHandler);
                    service.enqueueTextToast(pkg, mToken, mText, mDuration, displayId, callback);
                }
            } else {
                service.enqueueToast(pkg, mToken, tn, mDuration, displayId);
            }
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
        if (Compatibility.isChangeEnabled(CHANGE_TEXT_TOASTS_IN_THE_SYSTEM)
                && mNextView == null) {
            try {
                getService().cancelToast(mContext.getOpPackageName(), mToken);
            } catch (RemoteException e) {
                // Empty
            }
        } else {
            mTN.cancel();
        }
    }

    /**
     * Set the view to show.
     *
     * @see #getView
     * @deprecated Custom toast views are deprecated. Apps can create a standard text toast with the
     *      {@link #makeText(Context, CharSequence, int)} method, or use a
     *      <a href="{@docRoot}reference/com/google/android/material/snackbar/Snackbar">Snackbar</a>
     *      when in the foreground. Starting from Android {@link Build.VERSION_CODES#R}, apps
     *      targeting API level {@link Build.VERSION_CODES#R} or higher that are in the background
     *      will not have custom toast views displayed.
     */
    @Deprecated
    public void setView(View view) {
        mNextView = view;
    }

    /**
     * Return the view.
     *
     * <p>Toasts constructed with {@link #Toast(Context)} that haven't called {@link #setView(View)}
     * with a non-{@code null} view will return {@code null} here.
     *
     * <p>Starting from Android {@link Build.VERSION_CODES#R}, in apps targeting API level {@link
     * Build.VERSION_CODES#R} or higher, toasts constructed with {@link #makeText(Context,
     * CharSequence, int)} or its variants will also return {@code null} here unless they had called
     * {@link #setView(View)} with a non-{@code null} view. If you want to be notified when the
     * toast is shown or hidden, use {@link #addCallback(Callback)}.
     *
     * @see #setView
     * @deprecated Custom toast views are deprecated. Apps can create a standard text toast with the
     *      {@link #makeText(Context, CharSequence, int)} method, or use a
     *      <a href="{@docRoot}reference/com/google/android/material/snackbar/Snackbar">Snackbar</a>
     *      when in the foreground. Starting from Android {@link Build.VERSION_CODES#R}, apps
     *      targeting API level {@link Build.VERSION_CODES#R} or higher that are in the background
     *      will not have custom toast views displayed.
     */
    @Deprecated
    @Nullable public View getView() {
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
     * <p><strong>Warning:</strong> Starting from Android {@link Build.VERSION_CODES#R}, for apps
     * targeting API level {@link Build.VERSION_CODES#R} or higher, this method is a no-op when
     * called on text toasts.
     *
     * @param horizontalMargin The horizontal margin, in percentage of the
     *        container width, between the container's edges and the
     *        notification
     * @param verticalMargin The vertical margin, in percentage of the
     *        container height, between the container's edges and the
     *        notification
     */
    public void setMargin(float horizontalMargin, float verticalMargin) {
        if (isSystemRenderedTextToast()) {
            Log.e(TAG, "setMargin() shouldn't be called on text toasts, the values won't be used");
        }
        mTN.mHorizontalMargin = horizontalMargin;
        mTN.mVerticalMargin = verticalMargin;
    }

    /**
     * Return the horizontal margin.
     *
     * <p><strong>Warning:</strong> Starting from Android {@link Build.VERSION_CODES#R}, for apps
     * targeting API level {@link Build.VERSION_CODES#R} or higher, this method shouldn't be called
     * on text toasts as its return value may not reflect actual value since text toasts are not
     * rendered by the app anymore.
     */
    public float getHorizontalMargin() {
        if (isSystemRenderedTextToast()) {
            Log.e(TAG, "getHorizontalMargin() shouldn't be called on text toasts, the result may "
                    + "not reflect actual values.");
        }
        return mTN.mHorizontalMargin;
    }

    /**
     * Return the vertical margin.
     *
     * <p><strong>Warning:</strong> Starting from Android {@link Build.VERSION_CODES#R}, for apps
     * targeting API level {@link Build.VERSION_CODES#R} or higher, this method shouldn't be called
     * on text toasts as its return value may not reflect actual value since text toasts are not
     * rendered by the app anymore.
     */
    public float getVerticalMargin() {
        if (isSystemRenderedTextToast()) {
            Log.e(TAG, "getVerticalMargin() shouldn't be called on text toasts, the result may not"
                    + " reflect actual values.");
        }
        return mTN.mVerticalMargin;
    }

    /**
     * Set the location at which the notification should appear on the screen.
     *
     * <p><strong>Warning:</strong> Starting from Android {@link Build.VERSION_CODES#R}, for apps
     * targeting API level {@link Build.VERSION_CODES#R} or higher, this method is a no-op when
     * called on text toasts.
     *
     * @see android.view.Gravity
     * @see #getGravity
     */
    public void setGravity(int gravity, int xOffset, int yOffset) {
        if (isSystemRenderedTextToast()) {
            Log.e(TAG, "setGravity() shouldn't be called on text toasts, the values won't be used");
        }
        mTN.mGravity = gravity;
        mTN.mX = xOffset;
        mTN.mY = yOffset;
    }

     /**
     * Get the location at which the notification should appear on the screen.
     *
     * <p><strong>Warning:</strong> Starting from Android {@link Build.VERSION_CODES#R}, for apps
     * targeting API level {@link Build.VERSION_CODES#R} or higher, this method shouldn't be called
     * on text toasts as its return value may not reflect actual value since text toasts are not
     * rendered by the app anymore.
     *
     * @see android.view.Gravity
     * @see #getGravity
     */
    public int getGravity() {
        if (isSystemRenderedTextToast()) {
            Log.e(TAG, "getGravity() shouldn't be called on text toasts, the result may not reflect"
                    + " actual values.");
        }
        return mTN.mGravity;
    }

    /**
     * Return the X offset in pixels to apply to the gravity's location.
     *
     * <p><strong>Warning:</strong> Starting from Android {@link Build.VERSION_CODES#R}, for apps
     * targeting API level {@link Build.VERSION_CODES#R} or higher, this method shouldn't be called
     * on text toasts as its return value may not reflect actual value since text toasts are not
     * rendered by the app anymore.
     */
    public int getXOffset() {
        if (isSystemRenderedTextToast()) {
            Log.e(TAG, "getXOffset() shouldn't be called on text toasts, the result may not reflect"
                    + " actual values.");
        }
        return mTN.mX;
    }

    /**
     * Return the Y offset in pixels to apply to the gravity's location.
     *
     * <p><strong>Warning:</strong> Starting from Android {@link Build.VERSION_CODES#R}, for apps
     * targeting API level {@link Build.VERSION_CODES#R} or higher, this method shouldn't be called
     * on text toasts as its return value may not reflect actual value since text toasts are not
     * rendered by the app anymore.
     */
    public int getYOffset() {
        if (isSystemRenderedTextToast()) {
            Log.e(TAG, "getYOffset() shouldn't be called on text toasts, the result may not reflect"
                    + " actual values.");
        }
        return mTN.mY;
    }

    private boolean isSystemRenderedTextToast() {
        return Compatibility.isChangeEnabled(CHANGE_TEXT_TOASTS_IN_THE_SYSTEM) && mNextView == null;
    }

    /**
     * Adds a callback to be notified when the toast is shown or hidden.
     *
     * Note that if the toast is blocked for some reason you won't get a call back.
     *
     * @see #removeCallback(Callback)
     */
    public void addCallback(@NonNull Callback callback) {
        checkNotNull(callback);
        synchronized (mCallbacks) {
            mCallbacks.add(callback);
        }
    }

    /**
     * Removes a callback previously added with {@link #addCallback(Callback)}.
     */
    public void removeCallback(@NonNull Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
    }

    /**
     * Gets the LayoutParams for the Toast window.
     * @hide
     */
    @UnsupportedAppUsage
    @Nullable public WindowManager.LayoutParams getWindowParams() {
        if (Compatibility.isChangeEnabled(CHANGE_TEXT_TOASTS_IN_THE_SYSTEM)) {
            if (mNextView != null) {
                // Custom toasts
                return mTN.mParams;
            } else {
                // Text toasts
                return null;
            }
        } else {
            // Text and custom toasts are app-rendered
            return mTN.mParams;
        }
    }

    /**
     * Make a standard toast that just contains text.
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
     *
     * @hide
     */
    public static Toast makeText(@NonNull Context context, @Nullable Looper looper,
            @NonNull CharSequence text, @Duration int duration) {
        if (Compatibility.isChangeEnabled(CHANGE_TEXT_TOASTS_IN_THE_SYSTEM)) {
            Toast result = new Toast(context, looper);
            result.mText = text;
            result.mDuration = duration;
            return result;
        } else {
            Toast result = new Toast(context, looper);
            View v = ToastPresenter.getTextToastView(context, text);
            result.mNextView = v;
            result.mDuration = duration;

            return result;
        }
    }

    /**
     * Make a standard toast that just contains text from a resource.
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
        if (Compatibility.isChangeEnabled(CHANGE_TEXT_TOASTS_IN_THE_SYSTEM)) {
            if (mNextView != null) {
                throw new IllegalStateException(
                        "Text provided for custom toast, remove previous setView() calls if you "
                                + "want a text toast instead.");
            }
            mText = s;
        } else {
            if (mNextView == null) {
                throw new RuntimeException("This Toast was not created with Toast.makeText()");
            }
            TextView tv = mNextView.findViewById(com.android.internal.R.id.message);
            if (tv == null) {
                throw new RuntimeException("This Toast was not created with Toast.makeText()");
            }
            tv.setText(s);
        }
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
        sService = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        return sService;
    }

    private static class TN extends ITransientNotification.Stub {
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        private final WindowManager.LayoutParams mParams;

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

        final String mPackageName;
        final Binder mToken;
        private final ToastPresenter mPresenter;

        @GuardedBy("mCallbacks")
        private final List<Callback> mCallbacks;

        /**
         * Creates a {@link ITransientNotification} object.
         *
         * The parameter {@code callbacks} is not copied and is accessed with itself as its own
         * lock.
         */
        TN(Context context, String packageName, Binder token, List<Callback> callbacks,
                @Nullable Looper looper) {
            IAccessibilityManager accessibilityManager = IAccessibilityManager.Stub.asInterface(
                    ServiceManager.getService(Context.ACCESSIBILITY_SERVICE));
            mPresenter = new ToastPresenter(context, accessibilityManager, getService(),
                    packageName);
            mParams = mPresenter.getLayoutParams();
            mPackageName = packageName;
            mToken = token;
            mCallbacks = callbacks;

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
                                getService().cancelToast(mPackageName, mToken);
                            } catch (RemoteException e) {
                            }
                            break;
                        }
                    }
                }
            };
        }

        private List<Callback> getCallbacks() {
            synchronized (mCallbacks) {
                return new ArrayList<>(mCallbacks);
            }
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
                mPresenter.show(mView, mToken, windowToken, mDuration, mGravity, mX, mY,
                        mHorizontalMargin, mVerticalMargin,
                        new CallbackBinder(getCallbacks(), mHandler));
            }
        }

        @UnsupportedAppUsage
        public void handleHide() {
            if (localLOGV) Log.v(TAG, "HANDLE HIDE: " + this + " mView=" + mView);
            if (mView != null) {
                checkState(mView == mPresenter.getView(),
                        "Trying to hide toast view different than the last one displayed");
                mPresenter.hide(new CallbackBinder(getCallbacks(), mHandler));
                mView = null;
            }
        }
    }

    /**
     * Callback object to be called when the toast is shown or hidden.
     *
     * @see #makeText(Context, CharSequence, int)
     * @see #addCallback(Callback)
     */
    public abstract static class Callback {
        /**
         * Called when the toast is displayed on the screen.
         */
        public void onToastShown() {}

        /**
         * Called when the toast is hidden.
         */
        public void onToastHidden() {}
    }

    private static class CallbackBinder extends ITransientNotificationCallback.Stub {
        private final Handler mHandler;

        @GuardedBy("mCallbacks")
        private final List<Callback> mCallbacks;

        /**
         * Creates a {@link ITransientNotificationCallback} object.
         *
         * The parameter {@code callbacks} is not copied and is accessed with itself as its own
         * lock.
         */
        private CallbackBinder(List<Callback> callbacks, Handler handler) {
            mCallbacks = callbacks;
            mHandler = handler;
        }

        @Override
        public void onToastShown() {
            mHandler.post(() -> {
                for (Callback callback : getCallbacks()) {
                    callback.onToastShown();
                }
            });
        }

        @Override
        public void onToastHidden() {
            mHandler.post(() -> {
                for (Callback callback : getCallbacks()) {
                    callback.onToastHidden();
                }
            });
        }

        private List<Callback> getCallbacks() {
            synchronized (mCallbacks) {
                return new ArrayList<>(mCallbacks);
            }
        }
    }
}
