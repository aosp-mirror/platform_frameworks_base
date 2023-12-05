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
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.lang.ref.WeakReference;

/**
 * Class responsible for toast presentation inside app's process and in system UI.
 *
 * @hide
 */
public class ToastPresenter {
    private static final String TAG = "ToastPresenter";
    private static final String WINDOW_TITLE = "Toast";

    // exclusively used to guarantee window timeouts
    private static final long SHORT_DURATION_TIMEOUT = 4000;
    private static final long LONG_DURATION_TIMEOUT = 7000;

    @VisibleForTesting
    public static final int TEXT_TOAST_LAYOUT = R.layout.transient_notification;
    @VisibleForTesting
    public static final int TEXT_TOAST_LAYOUT_WITH_ICON = R.layout.transient_notification_with_icon;

    /**
     * Returns the default text toast view for message {@code text}.
     */
    public static View getTextToastView(Context context, CharSequence text) {
        View view = LayoutInflater.from(context).inflate(TEXT_TOAST_LAYOUT, null);
        TextView textView = view.findViewById(com.android.internal.R.id.message);
        textView.setText(text);
        return view;
    }

    /**
     * Returns the default icon text toast view for message {@code text} and the icon {@code icon}.
     */
    public static View getTextToastViewWithIcon(Context context, CharSequence text, Drawable icon) {
        if (icon == null) {
            return getTextToastView(context, text);
        }

        View view = LayoutInflater.from(context).inflate(TEXT_TOAST_LAYOUT_WITH_ICON, null);
        TextView textView = view.findViewById(com.android.internal.R.id.message);
        textView.setText(text);
        ImageView imageView = view.findViewById(com.android.internal.R.id.icon);
        if (imageView != null) {
            imageView.setImageDrawable(icon);
        }
        return view;
    }

    private final WeakReference<Context> mContext;
    private final Resources mResources;
    private final IAccessibilityManager mAccessibilityManagerService;
    private final INotificationManager mNotificationManager;
    private final String mPackageName;
    private final String mContextPackageName;
    private final WindowManager.LayoutParams mParams;
    @Nullable private View mView;
    @Nullable private IBinder mToken;

    public ToastPresenter(Context context, IAccessibilityManager accessibilityManager,
            INotificationManager notificationManager, String packageName) {
        mContext = new WeakReference<>(context);
        mResources = context.getResources();
        mNotificationManager = notificationManager;
        mPackageName = packageName;
        mContextPackageName = context.getPackageName();
        mParams = createLayoutParams();
        mAccessibilityManagerService = accessibilityManager;
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
            float verticalMargin, boolean removeWindowAnimations) {
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
        params.packageName = mContextPackageName;
        params.hideTimeoutMilliseconds =
                (duration == Toast.LENGTH_LONG) ? LONG_DURATION_TIMEOUT : SHORT_DURATION_TIMEOUT;
        params.token = windowToken;

        if (removeWindowAnimations && params.windowAnimations == R.style.Animation_Toast) {
            params.windowAnimations = 0;
        }
    }

    /**
     * Update the LayoutParameters of the currently showing toast view. This is used for layout
     * updates based on orientation changes.
     */
    public void updateLayoutParams(int xOffset, int yOffset, float horizontalMargin,
            float verticalMargin, int gravity) {
        checkState(mView != null, "Toast must be showing to update its layout parameters.");
        Configuration config = mResources.getConfiguration();
        mParams.gravity = Gravity.getAbsoluteGravity(gravity, config.getLayoutDirection());
        mParams.x = xOffset;
        mParams.y = yOffset;
        mParams.horizontalMargin = horizontalMargin;
        mParams.verticalMargin = verticalMargin;
        mView.setLayoutParams(mParams);
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
     * Uses window animations to animate the toast.
     */
    public void show(View view, IBinder token, IBinder windowToken, int duration, int gravity,
            int xOffset, int yOffset, float horizontalMargin, float verticalMargin,
            @Nullable ITransientNotificationCallback callback) {
        show(view, token, windowToken, duration, gravity, xOffset, yOffset, horizontalMargin,
                verticalMargin, callback, false /* removeWindowAnimations */);
    }

    /**
     * Shows the toast in {@code view} with the parameters passed and callback {@code callback}.
     * Can optionally remove window animations from the toast window.
     */
    public void show(View view, IBinder token, IBinder windowToken, int duration, int gravity,
            int xOffset, int yOffset, float horizontalMargin, float verticalMargin,
            @Nullable ITransientNotificationCallback callback, boolean removeWindowAnimations) {
        checkState(mView == null, "Only one toast at a time is allowed, call hide() first.");
        mView = view;
        mToken = token;

        adjustLayoutParams(mParams, windowToken, duration, gravity, xOffset, yOffset,
                horizontalMargin, verticalMargin, removeWindowAnimations);
        addToastView();
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

        final WindowManager windowManager = getWindowManager(mView);
        if (mView.getParent() != null && windowManager != null) {
            windowManager.removeViewImmediate(mView);
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
                Log.w(TAG, "Error calling back " + mPackageName + " to notify onToastHide()",
                        e);
            }
        }
        mView = null;
        mToken = null;
    }

    private WindowManager getWindowManager(View view) {
        Context context = mContext.get();
        if (context == null && view != null) {
            context = view.getContext();
        }
        if (context != null) {
            return context.getSystemService(WindowManager.class);
        }
        return null;
    }

    /**
     * Sends {@link AccessibilityEvent#TYPE_NOTIFICATION_STATE_CHANGED} event if accessibility is
     * enabled.
     */
    public void trySendAccessibilityEvent(View view, String packageName) {
        final Context context = mContext.get();
        if (context == null) {
            return;
        }

        // We obtain AccessibilityManager manually via its constructor instead of using method
        // AccessibilityManager.getInstance() for 2 reasons:
        //   1. We want to be able to inject IAccessibilityManager in tests to verify behavior.
        //   2. getInstance() caches the instance for the process even if we pass a different
        //      context to it. This is problematic for multi-user because callers can pass a context
        //      created via Context.createContextAsUser().
        final AccessibilityManager accessibilityManager = new AccessibilityManager(context,
                    mAccessibilityManagerService, context.getUserId());

        if (!accessibilityManager.isEnabled()) {
            accessibilityManager.removeClient();
            return;
        }
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setClassName(Toast.class.getName());
        event.setPackageName(packageName);
        view.dispatchPopulateAccessibilityEvent(event);
        accessibilityManager.sendAccessibilityEvent(event);
        // Every new instance of A11yManager registers an IA11yManagerClient object with the
        // backing service. This client isn't removed until the calling process is destroyed,
        // causing a leak here. We explicitly remove the client.
        accessibilityManager.removeClient();
    }

    private void addToastView() {
        final WindowManager windowManager = getWindowManager(mView);
        if (windowManager == null) {
            return;
        }
        if (mView.getParent() != null) {
            windowManager.removeView(mView);
        }
        try {
            windowManager.addView(mView, mParams);
        } catch (WindowManager.BadTokenException e) {
            // Since the notification manager service cancels the token right after it notifies us
            // to cancel the toast there is an inherent race and we may attempt to add a window
            // after the token has been invalidated. Let us hedge against that.
            Log.w(TAG, "Error while attempting to show toast from " + mPackageName, e);
        } catch (WindowManager.InvalidDisplayException e) {
            // Display the toast was scheduled on might have been meanwhile removed.
            Log.w(TAG, "Cannot show toast from " + mPackageName
                    + " on display it was scheduled on.", e);
        }
    }
}
