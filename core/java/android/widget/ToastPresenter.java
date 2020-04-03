/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.checkState;

import android.annotation.Nullable;
import android.app.INotificationManager;
import android.app.ITransientNotificationCallback;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;

/**
 * Class responsible for toast presentation inside app's process and in system UI.
 *
 * @hide
 */
public class ToastPresenter {
    private static final String TAG = "ToastPresenter";
    private static final String WINDOW_TITLE = "Toast";
    private static final long SHORT_DURATION_TIMEOUT = 4000;
    private static final long LONG_DURATION_TIMEOUT = 7000;

    /**
     * Returns the default text toast view for message {@code text}.
     */
    public static View getTextToastView(Context context, CharSequence text) {
        View view = LayoutInflater.from(context).inflate(
                R.layout.transient_notification, null);
        TextView textView = view.findViewById(com.android.internal.R.id.message);
        textView.setText(text);
        return view;
    }

    private final Context mContext;
    private final Resources mResources;
    private final WindowManager mWindowManager;
    private final AccessibilityManager mAccessibilityManager;
    private final INotificationManager mNotificationManager;
    private final String mPackageName;
    private final WindowManager.LayoutParams mParams;
    @Nullable private View mView;
    @Nullable private IBinder mToken;

    public ToastPresenter(Context context, WindowManager windowManager,
            AccessibilityManager accessibilityManager,
            INotificationManager notificationManager, String packageName) {
        mContext = context;
        mResources = context.getResources();
        mWindowManager = windowManager;
        mAccessibilityManager = accessibilityManager;
        mNotificationManager = notificationManager;
        mPackageName = packageName;
        mParams = createLayoutParams();
    }

    public String getPackageName() {
        return mPackageName;
    }

    public WindowManager.LayoutParams getLayoutParams() {
        return mParams;
    }

    /**
     * Returns the {@link View} being shown at the moment or {@code null} if no toast is being
     * displayed.
     */
    @Nullable
    public View getView() {
        return mView;
    }

    /**
     * Returns the {@link IBinder} token used to display the toast or {@code null} if there is no
     * toast being shown at the moment.
     */
    @Nullable
    public IBinder getToken() {
        return mToken;
    }

    /**
     * Creates {@link WindowManager.LayoutParams} with default values for toasts.
     */
    private WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.format = PixelFormat.TRANSLUCENT;
        params.windowAnimations = R.style.Animation_Toast;
        params.type = WindowManager.LayoutParams.TYPE_TOAST;
        params.setFitInsetsIgnoringVisibility(true);
        params.setTitle(WINDOW_TITLE);
        params.flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        setShowForAllUsersIfApplicable(params, mPackageName);
        return params;
    }

    /**
     * Customizes {@code params} according to other parameters, ready to be passed to {@link
     * WindowManager#addView(View, ViewGroup.LayoutParams)}.
     */
    private void adjustLayoutParams(WindowManager.LayoutParams params, IBinder windowToken,
            int duration, int gravity, int xOffset, int yOffset, float horizontalMargin,
            float verticalMargin) {
        Configuration config = mResources.getConfiguration();
        int absGravity = Gravity.getAbsoluteGravity(gravity, config.getLayoutDirection());
        params.gravity = absGravity;
        if ((absGravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.FILL_HORIZONTAL) {
            params.horizontalWeight = 1.0f;
        }
        if ((absGravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.FILL_VERTICAL) {
            params.verticalWeight = 1.0f;
        }
        params.x = xOffset;
        params.y = yOffset;
        params.horizontalMargin = horizontalMargin;
        params.verticalMargin = verticalMargin;
        params.packageName = mContext.getPackageName();
        params.hideTimeoutMilliseconds =
                (duration == Toast.LENGTH_LONG) ? LONG_DURATION_TIMEOUT : SHORT_DURATION_TIMEOUT;
        params.token = windowToken;
    }

    /**
     * Sets {@link WindowManager.LayoutParams#SYSTEM_FLAG_SHOW_FOR_ALL_USERS} flag if {@code
     * packageName} is a cross-user package.
     *
     * <p>Implementation note:
     *     This code is safe to be executed in SystemUI and the app's process:
     *         <li>SystemUI: It's running on a trusted domain so apps can't tamper with it. SystemUI
     *             has the permission INTERNAL_SYSTEM_WINDOW needed by the flag, so SystemUI can add
     *             the flag on behalf of those packages, which all contain INTERNAL_SYSTEM_WINDOW
     *             permission.
     *         <li>App: The flag being added is protected behind INTERNAL_SYSTEM_WINDOW permission
     *             and any app can already add that flag via getWindowParams() if it has that
     *             permission, so we are just doing this automatically for cross-user packages.
     */
    private void setShowForAllUsersIfApplicable(WindowManager.LayoutParams params,
            String packageName) {
        if (isCrossUserPackage(packageName)) {
            params.privateFlags = WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        }
    }

    private boolean isCrossUserPackage(String packageName) {
        String[] packages = mResources.getStringArray(R.array.config_toastCrossUserPackages);
        return ArrayUtils.contains(packages, packageName);
    }

    /**
     * Shows the toast in {@code view} with the parameters passed and callback {@code callback}.
     */
    public void show(View view, IBinder token, IBinder windowToken, int duration, int gravity,
            int xOffset, int yOffset, float horizontalMargin, float verticalMargin,
            @Nullable ITransientNotificationCallback callback) {
        checkState(mView == null, "Only one toast at a time is allowed, call hide() first.");
        mView = view;
        mToken = token;

        adjustLayoutParams(mParams, windowToken, duration, gravity, xOffset, yOffset,
                horizontalMargin, verticalMargin);
        if (mView.getParent() != null) {
            mWindowManager.removeView(mView);
        }
        try {
            mWindowManager.addView(mView, mParams);
        } catch (WindowManager.BadTokenException e) {
            // Since the notification manager service cancels the token right after it notifies us
            // to cancel the toast there is an inherent race and we may attempt to add a window
            // after the token has been invalidated. Let us hedge against that.
            Log.w(TAG, "Error while attempting to show toast from " + mPackageName, e);
            return;
        }
        trySendAccessibilityEvent(mView, mPackageName);
        if (callback != null) {
            try {
                callback.onToastShown();
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling back " + mPackageName + " to notify onToastShow()", e);
            }
        }
    }

    /**
     * Hides toast that was shown using {@link #show(View, IBinder, IBinder, int,
     * int, int, int, float, float, ITransientNotificationCallback)}.
     *
     * <p>This method has to be called on the same thread on which {@link #show(View, IBinder,
     * IBinder, int, int, int, int, float, float, ITransientNotificationCallback)} was called.
     */
    public void hide(@Nullable ITransientNotificationCallback callback) {
        checkState(mView != null, "No toast to hide.");

        if (mView.getParent() != null) {
            mWindowManager.removeViewImmediate(mView);
        }
        try {
            mNotificationManager.finishToken(mPackageName, mToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Error finishing toast window token from package " + mPackageName, e);
        }
        if (callback != null) {
            try {
                callback.onToastHidden();
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling back " + mPackageName + " to notify onToastHide()", e);
            }
        }
        mView = null;
        mToken = null;
    }

    /**
     * Sends {@link AccessibilityEvent#TYPE_NOTIFICATION_STATE_CHANGED} event if accessibility is
     * enabled.
     */
    public void trySendAccessibilityEvent(View view, String packageName) {
        if (!mAccessibilityManager.isEnabled()) {
            return;
        }
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setClassName(Toast.class.getName());
        event.setPackageName(packageName);
        view.dispatchPopulateAccessibilityEvent(event);
        mAccessibilityManager.sendAccessibilityEvent(event);
    }
}
