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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest.permission;
import android.annotation.CallbackExecutor;
import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.StringDef;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.Activity;
import android.app.IServiceConnection;
import android.app.KeyguardManager;
import android.app.admin.SecurityLog.SecurityEvent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.net.NetworkUtils;
import android.net.PrivateDnsConnectivityChecker;
import android.net.ProxyInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManager.UserOperationException;
import android.os.UserManager.UserOperationResult;
import android.provider.CalendarContract;
import android.provider.ContactsContract.Directory;
import android.provider.Settings;
import android.security.AttestedKeyPair;
import android.security.Credentials;
import android.security.KeyChain;
import android.security.KeyChainException;
import android.security.keymaster.KeymasterCertificateChain;
import android.security.keystore.AttestationUtils;
import android.security.keystore.KeyAttestationException;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.ParcelableKeyGenParameterSpec;
import android.security.keystore.StrongBoxUnavailableException;
import android.service.restrictions.RestrictionsReceiver;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.Preconditions;
import com.android.org.conscrypt.TrustedCertificateStore;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.KeyFactory;
import java.security.KeyPair;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

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
@SystemService(Context.DEVICE_POLICY_SERVICE)
@RequiresFeature(PackageManager.FEATURE_DEVICE_ADMIN)
public class DevicePolicyManager {

    private static String TAG = "DevicePolicyManager";

    private final Context mContext;
    private final IDevicePolicyManager mService;
    private final boolean mParentInstance;

    /** @hide */
    public DevicePolicyManager(Context context, IDevicePolicyManager service) {
        this(context, service, false);
    }

    /** @hide */
    @VisibleForTesting
    protected DevicePolicyManager(Context context, IDevicePolicyManager service,
            boolean parentInstance) {
        mContext = context;
        mService = service;
        mParentInstance = parentInstance;
    }

    /** @hide test will override it. */
    @VisibleForTesting
    protected int myUserId() {
        return mContext.getUserId();
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
     * <li>{@link #EXTRA_PROVISIONING_SKIP_USER_CONSENT}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_KEEP_ACCOUNT_ON_MIGRATION}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DISCLAIMERS}, optional</li>
     * </ul>
     *
     * <p>When managed provisioning has completed, broadcasts are sent to the application specified
     * in the provisioning intent. The
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} broadcast is sent in the
     * managed profile and the {@link #ACTION_MANAGED_PROFILE_PROVISIONED} broadcast is sent in
     * the primary profile.
     *
     * <p>From version {@link android.os.Build.VERSION_CODES#O}, when managed provisioning has
     * completed, along with the above broadcast, activity intent
     * {@link #ACTION_PROVISIONING_SUCCESSFUL} will also be sent to the profile owner.
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
     * <li>{@link #EXTRA_PROVISIONING_DISCLAIMERS}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS}, optional</li>
     * </ul>
     *
     * <p>When device owner provisioning has completed, an intent of the type
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} is broadcast to the
     * device owner.
     *
     * <p>From version {@link android.os.Build.VERSION_CODES#O}, when device owner provisioning has
     * completed, along with the above broadcast, activity intent
     * {@link #ACTION_PROVISIONING_SUCCESSFUL} will also be sent to the device owner.
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
     * Activity action: launch when user provisioning completed, i.e.
     * {@link #getUserProvisioningState()} returns one of the complete state.
     *
     * <p> Please note that the API behavior is not necessarily consistent across various releases,
     * and devices, as it's contract between SetupWizard and ManagedProvisioning. The default
     * implementation is that ManagedProvisioning launches SetupWizard in NFC provisioning only.
     *
     * <p> The activity must be protected by permission
     * {@link android.Manifest.permission#BIND_DEVICE_ADMIN}, and the process must hold
     * {@link android.Manifest.permission#DISPATCH_PROVISIONING_MESSAGE} to be launched.
     * Only one {@link ComponentName} in the entire system should be enabled, and the rest of the
     * components are not started by this intent.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_STATE_USER_SETUP_COMPLETE =
            "android.app.action.STATE_USER_SETUP_COMPLETE";

    /**
     * Activity action: Starts the provisioning flow which sets up a managed device.
     *
     * <p>During device owner provisioning, a device admin app is downloaded and set as the owner of
     * the device. A device owner has full control over the device. The device owner can not be
     * modified by the user and the only way of resetting the device is via factory reset.
     *
     * <p>From version {@link android.os.Build.VERSION_CODES#Q}, the admin app can choose
     * whether to set up a fully managed device or a managed profile. For the admin app to support
     * this, it must have an activity with intent filter {@link #ACTION_GET_PROVISIONING_MODE} and
     * another one with intent filter {@link #ACTION_ADMIN_POLICY_COMPLIANCE}. For example:
     * <pre>
     * &lt;activity
     *     android:name=".GetProvisioningModeActivity"
     *     android:label="@string/app_name"
     *     android:permission="android.permission.BIND_DEVICE_ADMIN"&gt;
     *     &lt;intent-filter&gt;
     *         &lt;action
     *             android:name="android.app.action.GET_PROVISIONING_MODE" /&gt;
     *         &lt;category android:name="android.intent.category.DEFAULT" /&gt;
     *     &lt;/intent-filter&gt;
     * &lt;/activity&gt;
     *
     * &lt;activity
     *     android:name=".PolicyComplianceActivity"
     *     android:label="@string/app_name"
     *     android:permission="android.permission.BIND_DEVICE_ADMIN"&gt;
     *     &lt;intent-filter&gt;
     *         &lt;action
     *             android:name="android.app.action.ADMIN_POLICY_COMPLIANCE" /&gt;
     *         &lt;category android:name="android.intent.category.DEFAULT" /&gt;
     *     &lt;/intent-filter&gt;
     * &lt;/activity&gt;</pre>
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
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_LABEL}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_ICON_URI}, optional</li>
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
     * <li>{@link #EXTRA_PROVISIONING_SUPPORT_URL}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ORGANIZATION_NAME}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_USE_MOBILE_DATA}, optional </li>
     * <li>{@link #EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS}, optional - when not used for
     * cloud enrollment, NFC or QR provisioning</li>
     * </ul>
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE =
            "android.app.action.PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE";

    /**
     * Activity action: Starts the provisioning flow which sets up a financed device.
     *
     * <p>During financed device provisioning, a device admin app is downloaded and set as the owner
     * of the device. A device owner has full control over the device. The device owner can not be
     * modified by the user.
     *
     * <p>A typical use case would be a device that is bought from the reseller through financing
     * program.
     *
     * <p>An intent with this action can be sent only on an unprovisioned device.
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
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_LABEL}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_ICON_URI}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_SUPPORT_URL}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ORGANIZATION_NAME}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional</li>
     * </ul>
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_PROVISION_FINANCED_DEVICE =
            "android.app.action.PROVISION_FINANCED_DEVICE";

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
     * <p>From version {@link android.os.Build.VERSION_CODES#O}, when device owner provisioning has
     * completed, along with the above broadcast, activity intent
     * {@link #ACTION_PROVISIONING_SUCCESSFUL} will also be sent to the device owner.
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
            "com.android.server.action.REMOTE_BUGREPORT_SHARING_ACCEPTED";

    /**
     * Action: Bugreport sharing with device owner has been declined by the user.
     *
     * @hide
     */
    public static final String ACTION_BUGREPORT_SHARING_DECLINED =
            "com.android.server.action.REMOTE_BUGREPORT_SHARING_DECLINED";

    /**
     * Action: Bugreport has been collected and is dispatched to {@code DevicePolicyManagerService}.
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
     * Default and maximum timeout in milliseconds after which unlocking with weak auth times out,
     * i.e. the user has to use a strong authentication method like password, PIN or pattern.
     *
     * @hide
     */
    public static final long DEFAULT_STRONG_AUTH_TIMEOUT_MS = 72 * 60 * 60 * 1000; // 72h

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
     * Boolean extra to indicate that the migrated account should be kept. This is used in
     * conjunction with {@link #EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE}. If it's set to {@code true},
     * the account will not be removed from the primary user after it is migrated to the newly
     * created user or profile.
     *
     * <p> Defaults to {@code false}
     *
     * <p> Use with {@link #ACTION_PROVISION_MANAGED_PROFILE} and
     * {@link #EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE}
     */
    public static final String EXTRA_PROVISIONING_KEEP_ACCOUNT_ON_MIGRATION
            = "android.app.extra.PROVISIONING_KEEP_ACCOUNT_ON_MIGRATION";

    /**
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}, never used while provisioning the
     * device.
     */
    @Deprecated
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
     * {@link #EXTRA_PROVISIONING_WIFI_SSID} and could be one of {@code NONE}, {@code WPA},
     * {@code WEP} or {@code EAP}.
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
    public static final String EXTRA_PROVISIONING_WIFI_PASSWORD =
            "android.app.extra.PROVISIONING_WIFI_PASSWORD";

    /**
     * The EAP method of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}
     * and could be one of {@code PEAP}, {@code TLS}, {@code TTLS}, {@code PWD}, {@code SIM},
     * {@code AKA} or {@code AKA_PRIME}. This is only used if the
     * {@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_EAP_METHOD =
            "android.app.extra.PROVISIONING_WIFI_EAP_METHOD";

    /**
     * The phase 2 authentication of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}
     * and could be one of {@code NONE}, {@code PAP}, {@code MSCHAP}, {@code MSCHAPV2}, {@code GTC},
     * {@code SIM}, {@code AKA} or {@code AKA_PRIME}. This is only used if the
     * {@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PHASE2_AUTH =
            "android.app.extra.PROVISIONING_WIFI_PHASE2_AUTH";

    /**
     * The CA certificate of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}. This should
     * be an X.509 certificate Base64 encoded DER format, ie. PEM representation of a certificate
     * without header, footer and line breaks. <a href=
     * "https://tools.ietf.org/html/rfc7468"> More information</a> This is only
     * used if the {@link
     * #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_CA_CERTIFICATE =
            "android.app.extra.PROVISIONING_WIFI_CA_CERTIFICATE";

    /**
     * The user certificate of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}. This
     * should be an X.509 certificate and private key Base64 encoded DER format, ie. PEM
     * representation of a certificate and key without header, footer and line breaks. <a href=
     * "https://tools.ietf.org/html/rfc7468"> More information</a> This is only
     * used if the {@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_USER_CERTIFICATE =
            "android.app.extra.PROVISIONING_WIFI_USER_CERTIFICATE";

    /**
     * The identity of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}. This is only used
     * if the {@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_IDENTITY =
            "android.app.extra.PROVISIONING_WIFI_IDENTITY";

    /**
     * The anonymous identity of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}. This is
     * only used if the {@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */

    public static final String EXTRA_PROVISIONING_WIFI_ANONYMOUS_IDENTITY =
            "android.app.extra.PROVISIONING_WIFI_ANONYMOUS_IDENTITY";
    /**
     * The domain of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}. This is only used if
     * the {@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_DOMAIN =
            "android.app.extra.PROVISIONING_WIFI_DOMAIN";

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
     * A String extra holding the localized name of the organization under management.
     *
     * The name is displayed only during provisioning.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}
     * or {@link #ACTION_PROVISION_FINANCED_DEVICE}
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_ORGANIZATION_NAME =
            "android.app.extra.PROVISIONING_ORGANIZATION_NAME";

    /**
     * A String extra holding a url to the website of the device provider so the user can open it
     * during provisioning. If the url is not HTTPS, an error will be shown.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}
     * or {@link #ACTION_PROVISION_FINANCED_DEVICE}
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_SUPPORT_URL =
            "android.app.extra.PROVISIONING_SUPPORT_URL";

    /**
     * A String extra holding the localized name of the device admin package. It should be the same
     * as the app label of the package.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}
     * or {@link #ACTION_PROVISION_FINANCED_DEVICE}
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_LABEL =
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_LABEL";

    /**
     * A {@link Uri} extra pointing to the app icon of device admin package. This image will be
     * shown during the provisioning.
     * <h5>The following URI schemes are accepted:</h5>
     * <ul>
     * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
     * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})</li>
     * </ul>
     *
     * <p> It is the responsibility of the caller to provide an image with a reasonable
     * pixel density for the device.
     *
     * <p> If a content: URI is passed, the intent should have the flag
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} and the uri should be added to the
     * {@link android.content.ClipData} of the intent too.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}
     * or {@link #ACTION_PROVISION_FINANCED_DEVICE}
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_ICON_URI =
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_ICON_URI";

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
     * A String extra holding the URL-safe base64 encoded SHA-256 hash of the file at download
     * location specified in {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}.
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
     * addition to SHA-1. From {@link android.os.Build.VERSION_CODES#Q}, only SHA-256 hash is
     * supported.
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
     * <p>This intent will contain the following extras
     * <ul>
     * <li>{@link Intent#EXTRA_USER}, corresponds to the {@link UserHandle} of the managed
     * profile.</li>
     * <li>{@link #EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE}, corresponds to the account requested to
     * be migrated at provisioning time, if any.</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGED_PROFILE_PROVISIONED
        = "android.app.action.MANAGED_PROFILE_PROVISIONED";

    /**
     * Activity action: This activity action is sent to indicate that provisioning of a managed
     * profile or managed device has completed successfully. It'll be sent at the same time as
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} broadcast but this will be
     * delivered faster as it's an activity intent.
     *
     * <p>The intent is only sent to the new device or profile owner.
     *
     * @see #ACTION_PROVISION_MANAGED_PROFILE
     * @see #ACTION_PROVISION_MANAGED_DEVICE
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISIONING_SUCCESSFUL =
            "android.app.action.PROVISIONING_SUCCESSFUL";

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
     * <p> It is the responsibility of the caller to provide an image with a reasonable
     * pixel density for the device.
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
     * A {@link Bundle}[] extra consisting of list of disclaimer headers and disclaimer contents.
     * Each {@link Bundle} must have both {@link #EXTRA_PROVISIONING_DISCLAIMER_HEADER}
     * as disclaimer header, and {@link #EXTRA_PROVISIONING_DISCLAIMER_CONTENT} as disclaimer
     * content.
     *
     * <p> The extra typically contains one disclaimer from the company of mobile device
     * management application (MDM), and one disclaimer from the organization.
     *
     * <p> Call {@link Bundle#putParcelableArray(String, Parcelable[])} to put the {@link Bundle}[]
     *
     * <p> Maximum 3 key-value pairs can be specified. The rest will be ignored.
     *
     * <p> Use in an intent with action {@link #ACTION_PROVISION_MANAGED_PROFILE} or
     * {@link #ACTION_PROVISION_MANAGED_DEVICE}
     */
    public static final String EXTRA_PROVISIONING_DISCLAIMERS =
            "android.app.extra.PROVISIONING_DISCLAIMERS";

    /**
     * A String extra of localized disclaimer header.
     *
     * <p> The extra is typically the company name of mobile device management application (MDM)
     * or the organization name.
     *
     * <p> Use in Bundle {@link #EXTRA_PROVISIONING_DISCLAIMERS}
     *
     * <p> System app, i.e. application with {@link ApplicationInfo#FLAG_SYSTEM}, can also insert a
     * disclaimer by declaring an application-level meta-data in {@code AndroidManifest.xml}.
     * Must use it with {@link #EXTRA_PROVISIONING_DISCLAIMER_CONTENT}. Here is the example:
     *
     * <pre>
     *  &lt;meta-data
     *      android:name="android.app.extra.PROVISIONING_DISCLAIMER_HEADER"
     *      android:resource="@string/disclaimer_header"
     * /&gt;</pre>
     */
    public static final String EXTRA_PROVISIONING_DISCLAIMER_HEADER =
            "android.app.extra.PROVISIONING_DISCLAIMER_HEADER";

    /**
     * A {@link Uri} extra pointing to disclaimer content.
     *
     * <h5>The following URI schemes are accepted:</h5>
     * <ul>
     * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
     * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})</li>
     * </ul>
     *
     * <p> Styled text is supported in the disclaimer content. The content is parsed by
     * {@link android.text.Html#fromHtml(String)} and displayed in a
     * {@link android.widget.TextView}.
     *
     * <p> If a <code>content:</code> URI is passed, URI is passed, the intent should have the flag
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} and the uri should be added to the
     * {@link android.content.ClipData} of the intent too.
     *
     * <p> Use in Bundle {@link #EXTRA_PROVISIONING_DISCLAIMERS}
     *
     * <p> System app, i.e. application with {@link ApplicationInfo#FLAG_SYSTEM}, can also insert a
     * disclaimer by declaring an application-level meta-data in {@code AndroidManifest.xml}.
     * Must use it with {@link #EXTRA_PROVISIONING_DISCLAIMER_HEADER}. Here is the example:
     *
     * <pre>
     *  &lt;meta-data
     *      android:name="android.app.extra.PROVISIONING_DISCLAIMER_CONTENT"
     *      android:resource="@string/disclaimer_content"
     * /&gt;</pre>
     */
    public static final String EXTRA_PROVISIONING_DISCLAIMER_CONTENT =
            "android.app.extra.PROVISIONING_DISCLAIMER_CONTENT";

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
     * A boolean extra indicating if the user consent steps from the provisioning flow should be
     * skipped. If unspecified, defaults to {@code false}.
     *
     * It can only be used by an existing device owner trying to create a managed profile via
     * {@link #ACTION_PROVISION_MANAGED_PROFILE}. Otherwise it is ignored.
     */
    public static final String EXTRA_PROVISIONING_SKIP_USER_CONSENT =
            "android.app.extra.PROVISIONING_SKIP_USER_CONSENT";

    /**
     * A boolean extra indicating if the education screens from the provisioning flow should be
     * skipped. If unspecified, defaults to {@code false}.
     *
     * <p>This extra can be set in the following ways:
     * <ul>
     * <li>By the admin app when performing the admin-integrated
     * provisioning flow as a result of the {@link #ACTION_GET_PROVISIONING_MODE} activity</li>
     * <li>With intent action {@link #ACTION_PROVISION_MANAGED_DEVICE}</li>
     * </ul>
     *
     * <p>If the education screens are skipped, it is the admin application's responsibility
     * to display its own user education screens.
     */
    public static final String EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS =
            "android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS";

    /**
     * A boolean extra indicating if mobile data should be used during NFC device owner provisioning
     * for downloading the mobile device management application. If {@link
     * #EXTRA_PROVISIONING_WIFI_SSID} is also specified, wifi network will be used instead.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     *
     * @hide
     */
    public static final String EXTRA_PROVISIONING_USE_MOBILE_DATA =
            "android.app.extra.PROVISIONING_USE_MOBILE_DATA";

    /**
     * A String extra holding the provisioning trigger. It could be one of
     * {@link #PROVISIONING_TRIGGER_CLOUD_ENROLLMENT}, {@link #PROVISIONING_TRIGGER_QR_CODE},
     * {@link #PROVISIONING_TRIGGER_PERSISTENT_DEVICE_OWNER} or {@link
     * #PROVISIONING_TRIGGER_UNSPECIFIED}.
     *
     * <p>Use in an intent with action {@link
     * #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_TRIGGER =
            "android.app.extra.PROVISIONING_TRIGGER";

    /**
     * A value for {@link #EXTRA_PROVISIONING_TRIGGER} indicating that the provisioning
     * trigger has not been specified.
     * @see #PROVISIONING_TRIGGER_CLOUD_ENROLLMENT
     * @see #PROVISIONING_TRIGGER_QR_CODE
     * @see #PROVISIONING_TRIGGER_PERSISTENT_DEVICE_OWNER
     * @hide
     */
    @SystemApi
    public static final int PROVISIONING_TRIGGER_UNSPECIFIED = 0;

    /**
     * A value for {@link #EXTRA_PROVISIONING_TRIGGER} indicating that the provisioning
     * trigger is cloud enrollment.
     * @see #PROVISIONING_TRIGGER_QR_CODE
     * @see #PROVISIONING_TRIGGER_PERSISTENT_DEVICE_OWNER
     * @see #PROVISIONING_TRIGGER_UNSPECIFIED
     * @hide
     */
    @SystemApi
    public static final int PROVISIONING_TRIGGER_CLOUD_ENROLLMENT = 1;

    /**
     * A value for {@link #EXTRA_PROVISIONING_TRIGGER} indicating that the provisioning
     * trigger is the QR code scanner.
     * @see #PROVISIONING_TRIGGER_CLOUD_ENROLLMENT
     * @see #PROVISIONING_TRIGGER_PERSISTENT_DEVICE_OWNER
     * @see #PROVISIONING_TRIGGER_UNSPECIFIED
     * @hide
     */
    @SystemApi
    public static final int PROVISIONING_TRIGGER_QR_CODE = 2;

    /**
     * A value for {@link #EXTRA_PROVISIONING_TRIGGER} indicating that the provisioning
     * trigger is persistent device owner enrollment.
     * @see #PROVISIONING_TRIGGER_CLOUD_ENROLLMENT
     * @see #PROVISIONING_TRIGGER_QR_CODE
     * @see #PROVISIONING_TRIGGER_UNSPECIFIED
     * @hide
     */
    @SystemApi
    public static final int PROVISIONING_TRIGGER_PERSISTENT_DEVICE_OWNER = 3;

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
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#M} </li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_EAP_METHOD}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PHASE2_AUTH}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_CA_CERTIFICATE}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_USER_CERTIFICATE}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_IDENTITY}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_ANONYMOUS_IDENTITY}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_DOMAIN}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li></ul>
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
     * If the profile owner of an organization-owned managed profile changes some user
     * restriction explicitly on the parent user, this broadcast will <em>not</em> be
     * sent to the parent user.
     * @hide
     */
    @UnsupportedAppUsage
    public static final String ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
            = "android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED";

    /**
     * Broadcast action: sent when the device owner is set, changed or cleared.
     *
     * This broadcast is sent only to the primary user.
     * @see #ACTION_PROVISION_MANAGED_DEVICE
     * @see DevicePolicyManager#transferOwnership(ComponentName, ComponentName, PersistableBundle)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_OWNER_CHANGED
            = "android.app.action.DEVICE_OWNER_CHANGED";

    /**
     * Broadcast action: sent when the factory reset protection (FRP) policy is changed.
     *
     * @see #setFactoryResetProtectionPolicy
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_FACTORY_RESET_PROTECTION)
    @SystemApi
    public static final String ACTION_RESET_PROTECTION_POLICY_CHANGED =
            "android.app.action.RESET_PROTECTION_POLICY_CHANGED";

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
     * Constant to indicate the feature of disabling the camera. Used as argument to
     * {@link #createAdminSupportIntent(String)}.
     * @see #setCameraDisabled(ComponentName, boolean)
     */
    public static final String POLICY_DISABLE_CAMERA = "policy_disable_camera";

    /**
     * Constant to indicate the feature of disabling screen captures. Used as argument to
     * {@link #createAdminSupportIntent(String)}.
     * @see #setScreenCaptureDisabled(ComponentName, boolean)
     */
    public static final String POLICY_DISABLE_SCREEN_CAPTURE = "policy_disable_screen_capture";

    /**
     * Constant to indicate the feature of suspending app. Use it as the value of
     * {@link #EXTRA_RESTRICTION}.
     * @hide
     */
    public static final String POLICY_SUSPEND_PACKAGES = "policy_suspend_packages";

    /**
     * A String indicating a specific restricted feature. Can be a user restriction from the
     * {@link UserManager}, e.g. {@link UserManager#DISALLOW_ADJUST_VOLUME}, or one of the values
     * {@link #POLICY_DISABLE_CAMERA} or {@link #POLICY_DISABLE_SCREEN_CAPTURE}.
     * @see #createAdminSupportIntent(String)
     * @hide
     */
    @TestApi @SystemApi
    public static final String EXTRA_RESTRICTION = "android.app.extra.RESTRICTION";

    /**
     * Activity action: have the user enter a new password.
     *
     * <p>For admin apps, this activity should be launched after using {@link
     * #setPasswordQuality(ComponentName, int)}, or {@link
     * #setPasswordMinimumLength(ComponentName, int)} to have the user enter a new password that
     * meets the current requirements. You can use {@link #isActivePasswordSufficient()} to
     * determine whether you need to have the user select a new password in order to meet the
     * current constraints. Upon being resumed from this activity, you can check the new
     * password characteristics to see if they are sufficient.
     *
     * <p>Non-admin apps can use {@link #getPasswordComplexity()} to check the current screen lock
     * complexity, and use this activity with extra {@link #EXTRA_PASSWORD_COMPLEXITY} to suggest
     * to users how complex the app wants the new screen lock to be. Note that both {@link
     * #getPasswordComplexity()} and the extra {@link #EXTRA_PASSWORD_COMPLEXITY} require the
     * calling app to have the permission {@link permission#REQUEST_PASSWORD_COMPLEXITY}.
     *
     * <p>If the intent is launched from within a managed profile with a profile
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
     * An integer indicating the complexity level of the new password an app would like the user to
     * set when launching the action {@link #ACTION_SET_NEW_PASSWORD}.
     *
     * <p>Must be one of
     * <ul>
     *     <li>{@link #PASSWORD_COMPLEXITY_HIGH}
     *     <li>{@link #PASSWORD_COMPLEXITY_MEDIUM}
     *     <li>{@link #PASSWORD_COMPLEXITY_LOW}
     *     <li>{@link #PASSWORD_COMPLEXITY_NONE}
     * </ul>
     *
     * <p>If an invalid value is used, it will be treated as {@link #PASSWORD_COMPLEXITY_NONE}.
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_PASSWORD_COMPLEXITY)
    public static final String EXTRA_PASSWORD_COMPLEXITY =
            "android.app.extra.PASSWORD_COMPLEXITY";

    /**
     * Constant for {@link #getPasswordComplexity()}: no password.
     *
     * <p>Note that these complexity constants are ordered so that higher values are more complex.
     */
    public static final int PASSWORD_COMPLEXITY_NONE = 0;

    /**
     * Constant for {@link #getPasswordComplexity()}: password satisfies one of the following:
     * <ul>
     * <li>pattern
     * <li>PIN with repeating (4444) or ordered (1234, 4321, 2468) sequences
     * </ul>
     *
     * <p>Note that these complexity constants are ordered so that higher values are more complex.
     *
     * @see #PASSWORD_QUALITY_SOMETHING
     * @see #PASSWORD_QUALITY_NUMERIC
     */
    public static final int PASSWORD_COMPLEXITY_LOW = 0x10000;

    /**
     * Constant for {@link #getPasswordComplexity()}: password satisfies one of the following:
     * <ul>
     * <li>PIN with <b>no</b> repeating (4444) or ordered (1234, 4321, 2468) sequences, length at
     * least 4
     * <li>alphabetic, length at least 4
     * <li>alphanumeric, length at least 4
     * </ul>
     *
     * <p>Note that these complexity constants are ordered so that higher values are more complex.
     *
     * @see #PASSWORD_QUALITY_NUMERIC_COMPLEX
     * @see #PASSWORD_QUALITY_ALPHABETIC
     * @see #PASSWORD_QUALITY_ALPHANUMERIC
     */
    public static final int PASSWORD_COMPLEXITY_MEDIUM = 0x30000;

    /**
     * Constant for {@link #getPasswordComplexity()}: password satisfies one of the following:
     * <ul>
     * <li>PIN with <b>no</b> repeating (4444) or ordered (1234, 4321, 2468) sequences, length at
     * least 8
     * <li>alphabetic, length at least 6
     * <li>alphanumeric, length at least 6
     * </ul>
     *
     * <p>Note that these complexity constants are ordered so that higher values are more complex.
     *
     * @see #PASSWORD_QUALITY_NUMERIC_COMPLEX
     * @see #PASSWORD_QUALITY_ALPHABETIC
     * @see #PASSWORD_QUALITY_ALPHANUMERIC
     */
    public static final int PASSWORD_COMPLEXITY_HIGH = 0x50000;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"PASSWORD_COMPLEXITY_"}, value = {
            PASSWORD_COMPLEXITY_NONE,
            PASSWORD_COMPLEXITY_LOW,
            PASSWORD_COMPLEXITY_MEDIUM,
            PASSWORD_COMPLEXITY_HIGH,
    })
    public @interface PasswordComplexity {}

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
     * Broadcast action: Tell the status bar to open the device monitoring dialog, e.g. when
     * Network logging was enabled and the user tapped the notification.
     * <p class="note">This is a protected intent that can only be sent by the system.</p>
     * @hide
     */
    public static final String ACTION_SHOW_DEVICE_MONITORING_DIALOG
            = "android.app.action.SHOW_DEVICE_MONITORING_DIALOG";

    /**
     * Broadcast Action: Sent after application delegation scopes are changed. The new delegation
     * scopes will be sent in an {@code ArrayList<String>} extra identified by the
     * {@link #EXTRA_DELEGATION_SCOPES} key.
     *
     * <p class="note"><b>Note:</b> This is a protected intent that can only be sent by the
     * system.</p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_APPLICATION_DELEGATION_SCOPES_CHANGED =
            "android.app.action.APPLICATION_DELEGATION_SCOPES_CHANGED";

    /**
     * An {@code ArrayList<String>} corresponding to the delegation scopes given to an app in the
     * {@link #ACTION_APPLICATION_DELEGATION_SCOPES_CHANGED} broadcast.
     */
    public static final String EXTRA_DELEGATION_SCOPES = "android.app.extra.DELEGATION_SCOPES";

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
     * Broadcast action to notify ManagedProvisioning that
     * {@link UserManager#DISALLOW_SHARE_INTO_MANAGED_PROFILE} restriction has changed.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DATA_SHARING_RESTRICTION_CHANGED =
            "android.app.action.DATA_SHARING_RESTRICTION_CHANGED";

    /**
     * Broadcast action from ManagedProvisioning to notify that the latest change to
     * {@link UserManager#DISALLOW_SHARE_INTO_MANAGED_PROFILE} restriction has been successfully
     * applied (cross profile intent filters updated). Only usesd for CTS tests.
     * @hide
     */
    @TestApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DATA_SHARING_RESTRICTION_APPLIED =
            "android.app.action.DATA_SHARING_RESTRICTION_APPLIED";

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
     * Possible policy values for permissions.
     *
     * @hide
     */
    @IntDef(prefix = { "PERMISSION_GRANT_STATE_" }, value = {
            PERMISSION_GRANT_STATE_DEFAULT,
            PERMISSION_GRANT_STATE_GRANTED,
            PERMISSION_GRANT_STATE_DENIED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionGrantState {}

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
     * Delegation of certificate installation and management. This scope grants access to the
     * {@link #getInstalledCaCerts}, {@link #hasCaCertInstalled}, {@link #installCaCert},
     * {@link #uninstallCaCert}, {@link #uninstallAllUserCaCerts} and {@link #installKeyPair} APIs.
     */
    public static final String DELEGATION_CERT_INSTALL = "delegation-cert-install";

    /**
     * Delegation of application restrictions management. This scope grants access to the
     * {@link #setApplicationRestrictions} and {@link #getApplicationRestrictions} APIs.
     */
    public static final String DELEGATION_APP_RESTRICTIONS = "delegation-app-restrictions";

    /**
     * Delegation of application uninstall block. This scope grants access to the
     * {@link #setUninstallBlocked} API.
     */
    public static final String DELEGATION_BLOCK_UNINSTALL = "delegation-block-uninstall";

    /**
     * Delegation of permission policy and permission grant state. This scope grants access to the
     * {@link #setPermissionPolicy}, {@link #getPermissionGrantState},
     * and {@link #setPermissionGrantState} APIs.
     */
    public static final String DELEGATION_PERMISSION_GRANT = "delegation-permission-grant";

    /**
     * Delegation of package access state. This scope grants access to the
     * {@link #isApplicationHidden}, {@link #setApplicationHidden}, {@link #isPackageSuspended}, and
     * {@link #setPackagesSuspended} APIs.
     */
    public static final String DELEGATION_PACKAGE_ACCESS = "delegation-package-access";

    /**
     * Delegation for enabling system apps. This scope grants access to the {@link #enableSystemApp}
     * API.
     */
    public static final String DELEGATION_ENABLE_SYSTEM_APP = "delegation-enable-system-app";

    /**
     * Delegation for installing existing packages. This scope grants access to the
     * {@link #installExistingPackage} API.
     */
    public static final String DELEGATION_INSTALL_EXISTING_PACKAGE =
            "delegation-install-existing-package";

    /**
     * Delegation of management of uninstalled packages. This scope grants access to the
     * {@link #setKeepUninstalledPackages} and {@link #getKeepUninstalledPackages} APIs.
     */
    public static final String DELEGATION_KEEP_UNINSTALLED_PACKAGES =
            "delegation-keep-uninstalled-packages";

    /**
     * Grants access to {@link #setNetworkLoggingEnabled}, {@link #isNetworkLoggingEnabled} and
     * {@link #retrieveNetworkLogs}. Once granted the delegated app will start receiving
     * DelegatedAdminReceiver.onNetworkLogsAvailable() callback, and Device owner will no longer
     * receive the DeviceAdminReceiver.onNetworkLogsAvailable() callback.
     * There can be at most one app that has this delegation.
     * If another app already had delegated network logging access,
     * it will lose the delegation when a new app is delegated.
     *
     * <p> Can only be granted by Device Owner.
     */
    public static final String DELEGATION_NETWORK_LOGGING = "delegation-network-logging";

    /**
     * Grants access to selection of KeyChain certificates on behalf of requesting apps.
     * Once granted the app will start receiving
     * {@link DelegatedAdminReceiver#onChoosePrivateKeyAlias}. The caller (PO/DO) will
     * no longer receive {@link DeviceAdminReceiver#onChoosePrivateKeyAlias}.
     * There can be at most one app that has this delegation.
     * If another app already had delegated certificate selection access,
     * it will lose the delegation when a new app is delegated.
     * <p> The delegaetd app can also call {@link #grantKeyPairToApp} and
     * {@link #revokeKeyPairFromApp} to directly grant KeyCain keys to other apps.
     * <p> Can be granted by Device Owner or Profile Owner.
     */
    public static final String DELEGATION_CERT_SELECTION = "delegation-cert-selection";

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
    @IntDef(prefix = { "STATE_USER_" }, value = {
            STATE_USER_UNMANAGED,
            STATE_USER_SETUP_INCOMPLETE,
            STATE_USER_SETUP_COMPLETE,
            STATE_USER_SETUP_FINALIZED,
            STATE_USER_PROFILE_COMPLETE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UserProvisioningState {}

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE},
     * {@link #ACTION_PROVISION_MANAGED_PROFILE}, {@link #ACTION_PROVISION_MANAGED_USER} and
     * {@link #ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE} when provisioning is allowed.
     *
     * @hide
     */
    public static final int CODE_OK = 0;

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE} and
     * {@link #ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE} when the device already has a device
     * owner.
     *
     * @hide
     */
    public static final int CODE_HAS_DEVICE_OWNER = 1;

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE},
     * {@link #ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE} when the user has a profile owner and for
     * {@link #ACTION_PROVISION_MANAGED_PROFILE} when the profile owner is already set.
     *
     * @hide
     */
    public static final int CODE_USER_HAS_PROFILE_OWNER = 2;

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE} and
     * {@link #ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE} when the user isn't running.
     *
     * @hide
     */
    public static final int CODE_USER_NOT_RUNNING = 3;

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE},
     * {@link #ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE} if the device has already been setup and
     * for {@link #ACTION_PROVISION_MANAGED_USER} if the user has already been setup.
     *
     * @hide
     */
    public static final int CODE_USER_SETUP_COMPLETED = 4;

    /**
     * Code used to indicate that the device also has a user other than the system user.
     *
     * @hide
     */
    public static final int CODE_NONSYSTEM_USER_EXISTS = 5;

    /**
     * Code used to indicate that device has an account that prevents provisioning.
     *
     * @hide
     */
    public static final int CODE_ACCOUNTS_NOT_EMPTY = 6;

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE} and
     * {@link #ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE} if the user is not a system user.
     *
     * @hide
     */
    public static final int CODE_NOT_SYSTEM_USER = 7;

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE},
     * {@link #ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE} and {@link #ACTION_PROVISION_MANAGED_USER}
     * when the device is a watch and is already paired.
     *
     * @hide
     */
    public static final int CODE_HAS_PAIRED = 8;

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_PROFILE} and
     * {@link #ACTION_PROVISION_MANAGED_USER} on devices which do not support managed users.
     *
     * @see {@link PackageManager#FEATURE_MANAGED_USERS}
     * @hide
     */
    public static final int CODE_MANAGED_USERS_NOT_SUPPORTED = 9;

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_USER} if the user is a system user.
     *
     * @hide
     */
    public static final int CODE_SYSTEM_USER = 10;

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_PROFILE} when the user cannot have more
     * managed profiles.
     *
     * @hide
     */
    public static final int CODE_CANNOT_ADD_MANAGED_PROFILE = 11;

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_USER} and
     * {@link #ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE} on devices not running with split system
     * user.
     *
     * @hide
     */
    public static final int CODE_NOT_SYSTEM_USER_SPLIT = 12;

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE},
     * {@link #ACTION_PROVISION_MANAGED_PROFILE}, {@link #ACTION_PROVISION_MANAGED_USER} and
     * {@link #ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE} on devices which do no support device
     * admins.
     *
     * @hide
     */
    public static final int CODE_DEVICE_ADMIN_NOT_SUPPORTED = 13;

    /**
     * Result code for {@link #checkProvisioningPreCondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_PROFILE} when the device the user is a
     * system user on a split system user device.
     *
     * @hide
     */
    public static final int CODE_SPLIT_SYSTEM_USER_DEVICE_SYSTEM_USER = 14;

    /**
     * Result codes for {@link #checkProvisioningPreCondition} indicating all the provisioning pre
     * conditions.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "CODE_" }, value = {
            CODE_OK, CODE_HAS_DEVICE_OWNER, CODE_USER_HAS_PROFILE_OWNER, CODE_USER_NOT_RUNNING,
            CODE_USER_SETUP_COMPLETED, CODE_NOT_SYSTEM_USER, CODE_HAS_PAIRED,
            CODE_MANAGED_USERS_NOT_SUPPORTED, CODE_SYSTEM_USER, CODE_CANNOT_ADD_MANAGED_PROFILE,
            CODE_NOT_SYSTEM_USER_SPLIT, CODE_DEVICE_ADMIN_NOT_SUPPORTED,
            CODE_SPLIT_SYSTEM_USER_DEVICE_SYSTEM_USER
    })
    public @interface ProvisioningPreCondition {}

    /**
     * Disable all configurable SystemUI features during LockTask mode. This includes,
     * <ul>
     *     <li>system info area in the status bar (connectivity icons, clock, etc.)
     *     <li>notifications (including alerts, icons, and the notification shade)
     *     <li>Home button
     *     <li>Recents button and UI
     *     <li>global actions menu (i.e. power button menu)
     *     <li>keyguard
     * </ul>
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_NONE = 0;

    /**
     * Enable the system info area in the status bar during LockTask mode. The system info area
     * usually occupies the right side of the status bar (although this can differ across OEMs). It
     * includes all system information indicators, such as date and time, connectivity, battery,
     * vibration mode, etc.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_SYSTEM_INFO = 1;

    /**
     * Enable notifications during LockTask mode. This includes notification icons on the status
     * bar, heads-up notifications, and the expandable notification shade. Note that the Quick
     * Settings panel remains disabled. This feature flag can only be used in combination with
     * {@link #LOCK_TASK_FEATURE_HOME}. {@link #setLockTaskFeatures(ComponentName, int)}
     * throws an {@link IllegalArgumentException} if this feature flag is defined without
     * {@link #LOCK_TASK_FEATURE_HOME}.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_NOTIFICATIONS = 1 << 1;

    /**
     * Enable the Home button during LockTask mode. Note that if a custom launcher is used, it has
     * to be registered as the default launcher with
     * {@link #addPersistentPreferredActivity(ComponentName, IntentFilter, ComponentName)}, and its
     * package needs to be whitelisted for LockTask with
     * {@link #setLockTaskPackages(ComponentName, String[])}.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_HOME = 1 << 2;

    /**
     * Enable the Overview button and the Overview screen during LockTask mode. This feature flag
     * can only be used in combination with {@link #LOCK_TASK_FEATURE_HOME}, and
     * {@link #setLockTaskFeatures(ComponentName, int)} will throw an
     * {@link IllegalArgumentException} if this feature flag is defined without
     * {@link #LOCK_TASK_FEATURE_HOME}.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_OVERVIEW = 1 << 3;

    /**
     * Enable the global actions dialog during LockTask mode. This is the dialog that shows up when
     * the user long-presses the power button, for example. Note that the user may not be able to
     * power off the device if this flag is not set.
     *
     * <p>This flag is enabled by default until {@link #setLockTaskFeatures(ComponentName, int)} is
     * called for the first time.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_GLOBAL_ACTIONS = 1 << 4;

    /**
     * Enable the keyguard during LockTask mode. Note that if the keyguard is already disabled with
     * {@link #setKeyguardDisabled(ComponentName, boolean)}, setting this flag will have no effect.
     * If this flag is not set, the keyguard will not be shown even if the user has a lock screen
     * credential.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_KEYGUARD = 1 << 5;

    /**
     * Enable blocking of non-whitelisted activities from being started into a locked task.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK = 1 << 6;

    /**
     * Flags supplied to {@link #setLockTaskFeatures(ComponentName, int)}.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "LOCK_TASK_FEATURE_" }, value = {
            LOCK_TASK_FEATURE_NONE,
            LOCK_TASK_FEATURE_SYSTEM_INFO,
            LOCK_TASK_FEATURE_NOTIFICATIONS,
            LOCK_TASK_FEATURE_HOME,
            LOCK_TASK_FEATURE_OVERVIEW,
            LOCK_TASK_FEATURE_GLOBAL_ACTIONS,
            LOCK_TASK_FEATURE_KEYGUARD,
            LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK
    })
    public @interface LockTaskFeature {}

    /**
     * Service action: Action for a service that device owner and profile owner can optionally
     * own.  If a device owner or a profile owner has such a service, the system tries to keep
     * a bound connection to it, in order to keep their process always running.
     * The service must be protected with the {@link android.Manifest.permission#BIND_DEVICE_ADMIN}
     * permission.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_DEVICE_ADMIN_SERVICE
            = "android.app.action.DEVICE_ADMIN_SERVICE";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = {"ID_TYPE_"}, value = {
        ID_TYPE_BASE_INFO,
        ID_TYPE_SERIAL,
        ID_TYPE_IMEI,
        ID_TYPE_MEID,
        ID_TYPE_INDIVIDUAL_ATTESTATION
    })
    public @interface AttestationIdType {}

    /**
     * Specifies that the device should attest its manufacturer details. For use with
     * {@link #generateKeyPair}.
     *
     * @see #generateKeyPair
     */
    public static final int ID_TYPE_BASE_INFO = 1;

    /**
     * Specifies that the device should attest its serial number. For use with
     * {@link #generateKeyPair}.
     *
     * @see #generateKeyPair
     */
    public static final int ID_TYPE_SERIAL = 2;

    /**
     * Specifies that the device should attest its IMEI. For use with {@link #generateKeyPair}.
     *
     * @see #generateKeyPair
     */
    public static final int ID_TYPE_IMEI = 4;

    /**
     * Specifies that the device should attest its MEID. For use with {@link #generateKeyPair}.
     *
     * @see #generateKeyPair
     */
    public static final int ID_TYPE_MEID = 8;

    /**
     * Specifies that the device should attest using an individual attestation certificate.
     * For use with {@link #generateKeyPair}.
     *
     * @see #generateKeyPair
     */
    public static final int ID_TYPE_INDIVIDUAL_ATTESTATION = 16;

    /**
     * Service-specific error code for {@link #generateKeyPair}:
     * Indicates the call has failed due to StrongBox unavailability.
     * @hide
     */
    public static final int KEY_GEN_STRONGBOX_UNAVAILABLE = 1;

    /**
     * Specifies that the calling app should be granted access to the installed credentials
     * immediately. Otherwise, access to the credentials will be gated by user approval.
     * For use with {@link #installKeyPair(ComponentName, PrivateKey, Certificate[], String, int)}
     *
     * @see #installKeyPair(ComponentName, PrivateKey, Certificate[], String, int)
     */
    public static final int INSTALLKEY_REQUEST_CREDENTIALS_ACCESS = 1;

    /**
     * Specifies that a user can select the key via the Certificate Selection prompt.
     * If this flag is not set when calling {@link #installKeyPair}, the key can only be granted
     * access by implementing {@link android.app.admin.DeviceAdminReceiver#onChoosePrivateKeyAlias}.
     * For use with {@link #installKeyPair(ComponentName, PrivateKey, Certificate[], String, int)}
     *
     * @see #installKeyPair(ComponentName, PrivateKey, Certificate[], String, int)
     */
    public static final int INSTALLKEY_SET_USER_SELECTABLE = 2;

    /**
     * Broadcast action: sent when the profile owner is set, changed or cleared.
     *
     * This broadcast is sent only to the user managed by the new profile owner.
     * @see DevicePolicyManager#transferOwnership(ComponentName, ComponentName, PersistableBundle)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PROFILE_OWNER_CHANGED =
            "android.app.action.PROFILE_OWNER_CHANGED";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = {"PRIVATE_DNS_MODE_"}, value = {
            PRIVATE_DNS_MODE_UNKNOWN,
            PRIVATE_DNS_MODE_OFF,
            PRIVATE_DNS_MODE_OPPORTUNISTIC,
            PRIVATE_DNS_MODE_PROVIDER_HOSTNAME
    })
    public @interface PrivateDnsMode {}

    /**
     * Specifies that the Private DNS setting is in an unknown state.
     */
    public static final int PRIVATE_DNS_MODE_UNKNOWN = 0;

    /**
     * Specifies that Private DNS was turned off completely.
     */
    public static final int PRIVATE_DNS_MODE_OFF = 1;

    /**
     * Specifies that the device owner requested opportunistic DNS over TLS
     */
    public static final int PRIVATE_DNS_MODE_OPPORTUNISTIC = 2;

    /**
     * Specifies that the device owner configured a specific host to use for Private DNS.
     */
    public static final int PRIVATE_DNS_MODE_PROVIDER_HOSTNAME = 3;

    /**
     * Callback used in {@link #installSystemUpdate} to indicate that there was an error while
     * trying to install an update.
     */
    public abstract static class InstallSystemUpdateCallback {
        /** Represents an unknown error while trying to install an update. */
        public static final int UPDATE_ERROR_UNKNOWN = 1;

        /** Represents the update file being intended for different OS version. */
        public static final int UPDATE_ERROR_INCORRECT_OS_VERSION = 2;

        /**
         * Represents the update file being wrong; e.g. payloads are mismatched, or the wrong
         * compression method is used.
         */
        public static final int UPDATE_ERROR_UPDATE_FILE_INVALID = 3;

        /** Represents that the file could not be found. */
        public static final int UPDATE_ERROR_FILE_NOT_FOUND = 4;

        /** Represents the battery being too low to apply an update. */
        public static final int UPDATE_ERROR_BATTERY_LOW = 5;

        /**
         * Method invoked when there was an error while installing an update.
         *
         * <p>The given error message is not intended to be user-facing. It is intended to be
         * reported back to the IT admin to be read.
         */
        public void onInstallUpdateError(
                @InstallUpdateCallbackErrorConstants int errorCode, @NonNull String errorMessage) {
        }
    }

    /**
     * @hide
     */
    @IntDef(prefix = { "UPDATE_ERROR_" }, value = {
            InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN,
            InstallSystemUpdateCallback.UPDATE_ERROR_INCORRECT_OS_VERSION,
            InstallSystemUpdateCallback.UPDATE_ERROR_UPDATE_FILE_INVALID,
            InstallSystemUpdateCallback.UPDATE_ERROR_FILE_NOT_FOUND,
            InstallSystemUpdateCallback.UPDATE_ERROR_BATTERY_LOW
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InstallUpdateCallbackErrorConstants {}

    /**
     * The selected mode has been set successfully. If the mode is
     * {@code PRIVATE_DNS_MODE_PROVIDER_HOSTNAME} then it implies the supplied host is valid
     * and reachable.
     */
    public static final int PRIVATE_DNS_SET_NO_ERROR = 0;

    /**
     * If the {@code privateDnsHost} provided was of a valid hostname but that host was found
     * to not support DNS-over-TLS.
     */
    public static final int PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING = 1;

    /**
     * General failure to set the Private DNS mode, not due to one of the reasons listed above.
     */
    public static final int PRIVATE_DNS_SET_ERROR_FAILURE_SETTING = 2;

    /**
     * @hide
     */
    @IntDef(prefix = {"PRIVATE_DNS_SET_"}, value = {
            PRIVATE_DNS_SET_NO_ERROR,
            PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING,
            PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PrivateDnsModeErrorCodes {}

    /**
     * Activity action: Starts the administrator to get the mode for the provisioning.
     * This intent may contain the following extras:
     * <ul>
     *     <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}</li>
     *     <li>{@link #EXTRA_PROVISIONING_IMEI}</li>
     *     <li>{@link #EXTRA_PROVISIONING_SERIAL_NUMBER}</li>
     * </ul>
     *
     * <p>The target activity should return one of the following values in
     * {@link #EXTRA_PROVISIONING_MODE} as result:
     * <ul>
     *     <li>{@link #PROVISIONING_MODE_FULLY_MANAGED_DEVICE}</li>
     *     <li>{@link #PROVISIONING_MODE_MANAGED_PROFILE}</li>
     * </ul>
     *
     * <p>If performing fully-managed device provisioning and the admin app desires to show its
     * own education screens, the target activity can additionally return
     * {@link #EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS} set to <code>true</code>.
     *
     * <p>The target activity may also return the account that needs to be migrated from primary
     * user to managed profile in case of a profile owner provisioning in
     * {@link #EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE} as result.
     */
    public static final String ACTION_GET_PROVISIONING_MODE =
            "android.app.action.GET_PROVISIONING_MODE";

    /**
     * A string extra holding the IMEI (International Mobile Equipment Identity) of the device.
     */
    public static final String EXTRA_PROVISIONING_IMEI = "android.app.extra.PROVISIONING_IMEI";

    /**
     * A string extra holding the serial number of the device.
     */
    public static final String EXTRA_PROVISIONING_SERIAL_NUMBER =
            "android.app.extra.PROVISIONING_SERIAL_NUMBER";

    /**
     * An intent extra holding the provisioning mode returned by the administrator.
     * The value for this extra should be one of the following:
     * <ul>
     *     <li>{@link #PROVISIONING_MODE_FULLY_MANAGED_DEVICE}</li>
     *     <li>{@link #PROVISIONING_MODE_MANAGED_PROFILE}</li>
     * </ul>
     */
    public static final String EXTRA_PROVISIONING_MODE =
            "android.app.extra.PROVISIONING_MODE";

    /**
     * The provisioning mode for fully managed device.
     */
    public static final int PROVISIONING_MODE_FULLY_MANAGED_DEVICE = 1;

    /**
     * The provisioning mode for managed profile.
     */
    public static final int PROVISIONING_MODE_MANAGED_PROFILE = 2;

    /**
     * Activity action: Starts the administrator to show policy compliance for the provisioning.
     * This action is used any time that the administrator has an opportunity to show policy
     * compliance before the end of setup wizard. This could happen as part of the admin-integrated
     * provisioning flow (in which case this gets sent after {@link #ACTION_GET_PROVISIONING_MODE}),
     * or it could happen during provisioning finalization if the administrator supports
     * finalization during setup wizard.
     */
    public static final String ACTION_ADMIN_POLICY_COMPLIANCE =
            "android.app.action.ADMIN_POLICY_COMPLIANCE";

    /**
     * Maximum supported password length. Kind-of arbitrary.
     * @hide
     */
    public static final int MAX_PASSWORD_LENGTH = 16;

    /**
     * Service Action: Service implemented by a device owner or profile owner supervision app to
     * provide a secondary lockscreen.
     * @hide
     */
    @SystemApi
    public static final String ACTION_BIND_SECONDARY_LOCKSCREEN_SERVICE =
            "android.app.action.BIND_SECONDARY_LOCKSCREEN_SERVICE";

    /**
     * Return value for {@link #getPersonalAppsSuspendedReasons} when personal apps are not
     * suspended.
     */
    public static final int PERSONAL_APPS_NOT_SUSPENDED = 0;

    /**
     * Flag for {@link #getPersonalAppsSuspendedReasons} return value. Set when personal
     * apps are suspended by an admin explicitly via {@link #setPersonalAppsSuspended}.
     */
    public static final int PERSONAL_APPS_SUSPENDED_EXPLICITLY = 1 << 0;

    /**
     * Flag for {@link #getPersonalAppsSuspendedReasons} return value. Set when personal apps are
     * suspended by framework because managed profile was off for longer than allowed by policy.
     * @see #setManagedProfileMaximumTimeOff
     */
    public static final int PERSONAL_APPS_SUSPENDED_PROFILE_TIMEOUT = 1 << 1;

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = { "PERSONAL_APPS_" }, value = {
            PERSONAL_APPS_NOT_SUSPENDED,
            PERSONAL_APPS_SUSPENDED_EXPLICITLY,
            PERSONAL_APPS_SUSPENDED_PROFILE_TIMEOUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PersonalAppsSuspensionReason {}

    /**
     * Return true if the given administrator component is currently active (enabled) in the system.
     *
     * @param admin The administrator component to check for.
     * @return {@code true} if {@code admin} is currently enabled in the system, {@code false}
     *         otherwise
     */
    public boolean isAdminActive(@NonNull ComponentName admin) {
        throwIfParentInstance("isAdminActive");
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
    public @Nullable List<ComponentName> getActiveAdmins() {
        throwIfParentInstance("getActiveAdmins");
        return getActiveAdminsAsUser(myUserId());
    }

    /**
     * @see #getActiveAdmins()
     * @hide
     */
    @UnsupportedAppUsage
    public @Nullable List<ComponentName> getActiveAdminsAsUser(int userId) {
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
    @SystemApi
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public boolean packageHasActiveAdmins(String packageName) {
        return packageHasActiveAdmins(packageName, myUserId());
    }

    /**
     * Used by package administration code to determine if a package can be stopped
     * or uninstalled.
     * @hide
     */
    @UnsupportedAppUsage
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
     * Constant for {@link #setPasswordQuality}: allows the admin to set precisely how many
     * characters of various types the password should contain to satisfy the policy. The admin
     * should set these requirements via {@link #setPasswordMinimumLetters},
     * {@link #setPasswordMinimumNumeric}, {@link #setPasswordMinimumSymbols},
     * {@link #setPasswordMinimumUpperCase}, {@link #setPasswordMinimumLowerCase},
     * {@link #setPasswordMinimumNonLetter}, and {@link #setPasswordMinimumLength}.
     * Note that quality constants are ordered so that higher values are more restrictive.
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
     * @hide
     *
     * adb shell dpm set-{device,profile}-owner will normally not allow installing an owner to
     * a user with accounts.  {@link #ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_ALLOWED}
     * and {@link #ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_DISALLOWED} are the account features
     * used by authenticator to exempt their accounts from this:
     *
     * <ul>
     *     <li>Non-test-only DO/PO still can't be installed when there are accounts.
     *     <p>In order to make an apk test-only, add android:testOnly="true" to the
     *     &lt;application&gt; tag in the manifest.
     *
     *     <li>Test-only DO/PO can be installed even when there are accounts, as long as all the
     *     accounts have the {@link #ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_ALLOWED} feature.
     *     Some authenticators claim to have any features, so to detect it, we also check
     *     {@link #ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_DISALLOWED} and disallow installing
     *     if any of the accounts have it.
     * </ul>
     */
    @SystemApi
    @TestApi
    public static final String ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_ALLOWED =
            "android.account.DEVICE_OR_PROFILE_OWNER_ALLOWED";

    /** @hide See {@link #ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_ALLOWED} */
    @SystemApi
    @TestApi
    public static final String ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_DISALLOWED =
            "android.account.DEVICE_OR_PROFILE_OWNER_DISALLOWED";

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
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
     *            {@link #PASSWORD_QUALITY_BIOMETRIC_WEAK},
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
     * <p>Note: on devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature,
     * the password is always treated as empty.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     */
    public int getPasswordQuality(@Nullable ComponentName admin) {
        return getPasswordQuality(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
     * {@link #setPasswordQuality}. If an app targeting SDK level
     * {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without settings
     * password quality to one of these values first, this method will throw
     * {@link IllegalStateException}.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
     *     restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *     does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     */
    public int getPasswordMinimumLength(@Nullable ComponentName admin) {
        return getPasswordMinimumLength(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. If an app targeting
     * SDK level {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without
     * settings password quality to {@link #PASSWORD_QUALITY_COMPLEX} first, this method will throw
     * {@link IllegalStateException}. The default value is 0.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. If an app targeting
     * SDK level {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without
     * settings password quality to {@link #PASSWORD_QUALITY_COMPLEX} first, this method will throw
     * {@link IllegalStateException}. The default value is 0.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
     * {@link #setPasswordQuality}. If an app targeting SDK level
     * {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without settings
     * password quality to {@link #PASSWORD_QUALITY_COMPLEX} first, this method will throw
     * {@link IllegalStateException}. The default value is 1.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. If an app targeting
     * SDK level {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without
     * settings password quality to {@link #PASSWORD_QUALITY_COMPLEX} first, this method will throw
     * {@link IllegalStateException}. The default value is 1.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
     * {@link #setPasswordQuality}. If an app targeting SDK level
     * {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without settings
     * password quality to {@link #PASSWORD_QUALITY_COMPLEX} first, this method will throw
     * {@link IllegalStateException}. The default value is 1.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. If an app targeting
     * SDK level {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without
     * settings password quality to {@link #PASSWORD_QUALITY_COMPLEX} first, this method will throw
     * {@link IllegalStateException}. The default value is 0.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
     * Returns minimum PasswordMetrics that satisfies all admin policies.
     *
     * @hide
     */
    public PasswordMetrics getPasswordMinimumMetrics(@UserIdInt int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumMetrics(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by an application that is administering the device to set the length of the password
     * history. After setting this, the user will not be able to enter a new password that is the
     * same as any password in the history. Note that the current password will remain until the
     * user has set a new one, so the change does not take place immediately. To prompt the user for
     * a new password, use {@link #ACTION_SET_NEW_PASSWORD} or
     * {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after setting this value.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password history length is always 0.
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
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password expiration is always disabled.
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
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password expiration is always disabled and this method always returns 0.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate all admins.
     * @return The timeout for the given admin or the minimum of all timeouts
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password expiration is always disabled and this method always returns 0.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate all admins.
     * @return The password expiration time, in milliseconds since epoch.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password history length is always 0.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     * @return The length of the password history
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public int getPasswordHistoryLength(@Nullable ComponentName admin) {
        return getPasswordHistoryLength(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always empty and this method always returns 0.
     * @param quality The quality being interrogated.
     * @return Returns the maximum length that the user can enter.
     */
    public int getPasswordMaximumLength(int quality) {
        PackageManager pm = mContext.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)) {
            return 0;
        }
        return MAX_PASSWORD_LENGTH;
    }

    /**
     * Determines whether the calling user's current password meets policy requirements
     * (e.g. quality, minimum length). The user must be unlocked to perform this check.
     *
     * <p>Policy requirements which affect this check can be set by admins of the user, but also
     * by the admin of a managed profile associated with the calling user (when the managed profile
     * doesn't have a separate work challenge). When a managed profile has a separate work
     * challenge, its policy requirements only affect the managed profile.
     *
     * <p>Depending on the user, this method checks the policy requirement against one of the
     * following passwords:
     * <ul>
     * <li>For the primary user or secondary users: the personal keyguard password.
     * <li>For managed profiles: a work challenge if set, otherwise the parent user's personal
     *     keyguard password.
     * <ul/>
     * In other words, it's always checking the requirement against the password that is protecting
     * the calling user.
     *
     * <p>Note that this method considers all policy requirements targeting the password in
     * question. For example a profile owner might set a requirement on the parent profile i.e.
     * personal keyguard but not on the profile itself. When the device has a weak personal keyguard
     * password and no separate work challenge, calling this method will return {@code false}
     * despite the profile owner not setting a policy on the profile itself. This is because the
     * profile's current password is the personal keyguard password, and it does not meet all policy
     * requirements.
     *
     * <p>Device admins must request {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} before
     * calling this method. Note, this policy type is deprecated for device admins in Android 9.0
     * (API level 28) or higher.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to determine if the password set on
     * the parent profile is sufficient.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty - i.e. this method will always return false on such
     * devices, provided any password requirements were set.
     *
     * @return {@code true} if the password meets the policy requirements, {@code false} otherwise
     * @throws SecurityException if the calling application isn't an active admin that uses
     *     {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     * @throws IllegalStateException if the user isn't unlocked
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
     * Returns how complex the current user's screen lock is.
     *
     * <p>Note that when called from a profile which uses an unified challenge with its parent, the
     * screen lock complexity of the parent will be returned.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @throws IllegalStateException if the user is not unlocked.
     * @throws SecurityException     if the calling application does not have the permission
     *                               {@link permission#REQUEST_PASSWORD_COMPLEXITY}
     */
    @PasswordComplexity
    @RequiresPermission(android.Manifest.permission.REQUEST_PASSWORD_COMPLEXITY)
    public int getPasswordComplexity() {
        if (mService == null) {
            return PASSWORD_COMPLEXITY_NONE;
        }

        try {
            return mService.getPasswordComplexity(mParentInstance);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * When called by a profile owner of a managed profile returns true if the profile uses unified
     * challenge with its parent user.
     *
     * <strong>Note</strong>: This method is not concerned with password quality and will return
     * false if the profile has empty password as a separate challenge.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a profile owner of a managed profile.
     * @see UserManager#DISALLOW_UNIFIED_PASSWORD
     */
    public boolean isUsingUnifiedPassword(@NonNull ComponentName admin) {
        throwIfParentInstance("isUsingUnifiedPassword");
        if (mService != null) {
            try {
                return mService.isUsingUnifiedPassword(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return true;
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
     * Returns whether the given user's credential will be sufficient for all password policy
     * requirement, once the user's profile has switched to unified challenge.
     *
     * <p>This is different from {@link #isActivePasswordSufficient()} since once the profile
     * switches to unified challenge, policies set explicitly on the profile will start to affect
     * the parent user.
     * @param userHandle the user whose password requirement will be checked
     * @param profileUser the profile user whose lockscreen challenge will be unified.
     * @hide
     */
    public boolean isPasswordSufficientAfterProfileUnification(int userHandle, int profileUser) {
        if (mService != null) {
            try {
                return mService.isPasswordSufficientAfterProfileUnification(userHandle,
                        profileUser);
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always empty and this method always returns 0.
     *
     * @return The number of times user has entered an incorrect password since the last correct
     *         password entry.
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN}
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
    @UnsupportedAppUsage
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
     * When this policy is set by a device owner, profile owner of an organization-owned device or
     * an admin on the primary user, the device will be factory reset after too many incorrect
     * password attempts. When set by a profile owner or an admin on a secondary user or a managed
     * profile, only the corresponding user or profile will be wiped.
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always empty and this method has no effect - i.e. the policy is not set.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param num The number of failed password attempts at which point the device or profile will
     *            be wiped.
     * @throws SecurityException if {@code admin} is not an active administrator or does not use
     *             both {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} and
     *             {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA}.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always empty and this method returns a default value (0) indicating that the
     * policy is not set.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public int getMaximumFailedPasswordsForWipe(@Nullable ComponentName admin) {
        return getMaximumFailedPasswordsForWipe(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
     * Returns the user that will be wiped first when too many failed attempts are made to unlock
     * user {@code userHandle}. That user is either the same as {@code userHandle} or belongs to the
     * same profile group. When there is no such policy, returns {@code UserHandle.USER_NULL}.
     * E.g. managed profile user may be wiped as a result of failed primary profile password
     * attempts when using unified challenge. Primary user may be wiped as a result of failed
     * password attempts on the managed profile on an organization-owned device.
     * @hide Used only by Keyguard
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
     * Flag for {@link #resetPasswordWithToken} and {@link #resetPassword}: don't allow other admins
     * to change the password again until the user has entered it.
     */
    public static final int RESET_PASSWORD_REQUIRE_ENTRY = 0x0001;

    /**
     * Flag for {@link #resetPasswordWithToken} and {@link #resetPassword}: don't ask for user
     * credentials on device boot.
     * If the flag is set, the device can be booted without asking for user password.
     * The absence of this flag does not change the current boot requirements. This flag
     * can be set by the device owner only. If the app is not the device owner, the flag
     * is ignored. Once the flag is set, it cannot be reverted back without resetting the
     * device to factory defaults.
     */
    public static final int RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT = 0x0002;

    /**
     * Force a new password for device unlock (the password needed to access the entire device) or
     * the work profile challenge on the current user. This takes effect immediately.
     *
     * <p> Before {@link android.os.Build.VERSION_CODES#N}, this API is available to device admin,
     * profile owner and device owner. Starting from {@link android.os.Build.VERSION_CODES#N},
     * legacy device admin (who is not also profile owner or device owner) can only call this
     * API to set a new password if there is currently no password set. Profile owner and device
     * owner can continue to force change an existing password as long as the target user is
     * unlocked, although device owner will not be able to call this API at all if there is also a
     * managed profile on the device.
     *
     * <p> Between {@link android.os.Build.VERSION_CODES#O},
     * {@link android.os.Build.VERSION_CODES#P} and {@link android.os.Build.VERSION_CODES#Q},
     * profile owner and devices owner targeting SDK level {@link android.os.Build.VERSION_CODES#O}
     * or above who attempt to call this API will receive {@link SecurityException}; they are
     * encouraged to migrate to the new {@link #resetPasswordWithToken} API instead.
     * Profile owner and device owner targeting older SDK levels are not affected: they continue
     * to experience the existing behaviour described in the previous paragraph.
     *
     * <p><em>Starting from {@link android.os.Build.VERSION_CODES#R}, this API is no longer
     * supported in most cases.</em> Device owner and profile owner calling
     * this API will receive {@link SecurityException} if they target SDK level
     * {@link android.os.Build.VERSION_CODES#O} or above, or they will receive a silent failure
     * (API returning {@code false}) if they target lower SDK level.
     * For legacy device admins, this API throws {@link SecurityException} if they target SDK level
     * {@link android.os.Build.VERSION_CODES#N} or above, and returns {@code false} otherwise. Only
     * privileged apps holding RESET_PASSWORD permission which are part of
     * the system factory image can still call this API to set a new password if there is currently
     * no password set. In this case, if the device already has a password, this API will throw
     * {@link SecurityException}.
     *
     * <p>
     * The given password must be sufficient for the current password quality and length constraints
     * as returned by {@link #getPasswordQuality(ComponentName)} and
     * {@link #getPasswordMinimumLength(ComponentName)}; if it does not meet these constraints, then
     * it will be rejected and false returned. Note that the password may be a stronger quality
     * (containing alphanumeric characters when the requested quality is only numeric), in which
     * case the currently active quality will be increased to match.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, this
     * methods does nothing.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_RESET_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     *
     * @param password The new password for the user. Null or empty clears the password.
     * @param flags May be 0 or combination of {@link #RESET_PASSWORD_REQUIRE_ENTRY} and
     *            {@link #RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT}.
     * @return Returns true if the password was applied, or false if it is not acceptable for the
     *         current constraints.
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_RESET_PASSWORD}
     * @throws IllegalStateException if the calling user is locked or has a managed profile.
     * @deprecated Please use {@link #resetPasswordWithToken} instead.
     */
    @Deprecated
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
     * Called by a profile or device owner to provision a token which can later be used to reset the
     * device lockscreen password (if called by device owner), or managed profile challenge (if
     * called by profile owner), via {@link #resetPasswordWithToken}.
     * <p>
     * If the user currently has a lockscreen password, the provisioned token will not be
     * immediately usable; it only becomes active after the user performs a confirm credential
     * operation, which can be triggered by {@link KeyguardManager#createConfirmDeviceCredentialIntent}.
     * If the user has no lockscreen password, the token is activated immediately. In all cases,
     * the active state of the current token can be checked by {@link #isResetPasswordTokenActive}.
     * For security reasons, un-activated tokens are only stored in memory and will be lost once
     * the device reboots. In this case a new token needs to be provisioned again.
     * <p>
     * Once provisioned and activated, the token will remain effective even if the user changes
     * or clears the lockscreen password.
     * <p>
     * <em>This token is highly sensitive and should be treated at the same level as user
     * credentials. In particular, NEVER store this token on device in plaintext. Do not store
     * the plaintext token in device-encrypted storage if it will be needed to reset password on
     * file-based encryption devices before user unlocks. Consider carefully how any password token
     * will be stored on your server and who will need access to them. Tokens may be the subject of
     * legal access requests.
     * </em>
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * reset token is not set and this method returns false.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param token a secure token a least 32-byte long, which must be generated by a
     *        cryptographically strong random number generator.
     * @return true if the operation is successful, false otherwise.
     * @throws SecurityException if admin is not a device or profile owner.
     * @throws IllegalArgumentException if the supplied token is invalid.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public boolean setResetPasswordToken(ComponentName admin, byte[] token) {
        throwIfParentInstance("setResetPasswordToken");
        if (mService != null) {
            try {
                return mService.setResetPasswordToken(admin, token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a profile or device owner to revoke the current password reset token.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, this
     * method has no effect - the reset token should not have been set in the first place - and
     * false is returned.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return true if the operation is successful, false otherwise.
     * @throws SecurityException if admin is not a device or profile owner.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public boolean clearResetPasswordToken(ComponentName admin) {
        throwIfParentInstance("clearResetPasswordToken");
        if (mService != null) {
            try {
                return mService.clearResetPasswordToken(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a profile or device owner to check if the current reset password token is active.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature,
     * false is always returned.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return true if the token is active, false otherwise.
     * @throws SecurityException if admin is not a device or profile owner.
     * @throws IllegalStateException if no token has been set.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public boolean isResetPasswordTokenActive(ComponentName admin) {
        throwIfParentInstance("isResetPasswordTokenActive");
        if (mService != null) {
            try {
                return mService.isResetPasswordTokenActive(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device or profile owner to force set a new device unlock password or a managed
     * profile challenge on current user. This takes effect immediately.
     * <p>
     * Unlike {@link #resetPassword}, this API can change the password even before the user or
     * device is unlocked or decrypted. The supplied token must have been previously provisioned via
     * {@link #setResetPasswordToken}, and in active state {@link #isResetPasswordTokenActive}.
     * <p>
     * The given password must be sufficient for the current password quality and length constraints
     * as returned by {@link #getPasswordQuality(ComponentName)} and
     * {@link #getPasswordMinimumLength(ComponentName)}; if it does not meet these constraints, then
     * it will be rejected and false returned. Note that the password may be a stronger quality, for
     * example, a password containing alphanumeric characters when the requested quality is only
     * numeric.
     * <p>
     * Calling with a {@code null} or empty password will clear any existing PIN, pattern or
     * password if the current password constraints allow it.
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature,
     * calling this methods has no effect - the password is always empty - and false is returned.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param password The new password for the user. {@code null} or empty clears the password.
     * @param token the password reset token previously provisioned by
     *        {@link #setResetPasswordToken}.
     * @param flags May be 0 or combination of {@link #RESET_PASSWORD_REQUIRE_ENTRY} and
     *        {@link #RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT}.
     * @return Returns true if the password was applied, or false if it is not acceptable for the
     *         current constraints.
     * @throws SecurityException if admin is not a device or profile owner.
     * @throws IllegalStateException if the provided token is not valid.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public boolean resetPasswordWithToken(@NonNull ComponentName admin, String password,
            byte[] token, int flags) {
        throwIfParentInstance("resetPassword");
        if (mService != null) {
            try {
                return mService.resetPasswordWithToken(admin, password, token, flags);
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
    @UnsupportedAppUsage
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
     * Called by a device/profile owner to set the timeout after which unlocking with secondary, non
     * strong auth (e.g. fingerprint, face, trust agents) times out, i.e. the user has to use a
     * strong authentication method like password, pin or pattern.
     *
     * <p>This timeout is used internally to reset the timer to require strong auth again after
     * specified timeout each time it has been successfully used.
     *
     * <p>Fingerprint can also be disabled altogether using {@link #KEYGUARD_DISABLE_FINGERPRINT}.
     *
     * <p>Trust agents can also be disabled altogether using {@link #KEYGUARD_DISABLE_TRUST_AGENTS}.
     *
     * <p>The calling device admin must be a device or profile owner. If it is not,
     * a {@link SecurityException} will be thrown.
     *
     * <p>The calling device admin can verify the value it has set by calling
     * {@link #getRequiredStrongAuthTimeout(ComponentName)} and passing in its instance.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature,
     * calling this methods has no effect - i.e. the timeout is not set.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param timeoutMs The new timeout in milliseconds, after which the user will have to unlock
     *         with strong authentication method. A value of 0 means the admin is not participating
     *         in controlling the timeout.
     *         The minimum and maximum timeouts are platform-defined and are typically 1 hour and
     *         72 hours, respectively. Though discouraged, the admin may choose to require strong
     *         auth at all times using {@link #KEYGUARD_DISABLE_FINGERPRINT} and/or
     *         {@link #KEYGUARD_DISABLE_TRUST_AGENTS}.
     *
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public void setRequiredStrongAuthTimeout(@NonNull ComponentName admin,
            long timeoutMs) {
        if (mService != null) {
            try {
                mService.setRequiredStrongAuthTimeout(admin, timeoutMs, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Determine for how long the user will be able to use secondary, non strong auth for
     * authentication, since last strong method authentication (password, pin or pattern) was used.
     * After the returned timeout the user is required to use strong authentication method.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature,
     * 0 is returned to indicate that no timeout is configured.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     *         across all participating admins.
     * @return The timeout in milliseconds or 0 if not configured for the provided admin.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public long getRequiredStrongAuthTimeout(@Nullable ComponentName admin) {
        return getRequiredStrongAuthTimeout(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public long getRequiredStrongAuthTimeout(@Nullable ComponentName admin, @UserIdInt int userId) {
        if (mService != null) {
            try {
                return mService.getRequiredStrongAuthTimeout(admin, userId, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return DEFAULT_STRONG_AUTH_TIMEOUT_MS;
    }

    /**
     * Flag for {@link #lockNow(int)}: also evict the user's credential encryption key from the
     * keyring. The user's credential will need to be entered again in order to derive the
     * credential encryption key that will be stored back in the keyring for future use.
     * <p>
     * This flag can only be used by a profile owner when locking a managed profile when
     * {@link #getStorageEncryptionStatus} returns {@link #ENCRYPTION_STATUS_ACTIVE_PER_USER}.
     * <p>
     * In order to secure user data, the user will be stopped and restarted so apps should wait
     * until they are next run to perform further actions.
     */
    public static final int FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "FLAG_EVICT_" }, value = {
            FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY
    })
    public @interface LockNowFlag {}

    /**
     * Make the device lock immediately, as if the lock screen timeout has expired at the point of
     * this call.
     * <p>
     * This method secures the device in response to an urgent situation, such as a lost or stolen
     * device. After this method is called, the device must be unlocked using strong authentication
     * (PIN, pattern, or password). This API is intended for use only by device admins.
     * <p>
     * The calling device admin must have requested {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK}
     * to be able to call this method; if it has not, a security exception will be thrown.
     * <p>
     * If there's no lock type set, this method forces the device to go to sleep but doesn't lock
     * the device. Device admins who find the device in this state can lock an otherwise-insecure
     * device by first calling {@link #resetPassword} to set the password and then lock the device.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to lock the parent profile.
     * <p>
     * Equivalent to calling {@link #lockNow(int)} with no flags.
     *
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK}
     */
    public void lockNow() {
        lockNow(0);
    }

    /**
     * Make the device lock immediately, as if the lock screen timeout has expired at the point of
     * this call.
     * <p>
     * This method secures the device in response to an urgent situation, such as a lost or stolen
     * device. After this method is called, the device must be unlocked using strong authentication
     * (PIN, pattern, or password). This API is intended for use only by device admins.
     * <p>
     * The calling device admin must have requested {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK}
     * to be able to call this method; if it has not, a security exception will be thrown.
     * <p>
     * If there's no lock type set, this method forces the device to go to sleep but doesn't lock
     * the device. Device admins who find the device in this state can lock an otherwise-insecure
     * device by first calling {@link #resetPassword} to set the password and then lock the device.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to lock the parent profile as
     * well as the managed profile.
     * <p>
     * NOTE: In order to lock the parent profile and evict the encryption key of the managed
     * profile, {@link #lockNow()} must be called twice: First, {@link #lockNow()} should be called
     * on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)}, then {@link #lockNow(int)} should be
     * called on the {@link DevicePolicyManager} instance associated with the managed profile,
     * with the {@link #FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY} flag.
     * Calling the method twice in this order ensures that all users are locked and does not
     * stop the device admin on the managed profile from issuing a second call to lock its own
     * profile.
     *
     * @param flags May be 0 or {@link #FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY}.
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK} or the
     *             {@link #FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY} flag is passed by an application
     *             that is not a profile
     *             owner of a managed profile.
     * @throws IllegalArgumentException if the {@link #FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY} flag is
     *             passed when locking the parent profile.
     * @throws UnsupportedOperationException if the {@link #FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY}
     *             flag is passed when {@link #getStorageEncryptionStatus} does not return
     *             {@link #ENCRYPTION_STATUS_ACTIVE_PER_USER}.
     */
    public void lockNow(@LockNowFlag int flags) {
        if (mService != null) {
            try {
                mService.lockNow(flags, mParentInstance);
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
     * Flag for {@link #wipeData(int)}: also erase the device's eUICC data.
     */
    public static final int WIPE_EUICC = 0x0004;

    /**
     * Flag for {@link #wipeData(int)}: won't show reason for wiping to the user.
     */
    public static final int WIPE_SILENTLY = 0x0008;

    /**
     * Ask that all user data be wiped. If called as a secondary user, the user will be removed and
     * other users will remain unaffected. Calling from the primary user will cause the device to
     * reboot, erasing all device data - including all the secondary users and their data - while
     * booting up.
     * <p>
     * The calling device admin must have requested {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA} to
     * be able to call this method; if it has not, a security exception will be thrown.
     *
     * If the caller is a profile owner of an organization-owned managed profile, it may
     * additionally call this method on the parent instance.
     * Calling this method on the parent {@link DevicePolicyManager} instance would wipe the
     * entire device, while calling it on the current profile instance would relinquish the device
     * for personal use, removing the managed profile and all policies set by the profile owner.
     *
     * @param flags Bit mask of additional options: currently supported flags are
     *            {@link #WIPE_EXTERNAL_STORAGE}, {@link #WIPE_RESET_PROTECTION_DATA},
     *            {@link #WIPE_EUICC} and {@link #WIPE_SILENTLY}.
     * @throws SecurityException if the calling application does not own an active administrator
     *            that uses {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA}
     */
    public void wipeData(int flags) {
        wipeDataInternal(flags, "");
    }

    /**
     * Ask that all user data be wiped. If called as a secondary user, the user will be removed and
     * other users will remain unaffected, the provided reason for wiping data can be shown to
     * user. Calling from the primary user will cause the device to reboot, erasing all device data
     * - including all the secondary users and their data - while booting up. In this case, we don't
     * show the reason to the user since the device would be factory reset.
     * <p>
     * The calling device admin must have requested {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA} to
     * be able to call this method; if it has not, a security exception will be thrown.
     *
     * If the caller is a profile owner of an organization-owned managed profile, it may
     * additionally call this method on the parent instance.
     * Calling this method on the parent {@link DevicePolicyManager} instance would wipe the
     * entire device, while calling it on the current profile instance would relinquish the device
     * for personal use, removing the managed profile and all policies set by the profile owner.
     *
     * @param flags Bit mask of additional options: currently supported flags are
     *            {@link #WIPE_EXTERNAL_STORAGE}, {@link #WIPE_RESET_PROTECTION_DATA} and
     *            {@link #WIPE_EUICC}.
     * @param reason a string that contains the reason for wiping data, which can be
     *            presented to the user.
     * @throws SecurityException if the calling application does not own an active administrator
     *            that uses {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA}
     * @throws IllegalArgumentException if the input reason string is null or empty, or if
     *            {@link #WIPE_SILENTLY} is set.
     */
    public void wipeData(int flags, @NonNull CharSequence reason) {
        Objects.requireNonNull(reason, "reason string is null");
        Preconditions.checkStringNotEmpty(reason, "reason string is empty");
        Preconditions.checkArgument((flags & WIPE_SILENTLY) == 0, "WIPE_SILENTLY cannot be set");
        wipeDataInternal(flags, reason.toString());
    }

    /**
     * Internal function for both {@link #wipeData(int)} and
     * {@link #wipeData(int, CharSequence)} to call.
     *
     * @see #wipeData(int)
     * @see #wipeData(int, CharSequence)
     * @hide
     */
    private void wipeDataInternal(int flags, @NonNull String wipeReasonForUser) {
        if (mService != null) {
            try {
                mService.wipeDataWithReason(flags, wipeReasonForUser, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Callable by device owner or profile owner of an organization-owned device, to set a
     * factory reset protection (FRP) policy. When a new policy is set, the system
     * notifies the FRP management agent of a policy change by broadcasting
     * {@code ACTION_RESET_PROTECTION_POLICY_CHANGED}.
     *
     * @param admin  Which {@link DeviceAdminReceiver} this request is associated with.
     * @param policy the new FRP policy, or {@code null} to clear the current policy.
     * @throws SecurityException if {@code admin} is not a device owner or a profile owner of
     *                           an organization-owned device.
     * @throws UnsupportedOperationException if factory reset protection is not
     *                           supported on the device.
     */
    public void setFactoryResetProtectionPolicy(@NonNull ComponentName admin,
            @Nullable FactoryResetProtectionPolicy policy) {
        throwIfParentInstance("setFactoryResetProtectionPolicy");
        if (mService != null) {
            try {
                mService.setFactoryResetProtectionPolicy(admin, policy);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Callable by device owner or profile owner of an organization-owned device, to retrieve
     * the current factory reset protection (FRP) policy set previously by
     * {@link #setFactoryResetProtectionPolicy}.
     * <p>
     * This method can also be called by the FRP management agent on device or with the permission
     * {@link android.Manifest.permission#MASTER_CLEAR}, in which case, it can pass {@code null}
     * as the ComponentName.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with or
     *              {@code null} if called by the FRP management agent on device or with the
     *              permission {@link android.Manifest.permission#MASTER_CLEAR}.
     * @return The current FRP policy object or {@code null} if no policy is set.
     * @throws SecurityException if {@code admin} is not a device owner, a profile owner of
     *                           an organization-owned device or the FRP management agent.
     * @throws UnsupportedOperationException if factory reset protection is not
     *                           supported on the device.
     */
    public @Nullable FactoryResetProtectionPolicy getFactoryResetProtectionPolicy(
            @Nullable ComponentName admin) {
        throwIfParentInstance("getFactoryResetProtectionPolicy");
        if (mService != null) {
            try {
                return mService.getFactoryResetProtectionPolicy(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
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
    @UnsupportedAppUsage
    public @Nullable ComponentName setGlobalProxy(@NonNull ComponentName admin, Proxy proxySpec,
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
    public @Nullable ComponentName getGlobalProxyAdmin() {
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
     * Activity action: launch the DPC to check policy compliance. This intent is launched when
     * the user taps on the notification about personal apps suspension. When handling this intent
     * the DPC must check if personal apps should still be suspended and either unsuspend them or
     * instruct the user on how to resolve the noncompliance causing the suspension.
     *
     * @see #setPersonalAppsSuspended
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CHECK_POLICY_COMPLIANCE =
            "android.app.action.CHECK_POLICY_COMPLIANCE";

    /**
     * Broadcast action: notify managed provisioning that new managed user is created.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MANAGED_USER_CREATED =
            "android.app.action.MANAGED_USER_CREATED";

    /**
     * Widgets are enabled in keyguard
     */
    public static final int KEYGUARD_DISABLE_FEATURES_NONE = 0;

    /**
     * Disable all keyguard widgets. Has no effect starting from
     * {@link android.os.Build.VERSION_CODES#LOLLIPOP} since keyguard widget is only supported
     * on Android versions lower than 5.0.
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
     * Disable trust agents on secure keyguard screens (e.g. PIN/Pattern/Password).
     * By setting this flag alone, all trust agents are disabled. If the admin then wants to
     * whitelist specific features of some trust agent, {@link #setTrustAgentConfiguration} can be
     * used in conjuction to set trust-agent-specific configurations.
     */
    public static final int KEYGUARD_DISABLE_TRUST_AGENTS = 1 << 4;

    /**
     * Disable fingerprint authentication on keyguard secure screens (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_FINGERPRINT = 1 << 5;

    /**
     * Disable text entry into notifications on secure keyguard screens (e.g. PIN/Pattern/Password).
     * This flag has no effect starting from version {@link android.os.Build.VERSION_CODES#N}
     */
    public static final int KEYGUARD_DISABLE_REMOTE_INPUT = 1 << 6;

    /**
     * Disable face authentication on keyguard secure screens (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_FACE = 1 << 7;

    /**
     * Disable iris authentication on keyguard secure screens (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_IRIS = 1 << 8;

    /**
     * NOTE: Please remember to update the DevicePolicyManagerTest's testKeyguardDisabledFeatures
     * CTS test when adding to the list above.
     */

    /**
     * Disable all biometric authentication on keyguard secure screens (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_BIOMETRICS =
            DevicePolicyManager.KEYGUARD_DISABLE_FACE
            | DevicePolicyManager.KEYGUARD_DISABLE_IRIS
            | DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT;

    /**
     * Disable all current and future keyguard customizations.
     */
    public static final int KEYGUARD_DISABLE_FEATURES_ALL = 0x7fffffff;

    /**
     * Keyguard features that when set on a non-organization-owned managed profile that doesn't
     * have its own challenge will affect the profile's parent user. These can also be set on the
     * managed profile's parent {@link DevicePolicyManager} instance to explicitly control the
     * parent user.
     *
     * <p>
     * Organization-owned managed profile supports disabling additional keyguard features on the
     * parent user as defined in {@link #ORG_OWNED_PROFILE_KEYGUARD_FEATURES_PARENT_ONLY}.
     *
     * @hide
     */
    public static final int NON_ORG_OWNED_PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER =
            DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS
            | DevicePolicyManager.KEYGUARD_DISABLE_BIOMETRICS;

    /**
     * Keyguard features that when set by the profile owner of an organization-owned managed
     * profile will affect the profile's parent user if set on the managed profile's parent
     * {@link DevicePolicyManager} instance.
     *
     * @hide
     */
    public static final int ORG_OWNED_PROFILE_KEYGUARD_FEATURES_PARENT_ONLY =
            DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA
                    | DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS;

    /**
     * Keyguard features that when set on a normal or organization-owned managed profile, have
     * the potential to affect the profile's parent user.
     *
     * @hide
     */
    public static final int PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER =
            DevicePolicyManager.NON_ORG_OWNED_PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER
                    | DevicePolicyManager.ORG_OWNED_PROFILE_KEYGUARD_FEATURES_PARENT_ONLY;

    /**
     * @deprecated This method does not actually modify the storage encryption of the device.
     * It has never affected the encryption status of a device.
     *
     * Called by an application that is administering the device to request that the storage system
     * be encrypted. Does nothing if the caller is on a secondary user or a managed profile.
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
     * @return the new total request status (for all active admins), or {@link
     *         DevicePolicyManager#ENCRYPTION_STATUS_UNSUPPORTED} if called for a non-system user.
     *         Will be one of {@link #ENCRYPTION_STATUS_UNSUPPORTED}, {@link
     *         #ENCRYPTION_STATUS_INACTIVE}, or {@link #ENCRYPTION_STATUS_ACTIVE}. This is the value
     *         of the requests; use {@link #getStorageEncryptionStatus()} to query the actual device
     *         state.
     *
     * @throws SecurityException if {@code admin} is not an active administrator or does not use
     *             {@link DeviceAdminInfo#USES_ENCRYPTED_STORAGE}
     */
    @Deprecated
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
     * @deprecated This method only returns the value set by {@link #setStorageEncryption}.
     * It does not actually reflect the storage encryption status.
     * Use {@link #getStorageEncryptionStatus} for that.
     *
     * Called by an application that is administering the device to
     * determine the requested setting for secure storage.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.  If null,
     * this will return the requested encryption setting as an aggregate of all active
     * administrators.
     * @return true if the admin(s) are requesting encryption, false if not.
     */
    @Deprecated
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
    @UnsupportedAppUsage
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
     * <p>
     * Inserted user CAs aren't automatically trusted by apps in Android 7.0 (API level 24) and
     * higher. App developers can change the default behavior for an app by adding a
     * <a href="{@docRoot}training/articles/security-config.html">Security Configuration
     * File</a> to the app manifest file.
     *
     * The caller must be a profile or device owner on that user, or a delegate package given the
     * {@link #DELEGATION_CERT_INSTALL} scope via {@link #setDelegatedScopes}; otherwise a
     * security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @param certBuffer encoded form of the certificate to install.
     *
     * @return false if the certBuffer cannot be parsed or installation is
     *         interrupted, true otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
     */
    public boolean installCaCert(@Nullable ComponentName admin, byte[] certBuffer) {
        throwIfParentInstance("installCaCert");
        if (mService != null) {
            try {
                return mService.installCaCert(admin, mContext.getPackageName(), certBuffer);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Uninstalls the given certificate from trusted user CAs, if present.
     *
     * The caller must be a profile or device owner on that user, or a delegate package given the
     * {@link #DELEGATION_CERT_INSTALL} scope via {@link #setDelegatedScopes}; otherwise a
     * security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @param certBuffer encoded form of the certificate to remove.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
     */
    public void uninstallCaCert(@Nullable ComponentName admin, byte[] certBuffer) {
        throwIfParentInstance("uninstallCaCert");
        if (mService != null) {
            try {
                final String alias = getCaCertAlias(certBuffer);
                mService.uninstallCaCerts(admin, mContext.getPackageName(), new String[] {alias});
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
    public @NonNull List<byte[]> getInstalledCaCerts(@Nullable ComponentName admin) {
        final List<byte[]> certs = new ArrayList<byte[]>();
        throwIfParentInstance("getInstalledCaCerts");
        if (mService != null) {
            try {
                mService.enforceCanManageCaCerts(admin, mContext.getPackageName());
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
                mService.uninstallCaCerts(admin, mContext.getPackageName(),
                        new TrustedCertificateStore().userAliases() .toArray(new String[0]));
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
                mService.enforceCanManageCaCerts(admin, mContext.getPackageName());
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
     * <p>Note: If the provided {@code alias} is of an existing alias, all former grants that apps
     * have been given to access the key and certificates associated with this alias will be
     * revoked.
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
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
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
     * <p>Note: If the provided {@code alias} is of an existing alias, all former grants that apps
     * have been given to access the key and certificates associated with this alias will be
     * revoked.
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
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
     */
    public boolean installKeyPair(@Nullable ComponentName admin, @NonNull PrivateKey privKey,
            @NonNull Certificate[] certs, @NonNull String alias, boolean requestAccess) {
        int flags = INSTALLKEY_SET_USER_SELECTABLE;
        if (requestAccess) {
            flags |= INSTALLKEY_REQUEST_CREDENTIALS_ACCESS;
        }
        return installKeyPair(admin, privKey, certs, alias, flags);
    }

    /**
     * Called by a device or profile owner, or delegated certificate installer, to install a
     * certificate chain and corresponding private key for the leaf certificate. All apps within the
     * profile will be able to access the certificate chain and use the private key, given direct
     * user approval (if the user is allowed to select the private key).
     *
     * <p>The caller of this API may grant itself access to the certificate and private key
     * immediately, without user approval. It is a best practice not to request this unless strictly
     * necessary since it opens up additional security vulnerabilities.
     *
     * <p>Include {@link #INSTALLKEY_SET_USER_SELECTABLE} in the {@code flags} argument to allow
     * the user to select the key from a dialog.
     *
     * <p>Note: If the provided {@code alias} is of an existing alias, all former grants that apps
     * have been given to access the key and certificates associated with this alias will be
     * revoked.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if calling from a delegated certificate installer.
     * @param privKey The private key to install.
     * @param certs The certificate chain to install. The chain should start with the leaf
     *        certificate and include the chain of trust in order. This will be returned by
     *        {@link android.security.KeyChain#getCertificateChain}.
     * @param alias The private key alias under which to install the certificate. If a certificate
     *        with that alias already exists, it will be overwritten.
     * @param flags Flags to request that the calling app be granted access to the credentials
     *        and set the key to be user-selectable. See {@link #INSTALLKEY_SET_USER_SELECTABLE} and
     *        {@link #INSTALLKEY_REQUEST_CREDENTIALS_ACCESS}.
     * @return {@code true} if the keys were installed, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     * @see android.security.KeyChain#getCertificateChain
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
     */
    public boolean installKeyPair(@Nullable ComponentName admin, @NonNull PrivateKey privKey,
            @NonNull Certificate[] certs, @NonNull String alias, int flags) {
        throwIfParentInstance("installKeyPair");
        boolean requestAccess = (flags & INSTALLKEY_REQUEST_CREDENTIALS_ACCESS)
                == INSTALLKEY_REQUEST_CREDENTIALS_ACCESS;
        boolean isUserSelectable = (flags & INSTALLKEY_SET_USER_SELECTABLE)
                == INSTALLKEY_SET_USER_SELECTABLE;
        try {
            final byte[] pemCert = Credentials.convertToPem(certs[0]);
            byte[] pemChain = null;
            if (certs.length > 1) {
                pemChain = Credentials.convertToPem(Arrays.copyOfRange(certs, 1, certs.length));
            }
            final byte[] pkcs8Key = KeyFactory.getInstance(privKey.getAlgorithm())
                    .getKeySpec(privKey, PKCS8EncodedKeySpec.class).getEncoded();
            return mService.installKeyPair(admin, mContext.getPackageName(), pkcs8Key, pemCert,
                    pemChain, alias, requestAccess, isUserSelectable);
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
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
     */
    public boolean removeKeyPair(@Nullable ComponentName admin, @NonNull String alias) {
        throwIfParentInstance("removeKeyPair");
        try {
            return mService.removeKeyPair(admin, mContext.getPackageName(), alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device or profile owner, or delegated certificate installer, to generate a
     * new private/public key pair. If the device supports key generation via secure hardware,
     * this method is useful for creating a key in KeyChain that never left the secure hardware.
     * Access to the key is controlled the same way as in {@link #installKeyPair}.
     *
     * <p>Because this method might take several seconds to complete, it should only be called from
     * a worker thread. This method returns {@code null} when called from the main thread.
     *
     * <p>This method is not thread-safe, calling it from multiple threads at the same time will
     * result in undefined behavior. If the calling thread is interrupted while the invocation is
     * in-flight, it will eventually terminate and return {@code null}.
     *
     * <p>Note: If the provided {@code alias} is of an existing alias, all former grants that apps
     * have been given to access the key and certificates associated with this alias will be
     * revoked.
     *
     * <p>Attestation: to enable attestation, set an attestation challenge in {@code keySpec} via
     * {@link KeyGenParameterSpec.Builder#setAttestationChallenge}. By specifying flags to the
     * {@code idAttestationFlags} parameter, it is possible to request the device's unique
     * identity to be included in the attestation record.
     *
     * <p>Specific identifiers can be included in the attestation record, and an individual
     * attestation certificate can be used to sign the attestation record. To find out if the device
     * supports these features, refer to {@link #isDeviceIdAttestationSupported()} and
     * {@link #isUniqueDeviceAttestationSupported()}.
     *
     * <p>Device owner, profile owner and their delegated certificate installer can use
     * {@link #ID_TYPE_BASE_INFO} to request inclusion of the general device information
     * including manufacturer, model, brand, device and product in the attestation record.
     * Only device owner, profile owner on an organization-owned device and their delegated
     * certificate installers can use {@link #ID_TYPE_SERIAL}, {@link #ID_TYPE_IMEI} and
     * {@link #ID_TYPE_MEID} to request unique device identifiers to be attested (the serial number,
     * IMEI and MEID correspondingly), if supported by the device
     * (see {@link #isDeviceIdAttestationSupported()}).
     * Additionally, device owner, profile owner on an organization-owned device and their delegated
     * certificate installers can also request the attestation record to be signed using an
     * individual attestation certificate by specifying the {@link #ID_TYPE_INDIVIDUAL_ATTESTATION}
     * flag (if supported by the device, see {@link #isUniqueDeviceAttestationSupported()}).
     * <p>
     * If any of {@link #ID_TYPE_SERIAL}, {@link #ID_TYPE_IMEI} and {@link #ID_TYPE_MEID}
     * is set, it is implicitly assumed that {@link #ID_TYPE_BASE_INFO} is also set.
     * <p>
     * Attestation using {@link #ID_TYPE_INDIVIDUAL_ATTESTATION} can only be requested if
     * key generation is done in StrongBox.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if calling from a delegated certificate installer.
     * @param algorithm The key generation algorithm, see {@link java.security.KeyPairGenerator}.
     * @param keySpec Specification of the key to generate, see
     * {@link java.security.KeyPairGenerator}.
     * @param idAttestationFlags A bitmask of the identifiers that should be included in the
     *        attestation record ({@code ID_TYPE_BASE_INFO}, {@code ID_TYPE_SERIAL},
     *        {@code ID_TYPE_IMEI} and {@code ID_TYPE_MEID}), and
     *        {@code ID_TYPE_INDIVIDUAL_ATTESTATION} if the attestation record should be signed
     *        using an individual attestation certificate.
     *        <p>
     *        {@code 0} should be passed in if no device identification is required in the
     *        attestation record and the batch attestation certificate should be used.
     *        <p>
     *        If any flag is specified, then an attestation challenge must be included in the
     *        {@code keySpec}.
     * @return A non-null {@code AttestedKeyPair} if the key generation succeeded, null otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner. If Device ID attestation is requested (using {@link #ID_TYPE_SERIAL},
     *         {@link #ID_TYPE_IMEI} or {@link #ID_TYPE_MEID}), the caller must be the Device Owner
     *         or the Certificate Installer delegate.
     * @throws IllegalArgumentException in the following cases:
     *         <p>
     *         <ul>
     *         <li>The alias in {@code keySpec} is empty.</li>
     *         <li>The algorithm specification in {@code keySpec} is not
     *         {@code RSAKeyGenParameterSpec} or {@code ECGenParameterSpec}.</li>
     *         <li>Device ID attestation was requested but the {@code keySpec} does not contain an
     *         attestation challenge.</li>
     *         </ul>
     * @throws UnsupportedOperationException if Device ID attestation or individual attestation
     *         was requested but the underlying hardware does not support it.
     * @throws StrongBoxUnavailableException if the use of StrongBox for key generation was
     *         specified in {@code keySpec} but the device does not have one.
     * @see KeyGenParameterSpec.Builder#setAttestationChallenge(byte[])
     */
    public AttestedKeyPair generateKeyPair(@Nullable ComponentName admin,
            @NonNull String algorithm, @NonNull KeyGenParameterSpec keySpec,
            @AttestationIdType int idAttestationFlags) {
        throwIfParentInstance("generateKeyPair");
        try {
            final ParcelableKeyGenParameterSpec parcelableSpec =
                    new ParcelableKeyGenParameterSpec(keySpec);
            KeymasterCertificateChain attestationChain = new KeymasterCertificateChain();

            // Translate ID attestation flags to values used by AttestationUtils
            final boolean success = mService.generateKeyPair(
                    admin, mContext.getPackageName(), algorithm, parcelableSpec,
                    idAttestationFlags, attestationChain);
            if (!success) {
                Log.e(TAG, "Error generating key via DevicePolicyManagerService.");
                return null;
            }

            final String alias = keySpec.getKeystoreAlias();
            final KeyPair keyPair = KeyChain.getKeyPair(mContext, alias);
            Certificate[] outputChain = null;
            try {
                if (AttestationUtils.isChainValid(attestationChain)) {
                    outputChain = AttestationUtils.parseCertificateChain(attestationChain);
                }
            } catch (KeyAttestationException e) {
                Log.e(TAG, "Error parsing attestation chain for alias " + alias, e);
                mService.removeKeyPair(admin, mContext.getPackageName(), alias);
                return null;
            }
            return new AttestedKeyPair(keyPair, outputChain);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (KeyChainException e) {
            Log.w(TAG, "Failed to generate key", e);
        } catch (InterruptedException e) {
            Log.w(TAG, "Interrupted while generating key", e);
            Thread.currentThread().interrupt();
        } catch (ServiceSpecificException e) {
            Log.w(TAG, String.format("Key Generation failure: %d", e.errorCode));
            switch (e.errorCode) {
                case KEY_GEN_STRONGBOX_UNAVAILABLE:
                    throw new StrongBoxUnavailableException("No StrongBox for key generation.");
                default:
                    throw new RuntimeException(
                            String.format("Unknown error while generating key: %d", e.errorCode));
            }
        }
        return null;
    }


    /**
     * Called by a device or profile owner, or delegated certificate chooser (an app that has been
     * delegated the {@link #DELEGATION_CERT_SELECTION} privilege), to grant an application access
     * to an already-installed (or generated) KeyChain key.
     * This is useful (in combination with {@link #installKeyPair} or {@link #generateKeyPair}) to
     * let an application call {@link android.security.KeyChain#getPrivateKey} without having to
     * call {@link android.security.KeyChain#choosePrivateKeyAlias} first.
     *
     * The grantee app will receive the {@link android.security.KeyChain#ACTION_KEY_ACCESS_CHANGED}
     * broadcast when access to a key is granted.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if calling from a delegated certificate installer.
     * @param alias The alias of the key to grant access to.
     * @param packageName The name of the (already installed) package to grant access to.
     * @return {@code true} if the grant was set successfully, {@code false} otherwise.
     *
     * @throws SecurityException if the caller is not a device owner, a profile owner or
     *         delegated certificate chooser.
     * @throws IllegalArgumentException if {@code packageName} or {@code alias} are empty, or if
     *         {@code packageName} is not a name of an installed package.
     * @see #revokeKeyPairFromApp
     */
    public boolean grantKeyPairToApp(@Nullable ComponentName admin, @NonNull String alias,
            @NonNull String packageName) {
        throwIfParentInstance("grantKeyPairToApp");
        try {
            return mService.setKeyGrantForApp(
                    admin, mContext.getPackageName(), alias, packageName, true);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Called by a device or profile owner, or delegated certificate chooser (an app that has been
     * delegated the {@link #DELEGATION_CERT_SELECTION} privilege), to revoke an application's
     * grant to a KeyChain key pair.
     * Calls by the application to {@link android.security.KeyChain#getPrivateKey}
     * will fail after the grant is revoked.
     *
     * The grantee app will receive the {@link android.security.KeyChain#ACTION_KEY_ACCESS_CHANGED}
     * broadcast when access to a key is revoked.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if calling from a delegated certificate installer.
     * @param alias The alias of the key to revoke access from.
     * @param packageName The name of the (already installed) package to revoke access from.
     * @return {@code true} if the grant was revoked successfully, {@code false} otherwise.
     *
     * @throws SecurityException if the caller is not a device owner, a profile owner or
     *         delegated certificate chooser.
     * @throws IllegalArgumentException if {@code packageName} or {@code alias} are empty, or if
     *         {@code packageName} is not a name of an installed package.
     * @see #grantKeyPairToApp
     */
    public boolean revokeKeyPairFromApp(@Nullable ComponentName admin, @NonNull String alias,
            @NonNull String packageName) {
        throwIfParentInstance("revokeKeyPairFromApp");
        try {
            return mService.setKeyGrantForApp(
                    admin, mContext.getPackageName(), alias, packageName, false);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Returns {@code true} if the device supports attestation of device identifiers in addition
     * to key attestation. See
     * {@link #generateKeyPair(ComponentName, String, KeyGenParameterSpec, int)}
     * @return {@code true} if Device ID attestation is supported.
     */
    public boolean isDeviceIdAttestationSupported() {
        PackageManager pm = mContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_DEVICE_ID_ATTESTATION);
    }

    /**
     * Returns {@code true} if the StrongBox Keymaster implementation on the device was provisioned
     * with an individual attestation certificate and can sign attestation records using it (as
     * attestation using an individual attestation certificate is a feature only Keymaster
     * implementations with StrongBox security level can implement).
     * For use prior to calling
     * {@link #generateKeyPair(ComponentName, String, KeyGenParameterSpec, int)}.
     * @return {@code true} if individual attestation is supported.
     */
    public boolean isUniqueDeviceAttestationSupported() {
        PackageManager pm = mContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_DEVICE_UNIQUE_ATTESTATION);
    }

    /**
     * Called by a device or profile owner, or delegated certificate installer, to associate
     * certificates with a key pair that was generated using {@link #generateKeyPair}, and
     * set whether the key is available for the user to choose in the certificate selection
     * prompt.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if calling from a delegated certificate installer.
     * @param alias The private key alias under which to install the certificate. The {@code alias}
     *        should denote an existing private key. If a certificate with that alias already
     *        exists, it will be overwritten.
     * @param certs The certificate chain to install. The chain should start with the leaf
     *        certificate and include the chain of trust in order. This will be returned by
     *        {@link android.security.KeyChain#getCertificateChain}.
     * @param isUserSelectable {@code true} to indicate that a user can select this key via the
     *        certificate selection prompt, {@code false} to indicate that this key can only be
     *        granted access by implementing
     *        {@link android.app.admin.DeviceAdminReceiver#onChoosePrivateKeyAlias}.
     * @return {@code true} if the provided {@code alias} exists and the certificates has been
     *        successfully associated with it, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner, or {@code admin} is null but the calling application is not a delegated
     *         certificate installer.
     */
    public boolean setKeyPairCertificate(@Nullable ComponentName admin,
            @NonNull String alias, @NonNull List<Certificate> certs, boolean isUserSelectable) {
        throwIfParentInstance("setKeyPairCertificate");
        try {
            final byte[] pemCert = Credentials.convertToPem(certs.get(0));
            byte[] pemChain = null;
            if (certs.size() > 1) {
                pemChain = Credentials.convertToPem(
                        certs.subList(1, certs.size()).toArray(new Certificate[0]));
            }
            return mService.setKeyPairCertificate(admin, mContext.getPackageName(), alias, pemCert,
                    pemChain, isUserSelectable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (CertificateException | IOException e) {
            Log.w(TAG, "Could not pem-encode certificate", e);
        }
        return false;
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
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}. Use {@link #setDelegatedScopes}
     * with the {@link #DELEGATION_CERT_INSTALL} scope instead.
     */
    @Deprecated
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
     * Called by a profile owner or device owner to retrieve the certificate installer for the user,
     * or {@code null} if none is set. If there are multiple delegates this function will return one
     * of them.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The package name of the current delegated certificate installer, or {@code null} if
     *         none is set.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}. Use {@link #getDelegatePackages}
     * with the {@link #DELEGATION_CERT_INSTALL} scope instead.
     */
    @Deprecated
    public @Nullable String getCertInstallerPackage(@NonNull ComponentName admin)
            throws SecurityException {
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
     * Called by a profile owner or device owner to grant access to privileged APIs to another app.
     * Granted APIs are determined by {@code scopes}, which is a list of the {@code DELEGATION_*}
     * constants.
     * <p>
     * A broadcast with the {@link #ACTION_APPLICATION_DELEGATION_SCOPES_CHANGED} action will be
     * sent to the {@code delegatePackage} with its new scopes in an {@code ArrayList<String>} extra
     * under the {@link #EXTRA_DELEGATION_SCOPES} key. The broadcast is sent with the
     * {@link Intent#FLAG_RECEIVER_REGISTERED_ONLY} flag.
     * <p>
     * Delegated scopes are a per-user state. The delegated access is persistent until it is later
     * cleared by calling this method with an empty {@code scopes} list or uninstalling the
     * {@code delegatePackage}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param delegatePackage The package name of the app which will be given access.
     * @param scopes The groups of privileged APIs whose access should be granted to
     *            {@code delegatedPackage}.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     */
     public void setDelegatedScopes(@NonNull ComponentName admin, @NonNull String delegatePackage,
            @NonNull List<String> scopes) {
        throwIfParentInstance("setDelegatedScopes");
        if (mService != null) {
            try {
                mService.setDelegatedScopes(admin, delegatePackage, scopes);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner or device owner to retrieve a list of the scopes given to a
     * delegate package. Other apps can use this method to retrieve their own delegated scopes by
     * passing {@code null} for {@code admin} and their own package name as
     * {@code delegatedPackage}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is {@code delegatedPackage}.
     * @param delegatedPackage The package name of the app whose scopes should be retrieved.
     * @return A list containing the scopes given to {@code delegatedPackage}.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     */
     @NonNull
     public List<String> getDelegatedScopes(@Nullable ComponentName admin,
             @NonNull String delegatedPackage) {
         throwIfParentInstance("getDelegatedScopes");
         if (mService != null) {
             try {
                 return mService.getDelegatedScopes(admin, delegatedPackage);
             } catch (RemoteException e) {
                 throw e.rethrowFromSystemServer();
             }
         }
         return null;
    }

    /**
     * Called by a profile owner or device owner to retrieve a list of delegate packages that were
     * granted a delegation scope.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param delegationScope The scope whose delegates should be retrieved.
     * @return A list of package names of the current delegated packages for
               {@code delegationScope}.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     */
     @Nullable
     public List<String> getDelegatePackages(@NonNull ComponentName admin,
             @NonNull String delegationScope) {
        throwIfParentInstance("getDelegatePackages");
        if (mService != null) {
            try {
                return mService.getDelegatePackages(admin, delegationScope);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Service-specific error code used in implementation of {@code setAlwaysOnVpnPackage} methods.
     * @hide
     */
    public static final int ERROR_VPN_PACKAGE_NOT_FOUND = 1;

    /**
     * Called by a device or profile owner to configure an always-on VPN connection through a
     * specific application for the current user. This connection is automatically granted and
     * persisted after a reboot.
     * <p> To support the always-on feature, an app must
     * <ul>
     *     <li>declare a {@link android.net.VpnService} in its manifest, guarded by
     *         {@link android.Manifest.permission#BIND_VPN_SERVICE};</li>
     *     <li>target {@link android.os.Build.VERSION_CODES#N API 24} or above; and</li>
     *     <li><i>not</i> explicitly opt out of the feature through
     *         {@link android.net.VpnService#SERVICE_META_DATA_SUPPORTS_ALWAYS_ON}.</li>
     * </ul>
     * The call will fail if called with the package name of an unsupported VPN app.
     * <p> Enabling lockdown via {@code lockdownEnabled} argument carries the risk that any failure
     * of the VPN provider could break networking for all apps. This method clears any lockdown
     * whitelist set by {@link #setAlwaysOnVpnPackage(ComponentName, String, boolean, Set)}.
     *
     * @param vpnPackage The package name for an installed VPN app on the device, or {@code null} to
     *        remove an existing always-on VPN configuration.
     * @param lockdownEnabled {@code true} to disallow networking when the VPN is not connected or
     *        {@code false} otherwise. This has no effect when clearing.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     * @throws NameNotFoundException if {@code vpnPackage} is not installed.
     * @throws UnsupportedOperationException if {@code vpnPackage} exists but does not support being
     *         set as always-on, or if always-on VPN is not available.
     * @see #setAlwaysOnVpnPackage(ComponentName, String, boolean, Set)
     */
    public void setAlwaysOnVpnPackage(@NonNull ComponentName admin, @Nullable String vpnPackage,
            boolean lockdownEnabled) throws NameNotFoundException {
        setAlwaysOnVpnPackage(admin, vpnPackage, lockdownEnabled, Collections.emptySet());
    }

    /**
     * A version of {@link #setAlwaysOnVpnPackage(ComponentName, String, boolean)} that allows the
     * admin to specify a set of apps that should be able to access the network directly when VPN
     * is not connected. When VPN connects these apps switch over to VPN if allowed to use that VPN.
     * System apps can always bypass VPN.
     * <p> Note that the system doesn't update the whitelist when packages are installed or
     * uninstalled, the admin app must call this method to keep the list up to date.
     * <p> When {@code lockdownEnabled} is false {@code lockdownWhitelist} is ignored . When
     * {@code lockdownEnabled} is {@code true} and {@code lockdownWhitelist} is {@code null} or
     * empty, only system apps can bypass VPN.
     * <p> Setting always-on VPN package to {@code null} or using
     * {@link #setAlwaysOnVpnPackage(ComponentName, String, boolean)} clears lockdown whitelist.
     *
     * @param vpnPackage package name for an installed VPN app on the device, or {@code null}
     *         to remove an existing always-on VPN configuration
     * @param lockdownEnabled {@code true} to disallow networking when the VPN is not connected or
     *         {@code false} otherwise. This has no effect when clearing.
     * @param lockdownWhitelist Packages that will be able to access the network directly when VPN
     *         is in lockdown mode but not connected. Has no effect when clearing.
     * @throws SecurityException if {@code admin} is not a device or a profile
     *         owner.
     * @throws NameNotFoundException if {@code vpnPackage} or one of
     *         {@code lockdownWhitelist} is not installed.
     * @throws UnsupportedOperationException if {@code vpnPackage} exists but does
     *         not support being set as always-on, or if always-on VPN is not
     *         available.
     */
    public void setAlwaysOnVpnPackage(@NonNull ComponentName admin, @Nullable String vpnPackage,
            boolean lockdownEnabled, @Nullable Set<String> lockdownWhitelist)
            throws NameNotFoundException {
        throwIfParentInstance("setAlwaysOnVpnPackage");
        if (mService != null) {
            try {
                mService.setAlwaysOnVpnPackage(admin, vpnPackage, lockdownEnabled,
                        lockdownWhitelist == null ? null : new ArrayList<>(lockdownWhitelist));
            } catch (ServiceSpecificException e) {
                switch (e.errorCode) {
                    case ERROR_VPN_PACKAGE_NOT_FOUND:
                        throw new NameNotFoundException(e.getMessage());
                    default:
                        throw new RuntimeException(
                                "Unknown error setting always-on VPN: " + e.errorCode, e);
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by device or profile owner to query whether current always-on VPN is configured in
     * lockdown mode. Returns {@code false} when no always-on configuration is set.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     *
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     *
     * @see #setAlwaysOnVpnPackage(ComponentName, String, boolean)
     */
    public boolean isAlwaysOnVpnLockdownEnabled(@NonNull ComponentName admin) {
        throwIfParentInstance("isAlwaysOnVpnLockdownEnabled");
        if (mService != null) {
            try {
                // Starting from Android R, the caller can pass the permission check in
                // DevicePolicyManagerService if it holds android.permission.MAINLINE_NETWORK_STACK.
                // Note that the android.permission.MAINLINE_NETWORK_STACK is a signature permission
                // which is used by the NetworkStack mainline module.
                return mService.isAlwaysOnVpnLockdownEnabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns whether the admin has enabled always-on VPN lockdown for the current user.
     *
     * Only callable by the system.
    * @hide
    */
    @UserHandleAware
    public boolean isAlwaysOnVpnLockdownEnabled() {
        throwIfParentInstance("isAlwaysOnVpnLockdownEnabled");
        if (mService != null) {
            try {
                return mService.isAlwaysOnVpnLockdownEnabledForUser(myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device or profile owner to query the set of packages that are allowed to access
     * the network directly when always-on VPN is in lockdown mode but not connected. Returns
     * {@code null} when always-on VPN is not active or not in lockdown mode.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     *
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     *
     * @see #setAlwaysOnVpnPackage(ComponentName, String, boolean, Set)
     */
    public @Nullable Set<String> getAlwaysOnVpnLockdownWhitelist(@NonNull ComponentName admin) {
        throwIfParentInstance("getAlwaysOnVpnLockdownWhitelist");
        if (mService != null) {
            try {
                final List<String> whitelist =
                        mService.getAlwaysOnVpnLockdownWhitelist(admin);
                return whitelist == null ? null : new HashSet<>(whitelist);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
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
    public @Nullable String getAlwaysOnVpnPackage(@NonNull ComponentName admin) {
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
     * Returns the VPN package name if the admin has enabled always-on VPN on the current user,
     * or {@code null} if none is set.
     *
     * Only callable by the system.
     * @hide
     */
    @UserHandleAware
    public @Nullable String getAlwaysOnVpnPackage() {
        throwIfParentInstance("getAlwaysOnVpnPackage");
        if (mService != null) {
            try {
                return mService.getAlwaysOnVpnPackageForUser(myUserId());
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
     * This method can be called on the {@link DevicePolicyManager} instance,
     * returned by {@link #getParentProfileInstance(ComponentName)}, where the caller must be
     * the profile owner of an organization-owned managed profile.
     * <p>
     * If the caller is device owner or called on the parent instance, then the
     * restriction will be applied to all users.
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
        if (mService != null) {
            try {
                mService.setCameraDisabled(admin, disabled, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Determine whether or not the device's cameras have been disabled for this user,
     * either by the calling admin, if specified, or all admins.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance,
     * returned by {@link #getParentProfileInstance(ComponentName)}, where the caller must be
     * the profile owner of an organization-owned managed profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to check whether any admins
     * have disabled the camera
     */
    public boolean getCameraDisabled(@Nullable ComponentName admin) {
        return getCameraDisabled(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage
    public boolean getCameraDisabled(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getCameraDisabled(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner to request a bugreport.
     * <p>
     * If the device contains secondary users or profiles, they must be affiliated with the device.
     * Otherwise a {@link SecurityException} will be thrown. See {@link #isAffiliatedUser}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return {@code true} if the bugreport collection started successfully, or {@code false} if it
     *         wasn't triggered because a previous bugreport operation is still active (either the
     *         bugreport is still running or waiting for the user to share or decline)
     * @throws SecurityException if {@code admin} is not a device owner, or there is at least one
     *         profile or secondary user that is not affiliated with the device.
     * @see #isAffiliatedUser
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
     * Called by a device/profile owner to set whether the screen capture is disabled. Disabling
     * screen capture also prevents the content from being shown on display devices that do not have
     * a secure video output. See {@link android.view.Display#FLAG_SECURE} for more details about
     * secure surfaces and secure displays.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance, returned by
     * {@link #getParentProfileInstance(ComponentName)}, where the calling device admin must be
     * the profile owner of an organization-owned managed profile. If it is not, a security
     * exception will be thrown.
     * <p>
     * If the caller is device owner or called on the parent instance by a profile owner of an
     * organization-owned managed profile, then the restriction will be applied to all users.
     * <p>
     * From version {@link android.os.Build.VERSION_CODES#M} disabling screen capture also blocks
     * assist requests for all activities of the relevant user.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled Whether screen capture is disabled or not.
     * @throws SecurityException if {@code admin} is not a device or profile owner or if
     *                           called on the parent profile and the {@code admin} is not a
     *                           profile owner of an organization-owned managed profile.
     */
    public void setScreenCaptureDisabled(@NonNull ComponentName admin, boolean disabled) {
        if (mService != null) {
            try {
                mService.setScreenCaptureDisabled(admin, disabled, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Determine whether or not screen capture has been disabled by the calling
     * admin, if specified, or all admins.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance,
     * returned by {@link #getParentProfileInstance(ComponentName)}, where the caller must be
     * the profile owner of an organization-owned managed profile (the calling admin must be
     * specified).
     *
     * @param admin The name of the admin component to check, or {@code null} to check whether any
     *              admins have disabled screen capture.
     */
    public boolean getScreenCaptureDisabled(@Nullable ComponentName admin) {
        return getScreenCaptureDisabled(admin, myUserId());
    }

    /** @hide per-user version */
    public boolean getScreenCaptureDisabled(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getScreenCaptureDisabled(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner, or alternatively a profile owner from Android 8.0 (API level 26) or
     * higher, to set whether auto time is required. If auto time is required, no user will be able
     * set the date and time and network date and time will be used.
     * <p>
     * Note: if auto time is required the user can still manually set the time zone.
     * <p>
     * The calling device admin must be a device owner, or alternatively a profile owner from
     * Android 8.0 (API level 26) or higher. If it is not, a security exception will be thrown.
     * <p>
     * Staring from Android 11, this API switches to use
     * {@link UserManager#DISALLOW_CONFIG_DATE_TIME} to enforce the auto time settings. Calling
     * this API to enforce auto time will result in
     * {@link UserManager#DISALLOW_CONFIG_DATE_TIME} being set, while calling this API to lift
     * the requirement will result in {@link UserManager#DISALLOW_CONFIG_DATE_TIME} being cleared.
     * From Android 11, this API can also no longer be called on a managed profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param required Whether auto time is set required or not.
     * @throws SecurityException if {@code admin} is not a device owner, not a profile owner or
     * if this API is called on a managed profile.
     * @deprecated From {@link android.os.Build.VERSION_CODES#R}. Use {@link #setAutoTimeEnabled}
     * to turn auto time on or off and use {@link UserManager#DISALLOW_CONFIG_DATE_TIME}
     * to prevent the user from changing this setting.
     */
    @Deprecated
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
     * @deprecated From {@link android.os.Build.VERSION_CODES#R}. Use {@link #getAutoTimeEnabled}
     */
    @Deprecated
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
     * Called by a device owner, a profile owner for the primary user or a profile
     * owner of an organization-owned managed profile to turn auto time on and off.
     * Callers are recommended to use {@link UserManager#DISALLOW_CONFIG_DATE_TIME}
     * to prevent the user from changing this setting.
     * <p>
     * If user restriction {@link UserManager#DISALLOW_CONFIG_DATE_TIME} is used,
     * no user will be able set the date and time. Instead, the network date
     * and time will be used.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param enabled Whether time should be obtained automatically from the network or not.
     * @throws SecurityException if caller is not a device owner, a profile owner for the
     * primary user, or a profile owner of an organization-owned managed profile.
     */
    public void setAutoTimeEnabled(@NonNull ComponentName admin, boolean enabled) {
        if (mService != null) {
            try {
                mService.setAutoTimeEnabled(admin, enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @return true if auto time is enabled on the device.
     * @throws SecurityException if caller is not a device owner, a profile owner for the
     * primary user, or a profile owner of an organization-owned managed profile.
     */
    public boolean getAutoTimeEnabled(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getAutoTimeEnabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner, a profile owner for the primary user or a profile
     * owner of an organization-owned managed profile to turn auto time zone on and off.
     * Callers are recommended to use {@link UserManager#DISALLOW_CONFIG_DATE_TIME}
     * to prevent the user from changing this setting.
     * <p>
     * If user restriction {@link UserManager#DISALLOW_CONFIG_DATE_TIME} is used,
     * no user will be able set the date and time zone. Instead, the network date
     * and time zone will be used.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param enabled Whether time zone should be obtained automatically from the network or not.
     * @throws SecurityException if caller is not a device owner, a profile owner for the
     * primary user, or a profile owner of an organization-owned managed profile.
     */
    public void setAutoTimeZoneEnabled(@NonNull ComponentName admin, boolean enabled) {
        throwIfParentInstance("setAutoTimeZone");
        if (mService != null) {
            try {
                mService.setAutoTimeZoneEnabled(admin, enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @return true if auto time zone is enabled on the device.
     * @throws SecurityException if caller is not a device owner, a profile owner for the
     * primary user, or a profile owner of an organization-owned managed profile.
     */
    public boolean getAutoTimeZoneEnabled(@NonNull ComponentName admin) {
        throwIfParentInstance("getAutoTimeZone");
        if (mService != null) {
            try {
                return mService.getAutoTimeZoneEnabled(admin);
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
     * <li>{@link #KEYGUARD_DISABLE_FINGERPRINT}, {@link #KEYGUARD_DISABLE_FACE} or
     * {@link #KEYGUARD_DISABLE_IRIS} which affects the managed profile challenge if
     * there is one, or the parent user otherwise.
     * <li>{@link #KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS} which affects notifications generated
     * by applications in the managed profile.
     * </ul>
     * <p>
     * From version {@link android.os.Build.VERSION_CODES#R} the profile owner of an
     * organization-owned managed profile can set:
     * <ul>
     * <li>{@link #KEYGUARD_DISABLE_SECURE_CAMERA} which affects the parent user when called on the
     * parent profile.
     * <li>{@link #KEYGUARD_DISABLE_SECURE_NOTIFICATIONS} which affects the parent user when called
     * on the parent profile.
     * </ul>
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS}, {@link #KEYGUARD_DISABLE_FINGERPRINT},
     * {@link #KEYGUARD_DISABLE_FACE}, {@link #KEYGUARD_DISABLE_IRIS},
     * {@link #KEYGUARD_DISABLE_SECURE_CAMERA} and {@link #KEYGUARD_DISABLE_SECURE_NOTIFICATIONS}
     * can also be set on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile. {@link #KEYGUARD_DISABLE_SECURE_CAMERA} can only be set on the parent profile
     * instance if the calling device admin is the profile owner of an organization-owned
     * managed profile.
     * <p>
     * Requests to disable other features on a managed profile will be ignored.
     * <p>
     * The admin can check which features have been disabled by calling
     * {@link #getKeyguardDisabledFeatures(ComponentName)}
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param which The disabled features flag which can be either
     *            {@link #KEYGUARD_DISABLE_FEATURES_NONE} (default),
     *            {@link #KEYGUARD_DISABLE_FEATURES_ALL}, or a combination of
     *            {@link #KEYGUARD_DISABLE_WIDGETS_ALL}, {@link #KEYGUARD_DISABLE_SECURE_CAMERA},
     *            {@link #KEYGUARD_DISABLE_SECURE_NOTIFICATIONS},
     *            {@link #KEYGUARD_DISABLE_TRUST_AGENTS},
     *            {@link #KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS},
     *            {@link #KEYGUARD_DISABLE_FINGERPRINT},
     *            {@link #KEYGUARD_DISABLE_FACE},
     *            {@link #KEYGUARD_DISABLE_IRIS}.
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public void reportPasswordChanged(@UserIdInt int userId) {
        if (mService != null) {
            try {
                mService.reportPasswordChanged(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
    @UnsupportedAppUsage
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public void reportFailedBiometricAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportFailedBiometricAttempt(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public void reportSuccessfulBiometricAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportSuccessfulBiometricAttempt(userHandle);
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
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
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
     * @return Handle of the user who runs device owner, or {@code null} if there's no device owner.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @SystemApi
    public @Nullable UserHandle getDeviceOwnerUser() {
        if (mService != null) {
            try {
                int userId = mService.getDeviceOwnerUserId();

                if (userId != UserHandle.USER_NULL) {
                    return UserHandle.of(userId);
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
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
     * a part of device setup, before it completes.
     * <p>
     * While some policies previously set by the device owner will be cleared by this method, it is
     * a best-effort process and some other policies will still remain in place after the device
     * owner is cleared.
     *
     * @param packageName The package name of the device owner.
     * @throws SecurityException if the caller is not in {@code packageName} or {@code packageName}
     *             does not own the current device owner component.
     *
     * @deprecated This method is expected to be used for testing purposes only. The device owner
     * will lose control of the device and its data after calling it. In order to protect any
     * sensitive data that remains on the device, it is advised that the device owner factory resets
     * the device instead of calling this method. See {@link #wipeData(int)}.
     */
    @Deprecated
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
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public @Nullable String getDeviceOwner() {
        throwIfParentInstance("getDeviceOwner");
        final ComponentName name = getDeviceOwnerComponentOnCallingUser();
        return name != null ? name.getPackageName() : null;
    }

    /**
     * Called by the system to find out whether the device is managed by a Device Owner.
     *
     * @return whether the device is managed by a Device Owner.
     * @throws SecurityException if the caller is not the device owner, does not hold the
     *         MANAGE_USERS permission and is not the system.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @SuppressLint("Doclava125")
    public boolean isDeviceManaged() {
        try {
            return mService.hasDeviceOwner();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the device owner name.  Note this method *will* return the device owner
     * name when it's running on a different user.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
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
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_DEVICE_ADMINS)
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
     * Clears the active profile owner. The caller must be the profile owner of this user, otherwise
     * a SecurityException will be thrown. This method is not available to managed profile owners.
     * <p>
     * While some policies previously set by the profile owner will be cleared by this method, it is
     * a best-effort process and some other policies will still remain in place after the profile
     * owner is cleared.
     *
     * @param admin The component to remove as the profile owner.
     * @throws SecurityException if {@code admin} is not an active profile owner, or the method is
     * being called from a managed profile.
     *
     * @deprecated This method is expected to be used for testing purposes only. The profile owner
     * will lose control of the user and its data after calling it. In order to protect any
     * sensitive data that remains on this user, it is advised that the profile owner deletes it
     * instead of calling this method. See {@link #wipeData(int)}.
     */
    @Deprecated
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
     * Device owner information set using this method overrides any owner information manually set
     * by the user and prevents the user from further changing it.
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
     * <p>
     * May be called by the device owner or the profile owner of an organization-owned device.
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
     * Called by device or profile owners to suspend packages for this user. This function can be
     * called by a device owner, profile owner, or by a delegate given the
     * {@link #DELEGATION_PACKAGE_ACCESS} scope via {@link #setDelegatedScopes}.
     * <p>
     * A suspended package will not be able to start activities. Its notifications will be hidden,
     * it will not show up in recents, will not be able to show toasts or dialogs or ring the
     * device.
     * <p>
     * The package must already be installed. If the package is uninstalled while suspended the
     * package will no longer be suspended. The admin can block this by using
     * {@link #setUninstallBlocked}.
     *
     * <p>Some apps cannot be suspended, such as device admins, the active launcher, the required
     * package installer, the required package uninstaller, the required package verifier, the
     * default dialer, and the permission controller.
     *
     * @param admin The name of the admin component to check, or {@code null} if the caller is a
     *            package access delegate.
     * @param packageNames The package names to suspend or unsuspend.
     * @param suspended If set to {@code true} than the packages will be suspended, if set to
     *            {@code false} the packages will be unsuspended.
     * @return an array of package names for which the suspended status is not set as requested in
     *         this method.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    public @NonNull String[] setPackagesSuspended(@NonNull ComponentName admin,
            @NonNull String[] packageNames, boolean suspended) {
        throwIfParentInstance("setPackagesSuspended");
        if (mService != null) {
            try {
                return mService.setPackagesSuspended(admin, mContext.getPackageName(), packageNames,
                        suspended);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return packageNames;
    }

    /**
     * Determine if a package is suspended. This function can be called by a device owner, profile
     * owner, or by a delegate given the {@link #DELEGATION_PACKAGE_ACCESS} scope via
     * {@link #setDelegatedScopes}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is a package access delegate.
     * @param packageName The name of the package to retrieve the suspended status of.
     * @return {@code true} if the package is suspended or {@code false} if the package is not
     *         suspended, could not be found or an error occurred.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @throws NameNotFoundException if the package could not be found.
     * @see #setDelegatedScopes
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    public boolean isPackageSuspended(@NonNull ComponentName admin, String packageName)
            throws NameNotFoundException {
        throwIfParentInstance("isPackageSuspended");
        if (mService != null) {
            try {
                return mService.isPackageSuspended(admin, mContext.getPackageName(), packageName);
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
    public @Nullable ComponentName getProfileOwner() throws IllegalArgumentException {
        throwIfParentInstance("getProfileOwner");
        return getProfileOwnerAsUser(mContext.getUserId());
    }

    /**
     * @see #getProfileOwner()
     * @hide
     */
    @RequiresPermission(value = android.Manifest.permission.INTERACT_ACROSS_USERS,
            conditional = true)
    public @Nullable ComponentName getProfileOwnerAsUser(@NonNull UserHandle user) {
        if (mService != null) {
            try {
                return mService.getProfileOwnerAsUser(user.getIdentifier());
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public @Nullable ComponentName getProfileOwnerAsUser(final int userId) {
        if (mService != null) {
            try {
                return mService.getProfileOwnerAsUser(userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Returns the configured supervision app if it exists and is the device owner or policy owner.
     * @hide
     */
    public @Nullable ComponentName getProfileOwnerOrDeviceOwnerSupervisionComponent(
            @NonNull UserHandle user) {
        if (mService != null) {
            try {
                return mService.getProfileOwnerOrDeviceOwnerSupervisionComponent(user);
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
    public @Nullable String getProfileOwnerName() throws IllegalArgumentException {
        if (mService != null) {
            try {
                return mService.getProfileOwnerName(mContext.getUserId());
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
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public @Nullable String getProfileOwnerNameAsUser(int userId) throws IllegalArgumentException {
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
     * Apps can use this method to find out if the device was provisioned as
     * organization-owend device with a managed profile.
     *
     * This, together with checking whether the device has a device owner (by calling
     * {@link #isDeviceOwnerApp}), could be used to learn whether the device is owned by an
     * organization or an individual:
     * If this method returns true OR {@link #isDeviceOwnerApp} returns true (for any package),
     * then the device is owned by an organization. Otherwise, it's owned by an individual.
     *
     * @return {@code true} if the device was provisioned as organization-owned device,
     * {@code false} otherwise.
     */
    public boolean isOrganizationOwnedDeviceWithManagedProfile() {
        throwIfParentInstance("isOrganizationOwnedDeviceWithManagedProfile");
        if (mService != null) {
            try {
                return mService.isOrganizationOwnedDeviceWithManagedProfile();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns whether the specified package can read the device identifiers.
     *
     * @param packageName The package name of the app to check for device identifier access.
     * @param pid The process id of the package to be checked.
     * @param uid The uid of the package to be checked.
     * @return whether the package can read the device identifiers.
     *
     * @hide
     */
    public boolean hasDeviceIdentifierAccess(@NonNull String packageName, int pid, int uid) {
        throwIfParentInstance("hasDeviceIdentifierAccess");
        if (packageName == null) {
            return false;
        }
        if (mService != null) {
            try {
                return mService.checkDeviceIdentifierAccess(packageName, pid, uid);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a profile owner or device owner to set a default activity that the system selects
     * to handle intents that match the given {@link IntentFilter}. This activity will remain the
     * default intent handler even if the set of potential event handlers for the intent filter
     * changes and if the intent preferences are reset.
     * <p>
     * Note that the caller should still declare the activity in the manifest, the API just sets
     * the activity to be the default one to handle the given intent filter.
     * <p>
     * The default disambiguation mechanism takes over if the activity is not installed (anymore).
     * When the activity is (re)installed, it is automatically reset as default intent handler for
     * the filter.
     * <p>
     * The calling device admin must be a profile owner or device owner. If it is not, a security
     * exception will be thrown.
     *
     * <p>NOTE: Performs disk I/O and shouldn't be called on the main thread.
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
     * Must be called by a device owner or a profile owner of an organization-owned managed profile
     * to set the default SMS application.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance, returned by
     * {@link #getParentProfileInstance(ComponentName)}, where the caller must be the profile owner
     * of an organization-owned managed profile and the package must be a pre-installed system
     * package. If called on the parent instance, then the default SMS application is set on the
     * personal profile.
     *
     * @param admin       Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package to set as the default SMS application.
     * @throws SecurityException        if {@code admin} is not a device or profile owner or if
     *                                  called on the parent profile and the {@code admin} is not a
     *                                  profile owner of an organization-owned managed profile.
     * @throws IllegalArgumentException if called on the parent profile and the package
     *                                  provided is not a pre-installed system package.
     */
    public void setDefaultSmsApplication(@NonNull ComponentName admin,
            @NonNull String packageName) {
        if (mService != null) {
            try {
                mService.setDefaultSmsApplication(admin, packageName, mParentInstance);
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
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}. Use {@link #setDelegatedScopes}
     * with the {@link #DELEGATION_APP_RESTRICTIONS} scope instead.
     */
    @Deprecated
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
     * package for the current user, or {@code null} if none is set. If there are multiple
     * delegates this function will return one of them.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The package name allowed to manage application restrictions on the current user, or
     *         {@code null} if none is set.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}. Use {@link #getDelegatePackages}
     * with the {@link #DELEGATION_APP_RESTRICTIONS} scope instead.
     */
    @Deprecated
    @Nullable
    public String getApplicationRestrictionsManagingPackage(
            @NonNull ComponentName admin) {
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
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}. Use {@link #getDelegatedScopes}
     * instead.
     */
    @Deprecated
    public boolean isCallerApplicationRestrictionsManagingPackage() {
        throwIfParentInstance("isCallerApplicationRestrictionsManagingPackage");
        if (mService != null) {
            try {
                return mService.isCallerApplicationRestrictionsManagingPackage(
                        mContext.getPackageName());
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
     * application restrictions via {@link #setDelegatedScopes} with the
     * {@link #DELEGATION_APP_RESTRICTIONS} scope; otherwise a security exception will be thrown.
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
     * <p>NOTE: The method performs disk I/O and shouldn't be called on the main thread
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if called by the application restrictions managing package.
     * @param packageName The name of the package to update restricted settings for.
     * @param settings A {@link Bundle} to be parsed by the receiving application, conveying a new
     *            set of active restrictions.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_APP_RESTRICTIONS
     * @see UserManager#KEY_RESTRICTIONS_PENDING
     */
    @WorkerThread
    public void setApplicationRestrictions(@Nullable ComponentName admin, String packageName,
            Bundle settings) {
        throwIfParentInstance("setApplicationRestrictions");
        if (mService != null) {
            try {
                mService.setApplicationRestrictions(admin, mContext.getPackageName(), packageName,
                        settings);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Sets a list of configuration features to enable for a trust agent component. This is meant to
     * be used in conjunction with {@link #KEYGUARD_DISABLE_TRUST_AGENTS}, which disables all trust
     * agents but those enabled by this function call. If flag
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS} is not set, then this call has no effect.
     * <p>
     * For any specific trust agent, whether it is disabled or not depends on the aggregated state
     * of each admin's {@link #KEYGUARD_DISABLE_TRUST_AGENTS} setting and its trust agent
     * configuration as set by this function call. In particular: if any admin sets
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS} and does not additionally set any
     * trust agent configuration, the trust agent is disabled completely. Otherwise, the trust agent
     * will receive the list of configurations from all admins who set
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS} and aggregate the configurations to determine its
     * behavior. The exact meaning of aggregation is trust-agent-specific.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES} to be able to call this method;
     * if not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set the configuration for
     * the parent profile.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, calling
     * this method has no effect - no trust agent configuration will be set.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param target Component name of the agent to be configured.
     * @param configuration Trust-agent-specific feature configuration bundle. Please consult
     *        documentation of the specific trust agent to determine the interpretation of this
     *        bundle.
     * @throws SecurityException if {@code admin} is not an active administrator or does not use
     *             {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES}
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
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
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, null is
     * always returned.
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
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public @Nullable List<PersistableBundle> getTrustAgentConfiguration(
            @Nullable ComponentName admin, @NonNull ComponentName agent) {
        return getTrustAgentConfiguration(admin, agent, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public @Nullable List<PersistableBundle> getTrustAgentConfiguration(
            @Nullable ComponentName admin, @NonNull ComponentName agent, int userHandle) {
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
     * @throws SecurityException if {@code admin} is not a profile owner.
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
     * @throws SecurityException if {@code admin} is not a profile owner.
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
     * @throws SecurityException if {@code admin} is not a profile owner.
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
     * @throws SecurityException if {@code admin} is not a profile owner.
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
     * @throws SecurityException if {@code admin} is not a profile owner.
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
     * @throws SecurityException if {@code admin} is not a profile owner.
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
    @SystemApi
    @RequiresPermission(permission.INTERACT_ACROSS_USERS)
    public boolean getBluetoothContactSharingDisabled(@NonNull UserHandle userHandle) {
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
     * <p>
     * <em>Note</em>: A list of default cross profile intent filters are set up by the system when
     * the profile is created, some of them ensure the proper functioning of the profile, while
     * others enable sharing of data from the parent to the managed profile for user convenience.
     * These default intent filters are not cleared when this API is called. If the default cross
     * profile data sharing is not desired, they can be disabled with
     * {@link UserManager#DISALLOW_SHARE_INTO_MANAGED_PROFILE}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a profile owner.
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
     * Called by a profile or device owner to set the permitted
     * {@link android.accessibilityservice.AccessibilityService}. When set by
     * a device owner or profile owner the restriction applies to all profiles of the user the
     * device owner or profile owner is an admin for. By default, the user can use any accessibility
     * service. When zero or more packages have been added, accessibility services that are not in
     * the list and not part of the system can not be enabled by the user.
     * <p>
     * Calling with a null value for the list disables the restriction so that all services can be
     * used, calling with an empty list only allows the built-in system services. Any non-system
     * accessibility service that's currently enabled must be included in the list.
     * <p>
     * System accessibility services are always available to the user and this method can't
     * disable them.
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageNames List of accessibility service package names.
     * @return {@code true} if the operation succeeded, or {@code false} if the list didn't
     *         contain every enabled non-system accessibility service.
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
    public @Nullable List<String> getPermittedAccessibilityServices(@NonNull ComponentName admin) {
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
     @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
     public @Nullable List<String> getPermittedAccessibilityServices(int userId) {
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
     * Called by a profile or device owner to set the permitted input methods services for this
     * user. By default, the user can use any input method.
     * <p>
     * When zero or more packages have been added, input method that are not in the list and not
     * part of the system can not be enabled by the user. This method will fail if it is called for
     * a admin that is not for the foreground user or a profile of the foreground user. Any
     * non-system input method service that's currently enabled must be included in the list.
     * <p>
     * Calling with a null value for the list disables the restriction so that all input methods can
     * be used, calling with an empty list disables all but the system's own input methods.
     * <p>
     * System input methods are always available to the user - this method can't modify this.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageNames List of input method package names.
     * @return {@code true} if the operation succeeded, or {@code false} if the list didn't
     *        contain every enabled non-system input method service.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean setPermittedInputMethods(
            @NonNull ComponentName admin, List<String> packageNames) {
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
    public @Nullable List<String> getPermittedInputMethods(@NonNull ComponentName admin) {
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
     * Returns the list of input methods permitted by the device or profiles owners.
     *
     * <p>On {@link android.os.Build.VERSION_CODES#Q} and later devices, this method returns the
     * result for the calling user.</p>
     *
     * <p>On Android P and prior devices, this method returns the result for the current user.</p>
     *
     * <p>Null means all input methods are allowed, if a non-null list is returned
     * it will contain the intersection of the permitted lists for any device or profile
     * owners that apply to this user. It will also include any system input methods.
     *
     * @return List of input method package names.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public @Nullable List<String> getPermittedInputMethodsForCurrentUser() {
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
     * Called by a profile owner of a managed profile to set the packages that are allowed to use
     * a {@link android.service.notification.NotificationListenerService} in the primary user to
     * see notifications from the managed profile. By default all packages are permitted by this
     * policy. When zero or more packages have been added, notification listeners installed on the
     * primary user that are not in the list and are not part of the system won't receive events
     * for managed profile notifications.
     * <p>
     * Calling with a {@code null} value for the list disables the restriction so that all
     * notification listener services be used. Calling with an empty list disables all but the
     * system's own notification listeners. System notification listener services are always
     * available to the user.
     * <p>
     * If a device or profile owner want to stop notification listeners in their user from seeing
     * that user's notifications they should prevent that service from running instead (e.g. via
     * {@link #setApplicationHidden(ComponentName, String, boolean)})
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageList List of package names to whitelist
     * @return true if setting the restriction succeeded. It will fail if called outside a managed
     * profile
     * @throws SecurityException if {@code admin} is not a profile owner.
     *
     * @see android.service.notification.NotificationListenerService
     */
    public boolean setPermittedCrossProfileNotificationListeners(
            @NonNull ComponentName admin, @Nullable List<String> packageList) {
        throwIfParentInstance("setPermittedCrossProfileNotificationListeners");
        if (mService != null) {
            try {
                return mService.setPermittedCrossProfileNotificationListeners(admin, packageList);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns the list of packages installed on the primary user that allowed to use a
     * {@link android.service.notification.NotificationListenerService} to receive
     * notifications from this managed profile, as set by the profile owner.
     * <p>
     * An empty list means no notification listener services except system ones are allowed.
     * A {@code null} return value indicates that all notification listeners are allowed.
     */
    public @Nullable List<String> getPermittedCrossProfileNotificationListeners(
            @NonNull ComponentName admin) {
        throwIfParentInstance("getPermittedCrossProfileNotificationListeners");
        if (mService != null) {
            try {
                return mService.getPermittedCrossProfileNotificationListeners(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Returns true if {@code NotificationListenerServices} from the given package are allowed to
     * receive events for notifications from the given user id. Can only be called by the system uid
     *
     * @see #setPermittedCrossProfileNotificationListeners(ComponentName, List)
     *
     * @hide
     */
    public boolean isNotificationListenerServicePermitted(
            @NonNull String packageName, @UserIdInt int userId) {
        if (mService != null) {
            try {
                return mService.isNotificationListenerServicePermitted(packageName, userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return true;
    }

    /**
     * Get the list of apps to keep around as APKs even if no user has currently installed it. This
     * function can be called by a device owner or by a delegate given the
     * {@link #DELEGATION_KEEP_UNINSTALLED_PACKAGES} scope via {@link #setDelegatedScopes}.
     * <p>
     * Please note that packages returned in this method are not automatically pre-cached.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is a keep uninstalled packages delegate.
     * @return List of package names to keep cached.
     * @see #setDelegatedScopes
     * @see #DELEGATION_KEEP_UNINSTALLED_PACKAGES
     */
    public @Nullable List<String> getKeepUninstalledPackages(@Nullable ComponentName admin) {
        throwIfParentInstance("getKeepUninstalledPackages");
        if (mService != null) {
            try {
                return mService.getKeepUninstalledPackages(admin, mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Set a list of apps to keep around as APKs even if no user has currently installed it. This
     * function can be called by a device owner or by a delegate given the
     * {@link #DELEGATION_KEEP_UNINSTALLED_PACKAGES} scope via {@link #setDelegatedScopes}.
     *
     * <p>Please note that setting this policy does not imply that specified apps will be
     * automatically pre-cached.</p>
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is a keep uninstalled packages delegate.
     * @param packageNames List of package names to keep cached.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_KEEP_UNINSTALLED_PACKAGES
     */
    public void setKeepUninstalledPackages(@Nullable ComponentName admin,
            @NonNull List<String> packageNames) {
        throwIfParentInstance("setKeepUninstalledPackages");
        if (mService != null) {
            try {
                mService.setKeepUninstalledPackages(admin, mContext.getPackageName(), packageNames);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
      * Flag used by {@link #createAndManageUser} to skip setup wizard after creating a new user.
      */
    public static final int SKIP_SETUP_WIZARD = 0x0001;

    /**
     * Flag used by {@link #createAndManageUser} to specify that the user should be created
     * ephemeral. Ephemeral users will be removed after switching to another user or rebooting the
     * device.
     */
    public static final int MAKE_USER_EPHEMERAL = 0x0002;

    /**
     * Flag used by {@link #createAndManageUser} to specify that the user should be created as a
     * demo user.
     * @hide
     */
    public static final int MAKE_USER_DEMO = 0x0004;

    /**
     * Flag used by {@link #createAndManageUser} to specify that the newly created user should skip
     * the disabling of system apps during provisioning.
     */
    public static final int LEAVE_ALL_SYSTEM_APPS_ENABLED = 0x0010;

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = { "SKIP_", "MAKE_USER_", "START_", "LEAVE_" }, value = {
            SKIP_SETUP_WIZARD,
            MAKE_USER_EPHEMERAL,
            MAKE_USER_DEMO,
            LEAVE_ALL_SYSTEM_APPS_ENABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CreateAndManageUserFlags {}

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
     * <p>From {@link android.os.Build.VERSION_CODES#P} onwards, if targeting
     * {@link android.os.Build.VERSION_CODES#P}, throws {@link UserOperationException} instead of
     * returning {@code null} on failure.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param name The user's name.
     * @param profileOwner Which {@link DeviceAdminReceiver} will be profile owner. Has to be in the
     *            same package as admin, otherwise no user is created and an
     *            IllegalArgumentException is thrown.
     * @param adminExtras Extras that will be passed to onEnable of the admin receiver on the new
     *            user.
     * @param flags {@link #SKIP_SETUP_WIZARD}, {@link #MAKE_USER_EPHEMERAL} and
     *        {@link #LEAVE_ALL_SYSTEM_APPS_ENABLED} are supported.
     * @see UserHandle
     * @return the {@link android.os.UserHandle} object for the created user, or {@code null} if the
     *         user could not be created.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @throws UserOperationException if the user could not be created and the calling app is
     * targeting {@link android.os.Build.VERSION_CODES#P} and running on
     * {@link android.os.Build.VERSION_CODES#P}.
     */
    public @Nullable UserHandle createAndManageUser(@NonNull ComponentName admin,
            @NonNull String name,
            @NonNull ComponentName profileOwner, @Nullable PersistableBundle adminExtras,
            @CreateAndManageUserFlags int flags) {
        throwIfParentInstance("createAndManageUser");
        try {
            return mService.createAndManageUser(admin, name, profileOwner, adminExtras, flags);
        } catch (ServiceSpecificException e) {
            throw new UserOperationException(e.getMessage(), e.errorCode);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to remove a user/profile and all associated data. The primary user
     * can not be removed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to remove.
     * @return {@code true} if the user was removed, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public boolean removeUser(@NonNull ComponentName admin, @NonNull UserHandle userHandle) {
        throwIfParentInstance("removeUser");
        try {
            return mService.removeUser(admin, userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to switch the specified secondary user to the foreground.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to switch to; null will switch to primary.
     * @return {@code true} if the switch was successful, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see Intent#ACTION_USER_FOREGROUND
     * @see #getSecondaryUsers(ComponentName)
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
     * Called by a device owner to start the specified secondary user in background.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to be started in background.
     * @return one of the following result codes:
     * {@link UserManager#USER_OPERATION_ERROR_UNKNOWN},
     * {@link UserManager#USER_OPERATION_SUCCESS},
     * {@link UserManager#USER_OPERATION_ERROR_MANAGED_PROFILE},
     * {@link UserManager#USER_OPERATION_ERROR_MAX_RUNNING_USERS},
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see #getSecondaryUsers(ComponentName)
     */
    public @UserOperationResult int startUserInBackground(
            @NonNull ComponentName admin, @NonNull UserHandle userHandle) {
        throwIfParentInstance("startUserInBackground");
        try {
            return mService.startUserInBackground(admin, userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to stop the specified secondary user.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to be stopped.
     * @return one of the following result codes:
     * {@link UserManager#USER_OPERATION_ERROR_UNKNOWN},
     * {@link UserManager#USER_OPERATION_SUCCESS},
     * {@link UserManager#USER_OPERATION_ERROR_MANAGED_PROFILE},
     * {@link UserManager#USER_OPERATION_ERROR_CURRENT_USER}
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see #getSecondaryUsers(ComponentName)
     */
    public @UserOperationResult int stopUser(
            @NonNull ComponentName admin, @NonNull UserHandle userHandle) {
        throwIfParentInstance("stopUser");
        try {
            return mService.stopUser(admin, userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a profile owner of secondary user that is affiliated with the device to stop the
     * calling user and switch back to primary.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return one of the following result codes:
     * {@link UserManager#USER_OPERATION_ERROR_UNKNOWN},
     * {@link UserManager#USER_OPERATION_SUCCESS},
     * {@link UserManager#USER_OPERATION_ERROR_MANAGED_PROFILE},
     * {@link UserManager#USER_OPERATION_ERROR_CURRENT_USER}
     * @throws SecurityException if {@code admin} is not a profile owner affiliated with the device.
     * @see #getSecondaryUsers(ComponentName)
     */
    public @UserOperationResult int logoutUser(@NonNull ComponentName admin) {
        throwIfParentInstance("logoutUser");
        try {
            return mService.logoutUser(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to list all secondary users on the device. Managed profiles are not
     * considered as secondary users.
     * <p> Used for various user management APIs, including {@link #switchUser}, {@link #removeUser}
     * and {@link #stopUser}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return list of other {@link UserHandle}s on the device.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see #removeUser(ComponentName, UserHandle)
     * @see #switchUser(ComponentName, UserHandle)
     * @see #startUserInBackground(ComponentName, UserHandle)
     * @see #stopUser(ComponentName, UserHandle)
     */
    public List<UserHandle> getSecondaryUsers(@NonNull ComponentName admin) {
        throwIfParentInstance("getSecondaryUsers");
        try {
            return mService.getSecondaryUsers(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the profile owner is running in an ephemeral user.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return whether the profile owner is running in an ephemeral user.
     */
    public boolean isEphemeralUser(@NonNull ComponentName admin) {
        throwIfParentInstance("isEphemeralUser");
        try {
            return mService.isEphemeralUser(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the application restrictions for a given target application running in the calling
     * user.
     * <p>
     * The caller must be a profile or device owner on that user, or the package allowed to manage
     * application restrictions via {@link #setDelegatedScopes} with the
     * {@link #DELEGATION_APP_RESTRICTIONS} scope; otherwise a security exception will be thrown.
     *
     * <p>NOTE: The method performs disk I/O and shouldn't be called on the main thread
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if called by the application restrictions managing package.
     * @param packageName The name of the package to fetch restricted settings of.
     * @return {@link Bundle} of settings corresponding to what was set last time
     *         {@link DevicePolicyManager#setApplicationRestrictions} was called, or an empty
     *         {@link Bundle} if no restrictions have been set.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_APP_RESTRICTIONS
     */
    @WorkerThread
    public @NonNull Bundle getApplicationRestrictions(
            @Nullable ComponentName admin, String packageName) {
        throwIfParentInstance("getApplicationRestrictions");
        if (mService != null) {
            try {
                return mService.getApplicationRestrictions(admin, mContext.getPackageName(),
                        packageName);
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
     * <p>
     * The profile owner of an organization-owned managed profile may invoke this method on
     * the {@link DevicePolicyManager} instance it obtained from
     * {@link #getParentProfileInstance(ComponentName)}, for enforcing device-wide restrictions.
     * <p>
     * See the constants in {@link android.os.UserManager} for the list of restrictions that can
     * be enforced device-wide.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param key   The key of the restriction.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void addUserRestriction(@NonNull ComponentName admin,
            @UserManager.UserRestrictionKey String key) {
        if (mService != null) {
            try {
                mService.setUserRestriction(admin, key, true, mParentInstance);
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
     * <p>
     * The profile owner of an organization-owned managed profile may invoke this method on
     * the {@link DevicePolicyManager} instance it obtained from
     * {@link #getParentProfileInstance(ComponentName)}, for clearing device-wide restrictions.
     * <p>
     * See the constants in {@link android.os.UserManager} for the list of restrictions.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param key   The key of the restriction.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void clearUserRestriction(@NonNull ComponentName admin,
            @UserManager.UserRestrictionKey String key) {
        if (mService != null) {
            try {
                mService.setUserRestriction(admin, key, false, mParentInstance);
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
     * <p>
     * The profile owner of an organization-owned managed profile may invoke this method on
     * the {@link DevicePolicyManager} instance it obtained from
     * {@link #getParentProfileInstance(ComponentName)}, for retrieving device-wide restrictions
     * it previously set with {@link #addUserRestriction(ComponentName, String)}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public @NonNull Bundle getUserRestrictions(@NonNull ComponentName admin) {
        Bundle ret = null;
        if (mService != null) {
            try {
                ret = mService.getUserRestrictions(admin, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return ret == null ? new Bundle() : ret;
    }

    /**
     * Called by any app to display a support dialog when a feature was disabled by an admin.
     * This returns an intent that can be used with {@link Context#startActivity(Intent)} to
     * display the dialog. It will tell the user that the feature indicated by {@code restriction}
     * was disabled by an admin, and include a link for more information. The default content of
     * the dialog can be changed by the restricting admin via
     * {@link #setShortSupportMessage(ComponentName, CharSequence)}. If the restriction is not
     * set (i.e. the feature is available), then the return value will be {@code null}.
     * @param restriction Indicates for which feature the dialog should be displayed. Can be a
     *            user restriction from {@link UserManager}, e.g.
     *            {@link UserManager#DISALLOW_ADJUST_VOLUME}, or one of the constants
     *            {@link #POLICY_DISABLE_CAMERA} or {@link #POLICY_DISABLE_SCREEN_CAPTURE}.
     * @return Intent An intent to be used to start the dialog-activity if the restriction is
     *            set by an admin, or null if the restriction does not exist or no admin set it.
     */
    public Intent createAdminSupportIntent(@NonNull String restriction) {
        throwIfParentInstance("createAdminSupportIntent");
        if (mService != null) {
            try {
                return mService.createAdminSupportIntent(restriction);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Hide or unhide packages. When a package is hidden it is unavailable for use, but the data and
     * actual package file remain. This function can be called by a device owner, profile owner, or
     * by a delegate given the {@link #DELEGATION_PACKAGE_ACCESS} scope via
     * {@link #setDelegatedScopes}.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance, returned by
     * {@link #getParentProfileInstance(ComponentName)}, where the caller must be the profile owner
     * of an organization-owned managed profile and the package must be a system package. If called
     * on the parent instance, then the package is hidden or unhidden in the personal profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is a package access delegate.
     * @param packageName The name of the package to hide or unhide.
     * @param hidden {@code true} if the package should be hidden, {@code false} if it should be
     *            unhidden.
     * @return boolean Whether the hidden setting of the package was successfully updated.
     * @throws SecurityException if {@code admin} is not a device or profile owner or if called on
     *            the parent profile and the {@code admin} is not a profile owner of an
     *            organization-owned managed profile.
     * @throws IllegalArgumentException if called on the parent profile and the package provided
     *            is not a system package.
     * @see #setDelegatedScopes
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    public boolean setApplicationHidden(@NonNull ComponentName admin, String packageName,
            boolean hidden) {
        if (mService != null) {
            try {
                return mService.setApplicationHidden(admin, mContext.getPackageName(), packageName,
                        hidden, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Determine if a package is hidden. This function can be called by a device owner, profile
     * owner, or by a delegate given the {@link #DELEGATION_PACKAGE_ACCESS} scope via
     * {@link #setDelegatedScopes}.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance, returned by
     * {@link #getParentProfileInstance(ComponentName)}, where the caller must be the profile owner
     * of an organization-owned managed profile and the package must be a system package. If called
     * on the parent instance, this will determine whether the package is hidden or unhidden in the
     * personal profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is a package access delegate.
     * @param packageName The name of the package to retrieve the hidden status of.
     * @return boolean {@code true} if the package is hidden, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device or profile owner or if called on
     *            the parent profile and the {@code admin} is not a profile owner of an
     *            organization-owned managed profile.
     * @throws IllegalArgumentException if called on the parent profile and the package provided
     *            is not a system package.
     * @see #setDelegatedScopes
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    public boolean isApplicationHidden(@NonNull ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                return mService.isApplicationHidden(admin, mContext.getPackageName(), packageName,
                        mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Re-enable a system app that was disabled by default when the user was initialized. This
     * function can be called by a device owner, profile owner, or by a delegate given the
     * {@link #DELEGATION_ENABLE_SYSTEM_APP} scope via {@link #setDelegatedScopes}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is an enable system app delegate.
     * @param packageName The package to be re-enabled in the calling profile.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    public void enableSystemApp(@NonNull ComponentName admin, String packageName) {
        throwIfParentInstance("enableSystemApp");
        if (mService != null) {
            try {
                mService.enableSystemApp(admin, mContext.getPackageName(), packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Re-enable system apps by intent that were disabled by default when the user was initialized.
     * This function can be called by a device owner, profile owner, or by a delegate given the
     * {@link #DELEGATION_ENABLE_SYSTEM_APP} scope via {@link #setDelegatedScopes}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is an enable system app delegate.
     * @param intent An intent matching the app(s) to be installed. All apps that resolve for this
     *            intent will be re-enabled in the calling profile.
     * @return int The number of activities that matched the intent and were installed.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    public int enableSystemApp(@NonNull ComponentName admin, Intent intent) {
        throwIfParentInstance("enableSystemApp");
        if (mService != null) {
            try {
                return mService.enableSystemAppWithIntent(admin, mContext.getPackageName(), intent);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Install an existing package that has been installed in another user, or has been kept after
     * removal via {@link #setKeepUninstalledPackages}.
     * This function can be called by a device owner, profile owner or a delegate given
     * the {@link #DELEGATION_INSTALL_EXISTING_PACKAGE} scope via {@link #setDelegatedScopes}.
     * When called in a secondary user or managed profile, the user/profile must be affiliated with
     * the device. See {@link #isAffiliatedUser}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package to be installed in the calling profile.
     * @return {@code true} if the app is installed; {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not the device owner, or the profile owner of
     * an affiliated user or profile.
     * @see #setKeepUninstalledPackages
     * @see #setDelegatedScopes
     * @see #isAffiliatedUser
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    public boolean installExistingPackage(@NonNull ComponentName admin, String packageName) {
        throwIfParentInstance("installExistingPackage");
        if (mService != null) {
            try {
                return mService.installExistingPackage(admin, mContext.getPackageName(),
                        packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
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
     * <p>
     * This method may be called on the {@code DevicePolicyManager} instance returned from
     * {@link #getParentProfileInstance(ComponentName)} by the profile owner on an
     * organization-owned device, to restrict accounts that may not be managed on the primary
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param accountType For which account management is disabled or enabled.
     * @param disabled The boolean indicating that account management will be disabled (true) or
     *            enabled (false).
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setAccountManagementDisabled(@NonNull ComponentName admin, String accountType,
            boolean disabled) {
        if (mService != null) {
            try {
                mService.setAccountManagementDisabled(admin, accountType, disabled,
                        mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Gets the array of accounts for which account management is disabled by the profile owner
     * or device owner.
     *
     * <p> Account management can be disabled/enabled by calling
     * {@link #setAccountManagementDisabled}.
     * <p>
     * This method may be called on the {@code DevicePolicyManager} instance returned from
     * {@link #getParentProfileInstance(ComponentName)}. Note that only a profile owner on
     * an organization-owned device can affect account types on the parent profile instance.
     *
     * @return a list of account types for which account management has been disabled.
     *
     * @see #setAccountManagementDisabled
     */
    public @Nullable String[] getAccountTypesWithManagementDisabled() {
        return getAccountTypesWithManagementDisabledAsUser(myUserId(), mParentInstance);
    }

    /**
     * @see #getAccountTypesWithManagementDisabled()
     * Note that calling this method on the parent profile instance will return the same
     * value as calling it on the main {@code DevicePolicyManager} instance.
     * @hide
     */
    public @Nullable String[] getAccountTypesWithManagementDisabledAsUser(int userId) {
        return getAccountTypesWithManagementDisabledAsUser(userId, false);
    }

    /**
     * @see #getAccountTypesWithManagementDisabled()
     * @hide
     */
    public @Nullable String[] getAccountTypesWithManagementDisabledAsUser(
            int userId, boolean parentInstance) {
        if (mService != null) {
            try {
                return mService.getAccountTypesWithManagementDisabledAsUser(userId, parentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        return null;
    }

    /**
     * Called by device owner or profile owner to set whether a secondary lockscreen needs to be
     * shown.
     *
     * <p>The secondary lockscreen will by displayed after the primary keyguard security screen
     * requirements are met. To provide the lockscreen content the DO/PO will need to provide a
     * service handling the {@link #ACTION_BIND_SECONDARY_LOCKSCREEN_SERVICE} intent action,
     * extending the {@link DevicePolicyKeyguardService} class.
     *
     * <p>Relevant interactions on the secondary lockscreen should be communicated back to the
     * keyguard via {@link IKeyguardCallback}, such as when the screen is ready to be dismissed.
     *
     * <p>This API, and associated APIs, can only be called by the default supervision app when it
     * is set as the device owner or profile owner.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param enabled Whether or not the lockscreen needs to be shown.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #isSecondaryLockscreenEnabled
     * @hide
     **/
    @SystemApi
    public void setSecondaryLockscreenEnabled(@NonNull ComponentName admin, boolean enabled) {
        throwIfParentInstance("setSecondaryLockscreenEnabled");
        if (mService != null) {
            try {
                mService.setSecondaryLockscreenEnabled(admin, enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns whether the secondary lock screen needs to be shown.
     * @see #setSecondaryLockscreenEnabled
     * @hide
     */
    @SystemApi
    public boolean isSecondaryLockscreenEnabled(@NonNull UserHandle userHandle) {
        throwIfParentInstance("isSecondaryLockscreenEnabled");
        if (mService != null) {
            try {
                return mService.isSecondaryLockscreenEnabled(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Sets which packages may enter lock task mode.
     * <p>
     * Any packages that share uid with an allowed package will also be allowed to activate lock
     * task. From {@link android.os.Build.VERSION_CODES#M} removing packages from the lock task
     * package list results in locked tasks belonging to those packages to be finished.
     * <p>
     * This function can only be called by the device owner, a profile owner of an affiliated user
     * or profile, or the profile owner when no device owner is set. See {@link #isAffiliatedUser}.
     * Any package set via this method will be cleared if the user becomes unaffiliated.
     *
     * @param packages The list of packages allowed to enter lock task mode
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not the device owner, the profile owner of an
     * affiliated user or profile, or the profile owner when no device owner is set.
     * @see #isAffiliatedUser
     * @see Activity#startLockTask()
     * @see DeviceAdminReceiver#onLockTaskModeEntering(Context, Intent, String)
     * @see DeviceAdminReceiver#onLockTaskModeExiting(Context, Intent)
     * @see UserManager#DISALLOW_CREATE_WINDOWS
     */
    public void setLockTaskPackages(@NonNull ComponentName admin, @NonNull String[] packages)
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
     * Returns the list of packages allowed to start the lock task mode.
     *
     * @throws SecurityException if {@code admin} is not the device owner, the profile owner of an
     * affiliated user or profile, or the profile owner when no device owner is set.
     * @see #isAffiliatedUser
     * @see #setLockTaskPackages
     */
    public @NonNull String[] getLockTaskPackages(@NonNull ComponentName admin) {
        throwIfParentInstance("getLockTaskPackages");
        if (mService != null) {
            try {
                return mService.getLockTaskPackages(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return new String[0];
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
     * Sets which system features are enabled when the device runs in lock task mode. This method
     * doesn't affect the features when lock task mode is inactive. Any system features not included
     * in {@code flags} are implicitly disabled when calling this method. By default, only
     * {@link #LOCK_TASK_FEATURE_GLOBAL_ACTIONS} is enabled; all the other features are disabled. To
     * disable the global actions dialog, call this method omitting
     * {@link #LOCK_TASK_FEATURE_GLOBAL_ACTIONS}.
     *
     * <p>This method can only be called by the device owner, a profile owner of an affiliated
     * user or profile, or the profile owner when no device owner is set. See
     * {@link #isAffiliatedUser}.
     * Any features set using this method are cleared if the user becomes unaffiliated.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param flags The system features enabled during lock task mode.
     * @throws SecurityException if {@code admin} is not the device owner, the profile owner of an
     * affiliated user or profile, or the profile owner when no device owner is set.
     * @see #isAffiliatedUser
     **/
    public void setLockTaskFeatures(@NonNull ComponentName admin, @LockTaskFeature int flags) {
        throwIfParentInstance("setLockTaskFeatures");
        if (mService != null) {
            try {
                mService.setLockTaskFeatures(admin, flags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Gets which system features are enabled for LockTask mode.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return bitfield of flags. See {@link #setLockTaskFeatures(ComponentName, int)} for a list.
     * @throws SecurityException if {@code admin} is not the device owner, the profile owner of an
     * affiliated user or profile, or the profile owner when no device owner is set.
     * @see #isAffiliatedUser
     * @see #setLockTaskFeatures
     */
    public @LockTaskFeature int getLockTaskFeatures(@NonNull ComponentName admin) {
        throwIfParentInstance("getLockTaskFeatures");
        if (mService != null) {
            try {
                return mService.getLockTaskFeatures(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * This method is mostly deprecated.
     * Most of the settings that still have an effect have dedicated setter methods or user
     * restrictions. See individual settings for details.
     * <p>
     * Called by device owner to update {@link android.provider.Settings.Global} settings.
     * Validation that the value of the setting is in the correct form for the setting type should
     * be performed by the caller.
     * <p>
     * The settings that can be updated with this method are:
     * <ul>
     * <li>{@link android.provider.Settings.Global#ADB_ENABLED} : use
     * {@link UserManager#DISALLOW_DEBUGGING_FEATURES} instead to restrict users from enabling
     * debugging features and this setting to turn adb on.</li>
     * <li>{@link android.provider.Settings.Global#USB_MASS_STORAGE_ENABLED}</li>
     * <li>{@link android.provider.Settings.Global#STAY_ON_WHILE_PLUGGED_IN} This setting is only
     * available from {@link android.os.Build.VERSION_CODES#M} onwards and can only be set if
     * {@link #setMaximumTimeToLock} is not used to set a timeout.</li>
     * <li>{@link android.provider.Settings.Global#WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN}</li> This
     * setting is only available from {@link android.os.Build.VERSION_CODES#M} onwards.</li>
     * </ul>
     * <p>
     * The following settings used to be supported, but can be controlled in other ways:
     * <ul>
     * <li>{@link android.provider.Settings.Global#AUTO_TIME} : Use {@link #setAutoTimeEnabled} and
     * {@link UserManager#DISALLOW_CONFIG_DATE_TIME} instead.</li>
     * <li>{@link android.provider.Settings.Global#AUTO_TIME_ZONE} : Use
     * {@link #setAutoTimeZoneEnabled} and {@link UserManager#DISALLOW_CONFIG_DATE_TIME}
     * instead.</li>
     * <li>{@link android.provider.Settings.Global#DATA_ROAMING} : Use
     * {@link UserManager#DISALLOW_DATA_ROAMING} instead.</li>
     * </ul>
     * <p>
     * Changing the following settings has no effect as of {@link android.os.Build.VERSION_CODES#M}:
     * <ul>
     * <li>{@link android.provider.Settings.Global#BLUETOOTH_ON}. Use
     * {@link android.bluetooth.BluetoothAdapter#enable()} and
     * {@link android.bluetooth.BluetoothAdapter#disable()} instead.</li>
     * <li>{@link android.provider.Settings.Global#DEVELOPMENT_SETTINGS_ENABLED}</li>
     * <li>{@link android.provider.Settings.Global#MODE_RINGER}. Use
     * {@link android.media.AudioManager#setRingerMode(int)} instead.</li>
     * <li>{@link android.provider.Settings.Global#NETWORK_PREFERENCE}</li>
     * <li>{@link android.provider.Settings.Global#WIFI_ON}. Use
     * {@link android.net.wifi.WifiManager#setWifiEnabled(boolean)} instead.</li>
     * <li>{@link android.provider.Settings.Global#WIFI_SLEEP_POLICY}. No longer has effect.</li>
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

    /** @hide */
    @StringDef({
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS,
            Settings.System.SCREEN_BRIGHTNESS_FLOAT,
            Settings.System.SCREEN_OFF_TIMEOUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SystemSettingsWhitelist {}

    /**
     * Called by a device or profile owner to update {@link android.provider.Settings.System}
     * settings. Validation that the value of the setting is in the correct form for the setting
     * type should be performed by the caller.
     * <p>
     * The settings that can be updated by a device owner or profile owner of secondary user with
     * this method are:
     * <ul>
     * <li>{@link android.provider.Settings.System#SCREEN_BRIGHTNESS}</li>
     * <li>{@link android.provider.Settings.System#SCREEN_BRIGHTNESS_MODE}</li>
     * <li>{@link android.provider.Settings.System#SCREEN_OFF_TIMEOUT}</li>
     * </ul>
     * <p>
     *
     * @see android.provider.Settings.System#SCREEN_OFF_TIMEOUT
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param setting The name of the setting to update.
     * @param value The value to update the setting to.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setSystemSetting(@NonNull ComponentName admin,
            @NonNull @SystemSettingsWhitelist String setting, String value) {
        throwIfParentInstance("setSystemSetting");
        if (mService != null) {
            try {
                mService.setSystemSetting(admin, setting, value);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device owner or a profile owner of an organization-owned managed profile to
     * control whether the user can change networks configured by the admin.
     * <p>
     * WiFi network configuration lockdown is controlled by a global settings
     * {@link android.provider.Settings.Global#WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN} and calling
     * this API effectively modifies the global settings. Previously device owners can also
     * control this directly via {@link #setGlobalSetting} but they are recommended to switch
     * to this API.
     *
     * @param admin             admin Which {@link DeviceAdminReceiver} this request is associated
     *                          with.
     * @param lockdown Whether the admin configured networks should be unmodifiable by the
     *                          user.
     * @throws SecurityException if caller is not a device owner or a profile owner of an
     *                           organization-owned managed profile.
     */
    public void setConfiguredNetworksLockdownState(@NonNull ComponentName admin, boolean lockdown) {
        throwIfParentInstance("setConfiguredNetworksLockdownState");
        if (mService != null) {
            try {
                mService.setConfiguredNetworksLockdownState(admin, lockdown);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device owner or a profile owner of an organization-owned managed profile to
     * determine whether the user is prevented from modifying networks configured by the admin.
     *
     * @param admin             admin Which {@link DeviceAdminReceiver} this request is associated
     *                          with.
     * @throws SecurityException if caller is not a device owner or a profile owner of an
     *                           organization-owned managed profile.
     */
    public boolean hasLockdownAdminConfiguredNetworks(@NonNull ComponentName admin) {
        throwIfParentInstance("hasLockdownAdminConfiguredNetworks");
        if (mService != null) {
            try {
                return mService.hasLockdownAdminConfiguredNetworks(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner or a profile owner of an organization-owned managed
     * profile to set the system wall clock time. This only takes effect if called when
     * {@link android.provider.Settings.Global#AUTO_TIME} is 0, otherwise {@code false}
     * will be returned.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with
     * @param millis time in milliseconds since the Epoch
     * @return {@code true} if set time succeeded, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner or a profile owner
     * of an organization-owned managed profile.
     */
    public boolean setTime(@NonNull ComponentName admin, long millis) {
        throwIfParentInstance("setTime");
        if (mService != null) {
            try {
                return mService.setTime(admin, millis);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner or a profile owner of an organization-owned managed
     * profile to set the system's persistent default time zone. This only takes
     * effect if called when {@link android.provider.Settings.Global#AUTO_TIME_ZONE}
     * is 0, otherwise {@code false} will be returned.
     *
     * @see android.app.AlarmManager#setTimeZone(String)
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with
     * @param timeZone one of the Olson ids from the list returned by
     *     {@link java.util.TimeZone#getAvailableIDs}
     * @return {@code true} if set timezone succeeded, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner or a profile owner
     * of an organization-owned managed profile.
     */
    public boolean setTimeZone(@NonNull ComponentName admin, String timeZone) {
        throwIfParentInstance("setTimeZone");
        if (mService != null) {
            try {
                return mService.setTimeZone(admin, timeZone);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device owners to set the user's master location setting.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with
     * @param locationEnabled whether location should be enabled or disabled
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setLocationEnabled(@NonNull ComponentName admin, boolean locationEnabled) {
        throwIfParentInstance("setLocationEnabled");
        if (mService != null) {
            try {
                mService.setLocationEnabled(admin, locationEnabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * This method is mostly deprecated.
     * Most of the settings that still have an effect have dedicated setter methods
     * (e.g. {@link #setLocationEnabled}) or user restrictions.
     * <p>
     *
     * Called by profile or device owners to update {@link android.provider.Settings.Secure}
     * settings. Validation that the value of the setting is in the correct form for the setting
     * type should be performed by the caller.
     * <p>
     * The settings that can be updated by a profile or device owner with this method are:
     * <ul>
     * <li>{@link android.provider.Settings.Secure#DEFAULT_INPUT_METHOD}</li>
     * <li>{@link android.provider.Settings.Secure#SKIP_FIRST_USE_HINTS}</li>
     * </ul>
     * <p>
     * A device owner can additionally update the following settings:
     * <ul>
     * <li>{@link android.provider.Settings.Secure#LOCATION_MODE}, but see note below.</li>
     * </ul>
     *
     * <strong>Note: Starting from Android O, apps should no longer call this method with the
     * setting {@link android.provider.Settings.Secure#INSTALL_NON_MARKET_APPS}, which is
     * deprecated. Instead, device owners or profile owners should use the restriction
     * {@link UserManager#DISALLOW_INSTALL_UNKNOWN_SOURCES}.
     * If any app targeting {@link android.os.Build.VERSION_CODES#O} or higher calls this method
     * with {@link android.provider.Settings.Secure#INSTALL_NON_MARKET_APPS},
     * an {@link UnsupportedOperationException} is thrown.
     *
     * Starting from Android Q, the device and profile owner can also call
     * {@link UserManager#DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY} to restrict unknown sources for
     * all users.
     * </strong>
     *
     * <strong>Note: Starting from Android R, apps should no longer call this method with the
     * setting {@link android.provider.Settings.Secure#LOCATION_MODE}, which is deprecated. Instead,
     * device owners should call {@link #setLocationEnabled(ComponentName, boolean)}. This will be
     * enforced for all apps targeting Android R or above.
     * </strong>
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
     * This has no effect when set on a managed profile.
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
     * Change whether a user can uninstall a package. This function can be called by a device owner,
     * profile owner, or by a delegate given the {@link #DELEGATION_BLOCK_UNINSTALL} scope via
     * {@link #setDelegatedScopes}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *             {@code null} if the caller is a block uninstall delegate.
     * @param packageName package to change.
     * @param uninstallBlocked true if the user shouldn't be able to uninstall the package.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_BLOCK_UNINSTALL
     */
    public void setUninstallBlocked(@Nullable ComponentName admin, String packageName,
            boolean uninstallBlocked) {
        throwIfParentInstance("setUninstallBlocked");
        if (mService != null) {
            try {
                mService.setUninstallBlocked(admin, mContext.getPackageName(), packageName,
                    uninstallBlocked);
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
    public @NonNull List<String> getCrossProfileWidgetProviders(@NonNull ComponentName admin) {
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
     * Called by device owners or profile owners of an organization-owned managed profile to to set
     * a local system update policy. When a new policy is set,
     * {@link #ACTION_SYSTEM_UPDATE_POLICY_CHANGED} is broadcasted.
     * <p>
     * If the supplied system update policy has freeze periods set but the freeze periods do not
     * meet 90-day maximum length or 60-day minimum separation requirement set out in
     * {@link SystemUpdatePolicy#setFreezePeriods},
     * {@link SystemUpdatePolicy.ValidationFailedException} will the thrown. Note that the system
     * keeps a record of freeze periods the device experienced previously, and combines them with
     * the new freeze periods to be set when checking the maximum freeze length and minimum freeze
     * separation constraints. As a result, freeze periods that passed validation during
     * {@link SystemUpdatePolicy#setFreezePeriods} might fail the additional checks here due to
     * the freeze period history. If this is causing issues during development,
     * {@code adb shell dpm clear-freeze-period-record} can be used to clear the record.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. All
     *            components in the device owner package can set system update policies and the most
     *            recent policy takes effect.
     * @param policy the new policy, or {@code null} to clear the current policy.
     * @throws SecurityException if {@code admin} is not a device owner or a profile owner of an
     *      organization-owned managed profile.
     * @throws IllegalArgumentException if the policy type or maintenance window is not valid.
     * @throws SystemUpdatePolicy.ValidationFailedException if the policy's freeze period does not
     *             meet the requirement.
     * @see SystemUpdatePolicy
     * @see SystemUpdatePolicy#setFreezePeriods(List)
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
    public @Nullable SystemUpdatePolicy getSystemUpdatePolicy() {
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
     * Reset record of previous system update freeze period the device went through.
     * Only callable by ADB.
     * @hide
     */
    public void clearSystemUpdatePolicyFreezePeriodRecord() {
        throwIfParentInstance("clearSystemUpdatePolicyFreezePeriodRecord");
        if (mService == null) {
            return;
        }
        try {
            mService.clearSystemUpdatePolicyFreezePeriodRecord();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner or profile owner of secondary users that is affiliated with the
     * device to disable the keyguard altogether.
     * <p>
     * Setting the keyguard to disabled has the same effect as choosing "None" as the screen lock
     * type. However, this call has no effect if a password, pin or pattern is currently set. If a
     * password, pin or pattern is set after the keyguard was disabled, the keyguard stops being
     * disabled.
     *
     * <p>
     * As of {@link android.os.Build.VERSION_CODES#P}, this call also dismisses the
     * keyguard if it is currently shown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled {@code true} disables the keyguard, {@code false} reenables it.
     * @return {@code false} if attempting to disable the keyguard while a lock password was in
     *         place. {@code true} otherwise.
     * @throws SecurityException if {@code admin} is not the device owner, or a profile owner of
     * secondary user that is affiliated with the device.
     * @see #isAffiliatedUser
     * @see #getSecondaryUsers
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
     * Called by device owner or profile owner of secondary users that is affiliated with the
     * device to disable the status bar. Disabling the status bar blocks notifications and quick
     * settings.
     * <p>
     * <strong>Note:</strong> This method has no effect for LockTask mode. The behavior of the
     * status bar in LockTask mode can be configured with
     * {@link #setLockTaskFeatures(ComponentName, int)}. Calls to this method when the device is in
     * LockTask mode will be registered, but will only take effect when the device leaves LockTask
     * mode.
     *
     * <p>This policy does not have any effect while on the lock screen, where the status bar will
     * not be disabled. Using LockTask instead of this method is recommended.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled {@code true} disables the status bar, {@code false} reenables it.
     * @return {@code false} if attempting to disable the status bar failed. {@code true} otherwise.
     * @throws SecurityException if {@code admin} is not the device owner, or a profile owner of
     * secondary user that is affiliated with the device.
     * @see #isAffiliatedUser
     * @see #getSecondaryUsers
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
     * Called by the system update service to notify device and profile owners of pending system
     * updates.
     *
     * This method should only be used when it is unknown whether the pending system
     * update is a security patch. Otherwise, use
     * {@link #notifyPendingSystemUpdate(long, boolean)}.
     *
     * @param updateReceivedTime The time as given by {@link System#currentTimeMillis()}
     *         indicating when the current pending update was first available. {@code -1} if no
     *         update is available.
     * @see #notifyPendingSystemUpdate(long, boolean)
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NOTIFY_PENDING_SYSTEM_UPDATE)
    public void notifyPendingSystemUpdate(long updateReceivedTime) {
        throwIfParentInstance("notifyPendingSystemUpdate");
        if (mService != null) {
            try {
                mService.notifyPendingSystemUpdate(SystemUpdateInfo.of(updateReceivedTime));
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by the system update service to notify device and profile owners of pending system
     * updates.
     *
     * This method should be used instead of {@link #notifyPendingSystemUpdate(long)}
     * when it is known whether the pending system update is a security patch.
     *
     * @param updateReceivedTime The time as given by {@link System#currentTimeMillis()}
     *         indicating when the current pending update was first available. {@code -1} if no
     *         update is available.
     * @param isSecurityPatch {@code true} if this system update is purely a security patch;
     *         {@code false} if not.
     * @see #notifyPendingSystemUpdate(long)
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NOTIFY_PENDING_SYSTEM_UPDATE)
    public void notifyPendingSystemUpdate(long updateReceivedTime, boolean isSecurityPatch) {
        throwIfParentInstance("notifyPendingSystemUpdate");
        if (mService != null) {
            try {
                mService.notifyPendingSystemUpdate(SystemUpdateInfo.of(updateReceivedTime,
                        isSecurityPatch));
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by device or profile owners to get information about a pending system update.
     *
     * @param admin Which profile or device owner this request is associated with.
     * @return Information about a pending system update or {@code null} if no update pending.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see DeviceAdminReceiver#onSystemUpdatePending(Context, Intent, long)
     */
    public @Nullable SystemUpdateInfo getPendingSystemUpdate(@NonNull ComponentName admin) {
        throwIfParentInstance("getPendingSystemUpdate");
        try {
            return mService.getPendingSystemUpdate(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Set the default response for future runtime permission requests by applications. This
     * function can be called by a device owner, profile owner, or by a delegate given the
     * {@link #DELEGATION_PERMISSION_GRANT} scope via {@link #setDelegatedScopes}.
     * The policy can allow for normal operation which prompts the user to grant a permission, or
     * can allow automatic granting or denying of runtime permission requests by an application.
     * This also applies to new permissions declared by app updates. When a permission is denied or
     * granted this way, the effect is equivalent to setting the permission * grant state via
     * {@link #setPermissionGrantState}.
     * <p/>
     * As this policy only acts on runtime permission requests, it only applies to applications
     * built with a {@code targetSdkVersion} of {@link android.os.Build.VERSION_CODES#M} or later.
     *
     * @param admin Which profile or device owner this request is associated with.
     * @param policy One of the policy constants {@link #PERMISSION_POLICY_PROMPT},
     *            {@link #PERMISSION_POLICY_AUTO_GRANT} and {@link #PERMISSION_POLICY_AUTO_DENY}.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setPermissionGrantState
     * @see #setDelegatedScopes
     * @see #DELEGATION_PERMISSION_GRANT
     */
    public void setPermissionPolicy(@NonNull ComponentName admin, int policy) {
        throwIfParentInstance("setPermissionPolicy");
        try {
            mService.setPermissionPolicy(admin, mContext.getPackageName(), policy);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current runtime permission policy set by the device or profile owner. The
     * default is {@link #PERMISSION_POLICY_PROMPT}.
     *
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
     * the permission is granted and the user cannot manage it through the UI. This method can only
     * be called by a profile owner, device owner, or a delegate given the
     * {@link #DELEGATION_PERMISSION_GRANT} scope via {@link #setDelegatedScopes}.
     * <p/>
     * Note that user cannot manage other permissions in the affected group through the UI
     * either and their granted state will be kept as the current value. Thus, it's recommended that
     * you set the grant state of all the permissions in the affected group.
     * <p/>
     * Setting the grant state to {@link #PERMISSION_GRANT_STATE_DEFAULT default} does not revoke
     * the permission. It retains the previous grant, if any.
     * <p/>
     * Device admins with a {@code targetSdkVersion} &lt; {@link android.os.Build.VERSION_CODES#Q}
     * cannot grant and revoke permissions for applications built with a {@code targetSdkVersion}
     * &lt; {@link android.os.Build.VERSION_CODES#M}.
     * <p/>
     * Admins with a {@code targetSdkVersion} &ge; {@link android.os.Build.VERSION_CODES#Q} can
     * grant and revoke permissions of all apps. Similar to the user revoking a permission from a
     * application built with a {@code targetSdkVersion} &lt;
     * {@link android.os.Build.VERSION_CODES#M} the app-op matching the permission is set to
     * {@link android.app.AppOpsManager#MODE_IGNORED}, but the permission stays granted.
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
     * @see #setDelegatedScopes
     * @see #DELEGATION_PERMISSION_GRANT
     */
    public boolean setPermissionGrantState(@NonNull ComponentName admin,
            @NonNull String packageName, @NonNull String permission,
            @PermissionGrantState int grantState) {
        throwIfParentInstance("setPermissionGrantState");
        try {
            CompletableFuture<Boolean> result = new CompletableFuture<>();

            mService.setPermissionGrantState(admin, mContext.getPackageName(), packageName,
                    permission, grantState, new RemoteCallback((b) -> result.complete(b != null)));

            // Timeout
            BackgroundThread.getHandler().sendMessageDelayed(
                    obtainMessage(CompletableFuture::complete, result, false),
                    20_000);

            return result.get();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the current grant state of a runtime permission for a specific application. This
     * function can be called by a device owner, profile owner, or by a delegate given the
     * {@link #DELEGATION_PERMISSION_GRANT} scope via {@link #setDelegatedScopes}.
     *
     * @param admin Which profile or device owner this request is associated with, or {@code null}
     *            if the caller is a permission grant delegate.
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
     * @see #setDelegatedScopes
     * @see #DELEGATION_PERMISSION_GRANT
     */
    public @PermissionGrantState int getPermissionGrantState(@Nullable ComponentName admin,
            @NonNull String packageName, @NonNull String permission) {
        throwIfParentInstance("getPermissionGrantState");
        try {
            return mService.getPermissionGrantState(admin, mContext.getPackageName(), packageName,
                    permission);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether it is possible for the caller to initiate provisioning of a managed profile
     * or device, setting itself as the device or profile owner.
     *
     * @param action One of {@link #ACTION_PROVISION_MANAGED_DEVICE},
     * {@link #ACTION_PROVISION_MANAGED_PROFILE}.
     * @return whether provisioning a managed profile or device is possible.
     * @throws IllegalArgumentException if the supplied action is not valid.
     */
    public boolean isProvisioningAllowed(@NonNull String action) {
        throwIfParentInstance("isProvisioningAllowed");
        try {
            return mService.isProvisioningAllowed(action, mContext.getPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether it is possible to initiate provisioning a managed device,
     * profile or user, setting the given package as owner.
     *
     * @param action One of {@link #ACTION_PROVISION_MANAGED_DEVICE},
     *        {@link #ACTION_PROVISION_MANAGED_PROFILE},
     *        {@link #ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE},
     *        {@link #ACTION_PROVISION_MANAGED_USER}
     * @param packageName The package of the component that would be set as device, user, or profile
     *        owner.
     * @return A {@link ProvisioningPreCondition} value indicating whether provisioning is allowed.
     * @hide
     */
    public @ProvisioningPreCondition int checkProvisioningPreCondition(
            String action, @NonNull String packageName) {
        try {
            return mService.checkProvisioningPreCondition(action, packageName);
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
     * Called by device owner, or profile owner on organization-owned device, to get the MAC
     * address of the Wi-Fi device.
     *
     * NOTE: The MAC address returned here should only be used for inventory management and is
     * not likely to be the MAC address used by the device to connect to Wi-Fi networks: MAC
     * addresses used for scanning and connecting to Wi-Fi networks are randomized by default.
     * To get the randomized MAC address used, call
     * {@link android.net.wifi.WifiConfiguration#getRandomizedMacAddress}.
     *
     * @param admin Which device owner this request is associated with.
     * @return the MAC address of the Wi-Fi device, or null when the information is not available.
     *         (For example, Wi-Fi hasn't been enabled, or the device doesn't support Wi-Fi.)
     *         <p>
     *         The address will be in the {@code XX:XX:XX:XX:XX:XX} format.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public @Nullable String getWifiMacAddress(@NonNull ComponentName admin) {
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
    public @Nullable CharSequence getLongSupportMessage(@NonNull ComponentName admin) {
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
    public @Nullable CharSequence getShortSupportMessageForUser(@NonNull ComponentName admin,
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
    public @Nullable CharSequence getLongSupportMessageForUser(
            @NonNull ComponentName admin, int userHandle) {
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
     * <li>{@link #getPasswordMaximumLength}</li>
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
     * <li>{@link #getRequiredStrongAuthTimeout}</li>
     * <li>{@link #setRequiredStrongAuthTimeout}</li>
     * <li>{@link #getAccountTypesWithManagementDisabled}</li>
     * </ul>
     * <p>
     * The following methods are supported for the parent instance but can only be called by the
     * profile owner of a managed profile that was created during the device provisioning flow:
     * <ul>
     * <li>{@link #getPasswordComplexity}</li>
     * <li>{@link #setCameraDisabled}</li>
     * <li>{@link #getCameraDisabled}</li>
     * <li>{@link #setAccountManagementDisabled(ComponentName, String, boolean)}</li>
     * </ul>
     *
     * <p>The following methods can be called by the profile owner of a managed profile
     * on an organization-owned device:
     * <ul>
     * <li>{@link #wipeData}</li>
     * </ul>
     *
     * @return a new instance of {@link DevicePolicyManager} that acts on the parent profile.
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    public @NonNull DevicePolicyManager getParentProfileInstance(@NonNull ComponentName admin) {
        throwIfParentInstance("getParentProfileInstance");
        try {
            if (!mService.isManagedProfile(admin)) {
                throw new SecurityException("The current user does not have a parent profile.");
            }
            return new DevicePolicyManager(mContext, mService, true);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner or a profile owner of an organization-owned managed profile to
     * control the security logging feature.
     *
     * <p> Security logs contain various information intended for security auditing purposes.
     * When security logging is enabled by a profile owner of
     * an organization-owned managed profile, certain security logs are not visible (for example
     * personal app launch events) or they will be redacted (for example, details of the physical
     * volume mount events). Please see {@link SecurityEvent} for details.
     *
     * <p><strong>Note:</strong> The device owner won't be able to retrieve security logs if there
     * are unaffiliated secondary users or profiles on the device, regardless of whether the
     * feature is enabled. Logs will be discarded if the internal buffer fills up while waiting for
     * all users to become affiliated. Therefore it's recommended that affiliation ids are set for
     * new users as soon as possible after provisioning via {@link #setAffiliationIds}. Profile
     * owner of organization-owned managed profile is not subject to this restriction since all
     * privacy-sensitive events happening outside the managed profile would have been redacted
     * already.
     *
     * @param admin Which device admin this request is associated with.
     * @param enabled whether security logging should be enabled or not.
     * @throws SecurityException if {@code admin} is not allowed to control security logging.
     * @see #setAffiliationIds
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
     * Return whether security logging is enabled or not by the admin.
     *
     * <p>Can only be called by the device owner or a profile owner of an organization-owned
     * managed profile, otherwise a {@link SecurityException} will be thrown.
     *
     * @param admin Which device admin this request is associated with.
     * @return {@code true} if security logging is enabled by device owner, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not allowed to control security logging.
     */
    public boolean isSecurityLoggingEnabled(@Nullable ComponentName admin) {
        throwIfParentInstance("isSecurityLoggingEnabled");
        try {
            return mService.isSecurityLoggingEnabled(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner or profile owner of an organization-owned managed profile to retrieve
     * all new security logging entries since the last call to this API after device boots.
     *
     * <p> Access to the logs is rate limited and it will only return new logs after the device
     * owner has been notified via {@link DeviceAdminReceiver#onSecurityLogsAvailable}.
     *
     * <p> When called by a device owner, if there is any other user or profile on the device,
     * it must be affiliated with the device. Otherwise a {@link SecurityException} will be thrown.
     * See {@link #isAffiliatedUser}.
     *
     * @param admin Which device admin this request is associated with.
     * @return the new batch of security logs which is a list of {@link SecurityEvent},
     * or {@code null} if rate limitation is exceeded or if logging is currently disabled.
     * @throws SecurityException if {@code admin} is not allowed to access security logging,
     * or there is at least one profile or secondary user that is not affiliated with the device.
     * @see #isAffiliatedUser
     * @see DeviceAdminReceiver#onSecurityLogsAvailable
     */
    public @Nullable List<SecurityEvent> retrieveSecurityLogs(@NonNull ComponentName admin) {
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
     * Makes all accumulated network logs available to DPC in a new batch.
     * Only callable by ADB. If throttled, returns time to wait in milliseconds, otherwise 0.
     * @hide
     */
    public long forceNetworkLogs() {
        if (mService == null) {
            return -1;
        }
        try {
            return mService.forceNetworkLogs();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Forces a batch of security logs to be fetched from logd and makes it available for DPC.
     * Only callable by ADB. If throttled, returns time to wait in milliseconds, otherwise 0.
     * @hide
     */
    public long forceSecurityLogs() {
        if (mService == null) {
            return 0;
        }
        try {
            return mService.forceSecurityLogs();
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
    public @NonNull DevicePolicyManager getParentProfileInstance(UserInfo uInfo) {
        mContext.checkSelfPermission(
                android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        if (!uInfo.isManagedProfile()) {
            throw new SecurityException("The user " + uInfo.id
                    + " does not have a parent profile.");
        }
        return new DevicePolicyManager(mContext, mService, true);
    }

    /**
     * Called by a device or profile owner to restrict packages from using metered data.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageNames the list of package names to be restricted.
     * @return a list of package names which could not be restricted.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public @NonNull List<String> setMeteredDataDisabledPackages(@NonNull ComponentName admin,
            @NonNull List<String> packageNames) {
        throwIfParentInstance("setMeteredDataDisabled");
        if (mService != null) {
            try {
                return mService.setMeteredDataDisabledPackages(admin, packageNames);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return packageNames;
    }

    /**
     * Called by a device or profile owner to retrieve the list of packages which are restricted
     * by the admin from using metered data.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @return the list of restricted package names.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public @NonNull List<String> getMeteredDataDisabledPackages(@NonNull ComponentName admin) {
        throwIfParentInstance("getMeteredDataDisabled");
        if (mService != null) {
            try {
                return mService.getMeteredDataDisabledPackages(admin);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return new ArrayList<>();
    }

    /**
     * Called by the system to check if a package is restricted from using metered data
     * by {@param admin}.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName the package whose restricted status is needed.
     * @param userId the user to which {@param packageName} belongs.
     * @return {@code true} if the package is restricted by admin, otherwise {@code false}
     * @throws SecurityException if the caller doesn't run with {@link Process#SYSTEM_UID}
     * @hide
     */
    public boolean isMeteredDataDisabledPackageForUser(@NonNull ComponentName admin,
            String packageName, @UserIdInt int userId) {
        throwIfParentInstance("getMeteredDataDisabledForUser");
        if (mService != null) {
            try {
                return mService.isMeteredDataDisabledPackageForUser(admin, packageName, userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device owner or profile owner of an organization-owned managed profile to retrieve
     * device logs from before the device's last reboot.
     * <p>
     * <strong> This API is not supported on all devices. Calling this API on unsupported devices
     * will result in {@code null} being returned. The device logs are retrieved from a RAM region
     * which is not guaranteed to be corruption-free during power cycles, as a result be cautious
     * about data corruption when parsing. </strong>
     *
     * <p> When called by a device owner, if there is any other user or profile on the device,
     * it must be affiliated with the device. Otherwise a {@link SecurityException} will be thrown.
     * See {@link #isAffiliatedUser}.
     *
     * @param admin Which device admin this request is associated with.
     * @return Device logs from before the latest reboot of the system, or {@code null} if this API
     *         is not supported on the device.
     * @throws SecurityException if {@code admin} is not allowed to access security logging, or
     * there is at least one profile or secondary user that is not affiliated with the device.
     * @see #isAffiliatedUser
     * @see #retrieveSecurityLogs
     */
    public @Nullable List<SecurityEvent> retrievePreRebootSecurityLogs(
            @NonNull ComponentName admin) {
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
     * <p>
     * Starting from Android R, the organization color will no longer be used as the background
     * color of the confirm credentials screen.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param color The 24bit (0xRRGGBB) representation of the color to be used.
     * @throws SecurityException if {@code admin} is not a profile owner.
     * @deprecated From {@link android.os.Build.VERSION_CODES#R}, the organization color is never
     * used as the background color of the confirm credentials screen.
     */
    @Deprecated
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
     * @deprecated From {@link android.os.Build.VERSION_CODES#R}, the organization color is never
     * used as the background color of the confirm credentials screen.
     */
    @Deprecated
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
     * @deprecated From {@link android.os.Build.VERSION_CODES#R}, the organization color is never
     * used as the background color of the confirm credentials screen.
     */
    @Deprecated
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
     * @deprecated From {@link android.os.Build.VERSION_CODES#R}, the organization color is never
     * used as the background color of the confirm credentials screen.
     */
    @Deprecated
    public @ColorInt int getOrganizationColorForUser(int userHandle) {
        try {
            return mService.getOrganizationColorForUser(userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the device owner (since API 26) or profile owner (since API 24) to set the name of
     * the organization under management.
     *
     * <p>If the organization name needs to be localized, it is the responsibility of the {@link
     * DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast and set
     * a new version of this string accordingly.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param title The organization name or {@code null} to clear a previously set name.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
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
    public @Nullable CharSequence getOrganizationName(@NonNull ComponentName admin) {
        throwIfParentInstance("getOrganizationName");
        try {
            return mService.getOrganizationName(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system to retrieve the name of the organization managing the device.
     *
     * @return The organization name or {@code null} if none is set.
     * @throws SecurityException if the caller is not the device owner, does not hold the
     *         MANAGE_USERS permission and is not the system.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @SuppressLint("Doclava125")
    public @Nullable CharSequence getDeviceOwnerOrganizationName() {
        try {
            return mService.getDeviceOwnerOrganizationName();
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
    public @Nullable CharSequence getOrganizationNameForUser(int userHandle) {
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
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
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
     * Indicates the entity that controls the device. Two users are
     * affiliated if the set of ids set by the device owner and the admin of the secondary user.
     *
     * <p>A user that is affiliated with the device owner user is considered to be
     * affiliated with the device.
     *
     * <p><strong>Note:</strong> Features that depend on user affiliation (such as security logging
     * or {@link #bindDeviceAdminServiceAsUser}) won't be available when a secondary user
     * is created, until it becomes affiliated. Therefore it is recommended that the appropriate
     * affiliation ids are set by its owner as soon as possible after the user is
     * created.
     * <p>
     * Note: This method used to be available for affiliating device owner and profile
     * owner. However, since Android 11, this combination is not possible. This method is now
     * only useful for affiliating the primary user with managed secondary users.
     *
     * @param admin Which device owner, or owner of secondary user, this request is associated with.
     * @param ids A set of opaque non-empty affiliation ids.
     *
     * @throws IllegalArgumentException if {@code ids} is null or contains an empty string.
     * @see #isAffiliatedUser
     */
    public void setAffiliationIds(@NonNull ComponentName admin, @NonNull Set<String> ids) {
        throwIfParentInstance("setAffiliationIds");
        if (ids == null) {
            throw new IllegalArgumentException("ids must not be null");
        }
        try {
            mService.setAffiliationIds(admin, new ArrayList<>(ids));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the set of affiliation ids previously set via {@link #setAffiliationIds}, or an
     * empty set if none have been set.
     */
    public @NonNull Set<String> getAffiliationIds(@NonNull ComponentName admin) {
        throwIfParentInstance("getAffiliationIds");
        try {
            return new ArraySet<>(mService.getAffiliationIds(admin));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether this user is affiliated with the device.
     * <p>
     * By definition, the user that the device owner runs on is always affiliated with the device.
     * Any other user is considered affiliated with the device if the set specified by its
     * profile owner via {@link #setAffiliationIds} intersects with the device owner's.
     * @see #setAffiliationIds
     */
    public boolean isAffiliatedUser() {
        throwIfParentInstance("isAffiliatedUser");
        try {
            return mService.isAffiliatedUser();
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
     * Returns whether the device has been provisioned.
     *
     * <p>Not for use by third-party applications.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean isDeviceProvisioned() {
        try {
            return mService.isDeviceProvisioned();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
      * Writes that the provisioning configuration has been applied.
      *
      * <p>The caller must hold the {@link android.Manifest.permission#MANAGE_USERS}
      * permission.
      *
      * <p>Not for use by third-party applications.
      *
      * @hide
      */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public void setDeviceProvisioningConfigApplied() {
        try {
            mService.setDeviceProvisioningConfigApplied();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the provisioning configuration has been applied.
     *
     * <p>The caller must hold the {@link android.Manifest.permission#MANAGE_USERS} permission.
     *
     * <p>Not for use by third-party applications.
     *
     * @return whether the provisioning configuration has been applied.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean isDeviceProvisioningConfigApplied() {
        try {
            return mService.isDeviceProvisioningConfigApplied();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Force update user setup completed status. This API has no effect on user build.
     * @throws {@link SecurityException} if the caller has no
     *         {@code android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS} or the caller is
     *         not {@link UserHandle#SYSTEM_USER}
     */
    public void forceUpdateUserSetupComplete() {
        try {
            mService.forceUpdateUserSetupComplete();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    @UnsupportedAppUsage
    private void throwIfParentInstance(String functionName) {
        if (mParentInstance) {
            throw new SecurityException(functionName + " cannot be called on the parent instance");
        }
    }

    /**
     * Allows the device owner or profile owner to enable or disable the backup service.
     *
     * <p> Each user has its own backup service which manages the backup and restore mechanisms in
     * that user. Disabling the backup service will prevent data from being backed up or restored.
     *
     * <p> Device owner calls this API to control backup services across all users on the device.
     * Profile owner can use this API to enable or disable the profile's backup service. However,
     * for a managed profile its backup functionality is only enabled if both the device owner
     * and the profile owner have enabled the backup service.
     *
     * <p> By default, backup service is disabled on a device with device owner, and within a
     * managed profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param enabled {@code true} to enable the backup service, {@code false} to disable it.
     * @throws SecurityException if {@code admin} is not a device owner or a profile owner.
     */
    public void setBackupServiceEnabled(@NonNull ComponentName admin, boolean enabled) {
        throwIfParentInstance("setBackupServiceEnabled");
        try {
            mService.setBackupServiceEnabled(admin, enabled);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether the backup service is enabled by the device owner or profile owner for the
     * current user, as previously set by {@link #setBackupServiceEnabled(ComponentName, boolean)}.
     *
     * <p> Whether the backup functionality is actually enabled or not depends on settings from both
     * the current user and the device owner, please see
     * {@link #setBackupServiceEnabled(ComponentName, boolean)} for details.
     *
     * <p> Backup service manages all backup and restore mechanisms on the device.
     *
     * @return {@code true} if backup service is enabled, {@code false} otherwise.
     * @see #setBackupServiceEnabled
     */
    public boolean isBackupServiceEnabled(@NonNull ComponentName admin) {
        throwIfParentInstance("isBackupServiceEnabled");
        try {
            return mService.isBackupServiceEnabled(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner or delegated app with {@link #DELEGATION_NETWORK_LOGGING} to
     * control the network logging feature.
     *
     * <p> Network logs contain DNS lookup and connect() library call events. The following library
     *     functions are recorded while network logging is active:
     *     <ul>
     *       <li>{@code getaddrinfo()}</li>
     *       <li>{@code gethostbyname()}</li>
     *       <li>{@code connect()}</li>
     *     </ul>
     *
     * <p> Network logging is a low-overhead tool for forensics but it is not guaranteed to use
     *     full system call logging; event reporting is enabled by default for all processes but not
     *     strongly enforced.
     *     Events from applications using alternative implementations of libc, making direct kernel
     *     calls, or deliberately obfuscating traffic may not be recorded.
     *
     * <p> Some common network events may not be reported. For example:
     *     <ul>
     *       <li>Applications may hardcode IP addresses to reduce the number of DNS lookups, or use
     *           an alternative system for name resolution, and so avoid calling
     *           {@code getaddrinfo()} or {@code gethostbyname}.</li>
     *       <li>Applications may use datagram sockets for performance reasons, for example
     *           for a game client. Calling {@code connect()} is unnecessary for this kind of
     *           socket, so it will not trigger a network event.</li>
     *     </ul>
     *
     * <p> It is possible to directly intercept layer 3 traffic leaving the device using an
     *     always-on VPN service.
     *     See {@link #setAlwaysOnVpnPackage(ComponentName, String, boolean)}
     *     and {@link android.net.VpnService} for details.
     *
     * <p><strong>Note:</strong> The device owner won't be able to retrieve network logs if there
     * are unaffiliated secondary users or profiles on the device, regardless of whether the
     * feature is enabled. Logs will be discarded if the internal buffer fills up while waiting for
     * all users to become affiliated. Therefore it's recommended that affiliation ids are set for
     * new users as soon as possible after provisioning via {@link #setAffiliationIds}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if called by a delegated app.
     * @param enabled whether network logging should be enabled or not.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see #setAffiliationIds
     * @see #retrieveNetworkLogs
     */
    public void setNetworkLoggingEnabled(@Nullable ComponentName admin, boolean enabled) {
        throwIfParentInstance("setNetworkLoggingEnabled");
        try {
            mService.setNetworkLoggingEnabled(admin, mContext.getPackageName(), enabled);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether network logging is enabled by a device owner.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Can only
     * be {@code null} if the caller is a delegated app with {@link #DELEGATION_NETWORK_LOGGING}
     * or has MANAGE_USERS permission.
     * @return {@code true} if network logging is enabled by device owner, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner and caller has
     * no MANAGE_USERS permission
     */
    public boolean isNetworkLoggingEnabled(@Nullable ComponentName admin) {
        throwIfParentInstance("isNetworkLoggingEnabled");
        try {
            return mService.isNetworkLoggingEnabled(admin, mContext.getPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner or delegated app with {@link #DELEGATION_NETWORK_LOGGING} to retrieve
     * the most recent batch of network logging events.
     * A device owner has to provide a batchToken provided as part of
     * {@link DeviceAdminReceiver#onNetworkLogsAvailable} callback. If the token doesn't match the
     * token of the most recent available batch of logs, {@code null} will be returned.
     *
     * <p> {@link NetworkEvent} can be one of {@link DnsEvent} or {@link ConnectEvent}.
     *
     * <p> The list of network events is sorted chronologically, and contains at most 1200 events.
     *
     * <p> Access to the logs is rate limited and this method will only return a new batch of logs
     * after the device device owner has been notified via
     * {@link DeviceAdminReceiver#onNetworkLogsAvailable}.
     *
     * <p>If a secondary user or profile is created, calling this method will throw a
     * {@link SecurityException} until all users become affiliated again. It will also no longer be
     * possible to retrieve the network logs batch with the most recent batchToken provided
     * by {@link DeviceAdminReceiver#onNetworkLogsAvailable}. See
     * {@link DevicePolicyManager#setAffiliationIds}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if called by a delegated app.
     * @param batchToken A token of the batch to retrieve
     * @return A new batch of network logs which is a list of {@link NetworkEvent}. Returns
     *        {@code null} if the batch represented by batchToken is no longer available or if
     *        logging is disabled.
     * @throws SecurityException if {@code admin} is not a device owner, or there is at least one
     * profile or secondary user that is not affiliated with the device.
     * @see #setAffiliationIds
     * @see DeviceAdminReceiver#onNetworkLogsAvailable
     */
    public @Nullable List<NetworkEvent> retrieveNetworkLogs(@Nullable ComponentName admin,
            long batchToken) {
        throwIfParentInstance("retrieveNetworkLogs");
        try {
            return mService.retrieveNetworkLogs(admin, mContext.getPackageName(), batchToken);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to bind to a service from a secondary managed user or vice versa.
     * See {@link #getBindDeviceAdminTargetUsers} for the pre-requirements of a
     * device owner to bind to services of another managed user.
     * <p>
     * The service must be protected by {@link android.Manifest.permission#BIND_DEVICE_ADMIN}.
     * Note that the {@link Context} used to obtain this
     * {@link DevicePolicyManager} instance via {@link Context#getSystemService(Class)} will be used
     * to bind to the {@link android.app.Service}.
     * <p>
     * Note: This method used to be available for communication between device owner and profile
     * owner. However, since Android 11, this combination is not possible. This method is now
     * only useful for communication between device owner and managed secondary users.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param serviceIntent Identifies the service to connect to.  The Intent must specify either an
     *        explicit component name or a package name to match an
     *        {@link IntentFilter} published by a service.
     * @param conn Receives information as the service is started and stopped in main thread. This
     *        must be a valid {@link ServiceConnection} object; it must not be {@code null}.
     * @param flags Operation options for the binding operation. See
     *        {@link Context#bindService(Intent, ServiceConnection, int)}.
     * @param targetUser Which user to bind to. Must be one of the users returned by
     *        {@link #getBindDeviceAdminTargetUsers}, otherwise a {@link SecurityException} will
     *        be thrown.
     * @return If you have successfully bound to the service, {@code true} is returned;
     *         {@code false} is returned if the connection is not made and you will not
     *         receive the service object.
     *
     * @see Context#bindService(Intent, ServiceConnection, int)
     * @see #getBindDeviceAdminTargetUsers(ComponentName)
     */
    public boolean bindDeviceAdminServiceAsUser(
            @NonNull ComponentName admin,  Intent serviceIntent, @NonNull ServiceConnection conn,
            @Context.BindServiceFlags int flags, @NonNull UserHandle targetUser) {
        throwIfParentInstance("bindDeviceAdminServiceAsUser");
        // Keep this in sync with ContextImpl.bindServiceCommon.
        try {
            final IServiceConnection sd = mContext.getServiceDispatcher(
                    conn, mContext.getMainThreadHandler(), flags);
            serviceIntent.prepareToLeaveProcess(mContext);
            return mService.bindDeviceAdminServiceAsUser(admin,
                    mContext.getIApplicationThread(), mContext.getActivityToken(), serviceIntent,
                    sd, flags, targetUser.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of target users that the calling device owner or owner of secondary user
     * can use when calling {@link #bindDeviceAdminServiceAsUser}.
     * <p>
     * A device owner can bind to a service from a secondary managed user and vice versa, provided
     * that both users are affiliated. See {@link #setAffiliationIds}.
     */
    public @NonNull List<UserHandle> getBindDeviceAdminTargetUsers(@NonNull ComponentName admin) {
        throwIfParentInstance("getBindDeviceAdminTargetUsers");
        try {
            return mService.getBindDeviceAdminTargetUsers(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system to get the time at which the device owner last retrieved security
     * logging entries.
     *
     * @return the time at which the device owner most recently retrieved security logging entries,
     *         in milliseconds since epoch; -1 if security logging entries were never retrieved.
     * @throws SecurityException if the caller is not the device owner, does not hold the
     *         MANAGE_USERS permission and is not the system.
     *
     * @hide
     */
    @TestApi
    public long getLastSecurityLogRetrievalTime() {
        try {
            return mService.getLastSecurityLogRetrievalTime();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system to get the time at which the device owner last requested a bug report.
     *
     * @return the time at which the device owner most recently requested a bug report, in
     *         milliseconds since epoch; -1 if a bug report was never requested.
     * @throws SecurityException if the caller is not the device owner, does not hold the
     *         MANAGE_USERS permission and is not the system.
     *
     * @hide
     */
    @TestApi
    public long getLastBugReportRequestTime() {
        try {
            return mService.getLastBugReportRequestTime();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system to get the time at which the device owner last retrieved network logging
     * events.
     *
     * @return the time at which the device owner most recently retrieved network logging events, in
     *         milliseconds since epoch; -1 if network logging events were never retrieved.
     * @throws SecurityException if the caller is not the device owner, does not hold the
     *         MANAGE_USERS permission and is not the system.
     *
     * @hide
     */
    @TestApi
    public long getLastNetworkLogRetrievalTime() {
        try {
            return mService.getLastNetworkLogRetrievalTime();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system to find out whether the current user's IME was set by the device/profile
     * owner or the user.
     *
     * @return {@code true} if the user's IME was set by the device or profile owner, {@code false}
     *         otherwise.
     * @throws SecurityException if the caller is not the device owner/profile owner.
     *
     * @hide
     */
    @TestApi
    public boolean isCurrentInputMethodSetByOwner() {
        try {
            return mService.isCurrentInputMethodSetByOwner();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system to get a list of CA certificates that were installed by the device or
     * profile owner.
     *
     * <p> The caller must be the target user's device owner/profile Owner or hold the
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL} permission.
     *
     * @param user The user for whom to retrieve information.
     * @return list of aliases identifying CA certificates installed by the device or profile owner
     * @throws SecurityException if the caller does not have permission to retrieve information
     *         about the given user's CA certificates.
     *
     * @hide
     */
    @TestApi
    public List<String> getOwnerInstalledCaCerts(@NonNull UserHandle user) {
        try {
            return mService.getOwnerInstalledCaCerts(user).getList();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether factory reset protection policy is supported on the device.
     *
     * @return {@code true} if the device support factory reset protection policy.
     *
     * @hide
     */
    @TestApi
    public boolean isFactoryResetProtectionPolicySupported() {
        try {
            return mService.isFactoryResetProtectionPolicySupported();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the device owner or profile owner to clear application user data of a given
     * package. The behaviour of this is equivalent to the target application calling
     * {@link android.app.ActivityManager#clearApplicationUserData()}.
     *
     * <p><strong>Note:</strong> an application can store data outside of its application data, e.g.
     * external storage or user dictionary. This data will not be wiped by calling this API.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package which will have its user data wiped.
     * @param executor The executor through which the listener should be invoked.
     * @param listener A callback object that will inform the caller when the clearing is done.
     * @throws SecurityException if the caller is not the device owner/profile owner.
     */
    public void clearApplicationUserData(@NonNull ComponentName admin,
            @NonNull String packageName, @NonNull @CallbackExecutor Executor executor,
            @NonNull OnClearApplicationUserDataListener listener) {
        throwIfParentInstance("clearAppData");
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        try {
            mService.clearApplicationUserData(admin, packageName,
                    new IPackageDataObserver.Stub() {
                        public void onRemoveCompleted(String pkg, boolean succeeded) {
                            executor.execute(() ->
                                    listener.onApplicationUserDataCleared(pkg, succeeded));
                        }
                    });
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to specify whether logout is enabled for all secondary users. The
     * system may show a logout button that stops the user and switches back to the primary user.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param enabled whether logout should be enabled or not.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setLogoutEnabled(@NonNull ComponentName admin, boolean enabled) {
        throwIfParentInstance("setLogoutEnabled");
        try {
            mService.setLogoutEnabled(admin, enabled);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether logout is enabled by a device owner.
     *
     * @return {@code true} if logout is enabled by device owner, {@code false} otherwise.
     */
    public boolean isLogoutEnabled() {
        throwIfParentInstance("isLogoutEnabled");
        try {
            return mService.isLogoutEnabled();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Callback used in {@link #clearApplicationUserData}
     * to indicate that the clearing of an application's user data is done.
     */
    public interface OnClearApplicationUserDataListener {
        /**
         * Method invoked when clearing the application user data has completed.
         *
         * @param packageName The name of the package which had its user data cleared.
         * @param succeeded Whether the clearing succeeded. Clearing fails for device administrator
         *                  apps and protected system packages.
         */
        void onApplicationUserDataCleared(String packageName, boolean succeeded);
    }

    /**
     * Returns set of system apps that should be removed during provisioning.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userId ID of the user to be provisioned.
     * @param provisioningAction action indicating type of provisioning, should be one of
     * {@link #ACTION_PROVISION_MANAGED_DEVICE}, {@link #ACTION_PROVISION_MANAGED_PROFILE} or
     * {@link #ACTION_PROVISION_MANAGED_USER}.
     *
     * @hide
     */
    public Set<String> getDisallowedSystemApps(ComponentName admin, int userId,
            String provisioningAction) {
        try {
            return new ArraySet<>(
                    mService.getDisallowedSystemApps(admin, userId, provisioningAction));
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Changes the current administrator to another one. All policies from the current
     * administrator are migrated to the new administrator. The whole operation is atomic -
     * the transfer is either complete or not done at all.
     *
     * <p>Depending on the current administrator (device owner, profile owner), you have the
     * following expected behaviour:
     * <ul>
     *     <li>A device owner can only be transferred to a new device owner</li>
     *     <li>A profile owner can only be transferred to a new profile owner</li>
     * </ul>
     *
     * <p>Use the {@code bundle} parameter to pass data to the new administrator. The data
     * will be received in the
     * {@link DeviceAdminReceiver#onTransferOwnershipComplete(Context, PersistableBundle)}
     * callback of the new administrator.
     *
     * <p>The transfer has failed if the original administrator is still the corresponding owner
     * after calling this method.
     *
     * <p>The incoming target administrator must have the
     * <code>&lt;support-transfer-ownership /&gt;</code> tag inside the
     * <code>&lt;device-admin&gt;&lt;/device-admin&gt;</code> tags in the xml file referenced by
     * {@link DeviceAdminReceiver#DEVICE_ADMIN_META_DATA}. Otherwise an
     * {@link IllegalArgumentException} will be thrown.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @param target which {@link DeviceAdminReceiver} we want the new administrator to be
     * @param bundle data to be sent to the new administrator
     * @throws SecurityException if {@code admin} is not a device owner nor a profile owner
     * @throws IllegalArgumentException if {@code admin} or {@code target} is {@code null}, they
     * are components in the same package or {@code target} is not an active admin
     */
    public void transferOwnership(@NonNull ComponentName admin, @NonNull ComponentName target,
            @Nullable PersistableBundle bundle) {
        throwIfParentInstance("transferOwnership");
        try {
            mService.transferOwnership(admin, target, bundle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to specify the user session start message. This may be displayed
     * during a user switch.
     * <p>
     * The message should be limited to a short statement or it may be truncated.
     * <p>
     * If the message needs to be localized, it is the responsibility of the
     * {@link DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast
     * and set a new version of this message accordingly.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @param startUserSessionMessage message for starting user session, or {@code null} to use
     * system default message.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setStartUserSessionMessage(
            @NonNull ComponentName admin, @Nullable CharSequence startUserSessionMessage) {
        throwIfParentInstance("setStartUserSessionMessage");
        try {
            mService.setStartUserSessionMessage(admin, startUserSessionMessage);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to specify the user session end message. This may be displayed
     * during a user switch.
     * <p>
     * The message should be limited to a short statement or it may be truncated.
     * <p>
     * If the message needs to be localized, it is the responsibility of the
     * {@link DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast
     * and set a new version of this message accordingly.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @param endUserSessionMessage message for ending user session, or {@code null} to use system
     * default message.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setEndUserSessionMessage(
            @NonNull ComponentName admin, @Nullable CharSequence endUserSessionMessage) {
        throwIfParentInstance("setEndUserSessionMessage");
        try {
            mService.setEndUserSessionMessage(admin, endUserSessionMessage);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user session start message.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public CharSequence getStartUserSessionMessage(@NonNull ComponentName admin) {
        throwIfParentInstance("getStartUserSessionMessage");
        try {
            return mService.getStartUserSessionMessage(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user session end message.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public CharSequence getEndUserSessionMessage(@NonNull ComponentName admin) {
        throwIfParentInstance("getEndUserSessionMessage");
        try {
            return mService.getEndUserSessionMessage(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner to add an override APN.
     *
     * <p>This method may returns {@code -1} if {@code apnSetting} conflicts with an existing
     * override APN. Update the existing conflicted APN with
     * {@link #updateOverrideApn(ComponentName, int, ApnSetting)} instead of adding a new entry.
     * <p>Two override APNs are considered to conflict when all the following APIs return
     * the same values on both override APNs:
     * <ul>
     *   <li>{@link ApnSetting#getOperatorNumeric()}</li>
     *   <li>{@link ApnSetting#getApnName()}</li>
     *   <li>{@link ApnSetting#getProxyAddressAsString()}</li>
     *   <li>{@link ApnSetting#getProxyPort()}</li>
     *   <li>{@link ApnSetting#getMmsProxyAddressAsString()}</li>
     *   <li>{@link ApnSetting#getMmsProxyPort()}</li>
     *   <li>{@link ApnSetting#getMmsc()}</li>
     *   <li>{@link ApnSetting#isEnabled()}</li>
     *   <li>{@link ApnSetting#getMvnoType()}</li>
     *   <li>{@link ApnSetting#getProtocol()}</li>
     *   <li>{@link ApnSetting#getRoamingProtocol()}</li>
     * </ul>
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @param apnSetting the override APN to insert
     * @return The {@code id} of inserted override APN. Or {@code -1} when failed to insert into
     *         the database.
     * @throws SecurityException if {@code admin} is not a device owner.
     *
     * @see #setOverrideApnsEnabled(ComponentName, boolean)
     */
    public int addOverrideApn(@NonNull ComponentName admin, @NonNull ApnSetting apnSetting) {
        throwIfParentInstance("addOverrideApn");
        if (mService != null) {
            try {
                return mService.addOverrideApn(admin, apnSetting);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return -1;
    }

    /**
     * Called by device owner to update an override APN.
     *
     * <p>This method may returns {@code false} if there is no override APN with the given
     * {@code apnId}.
     * <p>This method may also returns {@code false} if {@code apnSetting} conflicts with an
     * existing override APN. Update the existing conflicted APN instead.
     * <p>See {@link #addOverrideApn} for the definition of conflict.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @param apnId the {@code id} of the override APN to update
     * @param apnSetting the override APN to update
     * @return {@code true} if the required override APN is successfully updated,
     *         {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     *
     * @see #setOverrideApnsEnabled(ComponentName, boolean)
     */
    public boolean updateOverrideApn(@NonNull ComponentName admin, int apnId,
            @NonNull ApnSetting apnSetting) {
        throwIfParentInstance("updateOverrideApn");
        if (mService != null) {
            try {
                return mService.updateOverrideApn(admin, apnId, apnSetting);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device owner to remove an override APN.
     *
     * <p>This method may returns {@code false} if there is no override APN with the given
     * {@code apnId}.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @param apnId the {@code id} of the override APN to remove
     * @return {@code true} if the required override APN is successfully removed, {@code false}
     *         otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     *
     * @see #setOverrideApnsEnabled(ComponentName, boolean)
     */
    public boolean removeOverrideApn(@NonNull ComponentName admin, int apnId) {
        throwIfParentInstance("removeOverrideApn");
        if (mService != null) {
            try {
                return mService.removeOverrideApn(admin, apnId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device owner to get all override APNs inserted by device owner.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @return A list of override APNs inserted by device owner.
     * @throws SecurityException if {@code admin} is not a device owner.
     *
     * @see #setOverrideApnsEnabled(ComponentName, boolean)
     */
    public List<ApnSetting> getOverrideApns(@NonNull ComponentName admin) {
        throwIfParentInstance("getOverrideApns");
        if (mService != null) {
            try {
                return mService.getOverrideApns(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptyList();
    }

    /**
     * Called by device owner to set if override APNs should be enabled.
     * <p> Override APNs are separated from other APNs on the device, and can only be inserted or
     * modified by the device owner. When enabled, only override APNs are in use, any other APNs
     * are ignored.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @param enabled {@code true} if override APNs should be enabled, {@code false} otherwise
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setOverrideApnsEnabled(@NonNull ComponentName admin, boolean enabled) {
        throwIfParentInstance("setOverrideApnEnabled");
        if (mService != null) {
            try {
                mService.setOverrideApnsEnabled(admin, enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by device owner to check if override APNs are currently enabled.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @return {@code true} if override APNs are currently enabled, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     *
     * @see #setOverrideApnsEnabled(ComponentName, boolean)
     */
    public boolean isOverrideApnEnabled(@NonNull ComponentName admin) {
        throwIfParentInstance("isOverrideApnEnabled");
        if (mService != null) {
            try {
                return mService.isOverrideApnEnabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns the data passed from the current administrator to the new administrator during an
     * ownership transfer. This is the same {@code bundle} passed in
     * {@link #transferOwnership(ComponentName, ComponentName, PersistableBundle)}. The bundle is
     * persisted until the profile owner or device owner is removed.
     *
     * <p>This is the same <code>bundle</code> received in the
     * {@link DeviceAdminReceiver#onTransferOwnershipComplete(Context, PersistableBundle)}.
     * Use this method to retrieve it after the transfer as long as the new administrator is the
     * active device or profile owner.
     *
     * <p>Returns <code>null</code> if no ownership transfer was started for the calling user.
     *
     * @see #transferOwnership
     * @see DeviceAdminReceiver#onTransferOwnershipComplete(Context, PersistableBundle)
     * @throws SecurityException if the caller is not a device or profile owner.
     */
    @Nullable
    public PersistableBundle getTransferOwnershipBundle() {
        throwIfParentInstance("getTransferOwnershipBundle");
        try {
            return mService.getTransferOwnershipBundle();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the global Private DNS mode to opportunistic.
     * May only be called by the device owner.
     *
     * <p>In this mode, the DNS subsystem will attempt a TLS handshake to the network-supplied
     * resolver prior to attempting name resolution in cleartext.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     *
     * @return {@code PRIVATE_DNS_SET_NO_ERROR} if the mode was set successfully, or
     *         {@code PRIVATE_DNS_SET_ERROR_FAILURE_SETTING} if it could not be set.
     *
     * @throws SecurityException if the caller is not the device owner.
     */
    public @PrivateDnsModeErrorCodes int setGlobalPrivateDnsModeOpportunistic(
            @NonNull ComponentName admin) {
        throwIfParentInstance("setGlobalPrivateDnsModeOpportunistic");

        if (mService == null) {
            return PRIVATE_DNS_SET_ERROR_FAILURE_SETTING;
        }

        try {
            return mService.setGlobalPrivateDns(admin, PRIVATE_DNS_MODE_OPPORTUNISTIC, null);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the global Private DNS host to be used.
     * May only be called by the device owner.
     *
     * <p>Note that the method is blocking as it will perform a connectivity check to the resolver,
     * to ensure it is valid. Because of that, the method should not be called on any thread that
     * relates to user interaction, such as the UI thread.
     *
     * <p>In case a VPN is used in conjunction with Private DNS resolver, the Private DNS resolver
     * must be reachable both from within and outside the VPN. Otherwise, the device may lose
     * the ability to resolve hostnames as system traffic to the resolver may not go through the
     * VPN.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @param privateDnsHost The hostname of a server that implements DNS over TLS (RFC7858).
     *
     * @return {@code PRIVATE_DNS_SET_NO_ERROR} if the mode was set successfully,
     *         {@code PRIVATE_DNS_SET_ERROR_FAILURE_SETTING} if it could not be set or
     *         {@code PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING} if the specified host does not
     *         implement RFC7858.
     *
     * @throws IllegalArgumentException if the {@code privateDnsHost} is not a valid hostname.
     *
     * @throws SecurityException if the caller is not the device owner.
     */
    @WorkerThread public @PrivateDnsModeErrorCodes int setGlobalPrivateDnsModeSpecifiedHost(
            @NonNull ComponentName admin, @NonNull String privateDnsHost) {
        throwIfParentInstance("setGlobalPrivateDnsModeSpecifiedHost");
        Objects.requireNonNull(privateDnsHost, "dns resolver is null");

        if (mService == null) {
            return PRIVATE_DNS_SET_ERROR_FAILURE_SETTING;
        }

        if (NetworkUtils.isWeaklyValidatedHostname(privateDnsHost)) {
            if (!PrivateDnsConnectivityChecker.canConnectToPrivateDnsServer(privateDnsHost)) {
                return PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING;
            }
        }

        try {
            return mService.setGlobalPrivateDns(
                    admin, PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, privateDnsHost);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner or profile owner of an organization-owned managed profile to install
     * a system update from the given file. The device will be
     * rebooted in order to finish installing the update. Note that if the device is rebooted, this
     * doesn't necessarily mean that the update has been applied successfully. The caller should
     * additionally check the system version with {@link android.os.Build#FINGERPRINT} or {@link
     * android.os.Build.VERSION}. If an error occurs during processing the OTA before the reboot,
     * the caller will be notified by {@link InstallSystemUpdateCallback}. If device does not have
     * sufficient battery level, the installation will fail with error {@link
     * InstallSystemUpdateCallback#UPDATE_ERROR_BATTERY_LOW}.
     *
     * @param admin The {@link DeviceAdminReceiver} that this request is associated with.
     * @param updateFilePath An Uri of the file that contains the update. The file should be
     * readable by the calling app.
     * @param executor The executor through which the callback should be invoked.
     * @param callback A callback object that will inform the caller when installing an update
     * fails.
     */
    public void installSystemUpdate(
            @NonNull ComponentName admin, @NonNull Uri updateFilePath,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull InstallSystemUpdateCallback callback) {
        throwIfParentInstance("installUpdate");
        if (mService == null) {
            return;
        }
        try (ParcelFileDescriptor fileDescriptor = mContext.getContentResolver()
                    .openFileDescriptor(updateFilePath, "r")) {
            mService.installUpdateFromFile(
                    admin, fileDescriptor, new StartInstallingUpdateCallback.Stub() {
                        @Override
                        public void onStartInstallingUpdateError(
                                int errorCode, String errorMessage) {
                            executeCallback(errorCode, errorMessage, executor, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (FileNotFoundException e) {
            Log.w(TAG, e);
            executeCallback(
                    InstallSystemUpdateCallback.UPDATE_ERROR_FILE_NOT_FOUND,
                    Log.getStackTraceString(e),
                    executor, callback);
        } catch (IOException e) {
            Log.w(TAG, e);
            executeCallback(
                    InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN, Log.getStackTraceString(e),
                    executor, callback);
        }
    }

    private void executeCallback(int errorCode, String errorMessage,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull InstallSystemUpdateCallback callback) {
        executor.execute(() -> callback.onInstallUpdateError(errorCode, errorMessage));
    }

    /**
     * Returns the system-wide Private DNS mode.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @return one of {@code PRIVATE_DNS_MODE_OFF}, {@code PRIVATE_DNS_MODE_OPPORTUNISTIC},
     * {@code PRIVATE_DNS_MODE_PROVIDER_HOSTNAME} or {@code PRIVATE_DNS_MODE_UNKNOWN}.
     * @throws SecurityException if the caller is not the device owner.
     */
    public int getGlobalPrivateDnsMode(@NonNull ComponentName admin) {
        throwIfParentInstance("setGlobalPrivateDns");
        if (mService == null) {
            return PRIVATE_DNS_MODE_UNKNOWN;
        }

        try {
            return mService.getGlobalPrivateDnsMode(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the system-wide Private DNS host.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @return The hostname used for Private DNS queries, null if none is set.
     * @throws SecurityException if the caller is not the device owner.
     */
    public @Nullable String getGlobalPrivateDnsHost(@NonNull ComponentName admin) {
        throwIfParentInstance("setGlobalPrivateDns");
        if (mService == null) {
            return null;
        }

        try {
            return mService.getGlobalPrivateDnsHost(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Deprecated. Use {@code markProfileOwnerOnOrganizationOwnedDevice} instead.
     * When called by an app targeting SDK level {@link android.os.Build.VERSION_CODES#Q} or
     * below, will behave the same as {@link #markProfileOwnerOnOrganizationOwnedDevice}.
     *
     * When called by an app targeting SDK level {@link android.os.Build.VERSION_CODES#R}
     * or above, will throw an UnsupportedOperationException when called.
     *
     * @deprecated Use {@link #markProfileOwnerOnOrganizationOwnedDevice} instead.
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(value = android.Manifest.permission.GRANT_PROFILE_OWNER_DEVICE_IDS_ACCESS,
            conditional = true)
    public void setProfileOwnerCanAccessDeviceIds(@NonNull ComponentName who) {
        ApplicationInfo ai = mContext.getApplicationInfo();
        if (ai.targetSdkVersion > Build.VERSION_CODES.Q) {
            throw new UnsupportedOperationException(
                    "This method is deprecated. use markProfileOwnerOnOrganizationOwnedDevice"
                    + " instead.");
        } else {
            markProfileOwnerOnOrganizationOwnedDevice(who);
        }
    }

    /**
     * Marks the profile owner of the given user as managing an organization-owned device.
     * That will give it access to device identifiers (such as serial number, IMEI and MEID)
     * as well as other privileges.
     *
     * @hide
     */
    @RequiresPermission(value = android.Manifest.permission.MARK_DEVICE_ORGANIZATION_OWNED,
            conditional = true)
    public void markProfileOwnerOnOrganizationOwnedDevice(@NonNull ComponentName who) {
        if (mService == null) {
            return;
        }
        try {
            mService.markProfileOwnerOnOrganizationOwnedDevice(who, myUserId());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Allows a set of packages to access cross-profile calendar APIs.
     *
     * <p>Called by a profile owner of a managed profile.
     *
     * <p>Calling with a {@code null} value for the set disables the restriction so that all
     * packages are allowed to access cross-profile calendar APIs. Calling with an empty set
     * disallows all packages from accessing cross-profile calendar APIs. If this method isn't
     * called, no package is allowed to access cross-profile calendar APIs by default.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @param packageNames set of packages to be whitelisted
     * @throws SecurityException if {@code admin} is not a profile owner
     *
     * @see #getCrossProfileCalendarPackages(ComponentName)
     */
    public void setCrossProfileCalendarPackages(@NonNull ComponentName admin,
            @Nullable Set<String> packageNames) {
        throwIfParentInstance("setCrossProfileCalendarPackages");
        if (mService != null) {
            try {
                mService.setCrossProfileCalendarPackages(admin, packageNames == null ? null
                        : new ArrayList<>(packageNames));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Gets a set of package names that are allowed to access cross-profile calendar APIs.
     *
     * <p>Called by a profile owner of a managed profile.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @return the set of names of packages that were previously allowed via
     * {@link #setCrossProfileCalendarPackages(ComponentName, Set)}, or an
     * empty set if none have been allowed
     * @throws SecurityException if {@code admin} is not a profile owner
     *
     * @see #setCrossProfileCalendarPackages(ComponentName, Set)
     */
    public @Nullable Set<String> getCrossProfileCalendarPackages(@NonNull ComponentName admin) {
        throwIfParentInstance("getCrossProfileCalendarPackages");
        if (mService != null) {
            try {
                final List<String> packageNames = mService.getCrossProfileCalendarPackages(admin);
                return packageNames == null ? null : new ArraySet<>(packageNames);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptySet();
    }

    /**
     * Returns if a package is allowed to access cross-profile calendar APIs.
     *
     * <p>A package is allowed to access cross-profile calendar APIs if it's allowed by
     * admins via {@link #setCrossProfileCalendarPackages(ComponentName, Set)} and
     * {@link android.provider.Settings.Secure#CROSS_PROFILE_CALENDAR_ENABLED}
     * is turned on in the managed profile.
     *
     * <p>To query for a specific user, use
     * {@link Context#createPackageContextAsUser(String, int, UserHandle)} to create a context for
     * that user, and get a {@link DevicePolicyManager} from this context.
     *
     * @param packageName the name of the package
     * @return {@code true} if the package is allowed to access cross-profile calendar APIs,
     * {@code false} otherwise
     *
     * @see #setCrossProfileCalendarPackages(ComponentName, Set)
     * @see #getCrossProfileCalendarPackages(ComponentName)
     * @hide
     */
    public boolean isPackageAllowedToAccessCalendar(@NonNull  String packageName) {
        throwIfParentInstance("isPackageAllowedToAccessCalendar");
        if (mService != null) {
            try {
                return mService.isPackageAllowedToAccessCalendarForUser(packageName,
                        myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Gets a set of package names that are allowed to access cross-profile calendar APIs.
     *
     * <p>To query for a specific user, use
     * {@link Context#createPackageContextAsUser(String, int, UserHandle)} to create a context for
     * that user, and get a {@link DevicePolicyManager} from this context.
     *
     * @return the set of names of packages that were previously allowed via
     * {@link #setCrossProfileCalendarPackages(ComponentName, Set)}, or an
     * empty set if none have been allowed
     *
     * @see #setCrossProfileCalendarPackages(ComponentName, Set)
     * @see #getCrossProfileCalendarPackages(ComponentName)
     * @hide
     */
    public @Nullable Set<String> getCrossProfileCalendarPackages() {
        throwIfParentInstance("getCrossProfileCalendarPackages");
        if (mService != null) {
            try {
                final List<String> packageNames = mService.getCrossProfileCalendarPackagesForUser(
                        myUserId());
                return packageNames == null ? null : new ArraySet<>(packageNames);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptySet();
    }

    /**
     * Sets the set of admin-whitelisted package names that are allowed to request user consent for
     * cross-profile communication.
     *
     * <p>Assumes that the caller is a profile owner and is the given {@code admin}.
     *
     * <p>Previous calls are overridden by each subsequent call to this method.
     *
     * <p>Note that other apps may be able to request user consent for cross-profile communication
     * if they have been explicitly whitelisted by the OEM.
     *
     * <p>When previously-set cross-profile packages are missing from {@code packageNames}, the
     * app-op for {@code INTERACT_ACROSS_PROFILES} will be reset for those packages. This will not
     * occur for packages that are whitelisted by the OEM.
     *
     * @param admin the {@link DeviceAdminReceiver} this request is associated with
     * @param packageNames the new cross-profile package names
     */
    public void setCrossProfilePackages(
            @NonNull ComponentName admin, @NonNull Set<String> packageNames) {
        throwIfParentInstance("setCrossProfilePackages");
        if (mService != null) {
            try {
                mService.setCrossProfilePackages(admin, new ArrayList<>(packageNames));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the set of package names that the admin has previously set as allowed to request user
     * consent for cross-profile communication, via {@link #setCrossProfilePackages(ComponentName,
     * Set)}.
     *
     * <p>Assumes that the caller is a profile owner and is the given {@code admin}.
     *
     * <p>Note that other apps not included in the returned set may be able to request user consent
     * for cross-profile communication if they have been explicitly whitelisted by the OEM.
     *
     * @param admin the {@link DeviceAdminReceiver} this request is associated with
     * @return the set of package names the admin has previously set as allowed to request user
     * consent for cross-profile communication, via {@link #setCrossProfilePackages(ComponentName,
     * Set)}
     */
    public @NonNull Set<String> getCrossProfilePackages(@NonNull ComponentName admin) {
        throwIfParentInstance("getCrossProfilePackages");
        if (mService != null) {
            try {
                return new ArraySet<>(mService.getCrossProfilePackages(admin));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptySet();
    }

    /**
     * Returns the combined set of the following:
     * <ul>
     * <li>The package names that the admin has previously set as allowed to request user consent
     * for cross-profile communication, via {@link #setCrossProfilePackages(ComponentName,
     * Set)}.</li>
     * <li>The default package names set by the OEM that are allowed to request user consent for
     * cross-profile communication without being explicitly enabled by the admin, via {@link
     * com.android.internal.R.array#cross_profile_apps} and {@link com.android.internal.R.array
     * #vendor_cross_profile_apps}.</li>
     * </ul>
     *
     * @return the combined set of whitelisted package names set via
     * {@link #setCrossProfilePackages(ComponentName, Set)}, {@link com.android.internal.R.array
     * #cross_profile_apps}, and {@link com.android.internal.R.array#vendor_cross_profile_apps}.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            permission.INTERACT_ACROSS_USERS_FULL,
            permission.INTERACT_ACROSS_USERS,
            permission.INTERACT_ACROSS_PROFILES
    })
    public @NonNull Set<String> getAllCrossProfilePackages() {
        throwIfParentInstance("getAllCrossProfilePackages");
        if (mService != null) {
            try {
                return new ArraySet<>(mService.getAllCrossProfilePackages());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptySet();
    }

    /**
     * Returns the default package names set by the OEM that are allowed to request user consent for
     * cross-profile communication without being explicitly enabled by the admin, via {@link
     * com.android.internal.R.array#cross_profile_apps} and {@link com.android.internal.R.array
     * #vendor_cross_profile_apps}.
     *
     * @hide
     */
    public @NonNull Set<String> getDefaultCrossProfilePackages() {
        throwIfParentInstance("getDefaultCrossProfilePackages");
        if (mService != null) {
            try {
                return new ArraySet<>(mService.getDefaultCrossProfilePackages());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptySet();
    }

    /**
     * Returns whether the device is being used as a managed kiosk. These requirements are as
     * follows:
     * <ul>
     *     <li>The device is in Lock Task (therefore there is also a Device Owner app on the
     *     device)</li>
     *     <li>The Lock Task feature {@link DevicePolicyManager#LOCK_TASK_FEATURE_SYSTEM_INFO} is
     *     not enabled, so the system info in the status bar is not visible</li>
     *     <li>The device does not have a secure lock screen (e.g. it has no lock screen or has
     *     swipe-to-unlock)</li>
     *     <li>The device is not in the middle of an ephemeral user session</li>
     * </ul>
     *
     * <p>Publicly-accessible dedicated devices don't have the same privacy model as
     * personally-used devices. In particular, user consent popups don't make sense as a barrier to
     * accessing persistent data on these devices since the user giving consent and the user whose
     * data is on the device are unlikely to be the same. These consent popups prevent the true
     * remote management of these devices.
     *
     * <p>This condition is not sufficient to cover APIs that would access data that only lives for
     * the duration of the user's session, since the user has an expectation of privacy in these
     * conditions that more closely resembles use of a personal device. In those cases, see {@link
     * #isUnattendedManagedKiosk()}.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean isManagedKiosk() {
        throwIfParentInstance("isManagedKiosk");
        if (mService != null) {
            try {
                return mService.isManagedKiosk();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns whether the device is being used as an unattended managed kiosk. These requirements
     * are as follows:
     * <ul>
     *     <li>The device is being used as a managed kiosk, as defined at {@link
     *     #isManagedKiosk()}</li>
     *     <li>The device has not received user input for at least 30 minutes</li>
     * </ul>
     *
     * <p>See {@link #isManagedKiosk()} for context. This is a stronger requirement that also
     * ensures that the device hasn't been interacted with recently, making it an appropriate check
     * for privacy-sensitive APIs that wouldn't be appropriate during an active user session.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean isUnattendedManagedKiosk() {
        throwIfParentInstance("isUnattendedManagedKiosk");
        if (mService != null) {
            try {
                return mService.isUnattendedManagedKiosk();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Starts an activity to view calendar events in the managed profile.
     *
     * @param eventId the id of the event to be viewed
     * @param start the start time of the event
     * @param end the end time of the event
     * @param allDay if the event is an all-day event
     * @param flags flags to be set for the intent
     * @return {@code true} if the activity is started successfully, {@code false} otherwise
     *
     * @see CalendarContract#startViewCalendarEventInManagedProfile(Context, String, long, long,
     * long, boolean, int)
     *
     * @hide
     */
    public boolean startViewCalendarEventInManagedProfile(long eventId, long start, long end,
            boolean allDay, int flags) {
        throwIfParentInstance("startViewCalendarEventInManagedProfile");
        if (mService != null) {
            try {
                return mService.startViewCalendarEventInManagedProfile(mContext.getPackageName(),
                        eventId, start, end, allDay, flags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by Device owner to disable user control over apps. User will not be able to clear
     * app data or force-stop packages.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @param packages The package names for the apps.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setUserControlDisabledPackages(@NonNull ComponentName admin,
            @NonNull List<String> packages) {
        throwIfParentInstance("setUserControlDisabledPackages");
        if (mService != null) {
            try {
                mService.setUserControlDisabledPackages(admin, packages);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the list of packages over which user control is disabled by the device owner.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public @NonNull List<String> getUserControlDisabledPackages(@NonNull ComponentName admin) {
        throwIfParentInstance("getUserControlDisabledPackages");
        if (mService != null) {
            try {
                return mService.getUserControlDisabledPackages(admin);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return Collections.emptyList();
    }

    /**
     * Called by device owner or profile owner of an organization-owned managed profile to toggle
     * Common Criteria mode for the device. When the device is in Common Criteria mode,
     * certain device functionalities are tuned to meet the higher
     * security level required by Common Criteria certification. For example:
     * <ul>
     * <li> Bluetooth long term key material is additionally integrity-protected with AES-GCM. </li>
     * <li> WiFi configuration store is additionally integrity-protected with AES-GCM. </li>
     * </ul>
     * Common Criteria mode is disabled by default.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @param enabled whether Common Criteria mode should be enabled or not.
     */
    public void setCommonCriteriaModeEnabled(@NonNull ComponentName admin, boolean enabled) {
        throwIfParentInstance("setCommonCriteriaModeEnabled");
        if (mService != null) {
            try {
                mService.setCommonCriteriaModeEnabled(admin, enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns whether Common Criteria mode is currently enabled. Device owner and profile owner of
     * an organization-owned managed profile can query its own Common Criteria mode setting by
     * calling this method with its admin {@link ComponentName}. Any caller can obtain the
     * aggregated device-wide Common Criteria mode state by passing {@code null} as the
     * {@code admin} argument.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with, or
     *     {@code null} if the caller is not a device admin.
     * @return {@code true} if Common Criteria mode is enabled, {@code false} otherwise.
     */
    public boolean isCommonCriteriaModeEnabled(@Nullable ComponentName admin) {
        throwIfParentInstance("isCommonCriteriaModeEnabled");
        if (mService != null) {
            try {
                return mService.isCommonCriteriaModeEnabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by profile owner of an organization-owned managed profile to check whether
     * personal apps are suspended.
     *
     * @return a bitmask of reasons for personal apps suspension or
     *     {@link #PERSONAL_APPS_NOT_SUSPENDED} if apps are not suspended.
     * @see #setPersonalAppsSuspended
     */
    public @PersonalAppsSuspensionReason int getPersonalAppsSuspendedReasons(
            @NonNull ComponentName admin) {
        throwIfParentInstance("getPersonalAppsSuspendedReasons");
        if (mService != null) {
            try {
                return mService.getPersonalAppsSuspendedReasons(admin);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by a profile owner of an organization-owned managed profile to suspend personal
     * apps on the device. When personal apps are suspended the device can only be used for calls.
     *
     * <p>When personal apps are suspended, an ongoing notification about that is shown to the user.
     * When the user taps the notification, system invokes {@link #ACTION_CHECK_POLICY_COMPLIANCE}
     * in the profile owner package. Profile owner implementation that uses personal apps suspension
     * must handle this intent.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with
     * @param suspended Whether personal apps should be suspended.
     * @throws IllegalStateException if the profile owner doesn't have an activity that handles
     *        {@link #ACTION_CHECK_POLICY_COMPLIANCE}
     */
    public void setPersonalAppsSuspended(@NonNull ComponentName admin, boolean suspended) {
        throwIfParentInstance("setPersonalAppsSuspended");
        if (mService != null) {
            try {
                mService.setPersonalAppsSuspended(admin, suspended);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of an organization-owned managed profile to set maximum time
     * the profile is allowed to be turned off. If the profile is turned off for longer, personal
     * apps are suspended on the device.
     *
     * <p>When personal apps are suspended, an ongoing notification about that is shown to the user.
     * When the user taps the notification, system invokes {@link #ACTION_CHECK_POLICY_COMPLIANCE}
     * in the profile owner package. Profile owner implementation that uses personal apps suspension
     * must handle this intent.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with
     * @param timeoutMillis Maximum time the profile is allowed to be off in milliseconds or 0 if
     *        not limited. The minimum non-zero value corresponds to 72 hours. If an admin sets a
     *        smaller non-zero vaulue, 72 hours will be set instead.
     * @throws IllegalStateException if the profile owner doesn't have an activity that handles
     *        {@link #ACTION_CHECK_POLICY_COMPLIANCE}
     * @see #setPersonalAppsSuspended
     */
    public void setManagedProfileMaximumTimeOff(@NonNull ComponentName admin, long timeoutMillis) {
        throwIfParentInstance("setManagedProfileMaximumTimeOff");
        if (mService != null) {
            try {
                mService.setManagedProfileMaximumTimeOff(admin, timeoutMillis);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

     /**
     * Called by a profile owner of an organization-owned managed profile to get maximum time
     * the profile is allowed to be turned off.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with
     * @return Maximum time the profile is allowed to be off in milliseconds or 0 if not limited.
     * @see #setPersonalAppsSuspended
     */
    public long getManagedProfileMaximumTimeOff(@NonNull ComponentName admin) {
        throwIfParentInstance("getManagedProfileMaximumTimeOff");
        if (mService != null) {
            try {
                return mService.getManagedProfileMaximumTimeOff(admin);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Returns {@code true} when {@code userId} has a profile owner that is capable of resetting
     * password in RUNNING_LOCKED state. For that it should have at least one direct boot aware
     * component and have an active password reset token. Can only be called by the system.
     * @hide
     */
    public boolean canProfileOwnerResetPasswordWhenLocked(int userId) {
        if (mService != null) {
            try {
                return mService.canProfileOwnerResetPasswordWhenLocked(userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }
}
