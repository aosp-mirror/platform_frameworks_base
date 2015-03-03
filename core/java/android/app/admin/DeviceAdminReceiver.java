/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app.admin;

import android.accounts.AccountManager;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Base class for implementing a device administration component.  This
 * class provides a convenience for interpreting the raw intent actions
 * that are sent by the system.
 *
 * <p>The callback methods, like the base
 * {@link BroadcastReceiver#onReceive(Context, Intent) BroadcastReceiver.onReceive()}
 * method, happen on the main thread of the process.  Thus long running
 * operations must be done on another thread.  Note that because a receiver
 * is done once returning from its receive function, such long-running operations
 * should probably be done in a {@link Service}.
 *
 * <p>When publishing your DeviceAdmin subclass as a receiver, it must
 * handle {@link #ACTION_DEVICE_ADMIN_ENABLED} and require the
 * {@link android.Manifest.permission#BIND_DEVICE_ADMIN} permission.  A typical
 * manifest entry would look like:</p>
 *
 * {@sample development/samples/ApiDemos/AndroidManifest.xml device_admin_declaration}
 *
 * <p>The meta-data referenced here provides addition information specific
 * to the device administrator, as parsed by the {@link DeviceAdminInfo} class.
 * A typical file would be:</p>
 *
 * {@sample development/samples/ApiDemos/res/xml/device_admin_sample.xml meta_data}
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about device administration, read the
 * <a href="{@docRoot}guide/topics/admin/device-admin.html">Device Administration</a>
 * developer guide.</p>
 * </div>
 */
public class DeviceAdminReceiver extends BroadcastReceiver {
    private static String TAG = "DevicePolicy";
    private static boolean localLOGV = false;

    /**
     * This is the primary action that a device administrator must implement to be
     * allowed to manage a device.  This will be set to the receiver
     * when the user enables it for administration.  You will generally
     * handle this in {@link DeviceAdminReceiver#onEnabled(Context, Intent)}.  To be
     * supported, the receiver must also require the
     * {@link android.Manifest.permission#BIND_DEVICE_ADMIN} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_ADMIN_ENABLED
            = "android.app.action.DEVICE_ADMIN_ENABLED";

    /**
     * Action sent to a device administrator when the user has requested to
     * disable it, but before this has actually been done.  This gives you
     * a chance to supply a message to the user about the impact of
     * disabling your admin, by setting the extra field
     * {@link #EXTRA_DISABLE_WARNING} in the result Intent.  If not set,
     * no warning will be displayed.  If set, the given text will be shown
     * to the user before they disable your admin.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_ADMIN_DISABLE_REQUESTED
            = "android.app.action.DEVICE_ADMIN_DISABLE_REQUESTED";

    /**
     * A CharSequence that can be shown to the user informing them of the
     * impact of disabling your admin.
     *
     * @see #ACTION_DEVICE_ADMIN_DISABLE_REQUESTED
     */
    public static final String EXTRA_DISABLE_WARNING = "android.app.extra.DISABLE_WARNING";

    /**
     * Action sent to a device administrator when the user has disabled
     * it.  Upon return, the application no longer has access to the
     * protected device policy manager APIs.  You will generally
     * handle this in {@link DeviceAdminReceiver#onDisabled(Context, Intent)}.  Note
     * that this action will be
     * sent the receiver regardless of whether it is explicitly listed in
     * its intent filter.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_ADMIN_DISABLED
            = "android.app.action.DEVICE_ADMIN_DISABLED";

    /**
     * Action sent to a device administrator when the user has changed the
     * password of their device.  You can at this point check the characteristics
     * of the new password with {@link DevicePolicyManager#isActivePasswordSufficient()
     * DevicePolicyManager.isActivePasswordSufficient()}.
     * You will generally
     * handle this in {@link DeviceAdminReceiver#onPasswordChanged}.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to receive
     * this broadcast.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PASSWORD_CHANGED
            = "android.app.action.ACTION_PASSWORD_CHANGED";

    /**
     * Action sent to a device administrator when the user has failed at
     * attempted to enter the password.  You can at this point check the
     * number of failed password attempts there have been with
     * {@link DevicePolicyManager#getCurrentFailedPasswordAttempts
     * DevicePolicyManager.getCurrentFailedPasswordAttempts()}.  You will generally
     * handle this in {@link DeviceAdminReceiver#onPasswordFailed}.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} to receive
     * this broadcast.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PASSWORD_FAILED
            = "android.app.action.ACTION_PASSWORD_FAILED";

    /**
     * Action sent to a device administrator when the user has successfully
     * entered their password, after failing one or more times.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} to receive
     * this broadcast.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PASSWORD_SUCCEEDED
            = "android.app.action.ACTION_PASSWORD_SUCCEEDED";

    /**
     * Action periodically sent to a device administrator when the device password
     * is expiring.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_EXPIRE_PASSWORD} to receive
     * this broadcast.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PASSWORD_EXPIRING
            = "android.app.action.ACTION_PASSWORD_EXPIRING";

    /**
     * Action sent to a device administrator to notify that the device is entering
     * lock task mode from an authorized package.  The extra {@link #EXTRA_LOCK_TASK_PACKAGE}
     * will describe the authorized package using lock task mode.
     *
     * <p>The calling device admin must be the device owner or profile
     * owner to receive this broadcast.
     *
     * @see DevicePolicyManager#isLockTaskPermitted(String)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LOCK_TASK_ENTERING
            = "android.app.action.LOCK_TASK_ENTERING";

    /**
     * Action sent to a device administrator to notify that the device is exiting
     * lock task mode from an authorized package.
     *
     * <p>The calling device admin must be the device owner or profile
     * owner to receive this broadcast.
     *
     * @see DevicePolicyManager#isLockTaskPermitted(String)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LOCK_TASK_EXITING
            = "android.app.action.LOCK_TASK_EXITING";

    /**
     * A string containing the name of the package entering lock task mode.
     *
     * @see #ACTION_LOCK_TASK_ENTERING
     */
    public static final String EXTRA_LOCK_TASK_PACKAGE =
            "android.app.extra.LOCK_TASK_PACKAGE";

    /**
     * Broadcast Action: This broadcast is sent to indicate that provisioning of a managed profile
     * or managed device has completed successfully.
     *
     * <p>The broadcast is limited to the profile that will be managed by the application that
     * requested provisioning. In the device owner case the profile is the primary user.
     * The broadcast will also be limited to the {@link DeviceAdminReceiver} component
     * specified in the original intent or NFC bump that started the provisioning process
     * (see {@link DevicePolicyManager#ACTION_PROVISION_MANAGED_PROFILE
     * DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE}).
     *
     * <p>A device admin application which listens to this intent can find out if the device was
     * provisioned for the device owner or profile owner case by calling respectively
     * {@link android.app.admin.DevicePolicyManager#isDeviceOwnerApp} and
     * {@link android.app.admin.DevicePolicyManager#isProfileOwnerApp}.
     *
     * <p>Input: Nothing.</p>
     * <p>Output: Nothing</p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PROFILE_PROVISIONING_COMPLETE =
            "android.app.action.PROFILE_PROVISIONING_COMPLETE";

    /**
     * Name under which a DevicePolicy component publishes information
     * about itself.  This meta-data must reference an XML resource containing
     * a device-admin tag.
     */
    //  TO DO: describe syntax.
    public static final String DEVICE_ADMIN_META_DATA = "android.app.device_admin";

    private DevicePolicyManager mManager;
    private ComponentName mWho;

    /**
     * Retrieve the DevicePolicyManager interface for this administrator to work
     * with the system.
     */
    public DevicePolicyManager getManager(Context context) {
        if (mManager != null) {
            return mManager;
        }
        mManager = (DevicePolicyManager)context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        return mManager;
    }

    /**
     * Retrieve the ComponentName describing who this device administrator is, for
     * use in {@link DevicePolicyManager} APIs that require the administrator to
     * identify itself.
     */
    public ComponentName getWho(Context context) {
        if (mWho != null) {
            return mWho;
        }
        mWho = new ComponentName(context, getClass());
        return mWho;
    }

    /**
     * Called after the administrator is first enabled, as a result of
     * receiving {@link #ACTION_DEVICE_ADMIN_ENABLED}.  At this point you
     * can use {@link DevicePolicyManager} to set your desired policies.
     *
     * <p> If the admin is activated by a device owner, then the intent
     * may contain private extras that are relevant to user setup.
     * {@see DevicePolicyManager#createAndInitializeUser(ComponentName, String, String,
     *      ComponentName, Intent)}
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     */
    public void onEnabled(Context context, Intent intent) {
    }

    /**
     * Called when the user has asked to disable the administrator, as a result of
     * receiving {@link #ACTION_DEVICE_ADMIN_DISABLE_REQUESTED}, giving you
     * a chance to present a warning message to them.  The message is returned
     * as the result; if null is returned (the default implementation), no
     * message will be displayed.
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @return Return the warning message to display to the user before
     * being disabled; if null is returned, no message is displayed.
     */
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return null;
    }

    /**
     * Called prior to the administrator being disabled, as a result of
     * receiving {@link #ACTION_DEVICE_ADMIN_DISABLED}.  Upon return, you
     * can no longer use the protected parts of the {@link DevicePolicyManager}
     * API.
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     */
    public void onDisabled(Context context, Intent intent) {
    }

    /**
     * Called after the user has changed their password, as a result of
     * receiving {@link #ACTION_PASSWORD_CHANGED}.  At this point you
     * can use {@link DevicePolicyManager#getCurrentFailedPasswordAttempts()
     * DevicePolicyManager.getCurrentFailedPasswordAttempts()}
     * to retrieve the active password characteristics.
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     */
    public void onPasswordChanged(Context context, Intent intent) {
    }

    /**
     * Called after the user has failed at entering their current password, as a result of
     * receiving {@link #ACTION_PASSWORD_FAILED}.  At this point you
     * can use {@link DevicePolicyManager} to retrieve the number of failed
     * password attempts.
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     */
    public void onPasswordFailed(Context context, Intent intent) {
    }

    /**
     * Called after the user has succeeded at entering their current password,
     * as a result of receiving {@link #ACTION_PASSWORD_SUCCEEDED}.  This will
     * only be received the first time they succeed after having previously
     * failed.
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     */
    public void onPasswordSucceeded(Context context, Intent intent) {
    }

    /**
     * Called periodically when the password is about to expire or has expired.  It will typically
     * be called at these times: on device boot, once per day before the password expires,
     * and at the time when the password expires.
     *
     * <p>If the password is not updated by the user, this method will continue to be called
     * once per day until the password is changed or the device admin disables password expiration.
     *
     * <p>The admin will typically post a notification requesting the user to change their password
     * in response to this call. The actual password expiration time can be obtained by calling
     * {@link DevicePolicyManager#getPasswordExpiration(ComponentName) }
     *
     * <p>The admin should be sure to take down any notifications it posted in response to this call
     * when it receives {@link DeviceAdminReceiver#onPasswordChanged(Context, Intent) }.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     */
    public void onPasswordExpiring(Context context, Intent intent) {
    }

    /**
     * Called when provisioning of a managed profile or managed device has completed successfully.
     *
     * <p> As a prerequisit for the execution of this callback the (@link DeviceAdminReceiver} has
     * to declare an intent filter for {@link #ACTION_PROFILE_PROVISIONING_COMPLETE}.
     * Its component must also be specified in the {@link DevicePolicyManager#EXTRA_DEVICE_ADMIN}
     * of the {@link DevicePolicyManager#ACTION_PROVISION_MANAGED_PROFILE} intent that started the
     * managed provisioning.
     *
     * <p>When provisioning is complete, the managed profile is hidden until the profile owner
     * calls {DevicePolicyManager#setProfileEnabled(ComponentName admin)}. Typically a profile
     * owner will enable the profile when it has finished any additional setup such as adding an
     * account by using the {@link AccountManager} and calling apis to bring the profile into the
     * desired state.
     *
     * <p> Note that provisioning completes without waiting for any server interactions, so the
     * profile owner needs to wait for data to be available if required (e.g android device ids or
     * other data that is set as a result of server interactions).
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     */
    public void onProfileProvisioningComplete(Context context, Intent intent) {
    }

    /**
     * Called when a device is entering lock task mode by a package authorized
     * by {@link DevicePolicyManager#isLockTaskPermitted(String)}
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param pkg If entering, the authorized package using lock task mode, otherwise null.
     */
    public void onLockTaskModeEntering(Context context, Intent intent, String pkg) {
    }

    /**
     * Called when a device is exiting lock task mode by a package authorized
     * by {@link DevicePolicyManager#isLockTaskPermitted(String)}
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     */
    public void onLockTaskModeExiting(Context context, Intent intent) {
    }

    /**
     * Intercept standard device administrator broadcasts.  Implementations
     * should not override this method; it is better to implement the
     * convenience callbacks for each action.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (ACTION_PASSWORD_CHANGED.equals(action)) {
            onPasswordChanged(context, intent);
        } else if (ACTION_PASSWORD_FAILED.equals(action)) {
            onPasswordFailed(context, intent);
        } else if (ACTION_PASSWORD_SUCCEEDED.equals(action)) {
            onPasswordSucceeded(context, intent);
        } else if (ACTION_DEVICE_ADMIN_ENABLED.equals(action)) {
            onEnabled(context, intent);
        } else if (ACTION_DEVICE_ADMIN_DISABLE_REQUESTED.equals(action)) {
            CharSequence res = onDisableRequested(context, intent);
            if (res != null) {
                Bundle extras = getResultExtras(true);
                extras.putCharSequence(EXTRA_DISABLE_WARNING, res);
            }
        } else if (ACTION_DEVICE_ADMIN_DISABLED.equals(action)) {
            onDisabled(context, intent);
        } else if (ACTION_PASSWORD_EXPIRING.equals(action)) {
            onPasswordExpiring(context, intent);
        } else if (ACTION_PROFILE_PROVISIONING_COMPLETE.equals(action)) {
            onProfileProvisioningComplete(context, intent);
        } else if (ACTION_LOCK_TASK_ENTERING.equals(action)) {
            String pkg = intent.getStringExtra(EXTRA_LOCK_TASK_PACKAGE);
            onLockTaskModeEntering(context, intent, pkg);
        } else if (ACTION_LOCK_TASK_EXITING.equals(action)) {
            onLockTaskModeExiting(context, intent);
        }
    }
}
