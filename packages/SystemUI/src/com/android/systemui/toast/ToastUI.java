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
import android.content.res.Resources;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
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

    private final CommandQueue mCommandQueue;
    private final WindowManager mWindowManager;
    private final INotificationManager mNotificationManager;
    private final AccessibilityManager mAccessibilityManager;
    private final int mGravity;
    private final int mY;
    @Nullable private ToastPresenter mPresenter;
    @Nullable private ITransientNotificationCallback mCallback;

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
        Resources resources = mContext.getResources();
        mGravity = resources.getInteger(R.integer.config_toastDefaultGravity);
        mY = resources.getDimensionPixelSize(R.dimen.toast_y_offset);
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
    }

    @Override
    @MainThread
    public void showToast(String packageName, IBinder token, CharSequence text,
            IBinder windowToken, int duration, @Nullable ITransientNotificationCallback callback) {
        if (mPresenter != null) {
            hideCurrentToast();
        }
        View view = ToastPresenter.getTextToastView(mContext, text);
        mCallback = callback;
        mPresenter = new ToastPresenter(mContext, mWindowManager, mAccessibilityManager,
                mNotificationManager, packageName);
        mPresenter.show(view, token, windowToken, duration, mGravity, 0, mY, 0, 0, mCallback);
    }

    @Override
    @MainThread
    public void hideToast(String packageName, IBinder token) {
        if (mPresenter == null || !Objects.equals(mPresenter.getPackageName(), packageName)
                || !Objects.equals(mPresenter.getToken(), token)) {
            Log.w(TAG, "Attempt to hide non-current toast from package " + packageName);
            return;
        }
        hideCurrentToast();
    }

    @MainThread
    private void hideCurrentToast() {
        mPresenter.hide(mCallback);
        mPresenter = null;
    }
}
