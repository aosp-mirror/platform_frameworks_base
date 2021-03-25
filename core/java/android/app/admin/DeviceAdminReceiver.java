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

import static android.app.admin.DevicePolicyManager.OperationSafetyReason;

import android.accounts.AccountManager;
import android.annotation.BroadcastBehavior;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;
import android.security.KeyChain;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
    private static final String TAG = "DevicePolicy";
    private static final boolean LOCAL_LOGV = false;

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
    @BroadcastBehavior(explicitOnly = true)
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
    @BroadcastBehavior(explicitOnly = true)
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
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_DEVICE_ADMIN_DISABLED
            = "android.app.action.DEVICE_ADMIN_DISABLED";

    /**
     * Action sent to a device administrator when the user has changed the password of their device
     * or profile challenge.  You can at this point check the characteristics
     * of the new password with {@link DevicePolicyManager#isActivePasswordSufficient()
     * DevicePolicyManager.isActivePasswordSufficient()}.
     * You will generally
     * handle this in {@link DeviceAdminReceiver#onPasswordChanged(Context, Intent, UserHandle)}.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to receive
     * this broadcast.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_PASSWORD_CHANGED
            = "android.app.action.ACTION_PASSWORD_CHANGED";

    /**
     * Action sent to a device administrator when the user has entered an incorrect device
     * or profile challenge password.  You can at this point check the
     * number of failed password attempts there have been with
     * {@link DevicePolicyManager#getCurrentFailedPasswordAttempts
     * DevicePolicyManager.getCurrentFailedPasswordAttempts()}.  You will generally
     * handle this in {@link DeviceAdminReceiver#onPasswordFailed(Context, Intent, UserHandle)}.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} to receive
     * this broadcast.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_PASSWORD_FAILED
            = "android.app.action.ACTION_PASSWORD_FAILED";

    /**
     * Action sent to a device administrator when the user has successfully entered their device
     * or profile challenge password, after failing one or more times.  You will generally
     * handle this in {@link DeviceAdminReceiver#onPasswordSucceeded(Context, Intent, UserHandle)}.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} to receive
     * this broadcast.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_PASSWORD_SUCCEEDED
            = "android.app.action.ACTION_PASSWORD_SUCCEEDED";

    /**
     * Action periodically sent to a device administrator when the device or profile challenge
     * password is expiring.  You will generally
     * handle this in {@link DeviceAdminReceiver#onPasswordExpiring(Context, Intent, UserHandle)}.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_EXPIRE_PASSWORD} to receive
     * this broadcast.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_PASSWORD_EXPIRING
            = "android.app.action.ACTION_PASSWORD_EXPIRING";

    /**
     * Action sent to a device administrator to notify that the device is entering
     * lock task mode.  The extra {@link #EXTRA_LOCK_TASK_PACKAGE}
     * will describe the package using lock task mode.
     *
     * <p>The calling device admin must be the device owner or profile
     * owner to receive this broadcast.
     *
     * @see DevicePolicyManager#isLockTaskPermitted(String)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_LOCK_TASK_ENTERING
            = "android.app.action.LOCK_TASK_ENTERING";

    /**
     * Action sent to a device administrator to notify that the device is exiting
     * lock task mode.
     *
     * <p>The calling device admin must be the device owner or profile
     * owner to receive this broadcast.
     *
     * @see DevicePolicyManager#isLockTaskPermitted(String)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
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
     * {@link android.app.admin.DevicePolicyManager#isProfileOwnerApp}. You will generally handle
     * this in {@link DeviceAdminReceiver#onProfileProvisioningComplete}.
     *
     * @see DevicePolicyManager#ACTION_PROVISIONING_SUCCESSFUL
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_PROFILE_PROVISIONING_COMPLETE =
            "android.app.action.PROFILE_PROVISIONING_COMPLETE";

    /**
     * Action sent to a device administrator to notify that the device user
     * has declined sharing a bugreport.
     *
     * <p>The calling device admin must be the device owner to receive this broadcast.
     * @see DevicePolicyManager#requestBugreport
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_BUGREPORT_SHARING_DECLINED =
            "android.app.action.BUGREPORT_SHARING_DECLINED";

    /**
     * Action sent to a device administrator to notify that the collection of a bugreport
     * has failed.
     *
     * <p>The calling device admin must be the device owner to receive this broadcast.
     * @see DevicePolicyManager#requestBugreport
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_BUGREPORT_FAILED = "android.app.action.BUGREPORT_FAILED";

    /**
     * Action sent to a device administrator to share the bugreport.
     *
     * <p>The calling device admin must be the device owner to receive this broadcast.
     * @see DevicePolicyManager#requestBugreport
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_BUGREPORT_SHARE =
            "android.app.action.BUGREPORT_SHARE";

    /**
     * Broadcast action: notify that a new batch of security logs is ready to be collected.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_SECURITY_LOGS_AVAILABLE
            = "android.app.action.SECURITY_LOGS_AVAILABLE";

    /**
     * Broadcast action: notify that a new batch of network logs is ready to be collected.
     * @see DeviceAdminReceiver#onNetworkLogsAvailable
     * @see DelegatedAdminReceiver#onNetworkLogsAvailable
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_NETWORK_LOGS_AVAILABLE
            = "android.app.action.NETWORK_LOGS_AVAILABLE";

    /**
     * A {@code long} containing a token of the current batch of network logs, that has to be used
     * to retrieve the batch of logs by the device owner.
     *
     * @see #ACTION_NETWORK_LOGS_AVAILABLE
     * @see DevicePolicyManager#retrieveNetworkLogs
     * @hide
     */
    public static final String EXTRA_NETWORK_LOGS_TOKEN =
            "android.app.extra.EXTRA_NETWORK_LOGS_TOKEN";

    /**
     * An {@code int} count representing a total count of network logs inside the current batch of
     * network logs.
     *
     * @see #ACTION_NETWORK_LOGS_AVAILABLE
     * @hide
     */
    public static final String EXTRA_NETWORK_LOGS_COUNT =
            "android.app.extra.EXTRA_NETWORK_LOGS_COUNT";

    /**
     * Broadcast action: notify the device owner that a user or profile has been added.
     * Carries an extra {@link Intent#EXTRA_USER} that has the {@link UserHandle} of
     * the new user.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_USER_ADDED = "android.app.action.USER_ADDED";

    /**
     * Broadcast action: notify the device owner that a user or profile has been removed.
     * Carries an extra {@link Intent#EXTRA_USER} that has the {@link UserHandle} of
     * the user.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_USER_REMOVED = "android.app.action.USER_REMOVED";

    /**
     * Broadcast action: notify the device owner that a user or profile has been started.
     * Carries an extra {@link Intent#EXTRA_USER} that has the {@link UserHandle} of
     * the user.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_USER_STARTED = "android.app.action.USER_STARTED";

    /**
     * Broadcast action: notify the device owner that a user or profile has been stopped.
     * Carries an extra {@link Intent#EXTRA_USER} that has the {@link UserHandle} of
     * the user.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_USER_STOPPED = "android.app.action.USER_STOPPED";

    /**
     * Broadcast action: notify the device owner that a user or profile has been switched to.
     * Carries an extra {@link Intent#EXTRA_USER} that has the {@link UserHandle} of
     * the user.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_USER_SWITCHED = "android.app.action.USER_SWITCHED";

    /**
     * A string containing the SHA-256 hash of the bugreport file.
     *
     * @see #ACTION_BUGREPORT_SHARE
     * @hide
     */
    public static final String EXTRA_BUGREPORT_HASH = "android.app.extra.BUGREPORT_HASH";

    /**
     * An {@code int} failure code representing the reason of the bugreport failure. One of
     * {@link #BUGREPORT_FAILURE_FAILED_COMPLETING}
     * or {@link #BUGREPORT_FAILURE_FILE_NO_LONGER_AVAILABLE}
     *
     * @see #ACTION_BUGREPORT_FAILED
     * @hide
     */
    public static final String EXTRA_BUGREPORT_FAILURE_REASON =
            "android.app.extra.BUGREPORT_FAILURE_REASON";

    /**
     * An interface representing reason of bugreport failure.
     *
     * @see #EXTRA_BUGREPORT_FAILURE_REASON
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "BUGREPORT_FAILURE_" }, value = {
            BUGREPORT_FAILURE_FAILED_COMPLETING,
            BUGREPORT_FAILURE_FILE_NO_LONGER_AVAILABLE
    })
    public @interface BugreportFailureCode {}

    /**
     * Bugreport completion process failed.
     *
     * <p>If this error code is received, the requesting of bugreport can be retried.
     * @see DevicePolicyManager#requestBugreport
     */
    public static final int BUGREPORT_FAILURE_FAILED_COMPLETING = 0;

    /**
     * Bugreport has been created, but is no longer available for collection.
     *
     * <p>This error likely occurs because the user of the device hasn't consented to share
     * the bugreport for a long period after its creation.
     *
     * <p>If this error code is received, the requesting of bugreport can be retried.
     * @see DevicePolicyManager#requestBugreport
     */
    public static final int BUGREPORT_FAILURE_FILE_NO_LONGER_AVAILABLE = 1;

    /**
     * Broadcast action: notify that some app is attempting to choose a KeyChain key.
     * @see DeviceAdminReceiver#onChoosePrivateKeyAlias
     * @see DelegatedAdminReceiver#onChoosePrivateKeyAlias
     */
    public static final String ACTION_CHOOSE_PRIVATE_KEY_ALIAS =
            "android.app.action.CHOOSE_PRIVATE_KEY_ALIAS";

    /** @hide */
    public static final String EXTRA_CHOOSE_PRIVATE_KEY_SENDER_UID =
            "android.app.extra.CHOOSE_PRIVATE_KEY_SENDER_UID";

    /** @hide */
    public static final String EXTRA_CHOOSE_PRIVATE_KEY_URI =
            "android.app.extra.CHOOSE_PRIVATE_KEY_URI";

    /** @hide */
    public static final String EXTRA_CHOOSE_PRIVATE_KEY_ALIAS =
            "android.app.extra.CHOOSE_PRIVATE_KEY_ALIAS";

    /** @hide */
    public static final String EXTRA_CHOOSE_PRIVATE_KEY_RESPONSE =
            "android.app.extra.CHOOSE_PRIVATE_KEY_RESPONSE";

    /**
     * Broadcast action: notify device owner that there is a pending system update.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_NOTIFY_PENDING_SYSTEM_UPDATE =
            "android.app.action.NOTIFY_PENDING_SYSTEM_UPDATE";

    /**
     * A long type extra for {@link #onSystemUpdatePending} recording the system time as given by
     * {@link System#currentTimeMillis()} when the current pending system update is first available.
     * @hide
     */
    public static final String EXTRA_SYSTEM_UPDATE_RECEIVED_TIME =
            "android.app.extra.SYSTEM_UPDATE_RECEIVED_TIME";

    /**
     * Name under which a DevicePolicy component publishes information
     * about itself.  This meta-data must reference an XML resource containing
     * a device-admin tag.
     */
    //  TO DO: describe syntax.
    public static final String DEVICE_ADMIN_META_DATA = "android.app.device_admin";

    /**
     * Broadcast action: notify the newly transferred administrator that the transfer
     * from the original administrator was successful.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_TRANSFER_OWNERSHIP_COMPLETE =
            "android.app.action.TRANSFER_OWNERSHIP_COMPLETE";

    /**
     * Broadcast action: notify the device owner that the ownership of one of its affiliated
     * profiles is transferred.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_AFFILIATED_PROFILE_TRANSFER_OWNERSHIP_COMPLETE =
            "android.app.action.AFFILIATED_PROFILE_TRANSFER_OWNERSHIP_COMPLETE";

    /**
     * A {@link android.os.Parcelable} extra of type {@link android.os.PersistableBundle} that
     * allows a mobile device management application to pass data to the management application
     * instance after owner transfer.
     *
     * <p>If the transfer is successful, the new owner receives the data in
     * {@link DeviceAdminReceiver#onTransferOwnershipComplete(Context, PersistableBundle)}.
     * The bundle is not changed during the ownership transfer.
     *
     * @see DevicePolicyManager#transferOwnership(ComponentName, ComponentName, PersistableBundle)
     */
    public static final String EXTRA_TRANSFER_OWNERSHIP_ADMIN_EXTRAS_BUNDLE =
            "android.app.extra.TRANSFER_OWNERSHIP_ADMIN_EXTRAS_BUNDLE";

    /**
     * Broadcast action: notify the admin that the state of operations that can be unsafe because
     * of a given reason (specified by the {@link #EXTRA_OPERATION_SAFETY_REASON} {@code int} extra)
     * has changed (the new value is specified by the {@link #EXTRA_OPERATION_SAFETY_STATE}
     * {@code boolean} extra).
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_OPERATION_SAFETY_STATE_CHANGED =
            "android.app.action.OPERATION_SAFETY_STATE_CHANGED";

    /**
     * Broadcast action: notify the profile owner on an organization-owned device that it needs to
     * acknowledge device compliance.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_COMPLIANCE_ACKNOWLEDGEMENT_REQUIRED =
            "android.app.action.COMPLIANCE_ACKNOWLEDGEMENT_REQUIRED";

    /**
     * An {@code int} extra specifying an {@link OperationSafetyReason}.
     *
     * @hide
     */
    public static final String EXTRA_OPERATION_SAFETY_REASON  =
            "android.app.extra.OPERATION_SAFETY_REASON";

    /**
     * An {@code boolean} extra specifying whether an operation will fail due to a
     * {@link OperationSafetyReason}. {@code true} means operations that rely on that reason are
     * safe, while {@code false} means they're unsafe.
     *
     * @hide
     */
    public static final String EXTRA_OPERATION_SAFETY_STATE  =
            "android.app.extra.OPERATION_SAFETY_STATE";

    private DevicePolicyManager mManager;
    private ComponentName mWho;

    /**
     * Retrieve the DevicePolicyManager interface for this administrator to work
     * with the system.
     */
    public @NonNull DevicePolicyManager getManager(@NonNull Context context) {
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
    public @NonNull ComponentName getWho(@NonNull Context context) {
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
     * {@see DevicePolicyManager#createAndManageUser(ComponentName, String, ComponentName,
     *      PersistableBundle, int)}
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     */
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) {
        if (LOCAL_LOGV) {
            Log.v(TAG, getClass().getName() + ".onEnabled() on user " + context.getUserId());
        }
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
    public @Nullable CharSequence onDisableRequested(@NonNull Context context,
            @NonNull Intent intent) {
        if (LOCAL_LOGV) {
            Log.v(TAG, getClass().getName() + ".onDisableRequested() on user "
                    + context.getUserId());
        }
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
    public void onDisabled(@NonNull Context context, @NonNull Intent intent) {
        if (LOCAL_LOGV) {
            Log.v(TAG, getClass().getName() + ".onDisabled() on user " + context.getUserId());
        }
    }

    /**
     * Called after the user has changed their device or profile challenge password, as a result of
     * receiving {@link #ACTION_PASSWORD_CHANGED}.  At this point you
     * can use {@link DevicePolicyManager#getPasswordQuality(android.content.ComponentName)}
     * to retrieve the active password characteristics.
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}, use
     *             {@link #onPasswordChanged(Context, Intent, UserHandle)} instead.
     */
    @Deprecated
    public void onPasswordChanged(@NonNull Context context, @NonNull Intent intent) {
    }

    /**
     * Called after the user has changed their device or profile challenge password, as a result of
     * receiving {@link #ACTION_PASSWORD_CHANGED}.  At this point you
     * can use {@link DevicePolicyManager#getPasswordQuality(android.content.ComponentName)}
     * to retrieve the active password characteristics.
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param user The user or profile for whom the password changed. To see whether this
     *        user is the current profile or a parent user, check for equality with
     *        {@link Process#myUserHandle}.
     */
    public void onPasswordChanged(@NonNull Context context, @NonNull Intent intent,
            @NonNull UserHandle user) {
        onPasswordChanged(context, intent);
    }

    /**
     * Called after the user has failed at entering their device or profile challenge password,
     * as a result of receiving {@link #ACTION_PASSWORD_FAILED}.  At this point you can use
     * {@link DevicePolicyManager#getCurrentFailedPasswordAttempts()} to retrieve the number of
     * failed password attempts.
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}, use
     *             {@link #onPasswordFailed(Context, Intent, UserHandle)} instead.
     */
    @Deprecated
    public void onPasswordFailed(@NonNull Context context, @NonNull Intent intent) {
    }

    /**
     * Called after the user has failed at entering their device or profile challenge password,
     * as a result of receiving {@link #ACTION_PASSWORD_FAILED}.  At this point you can use
     * {@link DevicePolicyManager#getCurrentFailedPasswordAttempts()} to retrieve the number of
     * failed password attempts.
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param user The user or profile for whom the password check failed. To see whether this
     *        user is the current profile or a parent user, check for equality with
     *        {@link Process#myUserHandle}.
     */
    public void onPasswordFailed(@NonNull Context context, @NonNull Intent intent,
            @NonNull UserHandle user) {
        onPasswordFailed(context, intent);
    }

    /**
     * Called after the user has succeeded at entering their device or profile challenge password,
     * as a result of receiving {@link #ACTION_PASSWORD_SUCCEEDED}.  This will
     * only be received the first time they succeed after having previously
     * failed.
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}, use
     *             {@link #onPasswordSucceeded(Context, Intent, UserHandle)} instead.
     */
    @Deprecated
    public void onPasswordSucceeded(@NonNull Context context, @NonNull Intent intent) {
    }

    /**
     * Called after the user has succeeded at entering their device or profile challenge password,
     * as a result of receiving {@link #ACTION_PASSWORD_SUCCEEDED}.  This will
     * only be received the first time they succeed after having previously
     * failed.
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param user The user of profile for whom the password check succeeded.  To see whether this
     *        user is the current profile or a parent user, check for equality with
     *        {@link Process#myUserHandle}.
     */
    public void onPasswordSucceeded(@NonNull Context context, @NonNull Intent intent,
            @NonNull UserHandle user) {
        onPasswordSucceeded(context, intent);
    }

    /**
     * Called periodically when the device or profile challenge password is about to expire
     * or has expired.  It will typically be called at these times: on device boot, once per day
     * before the password expires, and at the time when the password expires.
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
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}, use
     *             {@link #onPasswordExpiring(Context, Intent, UserHandle)} instead.
     */
    @Deprecated
    public void onPasswordExpiring(@NonNull Context context, @NonNull Intent intent) {
    }

    /**
     * Called periodically when the device or profile challenge password is about to expire
     * or has expired.  It will typically be called at these times: on device boot, once per day
     * before the password expires, and at the time when the password expires.
     *
     * <p>If the password is not updated by the user, this method will continue to be called
     * once per day until the password is changed or the device admin disables password expiration.
     *
     * <p>The admin will typically post a notification requesting the user to change their password
     * in response to this call. The actual password expiration time can be obtained by calling
     * {@link DevicePolicyManager#getPasswordExpiration(ComponentName) }
     *
     * <p>The admin should be sure to take down any notifications it posted in response to this call
     * when it receives {@link DeviceAdminReceiver#onPasswordChanged(Context, Intent, UserHandle) }.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param user The user or profile for whom the password is expiring. To see whether this
     *        user is the current profile or a parent user, check for equality with
     *        {@link Process#myUserHandle}.
     */
    public void onPasswordExpiring(@NonNull Context context, @NonNull Intent intent,
            @NonNull UserHandle user) {
        onPasswordExpiring(context, intent);
    }

    /**
     * Called when provisioning of a managed profile or managed device has completed successfully.
     *
     * <p> As a prerequisite for the execution of this callback the {@link DeviceAdminReceiver} has
     * to declare an intent filter for {@link #ACTION_PROFILE_PROVISIONING_COMPLETE}.
     * Its component must also be specified in the {@link DevicePolicyManager#EXTRA_DEVICE_ADMIN}
     * of the {@link DevicePolicyManager#ACTION_PROVISION_MANAGED_PROFILE} intent that started the
     * managed provisioning.
     *
     * <p>When provisioning of a managed profile is complete, the managed profile is hidden until
     * the profile owner calls {@link DevicePolicyManager#setProfileEnabled(ComponentName admin)}.
     * Typically a profile owner will enable the profile when it has finished any additional setup
     * such as adding an account by using the {@link AccountManager} and calling APIs to bring the
     * profile into the desired state.
     *
     * <p> Note that provisioning completes without waiting for any server interactions, so the
     * profile owner needs to wait for data to be available if required (e.g. Android device IDs or
     * other data that is set as a result of server interactions).
     *
     * <p>From version {@link android.os.Build.VERSION_CODES#O}, when managed provisioning has
     * completed, along with this callback the activity intent
     * {@link DevicePolicyManager#ACTION_PROVISIONING_SUCCESSFUL} will also be sent to the same
     * application.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     */
    public void onProfileProvisioningComplete(@NonNull Context context, @NonNull Intent intent) {
        if (LOCAL_LOGV) {
            Log.v(TAG, getClass().getName() + ".onProfileProvisioningComplete() on user "
                    + context.getUserId());
        }
    }

    /**
     * Called during provisioning of a managed device to allow the device initializer to perform
     * user setup steps.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @deprecated Do not use
     */
    @Deprecated
    public void onReadyForUserInitialization(@NonNull Context context, @NonNull Intent intent) {
    }

    /**
     * Called when a device is entering lock task mode.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param pkg The authorized package using lock task mode.
     */
    public void onLockTaskModeEntering(@NonNull Context context, @NonNull Intent intent,
            @NonNull String pkg) {
    }

    /**
     * Called when a device is exiting lock task mode.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     */
    public void onLockTaskModeExiting(@NonNull Context context, @NonNull Intent intent) {
    }

    /**
     * Allows this receiver to select the alias for a private key and certificate pair for
     * authentication. If this method returns null, the default {@link android.app.Activity} will be
     * shown that lets the user pick a private key and certificate pair.
     * If this method returns {@link KeyChain#KEY_ALIAS_SELECTION_DENIED},
     * the default {@link android.app.Activity} will not be shown and the user will not be allowed
     * to pick anything. And the app, that called {@link KeyChain#choosePrivateKeyAlias}, will
     * receive {@code null} back.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param uid The uid of the app asking for the private key and certificate pair.
     * @param uri The URI to authenticate, may be null.
     * @param alias The alias preselected by the client, or null.
     * @return The private key alias to return and grant access to.
     * @see KeyChain#choosePrivateKeyAlias
     */
    public @Nullable String onChoosePrivateKeyAlias(@NonNull Context context,
            @NonNull Intent intent, int uid, @Nullable Uri uri, @Nullable String alias) {
        return null;
    }

    /**
     * Called when the information about a pending system update is available.
     *
     * <p>Allows the receiver to be notified when information about a pending system update is
     * available from the system update service. The same pending system update can trigger multiple
     * calls to this method, so it is necessary to examine the incoming parameters for details about
     * the update.
     *
     * <p>This callback is only applicable to device owners and profile owners.
     *
     * <p>To get further information about a pending system update (for example, whether or not the
     * update is a security patch), the device owner or profile owner can call
     * {@link DevicePolicyManager#getPendingSystemUpdate}.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param receivedTime The time as given by {@link System#currentTimeMillis()} indicating when
     *        the current pending update was first available. -1 if no pending update is available.
     * @see DevicePolicyManager#getPendingSystemUpdate
     */
    public void onSystemUpdatePending(@NonNull Context context, @NonNull Intent intent,
            long receivedTime) {
    }

    /**
     * Called when sharing a bugreport has been cancelled by the user of the device.
     *
     * <p>This callback is only applicable to device owners.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @see DevicePolicyManager#requestBugreport
     */
    public void onBugreportSharingDeclined(@NonNull Context context, @NonNull Intent intent) {
    }

    /**
     * Called when the bugreport has been shared with the device administrator app.
     *
     * <p>This callback is only applicable to device owners.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}. Contains the URI of
     * the bugreport file (with MIME type "application/vnd.android.bugreport"), that can be accessed
     * by calling {@link Intent#getData()}
     * @param bugreportHash SHA-256 hash of the bugreport file.
     * @see DevicePolicyManager#requestBugreport
     */
    public void onBugreportShared(@NonNull Context context, @NonNull Intent intent,
            @NonNull String bugreportHash) {
    }

    /**
     * Called when the bugreport collection flow has failed.
     *
     * <p>This callback is only applicable to device owners.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param failureCode int containing failure code. One of
     * {@link #BUGREPORT_FAILURE_FAILED_COMPLETING}
     * or {@link #BUGREPORT_FAILURE_FILE_NO_LONGER_AVAILABLE}
     * @see DevicePolicyManager#requestBugreport
     */
    public void onBugreportFailed(@NonNull Context context, @NonNull Intent intent,
            @BugreportFailureCode int failureCode) {
    }

    /**
     * Called when a new batch of security logs can be retrieved.
     *
     * <p>If a secondary user or profile is created, this callback won't be received until all users
     * become affiliated again (even if security logging is enabled).
     * See {@link DevicePolicyManager#setAffiliationIds}
     *
     * <p>This callback will be re-triggered if the logs are not retrieved.
     *
     * <p>This callback is only applicable to device owners.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @see DevicePolicyManager#retrieveSecurityLogs(ComponentName)
     */
    public void onSecurityLogsAvailable(@NonNull Context context, @NonNull Intent intent) {
    }

    /**
     * Called each time a new batch of network logs can be retrieved. This callback method will only
     * ever be called when network logging is enabled. The logs can only be retrieved while network
     * logging is enabled.
     *
     * <p>If a secondary user or profile is created, this callback won't be received until all users
     * become affiliated again (even if network logging is enabled). It will also no longer be
     * possible to retrieve the network logs batch with the most recent {@code batchToken} provided
     * by this callback. See {@link DevicePolicyManager#setAffiliationIds}.
     *
     * <p>This callback is only applicable to device owners.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param batchToken The token representing the current batch of network logs.
     * @param networkLogsCount The total count of events in the current batch of network logs.
     * @see DevicePolicyManager#retrieveNetworkLogs
     */
    public void onNetworkLogsAvailable(@NonNull Context context, @NonNull Intent intent,
            long batchToken, @IntRange(from = 1) int networkLogsCount) {
    }

    /**
     * Called when a user or profile is created.
     *
     * <p>This callback is only applicable to device owners.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param addedUser The {@link UserHandle} of the user that has just been added.
     */
    public void onUserAdded(@NonNull Context context, @NonNull Intent intent,
            @NonNull UserHandle addedUser) {
        if (LOCAL_LOGV) {
            Log.v(TAG, getClass().getName() + ".onUserAdded() on user " + context.getUserId());
        }
    }

    /**
     * Called when a user or profile is removed.
     *
     * <p>This callback is only applicable to device owners.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param removedUser The {@link UserHandle} of the user that has just been removed.
     */
    public void onUserRemoved(@NonNull Context context, @NonNull Intent intent,
            @NonNull UserHandle removedUser) {
        if (LOCAL_LOGV) {
            Log.v(TAG, getClass().getName() + ".onUserRemoved() on user " + context.getUserId());
        }
    }

    /**
     * Called when a user or profile is started.
     *
     * <p>This callback is only applicable to device owners.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param startedUser The {@link UserHandle} of the user that has just been started.
     */
    public void onUserStarted(@NonNull Context context, @NonNull Intent intent,
            @NonNull UserHandle startedUser) {
        if (LOCAL_LOGV) {
            Log.v(TAG, getClass().getName() + ".onUserStarted() on user " + context.getUserId());
        }
    }

    /**
     * Called when a user or profile is stopped.
     *
     * <p>This callback is only applicable to device owners.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param stoppedUser The {@link UserHandle} of the user that has just been stopped.
     */
    public void onUserStopped(@NonNull Context context, @NonNull Intent intent,
            @NonNull UserHandle stoppedUser) {
        if (LOCAL_LOGV) {
            Log.v(TAG, getClass().getName() + ".onUserStopped() on user " + context.getUserId());
        }
    }

    /**
     * Called when a user or profile is switched to.
     *
     * <p>This callback is only applicable to device owners.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param switchedUser The {@link UserHandle} of the user that has just been switched to.
     */
    public void onUserSwitched(@NonNull Context context, @NonNull Intent intent,
            @NonNull UserHandle switchedUser) {
        if (LOCAL_LOGV) {
            Log.v(TAG, getClass().getName() + ".onUserSwitched() on user " + context.getUserId());
        }
    }

    /**
     * Called on the newly assigned owner (either device owner or profile owner) when the ownership
     * transfer has completed successfully.
     *
     * <p> The {@code bundle} parameter allows the original owner to pass data
     * to the new one.
     *
     * @param context the running context as per {@link #onReceive}
     * @param bundle the data to be passed to the new owner
     */
    public void onTransferOwnershipComplete(@NonNull Context context,
            @Nullable PersistableBundle bundle) {
    }

    /**
     * Called on the device owner when the ownership of one of its affiliated profiles is
     * transferred.
     *
     * <p>This can be used when transferring both device and profile ownership when using
     * work profile on a fully managed device. The process would look like this:
     * <ol>
     * <li>Transfer profile ownership</li>
     * <li>The device owner gets notified with this callback</li>
     * <li>Transfer device ownership</li>
     * <li>Both profile and device ownerships have been transferred</li>
     * </ol>
     *
     * @param context the running context as per {@link #onReceive}
     * @param user the {@link UserHandle} of the affiliated user
     * @see DevicePolicyManager#transferOwnership(ComponentName, ComponentName, PersistableBundle)
     */
    public void onTransferAffiliatedProfileOwnershipComplete(@NonNull Context context,
            @NonNull UserHandle user) {
    }

    /**
     * Called to notify the state of operations that can be unsafe to execute has changed.
     *
     * <p><b>Note:/b> notice that the operation safety state might change between the time this
     * callback is received and the operation's method on {@link DevicePolicyManager} is called, so
     * calls to the latter could still throw a {@link UnsafeStateException} even when this method
     * is called with {@code isSafe} as {@code true}
     *
     * @param context the running context as per {@link #onReceive}
     * @param reason the reason an operation could be unsafe.
     * @param isSafe whether the operation is safe to be executed.
     */
    public void onOperationSafetyStateChanged(@NonNull Context context,
            @OperationSafetyReason int reason, boolean isSafe) {
        if (LOCAL_LOGV) {
            Log.v(TAG, String.format("onOperationSafetyStateChanged(): %s=%b",
                    DevicePolicyManager.operationSafetyReasonToString(reason), isSafe));
        }
    }

    private void onOperationSafetyStateChanged(Context context, Intent intent) {
        if (!hasRequiredExtra(intent, EXTRA_OPERATION_SAFETY_REASON)
                || !hasRequiredExtra(intent, EXTRA_OPERATION_SAFETY_STATE)) {
            Log.w(TAG, "Igoring intent that's missing required extras");
            return;
        }

        int reason = intent.getIntExtra(EXTRA_OPERATION_SAFETY_REASON,
                DevicePolicyManager.OPERATION_SAFETY_REASON_NONE);
        if (!DevicePolicyManager.isValidOperationSafetyReason(reason)) {
            Log.wtf(TAG, "Received invalid reason on " + intent.getAction() + ": " + reason);
            return;
        }
        boolean isSafe = intent.getBooleanExtra(EXTRA_OPERATION_SAFETY_STATE,
                /* defaultValue=*/ false);
        onOperationSafetyStateChanged(context, reason, isSafe);
    }

    /**
     * Called to notify a profile owner of an organization-owned device that it needs to acknowledge
     * device compliance to allow the user to turn the profile off if needed according to the
     * maximum profile time off policy.
     *
     * Default implementation acknowledges compliance immediately. DPC may prefer to override this
     * implementation to delay acknowledgement until a successful policy sync. Until compliance is
     * acknowledged the user is still free to turn the profile off, but the timer won't be reset,
     * so personal apps will be suspended sooner. This callback is delivered using a foreground
     * broadcast and should be handled quickly.
     *
     * @param context the running context as per {@link #onReceive}
     * @param intent The received intent as per {@link #onReceive}.
     *
     * @see DevicePolicyManager#acknowledgeDeviceCompliant()
     * @see DevicePolicyManager#isComplianceAcknowledgementRequired()
     * @see DevicePolicyManager#setManagedProfileMaximumTimeOff(ComponentName, long)
     */
    public void onComplianceAcknowledgementRequired(
            @NonNull Context context, @NonNull Intent intent) {
        getManager(context).acknowledgeDeviceCompliant();
    }

    private boolean hasRequiredExtra(Intent intent, String extra) {
        if (intent.hasExtra(extra)) return true;

        Log.wtf(TAG, "Missing '" + extra + "' on intent " +  intent);
        return false;
    }

    /**
     * Intercept standard device administrator broadcasts.  Implementations
     * should not override this method; it is better to implement the
     * convenience callbacks for each action.
     */
    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        String action = intent.getAction();
        if (LOCAL_LOGV) {
            Log.v(TAG, getClass().getName() + ".onReceive(): received " + action + " on user "
                    + context.getUserId());
        }

        if (ACTION_PASSWORD_CHANGED.equals(action)) {
            onPasswordChanged(context, intent, intent.getParcelableExtra(Intent.EXTRA_USER));
        } else if (ACTION_PASSWORD_FAILED.equals(action)) {
            onPasswordFailed(context, intent, intent.getParcelableExtra(Intent.EXTRA_USER));
        } else if (ACTION_PASSWORD_SUCCEEDED.equals(action)) {
            onPasswordSucceeded(context, intent, intent.getParcelableExtra(Intent.EXTRA_USER));
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
            onPasswordExpiring(context, intent, intent.getParcelableExtra(Intent.EXTRA_USER));
        } else if (ACTION_PROFILE_PROVISIONING_COMPLETE.equals(action)) {
            onProfileProvisioningComplete(context, intent);
        } else if (ACTION_CHOOSE_PRIVATE_KEY_ALIAS.equals(action)) {
            int uid = intent.getIntExtra(EXTRA_CHOOSE_PRIVATE_KEY_SENDER_UID, -1);
            Uri uri = intent.getParcelableExtra(EXTRA_CHOOSE_PRIVATE_KEY_URI);
            String alias = intent.getStringExtra(EXTRA_CHOOSE_PRIVATE_KEY_ALIAS);
            String chosenAlias = onChoosePrivateKeyAlias(context, intent, uid, uri, alias);
            setResultData(chosenAlias);
        } else if (ACTION_LOCK_TASK_ENTERING.equals(action)) {
            String pkg = intent.getStringExtra(EXTRA_LOCK_TASK_PACKAGE);
            onLockTaskModeEntering(context, intent, pkg);
        } else if (ACTION_LOCK_TASK_EXITING.equals(action)) {
            onLockTaskModeExiting(context, intent);
        } else if (ACTION_NOTIFY_PENDING_SYSTEM_UPDATE.equals(action)) {
            long receivedTime = intent.getLongExtra(EXTRA_SYSTEM_UPDATE_RECEIVED_TIME, -1);
            onSystemUpdatePending(context, intent, receivedTime);
        } else if (ACTION_BUGREPORT_SHARING_DECLINED.equals(action)) {
            onBugreportSharingDeclined(context, intent);
        } else if (ACTION_BUGREPORT_SHARE.equals(action)) {
            String bugreportFileHash = intent.getStringExtra(EXTRA_BUGREPORT_HASH);
            onBugreportShared(context, intent, bugreportFileHash);
        } else if (ACTION_BUGREPORT_FAILED.equals(action)) {
            int failureCode = intent.getIntExtra(EXTRA_BUGREPORT_FAILURE_REASON,
                    BUGREPORT_FAILURE_FAILED_COMPLETING);
            onBugreportFailed(context, intent, failureCode);
        } else if (ACTION_SECURITY_LOGS_AVAILABLE.equals(action)) {
            onSecurityLogsAvailable(context, intent);
        } else if (ACTION_NETWORK_LOGS_AVAILABLE.equals(action)) {
            long batchToken = intent.getLongExtra(EXTRA_NETWORK_LOGS_TOKEN, -1);
            int networkLogsCount = intent.getIntExtra(EXTRA_NETWORK_LOGS_COUNT, 0);
            onNetworkLogsAvailable(context, intent, batchToken, networkLogsCount);
        } else if (ACTION_USER_ADDED.equals(action)) {
            onUserAdded(context, intent, intent.getParcelableExtra(Intent.EXTRA_USER));
        } else if (ACTION_USER_REMOVED.equals(action)) {
            onUserRemoved(context, intent, intent.getParcelableExtra(Intent.EXTRA_USER));
        } else if (ACTION_USER_STARTED.equals(action)) {
            onUserStarted(context, intent, intent.getParcelableExtra(Intent.EXTRA_USER));
        } else if (ACTION_USER_STOPPED.equals(action)) {
            onUserStopped(context, intent, intent.getParcelableExtra(Intent.EXTRA_USER));
        } else if (ACTION_USER_SWITCHED.equals(action)) {
            onUserSwitched(context, intent, intent.getParcelableExtra(Intent.EXTRA_USER));
        } else if (ACTION_TRANSFER_OWNERSHIP_COMPLETE.equals(action)) {
            PersistableBundle bundle =
                    intent.getParcelableExtra(EXTRA_TRANSFER_OWNERSHIP_ADMIN_EXTRAS_BUNDLE);
            onTransferOwnershipComplete(context, bundle);
        } else if (ACTION_AFFILIATED_PROFILE_TRANSFER_OWNERSHIP_COMPLETE.equals(action)) {
            onTransferAffiliatedProfileOwnershipComplete(context,
                    intent.getParcelableExtra(Intent.EXTRA_USER));
        } else if (ACTION_OPERATION_SAFETY_STATE_CHANGED.equals(action)) {
            onOperationSafetyStateChanged(context, intent);
        } else if (ACTION_COMPLIANCE_ACKNOWLEDGEMENT_REQUIRED.equals(action)) {
            onComplianceAcknowledgementRequired(context, intent);
        }
    }
}
