/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.policy.keyguard;

import android.app.ActivityManager;
import android.content.Context;
import android.content.ContentResolver;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.security.keystore.IKeystoreService;
import android.util.Slog;

import com.android.internal.policy.IKeyguardService;
import com.android.internal.policy.IKeyguardStateCallback;
import com.android.internal.widget.LockPatternUtils;

import lineageos.providers.LineageSettings;
import vendor.lineage.trust.V1_0.IUsbRestrict;

import java.io.PrintWriter;
import java.util.NoSuchElementException;

/**
 * Maintains a cached copy of Keyguard's state.
 * @hide
 */
public class KeyguardStateMonitor extends IKeyguardStateCallback.Stub {
    private static final String TAG = "KeyguardStateMonitor";

    // These cache the current state of Keyguard to improve performance and avoid deadlock. After
    // Keyguard changes its state, it always triggers a layout in window manager. Because
    // IKeyguardStateCallback is synchronous and because these states are declared volatile, it's
    // guaranteed that window manager picks up the new state all the time in the layout caused by
    // the state change of Keyguard. To be extra safe, assume most restrictive values until Keyguard
    // tells us the actual value.
    private volatile boolean mIsShowing = true;
    private volatile boolean mSimSecure = true;
    private volatile boolean mInputRestricted = true;
    private volatile boolean mTrusted = false;
    private volatile boolean mHasLockscreenWallpaper = false;

    private int mCurrentUserId;

    private final LockPatternUtils mLockPatternUtils;
    private final StateCallback mCallback;

    private IUsbRestrict mUsbRestrictor = null;
    private ContentResolver mContentResolver;

    IKeystoreService mKeystoreService;

    public KeyguardStateMonitor(Context context, IKeyguardService service, StateCallback callback) {
        mLockPatternUtils = new LockPatternUtils(context);
        mCurrentUserId = ActivityManager.getCurrentUser();
        mCallback = callback;
        mContentResolver = context.getContentResolver();

        mKeystoreService = IKeystoreService.Stub.asInterface(ServiceManager
                .getService("android.security.keystore"));

        try {
            service.addStateMonitorCallback(this);
        } catch (RemoteException e) {
            Slog.w(TAG, "Remote Exception", e);
        }
    }

    public boolean isShowing() {
        return mIsShowing;
    }

    public boolean isSecure(int userId) {
        return mLockPatternUtils.isSecure(userId) || mSimSecure;
    }

    public boolean isInputRestricted() {
        return mInputRestricted;
    }

    public boolean isTrusted() {
        return mTrusted;
    }

    public boolean hasLockscreenWallpaper() {
        return mHasLockscreenWallpaper;
    }

    @Override // Binder interface
    public void onShowingStateChanged(boolean showing) {
        mIsShowing = showing;

        mCallback.onShowingChanged();
        int retry = 2;
        while (retry > 0) {
            try {
                mKeystoreService.onKeyguardVisibilityChanged(showing, mCurrentUserId);
                break;
            } catch (RemoteException e) {
                if (retry == 2) {
                    Slog.w(TAG, "Error informing keystore of screen lock. Keystore may have died"
                            + " -> refreshing service token and retrying");
                    mKeystoreService = IKeystoreService.Stub.asInterface(ServiceManager
                            .getService("android.security.keystore"));
                } else {
                    Slog.e(TAG, "Error informing keystore of screen lock after retrying once", e);
                }
                --retry;
            }
        }

        if (mUsbRestrictor == null) {
            try {
                mUsbRestrictor = IUsbRestrict.getService();
                if (mUsbRestrictor == null) {
                    // Ignore, the hal is not available
                    return;
                }
            } catch (NoSuchElementException | RemoteException ignored) {
                return;
            }
        }

        boolean shouldRestrictUsb = LineageSettings.Secure.getInt(mContentResolver,
                LineageSettings.Secure.TRUST_RESTRICT_USB_KEYGUARD, 0) == 1;
        if (shouldRestrictUsb) {
            try {
                mUsbRestrictor.setEnabled(showing);
            } catch (RemoteException ignored) {
                // This feature is not supported
            }
        }
    }

    @Override // Binder interface
    public void onSimSecureStateChanged(boolean simSecure) {
        mSimSecure = simSecure;
    }

    public synchronized void setCurrentUser(int userId) {
        mCurrentUserId = userId;
    }

    private synchronized int getCurrentUser() {
        return mCurrentUserId;
    }

    @Override // Binder interface
    public void onInputRestrictedStateChanged(boolean inputRestricted) {
        mInputRestricted = inputRestricted;
    }

    @Override // Binder interface
    public void onTrustedChanged(boolean trusted) {
        mTrusted = trusted;
        mCallback.onTrustedChanged();
    }

    @Override // Binder interface
    public void onHasLockscreenWallpaperChanged(boolean hasLockscreenWallpaper) {
        mHasLockscreenWallpaper = hasLockscreenWallpaper;
    }

    public interface StateCallback {
        void onTrustedChanged();
        void onShowingChanged();
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + TAG);
        prefix += "  ";
        pw.println(prefix + "mIsShowing=" + mIsShowing);
        pw.println(prefix + "mSimSecure=" + mSimSecure);
        pw.println(prefix + "mInputRestricted=" + mInputRestricted);
        pw.println(prefix + "mTrusted=" + mTrusted);
        pw.println(prefix + "mCurrentUserId=" + mCurrentUserId);
    }
}
