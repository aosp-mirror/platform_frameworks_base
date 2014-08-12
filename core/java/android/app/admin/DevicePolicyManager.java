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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.app.Activity;
import android.app.admin.IDevicePolicyManager;
import android.content.AbstractRestrictionsProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.RestrictionsManager;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ProxyInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.Credentials;
import android.util.Log;

import com.android.org.conscrypt.TrustedCertificateStore;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Public interface for managing policies enforced on a device.  Most clients
 * of this class must have published a {@link DeviceAdminReceiver} that the user
 * has currently enabled.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about managing policies for device adminstration, read the
 * <a href="{@docRoot}guide/topics/admin/device-admin.html">Device Administration</a>
 * developer guide.</p>
 * </div>
 */
public class DevicePolicyManager {
    private static String TAG = "DevicePolicyManager";

    private final Context mContext;
    private final IDevicePolicyManager mService;

    private DevicePolicyManager(Context context, Handler handler) {
        mContext = context;
        mService = IDevicePolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));
    }

    /** @hide */
    public static DevicePolicyManager create(Context context, Handler handler) {
        DevicePolicyManager me = new DevicePolicyManager(context, handler);
        return me.mService != null ? me : null;
    }

    /**
     * Activity action: Starts the provisioning flow which sets up a managed profile.
     *
     * <p>A managed profile allows data separation for example for the usage of a
     * device as a personal and corporate device. The user which provisioning is started from and
     * the managed profile share a launcher.
     *
     * <p>This intent will typically be sent by a mobile device management application (mdm).
     * Provisioning adds a managed profile and sets the mdm as the profile owner who has full
     * control over the profile
     *
     * <p>This intent must contain the extras {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME}
     * {@link #EXTRA_PROVISIONING_DEFAULT_MANAGED_PROFILE_NAME} and {@link #EXTRA_DEVICE_ADMIN}.
     *
     * <p> When managed provisioning has completed, an intent of the type
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} is broadcasted to the
     * managed profile.
     *
     * <p> If provisioning fails, the managedProfile is removed so the device returns to its
     * previous state.
     *
     * <p>Input: Nothing.</p>
     * <p>Output: Nothing</p>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_MANAGED_PROFILE
        = "android.app.action.ACTION_PROVISION_MANAGED_PROFILE";

    /**
     * A String extra holding the package name of the mobile device management application that
     * will be set as the profile owner or device owner.
     *
     * <p>If an application starts provisioning directly via an intent with action
     * {@link #ACTION_PROVISION_MANAGED_PROFILE} this package has to match the package name of the
     * application that started provisioning. The package will be set as profile owner in that case.
     *
     * <p>This package is set as device owner when device owner provisioning is started by an Nfc
     * message containing an Nfc record with MIME type {@link #PROVISIONING_NFC_MIME_TYPE}.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME
        = "android.app.extra.deviceAdminPackageName";

    /**
     * A String extra holding the default name of the profile that is created during managed profile
     * provisioning.
     *
     * <p>Use with {@link #ACTION_PROVISION_MANAGED_PROFILE}
     */
    public static final String EXTRA_PROVISIONING_DEFAULT_MANAGED_PROFILE_NAME
        = "android.app.extra.defaultManagedProfileName";

    /**
     * A String extra that, holds the email address of the account which a managed profile is
     * created for. Used with {@link #ACTION_PROVISION_MANAGED_PROFILE} and
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE}.
     *
     * <p> If the {@link #ACTION_PROVISION_MANAGED_PROFILE} intent that starts managed provisioning
     * contains this extra, it is forwarded in the
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} intent to the mobile
     * device management application that was set as the profile owner during provisioning.
     * It is usually used to avoid that the user has to enter their email address twice.
     */
    public static final String EXTRA_PROVISIONING_EMAIL_ADDRESS
        = "android.app.extra.ManagedProfileEmailAddress";

    /**
     * A String extra holding the time zone {@link android.app.AlarmManager} that the device
     * will be set to.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_TIME_ZONE
        = "android.app.extra.timeZone";

    /**
     * A Long extra holding the local time {@link android.app.AlarmManager} that the device
     * will be set to.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_LOCAL_TIME
        = "android.app.extra.localTime";

    /**
     * A String extra holding the {@link java.util.Locale} that the device will be set to.
     * Format: xx_yy, where xx is the language code, and yy the country code.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_LOCALE
        = "android.app.extra.locale";

    /**
     * A String extra holding the ssid of the wifi network that should be used during nfc device
     * owner provisioning for downloading the mobile device management application.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_SSID
        = "android.app.extra.wifiSsid";

    /**
     * A boolean extra indicating whether the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}
     * is hidden or not.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_HIDDEN
        = "android.app.extra.wifiHidden";

    /**
     * A String extra indicating the security type of the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_SECURITY_TYPE
        = "android.app.extra.wifiSecurityType";

    /**
     * A String extra holding the password of the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PASSWORD
        = "android.app.extra.wifiPassword";

    /**
     * A String extra holding the proxy host for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_HOST
        = "android.app.extra.wifiProxyHost";

    /**
     * An int extra holding the proxy port for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_PORT
        = "android.app.extra.wifiProxyPort";

    /**
     * A String extra holding the proxy bypass for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_BYPASS
        = "android.app.extra.wifiProxyBypassHosts";

    /**
     * A String extra holding the proxy auto-config (PAC) URL for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PAC_URL
        = "android.app.extra.wifiPacUrl";

    /**
     * A String extra holding a url that specifies the download location of the device admin
     * package. When not provided it is assumed that the device admin package is already installed.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION
        = "android.app.extra.deviceAdminPackageDownloadLocation";

    /**
     * A String extra holding a http cookie header which should be used in the http request to the
     * url specified in {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER
        = "android.app.extra.deviceAdminPackageDownloadCookieHeader";

    /**
     * A String extra holding the SHA-1 checksum of the file at download location specified in
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}. If this doesn't match
     * the file at the download location an error will be shown to the user and the user will be
     * asked to factory reset the device.
     *
     * <p>Use in an Nfc record with {@link #PROVISIONING_NFC_MIME_TYPE} that starts device owner
     * provisioning via an Nfc bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM
        = "android.app.extra.deviceAdminPackageChecksum";

    /**
     * This MIME type is used for starting the Device Owner provisioning.
     *
     * <p>During device owner provisioning a device admin app is set as the owner of the device.
     * A device owner has full control over the device. The device owner can not be modified by the
     * user and the only way of resetting the device is if the device owner app calls a factory
     * reset.
     *
     * <p> A typical use case would be a device that is owned by a company, but used by either an
     * employee or client.
     *
     * <p> The Nfc message should be send to an unprovisioned device.
     *
     * <p>The Nfc record must contain a serialized {@link java.util.Properties} object which
     * contains the following properties:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME}</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM}</li>
     * <li>{@link #EXTRA_PROVISIONING_LOCAL_TIME} (convert to String), optional</li>
     * <li>{@link #EXTRA_PROVISIONING_TIME_ZONE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LOCALE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_SSID}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_HIDDEN} (convert to String), optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PASSWORD}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PROXY_HOST}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PROXY_PORT} (convert to String), optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PROXY_BYPASS}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PAC_URL}, optional</li></ul>
     *
     * <p> When device owner provisioning has completed, an intent of the type
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} is broadcasted to the
     * device owner.
     *
     * <p>
     * If provisioning fails, the device is factory reset.
     *
     * <p>Input: Nothing.</p>
     * <p>Output: Nothing</p>
     */
    public static final String PROVISIONING_NFC_MIME_TYPE
        = "application/com.android.managedprovisioning";

    /**
     * Activity action: ask the user to add a new device administrator to the system.
     * The desired policy is the ComponentName of the policy in the
     * {@link #EXTRA_DEVICE_ADMIN} extra field.  This will invoke a UI to
     * bring the user through adding the device administrator to the system (or
     * allowing them to reject it).
     *
     * <p>You can optionally include the {@link #EXTRA_ADD_EXPLANATION}
     * field to provide the user with additional explanation (in addition
     * to your component's description) about what is being added.
     *
     * <p>If your administrator is already active, this will ordinarily return immediately (without
     * user intervention).  However, if your administrator has been updated and is requesting
     * additional uses-policy flags, the user will be presented with the new list.  New policies
     * will not be available to the updated administrator until the user has accepted the new list.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ADD_DEVICE_ADMIN
            = "android.app.action.ADD_DEVICE_ADMIN";

    /**
     * @hide
     * Activity action: ask the user to add a new device administrator as the profile owner
     * for this user. Only system privileged apps that have MANAGE_USERS and MANAGE_DEVICE_ADMINS
     * permission can call this API.
     *
     * <p>The ComponentName of the profile owner admin is pass in {@link #EXTRA_DEVICE_ADMIN} extra
     * field. This will invoke a UI to bring the user through adding the profile owner admin
     * to remotely control restrictions on the user.
     *
     * <p>The intent must be invoked via {@link Activity#startActivityForResult()} to receive the
     * result of whether or not the user approved the action. If approved, the result will
     * be {@link Activity#RESULT_OK} and the component will be set as an active admin as well
     * as a profile owner.
     *
     * <p>You can optionally include the {@link #EXTRA_ADD_EXPLANATION}
     * field to provide the user with additional explanation (in addition
     * to your component's description) about what is being added.
     *
     * <p>If there is already a profile owner active or the caller doesn't have the required
     * permissions, the operation will return a failure result.
     */
    @SystemApi
    public static final String ACTION_SET_PROFILE_OWNER
            = "android.app.action.SET_PROFILE_OWNER";

    /**
     * @hide
     * Name of the profile owner admin that controls the user.
     */
    @SystemApi
    public static final String EXTRA_PROFILE_OWNER_NAME
            = "android.app.extra.PROFILE_OWNER_NAME";

    /**
     * Activity action: send when any policy admin changes a policy.
     * This is generally used to find out when a new policy is in effect.
     *
     * @hide
     */
    public static final String ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
            = "android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED";

    /**
     * The ComponentName of the administrator component.
     *
     * @see #ACTION_ADD_DEVICE_ADMIN
     */
    public static final String EXTRA_DEVICE_ADMIN = "android.app.extra.DEVICE_ADMIN";

    /**
     * An optional CharSequence providing additional explanation for why the
     * admin is being added.
     *
     * @see #ACTION_ADD_DEVICE_ADMIN
     */
    public static final String EXTRA_ADD_EXPLANATION = "android.app.extra.ADD_EXPLANATION";

    /**
     * Activity action: have the user enter a new password. This activity should
     * be launched after using {@link #setPasswordQuality(ComponentName, int)},
     * or {@link #setPasswordMinimumLength(ComponentName, int)} to have the user
     * enter a new password that meets the current requirements. You can use
     * {@link #isActivePasswordSufficient()} to determine whether you need to
     * have the user select a new password in order to meet the current
     * constraints. Upon being resumed from this activity, you can check the new
     * password characteristics to see if they are sufficient.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SET_NEW_PASSWORD
            = "android.app.action.SET_NEW_PASSWORD";

    /**
     * Flag used by {@link #addCrossProfileIntentFilter} to allow access of certain intents from a
     * managed profile to its parent.
     */
    public static int FLAG_PARENT_CAN_ACCESS_MANAGED = 0x0001;

    /**
     * Flag used by {@link #addCrossProfileIntentFilter} to allow access of certain intents from the
     * parent to its managed profile.
     */
    public static int FLAG_MANAGED_CAN_ACCESS_PARENT = 0x0002;

    /**
     * Return true if the given administrator component is currently
     * active (enabled) in the system.
     */
    public boolean isAdminActive(ComponentName who) {
        return isAdminActiveAsUser(who, UserHandle.myUserId());
    }

    /**
     * @see #isAdminActive(ComponentName)
     * @hide
     */
    public boolean isAdminActiveAsUser(ComponentName who, int userId) {
        if (mService != null) {
            try {
                return mService.isAdminActive(who, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Return a list of all currently active device administrator's component
     * names.  Note that if there are no administrators than null may be
     * returned.
     */
    public List<ComponentName> getActiveAdmins() {
        return getActiveAdminsAsUser(UserHandle.myUserId());
    }

    /**
     * @see #getActiveAdmins()
     * @hide
     */
    public List<ComponentName> getActiveAdminsAsUser(int userId) {
        if (mService != null) {
            try {
                return mService.getActiveAdmins(userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Used by package administration code to determine if a package can be stopped
     * or uninstalled.
     * @hide
     */
    public boolean packageHasActiveAdmins(String packageName) {
        if (mService != null) {
            try {
                return mService.packageHasActiveAdmins(packageName, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Remove a current administration component.  This can only be called
     * by the application that owns the administration component; if you
     * try to remove someone else's component, a security exception will be
     * thrown.
     */
    public void removeActiveAdmin(ComponentName who) {
        if (mService != null) {
            try {
                mService.removeActiveAdmin(who, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Returns true if an administrator has been granted a particular device policy.  This can
     * be used to check if the administrator was activated under an earlier set of policies,
     * but requires additional policies after an upgrade.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.  Must be
     * an active administrator, or an exception will be thrown.
     * @param usesPolicy Which uses-policy to check, as defined in {@link DeviceAdminInfo}.
     */
    public boolean hasGrantedPolicy(ComponentName admin, int usesPolicy) {
        if (mService != null) {
            try {
                return mService.hasGrantedPolicy(admin, usesPolicy, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Constant for {@link #setPasswordQuality}: the policy has no requirements
     * for the password.  Note that quality constants are ordered so that higher
     * values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_UNSPECIFIED = 0;

    /**
     * Constant for {@link #setPasswordQuality}: the policy allows for low-security biometric
     * recognition technology.  This implies technologies that can recognize the identity of
     * an individual to about a 3 digit PIN (false detection is less than 1 in 1,000).
     * Note that quality constants are ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_BIOMETRIC_WEAK = 0x8000;

    /**
     * Constant for {@link #setPasswordQuality}: the policy requires some kind
     * of password, but doesn't care what it is.  Note that quality constants
     * are ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_SOMETHING = 0x10000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least numeric characters.  Note that quality
     * constants are ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_NUMERIC = 0x20000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least numeric characters with no repeating (4444)
     * or ordered (1234, 4321, 2468) sequences.  Note that quality
     * constants are ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_NUMERIC_COMPLEX = 0x30000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least alphabetic (or other symbol) characters.
     * Note that quality constants are ordered so that higher values are more
     * restrictive.
     */
    public static final int PASSWORD_QUALITY_ALPHABETIC = 0x40000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least <em>both></em> numeric <em>and</em>
     * alphabetic (or other symbol) characters.  Note that quality constants are
     * ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_ALPHANUMERIC = 0x50000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least a letter, a numerical digit and a special
     * symbol, by default. With this password quality, passwords can be
     * restricted to contain various sets of characters, like at least an
     * uppercase letter, etc. These are specified using various methods,
     * like {@link #setPasswordMinimumLowerCase(ComponentName, int)}. Note
     * that quality constants are ordered so that higher values are more
     * restrictive.
     */
    public static final int PASSWORD_QUALITY_COMPLEX = 0x60000;

    /**
     * Called by an application that is administering the device to set the
     * password restrictions it is imposing.  After setting this, the user
     * will not be able to enter a new password that is not at least as
     * restrictive as what has been set.  Note that the current password
     * will remain until the user has set a new one, so the change does not
     * take place immediately.  To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value.
     *
     * <p>Quality constants are ordered so that higher values are more restrictive;
     * thus the highest requested quality constant (between the policy set here,
     * the user's preference, and any other considerations) is the one that
     * is in effect.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param quality The new desired quality.  One of
     * {@link #PASSWORD_QUALITY_UNSPECIFIED}, {@link #PASSWORD_QUALITY_SOMETHING},
     * {@link #PASSWORD_QUALITY_NUMERIC}, {@link #PASSWORD_QUALITY_NUMERIC_COMPLEX},
     * {@link #PASSWORD_QUALITY_ALPHABETIC}, {@link #PASSWORD_QUALITY_ALPHANUMERIC}
     * or {@link #PASSWORD_QUALITY_COMPLEX}.
     */
    public void setPasswordQuality(ComponentName admin, int quality) {
        if (mService != null) {
            try {
                mService.setPasswordQuality(admin, quality, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current minimum password quality for all admins of this user
     * and its profiles or a particular one.
     * @param admin The name of the admin component to check, or null to aggregate
     * all admins.
     */
    public int getPasswordQuality(ComponentName admin) {
        return getPasswordQuality(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordQuality(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordQuality(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return PASSWORD_QUALITY_UNSPECIFIED;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum allowed password length.  After setting this, the user
     * will not be able to enter a new password that is not at least as
     * restrictive as what has been set.  Note that the current password
     * will remain until the user has set a new one, so the change does not
     * take place immediately.  To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value.  This
     * constraint is only imposed if the administrator has also requested either
     * {@link #PASSWORD_QUALITY_NUMERIC}, {@link #PASSWORD_QUALITY_NUMERIC_COMPLEX},
     * {@link #PASSWORD_QUALITY_ALPHABETIC}, {@link #PASSWORD_QUALITY_ALPHANUMERIC},
     * or {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum password length.  A value of 0
     * means there is no restriction.
     */
    public void setPasswordMinimumLength(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLength(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current minimum password length for all admins of this
     * user and its profiles or a particular one.
     * @param admin The name of the admin component to check, or null to aggregate
     * all admins.
     */
    public int getPasswordMinimumLength(ComponentName admin) {
        return getPasswordMinimumLength(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumLength(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLength(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of upper case letters required in the password. After
     * setting this, the user will not be able to enter a new password that is
     * not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does
     * not take place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of upper case letters
     *            required in the password. A value of 0 means there is no
     *            restriction.
     */
    public void setPasswordMinimumUpperCase(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumUpperCase(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of upper case letters required in the
     * password for all admins of this user and its profiles or a particular one.
     * This is the same value as set by
     * {#link {@link #setPasswordMinimumUpperCase(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or null to
     *            aggregate all admins.
     * @return The minimum number of upper case letters required in the
     *         password.
     */
    public int getPasswordMinimumUpperCase(ComponentName admin) {
        return getPasswordMinimumUpperCase(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumUpperCase(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumUpperCase(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of lower case letters required in the password. After
     * setting this, the user will not be able to enter a new password that is
     * not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does
     * not take place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of lower case letters
     *            required in the password. A value of 0 means there is no
     *            restriction.
     */
    public void setPasswordMinimumLowerCase(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLowerCase(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of lower case letters required in the
     * password for all admins of this user and its profiles or a particular one.
     * This is the same value as set by
     * {#link {@link #setPasswordMinimumLowerCase(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or null to
     *            aggregate all admins.
     * @return The minimum number of lower case letters required in the
     *         password.
     */
    public int getPasswordMinimumLowerCase(ComponentName admin) {
        return getPasswordMinimumLowerCase(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumLowerCase(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLowerCase(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of letters required in the password. After setting this,
     * the user will not be able to enter a new password that is not at least as
     * restrictive as what has been set. Note that the current password will
     * remain until the user has set a new one, so the change does not take
     * place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 1.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of letters required in the
     *            password. A value of 0 means there is no restriction.
     */
    public void setPasswordMinimumLetters(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLetters(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of letters required in the password for all
     * admins or a particular one. This is the same value as
     * set by {#link {@link #setPasswordMinimumLetters(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or null to
     *            aggregate all admins.
     * @return The minimum number of letters required in the password.
     */
    public int getPasswordMinimumLetters(ComponentName admin) {
        return getPasswordMinimumLetters(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumLetters(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLetters(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of numerical digits required in the password. After
     * setting this, the user will not be able to enter a new password that is
     * not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does
     * not take place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 1.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of numerical digits required
     *            in the password. A value of 0 means there is no restriction.
     */
    public void setPasswordMinimumNumeric(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumNumeric(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of numerical digits required in the password
     * for all admins of this user and its profiles or a particular one.
     * This is the same value as set by
     * {#link {@link #setPasswordMinimumNumeric(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or null to
     *            aggregate all admins.
     * @return The minimum number of numerical digits required in the password.
     */
    public int getPasswordMinimumNumeric(ComponentName admin) {
        return getPasswordMinimumNumeric(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumNumeric(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumNumeric(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of symbols required in the password. After setting this,
     * the user will not be able to enter a new password that is not at least as
     * restrictive as what has been set. Note that the current password will
     * remain until the user has set a new one, so the change does not take
     * place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 1.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of symbols required in the
     *            password. A value of 0 means there is no restriction.
     */
    public void setPasswordMinimumSymbols(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumSymbols(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of symbols required in the password for all
     * admins or a particular one. This is the same value as
     * set by {#link {@link #setPasswordMinimumSymbols(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or null to
     *            aggregate all admins.
     * @return The minimum number of symbols required in the password.
     */
    public int getPasswordMinimumSymbols(ComponentName admin) {
        return getPasswordMinimumSymbols(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumSymbols(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumSymbols(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of non-letter characters (numerical digits or symbols)
     * required in the password. After setting this, the user will not be able
     * to enter a new password that is not at least as restrictive as what has
     * been set. Note that the current password will remain until the user has
     * set a new one, so the change does not take place immediately. To prompt
     * the user for a new password, use {@link #ACTION_SET_NEW_PASSWORD} after
     * setting this value. This constraint is only imposed if the administrator
     * has also requested {@link #PASSWORD_QUALITY_COMPLEX} with
     * {@link #setPasswordQuality}. The default value is 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of letters required in the
     *            password. A value of 0 means there is no restriction.
     */
    public void setPasswordMinimumNonLetter(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumNonLetter(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of non-letter characters required in the
     * password for all admins of this user and its profiles or a particular one.
     * This is the same value as set by
     * {#link {@link #setPasswordMinimumNonLetter(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or null to
     *            aggregate all admins.
     * @return The minimum number of letters required in the password.
     */
    public int getPasswordMinimumNonLetter(ComponentName admin) {
        return getPasswordMinimumNonLetter(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumNonLetter(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumNonLetter(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

  /**
   * Called by an application that is administering the device to set the length
   * of the password history. After setting this, the user will not be able to
   * enter a new password that is the same as any password in the history. Note
   * that the current password will remain until the user has set a new one, so
   * the change does not take place immediately. To prompt the user for a new
   * password, use {@link #ACTION_SET_NEW_PASSWORD} after setting this value.
   * This constraint is only imposed if the administrator has also requested
   * either {@link #PASSWORD_QUALITY_NUMERIC}, {@link #PASSWORD_QUALITY_NUMERIC_COMPLEX}
   * {@link #PASSWORD_QUALITY_ALPHABETIC}, or {@link #PASSWORD_QUALITY_ALPHANUMERIC}
   * with {@link #setPasswordQuality}.
   *
   * <p>
   * The calling device admin must have requested
   * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this
   * method; if it has not, a security exception will be thrown.
   *
   * @param admin Which {@link DeviceAdminReceiver} this request is associated
   *        with.
   * @param length The new desired length of password history. A value of 0
   *        means there is no restriction.
   */
    public void setPasswordHistoryLength(ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordHistoryLength(admin, length, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a device admin to set the password expiration timeout. Calling this method
     * will restart the countdown for password expiration for the given admin, as will changing
     * the device password (for all admins).
     *
     * <p>The provided timeout is the time delta in ms and will be added to the current time.
     * For example, to have the password expire 5 days from now, timeout would be
     * 5 * 86400 * 1000 = 432000000 ms for timeout.
     *
     * <p>To disable password expiration, a value of 0 may be used for timeout.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_EXPIRE_PASSWORD} to be able to call this
     * method; if it has not, a security exception will be thrown.
     *
     * <p> Note that setting the password will automatically reset the expiration time for all
     * active admins. Active admins do not need to explicitly call this method in that case.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param timeout The limit (in ms) that a password can remain in effect. A value of 0
     *        means there is no restriction (unlimited).
     */
    public void setPasswordExpirationTimeout(ComponentName admin, long timeout) {
        if (mService != null) {
            try {
                mService.setPasswordExpirationTimeout(admin, timeout, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Get the password expiration timeout for the given admin. The expiration timeout is the
     * recurring expiration timeout provided in the call to
     * {@link #setPasswordExpirationTimeout(ComponentName, long)} for the given admin or the
     * aggregate of all policy administrators if admin is null.
     *
     * @param admin The name of the admin component to check, or null to aggregate all admins.
     * @return The timeout for the given admin or the minimum of all timeouts
     */
    public long getPasswordExpirationTimeout(ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPasswordExpirationTimeout(admin, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Get the current password expiration time for the given admin or an aggregate of
     * all admins of this user and its profiles if admin is null. If the password is
     * expired, this will return the time since the password expired as a negative number.
     * If admin is null, then a composite of all expiration timeouts is returned
     * - which will be the minimum of all timeouts.
     *
     * @param admin The name of the admin component to check, or null to aggregate all admins.
     * @return The password expiration time, in ms.
     */
    public long getPasswordExpiration(ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPasswordExpiration(admin, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Retrieve the current password history length for all admins of this
     * user and its profiles or a particular one.
     * @param admin The name of the admin component to check, or null to aggregate
     * all admins.
     * @return The length of the password history
     */
    public int getPasswordHistoryLength(ComponentName admin) {
        return getPasswordHistoryLength(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordHistoryLength(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordHistoryLength(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Return the maximum password length that the device supports for a
     * particular password quality.
     * @param quality The quality being interrogated.
     * @return Returns the maximum length that the user can enter.
     */
    public int getPasswordMaximumLength(int quality) {
        // Kind-of arbitrary.
        return 16;
    }

    /**
     * Determine whether the current password the user has set is sufficient
     * to meet the policy requirements (quality, minimum length) that have been
     * requested by the admins of this user and its profiles.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @return Returns true if the password meets the current requirements, else false.
     */
    public boolean isActivePasswordSufficient() {
        if (mService != null) {
            try {
                return mService.isActivePasswordSufficient(UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Retrieve the number of times the user has failed at entering a
     * password since that last successful password entry.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} to be able to call
     * this method; if it has not, a security exception will be thrown.
     */
    public int getCurrentFailedPasswordAttempts() {
        if (mService != null) {
            try {
                return mService.getCurrentFailedPasswordAttempts(UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return -1;
    }

    /**
     * Setting this to a value greater than zero enables a built-in policy
     * that will perform a device wipe after too many incorrect
     * device-unlock passwords have been entered.  This built-in policy combines
     * watching for failed passwords and wiping the device, and requires
     * that you request both {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} and
     * {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA}}.
     *
     * <p>To implement any other policy (e.g. wiping data for a particular
     * application only, erasing or revoking credentials, or reporting the
     * failure to a server), you should implement
     * {@link DeviceAdminReceiver#onPasswordFailed(Context, android.content.Intent)}
     * instead.  Do not use this API, because if the maximum count is reached,
     * the device will be wiped immediately, and your callback will not be invoked.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param num The number of failed password attempts at which point the
     * device will wipe its data.
     */
    public void setMaximumFailedPasswordsForWipe(ComponentName admin, int num) {
        if (mService != null) {
            try {
                mService.setMaximumFailedPasswordsForWipe(admin, num, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current maximum number of login attempts that are allowed
     * before the device wipes itself, for all admins of this user and its profiles
     * or a particular one.
     * @param admin The name of the admin component to check, or null to aggregate
     * all admins.
     */
    public int getMaximumFailedPasswordsForWipe(ComponentName admin) {
        return getMaximumFailedPasswordsForWipe(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getMaximumFailedPasswordsForWipe(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getMaximumFailedPasswordsForWipe(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Flag for {@link #resetPassword}: don't allow other admins to change
     * the password again until the user has entered it.
     */
    public static final int RESET_PASSWORD_REQUIRE_ENTRY = 0x0001;

    /**
     * Force a new device unlock password (the password needed to access the
     * entire device, not for individual accounts) on the user.  This takes
     * effect immediately.
     * The given password must be sufficient for the
     * current password quality and length constraints as returned by
     * {@link #getPasswordQuality(ComponentName)} and
     * {@link #getPasswordMinimumLength(ComponentName)}; if it does not meet
     * these constraints, then it will be rejected and false returned.  Note
     * that the password may be a stronger quality (containing alphanumeric
     * characters when the requested quality is only numeric), in which case
     * the currently active quality will be increased to match.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_RESET_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * Can not be called from a managed profile.
     *
     * @param password The new password for the user.
     * @param flags May be 0 or {@link #RESET_PASSWORD_REQUIRE_ENTRY}.
     * @return Returns true if the password was applied, or false if it is
     * not acceptable for the current constraints.
     */
    public boolean resetPassword(String password, int flags) {
        if (mService != null) {
            try {
                return mService.resetPassword(password, flags, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to set the
     * maximum time for user activity until the device will lock.  This limits
     * the length that the user can set.  It takes effect immediately.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param timeMs The new desired maximum time to lock in milliseconds.
     * A value of 0 means there is no restriction.
     */
    public void setMaximumTimeToLock(ComponentName admin, long timeMs) {
        if (mService != null) {
            try {
                mService.setMaximumTimeToLock(admin, timeMs, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current maximum time to unlock for all admins of this user
     * and its profiles or a particular one.
     * @param admin The name of the admin component to check, or null to aggregate
     * all admins.
     */
    public long getMaximumTimeToLock(ComponentName admin) {
        return getMaximumTimeToLock(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public long getMaximumTimeToLock(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getMaximumTimeToLock(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Make the device lock immediately, as if the lock screen timeout has
     * expired at the point of this call.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK} to be able to call
     * this method; if it has not, a security exception will be thrown.
     */
    public void lockNow() {
        if (mService != null) {
            try {
                mService.lockNow();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Flag for {@link #wipeData(int)}: also erase the device's external
     * storage.
     */
    public static final int WIPE_EXTERNAL_STORAGE = 0x0001;

    /**
     * Ask the user data be wiped.  This will cause the device to reboot,
     * erasing all user data while next booting up.  External storage such
     * as SD cards will be also erased if the flag {@link #WIPE_EXTERNAL_STORAGE}
     * is set.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param flags Bit mask of additional options: currently 0 and
     *              {@link #WIPE_EXTERNAL_STORAGE} are supported.
     */
    public void wipeData(int flags) {
        if (mService != null) {
            try {
                mService.wipeData(flags, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by an application that is administering the device to set the
     * global proxy and exclusion list.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_SETS_GLOBAL_PROXY} to be able to call
     * this method; if it has not, a security exception will be thrown.
     * Only the first device admin can set the proxy. If a second admin attempts
     * to set the proxy, the {@link ComponentName} of the admin originally setting the
     * proxy will be returned. If successful in setting the proxy, null will
     * be returned.
     * The method can be called repeatedly by the device admin alrady setting the
     * proxy to update the proxy and exclusion list.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param proxySpec the global proxy desired. Must be an HTTP Proxy.
     *            Pass Proxy.NO_PROXY to reset the proxy.
     * @param exclusionList a list of domains to be excluded from the global proxy.
     * @return returns null if the proxy was successfully set, or a {@link ComponentName}
     *            of the device admin that sets thew proxy otherwise.
     * @hide
     */
    public ComponentName setGlobalProxy(ComponentName admin, Proxy proxySpec,
            List<String> exclusionList ) {
        if (proxySpec == null) {
            throw new NullPointerException();
        }
        if (mService != null) {
            try {
                String hostSpec;
                String exclSpec;
                if (proxySpec.equals(Proxy.NO_PROXY)) {
                    hostSpec = null;
                    exclSpec = null;
                } else {
                    if (!proxySpec.type().equals(Proxy.Type.HTTP)) {
                        throw new IllegalArgumentException();
                    }
                    InetSocketAddress sa = (InetSocketAddress)proxySpec.address();
                    String hostName = sa.getHostName();
                    int port = sa.getPort();
                    StringBuilder hostBuilder = new StringBuilder();
                    hostSpec = hostBuilder.append(hostName)
                        .append(":").append(Integer.toString(port)).toString();
                    if (exclusionList == null) {
                        exclSpec = "";
                    } else {
                        StringBuilder listBuilder = new StringBuilder();
                        boolean firstDomain = true;
                        for (String exclDomain : exclusionList) {
                            if (!firstDomain) {
                                listBuilder = listBuilder.append(",");
                            } else {
                                firstDomain = false;
                            }
                            listBuilder = listBuilder.append(exclDomain.trim());
                        }
                        exclSpec = listBuilder.toString();
                    }
                    if (android.net.Proxy.validate(hostName, Integer.toString(port), exclSpec)
                            != android.net.Proxy.PROXY_VALID)
                        throw new IllegalArgumentException();
                }
                return mService.setGlobalProxy(admin, hostSpec, exclSpec, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Set a network-independent global HTTP proxy.  This is not normally what you want
     * for typical HTTP proxies - they are generally network dependent.  However if you're
     * doing something unusual like general internal filtering this may be useful.  On
     * a private network where the proxy is not accessible, you may break HTTP using this.
     *
     * <p>This method requires the caller to be the device owner.
     *
     * <p>This proxy is only a recommendation and it is possible that some apps will ignore it.
     * @see ProxyInfo
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param proxyInfo The a {@link ProxyInfo} object defining the new global
     *        HTTP proxy.  A {@code null} value will clear the global HTTP proxy.
     */
    public void setRecommendedGlobalProxy(ComponentName admin, ProxyInfo proxyInfo) {
        if (mService != null) {
            try {
                mService.setRecommendedGlobalProxy(admin, proxyInfo);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Returns the component name setting the global proxy.
     * @return ComponentName object of the device admin that set the global proxy, or
     *            null if no admin has set the proxy.
     * @hide
     */
    public ComponentName getGlobalProxyAdmin() {
        if (mService != null) {
            try {
                return mService.getGlobalProxyAdmin(UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Result code for {@link #setStorageEncryption} and {@link #getStorageEncryptionStatus}:
     * indicating that encryption is not supported.
     */
    public static final int ENCRYPTION_STATUS_UNSUPPORTED = 0;

    /**
     * Result code for {@link #setStorageEncryption} and {@link #getStorageEncryptionStatus}:
     * indicating that encryption is supported, but is not currently active.
     */
    public static final int ENCRYPTION_STATUS_INACTIVE = 1;

    /**
     * Result code for {@link #setStorageEncryption} and {@link #getStorageEncryptionStatus}:
     * indicating that encryption is not currently active, but is currently
     * being activated.  This is only reported by devices that support
     * encryption of data and only when the storage is currently
     * undergoing a process of becoming encrypted.  A device that must reboot and/or wipe data
     * to become encrypted will never return this value.
     */
    public static final int ENCRYPTION_STATUS_ACTIVATING = 2;

    /**
     * Result code for {@link #setStorageEncryption} and {@link #getStorageEncryptionStatus}:
     * indicating that encryption is active.
     */
    public static final int ENCRYPTION_STATUS_ACTIVE = 3;

    /**
     * Activity action: begin the process of encrypting data on the device.  This activity should
     * be launched after using {@link #setStorageEncryption} to request encryption be activated.
     * After resuming from this activity, use {@link #getStorageEncryption}
     * to check encryption status.  However, on some devices this activity may never return, as
     * it may trigger a reboot and in some cases a complete data wipe of the device.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_START_ENCRYPTION
            = "android.app.action.START_ENCRYPTION";

    /**
     * Widgets are enabled in keyguard
     */
    public static final int KEYGUARD_DISABLE_FEATURES_NONE = 0;

    /**
     * Disable all keyguard widgets. Has no effect.
     */
    public static final int KEYGUARD_DISABLE_WIDGETS_ALL = 1 << 0;

    /**
     * Disable the camera on secure keyguard screens (e.g. PIN/Pattern/Password)
     */
    public static final int KEYGUARD_DISABLE_SECURE_CAMERA = 1 << 1;

    /**
     * Disable showing all notifications on secure keyguard screens (e.g. PIN/Pattern/Password)
     */
    public static final int KEYGUARD_DISABLE_SECURE_NOTIFICATIONS = 1 << 2;

    /**
     * Only allow redacted notifications on secure keyguard screens (e.g. PIN/Pattern/Password)
     */
    public static final int KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS = 1 << 3;

    /**
     * Ignore trust agent state on secure keyguard screens
     * (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_TRUST_AGENTS = 1 << 4;

    /**
     * Disable fingerprint sensor on keyguard secure screens (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_FINGERPRINT = 1 << 5;

    /**
     * Disable all current and future keyguard customizations.
     */
    public static final int KEYGUARD_DISABLE_FEATURES_ALL = 0x7fffffff;

    /**
     * Called by an application that is administering the device to
     * request that the storage system be encrypted.
     *
     * <p>When multiple device administrators attempt to control device
     * encryption, the most secure, supported setting will always be
     * used.  If any device administrator requests device encryption,
     * it will be enabled;  Conversely, if a device administrator
     * attempts to disable device encryption while another
     * device administrator has enabled it, the call to disable will
     * fail (most commonly returning {@link #ENCRYPTION_STATUS_ACTIVE}).
     *
     * <p>This policy controls encryption of the secure (application data) storage area.  Data
     * written to other storage areas may or may not be encrypted, and this policy does not require
     * or control the encryption of any other storage areas.
     * There is one exception:  If {@link android.os.Environment#isExternalStorageEmulated()} is
     * {@code true}, then the directory returned by
     * {@link android.os.Environment#getExternalStorageDirectory()} must be written to disk
     * within the encrypted storage area.
     *
     * <p>Important Note:  On some devices, it is possible to encrypt storage without requiring
     * the user to create a device PIN or Password.  In this case, the storage is encrypted, but
     * the encryption key may not be fully secured.  For maximum security, the administrator should
     * also require (and check for) a pattern, PIN, or password.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param encrypt true to request encryption, false to release any previous request
     * @return the new request status (for all active admins) - will be one of
     * {@link #ENCRYPTION_STATUS_UNSUPPORTED}, {@link #ENCRYPTION_STATUS_INACTIVE}, or
     * {@link #ENCRYPTION_STATUS_ACTIVE}.  This is the value of the requests;  Use
     * {@link #getStorageEncryptionStatus()} to query the actual device state.
     */
    public int setStorageEncryption(ComponentName admin, boolean encrypt) {
        if (mService != null) {
            try {
                return mService.setStorageEncryption(admin, encrypt, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return ENCRYPTION_STATUS_UNSUPPORTED;
    }

    /**
     * Called by an application that is administering the device to
     * determine the requested setting for secure storage.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.  If null,
     * this will return the requested encryption setting as an aggregate of all active
     * administrators.
     * @return true if the admin(s) are requesting encryption, false if not.
     */
    public boolean getStorageEncryption(ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getStorageEncryption(admin, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to
     * determine the current encryption status of the device.
     *
     * Depending on the returned status code, the caller may proceed in different
     * ways.  If the result is {@link #ENCRYPTION_STATUS_UNSUPPORTED}, the
     * storage system does not support encryption.  If the
     * result is {@link #ENCRYPTION_STATUS_INACTIVE}, use {@link
     * #ACTION_START_ENCRYPTION} to begin the process of encrypting or decrypting the
     * storage.  If the result is {@link #ENCRYPTION_STATUS_ACTIVATING} or
     * {@link #ENCRYPTION_STATUS_ACTIVE}, no further action is required.
     *
     * @return current status of encryption. The value will be one of
     * {@link #ENCRYPTION_STATUS_UNSUPPORTED}, {@link #ENCRYPTION_STATUS_INACTIVE},
     * {@link #ENCRYPTION_STATUS_ACTIVATING}, or{@link #ENCRYPTION_STATUS_ACTIVE}.
     */
    public int getStorageEncryptionStatus() {
        return getStorageEncryptionStatus(UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getStorageEncryptionStatus(int userHandle) {
        if (mService != null) {
            try {
                return mService.getStorageEncryptionStatus(userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return ENCRYPTION_STATUS_UNSUPPORTED;
    }

    /**
     * Installs the given certificate as a user CA.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param certBuffer encoded form of the certificate to install.
     *
     * @return false if the certBuffer cannot be parsed or installation is
     *         interrupted, true otherwise.
     */
    public boolean installCaCert(ComponentName admin, byte[] certBuffer) {
        if (mService != null) {
            try {
                return mService.installCaCert(admin, certBuffer);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Uninstalls the given certificate from trusted user CAs, if present.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param certBuffer encoded form of the certificate to remove.
     */
    public void uninstallCaCert(ComponentName admin, byte[] certBuffer) {
        if (mService != null) {
            try {
                final String alias = getCaCertAlias(certBuffer);
                mService.uninstallCaCert(admin, alias);
            } catch (CertificateException e) {
                Log.w(TAG, "Unable to parse certificate", e);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Returns all CA certificates that are currently trusted, excluding system CA certificates.
     * If a user has installed any certificates by other means than device policy these will be
     * included too.
     *
     * @return a List of byte[] arrays, each encoding one user CA certificate.
     */
    public List<byte[]> getInstalledCaCerts() {
        final TrustedCertificateStore certStore = new TrustedCertificateStore();
        List<byte[]> certs = new ArrayList<byte[]>();
        for (String alias : certStore.userAliases()) {
            try {
                certs.add(certStore.getCertificate(alias).getEncoded());
            } catch (CertificateException ce) {
                Log.w(TAG, "Could not encode certificate: " + alias, ce);
            }
        }
        return certs;
    }

    /**
     * Uninstalls all custom trusted CA certificates from the profile. Certificates installed by
     * means other than device policy will also be removed, except for system CA certificates.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     */
    public void uninstallAllUserCaCerts(ComponentName admin) {
        if (mService != null) {
            for (String alias : new TrustedCertificateStore().userAliases()) {
                try {
                    mService.uninstallCaCert(admin, alias);
                } catch (RemoteException re) {
                    Log.w(TAG, "Failed talking with device policy service", re);
                }
            }
        }
    }

    /**
     * Returns whether this certificate is installed as a trusted CA.
     *
     * @param certBuffer encoded form of the certificate to look up.
     */
    public boolean hasCaCertInstalled(byte[] certBuffer) {
        try {
            return getCaCertAlias(certBuffer) != null;
        } catch (CertificateException ce) {
            Log.w(TAG, "Could not parse certificate", ce);
        }
        return false;
    }

    /**
     * Silently installs a certificate and private key pair.
     *
     * @param who Which {@link DeviceAdminReceiver} this request is associated with.
     * @param privKey The private key to install.
     * @param cert The certificate to install.
     * @param alias The private key alias under which to install the certificate. If a certificate
     * with that alias already exists, it will be overwritten.
     */
    public void installKeyPair(ComponentName who, PrivateKey privKey, Certificate cert,
            String alias) {
        try {
            Log.d(TAG, "PEM: " + Credentials.convertToPem(cert));
            Log.d(TAG, "Encoded: " + cert.getEncoded());
            mService.installKeyPair(who, privKey.getEncoded(), Credentials.convertToPem(cert),
                    alias);
        } catch (CertificateException e) {
            Log.w(TAG, "Error encoding certificate", e);
        } catch (IOException e) {
            Log.w(TAG, "Error writing certificate", e);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed talking with device policy service", e);
        }
    }

    /**
     * Returns the alias of a given CA certificate in the certificate store, or null if it
     * doesn't exist.
     */
    private static String getCaCertAlias(byte[] certBuffer) throws CertificateException {
        final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        final X509Certificate cert = (X509Certificate) certFactory.generateCertificate(
                              new ByteArrayInputStream(certBuffer));
        return new TrustedCertificateStore().getCertificateAlias(cert);
    }

    /**
     * Called by an application that is administering the device to disable all cameras
     * on the device.  After setting this, no applications will be able to access any cameras
     * on the device.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_CAMERA} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled Whether or not the camera should be disabled.
     */
    public void setCameraDisabled(ComponentName admin, boolean disabled) {
        if (mService != null) {
            try {
                mService.setCameraDisabled(admin, disabled, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Determine whether or not the device's cameras have been disabled either by the current
     * admin, if specified, or all admins.
     * @param admin The name of the admin component to check, or null to check if any admins
     * have disabled the camera
     */
    public boolean getCameraDisabled(ComponentName admin) {
        return getCameraDisabled(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public boolean getCameraDisabled(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getCameraDisabled(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by a device/profile owner to set whether the screen capture is disabled.
     *
     * <p>The calling device admin must be a device or profile owner. If it is not, a
     * security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     */
    public void setScreenCaptureDisabled(ComponentName admin, boolean disabled) {
        if (mService != null) {
            try {
                mService.setScreenCaptureDisabled(admin, UserHandle.myUserId(), disabled);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Determine whether or not screen capture has been disabled by the current
     * admin, if specified, or all admins.
     * @param admin The name of the admin component to check, or null to check if any admins
     * have disabled screen capture.
     */
    public boolean getScreenCaptureDisabled(ComponentName admin) {
        return getScreenCaptureDisabled(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public boolean getScreenCaptureDisabled(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getScreenCaptureDisabled(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to disable keyguard customizations,
     * such as widgets. After setting this, keyguard features will be disabled according to the
     * provided feature list.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param which {@link #KEYGUARD_DISABLE_FEATURES_NONE} (default),
     * {@link #KEYGUARD_DISABLE_WIDGETS_ALL}, {@link #KEYGUARD_DISABLE_SECURE_CAMERA},
     * {@link #KEYGUARD_DISABLE_SECURE_NOTIFICATIONS}, {@link #KEYGUARD_DISABLE_TRUST_AGENTS},
     * {@link #KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS}, {@link #KEYGUARD_DISABLE_FEATURES_ALL}
     */
    public void setKeyguardDisabledFeatures(ComponentName admin, int which) {
        if (mService != null) {
            try {
                mService.setKeyguardDisabledFeatures(admin, which, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Determine whether or not features have been disabled in keyguard either by the current
     * admin, if specified, or all admins.
     * @param admin The name of the admin component to check, or null to check if any admins
     * have disabled features in keyguard.
     * @return bitfield of flags. See {@link #setKeyguardDisabledFeatures(ComponentName, int)}
     * for a list.
     */
    public int getKeyguardDisabledFeatures(ComponentName admin) {
        return getKeyguardDisabledFeatures(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getKeyguardDisabledFeatures(ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getKeyguardDisabledFeatures(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return KEYGUARD_DISABLE_FEATURES_NONE;
    }

    /**
     * @hide
     */
    public void setActiveAdmin(ComponentName policyReceiver, boolean refreshing, int userHandle) {
        if (mService != null) {
            try {
                mService.setActiveAdmin(policyReceiver, refreshing, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     */
    public void setActiveAdmin(ComponentName policyReceiver, boolean refreshing) {
        setActiveAdmin(policyReceiver, refreshing, UserHandle.myUserId());
    }

    /**
     * Returns the DeviceAdminInfo as defined by the administrator's package info & meta-data
     * @hide
     */
    public DeviceAdminInfo getAdminInfo(ComponentName cn) {
        ActivityInfo ai;
        try {
            ai = mContext.getPackageManager().getReceiverInfo(cn,
                    PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Unable to retrieve device policy " + cn, e);
            return null;
        }

        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = ai;

        try {
            return new DeviceAdminInfo(mContext, ri);
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Unable to parse device policy " + cn, e);
            return null;
        } catch (IOException e) {
            Log.w(TAG, "Unable to parse device policy " + cn, e);
            return null;
        }
    }

    /**
     * @hide
     */
    public void getRemoveWarning(ComponentName admin, RemoteCallback result) {
        if (mService != null) {
            try {
                mService.getRemoveWarning(admin, result, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     */
    public void setActivePasswordState(int quality, int length, int letters, int uppercase,
            int lowercase, int numbers, int symbols, int nonletter, int userHandle) {
        if (mService != null) {
            try {
                mService.setActivePasswordState(quality, length, letters, uppercase, lowercase,
                        numbers, symbols, nonletter, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     */
    public void reportFailedPasswordAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportFailedPasswordAttempt(userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     */
    public void reportSuccessfulPasswordAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportSuccessfulPasswordAttempt(userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     * Sets the given package as the device owner. The package must already be installed and there
     * shouldn't be an existing device owner registered, for this call to succeed. Also, this
     * method must be called before the device is provisioned.
     * @param packageName the package name of the application to be registered as the device owner.
     * @return whether the package was successfully registered as the device owner.
     * @throws IllegalArgumentException if the package name is null or invalid
     * @throws IllegalStateException if a device owner is already registered or the device has
     *         already been provisioned.
     */
    public boolean setDeviceOwner(String packageName) throws IllegalArgumentException,
            IllegalStateException {
        return setDeviceOwner(packageName, null);
    }

    /**
     * @hide
     * Sets the given package as the device owner. The package must already be installed and there
     * shouldn't be an existing device owner registered, for this call to succeed. Also, this
     * method must be called before the device is provisioned.
     * @param packageName the package name of the application to be registered as the device owner.
     * @param ownerName the human readable name of the institution that owns this device.
     * @return whether the package was successfully registered as the device owner.
     * @throws IllegalArgumentException if the package name is null or invalid
     * @throws IllegalStateException if a device owner is already registered or the device has
     *         already been provisioned.
     */
    public boolean setDeviceOwner(String packageName, String ownerName)
            throws IllegalArgumentException, IllegalStateException {
        if (mService != null) {
            try {
                return mService.setDeviceOwner(packageName, ownerName);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to set device owner");
            }
        }
        return false;
    }

    /**
     * Used to determine if a particular package has been registered as a Device Owner app.
     * A device owner app is a special device admin that cannot be deactivated by the user, once
     * activated as a device admin. It also cannot be uninstalled. To check if a particular
     * package is currently registered as the device owner app, pass in the package name from
     * {@link Context#getPackageName()} to this method.<p/>This is useful for device
     * admin apps that want to check if they are also registered as the device owner app. The
     * exact mechanism by which a device admin app is registered as a device owner app is defined by
     * the setup process.
     * @param packageName the package name of the app, to compare with the registered device owner
     * app, if any.
     * @return whether or not the package is registered as the device owner app.
     */
    public boolean isDeviceOwnerApp(String packageName) {
        if (mService != null) {
            try {
                return mService.isDeviceOwner(packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to check device owner");
            }
        }
        return false;
    }

    /**
     * @hide
     * Redirect to isDeviceOwnerApp.
     */
    public boolean isDeviceOwner(String packageName) {
        return isDeviceOwnerApp(packageName);
    }

    /**
     * Clears the current device owner.  The caller must be the device owner.
     *
     * This function should be used cautiously as once it is called it cannot
     * be undone.  The device owner can only be set as a part of device setup
     * before setup completes.
     *
     * @param packageName The package name of the device owner.
     */
    public void clearDeviceOwnerApp(String packageName) {
        if (mService != null) {
            try {
                mService.clearDeviceOwner(packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to clear device owner");
            }
        }
    }

    /** @hide */
    public String getDeviceOwner() {
        if (mService != null) {
            try {
                return mService.getDeviceOwner();
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get device owner");
            }
        }
        return null;
    }

    /** @hide */
    public String getDeviceOwnerName() {
        if (mService != null) {
            try {
                return mService.getDeviceOwnerName();
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get device owner");
            }
        }
        return null;
    }

    /**
     * @hide
     * @deprecated Use #ACTION_SET_PROFILE_OWNER
     * Sets the given component as an active admin and registers the package as the profile
     * owner for this user. The package must already be installed and there shouldn't be
     * an existing profile owner registered for this user. Also, this method must be called
     * before the user setup has been completed.
     * <p>
     * This method can only be called by system apps that hold MANAGE_USERS permission and
     * MANAGE_DEVICE_ADMINS permission.
     * @param admin The component to register as an active admin and profile owner.
     * @param ownerName The user-visible name of the entity that is managing this user.
     * @return whether the admin was successfully registered as the profile owner.
     * @throws IllegalArgumentException if packageName is null, the package isn't installed, or
     *         the user has already been set up.
     */
    @SystemApi
    public boolean setActiveProfileOwner(ComponentName admin, String ownerName)
            throws IllegalArgumentException {
        if (mService != null) {
            try {
                final int myUserId = UserHandle.myUserId();
                mService.setActiveAdmin(admin, false, myUserId);
                return mService.setProfileOwner(admin, ownerName, myUserId);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to set profile owner " + re);
                throw new IllegalArgumentException("Couldn't set profile owner.", re);
            }
        }
        return false;
    }

    /**
     * @hide
     * Clears the active profile owner and removes all user restrictions. The caller must
     * be from the same package as the active profile owner for this user, otherwise a
     * SecurityException will be thrown.
     *
     * @param admin The component to remove as the profile owner.
     * @return
     */
    @SystemApi
    public void clearProfileOwner(ComponentName admin) {
        if (mService != null) {
            try {
                mService.clearProfileOwner(admin);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to clear profile owner " + admin + re);
            }
        }
    }

    /**
     * @hide
     * Checks if the user was already setup.
     */
    public boolean hasUserSetupCompleted() {
        if (mService != null) {
            try {
                return mService.hasUserSetupCompleted();
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to check if user setup has completed");
            }
        }
        return true;
    }

    /**
     * @deprecated Use setProfileOwner(ComponentName ...)
     * @hide
     * Sets the given package as the profile owner of the given user profile. The package must
     * already be installed and there shouldn't be an existing profile owner registered for this
     * user. Also, this method must be called before the user has been used for the first time.
     * @param packageName the package name of the application to be registered as profile owner.
     * @param ownerName the human readable name of the organisation associated with this DPM.
     * @param userHandle the userId to set the profile owner for.
     * @return whether the package was successfully registered as the profile owner.
     * @throws IllegalArgumentException if packageName is null, the package isn't installed, or
     *         the user has already been set up.
     */
    public boolean setProfileOwner(String packageName, String ownerName, int userHandle)
            throws IllegalArgumentException {
        if (packageName == null) {
            throw new NullPointerException("packageName cannot be null");
        }
        return setProfileOwner(new ComponentName(packageName, ""), ownerName, userHandle);
    }

    /**
     * @hide
     * Sets the given component as the profile owner of the given user profile. The package must
     * already be installed and there shouldn't be an existing profile owner registered for this
     * user. Only the system can call this API if the user has already completed setup.
     * @param admin the component name to be registered as profile owner.
     * @param ownerName the human readable name of the organisation associated with this DPM.
     * @param userHandle the userId to set the profile owner for.
     * @return whether the component was successfully registered as the profile owner.
     * @throws IllegalArgumentException if admin is null, the package isn't installed, or
     *         the user has already been set up.
     */
    public boolean setProfileOwner(ComponentName admin, String ownerName, int userHandle)
            throws IllegalArgumentException {
        if (admin == null) {
            throw new NullPointerException("admin cannot be null");
        }
        if (mService != null) {
            try {
                if (ownerName == null) {
                    ownerName = "";
                }
                return mService.setProfileOwner(admin, ownerName, userHandle);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to set profile owner", re);
                throw new IllegalArgumentException("Couldn't set profile owner.", re);
            }
        }
        return false;
    }

    /**
     * Sets the enabled state of the profile. A profile should be enabled only once it is ready to
     * be used. Only the profile owner can call this.
     *
     * @see #isProfileOwnerApp
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     */
    public void setProfileEnabled(ComponentName admin) {
        if (mService != null) {
            try {
                mService.setProfileEnabled(admin);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Sets the name of the Managed profile. In the device owner case it sets the name of the user
     * which it is called from. Only the profile owner or device owner can call this. If this is
     * never called by the profile or device owner, the name will be set to default values.
     *
     * @see #isProfileOwnerApp
     * @see #isDeviceOwnerApp
     *
     * @param profileName The name of the profile.
     */
    public void setProfileName(ComponentName who, String profileName) {
        if (mService != null) {
            try {
            mService.setProfileName(who, profileName);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed talking with device policy service", e);
        }
    }
}

    /**
     * Used to determine if a particular package is registered as the Profile Owner for the
     * current user. A profile owner is a special device admin that has additional privileges
     * within the managed profile.
     *
     * @param packageName The package name of the app to compare with the registered profile owner.
     * @return Whether or not the package is registered as the profile owner.
     */
    public boolean isProfileOwnerApp(String packageName) {
        if (mService != null) {
            try {
                ComponentName profileOwner = mService.getProfileOwner(
                        Process.myUserHandle().getIdentifier());
                return profileOwner != null
                        && profileOwner.getPackageName().equals(packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to check profile owner");
            }
        }
        return false;
    }

    /**
     * @hide
     * @return the packageName of the owner of the given user profile or null if no profile
     * owner has been set for that user.
     * @throws IllegalArgumentException if the userId is invalid.
     */
    public ComponentName getProfileOwner() throws IllegalArgumentException {
        return getProfileOwnerAsUser(Process.myUserHandle().getIdentifier());
    }

    /**
     * @see #getProfileOwner()
     * @hide
     */
    public ComponentName getProfileOwnerAsUser(final int userId) throws IllegalArgumentException {
        if (mService != null) {
            try {
                return mService.getProfileOwner(userId);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get profile owner");
                throw new IllegalArgumentException(
                        "Requested profile owner for invalid userId", re);
            }
        }
        return null;
    }

    /**
     * @hide
     * @return the human readable name of the organisation associated with this DPM or null if
     *         one is not set.
     * @throws IllegalArgumentException if the userId is invalid.
     */
    public String getProfileOwnerName() throws IllegalArgumentException {
        if (mService != null) {
            try {
                return mService.getProfileOwnerName(Process.myUserHandle().getIdentifier());
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get profile owner");
                throw new IllegalArgumentException(
                        "Requested profile owner for invalid userId", re);
            }
        }
        return null;
    }

    /**
     * Called by a profile owner or device owner to add a default intent handler activity for
     * intents that match a certain intent filter. This activity will remain the default intent
     * handler even if the set of potential event handlers for the intent filter changes and if
     * the intent preferences are reset.
     *
     * <p>The default disambiguation mechanism takes over if the activity is not installed
     * (anymore). When the activity is (re)installed, it is automatically reset as default
     * intent handler for the filter.
     *
     * <p>The calling device admin must be a profile owner or device owner. If it is not, a
     * security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param filter The IntentFilter for which a default handler is added.
     * @param activity The Activity that is added as default intent handler.
     */
    public void addPersistentPreferredActivity(ComponentName admin, IntentFilter filter,
            ComponentName activity) {
        if (mService != null) {
            try {
                mService.addPersistentPreferredActivity(admin, filter, activity);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile owner or device owner to remove all persistent intent handler preferences
     * associated with the given package that were set by {@link #addPersistentPreferredActivity}.
     *
     * <p>The calling device admin must be a profile owner. If it is not, a security
     * exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package for which preferences are removed.
     */
    public void clearPackagePersistentPreferredActivities(ComponentName admin,
            String packageName) {
        if (mService != null) {
            try {
                mService.clearPackagePersistentPreferredActivities(admin, packageName);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile or device owner to set the application restrictions for a given target
     * application running in the managed profile.
     *
     * <p>The provided {@link Bundle} consists of key-value pairs, where the types of values may be
     * boolean, int, String, or String[]. The recommended format for keys
     * is "com.example.packagename/example-setting" to avoid naming conflicts with library
     * components such as {@link android.webkit.WebView}.
     *
     * <p>The application restrictions are only made visible to the target application and the
     * profile or device owner.
     *
     * <p>The calling device admin must be a profile or device owner; if it is not, a security
     * exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package to update restricted settings for.
     * @param settings A {@link Bundle} to be parsed by the receiving application, conveying a new
     * set of active restrictions.
     */
    public void setApplicationRestrictions(ComponentName admin, String packageName,
            Bundle settings) {
        if (mService != null) {
            try {
                mService.setApplicationRestrictions(admin, packageName, settings);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile owner to register a listener for private key access.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param listener The {@link DeviceAdminReceiver} that should listen for private key access
     * requests.
     * @see DeviceAdminReceiver#onChoosePrivateKeyAlias
     */
    public void registerPrivateKeyAccessListener(ComponentName admin, ComponentName listener) {
        if (mService != null) {
            try {
                mService.registerPrivateKeyAccessListener(admin, listener);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to register private key access listener with device policy " +
                        "service", e);
            }
        }
    }

    /**
     * Sets a list of features to enable for a TrustAgentService component. This is meant to be
     * Sets a list of features to enable for a TrustAgent component. This is meant to be
     * used in conjunction with {@link #KEYGUARD_DISABLE_TRUST_AGENTS}, which will disable all
     * trust agents but those with features enabled by this function call.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param agent Which component to enable features for.
     * @param features List of features to enable. Consult specific TrustAgent documentation for
     * the feature list.
     */
    public void setTrustAgentFeaturesEnabled(ComponentName admin, ComponentName agent,
            List<String> features) {
        if (mService != null) {
            try {
                mService.setTrustAgentFeaturesEnabled(admin, agent, features, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Gets list of enabled features for the given TrustAgent component. If admin is
     * null, this will return the intersection of all features enabled for the given agent by all
     * admins.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param agent Which component to get enabled features for.
     * @return List of enabled features.
     */
    public List<String> getTrustAgentFeaturesEnabled(ComponentName admin, ComponentName agent) {
        if (mService != null) {
            try {
                return mService.getTrustAgentFeaturesEnabled(admin, agent, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return new ArrayList<String>(); // empty list
    }

    /**
     * Called by a profile owner to set whether caller-Id information from the managed
     * profile will be shown for incoming calls.
     *
     * <p>The calling device admin must be a profile owner. If it is not, a
     * security exception will be thrown.
     *
     * @param who Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled If true caller-Id information in the managed profile is not displayed.
     */
    public void setCrossProfileCallerIdDisabled(ComponentName who, boolean disabled) {
        if (mService != null) {
            try {
                mService.setCrossProfileCallerIdDisabled(who, disabled);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Determine whether or not caller-Id information has been disabled.
     *
     * <p>The calling device admin must be a profile owner. If it is not, a
     * security exception will be thrown.
     *
     * @param who Which {@link DeviceAdminReceiver} this request is associated with.
     */
    public boolean getCrossProfileCallerIdDisabled(ComponentName who) {
        if (mService != null) {
            try {
                return mService.getCrossProfileCallerIdDisabled(who);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Determine whether or not caller-Id information has been disabled.
     *
     * @param userHandle The user for whom to check the caller-id permission
     * @hide
     */
    public boolean getCrossProfileCallerIdDisabled(UserHandle userHandle) {
        if (mService != null) {
            try {
                return mService.getCrossProfileCallerIdDisabledForUser(userHandle.getIdentifier());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by the profile owner so that some intents sent in the managed profile can also be
     * resolved in the parent, or vice versa.
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param filter The {@link IntentFilter} the intent has to match to be also resolved in the
     * other profile
     * @param flags {@link DevicePolicyManager#FLAG_MANAGED_CAN_ACCESS_PARENT} and
     * {@link DevicePolicyManager#FLAG_PARENT_CAN_ACCESS_MANAGED} are supported.
     */
    public void addCrossProfileIntentFilter(ComponentName admin, IntentFilter filter, int flags) {
        if (mService != null) {
            try {
                mService.addCrossProfileIntentFilter(admin, filter, flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile owner to remove the cross-profile intent filters that go from the
     * managed profile to the parent, or from the parent to the managed profile.
     * Only removes those that have been set by the profile owner.
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     */
    public void clearCrossProfileIntentFilters(ComponentName admin) {
        if (mService != null) {
            try {
                mService.clearCrossProfileIntentFilters(admin);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a device owner to create a user with the specified name. The UserHandle returned
     * by this method should not be persisted as user handles are recycled as users are removed and
     * created. If you need to persist an identifier for this user, use
     * {@link UserManager#getSerialNumberForUser}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param name the user's name
     * @see UserHandle
     * @return the UserHandle object for the created user, or null if the user could not be created.
     */
    public UserHandle createUser(ComponentName admin, String name) {
        try {
            return mService.createUser(admin, name);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not create a user", re);
        }
        return null;
    }

    /**
     * Called by a device owner to create a user with the specified name. The UserHandle returned
     * by this method should not be persisted as user handles are recycled as users are removed and
     * created. If you need to persist an identifier for this user, use
     * {@link UserManager#getSerialNumberForUser}.  The new user will be started in the background
     * immediately.
     *
     * <p> profileOwnerComponent is the {@link DeviceAdminReceiver} to be the profile owner as well
     * as registered as an active admin on the new user.  The profile owner package will be
     * installed on the new user if it already is installed on the device.
     *
     * <p>If the optionalInitializeData is not null, then the extras will be passed to the
     * profileOwnerComponent when onEnable is called.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param name the user's name
     * @param ownerName the human readable name of the organisation associated with this DPM.
     * @param profileOwnerComponent The {@link DeviceAdminReceiver} that will be an active admin on
     *      the user.
     * @param adminExtras Extras that will be passed to onEnable of the admin receiver
     *      on the new user.
     * @see UserHandle
     * @return the UserHandle object for the created user, or null if the user could not be created.
     */
    public UserHandle createAndInitializeUser(ComponentName admin, String name, String ownerName,
            ComponentName profileOwnerComponent, Bundle adminExtras) {
        try {
            return mService.createAndInitializeUser(admin, name, ownerName, profileOwnerComponent,
                    adminExtras);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not create a user", re);
        }
        return null;
    }

    /**
     * Called by a device owner to remove a user and all associated data. The primary user can
     * not be removed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to remove.
     * @return {@code true} if the user was removed, {@code false} otherwise.
     */
    public boolean removeUser(ComponentName admin, UserHandle userHandle) {
        try {
            return mService.removeUser(admin, userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not remove user ", re);
            return false;
        }
    }

    /**
     * Called by a device owner to switch the specified user to the foreground.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to switch to; null will switch to primary.
     * @return {@code true} if the switch was successful, {@code false} otherwise.
     *
     * @see Intent#ACTION_USER_FOREGROUND
     */
    public boolean switchUser(ComponentName admin, UserHandle userHandle) {
        try {
            return mService.switchUser(admin, userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not switch user ", re);
            return false;
        }
    }

    /**
     * Called by a profile or device owner to get the application restrictions for a given target
     * application running in the managed profile.
     *
     * <p>The calling device admin must be a profile or device owner; if it is not, a security
     * exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package to fetch restricted settings of.
     * @return {@link Bundle} of settings corresponding to what was set last time
     * {@link DevicePolicyManager#setApplicationRestrictions} was called, or an empty {@link Bundle}
     * if no restrictions have been set.
     */
    public Bundle getApplicationRestrictions(ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                return mService.getApplicationRestrictions(admin, packageName);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Called by a profile or device owner to set a user restriction specified
     * by the key.
     * <p>
     * The calling device admin must be a profile or device owner; if it is not,
     * a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param key The key of the restriction. See the constants in
     *            {@link android.os.UserManager} for the list of keys.
     */
    public void addUserRestriction(ComponentName admin, String key) {
        if (mService != null) {
            try {
                mService.setUserRestriction(admin, key, true);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile or device owner to clear a user restriction specified
     * by the key.
     * <p>
     * The calling device admin must be a profile or device owner; if it is not,
     * a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param key The key of the restriction. See the constants in
     *            {@link android.os.UserManager} for the list of keys.
     */
    public void clearUserRestriction(ComponentName admin, String key) {
        if (mService != null) {
            try {
                mService.setUserRestriction(admin, key, false);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by device or profile owner to hide or unhide packages. When a package is hidden it
     * is unavailable for use, but the data and actual package file remain.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package to hide or unhide.
     * @param hidden {@code true} if the package should be hidden, {@code false} if it should be
     *                 unhidden.
     * @return boolean Whether the hidden setting of the package was successfully updated.
     */
    public boolean setApplicationHidden(ComponentName admin, String packageName,
            boolean hidden) {
        if (mService != null) {
            try {
                return mService.setApplicationHidden(admin, packageName, hidden);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by profile or device owner to hide or unhide currently installed packages. This
     * should only be called by a profile or device owner running within a managed profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param intent An intent matching the app(s) to be updated. All apps that resolve for this
     *               intent will be updated in the current profile.
     * @param hidden {@code true} if the packages should be hidden, {@code false} if they should
     *                 be unhidden.
     * @return int The number of activities that matched the intent and were updated.
     */
    public int setApplicationsHidden(ComponentName admin, Intent intent, boolean hidden) {
        if (mService != null) {
            try {
                return mService.setApplicationsHidden(admin, intent, hidden);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by device or profile owner to determine if a package is hidden.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package to retrieve the hidden status of.
     * @return boolean {@code true} if the package is hidden, {@code false} otherwise.
     */
    public boolean isApplicationHidden(ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                return mService.isApplicationHidden(admin, packageName);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by profile or device owner to re-enable a system app that was disabled by default
     * when the managed profile was created. This can only be called from a profile or device
     * owner running within a managed profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package to be re-enabled in the current profile.
     */
    public void enableSystemApp(ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                mService.enableSystemApp(admin, packageName);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to install package: " + packageName);
            }
        }
    }

    /**
     * Called by profile or device owner to re-enable system apps by intent that were disabled
     * by default when the managed profile was created. This can only be called from a profile
     * or device owner running within a managed profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param intent An intent matching the app(s) to be installed. All apps that resolve for this
     *               intent will be re-enabled in the current profile.
     * @return int The number of activities that matched the intent and were installed.
     */
    public int enableSystemApp(ComponentName admin, Intent intent) {
        if (mService != null) {
            try {
                return mService.enableSystemAppWithIntent(admin, intent);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to install packages matching filter: " + intent);
            }
        }
        return 0;
    }

    /**
     * Called by a profile owner to disable account management for a specific type of account.
     *
     * <p>The calling device admin must be a profile owner. If it is not, a
     * security exception will be thrown.
     *
     * <p>When account management is disabled for an account type, adding or removing an account
     * of that type will not be possible.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param accountType For which account management is disabled or enabled.
     * @param disabled The boolean indicating that account management will be disabled (true) or
     * enabled (false).
     */
    public void setAccountManagementDisabled(ComponentName admin, String accountType,
            boolean disabled) {
        if (mService != null) {
            try {
                mService.setAccountManagementDisabled(admin, accountType, disabled);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Gets the array of accounts for which account management is disabled by the profile owner.
     *
     * <p> Account management can be disabled/enabled by calling
     * {@link #setAccountManagementDisabled}.
     *
     * @return a list of account types for which account management has been disabled.
     *
     * @see #setAccountManagementDisabled
     */
    public String[] getAccountTypesWithManagementDisabled() {
        return getAccountTypesWithManagementDisabledAsUser(UserHandle.myUserId());
    }

    /**
     * @see #getAccountTypesWithManagementDisabled()
     * @hide
     */
    public String[] getAccountTypesWithManagementDisabledAsUser(int userId) {
        if (mService != null) {
            try {
                return mService.getAccountTypesWithManagementDisabledAsUser(userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }

        return null;
    }

    /**
     * Sets which packages may enter lock task mode.
     *
     * <p>Any packages that shares uid with an allowed package will also be allowed
     * to activate lock task.
     *
     * This function can only be called by the device owner.
     * @param packages The list of packages allowed to enter lock task mode
     *
     * @see Activity#startLockTask()
     * @see DeviceAdminReceiver#onLockTaskModeChanged(Context, Intent, boolean, String)
     * @see UserManager#DISALLOW_CREATE_WINDOWS
     */
    public void setLockTaskPackages(String[] packages) throws SecurityException {
        if (mService != null) {
            try {
                mService.setLockTaskPackages(packages);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * This function returns the list of packages allowed to start the lock task mode.
     * @hide
     */
    public String[] getLockTaskPackages() {
        if (mService != null) {
            try {
                return mService.getLockTaskPackages();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * This function lets the caller know whether the given component is allowed to start the
     * lock task mode.
     * @param pkg The package to check
     */
    public boolean isLockTaskPermitted(String pkg) {
        if (mService != null) {
            try {
                return mService.isLockTaskPermitted(pkg);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by device owners to update {@link Settings.Global} settings. Validation that the value
     * of the setting is in the correct form for the setting type should be performed by the caller.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param setting The name of the setting to update.
     * @param value The value to update the setting to.
     */
    public void setGlobalSetting(ComponentName admin, String setting, String value) {
        if (mService != null) {
            try {
                mService.setGlobalSetting(admin, setting, value);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by profile or device owners to update {@link Settings.Secure} settings. Validation
     * that the value of the setting is in the correct form for the setting type should be performed
     * by the caller.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param setting The name of the setting to update.
     * @param value The value to update the setting to.
     */
    public void setSecureSetting(ComponentName admin, String setting, String value) {
        if (mService != null) {
            try {
                mService.setSecureSetting(admin, setting, value);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Designates a specific service component as the provider for
     * making permission requests of a local or remote administrator of the user.
     * <p/>
     * Only a profile owner can designate the restrictions provider.
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param provider The component name of the service that implements
     * {@link AbstractRestrictionsProvider}. If this param is null,
     * it removes the restrictions provider previously assigned.
     */
    public void setRestrictionsProvider(ComponentName admin, ComponentName provider) {
        if (mService != null) {
            try {
                mService.setRestrictionsProvider(admin, provider);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to set permission provider on device policy service");
            }
        }
    }

    /**
     * Called by profile or device owners to set the master volume mute on or off.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param on {@code true} to mute master volume, {@code false} to turn mute off.
     */
    public void setMasterVolumeMuted(ComponentName admin, boolean on) {
        if (mService != null) {
            try {
                mService.setMasterVolumeMuted(admin, on);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to setMasterMute on device policy service");
            }
        }
    }

    /**
     * Called by profile or device owners to check whether the master volume mute is on or off.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return {@code true} if master volume is muted, {@code false} if it's not.
     */
    public boolean isMasterVolumeMuted(ComponentName admin) {
        if (mService != null) {
            try {
                return mService.isMasterVolumeMuted(admin);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get isMasterMute on device policy service");
            }
        }
        return false;
    }

    /**
     * Called by profile or device owners to change whether a user can uninstall
     * a package.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName package to change.
     * @param blockUninstall true if the user shouldn't be able to uninstall the package.
     */
    public void setBlockUninstall(ComponentName admin, String packageName, boolean blockUninstall) {
        if (mService != null) {
            try {
                mService.setBlockUninstall(admin, packageName, blockUninstall);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to call block uninstall on device policy service");
            }
        }
    }

    /**
     * Called by profile or device owners to check whether a user has been blocked from
     * uninstalling a package.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName package to check.
     * @return true if the user shouldn't be able to uninstall the package.
     */
    public boolean getBlockUninstall(ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                return mService.getBlockUninstall(admin, packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to call block uninstall on device policy service");
            }
        }
        return false;
    }

    /**
     * Called by the profile owner to enable widget providers from a given package
     * to be available in the parent profile. As a result the user will be able to
     * add widgets from the white-listed package running under the profile to a widget
     * host which runs under the device owner, for example the home screen. Note that
     * a package may have zero or more provider components, where each component
     * provides a different widget type.
     * <p>
     * <strong>Note:</strong> By default no widget provider package is white-listed.
     * </p>
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package from which widget providers are white-listed.
     * @return Whether the package was added.
     *
     * @see #removeCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @see #getCrossProfileWidgetProviders(android.content.ComponentName)
     */
    public boolean addCrossProfileWidgetProvider(ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                return mService.addCrossProfileWidgetProvider(admin, packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Error calling addCrossProfileWidgetProvider", re);
            }
        }
        return false;
    }

    /**
     * Called by the profile owner to disable widget providers from a given package
     * to be available in the parent profile. For this method to take effect the
     * package should have been added via {@link #addCrossProfileWidgetProvider(
     * android.content.ComponentName, String)}.
     * <p>
     * <strong>Note:</strong> By default no widget provider package is white-listed.
     * </p>
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package from which widget providers are no longer
     *     white-listed.
     * @return Whether the package was removed.
     *
     * @see #addCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @see #getCrossProfileWidgetProviders(android.content.ComponentName)
     */
    public boolean removeCrossProfileWidgetProvider(ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                return mService.removeCrossProfileWidgetProvider(admin, packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Error calling removeCrossProfileWidgetProvider", re);
            }
        }
        return false;
    }

    /**
     * Called by the profile owner to query providers from which packages are
     * available in the parent profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The white-listed package list.
     *
     * @see #addCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @see #removeCrossProfileWidgetProvider(android.content.ComponentName, String)
     */
    public List<String> getCrossProfileWidgetProviders(ComponentName admin) {
        if (mService != null) {
            try {
                List<String> providers = mService.getCrossProfileWidgetProviders(admin);
                if (providers != null) {
                    return providers;
                }
            } catch (RemoteException re) {
                Log.w(TAG, "Error calling getCrossProfileWidgetProviders", re);
            }
        }
        return Collections.emptyList();
    }
}
