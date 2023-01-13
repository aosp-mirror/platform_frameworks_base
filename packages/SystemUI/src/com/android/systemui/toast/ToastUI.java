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

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.INotificationManager;
import android.app.ITransientNotificationCallback;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.widget.ToastPresenter;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.CommandQueue;

import java.util.Objects;

import javax.inject.Inject;

/**
 * Controls display of text toasts.
 */
@SysUISingleton
public class ToastUI implements CoreStartable, CommandQueue.Callbacks {
    // values from NotificationManagerService#LONG_DELAY and NotificationManagerService#SHORT_DELAY
    private static final int TOAST_LONG_TIME = 3500; // 3.5 seconds
    private static final int TOAST_SHORT_TIME = 2000; // 2 seconds

    private static final String TAG = "ToastUI";

    private final Context mContext;
    private final CommandQueue mCommandQueue;
    private final INotificationManager mNotificationManager;
    private final IAccessibilityManager mIAccessibilityManager;
    private final AccessibilityManager mAccessibilityManager;
    private final ToastFactory mToastFactory;
    private final ToastLogger mToastLogger;
    @Nullable private ToastPresenter mPresenter;
    @Nullable private ITransientNotificationCallback mCallback;
    private ToastOutAnimatorListener mToastOutAnimatorListener;

    @VisibleForTesting SystemUIToast mToast;
    private int mOrientation = ORIENTATION_PORTRAIT;

    @Inject
    public ToastUI(
            Context context,
            CommandQueue commandQueue,
            ToastFactory toastFactory,
            ToastLogger toastLogger) {
        this(context, commandQueue,
                INotificationManager.Stub.asInterface(
                        ServiceManager.getService(Context.NOTIFICATION_SERVICE)),
                IAccessibilityManager.Stub.asInterface(
                        ServiceManager.getService(Context.ACCESSIBILITY_SERVICE)),
                toastFactory,
                toastLogger);
    }

    @VisibleForTesting
    ToastUI(Context context, CommandQueue commandQueue, INotificationManager notificationManager,
            @Nullable IAccessibilityManager accessibilityManager,
            ToastFactory toastFactory, ToastLogger toastLogger
    ) {
        mContext = context;
        mCommandQueue = commandQueue;
        mNotificationManager = notificationManager;
        mIAccessibilityManager = accessibilityManager;
        mToastFactory = toastFactory;
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
            IBinder windowToken, int duration, @Nullable ITransientNotificationCallback callback,
            int displayId) {
        Runnable showToastRunnable = () -> {
            UserHandle userHandle = UserHandle.getUserHandleForUid(uid);
            Context context = mContext.createContextAsUser(userHandle, 0);

            DisplayManager mDisplayManager = mContext.getSystemService(DisplayManager.class);
            Context displayContext = context.createDisplayContext(
                    mDisplayManager.getDisplay(displayId));
            mToast = mToastFactory.createToast(mContext /* sysuiContext */, text, packageName,
                    userHandle.getIdentifier(), mOrientation);

            if (mToast.getInAnimation() != null) {
                mToast.getInAnimation().start();
            }

            mCallback = callback;
            mPresenter = new ToastPresenter(displayContext, mIAccessibilityManager,
                    mNotificationManager, packageName);
            // Set as trusted overlay so touches can pass through toasts
            mPresenter.getLayoutParams().setTrustedOverlay();
            mToastLogger.logOnShowToast(uid, packageName, text.toString(), token.toString());
            mPresenter.show(mToast.getView(), token, windowToken, duration, mToast.getGravity(),
                    mToast.getXOffset(), mToast.getYOffset(), mToast.getHorizontalMargin(),
                    mToast.getVerticalMargin(), mCallback, mToast.hasCustomAnimation());
        };

        if (mToastOutAnimatorListener != null) {
            // if we're currently animating out a toast, show new toast after prev toast is hidden
            mToastOutAnimatorListener.setShowNextToastRunnable(showToastRunnable);
        } else if (mPresenter != null) {
            // if there's a toast already showing that we haven't tried hiding yet, hide it and
            // then show the next toast after its hidden animation is done
            hideCurrentToast(showToastRunnable);
        } else {
            // else, show this next toast immediately
            showToastRunnable.run();
        }
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
        hideCurrentToast(null);
    }

    @MainThread
    private void hideCurrentToast(Runnable runnable) {
        if (mToast.getOutAnimation() != null) {
            Animator animator = mToast.getOutAnimation();
            mToastOutAnimatorListener = new ToastOutAnimatorListener(mPresenter, mCallback,
                    runnable);
            animator.addListener(mToastOutAnimatorListener);
            animator.start();
        } else {
            mPresenter.hide(mCallback);
            if (runnable != null) {
                runnable.run();
            }
        }
        mToast = null;
        mPresenter = null;
        mCallback = null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (newConfig.orientation != mOrientation) {
            mOrientation = newConfig.orientation;
            if (mToast != null) {
                mToastLogger.logOrientationChange(mToast.mText.toString(),
                        mOrientation == ORIENTATION_PORTRAIT);
                mToast.onOrientationChange(mOrientation);
                mPresenter.updateLayoutParams(
                        mToast.getXOffset(),
                        mToast.getYOffset(),
                        mToast.getHorizontalMargin(),
                        mToast.getVerticalMargin(),
                        mToast.getGravity());
            }
        }
    }

    /**
     * Once the out animation for a toast is finished, start showing the next toast.
     */
    class ToastOutAnimatorListener extends AnimatorListenerAdapter {
        final ToastPresenter mPrevPresenter;
        final ITransientNotificationCallback mPrevCallback;
        @Nullable Runnable mShowNextToastRunnable;

        ToastOutAnimatorListener(
                @NonNull ToastPresenter presenter,
                @NonNull ITransientNotificationCallback callback,
                @Nullable Runnable runnable) {
            mPrevPresenter = presenter;
            mPrevCallback = callback;
            mShowNextToastRunnable = runnable;
        }

        void setShowNextToastRunnable(Runnable runnable) {
            mShowNextToastRunnable = runnable;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            mPrevPresenter.hide(mPrevCallback);
            if (mShowNextToastRunnable != null) {
                mShowNextToastRunnable.run();
            }
            mToastOutAnimatorListener = null;
        }
    }
}
