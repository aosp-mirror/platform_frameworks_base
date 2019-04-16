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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UnsupportedAppUsage;
import android.app.trust.ITrustManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.provider.Settings;
import android.service.persistentdata.IPersistentDataBlockService;
import android.util.Log;
import android.view.IOnKeyguardExitResult;
import android.view.IWindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerGlobal;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.widget.LockPatternUtils;

import java.util.List;

/**
 * Class that can be used to lock and unlock the keyboard. The
 * actual class to control the keyboard locking is
 * {@link android.app.KeyguardManager.KeyguardLock}.
 */
@SystemService(Context.KEYGUARD_SERVICE)
public class KeyguardManager {

    private static final String TAG = "KeyguardManager";

    private final Context mContext;
    private final IWindowManager mWM;
    private final IActivityManager mAm;
    private final ITrustManager mTrustManager;

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
     * Intent used to prompt user for factory reset credentials.
     * @hide
     */
    public static final String ACTION_CONFIRM_FRP_CREDENTIAL =
            "android.app.action.CONFIRM_FRP_CREDENTIAL";

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
     * A CharSequence description to show to the user on the alternate button when used with
     * {@link #ACTION_CONFIRM_FRP_CREDENTIAL}.
     * @hide
     */
    public static final String EXTRA_ALTERNATE_BUTTON_LABEL =
            "android.app.extra.ALTERNATE_BUTTON_LABEL";

    /**
     * Result code returned by the activity started by
     * {@link #createConfirmFactoryResetCredentialIntent} indicating that the user clicked the
     * alternate button.
     *
     * @hide
     */
    public static final int RESULT_ALTERNATE = 1;

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

    /**
     * Get an intent to prompt the user to confirm credentials (pin, pattern or password)
     * for the previous owner of the device. The caller is expected to launch this activity using
     * {@link android.app.Activity#startActivityForResult(Intent, int)} and check for
     * {@link android.app.Activity#RESULT_OK} if the user successfully completes the challenge.
     *
     * @param alternateButtonLabel if not empty, a button is provided with the given label. Upon
     *                             clicking this button, the activity returns
     *                             {@link #RESULT_ALTERNATE}
     *
     * @return the intent for launching the activity or null if the previous owner of the device
     *         did not set a credential.
     * @throws UnsupportedOperationException if the device does not support factory reset
     *                                       credentials
     * @throws IllegalStateException if the device has already been provisioned
     * @hide
     */
    @SystemApi
    public Intent createConfirmFactoryResetCredentialIntent(
            CharSequence title, CharSequence description, CharSequence alternateButtonLabel) {
        if (!LockPatternUtils.frpCredentialEnabled(mContext)) {
            Log.w(TAG, "Factory reset credentials not supported.");
            throw new UnsupportedOperationException("not supported on this device");
        }

        // Cannot verify credential if the device is provisioned
        if (Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0) {
            Log.e(TAG, "Factory reset credential cannot be verified after provisioning.");
            throw new IllegalStateException("must not be provisioned yet");
        }

        // Make sure we have a credential
        try {
            IPersistentDataBlockService pdb = IPersistentDataBlockService.Stub.asInterface(
                    ServiceManager.getService(Context.PERSISTENT_DATA_BLOCK_SERVICE));
            if (pdb == null) {
                Log.e(TAG, "No persistent data block service");
                throw new UnsupportedOperationException("not supported on this device");
            }
            // The following will throw an UnsupportedOperationException if the device does not
            // support factory reset credentials (or something went wrong retrieving it).
            if (!pdb.hasFrpCredentialHandle()) {
                Log.i(TAG, "The persistent data block does not have a factory reset credential.");
                return null;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        Intent intent = new Intent(ACTION_CONFIRM_FRP_CREDENTIAL);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_DESCRIPTION, description);
        intent.putExtra(EXTRA_ALTERNATE_BUTTON_LABEL, alternateButtonLabel);

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
     * @deprecated Use {@link LayoutParams#FLAG_DISMISS_KEYGUARD}
     * and/or {@link LayoutParams#FLAG_SHOW_WHEN_LOCKED}
     * instead; this allows you to seamlessly hide the keyguard as your application
     * moves in and out of the foreground and does not require that any special
     * permissions be requested.
     *
     * Handle returned by {@link KeyguardManager#newKeyguardLock} that allows
     * you to disable / reenable the keyguard.
     */
    @Deprecated
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
     * @deprecated Use {@link KeyguardDismissCallback}
     * Callback passed to {@link KeyguardManager#exitKeyguardSecurely} to notify
     * caller of result.
     */
    @Deprecated
    public interface OnKeyguardExitResult {

        /**
         * @param success True if the user was able to authenticate, false if
         *   not.
         */
        void onKeyguardExitResult(boolean success);
    }

    /**
     * Callback passed to
     * {@link KeyguardManager#requestDismissKeyguard(Activity, KeyguardDismissCallback)}
     * to notify caller of result.
     */
    public static abstract class KeyguardDismissCallback {

        /**
         * Called when dismissing Keyguard is currently not feasible, i.e. when Keyguard is not
         * available, not showing or when the activity requesting the Keyguard dismissal isn't
         * showing or isn't showing behind Keyguard.
         */
        public void onDismissError() { }

        /**
         * Called when dismissing Keyguard has succeeded and the device is now unlocked.
         */
        public void onDismissSucceeded() { }

        /**
         * Called when dismissing Keyguard has been cancelled, i.e. when the user cancelled the
         * operation or the bouncer was hidden for some other reason.
         */
        public void onDismissCancelled() { }
    }

    KeyguardManager(Context context) throws ServiceNotFoundException {
        mContext = context;
        mWM = WindowManagerGlobal.getWindowManagerService();
        mAm = ActivityManager.getService();
        mTrustManager = ITrustManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TRUST_SERVICE));
    }

    /**
     * @deprecated Use {@link LayoutParams#FLAG_DISMISS_KEYGUARD}
     * and/or {@link LayoutParams#FLAG_SHOW_WHEN_LOCKED}
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
     * @deprecated Use {@link #isKeyguardLocked()} instead.
     *
     * If keyguard screen is showing or in restricted key input mode (i.e. in
     * keyguard password emergency screen). When in such mode, certain keys,
     * such as the Home key and the right soft keys, don't work.
     *
     * @return true if in keyguard restricted input mode.
     */
    public boolean inKeyguardRestrictedInputMode() {
        return isKeyguardLocked();
    }

    /**
     * Returns whether the device is currently locked and requires a PIN, pattern or
     * password to unlock.
     *
     * @return true if unlocking the device currently requires a PIN, pattern or
     * password.
     */
    public boolean isDeviceLocked() {
        return isDeviceLocked(mContext.getUserId());
    }

    /**
     * Per-user version of {@link #isDeviceLocked()}.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public boolean isDeviceLocked(int userId) {
        try {
            return mTrustManager.isDeviceLocked(userId);
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
        return isDeviceSecure(mContext.getUserId());
    }

    /**
     * Per-user version of {@link #isDeviceSecure()}.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isDeviceSecure(int userId) {
        try {
            return mTrustManager.isDeviceSecure(userId);
        } catch (RemoteException e) {
            return false;
        }
    }

    /** @removed */
    @Deprecated
    public void dismissKeyguard(@NonNull Activity activity,
            @Nullable KeyguardDismissCallback callback, @Nullable Handler handler) {
        requestDismissKeyguard(activity, callback);
    }

    /**
     * If the device is currently locked (see {@link #isKeyguardLocked()}, requests the Keyguard to
     * be dismissed.
     * <p>
     * If the Keyguard is not secure or the device is currently in a trusted state, calling this
     * method will immediately dismiss the Keyguard without any user interaction.
     * <p>
     * If the Keyguard is secure and the device is not in a trusted state, this will bring up the
     * UI so the user can enter their credentials.
     * <p>
     * If the value set for the {@link Activity} attr {@link android.R.attr#turnScreenOn} is true,
     * the screen will turn on when the keyguard is dismissed.
     *
     * @param activity The activity requesting the dismissal. The activity must be either visible
     *                 by using {@link LayoutParams#FLAG_SHOW_WHEN_LOCKED} or must be in a state in
     *                 which it would be visible if Keyguard would not be hiding it. If that's not
     *                 the case, the request will fail immediately and
     *                 {@link KeyguardDismissCallback#onDismissError} will be invoked.
     * @param callback The callback to be called if the request to dismiss Keyguard was successful
     *                 or {@code null} if the caller isn't interested in knowing the result. The
     *                 callback will not be invoked if the activity was destroyed before the
     *                 callback was received.
     */
    public void requestDismissKeyguard(@NonNull Activity activity,
            @Nullable KeyguardDismissCallback callback) {
        requestDismissKeyguard(activity, null /* message */, callback);
    }

    /**
     * If the device is currently locked (see {@link #isKeyguardLocked()}, requests the Keyguard to
     * be dismissed.
     * <p>
     * If the Keyguard is not secure or the device is currently in a trusted state, calling this
     * method will immediately dismiss the Keyguard without any user interaction.
     * <p>
     * If the Keyguard is secure and the device is not in a trusted state, this will bring up the
     * UI so the user can enter their credentials.
     * <p>
     * If the value set for the {@link Activity} attr {@link android.R.attr#turnScreenOn} is true,
     * the screen will turn on when the keyguard is dismissed.
     *
     * @param activity The activity requesting the dismissal. The activity must be either visible
     *                 by using {@link LayoutParams#FLAG_SHOW_WHEN_LOCKED} or must be in a state in
     *                 which it would be visible if Keyguard would not be hiding it. If that's not
     *                 the case, the request will fail immediately and
     *                 {@link KeyguardDismissCallback#onDismissError} will be invoked.
     * @param message  A message that will be shown in the keyguard explaining why the user
     *                 would want to dismiss it.
     * @param callback The callback to be called if the request to dismiss Keyguard was successful
     *                 or {@code null} if the caller isn't interested in knowing the result. The
     *                 callback will not be invoked if the activity was destroyed before the
     *                 callback was received.
     * @hide
     */
    @RequiresPermission(Manifest.permission.SHOW_KEYGUARD_MESSAGE)
    @SystemApi
    public void requestDismissKeyguard(@NonNull Activity activity, @Nullable CharSequence message,
            @Nullable KeyguardDismissCallback callback) {
        try {
            mAm.dismissKeyguard(activity.getActivityToken(), new IKeyguardDismissCallback.Stub() {
                @Override
                public void onDismissError() throws RemoteException {
                    if (callback != null && !activity.isDestroyed()) {
                        activity.mHandler.post(callback::onDismissError);
                    }
                }

                @Override
                public void onDismissSucceeded() throws RemoteException {
                    if (callback != null && !activity.isDestroyed()) {
                        activity.mHandler.post(callback::onDismissSucceeded);
                    }
                }

                @Override
                public void onDismissCancelled() throws RemoteException {
                    if (callback != null && !activity.isDestroyed()) {
                        activity.mHandler.post(callback::onDismissCancelled);
                    }
                }
            }, message);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @deprecated Use {@link LayoutParams#FLAG_DISMISS_KEYGUARD}
     * and/or {@link LayoutParams#FLAG_SHOW_WHEN_LOCKED}
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
     * @param callback Lets you know whether the operation was successful and
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
