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
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManager.PasswordComplexity;
import android.app.admin.PasswordMetrics;
import android.app.trust.ITrustManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
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
import android.os.UserHandle;
import android.provider.Settings;
import android.service.persistentdata.IPersistentDataBlockService;
import android.util.ArrayMap;
import android.util.Log;
import android.view.IOnKeyguardExitResult;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IKeyguardLockedStateListener;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.IWeakEscrowTokenActivatedListener;
import com.android.internal.widget.IWeakEscrowTokenRemovedListener;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.PasswordValidationError;
import com.android.internal.widget.VerifyCredentialResponse;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Class to manage and query the state of the lock screen (also known as Keyguard).
 */
@SystemService(Context.KEYGUARD_SERVICE)
public class KeyguardManager {

    private static final String TAG = "KeyguardManager";

    private final Context mContext;
    private final LockPatternUtils mLockPatternUtils;
    private final IWindowManager mWM;
    private final IActivityManager mAm;
    private final ITrustManager mTrustManager;
    private final INotificationManager mNotificationManager;
    private final ArrayMap<WeakEscrowTokenRemovedListener, IWeakEscrowTokenRemovedListener>
            mListeners = new ArrayMap<>();

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
     * Intent used to prompt user to to validate the credentials of a remote device.
     * @hide
     */
    public static final String ACTION_CONFIRM_REMOTE_DEVICE_CREDENTIAL =
            "android.app.action.CONFIRM_REMOTE_DEVICE_CREDENTIAL";

    /**
     * Intent used to prompt user for device credential for entering repair
     * mode. If the credential is verified successfully, then the information
     * needed to verify the credential again will be written to a location that
     * is available to repair mode. This makes it possible for repair mode to
     * require that the same credential be provided to exit repair mode.
     * @hide
     */
    public static final String ACTION_PREPARE_REPAIR_MODE_DEVICE_CREDENTIAL =
            "android.app.action.PREPARE_REPAIR_MODE_DEVICE_CREDENTIAL";

    /**
     * Intent used to prompt user for device credential that is written by
     * {@link #ACTION_PREPARE_REPAIR_MODE_DEVICE_CREDENTIAL} for exiting
     * repair mode.
     * @hide
     */
    public static final String ACTION_CONFIRM_REPAIR_MODE_DEVICE_CREDENTIAL =
            "android.app.action.CONFIRM_REPAIR_MODE_DEVICE_CREDENTIAL";

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
     * A CharSequence label for the checkbox when used with
     * {@link #ACTION_CONFIRM_REMOTE_DEVICE_CREDENTIAL}
     * @hide
     */
    public static final String EXTRA_CHECKBOX_LABEL = "android.app.extra.CHECKBOX_LABEL";

    /**
     * A {@link RemoteLockscreenValidationSession} extra to be sent along with
     * {@link #ACTION_CONFIRM_REMOTE_DEVICE_CREDENTIAL} containing the data needed to prompt for
     * a remote device's lock screen.
     * @hide
     */
    public static final String EXTRA_REMOTE_LOCKSCREEN_VALIDATION_SESSION =
            "android.app.extra.REMOTE_LOCKSCREEN_VALIDATION_SESSION";

    /**
     * A boolean indicating that credential confirmation activity should be a task overlay.
     * {@link #ACTION_CONFIRM_DEVICE_CREDENTIAL_WITH_USER}.
     * @hide
     */
    public static final String EXTRA_FORCE_TASK_OVERLAY =
            "android.app.KeyguardManager.FORCE_TASK_OVERLAY";

    /**
     * Result code returned by the activity started by
     * {@link #createConfirmFactoryResetCredentialIntent} or
     * {@link #createConfirmDeviceCredentialForRemoteValidationIntent}
     * indicating that the user clicked the alternate button.
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
    @Retention(RetentionPolicy.SOURCE)
    @interface LockTypes {}

    private final IKeyguardLockedStateListener mIKeyguardLockedStateListener =
            new IKeyguardLockedStateListener.Stub() {
                @Override
                public void onKeyguardLockedStateChanged(boolean isKeyguardLocked) {
                    mKeyguardLockedStateListeners.forEach((listener, executor) -> {
                        executor.execute(
                                () -> listener.onKeyguardLockedStateChanged(isKeyguardLocked));
                    });
                }
            };
    private final ArrayMap<KeyguardLockedStateListener, Executor>
            mKeyguardLockedStateListeners = new ArrayMap<>();

    /**
     * Get an intent to prompt the user to confirm credentials (pin, pattern, password or biometrics
     * if enrolled) for the current user of the device. The caller is expected to launch this
     * activity using {@link android.app.Activity#startActivityForResult(Intent, int)} and check for
     * {@link android.app.Activity#RESULT_OK} if the user successfully completes the challenge.
     *
     * @return the intent for launching the activity or null if no password is required.
     *
     * @deprecated see {@link
     *   android.hardware.biometrics.BiometricPrompt.Builder#setAllowedAuthenticators(int)}
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
        if (intent != null) {
            intent.putExtra(EXTRA_DISALLOW_BIOMETRICS_IF_POLICY_EXISTS,
                    disallowBiometricsIfPolicyExists);
        }
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
     * Get an Intent to launch an activity to prompt the user to confirm the
     * credentials (pin, pattern or password) of a remote device.
     * @param session contains information necessary to start remote device credential validation.
     * @param remoteLockscreenValidationServiceComponent
     *          the {@link ComponentName} of the implementation of
     *          {@link android.service.remotelockscreenvalidation.RemoteLockscreenValidationService}
     * @param checkboxLabel if not empty, a checkbox is provided with the given label. When checked,
     *                      the validated remote device credential will be set as the device lock of
     *                      the current device.
     * @param alternateButtonLabel if not empty, a button is provided with the given label. Upon
     *                             clicking this button, the activity returns
     *                             {@link #RESULT_ALTERNATE}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CHECK_REMOTE_LOCKSCREEN)
    @NonNull
    public Intent createConfirmDeviceCredentialForRemoteValidationIntent(
            @NonNull RemoteLockscreenValidationSession session,
            @NonNull ComponentName remoteLockscreenValidationServiceComponent,
            @Nullable CharSequence title,
            @Nullable CharSequence description,
            @Nullable CharSequence checkboxLabel,
            @Nullable CharSequence alternateButtonLabel) {
        Intent intent = new Intent(ACTION_CONFIRM_REMOTE_DEVICE_CREDENTIAL)
                .putExtra(EXTRA_REMOTE_LOCKSCREEN_VALIDATION_SESSION, session)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, remoteLockscreenValidationServiceComponent)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DESCRIPTION, description)
                .putExtra(EXTRA_CHECKBOX_LABEL, checkboxLabel)
                .putExtra(EXTRA_ALTERNATE_BUTTON_LABEL, alternateButtonLabel);

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
     * you to temporarily disable / reenable the keyguard (lock screen).
     *
     * @deprecated Use {@link android.R.attr#showWhenLocked} or {@link
     *   android.app.Activity#setShowWhenLocked(boolean)} instead. This allows you to seamlessly
     *   occlude and unocclude the keyguard as your application moves in and out of the foreground
     *   and does not require that any special permissions be requested.
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
         * <p>
         * This only works if the keyguard is not secure.
         * <p>
         * A good place to call this is from {@link android.app.Activity#onResume()}
         *
         * @see KeyguardManager#isKeyguardSecure()
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

    /**
     * Callback passed to
     * {@link KeyguardManager#addWeakEscrowToken}
     * to notify caller of state change.
     * @hide
     */
    @SystemApi
    public interface WeakEscrowTokenActivatedListener {
        /**
         * The method to be called when the token is activated.
         * @param handle 64 bit handle corresponding to the escrow token
         * @param user user for whom the weak escrow token has been added
         */
        void onWeakEscrowTokenActivated(long handle, @NonNull UserHandle user);
    }

    /**
     * Listener passed to
     * {@link KeyguardManager#registerWeakEscrowTokenRemovedListener} and
     * {@link KeyguardManager#unregisterWeakEscrowTokenRemovedListener}
     * to notify caller of an weak escrow token has been removed.
     * @hide
     */
    @SystemApi
    public interface WeakEscrowTokenRemovedListener {
        /**
         * The method to be called when the token is removed.
         * @param handle 64 bit handle corresponding to the escrow token
         * @param user user for whom the escrow token has been added
         */
        void onWeakEscrowTokenRemoved(long handle, @NonNull UserHandle user);
    }

    KeyguardManager(Context context) throws ServiceNotFoundException {
        mContext = context;
        mLockPatternUtils = new LockPatternUtils(context);
        mWM = WindowManagerGlobal.getWindowManagerService();
        mAm = ActivityManager.getService();
        mTrustManager = ITrustManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TRUST_SERVICE));
        mNotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.NOTIFICATION_SERVICE));
    }

    /**
     * Enables you to temporarily disable / reenable the keyguard (lock screen).
     *
     * @param tag A tag that informally identifies who you are (for debugging who
     *   is disabling the keyguard).
     *
     * @return A {@link KeyguardLock} handle to use to disable and reenable the
     *   keyguard.
     *
     * @deprecated Use {@link android.R.attr#showWhenLocked} or {@link
     *   android.app.Activity#setShowWhenLocked(boolean)} instead. This allows you to seamlessly
     *   occlude and unocclude the keyguard as your application moves in and out of the foreground
     *   and does not require that any special permissions be requested.
     */
    @Deprecated
    public KeyguardLock newKeyguardLock(String tag) {
        return new KeyguardLock(tag);
    }

    /**
     * Returns whether the lock screen (also known as Keyguard) is showing.
     * <p>
     * Specifically, this returns {@code true} in the following cases:
     * <ul>
     *   <li>The lock screen is showing in the foreground.</li>
     *   <li>The lock screen is showing, but it is occluded by an activity that is showing on top of
     *   it. A common example is the phone app receiving a call or making an emergency call.</li>
     *   <li>The lock screen was showing but is temporarily disabled as a result of <a
     *   href="https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode">lock task
     *   mode</a> or an app using the deprecated {@link KeyguardLock} API.</li>
     * </ul>
     * <p>
     * "Showing" refers to a logical state of the UI, regardless of whether the screen happens to be
     * on. When the power button is pressed on an unlocked device, the lock screen starts "showing"
     * immediately when the screen turns off.
     * <p>
     * This method does not distinguish a lock screen that is requiring authentication (e.g. with
     * PIN, pattern, password, or biometric) from a lock screen that is trivially dismissible (e.g.
     * with swipe). It also does not distinguish a lock screen requesting a SIM card PIN from a
     * normal device lock screen. Finally, it always returns the global lock screen state and does
     * not consider the {@link Context}'s user specifically.
     * <p>
     * Note that {@code isKeyguardLocked()} is confusingly named and probably should be called
     * {@code isKeyguardShowing()}. On many devices, the lock screen displays an <i>unlocked</i>
     * padlock icon when it is trivially dismissible. As mentioned above, {@code isKeyguardLocked()}
     * actually returns {@code true} in this case, not {@code false} as might be expected. {@link
     * #isDeviceLocked()} is an alternative API that has slightly different semantics.
     *
     * @return {@code true} if the lock screen is showing
     * @see #isDeviceLocked()
     */
    public boolean isKeyguardLocked() {
        try {
            return mWM.isKeyguardLocked();
        } catch (RemoteException ex) {
            return false;
        }
    }

    /**
     * Returns whether the user has a secure lock screen or there is a locked SIM card.
     * <p>
     * Specifically, this returns {@code true} if at least one of the following is true:
     * <ul>
     *   <li>The {@link Context}'s user has a secure lock screen. A full user or a profile that uses
     *   a separate challenge has a secure lock screen if its lock screen is set to PIN, pattern, or
     *   password, as opposed to swipe or none. A profile that uses a unified challenge is
     *   considered to have a secure lock screen if and only if its parent user has a secure lock
     *   screen.</li>
     *   <li>At least one SIM card is currently locked and requires a PIN.</li>
     * </ul>
     * <p>
     * This method does not consider whether the lock screen is currently showing or not.
     * <p>
     * See also {@link #isDeviceSecure()} which excludes locked SIM cards.
     *
     * @return {@code true} if the user has a secure lock screen or there is a locked SIM card
     * @see #isDeviceSecure()
     */
    public boolean isKeyguardSecure() {
        try {
            return mWM.isKeyguardSecure(mContext.getUserId());
        } catch (RemoteException ex) {
            return false;
        }
    }

    /**
     * Returns whether the lock screen is showing.
     * <p>
     * This is exactly the same as {@link #isKeyguardLocked()}.
     *
     * @return the value of {@link #isKeyguardLocked()}
     * @deprecated Use {@link #isKeyguardLocked()} instead.
     */
    public boolean inKeyguardRestrictedInputMode() {
        return isKeyguardLocked();
    }

    /**
     * Returns whether the device is currently locked for the user.
     * <p>
     * This method returns the device locked state for the {@link Context}'s user. The device is
     * considered to be locked for a user when the user's apps are currently inaccessible and some
     * form of lock screen authentication is required to regain access to them. The lock screen
     * authentication typically uses PIN, pattern, password, or biometric. Some devices may support
     * additional methods, such as unlock using a paired smartwatch. "Swipe" does not count as
     * authentication; if the lock screen is dismissible with swipe, for example due to the lock
     * screen being set to Swipe or due to the device being kept unlocked by being near a trusted
     * bluetooth device or in a trusted location, the device is considered unlocked.
     * <div class="note">
     * <p>
     * <b>Note:</b> In the case of multiple full users, each user can have their own lock screen
     * authentication configured. The device-locked state may differ between different users. For
     * example, the device may be unlocked for the current user, but locked for a non-current user
     * if lock screen authentication would be required to access that user's apps after switching to
     * that user.
     * <p>
     * In the case of a profile, when the device goes to the main lock screen, up to two layers of
     * authentication may be required to regain access to the profile's apps: one to unlock the main
     * lock screen, and one to unlock the profile (when a separate profile challenge is required).
     * For a profile, the device is considered to be locked as long as any challenge remains, either
     * the parent user's challenge (when applicable) or the profile's challenge (when applicable).
     * </div>
     *
     * @return {@code true} if the device is currently locked for the user
     * @see #isKeyguardLocked()
     */
    public boolean isDeviceLocked() {
        return isDeviceLocked(mContext.getUserId(), mContext.getDeviceId());
    }

    /**
     * Per-user version of {@link #isDeviceLocked()}.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public boolean isDeviceLocked(int userId) {
        return isDeviceLocked(userId, mContext.getDeviceId());
    }

    /**
     * Per-user per-device version of {@link #isDeviceLocked()}.
     *
     * @hide
     */
    public boolean isDeviceLocked(@UserIdInt int userId, int deviceId) {
        try {
            return mTrustManager.isDeviceLocked(userId, deviceId);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Returns whether the user has a secure lock screen.
     * <p>
     * This returns {@code true} if the {@link Context}'s user has a secure lock screen. A full user
     * or a profile that uses a separate challenge has a secure lock screen if its lock screen is
     * set to PIN, pattern, or password, as opposed to swipe or none. A profile that uses a unified
     * challenge is considered to have a secure lock screen if and only if its parent user has a
     * secure lock screen.
     * <p>
     * This method does not consider whether the lock screen is currently showing or not.
     * <p>
     * See also {@link #isKeyguardSecure()} which includes locked SIM cards.
     *
     * @return {@code true} if the user has a secure lock screen
     * @see #isKeyguardSecure()
     */
    public boolean isDeviceSecure() {
        return isDeviceSecure(mContext.getUserId(), mContext.getDeviceId());
    }

    /**
     * Per-user version of {@link #isDeviceSecure()}.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isDeviceSecure(int userId) {
        return isDeviceSecure(userId, mContext.getDeviceId());
    }

    /**
     * Per-user per-device version of {@link #isDeviceSecure()}.
     *
     * @hide
     */
    public boolean isDeviceSecure(@UserIdInt int userId, int deviceId) {
        try {
            return mTrustManager.isDeviceSecure(userId, deviceId);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Requests that the Keyguard (lock screen) be dismissed if it is currently showing.
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
     * @param activity The activity requesting the dismissal. The activity must either be visible
     *                 by using {@link android.R.attr#showWhenLocked} or {@link
     *                 android.app.Activity#setShowWhenLocked(boolean)}, or must be in a state in
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
     * Requests that the Keyguard (lock screen) be dismissed if it is currently showing.
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
     * @param activity The activity requesting the dismissal. The activity must either be visible
     *                 by using {@link android.R.attr#showWhenLocked} or {@link
     *                 android.app.Activity#setShowWhenLocked(boolean)}, or must be in a state in
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
     *
     * @deprecated Use {@link android.R.attr#showWhenLocked} or {@link
     *   android.app.Activity#setShowWhenLocked(boolean)} to seamlessly occlude and unocclude the
     *   keyguard as your application moves in and out of the foreground, without requiring any
     *   special permissions. Use {@link #requestDismissKeyguard(android.app.Activity,
     *   KeyguardDismissCallback)} to request dismissal of the keyguard.
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

    /** @hide */
    @VisibleForTesting
    public boolean checkInitialLockMethodUsage() {
        if (!hasPermission(Manifest.permission.SET_INITIAL_LOCK)) {
            throw new SecurityException("Requires SET_INITIAL_LOCK permission.");
        }
        return true;
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
    * @return {@code true} if the password is valid, false otherwise
    * @hide
    */
    @RequiresPermission(Manifest.permission.SET_INITIAL_LOCK)
    @SystemApi
    public boolean isValidLockPasswordComplexity(@LockTypes int lockType, @NonNull byte[] password,
            @PasswordComplexity int complexity) {
        if (!checkInitialLockMethodUsage()) {
            return false;
        }
        Objects.requireNonNull(password, "Password cannot be null.");
        complexity = PasswordMetrics.sanitizeComplexityLevel(complexity);
        PasswordMetrics adminMetrics =
                mLockPatternUtils.getRequestedPasswordMetrics(mContext.getUserId());
        try (LockscreenCredential credential = createLockscreenCredential(lockType, password)) {
            return PasswordMetrics.validateCredential(adminMetrics, complexity,
                    credential).size() == 0;
        }
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
        PasswordMetrics adminMetrics =
                mLockPatternUtils.getRequestedPasswordMetrics(mContext.getUserId());
        PasswordMetrics minMetrics =
                PasswordMetrics.applyComplexity(adminMetrics, isPin, complexity);
        return minMetrics.length;
    }

    /**
    * Set the lockscreen password after validating against its expected complexity level.
    *
    * Below {@link android.os.Build.VERSION_CODES#S_V2}, this API will only work
    * when {@link PackageManager.FEATURE_AUTOMOTIVE} is present.
    * @param lockType - type of lock as specified in {@link LockTypes}
    * @param password - password to validate; this has the same encoding
    *        as the output of String#getBytes
    * @param complexity - complexity level imposed by the requester
    *        as defined in {@code DevicePolicyManager.PasswordComplexity}
    * @return {@code true} if the lock is successfully set, false otherwise
    * @hide
    */
    @RequiresPermission(Manifest.permission.SET_INITIAL_LOCK)
    @SystemApi
    public boolean setLock(@LockTypes int lockType, @NonNull byte[] password,
            @PasswordComplexity int complexity) {
        if (!checkInitialLockMethodUsage()) {
            return false;
        }

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
        try (LockscreenCredential credential = createLockscreenCredential(lockType, password)) {
            success = mLockPatternUtils.setLockCredential(
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
     * Create a weak escrow token for the current user, which can later be used to unlock FBE
     * or change user password.
     *
     * After adding, if the user currently  has a secure lockscreen, they will need to perform a
     * confirm credential operation in order to activate the token for future use. If the user
     * has no secure lockscreen, then the token is activated immediately.
     *
     * If the user changes or removes the lockscreen password, any activated weak escrow token will
     * be removed.
     *
     * @return a unique 64-bit token handle which is needed to refer to this token later.
     * @hide
     */
    @RequiresFeature(PackageManager.FEATURE_AUTOMOTIVE)
    @RequiresPermission(Manifest.permission.MANAGE_WEAK_ESCROW_TOKEN)
    @SystemApi
    public long addWeakEscrowToken(@NonNull byte[] token, @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull WeakEscrowTokenActivatedListener listener) {
        Objects.requireNonNull(token, "Token cannot be null.");
        Objects.requireNonNull(user, "User cannot be null.");
        Objects.requireNonNull(executor, "Executor cannot be null.");
        Objects.requireNonNull(listener, "Listener cannot be null.");
        int userId = user.getIdentifier();
        IWeakEscrowTokenActivatedListener internalListener =
                new IWeakEscrowTokenActivatedListener.Stub() {
            @Override
            public void onWeakEscrowTokenActivated(long handle, int userId) {
                UserHandle user = UserHandle.of(userId);
                final long restoreToken = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> listener.onWeakEscrowTokenActivated(handle, user));
                } finally {
                    Binder.restoreCallingIdentity(restoreToken);
                }
                Log.i(TAG, "Weak escrow token activated.");
            }
        };
        return mLockPatternUtils.addWeakEscrowToken(token, userId, internalListener);
    }

    /**
     * Remove a weak escrow token.
     *
     * @return {@code true} if the given handle refers to a valid weak token previously returned
     * from {@link #addWeakEscrowToken}, whether it's active or not. return false otherwise.
     * @hide
     */
    @RequiresFeature(PackageManager.FEATURE_AUTOMOTIVE)
    @RequiresPermission(Manifest.permission.MANAGE_WEAK_ESCROW_TOKEN)
    @SystemApi
    public boolean removeWeakEscrowToken(long handle, @NonNull UserHandle user) {
        Objects.requireNonNull(user, "User cannot be null.");
        return mLockPatternUtils.removeWeakEscrowToken(handle, user.getIdentifier());
    }

    /**
     * Check if the given weak escrow token is active or not.
     * @hide
     */
    @RequiresFeature(PackageManager.FEATURE_AUTOMOTIVE)
    @RequiresPermission(Manifest.permission.MANAGE_WEAK_ESCROW_TOKEN)
    @SystemApi
    public boolean isWeakEscrowTokenActive(long handle, @NonNull UserHandle user) {
        Objects.requireNonNull(user, "User cannot be null.");
        return mLockPatternUtils.isWeakEscrowTokenActive(handle, user.getIdentifier());
    }

    /**
     * Check if the given weak escrow token is validate.
     * @hide
     */
    @RequiresFeature(PackageManager.FEATURE_AUTOMOTIVE)
    @RequiresPermission(Manifest.permission.MANAGE_WEAK_ESCROW_TOKEN)
    @SystemApi
    public boolean isWeakEscrowTokenValid(long handle, @NonNull byte[] token,
            @NonNull UserHandle user) {
        Objects.requireNonNull(token, "Token cannot be null.");
        Objects.requireNonNull(user, "User cannot be null.");
        return mLockPatternUtils.isWeakEscrowTokenValid(handle, token, user.getIdentifier());
    }

    /**
     * Register the given WeakEscrowTokenRemovedListener.
     *
     * @return {@code true} if the listener is registered successfully, return false otherwise.
     * @hide
     */
    @RequiresFeature(PackageManager.FEATURE_AUTOMOTIVE)
    @RequiresPermission(Manifest.permission.MANAGE_WEAK_ESCROW_TOKEN)
    @SystemApi
    public boolean registerWeakEscrowTokenRemovedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull WeakEscrowTokenRemovedListener listener) {
        Objects.requireNonNull(listener, "Listener cannot be null.");
        Objects.requireNonNull(executor, "Executor cannot be null.");
        Preconditions.checkArgument(!mListeners.containsKey(listener),
                "Listener already registered: %s", listener);
        IWeakEscrowTokenRemovedListener internalListener =
                new IWeakEscrowTokenRemovedListener.Stub() {
            @Override
            public void onWeakEscrowTokenRemoved(long handle, int userId) {
                UserHandle user = UserHandle.of(userId);
                final long token = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> listener.onWeakEscrowTokenRemoved(handle, user));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        };
        if (mLockPatternUtils.registerWeakEscrowTokenRemovedListener(internalListener)) {
            mListeners.put(listener, internalListener);
            return true;
        } else {
            Log.e(TAG, "Listener failed to register");
            return false;
        }
    }

    /**
     * Unregister the given WeakEscrowTokenRemovedListener.
     *
     * @return {@code true} if the listener is unregistered successfully, return false otherwise.
     * @hide
     */
    @RequiresFeature(PackageManager.FEATURE_AUTOMOTIVE)
    @RequiresPermission(Manifest.permission.MANAGE_WEAK_ESCROW_TOKEN)
    @SystemApi
    public boolean unregisterWeakEscrowTokenRemovedListener(
            @NonNull WeakEscrowTokenRemovedListener listener) {
        Objects.requireNonNull(listener, "Listener cannot be null.");
        IWeakEscrowTokenRemovedListener internalListener = mListeners.get(listener);
        Preconditions.checkArgument(internalListener != null, "Listener was not registered");
        if (mLockPatternUtils.unregisterWeakEscrowTokenRemovedListener(internalListener)) {
            mListeners.remove(listener);
            return true;
        } else {
            Log.e(TAG, "Listener failed to unregister.");
            return false;
        }
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
        final int userId = mContext.getUserId();
        try (LockscreenCredential currentCredential = createLockscreenCredential(
                currentLockType, currentPassword);
                LockscreenCredential newCredential = createLockscreenCredential(
                        newLockType, newPassword)) {
            PasswordMetrics adminMetrics =
                    mLockPatternUtils.getRequestedPasswordMetrics(mContext.getUserId());
            List<PasswordValidationError> errors = PasswordMetrics.validateCredential(adminMetrics,
                    DevicePolicyManager.PASSWORD_COMPLEXITY_NONE, newCredential);
            if (!errors.isEmpty()) {
                Log.e(TAG, "New credential is not valid: " + errors.get(0));
                return false;
            }
            return mLockPatternUtils.setLockCredential(newCredential, currentCredential, userId);
        }
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
        try (LockscreenCredential credential = createLockscreenCredential(lockType, password)) {
            final VerifyCredentialResponse response = mLockPatternUtils.verifyCredential(
                    credential, mContext.getUserId(), /* flags= */ 0);
            if (response == null) {
                return false;
            }
            return response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK;
        }
    }

    /** Starts a session to verify lockscreen credentials provided by a remote device.
     *
     * The session and corresponding public key will be removed when
     * {@code validateRemoteLockScreen} provides a correct guess or after 10 minutes of inactivity.
     *
     * @return information necessary to perform remote lock screen credentials check, including

     * short lived public key used to send encrypted guess and lock screen type.
     *
     * @throws IllegalStateException if lock screen is not set
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CHECK_REMOTE_LOCKSCREEN)
    @NonNull
    public RemoteLockscreenValidationSession startRemoteLockscreenValidation() {
        return mLockPatternUtils.startRemoteLockscreenValidation();
    }

    /**
     * Verifies credentials guess from a remote device.
     *
     * <p>Secret must be encrypted using {@code SecureBox} library
     * with public key from {@code RemoteLockscreenValidationSession}
     * and header set to {@code "encrypted_remote_credentials"} in UTF-8 encoding.
     *
     * @throws IllegalStateException if there was a decryption error.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CHECK_REMOTE_LOCKSCREEN)
    @NonNull
    public RemoteLockscreenValidationResult validateRemoteLockscreen(
            @NonNull byte[] encryptedCredential) {
        return mLockPatternUtils.validateRemoteLockscreen(encryptedCredential);
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

    /**
     * Listener for keyguard locked state changes.
     */
    @FunctionalInterface
    public interface KeyguardLockedStateListener {
        /**
         * Callback function that executes when the keyguard locked state changes.
         */
        void onKeyguardLockedStateChanged(boolean isKeyguardLocked);
    }

    /**
     * Registers a listener to execute when the keyguard locked state changes.
     *
     * @param listener The listener to add to receive keyguard locked state changes.
     *
     * @see #isKeyguardLocked()
     * @see #removeKeyguardLockedStateListener(KeyguardLockedStateListener)
     */
    @RequiresPermission(Manifest.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE)
    public void addKeyguardLockedStateListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull KeyguardLockedStateListener listener) {
        synchronized (mKeyguardLockedStateListeners) {
            mKeyguardLockedStateListeners.put(listener, executor);
            if (mKeyguardLockedStateListeners.size() > 1) {
                return;
            }
            try {
                mWM.addKeyguardLockedStateListener(mIKeyguardLockedStateListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters a listener that executes when the keyguard locked state changes.
     *
     * @param listener The listener to remove.
     *
     * @see #isKeyguardLocked()
     * @see #addKeyguardLockedStateListener(Executor, KeyguardLockedStateListener)
     */
    @RequiresPermission(Manifest.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE)
    public void removeKeyguardLockedStateListener(@NonNull KeyguardLockedStateListener listener) {
        synchronized (mKeyguardLockedStateListeners) {
            mKeyguardLockedStateListeners.remove(listener);
            if (!mKeyguardLockedStateListeners.isEmpty()) {
                return;
            }
            try {
                mWM.removeKeyguardLockedStateListener(mIKeyguardLockedStateListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
