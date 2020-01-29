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

package com.android.systemui.toast;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.app.INotificationManager;
import android.app.ITransientNotificationCallback;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.CommandQueue;

import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controls display of text toasts.
 */
@Singleton
public class ToastUI extends SystemUI implements CommandQueue.Callbacks {
    private static final String TAG = "ToastUI";

    /**
     * Values taken from {@link Toast}.
     */
    private static final long DURATION_SHORT = 4000;
    private static final long DURATION_LONG = 7000;

    private final CommandQueue mCommandQueue;
    private final WindowManager mWindowManager;
    private final INotificationManager mNotificationManager;
    private final AccessibilityManager mAccessibilityManager;
    private ToastEntry mCurrentToast;

    @Inject
    public ToastUI(Context context, CommandQueue commandQueue) {
        this(context, commandQueue,
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE),
                INotificationManager.Stub.asInterface(
                        ServiceManager.getService(Context.NOTIFICATION_SERVICE)),
                AccessibilityManager.getInstance(context));
    }

    @VisibleForTesting
    ToastUI(Context context, CommandQueue commandQueue, WindowManager windowManager,
            INotificationManager notificationManager, AccessibilityManager accessibilityManager) {
        super(context);
        mCommandQueue = commandQueue;
        mWindowManager = windowManager;
        mNotificationManager = notificationManager;
        mAccessibilityManager = accessibilityManager;
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
    }

    @Override
    @MainThread
    public void showToast(String packageName, IBinder token, CharSequence text,
            IBinder windowToken, int duration, @Nullable ITransientNotificationCallback callback) {
        if (mCurrentToast != null) {
            hideCurrentToast();
        }
        View view = getView(text);
        LayoutParams params = getLayoutParams(windowToken, duration);
        mCurrentToast = new ToastEntry(packageName, token, view, windowToken, callback);
        try {
            mWindowManager.addView(view, params);
        } catch (WindowManager.BadTokenException e) {
            Log.w(TAG, "Error while attempting to show toast from " + packageName, e);
            return;
        }
        trySendAccessibilityEvent(view, packageName);
        if (callback != null) {
            try {
                callback.onToastShown();
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling back " + packageName + " to notify onToastShow()", e);
            }
        }
    }

    @Override
    @MainThread
    public void hideToast(String packageName, IBinder token) {
        if (mCurrentToast == null || !Objects.equals(mCurrentToast.packageName, packageName)
                || !Objects.equals(mCurrentToast.token, token)) {
            Log.w(TAG, "Attempt to hide non-current toast from package " + packageName);
            return;
        }
        hideCurrentToast();
    }

    @MainThread
    private void hideCurrentToast() {
        if (mCurrentToast.view.getParent() != null) {
            mWindowManager.removeViewImmediate(mCurrentToast.view);
        }
        String packageName = mCurrentToast.packageName;
        try {
            mNotificationManager.finishToken(packageName, mCurrentToast.windowToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Error finishing toast window token from package " + packageName, e);
        }
        if (mCurrentToast.callback != null) {
            try {
                mCurrentToast.callback.onToastHidden();
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling back " + packageName + " to notify onToastHide()", e);
            }
        }
        mCurrentToast = null;
    }

    private void trySendAccessibilityEvent(View view, String packageName) {
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

    private View getView(CharSequence text) {
        View view = LayoutInflater.from(mContext).inflate(
                R.layout.transient_notification, null);
        TextView textView = view.findViewById(com.android.internal.R.id.message);
        textView.setText(text);
        return view;
    }

    private LayoutParams getLayoutParams(IBinder windowToken, int duration) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.format = PixelFormat.TRANSLUCENT;
        params.windowAnimations = com.android.internal.R.style.Animation_Toast;
        params.type = WindowManager.LayoutParams.TYPE_TOAST;
        params.setTitle("Toast");
        params.flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        Configuration config = mContext.getResources().getConfiguration();
        int specificGravity = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_toastDefaultGravity);
        int gravity = Gravity.getAbsoluteGravity(specificGravity, config.getLayoutDirection());
        params.gravity = gravity;
        if ((gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.FILL_HORIZONTAL) {
            params.horizontalWeight = 1.0f;
        }
        if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.FILL_VERTICAL) {
            params.verticalWeight = 1.0f;
        }
        params.x = 0;
        params.y = mContext.getResources().getDimensionPixelSize(R.dimen.toast_y_offset);
        params.verticalMargin = 0;
        params.horizontalMargin = 0;
        params.packageName = mContext.getPackageName();
        params.hideTimeoutMilliseconds =
                (duration == Toast.LENGTH_LONG) ? DURATION_LONG : DURATION_SHORT;
        params.token = windowToken;
        return params;
    }

    private static class ToastEntry {
        public final String packageName;
        public final IBinder token;
        public final View view;
        public final IBinder windowToken;

        @Nullable
        public final ITransientNotificationCallback callback;

        private ToastEntry(String packageName, IBinder token, View view, IBinder windowToken,
                @Nullable ITransientNotificationCallback callback) {
            this.packageName = packageName;
            this.token = token;
            this.view = view;
            this.windowToken = windowToken;
            this.callback = callback;
        }
    }
}
