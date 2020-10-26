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

import android.animation.Animator;
import android.annotation.MainThread;
import android.annotation.Nullable;
import android.app.INotificationManager;
import android.app.ITransientNotificationCallback;
import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.widget.Toast;
import android.widget.ToastPresenter;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.Objects;

import javax.inject.Inject;

/**
 * Controls display of text toasts.
 */
@SysUISingleton
public class ToastUI extends SystemUI implements CommandQueue.Callbacks {
    // values from NotificationManagerService#LONG_DELAY and NotificationManagerService#SHORT_DELAY
    private static final int TOAST_LONG_TIME = 3500; // 3.5 seconds
    private static final int TOAST_SHORT_TIME = 2000; // 2 seconds

    private static final String TAG = "ToastUI";

    private final CommandQueue mCommandQueue;
    private final INotificationManager mNotificationManager;
    private final IAccessibilityManager mIAccessibilityManager;
    private final AccessibilityManager mAccessibilityManager;
    private final ToastFactory mToastFactory;
    private final DelayableExecutor mMainExecutor;
    private final ToastLogger mToastLogger;
    private SystemUIToast mToast;
    @Nullable private ToastPresenter mPresenter;
    @Nullable private ITransientNotificationCallback mCallback;

    @Inject
    public ToastUI(
            Context context,
            CommandQueue commandQueue,
            ToastFactory toastFactory,
            @Main DelayableExecutor mainExecutor,
            ToastLogger toastLogger) {
        this(context, commandQueue,
                INotificationManager.Stub.asInterface(
                        ServiceManager.getService(Context.NOTIFICATION_SERVICE)),
                IAccessibilityManager.Stub.asInterface(
                        ServiceManager.getService(Context.ACCESSIBILITY_SERVICE)),
                toastFactory,
                mainExecutor,
                toastLogger);
    }

    @VisibleForTesting
    ToastUI(Context context, CommandQueue commandQueue, INotificationManager notificationManager,
            @Nullable IAccessibilityManager accessibilityManager,
            ToastFactory toastFactory, DelayableExecutor mainExecutor, ToastLogger toastLogger
    ) {
        super(context);
        mCommandQueue = commandQueue;
        mNotificationManager = notificationManager;
        mIAccessibilityManager = accessibilityManager;
        mToastFactory = toastFactory;
        mMainExecutor = mainExecutor;
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
        mToastLogger = toastLogger;
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
    }

    @Override
    @MainThread
    public void showToast(int uid, String packageName, IBinder token, CharSequence text,
            IBinder windowToken, int duration, @Nullable ITransientNotificationCallback callback) {
        if (mPresenter != null) {
            hideCurrentToast();
        }
        UserHandle userHandle = UserHandle.getUserHandleForUid(uid);
        Context context = mContext.createContextAsUser(userHandle, 0);
        mToast = mToastFactory.createToast(context, text, packageName, userHandle.getIdentifier());

        if (mToast.hasCustomAnimation()) {
            if (mToast.getInAnimation() != null) {
                mToast.getInAnimation().start();
            }
            final Animator hideAnimator = mToast.getOutAnimation();
            if (hideAnimator != null) {
                final long durationMillis = duration == Toast.LENGTH_LONG
                        ? TOAST_LONG_TIME : TOAST_SHORT_TIME;
                final long updatedDuration = mAccessibilityManager.getRecommendedTimeoutMillis(
                        (int) durationMillis, AccessibilityManager.FLAG_CONTENT_TEXT);
                mMainExecutor.executeDelayed(() -> hideAnimator.start(),
                        updatedDuration - hideAnimator.getTotalDuration());
            }
        }
        mCallback = callback;
        mPresenter = new ToastPresenter(context, mIAccessibilityManager, mNotificationManager,
                packageName);
        // Set as trusted overlay so touches can pass through toasts
        mPresenter.getLayoutParams().setTrustedOverlay();
        mToastLogger.logOnShowToast(uid, packageName, text.toString(), token.toString());
        mPresenter.show(mToast.getView(), token, windowToken, duration, mToast.getGravity(),
                mToast.getXOffset(), mToast.getYOffset(), mToast.getHorizontalMargin(),
                mToast.getVerticalMargin(), mCallback, mToast.hasCustomAnimation());
    }

    @Override
    @MainThread
    public void hideToast(String packageName, IBinder token) {
        if (mPresenter == null || !Objects.equals(mPresenter.getPackageName(), packageName)
                || !Objects.equals(mPresenter.getToken(), token)) {
            Log.w(TAG, "Attempt to hide non-current toast from package " + packageName);
            return;
        }
        mToastLogger.logOnHideToast(packageName, token.toString());
        hideCurrentToast();
    }

    @MainThread
    private void hideCurrentToast() {
        mPresenter.hide(mCallback);
        mPresenter = null;
    }
}
