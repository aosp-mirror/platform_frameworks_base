/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import android.Manifest;
import android.annotation.RequiresPermission;
import android.app.trust.ITrustManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.view.IOnKeyguardExitResult;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import java.util.List;

/**
 * Class that can be used to lock and unlock the keyboard. Get an instance of this
 * class by calling {@link android.content.Context#getSystemService(java.lang.String)}
 * with argument {@link android.content.Context#KEYGUARD_SERVICE}. The
 * actual class to control the keyboard locking is
 * {@link android.app.KeyguardManager.KeyguardLock}.
 */
public class KeyguardManager {
    private IWindowManager mWM;
    private ITrustManager mTrustManager;
    private IUserManager mUserManager;
    private Context mContext;

    /**
     * Intent used to prompt user for device credentials.
     * @hide
     */
    public static final String ACTION_CONFIRM_DEVICE_CREDENTIAL =
            "android.app.action.CONFIRM_DEVICE_CREDENTIAL";

    /**
     * Intent used to prompt user for device credentials.
     * @hide
     */
    public static final String ACTION_CONFIRM_DEVICE_CREDENTIAL_WITH_USER =
            "android.app.action.CONFIRM_DEVICE_CREDENTIAL_WITH_USER";

    /**
     * A CharSequence dialog title to show to the user when used with a
     * {@link #ACTION_CONFIRM_DEVICE_CREDENTIAL}.
     * @hide
     */
    public static final String EXTRA_TITLE = "android.app.extra.TITLE";

    /**
     * A CharSequence description to show to the user when used with
     * {@link #ACTION_CONFIRM_DEVICE_CREDENTIAL}.
     * @hide
     */
    public static final String EXTRA_DESCRIPTION = "android.app.extra.DESCRIPTION";

    /**
     * Get an intent to prompt the user to confirm credentials (pin, pattern or password)
     * for the current user of the device. The caller is expected to launch this activity using
     * {@link android.app.Activity#startActivityForResult(Intent, int)} and check for
     * {@link android.app.Activity#RESULT_OK} if the user successfully completes the challenge.
     *
     * @return the intent for launching the activity or null if no password is required.
     **/
    public Intent createConfirmDeviceCredentialIntent(CharSequence title, CharSequence description) {
        if (!isDeviceSecure()) return null;
        Intent intent = new Intent(ACTION_CONFIRM_DEVICE_CREDENTIAL);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_DESCRIPTION, description);

        // explicitly set the package for security
        intent.setPackage(getSettingsPackageForIntent(intent));
        return intent;
    }

    /**
     * Get an intent to prompt the user to confirm credentials (pin, pattern or password)
     * for the given user. The caller is expected to launch this activity using
     * {@link android.app.Activity#startActivityForResult(Intent, int)} and check for
     * {@link android.app.Activity#RESULT_OK} if the user successfully completes the challenge.
     *
     * @return the intent for launching the activity or null if no password is required.
     *
     * @hide
     */
    public Intent createConfirmDeviceCredentialIntent(
            CharSequence title, CharSequence description, int userId) {
        if (!isDeviceSecure(userId)) return null;
        Intent intent = new Intent(ACTION_CONFIRM_DEVICE_CREDENTIAL_WITH_USER);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_DESCRIPTION, description);
        intent.putExtra(Intent.EXTRA_USER_ID, userId);

        // explicitly set the package for security
        intent.setPackage(getSettingsPackageForIntent(intent));

        return intent;
    }

    private String getSettingsPackageForIntent(Intent intent) {
        List<ResolveInfo> resolveInfos = mContext.getPackageManager()
                .queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY);
        for (int i = 0; i < resolveInfos.size(); i++) {
            return resolveInfos.get(i).activityInfo.packageName;
        }

        return "com.android.settings";
    }

    /**
     * @deprecated Use {@link android.view.WindowManager.LayoutParams#FLAG_DISMISS_KEYGUARD}
     * and/or {@link android.view.WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED}
     * instead; this allows you to seamlessly hide the keyguard as your application
     * moves in and out of the foreground and does not require that any special
     * permissions be requested.
     *
     * Handle returned by {@link KeyguardManager#newKeyguardLock} that allows
     * you to disable / reenable the keyguard.
     */
    public class KeyguardLock {
        private final IBinder mToken = new Binder();
        private final String mTag;

        KeyguardLock(String tag) {
            mTag = tag;
        }

        /**
         * Disable the keyguard from showing.  If the keyguard is currently
         * showing, hide it.  The keyguard will be prevented from showing again
         * until {@link #reenableKeyguard()} is called.
         *
         * A good place to call this is from {@link android.app.Activity#onResume()}
         *
         * Note: This call has no effect while any {@link android.app.admin.DevicePolicyManager}
         * is enabled that requires a password.
         *
         * <p>This method requires the caller to hold the permission
         * {@link android.Manifest.permission#DISABLE_KEYGUARD}.
         *
         * @see #reenableKeyguard()
         */
        @RequiresPermission(Manifest.permission.DISABLE_KEYGUARD)
        public void disableKeyguard() {
            try {
                mWM.disableKeyguard(mToken, mTag);
            } catch (RemoteException ex) {
            }
        }

        /**
         * Reenable the keyguard.  The keyguard will reappear if the previous
         * call to {@link #disableKeyguard()} caused it to be hidden.
         *
         * A good place to call this is from {@link android.app.Activity#onPause()}
         *
         * Note: This call has no effect while any {@link android.app.admin.DevicePolicyManager}
         * is enabled that requires a password.
         *
         * <p>This method requires the caller to hold the permission
         * {@link android.Manifest.permission#DISABLE_KEYGUARD}.
         *
         * @see #disableKeyguard()
         */
        @RequiresPermission(Manifest.permission.DISABLE_KEYGUARD)
        public void reenableKeyguard() {
            try {
                mWM.reenableKeyguard(mToken);
            } catch (RemoteException ex) {
            }
        }
    }

    /**
     * Callback passed to {@link KeyguardManager#exitKeyguardSecurely} to notify
     * caller of result.
     */
    public interface OnKeyguardExitResult {

        /**
         * @param success True if the user was able to authenticate, false if
         *   not.
         */
        void onKeyguardExitResult(boolean success);
    }


    KeyguardManager(Context context) {
        mContext = context;
        mWM = WindowManagerGlobal.getWindowManagerService();
        mTrustManager = ITrustManager.Stub.asInterface(
                ServiceManager.getService(Context.TRUST_SERVICE));
        mUserManager = IUserManager.Stub.asInterface(
                ServiceManager.getService(Context.USER_SERVICE));
    }

    /**
     * @deprecated Use {@link android.view.WindowManager.LayoutParams#FLAG_DISMISS_KEYGUARD}
     * and/or {@link android.view.WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED}
     * instead; this allows you to seamlessly hide the keyguard as your application
     * moves in and out of the foreground and does not require that any special
     * permissions be requested.
     *
     * Enables you to lock or unlock the keyboard. Get an instance of this class by
     * calling {@link android.content.Context#getSystemService(java.lang.String) Context.getSystemService()}.
     * This class is wrapped by {@link android.app.KeyguardManager KeyguardManager}.
     * @param tag A tag that informally identifies who you are (for debugging who
     *   is disabling he keyguard).
     *
     * @return A {@link KeyguardLock} handle to use to disable and reenable the
     *   keyguard.
     */
    @Deprecated
    public KeyguardLock newKeyguardLock(String tag) {
        return new KeyguardLock(tag);
    }

    /**
     * Return whether the keyguard is currently locked.
     *
     * @return true if keyguard is locked.
     */
    public boolean isKeyguardLocked() {
        try {
            return mWM.isKeyguardLocked();
        } catch (RemoteException ex) {
            return false;
        }
    }

    /**
     * Return whether the keyguard is secured by a PIN, pattern or password or a SIM card
     * is currently locked.
     *
     * <p>See also {@link #isDeviceSecure()} which ignores SIM locked states.
     *
     * @return true if a PIN, pattern or password is set or a SIM card is locked.
     */
    public boolean isKeyguardSecure() {
        try {
            return mWM.isKeyguardSecure();
        } catch (RemoteException ex) {
            return false;
        }
    }

    /**
     * If keyguard screen is showing or in restricted key input mode (i.e. in
     * keyguard password emergency screen). When in such mode, certain keys,
     * such as the Home key and the right soft keys, don't work.
     *
     * @return true if in keyguard restricted input mode.
     *
     * @see android.view.WindowManagerPolicy#inKeyguardRestrictedKeyInputMode
     */
    public boolean inKeyguardRestrictedInputMode() {
        try {
            return mWM.inKeyguardRestrictedInputMode();
        } catch (RemoteException ex) {
            return false;
        }
    }

    /**
     * Returns whether the device is currently locked and requires a PIN, pattern or
     * password to unlock.
     *
     * @return true if unlocking the device currently requires a PIN, pattern or
     * password.
     */
    public boolean isDeviceLocked() {
        return isDeviceLocked(UserHandle.getCallingUserId());
    }

    /**
     * Per-user version of {@link #isDeviceLocked()}.
     *
     * @hide
     */
    public boolean isDeviceLocked(int userId) {
        ITrustManager trustManager = getTrustManager();
        try {
            return trustManager.isDeviceLocked(userId);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Returns whether the device is secured with a PIN, pattern or
     * password.
     *
     * <p>See also {@link #isKeyguardSecure} which treats SIM locked states as secure.
     *
     * @return true if a PIN, pattern or password was set.
     */
    public boolean isDeviceSecure() {
        return isDeviceSecure(UserHandle.getCallingUserId());
    }

    /**
     * Per-user version of {@link #isDeviceSecure()}.
     *
     * @hide
     */
    public boolean isDeviceSecure(int userId) {
        ITrustManager trustManager = getTrustManager();
        try {
            return trustManager.isDeviceSecure(userId);
        } catch (RemoteException e) {
            return false;
        }
    }

    private synchronized ITrustManager getTrustManager() {
        if (mTrustManager == null) {
            mTrustManager = ITrustManager.Stub.asInterface(
                    ServiceManager.getService(Context.TRUST_SERVICE));
        }
        return mTrustManager;
    }

    /**
     * @deprecated Use {@link android.view.WindowManager.LayoutParams#FLAG_DISMISS_KEYGUARD}
     * and/or {@link android.view.WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED}
     * instead; this allows you to seamlessly hide the keyguard as your application
     * moves in and out of the foreground and does not require that any special
     * permissions be requested.
     *
     * Exit the keyguard securely.  The use case for this api is that, after
     * disabling the keyguard, your app, which was granted permission to
     * disable the keyguard and show a limited amount of information deemed
     * safe without the user getting past the keyguard, needs to navigate to
     * something that is not safe to view without getting past the keyguard.
     *
     * This will, if the keyguard is secure, bring up the unlock screen of
     * the keyguard.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#DISABLE_KEYGUARD}.
     *
     * @param callback Let's you know whether the operation was succesful and
     *   it is safe to launch anything that would normally be considered safe
     *   once the user has gotten past the keyguard.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.DISABLE_KEYGUARD)
    public void exitKeyguardSecurely(final OnKeyguardExitResult callback) {
        try {
            mWM.exitKeyguardSecurely(new IOnKeyguardExitResult.Stub() {
                public void onKeyguardExitResult(boolean success) throws RemoteException {
                    if (callback != null) {
                        callback.onKeyguardExitResult(success);
                    }
                }
            });
        } catch (RemoteException e) {

        }
    }
}
