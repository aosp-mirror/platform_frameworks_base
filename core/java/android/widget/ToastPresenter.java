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

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.IBinder;
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
    private static final long SHORT_DURATION_TIMEOUT = 4000;
    private static final long LONG_DURATION_TIMEOUT = 7000;

    private final Context mContext;
    private final Resources mResources;
    private final AccessibilityManager mAccessibilityManager;

    public ToastPresenter(Context context, AccessibilityManager accessibilityManager) {
        mContext = context;
        mResources = context.getResources();
        mAccessibilityManager = accessibilityManager;
    }

    /**
     * Initializes {@code params} with default values for toasts.
     */
    public void startLayoutParams(WindowManager.LayoutParams params, String packageName) {
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.format = PixelFormat.TRANSLUCENT;
        params.windowAnimations = R.style.Animation_Toast;
        params.type = WindowManager.LayoutParams.TYPE_TOAST;
        params.setFitInsetsIgnoringVisibility(true);
        params.setTitle("Toast");
        params.flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        setShowForAllUsersIfApplicable(params, packageName);
    }

    /**
     * Customizes {@code params} according to other parameters, ready to be passed to {@link
     * WindowManager#addView(View, ViewGroup.LayoutParams)}.
     */
    public void adjustLayoutParams(WindowManager.LayoutParams params, IBinder windowToken,
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
     * Implementation note:
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
     * Returns the default text toast view for message {@code text}.
     */
    public View getTextToastView(CharSequence text) {
        View view = LayoutInflater.from(mContext).inflate(
                R.layout.transient_notification, null);
        TextView textView = view.findViewById(com.android.internal.R.id.message);
        textView.setText(text);
        return view;
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
