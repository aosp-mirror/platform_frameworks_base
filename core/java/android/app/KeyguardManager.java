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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManager.PasswordComplexity;
import android.app.admin.PasswordMetrics;
import android.app.trust.ITrustManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Build;
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
import com.android.internal.widget.LockPatternView;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

/**
 * Class that can be used to lock and unlock the keyguard. The
 * actual class to control the keyguard locking is
 * {@link android.app.KeyguardManager.KeyguardLock}.
 */
@SystemService(Context.KEYGUARD_SERVICE)
public class KeyguardManager {

    private static final String TAG = "KeyguardManager";

    private final Context mContext;
    private final IWindowManager mWM;
    private final IActivityManager mAm;
    private final ITrustManager mTrustManager;
    private final INotificationManager mNotificationManager;

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
     *
     * If this is set, check device policy for allowed biometrics when the user is authenticating.
     * This should only be used in the context of managed profiles.
     *
     * @hide
     */
    public static final String EXTRA_DISALLOW_BIOMETRICS_IF_POLICY_EXISTS = "check_dpm";

    /**
     *
     * Password lock type, see {@link #setLock}
     *
     * @hide
     */
    @SystemApi
    public static final int PASSWORD = 0;

    /**
     *
     * Pin lock type, see {@link #setLock}
     *
     * @hide
     */
    @SystemApi
    public static final int PIN = 1;

    /**
     *
     * Pattern lock type, see {@link #setLock}
     *
     * @hide
     */
    @SystemApi
    public static final int PATTERN = 2;

    /**
     * Available lock types
     */
    @IntDef({
            PASSWORD,
            PIN,
            PATTERN
    })
    @interface LockTypes {}

    /**
     * Get an intent to prompt the user to confirm credentials (pin, pattern, password or biometrics
     * if enrolled) for the current user of the device. The caller is expected to launch this
     * activity using {@link android.app.Activity#startActivityForResult(Intent, int)} and check for
     * {@link android.app.Activity#RESULT_OK} if the user successfully completes the challenge.
     *
     * @return the intent for launching the activity or null if no password is required.
     * @deprecated see BiometricPrompt.Builder#setDeviceCredentialAllowed(boolean)
     */
    @Deprecated
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public Intent createConfirmDeviceCredentialIntent(CharSequence title,
            CharSequence description) {
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
     * for the given user. The caller is expected to launch this activity using
     * {@link android.app.Activity#startActivityForResult(Intent, int)} and check for
     * {@link android.app.Activity#RESULT_OK} if the user successfully completes the challenge.
     *
     * @param disallowBiometricsIfPolicyExists If true check if the Device Policy Manager has
     * disabled biometrics on the device. If biometrics are disabled, fall back to PIN/pattern/pass.
     *
     * @return the intent for launching the activity or null if no password is required.
     *
     * @hide
     */
    public Intent createConfirmDeviceCredentialIntent(
            CharSequence title, CharSequence description, int userId,
            boolean disallowBiometricsIfPolicyExists) {
        Intent intent = this.createConfirmDeviceCredentialIntent(title, description, userId);
        intent.putExtra(EXTRA_DISALLOW_BIOMETRICS_IF_POLICY_EXISTS,
                disallowBiometricsIfPolicyExists);
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
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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

    /**
     * Controls whether notifications can be shown atop a securely locked screen in their full
     * private form (same as when the device is unlocked).
     *
     * <p>Other sources like the DevicePolicyManger and Settings app can modify this configuration.
     * The result is that private notifications are only shown if all sources allow it.
     *
     * @param allow secure notifications can be shown if {@code true},
     * secure notifications cannot be shown if {@code false}
     * @hide
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    @RequiresPermission(Manifest.permission.CONTROL_KEYGUARD_SECURE_NOTIFICATIONS)
    @SystemApi
    public void setPrivateNotificationsAllowed(boolean allow) {
        try {
            mNotificationManager.setPrivateNotificationsAllowed(allow);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether notifications can be shown atop a securely locked screen in their full
     * private form (same as when the device is unlocked).
     *
     * @return {@code true} if secure notifications can be shown, {@code false} otherwise.
     * By default, private notifications are allowed.
     * @hide
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    @RequiresPermission(Manifest.permission.CONTROL_KEYGUARD_SECURE_NOTIFICATIONS)
    @SystemApi
    public boolean getPrivateNotificationsAllowed() {
        try {
            return mNotificationManager.getPrivateNotificationsAllowed();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * Handle returned by {@link KeyguardManager#newKeyguardLock} that allows
     * you to disable / reenable the keyguard.
     *
     * @deprecated Use {@link LayoutParams#FLAG_DISMISS_KEYGUARD}
     * and/or {@link LayoutParams#FLAG_SHOW_WHEN_LOCKED}
     * instead; this allows you to seamlessly hide the keyguard as your application
     * moves in and out of the foreground and does not require that any special
     * permissions be requested.
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
                mWM.disableKeyguard(mToken, mTag, mContext.getUserId());
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
                mWM.reenableKeyguard(mToken, mContext.getUserId());
            } catch (RemoteException ex) {
            }
        }
    }

    /**
     * Callback passed to {@link KeyguardManager#exitKeyguardSecurely} to notify
     * caller of result.
     *
     * @deprecated Use {@link KeyguardDismissCallback}
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
        mNotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.NOTIFICATION_SERVICE));
    }

    /**
     * Enables you to lock or unlock the keyguard. Get an instance of this class by
     * calling {@link android.content.Context#getSystemService(java.lang.String) Context.getSystemService()}.
     * This class is wrapped by {@link android.app.KeyguardManager KeyguardManager}.
     * @param tag A tag that informally identifies who you are (for debugging who
     *   is disabling the keyguard).
     *
     * @return A {@link KeyguardLock} handle to use to disable and reenable the
     *   keyguard.
     *
     * @deprecated Use {@link LayoutParams#FLAG_DISMISS_KEYGUARD}
     *   and/or {@link LayoutParams#FLAG_SHOW_WHEN_LOCKED}
     *   instead; this allows you to seamlessly hide the keyguard as your application
     *   moves in and out of the foreground and does not require that any special
     *   permissions be requested.
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
            return mWM.isKeyguardSecure(mContext.getUserId());
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
     * @deprecated Use {@link #isKeyguardLocked()} instead.
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isDeviceSecure(int userId) {
        try {
            return mTrustManager.isDeviceSecure(userId);
        } catch (RemoteException e) {
            return false;
        }
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
        ActivityClient.getInstance().dismissKeyguard(
                activity.getActivityToken(), new IKeyguardDismissCallback.Stub() {
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
    }

    /**
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

     * @deprecated Use {@link LayoutParams#FLAG_DISMISS_KEYGUARD}
     *   and/or {@link LayoutParams#FLAG_SHOW_WHEN_LOCKED}
     *   instead; this allows you to seamlessly hide the keyguard as your application
     *   moves in and out of the foreground and does not require that any special
     *   permissions be requested.
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

    private boolean checkInitialLockMethodUsage() {
        if (!hasPermission(Manifest.permission.SET_INITIAL_LOCK)) {
            throw new SecurityException("Requires SET_INITIAL_LOCK permission.");
        }
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    private boolean hasPermission(String permission) {
        return PackageManager.PERMISSION_GRANTED == mContext.checkCallingOrSelfPermission(
                permission);
    }

    /**
    * Determine if a given password is valid based off its lock type and expected complexity level.
    *
    * @param lockType - type of lock as specified in {@link LockTypes}
    * @param password - password to validate; this has the same encoding
    *        as the output of String#getBytes
    * @param complexity - complexity level imposed by the requester
    *        as defined in {@code DevicePolicyManager.PasswordComplexity}
    * @return true if the password is valid, false otherwise
    * @hide
    */
    @RequiresPermission(Manifest.permission.SET_INITIAL_LOCK)
    @SystemApi
    public boolean isValidLockPasswordComplexity(@LockTypes int lockType, @NonNull byte[] password,
            @PasswordComplexity int complexity) {
        if (!checkInitialLockMethodUsage()) {
            return false;
        }
        complexity = PasswordMetrics.sanitizeComplexityLevel(complexity);
        // TODO: b/131755827 add devicePolicyManager support for Auto
        DevicePolicyManager devicePolicyManager =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        PasswordMetrics adminMetrics =
                devicePolicyManager.getPasswordMinimumMetrics(mContext.getUserId());
        // Check if the password fits the mold of a pin or pattern.
        boolean isPinOrPattern = lockType != PASSWORD;

        return PasswordMetrics.validatePassword(
                adminMetrics, complexity, isPinOrPattern, password).size() == 0;
    }

    /**
    * Determine the minimum allowable length for a lock type for a given complexity level.
    *
    * @param isPin - whether this is a PIN-type password (only digits)
    * @param complexity - complexity level imposed by the requester
    *        as defined in {@code DevicePolicyManager.PasswordComplexity}
    * @return minimum allowable password length
    * @hide
    */
    @RequiresPermission(Manifest.permission.SET_INITIAL_LOCK)
    @SystemApi
    public int getMinLockLength(boolean isPin, @PasswordComplexity int complexity) {
        if (!checkInitialLockMethodUsage()) {
            return -1;
        }
        complexity = PasswordMetrics.sanitizeComplexityLevel(complexity);
        // TODO: b/131755827 add devicePolicyManager support for Auto
        DevicePolicyManager devicePolicyManager =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        PasswordMetrics adminMetrics =
                devicePolicyManager.getPasswordMinimumMetrics(mContext.getUserId());
        PasswordMetrics minMetrics =
                PasswordMetrics.applyComplexity(adminMetrics, isPin, complexity);
        return minMetrics.length;
    }

    /**
    * Set the lockscreen password after validating against its expected complexity level.
    *
    * @param lockType - type of lock as specified in {@link LockTypes}
    * @param password - password to validate; this has the same encoding
    *        as the output of String#getBytes
    * @param complexity - complexity level imposed by the requester
    *        as defined in {@code DevicePolicyManager.PasswordComplexity}
    * @return true if the lock is successfully set, false otherwise
    * @hide
    */
    @RequiresPermission(Manifest.permission.SET_INITIAL_LOCK)
    @SystemApi
    public boolean setLock(@LockTypes int lockType, @NonNull byte[] password,
            @PasswordComplexity int complexity) {
        if (!checkInitialLockMethodUsage()) {
            return false;
        }

        LockPatternUtils lockPatternUtils = new LockPatternUtils(mContext);
        int userId = mContext.getUserId();
        if (isDeviceSecure(userId)) {
            Log.e(TAG, "Password already set, rejecting call to setLock");
            return false;
        }
        if (!isValidLockPasswordComplexity(lockType, password, complexity)) {
            Log.e(TAG, "Password is not valid, rejecting call to setLock");
            return false;
        }
        boolean success;
        try {
            LockscreenCredential credential = createLockscreenCredential(
                    lockType, password);
            success = lockPatternUtils.setLockCredential(
                    credential,
                    /* savedPassword= */ LockscreenCredential.createNone(),
                    userId);
        } catch (Exception e) {
            Log.e(TAG, "Save lock exception", e);
            success = false;
        } finally {
            Arrays.fill(password, (byte) 0);
        }
        return success;
    }

    /**
     * Set the lockscreen password to {@code newPassword} after validating the current password
     * against {@code currentPassword}.
     * <p>If no password is currently set, {@code currentPassword} should be set to {@code null}.
     * <p>To clear the current password, {@code newPassword} should be set to {@code null}.
     *
     * @return {@code true} if password successfully set.
     *
     * @throws IllegalArgumentException if {@code newLockType} or {@code currentLockType}
     * is invalid.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(anyOf = {
            Manifest.permission.SET_AND_VERIFY_LOCKSCREEN_CREDENTIALS,
            Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE
    })
    public boolean setLock(@LockTypes int newLockType, @Nullable byte[] newPassword,
            @LockTypes int currentLockType, @Nullable byte[] currentPassword) {
        final LockPatternUtils lockPatternUtils = new LockPatternUtils(mContext);
        final int userId = mContext.getUserId();
        LockscreenCredential currentCredential = createLockscreenCredential(
                currentLockType, currentPassword);
        LockscreenCredential newCredential = createLockscreenCredential(
                newLockType, newPassword);
        return lockPatternUtils.setLockCredential(newCredential, currentCredential, userId);
    }

    /**
     * Verifies the current lock credentials against {@code password}.
     * <p>To check if no password is set, {@code password} should be set to {@code null}.
     *
     * @return {@code true} if credentials match
     *
     * @throws IllegalArgumentException if {@code lockType} is invalid.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(anyOf = {
            Manifest.permission.SET_AND_VERIFY_LOCKSCREEN_CREDENTIALS,
            Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE
    })
    public boolean checkLock(@LockTypes int lockType, @Nullable byte[] password) {
        final LockPatternUtils lockPatternUtils = new LockPatternUtils(mContext);
        final LockscreenCredential credential = createLockscreenCredential(
                lockType, password);
        final VerifyCredentialResponse response = lockPatternUtils.verifyCredential(
                credential, mContext.getUserId(), /* flags= */ 0);
        if (response == null) {
            return false;
        }
        return response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK;
    }

    private LockscreenCredential createLockscreenCredential(
            @LockTypes int lockType, @Nullable byte[] password) {
        if (password == null) {
            return LockscreenCredential.createNone();
        }
        switch (lockType) {
            case PASSWORD:
                CharSequence passwordStr = new String(password, Charset.forName("UTF-8"));
                return LockscreenCredential.createPassword(passwordStr);
            case PIN:
                CharSequence pinStr = new String(password);
                return LockscreenCredential.createPin(pinStr);
            case PATTERN:
                List<LockPatternView.Cell> pattern =
                        LockPatternUtils.byteArrayToPattern(password);
                return LockscreenCredential.createPattern(pattern);
            default:
                throw new IllegalArgumentException("Unknown lock type " + lockType);
        }
    }
}
