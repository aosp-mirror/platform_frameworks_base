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

import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.admin.SecurityLog.SecurityEvent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.net.ProxyInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.ContactsContract.Directory;
import android.provider.Settings;
import android.security.Credentials;
import android.service.restrictions.RestrictionsReceiver;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.org.conscrypt.TrustedCertificateStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Public interface for managing policies enforced on a device. Most clients of this class must be
 * registered with the system as a <a href="{@docRoot}guide/topics/admin/device-admin.html">device
 * administrator</a>. Additionally, a device administrator may be registered as either a profile or
 * device owner. A given method is accessible to all device administrators unless the documentation
 * for that method specifies that it is restricted to either device or profile owners. Any
 * application calling an api may only pass as an argument a device administrator component it
 * owns. Otherwise, a {@link SecurityException} will be thrown.
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>
 * For more information about managing policies for device administration, read the <a href=
 * "{@docRoot}guide/topics/admin/device-admin.html">Device Administration</a> developer
 * guide. </div>
 */
public class DevicePolicyManager {
    private static String TAG = "DevicePolicyManager";

    private final Context mContext;
    private final IDevicePolicyManager mService;
    private final boolean mParentInstance;

    private DevicePolicyManager(Context context, boolean parentInstance) {
        this(context,
                IDevicePolicyManager.Stub.asInterface(
                        ServiceManager.getService(Context.DEVICE_POLICY_SERVICE)),
                parentInstance);
    }

    /** @hide */
    @VisibleForTesting
    protected DevicePolicyManager(
            Context context, IDevicePolicyManager service, boolean parentInstance) {
        mContext = context;
        mService = service;
        mParentInstance = parentInstance;
    }

    /** @hide */
    public static DevicePolicyManager create(Context context) {
        DevicePolicyManager me = new DevicePolicyManager(context, false);
        return me.mService != null ? me : null;
    }

    /** @hide test will override it. */
    @VisibleForTesting
    protected int myUserId() {
        return UserHandle.myUserId();
    }

    /**
     * Activity action: Starts the provisioning flow which sets up a managed profile.
     *
     * <p>A managed profile allows data separation for example for the usage of a
     * device as a personal and corporate device. The user which provisioning is started from and
     * the managed profile share a launcher.
     *
     * <p>This intent will typically be sent by a mobile device management application (MDM).
     * Provisioning adds a managed profile and sets the MDM as the profile owner who has full
     * control over the profile.
     *
     * <p>It is possible to check if provisioning is allowed or not by querying the method
     * {@link #isProvisioningAllowed(String)}.
     *
     * <p>In version {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this intent must contain the
     * extra {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME}.
     * As of {@link android.os.Build.VERSION_CODES#M}, it should contain the extra
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME} instead, although specifying only
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME} is still supported.
     *
     * <p>The intent may also contain the following extras:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE}, optional </li>
     * <li>{@link #EXTRA_PROVISIONING_SKIP_ENCRYPTION}, optional, supported from
     * {@link android.os.Build.VERSION_CODES#N}</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LOGO_URI}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_MAIN_COLOR}, optional</li>
     * </ul>
     *
     * <p>When managed provisioning has completed, broadcasts are sent to the application specified
     * in the provisioning intent. The
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} broadcast is sent in the
     * managed profile and the {@link #ACTION_MANAGED_PROFILE_PROVISIONED} broadcast is sent in
     * the primary profile.
     *
     * <p>If provisioning fails, the managedProfile is removed so the device returns to its
     * previous state.
     *
     * <p>If launched with {@link android.app.Activity#startActivityForResult(Intent, int)} a
     * result code of {@link android.app.Activity#RESULT_OK} implies that the synchronous part of
     * the provisioning flow was successful, although this doesn't guarantee the full flow will
     * succeed. Conversely a result code of {@link android.app.Activity#RESULT_CANCELED} implies
     * that the user backed-out of provisioning, or some precondition for provisioning wasn't met.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_MANAGED_PROFILE
        = "android.app.action.PROVISION_MANAGED_PROFILE";

    /**
     * Activity action: Starts the provisioning flow which sets up a managed user.
     *
     * <p>This intent will typically be sent by a mobile device management application (MDM).
     * Provisioning configures the user as managed user and sets the MDM as the profile
     * owner who has full control over the user. Provisioning can only happen before user setup has
     * been completed. Use {@link #isProvisioningAllowed(String)} to check if provisioning is
     * allowed.
     *
     * <p>The intent contains the following extras:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}</li>
     * <li>{@link #EXTRA_PROVISIONING_SKIP_ENCRYPTION}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LOGO_URI}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_MAIN_COLOR}, optional</li>
     * </ul>
     *
     * <p>If provisioning fails, the device returns to its previous state.
     *
     * <p>If launched with {@link android.app.Activity#startActivityForResult(Intent, int)} a
     * result code of {@link android.app.Activity#RESULT_OK} implies that the synchronous part of
     * the provisioning flow was successful, although this doesn't guarantee the full flow will
     * succeed. Conversely a result code of {@link android.app.Activity#RESULT_CANCELED} implies
     * that the user backed-out of provisioning, or some precondition for provisioning wasn't met.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_MANAGED_USER
        = "android.app.action.PROVISION_MANAGED_USER";

    /**
     * Activity action: Starts the provisioning flow which sets up a managed device.
     * Must be started with {@link android.app.Activity#startActivityForResult(Intent, int)}.
     *
     * <p> During device owner provisioning a device admin app is set as the owner of the device.
     * A device owner has full control over the device. The device owner can not be modified by the
     * user.
     *
     * <p> A typical use case would be a device that is owned by a company, but used by either an
     * employee or client.
     *
     * <p> An intent with this action can be sent only on an unprovisioned device.
     * It is possible to check if provisioning is allowed or not by querying the method
     * {@link #isProvisioningAllowed(String)}.
     *
     * <p>The intent contains the following extras:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}</li>
     * <li>{@link #EXTRA_PROVISIONING_SKIP_ENCRYPTION}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LOGO_URI}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_MAIN_COLOR}, optional</li>
     * </ul>
     *
     * <p>When device owner provisioning has completed, an intent of the type
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} is broadcast to the
     * device owner.
     *
     * <p>If provisioning fails, the device is factory reset.
     *
     * <p>A result code of {@link android.app.Activity#RESULT_OK} implies that the synchronous part
     * of the provisioning flow was successful, although this doesn't guarantee the full flow will
     * succeed. Conversely a result code of {@link android.app.Activity#RESULT_CANCELED} implies
     * that the user backed-out of provisioning, or some precondition for provisioning wasn't met.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_MANAGED_DEVICE
        = "android.app.action.PROVISION_MANAGED_DEVICE";

    /**
     * Activity action: Starts the provisioning flow which sets up a managed device.
     *
     * <p>During device owner provisioning, a device admin app is downloaded and set as the owner of
     * the device. A device owner has full control over the device. The device owner can not be
     * modified by the user and the only way of resetting the device is via factory reset.
     *
     * <p>A typical use case would be a device that is owned by a company, but used by either an
     * employee or client.
     *
     * <p>The provisioning message should be sent to an unprovisioned device.
     *
     * <p>Unlike {@link #ACTION_PROVISION_MANAGED_DEVICE}, the provisioning message can only be sent
     * by a privileged app with the permission
     * {@link android.Manifest.permission#DISPATCH_PROVISIONING_MESSAGE}.
     *
     * <p>The provisioning intent contains the following properties:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM}, optional</li>
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
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PAC_URL}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional</li></ul>
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE =
            "android.app.action.PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE";

    /**
     * Activity action: Starts the provisioning flow which sets up a managed device.
     * Must be started with {@link android.app.Activity#startActivityForResult(Intent, int)}.
     *
     * <p>NOTE: This is only supported on split system user devices, and puts the device into a
     * management state that is distinct from that reached by
     * {@link #ACTION_PROVISION_MANAGED_DEVICE} - specifically the device owner runs on the system
     * user, and only has control over device-wide policies, not individual users and their data.
     * The primary benefit is that multiple non-system users are supported when provisioning using
     * this form of device management.
     *
     * <p>During device owner provisioning a device admin app is set as the owner of the device.
     * A device owner has full control over the device. The device owner can not be modified by the
     * user.
     *
     * <p>A typical use case would be a device that is owned by a company, but used by either an
     * employee or client.
     *
     * <p>An intent with this action can be sent only on an unprovisioned device.
     * It is possible to check if provisioning is allowed or not by querying the method
     * {@link #isProvisioningAllowed(String)}.
     *
     * <p>The intent contains the following extras:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}</li>
     * <li>{@link #EXTRA_PROVISIONING_SKIP_ENCRYPTION}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LOGO_URI}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_MAIN_COLOR}, optional</li>
     * </ul>
     *
     * <p>When device owner provisioning has completed, an intent of the type
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} is broadcast to the
     * device owner.
     *
     * <p>If provisioning fails, the device is factory reset.
     *
     * <p>A result code of {@link android.app.Activity#RESULT_OK} implies that the synchronous part
     * of the provisioning flow was successful, although this doesn't guarantee the full flow will
     * succeed. Conversely a result code of {@link android.app.Activity#RESULT_CANCELED} implies
     * that the user backed-out of provisioning, or some precondition for provisioning wasn't met.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE
        = "android.app.action.PROVISION_MANAGED_SHAREABLE_DEVICE";

    /**
     * Activity action: Finalizes management provisioning, should be used after user-setup
     * has been completed and {@link #getUserProvisioningState()} returns one of:
     * <ul>
     * <li>{@link #STATE_USER_SETUP_INCOMPLETE}</li>
     * <li>{@link #STATE_USER_SETUP_COMPLETE}</li>
     * <li>{@link #STATE_USER_PROFILE_COMPLETE}</li>
     * </ul>
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_FINALIZATION
            = "android.app.action.PROVISION_FINALIZATION";

    /**
     * Action: Bugreport sharing with device owner has been accepted by the user.
     *
     * @hide
     */
    public static final String ACTION_BUGREPORT_SHARING_ACCEPTED =
            "com.android.server.action.BUGREPORT_SHARING_ACCEPTED";

    /**
     * Action: Bugreport sharing with device owner has been declined by the user.
     *
     * @hide
     */
    public static final String ACTION_BUGREPORT_SHARING_DECLINED =
            "com.android.server.action.BUGREPORT_SHARING_DECLINED";

    /**
     * Action: Bugreport has been collected and is dispatched to {@link DevicePolicyManagerService}.
     *
     * @hide
     */
    public static final String ACTION_REMOTE_BUGREPORT_DISPATCH =
            "android.intent.action.REMOTE_BUGREPORT_DISPATCH";

    /**
     * Extra for shared bugreport's SHA-256 hash.
     *
     * @hide
     */
    public static final String EXTRA_REMOTE_BUGREPORT_HASH =
            "android.intent.extra.REMOTE_BUGREPORT_HASH";

    /**
     * Extra for remote bugreport notification shown type.
     *
     * @hide
     */
    public static final String EXTRA_BUGREPORT_NOTIFICATION_TYPE =
            "android.app.extra.bugreport_notification_type";

    /**
     * Notification type for a started remote bugreport flow.
     *
     * @hide
     */
    public static final int NOTIFICATION_BUGREPORT_STARTED = 1;

    /**
     * Notification type for a bugreport that has already been accepted to be shared, but is still
     * being taken.
     *
     * @hide
     */
    public static final int NOTIFICATION_BUGREPORT_ACCEPTED_NOT_FINISHED = 2;

    /**
     * Notification type for a bugreport that has been taken and can be shared or declined.
     *
     * @hide
     */
    public static final int NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED = 3;

    /**
     * A {@link android.os.Parcelable} extra of type {@link android.os.PersistableBundle} that
     * allows a mobile device management application or NFC programmer application which starts
     * managed provisioning to pass data to the management application instance after provisioning.
     * <p>
     * If used with {@link #ACTION_PROVISION_MANAGED_PROFILE} it can be used by the application that
     * sends the intent to pass data to itself on the newly created profile.
     * If used with {@link #ACTION_PROVISION_MANAGED_DEVICE} it allows passing data to the same
     * instance of the app on the primary user.
     * Starting from {@link android.os.Build.VERSION_CODES#M}, if used with
     * {@link #MIME_TYPE_PROVISIONING_NFC} as part of NFC managed device provisioning, the NFC
     * message should contain a stringified {@link java.util.Properties} instance, whose string
     * properties will be converted into a {@link android.os.PersistableBundle} and passed to the
     * management application after provisioning.
     *
     * <p>
     * In both cases the application receives the data in
     * {@link DeviceAdminReceiver#onProfileProvisioningComplete} via an intent with the action
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE}. The bundle is not changed
     * during the managed provisioning.
     */
    public static final String EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE =
            "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE";

    /**
     * A String extra holding the package name of the mobile device management application that
     * will be set as the profile owner or device owner.
     *
     * <p>If an application starts provisioning directly via an intent with action
     * {@link #ACTION_PROVISION_MANAGED_PROFILE} this package has to match the package name of the
     * application that started provisioning. The package will be set as profile owner in that case.
     *
     * <p>This package is set as device owner when device owner provisioning is started by an NFC
     * message containing an NFC record with MIME type {@link #MIME_TYPE_PROVISIONING_NFC}.
     *
     * <p> When this extra is set, the application must have exactly one device admin receiver.
     * This receiver will be set as the profile or device owner and active admin.
     *
     * @see DeviceAdminReceiver
     * @deprecated Use {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}. This extra is still
     * supported, but only if there is only one device admin receiver in the package that requires
     * the permission {@link android.Manifest.permission#BIND_DEVICE_ADMIN}.
     */
    @Deprecated
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME";

    /**
     * A ComponentName extra indicating the device admin receiver of the mobile device management
     * application that will be set as the profile owner or device owner and active admin.
     *
     * <p>If an application starts provisioning directly via an intent with action
     * {@link #ACTION_PROVISION_MANAGED_PROFILE} or
     * {@link #ACTION_PROVISION_MANAGED_DEVICE} the package name of this
     * component has to match the package name of the application that started provisioning.
     *
     * <p>This component is set as device owner and active admin when device owner provisioning is
     * started by an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE} or by an NFC
     * message containing an NFC record with MIME type
     * {@link #MIME_TYPE_PROVISIONING_NFC}. For the NFC record, the component name must be
     * flattened to a string, via {@link ComponentName#flattenToShortString()}.
     *
     * @see DeviceAdminReceiver
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME";

    /**
     * An {@link android.accounts.Account} extra holding the account to migrate during managed
     * profile provisioning. If the account supplied is present in the primary user, it will be
     * copied, along with its credentials to the managed profile and removed from the primary user.
     *
     * Use with {@link #ACTION_PROVISION_MANAGED_PROFILE}.
     */

    public static final String EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE
        = "android.app.extra.PROVISIONING_ACCOUNT_TO_MIGRATE";

    /**
     * A String extra that, holds the email address of the account which a managed profile is
     * created for. Used with {@link #ACTION_PROVISION_MANAGED_PROFILE} and
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE}.
     *
     * <p> This extra is part of the {@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}.
     *
     * <p> If the {@link #ACTION_PROVISION_MANAGED_PROFILE} intent that starts managed provisioning
     * contains this extra, it is forwarded in the
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} intent to the mobile
     * device management application that was set as the profile owner during provisioning.
     * It is usually used to avoid that the user has to enter their email address twice.
     */
    public static final String EXTRA_PROVISIONING_EMAIL_ADDRESS
        = "android.app.extra.PROVISIONING_EMAIL_ADDRESS";

    /**
     * A integer extra indicating the predominant color to show during the provisioning.
     * Refer to {@link android.graphics.Color} for how the color is represented.
     *
     * <p>Use with {@link #ACTION_PROVISION_MANAGED_PROFILE} or
     * {@link #ACTION_PROVISION_MANAGED_DEVICE}.
     */
    public static final String EXTRA_PROVISIONING_MAIN_COLOR =
             "android.app.extra.PROVISIONING_MAIN_COLOR";

    /**
     * A Boolean extra that can be used by the mobile device management application to skip the
     * disabling of system apps during provisioning when set to {@code true}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} or an intent with action
     * {@link #ACTION_PROVISION_MANAGED_DEVICE} that starts device owner provisioning.
     */
    public static final String EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED =
            "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED";

    /**
     * A String extra holding the time zone {@link android.app.AlarmManager} that the device
     * will be set to.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_TIME_ZONE
        = "android.app.extra.PROVISIONING_TIME_ZONE";

    /**
     * A Long extra holding the wall clock time (in milliseconds) to be set on the device's
     * {@link android.app.AlarmManager}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_LOCAL_TIME
        = "android.app.extra.PROVISIONING_LOCAL_TIME";

    /**
     * A String extra holding the {@link java.util.Locale} that the device will be set to.
     * Format: xx_yy, where xx is the language code, and yy the country code.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_LOCALE
        = "android.app.extra.PROVISIONING_LOCALE";

    /**
     * A String extra holding the ssid of the wifi network that should be used during nfc device
     * owner provisioning for downloading the mobile device management application.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_SSID
        = "android.app.extra.PROVISIONING_WIFI_SSID";

    /**
     * A boolean extra indicating whether the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}
     * is hidden or not.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_HIDDEN
        = "android.app.extra.PROVISIONING_WIFI_HIDDEN";

    /**
     * A String extra indicating the security type of the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID} and could be one of {@code NONE}, {@code WPA} or
     * {@code WEP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_SECURITY_TYPE
        = "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE";

    /**
     * A String extra holding the password of the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PASSWORD
        = "android.app.extra.PROVISIONING_WIFI_PASSWORD";

    /**
     * A String extra holding the proxy host for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_HOST
        = "android.app.extra.PROVISIONING_WIFI_PROXY_HOST";

    /**
     * An int extra holding the proxy port for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_PORT
        = "android.app.extra.PROVISIONING_WIFI_PROXY_PORT";

    /**
     * A String extra holding the proxy bypass for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_BYPASS
        = "android.app.extra.PROVISIONING_WIFI_PROXY_BYPASS";

    /**
     * A String extra holding the proxy auto-config (PAC) URL for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PAC_URL
        = "android.app.extra.PROVISIONING_WIFI_PAC_URL";

    /**
     * A String extra holding a url that specifies the download location of the device admin
     * package. When not provided it is assumed that the device admin package is already installed.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION";

    /**
     * An int extra holding a minimum required version code for the device admin package. If the
     * device admin is already installed on the device, it will only be re-downloaded from
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION} if the version of the
     * installed package is less than this version code.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE";

    /**
     * A String extra holding a http cookie header which should be used in the http request to the
     * url specified in {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER";

    /**
     * A String extra holding the URL-safe base64 encoded SHA-256 or SHA-1 hash (see notes below) of
     * the file at download location specified in
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>Either this extra or {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM} must be
     * present. The provided checksum must match the checksum of the file at the download
     * location. If the checksum doesn't match an error will be shown to the user and the user will
     * be asked to factory reset the device.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     *
     * <p><strong>Note:</strong> for devices running {@link android.os.Build.VERSION_CODES#LOLLIPOP}
     * and {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1} only SHA-1 hash is supported.
     * Starting from {@link android.os.Build.VERSION_CODES#M}, this parameter accepts SHA-256 in
     * addition to SHA-1. Support for SHA-1 is likely to be removed in future OS releases.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM";

    /**
     * A String extra holding the URL-safe base64 encoded SHA-256 checksum of any signature of the
     * android package archive at the download location specified in {@link
     * #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>The signatures of an android package archive can be obtained using
     * {@link android.content.pm.PackageManager#getPackageArchiveInfo} with flag
     * {@link android.content.pm.PackageManager#GET_SIGNATURES}.
     *
     * <p>Either this extra or {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM} must be
     * present. The provided checksum must match the checksum of any signature of the file at
     * the download location. If the checksum does not match an error will be shown to the user and
     * the user will be asked to factory reset the device.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM";

    /**
     * Broadcast Action: This broadcast is sent to indicate that provisioning of a managed profile
     * has completed successfully.
     *
     * <p>The broadcast is limited to the primary profile, to the app specified in the provisioning
     * intent with action {@link #ACTION_PROVISION_MANAGED_PROFILE}.
     *
     * <p>This intent will contain the extra {@link #EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE} which
     * corresponds to the account requested to be migrated at provisioning time, if any.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGED_PROFILE_PROVISIONED
        = "android.app.action.MANAGED_PROFILE_PROVISIONED";

    /**
     * A boolean extra indicating whether device encryption can be skipped as part of device owner
     * or managed profile provisioning.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} or an intent with action
     * {@link #ACTION_PROVISION_MANAGED_DEVICE} that starts device owner provisioning.
     *
     * <p>From {@link android.os.Build.VERSION_CODES#N} onwards, this is also supported for an
     * intent with action {@link #ACTION_PROVISION_MANAGED_PROFILE}.
     */
    public static final String EXTRA_PROVISIONING_SKIP_ENCRYPTION =
             "android.app.extra.PROVISIONING_SKIP_ENCRYPTION";

    /**
     * A {@link Uri} extra pointing to a logo image. This image will be shown during the
     * provisioning. If this extra is not passed, a default image will be shown.
     * <h5>The following URI schemes are accepted:</h5>
     * <ul>
     * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
     * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})</li>
     * </ul>
     *
     * <p> It is the responsability of the caller to provide an image with a reasonable
     * pixed density for the device.
     *
     * <p> If a content: URI is passed, the intent should have the flag
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} and the uri should be added to the
     * {@link android.content.ClipData} of the intent too.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_PROFILE} or
     * {@link #ACTION_PROVISION_MANAGED_DEVICE}
     */
    public static final String EXTRA_PROVISIONING_LOGO_URI =
            "android.app.extra.PROVISIONING_LOGO_URI";

    /**
     * A boolean extra indicating if user setup should be skipped, for when provisioning is started
     * during setup-wizard.
     *
     * <p>If unspecified, defaults to {@code true} to match the behavior in
     * {@link android.os.Build.VERSION_CODES#M} and earlier.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE} or
     * {@link #ACTION_PROVISION_MANAGED_USER}.
     *
     * @hide
     */
    public static final String EXTRA_PROVISIONING_SKIP_USER_SETUP =
            "android.app.extra.PROVISIONING_SKIP_USER_SETUP";

    /**
     * This MIME type is used for starting the device owner provisioning.
     *
     * <p>During device owner provisioning a device admin app is set as the owner of the device.
     * A device owner has full control over the device. The device owner can not be modified by the
     * user and the only way of resetting the device is if the device owner app calls a factory
     * reset.
     *
     * <p> A typical use case would be a device that is owned by a company, but used by either an
     * employee or client.
     *
     * <p> The NFC message must be sent to an unprovisioned device.
     *
     * <p>The NFC record must contain a serialized {@link java.util.Properties} object which
     * contains the following properties:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME}</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM}, optional</li>
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
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PAC_URL}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional, supported from
     * {@link android.os.Build.VERSION_CODES#M} </li></ul>
     *
     * <p>
     * As of {@link android.os.Build.VERSION_CODES#M}, the properties should contain
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME} instead of
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME}, (although specifying only
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME} is still supported).
     */
    public static final String MIME_TYPE_PROVISIONING_NFC
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
     * for this user. Only system apps can launch this intent.
     *
     * <p>The ComponentName of the profile owner admin is passed in the {@link #EXTRA_DEVICE_ADMIN}
     * extra field. This will invoke a UI to bring the user through adding the profile owner admin
     * to remotely control restrictions on the user.
     *
     * <p>The intent must be invoked via {@link Activity#startActivityForResult} to receive the
     * result of whether or not the user approved the action. If approved, the result will
     * be {@link Activity#RESULT_OK} and the component will be set as an active admin as well
     * as a profile owner.
     *
     * <p>You can optionally include the {@link #EXTRA_ADD_EXPLANATION}
     * field to provide the user with additional explanation (in addition
     * to your component's description) about what is being added.
     *
     * <p>If there is already a profile owner active or the caller is not a system app, the
     * operation will return a failure result.
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
     * Broadcast action: send when any policy admin changes a policy.
     * This is generally used to find out when a new policy is in effect.
     *
     * @hide
     */
    public static final String ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
            = "android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED";

    /**
     * Broadcast action: sent when the device owner is set or changed.
     *
     * This broadcast is sent only to the primary user.
     * @see #ACTION_PROVISION_MANAGED_DEVICE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_OWNER_CHANGED
            = "android.app.action.DEVICE_OWNER_CHANGED";

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
     *
     * If the intent is launched from within a managed profile with a profile
     * owner built against {@link android.os.Build.VERSION_CODES#M} or before,
     * this will trigger entering a new password for the parent of the profile.
     * For all other cases it will trigger entering a new password for the user
     * or profile it is launched from.
     *
     * @see #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SET_NEW_PASSWORD
            = "android.app.action.SET_NEW_PASSWORD";

    /**
     * Activity action: have the user enter a new password for the parent profile.
     * If the intent is launched from within a managed profile, this will trigger
     * entering a new password for the parent of the profile. In all other cases
     * the behaviour is identical to {@link #ACTION_SET_NEW_PASSWORD}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SET_NEW_PARENT_PROFILE_PASSWORD
            = "android.app.action.SET_NEW_PARENT_PROFILE_PASSWORD";

    /**
     * Flag used by {@link #addCrossProfileIntentFilter} to allow activities in
     * the parent profile to access intents sent from the managed profile.
     * That is, when an app in the managed profile calls
     * {@link Activity#startActivity(Intent)}, the intent can be resolved by a
     * matching activity in the parent profile.
     */
    public static final int FLAG_PARENT_CAN_ACCESS_MANAGED = 0x0001;

    /**
     * Flag used by {@link #addCrossProfileIntentFilter} to allow activities in
     * the managed profile to access intents sent from the parent profile.
     * That is, when an app in the parent profile calls
     * {@link Activity#startActivity(Intent)}, the intent can be resolved by a
     * matching activity in the managed profile.
     */
    public static final int FLAG_MANAGED_CAN_ACCESS_PARENT = 0x0002;

    /**
     * Broadcast action: notify that a new local system update policy has been set by the device
     * owner. The new policy can be retrieved by {@link #getSystemUpdatePolicy()}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SYSTEM_UPDATE_POLICY_CHANGED
            = "android.app.action.SYSTEM_UPDATE_POLICY_CHANGED";

    /**
     * Permission policy to prompt user for new permission requests for runtime permissions.
     * Already granted or denied permissions are not affected by this.
     */
    public static final int PERMISSION_POLICY_PROMPT = 0;

    /**
     * Permission policy to always grant new permission requests for runtime permissions.
     * Already granted or denied permissions are not affected by this.
     */
    public static final int PERMISSION_POLICY_AUTO_GRANT = 1;

    /**
     * Permission policy to always deny new permission requests for runtime permissions.
     * Already granted or denied permissions are not affected by this.
     */
    public static final int PERMISSION_POLICY_AUTO_DENY = 2;

    /**
     * Runtime permission state: The user can manage the permission
     * through the UI.
     */
    public static final int PERMISSION_GRANT_STATE_DEFAULT = 0;

    /**
     * Runtime permission state: The permission is granted to the app
     * and the user cannot manage the permission through the UI.
     */
    public static final int PERMISSION_GRANT_STATE_GRANTED = 1;

    /**
     * Runtime permission state: The permission is denied to the app
     * and the user cannot manage the permission through the UI.
     */
    public static final int PERMISSION_GRANT_STATE_DENIED = 2;

    /**
     * No management for current user in-effect. This is the default.
     * @hide
     */
    @SystemApi
    public static final int STATE_USER_UNMANAGED = 0;

    /**
     * Management partially setup, user setup needs to be completed.
     * @hide
     */
    @SystemApi
    public static final int STATE_USER_SETUP_INCOMPLETE = 1;

    /**
     * Management partially setup, user setup completed.
     * @hide
     */
    @SystemApi
    public static final int STATE_USER_SETUP_COMPLETE = 2;

    /**
     * Management setup and active on current user.
     * @hide
     */
    @SystemApi
    public static final int STATE_USER_SETUP_FINALIZED = 3;

    /**
     * Management partially setup on a managed profile.
     * @hide
     */
    @SystemApi
    public static final int STATE_USER_PROFILE_COMPLETE = 4;

    /**
     * @hide
     */
    @IntDef({STATE_USER_UNMANAGED, STATE_USER_SETUP_INCOMPLETE, STATE_USER_SETUP_COMPLETE,
            STATE_USER_SETUP_FINALIZED, STATE_USER_PROFILE_COMPLETE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface UserProvisioningState {}

    /**
     * Return true if the given administrator component is currently active (enabled) in the system.
     *
     * @param admin The administrator component to check for.
     * @return {@code true} if {@code admin} is currently enabled in the system, {@code false}
     *         otherwise
     */
    public boolean isAdminActive(@NonNull ComponentName admin) {
        return isAdminActiveAsUser(admin, myUserId());
    }

    /**
     * @see #isAdminActive(ComponentName)
     * @hide
     */
    public boolean isAdminActiveAsUser(@NonNull ComponentName admin, int userId) {
        if (mService != null) {
            try {
                return mService.isAdminActive(admin, userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }
    /**
     * Return true if the given administrator component is currently being removed
     * for the user.
     * @hide
     */
    public boolean isRemovingAdmin(@NonNull ComponentName admin, int userId) {
        if (mService != null) {
            try {
                return mService.isRemovingAdmin(admin, userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }


    /**
     * Return a list of all currently active device administrators' component
     * names.  If there are no administrators {@code null} may be
     * returned.
     */
    public List<ComponentName> getActiveAdmins() {
        throwIfParentInstance("getActiveAdmins");
        return getActiveAdminsAsUser(myUserId());
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
                throw e.rethrowFromSystemServer();
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
        return packageHasActiveAdmins(packageName, myUserId());
    }

    /**
     * Used by package administration code to determine if a package can be stopped
     * or uninstalled.
     * @hide
     */
    public boolean packageHasActiveAdmins(String packageName, int userId) {
        if (mService != null) {
            try {
                return mService.packageHasActiveAdmins(packageName, userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Remove a current administration component.  This can only be called
     * by the application that owns the administration component; if you
     * try to remove someone else's component, a security exception will be
     * thrown.
     *
     * <p>Note that the operation is not synchronous and the admin might still be active (as
     * indicated by {@link #getActiveAdmins()}) by the time this method returns.
     *
     * @param admin The administration compononent to remove.
     * @throws SecurityException if the caller is not in the owner application of {@code admin}.
     */
    public void removeActiveAdmin(@NonNull ComponentName admin) {
        throwIfParentInstance("removeActiveAdmin");
        if (mService != null) {
            try {
                mService.removeActiveAdmin(admin, myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns true if an administrator has been granted a particular device policy. This can be
     * used to check whether the administrator was activated under an earlier set of policies, but
     * requires additional policies after an upgrade.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Must be an
     *            active administrator, or an exception will be thrown.
     * @param usesPolicy Which uses-policy to check, as defined in {@link DeviceAdminInfo}.
     * @throws SecurityException if {@code admin} is not an active administrator.
     */
    public boolean hasGrantedPolicy(@NonNull ComponentName admin, int usesPolicy) {
        throwIfParentInstance("hasGrantedPolicy");
        if (mService != null) {
            try {
                return mService.hasGrantedPolicy(admin, usesPolicy, myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns true if the Profile Challenge is available to use for the given profile user.
     *
     * @hide
     */
    public boolean isSeparateProfileChallengeAllowed(int userHandle) {
        if (mService != null) {
            try {
                return mService.isSeparateProfileChallengeAllowed(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
     * of password or pattern, but doesn't care what it is. Note that quality constants
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
     * Constant for {@link #setPasswordQuality}: the user is not allowed to
     * modify password. In case this password quality is set, the password is
     * managed by a profile owner. The profile owner can set any password,
     * as if {@link #PASSWORD_QUALITY_UNSPECIFIED} is used. Note
     * that quality constants are ordered so that higher values are more
     * restrictive. The value of {@link #PASSWORD_QUALITY_MANAGED} is
     * the highest.
     * @hide
     */
    public static final int PASSWORD_QUALITY_MANAGED = 0x80000;

    /**
     * Called by an application that is administering the device to set the password restrictions it
     * is imposing. After setting this, the user will not be able to enter a new password that is
     * not at least as restrictive as what has been set. Note that the current password will remain
     * until the user has set a new one, so the change does not take place immediately. To prompt
     * the user for a new password, use {@link #ACTION_SET_NEW_PASSWORD} or
     * {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after calling this method.
     * <p>
     * Quality constants are ordered so that higher values are more restrictive; thus the highest
     * requested quality constant (between the policy set here, the user's preference, and any other
     * considerations) is the one that is in effect.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param quality The new desired quality. One of {@link #PASSWORD_QUALITY_UNSPECIFIED},
     *            {@link #PASSWORD_QUALITY_SOMETHING}, {@link #PASSWORD_QUALITY_NUMERIC},
     *            {@link #PASSWORD_QUALITY_NUMERIC_COMPLEX}, {@link #PASSWORD_QUALITY_ALPHABETIC},
     *            {@link #PASSWORD_QUALITY_ALPHANUMERIC} or {@link #PASSWORD_QUALITY_COMPLEX}.
     * @throws SecurityException if {@code admin} is not an active administrator or if {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     */
    public void setPasswordQuality(@NonNull ComponentName admin, int quality) {
        if (mService != null) {
            try {
                mService.setPasswordQuality(admin, quality, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current minimum password quality for a particular admin or all admins that set
     * restrictions on this user and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     */
    public int getPasswordQuality(@Nullable ComponentName admin) {
        return getPasswordQuality(admin, myUserId());
    }

    /** @hide per-user version */
    public int getPasswordQuality(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordQuality(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return PASSWORD_QUALITY_UNSPECIFIED;
    }

    /**
     * Called by an application that is administering the device to set the minimum allowed password
     * length. After setting this, the user will not be able to enter a new password that is not at
     * least as restrictive as what has been set. Note that the current password will remain until
     * the user has set a new one, so the change does not take place immediately. To prompt the user
     * for a new password, use {@link #ACTION_SET_NEW_PASSWORD} or
     * {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after setting this value. This constraint is
     * only imposed if the administrator has also requested either {@link #PASSWORD_QUALITY_NUMERIC}
     * , {@link #PASSWORD_QUALITY_NUMERIC_COMPLEX}, {@link #PASSWORD_QUALITY_ALPHABETIC},
     * {@link #PASSWORD_QUALITY_ALPHANUMERIC}, or {@link #PASSWORD_QUALITY_COMPLEX} with
     * {@link #setPasswordQuality}.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum password length. A value of 0 means there is no
     *            restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     */
    public void setPasswordMinimumLength(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLength(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current minimum password length for a particular admin or all admins that set
     * restrictions on this user and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * user and its profiles or a particular one.
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     */
    public int getPasswordMinimumLength(@Nullable ComponentName admin) {
        return getPasswordMinimumLength(admin, myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumLength(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLength(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the minimum number of upper
     * case letters required in the password. After setting this, the user will not be able to enter
     * a new password that is not at least as restrictive as what has been set. Note that the
     * current password will remain until the user has set a new one, so the change does not take
     * place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} or {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after
     * setting this value. This constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The default value is 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum number of upper case letters required in the password.
     *            A value of 0 means there is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     */
    public void setPasswordMinimumUpperCase(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumUpperCase(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current number of upper case letters required in the password
     * for a particular admin or all admins that set restrictions on this user and
     * its participating profiles. Restrictions on profiles that have a separate challenge
     * are not taken into account.
     * This is the same value as set by
     * {@link #setPasswordMinimumUpperCase(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of upper case letters required in the
     *         password.
     */
    public int getPasswordMinimumUpperCase(@Nullable ComponentName admin) {
        return getPasswordMinimumUpperCase(admin, myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumUpperCase(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumUpperCase(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the minimum number of lower
     * case letters required in the password. After setting this, the user will not be able to enter
     * a new password that is not at least as restrictive as what has been set. Note that the
     * current password will remain until the user has set a new one, so the change does not take
     * place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} or {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after
     * setting this value. This constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The default value is 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum number of lower case letters required in the password.
     *            A value of 0 means there is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     */
    public void setPasswordMinimumLowerCase(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLowerCase(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current number of lower case letters required in the password
     * for a particular admin or all admins that set restrictions on this user
     * and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     * This is the same value as set by
     * {@link #setPasswordMinimumLowerCase(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of lower case letters required in the
     *         password.
     */
    public int getPasswordMinimumLowerCase(@Nullable ComponentName admin) {
        return getPasswordMinimumLowerCase(admin, myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumLowerCase(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLowerCase(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the minimum number of
     * letters required in the password. After setting this, the user will not be able to enter a
     * new password that is not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does not take place
     * immediately. To prompt the user for a new password, use {@link #ACTION_SET_NEW_PASSWORD} or
     * {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after setting this value. This constraint is
     * only imposed if the administrator has also requested {@link #PASSWORD_QUALITY_COMPLEX} with
     * {@link #setPasswordQuality}. The default value is 1.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum number of letters required in the password. A value of
     *            0 means there is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     */
    public void setPasswordMinimumLetters(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLetters(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current number of letters required in the password
     * for a particular admin or all admins that set restrictions on this user
     * and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     * This is the same value as set by
     * {@link #setPasswordMinimumLetters(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of letters required in the password.
     */
    public int getPasswordMinimumLetters(@Nullable ComponentName admin) {
        return getPasswordMinimumLetters(admin, myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumLetters(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLetters(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the minimum number of
     * numerical digits required in the password. After setting this, the user will not be able to
     * enter a new password that is not at least as restrictive as what has been set. Note that the
     * current password will remain until the user has set a new one, so the change does not take
     * place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} or {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after
     * setting this value. This constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The default value is 1.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum number of numerical digits required in the password. A
     *            value of 0 means there is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     */
    public void setPasswordMinimumNumeric(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumNumeric(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current number of numerical digits required in the password
     * for a particular admin or all admins that set restrictions on this user
     * and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     * This is the same value as set by
     * {@link #setPasswordMinimumNumeric(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of numerical digits required in the password.
     */
    public int getPasswordMinimumNumeric(@Nullable ComponentName admin) {
        return getPasswordMinimumNumeric(admin, myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumNumeric(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumNumeric(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the minimum number of
     * symbols required in the password. After setting this, the user will not be able to enter a
     * new password that is not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does not take place
     * immediately. To prompt the user for a new password, use {@link #ACTION_SET_NEW_PASSWORD} or
     * {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after setting this value. This constraint is
     * only imposed if the administrator has also requested {@link #PASSWORD_QUALITY_COMPLEX} with
     * {@link #setPasswordQuality}. The default value is 1.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum number of symbols required in the password. A value of
     *            0 means there is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     */
    public void setPasswordMinimumSymbols(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumSymbols(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current number of symbols required in the password
     * for a particular admin or all admins that set restrictions on this user
     * and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account. This is the same value as
     * set by {@link #setPasswordMinimumSymbols(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of symbols required in the password.
     */
    public int getPasswordMinimumSymbols(@Nullable ComponentName admin) {
        return getPasswordMinimumSymbols(admin, myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumSymbols(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumSymbols(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the minimum number of
     * non-letter characters (numerical digits or symbols) required in the password. After setting
     * this, the user will not be able to enter a new password that is not at least as restrictive
     * as what has been set. Note that the current password will remain until the user has set a new
     * one, so the change does not take place immediately. To prompt the user for a new password,
     * use {@link #ACTION_SET_NEW_PASSWORD} or {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after
     * setting this value. This constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The default value is 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum number of letters required in the password. A value of
     *            0 means there is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     */
    public void setPasswordMinimumNonLetter(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumNonLetter(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current number of non-letter characters required in the password
     * for a particular admin or all admins that set restrictions on this user
     * and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     * This is the same value as set by
     * {@link #setPasswordMinimumNonLetter(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of letters required in the password.
     */
    public int getPasswordMinimumNonLetter(@Nullable ComponentName admin) {
        return getPasswordMinimumNonLetter(admin, myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumNonLetter(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumNonLetter(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the length of the password
     * history. After setting this, the user will not be able to enter a new password that is the
     * same as any password in the history. Note that the current password will remain until the
     * user has set a new one, so the change does not take place immediately. To prompt the user for
     * a new password, use {@link #ACTION_SET_NEW_PASSWORD} or
     * {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after setting this value. This constraint is
     * only imposed if the administrator has also requested either {@link #PASSWORD_QUALITY_NUMERIC}
     * , {@link #PASSWORD_QUALITY_NUMERIC_COMPLEX} {@link #PASSWORD_QUALITY_ALPHABETIC}, or
     * {@link #PASSWORD_QUALITY_ALPHANUMERIC} with {@link #setPasswordQuality}.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired length of password history. A value of 0 means there is no
     *            restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     */
    public void setPasswordHistoryLength(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordHistoryLength(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device admin to set the password expiration timeout. Calling this method will
     * restart the countdown for password expiration for the given admin, as will changing the
     * device password (for all admins).
     * <p>
     * The provided timeout is the time delta in ms and will be added to the current time. For
     * example, to have the password expire 5 days from now, timeout would be 5 * 86400 * 1000 =
     * 432000000 ms for timeout.
     * <p>
     * To disable password expiration, a value of 0 may be used for timeout.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_EXPIRE_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * Note that setting the password will automatically reset the expiration time for all active
     * admins. Active admins do not need to explicitly call this method in that case.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param timeout The limit (in ms) that a password can remain in effect. A value of 0 means
     *            there is no restriction (unlimited).
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_EXPIRE_PASSWORD}
     */
    public void setPasswordExpirationTimeout(@NonNull ComponentName admin, long timeout) {
        if (mService != null) {
            try {
                mService.setPasswordExpirationTimeout(admin, timeout, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Get the password expiration timeout for the given admin. The expiration timeout is the
     * recurring expiration timeout provided in the call to
     * {@link #setPasswordExpirationTimeout(ComponentName, long)} for the given admin or the
     * aggregate of all participating policy administrators if {@code admin} is null. Admins that
     * have set restrictions on profiles that have a separate challenge are not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate all admins.
     * @return The timeout for the given admin or the minimum of all timeouts
     */
    public long getPasswordExpirationTimeout(@Nullable ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPasswordExpirationTimeout(admin, myUserId(), mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Get the current password expiration time for a particular admin or all admins that set
     * restrictions on this user and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account. If admin is {@code null}, then a composite
     * of all expiration times is returned - which will be the minimum of all of them.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * the password expiration for the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate all admins.
     * @return The password expiration time, in milliseconds since epoch.
     */
    public long getPasswordExpiration(@Nullable ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPasswordExpiration(admin, myUserId(), mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Retrieve the current password history length for a particular admin or all admins that
     * set restrictions on this user and its participating profiles. Restrictions on profiles that
     * have a separate challenge are not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     * @return The length of the password history
     */
    public int getPasswordHistoryLength(@Nullable ComponentName admin) {
        return getPasswordHistoryLength(admin, myUserId());
    }

    /** @hide per-user version */
    public int getPasswordHistoryLength(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordHistoryLength(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
     * Determine whether the current password the user has set is sufficient to meet the policy
     * requirements (e.g. quality, minimum length) that have been requested by the admins of this
     * user and its participating profiles. Restrictions on profiles that have a separate challenge
     * are not taken into account.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to determine if the password set on
     * the parent profile is sufficient.
     *
     * @return Returns true if the password meets the current requirements, else false.
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     */
    public boolean isActivePasswordSufficient() {
        if (mService != null) {
            try {
                return mService.isActivePasswordSufficient(myUserId(), mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Determine whether the current profile password the user has set is sufficient
     * to meet the policy requirements (e.g. quality, minimum length) that have been
     * requested by the admins of the parent user and its profiles.
     *
     * @param userHandle the userId of the profile to check the password for.
     * @return Returns true if the password would meet the current requirements, else false.
     * @throws SecurityException if {@code userHandle} is not a managed profile.
     * @hide
     */
    public boolean isProfileActivePasswordSufficientForParent(int userHandle) {
        if (mService != null) {
            try {
                return mService.isProfileActivePasswordSufficientForParent(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Retrieve the number of times the user has failed at entering a password since that last
     * successful password entry.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to retrieve the number of failed
     * password attemts for the parent user.
     * <p>
     * The calling device admin must have requested {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN}
     * to be able to call this method; if it has not, a security exception will be thrown.
     *
     * @return The number of times user has entered an incorrect password since the last correct
     *         password entry.
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN}
     */
    public int getCurrentFailedPasswordAttempts() {
        return getCurrentFailedPasswordAttempts(myUserId());
    }

    /**
     * Retrieve the number of times the given user has failed at entering a
     * password since that last successful password entry.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} to be able to call this method; if it has
     * not and it is not the system uid, a security exception will be thrown.
     *
     * @hide
     */
    public int getCurrentFailedPasswordAttempts(int userHandle) {
        if (mService != null) {
            try {
                return mService.getCurrentFailedPasswordAttempts(userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return -1;
    }

    /**
     * Queries whether {@link #RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT} flag is set.
     *
     * @return true if RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT flag is set.
     * @hide
     */
    public boolean getDoNotAskCredentialsOnBoot() {
        if (mService != null) {
            try {
                return mService.getDoNotAskCredentialsOnBoot();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Setting this to a value greater than zero enables a built-in policy that will perform a
     * device or profile wipe after too many incorrect device-unlock passwords have been entered.
     * This built-in policy combines watching for failed passwords and wiping the device, and
     * requires that you request both {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} and
     * {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA}}.
     * <p>
     * To implement any other policy (e.g. wiping data for a particular application only, erasing or
     * revoking credentials, or reporting the failure to a server), you should implement
     * {@link DeviceAdminReceiver#onPasswordFailed(Context, android.content.Intent)} instead. Do not
     * use this API, because if the maximum count is reached, the device or profile will be wiped
     * immediately, and your callback will not be invoked.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set a value on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param num The number of failed password attempts at which point the device or profile will
     *            be wiped.
     * @throws SecurityException if {@code admin} is not an active administrator or does not use
     *             both {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} and
     *             {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA}.
     */
    public void setMaximumFailedPasswordsForWipe(@NonNull ComponentName admin, int num) {
        if (mService != null) {
            try {
                mService.setMaximumFailedPasswordsForWipe(admin, num, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current maximum number of login attempts that are allowed before the device
     * or profile is wiped, for a particular admin or all admins that set restrictions on this user
     * and its participating profiles. Restrictions on profiles that have a separate challenge are
     * not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * the value for the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     */
    public int getMaximumFailedPasswordsForWipe(@Nullable ComponentName admin) {
        return getMaximumFailedPasswordsForWipe(admin, myUserId());
    }

    /** @hide per-user version */
    public int getMaximumFailedPasswordsForWipe(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getMaximumFailedPasswordsForWipe(
                        admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Returns the profile with the smallest maximum failed passwords for wipe,
     * for the given user. So for primary user, it might return the primary or
     * a managed profile. For a secondary user, it would be the same as the
     * user passed in.
     * @hide Used only by Keyguard
     */
    public int getProfileWithMinimumFailedPasswordsForWipe(int userHandle) {
        if (mService != null) {
            try {
                return mService.getProfileWithMinimumFailedPasswordsForWipe(
                        userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return UserHandle.USER_NULL;
    }

    /**
     * Flag for {@link #resetPassword}: don't allow other admins to change
     * the password again until the user has entered it.
     */
    public static final int RESET_PASSWORD_REQUIRE_ENTRY = 0x0001;

    /**
     * Flag for {@link #resetPassword}: don't ask for user credentials on device boot.
     * If the flag is set, the device can be booted without asking for user password.
     * The absence of this flag does not change the current boot requirements. This flag
     * can be set by the device owner only. If the app is not the device owner, the flag
     * is ignored. Once the flag is set, it cannot be reverted back without resetting the
     * device to factory defaults.
     */
    public static final int RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT = 0x0002;

    /**
     * Force a new device unlock password (the password needed to access the entire device, not for
     * individual accounts) on the user. This takes effect immediately.
     * <p>
     * <em>Note: This API has been limited as of {@link android.os.Build.VERSION_CODES#N} for
     * device admins that are not device owner and not profile owner.
     * The password can now only be changed if there is currently no password set.  Device owner
     * and profile owner can still do this when user is unlocked and does not have a managed
     * profile.</em>
     * <p>
     * The given password must be sufficient for the current password quality and length constraints
     * as returned by {@link #getPasswordQuality(ComponentName)} and
     * {@link #getPasswordMinimumLength(ComponentName)}; if it does not meet these constraints, then
     * it will be rejected and false returned. Note that the password may be a stronger quality
     * (containing alphanumeric characters when the requested quality is only numeric), in which
     * case the currently active quality will be increased to match.
     * <p>
     * Calling with a null or empty password will clear any existing PIN, pattern or password if the
     * current password constraints allow it. <em>Note: This will not work in
     * {@link android.os.Build.VERSION_CODES#N} and later for managed profiles, or for device admins
     * that are not device owner or profile owner.  Once set, the password cannot be changed to null
     * or empty except by these admins.</em>
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_RESET_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     *
     * @param password The new password for the user. Null or empty clears the password.
     * @param flags May be 0 or combination of {@link #RESET_PASSWORD_REQUIRE_ENTRY} and
     *            {@link #RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT}.
     * @return Returns true if the password was applied, or false if it is not acceptable for the
     *         current constraints or if the user has not been decrypted yet.
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_RESET_PASSWORD}
     * @throws IllegalStateException if the calling user is locked or has a managed profile.
     */
    public boolean resetPassword(String password, int flags) {
        throwIfParentInstance("resetPassword");
        if (mService != null) {
            try {
                return mService.resetPassword(password, flags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to set the maximum time for user
     * activity until the device will lock. This limits the length that the user can set. It takes
     * effect immediately.
     * <p>
     * The calling device admin must have requested {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK}
     * to be able to call this method; if it has not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param timeMs The new desired maximum time to lock in milliseconds. A value of 0 means there
     *            is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or it does not use
     *             {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK}
     */
    public void setMaximumTimeToLock(@NonNull ComponentName admin, long timeMs) {
        if (mService != null) {
            try {
                mService.setMaximumTimeToLock(admin, timeMs, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current maximum time to unlock for a particular admin or all admins that set
     * restrictions on this user and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     * @return time in milliseconds for the given admin or the minimum value (strictest) of
     * all admins if admin is null. Returns 0 if there are no restrictions.
     */
    public long getMaximumTimeToLock(@Nullable ComponentName admin) {
        return getMaximumTimeToLock(admin, myUserId());
    }

    /** @hide per-user version */
    public long getMaximumTimeToLock(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getMaximumTimeToLock(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Returns maximum time to lock that applied by all profiles in this user. We do this because we
     * do not have a separate timeout to lock for work challenge only.
     *
     * @hide
     */
    public long getMaximumTimeToLockForUserAndProfiles(int userHandle) {
        if (mService != null) {
            try {
                return mService.getMaximumTimeToLockForUserAndProfiles(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Make the device lock immediately, as if the lock screen timeout has expired at the point of
     * this call.
     * <p>
     * The calling device admin must have requested {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK}
     * to be able to call this method; if it has not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to lock the parent profile.
     *
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK}
     */
    public void lockNow() {
        if (mService != null) {
            try {
                mService.lockNow(mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Flag for {@link #wipeData(int)}: also erase the device's external
     * storage (such as SD cards).
     */
    public static final int WIPE_EXTERNAL_STORAGE = 0x0001;

    /**
     * Flag for {@link #wipeData(int)}: also erase the factory reset protection
     * data.
     *
     * <p>This flag may only be set by device owner admins; if it is set by
     * other admins a {@link SecurityException} will be thrown.
     */
    public static final int WIPE_RESET_PROTECTION_DATA = 0x0002;

    /**
     * Ask the user data be wiped. Wiping the primary user will cause the device to reboot, erasing
     * all user data while next booting up.
     * <p>
     * The calling device admin must have requested {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA} to
     * be able to call this method; if it has not, a security exception will be thrown.
     *
     * @param flags Bit mask of additional options: currently supported flags are
     *            {@link #WIPE_EXTERNAL_STORAGE} and {@link #WIPE_RESET_PROTECTION_DATA}.
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA}
     */
    public void wipeData(int flags) {
        throwIfParentInstance("wipeData");
        if (mService != null) {
            try {
                mService.wipeData(flags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
     * proxy will be returned. If successful in setting the proxy, {@code null} will
     * be returned.
     * The method can be called repeatedly by the device admin alrady setting the
     * proxy to update the proxy and exclusion list.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param proxySpec the global proxy desired. Must be an HTTP Proxy.
     *            Pass Proxy.NO_PROXY to reset the proxy.
     * @param exclusionList a list of domains to be excluded from the global proxy.
     * @return {@code null} if the proxy was successfully set, or otherwise a {@link ComponentName}
     *            of the device admin that sets the proxy.
     * @hide
     */
    public ComponentName setGlobalProxy(@NonNull ComponentName admin, Proxy proxySpec,
            List<String> exclusionList ) {
        throwIfParentInstance("setGlobalProxy");
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
                return mService.setGlobalProxy(admin, hostSpec, exclSpec);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Set a network-independent global HTTP proxy. This is not normally what you want for typical
     * HTTP proxies - they are generally network dependent. However if you're doing something
     * unusual like general internal filtering this may be useful. On a private network where the
     * proxy is not accessible, you may break HTTP using this.
     * <p>
     * This method requires the caller to be the device owner.
     * <p>
     * This proxy is only a recommendation and it is possible that some apps will ignore it.
     *
     * @see ProxyInfo
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param proxyInfo The a {@link ProxyInfo} object defining the new global HTTP proxy. A
     *            {@code null} value will clear the global HTTP proxy.
     * @throws SecurityException if {@code admin} is not the device owner.
     */
    public void setRecommendedGlobalProxy(@NonNull ComponentName admin, @Nullable ProxyInfo
            proxyInfo) {
        throwIfParentInstance("setRecommendedGlobalProxy");
        if (mService != null) {
            try {
                mService.setRecommendedGlobalProxy(admin, proxyInfo);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the component name setting the global proxy.
     * @return ComponentName object of the device admin that set the global proxy, or {@code null}
     *         if no admin has set the proxy.
     * @hide
     */
    public ComponentName getGlobalProxyAdmin() {
        if (mService != null) {
            try {
                return mService.getGlobalProxyAdmin(myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
     * Result code for {@link #getStorageEncryptionStatus}:
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
     * <p>
     * Also see {@link #ENCRYPTION_STATUS_ACTIVE_PER_USER}.
     */
    public static final int ENCRYPTION_STATUS_ACTIVE = 3;

    /**
     * Result code for {@link #getStorageEncryptionStatus}:
     * indicating that encryption is active, but an encryption key has not
     * been set by the user.
     */
    public static final int ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY = 4;

    /**
     * Result code for {@link #getStorageEncryptionStatus}:
     * indicating that encryption is active and the encryption key is tied to the user or profile.
     * <p>
     * This value is only returned to apps targeting API level 24 and above. For apps targeting
     * earlier API levels, {@link #ENCRYPTION_STATUS_ACTIVE} is returned, even if the
     * encryption key is specific to the user or profile.
     */
    public static final int ENCRYPTION_STATUS_ACTIVE_PER_USER = 5;

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
     * Disable text entry into notifications on secure keyguard screens (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_REMOTE_INPUT = 1 << 6;

    /**
     * Disable all current and future keyguard customizations.
     */
    public static final int KEYGUARD_DISABLE_FEATURES_ALL = 0x7fffffff;

    /**
     * Called by an application that is administering the device to request that the storage system
     * be encrypted.
     * <p>
     * When multiple device administrators attempt to control device encryption, the most secure,
     * supported setting will always be used. If any device administrator requests device
     * encryption, it will be enabled; Conversely, if a device administrator attempts to disable
     * device encryption while another device administrator has enabled it, the call to disable will
     * fail (most commonly returning {@link #ENCRYPTION_STATUS_ACTIVE}).
     * <p>
     * This policy controls encryption of the secure (application data) storage area. Data written
     * to other storage areas may or may not be encrypted, and this policy does not require or
     * control the encryption of any other storage areas. There is one exception: If
     * {@link android.os.Environment#isExternalStorageEmulated()} is {@code true}, then the
     * directory returned by {@link android.os.Environment#getExternalStorageDirectory()} must be
     * written to disk within the encrypted storage area.
     * <p>
     * Important Note: On some devices, it is possible to encrypt storage without requiring the user
     * to create a device PIN or Password. In this case, the storage is encrypted, but the
     * encryption key may not be fully secured. For maximum security, the administrator should also
     * require (and check for) a pattern, PIN, or password.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param encrypt true to request encryption, false to release any previous request
     * @return the new request status (for all active admins) - will be one of
     *         {@link #ENCRYPTION_STATUS_UNSUPPORTED}, {@link #ENCRYPTION_STATUS_INACTIVE}, or
     *         {@link #ENCRYPTION_STATUS_ACTIVE}. This is the value of the requests; Use
     *         {@link #getStorageEncryptionStatus()} to query the actual device state.
     * @throws SecurityException if {@code admin} is not an active administrator or does not use
     *             {@link DeviceAdminInfo#USES_ENCRYPTED_STORAGE}
     */
    public int setStorageEncryption(@NonNull ComponentName admin, boolean encrypt) {
        throwIfParentInstance("setStorageEncryption");
        if (mService != null) {
            try {
                return mService.setStorageEncryption(admin, encrypt);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
    public boolean getStorageEncryption(@Nullable ComponentName admin) {
        throwIfParentInstance("getStorageEncryption");
        if (mService != null) {
            try {
                return mService.getStorageEncryption(admin, myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to
     * determine the current encryption status of the device.
     * <p>
     * Depending on the returned status code, the caller may proceed in different
     * ways.  If the result is {@link #ENCRYPTION_STATUS_UNSUPPORTED}, the
     * storage system does not support encryption.  If the
     * result is {@link #ENCRYPTION_STATUS_INACTIVE}, use {@link
     * #ACTION_START_ENCRYPTION} to begin the process of encrypting or decrypting the
     * storage.  If the result is {@link #ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY}, the
     * storage system has enabled encryption but no password is set so further action
     * may be required.  If the result is {@link #ENCRYPTION_STATUS_ACTIVATING},
     * {@link #ENCRYPTION_STATUS_ACTIVE} or {@link #ENCRYPTION_STATUS_ACTIVE_PER_USER},
     * no further action is required.
     *
     * @return current status of encryption. The value will be one of
     * {@link #ENCRYPTION_STATUS_UNSUPPORTED}, {@link #ENCRYPTION_STATUS_INACTIVE},
     * {@link #ENCRYPTION_STATUS_ACTIVATING}, {@link #ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY},
     * {@link #ENCRYPTION_STATUS_ACTIVE}, or {@link #ENCRYPTION_STATUS_ACTIVE_PER_USER}.
     */
    public int getStorageEncryptionStatus() {
        throwIfParentInstance("getStorageEncryptionStatus");
        return getStorageEncryptionStatus(myUserId());
    }

    /** @hide per-user version */
    public int getStorageEncryptionStatus(int userHandle) {
        if (mService != null) {
            try {
                return mService.getStorageEncryptionStatus(mContext.getPackageName(), userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return ENCRYPTION_STATUS_UNSUPPORTED;
    }

    /**
     * Mark a CA certificate as approved by the device user. This means that they have been notified
     * of the installation, were made aware of the risks, viewed the certificate and still wanted to
     * keep the certificate on the device.
     *
     * Calling with {@param approval} as {@code true} will cancel any ongoing warnings related to
     * this certificate.
     *
     * @hide
     */
    public boolean approveCaCert(String alias, int userHandle, boolean approval) {
        if (mService != null) {
            try {
                return mService.approveCaCert(alias, userHandle, approval);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Check whether a CA certificate has been approved by the device user.
     *
     * @hide
     */
    public boolean isCaCertApproved(String alias, int userHandle) {
        if (mService != null) {
            try {
                return mService.isCaCertApproved(alias, userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Installs the given certificate as a user CA.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @param certBuffer encoded form of the certificate to install.
     *
     * @return false if the certBuffer cannot be parsed or installation is
     *         interrupted, true otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     */
    public boolean installCaCert(@Nullable ComponentName admin, byte[] certBuffer) {
        throwIfParentInstance("installCaCert");
        if (mService != null) {
            try {
                return mService.installCaCert(admin, certBuffer);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Uninstalls the given certificate from trusted user CAs, if present.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @param certBuffer encoded form of the certificate to remove.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     */
    public void uninstallCaCert(@Nullable ComponentName admin, byte[] certBuffer) {
        throwIfParentInstance("uninstallCaCert");
        if (mService != null) {
            try {
                final String alias = getCaCertAlias(certBuffer);
                mService.uninstallCaCerts(admin, new String[] {alias});
            } catch (CertificateException e) {
                Log.w(TAG, "Unable to parse certificate", e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns all CA certificates that are currently trusted, excluding system CA certificates.
     * If a user has installed any certificates by other means than device policy these will be
     * included too.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @return a List of byte[] arrays, each encoding one user CA certificate.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     */
    public List<byte[]> getInstalledCaCerts(@Nullable ComponentName admin) {
        List<byte[]> certs = new ArrayList<byte[]>();
        throwIfParentInstance("getInstalledCaCerts");
        if (mService != null) {
            try {
                mService.enforceCanManageCaCerts(admin);
                final TrustedCertificateStore certStore = new TrustedCertificateStore();
                for (String alias : certStore.userAliases()) {
                    try {
                        certs.add(certStore.getCertificate(alias).getEncoded());
                    } catch (CertificateException ce) {
                        Log.w(TAG, "Could not encode certificate: " + alias, ce);
                    }
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return certs;
    }

    /**
     * Uninstalls all custom trusted CA certificates from the profile. Certificates installed by
     * means other than device policy will also be removed, except for system CA certificates.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     */
    public void uninstallAllUserCaCerts(@Nullable ComponentName admin) {
        throwIfParentInstance("uninstallAllUserCaCerts");
        if (mService != null) {
            try {
                mService.uninstallCaCerts(admin, new TrustedCertificateStore().userAliases()
                        .toArray(new String[0]));
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns whether this certificate is installed as a trusted CA.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @param certBuffer encoded form of the certificate to look up.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     */
    public boolean hasCaCertInstalled(@Nullable ComponentName admin, byte[] certBuffer) {
        throwIfParentInstance("hasCaCertInstalled");
        if (mService != null) {
            try {
                mService.enforceCanManageCaCerts(admin);
                return getCaCertAlias(certBuffer) != null;
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            } catch (CertificateException ce) {
                Log.w(TAG, "Could not parse certificate", ce);
            }
        }
        return false;
    }

    /**
     * Called by a device or profile owner, or delegated certificate installer, to install a
     * certificate and corresponding private key. All apps within the profile will be able to access
     * the certificate and use the private key, given direct user approval.
     *
     * <p>Access to the installed credentials will not be granted to the caller of this API without
     * direct user approval. This is for security - should a certificate installer become
     * compromised, certificates it had already installed will be protected.
     *
     * <p>If the installer must have access to the credentials, call
     * {@link #installKeyPair(ComponentName, PrivateKey, Certificate[], String, boolean)} instead.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if calling from a delegated certificate installer.
     * @param privKey The private key to install.
     * @param cert The certificate to install.
     * @param alias The private key alias under which to install the certificate. If a certificate
     * with that alias already exists, it will be overwritten.
     * @return {@code true} if the keys were installed, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     */
    public boolean installKeyPair(@Nullable ComponentName admin, @NonNull PrivateKey privKey,
            @NonNull Certificate cert, @NonNull String alias) {
        return installKeyPair(admin, privKey, new Certificate[] {cert}, alias, false);
    }

    /**
     * Called by a device or profile owner, or delegated certificate installer, to install a
     * certificate chain and corresponding private key for the leaf certificate. All apps within the
     * profile will be able to access the certificate chain and use the private key, given direct
     * user approval.
     *
     * <p>The caller of this API may grant itself access to the certificate and private key
     * immediately, without user approval. It is a best practice not to request this unless strictly
     * necessary since it opens up additional security vulnerabilities.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if calling from a delegated certificate installer.
     * @param privKey The private key to install.
     * @param certs The certificate chain to install. The chain should start with the leaf
     *        certificate and include the chain of trust in order. This will be returned by
     *        {@link android.security.KeyChain#getCertificateChain}.
     * @param alias The private key alias under which to install the certificate. If a certificate
     *        with that alias already exists, it will be overwritten.
     * @param requestAccess {@code true} to request that the calling app be granted access to the
     *        credentials immediately. Otherwise, access to the credentials will be gated by user
     *        approval.
     * @return {@code true} if the keys were installed, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     * @see android.security.KeyChain#getCertificateChain
     */
    public boolean installKeyPair(@Nullable ComponentName admin, @NonNull PrivateKey privKey,
            @NonNull Certificate[] certs, @NonNull String alias, boolean requestAccess) {
        throwIfParentInstance("installKeyPair");
        try {
            final byte[] pemCert = Credentials.convertToPem(certs[0]);
            byte[] pemChain = null;
            if (certs.length > 1) {
                pemChain = Credentials.convertToPem(Arrays.copyOfRange(certs, 1, certs.length));
            }
            final byte[] pkcs8Key = KeyFactory.getInstance(privKey.getAlgorithm())
                    .getKeySpec(privKey, PKCS8EncodedKeySpec.class).getEncoded();
            return mService.installKeyPair(admin, pkcs8Key, pemCert, pemChain, alias,
                    requestAccess);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            Log.w(TAG, "Failed to obtain private key material", e);
        } catch (CertificateException | IOException e) {
            Log.w(TAG, "Could not pem-encode certificate", e);
        }
        return false;
    }

    /**
     * Called by a device or profile owner, or delegated certificate installer, to remove a
     * certificate and private key pair installed under a given alias.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if calling from a delegated certificate installer.
     * @param alias The private key alias under which the certificate is installed.
     * @return {@code true} if the private key alias no longer exists, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     */
    public boolean removeKeyPair(@Nullable ComponentName admin, @NonNull String alias) {
        throwIfParentInstance("removeKeyPair");
        try {
            return mService.removeKeyPair(admin, alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return the alias of a given CA certificate in the certificate store, or {@code null} if it
     * doesn't exist.
     */
    private static String getCaCertAlias(byte[] certBuffer) throws CertificateException {
        final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        final X509Certificate cert = (X509Certificate) certFactory.generateCertificate(
                              new ByteArrayInputStream(certBuffer));
        return new TrustedCertificateStore().getCertificateAlias(cert);
    }

    /**
     * Called by a profile owner or device owner to grant access to privileged certificate
     * manipulation APIs to a third-party certificate installer app. Granted APIs include
     * {@link #getInstalledCaCerts}, {@link #hasCaCertInstalled}, {@link #installCaCert},
     * {@link #uninstallCaCert}, {@link #uninstallAllUserCaCerts} and {@link #installKeyPair}.
     * <p>
     * Delegated certificate installer is a per-user state. The delegated access is persistent until
     * it is later cleared by calling this method with a null value or uninstallling the certificate
     * installer.
     * <p>
     * <b>Note:</b>Starting from {@link android.os.Build.VERSION_CODES#N}, if the caller
     * application's target SDK version is {@link android.os.Build.VERSION_CODES#N} or newer, the
     * supplied certificate installer package must be installed when calling this API, otherwise an
     * {@link IllegalArgumentException} will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param installerPackage The package name of the certificate installer which will be given
     *            access. If {@code null} is given the current package will be cleared.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     */
    public void setCertInstallerPackage(@NonNull ComponentName admin, @Nullable String
            installerPackage) throws SecurityException {
        throwIfParentInstance("setCertInstallerPackage");
        if (mService != null) {
            try {
                mService.setCertInstallerPackage(admin, installerPackage);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner or device owner to retrieve the certificate installer for the user.
     * null if none is set.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The package name of the current delegated certificate installer, or {@code null} if
     *         none is set.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     */
    public String getCertInstallerPackage(@NonNull ComponentName admin) throws SecurityException {
        throwIfParentInstance("getCertInstallerPackage");
        if (mService != null) {
            try {
                return mService.getCertInstallerPackage(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a device or profile owner to configure an always-on VPN connection through a
     * specific application for the current user.
     *
     * @deprecated this version only exists for compability with previous developer preview builds.
     *             TODO: delete once there are no longer any live references.
     * @hide
     */
    public void setAlwaysOnVpnPackage(@NonNull ComponentName admin, @Nullable String vpnPackage)
            throws NameNotFoundException, UnsupportedOperationException {
        setAlwaysOnVpnPackage(admin, vpnPackage, /* lockdownEnabled */ true);
    }

    /**
     * Called by a device or profile owner to configure an always-on VPN connection through a
     * specific application for the current user. This connection is automatically granted and
     * persisted after a reboot.
     * <p>
     * The designated package should declare a {@link android.net.VpnService} in its manifest
     * guarded by {@link android.Manifest.permission#BIND_VPN_SERVICE}, otherwise the call will
     * fail.
     *
     * @param vpnPackage The package name for an installed VPN app on the device, or {@code null} to
     *        remove an existing always-on VPN configuration.
     * @param lockdownEnabled {@code true} to disallow networking when the VPN is not connected or
     *        {@code false} otherwise. This carries the risk that any failure of the VPN provider
     *        could break networking for all apps. This has no effect when clearing.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     * @throws NameNotFoundException if {@code vpnPackage} is not installed.
     * @throws UnsupportedOperationException if {@code vpnPackage} exists but does not support being
     *         set as always-on, or if always-on VPN is not available.
     */
    public void setAlwaysOnVpnPackage(@NonNull ComponentName admin, @Nullable String vpnPackage,
            boolean lockdownEnabled)
            throws NameNotFoundException, UnsupportedOperationException {
        throwIfParentInstance("setAlwaysOnVpnPackage");
        if (mService != null) {
            try {
                if (!mService.setAlwaysOnVpnPackage(admin, vpnPackage, lockdownEnabled)) {
                    throw new NameNotFoundException(vpnPackage);
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device or profile owner to read the name of the package administering an
     * always-on VPN connection for the current user. If there is no such package, or the always-on
     * VPN is provided by the system instead of by an application, {@code null} will be returned.
     *
     * @return Package name of VPN controller responsible for always-on VPN, or {@code null} if none
     *         is set.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     */
    public String getAlwaysOnVpnPackage(@NonNull ComponentName admin) {
        throwIfParentInstance("getAlwaysOnVpnPackage");
        if (mService != null) {
            try {
                return mService.getAlwaysOnVpnPackage(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by an application that is administering the device to disable all cameras on the
     * device, for this user. After setting this, no applications running as this user will be able
     * to access any cameras on the device.
     * <p>
     * If the caller is device owner, then the restriction will be applied to all users.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_CAMERA} to be able to call this method; if it has
     * not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled Whether or not the camera should be disabled.
     * @throws SecurityException if {@code admin} is not an active administrator or does not use
     *             {@link DeviceAdminInfo#USES_POLICY_DISABLE_CAMERA}.
     */
    public void setCameraDisabled(@NonNull ComponentName admin, boolean disabled) {
        throwIfParentInstance("setCameraDisabled");
        if (mService != null) {
            try {
                mService.setCameraDisabled(admin, disabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Determine whether or not the device's cameras have been disabled for this user,
     * either by the calling admin, if specified, or all admins.
     * @param admin The name of the admin component to check, or {@code null} to check whether any admins
     * have disabled the camera
     */
    public boolean getCameraDisabled(@Nullable ComponentName admin) {
        throwIfParentInstance("getCameraDisabled");
        return getCameraDisabled(admin, myUserId());
    }

    /** @hide per-user version */
    public boolean getCameraDisabled(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getCameraDisabled(admin, userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner to request a bugreport.
     * <p>
     * There must be only one user on the device, managed by the device owner. Otherwise a
     * {@link SecurityException} will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return {@code true} if the bugreport collection started successfully, or {@code false} if it
     *         wasn't triggered because a previous bugreport operation is still active (either the
     *         bugreport is still running or waiting for the user to share or decline)
     * @throws SecurityException if {@code admin} is not a device owner, or if there are users other
     *             than the one managed by the device owner.
     */
    public boolean requestBugreport(@NonNull ComponentName admin) {
        throwIfParentInstance("requestBugreport");
        if (mService != null) {
            try {
                return mService.requestBugreport(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Determine whether or not creating a guest user has been disabled for the device
     *
     * @hide
     */
    public boolean getGuestUserDisabled(@Nullable ComponentName admin) {
        // Currently guest users can always be created if multi-user is enabled
        // TODO introduce a policy for guest user creation
        return false;
    }

    /**
     * Called by a device/profile owner to set whether the screen capture is disabled. Disabling
     * screen capture also prevents the content from being shown on display devices that do not have
     * a secure video output. See {@link android.view.Display#FLAG_SECURE} for more details about
     * secure surfaces and secure displays.
     * <p>
     * The calling device admin must be a device or profile owner. If it is not, a security
     * exception will be thrown.
     * <p>
     * From version {@link android.os.Build.VERSION_CODES#M} disabling screen capture also blocks
     * assist requests for all activities of the relevant user.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled Whether screen capture is disabled or not.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setScreenCaptureDisabled(@NonNull ComponentName admin, boolean disabled) {
        throwIfParentInstance("setScreenCaptureDisabled");
        if (mService != null) {
            try {
                mService.setScreenCaptureDisabled(admin, disabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Determine whether or not screen capture has been disabled by the calling
     * admin, if specified, or all admins.
     * @param admin The name of the admin component to check, or {@code null} to check whether any admins
     * have disabled screen capture.
     */
    public boolean getScreenCaptureDisabled(@Nullable ComponentName admin) {
        throwIfParentInstance("getScreenCaptureDisabled");
        return getScreenCaptureDisabled(admin, myUserId());
    }

    /** @hide per-user version */
    public boolean getScreenCaptureDisabled(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getScreenCaptureDisabled(admin, userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner to set whether auto time is required. If auto time is required the
     * user cannot set the date and time, but has to use network date and time.
     * <p>
     * Note: if auto time is required the user can still manually set the time zone.
     * <p>
     * The calling device admin must be a device owner. If it is not, a security exception will be
     * thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param required Whether auto time is set required or not.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setAutoTimeRequired(@NonNull ComponentName admin, boolean required) {
        throwIfParentInstance("setAutoTimeRequired");
        if (mService != null) {
            try {
                mService.setAutoTimeRequired(admin, required);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @return true if auto time is required.
     */
    public boolean getAutoTimeRequired() {
        throwIfParentInstance("getAutoTimeRequired");
        if (mService != null) {
            try {
                return mService.getAutoTimeRequired();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner to set whether all users created on the device should be ephemeral.
     * <p>
     * The system user is exempt from this policy - it is never ephemeral.
     * <p>
     * The calling device admin must be the device owner. If it is not, a security exception will be
     * thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param forceEphemeralUsers If true, all the existing users will be deleted and all
     *            subsequently created users will be ephemeral.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @hide
     */
    public void setForceEphemeralUsers(
            @NonNull ComponentName admin, boolean forceEphemeralUsers) {
        throwIfParentInstance("setForceEphemeralUsers");
        if (mService != null) {
            try {
                mService.setForceEphemeralUsers(admin, forceEphemeralUsers);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @return true if all users are created ephemeral.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @hide
     */
    public boolean getForceEphemeralUsers(@NonNull ComponentName admin) {
        throwIfParentInstance("getForceEphemeralUsers");
        if (mService != null) {
            try {
                return mService.getForceEphemeralUsers(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to disable keyguard customizations,
     * such as widgets. After setting this, keyguard features will be disabled according to the
     * provided feature list.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES} to be able to call this method;
     * if it has not, a security exception will be thrown.
     * <p>
     * Calling this from a managed profile before version {@link android.os.Build.VERSION_CODES#M}
     * will throw a security exception. From version {@link android.os.Build.VERSION_CODES#M} the
     * profile owner of a managed profile can set:
     * <ul>
     * <li>{@link #KEYGUARD_DISABLE_TRUST_AGENTS}, which affects the parent user, but only if there
     * is no separate challenge set on the managed profile.
     * <li>{@link #KEYGUARD_DISABLE_FINGERPRINT} which affects the managed profile challenge if
     * there is one, or the parent user otherwise.
     * <li>{@link #KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS} which affects notifications generated
     * by applications in the managed profile.
     * </ul>
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS} and {@link #KEYGUARD_DISABLE_FINGERPRINT} can also be
     * set on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     * <p>
     * Requests to disable other features on a managed profile will be ignored.
     * <p>
     * The admin can check which features have been disabled by calling
     * {@link #getKeyguardDisabledFeatures(ComponentName)}
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param which {@link #KEYGUARD_DISABLE_FEATURES_NONE} (default),
     *            {@link #KEYGUARD_DISABLE_WIDGETS_ALL}, {@link #KEYGUARD_DISABLE_SECURE_CAMERA},
     *            {@link #KEYGUARD_DISABLE_SECURE_NOTIFICATIONS},
     *            {@link #KEYGUARD_DISABLE_TRUST_AGENTS},
     *            {@link #KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS},
     *            {@link #KEYGUARD_DISABLE_FINGERPRINT}, {@link #KEYGUARD_DISABLE_FEATURES_ALL}
     * @throws SecurityException if {@code admin} is not an active administrator or does not user
     *             {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES}
     */
    public void setKeyguardDisabledFeatures(@NonNull ComponentName admin, int which) {
        if (mService != null) {
            try {
                mService.setKeyguardDisabledFeatures(admin, which, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Determine whether or not features have been disabled in keyguard either by the calling
     * admin, if specified, or all admins that set restrictions on this user and its participating
     * profiles. Restrictions on profiles that have a separate challenge are not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to check whether any
     * admins have disabled features in keyguard.
     * @return bitfield of flags. See {@link #setKeyguardDisabledFeatures(ComponentName, int)}
     * for a list.
     */
    public int getKeyguardDisabledFeatures(@Nullable ComponentName admin) {
        return getKeyguardDisabledFeatures(admin, myUserId());
    }

    /** @hide per-user version */
    public int getKeyguardDisabledFeatures(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getKeyguardDisabledFeatures(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return KEYGUARD_DISABLE_FEATURES_NONE;
    }

    /**
     * @hide
     */
    public void setActiveAdmin(@NonNull ComponentName policyReceiver, boolean refreshing,
            int userHandle) {
        if (mService != null) {
            try {
                mService.setActiveAdmin(policyReceiver, refreshing, userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    public void setActiveAdmin(@NonNull ComponentName policyReceiver, boolean refreshing) {
        setActiveAdmin(policyReceiver, refreshing, myUserId());
    }

    /**
     * @hide
     */
    public void getRemoveWarning(@Nullable ComponentName admin, RemoteCallback result) {
        if (mService != null) {
            try {
                mService.getRemoveWarning(admin, result, myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
                throw e.rethrowFromSystemServer();
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
                throw e.rethrowFromSystemServer();
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
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    public void reportFailedFingerprintAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportFailedFingerprintAttempt(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    public void reportSuccessfulFingerprintAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportSuccessfulFingerprintAttempt(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Should be called when keyguard has been dismissed.
     * @hide
     */
    public void reportKeyguardDismissed(int userHandle) {
        if (mService != null) {
            try {
                mService.reportKeyguardDismissed(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Should be called when keyguard view has been shown to the user.
     * @hide
     */
    public void reportKeyguardSecured(int userHandle) {
        if (mService != null) {
            try {
                mService.reportKeyguardSecured(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     * Sets the given package as the device owner.
     * Same as {@link #setDeviceOwner(ComponentName, String)} but without setting a device owner name.
     * @param who the component name to be registered as device owner.
     * @return whether the package was successfully registered as the device owner.
     * @throws IllegalArgumentException if the package name is null or invalid
     * @throws IllegalStateException If the preconditions mentioned are not met.
     */
    public boolean setDeviceOwner(ComponentName who) {
        return setDeviceOwner(who, null);
    }

    /**
     * @hide
     */
    public boolean setDeviceOwner(ComponentName who, int userId)  {
        return setDeviceOwner(who, null, userId);
    }

    /**
     * @hide
     */
    public boolean setDeviceOwner(ComponentName who, String ownerName) {
        return setDeviceOwner(who, ownerName, UserHandle.USER_SYSTEM);
    }

    /**
     * @hide
     * Sets the given package as the device owner. The package must already be installed. There
     * must not already be a device owner.
     * Only apps with the MANAGE_PROFILE_AND_DEVICE_OWNERS permission and the shell uid can call
     * this method.
     * Calling this after the setup phase of the primary user has completed is allowed only if
     * the caller is the shell uid, and there are no additional users and no accounts.
     * @param who the component name to be registered as device owner.
     * @param ownerName the human readable name of the institution that owns this device.
     * @param userId ID of the user on which the device owner runs.
     * @return whether the package was successfully registered as the device owner.
     * @throws IllegalArgumentException if the package name is null or invalid
     * @throws IllegalStateException If the preconditions mentioned are not met.
     */
    public boolean setDeviceOwner(ComponentName who, String ownerName, int userId)
            throws IllegalArgumentException, IllegalStateException {
        if (mService != null) {
            try {
                return mService.setDeviceOwner(who, ownerName, userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Used to determine if a particular package has been registered as a Device Owner app.
     * A device owner app is a special device admin that cannot be deactivated by the user, once
     * activated as a device admin. It also cannot be uninstalled. To check whether a particular
     * package is currently registered as the device owner app, pass in the package name from
     * {@link Context#getPackageName()} to this method.<p/>This is useful for device
     * admin apps that want to check whether they are also registered as the device owner app. The
     * exact mechanism by which a device admin app is registered as a device owner app is defined by
     * the setup process.
     * @param packageName the package name of the app, to compare with the registered device owner
     * app, if any.
     * @return whether or not the package is registered as the device owner app.
     */
    public boolean isDeviceOwnerApp(String packageName) {
        throwIfParentInstance("isDeviceOwnerApp");
        return isDeviceOwnerAppOnCallingUser(packageName);
    }

    /**
     * @return true if a package is registered as device owner, only when it's running on the
     * calling user.
     *
     * <p>Same as {@link #isDeviceOwnerApp}, but bundled code should use it for clarity.
     * @hide
     */
    public boolean isDeviceOwnerAppOnCallingUser(String packageName) {
        return isDeviceOwnerAppOnAnyUserInner(packageName, /* callingUserOnly =*/ true);
    }

    /**
     * @return true if a package is registered as device owner, even if it's running on a different
     * user.
     *
     * <p>Requires the MANAGE_USERS permission.
     *
     * @hide
     */
    public boolean isDeviceOwnerAppOnAnyUser(String packageName) {
        return isDeviceOwnerAppOnAnyUserInner(packageName, /* callingUserOnly =*/ false);
    }

    /**
     * @return device owner component name, only when it's running on the calling user.
     *
     * @hide
     */
    public ComponentName getDeviceOwnerComponentOnCallingUser() {
        return getDeviceOwnerComponentInner(/* callingUserOnly =*/ true);
    }

    /**
     * @return device owner component name, even if it's running on a different user.
     *
     * <p>Requires the MANAGE_USERS permission.
     *
     * @hide
     */
    public ComponentName getDeviceOwnerComponentOnAnyUser() {
        return getDeviceOwnerComponentInner(/* callingUserOnly =*/ false);
    }

    private boolean isDeviceOwnerAppOnAnyUserInner(String packageName, boolean callingUserOnly) {
        if (packageName == null) {
            return false;
        }
        final ComponentName deviceOwner = getDeviceOwnerComponentInner(callingUserOnly);
        if (deviceOwner == null) {
            return false;
        }
        return packageName.equals(deviceOwner.getPackageName());
    }

    private ComponentName getDeviceOwnerComponentInner(boolean callingUserOnly) {
        if (mService != null) {
            try {
                return mService.getDeviceOwnerComponent(callingUserOnly);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * @return ID of the user who runs device owner, or {@link UserHandle#USER_NULL} if there's
     * no device owner.
     *
     * <p>Requires the MANAGE_USERS permission.
     *
     * @hide
     */
    public int getDeviceOwnerUserId() {
        if (mService != null) {
            try {
                return mService.getDeviceOwnerUserId();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return UserHandle.USER_NULL;
    }

    /**
     * Clears the current device owner. The caller must be the device owner. This function should be
     * used cautiously as once it is called it cannot be undone. The device owner can only be set as
     * a part of device setup before setup completes.
     *
     * @param packageName The package name of the device owner.
     * @throws SecurityException if the caller is not in {@code packageName} or {@code packageName}
     *             does not own the current device owner component.
     */
    public void clearDeviceOwnerApp(String packageName) {
        throwIfParentInstance("clearDeviceOwnerApp");
        if (mService != null) {
            try {
                mService.clearDeviceOwner(packageName);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the device owner package name, only if it's running on the calling user.
     *
     * <p>Bundled components should use {@code getDeviceOwnerComponentOnCallingUser()} for clarity.
     *
     * @hide
     */
    @SystemApi
    public String getDeviceOwner() {
        throwIfParentInstance("getDeviceOwner");
        final ComponentName name = getDeviceOwnerComponentOnCallingUser();
        return name != null ? name.getPackageName() : null;
    }

    /**
     * @return true if the device is managed by any device owner.
     *
     * <p>Requires the MANAGE_USERS permission.
     *
     * @hide
     */
    public boolean isDeviceManaged() {
        return getDeviceOwnerComponentOnAnyUser() != null;
    }

    /**
     * Returns the device owner name.  Note this method *will* return the device owner
     * name when it's running on a different user.
     *
     * <p>Requires the MANAGE_USERS permission.
     *
     * @hide
     */
    @SystemApi
    public String getDeviceOwnerNameOnAnyUser() {
        throwIfParentInstance("getDeviceOwnerNameOnAnyUser");
        if (mService != null) {
            try {
                return mService.getDeviceOwnerName();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * @hide
     * @deprecated Do not use
     * @removed
     */
    @Deprecated
    @SystemApi
    public String getDeviceInitializerApp() {
        return null;
    }

    /**
     * @hide
     * @deprecated Do not use
     * @removed
     */
    @Deprecated
    @SystemApi
    public ComponentName getDeviceInitializerComponent() {
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
    public boolean setActiveProfileOwner(@NonNull ComponentName admin, @Deprecated String ownerName)
            throws IllegalArgumentException {
        throwIfParentInstance("setActiveProfileOwner");
        if (mService != null) {
            try {
                final int myUserId = myUserId();
                mService.setActiveAdmin(admin, false, myUserId);
                return mService.setProfileOwner(admin, ownerName, myUserId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Clears the active profile owner and removes all user restrictions. The caller must be from
     * the same package as the active profile owner for this user, otherwise a SecurityException
     * will be thrown.
     * <p>
     * This doesn't work for managed profile owners.
     *
     * @param admin The component to remove as the profile owner.
     * @throws SecurityException if {@code admin} is not an active profile owner.
     */
    public void clearProfileOwner(@NonNull ComponentName admin) {
        throwIfParentInstance("clearProfileOwner");
        if (mService != null) {
            try {
                mService.clearProfileOwner(admin);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     * Checks whether the user was already setup.
     */
    public boolean hasUserSetupCompleted() {
        if (mService != null) {
            try {
                return mService.hasUserSetupCompleted();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return true;
    }

    /**
     * @hide
     * Sets the given component as the profile owner of the given user profile. The package must
     * already be installed. There must not already be a profile owner for this user.
     * Only apps with the MANAGE_PROFILE_AND_DEVICE_OWNERS permission and the shell uid can call
     * this method.
     * Calling this after the setup phase of the specified user has completed is allowed only if:
     * - the caller is SYSTEM_UID.
     * - or the caller is the shell uid, and there are no accounts on the specified user.
     * @param admin the component name to be registered as profile owner.
     * @param ownerName the human readable name of the organisation associated with this DPM.
     * @param userHandle the userId to set the profile owner for.
     * @return whether the component was successfully registered as the profile owner.
     * @throws IllegalArgumentException if admin is null, the package isn't installed, or the
     * preconditions mentioned are not met.
     */
    public boolean setProfileOwner(@NonNull ComponentName admin, @Deprecated String ownerName,
            int userHandle) throws IllegalArgumentException {
        if (mService != null) {
            try {
                if (ownerName == null) {
                    ownerName = "";
                }
                return mService.setProfileOwner(admin, ownerName, userHandle);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Sets the device owner information to be shown on the lock screen.
     * <p>
     * If the device owner information is {@code null} or empty then the device owner info is
     * cleared and the user owner info is shown on the lock screen if it is set.
     * <p>
     * If the device owner information contains only whitespaces then the message on the lock screen
     * will be blank and the user will not be allowed to change it.
     * <p>
     * If the device owner information needs to be localized, it is the responsibility of the
     * {@link DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast
     * and set a new version of this string accordingly.
     *
     * @param admin The name of the admin component to check.
     * @param info Device owner information which will be displayed instead of the user owner info.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setDeviceOwnerLockScreenInfo(@NonNull ComponentName admin, CharSequence info) {
        throwIfParentInstance("setDeviceOwnerLockScreenInfo");
        if (mService != null) {
            try {
                mService.setDeviceOwnerLockScreenInfo(admin, info);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @return The device owner information. If it is not set returns {@code null}.
     */
    public CharSequence getDeviceOwnerLockScreenInfo() {
        throwIfParentInstance("getDeviceOwnerLockScreenInfo");
        if (mService != null) {
            try {
                return mService.getDeviceOwnerLockScreenInfo();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by device or profile owners to suspend packages for this user.
     * <p>
     * A suspended package will not be able to start activities. Its notifications will be hidden,
     * it will not show up in recents, will not be able to show toasts or dialogs or ring the
     * device.
     * <p>
     * The package must already be installed. If the package is uninstalled while suspended the
     * package will no longer be suspended. The admin can block this by using
     * {@link #setUninstallBlocked}.
     *
     * @param admin The name of the admin component to check.
     * @param packageNames The package names to suspend or unsuspend.
     * @param suspended If set to {@code true} than the packages will be suspended, if set to
     *            {@code false} the packages will be unsuspended.
     * @return an array of package names for which the suspended status is not set as requested in
     *         this method.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public String[] setPackagesSuspended(@NonNull ComponentName admin, String[] packageNames,
            boolean suspended) {
        throwIfParentInstance("setPackagesSuspended");
        if (mService != null) {
            try {
                return mService.setPackagesSuspended(admin, packageNames, suspended);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return packageNames;
    }

    /**
     * Called by device or profile owners to determine if a package is suspended.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package to retrieve the suspended status of.
     * @return {@code true} if the package is suspended or {@code false} if the package is not
     *         suspended, could not be found or an error occurred.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @throws NameNotFoundException if the package could not be found.
     */
    public boolean isPackageSuspended(@NonNull ComponentName admin, String packageName)
            throws NameNotFoundException {
        throwIfParentInstance("isPackageSuspended");
        if (mService != null) {
            try {
                return mService.isPackageSuspended(admin, packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (IllegalArgumentException ex) {
                throw new NameNotFoundException(packageName);
            }
        }
        return false;
    }

    /**
     * Sets the enabled state of the profile. A profile should be enabled only once it is ready to
     * be used. Only the profile owner can call this.
     *
     * @see #isProfileOwnerApp
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    public void setProfileEnabled(@NonNull ComponentName admin) {
        throwIfParentInstance("setProfileEnabled");
        if (mService != null) {
            try {
                mService.setProfileEnabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Sets the name of the profile. In the device owner case it sets the name of the user which it
     * is called from. Only a profile owner or device owner can call this. If this is never called
     * by the profile or device owner, the name will be set to default values.
     *
     * @see #isProfileOwnerApp
     * @see #isDeviceOwnerApp
     * @param admin Which {@link DeviceAdminReceiver} this request is associate with.
     * @param profileName The name of the profile.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setProfileName(@NonNull ComponentName admin, String profileName) {
        throwIfParentInstance("setProfileName");
        if (mService != null) {
            try {
                mService.setProfileName(admin, profileName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Used to determine if a particular package is registered as the profile owner for the
     * user. A profile owner is a special device admin that has additional privileges
     * within the profile.
     *
     * @param packageName The package name of the app to compare with the registered profile owner.
     * @return Whether or not the package is registered as the profile owner.
     */
    public boolean isProfileOwnerApp(String packageName) {
        throwIfParentInstance("isProfileOwnerApp");
        if (mService != null) {
            try {
                ComponentName profileOwner = mService.getProfileOwner(myUserId());
                return profileOwner != null
                        && profileOwner.getPackageName().equals(packageName);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * @hide
     * @return the packageName of the owner of the given user profile or {@code null} if no profile
     * owner has been set for that user.
     * @throws IllegalArgumentException if the userId is invalid.
     */
    @SystemApi
    public ComponentName getProfileOwner() throws IllegalArgumentException {
        throwIfParentInstance("getProfileOwner");
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
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * @hide
     * @return the human readable name of the organisation associated with this DPM or {@code null}
     *         if one is not set.
     * @throws IllegalArgumentException if the userId is invalid.
     */
    public String getProfileOwnerName() throws IllegalArgumentException {
        if (mService != null) {
            try {
                return mService.getProfileOwnerName(Process.myUserHandle().getIdentifier());
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * @hide
     * @param userId The user for whom to fetch the profile owner name, if any.
     * @return the human readable name of the organisation associated with this profile owner or
     *         null if one is not set.
     * @throws IllegalArgumentException if the userId is invalid.
     */
    @SystemApi
    public String getProfileOwnerNameAsUser(int userId) throws IllegalArgumentException {
        throwIfParentInstance("getProfileOwnerNameAsUser");
        if (mService != null) {
            try {
                return mService.getProfileOwnerName(userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a profile owner or device owner to add a default intent handler activity for
     * intents that match a certain intent filter. This activity will remain the default intent
     * handler even if the set of potential event handlers for the intent filter changes and if the
     * intent preferences are reset.
     * <p>
     * The default disambiguation mechanism takes over if the activity is not installed (anymore).
     * When the activity is (re)installed, it is automatically reset as default intent handler for
     * the filter.
     * <p>
     * The calling device admin must be a profile owner or device owner. If it is not, a security
     * exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param filter The IntentFilter for which a default handler is added.
     * @param activity The Activity that is added as default intent handler.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void addPersistentPreferredActivity(@NonNull ComponentName admin, IntentFilter filter,
            @NonNull ComponentName activity) {
        throwIfParentInstance("addPersistentPreferredActivity");
        if (mService != null) {
            try {
                mService.addPersistentPreferredActivity(admin, filter, activity);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner or device owner to remove all persistent intent handler preferences
     * associated with the given package that were set by {@link #addPersistentPreferredActivity}.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package for which preferences are removed.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void clearPackagePersistentPreferredActivities(@NonNull ComponentName admin,
            String packageName) {
        throwIfParentInstance("clearPackagePersistentPreferredActivities");
        if (mService != null) {
            try {
                mService.clearPackagePersistentPreferredActivities(admin, packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner or device owner to grant permission to a package to manage
     * application restrictions for the calling user via {@link #setApplicationRestrictions} and
     * {@link #getApplicationRestrictions}.
     * <p>
     * This permission is persistent until it is later cleared by calling this method with a
     * {@code null} value or uninstalling the managing package.
     * <p>
     * The supplied application restriction managing package must be installed when calling this
     * API, otherwise an {@link NameNotFoundException} will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package name which will be given access to application restrictions
     *            APIs. If {@code null} is given the current package will be cleared.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @throws NameNotFoundException if {@code packageName} is not found
     */
    public void setApplicationRestrictionsManagingPackage(@NonNull ComponentName admin,
            @Nullable String packageName) throws NameNotFoundException {
        throwIfParentInstance("setApplicationRestrictionsManagingPackage");
        if (mService != null) {
            try {
                if (!mService.setApplicationRestrictionsManagingPackage(admin, packageName)) {
                    throw new NameNotFoundException(packageName);
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner or device owner to retrieve the application restrictions managing
     * package for the current user, or {@code null} if none is set.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The package name allowed to manage application restrictions on the current user, or
     *         {@code null} if none is set.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public String getApplicationRestrictionsManagingPackage(@NonNull ComponentName admin) {
        throwIfParentInstance("getApplicationRestrictionsManagingPackage");
        if (mService != null) {
            try {
                return mService.getApplicationRestrictionsManagingPackage(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by any application to find out whether it has been granted permission via
     * {@link #setApplicationRestrictionsManagingPackage} to manage application restrictions
     * for the calling user.
     *
     * <p>This is done by comparing the calling Linux uid with the uid of the package specified by
     * that method.
     */
    public boolean isCallerApplicationRestrictionsManagingPackage() {
        throwIfParentInstance("isCallerApplicationRestrictionsManagingPackage");
        if (mService != null) {
            try {
                return mService.isCallerApplicationRestrictionsManagingPackage();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Sets the application restrictions for a given target application running in the calling user.
     * <p>
     * The caller must be a profile or device owner on that user, or the package allowed to manage
     * application restrictions via {@link #setApplicationRestrictionsManagingPackage}; otherwise a
     * security exception will be thrown.
     * <p>
     * The provided {@link Bundle} consists of key-value pairs, where the types of values may be:
     * <ul>
     * <li>{@code boolean}
     * <li>{@code int}
     * <li>{@code String} or {@code String[]}
     * <li>From {@link android.os.Build.VERSION_CODES#M}, {@code Bundle} or {@code Bundle[]}
     * </ul>
     * <p>
     * If the restrictions are not available yet, but may be applied in the near future, the caller
     * can notify the target application of that by adding
     * {@link UserManager#KEY_RESTRICTIONS_PENDING} to the settings parameter.
     * <p>
     * The application restrictions are only made visible to the target application via
     * {@link UserManager#getApplicationRestrictions(String)}, in addition to the profile or device
     * owner, and the application restrictions managing package via
     * {@link #getApplicationRestrictions}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if called by the application restrictions managing package.
     * @param packageName The name of the package to update restricted settings for.
     * @param settings A {@link Bundle} to be parsed by the receiving application, conveying a new
     *            set of active restrictions.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setApplicationRestrictionsManagingPackage
     * @see UserManager#KEY_RESTRICTIONS_PENDING
     */
    public void setApplicationRestrictions(@Nullable ComponentName admin, String packageName,
            Bundle settings) {
        throwIfParentInstance("setApplicationRestrictions");
        if (mService != null) {
            try {
                mService.setApplicationRestrictions(admin, packageName, settings);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Sets a list of configuration features to enable for a TrustAgent component. This is meant to
     * be used in conjunction with {@link #KEYGUARD_DISABLE_TRUST_AGENTS}, which disables all trust
     * agents but those enabled by this function call. If flag
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS} is not set, then this call has no effect.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES} to be able to call this method;
     * if not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set the configuration for
     * the parent profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param target Component name of the agent to be enabled.
     * @param configuration TrustAgent-specific feature bundle. If null for any admin, agent will be
     *            strictly disabled according to the state of the
     *            {@link #KEYGUARD_DISABLE_TRUST_AGENTS} flag.
     *            <p>
     *            If {@link #KEYGUARD_DISABLE_TRUST_AGENTS} is set and options is not null for all
     *            admins, then it's up to the TrustAgent itself to aggregate the values from all
     *            device admins.
     *            <p>
     *            Consult documentation for the specific TrustAgent to determine legal options
     *            parameters.
     * @throws SecurityException if {@code admin} is not an active administrator or does not use
     *             {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES}
     */
    public void setTrustAgentConfiguration(@NonNull ComponentName admin,
            @NonNull ComponentName target, PersistableBundle configuration) {
        if (mService != null) {
            try {
                mService.setTrustAgentConfiguration(admin, target, configuration, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Gets configuration for the given trust agent based on aggregating all calls to
     * {@link #setTrustAgentConfiguration(ComponentName, ComponentName, PersistableBundle)} for
     * all device admins.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to retrieve the configuration set
     * on the parent profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. If null,
     * this function returns a list of configurations for all admins that declare
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS}. If any admin declares
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS} but doesn't call
     * {@link #setTrustAgentConfiguration(ComponentName, ComponentName, PersistableBundle)}
     * for this {@param agent} or calls it with a null configuration, null is returned.
     * @param agent Which component to get enabled features for.
     * @return configuration for the given trust agent.
     */
    public List<PersistableBundle> getTrustAgentConfiguration(@Nullable ComponentName admin,
            @NonNull ComponentName agent) {
        return getTrustAgentConfiguration(admin, agent, myUserId());
    }

    /** @hide per-user version */
    public List<PersistableBundle> getTrustAgentConfiguration(@Nullable ComponentName admin,
            @NonNull ComponentName agent, int userHandle) {
        if (mService != null) {
            try {
                return mService.getTrustAgentConfiguration(admin, agent, userHandle,
                        mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return new ArrayList<PersistableBundle>(); // empty list
    }

    /**
     * Called by a profile owner of a managed profile to set whether caller-Id information from the
     * managed profile will be shown in the parent profile, for incoming calls.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled If true caller-Id information in the managed profile is not displayed.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setCrossProfileCallerIdDisabled(@NonNull ComponentName admin, boolean disabled) {
        throwIfParentInstance("setCrossProfileCallerIdDisabled");
        if (mService != null) {
            try {
                mService.setCrossProfileCallerIdDisabled(admin, disabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to determine whether or not caller-Id
     * information has been disabled.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean getCrossProfileCallerIdDisabled(@NonNull ComponentName admin) {
        throwIfParentInstance("getCrossProfileCallerIdDisabled");
        if (mService != null) {
            try {
                return mService.getCrossProfileCallerIdDisabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a profile owner of a managed profile to set whether contacts search from the
     * managed profile will be shown in the parent profile, for incoming calls.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled If true contacts search in the managed profile is not displayed.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setCrossProfileContactsSearchDisabled(@NonNull ComponentName admin,
            boolean disabled) {
        throwIfParentInstance("setCrossProfileContactsSearchDisabled");
        if (mService != null) {
            try {
                mService.setCrossProfileContactsSearchDisabled(admin, disabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to determine whether or not contacts search
     * has been disabled.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean getCrossProfileContactsSearchDisabled(@NonNull ComponentName admin) {
        throwIfParentInstance("getCrossProfileContactsSearchDisabled");
        if (mService != null) {
            try {
                return mService.getCrossProfileContactsSearchDisabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }


    /**
     * Determine whether or not contacts search has been disabled.
     *
     * @param userHandle The user for whom to check the contacts search permission
     * @hide
     */
    public boolean getCrossProfileContactsSearchDisabled(@NonNull UserHandle userHandle) {
        if (mService != null) {
            try {
                return mService
                        .getCrossProfileContactsSearchDisabledForUser(userHandle.getIdentifier());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Start Quick Contact on the managed profile for the user, if the policy allows.
     *
     * @hide
     */
    public void startManagedQuickContact(String actualLookupKey, long actualContactId,
            boolean isContactIdIgnored, long directoryId, Intent originalIntent) {
        if (mService != null) {
            try {
                mService.startManagedQuickContact(actualLookupKey, actualContactId,
                        isContactIdIgnored, directoryId, originalIntent);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Start Quick Contact on the managed profile for the user, if the policy allows.
     * @hide
     */
    public void startManagedQuickContact(String actualLookupKey, long actualContactId,
            Intent originalIntent) {
        startManagedQuickContact(actualLookupKey, actualContactId, false, Directory.DEFAULT,
                originalIntent);
    }

    /**
     * Called by a profile owner of a managed profile to set whether bluetooth devices can access
     * enterprise contacts.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     * <p>
     * This API works on managed profile only.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled If true, bluetooth devices cannot access enterprise contacts.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setBluetoothContactSharingDisabled(@NonNull ComponentName admin, boolean disabled) {
        throwIfParentInstance("setBluetoothContactSharingDisabled");
        if (mService != null) {
            try {
                mService.setBluetoothContactSharingDisabled(admin, disabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to determine whether or not Bluetooth devices
     * cannot access enterprise contacts.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     * <p>
     * This API works on managed profile only.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean getBluetoothContactSharingDisabled(@NonNull ComponentName admin) {
        throwIfParentInstance("getBluetoothContactSharingDisabled");
        if (mService != null) {
            try {
                return mService.getBluetoothContactSharingDisabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return true;
    }

    /**
     * Determine whether or not Bluetooth devices cannot access contacts.
     * <p>
     * This API works on managed profile UserHandle only.
     *
     * @param userHandle The user for whom to check the caller-id permission
     * @hide
     */
    public boolean getBluetoothContactSharingDisabled(UserHandle userHandle) {
        if (mService != null) {
            try {
                return mService.getBluetoothContactSharingDisabledForUser(userHandle
                        .getIdentifier());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return true;
    }

    /**
     * Called by the profile owner of a managed profile so that some intents sent in the managed
     * profile can also be resolved in the parent, or vice versa. Only activity intents are
     * supported.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param filter The {@link IntentFilter} the intent has to match to be also resolved in the
     *            other profile
     * @param flags {@link DevicePolicyManager#FLAG_MANAGED_CAN_ACCESS_PARENT} and
     *            {@link DevicePolicyManager#FLAG_PARENT_CAN_ACCESS_MANAGED} are supported.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void addCrossProfileIntentFilter(@NonNull ComponentName admin, IntentFilter filter, int flags) {
        throwIfParentInstance("addCrossProfileIntentFilter");
        if (mService != null) {
            try {
                mService.addCrossProfileIntentFilter(admin, filter, flags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to remove the cross-profile intent filters
     * that go from the managed profile to the parent, or from the parent to the managed profile.
     * Only removes those that have been set by the profile owner.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void clearCrossProfileIntentFilters(@NonNull ComponentName admin) {
        throwIfParentInstance("clearCrossProfileIntentFilters");
        if (mService != null) {
            try {
                mService.clearCrossProfileIntentFilters(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile or device owner to set the permitted accessibility services. When set by
     * a device owner or profile owner the restriction applies to all profiles of the user the
     * device owner or profile owner is an admin for. By default the user can use any accessiblity
     * service. When zero or more packages have been added, accessiblity services that are not in
     * the list and not part of the system can not be enabled by the user.
     * <p>
     * Calling with a null value for the list disables the restriction so that all services can be
     * used, calling with an empty list only allows the builtin system's services.
     * <p>
     * System accesibility services are always available to the user the list can't modify this.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageNames List of accessibility service package names.
     * @return true if setting the restriction succeeded. It fail if there is one or more non-system
     *         accessibility services enabled, that are not in the list.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean setPermittedAccessibilityServices(@NonNull ComponentName admin,
            List<String> packageNames) {
        throwIfParentInstance("setPermittedAccessibilityServices");
        if (mService != null) {
            try {
                return mService.setPermittedAccessibilityServices(admin, packageNames);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns the list of permitted accessibility services set by this device or profile owner.
     * <p>
     * An empty list means no accessibility services except system services are allowed. Null means
     * all accessibility services are allowed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return List of accessiblity service package names.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public List<String> getPermittedAccessibilityServices(@NonNull ComponentName admin) {
        throwIfParentInstance("getPermittedAccessibilityServices");
        if (mService != null) {
            try {
                return mService.getPermittedAccessibilityServices(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by the system to check if a specific accessibility service is disabled by admin.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName Accessibility service package name that needs to be checked.
     * @param userHandle user id the admin is running as.
     * @return true if the accessibility service is permitted, otherwise false.
     *
     * @hide
     */
    public boolean isAccessibilityServicePermittedByAdmin(@NonNull ComponentName admin,
            @NonNull String packageName, int userHandle) {
        if (mService != null) {
            try {
                return mService.isAccessibilityServicePermittedByAdmin(admin, packageName,
                        userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns the list of accessibility services permitted by the device or profiles
     * owners of this user.
     *
     * <p>Null means all accessibility services are allowed, if a non-null list is returned
     * it will contain the intersection of the permitted lists for any device or profile
     * owners that apply to this user. It will also include any system accessibility services.
     *
     * @param userId which user to check for.
     * @return List of accessiblity service package names.
     * @hide
     */
     @SystemApi
     public List<String> getPermittedAccessibilityServices(int userId) {
        throwIfParentInstance("getPermittedAccessibilityServices");
        if (mService != null) {
            try {
                return mService.getPermittedAccessibilityServicesForUser(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
     }

    /**
     * Called by a profile or device owner to set the permitted input methods services. When set by
     * a device owner or profile owner the restriction applies to all profiles of the user the
     * device owner or profile owner is an admin for. By default the user can use any input method.
     * When zero or more packages have been added, input method that are not in the list and not
     * part of the system can not be enabled by the user. This method will fail if it is called for
     * a admin that is not for the foreground user or a profile of the foreground user.
     * <p>
     * Calling with a null value for the list disables the restriction so that all input methods can
     * be used, calling with an empty list disables all but the system's own input methods.
     * <p>
     * System input methods are always available to the user this method can't modify this.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageNames List of input method package names.
     * @return true if setting the restriction succeeded. It will fail if there are one or more
     *         non-system input methods currently enabled that are not in the packageNames list.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean setPermittedInputMethods(@NonNull ComponentName admin, List<String> packageNames) {
        throwIfParentInstance("setPermittedInputMethods");
        if (mService != null) {
            try {
                return mService.setPermittedInputMethods(admin, packageNames);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }


    /**
     * Returns the list of permitted input methods set by this device or profile owner.
     * <p>
     * An empty list means no input methods except system input methods are allowed. Null means all
     * input methods are allowed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return List of input method package names.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public List<String> getPermittedInputMethods(@NonNull ComponentName admin) {
        throwIfParentInstance("getPermittedInputMethods");
        if (mService != null) {
            try {
                return mService.getPermittedInputMethods(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by the system to check if a specific input method is disabled by admin.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName Input method package name that needs to be checked.
     * @param userHandle user id the admin is running as.
     * @return true if the input method is permitted, otherwise false.
     *
     * @hide
     */
    public boolean isInputMethodPermittedByAdmin(@NonNull ComponentName admin,
            @NonNull String packageName, int userHandle) {
        if (mService != null) {
            try {
                return mService.isInputMethodPermittedByAdmin(admin, packageName, userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns the list of input methods permitted by the device or profiles
     * owners of the current user.  (*Not* calling user, due to a limitation in InputMethodManager.)
     *
     * <p>Null means all input methods are allowed, if a non-null list is returned
     * it will contain the intersection of the permitted lists for any device or profile
     * owners that apply to this user. It will also include any system input methods.
     *
     * @return List of input method package names.
     * @hide
     */
    @SystemApi
    public List<String> getPermittedInputMethodsForCurrentUser() {
        throwIfParentInstance("getPermittedInputMethodsForCurrentUser");
        if (mService != null) {
            try {
                return mService.getPermittedInputMethodsForCurrentUser();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a device owner to get the list of apps to keep around as APKs even if no user has
     * currently installed it.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     *
     * @return List of package names to keep cached.
     * @hide
     */
    public List<String> getKeepUninstalledPackages(@NonNull ComponentName admin) {
        throwIfParentInstance("getKeepUninstalledPackages");
        if (mService != null) {
            try {
                return mService.getKeepUninstalledPackages(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a device owner to set a list of apps to keep around as APKs even if no user has
     * currently installed it.
     *
     * <p>Please note that setting this policy does not imply that specified apps will be
     * automatically pre-cached.</p>
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageNames List of package names to keep cached.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @hide
     */
    public void setKeepUninstalledPackages(@NonNull ComponentName admin,
            @NonNull List<String> packageNames) {
        throwIfParentInstance("setKeepUninstalledPackages");
        if (mService != null) {
            try {
                mService.setKeepUninstalledPackages(admin, packageNames);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
     * @return the {@link android.os.UserHandle} object for the created user, or {@code null} if the
     *         user could not be created.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#M}
     * @removed From {@link android.os.Build.VERSION_CODES#N}
     */
    @Deprecated
    public UserHandle createUser(@NonNull ComponentName admin, String name) {
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
     * @return the {@link android.os.UserHandle} object for the created user, or {@code null} if the
     *         user could not be created.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#M}
     * @removed From {@link android.os.Build.VERSION_CODES#N}
     */
    @Deprecated
    public UserHandle createAndInitializeUser(@NonNull ComponentName admin, String name,
            String ownerName, @NonNull ComponentName profileOwnerComponent, Bundle adminExtras) {
        return null;
    }

    /**
      * Flag used by {@link #createAndManageUser} to skip setup wizard after creating a new user.
      */
    public static final int SKIP_SETUP_WIZARD = 0x0001;

    /**
     * Flag used by {@link #createAndManageUser} to specify that the user should be created
     * ephemeral.
     * @hide
     */
    public static final int MAKE_USER_EPHEMERAL = 0x0002;

    /**
     * Called by a device owner to create a user with the specified name and a given component of
     * the calling package as profile owner. The UserHandle returned by this method should not be
     * persisted as user handles are recycled as users are removed and created. If you need to
     * persist an identifier for this user, use {@link UserManager#getSerialNumberForUser}. The new
     * user will not be started in the background.
     * <p>
     * admin is the {@link DeviceAdminReceiver} which is the device owner. profileOwner is also a
     * DeviceAdminReceiver in the same package as admin, and will become the profile owner and will
     * be registered as an active admin on the new user. The profile owner package will be installed
     * on the new user.
     * <p>
     * If the adminExtras are not null, they will be stored on the device until the user is started
     * for the first time. Then the extras will be passed to the admin when onEnable is called.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param name The user's name.
     * @param profileOwner Which {@link DeviceAdminReceiver} will be profile owner. Has to be in the
     *            same package as admin, otherwise no user is created and an
     *            IllegalArgumentException is thrown.
     * @param adminExtras Extras that will be passed to onEnable of the admin receiver on the new
     *            user.
     * @param flags {@link #SKIP_SETUP_WIZARD} is supported.
     * @see UserHandle
     * @return the {@link android.os.UserHandle} object for the created user, or {@code null} if the
     *         user could not be created.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public UserHandle createAndManageUser(@NonNull ComponentName admin, @NonNull String name,
            @NonNull ComponentName profileOwner, @Nullable PersistableBundle adminExtras,
            int flags) {
        throwIfParentInstance("createAndManageUser");
        try {
            return mService.createAndManageUser(admin, name, profileOwner, adminExtras, flags);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to remove a user and all associated data. The primary user can not
     * be removed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to remove.
     * @return {@code true} if the user was removed, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public boolean removeUser(@NonNull ComponentName admin, UserHandle userHandle) {
        throwIfParentInstance("removeUser");
        try {
            return mService.removeUser(admin, userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to switch the specified user to the foreground.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to switch to; null will switch to primary.
     * @return {@code true} if the switch was successful, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see Intent#ACTION_USER_FOREGROUND
     */
    public boolean switchUser(@NonNull ComponentName admin, @Nullable UserHandle userHandle) {
        throwIfParentInstance("switchUser");
        try {
            return mService.switchUser(admin, userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the application restrictions for a given target application running in the calling
     * user.
     * <p>
     * The caller must be a profile or device owner on that user, or the package allowed to manage
     * application restrictions via {@link #setApplicationRestrictionsManagingPackage}; otherwise a
     * security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if called by the application restrictions managing package.
     * @param packageName The name of the package to fetch restricted settings of.
     * @return {@link Bundle} of settings corresponding to what was set last time
     *         {@link DevicePolicyManager#setApplicationRestrictions} was called, or an empty
     *         {@link Bundle} if no restrictions have been set.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see {@link #setApplicationRestrictionsManagingPackage}
     */
    public Bundle getApplicationRestrictions(@Nullable ComponentName admin, String packageName) {
        throwIfParentInstance("getApplicationRestrictions");
        if (mService != null) {
            try {
                return mService.getApplicationRestrictions(admin, packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a profile or device owner to set a user restriction specified by the key.
     * <p>
     * The calling device admin must be a profile or device owner; if it is not, a security
     * exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param key The key of the restriction. See the constants in {@link android.os.UserManager}
     *            for the list of keys.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void addUserRestriction(@NonNull ComponentName admin, String key) {
        throwIfParentInstance("addUserRestriction");
        if (mService != null) {
            try {
                mService.setUserRestriction(admin, key, true);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile or device owner to clear a user restriction specified by the key.
     * <p>
     * The calling device admin must be a profile or device owner; if it is not, a security
     * exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param key The key of the restriction. See the constants in {@link android.os.UserManager}
     *            for the list of keys.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void clearUserRestriction(@NonNull ComponentName admin, String key) {
        throwIfParentInstance("clearUserRestriction");
        if (mService != null) {
            try {
                mService.setUserRestriction(admin, key, false);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile or device owner to get user restrictions set with
     * {@link #addUserRestriction(ComponentName, String)}.
     * <p>
     * The target user may have more restrictions set by the system or other device owner / profile
     * owner. To get all the user restrictions currently set, use
     * {@link UserManager#getUserRestrictions()}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public Bundle getUserRestrictions(@NonNull ComponentName admin) {
        throwIfParentInstance("getUserRestrictions");
        Bundle ret = null;
        if (mService != null) {
            try {
                ret = mService.getUserRestrictions(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return ret == null ? new Bundle() : ret;
    }

    /**
     * Called by profile or device owners to hide or unhide packages. When a package is hidden it is
     * unavailable for use, but the data and actual package file remain.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package to hide or unhide.
     * @param hidden {@code true} if the package should be hidden, {@code false} if it should be
     *            unhidden.
     * @return boolean Whether the hidden setting of the package was successfully updated.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean setApplicationHidden(@NonNull ComponentName admin, String packageName,
            boolean hidden) {
        throwIfParentInstance("setApplicationHidden");
        if (mService != null) {
            try {
                return mService.setApplicationHidden(admin, packageName, hidden);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by profile or device owners to determine if a package is hidden.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package to retrieve the hidden status of.
     * @return boolean {@code true} if the package is hidden, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean isApplicationHidden(@NonNull ComponentName admin, String packageName) {
        throwIfParentInstance("isApplicationHidden");
        if (mService != null) {
            try {
                return mService.isApplicationHidden(admin, packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by profile or device owners to re-enable a system app that was disabled by default
     * when the user was initialized.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package to be re-enabled in the calling profile.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void enableSystemApp(@NonNull ComponentName admin, String packageName) {
        throwIfParentInstance("enableSystemApp");
        if (mService != null) {
            try {
                mService.enableSystemApp(admin, packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by profile or device owners to re-enable system apps by intent that were disabled by
     * default when the user was initialized.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param intent An intent matching the app(s) to be installed. All apps that resolve for this
     *            intent will be re-enabled in the calling profile.
     * @return int The number of activities that matched the intent and were installed.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public int enableSystemApp(@NonNull ComponentName admin, Intent intent) {
        throwIfParentInstance("enableSystemApp");
        if (mService != null) {
            try {
                return mService.enableSystemAppWithIntent(admin, intent);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by a device owner or profile owner to disable account management for a specific type
     * of account.
     * <p>
     * The calling device admin must be a device owner or profile owner. If it is not, a security
     * exception will be thrown.
     * <p>
     * When account management is disabled for an account type, adding or removing an account of
     * that type will not be possible.
     * <p>
     * From {@link android.os.Build.VERSION_CODES#N} the profile or device owner can still use
     * {@link android.accounts.AccountManager} APIs to add or remove accounts when account
     * management for a specific type is disabled.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param accountType For which account management is disabled or enabled.
     * @param disabled The boolean indicating that account management will be disabled (true) or
     *            enabled (false).
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setAccountManagementDisabled(@NonNull ComponentName admin, String accountType,
            boolean disabled) {
        throwIfParentInstance("setAccountManagementDisabled");
        if (mService != null) {
            try {
                mService.setAccountManagementDisabled(admin, accountType, disabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
        throwIfParentInstance("getAccountTypesWithManagementDisabled");
        return getAccountTypesWithManagementDisabledAsUser(myUserId());
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
                throw e.rethrowFromSystemServer();
            }
        }

        return null;
    }

    /**
     * Sets which packages may enter lock task mode.
     * <p>
     * Any packages that shares uid with an allowed package will also be allowed to activate lock
     * task. From {@link android.os.Build.VERSION_CODES#M} removing packages from the lock task
     * package list results in locked tasks belonging to those packages to be finished. This
     * function can only be called by the device owner.
     *
     * @param packages The list of packages allowed to enter lock task mode
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see Activity#startLockTask()
     * @see DeviceAdminReceiver#onLockTaskModeEntering(Context, Intent, String)
     * @see DeviceAdminReceiver#onLockTaskModeExiting(Context, Intent)
     * @see UserManager#DISALLOW_CREATE_WINDOWS
     */
    public void setLockTaskPackages(@NonNull ComponentName admin, String[] packages)
            throws SecurityException {
        throwIfParentInstance("setLockTaskPackages");
        if (mService != null) {
            try {
                mService.setLockTaskPackages(admin, packages);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * This function returns the list of packages allowed to start the lock task mode.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @hide
     */
    public String[] getLockTaskPackages(@NonNull ComponentName admin) {
        throwIfParentInstance("getLockTaskPackages");
        if (mService != null) {
            try {
                return mService.getLockTaskPackages(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
        throwIfParentInstance("isLockTaskPermitted");
        if (mService != null) {
            try {
                return mService.isLockTaskPermitted(pkg);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device owners to update {@link Settings.Global} settings. Validation that the value
     * of the setting is in the correct form for the setting type should be performed by the caller.
     * <p>
     * The settings that can be updated with this method are:
     * <ul>
     * <li>{@link Settings.Global#ADB_ENABLED}</li>
     * <li>{@link Settings.Global#AUTO_TIME}</li>
     * <li>{@link Settings.Global#AUTO_TIME_ZONE}</li>
     * <li>{@link Settings.Global#DATA_ROAMING}</li>
     * <li>{@link Settings.Global#USB_MASS_STORAGE_ENABLED}</li>
     * <li>{@link Settings.Global#WIFI_SLEEP_POLICY}</li>
     * <li>{@link Settings.Global#STAY_ON_WHILE_PLUGGED_IN} This setting is only available from
     * {@link android.os.Build.VERSION_CODES#M} onwards and can only be set if
     * {@link #setMaximumTimeToLock} is not used to set a timeout.</li>
     * <li>{@link Settings.Global#WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN}</li> This setting is only
     * available from {@link android.os.Build.VERSION_CODES#M} onwards.</li>
     * </ul>
     * <p>
     * Changing the following settings has no effect as of {@link android.os.Build.VERSION_CODES#M}:
     * <ul>
     * <li>{@link Settings.Global#BLUETOOTH_ON}. Use
     * {@link android.bluetooth.BluetoothAdapter#enable()} and
     * {@link android.bluetooth.BluetoothAdapter#disable()} instead.</li>
     * <li>{@link Settings.Global#DEVELOPMENT_SETTINGS_ENABLED}</li>
     * <li>{@link Settings.Global#MODE_RINGER}. Use
     * {@link android.media.AudioManager#setRingerMode(int)} instead.</li>
     * <li>{@link Settings.Global#NETWORK_PREFERENCE}</li>
     * <li>{@link Settings.Global#WIFI_ON}. Use
     * {@link android.net.wifi.WifiManager#setWifiEnabled(boolean)} instead.</li>
     * </ul>
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param setting The name of the setting to update.
     * @param value The value to update the setting to.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setGlobalSetting(@NonNull ComponentName admin, String setting, String value) {
        throwIfParentInstance("setGlobalSetting");
        if (mService != null) {
            try {
                mService.setGlobalSetting(admin, setting, value);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by profile or device owners to update {@link Settings.Secure} settings. Validation
     * that the value of the setting is in the correct form for the setting type should be performed
     * by the caller.
     * <p>
     * The settings that can be updated by a profile or device owner with this method are:
     * <ul>
     * <li>{@link Settings.Secure#DEFAULT_INPUT_METHOD}</li>
     * <li>{@link Settings.Secure#INSTALL_NON_MARKET_APPS}</li>
     * <li>{@link Settings.Secure#SKIP_FIRST_USE_HINTS}</li>
     * </ul>
     * <p>
     * A device owner can additionally update the following settings:
     * <ul>
     * <li>{@link Settings.Secure#LOCATION_MODE}</li>
     * </ul>
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param setting The name of the setting to update.
     * @param value The value to update the setting to.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setSecureSetting(@NonNull ComponentName admin, String setting, String value) {
        throwIfParentInstance("setSecureSetting");
        if (mService != null) {
            try {
                mService.setSecureSetting(admin, setting, value);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Designates a specific service component as the provider for making permission requests of a
     * local or remote administrator of the user.
     * <p/>
     * Only a profile owner can designate the restrictions provider.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param provider The component name of the service that implements
     *            {@link RestrictionsReceiver}. If this param is null, it removes the restrictions
     *            provider previously assigned.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setRestrictionsProvider(@NonNull ComponentName admin,
            @Nullable ComponentName provider) {
        throwIfParentInstance("setRestrictionsProvider");
        if (mService != null) {
            try {
                mService.setRestrictionsProvider(admin, provider);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by profile or device owners to set the master volume mute on or off.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param on {@code true} to mute master volume, {@code false} to turn mute off.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setMasterVolumeMuted(@NonNull ComponentName admin, boolean on) {
        throwIfParentInstance("setMasterVolumeMuted");
        if (mService != null) {
            try {
                mService.setMasterVolumeMuted(admin, on);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by profile or device owners to check whether the master volume mute is on or off.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return {@code true} if master volume is muted, {@code false} if it's not.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean isMasterVolumeMuted(@NonNull ComponentName admin) {
        throwIfParentInstance("isMasterVolumeMuted");
        if (mService != null) {
            try {
                return mService.isMasterVolumeMuted(admin);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by profile or device owners to change whether a user can uninstall a package.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName package to change.
     * @param uninstallBlocked true if the user shouldn't be able to uninstall the package.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setUninstallBlocked(@NonNull ComponentName admin, String packageName,
            boolean uninstallBlocked) {
        throwIfParentInstance("setUninstallBlocked");
        if (mService != null) {
            try {
                mService.setUninstallBlocked(admin, packageName, uninstallBlocked);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Check whether the user has been blocked by device policy from uninstalling a package.
     * Requires the caller to be the profile owner if checking a specific admin's policy.
     * <p>
     * <strong>Note:</strong> Starting from {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1}, the
     * behavior of this API is changed such that passing {@code null} as the {@code admin} parameter
     * will return if any admin has blocked the uninstallation. Before L MR1, passing {@code null}
     * will cause a NullPointerException to be raised.
     *
     * @param admin The name of the admin component whose blocking policy will be checked, or
     *            {@code null} to check whether any admin has blocked the uninstallation.
     * @param packageName package to check.
     * @return true if uninstallation is blocked.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean isUninstallBlocked(@Nullable ComponentName admin, String packageName) {
        throwIfParentInstance("isUninstallBlocked");
        if (mService != null) {
            try {
                return mService.isUninstallBlocked(admin, packageName);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by the profile owner of a managed profile to enable widget providers from a given
     * package to be available in the parent profile. As a result the user will be able to add
     * widgets from the white-listed package running under the profile to a widget host which runs
     * under the parent profile, for example the home screen. Note that a package may have zero or
     * more provider components, where each component provides a different widget type.
     * <p>
     * <strong>Note:</strong> By default no widget provider package is white-listed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package from which widget providers are white-listed.
     * @return Whether the package was added.
     * @throws SecurityException if {@code admin} is not a profile owner.
     * @see #removeCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @see #getCrossProfileWidgetProviders(android.content.ComponentName)
     */
    public boolean addCrossProfileWidgetProvider(@NonNull ComponentName admin, String packageName) {
        throwIfParentInstance("addCrossProfileWidgetProvider");
        if (mService != null) {
            try {
                return mService.addCrossProfileWidgetProvider(admin, packageName);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by the profile owner of a managed profile to disable widget providers from a given
     * package to be available in the parent profile. For this method to take effect the package
     * should have been added via
     * {@link #addCrossProfileWidgetProvider( android.content.ComponentName, String)}.
     * <p>
     * <strong>Note:</strong> By default no widget provider package is white-listed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package from which widget providers are no longer white-listed.
     * @return Whether the package was removed.
     * @throws SecurityException if {@code admin} is not a profile owner.
     * @see #addCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @see #getCrossProfileWidgetProviders(android.content.ComponentName)
     */
    public boolean removeCrossProfileWidgetProvider(
            @NonNull ComponentName admin, String packageName) {
        throwIfParentInstance("removeCrossProfileWidgetProvider");
        if (mService != null) {
            try {
                return mService.removeCrossProfileWidgetProvider(admin, packageName);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by the profile owner of a managed profile to query providers from which packages are
     * available in the parent profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The white-listed package list.
     * @see #addCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @see #removeCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    public List<String> getCrossProfileWidgetProviders(@NonNull ComponentName admin) {
        throwIfParentInstance("getCrossProfileWidgetProviders");
        if (mService != null) {
            try {
                List<String> providers = mService.getCrossProfileWidgetProviders(admin);
                if (providers != null) {
                    return providers;
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return Collections.emptyList();
    }

    /**
     * Called by profile or device owners to set the user's photo.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param icon the bitmap to set as the photo.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setUserIcon(@NonNull ComponentName admin, Bitmap icon) {
        throwIfParentInstance("setUserIcon");
        try {
            mService.setUserIcon(admin, icon);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owners to set a local system update policy. When a new policy is set,
     * {@link #ACTION_SYSTEM_UPDATE_POLICY_CHANGED} is broadcasted.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. All
     *            components in the device owner package can set system update policies and the most
     *            recent policy takes effect.
     * @param policy the new policy, or {@code null} to clear the current policy.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see SystemUpdatePolicy
     */
    public void setSystemUpdatePolicy(@NonNull ComponentName admin, SystemUpdatePolicy policy) {
        throwIfParentInstance("setSystemUpdatePolicy");
        if (mService != null) {
            try {
                mService.setSystemUpdatePolicy(admin, policy);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve a local system update policy set previously by {@link #setSystemUpdatePolicy}.
     *
     * @return The current policy object, or {@code null} if no policy is set.
     */
    public SystemUpdatePolicy getSystemUpdatePolicy() {
        throwIfParentInstance("getSystemUpdatePolicy");
        if (mService != null) {
            try {
                return mService.getSystemUpdatePolicy();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a device owner to disable the keyguard altogether.
     * <p>
     * Setting the keyguard to disabled has the same effect as choosing "None" as the screen lock
     * type. However, this call has no effect if a password, pin or pattern is currently set. If a
     * password, pin or pattern is set after the keyguard was disabled, the keyguard stops being
     * disabled.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled {@code true} disables the keyguard, {@code false} reenables it.
     * @return {@code false} if attempting to disable the keyguard while a lock password was in
     *         place. {@code true} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public boolean setKeyguardDisabled(@NonNull ComponentName admin, boolean disabled) {
        throwIfParentInstance("setKeyguardDisabled");
        try {
            return mService.setKeyguardDisabled(admin, disabled);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner to disable the status bar. Disabling the status bar blocks
     * notifications, quick settings and other screen overlays that allow escaping from a single use
     * device.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled {@code true} disables the status bar, {@code false} reenables it.
     * @return {@code false} if attempting to disable the status bar failed. {@code true} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public boolean setStatusBarDisabled(@NonNull ComponentName admin, boolean disabled) {
        throwIfParentInstance("setStatusBarDisabled");
        try {
            return mService.setStatusBarDisabled(admin, disabled);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Callable by the system update service to notify device owners about pending updates.
     * The caller must hold {@link android.Manifest.permission#NOTIFY_PENDING_SYSTEM_UPDATE}
     * permission.
     *
     * @param updateReceivedTime The time as given by {@link System#currentTimeMillis()} indicating
     *        when the current pending update was first available. -1 if no update is available.
     * @hide
     */
    @SystemApi
    public void notifyPendingSystemUpdate(long updateReceivedTime) {
        throwIfParentInstance("notifyPendingSystemUpdate");
        if (mService != null) {
            try {
                mService.notifyPendingSystemUpdate(updateReceivedTime);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by profile or device owners to set the default response for future runtime permission
     * requests by applications. The policy can allow for normal operation which prompts the user to
     * grant a permission, or can allow automatic granting or denying of runtime permission requests
     * by an application. This also applies to new permissions declared by app updates. When a
     * permission is denied or granted this way, the effect is equivalent to setting the permission
     * grant state via {@link #setPermissionGrantState}.
     * <p/>
     * As this policy only acts on runtime permission requests, it only applies to applications
     * built with a {@code targetSdkVersion} of {@link android.os.Build.VERSION_CODES#M} or later.
     *
     * @param admin Which profile or device owner this request is associated with.
     * @param policy One of the policy constants {@link #PERMISSION_POLICY_PROMPT},
     *            {@link #PERMISSION_POLICY_AUTO_GRANT} and {@link #PERMISSION_POLICY_AUTO_DENY}.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setPermissionGrantState
     */
    public void setPermissionPolicy(@NonNull ComponentName admin, int policy) {
        throwIfParentInstance("setPermissionPolicy");
        try {
            mService.setPermissionPolicy(admin, policy);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current runtime permission policy set by the device or profile owner. The
     * default is {@link #PERMISSION_POLICY_PROMPT}.
     * @param admin Which profile or device owner this request is associated with.
     * @return the current policy for future permission requests.
     */
    public int getPermissionPolicy(ComponentName admin) {
        throwIfParentInstance("getPermissionPolicy");
        try {
            return mService.getPermissionPolicy(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the grant state of a runtime permission for a specific application. The state can be
     * {@link #PERMISSION_GRANT_STATE_DEFAULT default} in which a user can manage it through the UI,
     * {@link #PERMISSION_GRANT_STATE_DENIED denied}, in which the permission is denied and the user
     * cannot manage it through the UI, and {@link #PERMISSION_GRANT_STATE_GRANTED granted} in which
     * the permission is granted and the user cannot manage it through the UI. This might affect all
     * permissions in a group that the runtime permission belongs to. This method can only be called
     * by a profile or device owner.
     * <p/>
     * Setting the grant state to {@link #PERMISSION_GRANT_STATE_DEFAULT default} does not revoke
     * the permission. It retains the previous grant, if any.
     * <p/>
     * Permissions can be granted or revoked only for applications built with a
     * {@code targetSdkVersion} of {@link android.os.Build.VERSION_CODES#M} or later.
     *
     * @param admin Which profile or device owner this request is associated with.
     * @param packageName The application to grant or revoke a permission to.
     * @param permission The permission to grant or revoke.
     * @param grantState The permission grant state which is one of
     *            {@link #PERMISSION_GRANT_STATE_DENIED}, {@link #PERMISSION_GRANT_STATE_DEFAULT},
     *            {@link #PERMISSION_GRANT_STATE_GRANTED},
     * @return whether the permission was successfully granted or revoked.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #PERMISSION_GRANT_STATE_DENIED
     * @see #PERMISSION_GRANT_STATE_DEFAULT
     * @see #PERMISSION_GRANT_STATE_GRANTED
     */
    public boolean setPermissionGrantState(@NonNull ComponentName admin, String packageName,
            String permission, int grantState) {
        throwIfParentInstance("setPermissionGrantState");
        try {
            return mService.setPermissionGrantState(admin, packageName, permission, grantState);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current grant state of a runtime permission for a specific application.
     *
     * @param admin Which profile or device owner this request is associated with.
     * @param packageName The application to check the grant state for.
     * @param permission The permission to check for.
     * @return the current grant state specified by device policy. If the profile or device owner
     *         has not set a grant state, the return value is
     *         {@link #PERMISSION_GRANT_STATE_DEFAULT}. This does not indicate whether or not the
     *         permission is currently granted for the package.
     *         <p/>
     *         If a grant state was set by the profile or device owner, then the return value will
     *         be one of {@link #PERMISSION_GRANT_STATE_DENIED} or
     *         {@link #PERMISSION_GRANT_STATE_GRANTED}, which indicates if the permission is
     *         currently denied or granted.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setPermissionGrantState(ComponentName, String, String, int)
     * @see PackageManager#checkPermission(String, String)
     */
    public int getPermissionGrantState(@NonNull ComponentName admin, String packageName,
            String permission) {
        throwIfParentInstance("getPermissionGrantState");
        try {
            return mService.getPermissionGrantState(admin, packageName, permission);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns if provisioning a managed profile or device is possible or not.
     * @param action One of {@link #ACTION_PROVISION_MANAGED_DEVICE},
     * {@link #ACTION_PROVISION_MANAGED_PROFILE}.
     * @return if provisioning a managed profile or device is possible or not.
     * @throws IllegalArgumentException if the supplied action is not valid.
     */
    public boolean isProvisioningAllowed(String action) {
        throwIfParentInstance("isProvisioningAllowed");
        try {
            return mService.isProvisioningAllowed(action);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Return if this user is a managed profile of another user. An admin can become the profile
     * owner of a managed profile with {@link #ACTION_PROVISION_MANAGED_PROFILE} and of a managed
     * user with {@link #createAndManageUser}
     * @param admin Which profile owner this request is associated with.
     * @return if this user is a managed profile of another user.
     */
    public boolean isManagedProfile(@NonNull ComponentName admin) {
        throwIfParentInstance("isManagedProfile");
        try {
            return mService.isManagedProfile(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Return if this user is a system-only user. An admin can manage a device from a system only
     * user by calling {@link #ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE}.
     * @param admin Which device owner this request is associated with.
     * @return if this user is a system-only user.
     */
    public boolean isSystemOnlyUser(@NonNull ComponentName admin) {
        try {
            return mService.isSystemOnlyUser(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner to get the MAC address of the Wi-Fi device.
     *
     * @param admin Which device owner this request is associated with.
     * @return the MAC address of the Wi-Fi device, or null when the information is not available.
     *         (For example, Wi-Fi hasn't been enabled, or the device doesn't support Wi-Fi.)
     *         <p>
     *         The address will be in the {@code XX:XX:XX:XX:XX:XX} format.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public String getWifiMacAddress(@NonNull ComponentName admin) {
        throwIfParentInstance("getWifiMacAddress");
        try {
            return mService.getWifiMacAddress(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner to reboot the device. If there is an ongoing call on the device,
     * throws an {@link IllegalStateException}.
     * @param admin Which device owner the request is associated with.
     * @throws IllegalStateException if device has an ongoing call.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see TelephonyManager#CALL_STATE_IDLE
     */
    public void reboot(@NonNull ComponentName admin) {
        throwIfParentInstance("reboot");
        try {
            mService.reboot(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device admin to set the short support message. This will be displayed to the user
     * in settings screens where funtionality has been disabled by the admin. The message should be
     * limited to a short statement such as "This setting is disabled by your administrator. Contact
     * someone@example.com for support." If the message is longer than 200 characters it may be
     * truncated.
     * <p>
     * If the short support message needs to be localized, it is the responsibility of the
     * {@link DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast
     * and set a new version of this string accordingly.
     *
     * @see #setLongSupportMessage
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param message Short message to be displayed to the user in settings or null to clear the
     *            existing message.
     * @throws SecurityException if {@code admin} is not an active administrator.
     */
    public void setShortSupportMessage(@NonNull ComponentName admin,
            @Nullable CharSequence message) {
        throwIfParentInstance("setShortSupportMessage");
        if (mService != null) {
            try {
                mService.setShortSupportMessage(admin, message);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device admin to get the short support message.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The message set by {@link #setShortSupportMessage(ComponentName, CharSequence)} or
     *         null if no message has been set.
     * @throws SecurityException if {@code admin} is not an active administrator.
     */
    public CharSequence getShortSupportMessage(@NonNull ComponentName admin) {
        throwIfParentInstance("getShortSupportMessage");
        if (mService != null) {
            try {
                return mService.getShortSupportMessage(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a device admin to set the long support message. This will be displayed to the user
     * in the device administators settings screen.
     * <p>
     * If the long support message needs to be localized, it is the responsibility of the
     * {@link DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast
     * and set a new version of this string accordingly.
     *
     * @see #setShortSupportMessage
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param message Long message to be displayed to the user in settings or null to clear the
     *            existing message.
     * @throws SecurityException if {@code admin} is not an active administrator.
     */
    public void setLongSupportMessage(@NonNull ComponentName admin,
            @Nullable CharSequence message) {
        throwIfParentInstance("setLongSupportMessage");
        if (mService != null) {
            try {
                mService.setLongSupportMessage(admin, message);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device admin to get the long support message.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The message set by {@link #setLongSupportMessage(ComponentName, CharSequence)} or
     *         null if no message has been set.
     * @throws SecurityException if {@code admin} is not an active administrator.
     */
    public CharSequence getLongSupportMessage(@NonNull ComponentName admin) {
        throwIfParentInstance("getLongSupportMessage");
        if (mService != null) {
            try {
                return mService.getLongSupportMessage(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by the system to get the short support message.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle user id the admin is running as.
     * @return The message set by {@link #setShortSupportMessage(ComponentName, CharSequence)}
     *
     * @hide
     */
    public CharSequence getShortSupportMessageForUser(@NonNull ComponentName admin,
            int userHandle) {
        if (mService != null) {
            try {
                return mService.getShortSupportMessageForUser(admin, userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }


    /**
     * Called by the system to get the long support message.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle user id the admin is running as.
     * @return The message set by {@link #setLongSupportMessage(ComponentName, CharSequence)}
     *
     * @hide
     */
    public CharSequence getLongSupportMessageForUser(@NonNull ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getLongSupportMessageForUser(admin, userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by the profile owner of a managed profile to obtain a {@link DevicePolicyManager}
     * whose calls act on the parent profile.
     *
     * <p>The following methods are supported for the parent instance, all other methods will
     * throw a SecurityException when called on the parent instance:
     * <ul>
     * <li>{@link #getPasswordQuality}</li>
     * <li>{@link #setPasswordQuality}</li>
     * <li>{@link #getPasswordMinimumLength}</li>
     * <li>{@link #setPasswordMinimumLength}</li>
     * <li>{@link #getPasswordMinimumUpperCase}</li>
     * <li>{@link #setPasswordMinimumUpperCase}</li>
     * <li>{@link #getPasswordMinimumLowerCase}</li>
     * <li>{@link #setPasswordMinimumLowerCase}</li>
     * <li>{@link #getPasswordMinimumLetters}</li>
     * <li>{@link #setPasswordMinimumLetters}</li>
     * <li>{@link #getPasswordMinimumNumeric}</li>
     * <li>{@link #setPasswordMinimumNumeric}</li>
     * <li>{@link #getPasswordMinimumSymbols}</li>
     * <li>{@link #setPasswordMinimumSymbols}</li>
     * <li>{@link #getPasswordMinimumNonLetter}</li>
     * <li>{@link #setPasswordMinimumNonLetter}</li>
     * <li>{@link #getPasswordHistoryLength}</li>
     * <li>{@link #setPasswordHistoryLength}</li>
     * <li>{@link #getPasswordExpirationTimeout}</li>
     * <li>{@link #setPasswordExpirationTimeout}</li>
     * <li>{@link #getPasswordExpiration}</li>
     * <li>{@link #isActivePasswordSufficient}</li>
     * <li>{@link #getCurrentFailedPasswordAttempts}</li>
     * <li>{@link #getMaximumFailedPasswordsForWipe}</li>
     * <li>{@link #setMaximumFailedPasswordsForWipe}</li>
     * <li>{@link #getMaximumTimeToLock}</li>
     * <li>{@link #setMaximumTimeToLock}</li>
     * <li>{@link #lockNow}</li>
     * <li>{@link #getKeyguardDisabledFeatures}</li>
     * <li>{@link #setKeyguardDisabledFeatures}</li>
     * <li>{@link #getTrustAgentConfiguration}</li>
     * <li>{@link #setTrustAgentConfiguration}</li>
     * </ul>
     *
     * @return a new instance of {@link DevicePolicyManager} that acts on the parent profile.
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    public DevicePolicyManager getParentProfileInstance(@NonNull ComponentName admin) {
        throwIfParentInstance("getParentProfileInstance");
        try {
            if (!mService.isManagedProfile(admin)) {
                throw new SecurityException("The current user does not have a parent profile.");
            }
            return new DevicePolicyManager(mContext, true);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner to control the security logging feature. Logging can only be
     * enabled on single user devices where the sole user is managed by the device owner.
     *
     * <p> Security logs contain various information intended for security auditing purposes.
     * See {@link SecurityEvent} for details.
     *
     * <p>There must be only one user on the device, managed by the device owner.
     * Otherwise a {@link SecurityException} will be thrown.
     *
     * @param admin Which device owner this request is associated with.
     * @param enabled whether security logging should be enabled or not.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see #retrieveSecurityLogs
     */
    public void setSecurityLoggingEnabled(@NonNull ComponentName admin, boolean enabled) {
        throwIfParentInstance("setSecurityLoggingEnabled");
        try {
            mService.setSecurityLoggingEnabled(admin, enabled);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether security logging is enabled or not by the device owner.
     *
     * <p>Can only be called by the device owner, otherwise a {@link SecurityException} will be
     * thrown.
     *
     * @param admin Which device owner this request is associated with.
     * @return {@code true} if security logging is enabled by device owner, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public boolean isSecurityLoggingEnabled(@NonNull ComponentName admin) {
        throwIfParentInstance("isSecurityLoggingEnabled");
        try {
            return mService.isSecurityLoggingEnabled(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner to retrieve all new security logging entries since the last call to
     * this API after device boots.
     *
     * <p> Access to the logs is rate limited and it will only return new logs after the device
     * owner has been notified via {@link DeviceAdminReceiver#onSecurityLogsAvailable}.
     *
     * <p>There must be only one user on the device, managed by the device owner.
     * Otherwise a {@link SecurityException} will be thrown.
     *
     * @param admin Which device owner this request is associated with.
     * @return the new batch of security logs which is a list of {@link SecurityEvent},
     * or {@code null} if rate limitation is exceeded or if logging is currently disabled.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public List<SecurityEvent> retrieveSecurityLogs(@NonNull ComponentName admin) {
        throwIfParentInstance("retrieveSecurityLogs");
        try {
            ParceledListSlice<SecurityEvent> list = mService.retrieveSecurityLogs(admin);
            if (list != null) {
                return list.getList();
            } else {
                // Rate limit exceeded.
                return null;
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system to obtain a {@link DevicePolicyManager} whose calls act on the parent
     * profile.
     *
     * @hide
     */
    public DevicePolicyManager getParentProfileInstance(UserInfo uInfo) {
        mContext.checkSelfPermission(
                android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        if (!uInfo.isManagedProfile()) {
            throw new SecurityException("The user " + uInfo.id
                    + " does not have a parent profile.");
        }
        return new DevicePolicyManager(mContext, true);
    }

    /**
     * Called by device owners to retrieve device logs from before the device's last reboot.
     * <p>
     * <strong> This API is not supported on all devices. Calling this API on unsupported devices
     * will result in {@code null} being returned. The device logs are retrieved from a RAM region
     * which is not guaranteed to be corruption-free during power cycles, as a result be cautious
     * about data corruption when parsing. </strong>
     * <p>
     * There must be only one user on the device, managed by the device owner. Otherwise a
     * {@link SecurityException} will be thrown.
     *
     * @param admin Which device owner this request is associated with.
     * @return Device logs from before the latest reboot of the system, or {@code null} if this API
     *         is not supported on the device.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public List<SecurityEvent> retrievePreRebootSecurityLogs(@NonNull ComponentName admin) {
        throwIfParentInstance("retrievePreRebootSecurityLogs");
        try {
            ParceledListSlice<SecurityEvent> list = mService.retrievePreRebootSecurityLogs(admin);
            if (list != null) {
                return list.getList();
            } else {
                return null;
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a profile owner of a managed profile to set the color used for customization. This
     * color is used as background color of the confirm credentials screen for that user. The
     * default color is teal (#00796B).
     * <p>
     * The confirm credentials screen can be created using
     * {@link android.app.KeyguardManager#createConfirmDeviceCredentialIntent}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param color The 24bit (0xRRGGBB) representation of the color to be used.
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    public void setOrganizationColor(@NonNull ComponentName admin, int color) {
        throwIfParentInstance("setOrganizationColor");
        try {
            // always enforce alpha channel to have 100% opacity
            color |= 0xFF000000;
            mService.setOrganizationColor(admin, color);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     *
     * Sets the color used for customization.
     *
     * @param color The 24bit (0xRRGGBB) representation of the color to be used.
     * @param userId which user to set the color to.
     * @RequiresPermission(allOf = {
     *       Manifest.permission.MANAGE_USERS,
     *       Manifest.permission.INTERACT_ACROSS_USERS_FULL})
     */
    public void setOrganizationColorForUser(@ColorInt int color, @UserIdInt int userId) {
        try {
            // always enforce alpha channel to have 100% opacity
            color |= 0xFF000000;
            mService.setOrganizationColorForUser(color, userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a profile owner of a managed profile to retrieve the color used for customization.
     * This color is used as background color of the confirm credentials screen for that user.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The 24bit (0xRRGGBB) representation of the color to be used.
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    public @ColorInt int getOrganizationColor(@NonNull ComponentName admin) {
        throwIfParentInstance("getOrganizationColor");
        try {
            return mService.getOrganizationColor(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Retrieve the customization color for a given user.
     *
     * @param userHandle The user id of the user we're interested in.
     * @return The 24bit (0xRRGGBB) representation of the color to be used.
     */
    public @ColorInt int getOrganizationColorForUser(int userHandle) {
        try {
            return mService.getOrganizationColorForUser(userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a profile owner of a managed profile to set the name of the organization under
     * management.
     * <p>
     * If the organization name needs to be localized, it is the responsibility of the
     * {@link DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast
     * and set a new version of this string accordingly.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param title The organization name or {@code null} to clear a previously set name.
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    public void setOrganizationName(@NonNull ComponentName admin, @Nullable CharSequence title) {
        throwIfParentInstance("setOrganizationName");
        try {
            mService.setOrganizationName(admin, title);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a profile owner of a managed profile to retrieve the name of the organization under
     * management.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The organization name or {@code null} if none is set.
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    public CharSequence getOrganizationName(@NonNull ComponentName admin) {
        throwIfParentInstance("getOrganizationName");
        try {
            return mService.getOrganizationName(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve the default title message used in the confirm credentials screen for a given user.
     *
     * @param userHandle The user id of the user we're interested in.
     * @return The organization name or {@code null} if none is set.
     *
     * @hide
     */
    public CharSequence getOrganizationNameForUser(int userHandle) {
        try {
            return mService.getOrganizationNameForUser(userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @return the {@link UserProvisioningState} for the current user - for unmanaged users will
     *         return {@link #STATE_USER_UNMANAGED}
     * @hide
     */
    @SystemApi
    @UserProvisioningState
    public int getUserProvisioningState() {
        throwIfParentInstance("getUserProvisioningState");
        if (mService != null) {
            try {
                return mService.getUserProvisioningState();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return STATE_USER_UNMANAGED;
    }

    /**
     * Set the {@link UserProvisioningState} for the supplied user, if they are managed.
     *
     * @param state to store
     * @param userHandle for user
     * @hide
     */
    public void setUserProvisioningState(@UserProvisioningState int state, int userHandle) {
        if (mService != null) {
            try {
                mService.setUserProvisioningState(state, userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     * Indicates the entity that controls the device or profile owner. A user/profile is considered
     * affiliated if it is managed by the same entity as the device.
     *
     * <p> By definition, the user that the device owner runs on is always affiliated. Any other
     * user/profile is considered affiliated if the following conditions are both met:
     * <ul>
     * <li>The device owner and the user's/profile's profile owner have called this method,
     *   specifying a set of opaque affiliation ids each. If the sets specified by the device owner
     *   and a profile owner intersect, they must have come from the same source, which means that
     *   the device owner and profile owner are controlled by the same entity.</li>
     * <li>The device owner's and profile owner's package names are the same.</li>
     * </ul>
     *
     * @param admin Which profile or device owner this request is associated with.
     * @param ids A set of opaque affiliation ids.
     */
    public void setAffiliationIds(@NonNull ComponentName admin, Set<String> ids) {
        throwIfParentInstance("setAffiliationIds");
        try {
            mService.setAffiliationIds(admin, new ArrayList<String>(ids));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns whether this user/profile is affiliated with the device. See
     * {@link #setAffiliationIds} for the definition of affiliation.
     *
     * @return whether this user/profile is affiliated with the device.
     */
    public boolean isAffiliatedUser() {
        throwIfParentInstance("isAffiliatedUser");
        try {
            return mService != null && mService.isAffiliatedUser();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns whether the uninstall for {@code packageName} for the current user is in queue
     * to be started
     * @param packageName the package to check for
     * @return whether the uninstall intent for {@code packageName} is pending
     */
    public boolean isUninstallInQueue(String packageName) {
        try {
            return mService.isUninstallInQueue(packageName);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * @param packageName the package containing active DAs to be uninstalled
     */
    public void uninstallPackageWithActiveAdmins(String packageName) {
        try {
            mService.uninstallPackageWithActiveAdmins(packageName);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Remove a test admin synchronously without sending it a broadcast about being removed.
     * If the admin is a profile owner or device owner it will still be removed.
     *
     * @param userHandle user id to remove the admin for.
     * @param admin The administration compononent to remove.
     * @throws SecurityException if the caller is not shell / root or the admin package
     *         isn't a test application see {@link ApplicationInfo#FLAG_TEST_APP}.
     */
    public void forceRemoveActiveAdmin(ComponentName adminReceiver, int userHandle) {
        try {
            mService.forceRemoveActiveAdmin(adminReceiver, userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * @return whether {@link android.provider.Settings.Global#DEVICE_PROVISIONED} has ever been set
     * to 1.
     */
    public boolean isDeviceProvisioned() {
        try {
            return mService.isDeviceProvisioned();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Writes that the provisioning configuration has been applied.
     */
    public void setDeviceProvisioningConfigApplied() {
        try {
            mService.setDeviceProvisioningConfigApplied();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * @return whether the provisioning configuration has been applied.
     */
    public boolean isDeviceProvisioningConfigApplied() {
        try {
            return mService.isDeviceProvisioningConfigApplied();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    private void throwIfParentInstance(String functionName) {
        if (mParentInstance) {
            throw new SecurityException(functionName + " cannot be called on the parent instance");
        }
    }
}
