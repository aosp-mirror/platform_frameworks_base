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

package android.app;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Base class for implementing a device administration component.  This
 * class provides a convenience for interpreting the raw intent actions
 * that are sent by the system.
 * 
 * <p>When publishing your DeviceAdmin subclass as a receiver, it must
 * handle {@link #ACTION_DEVICE_ADMIN_ENABLED} and require the
 * {@link android.Manifest.permission#BIND_DEVICE_ADMIN} permission.  A typical
 * manifest entry would look like:</p>
 * 
 * <pre>{@include development/samples/ApiDemos/AndroidManifest.xml
 *   device_admin_declaration}</pre>
 *   
 * <p>The meta-data referenced here provides addition information specific
 * to the device administrator, as parsed by the {@link DeviceAdminInfo} class.
 * A typical file would be:</p>
 * 
 * <pre>{@include development/samples/ApiDemos/res/xml/sample_device_admin.xml
 *   meta_data}</pre>
 */
public class DeviceAdmin extends BroadcastReceiver {
    private static String TAG = "DevicePolicy";
    private static boolean DEBUG = false;
    private static boolean localLOGV = DEBUG || android.util.Config.LOGV;

    /**
     * This is the primary action that a device administrator must implement to be
     * allowed to manage a device.  This will be set to the receiver
     * when the user enables it for administration.  You will generally
     * handle this in {@link DeviceAdmin#onEnabled(Context, Intent)}.  To be
     * supported, the receiver must also require the
     * {@link android.Manifest.permission#BIND_DEVICE_ADMIN} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_ADMIN_ENABLED
            = "android.app.action.DEVICE_ADMIN_ENABLED";

    /**
     * Action sent to a device administrator when the user has disabled
     * it.  Upon return, the application no longer has access to the
     * protected device policy manager APIs.  You will generally
     * handle this in {@link DeviceAdmin#onDisabled(Context, Intent)}.  Note
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
     * of the new password with {@link DevicePolicyManager#getPasswordMode()
     * DevicePolicyManager.getActivePasswordMode()} and
     * {@link DevicePolicyManager#getMinimumPasswordLength()
     * DevicePolicyManager.getMinimumPasswordLength()}.  You will generally
     * handle this in {@link DeviceAdmin#onPasswordChanged(Context, Intent)}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PASSWORD_CHANGED
            = "android.app.action.ACTION_PASSWORD_CHANGED";
    
    /**
     * Action sent to a device administrator when the user has failed at
     * attempted to enter the password.  You can at this point check the
     * number of failed password attempts there have been with
     * {@link DevicePolicyManager#getCurrentFailedPasswordAttempts()
     * DevicePolicyManager.getCurrentFailedPasswordAttempts()}.  You will generally
     * handle this in {@link DeviceAdmin#onPasswordFailed(Context, Intent)}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PASSWORD_FAILED
            = "android.app.action.ACTION_PASSWORD_FAILED";
    
    /**
     * Action sent to a device administrator when the user has successfully
     * entered their password, after failing one or more times.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PASSWORD_SUCCEEDED
            = "android.app.action.ACTION_PASSWORD_SUCCEEDED";
    
    /**
     * Name under which an DevicePolicy component publishes information
     * about itself.  This meta-data must reference an XML resource containing
     * a device-admin tag.  XXX TO DO: describe syntax.
     */
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
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     */
    public void onEnabled(Context context, Intent intent) {
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
        } else if (ACTION_DEVICE_ADMIN_DISABLED.equals(action)) {
            onDisabled(context, intent);
        }
    }
}
