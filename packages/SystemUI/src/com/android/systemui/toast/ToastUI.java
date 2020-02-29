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
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;
import android.widget.ToastPresenter;

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
    private final ToastPresenter mPresenter;
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
        mPresenter = new ToastPresenter(context, accessibilityManager);
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
        View view = mPresenter.getTextToastView(text);
        LayoutParams params = getLayoutParams(packageName, windowToken, duration);
        mCurrentToast = new ToastEntry(packageName, token, view, windowToken, callback);
        try {
            mWindowManager.addView(view, params);
        } catch (WindowManager.BadTokenException e) {
            Log.w(TAG, "Error while attempting to show toast from " + packageName, e);
            return;
        }
        mPresenter.trySendAccessibilityEvent(view, packageName);
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

    private LayoutParams getLayoutParams(String packageName, IBinder windowToken, int duration) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        mPresenter.startLayoutParams(params, packageName);
        int gravity = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_toastDefaultGravity);
        int yOffset = mContext.getResources().getDimensionPixelSize(R.dimen.toast_y_offset);
        mPresenter.adjustLayoutParams(params, windowToken, duration, gravity, 0, yOffset, 0, 0);
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
