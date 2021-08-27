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
package com.android.keyguard;

import android.annotation.Nullable;
import android.app.admin.IKeyguardCallback;
import android.app.admin.IKeyguardClient;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.dagger.KeyguardBouncerScope;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.NoSuchElementException;

import javax.inject.Inject;

/**
 * Encapsulates all logic for secondary lockscreen state management.
 */
public class AdminSecondaryLockScreenController {
    private static final String TAG = "AdminSecondaryLockScreenController";
    private static final int REMOTE_CONTENT_READY_TIMEOUT_MILLIS = 500;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final Context mContext;
    private final ViewGroup mParent;
    private AdminSecurityView mView;
    private Handler mHandler;
    private IKeyguardClient mClient;
    private KeyguardSecurityCallback mKeyguardCallback;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mClient = IKeyguardClient.Stub.asInterface(service);
            if (mView.isAttachedToWindow() && mClient != null) {
                onSurfaceReady();

                try {
                    service.linkToDeath(mKeyguardClientDeathRecipient, 0);
                } catch (RemoteException e) {
                    // Failed to link to death, just dismiss and unbind the service for now.
                    Log.e(TAG, "Lost connection to secondary lockscreen service", e);
                    dismiss(KeyguardUpdateMonitor.getCurrentUser());
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            mClient = null;
        }
    };

    private final IBinder.DeathRecipient mKeyguardClientDeathRecipient = () -> {
        hide(); // hide also takes care of unlinking to death.
        Log.d(TAG, "KeyguardClient service died");
    };

    private final IKeyguardCallback mCallback = new IKeyguardCallback.Stub() {
        @Override
        public void onDismiss() {
            mHandler.post(() -> {
                dismiss(UserHandle.getCallingUserId());
            });
        }

        @Override
        public void onRemoteContentReady(
                @Nullable SurfaceControlViewHost.SurfacePackage surfacePackage) {
            if (mHandler != null) {
                mHandler.removeCallbacksAndMessages(null);
            }
            if (surfacePackage != null) {
                mView.setChildSurfacePackage(surfacePackage);
            } else {
                mHandler.post(() -> {
                    dismiss(KeyguardUpdateMonitor.getCurrentUser());
                });
            }
        }
    };

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onSecondaryLockscreenRequirementChanged(int userId) {
                    Intent newIntent = mUpdateMonitor.getSecondaryLockscreenRequirement(userId);
                    if (newIntent == null) {
                        dismiss(userId);
                    }
                }
            };

    @VisibleForTesting
    protected SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            final int userId = KeyguardUpdateMonitor.getCurrentUser();
            mUpdateMonitor.registerCallback(mUpdateCallback);

            if (mClient != null) {
                onSurfaceReady();
            }
            mHandler.postDelayed(
                    () -> {
                        // If the remote content is not readied within the timeout period,
                        // move on without the secondary lockscreen.
                        dismiss(userId);
                        Log.w(TAG, "Timed out waiting for secondary lockscreen content.");
                    },
                    REMOTE_CONTENT_READY_TIMEOUT_MILLIS);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            mUpdateMonitor.removeCallback(mUpdateCallback);
        }
    };

    private AdminSecondaryLockScreenController(Context context, KeyguardSecurityContainer parent,
            KeyguardUpdateMonitor updateMonitor, KeyguardSecurityCallback callback,
            @Main Handler handler) {
        mContext = context;
        mHandler = handler;
        mParent = parent;
        mUpdateMonitor = updateMonitor;
        mKeyguardCallback = callback;
        mView = new AdminSecurityView(mContext, mSurfaceHolderCallback);
    }

    /**
     * Displays the Admin security Surface view.
     */
    public void show(Intent serviceIntent) {
        if (mClient == null) {
            mContext.bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE);
        }
        if (!mView.isAttachedToWindow()) {
            mParent.addView(mView);
        }
    }

    /**
     * Hides the Admin security Surface view.
     */
    public void hide() {
        if (mView.isAttachedToWindow()) {
            mParent.removeView(mView);
        }
        if (mClient != null) {
            try {
                mClient.asBinder().unlinkToDeath(mKeyguardClientDeathRecipient, 0);
            } catch (NoSuchElementException e) {
                Log.w(TAG, "IKeyguardClient death recipient already released");
            }
            mContext.unbindService(mConnection);
            mClient = null;
        }
    }

    private void onSurfaceReady() {
        try {
            IBinder hostToken = mView.getHostToken();
            // Should never be null when SurfaceView is attached to window.
            if (hostToken != null) {
                mClient.onCreateKeyguardSurface(hostToken, mCallback);
            } else {
                hide();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error in onCreateKeyguardSurface", e);
            dismiss(KeyguardUpdateMonitor.getCurrentUser());
        }
    }

    private void dismiss(int userId) {
        mHandler.removeCallbacksAndMessages(null);
        if (mView.isAttachedToWindow() && userId == KeyguardUpdateMonitor.getCurrentUser()) {
            hide();
            if (mKeyguardCallback != null) {
                mKeyguardCallback.dismiss(/* securityVerified= */ true, userId,
                        /* bypassSecondaryLockScreen= */true);
            }
        }
    }

    /**
     * Custom {@link SurfaceView} used to allow a device admin to present an additional security
     * screen.
     */
    private class AdminSecurityView extends SurfaceView {
        private SurfaceHolder.Callback mSurfaceHolderCallback;

        AdminSecurityView(Context context, SurfaceHolder.Callback surfaceHolderCallback) {
            super(context);
            mSurfaceHolderCallback = surfaceHolderCallback;
            setZOrderOnTop(true);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getHolder().addCallback(mSurfaceHolderCallback);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            getHolder().removeCallback(mSurfaceHolderCallback);
        }
    }

    @KeyguardBouncerScope
    public static class Factory {
        private final Context mContext;
        private final KeyguardSecurityContainer mParent;
        private final KeyguardUpdateMonitor mUpdateMonitor;
        private final Handler mHandler;

        @Inject
        public Factory(Context context, KeyguardSecurityContainer parent,
                KeyguardUpdateMonitor updateMonitor, @Main Handler handler) {
            mContext = context;
            mParent = parent;
            mUpdateMonitor = updateMonitor;
            mHandler = handler;
        }

        public AdminSecondaryLockScreenController create(KeyguardSecurityCallback callback) {
            return new AdminSecondaryLockScreenController(mContext, mParent, mUpdateMonitor,
                    callback, mHandler);
        }
    }
}
