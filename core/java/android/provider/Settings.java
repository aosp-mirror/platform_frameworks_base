/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.provider;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.app.Application;
import android.app.AutomaticZenRule;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.SearchManager;
import android.app.WallpaperManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.location.ILocationManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkScoreManager;
import android.net.Uri;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.PowerManager;
import android.os.PowerManager.AutoPowerSaveModeTriggers;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.speech.tts.TextToSpeech;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.AndroidException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.MemoryIntArray;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.widget.Editor;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.ILockSettings;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
/**
 * The Settings provider contains global system-level device preferences.
 */
public final class Settings {
    /** @hide */
    public static final boolean DEFAULT_OVERRIDEABLE_BY_RESTORE = false;

    // Intent actions for Settings

    /**
     * Activity Action: Show system settings.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SETTINGS = "android.settings.SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of APNs.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * <p class="note">
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APN_SETTINGS = "android.settings.APN_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of current location
     * sources.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_LOCATION_SOURCE_SETTINGS =
            "android.settings.LOCATION_SOURCE_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of location controller extra package.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_LOCATION_CONTROLLER_EXTRA_PACKAGE_SETTINGS =
            "android.settings.LOCATION_CONTROLLER_EXTRA_PACKAGE_SETTINGS";

    /**
     * Activity Action: Show scanning settings to allow configuration of Wi-Fi
     * and Bluetooth scanning settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_LOCATION_SCANNING_SETTINGS =
            "android.settings.LOCATION_SCANNING_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of users.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_USER_SETTINGS =
            "android.settings.USER_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of wireless controls
     * such as Wi-Fi, Bluetooth and Mobile networks.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_WIRELESS_SETTINGS =
            "android.settings.WIRELESS_SETTINGS";

    /**
     * Activity Action: Show tether provisioning activity.
     *
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: {@link ConnectivityManager#EXTRA_TETHER_TYPE} should be included to specify which type
     * of tethering should be checked. {@link ConnectivityManager#EXTRA_PROVISION_CALLBACK} should
     * contain a {@link ResultReceiver} which will be called back with a tether result code.
     * <p>
     * Output: The result of the provisioning check.
     * {@link ConnectivityManager#TETHER_ERROR_NO_ERROR} if successful,
     * {@link ConnectivityManager#TETHER_ERROR_PROVISION_FAILED} for failure.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_TETHER_PROVISIONING_UI =
            "android.settings.TETHER_PROVISIONING_UI";

    /**
     * Activity Action: Show a dialog activity to notify tethering is NOT supported by carrier.
     *
     * When {@link android.telephony.CarrierConfigManager#KEY_CARRIER_SUPPORTS_TETHERING_BOOL}
     * is false, and tethering is started by Settings, this dialog activity will be started to
     * tell the user that tethering is not supported by carrier.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_TETHER_UNSUPPORTED_CARRIER_UI =
            "android.settings.TETHER_UNSUPPORTED_CARRIER_UI";

    /**
     * Activity Action: Show settings to allow entering/exiting airplane mode.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_AIRPLANE_MODE_SETTINGS =
            "android.settings.AIRPLANE_MODE_SETTINGS";

    /**
     * Activity Action: Show mobile data usage list.
     * <p>
     * Input: {@link EXTRA_NETWORK_TEMPLATE} and {@link EXTRA_SUB_ID} should be included to specify
     * how and what mobile data statistics should be collected.
     * <p>
     * Output: Nothing
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MOBILE_DATA_USAGE =
            "android.settings.MOBILE_DATA_USAGE";

    /** @hide */
    public static final String EXTRA_NETWORK_TEMPLATE = "network_template";

    /**
     * Activity Action: Show One-handed mode Settings page.
     * <p>
     * Input: Nothing
     * <p>
     * Output: Nothing
     * @hide
     */
    public static final String ACTION_ONE_HANDED_SETTINGS =
            "android.settings.action.ONE_HANDED_SETTINGS";
    /**
     * The return values for {@link Settings.Config#set}
     * @hide
     */
    @IntDef(prefix = "SET_ALL_RESULT_",
            value = { SET_ALL_RESULT_FAILURE, SET_ALL_RESULT_SUCCESS, SET_ALL_RESULT_DISABLED })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface SetAllResult {}

    /**
     * A return value for {@link #KEY_CONFIG_SET_ALL_RETURN}, indicates failure.
     * @hide
     */
    public static final int SET_ALL_RESULT_FAILURE = 0;

    /**
     * A return value for {@link #KEY_CONFIG_SET_ALL_RETURN}, indicates success.
     * @hide
     */
    public static final int SET_ALL_RESULT_SUCCESS = 1;

    /**
     * A return value for {@link #KEY_CONFIG_SET_ALL_RETURN}, indicates a set all is disabled.
     * @hide
     */
    public static final int SET_ALL_RESULT_DISABLED = 2;

    /** @hide */
    public static final String KEY_CONFIG_SET_ALL_RETURN = "config_set_all_return";

    /** @hide */
    public static final String KEY_CONFIG_GET_SYNC_DISABLED_MODE_RETURN =
            "config_get_sync_disabled_mode_return";

    /**
     * An int extra specifying a subscription ID.
     *
     * @see android.telephony.SubscriptionInfo#getSubscriptionId
     */
    public static final String EXTRA_SUB_ID = "android.provider.extra.SUB_ID";

    /**
     * Activity Action: Modify Airplane mode settings using a voice command.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard against this.
     * <p>
     * This intent MUST be started using
     * {@link android.service.voice.VoiceInteractionSession#startVoiceActivity
     * startVoiceActivity}.
     * <p>
     * Note: The activity implementing this intent MUST verify that
     * {@link android.app.Activity#isVoiceInteraction isVoiceInteraction} returns true before
     * modifying the setting.
     * <p>
     * Input: To tell which state airplane mode should be set to, add the
     * {@link #EXTRA_AIRPLANE_MODE_ENABLED} extra to this Intent with the state specified.
     * If the extra is not included, no changes will be made.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VOICE_CONTROL_AIRPLANE_MODE =
            "android.settings.VOICE_CONTROL_AIRPLANE_MODE";

    /**
     * Activity Action: Show settings for accessibility modules.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ACCESSIBILITY_SETTINGS =
            "android.settings.ACCESSIBILITY_SETTINGS";

    /**
     * Activity Action: Show detail settings of a particular accessibility service.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard against this.
     * <p>
     * Input: {@link Intent#EXTRA_COMPONENT_NAME} must specify the accessibility service component
     * name to be shown.
     * <p>
     * Output: Nothing.
     * @hide
     **/
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of Reduce Bright Colors.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REDUCE_BRIGHT_COLORS_SETTINGS =
            "android.settings.REDUCE_BRIGHT_COLORS_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of Color correction.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_COLOR_CORRECTION_SETTINGS =
            "com.android.settings.ACCESSIBILITY_COLOR_SPACE_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of Color inversion.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_COLOR_INVERSION_SETTINGS =
            "android.settings.COLOR_INVERSION_SETTINGS";

    /**
     * Activity Action: Show settings to control access to usage information.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_USAGE_ACCESS_SETTINGS =
            "android.settings.USAGE_ACCESS_SETTINGS";

    /**
     * Activity Category: Show application settings related to usage access.
     * <p>
     * An activity that provides a user interface for adjusting usage access related
     * preferences for its containing application. Optional but recommended for apps that
     * use {@link android.Manifest.permission#PACKAGE_USAGE_STATS}.
     * <p>
     * The activity may define meta-data to describe what usage access is
     * used for within their app with {@link #METADATA_USAGE_ACCESS_REASON}, which
     * will be displayed in Settings.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String INTENT_CATEGORY_USAGE_ACCESS_CONFIG =
            "android.intent.category.USAGE_ACCESS_CONFIG";

    /**
     * Metadata key: Reason for needing usage access.
     * <p>
     * A key for metadata attached to an activity that receives action
     * {@link #INTENT_CATEGORY_USAGE_ACCESS_CONFIG}, shown to the
     * user as description of how the app uses usage access.
     * <p>
     */
    public static final String METADATA_USAGE_ACCESS_REASON =
            "android.settings.metadata.USAGE_ACCESS_REASON";

    /**
     * Activity Action: Show settings to allow configuration of security and
     * location privacy.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SECURITY_SETTINGS =
            "android.settings.SECURITY_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of trusted external sources
     *
     * Input: Optionally, the Intent's data URI can specify the application package name to
     * directly invoke the management GUI specific to the package name. For example
     * "package:com.my.app".
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_UNKNOWN_APP_SOURCES =
            "android.settings.MANAGE_UNKNOWN_APP_SOURCES";

    /**
     * Activity Action: Show settings to allow configuration of
     * {@link Manifest.permission#SCHEDULE_EXACT_ALARM} permission
     *
     * Input: Optionally, the Intent's data URI can specify the application package name to
     * directly invoke the management GUI specific to the package name. For example
     * "package:com.my.app".
     * <p>
     * Output: When a package data uri is passed as input, the activity result is set to
     * {@link android.app.Activity#RESULT_OK} if the permission was granted to the app. Otherwise,
     * the result is set to {@link android.app.Activity#RESULT_CANCELED}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_SCHEDULE_EXACT_ALARM =
            "android.settings.REQUEST_SCHEDULE_EXACT_ALARM";

    /**
     * Activity Action: Show settings to allow configuration of
     * {@link Manifest.permission#MANAGE_MEDIA} permission
     *
     * Input: Optionally, the Intent's data URI can specify the application package name to
     * directly invoke the management GUI specific to the package name. For example
     * "package:com.my.app".
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_MANAGE_MEDIA =
            "android.settings.REQUEST_MANAGE_MEDIA";

    /**
     * Activity Action: Show settings to allow configuration of cross-profile access for apps
     *
     * Input: Optionally, the Intent's data URI can specify the application package name to
     * directly invoke the management GUI specific to the package name. For example
     * "package:com.my.app".
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_CROSS_PROFILE_ACCESS =
            "android.settings.MANAGE_CROSS_PROFILE_ACCESS";

    /**
     * Activity Action: Show the "Open by Default" page in a particular application's details page.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard against this.
     * <p>
     * Input: The Intent's data URI specifies the application package name
     * to be shown, with the "package" scheme. That is "package:com.my.app".
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APP_OPEN_BY_DEFAULT_SETTINGS =
            "android.settings.APP_OPEN_BY_DEFAULT_SETTINGS";

    /**
     * Activity Action: Show trusted credentials settings, opening to the user tab,
     * to allow management of installed credentials.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @UnsupportedAppUsage
    public static final String ACTION_TRUSTED_CREDENTIALS_USER =
            "com.android.settings.TRUSTED_CREDENTIALS_USER";

    /**
     * Activity Action: Show dialog explaining that an installed CA cert may enable
     * monitoring of encrypted network traffic.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this. Add {@link #EXTRA_NUMBER_OF_CERTIFICATES} extra to indicate the
     * number of certificates.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MONITORING_CERT_INFO =
            "com.android.settings.MONITORING_CERT_INFO";

    /**
     * Activity Action: Show settings to allow configuration of privacy options.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PRIVACY_SETTINGS =
            "android.settings.PRIVACY_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of VPN.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VPN_SETTINGS =
            "android.settings.VPN_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of Wi-Fi.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_WIFI_SETTINGS =
            "android.settings.WIFI_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of a static IP
     * address for Wi-Fi.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_WIFI_IP_SETTINGS =
            "android.settings.WIFI_IP_SETTINGS";

    /**
     * Activity Action: Show setting page to process a Wi-Fi Easy Connect (aka DPP) URI and start
     * configuration. This intent should be used when you want to use this device to take on the
     * configurator role for an IoT/other device. When provided with a valid DPP URI
     * string, Settings will open a Wi-Fi selection screen for the user to indicate which network
     * they would like to configure the device specified in the DPP URI string and
     * carry them through the rest of the flow for provisioning the device.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure to safeguard against this by
     * checking {@link WifiManager#isEasyConnectSupported()}.
     * <p>
     * Input: The Intent's data URI specifies bootstrapping information for authenticating and
     * provisioning the peer, and uses a "DPP" scheme. The URI should be attached to the intent
     * using {@link Intent#setData(Uri)}. The calling app can obtain a DPP URI in any
     * way, e.g. by scanning a QR code or other out-of-band methods. The calling app may also
     * attach the {@link #EXTRA_EASY_CONNECT_BAND_LIST} extra to provide information
     * about the bands supported by the enrollee device.
     * <p>
     * Output: After calling {@link android.app.Activity#startActivityForResult}, the callback
     * {@code onActivityResult} will have resultCode {@link android.app.Activity#RESULT_OK} if
     * the Wi-Fi Easy Connect configuration succeeded and the user tapped the 'Done' button, or
     * {@link android.app.Activity#RESULT_CANCELED} if the operation failed and user tapped the
     * 'Cancel' button. In case the operation has failed, a status code from
     * {@link android.net.wifi.EasyConnectStatusCallback} {@code EASY_CONNECT_EVENT_FAILURE_*} will
     * be returned as an Extra {@link #EXTRA_EASY_CONNECT_ERROR_CODE}. Easy Connect R2
     * Enrollees report additional details about the error they encountered, which will be
     * provided in the {@link #EXTRA_EASY_CONNECT_ATTEMPTED_SSID},
     * {@link #EXTRA_EASY_CONNECT_CHANNEL_LIST}, and {@link #EXTRA_EASY_CONNECT_BAND_LIST}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROCESS_WIFI_EASY_CONNECT_URI =
            "android.settings.PROCESS_WIFI_EASY_CONNECT_URI";

    /**
     * Activity Extra: The Easy Connect operation error code
     * <p>
     * An extra returned on the result intent received when using the
     * {@link #ACTION_PROCESS_WIFI_EASY_CONNECT_URI} intent to launch the Easy Connect Operation.
     * This extra contains the integer error code of the operation - one of
     * {@link android.net.wifi.EasyConnectStatusCallback} {@code EASY_CONNECT_EVENT_FAILURE_*}. If
     * there is no error, i.e. if the operation returns {@link android.app.Activity#RESULT_OK},
     * then this extra is not attached to the result intent.
     * <p>
     * Use the {@link Intent#hasExtra(String)} to determine whether the extra is attached and
     * {@link Intent#getIntExtra(String, int)} to obtain the error code data.
     */
    public static final String EXTRA_EASY_CONNECT_ERROR_CODE =
            "android.provider.extra.EASY_CONNECT_ERROR_CODE";

    /**
     * Activity Extra: The SSID that the Enrollee tried to connect to.
     * <p>
     * An extra returned on the result intent received when using the {@link
     * #ACTION_PROCESS_WIFI_EASY_CONNECT_URI} intent to launch the Easy Connect Operation. This
     * extra contains the SSID of the Access Point that the remote Enrollee tried to connect to.
     * This value is populated only by remote R2 devices, and only for the following error codes:
     * {@link android.net.wifi.EasyConnectStatusCallback#EASY_CONNECT_EVENT_FAILURE_CANNOT_FIND_NETWORK}
     * {@link android.net.wifi.EasyConnectStatusCallback#EASY_CONNECT_EVENT_FAILURE_ENROLLEE_AUTHENTICATION}.
     * Therefore, always check if this extra is available using {@link Intent#hasExtra(String)}. If
     * there is no error, i.e. if the operation returns {@link android.app.Activity#RESULT_OK}, then
     * this extra is not attached to the result intent.
     * <p>
     * Use the {@link Intent#getStringExtra(String)} to obtain the SSID.
     */
    public static final String EXTRA_EASY_CONNECT_ATTEMPTED_SSID =
            "android.provider.extra.EASY_CONNECT_ATTEMPTED_SSID";

    /**
     * Activity Extra: The Channel List that the Enrollee used to scan a network.
     * <p>
     * An extra returned on the result intent received when using the {@link
     * #ACTION_PROCESS_WIFI_EASY_CONNECT_URI} intent to launch the Easy Connect Operation. This
     * extra contains the channel list that the Enrollee scanned for a network. This value is
     * populated only by remote R2 devices, and only for the following error code: {@link
     * android.net.wifi.EasyConnectStatusCallback#EASY_CONNECT_EVENT_FAILURE_CANNOT_FIND_NETWORK}.
     * Therefore, always check if this extra is available using {@link Intent#hasExtra(String)}. If
     * there is no error, i.e. if the operation returns {@link android.app.Activity#RESULT_OK}, then
     * this extra is not attached to the result intent. The list is JSON formatted, as an array
     * (Wi-Fi global operating classes) of arrays (Wi-Fi channels).
     * <p>
     * Use the {@link Intent#getStringExtra(String)} to obtain the list.
     */
    public static final String EXTRA_EASY_CONNECT_CHANNEL_LIST =
            "android.provider.extra.EASY_CONNECT_CHANNEL_LIST";

    /**
     * Activity Extra: The Band List that the Enrollee supports.
     * <p>
     * This extra contains the bands the Enrollee supports, expressed as the Global Operating
     * Class, see Table E-4 in IEEE Std 802.11-2016 Global operating classes. It is used both as
     * input, to configure the Easy Connect operation and as output of the operation.
     * <p>
     * As input: an optional extra to be attached to the
     * {@link #ACTION_PROCESS_WIFI_EASY_CONNECT_URI}. If attached, it indicates the bands which
     * the remote device (enrollee, device-to-be-configured) supports. The Settings operation
     * may take this into account when presenting the user with list of networks configurations
     * to be used. The calling app may obtain this information in any out-of-band method. The
     * information should be attached as an array of raw integers - using the
     * {@link Intent#putExtra(String, int[])}.
     * <p>
     * As output: an extra returned on the result intent received when using the
     * {@link #ACTION_PROCESS_WIFI_EASY_CONNECT_URI} intent to launch the Easy Connect Operation
     * . This value is populated only by remote R2 devices, and only for the following error
     * codes:
     * {@link android.net.wifi.EasyConnectStatusCallback#EASY_CONNECT_EVENT_FAILURE_CANNOT_FIND_NETWORK},
     * {@link android.net.wifi.EasyConnectStatusCallback#EASY_CONNECT_EVENT_FAILURE_ENROLLEE_AUTHENTICATION},
     * or
     * {@link android.net.wifi.EasyConnectStatusCallback#EASY_CONNECT_EVENT_FAILURE_ENROLLEE_REJECTED_CONFIGURATION}.
     * Therefore, always check if this extra is available using {@link Intent#hasExtra(String)}.
     * If there is no error, i.e. if the operation returns {@link android.app.Activity#RESULT_OK}
     * , then this extra is not attached to the result intent.
     * <p>
     * Use the {@link Intent#getIntArrayExtra(String)} to obtain the list.
     */
    public static final String EXTRA_EASY_CONNECT_BAND_LIST =
            "android.provider.extra.EASY_CONNECT_BAND_LIST";

    /**
     * Activity Action: Show settings to allow configuration of data and view data usage.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DATA_USAGE_SETTINGS =
            "android.settings.DATA_USAGE_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of Bluetooth.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_BLUETOOTH_SETTINGS =
            "android.settings.BLUETOOTH_SETTINGS";

    /**
     * Activity action: Show Settings app search UI when this action is available for device.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APP_SEARCH_SETTINGS = "android.settings.APP_SEARCH_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of Assist Gesture.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ASSIST_GESTURE_SETTINGS =
            "android.settings.ASSIST_GESTURE_SETTINGS";

    /**
     * Activity Action: Show settings to enroll fingerprints, and setup PIN/Pattern/Pass if
     * necessary.
     * @deprecated See {@link #ACTION_BIOMETRIC_ENROLL}.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_FINGERPRINT_ENROLL =
            "android.settings.FINGERPRINT_ENROLL";

    /**
     * Activity Action: Show settings to enroll biometrics, and setup PIN/Pattern/Pass if
     * necessary. By default, this prompts the user to enroll biometrics with strength
     * Weak or above, as defined by the CDD. Only biometrics that meet or exceed Strong, as defined
     * in the CDD are allowed to participate in Keystore operations.
     * <p>
     * Input: extras {@link #EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED} as an integer, with
     * constants defined in {@link android.hardware.biometrics.BiometricManager.Authenticators},
     * e.g. {@link android.hardware.biometrics.BiometricManager.Authenticators#BIOMETRIC_STRONG}.
     * If not specified, the default behavior is
     * {@link android.hardware.biometrics.BiometricManager.Authenticators#BIOMETRIC_WEAK}.
     * <p>
     * Output: Nothing. Note that callers should still check
     * {@link android.hardware.biometrics.BiometricManager#canAuthenticate(int)}
     * afterwards to ensure that the user actually completed enrollment.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_BIOMETRIC_ENROLL =
            "android.settings.BIOMETRIC_ENROLL";

    /**
     * Activity Extra: The minimum strength to request enrollment for.
     * <p>
     * This can be passed as an extra field to the {@link #ACTION_BIOMETRIC_ENROLL} intent to
     * indicate that only enrollment for sensors that meet these requirements should be shown. The
     * value should be a combination of the constants defined in
     * {@link android.hardware.biometrics.BiometricManager.Authenticators}.
     */
    public static final String EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED =
            "android.provider.extra.BIOMETRIC_AUTHENTICATORS_ALLOWED";

    /**
     * Activity Action: Show settings to allow configuration of cast endpoints.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CAST_SETTINGS =
            "android.settings.CAST_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of date and time.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DATE_SETTINGS =
            "android.settings.DATE_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of sound and volume.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SOUND_SETTINGS =
            "android.settings.SOUND_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of display.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DISPLAY_SETTINGS =
            "android.settings.DISPLAY_SETTINGS";

    /**
     * Activity Action: Show Auto Rotate configuration settings.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_AUTO_ROTATE_SETTINGS =
            "android.settings.AUTO_ROTATE_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of Night display.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NIGHT_DISPLAY_SETTINGS =
            "android.settings.NIGHT_DISPLAY_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of Dark theme.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DARK_THEME_SETTINGS =
            "android.settings.DARK_THEME_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of locale.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_LOCALE_SETTINGS =
            "android.settings.LOCALE_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of per application locale.
     * <p>
     * Input: The Intent's data URI can specify the application package name to directly invoke the
     * app locale details GUI specific to the package name.
     * For example "package:com.my.app".
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APP_LOCALE_SETTINGS =
            "android.settings.APP_LOCALE_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of lockscreen.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_LOCKSCREEN_SETTINGS = "android.settings.LOCK_SCREEN_SETTINGS";

    /**
     * Activity Action: Show settings to allow pairing bluetooth devices.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_BLUETOOTH_PAIRING_SETTINGS =
            "android.settings.BLUETOOTH_PAIRING_SETTINGS";

    /**
     * Activity Action: Show settings to configure input methods, in particular
     * allowing the user to enable input methods.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VOICE_INPUT_SETTINGS =
            "android.settings.VOICE_INPUT_SETTINGS";

    /**
     * Activity Action: Show settings to configure input methods, in particular
     * allowing the user to enable input methods.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_INPUT_METHOD_SETTINGS =
            "android.settings.INPUT_METHOD_SETTINGS";

    /**
     * Activity Action: Show settings to enable/disable input method subtypes.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * To tell which input method's subtypes are displayed in the settings, add
     * {@link #EXTRA_INPUT_METHOD_ID} extra to this Intent with the input method id.
     * If there is no extra in this Intent, subtypes from all installed input methods
     * will be displayed in the settings.
     *
     * @see android.view.inputmethod.InputMethodInfo#getId
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_INPUT_METHOD_SUBTYPE_SETTINGS =
            "android.settings.INPUT_METHOD_SUBTYPE_SETTINGS";

    /**
     * Activity Action: Show settings to manage the user input dictionary.
     * <p>
     * Starting with {@link android.os.Build.VERSION_CODES#KITKAT},
     * it is guaranteed there will always be an appropriate implementation for this Intent action.
     * In prior releases of the platform this was optional, so ensure you safeguard against it.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_USER_DICTIONARY_SETTINGS =
            "android.settings.USER_DICTIONARY_SETTINGS";

    /**
     * Activity Action: Show settings to configure the hardware keyboard.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_HARD_KEYBOARD_SETTINGS =
            "android.settings.HARD_KEYBOARD_SETTINGS";

    /**
     * Activity Action: Adds a word to the user dictionary.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: An extra with key <code>word</code> that contains the word
     * that should be added to the dictionary.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final String ACTION_USER_DICTIONARY_INSERT =
            "com.android.settings.USER_DICTIONARY_INSERT";

    /**
     * Activity Action: Show settings to allow configuration of application-related settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APPLICATION_SETTINGS =
            "android.settings.APPLICATION_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of application
     * development-related settings.  As of
     * {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1} this action is
     * a required part of the platform.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APPLICATION_DEVELOPMENT_SETTINGS =
            "android.settings.APPLICATION_DEVELOPMENT_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of quick launch shortcuts.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_QUICK_LAUNCH_SETTINGS =
            "android.settings.QUICK_LAUNCH_SETTINGS";

    /**
     * Activity Action: Show settings to manage installed applications.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_APPLICATIONS_SETTINGS =
            "android.settings.MANAGE_APPLICATIONS_SETTINGS";

    /**
     * Activity Action: Show settings to manage all applications.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS =
            "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS";

    /**
     * Activity Action: Show settings to manage all SIM profiles.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS =
            "android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS";

    /**
     * Activity Action: Show screen for controlling which apps can draw on top of other apps.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard against this.
     * <p>
     * Input: Optionally, in versions of Android prior to {@link android.os.Build.VERSION_CODES#R},
     * the Intent's data URI can specify the application package name to directly invoke the
     * management GUI specific to the package name.
     * For example "package:com.my.app".
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_OVERLAY_PERMISSION =
            "android.settings.action.MANAGE_OVERLAY_PERMISSION";

    /**
     * Activity Action: Show screen for controlling if the app specified in the data URI of the
     * intent can draw on top of other apps.
     * <p>
     * Unlike {@link #ACTION_MANAGE_OVERLAY_PERMISSION}, which in Android {@link
     * android.os.Build.VERSION_CODES#R} can't be used to show a GUI for a specific package,
     * permission {@code android.permission.INTERNAL_SYSTEM_WINDOW} is needed to start an activity
     * with this intent.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: The Intent's data URI MUST specify the application package name whose ability of
     * drawing on top of other apps you want to control.
     * For example "package:com.my.app".
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_APP_OVERLAY_PERMISSION =
            "android.settings.MANAGE_APP_OVERLAY_PERMISSION";

    /**
     * Activity Action: Show screen for controlling which apps are allowed to write/modify
     * system settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Optionally, the Intent's data URI can specify the application package name to
     * directly invoke the management GUI specific to the package name. For example
     * "package:com.my.app".
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_WRITE_SETTINGS =
            "android.settings.action.MANAGE_WRITE_SETTINGS";

    /**
     * Activity Action: Show screen for controlling app usage properties for an app.
     * Input: Intent's extra {@link android.content.Intent#EXTRA_PACKAGE_NAME} must specify the
     * application package name.
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APP_USAGE_SETTINGS =
            "android.settings.action.APP_USAGE_SETTINGS";

    /**
     * Activity Action: Show screen of details about a particular application.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: The Intent's data URI specifies the application package name
     * to be shown, with the "package" scheme.  That is "package:com.my.app".
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APPLICATION_DETAILS_SETTINGS =
            "android.settings.APPLICATION_DETAILS_SETTINGS";

    /**
     * Activity Action: Show list of applications that have been running
     * foreground services (to the user "running in the background").
     * <p>
     * Input: Extras "packages" is a string array of package names.
     * <p>
     * Output: Nothing.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_FOREGROUND_SERVICES_SETTINGS =
            "android.settings.FOREGROUND_SERVICES_SETTINGS";

    /**
     * Activity Action: Show screen for controlling which apps can ignore battery optimizations.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * <p>
     * You can use {@link android.os.PowerManager#isIgnoringBatteryOptimizations
     * PowerManager.isIgnoringBatteryOptimizations()} to determine if an application is
     * already ignoring optimizations.  You can use
     * {@link #ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS} to ask the user to put you
     * on this list.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS =
            "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS";

    /**
     * Activity Action: Ask the user to allow an app to ignore battery optimizations (that is,
     * put them on the whitelist of apps shown by
     * {@link #ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS}).  For an app to use this, it also
     * must hold the {@link android.Manifest.permission#REQUEST_IGNORE_BATTERY_OPTIMIZATIONS}
     * permission.
     * <p><b>Note:</b> most applications should <em>not</em> use this; there are many facilities
     * provided by the platform for applications to operate correctly in the various power
     * saving modes.  This is only for unusual applications that need to deeply control their own
     * execution, at the potential expense of the user's battery life.  Note that these applications
     * greatly run the risk of showing to the user as high power consumers on their device.</p>
     * <p>
     * Input: The Intent's data URI must specify the application package name
     * to be shown, with the "package" scheme.  That is "package:com.my.app".
     * <p>
     * Output: Nothing.
     * <p>
     * You can use {@link android.os.PowerManager#isIgnoringBatteryOptimizations
     * PowerManager.isIgnoringBatteryOptimizations()} to determine if an application is
     * already ignoring optimizations.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS =
            "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS";

    /**
     * Activity Action: Open the advanced power usage details page of an associated app.
     * <p>
     * Input: Intent's data URI set with an application name, using the
     * "package" schema (like "package:com.my.app")
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL =
            "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL";

    /**
     * Activity Action: Show screen for controlling background data
     * restrictions for a particular application.
     * <p>
     * Input: Intent's data URI set with an application name, using the
     * "package" schema (like "package:com.my.app").
     *
     * <p>
     * Output: Nothing.
     * <p>
     * Applications can also use {@link android.net.ConnectivityManager#getRestrictBackgroundStatus
     * ConnectivityManager#getRestrictBackgroundStatus()} to determine the
     * status of the background data restrictions for them.
     *
     * <p class="note">
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS =
            "android.settings.IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS";

    /**
     * @hide
     * Activity Action: Show the "app ops" settings screen.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APP_OPS_SETTINGS =
            "android.settings.APP_OPS_SETTINGS";

    /**
     * Activity Action: Show settings for system update functionality.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SYSTEM_UPDATE_SETTINGS =
            "android.settings.SYSTEM_UPDATE_SETTINGS";

    /**
     * Activity Action: Show settings for managed profile settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGED_PROFILE_SETTINGS =
            "android.settings.MANAGED_PROFILE_SETTINGS";

    /**
     * Activity Action: Show settings to allow configuration of sync settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * The account types available to add via the add account button may be restricted by adding an
     * {@link #EXTRA_AUTHORITIES} extra to this Intent with one or more syncable content provider's
     * authorities. Only account types which can sync with that content provider will be offered to
     * the user.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SYNC_SETTINGS =
            "android.settings.SYNC_SETTINGS";

    /**
     * Activity Action: Show add account screen for creating a new account.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * The account types available to add may be restricted by adding an {@link #EXTRA_AUTHORITIES}
     * extra to the Intent with one or more syncable content provider's authorities.  Only account
     * types which can sync with that content provider will be offered to the user.
     * <p>
     * Account types can also be filtered by adding an {@link #EXTRA_ACCOUNT_TYPES} extra to the
     * Intent with one or more account types.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ADD_ACCOUNT =
            "android.settings.ADD_ACCOUNT_SETTINGS";

    /**
     * Activity Action: Show settings for enabling or disabling data saver
     * <p></p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DATA_SAVER_SETTINGS =
            "android.settings.DATA_SAVER_SETTINGS";

    /**
     * Activity Action: Show settings for selecting the network operator.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * The subscription ID of the subscription for which available network operators should be
     * displayed may be optionally specified with {@link #EXTRA_SUB_ID}.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NETWORK_OPERATOR_SETTINGS =
            "android.settings.NETWORK_OPERATOR_SETTINGS";

    /**
     * Activity Action: Show settings for selection of 2G/3G.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DATA_ROAMING_SETTINGS =
            "android.settings.DATA_ROAMING_SETTINGS";

    /**
     * Activity Action: Show settings for internal storage.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_INTERNAL_STORAGE_SETTINGS =
            "android.settings.INTERNAL_STORAGE_SETTINGS";
    /**
     * Activity Action: Show settings for memory card storage.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MEMORY_CARD_SETTINGS =
            "android.settings.MEMORY_CARD_SETTINGS";

    /**
     * Activity Action: Show settings for global search.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SEARCH_SETTINGS =
        "android.search.action.SEARCH_SETTINGS";

    /**
     * Activity Action: Show general device information settings (serial
     * number, software version, phone number, etc.).
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DEVICE_INFO_SETTINGS =
        "android.settings.DEVICE_INFO_SETTINGS";

    /**
     * Activity Action: Show NFC settings.
     * <p>
     * This shows UI that allows NFC to be turned on or off.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing
     * @see android.nfc.NfcAdapter#isEnabled()
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NFC_SETTINGS = "android.settings.NFC_SETTINGS";

    /**
     * Activity Action: Show NFC Sharing settings.
     * <p>
     * This shows UI that allows NDEF Push (Android Beam) to be turned on or
     * off.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing
     * @see android.nfc.NfcAdapter#isNdefPushEnabled()
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NFCSHARING_SETTINGS =
        "android.settings.NFCSHARING_SETTINGS";

    /**
     * Activity Action: Show NFC Tap & Pay settings
     * <p>
     * This shows UI that allows the user to configure Tap&Pay
     * settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NFC_PAYMENT_SETTINGS =
        "android.settings.NFC_PAYMENT_SETTINGS";

    /**
     * Activity Action: Show Daydream settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * @see android.service.dreams.DreamService
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DREAM_SETTINGS = "android.settings.DREAM_SETTINGS";

    /**
     * Activity Action: Show Notification assistant settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * @see android.service.notification.NotificationAssistantService
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_ASSISTANT_SETTINGS =
            "android.settings.NOTIFICATION_ASSISTANT_SETTINGS";

    /**
     * Activity Action: Show Notification listener settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * @see android.service.notification.NotificationListenerService
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_LISTENER_SETTINGS
            = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";

    /**
     * Activity Action: Show notification listener permission settings page for app.
     * <p>
     * Users can grant and deny access to notifications for a {@link ComponentName} from here.
     * See
     * {@link android.app.NotificationManager#isNotificationListenerAccessGranted(ComponentName)}
     * for more details.
     * <p>
     * Input: The extra {@link #EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME} containing the name
     * of the component to grant or revoke notification listener access to.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS =
            "android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS";

    /**
     * Activity Extra: What component name to show the notification listener permission
     * page for.
     * <p>
     * A string extra containing a {@link ComponentName}. This must be passed as an extra field to
     * {@link #ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS}.
     */
    public static final String EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME =
            "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME";

    /**
     * Activity Action: Show Do Not Disturb access settings.
     * <p>
     * Users can grant and deny access to Do Not Disturb configuration from here. Managed
     * profiles cannot grant Do Not Disturb access.
     * See {@link android.app.NotificationManager#isNotificationPolicyAccessGranted()} for more
     * details.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * <p class="note">
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            = "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS";

    /**
     * Activity Action: Show do not disturb setting page for app.
     * <p>
     * Users can grant and deny access to Do Not Disturb configuration for an app from here.
     * See {@link android.app.NotificationManager#isNotificationPolicyAccessGranted()} for more
     * details.
     * <p>
     * Input: Intent's data URI set with an application name, using the
     * "package" schema (like "package:com.my.app").
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS =
            "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS";

    /**
     * Activity Action: Show the automatic do not disturb rule listing page
     * <p>
     *     Users can add, enable, disable, and remove automatic do not disturb rules from this
     *     screen. See {@link NotificationManager#addAutomaticZenRule(AutomaticZenRule)} for more
     *     details.
     * </p>
     * <p>
     *     Input: Nothing
     *     Output: Nothing
     * </p>
     *
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CONDITION_PROVIDER_SETTINGS
            = "android.settings.ACTION_CONDITION_PROVIDER_SETTINGS";

    /**
     * Activity Action: Show settings for video captioning.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CAPTIONING_SETTINGS = "android.settings.CAPTIONING_SETTINGS";

    /**
     * Activity Action: Show the top level print settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PRINT_SETTINGS =
            "android.settings.ACTION_PRINT_SETTINGS";

    /**
     * Activity Action: Show Zen Mode configuration settings.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ZEN_MODE_SETTINGS = "android.settings.ZEN_MODE_SETTINGS";

    /**
     * Activity Action: Show Zen Mode visual effects configuration settings.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ZEN_MODE_BLOCKED_EFFECTS_SETTINGS =
            "android.settings.ZEN_MODE_BLOCKED_EFFECTS_SETTINGS";

    /**
     * Activity Action: Show Zen Mode onboarding activity.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ZEN_MODE_ONBOARDING = "android.settings.ZEN_MODE_ONBOARDING";

    /**
     * Activity Action: Show Zen Mode (aka Do Not Disturb) priority configuration settings.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ZEN_MODE_PRIORITY_SETTINGS
            = "android.settings.ZEN_MODE_PRIORITY_SETTINGS";

    /**
     * Activity Action: Show Zen Mode automation configuration settings.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ZEN_MODE_AUTOMATION_SETTINGS
            = "android.settings.ZEN_MODE_AUTOMATION_SETTINGS";

    /**
     * Activity Action: Modify do not disturb mode settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard against this.
     * <p>
     * This intent MUST be started using
     * {@link android.service.voice.VoiceInteractionSession#startVoiceActivity
     * startVoiceActivity}.
     * <p>
     * Note: The Activity implementing this intent MUST verify that
     * {@link android.app.Activity#isVoiceInteraction isVoiceInteraction}.
     * returns true before modifying the setting.
     * <p>
     * Input: The optional {@link #EXTRA_DO_NOT_DISTURB_MODE_MINUTES} extra can be used to indicate
     * how long the user wishes to avoid interruptions for. The optional
     * {@link #EXTRA_DO_NOT_DISTURB_MODE_ENABLED} extra can be to indicate if the user is
     * enabling or disabling do not disturb mode. If either extra is not included, the
     * user maybe asked to provide the value.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VOICE_CONTROL_DO_NOT_DISTURB_MODE =
            "android.settings.VOICE_CONTROL_DO_NOT_DISTURB_MODE";

    /**
     * Activity Action: Show Zen Mode schedule rule configuration settings.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ZEN_MODE_SCHEDULE_RULE_SETTINGS
            = "android.settings.ZEN_MODE_SCHEDULE_RULE_SETTINGS";

    /**
     * Activity Action: Show Zen Mode event rule configuration settings.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ZEN_MODE_EVENT_RULE_SETTINGS
            = "android.settings.ZEN_MODE_EVENT_RULE_SETTINGS";

    /**
     * Activity Action: Show Zen Mode external rule configuration settings.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ZEN_MODE_EXTERNAL_RULE_SETTINGS
            = "android.settings.ZEN_MODE_EXTERNAL_RULE_SETTINGS";

    /**
     * Activity Action: Show the regulatory information screen for the device.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String
            ACTION_SHOW_REGULATORY_INFO = "android.settings.SHOW_REGULATORY_INFO";

    /**
     * Activity Action: Show Device Name Settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String DEVICE_NAME_SETTINGS = "android.settings.DEVICE_NAME";

    /**
     * Activity Action: Show pairing settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PAIRING_SETTINGS = "android.settings.PAIRING_SETTINGS";

    /**
     * Activity Action: Show battery saver settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_BATTERY_SAVER_SETTINGS
            = "android.settings.BATTERY_SAVER_SETTINGS";

    /**
     * Activity Action: Modify Battery Saver mode setting using a voice command.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard against this.
     * <p>
     * This intent MUST be started using
     * {@link android.service.voice.VoiceInteractionSession#startVoiceActivity
     * startVoiceActivity}.
     * <p>
     * Note: The activity implementing this intent MUST verify that
     * {@link android.app.Activity#isVoiceInteraction isVoiceInteraction} returns true before
     * modifying the setting.
     * <p>
     * Input: To tell which state batter saver mode should be set to, add the
     * {@link #EXTRA_BATTERY_SAVER_MODE_ENABLED} extra to this Intent with the state specified.
     * If the extra is not included, no changes will be made.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VOICE_CONTROL_BATTERY_SAVER_MODE =
            "android.settings.VOICE_CONTROL_BATTERY_SAVER_MODE";

    /**
     * Activity Action: Show Home selection settings. If there are multiple activities
     * that can satisfy the {@link Intent#CATEGORY_HOME} intent, this screen allows you
     * to pick your preferred activity.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_HOME_SETTINGS
            = "android.settings.HOME_SETTINGS";

    /**
     * Activity Action: Show Default apps settings.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_DEFAULT_APPS_SETTINGS
            = "android.settings.MANAGE_DEFAULT_APPS_SETTINGS";

    /**
     * Activity Action: Show More default apps settings.
     * <p>
     * If a Settings activity handles this intent action, a "More defaults" entry will be shown in
     * the Default apps settings, and clicking it will launch that activity.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_MANAGE_MORE_DEFAULT_APPS_SETTINGS =
            "android.settings.MANAGE_MORE_DEFAULT_APPS_SETTINGS";

    /**
     * Activity Action: Show notification settings.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_SETTINGS
            = "android.settings.NOTIFICATION_SETTINGS";

    /**
     * Activity Action: Show conversation settings.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CONVERSATION_SETTINGS
            = "android.settings.CONVERSATION_SETTINGS";

    /**
     * Activity Action: Show notification history screen.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_HISTORY
            = "android.settings.NOTIFICATION_HISTORY";

    /**
     * Activity Action: Show app listing settings, filtered by those that send notifications.
     *
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ALL_APPS_NOTIFICATION_SETTINGS =
            "android.settings.ALL_APPS_NOTIFICATION_SETTINGS";

    /**
     * Activity Action: Show app settings specifically for sending notifications. Same as
     * ALL_APPS_NOTIFICATION_SETTINGS but meant for internal use.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ALL_APPS_NOTIFICATION_SETTINGS_FOR_REVIEW =
            "android.settings.ALL_APPS_NOTIFICATION_SETTINGS_FOR_REVIEW";

    /**
     * Activity Action: Show notification settings for a single app.
     * <p>
     *     Input: {@link #EXTRA_APP_PACKAGE}, the package to display.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APP_NOTIFICATION_SETTINGS
            = "android.settings.APP_NOTIFICATION_SETTINGS";

    /**
     * Activity Action: Show notification settings for a single {@link NotificationChannel}.
     * <p>
     *     Input: {@link #EXTRA_APP_PACKAGE}, the package containing the channel to display.
     *     Input: {@link #EXTRA_CHANNEL_ID}, the id of the channel to display.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CHANNEL_NOTIFICATION_SETTINGS
            = "android.settings.CHANNEL_NOTIFICATION_SETTINGS";

    /**
     * Activity Action: Show notification bubble settings for a single app.
     * See {@link NotificationManager#getBubblePreference()}.
     * <p>
     *     Input: {@link #EXTRA_APP_PACKAGE}, the package to display.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS
            = "android.settings.APP_NOTIFICATION_BUBBLE_SETTINGS";

    /**
     * Activity Extra: The package owner of the notification channel settings to display.
     * <p>
     * This must be passed as an extra field to the {@link #ACTION_CHANNEL_NOTIFICATION_SETTINGS}.
     */
    public static final String EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE";

    /**
     * Activity Extra: The {@link NotificationChannel#getId()} of the notification channel settings
     * to display.
     * <p>
     * This must be passed as an extra field to the {@link #ACTION_CHANNEL_NOTIFICATION_SETTINGS}.
     */
    public static final String EXTRA_CHANNEL_ID = "android.provider.extra.CHANNEL_ID";

    /**
     * Activity Extra: The {@link NotificationChannel#getConversationId()} of the notification
     * conversation settings to display.
     * <p>
     * This is an optional extra field to the {@link #ACTION_CHANNEL_NOTIFICATION_SETTINGS}. If
     * included the system will first look up notification settings by channel and conversation id,
     * and will fall back to channel id if a specialized channel for this conversation doesn't
     * exist, similar to {@link NotificationManager#getNotificationChannel(String, String)}.
     */
    public static final String EXTRA_CONVERSATION_ID = "android.provider.extra.CONVERSATION_ID";

    /**
     * Activity Extra: An {@code Arraylist<String>} of {@link NotificationChannel} field names to
     * show on the Settings UI.
     *
     * <p>
     * This is an optional extra field to the {@link #ACTION_CHANNEL_NOTIFICATION_SETTINGS}. If
     * included the system will filter out any Settings that doesn't appear in this list that
     * otherwise would display.
     */
    public static final String EXTRA_CHANNEL_FILTER_LIST
            = "android.provider.extra.CHANNEL_FILTER_LIST";

    /**
     * Activity Action: Show notification redaction settings.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APP_NOTIFICATION_REDACTION
            = "android.settings.ACTION_APP_NOTIFICATION_REDACTION";

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final String EXTRA_APP_UID = "app_uid";

    /**
     * Activity Action: Show power menu settings.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_POWER_MENU_SETTINGS =
            "android.settings.ACTION_POWER_MENU_SETTINGS";

    /**
     * Activity Action: Show controls settings.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DEVICE_CONTROLS_SETTINGS =
            "android.settings.ACTION_DEVICE_CONTROLS_SETTINGS";

    /**
     * Activity Action: Show media control settings
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MEDIA_CONTROLS_SETTINGS =
            "android.settings.ACTION_MEDIA_CONTROLS_SETTINGS";

    /**
     * Activity Action: Show a dialog with disabled by policy message.
     * <p> If an user action is disabled by policy, this dialog can be triggered to let
     * the user know about this.
     * <p>
     * Input: {@link Intent#EXTRA_USER}: The user of the admin.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    // Intent#EXTRA_USER_ID can also be used
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_ADMIN_SUPPORT_DETAILS =
            "android.settings.SHOW_ADMIN_SUPPORT_DETAILS";

    /**
     * Intent extra: The id of a setting restricted by supervisors.
     * <p>
     * Type: Integer with a value from the one of the SUPERVISOR_VERIFICATION_* constants below.
     * <ul>
     * <li>{@see #SUPERVISOR_VERIFICATION_SETTING_UNKNOWN}
     * <li>{@see #SUPERVISOR_VERIFICATION_SETTING_BIOMETRICS}
     * </ul>
     * </p>
     */
    public static final String EXTRA_SUPERVISOR_RESTRICTED_SETTING_KEY =
            "android.provider.extra.SUPERVISOR_RESTRICTED_SETTING_KEY";

    /**
     * The unknown setting can usually be ignored and is used for compatibility with future
     * supervisor settings.
     */
    public static final int SUPERVISOR_VERIFICATION_SETTING_UNKNOWN = 0;

    /**
     * Settings for supervisors to control what kinds of biometric sensors, such a face and
     * fingerprint scanners, can be used on the device.
     */
    public static final int SUPERVISOR_VERIFICATION_SETTING_BIOMETRICS = 1;

    /**
     * Keys for {@link #EXTRA_SUPERVISOR_RESTRICTED_SETTING_KEY}.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SUPERVISOR_VERIFICATION_SETTING_" }, value = {
            SUPERVISOR_VERIFICATION_SETTING_UNKNOWN,
            SUPERVISOR_VERIFICATION_SETTING_BIOMETRICS,
    })
    public @interface SupervisorVerificationSetting {}

    /**
     * Activity action: Launch UI to manage a setting restricted by supervisors.
     * <p>
     * Input: {@link #EXTRA_SUPERVISOR_RESTRICTED_SETTING_KEY} specifies what setting to open.
     * </p>
     * <p>
     * Output: Nothing.
     * </p>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_SUPERVISOR_RESTRICTED_SETTING =
            "android.settings.MANAGE_SUPERVISOR_RESTRICTED_SETTING";

    /**
     * Activity Action: Show a dialog for remote bugreport flow.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_REMOTE_BUGREPORT_DIALOG
            = "android.settings.SHOW_REMOTE_BUGREPORT_DIALOG";

    /**
     * Activity Action: Show VR listener settings.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @see android.service.vr.VrListenerService
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VR_LISTENER_SETTINGS
            = "android.settings.VR_LISTENER_SETTINGS";

    /**
     * Activity Action: Show Picture-in-picture settings.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PICTURE_IN_PICTURE_SETTINGS
            = "android.settings.PICTURE_IN_PICTURE_SETTINGS";

    /**
     * Activity Action: Show Storage Manager settings.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_STORAGE_MANAGER_SETTINGS
            = "android.settings.STORAGE_MANAGER_SETTINGS";

    /**
     * Activity Action: Allows user to select current webview implementation.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * <p class="note">
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.

     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_WEBVIEW_SETTINGS = "android.settings.WEBVIEW_SETTINGS";

    /**
     * Activity Action: Show enterprise privacy section.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ENTERPRISE_PRIVACY_SETTINGS
            = "android.settings.ENTERPRISE_PRIVACY_SETTINGS";

    /**
     * Activity Action: Show Work Policy info.
     * DPC apps can implement an activity that handles this intent in order to show device policies
     * associated with the work profile or managed device.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_WORK_POLICY_INFO =
            "android.settings.SHOW_WORK_POLICY_INFO";

    /**
     * Activity Action: Show screen that let user select its Autofill Service.
     * <p>
     * Input: Intent's data URI set with an application name, using the
     * "package" schema (like "package:com.my.app").
     *
     * <p>
     * Output: {@link android.app.Activity#RESULT_OK} if user selected an Autofill Service belonging
     * to the caller package.
     *
     * <p>
     * <b>NOTE: </b> Applications should call
     * {@link android.view.autofill.AutofillManager#hasEnabledAutofillServices()} and
     * {@link android.view.autofill.AutofillManager#isAutofillSupported()}, and only use this action
     * to start an activity if they return {@code false} and {@code true} respectively.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_SET_AUTOFILL_SERVICE =
            "android.settings.REQUEST_SET_AUTOFILL_SERVICE";

    /**
     * Activity Action: Show screen for controlling the Quick Access Wallet.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_QUICK_ACCESS_WALLET_SETTINGS =
            "android.settings.QUICK_ACCESS_WALLET_SETTINGS";

    /**
     * Activity Action: Show screen for controlling which apps have access on volume directories.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     * <p>
     * Applications typically use this action to ask the user to revert the "Do not ask again"
     * status of directory access requests made by
     * {@link android.os.storage.StorageVolume#createAccessIntent(String)}.
     * @deprecated use {@link #ACTION_APPLICATION_DETAILS_SETTINGS} to manage storage permissions
     *             for a specific application
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @Deprecated
    public static final String ACTION_STORAGE_VOLUME_ACCESS_SETTINGS =
            "android.settings.STORAGE_VOLUME_ACCESS_SETTINGS";


    /**
     * Activity Action: Show screen that let user select enable (or disable) Content Capture.
     * <p>
     * Input: Nothing.
     *
     * <p>
     * Output: Nothing
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_ENABLE_CONTENT_CAPTURE =
            "android.settings.REQUEST_ENABLE_CONTENT_CAPTURE";

    /**
     * Activity Action: Show screen that let user manage how Android handles URL resolution.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_DOMAIN_URLS = "android.settings.MANAGE_DOMAIN_URLS";

    /**
     * Activity Action: Show screen that let user select enable (or disable) tethering.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_TETHER_SETTINGS = "android.settings.TETHER_SETTINGS";

    /**
     * Activity Action: Show screen that lets user configure wifi tethering.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard against this.
     * <p>
     * Input: Nothing
     * <p>
     * Output: Nothing
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_WIFI_TETHER_SETTING =
            "com.android.settings.WIFI_TETHER_SETTINGS";

    /**
     * Broadcast to trigger notification of asking user to enable MMS.
     * Need to specify {@link #EXTRA_ENABLE_MMS_DATA_REQUEST_REASON} and {@link #EXTRA_SUB_ID}.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ENABLE_MMS_DATA_REQUEST =
            "android.settings.ENABLE_MMS_DATA_REQUEST";

    /**
     * Shows restrict settings dialog when settings is blocked.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SHOW_RESTRICTED_SETTING_DIALOG =
            "android.settings.SHOW_RESTRICTED_SETTING_DIALOG";

    /**
     * Integer value that specifies the reason triggering enable MMS data notification.
     * This must be passed as an extra field to the {@link #ACTION_ENABLE_MMS_DATA_REQUEST}.
     * Extra with value of EnableMmsDataReason interface.
     * @hide
     */
    public static final String EXTRA_ENABLE_MMS_DATA_REQUEST_REASON =
            "android.settings.extra.ENABLE_MMS_DATA_REQUEST_REASON";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "ENABLE_MMS_DATA_REQUEST_REASON_" }, value = {
            ENABLE_MMS_DATA_REQUEST_REASON_INCOMING_MMS,
            ENABLE_MMS_DATA_REQUEST_REASON_OUTGOING_MMS,
    })
    public @interface EnableMmsDataReason{}

    /**
     * Requesting to enable MMS data because there's an incoming MMS.
     * @hide
     */
    public static final int ENABLE_MMS_DATA_REQUEST_REASON_INCOMING_MMS = 0;

    /**
     * Requesting to enable MMS data because user is sending MMS.
     * @hide
     */
    public static final int ENABLE_MMS_DATA_REQUEST_REASON_OUTGOING_MMS = 1;

    /**
     * Activity Action: Show screen of a cellular subscription and highlight the
     * "enable MMS" toggle.
     * <p>
     * Input: {@link #EXTRA_SUB_ID}: Sub ID of the subscription.
     * <p>
     * Output: Nothing
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MMS_MESSAGE_SETTING = "android.settings.MMS_MESSAGE_SETTING";

    /**
     * Activity Action: Show a screen of bedtime settings, which is provided by the wellbeing app.
     * <p>
     * The handler of this intent action may not exist.
     * <p>
     * To start an activity with this intent, apps should set the wellbeing package explicitly in
     * the intent together with this action. The wellbeing package is defined in
     * {@code com.android.internal.R.string.config_defaultWellbeingPackage}.
     * <p>
     * Output: Nothing
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_BEDTIME_SETTINGS = "android.settings.BEDTIME_SETTINGS";

    // End of Intent actions for Settings

    /**
     * @hide - Private call() method on SettingsProvider to read from 'system' table.
     */
    public static final String CALL_METHOD_GET_SYSTEM = "GET_system";

    /**
     * @hide - Private call() method on SettingsProvider to read from 'secure' table.
     */
    public static final String CALL_METHOD_GET_SECURE = "GET_secure";

    /**
     * @hide - Private call() method on SettingsProvider to read from 'global' table.
     */
    public static final String CALL_METHOD_GET_GLOBAL = "GET_global";

    /**
     * @hide - Private call() method on SettingsProvider to read from 'config' table.
     */
    public static final String CALL_METHOD_GET_CONFIG = "GET_config";

    /**
     * @hide - Specifies that the caller of the fast-path call()-based flow tracks
     * the settings generation in order to cache values locally. If this key is
     * mapped to a <code>null</code> string extra in the request bundle, the response
     * bundle will contain the same key mapped to a parcelable extra which would be
     * an {@link android.util.MemoryIntArray}. The response will also contain an
     * integer mapped to the {@link #CALL_METHOD_GENERATION_INDEX_KEY} which is the
     * index in the array clients should use to lookup the generation. For efficiency
     * the caller should request the generation tracking memory array only if it
     * doesn't already have it.
     *
     * @see #CALL_METHOD_GENERATION_INDEX_KEY
     */
    public static final String CALL_METHOD_TRACK_GENERATION_KEY = "_track_generation";

    /**
     * @hide Key with the location in the {@link android.util.MemoryIntArray} where
     * to look up the generation id of the backing table. The value is an integer.
     *
     * @see #CALL_METHOD_TRACK_GENERATION_KEY
     */
    public static final String CALL_METHOD_GENERATION_INDEX_KEY = "_generation_index";

    /**
     * @hide Key with the settings table generation. The value is an integer.
     *
     * @see #CALL_METHOD_TRACK_GENERATION_KEY
     */
    public static final String CALL_METHOD_GENERATION_KEY = "_generation";

    /**
     * @hide - User handle argument extra to the fast-path call()-based requests
     */
    public static final String CALL_METHOD_USER_KEY = "_user";

    /**
     * @hide - Boolean argument extra to the fast-path call()-based requests
     */
    public static final String CALL_METHOD_MAKE_DEFAULT_KEY = "_make_default";

    /**
     * @hide - User handle argument extra to the fast-path call()-based requests
     */
    public static final String CALL_METHOD_RESET_MODE_KEY = "_reset_mode";

    /**
     * @hide - String argument extra to the fast-path call()-based requests
     */
    public static final String CALL_METHOD_TAG_KEY = "_tag";

    /**
     * @hide - String argument extra to the fast-path call()-based requests
     */
    public static final String CALL_METHOD_PREFIX_KEY = "_prefix";

    /**
     * @hide - String argument extra to the fast-path call()-based requests
     */
    public static final String CALL_METHOD_SYNC_DISABLED_MODE_KEY = "_disabled_mode";

    /**
     * @hide - RemoteCallback monitor callback argument extra to the fast-path call()-based requests
     */
    public static final String CALL_METHOD_MONITOR_CALLBACK_KEY = "_monitor_callback_key";

    /**
     * @hide - String argument extra to the fast-path call()-based requests
     */
    public static final String CALL_METHOD_FLAGS_KEY = "_flags";

    /**
     * @hide - String argument extra to the fast-path call()-based requests
     */
    public static final String CALL_METHOD_OVERRIDEABLE_BY_RESTORE_KEY = "_overrideable_by_restore";

    /** @hide - Private call() method to write to 'system' table */
    public static final String CALL_METHOD_PUT_SYSTEM = "PUT_system";

    /** @hide - Private call() method to write to 'secure' table */
    public static final String CALL_METHOD_PUT_SECURE = "PUT_secure";

    /** @hide - Private call() method to write to 'global' table */
    public static final String CALL_METHOD_PUT_GLOBAL= "PUT_global";

    /** @hide - Private call() method to write to 'configuration' table */
    public static final String CALL_METHOD_PUT_CONFIG = "PUT_config";

    /** @hide - Private call() method to write to and delete from the 'configuration' table */
    public static final String CALL_METHOD_SET_ALL_CONFIG = "SET_ALL_config";

    /** @hide - Private call() method to delete from the 'system' table */
    public static final String CALL_METHOD_DELETE_SYSTEM = "DELETE_system";

    /** @hide - Private call() method to delete from the 'secure' table */
    public static final String CALL_METHOD_DELETE_SECURE = "DELETE_secure";

    /** @hide - Private call() method to delete from the 'global' table */
    public static final String CALL_METHOD_DELETE_GLOBAL = "DELETE_global";

    /** @hide - Private call() method to reset to defaults the 'configuration' table */
    public static final String CALL_METHOD_DELETE_CONFIG = "DELETE_config";

    /** @hide - Private call() method to reset to defaults the 'secure' table */
    public static final String CALL_METHOD_RESET_SECURE = "RESET_secure";

    /** @hide - Private call() method to reset to defaults the 'global' table */
    public static final String CALL_METHOD_RESET_GLOBAL = "RESET_global";

    /** @hide - Private call() method to reset to defaults the 'configuration' table */
    public static final String CALL_METHOD_RESET_CONFIG = "RESET_config";

    /** @hide - Private call() method to query the 'system' table */
    public static final String CALL_METHOD_LIST_SYSTEM = "LIST_system";

    /** @hide - Private call() method to query the 'secure' table */
    public static final String CALL_METHOD_LIST_SECURE = "LIST_secure";

    /** @hide - Private call() method to query the 'global' table */
    public static final String CALL_METHOD_LIST_GLOBAL = "LIST_global";

    /** @hide - Private call() method to reset to defaults the 'configuration' table */
    public static final String CALL_METHOD_LIST_CONFIG = "LIST_config";

    /** @hide - Private call() method to disable / re-enable syncs to the 'configuration' table */
    public static final String CALL_METHOD_SET_SYNC_DISABLED_MODE_CONFIG =
            "SET_SYNC_DISABLED_MODE_config";

    /**
     * @hide - Private call() method to return the current mode of sync disabling for the
     * 'configuration' table
     */
    public static final String CALL_METHOD_GET_SYNC_DISABLED_MODE_CONFIG =
            "GET_SYNC_DISABLED_MODE_config";

    /** @hide - Private call() method to register monitor callback for 'configuration' table */
    public static final String CALL_METHOD_REGISTER_MONITOR_CALLBACK_CONFIG =
            "REGISTER_MONITOR_CALLBACK_config";

    /** @hide - String argument extra to the config monitor callback */
    public static final String EXTRA_MONITOR_CALLBACK_TYPE = "monitor_callback_type";

    /** @hide - String argument extra to the config monitor callback */
    public static final String EXTRA_ACCESS_CALLBACK = "access_callback";

    /** @hide - String argument extra to the config monitor callback */
    public static final String EXTRA_NAMESPACE_UPDATED_CALLBACK =
            "namespace_updated_callback";

    /** @hide - String argument extra to the config monitor callback */
    public static final String EXTRA_NAMESPACE = "namespace";

    /** @hide - String argument extra to the config monitor callback */
    public static final String EXTRA_CALLING_PACKAGE = "calling_package";

    /**
     * Activity Extra: Limit available options in launched activity based on the given authority.
     * <p>
     * This can be passed as an extra field in an Activity Intent with one or more syncable content
     * provider's authorities as a String[]. This field is used by some intents to alter the
     * behavior of the called activity.
     * <p>
     * Example: The {@link #ACTION_ADD_ACCOUNT} intent restricts the account types available based
     * on the authority given.
     */
    public static final String EXTRA_AUTHORITIES = "authorities";

    /**
     * Activity Extra: Limit available options in launched activity based on the given account
     * types.
     * <p>
     * This can be passed as an extra field in an Activity Intent with one or more account types
     * as a String[]. This field is used by some intents to alter the behavior of the called
     * activity.
     * <p>
     * Example: The {@link #ACTION_ADD_ACCOUNT} intent restricts the account types to the specified
     * list.
     */
    public static final String EXTRA_ACCOUNT_TYPES = "account_types";

    public static final String EXTRA_INPUT_METHOD_ID = "input_method_id";

    /**
     * Activity Extra: The device identifier to act upon.
     * <p>
     * This can be passed as an extra field in an Activity Intent with a single
     * InputDeviceIdentifier. This field is used by some activities to jump straight into the
     * settings for the given device.
     * <p>
     * Example: The {@link #ACTION_INPUT_METHOD_SETTINGS} intent opens the keyboard layout
     * dialog for the given device.
     * @hide
     */
    public static final String EXTRA_INPUT_DEVICE_IDENTIFIER = "input_device_identifier";

    /**
     * Activity Extra: Enable or disable Airplane Mode.
     * <p>
     * This can be passed as an extra field to the {@link #ACTION_VOICE_CONTROL_AIRPLANE_MODE}
     * intent as a boolean to indicate if it should be enabled.
     */
    public static final String EXTRA_AIRPLANE_MODE_ENABLED = "airplane_mode_enabled";

    /**
     * Activity Extra: Enable or disable Battery saver mode.
     * <p>
     * This can be passed as an extra field to the {@link #ACTION_VOICE_CONTROL_BATTERY_SAVER_MODE}
     * intent as a boolean to indicate if it should be enabled.
     */
    public static final String EXTRA_BATTERY_SAVER_MODE_ENABLED =
            "android.settings.extra.battery_saver_mode_enabled";

    /**
     * Activity Extra: Enable or disable Do Not Disturb mode.
     * <p>
     * This can be passed as an extra field to the {@link #ACTION_VOICE_CONTROL_DO_NOT_DISTURB_MODE}
     * intent as a boolean to indicate if it should be enabled.
     */
    public static final String EXTRA_DO_NOT_DISTURB_MODE_ENABLED =
            "android.settings.extra.do_not_disturb_mode_enabled";

    /**
     * Activity Extra: How many minutes to enable do not disturb mode for.
     * <p>
     * This can be passed as an extra field to the {@link #ACTION_VOICE_CONTROL_DO_NOT_DISTURB_MODE}
     * intent to indicate how long do not disturb mode should be enabled for.
     */
    public static final String EXTRA_DO_NOT_DISTURB_MODE_MINUTES =
            "android.settings.extra.do_not_disturb_mode_minutes";

    /**
     * Reset mode: reset to defaults only settings changed by the
     * calling package. If there is a default set the setting
     * will be set to it, otherwise the setting will be deleted.
     * This is the only type of reset available to non-system clients.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    public static final int RESET_MODE_PACKAGE_DEFAULTS = 1;

    /**
     * Reset mode: reset all settings set by untrusted packages, which is
     * packages that aren't a part of the system, to the current defaults.
     * If there is a default set the setting will be set to it, otherwise
     * the setting will be deleted. This mode is only available to the system.
     * @hide
     */
    public static final int RESET_MODE_UNTRUSTED_DEFAULTS = 2;

    /**
     * Reset mode: delete all settings set by untrusted packages, which is
     * packages that aren't a part of the system. If a setting is set by an
     * untrusted package it will be deleted if its default is not provided
     * by the system, otherwise the setting will be set to its default.
     * This mode is only available to the system.
     * @hide
     */
    public static final int RESET_MODE_UNTRUSTED_CHANGES = 3;

    /**
     * Reset mode: reset all settings to defaults specified by trusted
     * packages, which is packages that are a part of the system, and
     * delete all settings set by untrusted packages. If a setting has
     * a default set by a system package it will be set to the default,
     * otherwise the setting will be deleted. This mode is only available
     * to the system.
     * @hide
     */
    public static final int RESET_MODE_TRUSTED_DEFAULTS = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "RESET_MODE_" }, value = {
            RESET_MODE_PACKAGE_DEFAULTS,
            RESET_MODE_UNTRUSTED_DEFAULTS,
            RESET_MODE_UNTRUSTED_CHANGES,
            RESET_MODE_TRUSTED_DEFAULTS
    })
    public @interface ResetMode{}

    /**
     * Activity Extra: Number of certificates
     * <p>
     * This can be passed as an extra field to the {@link #ACTION_MONITORING_CERT_INFO}
     * intent to indicate the number of certificates
     * @hide
     */
    public static final String EXTRA_NUMBER_OF_CERTIFICATES =
            "android.settings.extra.number_of_certificates";

    private static final String SYSTEM_PACKAGE_NAME = "android";

    public static final String AUTHORITY = "settings";

    private static final String TAG = "Settings";
    private static final boolean LOCAL_LOGV = false;

    // Used in system server calling uid workaround in call()
    private static boolean sInSystemServer = false;
    private static final Object sInSystemServerLock = new Object();

    /** @hide */
    public static void setInSystemServer() {
        synchronized (sInSystemServerLock) {
            sInSystemServer = true;
        }
    }

    /** @hide */
    public static boolean isInSystemServer() {
        synchronized (sInSystemServerLock) {
            return sInSystemServer;
        }
    }

    public static class SettingNotFoundException extends AndroidException {
        public SettingNotFoundException(String msg) {
            super(msg);
        }
    }

    /**
     * Common base for tables of name/value settings.
     */
    public static class NameValueTable implements BaseColumns {
        public static final String NAME = "name";
        public static final String VALUE = "value";
        // A flag indicating whether the current value of a setting should be preserved during
        // restore.
        /** @hide */
        public static final String IS_PRESERVED_IN_RESTORE = "is_preserved_in_restore";

        protected static boolean putString(ContentResolver resolver, Uri uri,
                String name, String value) {
            // The database will take care of replacing duplicates.
            try {
                ContentValues values = new ContentValues();
                values.put(NAME, name);
                values.put(VALUE, value);
                resolver.insert(uri, values);
                return true;
            } catch (SQLException e) {
                Log.w(TAG, "Can't set key " + name + " in " + uri, e);
                return false;
            }
        }

        public static Uri getUriFor(Uri uri, String name) {
            return Uri.withAppendedPath(uri, name);
        }
    }

    private static final class GenerationTracker {
        private final MemoryIntArray mArray;
        private final Runnable mErrorHandler;
        private final int mIndex;
        private int mCurrentGeneration;

        public GenerationTracker(@NonNull MemoryIntArray array, int index,
                int generation, Runnable errorHandler) {
            mArray = array;
            mIndex = index;
            mErrorHandler = errorHandler;
            mCurrentGeneration = generation;
        }

        public boolean isGenerationChanged() {
            final int currentGeneration = readCurrentGeneration();
            if (currentGeneration >= 0) {
                if (currentGeneration == mCurrentGeneration) {
                    return false;
                }
                mCurrentGeneration = currentGeneration;
            }
            return true;
        }

        public int getCurrentGeneration() {
            return mCurrentGeneration;
        }

        private int readCurrentGeneration() {
            try {
                return mArray.get(mIndex);
            } catch (IOException e) {
                Log.e(TAG, "Error getting current generation", e);
                if (mErrorHandler != null) {
                    mErrorHandler.run();
                }
            }
            return -1;
        }

        public void destroy() {
            try {
                mArray.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing backing array", e);
                if (mErrorHandler != null) {
                    mErrorHandler.run();
                }
            }
        }
    }

    private static final class ContentProviderHolder {
        private final Object mLock = new Object();

        private final Uri mUri;
        @GuardedBy("mLock")
        @UnsupportedAppUsage
        private IContentProvider mContentProvider;

        public ContentProviderHolder(Uri uri) {
            mUri = uri;
        }

        public IContentProvider getProvider(ContentResolver contentResolver) {
            synchronized (mLock) {
                if (mContentProvider == null) {
                    mContentProvider = contentResolver
                            .acquireProvider(mUri.getAuthority());
                }
                return mContentProvider;
            }
        }

        public void clearProviderForTest() {
            synchronized (mLock) {
                mContentProvider = null;
            }
        }
    }

    // Thread-safe.
    private static class NameValueCache {
        private static final boolean DEBUG = false;

        private static final String[] SELECT_VALUE_PROJECTION = new String[] {
                Settings.NameValueTable.VALUE
        };

        private static final String NAME_EQ_PLACEHOLDER = "name=?";

        // Must synchronize on 'this' to access mValues and mValuesVersion.
        private final ArrayMap<String, String> mValues = new ArrayMap<>();

        private final Uri mUri;
        @UnsupportedAppUsage
        private final ContentProviderHolder mProviderHolder;

        // The method we'll call (or null, to not use) on the provider
        // for the fast path of retrieving settings.
        private final String mCallGetCommand;
        private final String mCallSetCommand;
        private final String mCallDeleteCommand;
        private final String mCallListCommand;
        private final String mCallSetAllCommand;

        private final ArraySet<String> mReadableFields;
        private final ArraySet<String> mAllFields;
        private final ArrayMap<String, Integer> mReadableFieldsWithMaxTargetSdk;

        @GuardedBy("this")
        private GenerationTracker mGenerationTracker;

        <T extends NameValueTable> NameValueCache(Uri uri, String getCommand,
                String setCommand, String deleteCommand, ContentProviderHolder providerHolder,
                Class<T> callerClass) {
            this(uri, getCommand, setCommand, deleteCommand, null, null, providerHolder,
                    callerClass);
        }

        private <T extends NameValueTable> NameValueCache(Uri uri, String getCommand,
                String setCommand, String deleteCommand, String listCommand, String setAllCommand,
                ContentProviderHolder providerHolder, Class<T> callerClass) {
            mUri = uri;
            mCallGetCommand = getCommand;
            mCallSetCommand = setCommand;
            mCallDeleteCommand = deleteCommand;
            mCallListCommand = listCommand;
            mCallSetAllCommand = setAllCommand;
            mProviderHolder = providerHolder;
            mReadableFields = new ArraySet<>();
            mAllFields = new ArraySet<>();
            mReadableFieldsWithMaxTargetSdk = new ArrayMap<>();
            getPublicSettingsForClass(callerClass, mAllFields, mReadableFields,
                    mReadableFieldsWithMaxTargetSdk);
        }

        public boolean putStringForUser(ContentResolver cr, String name, String value,
                String tag, boolean makeDefault, final int userHandle,
                boolean overrideableByRestore) {
            try {
                Bundle arg = new Bundle();
                arg.putString(Settings.NameValueTable.VALUE, value);
                arg.putInt(CALL_METHOD_USER_KEY, userHandle);
                if (tag != null) {
                    arg.putString(CALL_METHOD_TAG_KEY, tag);
                }
                if (makeDefault) {
                    arg.putBoolean(CALL_METHOD_MAKE_DEFAULT_KEY, true);
                }
                if (overrideableByRestore) {
                    arg.putBoolean(CALL_METHOD_OVERRIDEABLE_BY_RESTORE_KEY, true);
                }
                IContentProvider cp = mProviderHolder.getProvider(cr);
                cp.call(cr.getAttributionSource(),
                        mProviderHolder.mUri.getAuthority(), mCallSetCommand, name, arg);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't set key " + name + " in " + mUri, e);
                return false;
            }
            return true;
        }

        public @SetAllResult int setStringsForPrefix(ContentResolver cr, String prefix,
                HashMap<String, String> keyValues) {
            if (mCallSetAllCommand == null) {
                // This NameValueCache does not support atomically setting multiple flags
                return SET_ALL_RESULT_FAILURE;
            }
            try {
                Bundle args = new Bundle();
                args.putString(CALL_METHOD_PREFIX_KEY, prefix);
                args.putSerializable(CALL_METHOD_FLAGS_KEY, keyValues);
                IContentProvider cp = mProviderHolder.getProvider(cr);
                Bundle bundle = cp.call(cr.getAttributionSource(),
                        mProviderHolder.mUri.getAuthority(),
                        mCallSetAllCommand, null, args);
                return bundle.getInt(KEY_CONFIG_SET_ALL_RETURN);
            } catch (RemoteException e) {
                // Not supported by the remote side
                return SET_ALL_RESULT_FAILURE;
            }
        }

        public boolean deleteStringForUser(ContentResolver cr, String name, final int userHandle) {
            try {
                Bundle arg = new Bundle();
                arg.putInt(CALL_METHOD_USER_KEY, userHandle);
                IContentProvider cp = mProviderHolder.getProvider(cr);
                cp.call(cr.getAttributionSource(),
                        mProviderHolder.mUri.getAuthority(), mCallDeleteCommand, name, arg);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't delete key " + name + " in " + mUri, e);
                return false;
            }
            return true;
        }

        @UnsupportedAppUsage
        public String getStringForUser(ContentResolver cr, String name, final int userHandle) {
            // Check if the target settings key is readable. Reject if the caller is not system and
            // is trying to access a settings key defined in the Settings.Secure, Settings.System or
            // Settings.Global and is not annotated as @Readable.
            // Notice that a key string that is not defined in any of the Settings.* classes will
            // still be regarded as readable.
            if (!isCallerExemptFromReadableRestriction() && mAllFields.contains(name)) {
                if (!mReadableFields.contains(name)) {
                    throw new SecurityException(
                            "Settings key: <" + name + "> is not readable. From S+, settings keys "
                                    + "annotated with @hide are restricted to system_server and "
                                    + "system apps only, unless they are annotated with @Readable."
                    );
                } else {
                    // When the target settings key has @Readable annotation, if the caller app's
                    // target sdk is higher than the maxTargetSdk of the annotation, reject access.
                    if (mReadableFieldsWithMaxTargetSdk.containsKey(name)) {
                        final int maxTargetSdk = mReadableFieldsWithMaxTargetSdk.get(name);
                        final Application application = ActivityThread.currentApplication();
                        final boolean targetSdkCheckOk = application != null
                                && application.getApplicationInfo() != null
                                && application.getApplicationInfo().targetSdkVersion
                                <= maxTargetSdk;
                        if (!targetSdkCheckOk) {
                            throw new SecurityException(
                                    "Settings key: <" + name + "> is only readable to apps with "
                                            + "targetSdkVersion lower than or equal to: "
                                            + maxTargetSdk
                            );
                        }
                    }
                }
            }

            final boolean isSelf = (userHandle == UserHandle.myUserId());
            int currentGeneration = -1;
            if (isSelf) {
                synchronized (NameValueCache.this) {
                    if (mGenerationTracker != null) {
                        if (mGenerationTracker.isGenerationChanged()) {
                            if (DEBUG) {
                                Log.i(TAG, "Generation changed for type:"
                                        + mUri.getPath() + " in package:"
                                        + cr.getPackageName() +" and user:" + userHandle);
                            }
                            mValues.clear();
                        } else if (mValues.containsKey(name)) {
                            return mValues.get(name);
                        }
                        if (mGenerationTracker != null) {
                            currentGeneration = mGenerationTracker.getCurrentGeneration();
                        }
                    }
                }
            } else {
                if (LOCAL_LOGV) Log.v(TAG, "get setting for user " + userHandle
                        + " by user " + UserHandle.myUserId() + " so skipping cache");
            }

            IContentProvider cp = mProviderHolder.getProvider(cr);

            // Try the fast path first, not using query().  If this
            // fails (alternate Settings provider that doesn't support
            // this interface?) then we fall back to the query/table
            // interface.
            if (mCallGetCommand != null) {
                try {
                    Bundle args = null;
                    if (!isSelf) {
                        args = new Bundle();
                        args.putInt(CALL_METHOD_USER_KEY, userHandle);
                    }
                    boolean needsGenerationTracker = false;
                    synchronized (NameValueCache.this) {
                        if (isSelf && mGenerationTracker == null) {
                            needsGenerationTracker = true;
                            if (args == null) {
                                args = new Bundle();
                            }
                            args.putString(CALL_METHOD_TRACK_GENERATION_KEY, null);
                            if (DEBUG) {
                                Log.i(TAG, "Requested generation tracker for type: "+ mUri.getPath()
                                        + " in package:" + cr.getPackageName() +" and user:"
                                        + userHandle);
                            }
                        }
                    }
                    Bundle b;
                    // If we're in system server and in a binder transaction we need to clear the
                    // calling uid. This works around code in system server that did not call
                    // clearCallingIdentity, previously this wasn't needed because reading settings
                    // did not do permission checking but thats no longer the case.
                    // Long term this should be removed and callers should properly call
                    // clearCallingIdentity or use a ContentResolver from the caller as needed.
                    if (Settings.isInSystemServer() && Binder.getCallingUid() != Process.myUid()) {
                        final long token = Binder.clearCallingIdentity();
                        try {
                            b = cp.call(cr.getAttributionSource(),
                                    mProviderHolder.mUri.getAuthority(), mCallGetCommand, name,
                                    args);
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    } else {
                        b = cp.call(cr.getAttributionSource(),
                                mProviderHolder.mUri.getAuthority(), mCallGetCommand, name, args);
                    }
                    if (b != null) {
                        String value = b.getString(Settings.NameValueTable.VALUE);
                        // Don't update our cache for reads of other users' data
                        if (isSelf) {
                            synchronized (NameValueCache.this) {
                                if (needsGenerationTracker) {
                                    MemoryIntArray array = b.getParcelable(
                                            CALL_METHOD_TRACK_GENERATION_KEY);
                                    final int index = b.getInt(
                                            CALL_METHOD_GENERATION_INDEX_KEY, -1);
                                    if (array != null && index >= 0) {
                                        final int generation = b.getInt(
                                                CALL_METHOD_GENERATION_KEY, 0);
                                        if (DEBUG) {
                                            Log.i(TAG, "Received generation tracker for type:"
                                                    + mUri.getPath() + " in package:"
                                                    + cr.getPackageName() + " and user:"
                                                    + userHandle + " with index:" + index);
                                        }
                                        if (mGenerationTracker != null) {
                                            mGenerationTracker.destroy();
                                        }
                                        mGenerationTracker = new GenerationTracker(array, index,
                                                generation, () -> {
                                            synchronized (NameValueCache.this) {
                                                Log.e(TAG, "Error accessing generation"
                                                        + " tracker - removing");
                                                if (mGenerationTracker != null) {
                                                    GenerationTracker generationTracker =
                                                            mGenerationTracker;
                                                    mGenerationTracker = null;
                                                    generationTracker.destroy();
                                                    mValues.clear();
                                                }
                                            }
                                        });
                                        currentGeneration = generation;
                                    }
                                }
                                if (mGenerationTracker != null && currentGeneration ==
                                        mGenerationTracker.getCurrentGeneration()) {
                                    mValues.put(name, value);
                                }
                            }
                        } else {
                            if (LOCAL_LOGV) Log.i(TAG, "call-query of user " + userHandle
                                    + " by " + UserHandle.myUserId()
                                    + " so not updating cache");
                        }
                        return value;
                    }
                    // If the response Bundle is null, we fall through
                    // to the query interface below.
                } catch (RemoteException e) {
                    // Not supported by the remote side?  Fall through
                    // to query().
                }
            }

            Cursor c = null;
            try {
                Bundle queryArgs = ContentResolver.createSqlQueryBundle(
                        NAME_EQ_PLACEHOLDER, new String[]{name}, null);
                // Same workaround as above.
                if (Settings.isInSystemServer() && Binder.getCallingUid() != Process.myUid()) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        c = cp.query(cr.getAttributionSource(), mUri,
                                SELECT_VALUE_PROJECTION, queryArgs, null);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                } else {
                    c = cp.query(cr.getAttributionSource(), mUri,
                            SELECT_VALUE_PROJECTION, queryArgs, null);
                }
                if (c == null) {
                    Log.w(TAG, "Can't get key " + name + " from " + mUri);
                    return null;
                }

                String value = c.moveToNext() ? c.getString(0) : null;
                synchronized (NameValueCache.this) {
                    if (mGenerationTracker != null
                            && currentGeneration == mGenerationTracker.getCurrentGeneration()) {
                        mValues.put(name, value);
                    }
                }
                if (LOCAL_LOGV) {
                    Log.v(TAG, "cache miss [" + mUri.getLastPathSegment() + "]: " +
                            name + " = " + (value == null ? "(null)" : value));
                }
                return value;
            } catch (RemoteException e) {
                Log.w(TAG, "Can't get key " + name + " from " + mUri, e);
                return null;  // Return null, but don't cache it.
            } finally {
                if (c != null) c.close();
            }
        }

        private static boolean isCallerExemptFromReadableRestriction() {
            if (Settings.isInSystemServer()) {
                return true;
            }
            if (UserHandle.getAppId(Binder.getCallingUid()) < Process.FIRST_APPLICATION_UID) {
                return true;
            }
            final Application application = ActivityThread.currentApplication();
            if (application == null || application.getApplicationInfo() == null) {
                return false;
            }
            final ApplicationInfo applicationInfo = application.getApplicationInfo();
            final boolean isTestOnly =
                    (applicationInfo.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0;
            return isTestOnly || applicationInfo.isSystemApp() || applicationInfo.isPrivilegedApp()
                    || applicationInfo.isSignedWithPlatformKey();
        }

        public ArrayMap<String, String> getStringsForPrefix(ContentResolver cr, String prefix,
                List<String> names) {
            String namespace = prefix.substring(0, prefix.length() - 1);
            DeviceConfig.enforceReadPermission(ActivityThread.currentApplication(), namespace);
            ArrayMap<String, String> keyValues = new ArrayMap<>();
            int currentGeneration = -1;

            synchronized (NameValueCache.this) {
                if (mGenerationTracker != null) {
                    if (mGenerationTracker.isGenerationChanged()) {
                        if (DEBUG) {
                            Log.i(TAG, "Generation changed for type:" + mUri.getPath()
                                    + " in package:" + cr.getPackageName());
                        }
                        mValues.clear();
                    } else {
                        boolean prefixCached = mValues.containsKey(prefix);
                        if (prefixCached) {
                            if (!names.isEmpty()) {
                                for (String name : names) {
                                    if (mValues.containsKey(name)) {
                                        keyValues.put(name, mValues.get(name));
                                    }
                                }
                            } else {
                                for (int i = 0; i < mValues.size(); ++i) {
                                    String key = mValues.keyAt(i);
                                    // Explicitly exclude the prefix as it is only there to
                                    // signal that the prefix has been cached.
                                    if (key.startsWith(prefix) && !key.equals(prefix)) {
                                        keyValues.put(key, mValues.get(key));
                                    }
                                }
                            }
                            return keyValues;
                        }
                    }
                    if (mGenerationTracker != null) {
                        currentGeneration = mGenerationTracker.getCurrentGeneration();
                    }
                }
            }

            if (mCallListCommand == null) {
                // No list command specified, return empty map
                return keyValues;
            }
            IContentProvider cp = mProviderHolder.getProvider(cr);

            try {
                Bundle args = new Bundle();
                args.putString(Settings.CALL_METHOD_PREFIX_KEY, prefix);
                boolean needsGenerationTracker = false;
                synchronized (NameValueCache.this) {
                    if (mGenerationTracker == null) {
                        needsGenerationTracker = true;
                        args.putString(CALL_METHOD_TRACK_GENERATION_KEY, null);
                        if (DEBUG) {
                            Log.i(TAG, "Requested generation tracker for type: "
                                    + mUri.getPath() + " in package:" + cr.getPackageName());
                        }
                    }
                }

                // Fetch all flags for the namespace at once for caching purposes
                Bundle b = cp.call(cr.getAttributionSource(),
                        mProviderHolder.mUri.getAuthority(), mCallListCommand, null, args);
                if (b == null) {
                    // Invalid response, return an empty map
                    return keyValues;
                }

                // All flags for the namespace
                Map<String, String> flagsToValues =
                        (HashMap) b.getSerializable(Settings.NameValueTable.VALUE);
                // Only the flags requested by the caller
                if (!names.isEmpty()) {
                    for (Map.Entry<String, String> flag : flagsToValues.entrySet()) {
                        if (names.contains(flag.getKey())) {
                            keyValues.put(flag.getKey(), flag.getValue());
                        }
                    }
                } else {
                    keyValues.putAll(flagsToValues);
                }

                synchronized (NameValueCache.this) {
                    if (needsGenerationTracker) {
                        MemoryIntArray array = b.getParcelable(
                                CALL_METHOD_TRACK_GENERATION_KEY);
                        final int index = b.getInt(
                                CALL_METHOD_GENERATION_INDEX_KEY, -1);
                        if (array != null && index >= 0) {
                            final int generation = b.getInt(
                                    CALL_METHOD_GENERATION_KEY, 0);
                            if (DEBUG) {
                                Log.i(TAG, "Received generation tracker for type:"
                                        + mUri.getPath() + " in package:"
                                        + cr.getPackageName() + " with index:" + index);
                            }
                            if (mGenerationTracker != null) {
                                mGenerationTracker.destroy();
                            }
                            mGenerationTracker = new GenerationTracker(array, index,
                                    generation, () -> {
                                synchronized (NameValueCache.this) {
                                    Log.e(TAG, "Error accessing generation tracker"
                                            + " - removing");
                                    if (mGenerationTracker != null) {
                                        GenerationTracker generationTracker =
                                                mGenerationTracker;
                                        mGenerationTracker = null;
                                        generationTracker.destroy();
                                        mValues.clear();
                                    }
                                }
                            });
                            currentGeneration = generation;
                        }
                    }
                    if (mGenerationTracker != null && currentGeneration
                            == mGenerationTracker.getCurrentGeneration()) {
                        // cache the complete list of flags for the namespace
                        mValues.putAll(flagsToValues);
                        // Adding the prefix as a signal that the prefix is cached.
                        mValues.put(prefix, null);
                    }
                }
                return keyValues;
            } catch (RemoteException e) {
                // Not supported by the remote side, return an empty map
                return keyValues;
            }
        }

        public void clearGenerationTrackerForTest() {
            synchronized (NameValueCache.this) {
                if (mGenerationTracker != null) {
                    mGenerationTracker.destroy();
                }
                mValues.clear();
                mGenerationTracker = null;
            }
        }
    }

    /**
     * Checks if the specified context can draw on top of other apps. As of API
     * level 23, an app cannot draw on top of other apps unless it declares the
     * {@link android.Manifest.permission#SYSTEM_ALERT_WINDOW} permission in its
     * manifest, <em>and</em> the user specifically grants the app this
     * capability. To prompt the user to grant this approval, the app must send an
     * intent with the action
     * {@link android.provider.Settings#ACTION_MANAGE_OVERLAY_PERMISSION}, which
     * causes the system to display a permission management screen.
     *
     * @param context App context.
     * @return true if the specified context can draw on top of other apps, false otherwise
     */
    public static boolean canDrawOverlays(Context context) {
        return Settings.isCallingPackageAllowedToDrawOverlays(context, Process.myUid(),
                context.getOpPackageName(), false) || context.checkSelfPermission(
                Manifest.permission.SYSTEM_APPLICATION_OVERLAY)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * This annotation indicates that the value of a setting is allowed to be read
     * with the get* methods. The following settings should be readable:
     * 1) all the public settings
     * 2) all the hidden settings added before S
     */
    @Target({ ElementType.FIELD })
    @Retention(RetentionPolicy.RUNTIME)
    private @interface Readable {
        int maxTargetSdk() default 0;
    }

    private static <T extends NameValueTable> void getPublicSettingsForClass(
            Class<T> callerClass, Set<String> allKeys, Set<String> readableKeys,
            ArrayMap<String, Integer> keysWithMaxTargetSdk) {
        final Field[] allFields = callerClass.getDeclaredFields();
        try {
            for (int i = 0; i < allFields.length; i++) {
                final Field field = allFields[i];
                if (!field.getType().equals(String.class)) {
                    continue;
                }
                final Object value = field.get(callerClass);
                if (!value.getClass().equals(String.class)) {
                    continue;
                }
                allKeys.add((String) value);
                final Readable annotation = field.getAnnotation(Readable.class);

                if (annotation != null) {
                    final String key = (String) value;
                    final int maxTargetSdk = annotation.maxTargetSdk();
                    readableKeys.add(key);
                    if (maxTargetSdk != 0) {
                        keysWithMaxTargetSdk.put(key, maxTargetSdk);
                    }
                }
            }
        } catch (IllegalAccessException ignored) {
        }
    }

    private static float parseFloatSetting(String settingValue, String settingName)
            throws SettingNotFoundException {
        if (settingValue == null) {
            throw new SettingNotFoundException(settingName);
        }
        try {
            return Float.parseFloat(settingValue);
        } catch (NumberFormatException e) {
            throw new SettingNotFoundException(settingName);
        }
    }

    private static float parseFloatSettingWithDefault(String settingValue, float defaultValue) {
        try {
            return settingValue != null ? Float.parseFloat(settingValue) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int parseIntSetting(String settingValue, String settingName)
            throws SettingNotFoundException {
        if (settingValue == null) {
            throw new SettingNotFoundException(settingName);
        }
        try {
            return Integer.parseInt(settingValue);
        } catch (NumberFormatException e) {
            throw new SettingNotFoundException(settingName);
        }
    }

    private static int parseIntSettingWithDefault(String settingValue, int defaultValue) {
        try {
            return settingValue != null ? Integer.parseInt(settingValue) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseLongSetting(String settingValue, String settingName)
            throws SettingNotFoundException {
        if (settingValue == null) {
            throw new SettingNotFoundException(settingName);
        }
        try {
            return Long.parseLong(settingValue);
        } catch (NumberFormatException e) {
            throw new SettingNotFoundException(settingName);
        }
    }

    private static long parseLongSettingWithDefault(String settingValue, long defaultValue) {
        try {
            return settingValue != null ? Long.parseLong(settingValue) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * System settings, containing miscellaneous system preferences.  This
     * table holds simple name/value pairs.  There are convenience
     * functions for accessing individual settings entries.
     */
    public static final class System extends NameValueTable {
        // NOTE: If you add new settings here, be sure to add them to
        // com.android.providers.settings.SettingsProtoDumpUtil#dumpProtoSystemSettingsLocked.

        private static final float DEFAULT_FONT_SCALE = 1.0f;
        private static final int DEFAULT_FONT_WEIGHT = 0;

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/system");

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        private static final ContentProviderHolder sProviderHolder =
                new ContentProviderHolder(CONTENT_URI);

        @UnsupportedAppUsage
        private static final NameValueCache sNameValueCache = new NameValueCache(
                CONTENT_URI,
                CALL_METHOD_GET_SYSTEM,
                CALL_METHOD_PUT_SYSTEM,
                CALL_METHOD_DELETE_SYSTEM,
                sProviderHolder,
                System.class);

        @UnsupportedAppUsage
        private static final HashSet<String> MOVED_TO_SECURE;
        static {
            MOVED_TO_SECURE = new HashSet<>(30);
            MOVED_TO_SECURE.add(Secure.ADAPTIVE_SLEEP);
            MOVED_TO_SECURE.add(Secure.ANDROID_ID);
            MOVED_TO_SECURE.add(Secure.HTTP_PROXY);
            MOVED_TO_SECURE.add(Secure.LOCATION_PROVIDERS_ALLOWED);
            MOVED_TO_SECURE.add(Secure.LOCK_BIOMETRIC_WEAK_FLAGS);
            MOVED_TO_SECURE.add(Secure.LOCK_PATTERN_ENABLED);
            MOVED_TO_SECURE.add(Secure.LOCK_PATTERN_VISIBLE);
            MOVED_TO_SECURE.add(Secure.LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED);
            MOVED_TO_SECURE.add(Secure.LOGGING_ID);
            MOVED_TO_SECURE.add(Secure.PARENTAL_CONTROL_ENABLED);
            MOVED_TO_SECURE.add(Secure.PARENTAL_CONTROL_LAST_UPDATE);
            MOVED_TO_SECURE.add(Secure.PARENTAL_CONTROL_REDIRECT_URL);
            MOVED_TO_SECURE.add(Secure.SETTINGS_CLASSNAME);
            MOVED_TO_SECURE.add(Secure.USE_GOOGLE_MAIL);
            MOVED_TO_SECURE.add(Secure.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON);
            MOVED_TO_SECURE.add(Secure.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY);
            MOVED_TO_SECURE.add(Secure.WIFI_NUM_OPEN_NETWORKS_KEPT);
            MOVED_TO_SECURE.add(Secure.WIFI_ON);
            MOVED_TO_SECURE.add(Secure.WIFI_WATCHDOG_ACCEPTABLE_PACKET_LOSS_PERCENTAGE);
            MOVED_TO_SECURE.add(Secure.WIFI_WATCHDOG_AP_COUNT);
            MOVED_TO_SECURE.add(Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_DELAY_MS);
            MOVED_TO_SECURE.add(Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_ENABLED);
            MOVED_TO_SECURE.add(Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_TIMEOUT_MS);
            MOVED_TO_SECURE.add(Secure.WIFI_WATCHDOG_INITIAL_IGNORED_PING_COUNT);
            MOVED_TO_SECURE.add(Secure.WIFI_WATCHDOG_MAX_AP_CHECKS);
            MOVED_TO_SECURE.add(Secure.WIFI_WATCHDOG_ON);
            MOVED_TO_SECURE.add(Secure.WIFI_WATCHDOG_PING_COUNT);
            MOVED_TO_SECURE.add(Secure.WIFI_WATCHDOG_PING_DELAY_MS);
            MOVED_TO_SECURE.add(Secure.WIFI_WATCHDOG_PING_TIMEOUT_MS);

            // At one time in System, then Global, but now back in Secure
            MOVED_TO_SECURE.add(Secure.INSTALL_NON_MARKET_APPS);
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        private static final HashSet<String> MOVED_TO_GLOBAL;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        private static final HashSet<String> MOVED_TO_SECURE_THEN_GLOBAL;
        static {
            MOVED_TO_GLOBAL = new HashSet<>();
            MOVED_TO_SECURE_THEN_GLOBAL = new HashSet<>();

            // these were originally in system but migrated to secure in the past,
            // so are duplicated in the Secure.* namespace
            MOVED_TO_SECURE_THEN_GLOBAL.add(Global.ADB_ENABLED);
            MOVED_TO_SECURE_THEN_GLOBAL.add(Global.BLUETOOTH_ON);
            MOVED_TO_SECURE_THEN_GLOBAL.add(Global.DATA_ROAMING);
            MOVED_TO_SECURE_THEN_GLOBAL.add(Global.DEVICE_PROVISIONED);
            MOVED_TO_SECURE_THEN_GLOBAL.add(Global.HTTP_PROXY);
            MOVED_TO_SECURE_THEN_GLOBAL.add(Global.NETWORK_PREFERENCE);
            MOVED_TO_SECURE_THEN_GLOBAL.add(Global.USB_MASS_STORAGE_ENABLED);
            MOVED_TO_SECURE_THEN_GLOBAL.add(Global.WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS);
            MOVED_TO_SECURE_THEN_GLOBAL.add(Global.WIFI_MAX_DHCP_RETRY_COUNT);

            // these are moving directly from system to global
            MOVED_TO_GLOBAL.add(Settings.Global.AIRPLANE_MODE_ON);
            MOVED_TO_GLOBAL.add(Settings.Global.AIRPLANE_MODE_RADIOS);
            MOVED_TO_GLOBAL.add(Settings.Global.AIRPLANE_MODE_TOGGLEABLE_RADIOS);
            MOVED_TO_GLOBAL.add(Settings.Global.AUTO_TIME);
            MOVED_TO_GLOBAL.add(Settings.Global.AUTO_TIME_ZONE);
            MOVED_TO_GLOBAL.add(Settings.Global.CAR_DOCK_SOUND);
            MOVED_TO_GLOBAL.add(Settings.Global.CAR_UNDOCK_SOUND);
            MOVED_TO_GLOBAL.add(Settings.Global.DESK_DOCK_SOUND);
            MOVED_TO_GLOBAL.add(Settings.Global.DESK_UNDOCK_SOUND);
            MOVED_TO_GLOBAL.add(Settings.Global.DOCK_SOUNDS_ENABLED);
            MOVED_TO_GLOBAL.add(Settings.Global.LOCK_SOUND);
            MOVED_TO_GLOBAL.add(Settings.Global.UNLOCK_SOUND);
            MOVED_TO_GLOBAL.add(Settings.Global.LOW_BATTERY_SOUND);
            MOVED_TO_GLOBAL.add(Settings.Global.POWER_SOUNDS_ENABLED);
            MOVED_TO_GLOBAL.add(Settings.Global.STAY_ON_WHILE_PLUGGED_IN);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_SLEEP_POLICY);
            MOVED_TO_GLOBAL.add(Settings.Global.MODE_RINGER);
            MOVED_TO_GLOBAL.add(Settings.Global.WINDOW_ANIMATION_SCALE);
            MOVED_TO_GLOBAL.add(Settings.Global.TRANSITION_ANIMATION_SCALE);
            MOVED_TO_GLOBAL.add(Settings.Global.ANIMATOR_DURATION_SCALE);
            MOVED_TO_GLOBAL.add(Settings.Global.FANCY_IME_ANIMATIONS);
            MOVED_TO_GLOBAL.add(Settings.Global.COMPATIBILITY_MODE);
            MOVED_TO_GLOBAL.add(Settings.Global.EMERGENCY_TONE);
            MOVED_TO_GLOBAL.add(Settings.Global.CALL_AUTO_RETRY);
            MOVED_TO_GLOBAL.add(Settings.Global.DEBUG_APP);
            MOVED_TO_GLOBAL.add(Settings.Global.WAIT_FOR_DEBUGGER);
            MOVED_TO_GLOBAL.add(Settings.Global.ALWAYS_FINISH_ACTIVITIES);
            MOVED_TO_GLOBAL.add(Settings.Global.TZINFO_UPDATE_CONTENT_URL);
            MOVED_TO_GLOBAL.add(Settings.Global.TZINFO_UPDATE_METADATA_URL);
            MOVED_TO_GLOBAL.add(Settings.Global.SELINUX_UPDATE_CONTENT_URL);
            MOVED_TO_GLOBAL.add(Settings.Global.SELINUX_UPDATE_METADATA_URL);
            MOVED_TO_GLOBAL.add(Settings.Global.SMS_SHORT_CODES_UPDATE_CONTENT_URL);
            MOVED_TO_GLOBAL.add(Settings.Global.SMS_SHORT_CODES_UPDATE_METADATA_URL);
            MOVED_TO_GLOBAL.add(Settings.Global.CERT_PIN_UPDATE_CONTENT_URL);
            MOVED_TO_GLOBAL.add(Settings.Global.CERT_PIN_UPDATE_METADATA_URL);
            MOVED_TO_GLOBAL.add(Settings.Global.RADIO_NFC);
            MOVED_TO_GLOBAL.add(Settings.Global.RADIO_CELL);
            MOVED_TO_GLOBAL.add(Settings.Global.RADIO_WIFI);
            MOVED_TO_GLOBAL.add(Settings.Global.RADIO_BLUETOOTH);
            MOVED_TO_GLOBAL.add(Settings.Global.RADIO_WIMAX);
            MOVED_TO_GLOBAL.add(Settings.Global.SHOW_PROCESSES);
        }

        /** @hide */
        public static void getMovedToGlobalSettings(Set<String> outKeySet) {
            outKeySet.addAll(MOVED_TO_GLOBAL);
            outKeySet.addAll(MOVED_TO_SECURE_THEN_GLOBAL);
        }

        /** @hide */
        public static void getMovedToSecureSettings(Set<String> outKeySet) {
            outKeySet.addAll(MOVED_TO_SECURE);
        }

        /** @hide */
        public static void getNonLegacyMovedKeys(HashSet<String> outKeySet) {
            outKeySet.addAll(MOVED_TO_GLOBAL);
        }

        /** @hide */
        public static void clearProviderForTest() {
            sProviderHolder.clearProviderForTest();
            sNameValueCache.clearGenerationTrackerForTest();
        }

        /** @hide */
        public static void getPublicSettings(Set<String> allKeys, Set<String> readableKeys,
                ArrayMap<String, Integer> readableKeysWithMaxTargetSdk) {
            getPublicSettingsForClass(System.class, allKeys, readableKeys,
                    readableKeysWithMaxTargetSdk);
        }

        /**
         * Look up a name in the database.
         * @param resolver to access the database with
         * @param name to look up in the table
         * @return the corresponding value, or null if not present
         */
        public static String getString(ContentResolver resolver, String name) {
            return getStringForUser(resolver, name, resolver.getUserId());
        }

        /** @hide */
        @UnsupportedAppUsage
        public static String getStringForUser(ContentResolver resolver, String name,
                int userHandle) {
            if (MOVED_TO_SECURE.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.System"
                        + " to android.provider.Settings.Secure, returning read-only value.");
                return Secure.getStringForUser(resolver, name, userHandle);
            }
            if (MOVED_TO_GLOBAL.contains(name) || MOVED_TO_SECURE_THEN_GLOBAL.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.System"
                        + " to android.provider.Settings.Global, returning read-only value.");
                return Global.getStringForUser(resolver, name, userHandle);
            }

            return sNameValueCache.getStringForUser(resolver, name, userHandle);
        }

        /**
         * Store a name/value pair into the database.
         * @param resolver to access the database with
         * @param name to store
         * @param value to associate with the name
         * @return true if the value was set, false on database errors
         */
        public static boolean putString(ContentResolver resolver, String name, String value) {
            return putStringForUser(resolver, name, value, resolver.getUserId());
        }

        /**
         * Store a name/value pair into the database. Values written by this method will be
         * overridden if a restore happens in the future.
         *
         * @param resolver to access the database with
         * @param name to store
         * @param value to associate with the name
         *
         * @return true if the value was set, false on database errors
         *
         * @hide
         */
        @RequiresPermission(Manifest.permission.MODIFY_SETTINGS_OVERRIDEABLE_BY_RESTORE)
        @SystemApi
        public static boolean putString(@NonNull ContentResolver resolver,
                @NonNull String name, @Nullable String value, boolean overrideableByRestore) {
            return putStringForUser(resolver, name, value, resolver.getUserId(),
                   overrideableByRestore);
        }

        /** @hide */
        @UnsupportedAppUsage
        public static boolean putStringForUser(ContentResolver resolver, String name, String value,
                int userHandle) {
            return putStringForUser(resolver, name, value, userHandle,
                    DEFAULT_OVERRIDEABLE_BY_RESTORE);
        }

        private static boolean putStringForUser(ContentResolver resolver, String name, String value,
                int userHandle, boolean overrideableByRestore) {
            return putStringForUser(resolver, name, value, /* tag= */ null,
                    /* makeDefault= */ false, userHandle, overrideableByRestore);
        }

        private static boolean putStringForUser(ContentResolver resolver, String name, String value,
                String tag, boolean makeDefault, int userHandle, boolean overrideableByRestore) {
            if (MOVED_TO_SECURE.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.System"
                        + " to android.provider.Settings.Secure, value is unchanged.");
                return false;
            }
            if (MOVED_TO_GLOBAL.contains(name) || MOVED_TO_SECURE_THEN_GLOBAL.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.System"
                        + " to android.provider.Settings.Global, value is unchanged.");
                return false;
            }
            return sNameValueCache.putStringForUser(resolver, name, value, tag, makeDefault,
                    userHandle, overrideableByRestore);
        }

        /**
         * Construct the content URI for a particular name/value pair,
         * useful for monitoring changes with a ContentObserver.
         * @param name to look up in the table
         * @return the corresponding content URI, or null if not present
         */
        public static Uri getUriFor(String name) {
            if (MOVED_TO_SECURE.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.System"
                    + " to android.provider.Settings.Secure, returning Secure URI.");
                return Secure.getUriFor(Secure.CONTENT_URI, name);
            }
            if (MOVED_TO_GLOBAL.contains(name) || MOVED_TO_SECURE_THEN_GLOBAL.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.System"
                        + " to android.provider.Settings.Global, returning read-only global URI.");
                return Global.getUriFor(Global.CONTENT_URI, name);
            }
            return getUriFor(CONTENT_URI, name);
        }

        /**
         * Convenience function for retrieving a single system settings value
         * as an integer.  Note that internally setting values are always
         * stored as strings; this function converts the string to an integer
         * for you.  The default value will be returned if the setting is
         * not defined or not an integer.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         * @param def Value to return if the setting is not defined.
         *
         * @return The setting's current value, or 'def' if it is not defined
         * or not a valid integer.
         */
        public static int getInt(ContentResolver cr, String name, int def) {
            return getIntForUser(cr, name, def, cr.getUserId());
        }

        /** @hide */
        @UnsupportedAppUsage
        public static int getIntForUser(ContentResolver cr, String name, int def, int userHandle) {
            String v = getStringForUser(cr, name, userHandle);
            return parseIntSettingWithDefault(v, def);
        }

        /**
         * Convenience function for retrieving a single system settings value
         * as an integer.  Note that internally setting values are always
         * stored as strings; this function converts the string to an integer
         * for you.
         * <p>
         * This version does not take a default value.  If the setting has not
         * been set, or the string value is not a number,
         * it throws {@link SettingNotFoundException}.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         *
         * @throws SettingNotFoundException Thrown if a setting by the given
         * name can't be found or the setting value is not an integer.
         *
         * @return The setting's current value.
         */
        public static int getInt(ContentResolver cr, String name)
                throws SettingNotFoundException {
            return getIntForUser(cr, name, cr.getUserId());
        }

        /** @hide */
        @UnsupportedAppUsage
        public static int getIntForUser(ContentResolver cr, String name, int userHandle)
                throws SettingNotFoundException {
            String v = getStringForUser(cr, name, userHandle);
            return parseIntSetting(v, name);
        }

        /**
         * Convenience function for updating a single settings value as an
         * integer. This will either create a new entry in the table if the
         * given name does not exist, or modify the value of the existing row
         * with that name.  Note that internally setting values are always
         * stored as strings, so this function converts the given value to a
         * string before storing it.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to modify.
         * @param value The new value for the setting.
         * @return true if the value was set, false on database errors
         */
        public static boolean putInt(ContentResolver cr, String name, int value) {
            return putIntForUser(cr, name, value, cr.getUserId());
        }

        /** @hide */
        @UnsupportedAppUsage
        public static boolean putIntForUser(ContentResolver cr, String name, int value,
                int userHandle) {
            return putStringForUser(cr, name, Integer.toString(value), userHandle);
        }

        /**
         * Convenience function for retrieving a single system settings value
         * as a {@code long}.  Note that internally setting values are always
         * stored as strings; this function converts the string to a {@code long}
         * for you.  The default value will be returned if the setting is
         * not defined or not a {@code long}.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         * @param def Value to return if the setting is not defined.
         *
         * @return The setting's current value, or 'def' if it is not defined
         * or not a valid {@code long}.
         */
        public static long getLong(ContentResolver cr, String name, long def) {
            return getLongForUser(cr, name, def, cr.getUserId());
        }

        /** @hide */
        public static long getLongForUser(ContentResolver cr, String name, long def,
                int userHandle) {
            String v = getStringForUser(cr, name, userHandle);
            return parseLongSettingWithDefault(v, def);
        }

        /**
         * Convenience function for retrieving a single system settings value
         * as a {@code long}.  Note that internally setting values are always
         * stored as strings; this function converts the string to a {@code long}
         * for you.
         * <p>
         * This version does not take a default value.  If the setting has not
         * been set, or the string value is not a number,
         * it throws {@link SettingNotFoundException}.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         *
         * @return The setting's current value.
         * @throws SettingNotFoundException Thrown if a setting by the given
         * name can't be found or the setting value is not an integer.
         */
        public static long getLong(ContentResolver cr, String name)
                throws SettingNotFoundException {
            return getLongForUser(cr, name, cr.getUserId());
        }

        /** @hide */
        public static long getLongForUser(ContentResolver cr, String name, int userHandle)
                throws SettingNotFoundException {
            String v = getStringForUser(cr, name, userHandle);
            return parseLongSetting(v, name);
        }

        /**
         * Convenience function for updating a single settings value as a long
         * integer. This will either create a new entry in the table if the
         * given name does not exist, or modify the value of the existing row
         * with that name.  Note that internally setting values are always
         * stored as strings, so this function converts the given value to a
         * string before storing it.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to modify.
         * @param value The new value for the setting.
         * @return true if the value was set, false on database errors
         */
        public static boolean putLong(ContentResolver cr, String name, long value) {
            return putLongForUser(cr, name, value, cr.getUserId());
        }

        /** @hide */
        public static boolean putLongForUser(ContentResolver cr, String name, long value,
                int userHandle) {
            return putStringForUser(cr, name, Long.toString(value), userHandle);
        }

        /**
         * Convenience function for retrieving a single system settings value
         * as a floating point number.  Note that internally setting values are
         * always stored as strings; this function converts the string to an
         * float for you. The default value will be returned if the setting
         * is not defined or not a valid float.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         * @param def Value to return if the setting is not defined.
         *
         * @return The setting's current value, or 'def' if it is not defined
         * or not a valid float.
         */
        public static float getFloat(ContentResolver cr, String name, float def) {
            return getFloatForUser(cr, name, def, cr.getUserId());
        }

        /** @hide */
        public static float getFloatForUser(ContentResolver cr, String name, float def,
                int userHandle) {
            String v = getStringForUser(cr, name, userHandle);
            return parseFloatSettingWithDefault(v, def);
        }

        /**
         * Convenience function for retrieving a single system settings value
         * as a float.  Note that internally setting values are always
         * stored as strings; this function converts the string to a float
         * for you.
         * <p>
         * This version does not take a default value.  If the setting has not
         * been set, or the string value is not a number,
         * it throws {@link SettingNotFoundException}.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         *
         * @throws SettingNotFoundException Thrown if a setting by the given
         * name can't be found or the setting value is not a float.
         *
         * @return The setting's current value.
         */
        public static float getFloat(ContentResolver cr, String name)
                throws SettingNotFoundException {
            return getFloatForUser(cr, name, cr.getUserId());
        }

        /** @hide */
        public static float getFloatForUser(ContentResolver cr, String name, int userHandle)
                throws SettingNotFoundException {
            String v = getStringForUser(cr, name, userHandle);
            return parseFloatSetting(v, name);
        }

        /**
         * Convenience function for updating a single settings value as a
         * floating point number. This will either create a new entry in the
         * table if the given name does not exist, or modify the value of the
         * existing row with that name.  Note that internally setting values
         * are always stored as strings, so this function converts the given
         * value to a string before storing it.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to modify.
         * @param value The new value for the setting.
         * @return true if the value was set, false on database errors
         */
        public static boolean putFloat(ContentResolver cr, String name, float value) {
            return putFloatForUser(cr, name, value, cr.getUserId());
        }

        /** @hide */
        public static boolean putFloatForUser(ContentResolver cr, String name, float value,
                int userHandle) {
            return putStringForUser(cr, name, Float.toString(value), userHandle);
        }

        /**
         * Convenience function to read all of the current
         * configuration-related settings into a
         * {@link Configuration} object.
         *
         * @param cr The ContentResolver to access.
         * @param outConfig Where to place the configuration settings.
         */
        public static void getConfiguration(ContentResolver cr, Configuration outConfig) {
            adjustConfigurationForUser(cr, outConfig, cr.getUserId(),
                    false /* updateSettingsIfEmpty */);
        }

        /** @hide */
        public static void adjustConfigurationForUser(ContentResolver cr, Configuration outConfig,
                int userHandle, boolean updateSettingsIfEmpty) {
            outConfig.fontScale = Settings.System.getFloatForUser(
                    cr, FONT_SCALE, DEFAULT_FONT_SCALE, userHandle);
            if (outConfig.fontScale < 0) {
                outConfig.fontScale = DEFAULT_FONT_SCALE;
            }
            outConfig.fontWeightAdjustment = Settings.Secure.getIntForUser(
                    cr, Settings.Secure.FONT_WEIGHT_ADJUSTMENT, DEFAULT_FONT_WEIGHT, userHandle);

            final String localeValue =
                    Settings.System.getStringForUser(cr, SYSTEM_LOCALES, userHandle);
            if (localeValue != null) {
                outConfig.setLocales(LocaleList.forLanguageTags(localeValue));
            } else {
                // Do not update configuration with emtpy settings since we need to take over the
                // locale list of previous user if the settings value is empty. This happens when a
                // new user is created.

                if (updateSettingsIfEmpty) {
                    // Make current configuration persistent. This is necessary the first time a
                    // user log in. At the first login, the configuration settings are empty, so we
                    // need to store the adjusted configuration as the initial settings.
                    Settings.System.putStringForUser(
                            cr, SYSTEM_LOCALES, outConfig.getLocales().toLanguageTags(),
                            userHandle, DEFAULT_OVERRIDEABLE_BY_RESTORE);
                }
            }
        }

        /**
         * @hide Erase the fields in the Configuration that should be applied
         * by the settings.
         */
        public static void clearConfiguration(Configuration inoutConfig) {
            inoutConfig.fontScale = 0;
            if (!inoutConfig.userSetLocale && !inoutConfig.getLocales().isEmpty()) {
                inoutConfig.clearLocales();
            }
            inoutConfig.fontWeightAdjustment = Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED;
        }

        /**
         * Convenience function to write a batch of configuration-related
         * settings from a {@link Configuration} object.
         *
         * @param cr The ContentResolver to access.
         * @param config The settings to write.
         * @return true if the values were set, false on database errors
         */
        public static boolean putConfiguration(ContentResolver cr, Configuration config) {
            return putConfigurationForUser(cr, config, cr.getUserId());
        }

        /** @hide */
        public static boolean putConfigurationForUser(ContentResolver cr, Configuration config,
                int userHandle) {
            return Settings.System.putFloatForUser(cr, FONT_SCALE, config.fontScale, userHandle) &&
                    Settings.System.putStringForUser(
                            cr, SYSTEM_LOCALES, config.getLocales().toLanguageTags(), userHandle,
                            DEFAULT_OVERRIDEABLE_BY_RESTORE);
        }

        /**
         * Convenience function for checking if settings should be overwritten with config changes.
         * @see #putConfigurationForUser(ContentResolver, Configuration, int)
         * @hide
         */
        public static boolean hasInterestingConfigurationChanges(int changes) {
            return (changes & ActivityInfo.CONFIG_FONT_SCALE) != 0 ||
                    (changes & ActivityInfo.CONFIG_LOCALE) != 0;
        }

        /** @deprecated - Do not use */
        @Deprecated
        public static boolean getShowGTalkServiceStatus(ContentResolver cr) {
            return getShowGTalkServiceStatusForUser(cr, cr.getUserId());
        }

        /**
         * @hide
         * @deprecated - Do not use
         */
        @Deprecated
        public static boolean getShowGTalkServiceStatusForUser(ContentResolver cr,
                int userHandle) {
            return getIntForUser(cr, SHOW_GTALK_SERVICE_STATUS, 0, userHandle) != 0;
        }

        /** @deprecated - Do not use */
        @Deprecated
        public static void setShowGTalkServiceStatus(ContentResolver cr, boolean flag) {
            setShowGTalkServiceStatusForUser(cr, flag, cr.getUserId());
        }

        /**
         * @hide
         * @deprecated - Do not use
         */
        @Deprecated
        public static void setShowGTalkServiceStatusForUser(ContentResolver cr, boolean flag,
                int userHandle) {
            putIntForUser(cr, SHOW_GTALK_SERVICE_STATUS, flag ? 1 : 0, userHandle);
        }

        /**
         * @deprecated Use {@link android.provider.Settings.Global#STAY_ON_WHILE_PLUGGED_IN} instead
         */
        @Deprecated
        public static final String STAY_ON_WHILE_PLUGGED_IN = Global.STAY_ON_WHILE_PLUGGED_IN;

        /**
         * What happens when the user presses the end call button if they're not
         * on a call.<br/>
         * <b>Values:</b><br/>
         * 0 - The end button does nothing.<br/>
         * 1 - The end button goes to the home screen.<br/>
         * 2 - The end button puts the device to sleep and locks the keyguard.<br/>
         * 3 - The end button goes to the home screen.  If the user is already on the
         * home screen, it puts the device to sleep.
         */
        @Readable
        public static final String END_BUTTON_BEHAVIOR = "end_button_behavior";

        /**
         * END_BUTTON_BEHAVIOR value for "go home".
         * @hide
         */
        public static final int END_BUTTON_BEHAVIOR_HOME = 0x1;

        /**
         * END_BUTTON_BEHAVIOR value for "go to sleep".
         * @hide
         */
        public static final int END_BUTTON_BEHAVIOR_SLEEP = 0x2;

        /**
         * END_BUTTON_BEHAVIOR default value.
         * @hide
         */
        public static final int END_BUTTON_BEHAVIOR_DEFAULT = END_BUTTON_BEHAVIOR_SLEEP;

        /**
         * Is advanced settings mode turned on. 0 == no, 1 == yes
         * @hide
         */
        @Readable
        public static final String ADVANCED_SETTINGS = "advanced_settings";

        /**
         * ADVANCED_SETTINGS default value.
         * @hide
         */
        public static final int ADVANCED_SETTINGS_DEFAULT = 0;

        /**
         * If the triple press gesture for toggling accessibility is enabled.
         * Set to 1 for true and 0 for false.
         *
         * This setting is used only internally.
         * @hide
         */
        public static final String WEAR_ACCESSIBILITY_GESTURE_ENABLED
                = "wear_accessibility_gesture_enabled";

        /**
         * @deprecated Use {@link android.provider.Settings.Global#AIRPLANE_MODE_ON} instead
         */
        @Deprecated
        public static final String AIRPLANE_MODE_ON = Global.AIRPLANE_MODE_ON;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#RADIO_BLUETOOTH} instead
         */
        @Deprecated
        public static final String RADIO_BLUETOOTH = Global.RADIO_BLUETOOTH;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#RADIO_WIFI} instead
         */
        @Deprecated
        public static final String RADIO_WIFI = Global.RADIO_WIFI;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#RADIO_WIMAX} instead
         * {@hide}
         */
        @Deprecated
        public static final String RADIO_WIMAX = Global.RADIO_WIMAX;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#RADIO_CELL} instead
         */
        @Deprecated
        public static final String RADIO_CELL = Global.RADIO_CELL;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#RADIO_NFC} instead
         */
        @Deprecated
        public static final String RADIO_NFC = Global.RADIO_NFC;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#AIRPLANE_MODE_RADIOS} instead
         */
        @Deprecated
        public static final String AIRPLANE_MODE_RADIOS = Global.AIRPLANE_MODE_RADIOS;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#AIRPLANE_MODE_TOGGLEABLE_RADIOS} instead
         *
         * {@hide}
         */
        @Deprecated
        @UnsupportedAppUsage
        public static final String AIRPLANE_MODE_TOGGLEABLE_RADIOS =
                Global.AIRPLANE_MODE_TOGGLEABLE_RADIOS;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_SLEEP_POLICY} instead
         */
        @Deprecated
        public static final String WIFI_SLEEP_POLICY = Global.WIFI_SLEEP_POLICY;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_SLEEP_POLICY_DEFAULT} instead
         */
        @Deprecated
        public static final int WIFI_SLEEP_POLICY_DEFAULT = Global.WIFI_SLEEP_POLICY_DEFAULT;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_SLEEP_POLICY_NEVER_WHILE_PLUGGED} instead
         */
        @Deprecated
        public static final int WIFI_SLEEP_POLICY_NEVER_WHILE_PLUGGED =
                Global.WIFI_SLEEP_POLICY_NEVER_WHILE_PLUGGED;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_SLEEP_POLICY_NEVER} instead
         */
        @Deprecated
        public static final int WIFI_SLEEP_POLICY_NEVER = Global.WIFI_SLEEP_POLICY_NEVER;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#MODE_RINGER} instead
         */
        @Deprecated
        public static final String MODE_RINGER = Global.MODE_RINGER;

        /**
         * Whether to use static IP and other static network attributes.
         * <p>
         * Set to 1 for true and 0 for false.
         *
         * @deprecated Use {@link WifiManager} instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_USE_STATIC_IP = "wifi_use_static_ip";

        /**
         * The static IP address.
         * <p>
         * Example: "192.168.1.51"
         *
         * @deprecated Use {@link WifiManager} instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_STATIC_IP = "wifi_static_ip";

        /**
         * If using static IP, the gateway's IP address.
         * <p>
         * Example: "192.168.1.1"
         *
         * @deprecated Use {@link WifiManager} instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_STATIC_GATEWAY = "wifi_static_gateway";

        /**
         * If using static IP, the net mask.
         * <p>
         * Example: "255.255.255.0"
         *
         * @deprecated Use {@link WifiManager} instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_STATIC_NETMASK = "wifi_static_netmask";

        /**
         * If using static IP, the primary DNS's IP address.
         * <p>
         * Example: "192.168.1.1"
         *
         * @deprecated Use {@link WifiManager} instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_STATIC_DNS1 = "wifi_static_dns1";

        /**
         * If using static IP, the secondary DNS's IP address.
         * <p>
         * Example: "192.168.1.2"
         *
         * @deprecated Use {@link WifiManager} instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_STATIC_DNS2 = "wifi_static_dns2";

        /**
         * Determines whether remote devices may discover and/or connect to
         * this device.
         * <P>Type: INT</P>
         * 2 -- discoverable and connectable
         * 1 -- connectable but not discoverable
         * 0 -- neither connectable nor discoverable
         */
        @Readable
        public static final String BLUETOOTH_DISCOVERABILITY =
            "bluetooth_discoverability";

        /**
         * Bluetooth discoverability timeout.  If this value is nonzero, then
         * Bluetooth becomes discoverable for a certain number of seconds,
         * after which is becomes simply connectable.  The value is in seconds.
         */
        @Readable
        public static final String BLUETOOTH_DISCOVERABILITY_TIMEOUT =
            "bluetooth_discoverability_timeout";

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#LOCK_PATTERN_ENABLED}
         * instead
         */
        @Deprecated
        public static final String LOCK_PATTERN_ENABLED = Secure.LOCK_PATTERN_ENABLED;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#LOCK_PATTERN_VISIBLE}
         * instead
         */
        @Deprecated
        public static final String LOCK_PATTERN_VISIBLE = "lock_pattern_visible_pattern";

        /**
         * @deprecated Use
         * {@link android.provider.Settings.Secure#LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED}
         * instead
         */
        @Deprecated
        public static final String LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED =
            "lock_pattern_tactile_feedback_enabled";

        /**
         * A formatted string of the next alarm that is set, or the empty string
         * if there is no alarm set.
         *
         * @deprecated Use {@link android.app.AlarmManager#getNextAlarmClock()}.
         */
        @Deprecated
        @Readable
        public static final String NEXT_ALARM_FORMATTED = "next_alarm_formatted";

        /**
         * Scaling factor for fonts, float.
         */
        @Readable
        public static final String FONT_SCALE = "font_scale";

        /**
         * The serialized system locale value.
         *
         * Do not use this value directory.
         * To get system locale, use {@link LocaleList#getDefault} instead.
         * To update system locale, use {@link com.android.internal.app.LocalePicker#updateLocales}
         * instead.
         * @hide
         */
        @Readable
        public static final String SYSTEM_LOCALES = "system_locales";


        /**
         * Name of an application package to be debugged.
         *
         * @deprecated Use {@link Global#DEBUG_APP} instead
         */
        @Deprecated
        public static final String DEBUG_APP = Global.DEBUG_APP;

        /**
         * If 1, when launching DEBUG_APP it will wait for the debugger before
         * starting user code.  If 0, it will run normally.
         *
         * @deprecated Use {@link Global#WAIT_FOR_DEBUGGER} instead
         */
        @Deprecated
        public static final String WAIT_FOR_DEBUGGER = Global.WAIT_FOR_DEBUGGER;

        /**
         * Whether or not to dim the screen. 0=no  1=yes
         * @deprecated This setting is no longer used.
         */
        @Deprecated
        @Readable
        public static final String DIM_SCREEN = "dim_screen";

        /**
         * The display color mode.
         * @hide
         */
        @Readable
        public static final String DISPLAY_COLOR_MODE = "display_color_mode";

        /**
         * Hint to decide whether restored vendor color modes are compatible with the new device. If
         * unset or a match is not made, only the standard color modes will be restored.
         * @hide
         */
        public static final String DISPLAY_COLOR_MODE_VENDOR_HINT =
                "display_color_mode_vendor_hint";

        /**
         * The user selected min refresh rate in frames per second.
         *
         * If this isn't set, 0 will be used.
         * @hide
         */
        @Readable
        public static final String MIN_REFRESH_RATE = "min_refresh_rate";

        /**
         * The user selected peak refresh rate in frames per second.
         *
         * If this isn't set, the system falls back to a device specific default.
         * @hide
         */
        @Readable
        public static final String PEAK_REFRESH_RATE = "peak_refresh_rate";

        /**
         * The amount of time in milliseconds before the device goes to sleep or begins
         * to dream after a period of inactivity.  This value is also known as the
         * user activity timeout period since the screen isn't necessarily turned off
         * when it expires.
         *
         * <p>
         * This value is bounded by maximum timeout set by
         * {@link android.app.admin.DevicePolicyManager#setMaximumTimeToLock(ComponentName, long)}.
         */
        @Readable
        public static final String SCREEN_OFF_TIMEOUT = "screen_off_timeout";

        /**
         * The screen backlight brightness between 0 and 255.
         */
        @Readable
        public static final String SCREEN_BRIGHTNESS = "screen_brightness";

        /**
         * The screen backlight brightness between 0 and 255.
         * @hide
         */
        @Readable
        public static final String SCREEN_BRIGHTNESS_FOR_VR = "screen_brightness_for_vr";

        /**
         * The screen backlight brightness between 0.0f and 1.0f.
         * @hide
         */
        @Readable
        public static final String SCREEN_BRIGHTNESS_FOR_VR_FLOAT =
                "screen_brightness_for_vr_float";

        /**
         * The screen backlight brightness between 0.0f and 1.0f.
         * @hide
         */
        @Readable
        public static final String SCREEN_BRIGHTNESS_FLOAT = "screen_brightness_float";

        /**
         * Control whether to enable automatic brightness mode.
         */
        @Readable
        public static final String SCREEN_BRIGHTNESS_MODE = "screen_brightness_mode";

        /**
         * Adjustment to auto-brightness to make it generally more (>0.0 <1.0)
         * or less (<0.0 >-1.0) bright.
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String SCREEN_AUTO_BRIGHTNESS_ADJ = "screen_auto_brightness_adj";

        /**
         * SCREEN_BRIGHTNESS_MODE value for manual mode.
         */
        public static final int SCREEN_BRIGHTNESS_MODE_MANUAL = 0;

        /**
         * SCREEN_BRIGHTNESS_MODE value for automatic mode.
         */
        public static final int SCREEN_BRIGHTNESS_MODE_AUTOMATIC = 1;

        /**
         * Control whether to enable adaptive sleep mode.
         * @deprecated Use {@link android.provider.Settings.Secure#ADAPTIVE_SLEEP} instead.
         * @hide
         */
        @Deprecated
        @Readable
        public static final String ADAPTIVE_SLEEP = "adaptive_sleep";

        /**
         * Control whether the process CPU usage meter should be shown.
         *
         * @deprecated This functionality is no longer available as of
         * {@link android.os.Build.VERSION_CODES#N_MR1}.
         */
        @Deprecated
        public static final String SHOW_PROCESSES = Global.SHOW_PROCESSES;

        /**
         * If 1, the activity manager will aggressively finish activities and
         * processes as soon as they are no longer needed.  If 0, the normal
         * extended lifetime is used.
         *
         * @deprecated Use {@link Global#ALWAYS_FINISH_ACTIVITIES} instead
         */
        @Deprecated
        public static final String ALWAYS_FINISH_ACTIVITIES = Global.ALWAYS_FINISH_ACTIVITIES;

        /**
         * Determines which streams are affected by ringer and zen mode changes. The
         * stream type's bit should be set to 1 if it should be muted when going
         * into an inaudible ringer mode.
         */
        @Readable
        public static final String MODE_RINGER_STREAMS_AFFECTED = "mode_ringer_streams_affected";

        /**
          * Determines which streams are affected by mute. The
          * stream type's bit should be set to 1 if it should be muted when a mute request
          * is received.
          */
        @Readable
        public static final String MUTE_STREAMS_AFFECTED = "mute_streams_affected";

        /**
         * Whether vibrate is on for different events. This is used internally,
         * changing this value will not change the vibrate. See AudioManager.
         */
        @Readable
        public static final String VIBRATE_ON = "vibrate_on";

        /**
         * Whether applying ramping ringer on incoming phone call ringtone.
         * <p>1 = apply ramping ringer
         * <p>0 = do not apply ramping ringer
         * @hide
         */
        @Readable
        public static final String APPLY_RAMPING_RINGER = "apply_ramping_ringer";

        /**
         * If 1, redirects the system vibrator to all currently attached input devices
         * that support vibration.  If there are no such input devices, then the system
         * vibrator is used instead.
         * If 0, does not register the system vibrator.
         *
         * This setting is mainly intended to provide a compatibility mechanism for
         * applications that only know about the system vibrator and do not use the
         * input device vibrator API.
         *
         * @hide
         */
        @Readable
        public static final String VIBRATE_INPUT_DEVICES = "vibrate_input_devices";

        /**
         * The intensity of alarm vibrations, if configurable.
         *
         * Not all devices are capable of changing their vibration intensity; on these devices
         * there will likely be no difference between the various vibration intensities except for
         * intensity 0 (off) and the rest.
         *
         * <b>Values:</b><br/>
         * 0 - Vibration is disabled<br/>
         * 1 - Weak vibrations<br/>
         * 2 - Medium vibrations<br/>
         * 3 - Strong vibrations
         * @hide
         */
        public static final String ALARM_VIBRATION_INTENSITY =
                "alarm_vibration_intensity";

        /**
         * The intensity of media vibrations, if configurable.
         *
         * This includes any vibration that is part of media, such as music, movie, soundtrack,
         * game or animations.
         *
         * Not all devices are capable of changing their vibration intensity; on these devices
         * there will likely be no difference between the various vibration intensities except for
         * intensity 0 (off) and the rest.
         *
         * <b>Values:</b><br/>
         * 0 - Vibration is disabled<br/>
         * 1 - Weak vibrations<br/>
         * 2 - Medium vibrations<br/>
         * 3 - Strong vibrations
         * @hide
         */
        public static final String MEDIA_VIBRATION_INTENSITY =
                "media_vibration_intensity";

        /**
         * The intensity of notification vibrations, if configurable.
         *
         * Not all devices are capable of changing their vibration intensity; on these devices
         * there will likely be no difference between the various vibration intensities except for
         * intensity 0 (off) and the rest.
         *
         * <b>Values:</b><br/>
         * 0 - Vibration is disabled<br/>
         * 1 - Weak vibrations<br/>
         * 2 - Medium vibrations<br/>
         * 3 - Strong vibrations
         * @hide
         */
        @Readable
        public static final String NOTIFICATION_VIBRATION_INTENSITY =
                "notification_vibration_intensity";

        /**
         * The intensity of ringtone vibrations, if configurable.
         *
         * Not all devices are capable of changing their vibration intensity; on these devices
         * there will likely be no difference between the various vibration intensities except for
         * intensity 0 (off) and the rest.
         *
         * <b>Values:</b><br/>
         * 0 - Vibration is disabled<br/>
         * 1 - Weak vibrations<br/>
         * 2 - Medium vibrations<br/>
         * 3 - Strong vibrations
         * @hide
         */
        @Readable
        public static final String RING_VIBRATION_INTENSITY =
                "ring_vibration_intensity";

        /**
         * The intensity of haptic feedback vibrations, if configurable.
         *
         * Not all devices are capable of changing their feedback intensity; on these devices
         * there will likely be no difference between the various vibration intensities except for
         * intensity 0 (off) and the rest.
         *
         * <b>Values:</b><br/>
         * 0 - Vibration is disabled<br/>
         * 1 - Weak vibrations<br/>
         * 2 - Medium vibrations<br/>
         * 3 - Strong vibrations
         * @hide
         */
        @Readable
        public static final String HAPTIC_FEEDBACK_INTENSITY =
                "haptic_feedback_intensity";

        /**
         * The intensity of haptic feedback vibrations for interaction with hardware components from
         * the device, like buttons and sensors, if configurable.
         *
         * Not all devices are capable of changing their feedback intensity; on these devices
         * there will likely be no difference between the various vibration intensities except for
         * intensity 0 (off) and the rest.
         *
         * <b>Values:</b><br/>
         * 0 - Vibration is disabled<br/>
         * 1 - Weak vibrations<br/>
         * 2 - Medium vibrations<br/>
         * 3 - Strong vibrations
         * @hide
         */
        public static final String HARDWARE_HAPTIC_FEEDBACK_INTENSITY =
                "hardware_haptic_feedback_intensity";

        /**
         * Ringer volume. This is used internally, changing this value will not
         * change the volume. See AudioManager.
         *
         * @removed Not used by anything since API 2.
         */
        @Readable
        public static final String VOLUME_RING = "volume_ring";

        /**
         * System/notifications volume. This is used internally, changing this
         * value will not change the volume. See AudioManager.
         *
         * @removed Not used by anything since API 2.
         */
        @Readable
        public static final String VOLUME_SYSTEM = "volume_system";

        /**
         * Voice call volume. This is used internally, changing this value will
         * not change the volume. See AudioManager.
         *
         * @removed Not used by anything since API 2.
         */
        @Readable
        public static final String VOLUME_VOICE = "volume_voice";

        /**
         * Music/media/gaming volume. This is used internally, changing this
         * value will not change the volume. See AudioManager.
         *
         * @removed Not used by anything since API 2.
         */
        @Readable
        public static final String VOLUME_MUSIC = "volume_music";

        /**
         * Alarm volume. This is used internally, changing this
         * value will not change the volume. See AudioManager.
         *
         * @removed Not used by anything since API 2.
         */
        @Readable
        public static final String VOLUME_ALARM = "volume_alarm";

        /**
         * Notification volume. This is used internally, changing this
         * value will not change the volume. See AudioManager.
         *
         * @removed Not used by anything since API 2.
         */
        @Readable
        public static final String VOLUME_NOTIFICATION = "volume_notification";

        /**
         * Bluetooth Headset volume. This is used internally, changing this value will
         * not change the volume. See AudioManager.
         *
         * @removed Not used by anything since API 2.
         */
        @Readable
        public static final String VOLUME_BLUETOOTH_SCO = "volume_bluetooth_sco";

        /**
         * @hide
         * Acessibility volume. This is used internally, changing this
         * value will not change the volume.
         */
        @Readable
        public static final String VOLUME_ACCESSIBILITY = "volume_a11y";

        /**
         * @hide
         * Volume index for virtual assistant.
         */
        @Readable
        public static final String VOLUME_ASSISTANT = "volume_assistant";

        /**
         * Master volume (float in the range 0.0f to 1.0f).
         *
         * @hide
         */
        @Readable
        public static final String VOLUME_MASTER = "volume_master";

        /**
         * Master mono (int 1 = mono, 0 = normal).
         *
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String MASTER_MONO = "master_mono";

        /**
         * Master balance (float -1.f = 100% left, 0.f = dead center, 1.f = 100% right).
         *
         * @hide
         */
        @Readable
        public static final String MASTER_BALANCE = "master_balance";

        /**
         * Whether the notifications should use the ring volume (value of 1) or
         * a separate notification volume (value of 0). In most cases, users
         * will have this enabled so the notification and ringer volumes will be
         * the same. However, power users can disable this and use the separate
         * notification volume control.
         * <p>
         * Note: This is a one-off setting that will be removed in the future
         * when there is profile support. For this reason, it is kept hidden
         * from the public APIs.
         *
         * @hide
         * @deprecated
         */
        @Deprecated
        @Readable
        public static final String NOTIFICATIONS_USE_RING_VOLUME =
            "notifications_use_ring_volume";

        /**
         * Whether silent mode should allow vibration feedback. This is used
         * internally in AudioService and the Sound settings activity to
         * coordinate decoupling of vibrate and silent modes. This setting
         * will likely be removed in a future release with support for
         * audio/vibe feedback profiles.
         *
         * Not used anymore. On devices with vibrator, the user explicitly selects
         * silent or vibrate mode.
         * Kept for use by legacy database upgrade code in DatabaseHelper.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String VIBRATE_IN_SILENT = "vibrate_in_silent";

        /**
         * The mapping of stream type (integer) to its setting.
         *
         * @removed  Not used by anything since API 2.
         */
        public static final String[] VOLUME_SETTINGS = {
            VOLUME_VOICE, VOLUME_SYSTEM, VOLUME_RING, VOLUME_MUSIC,
            VOLUME_ALARM, VOLUME_NOTIFICATION, VOLUME_BLUETOOTH_SCO
        };

        /**
         * @hide
         * The mapping of stream type (integer) to its setting.
         * Unlike the VOLUME_SETTINGS array, this one contains as many entries as
         * AudioSystem.NUM_STREAM_TYPES, and has empty strings for stream types whose volumes
         * are never persisted.
         */
        public static final String[] VOLUME_SETTINGS_INT = {
                VOLUME_VOICE, VOLUME_SYSTEM, VOLUME_RING, VOLUME_MUSIC,
                VOLUME_ALARM, VOLUME_NOTIFICATION, VOLUME_BLUETOOTH_SCO,
                "" /*STREAM_SYSTEM_ENFORCED, no setting for this stream*/,
                "" /*STREAM_DTMF, no setting for this stream*/,
                "" /*STREAM_TTS, no setting for this stream*/,
                VOLUME_ACCESSIBILITY, VOLUME_ASSISTANT
            };

        /**
         * Appended to various volume related settings to record the previous
         * values before they the settings were affected by a silent/vibrate
         * ringer mode change.
         *
         * @removed  Not used by anything since API 2.
         */
        @Readable
        public static final String APPEND_FOR_LAST_AUDIBLE = "_last_audible";

        /**
         * Persistent store for the system-wide default ringtone URI.
         * <p>
         * If you need to play the default ringtone at any given time, it is recommended
         * you give {@link #DEFAULT_RINGTONE_URI} to the media player.  It will resolve
         * to the set default ringtone at the time of playing.
         *
         * @see #DEFAULT_RINGTONE_URI
         */
        @Readable
        public static final String RINGTONE = "ringtone";

        /**
         * A {@link Uri} that will point to the current default ringtone at any
         * given time.
         * <p>
         * If the current default ringtone is in the DRM provider and the caller
         * does not have permission, the exception will be a
         * FileNotFoundException.
         */
        public static final Uri DEFAULT_RINGTONE_URI = getUriFor(RINGTONE);

        /** {@hide} */
        @Readable
        public static final String RINGTONE_CACHE = "ringtone_cache";
        /** {@hide} */
        public static final Uri RINGTONE_CACHE_URI = getUriFor(RINGTONE_CACHE);

        /**
         * Persistent store for the system-wide default notification sound.
         *
         * @see #RINGTONE
         * @see #DEFAULT_NOTIFICATION_URI
         */
        @Readable
        public static final String NOTIFICATION_SOUND = "notification_sound";

        /**
         * A {@link Uri} that will point to the current default notification
         * sound at any given time.
         *
         * @see #DEFAULT_RINGTONE_URI
         */
        public static final Uri DEFAULT_NOTIFICATION_URI = getUriFor(NOTIFICATION_SOUND);

        /** {@hide} */
        @Readable
        public static final String NOTIFICATION_SOUND_CACHE = "notification_sound_cache";
        /** {@hide} */
        public static final Uri NOTIFICATION_SOUND_CACHE_URI = getUriFor(NOTIFICATION_SOUND_CACHE);

        /**
         * Persistent store for the system-wide default alarm alert.
         *
         * @see #RINGTONE
         * @see #DEFAULT_ALARM_ALERT_URI
         */
        @Readable
        public static final String ALARM_ALERT = "alarm_alert";

        /**
         * A {@link Uri} that will point to the current default alarm alert at
         * any given time.
         *
         * @see #DEFAULT_ALARM_ALERT_URI
         */
        public static final Uri DEFAULT_ALARM_ALERT_URI = getUriFor(ALARM_ALERT);

        /** {@hide} */
        @Readable
        public static final String ALARM_ALERT_CACHE = "alarm_alert_cache";
        /** {@hide} */
        public static final Uri ALARM_ALERT_CACHE_URI = getUriFor(ALARM_ALERT_CACHE);

        /**
         * Persistent store for the system default media button event receiver.
         *
         * @hide
         */
        @Readable(maxTargetSdk = Build.VERSION_CODES.R)
        public static final String MEDIA_BUTTON_RECEIVER = "media_button_receiver";

        /**
         * Setting to enable Auto Replace (AutoText) in text editors. 1 = On, 0 = Off
         */
        @Readable
        public static final String TEXT_AUTO_REPLACE = "auto_replace";

        /**
         * Setting to enable Auto Caps in text editors. 1 = On, 0 = Off
         */
        @Readable
        public static final String TEXT_AUTO_CAPS = "auto_caps";

        /**
         * Setting to enable Auto Punctuate in text editors. 1 = On, 0 = Off. This
         * feature converts two spaces to a "." and space.
         */
        @Readable
        public static final String TEXT_AUTO_PUNCTUATE = "auto_punctuate";

        /**
         * Setting to showing password characters in text editors. 1 = On, 0 = Off
         */
        @Readable
        public static final String TEXT_SHOW_PASSWORD = "show_password";

        @Readable
        public static final String SHOW_GTALK_SERVICE_STATUS =
                "SHOW_GTALK_SERVICE_STATUS";

        /**
         * Name of activity to use for wallpaper on the home screen.
         *
         * @deprecated Use {@link WallpaperManager} instead.
         */
        @Deprecated
        @Readable
        public static final String WALLPAPER_ACTIVITY = "wallpaper_activity";

        /**
         * @deprecated Use {@link android.provider.Settings.Global#AUTO_TIME}
         * instead
         */
        @Deprecated
        public static final String AUTO_TIME = Global.AUTO_TIME;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#AUTO_TIME_ZONE}
         * instead
         */
        @Deprecated
        public static final String AUTO_TIME_ZONE = Global.AUTO_TIME_ZONE;

        /**
         * Display the user's times, e.g. in the status bar, as 12 or 24 hours.
         * <ul>
         *    <li>24 = 24 hour</li>
         *    <li>12 = 12 hour</li>
         *    <li>[unset] = use the device locale's default</li>
         * </ul>
         */
        @Readable
        public static final String TIME_12_24 = "time_12_24";

        /**
         * @deprecated No longer used. Use {@link #TIME_12_24} instead.
         */
        @Deprecated
        @Readable
        public static final String DATE_FORMAT = "date_format";

        /**
         * Whether the setup wizard has been run before (on first boot), or if
         * it still needs to be run.
         *
         * nonzero = it has been run in the past
         * 0 = it has not been run in the past
         */
        @Readable
        public static final String SETUP_WIZARD_HAS_RUN = "setup_wizard_has_run";

        /**
         * Scaling factor for normal window animations. Setting to 0 will disable window
         * animations.
         *
         * @deprecated Use {@link Global#WINDOW_ANIMATION_SCALE} instead
         */
        @Deprecated
        public static final String WINDOW_ANIMATION_SCALE = Global.WINDOW_ANIMATION_SCALE;

        /**
         * Scaling factor for activity transition animations. Setting to 0 will disable window
         * animations.
         *
         * @deprecated Use {@link Global#TRANSITION_ANIMATION_SCALE} instead
         */
        @Deprecated
        public static final String TRANSITION_ANIMATION_SCALE = Global.TRANSITION_ANIMATION_SCALE;

        /**
         * Scaling factor for Animator-based animations. This affects both the start delay and
         * duration of all such animations. Setting to 0 will cause animations to end immediately.
         * The default value is 1.
         *
         * @deprecated Use {@link Global#ANIMATOR_DURATION_SCALE} instead
         */
        @Deprecated
        public static final String ANIMATOR_DURATION_SCALE = Global.ANIMATOR_DURATION_SCALE;

        /**
         * Control whether the accelerometer will be used to change screen
         * orientation.  If 0, it will not be used unless explicitly requested
         * by the application; if 1, it will be used by default unless explicitly
         * disabled by the application.
         */
        @Readable
        public static final String ACCELEROMETER_ROTATION = "accelerometer_rotation";

        /**
         * Default screen rotation when no other policy applies.
         * When {@link #ACCELEROMETER_ROTATION} is zero and no on-screen Activity expresses a
         * preference, this rotation value will be used. Must be one of the
         * {@link android.view.Surface#ROTATION_0 Surface rotation constants}.
         *
         * @see Display#getRotation
         */
        @Readable
        public static final String USER_ROTATION = "user_rotation";

        /**
         * Control whether the rotation lock toggle in the System UI should be hidden.
         * Typically this is done for accessibility purposes to make it harder for
         * the user to accidentally toggle the rotation lock while the display rotation
         * has been locked for accessibility.
         *
         * If 0, then rotation lock toggle is not hidden for accessibility (although it may be
         * unavailable for other reasons).  If 1, then the rotation lock toggle is hidden.
         *
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY =
                "hide_rotation_lock_toggle_for_accessibility";

        /**
         * Whether the phone vibrates when it is ringing due to an incoming call. This will
         * be used by Phone and Setting apps; it shouldn't affect other apps.
         * The value is boolean (1 or 0).
         *
         * Note: this is not same as "vibrate on ring", which had been available until ICS.
         * It was about AudioManager's setting and thus affected all the applications which
         * relied on the setting, while this is purely about the vibration setting for incoming
         * calls.
         *
         * @deprecated Replaced by using {@link android.os.VibrationAttributes#USAGE_RINGTONE} on
         * vibrations for incoming calls. User settings are applied automatically by the service and
         * should not be applied by individual apps.
         */
        @Deprecated
        @Readable
        public static final String VIBRATE_WHEN_RINGING = "vibrate_when_ringing";

        /**
         * When {@code 1}, Telecom enhanced call blocking functionality is enabled.  When
         * {@code 0}, enhanced call blocking functionality is disabled.
         * @hide
         */
        @Readable
        public static final String DEBUG_ENABLE_ENHANCED_CALL_BLOCKING =
                "debug.enable_enhanced_calling";

        /**
         * Whether the audible DTMF tones are played by the dialer when dialing. The value is
         * boolean (1 or 0).
         */
        @Readable
        public static final String DTMF_TONE_WHEN_DIALING = "dtmf_tone";

        /**
         * CDMA only settings
         * DTMF tone type played by the dialer when dialing.
         *                 0 = Normal
         *                 1 = Long
         */
        @Readable
        public static final String DTMF_TONE_TYPE_WHEN_DIALING = "dtmf_tone_type";

        /**
         * Whether the hearing aid is enabled. The value is
         * boolean (1 or 0).
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String HEARING_AID = "hearing_aid";

        /**
         * CDMA only settings
         * TTY Mode
         * 0 = OFF
         * 1 = FULL
         * 2 = VCO
         * 3 = HCO
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String TTY_MODE = "tty_mode";

        /**
         * Whether the sounds effects (key clicks, lid open ...) are enabled. The value is
         * boolean (1 or 0).
         */
        @Readable
        public static final String SOUND_EFFECTS_ENABLED = "sound_effects_enabled";

        /**
         * Whether haptic feedback (Vibrate on tap) is enabled. The value is
         * boolean (1 or 0).
         *
         * @deprecated Replaced by using {@link android.os.VibrationAttributes#USAGE_TOUCH} on
         * vibrations. User settings are applied automatically by the service and should not be
         * applied by individual apps.
         */
        @Deprecated
        @Readable
        public static final String HAPTIC_FEEDBACK_ENABLED = "haptic_feedback_enabled";

        /**
         * @deprecated Each application that shows web suggestions should have its own
         * setting for this.
         */
        @Deprecated
        @Readable
        public static final String SHOW_WEB_SUGGESTIONS = "show_web_suggestions";

        /**
         * Whether the notification LED should repeatedly flash when a notification is
         * pending. The value is boolean (1 or 0).
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String NOTIFICATION_LIGHT_PULSE = "notification_light_pulse";

        /**
         * Show pointer location on screen?
         * 0 = no
         * 1 = yes
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String POINTER_LOCATION = "pointer_location";

        /**
         * Show touch positions on screen?
         * 0 = no
         * 1 = yes
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String SHOW_TOUCHES = "show_touches";

        /**
         * Log raw orientation data from
         * {@link com.android.server.policy.WindowOrientationListener} for use with the
         * orientationplot.py tool.
         * 0 = no
         * 1 = yes
         * @hide
         */
        @Readable
        public static final String WINDOW_ORIENTATION_LISTENER_LOG =
                "window_orientation_listener_log";

        /**
         * @deprecated Use {@link android.provider.Settings.Global#POWER_SOUNDS_ENABLED}
         * instead
         * @hide
         */
        @Deprecated
        public static final String POWER_SOUNDS_ENABLED = Global.POWER_SOUNDS_ENABLED;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#DOCK_SOUNDS_ENABLED}
         * instead
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final String DOCK_SOUNDS_ENABLED = Global.DOCK_SOUNDS_ENABLED;

        /**
         * Whether to play sounds when the keyguard is shown and dismissed.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String LOCKSCREEN_SOUNDS_ENABLED = "lockscreen_sounds_enabled";

        /**
         * Whether the lockscreen should be completely disabled.
         * @hide
         */
        @Readable
        public static final String LOCKSCREEN_DISABLED = "lockscreen.disabled";

        /**
         * @deprecated Use {@link android.provider.Settings.Global#LOW_BATTERY_SOUND}
         * instead
         * @hide
         */
        @Deprecated
        public static final String LOW_BATTERY_SOUND = Global.LOW_BATTERY_SOUND;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#DESK_DOCK_SOUND}
         * instead
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final String DESK_DOCK_SOUND = Global.DESK_DOCK_SOUND;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#DESK_UNDOCK_SOUND}
         * instead
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final String DESK_UNDOCK_SOUND = Global.DESK_UNDOCK_SOUND;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#CAR_DOCK_SOUND}
         * instead
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final String CAR_DOCK_SOUND = Global.CAR_DOCK_SOUND;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#CAR_UNDOCK_SOUND}
         * instead
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final String CAR_UNDOCK_SOUND = Global.CAR_UNDOCK_SOUND;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#LOCK_SOUND}
         * instead
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final String LOCK_SOUND = Global.LOCK_SOUND;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#UNLOCK_SOUND}
         * instead
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final String UNLOCK_SOUND = Global.UNLOCK_SOUND;

        /**
         * Receive incoming SIP calls?
         * 0 = no
         * 1 = yes
         * @hide
         */
        @Readable
        public static final String SIP_RECEIVE_CALLS = "sip_receive_calls";

        /**
         * Call Preference String.
         * "SIP_ALWAYS" : Always use SIP with network access
         * "SIP_ADDRESS_ONLY" : Only if destination is a SIP address
         * @hide
         */
        @Readable
        public static final String SIP_CALL_OPTIONS = "sip_call_options";

        /**
         * One of the sip call options: Always use SIP with network access.
         * @hide
         */
        @Readable
        public static final String SIP_ALWAYS = "SIP_ALWAYS";

        /**
         * One of the sip call options: Only if destination is a SIP address.
         * @hide
         */
        @Readable
        public static final String SIP_ADDRESS_ONLY = "SIP_ADDRESS_ONLY";

        /**
         * @deprecated Use SIP_ALWAYS or SIP_ADDRESS_ONLY instead.  Formerly used to indicate that
         * the user should be prompted each time a call is made whether it should be placed using
         * SIP.  The {@link com.android.providers.settings.DatabaseHelper} replaces this with
         * SIP_ADDRESS_ONLY.
         * @hide
         */
        @Deprecated
        @Readable
        public static final String SIP_ASK_ME_EACH_TIME = "SIP_ASK_ME_EACH_TIME";

        /**
         * Pointer speed setting.
         * This is an integer value in a range between -7 and +7, so there are 15 possible values.
         *   -7 = slowest
         *    0 = default speed
         *   +7 = fastest
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String POINTER_SPEED = "pointer_speed";

        /**
         * Whether lock-to-app will be triggered by long-press on recents.
         * @hide
         */
        @Readable
        public static final String LOCK_TO_APP_ENABLED = "lock_to_app_enabled";

        /**
         * I am the lolrus.
         * <p>
         * Nonzero values indicate that the user has a bukkit.
         * Backward-compatible with <code>PrefGetPreference(prefAllowEasterEggs)</code>.
         * @hide
         */
        @Readable
        public static final String EGG_MODE = "egg_mode";

        /**
         * Setting to determine whether or not to show the battery percentage in the status bar.
         *    0 - Don't show percentage
         *    1 - Show percentage
         * @hide
         */
        @Readable
        public static final String SHOW_BATTERY_PERCENT = "status_bar_show_battery_percent";

        /**
         * Whether or not to enable multiple audio focus.
         * When enabled, requires more management by user over application playback activity,
         * for instance pausing media apps when another starts.
         * @hide
         */
        @Readable
        public static final String MULTI_AUDIO_FOCUS_ENABLED = "multi_audio_focus_enabled";

        /**
         * IMPORTANT: If you add a new public settings you also have to add it to
         * PUBLIC_SETTINGS below. If the new setting is hidden you have to add
         * it to PRIVATE_SETTINGS below. Also add a validator that can validate
         * the setting value. See an example above.
         */

        /**
         * Keys we no longer back up under the current schema, but want to continue to
         * process when restoring historical backup datasets.
         *
         * All settings in {@link LEGACY_RESTORE_SETTINGS} array *must* have a non-null validator,
         * otherwise they won't be restored.
         *
         * @hide
         */
        public static final String[] LEGACY_RESTORE_SETTINGS = {
        };

        /**
         * These are all public system settings
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final Set<String> PUBLIC_SETTINGS = new ArraySet<>();
        static {
            PUBLIC_SETTINGS.add(END_BUTTON_BEHAVIOR);
            PUBLIC_SETTINGS.add(WIFI_USE_STATIC_IP);
            PUBLIC_SETTINGS.add(WIFI_STATIC_IP);
            PUBLIC_SETTINGS.add(WIFI_STATIC_GATEWAY);
            PUBLIC_SETTINGS.add(WIFI_STATIC_NETMASK);
            PUBLIC_SETTINGS.add(WIFI_STATIC_DNS1);
            PUBLIC_SETTINGS.add(WIFI_STATIC_DNS2);
            PUBLIC_SETTINGS.add(BLUETOOTH_DISCOVERABILITY);
            PUBLIC_SETTINGS.add(BLUETOOTH_DISCOVERABILITY_TIMEOUT);
            PUBLIC_SETTINGS.add(NEXT_ALARM_FORMATTED);
            PUBLIC_SETTINGS.add(FONT_SCALE);
            PUBLIC_SETTINGS.add(SYSTEM_LOCALES);
            PUBLIC_SETTINGS.add(DIM_SCREEN);
            PUBLIC_SETTINGS.add(SCREEN_OFF_TIMEOUT);
            PUBLIC_SETTINGS.add(SCREEN_BRIGHTNESS);
            PUBLIC_SETTINGS.add(SCREEN_BRIGHTNESS_FLOAT);
            PUBLIC_SETTINGS.add(SCREEN_BRIGHTNESS_FOR_VR);
            PUBLIC_SETTINGS.add(SCREEN_BRIGHTNESS_FOR_VR_FLOAT);
            PUBLIC_SETTINGS.add(SCREEN_BRIGHTNESS_MODE);
            PUBLIC_SETTINGS.add(MODE_RINGER_STREAMS_AFFECTED);
            PUBLIC_SETTINGS.add(MUTE_STREAMS_AFFECTED);
            PUBLIC_SETTINGS.add(VIBRATE_ON);
            PUBLIC_SETTINGS.add(VOLUME_RING);
            PUBLIC_SETTINGS.add(VOLUME_SYSTEM);
            PUBLIC_SETTINGS.add(VOLUME_VOICE);
            PUBLIC_SETTINGS.add(VOLUME_MUSIC);
            PUBLIC_SETTINGS.add(VOLUME_ALARM);
            PUBLIC_SETTINGS.add(VOLUME_NOTIFICATION);
            PUBLIC_SETTINGS.add(VOLUME_BLUETOOTH_SCO);
            PUBLIC_SETTINGS.add(VOLUME_ASSISTANT);
            PUBLIC_SETTINGS.add(RINGTONE);
            PUBLIC_SETTINGS.add(NOTIFICATION_SOUND);
            PUBLIC_SETTINGS.add(ALARM_ALERT);
            PUBLIC_SETTINGS.add(TEXT_AUTO_REPLACE);
            PUBLIC_SETTINGS.add(TEXT_AUTO_CAPS);
            PUBLIC_SETTINGS.add(TEXT_AUTO_PUNCTUATE);
            PUBLIC_SETTINGS.add(TEXT_SHOW_PASSWORD);
            PUBLIC_SETTINGS.add(SHOW_GTALK_SERVICE_STATUS);
            PUBLIC_SETTINGS.add(WALLPAPER_ACTIVITY);
            PUBLIC_SETTINGS.add(TIME_12_24);
            PUBLIC_SETTINGS.add(DATE_FORMAT);
            PUBLIC_SETTINGS.add(SETUP_WIZARD_HAS_RUN);
            PUBLIC_SETTINGS.add(ACCELEROMETER_ROTATION);
            PUBLIC_SETTINGS.add(USER_ROTATION);
            PUBLIC_SETTINGS.add(DTMF_TONE_WHEN_DIALING);
            PUBLIC_SETTINGS.add(SOUND_EFFECTS_ENABLED);
            PUBLIC_SETTINGS.add(HAPTIC_FEEDBACK_ENABLED);
            PUBLIC_SETTINGS.add(SHOW_WEB_SUGGESTIONS);
            PUBLIC_SETTINGS.add(VIBRATE_WHEN_RINGING);
            PUBLIC_SETTINGS.add(APPLY_RAMPING_RINGER);
        }

        /**
         * These are all hidden system settings.
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final Set<String> PRIVATE_SETTINGS = new ArraySet<>();
        static {
            PRIVATE_SETTINGS.add(WIFI_USE_STATIC_IP);
            PRIVATE_SETTINGS.add(END_BUTTON_BEHAVIOR);
            PRIVATE_SETTINGS.add(ADVANCED_SETTINGS);
            PRIVATE_SETTINGS.add(WEAR_ACCESSIBILITY_GESTURE_ENABLED);
            PRIVATE_SETTINGS.add(SCREEN_AUTO_BRIGHTNESS_ADJ);
            PRIVATE_SETTINGS.add(VIBRATE_INPUT_DEVICES);
            PRIVATE_SETTINGS.add(VOLUME_MASTER);
            PRIVATE_SETTINGS.add(MASTER_MONO);
            PRIVATE_SETTINGS.add(MASTER_BALANCE);
            PRIVATE_SETTINGS.add(NOTIFICATIONS_USE_RING_VOLUME);
            PRIVATE_SETTINGS.add(VIBRATE_IN_SILENT);
            PRIVATE_SETTINGS.add(MEDIA_BUTTON_RECEIVER);
            PRIVATE_SETTINGS.add(HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY);
            PRIVATE_SETTINGS.add(DTMF_TONE_TYPE_WHEN_DIALING);
            PRIVATE_SETTINGS.add(HEARING_AID);
            PRIVATE_SETTINGS.add(TTY_MODE);
            PRIVATE_SETTINGS.add(NOTIFICATION_LIGHT_PULSE);
            PRIVATE_SETTINGS.add(POINTER_LOCATION);
            PRIVATE_SETTINGS.add(SHOW_TOUCHES);
            PRIVATE_SETTINGS.add(WINDOW_ORIENTATION_LISTENER_LOG);
            PRIVATE_SETTINGS.add(POWER_SOUNDS_ENABLED);
            PRIVATE_SETTINGS.add(DOCK_SOUNDS_ENABLED);
            PRIVATE_SETTINGS.add(LOCKSCREEN_SOUNDS_ENABLED);
            PRIVATE_SETTINGS.add(LOCKSCREEN_DISABLED);
            PRIVATE_SETTINGS.add(LOW_BATTERY_SOUND);
            PRIVATE_SETTINGS.add(DESK_DOCK_SOUND);
            PRIVATE_SETTINGS.add(DESK_UNDOCK_SOUND);
            PRIVATE_SETTINGS.add(CAR_DOCK_SOUND);
            PRIVATE_SETTINGS.add(CAR_UNDOCK_SOUND);
            PRIVATE_SETTINGS.add(LOCK_SOUND);
            PRIVATE_SETTINGS.add(UNLOCK_SOUND);
            PRIVATE_SETTINGS.add(SIP_RECEIVE_CALLS);
            PRIVATE_SETTINGS.add(SIP_CALL_OPTIONS);
            PRIVATE_SETTINGS.add(SIP_ALWAYS);
            PRIVATE_SETTINGS.add(SIP_ADDRESS_ONLY);
            PRIVATE_SETTINGS.add(SIP_ASK_ME_EACH_TIME);
            PRIVATE_SETTINGS.add(POINTER_SPEED);
            PRIVATE_SETTINGS.add(LOCK_TO_APP_ENABLED);
            PRIVATE_SETTINGS.add(EGG_MODE);
            PRIVATE_SETTINGS.add(SHOW_BATTERY_PERCENT);
            PRIVATE_SETTINGS.add(DISPLAY_COLOR_MODE);
            PRIVATE_SETTINGS.add(DISPLAY_COLOR_MODE_VENDOR_HINT);
        }

        /**
         * These entries are considered common between the personal and the managed profile,
         * since the managed profile doesn't get to change them.
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        private static final Set<String> CLONE_TO_MANAGED_PROFILE = new ArraySet<>();
        static {
            CLONE_TO_MANAGED_PROFILE.add(DATE_FORMAT);
            CLONE_TO_MANAGED_PROFILE.add(HAPTIC_FEEDBACK_ENABLED);
            CLONE_TO_MANAGED_PROFILE.add(SOUND_EFFECTS_ENABLED);
            CLONE_TO_MANAGED_PROFILE.add(TEXT_SHOW_PASSWORD);
            CLONE_TO_MANAGED_PROFILE.add(TIME_12_24);
        }

        /** @hide */
        public static void getCloneToManagedProfileSettings(Set<String> outKeySet) {
            outKeySet.addAll(CLONE_TO_MANAGED_PROFILE);
        }

        /**
         * These entries should be cloned from this profile's parent only if the dependency's
         * value is true ("1")
         *
         * Note: the dependencies must be Secure settings
         *
         * @hide
         */
        public static final Map<String, String> CLONE_FROM_PARENT_ON_VALUE = new ArrayMap<>();
        static {
            CLONE_FROM_PARENT_ON_VALUE.put(RINGTONE, Secure.SYNC_PARENT_SOUNDS);
            CLONE_FROM_PARENT_ON_VALUE.put(NOTIFICATION_SOUND, Secure.SYNC_PARENT_SOUNDS);
            CLONE_FROM_PARENT_ON_VALUE.put(ALARM_ALERT, Secure.SYNC_PARENT_SOUNDS);
        }

        /** @hide */
        public static void getCloneFromParentOnValueSettings(Map<String, String> outMap) {
            outMap.putAll(CLONE_FROM_PARENT_ON_VALUE);
        }

        /**
         * System settings which can be accessed by instant apps.
         * @hide
         */
        public static final Set<String> INSTANT_APP_SETTINGS = new ArraySet<>();
        static {
            INSTANT_APP_SETTINGS.add(TEXT_AUTO_REPLACE);
            INSTANT_APP_SETTINGS.add(TEXT_AUTO_CAPS);
            INSTANT_APP_SETTINGS.add(TEXT_AUTO_PUNCTUATE);
            INSTANT_APP_SETTINGS.add(TEXT_SHOW_PASSWORD);
            INSTANT_APP_SETTINGS.add(DATE_FORMAT);
            INSTANT_APP_SETTINGS.add(FONT_SCALE);
            INSTANT_APP_SETTINGS.add(HAPTIC_FEEDBACK_ENABLED);
            INSTANT_APP_SETTINGS.add(TIME_12_24);
            INSTANT_APP_SETTINGS.add(SOUND_EFFECTS_ENABLED);
            INSTANT_APP_SETTINGS.add(ACCELEROMETER_ROTATION);
        }

        /**
         * When to use Wi-Fi calling
         *
         * @see android.telephony.TelephonyManager.WifiCallingChoices
         * @hide
         */
        @Readable
        public static final String WHEN_TO_MAKE_WIFI_CALLS = "when_to_make_wifi_calls";

        // Settings moved to Settings.Secure

        /**
         * @deprecated Use {@link android.provider.Settings.Global#ADB_ENABLED}
         * instead
         */
        @Deprecated
        public static final String ADB_ENABLED = Global.ADB_ENABLED;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#ANDROID_ID} instead
         */
        @Deprecated
        public static final String ANDROID_ID = Secure.ANDROID_ID;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#BLUETOOTH_ON} instead
         */
        @Deprecated
        public static final String BLUETOOTH_ON = Global.BLUETOOTH_ON;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#DATA_ROAMING} instead
         */
        @Deprecated
        public static final String DATA_ROAMING = Global.DATA_ROAMING;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#DEVICE_PROVISIONED} instead
         */
        @Deprecated
        public static final String DEVICE_PROVISIONED = Global.DEVICE_PROVISIONED;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#HTTP_PROXY} instead
         */
        @Deprecated
        public static final String HTTP_PROXY = Global.HTTP_PROXY;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#INSTALL_NON_MARKET_APPS} instead
         */
        @Deprecated
        public static final String INSTALL_NON_MARKET_APPS = Secure.INSTALL_NON_MARKET_APPS;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#LOCATION_PROVIDERS_ALLOWED}
         * instead
         */
        @Deprecated
        public static final String LOCATION_PROVIDERS_ALLOWED = Secure.LOCATION_PROVIDERS_ALLOWED;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#LOGGING_ID} instead
         */
        @Deprecated
        public static final String LOGGING_ID = Secure.LOGGING_ID;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#NETWORK_PREFERENCE} instead
         */
        @Deprecated
        public static final String NETWORK_PREFERENCE = Global.NETWORK_PREFERENCE;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#PARENTAL_CONTROL_ENABLED}
         * instead
         */
        @Deprecated
        public static final String PARENTAL_CONTROL_ENABLED = Secure.PARENTAL_CONTROL_ENABLED;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#PARENTAL_CONTROL_LAST_UPDATE}
         * instead
         */
        @Deprecated
        public static final String PARENTAL_CONTROL_LAST_UPDATE = Secure.PARENTAL_CONTROL_LAST_UPDATE;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#PARENTAL_CONTROL_REDIRECT_URL}
         * instead
         */
        @Deprecated
        public static final String PARENTAL_CONTROL_REDIRECT_URL =
            Secure.PARENTAL_CONTROL_REDIRECT_URL;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#SETTINGS_CLASSNAME} instead
         */
        @Deprecated
        public static final String SETTINGS_CLASSNAME = Secure.SETTINGS_CLASSNAME;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#USB_MASS_STORAGE_ENABLED} instead
         */
        @Deprecated
        public static final String USB_MASS_STORAGE_ENABLED = Global.USB_MASS_STORAGE_ENABLED;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#USE_GOOGLE_MAIL} instead
         */
        @Deprecated
        public static final String USE_GOOGLE_MAIL = Global.USE_GOOGLE_MAIL;

       /**
         * @deprecated Use
         * {@link android.provider.Settings.Global#WIFI_MAX_DHCP_RETRY_COUNT} instead
         */
        @Deprecated
        public static final String WIFI_MAX_DHCP_RETRY_COUNT = Global.WIFI_MAX_DHCP_RETRY_COUNT;

        /**
         * @deprecated Use
         * {@link android.provider.Settings.Global#WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS} instead
         */
        @Deprecated
        public static final String WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS =
                Global.WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS;

        /**
         * @deprecated Use
         * {@link android.provider.Settings.Global#WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON} instead
         */
        @Deprecated
        public static final String WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON =
                Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON;

        /**
         * @deprecated Use
         * {@link android.provider.Settings.Global#WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY} instead
         */
        @Deprecated
        public static final String WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY =
                Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_NUM_OPEN_NETWORKS_KEPT}
         * instead
         */
        @Deprecated
        public static final String WIFI_NUM_OPEN_NETWORKS_KEPT = Global.WIFI_NUM_OPEN_NETWORKS_KEPT;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_ON} instead
         */
        @Deprecated
        public static final String WIFI_ON = Global.WIFI_ON;

        /**
         * @deprecated Use
         * {@link android.provider.Settings.Secure#WIFI_WATCHDOG_ACCEPTABLE_PACKET_LOSS_PERCENTAGE}
         * instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_ACCEPTABLE_PACKET_LOSS_PERCENTAGE =
                Secure.WIFI_WATCHDOG_ACCEPTABLE_PACKET_LOSS_PERCENTAGE;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#WIFI_WATCHDOG_AP_COUNT} instead
         */
        @Deprecated
        public static final String WIFI_WATCHDOG_AP_COUNT = Secure.WIFI_WATCHDOG_AP_COUNT;

        /**
         * @deprecated Use
         * {@link android.provider.Settings.Secure#WIFI_WATCHDOG_BACKGROUND_CHECK_DELAY_MS} instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_BACKGROUND_CHECK_DELAY_MS =
                Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_DELAY_MS;

        /**
         * @deprecated Use
         * {@link android.provider.Settings.Secure#WIFI_WATCHDOG_BACKGROUND_CHECK_ENABLED} instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_BACKGROUND_CHECK_ENABLED =
                Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_ENABLED;

        /**
         * @deprecated Use
         * {@link android.provider.Settings.Secure#WIFI_WATCHDOG_BACKGROUND_CHECK_TIMEOUT_MS}
         * instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_BACKGROUND_CHECK_TIMEOUT_MS =
                Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_TIMEOUT_MS;

        /**
         * @deprecated Use
         * {@link android.provider.Settings.Secure#WIFI_WATCHDOG_INITIAL_IGNORED_PING_COUNT} instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_INITIAL_IGNORED_PING_COUNT =
            Secure.WIFI_WATCHDOG_INITIAL_IGNORED_PING_COUNT;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#WIFI_WATCHDOG_MAX_AP_CHECKS}
         * instead
         */
        @Deprecated
        public static final String WIFI_WATCHDOG_MAX_AP_CHECKS = Secure.WIFI_WATCHDOG_MAX_AP_CHECKS;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_WATCHDOG_ON} instead
         */
        @Deprecated
        public static final String WIFI_WATCHDOG_ON = Global.WIFI_WATCHDOG_ON;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#WIFI_WATCHDOG_PING_COUNT} instead
         */
        @Deprecated
        public static final String WIFI_WATCHDOG_PING_COUNT = Secure.WIFI_WATCHDOG_PING_COUNT;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#WIFI_WATCHDOG_PING_DELAY_MS}
         * instead
         */
        @Deprecated
        public static final String WIFI_WATCHDOG_PING_DELAY_MS = Secure.WIFI_WATCHDOG_PING_DELAY_MS;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#WIFI_WATCHDOG_PING_TIMEOUT_MS}
         * instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_PING_TIMEOUT_MS =
            Secure.WIFI_WATCHDOG_PING_TIMEOUT_MS;

        /**
         * Checks if the specified app can modify system settings. As of API
         * level 23, an app cannot modify system settings unless it declares the
         * {@link android.Manifest.permission#WRITE_SETTINGS}
         * permission in its manifest, <em>and</em> the user specifically grants
         * the app this capability. To prompt the user to grant this approval,
         * the app must send an intent with the action {@link
         * android.provider.Settings#ACTION_MANAGE_WRITE_SETTINGS}, which causes
         * the system to display a permission management screen.
         *
         * @param context App context.
         * @return true if the calling app can write to system settings, false otherwise
         */
        public static boolean canWrite(Context context) {
            return isCallingPackageAllowedToWriteSettings(context, Process.myUid(),
                    context.getOpPackageName(), false);
        }
    }

    /**
     * Secure system settings, containing system preferences that applications
     * can read but are not allowed to write.  These are for preferences that
     * the user must explicitly modify through the UI of a system app. Normal
     * applications cannot modify the secure settings database, either directly
     * or by calling the "put" methods that this class contains.
     */
    public static final class Secure extends NameValueTable {
        // NOTE: If you add new settings here, be sure to add them to
        // com.android.providers.settings.SettingsProtoDumpUtil#dumpProtoSecureSettingsLocked.

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/secure");

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        private static final ContentProviderHolder sProviderHolder =
                new ContentProviderHolder(CONTENT_URI);

        // Populated lazily, guarded by class object:
        @UnsupportedAppUsage
        private static final NameValueCache sNameValueCache = new NameValueCache(
                CONTENT_URI,
                CALL_METHOD_GET_SECURE,
                CALL_METHOD_PUT_SECURE,
                CALL_METHOD_DELETE_SECURE,
                sProviderHolder,
                Secure.class);

        private static ILockSettings sLockSettings = null;

        private static boolean sIsSystemProcess;
        @UnsupportedAppUsage
        private static final HashSet<String> MOVED_TO_LOCK_SETTINGS;
        @UnsupportedAppUsage
        private static final HashSet<String> MOVED_TO_GLOBAL;
        static {
            MOVED_TO_LOCK_SETTINGS = new HashSet<>(3);
            MOVED_TO_LOCK_SETTINGS.add(Secure.LOCK_PATTERN_ENABLED);
            MOVED_TO_LOCK_SETTINGS.add(Secure.LOCK_PATTERN_VISIBLE);
            MOVED_TO_LOCK_SETTINGS.add(Secure.LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED);

            MOVED_TO_GLOBAL = new HashSet<>();
            MOVED_TO_GLOBAL.add(Settings.Global.ADB_ENABLED);
            MOVED_TO_GLOBAL.add(Settings.Global.ASSISTED_GPS_ENABLED);
            MOVED_TO_GLOBAL.add(Settings.Global.BLUETOOTH_ON);
            MOVED_TO_GLOBAL.add(Settings.Global.BUGREPORT_IN_POWER_MENU);
            MOVED_TO_GLOBAL.add(Settings.Global.CDMA_CELL_BROADCAST_SMS);
            MOVED_TO_GLOBAL.add(Settings.Global.CDMA_ROAMING_MODE);
            MOVED_TO_GLOBAL.add(Settings.Global.CDMA_SUBSCRIPTION_MODE);
            MOVED_TO_GLOBAL.add(Settings.Global.DATA_ACTIVITY_TIMEOUT_MOBILE);
            MOVED_TO_GLOBAL.add(Settings.Global.DATA_ACTIVITY_TIMEOUT_WIFI);
            MOVED_TO_GLOBAL.add(Settings.Global.DATA_ROAMING);
            MOVED_TO_GLOBAL.add(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED);
            MOVED_TO_GLOBAL.add(Settings.Global.DEVICE_PROVISIONED);
            MOVED_TO_GLOBAL.add(Settings.Global.DISPLAY_SIZE_FORCED);
            MOVED_TO_GLOBAL.add(Settings.Global.DOWNLOAD_MAX_BYTES_OVER_MOBILE);
            MOVED_TO_GLOBAL.add(Settings.Global.DOWNLOAD_RECOMMENDED_MAX_BYTES_OVER_MOBILE);
            MOVED_TO_GLOBAL.add(Settings.Global.MOBILE_DATA);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_DEV_BUCKET_DURATION);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_DEV_DELETE_AGE);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_DEV_PERSIST_BYTES);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_DEV_ROTATE_AGE);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_ENABLED);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_GLOBAL_ALERT_BYTES);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_POLL_INTERVAL);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_SAMPLE_ENABLED);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_TIME_CACHE_MAX_AGE);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_UID_BUCKET_DURATION);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_UID_DELETE_AGE);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_UID_PERSIST_BYTES);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_UID_ROTATE_AGE);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_UID_TAG_BUCKET_DURATION);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_UID_TAG_DELETE_AGE);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_UID_TAG_PERSIST_BYTES);
            MOVED_TO_GLOBAL.add(Settings.Global.NETSTATS_UID_TAG_ROTATE_AGE);
            MOVED_TO_GLOBAL.add(Settings.Global.NETWORK_PREFERENCE);
            MOVED_TO_GLOBAL.add(Settings.Global.NITZ_UPDATE_DIFF);
            MOVED_TO_GLOBAL.add(Settings.Global.NITZ_UPDATE_SPACING);
            MOVED_TO_GLOBAL.add(Settings.Global.NTP_SERVER);
            MOVED_TO_GLOBAL.add(Settings.Global.NTP_TIMEOUT);
            MOVED_TO_GLOBAL.add(Settings.Global.PDP_WATCHDOG_ERROR_POLL_COUNT);
            MOVED_TO_GLOBAL.add(Settings.Global.PDP_WATCHDOG_LONG_POLL_INTERVAL_MS);
            MOVED_TO_GLOBAL.add(Settings.Global.PDP_WATCHDOG_MAX_PDP_RESET_FAIL_COUNT);
            MOVED_TO_GLOBAL.add(Settings.Global.PDP_WATCHDOG_POLL_INTERVAL_MS);
            MOVED_TO_GLOBAL.add(Settings.Global.PDP_WATCHDOG_TRIGGER_PACKET_COUNT);
            MOVED_TO_GLOBAL.add(Settings.Global.SETUP_PREPAID_DATA_SERVICE_URL);
            MOVED_TO_GLOBAL.add(Settings.Global.SETUP_PREPAID_DETECTION_REDIR_HOST);
            MOVED_TO_GLOBAL.add(Settings.Global.SETUP_PREPAID_DETECTION_TARGET_URL);
            MOVED_TO_GLOBAL.add(Settings.Global.TETHER_DUN_APN);
            MOVED_TO_GLOBAL.add(Settings.Global.TETHER_DUN_REQUIRED);
            MOVED_TO_GLOBAL.add(Settings.Global.TETHER_SUPPORTED);
            MOVED_TO_GLOBAL.add(Settings.Global.USB_MASS_STORAGE_ENABLED);
            MOVED_TO_GLOBAL.add(Settings.Global.USE_GOOGLE_MAIL);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_COUNTRY_CODE);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_FRAMEWORK_SCAN_INTERVAL_MS);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_FREQUENCY_BAND);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_IDLE_MS);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_MAX_DHCP_RETRY_COUNT);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_NUM_OPEN_NETWORKS_KEPT);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_ON);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_P2P_DEVICE_NAME);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_SUPPLICANT_SCAN_INTERVAL_MS);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_ENHANCED_AUTO_JOIN);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_NETWORK_SHOW_RSSI);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_WATCHDOG_ON);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_WATCHDOG_POOR_NETWORK_TEST_ENABLED);
            MOVED_TO_GLOBAL.add(Settings.Global.WIFI_P2P_PENDING_FACTORY_RESET);
            MOVED_TO_GLOBAL.add(Settings.Global.WIMAX_NETWORKS_AVAILABLE_NOTIFICATION_ON);
            MOVED_TO_GLOBAL.add(Settings.Global.PACKAGE_VERIFIER_TIMEOUT);
            MOVED_TO_GLOBAL.add(Settings.Global.PACKAGE_VERIFIER_DEFAULT_RESPONSE);
            MOVED_TO_GLOBAL.add(Settings.Global.DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS);
            MOVED_TO_GLOBAL.add(Settings.Global.DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS);
            MOVED_TO_GLOBAL.add(Settings.Global.GPRS_REGISTER_CHECK_PERIOD_MS);
            MOVED_TO_GLOBAL.add(Settings.Global.WTF_IS_FATAL);
            MOVED_TO_GLOBAL.add(Settings.Global.BATTERY_DISCHARGE_DURATION_THRESHOLD);
            MOVED_TO_GLOBAL.add(Settings.Global.BATTERY_DISCHARGE_THRESHOLD);
            MOVED_TO_GLOBAL.add(Settings.Global.SEND_ACTION_APP_ERROR);
            MOVED_TO_GLOBAL.add(Settings.Global.DROPBOX_AGE_SECONDS);
            MOVED_TO_GLOBAL.add(Settings.Global.DROPBOX_MAX_FILES);
            MOVED_TO_GLOBAL.add(Settings.Global.DROPBOX_QUOTA_KB);
            MOVED_TO_GLOBAL.add(Settings.Global.DROPBOX_QUOTA_PERCENT);
            MOVED_TO_GLOBAL.add(Settings.Global.DROPBOX_RESERVE_PERCENT);
            MOVED_TO_GLOBAL.add(Settings.Global.DROPBOX_TAG_PREFIX);
            MOVED_TO_GLOBAL.add(Settings.Global.ERROR_LOGCAT_PREFIX);
            MOVED_TO_GLOBAL.add(Settings.Global.SYS_FREE_STORAGE_LOG_INTERVAL);
            MOVED_TO_GLOBAL.add(Settings.Global.DISK_FREE_CHANGE_REPORTING_THRESHOLD);
            MOVED_TO_GLOBAL.add(Settings.Global.SYS_STORAGE_THRESHOLD_PERCENTAGE);
            MOVED_TO_GLOBAL.add(Settings.Global.SYS_STORAGE_THRESHOLD_MAX_BYTES);
            MOVED_TO_GLOBAL.add(Settings.Global.SYS_STORAGE_FULL_THRESHOLD_BYTES);
            MOVED_TO_GLOBAL.add(Settings.Global.SYNC_MAX_RETRY_DELAY_IN_SECONDS);
            MOVED_TO_GLOBAL.add(Settings.Global.CONNECTIVITY_CHANGE_DELAY);
            MOVED_TO_GLOBAL.add(Settings.Global.CAPTIVE_PORTAL_DETECTION_ENABLED);
            MOVED_TO_GLOBAL.add(Settings.Global.CAPTIVE_PORTAL_SERVER);
            MOVED_TO_GLOBAL.add(Settings.Global.SET_INSTALL_LOCATION);
            MOVED_TO_GLOBAL.add(Settings.Global.DEFAULT_INSTALL_LOCATION);
            MOVED_TO_GLOBAL.add(Settings.Global.INET_CONDITION_DEBOUNCE_UP_DELAY);
            MOVED_TO_GLOBAL.add(Settings.Global.INET_CONDITION_DEBOUNCE_DOWN_DELAY);
            MOVED_TO_GLOBAL.add(Settings.Global.READ_EXTERNAL_STORAGE_ENFORCED_DEFAULT);
            MOVED_TO_GLOBAL.add(Settings.Global.HTTP_PROXY);
            MOVED_TO_GLOBAL.add(Settings.Global.GLOBAL_HTTP_PROXY_HOST);
            MOVED_TO_GLOBAL.add(Settings.Global.GLOBAL_HTTP_PROXY_PORT);
            MOVED_TO_GLOBAL.add(Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST);
            MOVED_TO_GLOBAL.add(Settings.Global.SET_GLOBAL_HTTP_PROXY);
            MOVED_TO_GLOBAL.add(Settings.Global.DEFAULT_DNS_SERVER);
            MOVED_TO_GLOBAL.add(Settings.Global.PREFERRED_NETWORK_MODE);
            MOVED_TO_GLOBAL.add(Settings.Global.WEBVIEW_DATA_REDUCTION_PROXY_KEY);
        }

        /** @hide */
        public static void getMovedToGlobalSettings(Set<String> outKeySet) {
            outKeySet.addAll(MOVED_TO_GLOBAL);
        }

        /** @hide */
        public static void getMovedToSystemSettings(Set<String> outKeySet) {
        }

        /** @hide */
        public static void clearProviderForTest() {
            sProviderHolder.clearProviderForTest();
            sNameValueCache.clearGenerationTrackerForTest();
        }

        /** @hide */
        public static void getPublicSettings(Set<String> allKeys, Set<String> readableKeys,
                ArrayMap<String, Integer> readableKeysWithMaxTargetSdk) {
            getPublicSettingsForClass(Secure.class, allKeys, readableKeys,
                    readableKeysWithMaxTargetSdk);
        }

        /**
         * Look up a name in the database.
         * @param resolver to access the database with
         * @param name to look up in the table
         * @return the corresponding value, or null if not present
         */
        public static String getString(ContentResolver resolver, String name) {
            return getStringForUser(resolver, name, resolver.getUserId());
        }

        /** @hide */
        @UnsupportedAppUsage
        public static String getStringForUser(ContentResolver resolver, String name,
                int userHandle) {
            if (MOVED_TO_GLOBAL.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.Secure"
                        + " to android.provider.Settings.Global.");
                return Global.getStringForUser(resolver, name, userHandle);
            }

            if (MOVED_TO_LOCK_SETTINGS.contains(name)) {
                synchronized (Secure.class) {
                    if (sLockSettings == null) {
                        sLockSettings = ILockSettings.Stub.asInterface(
                                (IBinder) ServiceManager.getService("lock_settings"));
                        sIsSystemProcess = Process.myUid() == Process.SYSTEM_UID;
                    }
                }
                if (sLockSettings != null && !sIsSystemProcess) {
                    // No context; use the ActivityThread's context as an approximation for
                    // determining the target API level.
                    Application application = ActivityThread.currentApplication();

                    boolean isPreMnc = application != null
                            && application.getApplicationInfo() != null
                            && application.getApplicationInfo().targetSdkVersion
                            <= VERSION_CODES.LOLLIPOP_MR1;
                    if (isPreMnc) {
                        try {
                            return sLockSettings.getString(name, "0", userHandle);
                        } catch (RemoteException re) {
                            // Fall through
                        }
                    } else {
                        throw new SecurityException("Settings.Secure." + name
                                + " is deprecated and no longer accessible."
                                + " See API documentation for potential replacements.");
                    }
                }
            }

            return sNameValueCache.getStringForUser(resolver, name, userHandle);
        }

        /**
         * Store a name/value pair into the database. Values written by this method will be
         * overridden if a restore happens in the future.
         *
         * @param resolver to access the database with
         * @param name to store
         * @param value to associate with the name
         * @return true if the value was set, false on database errors
         *
         * @hide
         */
        @RequiresPermission(Manifest.permission.MODIFY_SETTINGS_OVERRIDEABLE_BY_RESTORE)
        public static boolean putString(ContentResolver resolver, String name,
                String value, boolean overrideableByRestore) {
            return putStringForUser(resolver, name, value, /* tag */ null, /* makeDefault */ false,
                    resolver.getUserId(), overrideableByRestore);
        }

        /**
         * Store a name/value pair into the database.
         * @param resolver to access the database with
         * @param name to store
         * @param value to associate with the name
         * @return true if the value was set, false on database errors
         */
        public static boolean putString(ContentResolver resolver, String name, String value) {
            return putStringForUser(resolver, name, value, resolver.getUserId());
        }

        /** @hide */
        @UnsupportedAppUsage
        public static boolean putStringForUser(ContentResolver resolver, String name, String value,
                int userHandle) {
            return putStringForUser(resolver, name, value, null, false, userHandle,
                    DEFAULT_OVERRIDEABLE_BY_RESTORE);
        }

        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static boolean putStringForUser(@NonNull ContentResolver resolver,
                @NonNull String name, @Nullable String value, @Nullable String tag,
                boolean makeDefault, @UserIdInt int userHandle, boolean overrideableByRestore) {
            if (MOVED_TO_GLOBAL.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.Secure"
                        + " to android.provider.Settings.Global");
                return Global.putStringForUser(resolver, name, value,
                        tag, makeDefault, userHandle, DEFAULT_OVERRIDEABLE_BY_RESTORE);
            }
            return sNameValueCache.putStringForUser(resolver, name, value, tag,
                    makeDefault, userHandle, overrideableByRestore);
        }

        /**
         * Store a name/value pair into the database.
         * <p>
         * The method takes an optional tag to associate with the setting
         * which can be used to clear only settings made by your package and
         * associated with this tag by passing the tag to {@link
         * #resetToDefaults(ContentResolver, String)}. Anyone can override
         * the current tag. Also if another package changes the setting
         * then the tag will be set to the one specified in the set call
         * which can be null. Also any of the settings setters that do not
         * take a tag as an argument effectively clears the tag.
         * </p><p>
         * For example, if you set settings A and B with tags T1 and T2 and
         * another app changes setting A (potentially to the same value), it
         * can assign to it a tag T3 (note that now the package that changed
         * the setting is not yours). Now if you reset your changes for T1 and
         * T2 only setting B will be reset and A not (as it was changed by
         * another package) but since A did not change you are in the desired
         * initial state. Now if the other app changes the value of A (assuming
         * you registered an observer in the beginning) you would detect that
         * the setting was changed by another app and handle this appropriately
         * (ignore, set back to some value, etc).
         * </p><p>
         * Also the method takes an argument whether to make the value the
         * default for this setting. If the system already specified a default
         * value, then the one passed in here will <strong>not</strong>
         * be set as the default.
         * </p>
         *
         * @param resolver to access the database with.
         * @param name to store.
         * @param value to associate with the name.
         * @param tag to associate with the setting.
         * @param makeDefault whether to make the value the default one.
         * @return true if the value was set, false on database errors.
         *
         * @see #resetToDefaults(ContentResolver, String)
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
        public static boolean putString(@NonNull ContentResolver resolver,
                @NonNull String name, @Nullable String value, @Nullable String tag,
                boolean makeDefault) {
            return putStringForUser(resolver, name, value, tag, makeDefault,
                    resolver.getUserId(), DEFAULT_OVERRIDEABLE_BY_RESTORE);
        }

        /**
         * Reset the settings to their defaults. This would reset <strong>only</strong>
         * settings set by the caller's package. Think of it of a way to undo your own
         * changes to the global settings. Passing in the optional tag will reset only
         * settings changed by your package and associated with this tag.
         *
         * @param resolver Handle to the content resolver.
         * @param tag Optional tag which should be associated with the settings to reset.
         *
         * @see #putString(ContentResolver, String, String, String, boolean)
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
        public static void resetToDefaults(@NonNull ContentResolver resolver,
                @Nullable String tag) {
            resetToDefaultsAsUser(resolver, tag, RESET_MODE_PACKAGE_DEFAULTS,
                    resolver.getUserId());
        }

        /**
         *
         * Reset the settings to their defaults for a given user with a specific mode. The
         * optional tag argument is valid only for {@link #RESET_MODE_PACKAGE_DEFAULTS}
         * allowing resetting the settings made by a package and associated with the tag.
         *
         * @param resolver Handle to the content resolver.
         * @param tag Optional tag which should be associated with the settings to reset.
         * @param mode The reset mode.
         * @param userHandle The user for which to reset to defaults.
         *
         * @see #RESET_MODE_PACKAGE_DEFAULTS
         * @see #RESET_MODE_UNTRUSTED_DEFAULTS
         * @see #RESET_MODE_UNTRUSTED_CHANGES
         * @see #RESET_MODE_TRUSTED_DEFAULTS
         *
         * @hide
         */
        public static void resetToDefaultsAsUser(@NonNull ContentResolver resolver,
                @Nullable String tag, @ResetMode int mode, @IntRange(from = 0) int userHandle) {
            try {
                Bundle arg = new Bundle();
                arg.putInt(CALL_METHOD_USER_KEY, userHandle);
                if (tag != null) {
                    arg.putString(CALL_METHOD_TAG_KEY, tag);
                }
                arg.putInt(CALL_METHOD_RESET_MODE_KEY, mode);
                IContentProvider cp = sProviderHolder.getProvider(resolver);
                cp.call(resolver.getAttributionSource(),
                        sProviderHolder.mUri.getAuthority(), CALL_METHOD_RESET_SECURE, null, arg);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't reset do defaults for " + CONTENT_URI, e);
            }
        }

        /**
         * Construct the content URI for a particular name/value pair,
         * useful for monitoring changes with a ContentObserver.
         * @param name to look up in the table
         * @return the corresponding content URI, or null if not present
         */
        public static Uri getUriFor(String name) {
            if (MOVED_TO_GLOBAL.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.Secure"
                        + " to android.provider.Settings.Global, returning global URI.");
                return Global.getUriFor(Global.CONTENT_URI, name);
            }
            return getUriFor(CONTENT_URI, name);
        }

        /**
         * Convenience function for retrieving a single secure settings value
         * as an integer.  Note that internally setting values are always
         * stored as strings; this function converts the string to an integer
         * for you.  The default value will be returned if the setting is
         * not defined or not an integer.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         * @param def Value to return if the setting is not defined.
         *
         * @return The setting's current value, or 'def' if it is not defined
         * or not a valid integer.
         */
        public static int getInt(ContentResolver cr, String name, int def) {
            return getIntForUser(cr, name, def, cr.getUserId());
        }

        /** @hide */
        @UnsupportedAppUsage
        public static int getIntForUser(ContentResolver cr, String name, int def, int userHandle) {
            String v = getStringForUser(cr, name, userHandle);
            return parseIntSettingWithDefault(v, def);
        }

        /**
         * Convenience function for retrieving a single secure settings value
         * as an integer.  Note that internally setting values are always
         * stored as strings; this function converts the string to an integer
         * for you.
         * <p>
         * This version does not take a default value.  If the setting has not
         * been set, or the string value is not a number,
         * it throws {@link SettingNotFoundException}.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         *
         * @throws SettingNotFoundException Thrown if a setting by the given
         * name can't be found or the setting value is not an integer.
         *
         * @return The setting's current value.
         */
        public static int getInt(ContentResolver cr, String name)
                throws SettingNotFoundException {
            return getIntForUser(cr, name, cr.getUserId());
        }

        /** @hide */
        public static int getIntForUser(ContentResolver cr, String name, int userHandle)
                throws SettingNotFoundException {
            String v = getStringForUser(cr, name, userHandle);
            return parseIntSetting(v, name);
        }

        /**
         * Convenience function for updating a single settings value as an
         * integer. This will either create a new entry in the table if the
         * given name does not exist, or modify the value of the existing row
         * with that name.  Note that internally setting values are always
         * stored as strings, so this function converts the given value to a
         * string before storing it.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to modify.
         * @param value The new value for the setting.
         * @return true if the value was set, false on database errors
         */
        public static boolean putInt(ContentResolver cr, String name, int value) {
            return putIntForUser(cr, name, value, cr.getUserId());
        }

        /** @hide */
        @UnsupportedAppUsage
        public static boolean putIntForUser(ContentResolver cr, String name, int value,
                int userHandle) {
            return putStringForUser(cr, name, Integer.toString(value), userHandle);
        }

        /**
         * Convenience function for retrieving a single secure settings value
         * as a {@code long}.  Note that internally setting values are always
         * stored as strings; this function converts the string to a {@code long}
         * for you.  The default value will be returned if the setting is
         * not defined or not a {@code long}.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         * @param def Value to return if the setting is not defined.
         *
         * @return The setting's current value, or 'def' if it is not defined
         * or not a valid {@code long}.
         */
        public static long getLong(ContentResolver cr, String name, long def) {
            return getLongForUser(cr, name, def, cr.getUserId());
        }

        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static long getLongForUser(ContentResolver cr, String name, long def,
                int userHandle) {
            String v = getStringForUser(cr, name, userHandle);
            return parseLongSettingWithDefault(v, def);
        }

        /**
         * Convenience function for retrieving a single secure settings value
         * as a {@code long}.  Note that internally setting values are always
         * stored as strings; this function converts the string to a {@code long}
         * for you.
         * <p>
         * This version does not take a default value.  If the setting has not
         * been set, or the string value is not a number,
         * it throws {@link SettingNotFoundException}.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         *
         * @return The setting's current value.
         * @throws SettingNotFoundException Thrown if a setting by the given
         * name can't be found or the setting value is not an integer.
         */
        public static long getLong(ContentResolver cr, String name)
                throws SettingNotFoundException {
            return getLongForUser(cr, name, cr.getUserId());
        }

        /** @hide */
        public static long getLongForUser(ContentResolver cr, String name, int userHandle)
                throws SettingNotFoundException {
            String v = getStringForUser(cr, name, userHandle);
            return parseLongSetting(v, name);
        }

        /**
         * Convenience function for updating a secure settings value as a long
         * integer. This will either create a new entry in the table if the
         * given name does not exist, or modify the value of the existing row
         * with that name.  Note that internally setting values are always
         * stored as strings, so this function converts the given value to a
         * string before storing it.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to modify.
         * @param value The new value for the setting.
         * @return true if the value was set, false on database errors
         */
        public static boolean putLong(ContentResolver cr, String name, long value) {
            return putLongForUser(cr, name, value, cr.getUserId());
        }

        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static boolean putLongForUser(ContentResolver cr, String name, long value,
                int userHandle) {
            return putStringForUser(cr, name, Long.toString(value), userHandle);
        }

        /**
         * Convenience function for retrieving a single secure settings value
         * as a floating point number.  Note that internally setting values are
         * always stored as strings; this function converts the string to an
         * float for you. The default value will be returned if the setting
         * is not defined or not a valid float.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         * @param def Value to return if the setting is not defined.
         *
         * @return The setting's current value, or 'def' if it is not defined
         * or not a valid float.
         */
        public static float getFloat(ContentResolver cr, String name, float def) {
            return getFloatForUser(cr, name, def, cr.getUserId());
        }

        /** @hide */
        public static float getFloatForUser(ContentResolver cr, String name, float def,
                int userHandle) {
            String v = getStringForUser(cr, name, userHandle);
            return parseFloatSettingWithDefault(v, def);
        }

        /**
         * Convenience function for retrieving a single secure settings value
         * as a float.  Note that internally setting values are always
         * stored as strings; this function converts the string to a float
         * for you.
         * <p>
         * This version does not take a default value.  If the setting has not
         * been set, or the string value is not a number,
         * it throws {@link SettingNotFoundException}.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         *
         * @throws SettingNotFoundException Thrown if a setting by the given
         * name can't be found or the setting value is not a float.
         *
         * @return The setting's current value.
         */
        public static float getFloat(ContentResolver cr, String name)
                throws SettingNotFoundException {
            return getFloatForUser(cr, name, cr.getUserId());
        }

        /** @hide */
        public static float getFloatForUser(ContentResolver cr, String name, int userHandle)
                throws SettingNotFoundException {
            String v = getStringForUser(cr, name, userHandle);
            return parseFloatSetting(v, name);
        }

        /**
         * Convenience function for updating a single settings value as a
         * floating point number. This will either create a new entry in the
         * table if the given name does not exist, or modify the value of the
         * existing row with that name.  Note that internally setting values
         * are always stored as strings, so this function converts the given
         * value to a string before storing it.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to modify.
         * @param value The new value for the setting.
         * @return true if the value was set, false on database errors
         */
        public static boolean putFloat(ContentResolver cr, String name, float value) {
            return putFloatForUser(cr, name, value, cr.getUserId());
        }

        /** @hide */
        public static boolean putFloatForUser(ContentResolver cr, String name, float value,
                int userHandle) {
            return putStringForUser(cr, name, Float.toString(value), userHandle);
        }

        /**
         * Control whether to enable adaptive sleep mode.
         * @hide
         */
        @Readable
        public static final String ADAPTIVE_SLEEP = "adaptive_sleep";

        /**
         * Setting key to indicate whether camera-based autorotate is enabled.
         *
         * @hide
         */
        public static final String CAMERA_AUTOROTATE = "camera_autorotate";

        /**
         * @deprecated Use {@link android.provider.Settings.Global#DEVELOPMENT_SETTINGS_ENABLED}
         * instead
         */
        @Deprecated
        public static final String DEVELOPMENT_SETTINGS_ENABLED =
                Global.DEVELOPMENT_SETTINGS_ENABLED;

        /**
         * When the user has enable the option to have a "bug report" command
         * in the power menu.
         * @deprecated Use {@link android.provider.Settings.Global#BUGREPORT_IN_POWER_MENU} instead
         * @hide
         */
        @Deprecated
        @Readable
        public static final String BUGREPORT_IN_POWER_MENU = "bugreport_in_power_menu";

        /**
         * @deprecated Use {@link android.provider.Settings.Global#ADB_ENABLED} instead
         */
        @Deprecated
        public static final String ADB_ENABLED = Global.ADB_ENABLED;

        /**
         * Setting to allow mock locations and location provider status to be injected into the
         * LocationManager service for testing purposes during application development.  These
         * locations and status values  override actual location and status information generated
         * by network, gps, or other location providers.
         *
         * @deprecated This settings is not used anymore.
         */
        @Deprecated
        @Readable
        public static final String ALLOW_MOCK_LOCATION = "mock_location";

        /**
         * This is used by Bluetooth Manager to store adapter name
         * @hide
         */
        @Readable(maxTargetSdk = Build.VERSION_CODES.S)
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLUETOOTH_NAME = "bluetooth_name";

        /**
         * This is used by Bluetooth Manager to store adapter address
         * @hide
         */
        @Readable(maxTargetSdk = Build.VERSION_CODES.S)
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLUETOOTH_ADDRESS = "bluetooth_address";

        /**
         * This is used by Bluetooth Manager to store whether adapter address is valid
         * @hide
         */
        @Readable(maxTargetSdk = Build.VERSION_CODES.S)
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLUETOOTH_ADDR_VALID = "bluetooth_addr_valid";

        /**
         * Setting to indicate that on device captions are enabled.
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String ODI_CAPTIONS_ENABLED = "odi_captions_enabled";


        /**
         * Setting to indicate live caption button show or hide in the volume
         * rocker.
         *
         * @hide
         */
        public static final String ODI_CAPTIONS_VOLUME_UI_ENABLED =
                "odi_captions_volume_ui_enabled";

        /**
         * On Android 8.0 (API level 26) and higher versions of the platform,
         * a 64-bit number (expressed as a hexadecimal string), unique to
         * each combination of app-signing key, user, and device.
         * Values of {@code ANDROID_ID} are scoped by signing key and user.
         * The value may change if a factory reset is performed on the
         * device or if an APK signing key changes.
         *
         * For more information about how the platform handles {@code ANDROID_ID}
         * in Android 8.0 (API level 26) and higher, see <a
         * href="{@docRoot}about/versions/oreo/android-8.0-changes.html#privacy-all">
         * Android 8.0 Behavior Changes</a>.
         *
         * <p class="note"><strong>Note:</strong> For apps that were installed
         * prior to updating the device to a version of Android 8.0
         * (API level 26) or higher, the value of {@code ANDROID_ID} changes
         * if the app is uninstalled and then reinstalled after the OTA.
         * To preserve values across uninstalls after an OTA to Android 8.0
         * or higher, developers can use
         * <a href="{@docRoot}guide/topics/data/keyvaluebackup.html">
         * Key/Value Backup</a>.</p>
         *
         * <p>In versions of the platform lower than Android 8.0 (API level 26),
         * a 64-bit number (expressed as a hexadecimal string) that is randomly
         * generated when the user first sets up the device and should remain
         * constant for the lifetime of the user's device.
         *
         * On devices that have
         * <a href="{@docRoot}about/versions/android-4.2.html#MultipleUsers">
         * multiple users</a>, each user appears as a
         * completely separate device, so the {@code ANDROID_ID} value is
         * unique to each user.</p>
         *
         * <p class="note"><strong>Note:</strong> If the caller is an Instant App the ID is scoped
         * to the Instant App, it is generated when the Instant App is first installed and reset if
         * the user clears the Instant App.
         */
        @Readable
        public static final String ANDROID_ID = "android_id";

        /**
         * @deprecated Use {@link android.provider.Settings.Global#BLUETOOTH_ON} instead
         */
        @Deprecated
        public static final String BLUETOOTH_ON = Global.BLUETOOTH_ON;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#DATA_ROAMING} instead
         */
        @Deprecated
        public static final String DATA_ROAMING = Global.DATA_ROAMING;

        /**
         * Stores {@link android.view.inputmethod.InputMethodInfo#getId()} of the input method
         * service that is currently selected.
         *
         * <p>Although the name {@link #DEFAULT_INPUT_METHOD} implies that there is a concept of
         * <i>default</i> input method, in reality this setting is no more or less than the
         * <strong>currently selected</strong> input method. This setting can be updated at any
         * time as a result of user-initiated and system-initiated input method switching.</p>
         *
         * <p>Use {@link ComponentName#unflattenFromString(String)} to parse the stored value.</p>
         */
        @Readable
        public static final String DEFAULT_INPUT_METHOD = "default_input_method";

        /**
         * Setting to record the input method subtype used by default, holding the ID
         * of the desired method.
         */
        @Readable
        public static final String SELECTED_INPUT_METHOD_SUBTYPE =
                "selected_input_method_subtype";

        /**
         * The {@link android.view.inputmethod.InputMethodInfo.InputMethodInfo#getId() ID} of the
         * default voice input method.
         * <p>
         * This stores the last known default voice IME. If the related system config value changes,
         * this is reset by InputMethodManagerService.
         * <p>
         * This IME is not necessarily in the enabled IME list. That state is still stored in
         * {@link #ENABLED_INPUT_METHODS}.
         *
         * @hide
         */
        public static final String DEFAULT_VOICE_INPUT_METHOD = "default_voice_input_method";

        /**
         * Setting to record the history of input method subtype, holding the pair of ID of IME
         * and its last used subtype.
         * @hide
         */
        @Readable
        public static final String INPUT_METHODS_SUBTYPE_HISTORY =
                "input_methods_subtype_history";

        /**
         * Setting to record the visibility of input method selector
         */
        @Readable
        public static final String INPUT_METHOD_SELECTOR_VISIBILITY =
                "input_method_selector_visibility";

        /**
         * The currently selected voice interaction service flattened ComponentName.
         * @hide
         */
        @TestApi
        @Readable
        public static final String VOICE_INTERACTION_SERVICE = "voice_interaction_service";

        /**
         * The currently selected autofill service flattened ComponentName.
         * @hide
         */
        @TestApi
        @Readable
        public static final String AUTOFILL_SERVICE = "autofill_service";

        /**
         * Boolean indicating if Autofill supports field classification.
         *
         * @see android.service.autofill.AutofillService
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String AUTOFILL_FEATURE_FIELD_CLASSIFICATION =
                "autofill_field_classification";

        /**
         * Boolean indicating if the dark mode dialog shown on first toggle has been seen.
         *
         * @hide
         */
        @Readable
        public static final String DARK_MODE_DIALOG_SEEN =
                "dark_mode_dialog_seen";

        /**
         * Custom time when Dark theme is scheduled to activate.
         * Represented as milliseconds from midnight (e.g. 79200000 == 10pm).
         * @hide
         */
        @Readable
        public static final String DARK_THEME_CUSTOM_START_TIME =
                "dark_theme_custom_start_time";

        /**
         * Custom time when Dark theme is scheduled to deactivate.
         * Represented as milliseconds from midnight (e.g. 79200000 == 10pm).
         * @hide
         */
        @Readable
        public static final String DARK_THEME_CUSTOM_END_TIME =
                "dark_theme_custom_end_time";

        /**
         * Defines value returned by {@link android.service.autofill.UserData#getMaxUserDataSize()}.
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String AUTOFILL_USER_DATA_MAX_USER_DATA_SIZE =
                "autofill_user_data_max_user_data_size";

        /**
         * Defines value returned by
         * {@link android.service.autofill.UserData#getMaxFieldClassificationIdsSize()}.
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String AUTOFILL_USER_DATA_MAX_FIELD_CLASSIFICATION_IDS_SIZE =
                "autofill_user_data_max_field_classification_size";

        /**
         * Defines value returned by
         * {@link android.service.autofill.UserData#getMaxCategoryCount()}.
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String AUTOFILL_USER_DATA_MAX_CATEGORY_COUNT =
                "autofill_user_data_max_category_count";

        /**
         * Defines value returned by {@link android.service.autofill.UserData#getMaxValueLength()}.
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String AUTOFILL_USER_DATA_MAX_VALUE_LENGTH =
                "autofill_user_data_max_value_length";

        /**
         * Defines value returned by {@link android.service.autofill.UserData#getMinValueLength()}.
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String AUTOFILL_USER_DATA_MIN_VALUE_LENGTH =
                "autofill_user_data_min_value_length";

        /**
         * Defines whether Content Capture is enabled for the user.
         *
         * <p>Type: {@code int} ({@code 0} for disabled, {@code 1} for enabled).
         * <p>Default: enabled
         *
         * @hide
         */
        @TestApi
        @Readable
        public static final String CONTENT_CAPTURE_ENABLED = "content_capture_enabled";

        /**
         * @deprecated Use {@link android.provider.Settings.Global#DEVICE_PROVISIONED} instead
         */
        @Deprecated
        public static final String DEVICE_PROVISIONED = Global.DEVICE_PROVISIONED;

        /**
         * Indicates whether a DPC has been downloaded during provisioning.
         *
         * <p>Type: int (0 for false, 1 for true)
         *
         * <p>If this is true, then any attempts to begin setup again should result in factory reset
         *
         * @hide
         */
        @Readable
        public static final String MANAGED_PROVISIONING_DPC_DOWNLOADED =
                "managed_provisioning_dpc_downloaded";

        /**
         * Indicates whether the device is under restricted secure FRP mode.
         * Secure FRP mode is enabled when the device is under FRP. On solving of FRP challenge,
         * device is removed from this mode.
         * <p>
         * Type: int (0 for false, 1 for true)
         */
        @Readable
        public static final String SECURE_FRP_MODE = "secure_frp_mode";

        /**
         * Indicates whether the current user has completed setup via the setup wizard.
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String USER_SETUP_COMPLETE = "user_setup_complete";

        /**
         * Indicates that the user has not started setup personalization.
         * One of the possible states for {@link #USER_SETUP_PERSONALIZATION_STATE}.
         *
         * @hide
         */
        @SystemApi
        public static final int USER_SETUP_PERSONALIZATION_NOT_STARTED = 0;

        /**
         * Indicates that the user has not yet completed setup personalization.
         * One of the possible states for {@link #USER_SETUP_PERSONALIZATION_STATE}.
         *
         * @hide
         */
        @SystemApi
        public static final int USER_SETUP_PERSONALIZATION_STARTED = 1;

        /**
         * Indicates that the user has snoozed personalization and will complete it later.
         * One of the possible states for {@link #USER_SETUP_PERSONALIZATION_STATE}.
         *
         * @hide
         */
        @SystemApi
        public static final int USER_SETUP_PERSONALIZATION_PAUSED = 2;

        /**
         * Indicates that the user has completed setup personalization.
         * One of the possible states for {@link #USER_SETUP_PERSONALIZATION_STATE}.
         *
         * @hide
         */
        @SystemApi
        public static final int USER_SETUP_PERSONALIZATION_COMPLETE = 10;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({
                USER_SETUP_PERSONALIZATION_NOT_STARTED,
                USER_SETUP_PERSONALIZATION_STARTED,
                USER_SETUP_PERSONALIZATION_PAUSED,
                USER_SETUP_PERSONALIZATION_COMPLETE
        })
        public @interface UserSetupPersonalization {}

        /**
         * Defines the user's current state of device personalization.
         * The possible states are defined in {@link UserSetupPersonalization}.
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String USER_SETUP_PERSONALIZATION_STATE =
                "user_setup_personalization_state";

        /**
         * Whether the current user has been set up via setup wizard (0 = false, 1 = true)
         * This value differs from USER_SETUP_COMPLETE in that it can be reset back to 0
         * in case SetupWizard has been re-enabled on TV devices.
         *
         * @hide
         */
        @Readable
        public static final String TV_USER_SETUP_COMPLETE = "tv_user_setup_complete";

        /**
         * The prefix for a category name that indicates whether a suggested action from that
         * category was marked as completed.
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String COMPLETED_CATEGORY_PREFIX = "suggested.completed_category.";

        /**
         * Whether or not compress blocks should be released on install.
         * <p>The setting only determines if the platform will attempt to release
         * compress blocks; it does not guarantee that the files will have their
         * compress blocks released. Compression is currently only supported on
         * some f2fs filesystems.
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        public static final String RELEASE_COMPRESS_BLOCKS_ON_INSTALL =
                "release_compress_blocks_on_install";

        /**
         * List of input methods that are currently enabled.  This is a string
         * containing the IDs of all enabled input methods, each ID separated
         * by ':'.
         *
         * Format like "ime0;subtype0;subtype1;subtype2:ime1:ime2;subtype0"
         * where imeId is ComponentName and subtype is int32.
         */
        @Readable
        public static final String ENABLED_INPUT_METHODS = "enabled_input_methods";

        /**
         * List of system input methods that are currently disabled.  This is a string
         * containing the IDs of all disabled input methods, each ID separated
         * by ':'.
         * @hide
         */
        @Readable
        public static final String DISABLED_SYSTEM_INPUT_METHODS = "disabled_system_input_methods";

        /**
         * Whether to show the IME when a hard keyboard is connected. This is a boolean that
         * determines if the IME should be shown when a hard keyboard is attached.
         * @hide
         */
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String SHOW_IME_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard";

        /**
         * Host name and port for global http proxy. Uses ':' seperator for
         * between host and port.
         *
         * @deprecated Use {@link Global#HTTP_PROXY}
         */
        @Deprecated
        public static final String HTTP_PROXY = Global.HTTP_PROXY;

        /**
         * Package designated as always-on VPN provider.
         *
         * @hide
         */
        public static final String ALWAYS_ON_VPN_APP = "always_on_vpn_app";

        /**
         * Whether to block networking outside of VPN connections while always-on is set.
         * @see #ALWAYS_ON_VPN_APP
         *
         * @hide
         */
        @Readable
        public static final String ALWAYS_ON_VPN_LOCKDOWN = "always_on_vpn_lockdown";

        /**
         * Comma separated list of packages that are allowed to access the network when VPN is in
         * lockdown mode but not running.
         * @see #ALWAYS_ON_VPN_LOCKDOWN
         *
         * @hide
         */
        @Readable
        public static final String ALWAYS_ON_VPN_LOCKDOWN_WHITELIST =
                "always_on_vpn_lockdown_whitelist";

        /**
         * Whether applications can be installed for this user via the system's
         * {@link Intent#ACTION_INSTALL_PACKAGE} mechanism.
         *
         * <p>1 = permit app installation via the system package installer intent
         * <p>0 = do not allow use of the package installer
         * @deprecated Starting from {@link android.os.Build.VERSION_CODES#O}, apps should use
         * {@link PackageManager#canRequestPackageInstalls()}
         * @see PackageManager#canRequestPackageInstalls()
         */
        @Deprecated
        @Readable
        public static final String INSTALL_NON_MARKET_APPS = "install_non_market_apps";

        /**
         * A flag to tell {@link com.android.server.devicepolicy.DevicePolicyManagerService} that
         * the default for {@link #INSTALL_NON_MARKET_APPS} is reversed for this user on OTA. So it
         * can set the restriction {@link android.os.UserManager#DISALLOW_INSTALL_UNKNOWN_SOURCES}
         * on behalf of the profile owner if needed to make the change transparent for profile
         * owners.
         *
         * @hide
         */
        @Readable
        public static final String UNKNOWN_SOURCES_DEFAULT_REVERSED =
                "unknown_sources_default_reversed";

        /**
         * Comma-separated list of location providers that are enabled. Do not rely on this value
         * being present or correct, or on ContentObserver notifications on the corresponding Uri.
         *
         * @deprecated This setting no longer exists from Android S onwards as it no longer is
         * capable of realistically reflecting location settings. Use {@link
         * LocationManager#isProviderEnabled(String)} or {@link LocationManager#isLocationEnabled()}
         * instead.
         */
        @Deprecated
        @Readable
        public static final String LOCATION_PROVIDERS_ALLOWED = "location_providers_allowed";

        /**
         * The current location mode of the device. Do not rely on this value being present or on
         * ContentObserver notifications on the corresponding Uri.
         *
         * @deprecated The preferred methods for checking location mode and listening for changes
         * are via {@link LocationManager#isLocationEnabled()} and
         * {@link LocationManager#MODE_CHANGED_ACTION}.
         */
        @Deprecated
        @Readable
        public static final String LOCATION_MODE = "location_mode";

        /**
         * The App or module that changes the location mode.
         * @hide
         */
        @Readable
        public static final String LOCATION_CHANGER = "location_changer";

        /**
         * The location changer is unknown or unable to detect.
         * @hide
         */
        public static final int LOCATION_CHANGER_UNKNOWN = 0;

        /**
         * Location settings in system settings.
         * @hide
         */
        public static final int LOCATION_CHANGER_SYSTEM_SETTINGS = 1;

        /**
         * The location icon in drop down notification drawer.
         * @hide
         */
        public static final int LOCATION_CHANGER_QUICK_SETTINGS = 2;

        /**
         * Location mode is off.
         */
        public static final int LOCATION_MODE_OFF = 0;

        /**
         * This mode no longer has any distinct meaning, but is interpreted as the location mode is
         * on.
         *
         * @deprecated See {@link #LOCATION_MODE}.
         */
        @Deprecated
        public static final int LOCATION_MODE_SENSORS_ONLY = 1;

        /**
         * This mode no longer has any distinct meaning, but is interpreted as the location mode is
         * on.
         *
         * @deprecated See {@link #LOCATION_MODE}.
         */
        @Deprecated
        public static final int LOCATION_MODE_BATTERY_SAVING = 2;

        /**
         * This mode no longer has any distinct meaning, but is interpreted as the location mode is
         * on.
         *
         * @deprecated See {@link #LOCATION_MODE}.
         */
        @Deprecated
        public static final int LOCATION_MODE_HIGH_ACCURACY = 3;

        /**
         * Location mode is on.
         *
         * @hide
         */
        @SystemApi
        public static final int LOCATION_MODE_ON = LOCATION_MODE_HIGH_ACCURACY;

        /**
         * The current location time zone detection enabled state for the user.
         *
         * See {@link android.app.time.TimeManager#getTimeZoneCapabilitiesAndConfig} for access.
         * See {@link android.app.time.TimeManager#updateTimeZoneConfiguration} to update.
         * @hide
         */
        public static final String LOCATION_TIME_ZONE_DETECTION_ENABLED =
                "location_time_zone_detection_enabled";

        /**
         * The accuracy in meters used for coarsening location for clients with only the coarse
         * location permission.
         *
         * @hide
         */
        @Readable
        public static final String LOCATION_COARSE_ACCURACY_M = "locationCoarseAccuracy";

        /**
         * Whether or not to show display system location accesses.
         * @hide
         */
        public static final String LOCATION_SHOW_SYSTEM_OPS = "locationShowSystemOps";


        /**
         * Whether or not an indicator experiment has started.
         * @hide
         */
        public static final String LOCATION_INDICATOR_EXPERIMENT_STARTED =
                "locationIndicatorExperimentStarted";
        /**
         * A flag containing settings used for biometric weak
         * @hide
         */
        @Deprecated
        @Readable
        public static final String LOCK_BIOMETRIC_WEAK_FLAGS =
                "lock_biometric_weak_flags";

        /**
         * Whether lock-to-app will lock the keyguard when exiting.
         * @hide
         */
        @Readable
        public static final String LOCK_TO_APP_EXIT_LOCKED = "lock_to_app_exit_locked";

        /**
         * Whether autolock is enabled (0 = false, 1 = true)
         *
         * @deprecated Use {@link android.app.KeyguardManager} to determine the state and security
         *             level of the keyguard. Accessing this setting from an app that is targeting
         *             {@link VERSION_CODES#M} or later throws a {@code SecurityException}.
         */
        @Deprecated
        @Readable
        public static final String LOCK_PATTERN_ENABLED = "lock_pattern_autolock";

        /**
         * Whether lock pattern is visible as user enters (0 = false, 1 = true)
         *
         * @deprecated Accessing this setting from an app that is targeting
         *             {@link VERSION_CODES#M} or later throws a {@code SecurityException}.
         */
        @Deprecated
        @Readable
        public static final String LOCK_PATTERN_VISIBLE = "lock_pattern_visible_pattern";

        /**
         * Whether lock pattern will vibrate as user enters (0 = false, 1 =
         * true)
         *
         * @deprecated Starting in {@link VERSION_CODES#JELLY_BEAN_MR1} the
         *             lockscreen uses
         *             {@link Settings.System#HAPTIC_FEEDBACK_ENABLED}.
         *             Accessing this setting from an app that is targeting
         *             {@link VERSION_CODES#M} or later throws a {@code SecurityException}.
         */
        @Deprecated
        @Readable
        public static final String
                LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED = "lock_pattern_tactile_feedback_enabled";

        /**
         * This preference allows the device to be locked given time after screen goes off,
         * subject to current DeviceAdmin policy limits.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String LOCK_SCREEN_LOCK_AFTER_TIMEOUT = "lock_screen_lock_after_timeout";


        /**
         * This preference contains the string that shows for owner info on LockScreen.
         * @hide
         * @deprecated
         */
        @Deprecated
        @Readable
        public static final String LOCK_SCREEN_OWNER_INFO = "lock_screen_owner_info";

        /**
         * Ids of the user-selected appwidgets on the lockscreen (comma-delimited).
         * @hide
         */
        @Deprecated
        @Readable
        public static final String LOCK_SCREEN_APPWIDGET_IDS =
            "lock_screen_appwidget_ids";

        /**
         * Id of the appwidget shown on the lock screen when appwidgets are disabled.
         * @hide
         */
        @Deprecated
        @Readable
        public static final String LOCK_SCREEN_FALLBACK_APPWIDGET_ID =
            "lock_screen_fallback_appwidget_id";

        /**
         * Index of the lockscreen appwidget to restore, -1 if none.
         * @hide
         */
        @Deprecated
        @Readable
        public static final String LOCK_SCREEN_STICKY_APPWIDGET =
            "lock_screen_sticky_appwidget";

        /**
         * This preference enables showing the owner info on LockScreen.
         * @hide
         * @deprecated
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String LOCK_SCREEN_OWNER_INFO_ENABLED =
            "lock_screen_owner_info_enabled";

        /**
         * Indicates whether the user has allowed notifications to be shown atop a securely locked
         * screen in their full "private" form (same as when the device is unlocked).
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS =
                "lock_screen_allow_private_notifications";

        /**
         * When set by a user, allows notification remote input atop a securely locked screen
         * without having to unlock
         * @hide
         */
        @Readable
        public static final String LOCK_SCREEN_ALLOW_REMOTE_INPUT =
                "lock_screen_allow_remote_input";

        /**
         * Indicates which clock face to show on lock screen and AOD formatted as a serialized
         * {@link org.json.JSONObject} with the format:
         *     {"clock": id, "_applied_timestamp": timestamp}
         * @hide
         */
        @Readable
        public static final String LOCK_SCREEN_CUSTOM_CLOCK_FACE = "lock_screen_custom_clock_face";

        /**
         * Indicates which clock face to show on lock screen and AOD while docked.
         * @hide
         */
        @Readable
        public static final String DOCKED_CLOCK_FACE = "docked_clock_face";

        /**
         * Set by the system to track if the user needs to see the call to action for
         * the lockscreen notification policy.
         * @hide
         */
        @Readable
        public static final String SHOW_NOTE_ABOUT_NOTIFICATION_HIDING =
                "show_note_about_notification_hiding";

        /**
         * Set to 1 by the system after trust agents have been initialized.
         * @hide
         */
        @Readable
        public static final String TRUST_AGENTS_INITIALIZED =
                "trust_agents_initialized";

        /**
         * The Logging ID (a unique 64-bit value) as a hex string.
         * Used as a pseudonymous identifier for logging.
         * @deprecated This identifier is poorly initialized and has
         * many collisions.  It should not be used.
         */
        @Deprecated
        @Readable
        public static final String LOGGING_ID = "logging_id";

        /**
         * @deprecated Use {@link android.provider.Settings.Global#NETWORK_PREFERENCE} instead
         */
        @Deprecated
        public static final String NETWORK_PREFERENCE = Global.NETWORK_PREFERENCE;

        /**
         * No longer supported.
         */
        @Readable
        public static final String PARENTAL_CONTROL_ENABLED = "parental_control_enabled";

        /**
         * No longer supported.
         */
        @Readable
        public static final String PARENTAL_CONTROL_LAST_UPDATE = "parental_control_last_update";

        /**
         * No longer supported.
         */
        @Readable
        public static final String PARENTAL_CONTROL_REDIRECT_URL = "parental_control_redirect_url";

        /**
         * Settings classname to launch when Settings is clicked from All
         * Applications.  Needed because of user testing between the old
         * and new Settings apps.
         */
        // TODO: 881807
        @Readable
        public static final String SETTINGS_CLASSNAME = "settings_classname";

        /**
         * @deprecated Use {@link android.provider.Settings.Global#USB_MASS_STORAGE_ENABLED} instead
         */
        @Deprecated
        public static final String USB_MASS_STORAGE_ENABLED = Global.USB_MASS_STORAGE_ENABLED;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#USE_GOOGLE_MAIL} instead
         */
        @Deprecated
        public static final String USE_GOOGLE_MAIL = Global.USE_GOOGLE_MAIL;

        /**
         * If accessibility is enabled.
         */
        @Readable
        public static final String ACCESSIBILITY_ENABLED = "accessibility_enabled";

        /**
         * Whether select sound track with audio description by default.
         * @hide
         */
        public static final String ENABLED_ACCESSIBILITY_AUDIO_DESCRIPTION_BY_DEFAULT =
                "enabled_accessibility_audio_description_by_default";

        /**
         * Setting specifying if the accessibility shortcut is enabled.
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN =
                "accessibility_shortcut_on_lock_screen";

        /**
         * Setting specifying if the accessibility shortcut dialog has been shown to this user.
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN =
                "accessibility_shortcut_dialog_shown";

        /**
         * Setting specifying if the timeout restriction
         * {@link ViewConfiguration#getAccessibilityShortcutKeyTimeout()}
         * of the accessibility shortcut dialog is skipped.
         *
         * @hide
         */
        public static final String SKIP_ACCESSIBILITY_SHORTCUT_DIALOG_TIMEOUT_RESTRICTION =
                "skip_accessibility_shortcut_dialog_timeout_restriction";

        /**
         * Setting specifying the accessibility services, accessibility shortcut targets,
         * or features to be toggled via the accessibility shortcut.
         *
         * <p> This is a colon-separated string list which contains the flattened
         * {@link ComponentName} and the class name of a system class implementing a supported
         * accessibility feature.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @TestApi
        @Readable
        public static final String ACCESSIBILITY_SHORTCUT_TARGET_SERVICE =
                "accessibility_shortcut_target_service";

        /**
         * Setting specifying the accessibility service or feature to be toggled via the
         * accessibility button in the navigation bar. This is either a flattened
         * {@link ComponentName} or the class name of a system class implementing a supported
         * accessibility feature.
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_BUTTON_TARGET_COMPONENT =
                "accessibility_button_target_component";

        /**
         * Setting specifying the accessibility services, accessibility shortcut targets,
         * or features to be toggled via the accessibility button in the navigation bar.
         *
         * <p> This is a colon-separated string list which contains the flattened
         * {@link ComponentName} and the class name of a system class implementing a supported
         * accessibility feature.
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_BUTTON_TARGETS = "accessibility_button_targets";

        /**
         * The system class name of magnification controller which is a target to be toggled via
         * accessibility shortcut or accessibility button.
         *
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_SHORTCUT_TARGET_MAGNIFICATION_CONTROLLER =
                "com.android.server.accessibility.MagnificationController";

        /**
         * If touch exploration is enabled.
         */
        @Readable
        public static final String TOUCH_EXPLORATION_ENABLED = "touch_exploration_enabled";

        /**
         * List of the enabled accessibility providers.
         */
        @Readable
        public static final String ENABLED_ACCESSIBILITY_SERVICES =
            "enabled_accessibility_services";

        /**
         * List of the notified non-accessibility category accessibility services.
         *
         * @hide
         */
        @Readable
        public static final String NOTIFIED_NON_ACCESSIBILITY_CATEGORY_SERVICES =
                "notified_non_accessibility_category_services";

        /**
         * List of the accessibility services to which the user has granted
         * permission to put the device into touch exploration mode.
         *
         * @hide
         */
        @Readable
        public static final String TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES =
            "touch_exploration_granted_accessibility_services";

        /**
         * Is talkback service enabled or not. 0 == no, 1 == yes
         *
         * @hide
         */
        public static final String WEAR_TALKBACK_ENABLED = "wear_talkback_enabled";

        /**
         * Whether the Global Actions Panel is enabled.
         * @hide
         */
        @Readable
        public static final String GLOBAL_ACTIONS_PANEL_ENABLED = "global_actions_panel_enabled";

        /**
         * Whether the Global Actions Panel can be toggled on or off in Settings.
         * @hide
         */
        @Readable
        public static final String GLOBAL_ACTIONS_PANEL_AVAILABLE =
                "global_actions_panel_available";

        /**
         * Enables debug mode for the Global Actions Panel.
         * @hide
         */
        @Readable
        public static final String GLOBAL_ACTIONS_PANEL_DEBUG_ENABLED =
                "global_actions_panel_debug_enabled";

        /**
         * Whether the hush gesture has ever been used
         * @hide
         */
        @SystemApi
        @Readable
        public static final String HUSH_GESTURE_USED = "hush_gesture_used";

        /**
         * Number of times the user has manually clicked the ringer toggle
         * @hide
         */
        @Readable
        public static final String MANUAL_RINGER_TOGGLE_COUNT = "manual_ringer_toggle_count";

        /**
         * Whether to play a sound for charging events.
         * @hide
         */
        @Readable
        public static final String CHARGING_SOUNDS_ENABLED = "charging_sounds_enabled";

        /**
         * Whether to vibrate for charging events.
         * @hide
         */
        @Readable
        public static final String CHARGING_VIBRATION_ENABLED = "charging_vibration_enabled";

        /**
         * If 0, turning on dnd manually will last indefinitely.
         * Else if non-negative, turning on dnd manually will last for this many minutes.
         * Else (if negative), turning on dnd manually will surface a dialog that prompts
         * user to specify a duration.
         * @hide
         */
        @Readable
        public static final String ZEN_DURATION = "zen_duration";

        /** @hide */ public static final int ZEN_DURATION_PROMPT = -1;
        /** @hide */ public static final int ZEN_DURATION_FOREVER = 0;

        /**
         * If nonzero, will show the zen upgrade notification when the user toggles DND on/off.
         * @hide
         */
        @Readable
        public static final String SHOW_ZEN_UPGRADE_NOTIFICATION = "show_zen_upgrade_notification";

        /**
         * If nonzero, will show the zen update settings suggestion.
         * @hide
         */
        @Readable
        public static final String SHOW_ZEN_SETTINGS_SUGGESTION = "show_zen_settings_suggestion";

        /**
         * If nonzero, zen has not been updated to reflect new changes.
         * @hide
         */
        @Readable
        public static final String ZEN_SETTINGS_UPDATED = "zen_settings_updated";

        /**
         * If nonzero, zen setting suggestion has been viewed by user
         * @hide
         */
        @Readable
        public static final String ZEN_SETTINGS_SUGGESTION_VIEWED =
                "zen_settings_suggestion_viewed";

        /**
         * State of whether review notification permissions notification needs to
         * be shown the user, and whether the user has interacted.
         *
         * Valid values:
         *   -1 = UNKNOWN
         *    0 = SHOULD_SHOW
         *    1 = USER_INTERACTED
         *    2 = DISMISSED
         * @hide
         */
        public static final String REVIEW_PERMISSIONS_NOTIFICATION_STATE =
                "review_permissions_notification_state";

        /**
         * Whether the in call notification is enabled to play sound during calls.  The value is
         * boolean (1 or 0).
         * @hide
         */
        @Readable
        public static final String IN_CALL_NOTIFICATION_ENABLED = "in_call_notification_enabled";

        /**
         * Uri of the slice that's presented on the keyguard.
         * Defaults to a slice with the date and next alarm.
         *
         * @hide
         */
        @Readable
        public static final String KEYGUARD_SLICE_URI = "keyguard_slice_uri";

        /**
         * The adjustment in font weight. This is used to draw text in bold.
         *
         * <p> This value can be negative. To display bolded text, the adjustment used is 300,
         * which is the difference between
         * {@link android.graphics.fonts.FontStyle#FONT_WEIGHT_NORMAL} and
         * {@link android.graphics.fonts.FontStyle#FONT_WEIGHT_BOLD}.
         *
         * @hide
         */
        @Readable
        public static final String FONT_WEIGHT_ADJUSTMENT = "font_weight_adjustment";

        /**
         * Whether to speak passwords while in accessibility mode.
         *
         * @deprecated The speaking of passwords is controlled by individual accessibility services.
         * Apps should ignore this setting and provide complete information to accessibility
         * at all times, which was the behavior when this value was {@code true}.
         */
        @Deprecated
        @Readable
        public static final String ACCESSIBILITY_SPEAK_PASSWORD = "speak_password";

        /**
         * Whether to draw text with high contrast while in accessibility mode.
         *
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED =
                "high_text_contrast_enabled";

        /**
         * Setting that specifies whether the display magnification is enabled via a system-wide
         * triple tap gesture. Display magnifications allows the user to zoom in the display content
         * and is targeted to low vision users. The current magnification scale is controlled by
         * {@link #ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE}.
         *
         * @hide
         */
        @UnsupportedAppUsage
        @TestApi
        @Readable
        public static final String ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED =
                "accessibility_display_magnification_enabled";

        /**
         * Setting that specifies whether the display magnification is enabled via a shortcut
         * affordance within the system's navigation area. Display magnifications allows the user to
         * zoom in the display content and is targeted to low vision users. The current
         * magnification scale is controlled by {@link #ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE}.
         *
         * @deprecated Use {@link #ACCESSIBILITY_BUTTON_TARGETS} instead.
         * {@link #ACCESSIBILITY_BUTTON_TARGETS} holds the magnification system class name
         * when navigation bar magnification is enabled.
         * @hide
         */
        @SystemApi
        @Readable
        public static final String ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED =
                "accessibility_display_magnification_navbar_enabled";

        /**
         * Setting that specifies what the display magnification scale is.
         * Display magnifications allows the user to zoom in the display
         * content and is targeted to low vision users. Whether a display
         * magnification is performed is controlled by
         * {@link #ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED} and
         * {@link #ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED}
         *
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE =
                "accessibility_display_magnification_scale";

        /**
         * Unused mangnification setting
         *
         * @hide
         * @deprecated
         */
        @Deprecated
        @Readable
        public static final String ACCESSIBILITY_DISPLAY_MAGNIFICATION_AUTO_UPDATE =
                "accessibility_display_magnification_auto_update";

        /**
         * Setting that specifies what mode the soft keyboard is in (default or hidden). Can be
         * modified from an AccessibilityService using the SoftKeyboardController.
         *
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_SOFT_KEYBOARD_MODE =
                "accessibility_soft_keyboard_mode";

        /**
         * Default soft keyboard behavior.
         *
         * @hide
         */
        public static final int SHOW_MODE_AUTO = 0;

        /**
         * Soft keyboard is never shown.
         *
         * @hide
         */
        public static final int SHOW_MODE_HIDDEN = 1;

        /**
         * Setting that specifies whether timed text (captions) should be
         * displayed in video content. Text display properties are controlled by
         * the following settings:
         * <ul>
         * <li>{@link #ACCESSIBILITY_CAPTIONING_LOCALE}
         * <li>{@link #ACCESSIBILITY_CAPTIONING_BACKGROUND_COLOR}
         * <li>{@link #ACCESSIBILITY_CAPTIONING_FOREGROUND_COLOR}
         * <li>{@link #ACCESSIBILITY_CAPTIONING_EDGE_COLOR}
         * <li>{@link #ACCESSIBILITY_CAPTIONING_EDGE_TYPE}
         * <li>{@link #ACCESSIBILITY_CAPTIONING_TYPEFACE}
         * <li>{@link #ACCESSIBILITY_CAPTIONING_FONT_SCALE}
         * </ul>
         *
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_CAPTIONING_ENABLED =
                "accessibility_captioning_enabled";

        /**
         * Setting that specifies the language for captions as a locale string,
         * e.g. en_US.
         *
         * @see java.util.Locale#toString
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_CAPTIONING_LOCALE =
                "accessibility_captioning_locale";

        /**
         * Integer property that specifies the preset style for captions, one
         * of:
         * <ul>
         * <li>{@link android.view.accessibility.CaptioningManager.CaptionStyle#PRESET_CUSTOM}
         * <li>a valid index of {@link android.view.accessibility.CaptioningManager.CaptionStyle#PRESETS}
         * </ul>
         *
         * @see java.util.Locale#toString
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_CAPTIONING_PRESET =
                "accessibility_captioning_preset";

        /**
         * Integer property that specifes the background color for captions as a
         * packed 32-bit color.
         *
         * @see android.graphics.Color#argb
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_CAPTIONING_BACKGROUND_COLOR =
                "accessibility_captioning_background_color";

        /**
         * Integer property that specifes the foreground color for captions as a
         * packed 32-bit color.
         *
         * @see android.graphics.Color#argb
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_CAPTIONING_FOREGROUND_COLOR =
                "accessibility_captioning_foreground_color";

        /**
         * Integer property that specifes the edge type for captions, one of:
         * <ul>
         * <li>{@link android.view.accessibility.CaptioningManager.CaptionStyle#EDGE_TYPE_NONE}
         * <li>{@link android.view.accessibility.CaptioningManager.CaptionStyle#EDGE_TYPE_OUTLINE}
         * <li>{@link android.view.accessibility.CaptioningManager.CaptionStyle#EDGE_TYPE_DROP_SHADOW}
         * </ul>
         *
         * @see #ACCESSIBILITY_CAPTIONING_EDGE_COLOR
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_CAPTIONING_EDGE_TYPE =
                "accessibility_captioning_edge_type";

        /**
         * Integer property that specifes the edge color for captions as a
         * packed 32-bit color.
         *
         * @see #ACCESSIBILITY_CAPTIONING_EDGE_TYPE
         * @see android.graphics.Color#argb
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_CAPTIONING_EDGE_COLOR =
                "accessibility_captioning_edge_color";

        /**
         * Integer property that specifes the window color for captions as a
         * packed 32-bit color.
         *
         * @see android.graphics.Color#argb
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_CAPTIONING_WINDOW_COLOR =
                "accessibility_captioning_window_color";

        /**
         * String property that specifies the typeface for captions, one of:
         * <ul>
         * <li>DEFAULT
         * <li>MONOSPACE
         * <li>SANS_SERIF
         * <li>SERIF
         * </ul>
         *
         * @see android.graphics.Typeface
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String ACCESSIBILITY_CAPTIONING_TYPEFACE =
                "accessibility_captioning_typeface";

        /**
         * Floating point property that specifies font scaling for captions.
         *
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_CAPTIONING_FONT_SCALE =
                "accessibility_captioning_font_scale";

        /**
         * Setting that specifies whether display color inversion is enabled.
         */
        @Readable
        public static final String ACCESSIBILITY_DISPLAY_INVERSION_ENABLED =
                "accessibility_display_inversion_enabled";

        /**
         * Setting that specifies whether display color space adjustment is
         * enabled.
         *
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED =
                "accessibility_display_daltonizer_enabled";

        /**
         * Integer property that specifies the type of color space adjustment to
         * perform. Valid values are defined in AccessibilityManager and Settings arrays.xml:
         * - AccessibilityManager.DALTONIZER_DISABLED = -1
         * - AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY = 0
         * - <item>@string/daltonizer_mode_protanomaly</item> = 11
         * - AccessibilityManager.DALTONIZER_CORRECT_DEUTERANOMALY and
         *       <item>@string/daltonizer_mode_deuteranomaly</item> = 12
         * - <item>@string/daltonizer_mode_tritanomaly</item> = 13
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String ACCESSIBILITY_DISPLAY_DALTONIZER =
                "accessibility_display_daltonizer";

        /**
         * Setting that specifies whether automatic click when the mouse pointer stops moving is
         * enabled.
         *
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String ACCESSIBILITY_AUTOCLICK_ENABLED =
                "accessibility_autoclick_enabled";

        /**
         * Integer setting specifying amount of time in ms the mouse pointer has to stay still
         * before performing click when {@link #ACCESSIBILITY_AUTOCLICK_ENABLED} is set.
         *
         * @see #ACCESSIBILITY_AUTOCLICK_ENABLED
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_AUTOCLICK_DELAY =
                "accessibility_autoclick_delay";

        /**
         * Whether or not larger size icons are used for the pointer of mouse/trackpad for
         * accessibility.
         * (0 = false, 1 = true)
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String ACCESSIBILITY_LARGE_POINTER_ICON =
                "accessibility_large_pointer_icon";

        /**
         * The timeout for considering a press to be a long press in milliseconds.
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String LONG_PRESS_TIMEOUT = "long_press_timeout";

        /**
         * The duration in milliseconds between the first tap's up event and the second tap's
         * down event for an interaction to be considered part of the same multi-press.
         * @hide
         */
        @Readable
        public static final String MULTI_PRESS_TIMEOUT = "multi_press_timeout";

        /**
         * Setting that specifies recommended timeout in milliseconds for controls
         * which don't need user's interactions.
         *
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_NON_INTERACTIVE_UI_TIMEOUT_MS =
                "accessibility_non_interactive_ui_timeout_ms";

        /**
         * Setting that specifies recommended timeout in milliseconds for controls
         * which need user's interactions.
         *
         * @hide
         */
        @Readable
        public static final String ACCESSIBILITY_INTERACTIVE_UI_TIMEOUT_MS =
                "accessibility_interactive_ui_timeout_ms";


        /**
         * Setting that specifies whether Reduce Bright Colors, or brightness dimming by color
         * adjustment, is enabled.
         *
         * @hide
         */
        public static final String REDUCE_BRIGHT_COLORS_ACTIVATED =
                "reduce_bright_colors_activated";

        /**
         * Setting that specifies the level of Reduce Bright Colors in intensity. The range is
         * [0, 100].
         *
         * @hide
         */
        public static final String REDUCE_BRIGHT_COLORS_LEVEL =
                "reduce_bright_colors_level";

        /**
         * Setting that specifies whether Reduce Bright Colors should persist across reboots.
         *
         * @hide
         */
        public static final String REDUCE_BRIGHT_COLORS_PERSIST_ACROSS_REBOOTS =
                "reduce_bright_colors_persist_across_reboots";

        /**
         * List of the enabled print services.
         *
         * N and beyond uses {@link #DISABLED_PRINT_SERVICES}. But this might be used in an upgrade
         * from pre-N.
         *
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String ENABLED_PRINT_SERVICES =
            "enabled_print_services";

        /**
         * List of the disabled print services.
         *
         * @hide
         */
        @TestApi
        @Readable
        public static final String DISABLED_PRINT_SERVICES =
            "disabled_print_services";

        /**
         * The saved value for WindowManagerService.setForcedDisplayDensity()
         * formatted as a single integer representing DPI. If unset, then use
         * the real display density.
         *
         * @hide
         */
        @Readable
        public static final String DISPLAY_DENSITY_FORCED = "display_density_forced";

        /**
         * Setting to always use the default text-to-speech settings regardless
         * of the application settings.
         * 1 = override application settings,
         * 0 = use application settings (if specified).
         *
         * @deprecated  The value of this setting is no longer respected by
         * the framework text to speech APIs as of the Ice Cream Sandwich release.
         */
        @Deprecated
        @Readable
        public static final String TTS_USE_DEFAULTS = "tts_use_defaults";

        /**
         * Default text-to-speech engine speech rate. 100 = 1x
         */
        @Readable
        public static final String TTS_DEFAULT_RATE = "tts_default_rate";

        /**
         * Default text-to-speech engine pitch. 100 = 1x
         */
        @Readable
        public static final String TTS_DEFAULT_PITCH = "tts_default_pitch";

        /**
         * Default text-to-speech engine.
         */
        @Readable
        public static final String TTS_DEFAULT_SYNTH = "tts_default_synth";

        /**
         * Default text-to-speech language.
         *
         * @deprecated this setting is no longer in use, as of the Ice Cream
         * Sandwich release. Apps should never need to read this setting directly,
         * instead can query the TextToSpeech framework classes for the default
         * locale. {@link TextToSpeech#getLanguage()}.
         */
        @Deprecated
        @Readable
        public static final String TTS_DEFAULT_LANG = "tts_default_lang";

        /**
         * Default text-to-speech country.
         *
         * @deprecated this setting is no longer in use, as of the Ice Cream
         * Sandwich release. Apps should never need to read this setting directly,
         * instead can query the TextToSpeech framework classes for the default
         * locale. {@link TextToSpeech#getLanguage()}.
         */
        @Deprecated
        @Readable
        public static final String TTS_DEFAULT_COUNTRY = "tts_default_country";

        /**
         * Default text-to-speech locale variant.
         *
         * @deprecated this setting is no longer in use, as of the Ice Cream
         * Sandwich release. Apps should never need to read this setting directly,
         * instead can query the TextToSpeech framework classes for the
         * locale that is in use {@link TextToSpeech#getLanguage()}.
         */
        @Deprecated
        @Readable
        public static final String TTS_DEFAULT_VARIANT = "tts_default_variant";

        /**
         * Stores the default tts locales on a per engine basis. Stored as
         * a comma seperated list of values, each value being of the form
         * {@code engine_name:locale} for example,
         * {@code com.foo.ttsengine:eng-USA,com.bar.ttsengine:esp-ESP}. This
         * supersedes {@link #TTS_DEFAULT_LANG}, {@link #TTS_DEFAULT_COUNTRY} and
         * {@link #TTS_DEFAULT_VARIANT}. Apps should never need to read this
         * setting directly, and can query the TextToSpeech framework classes
         * for the locale that is in use.
         *
         * @hide
         */
        @Readable
        public static final String TTS_DEFAULT_LOCALE = "tts_default_locale";

        /**
         * Space delimited list of plugin packages that are enabled.
         */
        @Readable
        public static final String TTS_ENABLED_PLUGINS = "tts_enabled_plugins";

        /**
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON}
         * instead.
         */
        @Deprecated
        public static final String WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON =
                Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY}
         * instead.
         */
        @Deprecated
        public static final String WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY =
                Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_NUM_OPEN_NETWORKS_KEPT}
         * instead.
         */
        @Deprecated
        public static final String WIFI_NUM_OPEN_NETWORKS_KEPT =
                Global.WIFI_NUM_OPEN_NETWORKS_KEPT;

        /**
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_ON}
         * instead.
         */
        @Deprecated
        public static final String WIFI_ON = Global.WIFI_ON;

        /**
         * The acceptable packet loss percentage (range 0 - 100) before trying
         * another AP on the same network.
         * @deprecated This setting is not used.
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_ACCEPTABLE_PACKET_LOSS_PERCENTAGE =
                "wifi_watchdog_acceptable_packet_loss_percentage";

        /**
         * The number of access points required for a network in order for the
         * watchdog to monitor it.
         * @deprecated This setting is not used.
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_AP_COUNT = "wifi_watchdog_ap_count";

        /**
         * The delay between background checks.
         * @deprecated This setting is not used.
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_BACKGROUND_CHECK_DELAY_MS =
                "wifi_watchdog_background_check_delay_ms";

        /**
         * Whether the Wi-Fi watchdog is enabled for background checking even
         * after it thinks the user has connected to a good access point.
         * @deprecated This setting is not used.
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_BACKGROUND_CHECK_ENABLED =
                "wifi_watchdog_background_check_enabled";

        /**
         * The timeout for a background ping
         * @deprecated This setting is not used.
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_BACKGROUND_CHECK_TIMEOUT_MS =
                "wifi_watchdog_background_check_timeout_ms";

        /**
         * The number of initial pings to perform that *may* be ignored if they
         * fail. Again, if these fail, they will *not* be used in packet loss
         * calculation. For example, one network always seemed to time out for
         * the first couple pings, so this is set to 3 by default.
         * @deprecated This setting is not used.
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_INITIAL_IGNORED_PING_COUNT =
            "wifi_watchdog_initial_ignored_ping_count";

        /**
         * The maximum number of access points (per network) to attempt to test.
         * If this number is reached, the watchdog will no longer monitor the
         * initial connection state for the network. This is a safeguard for
         * networks containing multiple APs whose DNS does not respond to pings.
         * @deprecated This setting is not used.
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_MAX_AP_CHECKS = "wifi_watchdog_max_ap_checks";

        /**
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_WATCHDOG_ON} instead
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_ON = "wifi_watchdog_on";

        /**
         * A comma-separated list of SSIDs for which the Wi-Fi watchdog should be enabled.
         * @deprecated This setting is not used.
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_WATCH_LIST = "wifi_watchdog_watch_list";

        /**
         * The number of pings to test if an access point is a good connection.
         * @deprecated This setting is not used.
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_PING_COUNT = "wifi_watchdog_ping_count";

        /**
         * The delay between pings.
         * @deprecated This setting is not used.
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_PING_DELAY_MS = "wifi_watchdog_ping_delay_ms";

        /**
         * The timeout per ping.
         * @deprecated This setting is not used.
         */
        @Deprecated
        @Readable
        public static final String WIFI_WATCHDOG_PING_TIMEOUT_MS = "wifi_watchdog_ping_timeout_ms";

        /**
         * @deprecated Use
         * {@link android.provider.Settings.Global#WIFI_MAX_DHCP_RETRY_COUNT} instead
         */
        @Deprecated
        public static final String WIFI_MAX_DHCP_RETRY_COUNT = Global.WIFI_MAX_DHCP_RETRY_COUNT;

        /**
         * @deprecated Use
         * {@link android.provider.Settings.Global#WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS} instead
         */
        @Deprecated
        public static final String WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS =
                Global.WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS;

        /**
         * The number of milliseconds to hold on to a PendingIntent based request. This delay gives
         * the receivers of the PendingIntent an opportunity to make a new network request before
         * the Network satisfying the request is potentially removed.
         *
         * @hide
         */
        @Readable
        public static final String CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS =
                "connectivity_release_pending_intent_delay_ms";

        /**
         * Whether background data usage is allowed.
         *
         * @deprecated As of {@link VERSION_CODES#ICE_CREAM_SANDWICH},
         *             availability of background data depends on several
         *             combined factors. When background data is unavailable,
         *             {@link ConnectivityManager#getActiveNetworkInfo()} will
         *             now appear disconnected.
         */
        @Deprecated
        @Readable
        public static final String BACKGROUND_DATA = "background_data";

        /**
         * Origins for which browsers should allow geolocation by default.
         * The value is a space-separated list of origins.
         */
        @Readable
        public static final String ALLOWED_GEOLOCATION_ORIGINS
                = "allowed_geolocation_origins";

        /**
         * The preferred TTY mode     0 = TTy Off, CDMA default
         *                            1 = TTY Full
         *                            2 = TTY HCO
         *                            3 = TTY VCO
         * @hide
         */
        @Readable
        public static final String PREFERRED_TTY_MODE =
                "preferred_tty_mode";

        /**
         * Whether the enhanced voice privacy mode is enabled.
         * 0 = normal voice privacy
         * 1 = enhanced voice privacy
         * @hide
         */
        @Readable
        public static final String ENHANCED_VOICE_PRIVACY_ENABLED = "enhanced_voice_privacy_enabled";

        /**
         * Whether the TTY mode mode is enabled.
         * 0 = disabled
         * 1 = enabled
         * @hide
         */
        @Readable
        public static final String TTY_MODE_ENABLED = "tty_mode_enabled";

        /**
         * User-selected RTT mode. When on, outgoing and incoming calls will be answered as RTT
         * calls when supported by the device and carrier. Boolean value.
         * 0 = OFF
         * 1 = ON
         */
        @Readable
        public static final String RTT_CALLING_MODE = "rtt_calling_mode";

        /**
        /**
         * Controls whether settings backup is enabled.
         * Type: int ( 0 = disabled, 1 = enabled )
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String BACKUP_ENABLED = "backup_enabled";

        /**
         * Controls whether application data is automatically restored from backup
         * at install time.
         * Type: int ( 0 = disabled, 1 = enabled )
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String BACKUP_AUTO_RESTORE = "backup_auto_restore";

        /**
         * Indicates whether settings backup has been fully provisioned.
         * Type: int ( 0 = unprovisioned, 1 = fully provisioned )
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String BACKUP_PROVISIONED = "backup_provisioned";

        /**
         * Component of the transport to use for backup/restore.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String BACKUP_TRANSPORT = "backup_transport";

        /**
         * Indicates the version for which the setup wizard was last shown. The version gets
         * bumped for each release when there is new setup information to show.
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String LAST_SETUP_SHOWN = "last_setup_shown";

        /**
         * The interval in milliseconds after which Wi-Fi is considered idle.
         * When idle, it is possible for the device to be switched from Wi-Fi to
         * the mobile data network.
         * @hide
         * @deprecated Use {@link android.provider.Settings.Global#WIFI_IDLE_MS}
         * instead.
         */
        @Deprecated
        public static final String WIFI_IDLE_MS = Global.WIFI_IDLE_MS;

        /**
         * The global search provider chosen by the user (if multiple global
         * search providers are installed). This will be the provider returned
         * by {@link SearchManager#getGlobalSearchActivity()} if it's still
         * installed. This setting is stored as a flattened component name as
         * per {@link ComponentName#flattenToString()}.
         *
         * @hide
         */
        @Readable
        public static final String SEARCH_GLOBAL_SEARCH_ACTIVITY =
                "search_global_search_activity";

        /**
         * The number of promoted sources in GlobalSearch.
         * @hide
         */
        @Readable
        public static final String SEARCH_NUM_PROMOTED_SOURCES = "search_num_promoted_sources";
        /**
         * The maximum number of suggestions returned by GlobalSearch.
         * @hide
         */
        @Readable
        public static final String SEARCH_MAX_RESULTS_TO_DISPLAY = "search_max_results_to_display";
        /**
         * The number of suggestions GlobalSearch will ask each non-web search source for.
         * @hide
         */
        @Readable
        public static final String SEARCH_MAX_RESULTS_PER_SOURCE = "search_max_results_per_source";
        /**
         * The number of suggestions the GlobalSearch will ask the web search source for.
         * @hide
         */
        @Readable
        public static final String SEARCH_WEB_RESULTS_OVERRIDE_LIMIT =
                "search_web_results_override_limit";
        /**
         * The number of milliseconds that GlobalSearch will wait for suggestions from
         * promoted sources before continuing with all other sources.
         * @hide
         */
        @Readable
        public static final String SEARCH_PROMOTED_SOURCE_DEADLINE_MILLIS =
                "search_promoted_source_deadline_millis";
        /**
         * The number of milliseconds before GlobalSearch aborts search suggesiton queries.
         * @hide
         */
        @Readable
        public static final String SEARCH_SOURCE_TIMEOUT_MILLIS = "search_source_timeout_millis";
        /**
         * The maximum number of milliseconds that GlobalSearch shows the previous results
         * after receiving a new query.
         * @hide
         */
        @Readable
        public static final String SEARCH_PREFILL_MILLIS = "search_prefill_millis";
        /**
         * The maximum age of log data used for shortcuts in GlobalSearch.
         * @hide
         */
        @Readable
        public static final String SEARCH_MAX_STAT_AGE_MILLIS = "search_max_stat_age_millis";
        /**
         * The maximum age of log data used for source ranking in GlobalSearch.
         * @hide
         */
        @Readable
        public static final String SEARCH_MAX_SOURCE_EVENT_AGE_MILLIS =
                "search_max_source_event_age_millis";
        /**
         * The minimum number of impressions needed to rank a source in GlobalSearch.
         * @hide
         */
        @Readable
        public static final String SEARCH_MIN_IMPRESSIONS_FOR_SOURCE_RANKING =
                "search_min_impressions_for_source_ranking";
        /**
         * The minimum number of clicks needed to rank a source in GlobalSearch.
         * @hide
         */
        @Readable
        public static final String SEARCH_MIN_CLICKS_FOR_SOURCE_RANKING =
                "search_min_clicks_for_source_ranking";
        /**
         * The maximum number of shortcuts shown by GlobalSearch.
         * @hide
         */
        @Readable
        public static final String SEARCH_MAX_SHORTCUTS_RETURNED = "search_max_shortcuts_returned";
        /**
         * The size of the core thread pool for suggestion queries in GlobalSearch.
         * @hide
         */
        @Readable
        public static final String SEARCH_QUERY_THREAD_CORE_POOL_SIZE =
                "search_query_thread_core_pool_size";
        /**
         * The maximum size of the thread pool for suggestion queries in GlobalSearch.
         * @hide
         */
        @Readable
        public static final String SEARCH_QUERY_THREAD_MAX_POOL_SIZE =
                "search_query_thread_max_pool_size";
        /**
         * The size of the core thread pool for shortcut refreshing in GlobalSearch.
         * @hide
         */
        @Readable
        public static final String SEARCH_SHORTCUT_REFRESH_CORE_POOL_SIZE =
                "search_shortcut_refresh_core_pool_size";
        /**
         * The maximum size of the thread pool for shortcut refreshing in GlobalSearch.
         * @hide
         */
        @Readable
        public static final String SEARCH_SHORTCUT_REFRESH_MAX_POOL_SIZE =
                "search_shortcut_refresh_max_pool_size";
        /**
         * The maximun time that excess threads in the GlobalSeach thread pools will
         * wait before terminating.
         * @hide
         */
        @Readable
        public static final String SEARCH_THREAD_KEEPALIVE_SECONDS =
                "search_thread_keepalive_seconds";
        /**
         * The maximum number of concurrent suggestion queries to each source.
         * @hide
         */
        @Readable
        public static final String SEARCH_PER_SOURCE_CONCURRENT_QUERY_LIMIT =
                "search_per_source_concurrent_query_limit";

        /**
         * Whether or not alert sounds are played on StorageManagerService events.
         * (0 = false, 1 = true)
         * @hide
         */
        @Readable
        public static final String MOUNT_PLAY_NOTIFICATION_SND = "mount_play_not_snd";

        /**
         * Whether or not UMS auto-starts on UMS host detection. (0 = false, 1 = true)
         * @hide
         */
        @Readable
        public static final String MOUNT_UMS_AUTOSTART = "mount_ums_autostart";

        /**
         * Whether or not a notification is displayed on UMS host detection. (0 = false, 1 = true)
         * @hide
         */
        @Readable
        public static final String MOUNT_UMS_PROMPT = "mount_ums_prompt";

        /**
         * Whether or not a notification is displayed while UMS is enabled. (0 = false, 1 = true)
         * @hide
         */
        @Readable
        public static final String MOUNT_UMS_NOTIFY_ENABLED = "mount_ums_notify_enabled";

        /**
         * If nonzero, ANRs in invisible background processes bring up a dialog.
         * Otherwise, the process will be silently killed.
         *
         * Also prevents ANRs and crash dialogs from being suppressed.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String ANR_SHOW_BACKGROUND = "anr_show_background";

        /**
         * If nonzero, crashes in foreground processes will bring up a dialog.
         * Otherwise, the process will be silently killed.
         * @hide
         */
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String SHOW_FIRST_CRASH_DIALOG_DEV_OPTION =
                "show_first_crash_dialog_dev_option";

        /**
         * The {@link ComponentName} string of the service to be used as the voice recognition
         * service.
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String VOICE_RECOGNITION_SERVICE = "voice_recognition_service";

        /**
         * The {@link ComponentName} string of the selected spell checker service which is
         * one of the services managed by the text service manager.
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String SELECTED_SPELL_CHECKER = "selected_spell_checker";

        /**
         * {@link android.view.textservice.SpellCheckerSubtype#hashCode()} of the selected subtype
         * of the selected spell checker service which is one of the services managed by the text
         * service manager.
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String SELECTED_SPELL_CHECKER_SUBTYPE =
                "selected_spell_checker_subtype";

        /**
         * Whether spell checker is enabled or not.
         *
         * @hide
         */
        @Readable
        public static final String SPELL_CHECKER_ENABLED = "spell_checker_enabled";

        /**
         * What happens when the user presses the Power button while in-call
         * and the screen is on.<br/>
         * <b>Values:</b><br/>
         * 1 - The Power button turns off the screen and locks the device. (Default behavior)<br/>
         * 2 - The Power button hangs up the current call.<br/>
         *
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String INCALL_POWER_BUTTON_BEHAVIOR = "incall_power_button_behavior";

        /**
         * Whether the user allows minimal post processing or not.
         *
         * <p>Values:
         * 0 - Not allowed. Any preferences set through the Window.setPreferMinimalPostProcessing
         *     API will be ignored.
         * 1 - Allowed. Any preferences set through the Window.setPreferMinimalPostProcessing API
         *     will be respected and the appropriate signals will be sent to display.
         *     (Default behaviour)
         *
         * @hide
         */
        @Readable
        public static final String MINIMAL_POST_PROCESSING_ALLOWED =
                "minimal_post_processing_allowed";

        /**
         * No mode switching will happen.
         *
         * @see #MATCH_CONTENT_FRAME_RATE
         * @hide
         */
        public static final int MATCH_CONTENT_FRAMERATE_NEVER = 0;

        /**
         * Allow only refresh rate switching between modes in the same configuration group.
         * This way only switches without visual interruptions for the user will be allowed.
         *
         * @see #MATCH_CONTENT_FRAME_RATE
         * @hide
         */
        public static final int MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY = 1;

        /**
         * Allow refresh rate switching between all refresh rates even if the switch will have
         * visual interruptions for the user.
         *
         * @see #MATCH_CONTENT_FRAME_RATE
         * @hide
         */
        public static final int MATCH_CONTENT_FRAMERATE_ALWAYS = 2;

        /**
         * User's preference for refresh rate switching.
         *
         * <p>Values:
         * 0 - Never switch refresh rates.
         * 1 - Switch refresh rates only when it can be done seamlessly. (Default behaviour)
         * 2 - Always prefer refresh rate switching even if it's going to have visual interruptions
         *     for the user.
         *
         * @see android.view.Surface#setFrameRate
         * @see #MATCH_CONTENT_FRAMERATE_NEVER
         * @see #MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY
         * @see #MATCH_CONTENT_FRAMERATE_ALWAYS
         * @hide
         */
        public static final String MATCH_CONTENT_FRAME_RATE =
                "match_content_frame_rate";

        /**
         * INCALL_POWER_BUTTON_BEHAVIOR value for "turn off screen".
         * @hide
         */
        public static final int INCALL_POWER_BUTTON_BEHAVIOR_SCREEN_OFF = 0x1;

        /**
         * INCALL_POWER_BUTTON_BEHAVIOR value for "hang up".
         * @hide
         */
        public static final int INCALL_POWER_BUTTON_BEHAVIOR_HANGUP = 0x2;

        /**
         * INCALL_POWER_BUTTON_BEHAVIOR default value.
         * @hide
         */
        public static final int INCALL_POWER_BUTTON_BEHAVIOR_DEFAULT =
                INCALL_POWER_BUTTON_BEHAVIOR_SCREEN_OFF;

        /**
         * What happens when the user presses the Back button while in-call
         * and the screen is on.<br/>
         * <b>Values:</b><br/>
         * 0 - The Back buttons does nothing different.<br/>
         * 1 - The Back button hangs up the current call.<br/>
         *
         * @hide
         */
        @Readable
        public static final String INCALL_BACK_BUTTON_BEHAVIOR = "incall_back_button_behavior";

        /**
         * INCALL_BACK_BUTTON_BEHAVIOR value for no action.
         * @hide
         */
        public static final int INCALL_BACK_BUTTON_BEHAVIOR_NONE = 0x0;

        /**
         * INCALL_BACK_BUTTON_BEHAVIOR value for "hang up".
         * @hide
         */
        public static final int INCALL_BACK_BUTTON_BEHAVIOR_HANGUP = 0x1;

        /**
         * INCALL_POWER_BUTTON_BEHAVIOR default value.
         * @hide
         */
        public static final int INCALL_BACK_BUTTON_BEHAVIOR_DEFAULT =
                INCALL_BACK_BUTTON_BEHAVIOR_NONE;

        /**
         * Whether the device should wake when the wake gesture sensor detects motion.
         * @hide
         */
        @Readable
        public static final String WAKE_GESTURE_ENABLED = "wake_gesture_enabled";

        /**
         * Whether the device should doze if configured.
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String DOZE_ENABLED = "doze_enabled";

        /**
         * Indicates whether doze should be always on.
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String DOZE_ALWAYS_ON = "doze_always_on";

        /**
         * Whether the device should pulse on pick up gesture.
         * @hide
         */
        @Readable
        public static final String DOZE_PICK_UP_GESTURE = "doze_pulse_on_pick_up";

        /**
         * Whether the device should pulse on long press gesture.
         * @hide
         */
        @Readable
        public static final String DOZE_PULSE_ON_LONG_PRESS = "doze_pulse_on_long_press";

        /**
         * Whether the device should pulse on double tap gesture.
         * @hide
         */
        @Readable
        public static final String DOZE_DOUBLE_TAP_GESTURE = "doze_pulse_on_double_tap";

        /**
         * Whether the device should respond to the SLPI tap gesture.
         * @hide
         */
        @Readable
        public static final String DOZE_TAP_SCREEN_GESTURE = "doze_tap_gesture";

        /**
         * Gesture that wakes up the display, showing some version of the lock screen.
         * @hide
         */
        @Readable
        public static final String DOZE_WAKE_LOCK_SCREEN_GESTURE = "doze_wake_screen_gesture";

        /**
         * Gesture that wakes up the display, toggling between {@link Display.STATE_OFF} and
         * {@link Display.STATE_DOZE}.
         * @hide
         */
        @Readable
        public static final String DOZE_WAKE_DISPLAY_GESTURE = "doze_wake_display_gesture";

        /**
         * Gesture that wakes up the display on quick pickup, toggling between
         * {@link Display.STATE_OFF} and {@link Display.STATE_DOZE}.
         * @hide
         */
        public static final String DOZE_QUICK_PICKUP_GESTURE = "doze_quick_pickup_gesture";

        /**
         * Whether the device should suppress the current doze configuration and disable dozing.
         * @hide
         */
        @Readable
        public static final String SUPPRESS_DOZE = "suppress_doze";

        /**
         * Gesture that skips media.
         * @hide
         */
        @Readable
        public static final String SKIP_GESTURE = "skip_gesture";

        /**
         * Count of successful gestures.
         * @hide
         */
        @Readable
        public static final String SKIP_GESTURE_COUNT = "skip_gesture_count";

        /**
         * Count of non-gesture interaction.
         * @hide
         */
        @Readable
        public static final String SKIP_TOUCH_COUNT = "skip_touch_count";

        /**
         * Direction to advance media for skip gesture
         * @hide
         */
        @Readable
        public static final String SKIP_DIRECTION = "skip_gesture_direction";

        /**
         * Gesture that silences sound (alarms, notification, calls).
         * @hide
         */
        @Readable
        public static final String SILENCE_GESTURE = "silence_gesture";

        /**
         * Count of successful silence alarms gestures.
         * @hide
         */
        @Readable
        public static final String SILENCE_ALARMS_GESTURE_COUNT = "silence_alarms_gesture_count";

        /**
         * Count of successful silence timer gestures.
         * @hide
         */
        @Readable
        public static final String SILENCE_TIMER_GESTURE_COUNT = "silence_timer_gesture_count";

        /**
         * Count of successful silence call gestures.
         * @hide
         */
        @Readable
        public static final String SILENCE_CALL_GESTURE_COUNT = "silence_call_gesture_count";

        /**
         * Count of non-gesture interaction.
         * @hide
         */
        @Readable
        public static final String SILENCE_ALARMS_TOUCH_COUNT = "silence_alarms_touch_count";

        /**
         * Count of non-gesture interaction.
         * @hide
         */
        @Readable
        public static final String SILENCE_TIMER_TOUCH_COUNT = "silence_timer_touch_count";

        /**
         * Count of non-gesture interaction.
         * @hide
         */
        @Readable
        public static final String SILENCE_CALL_TOUCH_COUNT = "silence_call_touch_count";

        /**
         * Number of successful "Motion Sense" tap gestures to pause media.
         * @hide
         */
        @Readable
        public static final String AWARE_TAP_PAUSE_GESTURE_COUNT = "aware_tap_pause_gesture_count";

        /**
         * Number of touch interactions to pause media when a "Motion Sense" gesture could
         * have been used.
         * @hide
         */
        @Readable
        public static final String AWARE_TAP_PAUSE_TOUCH_COUNT = "aware_tap_pause_touch_count";

        /**
         * For user preference if swipe bottom to expand notification gesture enabled.
         * @hide
         */
        public static final String SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED =
                "swipe_bottom_to_notification_enabled";

        /**
         * Controls whether One-Handed mode is currently activated.
         * @hide
         */
        public static final String ONE_HANDED_MODE_ACTIVATED = "one_handed_mode_activated";

        /**
         * For user preference if One-Handed Mode enabled.
         * @hide
         */
        public static final String ONE_HANDED_MODE_ENABLED = "one_handed_mode_enabled";

        /**
         * For user preference if One-Handed Mode timeout.
         * @hide
         */
        public static final String ONE_HANDED_MODE_TIMEOUT = "one_handed_mode_timeout";

        /**
         * For user taps app to exit One-Handed Mode.
         * @hide
         */
        public static final String TAPS_APP_TO_EXIT = "taps_app_to_exit";

        /**
         * Internal use, one handed mode tutorial showed times.
         * @hide
         */
        public static final String ONE_HANDED_TUTORIAL_SHOW_COUNT =
                "one_handed_tutorial_show_count";

        /**
         * Toggle to enable/disable for the apps to use the Ui translation for Views. The value
         * indicates whether the Ui translation is enabled by the user.
         * <p>
         * Type: {@code int} ({@code 0} for disabled, {@code 1} for enabled)
         *
         * @hide
         */
        @SystemApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String UI_TRANSLATION_ENABLED = "ui_translation_enabled";

        /**
         * The current night mode that has been selected by the user.  Owned
         * and controlled by UiModeManagerService.  Constants are as per
         * UiModeManager.
         * @hide
         */
        @Readable
        public static final String UI_NIGHT_MODE = "ui_night_mode";

        /**
         * The current night mode custom type that has been selected by the user.  Owned
         * and controlled by UiModeManagerService. Constants are as per UiModeManager.
         * @hide
         */
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String UI_NIGHT_MODE_CUSTOM_TYPE = "ui_night_mode_custom_type";

        /**
         * The current night mode that has been overridden to turn on by the system.  Owned
         * and controlled by UiModeManagerService.  Constants are as per
         * UiModeManager.
         * @hide
         */
        @Readable
        public static final String UI_NIGHT_MODE_OVERRIDE_ON = "ui_night_mode_override_on";

        /**
         * The last computed night mode bool the last time the phone was on
         * @hide
         */
        public static final String UI_NIGHT_MODE_LAST_COMPUTED = "ui_night_mode_last_computed";

        /**
         * The current night mode that has been overridden to turn off by the system.  Owned
         * and controlled by UiModeManagerService.  Constants are as per
         * UiModeManager.
         * @hide
         */
        @Readable
        public static final String UI_NIGHT_MODE_OVERRIDE_OFF = "ui_night_mode_override_off";

        /**
         * Whether screensavers are enabled.
         * @hide
         */
        @Readable
        public static final String SCREENSAVER_ENABLED = "screensaver_enabled";

        /**
         * The user's chosen screensaver components.
         *
         * These will be launched by the PhoneWindowManager after a timeout when not on
         * battery, or upon dock insertion (if SCREENSAVER_ACTIVATE_ON_DOCK is set to 1).
         * @hide
         */
        @Readable
        public static final String SCREENSAVER_COMPONENTS = "screensaver_components";

        /**
         * If screensavers are enabled, whether the screensaver should be automatically launched
         * when the device is inserted into a (desk) dock.
         * @hide
         */
        @Readable
        public static final String SCREENSAVER_ACTIVATE_ON_DOCK = "screensaver_activate_on_dock";

        /**
         * If screensavers are enabled, whether the screensaver should be automatically launched
         * when the screen times out when not on battery.
         * @hide
         */
        @Readable
        public static final String SCREENSAVER_ACTIVATE_ON_SLEEP = "screensaver_activate_on_sleep";

        /**
         * If screensavers are enabled, the default screensaver component.
         * @hide
         */
        @Readable
        public static final String SCREENSAVER_DEFAULT_COMPONENT = "screensaver_default_component";

        /**
         * The complications that are enabled to be shown over the screensaver by the user. Holds
         * a comma separated list of
         * {@link com.android.settingslib.dream.DreamBackend.ComplicationType}.
         *
         * @hide
         */
        public static final String SCREENSAVER_ENABLED_COMPLICATIONS =
                "screensaver_enabled_complications";

        /**
         * The default NFC payment component
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final String NFC_PAYMENT_DEFAULT_COMPONENT = "nfc_payment_default_component";

        /**
         * Whether NFC payment is handled by the foreground application or a default.
         * @hide
         */
        @Readable
        public static final String NFC_PAYMENT_FOREGROUND = "nfc_payment_foreground";

        /**
         * Specifies the package name currently configured to be the primary sms application
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String SMS_DEFAULT_APPLICATION = "sms_default_application";

        /**
         * Specifies the package name currently configured to be the default dialer application
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String DIALER_DEFAULT_APPLICATION = "dialer_default_application";

        /**
         * Specifies the component name currently configured to be the default call screening
         * application
         * @hide
         */
        @Readable
        public static final String CALL_SCREENING_DEFAULT_COMPONENT =
                "call_screening_default_component";

        /**
         * Specifies the package name currently configured to be the emergency assistance application
         *
         * @see android.telephony.TelephonyManager#ACTION_EMERGENCY_ASSISTANCE
         *
         * @hide
         */
        @Readable
        public static final String EMERGENCY_ASSISTANCE_APPLICATION = "emergency_assistance_application";

        /**
         * Specifies whether the current app context on scren (assist data) will be sent to the
         * assist application (active voice interaction service).
         *
         * @hide
         */
        @Readable
        public static final String ASSIST_STRUCTURE_ENABLED = "assist_structure_enabled";

        /**
         * Specifies whether a screenshot of the screen contents will be sent to the assist
         * application (active voice interaction service).
         *
         * @hide
         */
        @Readable
        public static final String ASSIST_SCREENSHOT_ENABLED = "assist_screenshot_enabled";

        /**
         * Specifies whether the screen will show an animation if screen contents are sent to the
         * assist application (active voice interaction service).
         *
         * Note that the disclosure will be forced for third-party assistants or if the device
         * does not support disabling it.
         *
         * @hide
         */
        @Readable
        public static final String ASSIST_DISCLOSURE_ENABLED = "assist_disclosure_enabled";

        /**
         * Control if rotation suggestions are sent to System UI when in rotation locked mode.
         * Done to enable screen rotation while the screen rotation is locked. Enabling will
         * poll the accelerometer in rotation locked mode.
         *
         * If 0, then rotation suggestions are not sent to System UI. If 1, suggestions are sent.
         *
         * @hide
         */
        @Readable
        public static final String SHOW_ROTATION_SUGGESTIONS = "show_rotation_suggestions";

        /**
         * The disabled state of SHOW_ROTATION_SUGGESTIONS.
         * @hide
         */
        public static final int SHOW_ROTATION_SUGGESTIONS_DISABLED = 0x0;

        /**
         * The enabled state of SHOW_ROTATION_SUGGESTIONS.
         * @hide
         */
        public static final int SHOW_ROTATION_SUGGESTIONS_ENABLED = 0x1;

        /**
         * The default state of SHOW_ROTATION_SUGGESTIONS.
         * @hide
         */
        public static final int SHOW_ROTATION_SUGGESTIONS_DEFAULT =
                SHOW_ROTATION_SUGGESTIONS_ENABLED;

        /**
         * The number of accepted rotation suggestions. Used to determine if the user has been
         * introduced to rotation suggestions.
         * @hide
         */
        @Readable
        public static final String NUM_ROTATION_SUGGESTIONS_ACCEPTED =
                "num_rotation_suggestions_accepted";

        /**
         * Read only list of the service components that the current user has explicitly allowed to
         * see and assist with all of the user's notifications.
         *
         * @deprecated Use
         * {@link NotificationManager#isNotificationAssistantAccessGranted(ComponentName)}.
         * @hide
         */
        @Deprecated
        @Readable
        public static final String ENABLED_NOTIFICATION_ASSISTANT =
                "enabled_notification_assistant";

        /**
         * Read only list of the service components that the current user has explicitly allowed to
         * see all of the user's notifications, separated by ':'.
         *
         * @hide
         * @deprecated Use
         * {@link NotificationManager#isNotificationListenerAccessGranted(ComponentName)}.
         */
        @Deprecated
        @UnsupportedAppUsage
        @Readable
        public static final String ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners";

        /**
         * Read only list of the packages that the current user has explicitly allowed to
         * manage do not disturb, separated by ':'.
         *
         * @deprecated Use {@link NotificationManager#isNotificationPolicyAccessGranted()}.
         * @hide
         */
        @Deprecated
        @TestApi
        @Readable
        public static final String ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES =
                "enabled_notification_policy_access_packages";

        /**
         * Defines whether managed profile ringtones should be synced from it's parent profile
         * <p>
         * 0 = ringtones are not synced
         * 1 = ringtones are synced from the profile's parent (default)
         * <p>
         * This value is only used for managed profiles.
         * @hide
         */
        @TestApi
        @Readable
        @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
        public static final String SYNC_PARENT_SOUNDS = "sync_parent_sounds";

        /**
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @TestApi
        @Readable
        public static final String IMMERSIVE_MODE_CONFIRMATIONS = "immersive_mode_confirmations";

        /**
         * This is the query URI for finding a print service to install.
         *
         * @hide
         */
        @Readable
        public static final String PRINT_SERVICE_SEARCH_URI = "print_service_search_uri";

        /**
         * This is the query URI for finding a NFC payment service to install.
         *
         * @hide
         */
        @Readable
        public static final String PAYMENT_SERVICE_SEARCH_URI = "payment_service_search_uri";

        /**
         * This is the query URI for finding a auto fill service to install.
         *
         * @hide
         */
        @Readable
        public static final String AUTOFILL_SERVICE_SEARCH_URI = "autofill_service_search_uri";

        /**
         * If enabled, apps should try to skip any introductory hints on first launch. This might
         * apply to users that are already familiar with the environment or temporary users.
         * <p>
         * Type : int (0 to show hints, 1 to skip showing hints)
         */
        @Readable
        public static final String SKIP_FIRST_USE_HINTS = "skip_first_use_hints";

        /**
         * Persisted playback time after a user confirmation of an unsafe volume level.
         *
         * @hide
         */
        @Readable
        public static final String UNSAFE_VOLUME_MUSIC_ACTIVE_MS = "unsafe_volume_music_active_ms";

        /**
         * Indicates whether the spatial audio feature was enabled for this user.
         *
         * Type : int (0 disabled, 1 enabled)
         *
         * @hide
         */
        public static final String SPATIAL_AUDIO_ENABLED = "spatial_audio_enabled";

        /**
         * Indicates whether notification display on the lock screen is enabled.
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String LOCK_SCREEN_SHOW_NOTIFICATIONS =
                "lock_screen_show_notifications";

        /**
         * Indicates whether the lock screen should display silent notifications.
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        @Readable
        public static final String LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS =
                "lock_screen_show_silent_notifications";

        /**
         * Indicates whether snooze options should be shown on notifications
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        @Readable
        public static final String SHOW_NOTIFICATION_SNOOZE = "show_notification_snooze";

        /**
         * List of TV inputs that are currently hidden. This is a string
         * containing the IDs of all hidden TV inputs. Each ID is encoded by
         * {@link android.net.Uri#encode(String)} and separated by ':'.
         * @hide
         */
        @Readable
        public static final String TV_INPUT_HIDDEN_INPUTS = "tv_input_hidden_inputs";

        /**
         * List of custom TV input labels. This is a string containing <TV input id, custom name>
         * pairs. TV input id and custom name are encoded by {@link android.net.Uri#encode(String)}
         * and separated by ','. Each pair is separated by ':'.
         * @hide
         */
        @Readable
        public static final String TV_INPUT_CUSTOM_LABELS = "tv_input_custom_labels";

        /**
         * Whether TV app uses non-system inputs.
         *
         * <p>
         * The value is boolean (1 or 0), where 1 means non-system TV inputs are allowed,
         * and 0 means non-system TV inputs are not allowed.
         *
         * <p>
         * Devices such as sound bars may have changed the system property allow_third_party_inputs
         * to false so the TV Application only uses HDMI and other built in inputs. This setting
         * allows user to override the default and have the TV Application use third party TV inputs
         * available on play store.
         *
         * @hide
         */
        @Readable
        public static final String TV_APP_USES_NON_SYSTEM_INPUTS = "tv_app_uses_non_system_inputs";

        /**
         * Whether automatic routing of system audio to USB audio peripheral is disabled.
         * The value is boolean (1 or 0), where 1 means automatic routing is disabled,
         * and 0 means automatic routing is enabled.
         *
         * @hide
         */
        @Readable
        public static final String USB_AUDIO_AUTOMATIC_ROUTING_DISABLED =
                "usb_audio_automatic_routing_disabled";

        /**
         * The timeout in milliseconds before the device fully goes to sleep after
         * a period of inactivity.  This value sets an upper bound on how long the device
         * will stay awake or dreaming without user activity.  It should generally
         * be longer than {@link Settings.System#SCREEN_OFF_TIMEOUT} as otherwise the device
         * will sleep before it ever has a chance to dream.
         * <p>
         * Use -1 to disable this timeout.
         * </p>
         *
         * @hide
         */
        @Readable
        public static final String SLEEP_TIMEOUT = "sleep_timeout";

        /**
         * The timeout in milliseconds before the device goes to sleep due to user inattentiveness,
         * even if the system is holding wakelocks. It should generally be longer than {@code
         * config_attentiveWarningDuration}, as otherwise the device will show the attentive
         * warning constantly. Small timeouts are discouraged, as they will cause the device to
         * go to sleep quickly after waking up.
         * <p>
         * Use -1 to disable this timeout.
         * </p>
         *
         * @hide
         */
        @Readable
        public static final String ATTENTIVE_TIMEOUT = "attentive_timeout";

        /**
         * Controls whether double tap to wake is enabled.
         * @hide
         */
        @Readable
        public static final String DOUBLE_TAP_TO_WAKE = "double_tap_to_wake";

        /**
         * The current assistant component. It could be a voice interaction service,
         * or an activity that handles ACTION_ASSIST, or empty which means using the default
         * handling.
         *
         * <p>This should be set indirectly by setting the {@link
         * android.app.role.RoleManager#ROLE_ASSISTANT assistant role}.
         *
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String ASSISTANT = "assistant";

        /**
         * Whether the camera launch gesture should be disabled.
         *
         * @hide
         */
        @Readable
        public static final String CAMERA_GESTURE_DISABLED = "camera_gesture_disabled";

        /**
         * Whether the emergency gesture should be enabled.
         *
         * @hide
         */
        public static final String EMERGENCY_GESTURE_ENABLED = "emergency_gesture_enabled";

        /**
         * Whether the emergency gesture sound should be enabled.
         *
         * @hide
         */
        public static final String EMERGENCY_GESTURE_SOUND_ENABLED =
                "emergency_gesture_sound_enabled";

        /**
         * Whether the camera launch gesture to double tap the power button when the screen is off
         * should be disabled.
         *
         * @hide
         */
        @Readable
        public static final String CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED =
                "camera_double_tap_power_gesture_disabled";

        /**
         * Whether the camera double twist gesture to flip between front and back mode should be
         * enabled.
         *
         * @hide
         */
        @Readable
        public static final String CAMERA_DOUBLE_TWIST_TO_FLIP_ENABLED =
                "camera_double_twist_to_flip_enabled";

        /**
         * Whether or not the smart camera lift trigger that launches the camera when the user moves
         * the phone into a position for taking photos should be enabled.
         *
         * @hide
         */
        @Readable
        public static final String CAMERA_LIFT_TRIGGER_ENABLED = "camera_lift_trigger_enabled";

        /**
         * The default enable state of the camera lift trigger.
         *
         * @hide
         */
        public static final int CAMERA_LIFT_TRIGGER_ENABLED_DEFAULT = 1;

        /**
         * Whether or not the flashlight (camera torch mode) is available required to turn
         * on flashlight.
         *
         * @hide
         */
        @Readable
        public static final String FLASHLIGHT_AVAILABLE = "flashlight_available";

        /**
         * Whether or not flashlight is enabled.
         *
         * @hide
         */
        @Readable
        public static final String FLASHLIGHT_ENABLED = "flashlight_enabled";

        /**
         * Whether or not face unlock is allowed on Keyguard.
         * @hide
         */
        @Readable
        public static final String FACE_UNLOCK_KEYGUARD_ENABLED = "face_unlock_keyguard_enabled";

        /**
         * Whether or not face unlock dismisses the keyguard.
         * @hide
         */
        @Readable
        public static final String FACE_UNLOCK_DISMISSES_KEYGUARD =
                "face_unlock_dismisses_keyguard";

        /**
         * Whether or not media is shown automatically when bypassing as a heads up.
         * @hide
         */
        @Readable
        public static final String SHOW_MEDIA_WHEN_BYPASSING =
                "show_media_when_bypassing";

        /**
         * Whether or not face unlock requires attention. This is a cached value, the source of
         * truth is obtained through the HAL.
         * @hide
         */
        @Readable
        public static final String FACE_UNLOCK_ATTENTION_REQUIRED =
                "face_unlock_attention_required";

        /**
         * Whether or not face unlock requires a diverse set of poses during enrollment. This is a
         * cached value, the source of truth is obtained through the HAL.
         * @hide
         */
        @Readable
        public static final String FACE_UNLOCK_DIVERSITY_REQUIRED =
                "face_unlock_diversity_required";


        /**
         * Whether or not face unlock is allowed for apps (through BiometricPrompt).
         * @hide
         */
        @Readable
        public static final String FACE_UNLOCK_APP_ENABLED = "face_unlock_app_enabled";

        /**
         * Whether or not face unlock always requires user confirmation, meaning {@link
         * android.hardware.biometrics.BiometricPrompt.Builder#setConfirmationRequired(boolean)}
         * is always 'true'. This overrides the behavior that apps choose in the
         * setConfirmationRequired API.
         * @hide
         */
        @Readable
        public static final String FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION =
                "face_unlock_always_require_confirmation";

        /**
         * Whether or not a user should re enroll their face.
         *
         * Face unlock re enroll.
         *  0 = No re enrollment.
         *  1 = Re enrollment is suggested.
         *  2 = Re enrollment is required after a set time period.
         *  3 = Re enrollment is required immediately.
         *
         * @hide
         */
        @Readable
        public static final String FACE_UNLOCK_RE_ENROLL = "face_unlock_re_enroll";

        /**
         * Whether or not debugging is enabled.
         * @hide
         */
        @Readable
        public static final String BIOMETRIC_DEBUG_ENABLED =
                "biometric_debug_enabled";

        /**
         * Whether or not biometric is allowed on Keyguard.
         * @hide
         */
        @Readable
        public static final String BIOMETRIC_KEYGUARD_ENABLED = "biometric_keyguard_enabled";

        /**
         * Whether or not biometric is allowed for apps (through BiometricPrompt).
         * @hide
         */
        @Readable
        public static final String BIOMETRIC_APP_ENABLED = "biometric_app_enabled";

        /**
         * Whether the assist gesture should be enabled.
         *
         * @hide
         */
        @Readable
        public static final String ASSIST_GESTURE_ENABLED = "assist_gesture_enabled";

        /**
         * Sensitivity control for the assist gesture.
         *
         * @hide
         */
        @Readable
        public static final String ASSIST_GESTURE_SENSITIVITY = "assist_gesture_sensitivity";

        /**
         * Whether the assist gesture should silence alerts.
         *
         * @hide
         */
        @Readable
        public static final String ASSIST_GESTURE_SILENCE_ALERTS_ENABLED =
                "assist_gesture_silence_alerts_enabled";

        /**
         * Whether the assist gesture should wake the phone.
         *
         * @hide
         */
        @Readable
        public static final String ASSIST_GESTURE_WAKE_ENABLED =
                "assist_gesture_wake_enabled";

        /**
         * Indicates whether the Assist Gesture Deferred Setup has been completed.
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String ASSIST_GESTURE_SETUP_COMPLETE = "assist_gesture_setup_complete";

        /**
         * Whether the assistant can be triggered by a touch gesture.
         *
         * @hide
         */
        public static final String ASSIST_TOUCH_GESTURE_ENABLED =
                "assist_touch_gesture_enabled";

        /**
         * Whether the assistant can be triggered by long-pressing the home button
         *
         * @hide
         */
        public static final String ASSIST_LONG_PRESS_HOME_ENABLED =
                "assist_long_press_home_enabled";

        /**
         * Control whether Trust Agents are in active unlock or extend unlock mode.
         * @hide
         */
        @Readable
        public static final String TRUST_AGENTS_EXTEND_UNLOCK = "trust_agents_extend_unlock";

        /**
         * Control whether the screen locks when trust is lost.
         * @hide
         */
        @Readable
        public static final String LOCK_SCREEN_WHEN_TRUST_LOST = "lock_screen_when_trust_lost";

        /**
         * Control whether Night display is currently activated.
         * @hide
         */
        @Readable
        public static final String NIGHT_DISPLAY_ACTIVATED = "night_display_activated";

        /**
         * Control whether Night display will automatically activate/deactivate.
         * @hide
         */
        @Readable
        public static final String NIGHT_DISPLAY_AUTO_MODE = "night_display_auto_mode";

        /**
         * Control the color temperature of Night Display, represented in Kelvin.
         * @hide
         */
        @Readable
        public static final String NIGHT_DISPLAY_COLOR_TEMPERATURE =
                "night_display_color_temperature";

        /**
         * Custom time when Night display is scheduled to activate.
         * Represented as milliseconds from midnight (e.g. 79200000 == 10pm).
         * @hide
         */
        @Readable
        public static final String NIGHT_DISPLAY_CUSTOM_START_TIME =
                "night_display_custom_start_time";

        /**
         * Custom time when Night display is scheduled to deactivate.
         * Represented as milliseconds from midnight (e.g. 21600000 == 6am).
         * @hide
         */
        @Readable
        public static final String NIGHT_DISPLAY_CUSTOM_END_TIME = "night_display_custom_end_time";

        /**
         * A String representing the LocalDateTime when Night display was last activated. Use to
         * decide whether to apply the current activated state after a reboot or user change. In
         * legacy cases, this is represented by the time in milliseconds (since epoch).
         * @hide
         */
        @Readable
        public static final String NIGHT_DISPLAY_LAST_ACTIVATED_TIME =
                "night_display_last_activated_time";

        /**
         * Control whether display white balance is currently enabled.
         * @hide
         */
        @Readable
        public static final String DISPLAY_WHITE_BALANCE_ENABLED = "display_white_balance_enabled";

        /**
         * Names of the service components that the current user has explicitly allowed to
         * be a VR mode listener, separated by ':'.
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @TestApi
        @Readable
        public static final String ENABLED_VR_LISTENERS = "enabled_vr_listeners";

        /**
         * Behavior of the display while in VR mode.
         *
         * One of {@link #VR_DISPLAY_MODE_LOW_PERSISTENCE} or {@link #VR_DISPLAY_MODE_OFF}.
         *
         * @hide
         */
        @Readable
        public static final String VR_DISPLAY_MODE = "vr_display_mode";

        /**
         * Lower the display persistence while the system is in VR mode.
         *
         * @see PackageManager#FEATURE_VR_MODE_HIGH_PERFORMANCE
         *
         * @hide.
         */
        public static final int VR_DISPLAY_MODE_LOW_PERSISTENCE = 0;

        /**
         * Do not alter the display persistence while the system is in VR mode.
         *
         * @see PackageManager#FEATURE_VR_MODE_HIGH_PERFORMANCE
         *
         * @hide.
         */
        public static final int VR_DISPLAY_MODE_OFF = 1;

        /**
         * The latest SDK version that CarrierAppUtils#disableCarrierAppsUntilPrivileged has been
         * executed for.
         *
         * <p>This is used to ensure that we only take one pass which will disable apps that are not
         * privileged (if any). From then on, we only want to enable apps (when a matching SIM is
         * inserted), to avoid disabling an app that the user might actively be using.
         *
         * <p>Will be set to {@link android.os.Build.VERSION#SDK_INT} once executed. Note that older
         * SDK versions prior to R set 1 for this value.
         *
         * @hide
         */
        @Readable
        public static final String CARRIER_APPS_HANDLED = "carrier_apps_handled";

        /**
         * Whether parent user can access remote contact in managed profile.
         *
         * @hide
         */
        @Readable
        public static final String MANAGED_PROFILE_CONTACT_REMOTE_SEARCH =
                "managed_profile_contact_remote_search";

        /**
         * Whether parent profile can access remote calendar data in managed profile.
         *
         * @hide
         */
        @Readable
        public static final String CROSS_PROFILE_CALENDAR_ENABLED =
                "cross_profile_calendar_enabled";

        /**
         * Whether or not the automatic storage manager is enabled and should run on the device.
         *
         * @hide
         */
        @Readable
        public static final String AUTOMATIC_STORAGE_MANAGER_ENABLED =
                "automatic_storage_manager_enabled";

        /**
         * How many days of information for the automatic storage manager to retain on the device.
         *
         * @hide
         */
        @Readable
        public static final String AUTOMATIC_STORAGE_MANAGER_DAYS_TO_RETAIN =
                "automatic_storage_manager_days_to_retain";

        /**
         * Default number of days of information for the automatic storage manager to retain.
         *
         * @hide
         */
        public static final int AUTOMATIC_STORAGE_MANAGER_DAYS_TO_RETAIN_DEFAULT = 90;

        /**
         * How many bytes the automatic storage manager has cleared out.
         *
         * @hide
         */
        @Readable
        public static final String AUTOMATIC_STORAGE_MANAGER_BYTES_CLEARED =
                "automatic_storage_manager_bytes_cleared";

        /**
         * Last run time for the automatic storage manager.
         *
         * @hide
         */
        @Readable
        public static final String AUTOMATIC_STORAGE_MANAGER_LAST_RUN =
                "automatic_storage_manager_last_run";
        /**
         * If the automatic storage manager has been disabled by policy. Note that this doesn't
         * mean that the automatic storage manager is prevented from being re-enabled -- this only
         * means that it was turned off by policy at least once.
         *
         * @hide
         */
        @Readable
        public static final String AUTOMATIC_STORAGE_MANAGER_TURNED_OFF_BY_POLICY =
                "automatic_storage_manager_turned_off_by_policy";

        /**
         * Whether SystemUI navigation keys is enabled.
         * @hide
         */
        @Readable
        public static final String SYSTEM_NAVIGATION_KEYS_ENABLED =
                "system_navigation_keys_enabled";

        /**
         * Holds comma separated list of ordering of QS tiles.
         *
         * @hide
         */
        @Readable
        public static final String QS_TILES = "sysui_qs_tiles";

        /**
         * Whether this user has enabled Quick controls.
         *
         * 0 indicates disabled and 1 indicates enabled. A non existent value should be treated as
         * enabled.
         *
         * @deprecated Controls are migrated to Quick Settings, rendering this unnecessary and will
         *             be removed in a future release.
         * @hide
         */
        @Readable
        @Deprecated
        public static final String CONTROLS_ENABLED = "controls_enabled";

        /**
         * Whether power menu content (cards, passes, controls) will be shown when device is locked.
         *
         * 0 indicates hide and 1 indicates show. A non existent value will be treated as hide.
         * @hide
         */
        @TestApi
        @Readable
        public static final String POWER_MENU_LOCKED_SHOW_CONTENT =
                "power_menu_locked_show_content";

        /**
         * Whether home controls should be accessible from the lockscreen
         *
         * @hide
         */
        public static final String LOCKSCREEN_SHOW_CONTROLS = "lockscreen_show_controls";

        /**
         * Whether trivial home controls can be used without authentication
         *
         * @hide
         */
        public static final String LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS =
                "lockscreen_allow_trivial_controls";

        /**
         * Whether wallet should be accessible from the lockscreen
         *
         * @hide
         */
        public static final String LOCKSCREEN_SHOW_WALLET = "lockscreen_show_wallet";

        /**
         * Whether to use the lockscreen double-line clock
         *
         * @hide
         */
        public static final String LOCKSCREEN_USE_DOUBLE_LINE_CLOCK =
                "lockscreen_use_double_line_clock";

        /**
         * Whether to show the vibrate icon in the Status Bar (default off)
         *
         * @hide
         */
        public static final String STATUS_BAR_SHOW_VIBRATE_ICON = "status_bar_show_vibrate_icon";

        /**
         * Specifies whether the web action API is enabled.
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String INSTANT_APPS_ENABLED = "instant_apps_enabled";

        /**
         * Whether qr code scanner should be accessible from the lockscreen
         *
         * @hide
         */
        public static final String LOCK_SCREEN_SHOW_QR_CODE_SCANNER =
                "lock_screen_show_qr_code_scanner";

        /**
         * Whether or not to enable qr code code scanner setting to enable/disable lockscreen
         * entry point. Any value apart from null means setting needs to be enabled
         *
         * @hide
         */
        public static final String SHOW_QR_CODE_SCANNER_SETTING =
                "show_qr_code_scanner_setting";

        /**
         * Has this pairable device been paired or upgraded from a previously paired system.
         * @hide
         */
        @Readable
        public static final String DEVICE_PAIRED = "device_paired";

        /**
         * Specifies additional package name for broadcasting the CMAS messages.
         * @hide
         */
        @Readable
        public static final String CMAS_ADDITIONAL_BROADCAST_PKG = "cmas_additional_broadcast_pkg";

        /**
         * Whether the launcher should show any notification badges.
         * The value is boolean (1 or 0).
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @TestApi
        @Readable
        public static final String NOTIFICATION_BADGING = "notification_badging";

        /**
         * When enabled the system will maintain a rolling history of received notifications. When
         * disabled the history will be disabled and deleted.
         *
         * The value 1 - enable, 0 - disable
         * @hide
         */
        @Readable
        public static final String NOTIFICATION_HISTORY_ENABLED = "notification_history_enabled";

        /**
         * When enabled conversations marked as favorites will be set to bubble.
         *
         * The value 1 - enable, 0 - disable
         * @hide
         */
        @Readable
        public static final String BUBBLE_IMPORTANT_CONVERSATIONS
                = "bubble_important_conversations";

        /**
         * When enabled, notifications able to bubble will display an affordance allowing the user
         * to bubble them.
         * The value is boolean (1 to enable or 0 to disable).
         *
         * @hide
         */
        @TestApi
        @SuppressLint("NoSettingsProvider")
        @Readable
        public static final String NOTIFICATION_BUBBLES = "notification_bubbles";

        /**
         * Whether notifications are dismissed by a right-to-left swipe (instead of a left-to-right
         * swipe).
         *
         * @hide
         */
        @Readable
        public static final String NOTIFICATION_DISMISS_RTL = "notification_dismiss_rtl";

        /**
         * Whether the app-level notification setting is represented by a manifest permission.
         *
         * @hide
         */
        @Readable
        public static final String NOTIFICATION_PERMISSION_ENABLED =
                "notification_permission_enabled";

        /**
         * Comma separated list of QS tiles that have been auto-added already.
         * @hide
         */
        @Readable
        public static final String QS_AUTO_ADDED_TILES = "qs_auto_tiles";

        /**
         * The duration of timeout, in milliseconds, to switch from a non-primary user to the
         * primary user when the device is docked.
         * @hide
         */
        public static final String TIMEOUT_TO_USER_ZERO = "timeout_to_user_zero";

        /**
         * Backup manager behavioral parameters.
         * This is encoded as a key=value list, separated by commas. Ex:
         *
         * "key_value_backup_interval_milliseconds=14400000,key_value_backup_require_charging=true"
         *
         * The following keys are supported:
         *
         * <pre>
         * key_value_backup_interval_milliseconds  (long)
         * key_value_backup_fuzz_milliseconds      (long)
         * key_value_backup_require_charging       (boolean)
         * key_value_backup_required_network_type  (int)
         * full_backup_interval_milliseconds       (long)
         * full_backup_require_charging            (boolean)
         * full_backup_required_network_type       (int)
         * backup_finished_notification_receivers  (String[])
         * </pre>
         *
         * backup_finished_notification_receivers uses ":" as delimeter for values.
         *
         * <p>
         * Type: string
         * @hide
         */
        @Readable
        public static final String BACKUP_MANAGER_CONSTANTS = "backup_manager_constants";


        /**
         * Local transport parameters so we can configure it for tests.
         * This is encoded as a key=value list, separated by commas.
         *
         * The following keys are supported:
         *
         * <pre>
         * fake_encryption_flag  (boolean)
         * </pre>
         *
         * <p>
         * Type: string
         * @hide
         */
        @Readable
        public static final String BACKUP_LOCAL_TRANSPORT_PARAMETERS =
                "backup_local_transport_parameters";

        /**
         * Flag to set if the system should predictively attempt to re-enable Bluetooth while
         * the user is driving.
         * @hide
         */
        @Readable
        public static final String BLUETOOTH_ON_WHILE_DRIVING = "bluetooth_on_while_driving";

        /**
         * What behavior should be invoked when the volume hush gesture is triggered
         * One of VOLUME_HUSH_OFF, VOLUME_HUSH_VIBRATE, VOLUME_HUSH_MUTE.
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String VOLUME_HUSH_GESTURE = "volume_hush_gesture";

        /** @hide */
        @SystemApi
        public static final int VOLUME_HUSH_OFF = 0;
        /** @hide */
        @SystemApi
        public static final int VOLUME_HUSH_VIBRATE = 1;
        /** @hide */
        @SystemApi
        public static final int VOLUME_HUSH_MUTE = 2;

        /**
         * The number of times (integer) the user has manually enabled battery saver.
         * @hide
         */
        @Readable
        public static final String LOW_POWER_MANUAL_ACTIVATION_COUNT =
                "low_power_manual_activation_count";

        /**
         * Whether the "first time battery saver warning" dialog needs to be shown (0: default)
         * or not (1).
         *
         * @hide
         */
        @Readable
        public static final String LOW_POWER_WARNING_ACKNOWLEDGED =
                "low_power_warning_acknowledged";

        /**
         * 0 (default) Auto battery saver suggestion has not been suppressed. 1) it has been
         * suppressed.
         * @hide
         */
        @Readable
        public static final String SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION =
                "suppress_auto_battery_saver_suggestion";

        /**
         * List of packages, which data need to be unconditionally cleared before full restore.
         * Type: string
         * @hide
         */
        @Readable
        public static final String PACKAGES_TO_CLEAR_DATA_BEFORE_FULL_RESTORE =
                "packages_to_clear_data_before_full_restore";

        /**
         * How often to check for location access.
         * @hide
         */
        @SystemApi
        @Readable
        public static final String LOCATION_ACCESS_CHECK_INTERVAL_MILLIS =
                "location_access_check_interval_millis";

        /**
         * Delay between granting location access and checking it.
         * @hide
         */
        @SystemApi
        @Readable
        public static final String LOCATION_ACCESS_CHECK_DELAY_MILLIS =
                "location_access_check_delay_millis";

        /**
         * @deprecated This setting does not have any effect anymore
         * @hide
         */
        @SystemApi
        @Deprecated
        @Readable
        public static final String LOCATION_PERMISSIONS_UPGRADE_TO_Q_MODE =
                "location_permissions_upgrade_to_q_mode";

        /**
         * Whether or not the system Auto Revoke feature is disabled.
         * @hide
         */
        @SystemApi
        @Readable
        public static final String AUTO_REVOKE_DISABLED = "auto_revoke_disabled";

        /**
         * Map of android.theme.customization.* categories to the enabled overlay package for that
         * category, formatted as a serialized {@link org.json.JSONObject}. If there is no
         * corresponding package included for a category, then all overlay packages in that
         * category must be disabled.
         *
         * A few category keys have special meaning and are used for Material You theming.
         *
         * A {@code FabricatedOverlay} containing Material You tonal palettes will be generated
         * in case {@code android.theme.customization.system_palette} contains a
         * {@link android.annotation.ColorInt}.
         *
         * The strategy used for generating the tonal palettes can be defined with the
         * {@code android.theme.customization.theme_style} key, with one of the following options:
         * <ul>
         *   <li> {@code TONAL_SPOT} is a mid vibrancy palette that uses an accent 3 analogous to
         *   accent 1.</li>
         *   <li> {@code VIBRANT} is a high vibrancy palette that harmoniously blends subtle shifts
         *   between colors.</li>
         *   <li> {@code EXPRESSIVE} is a high vibrancy palette that pairs unexpected and unique
         *   accents colors together.</li>
         *   <li> {@code SPRITZ} is a low vibrancy palette that creates a soft wash between
         *   colors.</li>
         *   <li> {@code RAINBOW} uses both chromatic accents and neutral surfaces to create a more
         *   subtle color experience for users.</li>
         *   <li> {@code FRUIT_SALAD} experiments with the concept of "two tone colors" to give
         *   users more expression.</li>
         * </ul>
         *
         * Example of valid fabricated theme specification:
         * <pre>
         * {
         *     "android.theme.customization.system_palette":"B1611C",
         *     "android.theme.customization.theme_style":"EXPRESSIVE"
         * }
         * </pre>
         * @hide
         */
        @SystemApi
        @Readable
        public static final String THEME_CUSTOMIZATION_OVERLAY_PACKAGES =
                "theme_customization_overlay_packages";

        /**
         * Indicates whether the nav bar is forced to always be visible, even in immersive mode.
         * <p>Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        public static final String NAV_BAR_FORCE_VISIBLE = "nav_bar_force_visible";

        /**
         * Indicates whether the device is in kids nav mode.
         * <p>Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        public static final String NAV_BAR_KIDS_MODE = "nav_bar_kids_mode";

        /**
         * Navigation bar mode.
         *  0 = 3 button
         *  1 = 2 button
         *  2 = fully gestural
         * @hide
         */
        @Readable
        public static final String NAVIGATION_MODE =
                "navigation_mode";

        /**
         * Scale factor for the back gesture inset size on the left side of the screen.
         * @hide
         */
        @Readable
        public static final String BACK_GESTURE_INSET_SCALE_LEFT =
                "back_gesture_inset_scale_left";

        /**
         * Scale factor for the back gesture inset size on the right side of the screen.
         * @hide
         */
        @Readable
        public static final String BACK_GESTURE_INSET_SCALE_RIGHT =
                "back_gesture_inset_scale_right";

        /**
         * Current provider of proximity-based sharing services.
         * Default value in @string/config_defaultNearbySharingComponent.
         * No VALIDATOR as this setting will not be backed up.
         * @hide
         */
        @Readable
        public static final String NEARBY_SHARING_COMPONENT = "nearby_sharing_component";

        /**
         * Nearby Sharing Slice URI for the SliceProvider to
         * read Nearby Sharing scan results and then draw the UI.
         * @hide
         */
        public static final String NEARBY_SHARING_SLICE_URI = "nearby_sharing_slice_uri";

        /**
         * Current provider of Fast Pair saved devices page.
         * Default value in @string/config_defaultNearbyFastPairSettingsDevicesComponent.
         * No VALIDATOR as this setting will not be backed up.
         * @hide
         */
        public static final String NEARBY_FAST_PAIR_SETTINGS_DEVICES_COMPONENT =
                "nearby_fast_pair_settings_devices_component";

        /**
         * Current provider of the component for requesting ambient context consent.
         * Default value in @string/config_defaultAmbientContextConsentComponent.
         * No VALIDATOR as this setting will not be backed up.
         * @hide
         */
        public static final String AMBIENT_CONTEXT_CONSENT_COMPONENT =
                "ambient_context_consent_component";

        /**
         * Current provider of the intent extra key for the caller's package name while
         * requesting ambient context consent.
         * No VALIDATOR as this setting will not be backed up.
         * @hide
         */
        public static final String AMBIENT_CONTEXT_PACKAGE_NAME_EXTRA_KEY =
                "ambient_context_package_name_key";

        /**
         * Current provider of the intent extra key for the event code int array while
         * requesting ambient context consent.
         * Default value in @string/config_ambientContextEventArrayExtraKey.
         * No VALIDATOR as this setting will not be backed up.
         * @hide
         */
        public static final String AMBIENT_CONTEXT_EVENT_ARRAY_EXTRA_KEY =
                "ambient_context_event_array_key";

        /**
         * Controls whether aware is enabled.
         * @hide
         */
        @Readable
        public static final String AWARE_ENABLED = "aware_enabled";

        /**
         * Controls whether aware_lock is enabled.
         * @hide
         */
        @Readable
        public static final String AWARE_LOCK_ENABLED = "aware_lock_enabled";

        /**
         * Controls whether tap gesture is enabled.
         * @hide
         */
        @Readable
        public static final String TAP_GESTURE = "tap_gesture";

        /**
         * Controls whether the people strip is enabled.
         * @hide
         */
        @Readable
        public static final String PEOPLE_STRIP = "people_strip";

        /**
         * Whether or not to enable media resumption
         * When enabled, media controls in quick settings will populate on boot and persist if
         * resumable via a MediaBrowserService.
         * @see Settings.Global#SHOW_MEDIA_ON_QUICK_SETTINGS
         * @hide
         */
        @Readable
        public static final String MEDIA_CONTROLS_RESUME = "qs_media_resumption";

        /**
         * Controls whether contextual suggestions can be shown in the media controls.
         * @hide
         */
        public static final String MEDIA_CONTROLS_RECOMMENDATION = "qs_media_recommend";

        /**
         * Controls magnification mode when magnification is enabled via a system-wide triple tap
         * gesture or the accessibility shortcut.
         *
         * @see #ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
         * @see #ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
         * @hide
         */
        @TestApi
        @Readable
        public static final String ACCESSIBILITY_MAGNIFICATION_MODE =
                "accessibility_magnification_mode";

        /**
         * Magnification mode value that is a default value for the magnification logging feature.
         * @hide
         */
        public static final int ACCESSIBILITY_MAGNIFICATION_MODE_NONE = 0x0;

        /**
         * Magnification mode value that magnifies whole display.
         * @hide
         */
        @TestApi
        public static final int ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN = 0x1;

        /**
         * Magnification mode value that magnifies magnify particular region in a window
         * @hide
         */
        @TestApi
        public static final int ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW = 0x2;

        /**
         * Magnification mode value that is capable of magnifying whole display and particular
         * region in a window.
         * @hide
         */
        @TestApi
        public static final int ACCESSIBILITY_MAGNIFICATION_MODE_ALL = 0x3;

        /**
         * Whether the following typing focus feature for magnification is enabled.
         * @hide
         */
        public static final String ACCESSIBILITY_MAGNIFICATION_FOLLOW_TYPING_ENABLED =
                "accessibility_magnification_follow_typing_enabled";

        /**
         * Controls magnification capability. Accessibility magnification is capable of at least one
         * of the magnification modes.
         *
         * @see #ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
         * @see #ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
         * @see #ACCESSIBILITY_MAGNIFICATION_MODE_ALL
         * @hide
         */
        @TestApi
        @Readable
        public static final String ACCESSIBILITY_MAGNIFICATION_CAPABILITY =
                "accessibility_magnification_capability";

        /**
         *  Whether to show the window magnification prompt dialog when the user uses full-screen
         *  magnification first time after database is upgraded.
         *
         * @hide
         */
        public static final String ACCESSIBILITY_SHOW_WINDOW_MAGNIFICATION_PROMPT =
                "accessibility_show_window_magnification_prompt";

        /**
         * Controls the accessibility button mode. System will force-set the value to {@link
         * #ACCESSIBILITY_BUTTON_MODE_GESTURE} if {@link #NAVIGATION_MODE} is button; force-set the
         * value to {@link ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR} if {@link #NAVIGATION_MODE} is
         * gestural; otherwise, remain the option.
         * <ul>
         *    <li> 0 = button in navigation bar </li>
         *    <li> 1 = button floating on the display </li>
         *    <li> 2 = button using gesture to trigger </li>
         * </ul>
         *
         * @see #ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR
         * @see #ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU
         * @see #ACCESSIBILITY_BUTTON_MODE_GESTURE
         * @hide
         */
        public static final String ACCESSIBILITY_BUTTON_MODE =
                "accessibility_button_mode";

        /**
         * Accessibility button mode value that specifying the accessibility service or feature to
         * be toggled via the button in the navigation bar.
         *
         * @hide
         */
        public static final int ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR = 0x0;

        /**
         * Accessibility button mode value that specifying the accessibility service or feature to
         * be toggled via the button floating on the display.
         *
         * @hide
         */
        public static final int ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU = 0x1;

        /**
         * Accessibility button mode value that specifying the accessibility service or feature to
         * be toggled via the gesture.
         *
         * @hide
         */
        public static final int ACCESSIBILITY_BUTTON_MODE_GESTURE = 0x2;

        /**
         * The size of the accessibility floating menu.
         * <ul>
         *     <li> 0 = small size
         *     <li> 1 = large size
         * </ul>
         *
         * @hide
         */
        public static final String ACCESSIBILITY_FLOATING_MENU_SIZE =
                "accessibility_floating_menu_size";

        /**
         * The icon type of the accessibility floating menu.
         * <ul>
         *     <li> 0 = full circle type
         *     <li> 1 = half circle type
         * </ul>
         *
         * @hide
         */
        public static final String ACCESSIBILITY_FLOATING_MENU_ICON_TYPE =
                "accessibility_floating_menu_icon_type";

        /**
         * Whether the fade effect for the accessibility floating menu is enabled.
         *
         * @hide
         */
        public static final String ACCESSIBILITY_FLOATING_MENU_FADE_ENABLED =
                "accessibility_floating_menu_fade_enabled";

        /**
         * The opacity value for the accessibility floating menu fade out effect, from 0.0
         * (transparent) to 1.0 (opaque).
         *
         * @hide
         */
        public static final String ACCESSIBILITY_FLOATING_MENU_OPACITY =
                "accessibility_floating_menu_opacity";

        /**
         * Prompts the user to the Accessibility button is replaced with the floating menu.
         * <ul>
         *    <li> 0 = disabled </li>
         *    <li> 1 = enabled </li>
         * </ul>
         *
         * @hide
         */
        public static final String ACCESSIBILITY_FLOATING_MENU_MIGRATION_TOOLTIP_PROMPT =
                "accessibility_floating_menu_migration_tooltip_prompt";

        /**
         * Whether the Adaptive connectivity option is enabled.
         *
         * @hide
         */
        public static final String ADAPTIVE_CONNECTIVITY_ENABLED = "adaptive_connectivity_enabled";

        /**
         * Keys we no longer back up under the current schema, but want to continue to
         * process when restoring historical backup datasets.
         *
         * All settings in {@link LEGACY_RESTORE_SETTINGS} array *must* have a non-null validator,
         * otherwise they won't be restored.
         *
         * @hide
         */
        @Readable
        public static final String[] LEGACY_RESTORE_SETTINGS = {
                ENABLED_NOTIFICATION_LISTENERS,
                ENABLED_NOTIFICATION_ASSISTANT,
                ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES
        };

        /**
         * How long Assistant handles have enabled in milliseconds.
         *
         * @hide
         */
        public static final String ASSIST_HANDLES_LEARNING_TIME_ELAPSED_MILLIS =
                "reminder_exp_learning_time_elapsed";

        /**
         * How many times the Assistant has been triggered using the touch gesture.
         *
         * @hide
         */
        public static final String ASSIST_HANDLES_LEARNING_EVENT_COUNT =
                "reminder_exp_learning_event_count";

        /**
         * Whether to show clipboard access notifications.
         *
         * @hide
         */
        public static final String CLIPBOARD_SHOW_ACCESS_NOTIFICATIONS =
                "clipboard_show_access_notifications";

        /**
         * If nonzero, nas has not been updated to reflect new changes.
         * @hide
         */
        @Readable
        public static final String NAS_SETTINGS_UPDATED = "nas_settings_updated";

        /**
         * Control whether Game Dashboard shortcut is always on for all games.
         * @hide
         */
        @Readable
        public static final String GAME_DASHBOARD_ALWAYS_ON = "game_dashboard_always_on";


        /**
         * For this device state, no specific auto-rotation lock setting should be applied.
         * If the user toggles the auto-rotate lock in this state, the setting will apply to the
         * previously valid device state.
         * @hide
         */
        public static final int DEVICE_STATE_ROTATION_LOCK_IGNORED = 0;
        /**
         * For this device state, the setting for auto-rotation is locked.
         * @hide
         */
        public static final int DEVICE_STATE_ROTATION_LOCK_LOCKED = 1;
        /**
         * For this device state, the setting for auto-rotation is unlocked.
         * @hide
         */
        public static final int DEVICE_STATE_ROTATION_LOCK_UNLOCKED = 2;

        /**
         * The different settings that can be used as values with
         * {@link #DEVICE_STATE_ROTATION_LOCK}.
         * @hide
         */
        @IntDef(prefix = {"DEVICE_STATE_ROTATION_LOCK_"}, value = {
                DEVICE_STATE_ROTATION_LOCK_IGNORED,
                DEVICE_STATE_ROTATION_LOCK_LOCKED,
                DEVICE_STATE_ROTATION_LOCK_UNLOCKED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface DeviceStateRotationLockSetting {
        }

        /**
         * Rotation lock setting keyed on device state.
         *
         * This holds a serialized map using int keys that represent Device States and value of
         * {@link DeviceStateRotationLockSetting} representing the rotation lock setting for that
         * device state.
         *
         * Serialized as key0:value0:key1:value1:...:keyN:valueN.
         *
         * Example: "0:1:1:2:2:1"
         * This example represents a map of:
         * <ul>
         *     <li>0 -> DEVICE_STATE_ROTATION_LOCK_LOCKED</li>
         *     <li>1 -> DEVICE_STATE_ROTATION_LOCK_UNLOCKED</li>
         *     <li>2 -> DEVICE_STATE_ROTATION_LOCK_IGNORED</li>
         * </ul>
         *
         * @hide
         */
        public static final String DEVICE_STATE_ROTATION_LOCK =
                "device_state_rotation_lock";

        /**
         * Control whether communal mode is allowed on this device.
         *
         * @hide
         */
        public static final String COMMUNAL_MODE_ENABLED = "communal_mode_enabled";

        /**
         * An array of SSIDs of Wi-Fi networks that, when connected, are considered safe to enable
         * the communal mode.
         *
         * @hide
         */
        public static final String COMMUNAL_MODE_TRUSTED_NETWORKS =
                "communal_mode_trusted_networks";

        /**
         * Setting to store denylisted system languages by the CEC {@code <Set Menu Language>}
         * confirmation dialog.
         *
         * @hide
         */
        public static final String HDMI_CEC_SET_MENU_LANGUAGE_DENYLIST =
                "hdmi_cec_set_menu_language_denylist";

        /**
         * Whether the Taskbar Education is about to be shown or is currently showing.
         *
         * <p>1 if true, 0 or unset otherwise.
         *
         * <p>This setting is used to inform other components that the Taskbar Education is
         * currently showing, which can prevent them from showing something else to the user.
         *
         * @hide
         */
        public static final String LAUNCHER_TASKBAR_EDUCATION_SHOWING =
                "launcher_taskbar_education_showing";

        /**
         * These entries are considered common between the personal and the managed profile,
         * since the managed profile doesn't get to change them.
         */
        private static final Set<String> CLONE_TO_MANAGED_PROFILE = new ArraySet<>();

        static {
            CLONE_TO_MANAGED_PROFILE.add(ACCESSIBILITY_ENABLED);
            CLONE_TO_MANAGED_PROFILE.add(ALLOW_MOCK_LOCATION);
            CLONE_TO_MANAGED_PROFILE.add(ALLOWED_GEOLOCATION_ORIGINS);
            CLONE_TO_MANAGED_PROFILE.add(CONTENT_CAPTURE_ENABLED);
            CLONE_TO_MANAGED_PROFILE.add(ENABLED_ACCESSIBILITY_SERVICES);
            CLONE_TO_MANAGED_PROFILE.add(LOCATION_CHANGER);
            CLONE_TO_MANAGED_PROFILE.add(LOCATION_MODE);
            CLONE_TO_MANAGED_PROFILE.add(SHOW_IME_WITH_HARD_KEYBOARD);
            CLONE_TO_MANAGED_PROFILE.add(NOTIFICATION_BUBBLES);
        }

        /** @hide */
        public static void getCloneToManagedProfileSettings(Set<String> outKeySet) {
            outKeySet.addAll(CLONE_TO_MANAGED_PROFILE);
        }

        /**
         * Secure settings which can be accessed by instant apps.
         * @hide
         */
        public static final Set<String> INSTANT_APP_SETTINGS = new ArraySet<>();
        static {
            INSTANT_APP_SETTINGS.add(ENABLED_ACCESSIBILITY_SERVICES);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_SPEAK_PASSWORD);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_CAPTIONING_ENABLED);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_CAPTIONING_PRESET);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_CAPTIONING_EDGE_TYPE);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_CAPTIONING_EDGE_COLOR);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_CAPTIONING_LOCALE);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_CAPTIONING_BACKGROUND_COLOR);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_CAPTIONING_FOREGROUND_COLOR);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_CAPTIONING_TYPEFACE);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_CAPTIONING_FONT_SCALE);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_CAPTIONING_WINDOW_COLOR);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_DISPLAY_DALTONIZER);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_AUTOCLICK_DELAY);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_AUTOCLICK_ENABLED);
            INSTANT_APP_SETTINGS.add(ACCESSIBILITY_LARGE_POINTER_ICON);

            INSTANT_APP_SETTINGS.add(DEFAULT_INPUT_METHOD);
            INSTANT_APP_SETTINGS.add(ENABLED_INPUT_METHODS);

            INSTANT_APP_SETTINGS.add(ANDROID_ID);

            INSTANT_APP_SETTINGS.add(ALLOW_MOCK_LOCATION);
        }

        /**
         * Helper method for determining if a location provider is enabled.
         *
         * @param cr the content resolver to use
         * @param provider the location provider to query
         * @return true if the provider is enabled
         *
         * @deprecated use {@link LocationManager#isProviderEnabled(String)}
         */
        @Deprecated
        public static boolean isLocationProviderEnabled(ContentResolver cr, String provider) {
            IBinder binder = ServiceManager.getService(Context.LOCATION_SERVICE);
            ILocationManager lm = Objects.requireNonNull(ILocationManager.Stub.asInterface(binder));
            try {
                return lm.isProviderEnabledForUser(provider, cr.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Thread-safe method for enabling or disabling a single location provider. This will have
         * no effect on Android Q and above.
         * @param cr the content resolver to use
         * @param provider the location provider to enable or disable
         * @param enabled true if the provider should be enabled
         * @deprecated This API is deprecated
         */
        @Deprecated
        public static void setLocationProviderEnabled(ContentResolver cr,
                String provider, boolean enabled) {
        }
    }

    /**
     * Global system settings, containing preferences that always apply identically
     * to all defined users.  Applications can read these but are not allowed to write;
     * like the "Secure" settings, these are for preferences that the user must
     * explicitly modify through the system UI or specialized APIs for those values.
     */
    public static final class Global extends NameValueTable {
        // NOTE: If you add new settings here, be sure to add them to
        // com.android.providers.settings.SettingsProtoDumpUtil#dumpProtoGlobalSettingsLocked.

        /**
         * The content:// style URL for global secure settings items.  Not public.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/global");

        /**
         * Whether the notification bubbles are globally enabled
         * The value is boolean (1 or 0).
         * @hide
         * @deprecated moved to secure settings.
         */
        @Deprecated
        @TestApi
        @Readable
        public static final String NOTIFICATION_BUBBLES = "notification_bubbles";

        /**
         * Whether users are allowed to add more users or guest from lockscreen.
         * <p>
         * Type: int
         * @hide
         */
        @Readable
        public static final String ADD_USERS_WHEN_LOCKED = "add_users_when_locked";

        /**
         * Whether applying ramping ringer on incoming phone call ringtone.
         * <p>1 = apply ramping ringer
         * <p>0 = do not apply ramping ringer
         * @deprecated Use {@link AudioManager#isRampingRingerEnabled()} instead
         */
        @Deprecated
        @Readable
        public static final String APPLY_RAMPING_RINGER = "apply_ramping_ringer";

        /**
         * Setting whether the global gesture for enabling accessibility is enabled.
         * If this gesture is enabled the user will be able to perfrom it to enable
         * the accessibility state without visiting the settings app.
         *
         * @hide
         * No longer used. Should be removed once all dependencies have been updated.
         */
        @UnsupportedAppUsage
        @Readable
        public static final String ENABLE_ACCESSIBILITY_GLOBAL_GESTURE_ENABLED =
                "enable_accessibility_global_gesture_enabled";

        /**
         * Whether Airplane Mode is on.
         */
        @Readable
        public static final String AIRPLANE_MODE_ON = "airplane_mode_on";

        /**
         * Whether Theater Mode is on.
         * {@hide}
         */
        @SystemApi
        @Readable
        public static final String THEATER_MODE_ON = "theater_mode_on";

        /**
         * Constant for use in AIRPLANE_MODE_RADIOS to specify Bluetooth radio.
         */
        @Readable
        public static final String RADIO_BLUETOOTH = "bluetooth";

        /**
         * Constant for use in AIRPLANE_MODE_RADIOS to specify Wi-Fi radio.
         */
        @Readable
        public static final String RADIO_WIFI = "wifi";

        /**
         * {@hide}
         */
        @Readable
        public static final String RADIO_WIMAX = "wimax";
        /**
         * Constant for use in AIRPLANE_MODE_RADIOS to specify Cellular radio.
         */
        @Readable
        public static final String RADIO_CELL = "cell";

        /**
         * Constant for use in AIRPLANE_MODE_RADIOS to specify NFC radio.
         */
        @Readable
        public static final String RADIO_NFC = "nfc";

        /**
         * A comma separated list of radios that need to be disabled when airplane mode
         * is on. This overrides WIFI_ON and BLUETOOTH_ON, if Wi-Fi and bluetooth are
         * included in the comma separated list.
         */
        @Readable
        public static final String AIRPLANE_MODE_RADIOS = "airplane_mode_radios";

        /**
         * A comma separated list of radios that should to be disabled when airplane mode
         * is on, but can be manually reenabled by the user.  For example, if RADIO_WIFI is
         * added to both AIRPLANE_MODE_RADIOS and AIRPLANE_MODE_TOGGLEABLE_RADIOS, then Wifi
         * will be turned off when entering airplane mode, but the user will be able to reenable
         * Wifi in the Settings app.
         * @hide
         */
        @SystemApi
        @Readable
        public static final String AIRPLANE_MODE_TOGGLEABLE_RADIOS = "airplane_mode_toggleable_radios";

        /**
         * An integer representing the Bluetooth Class of Device (CoD).
         *
         * @hide
         */
        @Readable
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLUETOOTH_CLASS_OF_DEVICE = "bluetooth_class_of_device";

        /**
         * A Long representing a bitmap of profiles that should be disabled when bluetooth starts.
         * See {@link android.bluetooth.BluetoothProfile}.
         * {@hide}
         */
        @Readable
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLUETOOTH_DISABLED_PROFILES = "bluetooth_disabled_profiles";

        /**
         * A semi-colon separated list of Bluetooth interoperability workarounds.
         * Each entry is a partial Bluetooth device address string and an integer representing
         * the feature to be disabled, separated by a comma. The integer must correspond
         * to a interoperability feature as defined in "interop.h" in /system/bt.
         * <p>
         * Example: <br/>
         *   "00:11:22,0;01:02:03:04,2"
         * @hide
         */
        @Readable
       public static final String BLUETOOTH_INTEROPERABILITY_LIST = "bluetooth_interoperability_list";

        /**
         * The policy for deciding when Wi-Fi should go to sleep (which will in
         * turn switch to using the mobile data as an Internet connection).
         * <p>
         * Set to one of {@link #WIFI_SLEEP_POLICY_DEFAULT},
         * {@link #WIFI_SLEEP_POLICY_NEVER_WHILE_PLUGGED}, or
         * {@link #WIFI_SLEEP_POLICY_NEVER}.
         * @deprecated This is no longer used or set by the platform.
         */
        @Deprecated
        @Readable
        public static final String WIFI_SLEEP_POLICY = "wifi_sleep_policy";

        /**
         * Value for {@link #WIFI_SLEEP_POLICY} to use the default Wi-Fi sleep
         * policy, which is to sleep shortly after the turning off
         * according to the {@link #STAY_ON_WHILE_PLUGGED_IN} setting.
         * @deprecated This is no longer used by the platform.
         */
        @Deprecated
        public static final int WIFI_SLEEP_POLICY_DEFAULT = 0;

        /**
         * Value for {@link #WIFI_SLEEP_POLICY} to use the default policy when
         * the device is on battery, and never go to sleep when the device is
         * plugged in.
         * @deprecated This is no longer used by the platform.
         */
        @Deprecated
        public static final int WIFI_SLEEP_POLICY_NEVER_WHILE_PLUGGED = 1;

        /**
         * Value for {@link #WIFI_SLEEP_POLICY} to never go to sleep.
         * @deprecated This is no longer used by the platform.
         */
        @Deprecated
        public static final int WIFI_SLEEP_POLICY_NEVER = 2;

        /**
         * Value to specify if the device's UTC system clock should be set automatically, e.g. using
         * telephony signals like NITZ, or other sources like GNSS or NTP. 1=yes, 0=no (manual)
         */
        @Readable
        public static final String AUTO_TIME = "auto_time";

        /**
         * Value to specify if the device's time zone system property should be set automatically,
         * e.g. using telephony signals like MCC and NITZ, or other mechanisms like the location.
         * 1=yes, 0=no (manual).
         */
        @Readable
        public static final String AUTO_TIME_ZONE = "auto_time_zone";

        /**
         * URI for the car dock "in" event sound.
         * @hide
         */
        @Readable
        public static final String CAR_DOCK_SOUND = "car_dock_sound";

        /**
         * URI for the car dock "out" event sound.
         * @hide
         */
        @Readable
        public static final String CAR_UNDOCK_SOUND = "car_undock_sound";

        /**
         * URI for the desk dock "in" event sound.
         * @hide
         */
        @Readable
        public static final String DESK_DOCK_SOUND = "desk_dock_sound";

        /**
         * URI for the desk dock "out" event sound.
         * @hide
         */
        @Readable
        public static final String DESK_UNDOCK_SOUND = "desk_undock_sound";

        /**
         * Whether to play a sound for dock events.
         * @hide
         */
        @Readable
        public static final String DOCK_SOUNDS_ENABLED = "dock_sounds_enabled";

        /**
         * Whether to play a sound for dock events, only when an accessibility service is on.
         * @hide
         */
        @Readable
        public static final String DOCK_SOUNDS_ENABLED_WHEN_ACCESSIBILITY = "dock_sounds_enabled_when_accessbility";

        /**
         * URI for the "device locked" (keyguard shown) sound.
         * @hide
         */
        @Readable
        public static final String LOCK_SOUND = "lock_sound";

        /**
         * URI for the "device unlocked" sound.
         * @hide
         */
        @Readable
        public static final String UNLOCK_SOUND = "unlock_sound";

        /**
         * URI for the "device is trusted" sound, which is played when the device enters the trusted
         * state without unlocking.
         * @hide
         */
        @Readable
        public static final String TRUSTED_SOUND = "trusted_sound";

        /**
         * URI for the low battery sound file.
         * @hide
         */
        @Readable
        public static final String LOW_BATTERY_SOUND = "low_battery_sound";

        /**
         * Whether to play a sound for low-battery alerts.
         * @hide
         */
        @Readable
        public static final String POWER_SOUNDS_ENABLED = "power_sounds_enabled";

        /**
         * URI for the "wireless charging started" sound.
         * @hide
         */
        @Readable
        public static final String WIRELESS_CHARGING_STARTED_SOUND =
                "wireless_charging_started_sound";

        /**
         * URI for "wired charging started" sound.
         * @hide
         */
        @Readable
        public static final String CHARGING_STARTED_SOUND = "charging_started_sound";

        /**
         * Whether to play a sound for charging events.
         * @deprecated Use {@link android.provider.Settings.Secure#CHARGING_SOUNDS_ENABLED} instead
         * @hide
         */
        @Deprecated
        public static final String CHARGING_SOUNDS_ENABLED = "charging_sounds_enabled";

        /**
         * Whether to vibrate for wireless charging events.
         * @deprecated Use {@link android.provider.Settings.Secure#CHARGING_VIBRATION_ENABLED}
         * @hide
         */
        @Deprecated
        public static final String CHARGING_VIBRATION_ENABLED = "charging_vibration_enabled";

        /**
         * Whether we keep the device on while the device is plugged in.
         * Supported values are:
         * <ul>
         * <li>{@code 0} to never stay on while plugged in</li>
         * <li>{@link BatteryManager#BATTERY_PLUGGED_AC} to stay on for AC charger</li>
         * <li>{@link BatteryManager#BATTERY_PLUGGED_USB} to stay on for USB charger</li>
         * <li>{@link BatteryManager#BATTERY_PLUGGED_WIRELESS} to stay on for wireless charger</li>
         * </ul>
         * These values can be OR-ed together.
         */
        @Readable
        public static final String STAY_ON_WHILE_PLUGGED_IN = "stay_on_while_plugged_in";

        /**
         * When the user has enable the option to have a "bug report" command
         * in the power menu.
         * @hide
         */
        @Readable
        public static final String BUGREPORT_IN_POWER_MENU = "bugreport_in_power_menu";

        /**
         * The package name for the custom bugreport handler app. This app must be whitelisted.
         * This is currently used only by Power Menu short press.
         *
         * @hide
         */
        @Readable
        public static final String CUSTOM_BUGREPORT_HANDLER_APP = "custom_bugreport_handler_app";

        /**
         * The user id for the custom bugreport handler app. This is currently used only by Power
         * Menu short press.
         *
         * @hide
         */
        @Readable
        public static final String CUSTOM_BUGREPORT_HANDLER_USER = "custom_bugreport_handler_user";

        /**
         * Whether ADB over USB is enabled.
         */
        @Readable
        public static final String ADB_ENABLED = "adb_enabled";

        /**
         * Whether ADB over Wifi is enabled.
         * @hide
         */
        @Readable
        public static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";

        /**
         * Whether Views are allowed to save their attribute data.
         * @hide
         */
        @Readable
        public static final String DEBUG_VIEW_ATTRIBUTES = "debug_view_attributes";

        /**
         * Which application package is allowed to save View attribute data.
         * @hide
         */
        @Readable
        public static final String DEBUG_VIEW_ATTRIBUTES_APPLICATION_PACKAGE =
                "debug_view_attributes_application_package";

        /**
         * Whether assisted GPS should be enabled or not.
         * @hide
         */
        @Readable
        public static final String ASSISTED_GPS_ENABLED = "assisted_gps_enabled";

        /**
         * Whether bluetooth is enabled/disabled
         * 0=disabled. 1=enabled.
         */
        @Readable
        public static final String BLUETOOTH_ON = "bluetooth_on";

        /**
         * CDMA Cell Broadcast SMS
         *                            0 = CDMA Cell Broadcast SMS disabled
         *                            1 = CDMA Cell Broadcast SMS enabled
         * @hide
         */
        @Readable
        public static final String CDMA_CELL_BROADCAST_SMS =
                "cdma_cell_broadcast_sms";

        /**
         * The CDMA roaming mode 0 = Home Networks, CDMA default
         *                       1 = Roaming on Affiliated networks
         *                       2 = Roaming on any networks
         * @hide
         */
        @Readable
        public static final String CDMA_ROAMING_MODE = "roaming_settings";

        /**
         * The CDMA subscription mode 0 = RUIM/SIM (default)
         *                                1 = NV
         * @hide
         */
        @Readable
        public static final String CDMA_SUBSCRIPTION_MODE = "subscription_mode";

        /**
         * The default value for whether background data is enabled or not.
         *
         * Used by {@code NetworkPolicyManagerService}.
         *
         * @hide
         */
        @Readable
        public static final String DEFAULT_RESTRICT_BACKGROUND_DATA =
                "default_restrict_background_data";

        /** Inactivity timeout to track mobile data activity.
         *
         * If set to a positive integer, it indicates the inactivity timeout value in seconds to
         * infer the data activity of mobile network. After a period of no activity on mobile
         * networks with length specified by the timeout, an {@code ACTION_DATA_ACTIVITY_CHANGE}
         * intent is fired to indicate a transition of network status from "active" to "idle". Any
         * subsequent activity on mobile networks triggers the firing of {@code
         * ACTION_DATA_ACTIVITY_CHANGE} intent indicating transition from "idle" to "active".
         *
         * Network activity refers to transmitting or receiving data on the network interfaces.
         *
         * Tracking is disabled if set to zero or negative value.
         *
         * @hide
         */
        @Readable
        public static final String DATA_ACTIVITY_TIMEOUT_MOBILE = "data_activity_timeout_mobile";

        /** Timeout to tracking Wifi data activity. Same as {@code DATA_ACTIVITY_TIMEOUT_MOBILE}
         * but for Wifi network.
         * @hide
         */
        @Readable
        public static final String DATA_ACTIVITY_TIMEOUT_WIFI = "data_activity_timeout_wifi";

        /**
         * Whether or not data roaming is enabled. (0 = false, 1 = true)
         * Use {@link TelephonyManager#isDataRoamingEnabled} instead of calling via settings.
         */
        @Readable(maxTargetSdk = Build.VERSION_CODES.S)
        public static final String DATA_ROAMING = "data_roaming";

        /**
         * The value passed to a Mobile DataConnection via bringUp which defines the
         * number of retries to perform when setting up the initial connection. The default
         * value defined in DataConnectionTrackerBase#DEFAULT_MDC_INITIAL_RETRY is currently 1.
         * @hide
         */
        @Readable
        public static final String MDC_INITIAL_MAX_RETRY = "mdc_initial_max_retry";

        /**
         * Whether any package can be on external storage. When this is true, any
         * package, regardless of manifest values, is a candidate for installing
         * or moving onto external storage. (0 = false, 1 = true)
         * @hide
         */
        @Readable
        public static final String FORCE_ALLOW_ON_EXTERNAL = "force_allow_on_external";

        /**
         * The default SM-DP+ configured for this device.
         *
         * <p>An SM-DP+ is used by an LPA (see {@link android.service.euicc.EuiccService}) to
         * download profiles. If this value is set, the LPA will query this server for any profiles
         * available to this device. If any are available, they may be downloaded during device
         * provisioning or in settings without needing the user to enter an activation code.
         *
         * @see android.service.euicc.EuiccService
         * @hide
         */
        @SystemApi
        @Readable
        public static final String DEFAULT_SM_DP_PLUS = "default_sm_dp_plus";

        /**
         * Whether any profile has ever been downloaded onto a eUICC on the device.
         *
         * <p>Used to hide eUICC UI from users who have never made use of it and would only be
         * confused by seeing references to it in settings.
         * (0 = false, 1 = true)
         * @hide
         */
        @SystemApi
        @Readable
        public static final String EUICC_PROVISIONED = "euicc_provisioned";

        /**
         * List of ISO country codes in which eUICC UI is shown. Country codes should be separated
         * by comma.
         *
         * Note: if {@link #EUICC_SUPPORTED_COUNTRIES} is empty, then {@link
         * #EUICC_UNSUPPORTED_COUNTRIES} is used.
         *
         * <p>Used to hide eUICC UI from users who are currently in countries where no carriers
         * support eUICC.
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String EUICC_SUPPORTED_COUNTRIES = "euicc_supported_countries";

        /**
         * List of ISO country codes in which eUICC UI is not shown. Country codes should be
         * separated by comma.
         *
         * Note: if {@link #EUICC_SUPPORTED_COUNTRIES} is empty, then {@link
         * #EUICC_UNSUPPORTED_COUNTRIES} is used.
         *
         * <p>Used to hide eUICC UI from users who are currently in countries where no carriers
         * support eUICC.
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String EUICC_UNSUPPORTED_COUNTRIES = "euicc_unsupported_countries";

        /**
         * Whether any activity can be resized. When this is true, any
         * activity, regardless of manifest values, can be resized for multi-window.
         * (0 = false, 1 = true)
         * @hide
         */
        @Readable
        public static final String DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES
                = "force_resizable_activities";

        /**
         * Whether to enable experimental freeform support for windows.
         * @hide
         */
        @Readable
        public static final String DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT
                = "enable_freeform_support";

        /**
         * Whether to enable experimental desktop mode on secondary displays.
         * @hide
         */
        @Readable
        public static final String DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS =
                "force_desktop_mode_on_external_displays";

        /**
         * Whether to allow non-resizable apps to be shown in multi-window. The app will be
         * letterboxed if the request orientation is not met, and will be shown in size-compat
         * mode if the container size has changed.
         * @hide
         */
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW =
                "enable_non_resizable_multi_window";

        /**
         * If true, shadows drawn around the window will be rendered by the system compositor. If
         * false, shadows will be drawn by the client by setting an elevation on the root view and
         * the contents will be inset by the surface insets.
         * (0 = false, 1 = true)
         * @hide
         */
        @Readable
        public static final String DEVELOPMENT_RENDER_SHADOWS_IN_COMPOSITOR =
                "render_shadows_in_compositor";

        /**
         * If true, submit buffers using blast in ViewRootImpl.
         * (0 = false, 1 = true)
         * @hide
         */
        @Readable
        public static final String DEVELOPMENT_USE_BLAST_ADAPTER_VR =
                "use_blast_adapter_vr";

        /**
         * Path to the WindowManager display settings file. If unset, the default file path will
         * be used.
         *
         * @hide
         */
        public static final String DEVELOPMENT_WM_DISPLAY_SETTINGS_PATH =
                "wm_display_settings_path";

        /**
        * Whether user has enabled development settings.
        */
        @Readable
        public static final String DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled";

        /**
        * Whether the device has been provisioned (0 = false, 1 = true).
        * <p>On a multiuser device with a separate system user, the screen may be locked
        * as soon as this is set to true and further activities cannot be launched on the
        * system user unless they are marked to show over keyguard.
        */
        @Readable
        public static final String DEVICE_PROVISIONED = "device_provisioned";

        /**
         * Whether bypassing the device policy management role holder qualifcation is allowed,
         * (0 = false, 1 = true).
         *
         * @hide
         */
        public static final String BYPASS_DEVICE_POLICY_MANAGEMENT_ROLE_QUALIFICATIONS =
                "bypass_device_policy_management_role_qualifications";

        /**
         * Indicates whether mobile data should be allowed while the device is being provisioned.
         * This allows the provisioning process to turn off mobile data before the user
         * has an opportunity to set things up, preventing other processes from burning
         * precious bytes before wifi is setup.
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String DEVICE_PROVISIONING_MOBILE_DATA_ENABLED =
                "device_provisioning_mobile_data";

        /**
        * The saved value for WindowManagerService.setForcedDisplaySize().
        * Two integers separated by a comma.  If unset, then use the real display size.
        * @hide
        */
        @Readable
        public static final String DISPLAY_SIZE_FORCED = "display_size_forced";

        /**
        * The saved value for WindowManagerService.setForcedDisplayScalingMode().
        * 0 or unset if scaling is automatic, 1 if scaling is disabled.
        * @hide
        */
        @Readable
        public static final String DISPLAY_SCALING_FORCE = "display_scaling_force";

        /**
        * The maximum size, in bytes, of a download that the download manager will transfer over
        * a non-wifi connection.
        * @hide
        */
        @Readable
        public static final String DOWNLOAD_MAX_BYTES_OVER_MOBILE =
               "download_manager_max_bytes_over_mobile";

        /**
        * The recommended maximum size, in bytes, of a download that the download manager should
        * transfer over a non-wifi connection. Over this size, the use will be warned, but will
        * have the option to start the download over the mobile connection anyway.
        * @hide
        */
        @Readable
        public static final String DOWNLOAD_RECOMMENDED_MAX_BYTES_OVER_MOBILE =
               "download_manager_recommended_max_bytes_over_mobile";

        /**
        * @deprecated Use {@link android.provider.Settings.Secure#INSTALL_NON_MARKET_APPS} instead
        */
        @Deprecated
        public static final String INSTALL_NON_MARKET_APPS = Secure.INSTALL_NON_MARKET_APPS;

        /**
         * Whether or not media is shown automatically when bypassing as a heads up.
         * @hide
         */
        @Readable
        public static final String SHOW_MEDIA_ON_QUICK_SETTINGS =
                "qs_media_controls";

        /**
         * The interval in milliseconds at which location requests will be throttled when they are
         * coming from the background.
         *
         * @hide
         */
        @Readable
        public static final String LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS =
                "location_background_throttle_interval_ms";

        /**
         * Most frequent location update interval in milliseconds that proximity alert is allowed
         * to request.
         * @hide
         */
        @Readable
        public static final String LOCATION_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS =
                "location_background_throttle_proximity_alert_interval_ms";

        /**
         * Packages that are whitelisted for background throttling (throttling will not be applied).
         * @hide
         */
        @Readable
        public static final String LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST =
            "location_background_throttle_package_whitelist";

        /**
         * Packages that are whitelisted for ignoring location settings (may retrieve location even
         * when user location settings are off), for emergency purposes.
         * @deprecated No longer used from Android 12+
         * @hide
         */
        @TestApi
        @Readable
        @Deprecated
        public static final String LOCATION_IGNORE_SETTINGS_PACKAGE_WHITELIST =
                "location_ignore_settings_package_whitelist";

        /**
         * Whether to throttle location when the device is in doze and still.
         * @hide
         */
        public static final String LOCATION_ENABLE_STATIONARY_THROTTLE =
                "location_enable_stationary_throttle";

        /**
        * Whether TV will switch to MHL port when a mobile device is plugged in.
        * (0 = false, 1 = true)
        * @hide
        */
        @Readable
        public static final String MHL_INPUT_SWITCHING_ENABLED = "mhl_input_switching_enabled";

        /**
        * Whether TV will charge the mobile device connected at MHL port. (0 = false, 1 = true)
        * @hide
        */
        @Readable
        public static final String MHL_POWER_CHARGE_ENABLED = "mhl_power_charge_enabled";

        /**
        * Whether mobile data connections are allowed by the user.  See
        * ConnectivityManager for more info.
        * @hide
        */
        @UnsupportedAppUsage
        @Readable
        public static final String MOBILE_DATA = "mobile_data";

        /**
        * Whether the mobile data connection should remain active even when higher
        * priority networks like WiFi are active, to help make network switching faster.
        *
        * See ConnectivityService for more info.
        *
        * (0 = disabled, 1 = enabled)
        * @hide
        */
        @Readable
        public static final String MOBILE_DATA_ALWAYS_ON = "mobile_data_always_on";

        /**
         * Whether the wifi data connection should remain active even when higher
         * priority networks like Ethernet are active, to keep both networks.
         * In the case where higher priority networks are connected, wifi will be
         * unused unless an application explicitly requests to use it.
         *
         * See ConnectivityService for more info.
         *
         * (0 = disabled, 1 = enabled)
         * @hide
         */
        @Readable
        public static final String WIFI_ALWAYS_REQUESTED = "wifi_always_requested";

        /**
         * Size of the event buffer for IP connectivity metrics.
         * @hide
         */
        @Readable
        public static final String CONNECTIVITY_METRICS_BUFFER_SIZE =
              "connectivity_metrics_buffer_size";

        /** {@hide} */
        @Readable
        public static final String NETSTATS_ENABLED = "netstats_enabled";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_POLL_INTERVAL = "netstats_poll_interval";
        /**
         * @deprecated
         * {@hide}
         */
        @Deprecated
        @Readable
        public static final String NETSTATS_TIME_CACHE_MAX_AGE = "netstats_time_cache_max_age";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_GLOBAL_ALERT_BYTES = "netstats_global_alert_bytes";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_SAMPLE_ENABLED = "netstats_sample_enabled";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_AUGMENT_ENABLED = "netstats_augment_enabled";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_COMBINE_SUBTYPE_ENABLED =
                "netstats_combine_subtype_enabled";

        /** {@hide} */
        @Readable
        public static final String NETSTATS_DEV_BUCKET_DURATION = "netstats_dev_bucket_duration";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_DEV_PERSIST_BYTES = "netstats_dev_persist_bytes";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_DEV_ROTATE_AGE = "netstats_dev_rotate_age";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_DEV_DELETE_AGE = "netstats_dev_delete_age";

        /** {@hide} */
        @Readable
        public static final String NETSTATS_UID_BUCKET_DURATION = "netstats_uid_bucket_duration";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_UID_PERSIST_BYTES = "netstats_uid_persist_bytes";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_UID_ROTATE_AGE = "netstats_uid_rotate_age";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_UID_DELETE_AGE = "netstats_uid_delete_age";

        /** {@hide} */
        @Readable
        public static final String NETSTATS_UID_TAG_BUCKET_DURATION =
                "netstats_uid_tag_bucket_duration";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_UID_TAG_PERSIST_BYTES =
                "netstats_uid_tag_persist_bytes";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_UID_TAG_ROTATE_AGE = "netstats_uid_tag_rotate_age";
        /** {@hide} */
        @Readable
        public static final String NETSTATS_UID_TAG_DELETE_AGE = "netstats_uid_tag_delete_age";

        /** {@hide} */
        @Readable
        public static final String NETPOLICY_QUOTA_ENABLED = "netpolicy_quota_enabled";
        /** {@hide} */
        @Readable
        public static final String NETPOLICY_QUOTA_UNLIMITED = "netpolicy_quota_unlimited";
        /** {@hide} */
        @Readable
        public static final String NETPOLICY_QUOTA_LIMITED = "netpolicy_quota_limited";
        /** {@hide} */
        @Readable
        public static final String NETPOLICY_QUOTA_FRAC_JOBS = "netpolicy_quota_frac_jobs";
        /** {@hide} */
        @Readable
        public static final String NETPOLICY_QUOTA_FRAC_MULTIPATH =
                "netpolicy_quota_frac_multipath";

        /** {@hide} */
        @Readable
        public static final String NETPOLICY_OVERRIDE_ENABLED = "netpolicy_override_enabled";

        /**
        * User preference for which network(s) should be used. Only the
        * connectivity service should touch this.
        */
        @Readable
        public static final String NETWORK_PREFERENCE = "network_preference";

        /**
        * Which package name to use for network scoring. If null, or if the package is not a valid
        * scorer app, external network scores will neither be requested nor accepted.
        * @hide
        */
        @Readable
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final String NETWORK_SCORER_APP = "network_scorer_app";

        /**
         * Whether night display forced auto mode is available.
         * 0 = unavailable, 1 = available.
         * @hide
         */
        @Readable
        public static final String NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE =
                "night_display_forced_auto_mode_available";

        /**
         * If Unix epoch time between two NITZ signals is greater than this value then the second
         * signal cannot be ignored.
         *
         * <p>This value is in milliseconds. It is used for telephony-based time and time zone
         * detection.
         * @hide
         */
        @Readable
        public static final String NITZ_UPDATE_DIFF = "nitz_update_diff";

        /**
         * If the elapsed realtime between two NITZ signals is greater than this value then the
         * second signal cannot be ignored.
         *
         * <p>This value is in milliseconds. It is used for telephony-based time and time zone
         * detection.
         * @hide
         */
        @Readable
        public static final String NITZ_UPDATE_SPACING = "nitz_update_spacing";

        /**
         * If the device connects to a telephony network and was disconnected from a telephony
         * network for less than this time, a previously received NITZ signal can be restored.
         *
         * <p>This value is in milliseconds. It is used for telephony-based time and time zone
         * detection.
         * @hide
         */
        public static final String NITZ_NETWORK_DISCONNECT_RETENTION =
                "nitz_network_disconnect_retention";

        /** Preferred NTP server. {@hide} */
        @Readable
        public static final String NTP_SERVER = "ntp_server";
        /** Timeout in milliseconds to wait for NTP server. {@hide} */
        @Readable
        public static final String NTP_TIMEOUT = "ntp_timeout";

        /** {@hide} */
        @Readable
        public static final String STORAGE_BENCHMARK_INTERVAL = "storage_benchmark_interval";

        /**
         * Whether or not Settings should enable psd API.
         * {@hide}
         */
        @Readable
        public static final String SETTINGS_USE_PSD_API = "settings_use_psd_api";

        /**
         * Whether or not Settings should enable external provider API.
         * {@hide}
         */
        @Readable
        public static final String SETTINGS_USE_EXTERNAL_PROVIDER_API =
                "settings_use_external_provider_api";

        /**
        * Sample validity in seconds to configure for the system DNS resolver.
        * {@hide}
        */
        @Readable
        public static final String DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS =
               "dns_resolver_sample_validity_seconds";

        /**
        * Success threshold in percent for use with the system DNS resolver.
        * {@hide}
        */
        @Readable
        public static final String DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT =
                "dns_resolver_success_threshold_percent";

        /**
        * Minimum number of samples needed for statistics to be considered meaningful in the
        * system DNS resolver.
        * {@hide}
        */
        @Readable
        public static final String DNS_RESOLVER_MIN_SAMPLES = "dns_resolver_min_samples";

        /**
        * Maximum number taken into account for statistics purposes in the system DNS resolver.
        * {@hide}
        */
        @Readable
        public static final String DNS_RESOLVER_MAX_SAMPLES = "dns_resolver_max_samples";

        /**
        * Whether to disable the automatic scheduling of system updates.
        * 1 = system updates won't be automatically scheduled (will always
        * present notification instead).
        * 0 = system updates will be automatically scheduled. (default)
        * @hide
        */
        @SystemApi
        @Readable
        public static final String OTA_DISABLE_AUTOMATIC_UPDATE = "ota_disable_automatic_update";

        /** Timeout for package verification.
        * @hide */
        @Readable
        public static final String PACKAGE_VERIFIER_TIMEOUT = "verifier_timeout";

        /** Timeout for package verification during streaming installations.
         * @hide */
        @Readable
        public static final String PACKAGE_STREAMING_VERIFIER_TIMEOUT =
                "streaming_verifier_timeout";

        /** Timeout for app integrity verification.
         * @hide */
        @Readable
        public static final String APP_INTEGRITY_VERIFICATION_TIMEOUT =
                "app_integrity_verification_timeout";

        /** Default response code for package verification.
        * @hide */
        @Readable
        public static final String PACKAGE_VERIFIER_DEFAULT_RESPONSE = "verifier_default_response";

        /**
        * Show package verification setting in the Settings app.
        * 1 = show (default)
        * 0 = hide
        * @hide
        */
        @Readable
        public static final String PACKAGE_VERIFIER_SETTING_VISIBLE = "verifier_setting_visible";

        /**
        * Run package verification on apps installed through ADB/ADT/USB
        * 1 = perform package verification on ADB installs (default)
        * 0 = bypass package verification on ADB installs
        * @hide
        */
        @Readable
        public static final String PACKAGE_VERIFIER_INCLUDE_ADB = "verifier_verify_adb_installs";

        /**
         * Run integrity checks for integrity rule providers.
         * 0 = bypass integrity verification on installs from rule providers (default)
         * 1 = perform integrity verification on installs from rule providers
         * @hide
         */
        @Readable
        public static final String INTEGRITY_CHECK_INCLUDES_RULE_PROVIDER =
                "verify_integrity_for_rule_provider";

        /**
        * Time since last fstrim (milliseconds) after which we force one to happen
        * during device startup.  If unset, the default is 3 days.
        * @hide
        */
        @Readable
        public static final String FSTRIM_MANDATORY_INTERVAL = "fstrim_mandatory_interval";

        /**
        * The interval in milliseconds at which to check packet counts on the
        * mobile data interface when screen is on, to detect possible data
        * connection problems.
        * @hide
        */
        @Readable
        public static final String PDP_WATCHDOG_POLL_INTERVAL_MS =
               "pdp_watchdog_poll_interval_ms";

        /**
        * The interval in milliseconds at which to check packet counts on the
        * mobile data interface when screen is off, to detect possible data
        * connection problems.
        * @hide
        */
        @Readable
        public static final String PDP_WATCHDOG_LONG_POLL_INTERVAL_MS =
               "pdp_watchdog_long_poll_interval_ms";

        /**
        * The interval in milliseconds at which to check packet counts on the
        * mobile data interface after {@link #PDP_WATCHDOG_TRIGGER_PACKET_COUNT}
        * outgoing packets has been reached without incoming packets.
        * @hide
        */
        @Readable
        public static final String PDP_WATCHDOG_ERROR_POLL_INTERVAL_MS =
               "pdp_watchdog_error_poll_interval_ms";

        /**
        * The number of outgoing packets sent without seeing an incoming packet
        * that triggers a countdown (of {@link #PDP_WATCHDOG_ERROR_POLL_COUNT}
        * device is logged to the event log
        * @hide
        */
        @Readable
        public static final String PDP_WATCHDOG_TRIGGER_PACKET_COUNT =
               "pdp_watchdog_trigger_packet_count";

        /**
        * The number of polls to perform (at {@link #PDP_WATCHDOG_ERROR_POLL_INTERVAL_MS})
        * after hitting {@link #PDP_WATCHDOG_TRIGGER_PACKET_COUNT} before
        * attempting data connection recovery.
        * @hide
        */
        @Readable
        public static final String PDP_WATCHDOG_ERROR_POLL_COUNT =
               "pdp_watchdog_error_poll_count";

        /**
        * The number of failed PDP reset attempts before moving to something more
        * drastic: re-registering to the network.
        * @hide
        */
        @Readable
        public static final String PDP_WATCHDOG_MAX_PDP_RESET_FAIL_COUNT =
               "pdp_watchdog_max_pdp_reset_fail_count";

        /**
        * URL to open browser on to allow user to manage a prepay account
        * @hide
        */
        @Readable
        public static final String SETUP_PREPAID_DATA_SERVICE_URL =
               "setup_prepaid_data_service_url";

        /**
        * URL to attempt a GET on to see if this is a prepay device
        * @hide
        */
        @Readable
        public static final String SETUP_PREPAID_DETECTION_TARGET_URL =
               "setup_prepaid_detection_target_url";

        /**
        * Host to check for a redirect to after an attempt to GET
        * SETUP_PREPAID_DETECTION_TARGET_URL. (If we redirected there,
        * this is a prepaid device with zero balance.)
        * @hide
        */
        @Readable
        public static final String SETUP_PREPAID_DETECTION_REDIR_HOST =
               "setup_prepaid_detection_redir_host";

        /**
        * The interval in milliseconds at which to check the number of SMS sent out without asking
        * for use permit, to limit the un-authorized SMS usage.
        *
        * @hide
        */
        @Readable
        public static final String SMS_OUTGOING_CHECK_INTERVAL_MS =
               "sms_outgoing_check_interval_ms";

        /**
        * The number of outgoing SMS sent without asking for user permit (of {@link
        * #SMS_OUTGOING_CHECK_INTERVAL_MS}
        *
        * @hide
        */
        @Readable
        public static final String SMS_OUTGOING_CHECK_MAX_COUNT =
               "sms_outgoing_check_max_count";

        /**
        * Used to disable SMS short code confirmation - defaults to true.
        * True indcates we will do the check, etc.  Set to false to disable.
        * @see com.android.internal.telephony.SmsUsageMonitor
        * @hide
        */
        @Readable
        public static final String SMS_SHORT_CODE_CONFIRMATION = "sms_short_code_confirmation";

        /**
         * Used to select which country we use to determine premium sms codes.
         * One of com.android.internal.telephony.SMSDispatcher.PREMIUM_RULE_USE_SIM,
         * com.android.internal.telephony.SMSDispatcher.PREMIUM_RULE_USE_NETWORK,
         * or com.android.internal.telephony.SMSDispatcher.PREMIUM_RULE_USE_BOTH.
         * @hide
         */
        @Readable
        public static final String SMS_SHORT_CODE_RULE = "sms_short_code_rule";

        /**
         * Used to select TCP's default initial receiver window size in segments - defaults to a
         * build config value.
         * @hide
         */
        @Readable
        public static final String TCP_DEFAULT_INIT_RWND = "tcp_default_init_rwnd";

        /**
         * Used to disable Tethering on a device - defaults to true.
         * @hide
         */
        @SystemApi
        @Readable
        public static final String TETHER_SUPPORTED = "tether_supported";

        /**
         * Used to require DUN APN on the device or not - defaults to a build config value
         * which defaults to false.
         * @hide
         */
        @Readable
        public static final String TETHER_DUN_REQUIRED = "tether_dun_required";

        /**
         * Used to hold a gservices-provisioned apn value for DUN.  If set, or the
         * corresponding build config values are set it will override the APN DB
         * values.
         * Consists of a comma separated list of strings:
         * "name,apn,proxy,port,username,password,server,mmsc,mmsproxy,mmsport,mcc,mnc,auth,type"
         * note that empty fields can be omitted: "name,apn,,,,,,,,,310,260,,DUN"
         * @hide
         */
        @Readable
        public static final String TETHER_DUN_APN = "tether_dun_apn";

        /**
         * Used to disable trying to talk to any available tethering offload HAL.
         *
         * Integer values are interpreted as boolean, and the absence of an explicit setting
         * is interpreted as |false|.
         * @hide
         */
        @SystemApi
        @Readable
        public static final String TETHER_OFFLOAD_DISABLED = "tether_offload_disabled";

        /**
         * Use the old dnsmasq DHCP server for tethering instead of the framework implementation.
         *
         * Integer values are interpreted as boolean, and the absence of an explicit setting
         * is interpreted as |false|.
         * @hide
         */
        @Readable
        public static final String TETHER_ENABLE_LEGACY_DHCP_SERVER =
                "tether_enable_legacy_dhcp_server";

        /**
         * List of certificate (hex string representation of the application's certificate - SHA-1
         * or SHA-256) and carrier app package pairs which are whitelisted to prompt the user for
         * install when a sim card with matching UICC carrier privilege rules is inserted.  The
         * certificate is used as a key, so the certificate encoding here must be the same as the
         * certificate encoding used on the SIM.
         *
         * The value is "cert1:package1;cert2:package2;..."
         * @hide
         */
        @SystemApi
        @Readable
        public static final String CARRIER_APP_WHITELIST = "carrier_app_whitelist";

        /**
         * Map of package name to application names. The application names cannot and will not be
         * localized. App names may not contain colons or semicolons.
         *
         * The value is "packageName1:appName1;packageName2:appName2;..."
         * @hide
         */
        @SystemApi
        @Readable
        public static final String CARRIER_APP_NAMES = "carrier_app_names";

        /**
        * USB Mass Storage Enabled
        */
        @Readable
        public static final String USB_MASS_STORAGE_ENABLED = "usb_mass_storage_enabled";

        /**
        * If this setting is set (to anything), then all references
        * to Gmail on the device must change to Google Mail.
        */
        @Readable
        public static final String USE_GOOGLE_MAIL = "use_google_mail";

        /**
         * Whether or not switching/creating users is enabled by user.
         * @hide
         */
        @Readable
        public static final String USER_SWITCHER_ENABLED = "user_switcher_enabled";

        /**
         * Webview Data reduction proxy key.
         * @hide
         */
        @Readable
        public static final String WEBVIEW_DATA_REDUCTION_PROXY_KEY =
                "webview_data_reduction_proxy_key";

        /**
        * Name of the package used as WebView provider (if unset the provider is instead determined
        * by the system).
        * @hide
        */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String WEBVIEW_PROVIDER = "webview_provider";

        /**
        * Developer setting to enable WebView multiprocess rendering.
        * @hide
        */
        @SystemApi
        @Readable
        public static final String WEBVIEW_MULTIPROCESS = "webview_multiprocess";

        /**
        * The maximum number of notifications shown in 24 hours when switching networks.
        * @hide
        */
        @Readable
        public static final String NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT =
              "network_switch_notification_daily_limit";

        /**
        * The minimum time in milliseconds between notifications when switching networks.
        * @hide
        */
        @Readable
        public static final String NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS =
              "network_switch_notification_rate_limit_millis";

        /**
        * Whether to automatically switch away from wifi networks that lose Internet access.
        * Only meaningful if config_networkAvoidBadWifi is set to 0, otherwise the system always
        * avoids such networks. Valid values are:
        *
        * 0: Don't avoid bad wifi, don't prompt the user. Get stuck on bad wifi like it's 2013.
        * null: Ask the user whether to switch away from bad wifi.
        * 1: Avoid bad wifi.
        *
        * @hide
        */
        @Readable
        public static final String NETWORK_AVOID_BAD_WIFI = "network_avoid_bad_wifi";

        /**
        * User setting for ConnectivityManager.getMeteredMultipathPreference(). This value may be
        * overridden by the system based on device or application state. If null, the value
        * specified by config_networkMeteredMultipathPreference is used.
        *
        * @hide
        */
        @Readable
        public static final String NETWORK_METERED_MULTIPATH_PREFERENCE =
               "network_metered_multipath_preference";

        /**
         * Default daily multipath budget used by ConnectivityManager.getMultipathPreference()
         * on metered networks. This default quota is only used if quota could not be determined
         * from data plan or data limit/warning set by the user.
         * @hide
         */
        @Readable
        public static final String NETWORK_DEFAULT_DAILY_MULTIPATH_QUOTA_BYTES =
                "network_default_daily_multipath_quota_bytes";

        /**
         * Network watchlist last report time.
         * @hide
         */
        @Readable
        public static final String NETWORK_WATCHLIST_LAST_REPORT_TIME =
                "network_watchlist_last_report_time";

        /**
        * The thresholds of the wifi throughput badging (SD, HD etc.) as a comma-delimited list of
        * colon-delimited key-value pairs. The key is the badging enum value defined in
        * android.net.ScoredNetwork and the value is the minimum sustained network throughput in
        * kbps required for the badge. For example: "10:3000,20:5000,30:25000"
        *
        * @hide
        */
        @SystemApi
        @Readable
        public static final String WIFI_BADGING_THRESHOLDS = "wifi_badging_thresholds";

        /**
        * Whether Wifi display is enabled/disabled
        * 0=disabled. 1=enabled.
        * @hide
        */
        @Readable
        public static final String WIFI_DISPLAY_ON = "wifi_display_on";

        /**
        * Whether Wifi display certification mode is enabled/disabled
        * 0=disabled. 1=enabled.
        * @hide
        */
        @Readable
        public static final String WIFI_DISPLAY_CERTIFICATION_ON =
               "wifi_display_certification_on";

        /**
        * WPS Configuration method used by Wifi display, this setting only
        * takes effect when WIFI_DISPLAY_CERTIFICATION_ON is 1 (enabled).
        *
        * Possible values are:
        *
        * WpsInfo.INVALID: use default WPS method chosen by framework
        * WpsInfo.PBC    : use Push button
        * WpsInfo.KEYPAD : use Keypad
        * WpsInfo.DISPLAY: use Display
        * @hide
        */
        @Readable
        public static final String WIFI_DISPLAY_WPS_CONFIG =
           "wifi_display_wps_config";

        /**
        * Whether to notify the user of open networks.
        * <p>
        * If not connected and the scan results have an open network, we will
        * put this notification up. If we attempt to connect to a network or
        * the open network(s) disappear, we remove the notification. When we
        * show the notification, we will not show it again for
        * {@link android.provider.Settings.Secure#WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY} time.
        *
        * @deprecated This feature is no longer controlled by this setting in
        * {@link android.os.Build.VERSION_CODES#O}.
        */
        @Deprecated
        @Readable
        public static final String WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON =
               "wifi_networks_available_notification_on";

        /**
        * {@hide}
        */
        @Readable
        public static final String WIMAX_NETWORKS_AVAILABLE_NOTIFICATION_ON =
               "wimax_networks_available_notification_on";

        /**
        * Delay (in seconds) before repeating the Wi-Fi networks available notification.
        * Connecting to a network will reset the timer.
        * @deprecated This is no longer used or set by the platform.
        */
        @Deprecated
        @Readable
        public static final String WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY =
               "wifi_networks_available_repeat_delay";

        /**
        * 802.11 country code in ISO 3166 format
        * @hide
        */
        @Readable
        public static final String WIFI_COUNTRY_CODE = "wifi_country_code";

        /**
        * The interval in milliseconds to issue wake up scans when wifi needs
        * to connect. This is necessary to connect to an access point when
        * device is on the move and the screen is off.
        * @hide
        */
        @Readable
        public static final String WIFI_FRAMEWORK_SCAN_INTERVAL_MS =
               "wifi_framework_scan_interval_ms";

        /**
        * The interval in milliseconds after which Wi-Fi is considered idle.
        * When idle, it is possible for the device to be switched from Wi-Fi to
        * the mobile data network.
        * @hide
        */
        @Readable
        public static final String WIFI_IDLE_MS = "wifi_idle_ms";

        /**
        * When the number of open networks exceeds this number, the
        * least-recently-used excess networks will be removed.
        * @deprecated This is no longer used or set by the platform.
        */
        @Deprecated
        @Readable
        public static final String WIFI_NUM_OPEN_NETWORKS_KEPT = "wifi_num_open_networks_kept";

        /**
        * Whether the Wi-Fi should be on.  Only the Wi-Fi service should touch this.
        */
        @Readable
        public static final String WIFI_ON = "wifi_on";

        /**
        * Setting to allow scans to be enabled even wifi is turned off for connectivity.
        * @hide
        * @deprecated To be removed. Use {@link WifiManager#setScanAlwaysAvailable(boolean)} for
        * setting the value and {@link WifiManager#isScanAlwaysAvailable()} for query.
        */
        @Deprecated
        @Readable
        public static final String WIFI_SCAN_ALWAYS_AVAILABLE =
                "wifi_scan_always_enabled";

        /**
         * Indicate whether factory reset request is pending.
         *
         * Type: int (0 for false, 1 for true)
         * @hide
         * @deprecated To be removed.
         */
        @Deprecated
        @Readable
        public static final String WIFI_P2P_PENDING_FACTORY_RESET =
                "wifi_p2p_pending_factory_reset";

        /**
         * Whether soft AP will shut down after a timeout period when no devices are connected.
         *
         * Type: int (0 for false, 1 for true)
         * @hide
         * @deprecated To be removed. Use {@link SoftApConfiguration.Builder#
         * setAutoShutdownEnabled(boolean)} for setting the value and {@link SoftApConfiguration#
         * isAutoShutdownEnabled()} for query.
         */
        @Deprecated
        @Readable
        public static final String SOFT_AP_TIMEOUT_ENABLED = "soft_ap_timeout_enabled";

        /**
         * Value to specify if Wi-Fi Wakeup feature is enabled.
         *
         * Type: int (0 for false, 1 for true)
         * @hide
         * @deprecated Use {@link WifiManager#setAutoWakeupEnabled(boolean)} for setting the value
         * and {@link WifiManager#isAutoWakeupEnabled()} for query.
         */
        @Deprecated
        @SystemApi
        @Readable
        public static final String WIFI_WAKEUP_ENABLED = "wifi_wakeup_enabled";

        /**
         * Value to specify if wifi settings migration is complete or not.
         * Note: This should only be used from within {@link android.net.wifi.WifiMigration} class.
         *
         * Type: int (0 for false, 1 for true)
         * @hide
         */
        @Readable
        public static final String WIFI_MIGRATION_COMPLETED = "wifi_migration_completed";

        /**
         * Whether UWB should be enabled.
         * @hide
         */
        public static final String UWB_ENABLED = "uwb_enabled";

        /**
         * Value to specify whether network quality scores and badging should be shown in the UI.
         *
         * Type: int (0 for false, 1 for true)
         * @deprecated {@link NetworkScoreManager} is deprecated.
         * @hide
         */
        @Deprecated
        @Readable
        public static final String NETWORK_SCORING_UI_ENABLED = "network_scoring_ui_enabled";

        /**
         * Value to specify how long in milliseconds to retain seen score cache curves to be used
         * when generating SSID only bases score curves.
         *
         * Type: long
         * @deprecated {@link NetworkScoreManager} is deprecated.
         * @hide
         */
        @Deprecated
        @Readable
        public static final String SPEED_LABEL_CACHE_EVICTION_AGE_MILLIS =
                "speed_label_cache_eviction_age_millis";

        /**
         * Value to specify if network recommendations from
         * {@link com.android.server.NetworkScoreService} are enabled.
         *
         * Type: int
         * Valid values:
         *   -1 = Forced off
         *    0 = Disabled
         *    1 = Enabled
         *
         * Most readers of this setting should simply check if value == 1 to determine the
         * enabled state.
         * @hide
         * @deprecated To be removed.
         */
        @Deprecated
        @Readable
        public static final String NETWORK_RECOMMENDATIONS_ENABLED =
                "network_recommendations_enabled";

        /**
         * Which package name to use for network recommendations. If null, network recommendations
         * will neither be requested nor accepted.
         *
         * Use {@link NetworkScoreManager#getActiveScorerPackage()} to read this value and
         * {@link NetworkScoreManager#setActiveScorer(String)} to write it.
         *
         * Type: string - package name
         * @deprecated {@link NetworkScoreManager} is deprecated.
         * @hide
         */
        @Deprecated
        @Readable
        public static final String NETWORK_RECOMMENDATIONS_PACKAGE =
                "network_recommendations_package";

        /**
         * The package name of the application that connect and secures high quality open wifi
         * networks automatically.
         *
         * Type: string package name or null if the feature is either not provided or disabled.
         * @deprecated {@link NetworkScoreManager} is deprecated.
         * @hide
         */
        @Deprecated
        @TestApi
        @Readable
        public static final String USE_OPEN_WIFI_PACKAGE = "use_open_wifi_package";

        /**
         * The expiration time in milliseconds for the {@link android.net.WifiKey} request cache in
         * {@link com.android.server.wifi.RecommendedNetworkEvaluator}.
         *
         * Type: long
         * @deprecated {@link NetworkScoreManager} is deprecated.
         * @hide
         */
        @Deprecated
        @Readable
        public static final String RECOMMENDED_NETWORK_EVALUATOR_CACHE_EXPIRY_MS =
                "recommended_network_evaluator_cache_expiry_ms";

        /**
         * Whether wifi scan throttle is enabled or not.
         *
         * Type: int (0 for false, 1 for true)
         * @hide
         * @deprecated Use {@link WifiManager#setScanThrottleEnabled(boolean)} for setting the value
         * and {@link WifiManager#isScanThrottleEnabled()} for query.
         */
        @Deprecated
        @Readable
        public static final String WIFI_SCAN_THROTTLE_ENABLED = "wifi_scan_throttle_enabled";

        /**
        * Settings to allow BLE scans to be enabled even when Bluetooth is turned off for
        * connectivity.
        * @hide
        */
        @Readable
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLE_SCAN_ALWAYS_AVAILABLE = "ble_scan_always_enabled";

        /**
         * The length in milliseconds of a BLE scan window in a low-power scan mode.
         * @hide
         */
        @Readable
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLE_SCAN_LOW_POWER_WINDOW_MS = "ble_scan_low_power_window_ms";

        /**
         * The length in milliseconds of a BLE scan window in a balanced scan mode.
         * @hide
         */
        @Readable
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLE_SCAN_BALANCED_WINDOW_MS = "ble_scan_balanced_window_ms";

        /**
         * The length in milliseconds of a BLE scan window in a low-latency scan mode.
         * @hide
         */
        @Readable
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLE_SCAN_LOW_LATENCY_WINDOW_MS =
                "ble_scan_low_latency_window_ms";

        /**
         * The length in milliseconds of a BLE scan interval in a low-power scan mode.
         * @hide
         */
        @Readable
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLE_SCAN_LOW_POWER_INTERVAL_MS =
                "ble_scan_low_power_interval_ms";

        /**
         * The length in milliseconds of a BLE scan interval in a balanced scan mode.
         * @hide
         */
        @Readable
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLE_SCAN_BALANCED_INTERVAL_MS =
                "ble_scan_balanced_interval_ms";

        /**
         * The length in milliseconds of a BLE scan interval in a low-latency scan mode.
         * @hide
         */
        @Readable
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLE_SCAN_LOW_LATENCY_INTERVAL_MS =
                "ble_scan_low_latency_interval_ms";

        /**
         * The mode that BLE scanning clients will be moved to when in the background.
         * @hide
         */
        @Readable
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String BLE_SCAN_BACKGROUND_MODE = "ble_scan_background_mode";

        /**
        * The interval in milliseconds to scan as used by the wifi supplicant
        * @hide
        */
        @Readable
        public static final String WIFI_SUPPLICANT_SCAN_INTERVAL_MS =
               "wifi_supplicant_scan_interval_ms";

        /**
         * whether frameworks handles wifi auto-join
         * @hide
         */
        @Readable
        public static final String WIFI_ENHANCED_AUTO_JOIN =
                "wifi_enhanced_auto_join";

        /**
         * whether settings show RSSI
         * @hide
         */
        @Readable
        public static final String WIFI_NETWORK_SHOW_RSSI =
                "wifi_network_show_rssi";

        /**
        * The interval in milliseconds to scan at supplicant when p2p is connected
        * @hide
        */
        @Readable
        public static final String WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS =
               "wifi_scan_interval_p2p_connected_ms";

        /**
        * Whether the Wi-Fi watchdog is enabled.
        */
        @Readable
        public static final String WIFI_WATCHDOG_ON = "wifi_watchdog_on";

        /**
        * Setting to turn off poor network avoidance on Wi-Fi. Feature is enabled by default and
        * the setting needs to be set to 0 to disable it.
        * @hide
        */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String WIFI_WATCHDOG_POOR_NETWORK_TEST_ENABLED =
               "wifi_watchdog_poor_network_test_enabled";

        /**
        * Setting to enable verbose logging in Wi-Fi; disabled by default, and setting to 1
        * will enable it. In the future, additional values may be supported.
        * @hide
        * @deprecated Use {@link WifiManager#setVerboseLoggingEnabled(boolean)} for setting the
        * value and {@link WifiManager#isVerboseLoggingEnabled()} for query.
        */
        @Deprecated
        @Readable
        public static final String WIFI_VERBOSE_LOGGING_ENABLED =
               "wifi_verbose_logging_enabled";

        /**
         * Setting to enable connected MAC randomization in Wi-Fi; disabled by default, and
         * setting to 1 will enable it. In the future, additional values may be supported.
         * @deprecated MAC randomization is now a per-network setting
         * @hide
         */
        @Deprecated
        @Readable
        public static final String WIFI_CONNECTED_MAC_RANDOMIZATION_ENABLED =
                "wifi_connected_mac_randomization_enabled";

        /**
         * Parameters to adjust the performance of framework wifi scoring methods.
         * <p>
         * Encoded as a comma-separated key=value list, for example:
         *   "rssi5=-80:-77:-70:-57,rssi2=-83:-80:-73:-60,horizon=15"
         * This is intended for experimenting with new parameter values,
         * and is normally unset or empty. The example does not include all
         * parameters that may be honored.
         * Default values are provided by code or device configurations.
         * Errors in the parameters will cause the entire setting to be ignored.
         * @hide
         * @deprecated This is no longer used or set by the platform.
         */
        @Deprecated
        @Readable
        public static final String WIFI_SCORE_PARAMS =
                "wifi_score_params";

        /**
        * The maximum number of times we will retry a connection to an access
        * point for which we have failed in acquiring an IP address from DHCP.
        * A value of N means that we will make N+1 connection attempts in all.
        */
        @Readable
        public static final String WIFI_MAX_DHCP_RETRY_COUNT = "wifi_max_dhcp_retry_count";

        /**
        * Maximum amount of time in milliseconds to hold a wakelock while waiting for mobile
        * data connectivity to be established after a disconnect from Wi-Fi.
        */
        @Readable
        public static final String WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS =
           "wifi_mobile_data_transition_wakelock_timeout_ms";

        /**
        * This setting controls whether WiFi configurations created by a Device Owner app
        * should be locked down (that is, be editable or removable only by the Device Owner App,
        * not even by Settings app).
        * This setting takes integer values. Non-zero values mean DO created configurations
        * are locked down. Value of zero means they are not. Default value in the absence of
        * actual value to this setting is 0.
        */
        @Readable
        public static final String WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN =
               "wifi_device_owner_configs_lockdown";

        /**
        * The operational wifi frequency band
        * Set to one of {@link WifiManager#WIFI_FREQUENCY_BAND_AUTO},
        * {@link WifiManager#WIFI_FREQUENCY_BAND_5GHZ} or
        * {@link WifiManager#WIFI_FREQUENCY_BAND_2GHZ}
        *
        * @hide
        */
        @Readable
        public static final String WIFI_FREQUENCY_BAND = "wifi_frequency_band";

        /**
        * The Wi-Fi peer-to-peer device name
        * @hide
        * @deprecated Use {@link WifiP2pManager#setDeviceName(WifiP2pManager.Channel, String,
        * WifiP2pManager.ActionListener)} for setting the value and
        * {@link android.net.wifi.p2p.WifiP2pDevice#deviceName} for query.
        */
        @Deprecated
        @Readable
        public static final String WIFI_P2P_DEVICE_NAME = "wifi_p2p_device_name";

        /**
        * Timeout for ephemeral networks when all known BSSIDs go out of range. We will disconnect
        * from an ephemeral network if there is no BSSID for that network with a non-null score that
        * has been seen in this time period.
        *
        * If this is less than or equal to zero, we use a more conservative behavior and only check
        * for a non-null score from the currently connected or target BSSID.
        * @hide
        */
        @Readable
        public static final String WIFI_EPHEMERAL_OUT_OF_RANGE_TIMEOUT_MS =
               "wifi_ephemeral_out_of_range_timeout_ms";

        /**
        * The number of milliseconds to delay when checking for data stalls during
        * non-aggressive detection. (screen is turned off.)
        * @hide
        */
        @Readable
        public static final String DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS =
               "data_stall_alarm_non_aggressive_delay_in_ms";

        /**
        * The number of milliseconds to delay when checking for data stalls during
        * aggressive detection. (screen on or suspected data stall)
        * @hide
        */
        @Readable
        public static final String DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS =
               "data_stall_alarm_aggressive_delay_in_ms";

        /**
        * The number of milliseconds to allow the provisioning apn to remain active
        * @hide
        */
        @Readable
        public static final String PROVISIONING_APN_ALARM_DELAY_IN_MS =
               "provisioning_apn_alarm_delay_in_ms";

        /**
        * The interval in milliseconds at which to check gprs registration
        * after the first registration mismatch of gprs and voice service,
        * to detect possible data network registration problems.
        *
        * @hide
        */
        @Readable
        public static final String GPRS_REGISTER_CHECK_PERIOD_MS =
               "gprs_register_check_period_ms";

        /**
        * Nonzero causes Log.wtf() to crash.
        * @hide
        */
        @Readable
        public static final String WTF_IS_FATAL = "wtf_is_fatal";

        /**
        * Ringer mode. This is used internally, changing this value will not
        * change the ringer mode. See AudioManager.
        */
        @Readable
        public static final String MODE_RINGER = "mode_ringer";

        /**
         * Overlay display devices setting.
         * The associated value is a specially formatted string that describes the
         * size and density of simulated secondary display devices.
         * <p>
         * Format:
         * <pre>
         * [display1];[display2];...
         * </pre>
         * with each display specified as:
         * <pre>
         * [mode1]|[mode2]|...,[flag1],[flag2],...
         * </pre>
         * with each mode specified as:
         * <pre>
         * [width]x[height]/[densityDpi]
         * </pre>
         * Supported flags:
         * <ul>
         * <li><pre>secure</pre>: creates a secure display</li>
         * <li><pre>own_content_only</pre>: only shows this display's own content</li>
         * <li><pre>should_show_system_decorations</pre>: supports system decorations</li>
         * </ul>
         * </p><p>
         * Example:
         * <ul>
         * <li><code>1280x720/213</code>: make one overlay that is 1280x720 at 213dpi.</li>
         * <li><code>1920x1080/320,secure;1280x720/213</code>: make two overlays, the first at
         * 1080p and secure; the second at 720p.</li>
         * <li><code>1920x1080/320|3840x2160/640</code>: make one overlay that is 1920x1080 at
         * 213dpi by default, but can also be upscaled to 3840x2160 at 640dpi by the system if the
         * display device allows.</li>
         * <li>If the value is empty, then no overlay display devices are created.</li>
         * </ul></p>
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @TestApi
        @Readable
        public static final String OVERLAY_DISPLAY_DEVICES = "overlay_display_devices";

        /**
         * Threshold values for the duration and level of a discharge cycle,
         * under which we log discharge cycle info.
         *
         * @hide
         */
        @Readable
        public static final String
                BATTERY_DISCHARGE_DURATION_THRESHOLD = "battery_discharge_duration_threshold";

        /** @hide */
        @Readable
        public static final String BATTERY_DISCHARGE_THRESHOLD = "battery_discharge_threshold";

        /**
         * Flag for allowing ActivityManagerService to send ACTION_APP_ERROR
         * intents on application crashes and ANRs. If this is disabled, the
         * crash/ANR dialog will never display the "Report" button.
         * <p>
         * Type: int (0 = disallow, 1 = allow)
         *
         * @hide
         */
        @Readable
        public static final String SEND_ACTION_APP_ERROR = "send_action_app_error";

        /**
         * Maximum age of entries kept by {@link DropBoxManager}.
         *
         * @hide
         */
        @Readable
        public static final String DROPBOX_AGE_SECONDS = "dropbox_age_seconds";

        /**
         * Maximum number of entry files which {@link DropBoxManager} will keep
         * around.
         *
         * @hide
         */
        @Readable
        public static final String DROPBOX_MAX_FILES = "dropbox_max_files";

        /**
         * Maximum amount of disk space used by {@link DropBoxManager} no matter
         * what.
         *
         * @hide
         */
        @Readable
        public static final String DROPBOX_QUOTA_KB = "dropbox_quota_kb";

        /**
         * Percent of free disk (excluding reserve) which {@link DropBoxManager}
         * will use.
         *
         * @hide
         */
        @Readable
        public static final String DROPBOX_QUOTA_PERCENT = "dropbox_quota_percent";

        /**
         * Percent of total disk which {@link DropBoxManager} will never dip
         * into.
         *
         * @hide
         */
        @Readable
        public static final String DROPBOX_RESERVE_PERCENT = "dropbox_reserve_percent";

        /**
         * Prefix for per-tag dropbox disable/enable settings.
         *
         * @hide
         */
        @Readable
        public static final String DROPBOX_TAG_PREFIX = "dropbox:";

        /**
         * Lines of logcat to include with system crash/ANR/etc. reports, as a
         * prefix of the dropbox tag of the report type. For example,
         * "logcat_for_system_server_anr" controls the lines of logcat captured
         * with system server ANR reports. 0 to disable.
         *
         * @hide
         */
        @Readable
        public static final String ERROR_LOGCAT_PREFIX = "logcat_for_";

        /**
         * Maximum number of bytes of a system crash/ANR/etc. report that
         * ActivityManagerService should send to DropBox, as a prefix of the
         * dropbox tag of the report type. For example,
         * "max_error_bytes_for_system_server_anr" controls the maximum
         * number of bytes captured with system server ANR reports.
         * <p>
         * Type: int (max size in bytes)
         *
         * @hide
         */
        @Readable
        public static final String MAX_ERROR_BYTES_PREFIX = "max_error_bytes_for_";

        /**
         * The interval in minutes after which the amount of free storage left
         * on the device is logged to the event log
         *
         * @hide
         */
        @Readable
        public static final String SYS_FREE_STORAGE_LOG_INTERVAL = "sys_free_storage_log_interval";

        /**
         * Threshold for the amount of change in disk free space required to
         * report the amount of free space. Used to prevent spamming the logs
         * when the disk free space isn't changing frequently.
         *
         * @hide
         */
        @Readable
        public static final String
                DISK_FREE_CHANGE_REPORTING_THRESHOLD = "disk_free_change_reporting_threshold";

        /**
         * Minimum percentage of free storage on the device that is used to
         * determine if the device is running low on storage. The default is 10.
         * <p>
         * Say this value is set to 10, the device is considered running low on
         * storage if 90% or more of the device storage is filled up.
         *
         * @hide
         */
        @Readable
        public static final String
                SYS_STORAGE_THRESHOLD_PERCENTAGE = "sys_storage_threshold_percentage";

        /**
         * Maximum byte size of the low storage threshold. This is to ensure
         * that {@link #SYS_STORAGE_THRESHOLD_PERCENTAGE} does not result in an
         * overly large threshold for large storage devices. Currently this must
         * be less than 2GB. This default is 500MB.
         *
         * @hide
         */
        @Readable
        public static final String
                SYS_STORAGE_THRESHOLD_MAX_BYTES = "sys_storage_threshold_max_bytes";

        /**
         * Minimum bytes of free storage on the device before the data partition
         * is considered full. By default, 1 MB is reserved to avoid system-wide
         * SQLite disk full exceptions.
         *
         * @hide
         */
        @Readable
        public static final String
                SYS_STORAGE_FULL_THRESHOLD_BYTES = "sys_storage_full_threshold_bytes";

        /**
         * Minimum percentage of storage on the device that is reserved for
         * cached data.
         *
         * @hide
         */
        @Readable
        public static final String
                SYS_STORAGE_CACHE_PERCENTAGE = "sys_storage_cache_percentage";

        /**
         * The maximum reconnect delay for short network outages or when the
         * network is suspended due to phone use.
         *
         * @hide
         */
        @Readable
        public static final String
                SYNC_MAX_RETRY_DELAY_IN_SECONDS = "sync_max_retry_delay_in_seconds";

        /**
         * The number of milliseconds to delay before sending out
         * {@link ConnectivityManager#CONNECTIVITY_ACTION} broadcasts. Ignored.
         *
         * @hide
         */
        @Readable
        public static final String CONNECTIVITY_CHANGE_DELAY = "connectivity_change_delay";


        /**
         * Network sampling interval, in seconds. We'll generate link information
         * about bytes/packets sent and error rates based on data sampled in this interval
         *
         * @hide
         */
        @Readable
        public static final String CONNECTIVITY_SAMPLING_INTERVAL_IN_SECONDS =
                "connectivity_sampling_interval_in_seconds";

        /**
         * The series of successively longer delays used in retrying to download PAC file.
         * Last delay is used between successful PAC downloads.
         *
         * @hide
         */
        @Readable
        public static final String PAC_CHANGE_DELAY = "pac_change_delay";

        /**
         * Don't attempt to detect captive portals.
         *
         * @hide
         */
        public static final int CAPTIVE_PORTAL_MODE_IGNORE = 0;

        /**
         * When detecting a captive portal, display a notification that
         * prompts the user to sign in.
         *
         * @hide
         */
        public static final int CAPTIVE_PORTAL_MODE_PROMPT = 1;

        /**
         * When detecting a captive portal, immediately disconnect from the
         * network and do not reconnect to that network in the future.
         *
         * @hide
         */
        public static final int CAPTIVE_PORTAL_MODE_AVOID = 2;

        /**
         * What to do when connecting a network that presents a captive portal.
         * Must be one of the CAPTIVE_PORTAL_MODE_* constants above.
         *
         * The default for this setting is CAPTIVE_PORTAL_MODE_PROMPT.
         * @hide
         */
        @Readable
        public static final String CAPTIVE_PORTAL_MODE = "captive_portal_mode";

        /**
         * Setting to turn off captive portal detection. Feature is enabled by
         * default and the setting needs to be set to 0 to disable it.
         *
         * @deprecated use CAPTIVE_PORTAL_MODE_IGNORE to disable captive portal detection
         * @hide
         */
        @Deprecated
        @Readable
        public static final String
                CAPTIVE_PORTAL_DETECTION_ENABLED = "captive_portal_detection_enabled";

        /**
         * The server used for captive portal detection upon a new conection. A
         * 204 response code from the server is used for validation.
         * TODO: remove this deprecated symbol.
         *
         * @hide
         */
        @Readable
        public static final String CAPTIVE_PORTAL_SERVER = "captive_portal_server";

        /**
         * The URL used for HTTPS captive portal detection upon a new connection.
         * A 204 response code from the server is used for validation.
         *
         * @hide
         */
        @Readable
        public static final String CAPTIVE_PORTAL_HTTPS_URL = "captive_portal_https_url";

        /**
         * The URL used for HTTP captive portal detection upon a new connection.
         * A 204 response code from the server is used for validation.
         *
         * @hide
         */
        @Readable
        public static final String CAPTIVE_PORTAL_HTTP_URL = "captive_portal_http_url";

        /**
         * The URL used for fallback HTTP captive portal detection when previous HTTP
         * and HTTPS captive portal detection attemps did not return a conclusive answer.
         *
         * @hide
         */
        @Readable
        public static final String CAPTIVE_PORTAL_FALLBACK_URL = "captive_portal_fallback_url";

        /**
         * A comma separated list of URLs used for captive portal detection in addition to the
         * fallback HTTP url associated with the CAPTIVE_PORTAL_FALLBACK_URL settings.
         *
         * @hide
         */
        @Readable
        public static final String CAPTIVE_PORTAL_OTHER_FALLBACK_URLS =
                "captive_portal_other_fallback_urls";

        /**
         * A list of captive portal detection specifications used in addition to the fallback URLs.
         * Each spec has the format url@@/@@statusCodeRegex@@/@@contentRegex. Specs are separated
         * by "@@,@@".
         * @hide
         */
        @Readable
        public static final String CAPTIVE_PORTAL_FALLBACK_PROBE_SPECS =
                "captive_portal_fallback_probe_specs";

        /**
         * Whether to use HTTPS for network validation. This is enabled by default and the setting
         * needs to be set to 0 to disable it. This setting is a misnomer because captive portals
         * don't actually use HTTPS, but it's consistent with the other settings.
         *
         * @hide
         */
        @Readable
        public static final String CAPTIVE_PORTAL_USE_HTTPS = "captive_portal_use_https";

        /**
         * Which User-Agent string to use in the header of the captive portal detection probes.
         * The User-Agent field is unset when this setting has no value (HttpUrlConnection default).
         *
         * @hide
         */
        @Readable
        public static final String CAPTIVE_PORTAL_USER_AGENT = "captive_portal_user_agent";

        /**
         * Whether to try cellular data recovery when a bad network is reported.
         *
         * @hide
         */
        @Readable
        public static final String DATA_STALL_RECOVERY_ON_BAD_NETWORK =
                "data_stall_recovery_on_bad_network";

        /**
         * Minumim duration in millisecodns between cellular data recovery attempts
         *
         * @hide
         */
        @Readable
        public static final String MIN_DURATION_BETWEEN_RECOVERY_STEPS_IN_MS =
                "min_duration_between_recovery_steps";

        /**
         * Let user pick default install location.
         *
         * @hide
         */
        @Readable
        public static final String SET_INSTALL_LOCATION = "set_install_location";

        /**
         * Default install location value.
         * 0 = auto, let system decide
         * 1 = internal
         * 2 = sdcard
         * @hide
         */
        @Readable
        public static final String DEFAULT_INSTALL_LOCATION = "default_install_location";

        /**
         * ms during which to consume extra events related to Inet connection
         * condition after a transtion to fully-connected
         *
         * @hide
         */
        @Readable
        public static final String
                INET_CONDITION_DEBOUNCE_UP_DELAY = "inet_condition_debounce_up_delay";

        /**
         * ms during which to consume extra events related to Inet connection
         * condtion after a transtion to partly-connected
         *
         * @hide
         */
        @Readable
        public static final String
                INET_CONDITION_DEBOUNCE_DOWN_DELAY = "inet_condition_debounce_down_delay";

        /** {@hide} */
        @Readable
        public static final String
                READ_EXTERNAL_STORAGE_ENFORCED_DEFAULT = "read_external_storage_enforced_default";

        /**
         * Host name and port for global http proxy. Uses ':' seperator for
         * between host and port.
         */
        @Readable
        public static final String HTTP_PROXY = "http_proxy";

        /**
         * Host name for global http proxy. Set via ConnectivityManager.
         *
         * @hide
         */
        @Readable
        public static final String GLOBAL_HTTP_PROXY_HOST = "global_http_proxy_host";

        /**
         * Integer host port for global http proxy. Set via ConnectivityManager.
         *
         * @hide
         */
        @Readable
        public static final String GLOBAL_HTTP_PROXY_PORT = "global_http_proxy_port";

        /**
         * Exclusion list for global proxy. This string contains a list of
         * comma-separated domains where the global proxy does not apply.
         * Domains should be listed in a comma- separated list. Example of
         * acceptable formats: ".domain1.com,my.domain2.com" Use
         * ConnectivityManager to set/get.
         *
         * @hide
         */
        @Readable
        public static final String
                GLOBAL_HTTP_PROXY_EXCLUSION_LIST = "global_http_proxy_exclusion_list";

        /**
         * The location PAC File for the proxy.
         * @hide
         */
        @Readable
        public static final String
                GLOBAL_HTTP_PROXY_PAC = "global_proxy_pac_url";

        /**
         * Enables the UI setting to allow the user to specify the global HTTP
         * proxy and associated exclusion list.
         *
         * @hide
         */
        @Readable
        public static final String SET_GLOBAL_HTTP_PROXY = "set_global_http_proxy";

        /**
         * Setting for default DNS in case nobody suggests one
         *
         * @hide
         */
        @Readable
        public static final String DEFAULT_DNS_SERVER = "default_dns_server";

        /**
         * The requested Private DNS mode (string), and an accompanying specifier (string).
         *
         * Currently, the specifier holds the chosen provider name when the mode requests
         * a specific provider. It may be used to store the provider name even when the
         * mode changes so that temporarily disabling and re-enabling the specific
         * provider mode does not necessitate retyping the provider hostname.
         *
         * @hide
         */
        @Readable
        public static final String PRIVATE_DNS_MODE = "private_dns_mode";

        /**
         * @hide
         */
        @Readable
        public static final String PRIVATE_DNS_SPECIFIER = "private_dns_specifier";

        /**
          * Forced override of the default mode (hardcoded as "automatic", nee "opportunistic").
          * This allows changing the default mode without effectively disabling other modes,
          * all of which require explicit user action to enable/configure. See also b/79719289.
          *
          * Value is a string, suitable for assignment to PRIVATE_DNS_MODE above.
          *
          * {@hide}
          */
        @Readable
        public static final String PRIVATE_DNS_DEFAULT_MODE = "private_dns_default_mode";


        /** {@hide} */
        @Readable
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @SuppressLint("NoSettingsProvider")
        public static final String
                BLUETOOTH_BTSNOOP_DEFAULT_MODE = "bluetooth_btsnoop_default_mode";
        /** {@hide} */
        @Readable
        public static final String
                BLUETOOTH_HEADSET_PRIORITY_PREFIX = "bluetooth_headset_priority_";
        /** {@hide} */
        @Readable
        public static final String
                BLUETOOTH_A2DP_SINK_PRIORITY_PREFIX = "bluetooth_a2dp_sink_priority_";
        /** {@hide} */
        @Readable
        public static final String
                BLUETOOTH_A2DP_SRC_PRIORITY_PREFIX = "bluetooth_a2dp_src_priority_";
        /** {@hide} */
        @Readable
        public static final String BLUETOOTH_A2DP_SUPPORTS_OPTIONAL_CODECS_PREFIX =
                "bluetooth_a2dp_supports_optional_codecs_";
        /** {@hide} */
        @Readable
        public static final String BLUETOOTH_A2DP_OPTIONAL_CODECS_ENABLED_PREFIX =
                "bluetooth_a2dp_optional_codecs_enabled_";
        /** {@hide} */
        @Readable
        public static final String
                BLUETOOTH_INPUT_DEVICE_PRIORITY_PREFIX = "bluetooth_input_device_priority_";
        /** {@hide} */
        @Readable
        public static final String
                BLUETOOTH_MAP_PRIORITY_PREFIX = "bluetooth_map_priority_";
        /** {@hide} */
        @Readable
        public static final String
                BLUETOOTH_MAP_CLIENT_PRIORITY_PREFIX = "bluetooth_map_client_priority_";
        /** {@hide} */
        @Readable
        public static final String
                BLUETOOTH_PBAP_CLIENT_PRIORITY_PREFIX = "bluetooth_pbap_client_priority_";
        /** {@hide} */
        @Readable
        public static final String
                BLUETOOTH_SAP_PRIORITY_PREFIX = "bluetooth_sap_priority_";
        /** {@hide} */
        @Readable
        public static final String
                BLUETOOTH_PAN_PRIORITY_PREFIX = "bluetooth_pan_priority_";
        /** {@hide} */
        @Readable
        public static final String
                BLUETOOTH_HEARING_AID_PRIORITY_PREFIX = "bluetooth_hearing_aid_priority_";

        /**
         * Enable/disable radio bug detection
         *
         * {@hide}
         */
        @Readable
        public static final String
                ENABLE_RADIO_BUG_DETECTION = "enable_radio_bug_detection";

        /**
         * Count threshold of RIL wakelock timeout for radio bug detection
         *
         * {@hide}
         */
        @Readable
        public static final String
                RADIO_BUG_WAKELOCK_TIMEOUT_COUNT_THRESHOLD =
                "radio_bug_wakelock_timeout_count_threshold";

        /**
         * Count threshold of RIL system error for radio bug detection
         *
         * {@hide}
         */
        @Readable
        public static final String
                RADIO_BUG_SYSTEM_ERROR_COUNT_THRESHOLD =
                "radio_bug_system_error_count_threshold";

        /**
         * Activity manager specific settings.
         * This is encoded as a key=value list, separated by commas. Ex:
         *
         * "gc_timeout=5000,max_cached_processes=24"
         *
         * The following keys are supported:
         *
         * <pre>
         * max_cached_processes                 (int)
         * background_settle_time               (long)
         * fgservice_min_shown_time             (long)
         * fgservice_min_report_time            (long)
         * fgservice_screen_on_before_time      (long)
         * fgservice_screen_on_after_time       (long)
         * content_provider_retain_time         (long)
         * gc_timeout                           (long)
         * gc_min_interval                      (long)
         * full_pss_min_interval                (long)
         * full_pss_lowered_interval            (long)
         * power_check_interval                 (long)
         * power_check_max_cpu_1                (int)
         * power_check_max_cpu_2                (int)
         * power_check_max_cpu_3                (int)
         * power_check_max_cpu_4                (int)
         * service_usage_interaction_time       (long)
         * usage_stats_interaction_interval     (long)
         * service_restart_duration             (long)
         * service_reset_run_duration           (long)
         * service_restart_duration_factor      (int)
         * service_min_restart_time_between     (long)
         * service_max_inactivity               (long)
         * service_bg_start_timeout             (long)
         * service_bg_activity_start_timeout    (long)
         * process_start_async                  (boolean)
         * </pre>
         *
         * <p>
         * Type: string
         * @hide
         * @see com.android.server.am.ActivityManagerConstants
         */
        @Readable
        public static final String ACTIVITY_MANAGER_CONSTANTS = "activity_manager_constants";

        /**
         * Feature flag to enable or disable the activity starts logging feature.
         * Type: int (0 for false, 1 for true)
         * Default: 1
         * @hide
         */
        @Readable
        public static final String ACTIVITY_STARTS_LOGGING_ENABLED
                = "activity_starts_logging_enabled";

        /**
         * Feature flag to enable or disable the foreground service starts logging feature.
         * Type: int (0 for false, 1 for true)
         * Default: 1
         * @hide
         */
        @Readable
        public static final String FOREGROUND_SERVICE_STARTS_LOGGING_ENABLED =
                "foreground_service_starts_logging_enabled";

        /**
         * @hide
         * @see com.android.server.appbinding.AppBindingConstants
         */
        @Readable
        public static final String APP_BINDING_CONSTANTS = "app_binding_constants";

        /**
         * App ops specific settings.
         * This is encoded as a key=value list, separated by commas. Ex:
         *
         * "state_settle_time=10000"
         *
         * The following keys are supported:
         *
         * <pre>
         * top_state_settle_time                (long)
         * fg_service_state_settle_time         (long)
         * bg_state_settle_time                 (long)
         * </pre>
         *
         * <p>
         * Type: string
         * @hide
         * @see com.android.server.AppOpsService.Constants
         */
        @TestApi
        @Readable
        public static final String APP_OPS_CONSTANTS = "app_ops_constants";

        /**
         * Battery Saver specific settings
         * This is encoded as a key=value list, separated by commas. Ex:
         *
         * "vibration_disabled=true,adjust_brightness_factor=0.5"
         *
         * The following keys are supported:
         *
         * <pre>
         * advertise_is_enabled              (boolean)
         * datasaver_disabled                (boolean)
         * enable_night_mode                 (boolean)
         * launch_boost_disabled             (boolean)
         * vibration_disabled                (boolean)
         * animation_disabled                (boolean)
         * soundtrigger_disabled             (boolean)
         * fullbackup_deferred               (boolean)
         * keyvaluebackup_deferred           (boolean)
         * firewall_disabled                 (boolean)
         * gps_mode                          (int)
         * adjust_brightness_disabled        (boolean)
         * adjust_brightness_factor          (float)
         * force_all_apps_standby            (boolean)
         * force_background_check            (boolean)
         * optional_sensors_disabled         (boolean)
         * aod_disabled                      (boolean)
         * quick_doze_enabled                (boolean)
         * </pre>
         * @hide
         * @see com.android.server.power.batterysaver.BatterySaverPolicy
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @TestApi
        @Readable
        public static final String BATTERY_SAVER_CONSTANTS = "battery_saver_constants";

        /**
         * Battery Saver device specific settings
         * This is encoded as a key=value list, separated by commas.
         *
         * The following keys are supported:
         *
         * <pre>
         *     cpufreq-i (list of "core-number:frequency" pairs concatenated with /)
         *     cpufreq-n (list of "core-number:frequency" pairs concatenated with /)
         * </pre>
         *
         * See {@link com.android.server.power.batterysaver.BatterySaverPolicy} for the details.
         *
         * @hide
         */
        @Readable
        public static final String BATTERY_SAVER_DEVICE_SPECIFIC_CONSTANTS =
                "battery_saver_device_specific_constants";

        /**
         * Battery tip specific settings
         * This is encoded as a key=value list, separated by commas. Ex:
         *
         * "battery_tip_enabled=true,summary_enabled=true,high_usage_enabled=true,"
         * "high_usage_app_count=3,reduced_battery_enabled=false,reduced_battery_percent=50,"
         * "high_usage_battery_draining=25,high_usage_period_ms=3000"
         *
         * The following keys are supported:
         *
         * <pre>
         * battery_tip_enabled              (boolean)
         * summary_enabled                  (boolean)
         * battery_saver_tip_enabled        (boolean)
         * high_usage_enabled               (boolean)
         * high_usage_app_count             (int)
         * high_usage_period_ms             (long)
         * high_usage_battery_draining      (int)
         * app_restriction_enabled          (boolean)
         * reduced_battery_enabled          (boolean)
         * reduced_battery_percent          (int)
         * low_battery_enabled              (boolean)
         * low_battery_hour                 (int)
         * </pre>
         * @hide
         */
        @Readable
        public static final String BATTERY_TIP_CONSTANTS = "battery_tip_constants";

        /**
         * Battery anomaly detection specific settings
         * This is encoded as a key=value list, separated by commas.
         * wakeup_blacklisted_tags is a string, encoded as a set of tags, encoded via
         * {@link Uri#encode(String)}, separated by colons. Ex:
         *
         * "anomaly_detection_enabled=true,wakelock_threshold=2000,wakeup_alarm_enabled=true,"
         * "wakeup_alarm_threshold=10,wakeup_blacklisted_tags=tag1:tag2:with%2Ccomma:with%3Acolon"
         *
         * The following keys are supported:
         *
         * <pre>
         * anomaly_detection_enabled       (boolean)
         * wakelock_enabled                (boolean)
         * wakelock_threshold              (long)
         * wakeup_alarm_enabled            (boolean)
         * wakeup_alarm_threshold          (long)
         * wakeup_blacklisted_tags         (string)
         * bluetooth_scan_enabled          (boolean)
         * bluetooth_scan_threshold        (long)
         * </pre>
         * @hide
         */
        @Readable
        public static final String ANOMALY_DETECTION_CONSTANTS = "anomaly_detection_constants";

        /**
         * An integer to show the version of the anomaly config. Ex: 1, which means
         * current version is 1.
         * @hide
         */
        @Readable
        public static final String ANOMALY_CONFIG_VERSION = "anomaly_config_version";

        /**
         * A base64-encoded string represents anomaly stats config, used for
         * {@link android.app.StatsManager}.
         * @hide
         */
        @Readable
        public static final String ANOMALY_CONFIG = "anomaly_config";

        /**
         * Always on display(AOD) specific settings
         * This is encoded as a key=value list, separated by commas. Ex:
         *
         * "prox_screen_off_delay=10000,screen_brightness_array=0:1:2:3:4"
         *
         * The following keys are supported:
         *
         * <pre>
         * screen_brightness_array         (int[])
         * dimming_scrim_array             (int[])
         * prox_screen_off_delay           (long)
         * prox_cooldown_trigger           (long)
         * prox_cooldown_period            (long)
         * </pre>
         * @hide
         */
        @Readable
        public static final String ALWAYS_ON_DISPLAY_CONSTANTS = "always_on_display_constants";

        /**
        * UidCpuPower global setting. This links the sys.uidcpupower system property.
        * The following values are supported:
        * 0 -> /proc/uid_cpupower/* are disabled
        * 1 -> /proc/uid_cpupower/* are enabled
        * Any other value defaults to enabled.
        * @hide
        */
        @Readable
        public static final String SYS_UIDCPUPOWER = "sys_uidcpupower";

        /**
        * traced global setting. This controls weather the deamons: traced and
        * traced_probes run. This links the sys.traced system property.
        * The following values are supported:
        * 0 -> traced and traced_probes are disabled
        * 1 -> traced and traced_probes are enabled
        * Any other value defaults to disabled.
        * @hide
        */
        @Readable
        public static final String SYS_TRACED = "sys_traced";

        /**
         * An integer to reduce the FPS by this factor. Only for experiments. Need to reboot the
         * device for this setting to take full effect.
         *
         * @hide
         */
        @Readable
        public static final String FPS_DEVISOR = "fps_divisor";

        /**
         * Flag to enable or disable display panel low power mode (lpm)
         * false -> Display panel power saving mode is disabled.
         * true  -> Display panel power saving mode is enabled.
         *
         * @hide
         */
        @Readable
        public static final String DISPLAY_PANEL_LPM = "display_panel_lpm";

        /**
         * App time limit usage source setting.
         * This controls which app in a task will be considered the source of usage when
         * calculating app usage time limits.
         *
         * 1 -> task root app
         * 2 -> current app
         * Any other value defaults to task root app.
         *
         * Need to reboot the device for this setting to take effect.
         * @hide
         */
        @Readable
        public static final String APP_TIME_LIMIT_USAGE_SOURCE = "app_time_limit_usage_source";

        /**
         * Enable ART bytecode verification verifications for debuggable apps.
         * 0 = disable, 1 = enable.
         * @hide
         */
        @Readable
        public static final String ART_VERIFIER_VERIFY_DEBUGGABLE =
                "art_verifier_verify_debuggable";

        /**
         * Power manager specific settings.
         * This is encoded as a key=value list, separated by commas. Ex:
         *
         * "no_cached_wake_locks=1"
         *
         * The following keys are supported:
         *
         * <pre>
         * no_cached_wake_locks                 (boolean)
         * </pre>
         *
         * <p>
         * Type: string
         * @hide
         * @see com.android.server.power.PowerManagerConstants
         */
        @Readable
        public static final String POWER_MANAGER_CONSTANTS = "power_manager_constants";

        /**
         * ShortcutManager specific settings.
         * This is encoded as a key=value list, separated by commas. Ex:
         *
         * "reset_interval_sec=86400,max_updates_per_interval=1"
         *
         * The following keys are supported:
         *
         * <pre>
         * reset_interval_sec              (long)
         * max_updates_per_interval        (int)
         * max_icon_dimension_dp           (int, DP)
         * max_icon_dimension_dp_lowram    (int, DP)
         * max_shortcuts                   (int)
         * icon_quality                    (int, 0-100)
         * icon_format                     (String)
         * </pre>
         *
         * <p>
         * Type: string
         * @hide
         * @see com.android.server.pm.ShortcutService.ConfigConstants
         */
        @Readable
        public static final String SHORTCUT_MANAGER_CONSTANTS = "shortcut_manager_constants";

        /**
         * DevicePolicyManager specific settings.
         * This is encoded as a key=value list, separated by commas. Ex:
         *
         * <pre>
         * das_died_service_reconnect_backoff_sec       (long)
         * das_died_service_reconnect_backoff_increase  (float)
         * das_died_service_reconnect_max_backoff_sec   (long)
         * </pre>
         *
         * <p>
         * Type: string
         * @hide
         * see also com.android.server.devicepolicy.DevicePolicyConstants
         */
        @Readable
        public static final String DEVICE_POLICY_CONSTANTS = "device_policy_constants";

        /**
         * TextClassifier specific settings.
         * This is encoded as a key=value list, separated by commas. String[] types like
         * entity_list_default use ":" as delimiter for values. Ex:
         *
         * <pre>
         * classify_text_max_range_length                   (int)
         * detect_language_from_text_enabled                (boolean)
         * entity_list_default                              (String[])
         * entity_list_editable                             (String[])
         * entity_list_not_editable                         (String[])
         * generate_links_log_sample_rate                   (int)
         * generate_links_max_text_length                   (int)
         * in_app_conversation_action_types_default         (String[])
         * lang_id_context_settings                         (float[])
         * lang_id_threshold_override                       (float)
         * local_textclassifier_enabled                     (boolean)
         * model_dark_launch_enabled                        (boolean)
         * notification_conversation_action_types_default   (String[])
         * smart_linkify_enabled                            (boolean)
         * smart_select_animation_enabled                   (boolean)
         * smart_selection_enabled                          (boolean)
         * smart_text_share_enabled                         (boolean)
         * suggest_selection_max_range_length               (int)
         * system_textclassifier_enabled                    (boolean)
         * template_intent_factory_enabled                  (boolean)
         * translate_in_classification_enabled              (boolean)
         * </pre>
         *
         * <p>
         * Type: string
         * @hide
         * see also android.view.textclassifier.TextClassificationConstants
         */
        @Readable
        public static final String TEXT_CLASSIFIER_CONSTANTS = "text_classifier_constants";

        /**
         * BatteryStats specific settings.
         * This is encoded as a key=value list, separated by commas. Ex: "foo=1,bar=true"
         *
         * The following keys are supported:
         * <pre>
         * track_cpu_times_by_proc_state (boolean)
         * track_cpu_active_cluster_time (boolean)
         * read_binary_cpu_time          (boolean)
         * proc_state_cpu_times_read_delay_ms (long)
         * external_stats_collection_rate_limit_ms (long)
         * battery_level_collection_delay_ms (long)
         * max_history_files (int)
         * max_history_buffer_kb (int)
         * battery_charged_delay_ms (int)
         * </pre>
         *
         * <p>
         * Type: string
         * @hide
         * see also com.android.internal.os.BatteryStatsImpl.Constants
         */
        @Readable
        public static final String BATTERY_STATS_CONSTANTS = "battery_stats_constants";

        /**
         * SyncManager specific settings.
         *
         * <p>
         * Type: string
         * @hide
         * @see com.android.server.content.SyncManagerConstants
         */
        @Readable
        public static final String SYNC_MANAGER_CONSTANTS = "sync_manager_constants";

        /**
         * Broadcast dispatch tuning parameters specific to foreground broadcasts.
         *
         * This is encoded as a key=value list, separated by commas. Ex: "foo=1,bar=true"
         *
         * The following keys are supported:
         * <pre>
         * bcast_timeout                (long)
         * bcast_slow_time              (long)
         * bcast_deferral               (long)
         * bcast_deferral_decay_factor  (float)
         * bcast_deferral_floor         (long)
         * bcast_allow_bg_activity_start_timeout    (long)
         * </pre>
         *
         * @hide
         */
        @Readable
        public static final String BROADCAST_FG_CONSTANTS = "bcast_fg_constants";

        /**
         * Broadcast dispatch tuning parameters specific to background broadcasts.
         *
         * This is encoded as a key=value list, separated by commas. Ex: "foo=1,bar=true".
         * See {@link #BROADCAST_FG_CONSTANTS} for the list of supported keys.
         *
         * @hide
         */
        @Readable
        public static final String BROADCAST_BG_CONSTANTS = "bcast_bg_constants";

        /**
         * Broadcast dispatch tuning parameters specific to specific "offline" broadcasts.
         *
         * This is encoded as a key=value list, separated by commas. Ex: "foo=1,bar=true".
         * See {@link #BROADCAST_FG_CONSTANTS} for the list of supported keys.
         *
         * @hide
         */
        @Readable
        public static final String BROADCAST_OFFLOAD_CONSTANTS = "bcast_offload_constants";

        /**
         * Whether or not App Standby feature is enabled by system. This controls throttling of apps
         * based on usage patterns and predictions. Platform will turn on this feature if both this
         * flag and {@link #ADAPTIVE_BATTERY_MANAGEMENT_ENABLED} is on.
         * Type: int (0 for false, 1 for true)
         * Default: 1
         * @hide
         * @see #ADAPTIVE_BATTERY_MANAGEMENT_ENABLED
         */
        @SystemApi
        @Readable
        public static final String APP_STANDBY_ENABLED = "app_standby_enabled";

        /**
         * Whether or not adaptive battery feature is enabled by user. Platform will turn on this
         * feature if both this flag and {@link #APP_STANDBY_ENABLED} is on.
         * Type: int (0 for false, 1 for true)
         * Default: 1
         * @hide
         * @see #APP_STANDBY_ENABLED
         */
        @Readable
        public static final String ADAPTIVE_BATTERY_MANAGEMENT_ENABLED =
                "adaptive_battery_management_enabled";

        /**
         * Whether or not apps are allowed into the
         * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RESTRICTED} bucket.
         * Type: int (0 for false, 1 for true)
         * Default: {@value #DEFAULT_ENABLE_RESTRICTED_BUCKET}
         *
         * @hide
         */
        @Readable
        public static final String ENABLE_RESTRICTED_BUCKET = "enable_restricted_bucket";

        /**
         * @see #ENABLE_RESTRICTED_BUCKET
         * @hide
         */
        public static final int DEFAULT_ENABLE_RESTRICTED_BUCKET = 1;

        /**
         * Whether or not app auto restriction is enabled. When it is enabled, settings app will
         * auto restrict the app if it has bad behavior (e.g. hold wakelock for long time).
         *
         * Type: boolean (0 for false, 1 for true)
         * Default: 1
         *
         * @hide
         */
        @Readable
        public static final String APP_AUTO_RESTRICTION_ENABLED =
                "app_auto_restriction_enabled";

        /**
         * Feature flag to enable or disable the Forced App Standby feature.
         * Type: int (0 for false, 1 for true)
         * Default: 1
         * @hide
         */
        @Readable
        public static final String FORCED_APP_STANDBY_ENABLED = "forced_app_standby_enabled";

        /**
         * Whether or not to enable Forced App Standby on small battery devices.
         * Type: int (0 for false, 1 for true)
         * Default: 0
         * @hide
         */
        @Readable
        public static final String FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED
                = "forced_app_standby_for_small_battery_enabled";

        /**
         * Whether to enable the TARE subsystem as a whole or not.
         * 1 means enable, 0 means disable.
         *
         * @hide
         */
        public static final String ENABLE_TARE = "enable_tare";

        /**
         * Default value for {@link #ENABLE_TARE}.
         *
         * @hide
         */
        public static final int DEFAULT_ENABLE_TARE = 0;

        /**
         * Whether to enable the TARE AlarmManager economic policy or not.
         * 1 means enable, 0 means disable.
         *
         * @hide
         */
        public static final String ENABLE_TARE_ALARM_MANAGER = "enable_tare_alarm_manager";

        /**
         * Default value for {@link #ENABLE_TARE_ALARM_MANAGER}.
         *
         * @hide
         */
        public static final int DEFAULT_ENABLE_TARE_ALARM_MANAGER = 0;

        /**
         * Settings for AlarmManager's TARE EconomicPolicy (list of its economic factors).
         *
         * Keys are listed in {@link android.app.tare.EconomyManager}.
         *
         * @hide
         */
        public static final String TARE_ALARM_MANAGER_CONSTANTS = "tare_alarm_manager_constants";

        /**
         * Whether to enable the TARE JobScheduler economic policy or not.
         * 1 means enable, 0 means disable.
         *
         * @hide
         */
        public static final String ENABLE_TARE_JOB_SCHEDULER = "enable_tare_job_scheduler";

        /**
         * Default value for {@link #ENABLE_TARE_JOB_SCHEDULER}.
         *
         * @hide
         */
        public static final int DEFAULT_ENABLE_TARE_JOB_SCHEDULER = 0;

        /**
         * Settings for JobScheduler's TARE EconomicPolicy (list of its economic factors).
         *
         * Keys are listed in {@link android.app.tare.EconomyManager}.
         *
         * @hide
         */
        public static final String TARE_JOB_SCHEDULER_CONSTANTS = "tare_job_scheduler_constants";

        /**
         * Whether or not to enable the User Absent, Radios Off feature on small battery devices.
         * Type: int (0 for false, 1 for true)
         * Default: 0
         * @hide
         */
        @Readable
        public static final String USER_ABSENT_RADIOS_OFF_FOR_SMALL_BATTERY_ENABLED
                = "user_absent_radios_off_for_small_battery_enabled";

        /**
         * Whether or not to enable the User Absent, Touch Off feature on small battery devices.
         * Type: int (0 for false, 1 for true)
         * Default: 0
         * @hide
         */
        @Readable
        public static final String USER_ABSENT_TOUCH_OFF_FOR_SMALL_BATTERY_ENABLED
                = "user_absent_touch_off_for_small_battery_enabled";

        /**
         * Whether or not to turn on Wifi when proxy is disconnected.
         * Type: int (0 for false, 1 for true)
         * Default: 1
         * @hide
         */
        @Readable
        public static final String WIFI_ON_WHEN_PROXY_DISCONNECTED
                = "wifi_on_when_proxy_disconnected";

        /**
         * Time Only Mode specific settings.
         * This is encoded as a key=value list, separated by commas. Ex: "foo=1,bar=true"
         *
         * The following keys are supported:
         *
         * <pre>
         * enabled                  (boolean)
         * disable_home             (boolean)
         * disable_tilt_to_wake     (boolean)
         * disable_touch_to_wake    (boolean)
         * </pre>
         * Type: string
         * @hide
         */
        @Readable
        public static final String TIME_ONLY_MODE_CONSTANTS
                = "time_only_mode_constants";

        /**
         * Whether of not to send keycode sleep for ungaze when Home is the foreground activity on
         * watch type devices.
         * Type: int (0 for false, 1 for true)
         * Default: 1
         * @hide
         */
        @Readable
        public static final String UNGAZE_SLEEP_ENABLED = "ungaze_sleep_enabled";

        /**
         * Whether or not Network Watchlist feature is enabled.
         * Type: int (0 for false, 1 for true)
         * Default: 0
         * @hide
         */
        @Readable
        public static final String NETWORK_WATCHLIST_ENABLED = "network_watchlist_enabled";

        /**
         * Whether or not show hidden launcher icon apps feature is enabled.
         * Type: int (0 for false, 1 for true)
         * Default: 1
         * @hide
         */
        @Readable
        public static final String SHOW_HIDDEN_LAUNCHER_ICON_APPS_ENABLED =
                "show_hidden_icon_apps_enabled";

        /**
         * Whether or not show new app installed notification is enabled.
         * Type: int (0 for false, 1 for true)
         * Default: 0
         * @hide
         */
        @Readable
        public static final String SHOW_NEW_APP_INSTALLED_NOTIFICATION_ENABLED =
                "show_new_app_installed_notification_enabled";

        /**
         * Flag to keep background restricted profiles running after exiting. If disabled,
         * the restricted profile can be put into stopped state as soon as the user leaves it.
         * Type: int (0 for false, 1 for true)
         *
         * Overridden by the system based on device information. If null, the value specified
         * by {@code config_keepRestrictedProfilesInBackground} is used.
         *
         * @hide
         */
        @Readable
        public static final String KEEP_PROFILE_IN_BACKGROUND = "keep_profile_in_background";

        /**
         * The default time in ms within which a subsequent connection from an always allowed system
         * is allowed to reconnect without user interaction.
         *
         * @hide
         */
        public static final long DEFAULT_ADB_ALLOWED_CONNECTION_TIME = 604800000;

        /**
         * When the user first connects their device to a system a prompt is displayed to allow
         * the adb connection with an option to 'Always allow' connections from this system. If the
         * user selects this always allow option then the connection time is stored for the system.
         * This setting is the time in ms within which a subsequent connection from an always
         * allowed system is allowed to reconnect without user interaction.
         *
         * Type: long
         *
         * @hide
         */
        @Readable
        public static final String ADB_ALLOWED_CONNECTION_TIME =
                "adb_allowed_connection_time";

        /**
         * Scaling factor for normal window animations.
         *
         * The value is a float. Setting to 0.0f will disable window animations.
         */
        @Readable
        public static final String WINDOW_ANIMATION_SCALE = "window_animation_scale";

        /**
         * Setting to disable cross-window blurs. This includes window blur behind, (see
         *  {@link LayoutParams#setBlurBehindRadius}) and window background blur (see
         *  {@link Window#setBackgroundBlurRadius}).
         *
         * The value is a boolean (1 or 0).
         * @hide
         */
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String DISABLE_WINDOW_BLURS = "disable_window_blurs";

        /**
         * Scaling factor for activity transition animations.
         *
         * The value is a float. Setting to 0.0f will disable window animations.
         */
        @Readable
        public static final String TRANSITION_ANIMATION_SCALE = "transition_animation_scale";

        /**
         * Scaling factor for Animator-based animations. This affects both the
         * start delay and duration of all such animations.
         *
         * The value is a float. Setting to 0.0f will cause animations to end immediately.
         * The default value is 1.0f.
         */
        @Readable
        public static final String ANIMATOR_DURATION_SCALE = "animator_duration_scale";

        /**
         * Scaling factor for normal window animations. Setting to 0 will
         * disable window animations.
         *
         * @hide
         */
        @Readable
        public static final String FANCY_IME_ANIMATIONS = "fancy_ime_animations";

        /**
         * If 0, the compatibility mode is off for all applications.
         * If 1, older applications run under compatibility mode.
         * TODO: remove this settings before code freeze (bug/1907571)
         * @hide
         */
        @Readable
        public static final String COMPATIBILITY_MODE = "compatibility_mode";

        /**
         * CDMA only settings
         * Emergency Tone  0 = Off
         *                 1 = Alert
         *                 2 = Vibrate
         * @hide
         */
        @Readable
        public static final String EMERGENCY_TONE = "emergency_tone";

        /**
         * CDMA only settings
         * Whether the auto retry is enabled. The value is
         * boolean (1 or 0).
         * @hide
         */
        @Readable
        public static final String CALL_AUTO_RETRY = "call_auto_retry";

        /**
         * A setting that can be read whether the emergency affordance is currently needed.
         * The value is a boolean (1 or 0).
         * @hide
         */
        @Readable
        public static final String EMERGENCY_AFFORDANCE_NEEDED = "emergency_affordance_needed";

        /**
         * The power button "cooldown" period in milliseconds after the Emergency gesture is
         * triggered, during which single-key actions on the power button are suppressed. Cooldown
         * period is disabled if set to zero.
         *
         * @hide
         */
        public static final String EMERGENCY_GESTURE_POWER_BUTTON_COOLDOWN_PERIOD_MS =
                "emergency_gesture_power_button_cooldown_period_ms";

        /**
         * The minimum time in milliseconds to perform the emergency gesture.
         *
         * @hide
         */
        public static final String EMERGENCY_GESTURE_TAP_DETECTION_MIN_TIME_MS =
                "emergency_gesture_tap_detection_min_time_ms";

        /**
         * Whether to enable automatic system server heap dumps. This only works on userdebug or
         * eng builds, not on user builds. This is set by the user and overrides the config value.
         * 1 means enable, 0 means disable.
         *
         * @hide
         */
        @Readable
        public static final String ENABLE_AUTOMATIC_SYSTEM_SERVER_HEAP_DUMPS =
                "enable_automatic_system_server_heap_dumps";

        /**
         * See RIL_PreferredNetworkType in ril.h
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String PREFERRED_NETWORK_MODE =
                "preferred_network_mode";

        /**
         * Name of an application package to be debugged.
         */
        @Readable
        public static final String DEBUG_APP = "debug_app";

        /**
         * If 1, when launching DEBUG_APP it will wait for the debugger before
         * starting user code.  If 0, it will run normally.
         */
        @Readable
        public static final String WAIT_FOR_DEBUGGER = "wait_for_debugger";

        /**
         * Allow GPU debug layers?
         * 0 = no
         * 1 = yes
         * @hide
         */
        @Readable
        public static final String ENABLE_GPU_DEBUG_LAYERS = "enable_gpu_debug_layers";

        /**
         * App allowed to load GPU debug layers
         * @hide
         */
        @Readable
        public static final String GPU_DEBUG_APP = "gpu_debug_app";

        /**
         * Package containing ANGLE libraries other than system, which are only available
         * to dumpable apps that opt-in.
         * @hide
         */
        @Readable
        public static final String ANGLE_DEBUG_PACKAGE = "angle_debug_package";

        /**
         * Force all PKGs to use ANGLE, regardless of any other settings
         * The value is a boolean (1 or 0).
         * @hide
         */
        @Readable
        public static final String ANGLE_GL_DRIVER_ALL_ANGLE = "angle_gl_driver_all_angle";

        /**
         * List of PKGs that have an OpenGL driver selected
         * @hide
         */
        @Readable
        public static final String ANGLE_GL_DRIVER_SELECTION_PKGS =
                "angle_gl_driver_selection_pkgs";

        /**
         * List of selected OpenGL drivers, corresponding to the PKGs in GLOBAL_SETTINGS_DRIVER_PKGS
         * @hide
         */
        @Readable
        public static final String ANGLE_GL_DRIVER_SELECTION_VALUES =
                "angle_gl_driver_selection_values";

        /**
         * Lists of ANGLE EGL features for debugging.
         * Each list of features is separated by a comma, each feature in each list is separated by
         * a colon.
         * e.g. feature1:feature2:feature3,feature1:feature3:feature5
         * @hide
         */
        @Readable
        public static final String ANGLE_EGL_FEATURES = "angle_egl_features";

        /**
         * Show the "ANGLE In Use" dialog box to the user when ANGLE is the OpenGL driver.
         * The value is a boolean (1 or 0).
         * @hide
         */
        @Readable
        public static final String SHOW_ANGLE_IN_USE_DIALOG_BOX = "show_angle_in_use_dialog_box";

        /**
         * Updatable driver global preference for all Apps.
         * 0 = Default
         * 1 = All Apps use updatable production driver
         * 2 = All apps use updatable prerelease driver
         * 3 = All Apps use system graphics driver
         * @hide
         */
        @Readable
        public static final String UPDATABLE_DRIVER_ALL_APPS = "updatable_driver_all_apps";

        /**
         * List of Apps selected to use updatable production driver.
         * i.e. <pkg1>,<pkg2>,...,<pkgN>
         * @hide
         */
        @Readable
        public static final String UPDATABLE_DRIVER_PRODUCTION_OPT_IN_APPS =
                "updatable_driver_production_opt_in_apps";

        /**
         * List of Apps selected to use updatable prerelease driver.
         * i.e. <pkg1>,<pkg2>,...,<pkgN>
         * @hide
         */
        @Readable
        public static final String UPDATABLE_DRIVER_PRERELEASE_OPT_IN_APPS =
                "updatable_driver_prerelease_opt_in_apps";

        /**
         * List of Apps selected not to use updatable production driver.
         * i.e. <pkg1>,<pkg2>,...,<pkgN>
         * @hide
         */
        @Readable
        public static final String UPDATABLE_DRIVER_PRODUCTION_OPT_OUT_APPS =
                "updatable_driver_production_opt_out_apps";

        /**
         * Apps on the denylist that are forbidden to use updatable production driver.
         * @hide
         */
        @Readable
        public static final String UPDATABLE_DRIVER_PRODUCTION_DENYLIST =
                "updatable_driver_production_denylist";

        /**
         * List of denylists, each denylist is a denylist for a specific version of
         * updatable production driver.
         * @hide
         */
        @Readable
        public static final String UPDATABLE_DRIVER_PRODUCTION_DENYLISTS =
                "updatable_driver_production_denylists";

        /**
         * Apps on the allowlist that are allowed to use updatable production driver.
         * The string is a list of application package names, seperated by comma.
         * i.e. <apk1>,<apk2>,...,<apkN>
         * @hide
         */
        @Readable
        public static final String UPDATABLE_DRIVER_PRODUCTION_ALLOWLIST =
                "updatable_driver_production_allowlist";

        /**
         * List of libraries in sphal accessible by updatable driver
         * The string is a list of library names, separated by colon.
         * i.e. <lib1>:<lib2>:...:<libN>
         * @hide
         */
        @Readable
        public static final String UPDATABLE_DRIVER_SPHAL_LIBRARIES =
                "updatable_driver_sphal_libraries";

        /**
         * Ordered GPU debug layer list for Vulkan
         * i.e. <layer1>:<layer2>:...:<layerN>
         * @hide
         */
        @Readable
        public static final String GPU_DEBUG_LAYERS = "gpu_debug_layers";

        /**
         * Ordered GPU debug layer list for GLES
         * i.e. <layer1>:<layer2>:...:<layerN>
         * @hide
         */
        @Readable
        public static final String GPU_DEBUG_LAYERS_GLES = "gpu_debug_layers_gles";

        /**
         * Addition app for GPU layer discovery
         * @hide
         */
        @Readable
        public static final String GPU_DEBUG_LAYER_APP = "gpu_debug_layer_app";

        /**
         * Control whether the process CPU usage meter should be shown.
         *
         * @deprecated This functionality is no longer available as of
         * {@link android.os.Build.VERSION_CODES#N_MR1}.
         */
        @Deprecated
        @Readable
        public static final String SHOW_PROCESSES = "show_processes";

        /**
         * If 1 low power mode (aka battery saver) is enabled.
         * @hide
         */
        @TestApi
        @Readable
        public static final String LOW_POWER_MODE = "low_power";

        /**
         * If 1, battery saver ({@link #LOW_POWER_MODE}) will be re-activated after the device
         * is unplugged from a charger or rebooted.
         * @hide
         */
        @TestApi
        @Readable
        public static final String LOW_POWER_MODE_STICKY = "low_power_sticky";

        /**
         * When a device is unplugged from a changer (or is rebooted), do not re-activate battery
         * saver even if {@link #LOW_POWER_MODE_STICKY} is 1, if the battery level is equal to or
         * above this threshold.
         *
         * @hide
         */
        @Readable
        public static final String LOW_POWER_MODE_STICKY_AUTO_DISABLE_LEVEL =
                "low_power_sticky_auto_disable_level";

        /**
         * Whether sticky battery saver should be deactivated once the battery level has reached the
         * threshold specified by {@link #LOW_POWER_MODE_STICKY_AUTO_DISABLE_LEVEL}.
         *
         * @hide
         */
        @Readable
        public static final String LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED =
                "low_power_sticky_auto_disable_enabled";

        /**
         * Battery level [1-100] at which low power mode automatically turns on.
         * If 0, it will not automatically turn on. For Q and newer, it will only automatically
         * turn on if the value is greater than 0 and the {@link #AUTOMATIC_POWER_SAVE_MODE}
         * setting is also set to
         * {@link android.os.PowerManager.AutoPowerSaveMode#POWER_SAVE_MODE_TRIGGER_PERCENTAGE}.
         * @see #AUTOMATIC_POWER_SAVE_MODE
         * @see android.os.PowerManager#getPowerSaveModeTrigger()
         * @hide
         */
        @Readable
        public static final String LOW_POWER_MODE_TRIGGER_LEVEL = "low_power_trigger_level";

        /**
         * Whether battery saver is currently set to trigger based on percentage, dynamic power
         * savings trigger, or none. See {@link AutoPowerSaveModeTriggers} for
         * accepted values.
         *
         *  @hide
         */
        @TestApi
        @Readable
        public static final String AUTOMATIC_POWER_SAVE_MODE = "automatic_power_save_mode";

        /**
         * The setting that backs the disable threshold for the setPowerSavingsWarning api in
         * PowerManager
         *
         * @see android.os.PowerManager#setDynamicPowerSaveHint(boolean, int)
         * @hide
         */
        @TestApi
        @Readable
        public static final String DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD =
                "dynamic_power_savings_disable_threshold";

        /**
         * The setting which backs the setDynamicPowerSaveHint api in PowerManager.
         *
         * @see android.os.PowerManager#setDynamicPowerSaveHint(boolean, int)
         * @hide
         */
        @TestApi
        @Readable
        public static final String DYNAMIC_POWER_SAVINGS_ENABLED = "dynamic_power_savings_enabled";

        /**
         * A long value indicating how much longer the system battery is estimated to last in
         * millis. See {@link #BATTERY_ESTIMATES_LAST_UPDATE_TIME} for the last time this value
         * was updated.
         *
         * @deprecated Use {@link PowerManager#getBatteryDischargePrediction()} instead.
         * @hide
         */
        @Deprecated
        @Readable
        public static final String TIME_REMAINING_ESTIMATE_MILLIS =
                "time_remaining_estimate_millis";

        /**
         * A boolean indicating whether {@link #TIME_REMAINING_ESTIMATE_MILLIS} is customized
         * to the device's usage or using global models. See
         * {@link #BATTERY_ESTIMATES_LAST_UPDATE_TIME} for the last time this value was updated.
         *
         * @deprecated Use {@link PowerManager#isBatteryDischargePredictionPersonalized()} instead.
         *
         * @hide
         */
        @Deprecated
        @Readable
        public static final String TIME_REMAINING_ESTIMATE_BASED_ON_USAGE =
                "time_remaining_estimate_based_on_usage";

        /**
         * A long value indicating how long the system battery takes to deplete from 100% to 0% on
         * average based on historical drain rates. See {@link #BATTERY_ESTIMATES_LAST_UPDATE_TIME}
         * for the last time this value was updated.
         *
         * @deprecated Use {@link PowerManager#getHistoricalDischargeTime()} instead.
         * @hide
         */
        @Deprecated
        @Readable
        public static final String AVERAGE_TIME_TO_DISCHARGE = "average_time_to_discharge";

        /**
         * A long indicating the epoch time in milliseconds when
         * {@link #TIME_REMAINING_ESTIMATE_MILLIS}, {@link #TIME_REMAINING_ESTIMATE_BASED_ON_USAGE},
         * and {@link #AVERAGE_TIME_TO_DISCHARGE} were last updated.
         *
         * @hide
         * @deprecated No longer needed due to {@link PowerManager#getBatteryDischargePrediction}.
         */
        @Deprecated
        @Readable
        public static final String BATTERY_ESTIMATES_LAST_UPDATE_TIME =
                "battery_estimates_last_update_time";

        /**
         * The max value for {@link #LOW_POWER_MODE_TRIGGER_LEVEL}. If this setting is not set
         * or the value is 0, the default max will be used.
         *
         * @hide
         */
        @Readable
        public static final String LOW_POWER_MODE_TRIGGER_LEVEL_MAX = "low_power_trigger_level_max";

        /**
         * See com.android.settingslib.fuelgauge.BatterySaverUtils.
         * @hide
         */
        @Readable
        public static final String LOW_POWER_MODE_SUGGESTION_PARAMS =
                "low_power_mode_suggestion_params";

        /**
         * If not 0, the activity manager will aggressively finish activities and
         * processes as soon as they are no longer needed.  If 0, the normal
         * extended lifetime is used.
         */
        @Readable
        public static final String ALWAYS_FINISH_ACTIVITIES = "always_finish_activities";

        /**
         * If nonzero, all system error dialogs will be hidden.  For example, the
         * crash and ANR dialogs will not be shown, and the system will just proceed
         * as if they had been accepted by the user.
         * @hide
         */
        @TestApi
        @Readable
        public static final String HIDE_ERROR_DIALOGS = "hide_error_dialogs";

        /**
         * Use Dock audio output for media:
         *      0 = disabled
         *      1 = enabled
         * @hide
         */
        @Readable
        public static final String DOCK_AUDIO_MEDIA_ENABLED = "dock_audio_media_enabled";

        /**
         * The surround sound formats AC3, DTS or IEC61937 are
         * available for use if they are detected.
         * This is the default mode.
         *
         * Note that AUTO is equivalent to ALWAYS for Android TVs and other
         * devices that have an S/PDIF output. This is because S/PDIF
         * is unidirectional and the TV cannot know if a decoder is
         * connected. So it assumes they are always available.
         * @hide
         */
         public static final int ENCODED_SURROUND_OUTPUT_AUTO = 0;

        /**
         * AC3, DTS or IEC61937 are NEVER available, even if they
         * are detected by the hardware. Those formats will not be
         * reported.
         *
         * An example use case would be an AVR reports that it is capable of
         * surround sound decoding but is broken. If NEVER is chosen
         * then apps must use PCM output instead of encoded output.
         * @hide
         */
         public static final int ENCODED_SURROUND_OUTPUT_NEVER = 1;

        /**
         * AC3, DTS or IEC61937 are ALWAYS available, even if they
         * are not detected by the hardware. Those formats will be
         * reported as part of the HDMI output capability. Applications
         * are then free to use either PCM or encoded output.
         *
         * An example use case would be a when TV was connected over
         * TOS-link to an AVR. But the TV could not see it because TOS-link
         * is unidirectional.
         * @hide
         */
         public static final int ENCODED_SURROUND_OUTPUT_ALWAYS = 2;

        /**
         * Surround sound formats are available according to the choice
         * of user, even if they are not detected by the hardware. Those
         * formats will be reported as part of the HDMI output capability.
         * Applications are then free to use either PCM or encoded output.
         *
         * An example use case would be an AVR that doesn't report a surround
         * format while the user knows the AVR does support it.
         * @hide
         */
        public static final int ENCODED_SURROUND_OUTPUT_MANUAL = 3;

        /**
         * The maximum value for surround sound output mode in Android S.
         * @hide
         */
        public static final int ENCODED_SURROUND_SC_MAX = ENCODED_SURROUND_OUTPUT_MANUAL;

        /**
         * Set to ENCODED_SURROUND_OUTPUT_AUTO,
         * ENCODED_SURROUND_OUTPUT_NEVER,
         * ENCODED_SURROUND_OUTPUT_ALWAYS or
         * ENCODED_SURROUND_OUTPUT_MANUAL
         * @hide
         */
        @Readable
        public static final String ENCODED_SURROUND_OUTPUT = "encoded_surround_output";

        /**
         * Surround sounds formats that are enabled when ENCODED_SURROUND_OUTPUT is set to
         * ENCODED_SURROUND_OUTPUT_MANUAL. Encoded as comma separated list. Allowed values
         * are the format constants defined in AudioFormat.java. Ex:
         *
         * "5,6"
         *
         * @hide
         */
        @Readable
        public static final String ENCODED_SURROUND_OUTPUT_ENABLED_FORMATS =
                "encoded_surround_output_enabled_formats";

        /**
         * Persisted safe headphone volume management state by AudioService
         * @hide
         */
        @Readable
        public static final String AUDIO_SAFE_VOLUME_STATE = "audio_safe_volume_state";

        /**
         * URL for tzinfo (time zone) updates
         * @hide
         */
        @Readable
        public static final String TZINFO_UPDATE_CONTENT_URL = "tzinfo_content_url";

        /**
         * URL for tzinfo (time zone) update metadata
         * @hide
         */
        @Readable
        public static final String TZINFO_UPDATE_METADATA_URL = "tzinfo_metadata_url";

        /**
         * URL for selinux (mandatory access control) updates
         * @hide
         */
        @Readable
        public static final String SELINUX_UPDATE_CONTENT_URL = "selinux_content_url";

        /**
         * URL for selinux (mandatory access control) update metadata
         * @hide
         */
        @Readable
        public static final String SELINUX_UPDATE_METADATA_URL = "selinux_metadata_url";

        /**
         * URL for sms short code updates
         * @hide
         */
        @Readable
        public static final String SMS_SHORT_CODES_UPDATE_CONTENT_URL =
                "sms_short_codes_content_url";

        /**
         * URL for sms short code update metadata
         * @hide
         */
        @Readable
        public static final String SMS_SHORT_CODES_UPDATE_METADATA_URL =
                "sms_short_codes_metadata_url";

        /**
         * URL for apn_db updates
         * @hide
         */
        @Readable
        public static final String APN_DB_UPDATE_CONTENT_URL = "apn_db_content_url";

        /**
         * URL for apn_db update metadata
         * @hide
         */
        @Readable
        public static final String APN_DB_UPDATE_METADATA_URL = "apn_db_metadata_url";

        /**
         * URL for cert pinlist updates
         * @hide
         */
        @Readable
        public static final String CERT_PIN_UPDATE_CONTENT_URL = "cert_pin_content_url";

        /**
         * URL for cert pinlist updates
         * @hide
         */
        @Readable
        public static final String CERT_PIN_UPDATE_METADATA_URL = "cert_pin_metadata_url";

        /**
         * URL for intent firewall updates
         * @hide
         */
        @Readable
        public static final String INTENT_FIREWALL_UPDATE_CONTENT_URL =
                "intent_firewall_content_url";

        /**
         * URL for intent firewall update metadata
         * @hide
         */
        @Readable
        public static final String INTENT_FIREWALL_UPDATE_METADATA_URL =
                "intent_firewall_metadata_url";

        /**
         * URL for lang id model updates
         * @hide
         */
        @Readable
        public static final String LANG_ID_UPDATE_CONTENT_URL = "lang_id_content_url";

        /**
         * URL for lang id model update metadata
         * @hide
         */
        @Readable
        public static final String LANG_ID_UPDATE_METADATA_URL = "lang_id_metadata_url";

        /**
         * URL for smart selection model updates
         * @hide
         */
        @Readable
        public static final String SMART_SELECTION_UPDATE_CONTENT_URL =
                "smart_selection_content_url";

        /**
         * URL for smart selection model update metadata
         * @hide
         */
        @Readable
        public static final String SMART_SELECTION_UPDATE_METADATA_URL =
                "smart_selection_metadata_url";

        /**
         * URL for conversation actions model updates
         * @hide
         */
        @Readable
        public static final String CONVERSATION_ACTIONS_UPDATE_CONTENT_URL =
                "conversation_actions_content_url";

        /**
         * URL for conversation actions model update metadata
         * @hide
         */
        @Readable
        public static final String CONVERSATION_ACTIONS_UPDATE_METADATA_URL =
                "conversation_actions_metadata_url";

        /**
         * SELinux enforcement status. If 0, permissive; if 1, enforcing.
         * @hide
         */
        @Readable
        public static final String SELINUX_STATUS = "selinux_status";

        /**
         * Developer setting to force RTL layout.
         * @hide
         */
        @Readable
        public static final String DEVELOPMENT_FORCE_RTL = "debug.force_rtl";

        /**
         * Milliseconds after screen-off after which low battery sounds will be silenced.
         *
         * If zero, battery sounds will always play.
         * Defaults to @integer/def_low_battery_sound_timeout in SettingsProvider.
         *
         * @hide
         */
        @Readable
        public static final String LOW_BATTERY_SOUND_TIMEOUT = "low_battery_sound_timeout";

        /**
         * Milliseconds to wait before bouncing Wi-Fi after settings is restored. Note that after
         * the caller is done with this, they should call {@link ContentResolver#delete} to
         * clean up any value that they may have written.
         *
         * @hide
         */
        @Readable
        public static final String WIFI_BOUNCE_DELAY_OVERRIDE_MS = "wifi_bounce_delay_override_ms";

        /**
         * Defines global runtime overrides to window policy.
         *
         * See {@link com.android.server.wm.PolicyControl} for value format.
         *
         * @hide
         */
        @Readable
        public static final String POLICY_CONTROL = "policy_control";

        /**
         * {@link android.view.DisplayCutout DisplayCutout} emulation mode.
         *
         * @hide
         */
        @Readable
        public static final String EMULATE_DISPLAY_CUTOUT = "emulate_display_cutout";

        /** @hide */ public static final int EMULATE_DISPLAY_CUTOUT_OFF = 0;
        /** @hide */ public static final int EMULATE_DISPLAY_CUTOUT_ON = 1;

        /**
         * A colon separated list of keys for Settings Slices.
         *
         * @hide
         */
        @Readable
        public static final String BLOCKED_SLICES = "blocked_slices";

        /**
         * Defines global zen mode.  ZEN_MODE_OFF, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
         * or ZEN_MODE_NO_INTERRUPTIONS.
         *
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String ZEN_MODE = "zen_mode";

        /** @hide */
        @UnsupportedAppUsage
        public static final int ZEN_MODE_OFF = 0;
        /** @hide */
        @UnsupportedAppUsage
        public static final int ZEN_MODE_IMPORTANT_INTERRUPTIONS = 1;
        /** @hide */
        @UnsupportedAppUsage
        public static final int ZEN_MODE_NO_INTERRUPTIONS = 2;
        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final int ZEN_MODE_ALARMS = 3;

        /**
         * A comma-separated list of HDR formats that have been disabled by the user.
         * <p>
         * If present, these formats will not be reported to apps, even if the display supports
         * them. This list is treated as empty if the ARE_USER_DISABLED_HDR_FORMATS_ALLOWED setting
         * is '1'.
         * </p>
         * @hide
         */
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String USER_DISABLED_HDR_FORMATS = "user_disabled_hdr_formats";

        /**
         * Whether or not user-disabled HDR formats are allowed.
         * <p>
         * The value is boolean (1 or 0). The value '1' means the user preference for disabling a
         * format is ignored, and the disabled formats are still reported to apps (if supported
         * by the display). The value '0' means the user-disabled formats are not reported to
         * apps, even if the display supports them.
         * </p><p>
         * The list of formats disabled by the user are contained in the
         * USER_DISABLED_HDR_FORMATS setting. This list is treated as empty when the value of
         * this setting is '1'.
         * </p>
         * @hide
         */
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String ARE_USER_DISABLED_HDR_FORMATS_ALLOWED =
                "are_user_disabled_hdr_formats_allowed";

        /**
         * Whether or not syncs (bulk set operations) for {@link DeviceConfig} are currently
         * persistently disabled. This is only used for the {@link
         * Config#SYNC_DISABLED_MODE_PERSISTENT persistent} mode, {@link
         * Config#SYNC_DISABLED_MODE_UNTIL_REBOOT until_reboot} mode is not stored in settings.
         * The value is boolean (1 or 0). The value '1' means that {@link
         * DeviceConfig#setProperties(DeviceConfig.Properties)} will return {@code false}.
         *
         * @hide
         */
        public static final String DEVICE_CONFIG_SYNC_DISABLED = "device_config_sync_disabled";

        /** @hide */ public static String zenModeToString(int mode) {
            if (mode == ZEN_MODE_IMPORTANT_INTERRUPTIONS) return "ZEN_MODE_IMPORTANT_INTERRUPTIONS";
            if (mode == ZEN_MODE_ALARMS) return "ZEN_MODE_ALARMS";
            if (mode == ZEN_MODE_NO_INTERRUPTIONS) return "ZEN_MODE_NO_INTERRUPTIONS";
            return "ZEN_MODE_OFF";
        }

        /** @hide */ public static boolean isValidZenMode(int value) {
            switch (value) {
                case Global.ZEN_MODE_OFF:
                case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS:
                case Global.ZEN_MODE_ALARMS:
                case Global.ZEN_MODE_NO_INTERRUPTIONS:
                    return true;
                default:
                    return false;
            }
        }

        /**
         * Value of the ringer before entering zen mode.
         *
         * @hide
         */
        @Readable
        public static final String ZEN_MODE_RINGER_LEVEL = "zen_mode_ringer_level";

        /**
         * Opaque value, changes when persisted zen mode configuration changes.
         *
         * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String ZEN_MODE_CONFIG_ETAG = "zen_mode_config_etag";

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#ZEN_DURATION} instead
         * @hide
         */
        @Deprecated
        public static final String ZEN_DURATION = "zen_duration";

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#ZEN_DURATION_PROMPT} instead
         * @hide
         */
        @Deprecated
        public static final int ZEN_DURATION_PROMPT = -1;

        /**
         * @deprecated Use {@link android.provider.Settings.Secure#ZEN_DURATION_FOREVER} instead
         * @hide
         */
        @Deprecated
        public static final int ZEN_DURATION_FOREVER = 0;

        /**
         * Defines global heads up toggle.  One of HEADS_UP_OFF, HEADS_UP_ON.
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @Readable
        public static final String HEADS_UP_NOTIFICATIONS_ENABLED =
                "heads_up_notifications_enabled";

        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final int HEADS_UP_OFF = 0;
        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final int HEADS_UP_ON = 1;

        /**
         * The refresh rate chosen by the user.
         *
         * @hide
         */
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String USER_PREFERRED_REFRESH_RATE = "user_preferred_refresh_rate";

        /**
         * The resolution height chosen by the user.
         *
         * @hide
         */
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String USER_PREFERRED_RESOLUTION_HEIGHT =
                "user_preferred_resolution_height";

        /**
         * The resolution width chosen by the user.
         *
         * @hide
         */
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String USER_PREFERRED_RESOLUTION_WIDTH =
                "user_preferred_resolution_width";

        /**
         * The name of the device
         */
        @Readable
        public static final String DEVICE_NAME = "device_name";

        /**
         * Whether the NetworkScoringService has been first initialized.
         * <p>
         * Type: int (0 for false, 1 for true)
         * @hide
         */
        @Readable
        public static final String NETWORK_SCORING_PROVISIONED = "network_scoring_provisioned";

        /**
         * Indicates whether the user wants to be prompted for password to decrypt the device on
         * boot. This only matters if the storage is encrypted.
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String REQUIRE_PASSWORD_TO_DECRYPT = "require_password_to_decrypt";

        /**
         * Whether the Volte is enabled. If this setting is not set then we use the Carrier Config
         * value
         * {@link android.telephony.CarrierConfigManager#KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL}.
         * <p>
         * Type: int (0 for false, 1 for true)
         * @hide
         * @deprecated Use
         * {@link android.provider.Telephony.SimInfo#COLUMN_ENHANCED_4G_MODE_ENABLED} instead.
         */
        @Deprecated
        @Readable
        public static final String ENHANCED_4G_MODE_ENABLED =
                Telephony.SimInfo.COLUMN_ENHANCED_4G_MODE_ENABLED;

        /**
         * Whether VT (Video Telephony over IMS) is enabled
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         * @deprecated Use {@link android.provider.Telephony.SimInfo#COLUMN_VT_IMS_ENABLED} instead.
         */
        @Deprecated
        @Readable
        public static final String VT_IMS_ENABLED = Telephony.SimInfo.COLUMN_VT_IMS_ENABLED;

        /**
         * Whether WFC is enabled
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         * @deprecated Use
         * {@link android.provider.Telephony.SimInfo#COLUMN_WFC_IMS_ENABLED} instead.
         */
        @Deprecated
        @Readable
        public static final String WFC_IMS_ENABLED = Telephony.SimInfo.COLUMN_WFC_IMS_ENABLED;

        /**
         * WFC mode on home/non-roaming network.
         * <p>
         * Type: int - 2=Wi-Fi preferred, 1=Cellular preferred, 0=Wi-Fi only
         *
         * @hide
         * @deprecated Use {@link android.provider.Telephony.SimInfo#COLUMN_WFC_IMS_MODE} instead.
         */
        @Deprecated
        @Readable
        public static final String WFC_IMS_MODE = Telephony.SimInfo.COLUMN_WFC_IMS_MODE;

        /**
         * WFC mode on roaming network.
         * <p>
         * Type: int - see {@link #WFC_IMS_MODE} for values
         *
         * @hide
         * @deprecated Use {@link android.provider.Telephony.SimInfo#COLUMN_WFC_IMS_ROAMING_MODE}
         * instead.
         */
        @Deprecated
        @Readable
        public static final String WFC_IMS_ROAMING_MODE =
                Telephony.SimInfo.COLUMN_WFC_IMS_ROAMING_MODE;

        /**
         * Whether WFC roaming is enabled
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         * @deprecated Use {@link android.provider.Telephony.SimInfo#COLUMN_WFC_IMS_ROAMING_ENABLED}
         * instead
         */
        @Deprecated
        @Readable
        public static final String WFC_IMS_ROAMING_ENABLED =
                Telephony.SimInfo.COLUMN_WFC_IMS_ROAMING_ENABLED;

        /**
         * Whether user can enable/disable LTE as a preferred network. A carrier might control
         * this via gservices, OMA-DM, carrier app, etc.
         * <p>
         * Type: int (0 for false, 1 for true)
         * @hide
         */
        @Readable
        public static final String LTE_SERVICE_FORCED = "lte_service_forced";


        /**
         * Specifies the behaviour the lid triggers when closed
         * <p>
         * See WindowManagerPolicy.WindowManagerFuncs
         * @hide
         */
        @Readable
        public static final String LID_BEHAVIOR = "lid_behavior";

        /**
         * Ephemeral app cookie max size in bytes.
         * <p>
         * Type: int
         * @hide
         */
        @Readable
        public static final String EPHEMERAL_COOKIE_MAX_SIZE_BYTES =
                "ephemeral_cookie_max_size_bytes";

        /**
         * Toggle to enable/disable the entire ephemeral feature. By default, ephemeral is
         * enabled. Set to zero to disable.
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        @Readable
        public static final String ENABLE_EPHEMERAL_FEATURE = "enable_ephemeral_feature";

        /**
         * Toggle to enable/disable dexopt for instant applications. The default is for dexopt
         * to be disabled.
         * <p>
         * Type: int (0 to disable, 1 to enable)
         *
         * @hide
         */
        @Readable
        public static final String INSTANT_APP_DEXOPT_ENABLED = "instant_app_dexopt_enabled";

        /**
         * The min period for caching installed instant apps in milliseconds.
         * <p>
         * Type: long
         * @hide
         */
        @Readable
        public static final String INSTALLED_INSTANT_APP_MIN_CACHE_PERIOD =
                "installed_instant_app_min_cache_period";

        /**
         * The max period for caching installed instant apps in milliseconds.
         * <p>
         * Type: long
         * @hide
         */
        @Readable
        public static final String INSTALLED_INSTANT_APP_MAX_CACHE_PERIOD =
                "installed_instant_app_max_cache_period";

        /**
         * The min period for caching uninstalled instant apps in milliseconds.
         * <p>
         * Type: long
         * @hide
         */
        @Readable
        public static final String UNINSTALLED_INSTANT_APP_MIN_CACHE_PERIOD =
                "uninstalled_instant_app_min_cache_period";

        /**
         * The max period for caching uninstalled instant apps in milliseconds.
         * <p>
         * Type: long
         * @hide
         */
        @Readable
        public static final String UNINSTALLED_INSTANT_APP_MAX_CACHE_PERIOD =
                "uninstalled_instant_app_max_cache_period";

        /**
         * The min period for caching unused static shared libs in milliseconds.
         * <p>
         * Type: long
         * @hide
         */
        @Readable
        public static final String UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD =
                "unused_static_shared_lib_min_cache_period";

        /**
         * Allows switching users when system user is locked.
         * <p>
         * Type: int
         * @hide
         */
        @Readable
        public static final String ALLOW_USER_SWITCHING_WHEN_SYSTEM_USER_LOCKED =
                "allow_user_switching_when_system_user_locked";

        /**
         * Boot count since the device starts running API level 24.
         * <p>
         * Type: int
         */
        @Readable
        public static final String BOOT_COUNT = "boot_count";

        /**
         * Whether the safe boot is disallowed.
         *
         * <p>This setting should have the identical value as the corresponding user restriction.
         * The purpose of the setting is to make the restriction available in early boot stages
         * before the user restrictions are loaded.
         * @hide
         */
        @Readable
        public static final String SAFE_BOOT_DISALLOWED = "safe_boot_disallowed";

        /**
         * Indicates whether this device is currently in retail demo mode. If true, the device
         * usage is severely limited.
         * <p>
         * Type: int (0 for false, 1 for true)
         *
         * @hide
         */
        @SystemApi
        @Readable
        public static final String DEVICE_DEMO_MODE = "device_demo_mode";

        /**
         * Indicates the maximum time that an app is blocked for the network rules to get updated.
         *
         * Type: long
         *
         * @hide
         */
        @Readable
        public static final String NETWORK_ACCESS_TIMEOUT_MS = "network_access_timeout_ms";

        /**
         * The reason for the settings database being downgraded. This is only for
         * troubleshooting purposes and its value should not be interpreted in any way.
         *
         * Type: string
         *
         * @hide
         */
        @Readable
        public static final String DATABASE_DOWNGRADE_REASON = "database_downgrade_reason";

        /**
         * The build id of when the settings database was first created (or re-created due it
         * being missing).
         *
         * Type: string
         *
         * @hide
         */
        @Readable
        public static final String DATABASE_CREATION_BUILDID = "database_creation_buildid";

        /**
         * Flag to toggle journal mode WAL on or off for the contacts database. WAL is enabled by
         * default. Set to 0 to disable.
         *
         * @hide
         */
        @Readable
        public static final String CONTACTS_DATABASE_WAL_ENABLED = "contacts_database_wal_enabled";

        /**
         * Flag to enable the link to location permissions in location setting. Set to 0 to disable.
         *
         * @hide
         */
        @Readable
        public static final String LOCATION_SETTINGS_LINK_TO_PERMISSIONS_ENABLED =
                "location_settings_link_to_permissions_enabled";

        /**
         * Flag to set the waiting time for removing invisible euicc profiles inside System >
         * Settings.
         * Type: long
         *
         * @hide
         */
        @Readable
        public static final String EUICC_REMOVING_INVISIBLE_PROFILES_TIMEOUT_MILLIS =
                "euicc_removing_invisible_profiles_timeout_millis";

        /**
         * Flag to set the waiting time for euicc factory reset inside System > Settings
         * Type: long
         *
         * @hide
         */
        @Readable
        public static final String EUICC_FACTORY_RESET_TIMEOUT_MILLIS =
                "euicc_factory_reset_timeout_millis";

        /**
         * Flag to set the waiting time for euicc slot switch.
         * Type: long
         *
         * @hide
         */
        public static final String EUICC_SWITCH_SLOT_TIMEOUT_MILLIS =
                "euicc_switch_slot_timeout_millis";

        /**
         * Flag to set the waiting time for enabling multi SIM slot.
         * Type: long
         *
         * @hide
         */
        public static final String ENABLE_MULTI_SLOT_TIMEOUT_MILLIS =
                "enable_multi_slot_timeout_millis";

        /**
         * Flag to set the timeout for when to refresh the storage settings cached data.
         * Type: long
         *
         * @hide
         */
        @Readable
        public static final String STORAGE_SETTINGS_CLOBBER_THRESHOLD =
                "storage_settings_clobber_threshold";

        /**
         * If set to 1, SettingsProvider's restoreAnyVersion="true" attribute will be ignored
         * and restoring to lower version of platform API will be skipped.
         *
         * @hide
         */
        @Readable
        public static final String OVERRIDE_SETTINGS_PROVIDER_RESTORE_ANY_VERSION =
                "override_settings_provider_restore_any_version";
        /**
         * Flag to toggle whether system services report attribution chains when they attribute
         * battery use via a {@code WorkSource}.
         *
         * Type: int (0 to disable, 1 to enable)
         *
         * @hide
         */
        @Readable
        public static final String CHAINED_BATTERY_ATTRIBUTION_ENABLED =
                "chained_battery_attribution_enabled";

        /**
         * Toggle to enable/disable the incremental ADB installation by default.
         * If not set, default adb installations are incremental; set to zero to use full ones.
         * Note: only ADB uses it, no usages in the Framework code.
         * <p>
         * Type: int (0 to disable, 1 to enable)
         *
         * @hide
         */
        @Readable
        public static final String ENABLE_ADB_INCREMENTAL_INSTALL_DEFAULT =
                "enable_adb_incremental_install_default";

        /**
         * The packages whitelisted to be run in autofill compatibility mode. The list
         * of packages is {@code ":"} colon delimited, and each entry has the name of the
         * package and an optional list of url bar resource ids (the list is delimited by
         * brackets&mdash{@code [} and {@code ]}&mdash and is also comma delimited).
         *
         * <p>For example, a list with 3 packages {@code p1}, {@code p2}, and {@code p3}, where
         * package {@code p1} have one id ({@code url_bar}, {@code p2} has none, and {@code p3 }
         * have 2 ids {@code url_foo} and {@code url_bas}) would be
         * {@code p1[url_bar]:p2:p3[url_foo,url_bas]}
         *
         * @hide
         * @deprecated Use {@link android.view.autofill.AutofillManager
         * #DEVICE_CONFIG_AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES} instead.
         */
        @Deprecated
        @SystemApi
        @Readable
        public static final String AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES =
                "autofill_compat_mode_allowed_packages";

        /**
         * Level of autofill logging.
         *
         * <p>Valid values are
         * {@link android.view.autofill.AutofillManager#NO_LOGGING},
         * {@link android.view.autofill.AutofillManager#FLAG_ADD_CLIENT_DEBUG}, or
         * {@link android.view.autofill.AutofillManager#FLAG_ADD_CLIENT_VERBOSE}.
         *
         * @hide
         */
        @Readable
        public static final String AUTOFILL_LOGGING_LEVEL = "autofill_logging_level";

        /**
         * Maximum number of partitions that can be allowed in an autofill session.
         *
         * @hide
         */
        @Readable
        public static final String AUTOFILL_MAX_PARTITIONS_SIZE = "autofill_max_partitions_size";

        /**
         * Maximum number of visible datasets in the Autofill dataset picker UI, or {@code 0} to use
         * the default value from resources.
         *
         * @hide
         */
        @Readable
        public static final String AUTOFILL_MAX_VISIBLE_DATASETS = "autofill_max_visible_datasets";

        /**
         * Toggle for enabling stylus handwriting. When enabled, current Input method receives
         * stylus {@link MotionEvent}s if an {@link Editor} is focused.
         *
         * @hide
         */
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String STYLUS_HANDWRITING_ENABLED = "stylus_handwriting_enabled";

        /**
         * Exemptions to the hidden API blacklist.
         *
         * @hide
         */
        @TestApi
        @Readable
        public static final String HIDDEN_API_BLACKLIST_EXEMPTIONS =
                "hidden_api_blacklist_exemptions";

        /**
         * Hidden API enforcement policy for apps.
         *
         * Values correspond to @{@link
         * android.content.pm.ApplicationInfo.HiddenApiEnforcementPolicy}
         *
         * @hide
         */
        @TestApi
        @Readable
        public static final String HIDDEN_API_POLICY = "hidden_api_policy";

        /**
         * Flag for forcing {@link com.android.server.compat.OverrideValidatorImpl}
         * to consider this a non-debuggable build.
         *
         * @hide
         */
        public static final String FORCE_NON_DEBUGGABLE_FINAL_BUILD_FOR_COMPAT =
                "force_non_debuggable_final_build_for_compat";


        /**
         * Current version of signed configuration applied.
         *
         * @hide
         */
        @Readable
        public static final String SIGNED_CONFIG_VERSION = "signed_config_version";

        /**
         * Timeout for a single {@link android.media.soundtrigger.SoundTriggerDetectionService}
         * operation (in ms).
         *
         * @hide
         */
        @Readable
        public static final String SOUND_TRIGGER_DETECTION_SERVICE_OP_TIMEOUT =
                "sound_trigger_detection_service_op_timeout";

        /**
         * Maximum number of {@link android.media.soundtrigger.SoundTriggerDetectionService}
         * operations per day.
         *
         * @hide
         */
        @Readable
        public static final String MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY =
                "max_sound_trigger_detection_service_ops_per_day";

        /**
         * Setting to determine if the Clockwork Home application is ready.
         *
         * <p>
         * Set to 1 when the Clockwork Home application has finished starting up.
         * </p>
         *
         * @hide
         */
        public static final String CLOCKWORK_HOME_READY = "clockwork_home_ready";

        /**
         * Indicates whether aware is available in the current location.
         * @hide
         */
        @Readable
        public static final String AWARE_ALLOWED = "aware_allowed";

        /**
         * Overrides internal R.integer.config_longPressOnPowerBehavior.
         * Allowable values detailed in frameworks/base/core/res/res/values/config.xml.
         * Used by PhoneWindowManager.
         * @hide
         */
        @Readable
        public static final String POWER_BUTTON_LONG_PRESS =
                "power_button_long_press";

        /**
         * Override internal R.integer.config_longPressOnPowerDurationMs. It determines the length
         * of power button press to be considered a long press in milliseconds.
         * Used by PhoneWindowManager.
         * @hide
         */
        @Readable
        public static final String POWER_BUTTON_LONG_PRESS_DURATION_MS =
                "power_button_long_press_duration_ms";

        /**
         * Overrides internal R.integer.config_veryLongPressOnPowerBehavior.
         * Allowable values detailed in frameworks/base/core/res/res/values/config.xml.
         * Used by PhoneWindowManager.
         * @hide
         */
        @Readable
        public static final String POWER_BUTTON_VERY_LONG_PRESS =
                "power_button_very_long_press";

        /**
         * Overrides internal R.integer.config_keyChordPowerVolumeUp.
         * Allowable values detailed in frameworks/base/core/res/res/values/config.xml.
         * Used by PhoneWindowManager.
         * @hide
         */
        @Readable
        public static final String KEY_CHORD_POWER_VOLUME_UP =
                "key_chord_power_volume_up";

        /**
         * Keyguard should be on the left hand side of the screen, for wide screen layouts.
         *
         * @hide
         */
        public static final int ONE_HANDED_KEYGUARD_SIDE_LEFT = 0;

        /**
         * Keyguard should be on the right hand side of the screen, for wide screen layouts.
         *
         * @hide
         */
        public static final int ONE_HANDED_KEYGUARD_SIDE_RIGHT = 1;
        /**
         * In one handed mode, which side the keyguard should be on. Allowable values are one of
         * the ONE_HANDED_KEYGUARD_SIDE_* constants.
         *
         * @hide
         */
        public static final String ONE_HANDED_KEYGUARD_SIDE = "one_handed_keyguard_side";

        /**
         * Global settings that shouldn't be persisted.
         *
         * @hide
         */
        public static final String[] TRANSIENT_SETTINGS = {
                CLOCKWORK_HOME_READY,
        };

        /**
         * Keys we no longer back up under the current schema, but want to continue to
         * process when restoring historical backup datasets.
         *
         * All settings in {@link LEGACY_RESTORE_SETTINGS} array *must* have a non-null validator,
         * otherwise they won't be restored.
         *
         * @hide
         */
        public static final String[] LEGACY_RESTORE_SETTINGS = {
        };

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        private static final ContentProviderHolder sProviderHolder =
                new ContentProviderHolder(CONTENT_URI);

        // Populated lazily, guarded by class object:
        @UnsupportedAppUsage
        private static final NameValueCache sNameValueCache = new NameValueCache(
                    CONTENT_URI,
                    CALL_METHOD_GET_GLOBAL,
                    CALL_METHOD_PUT_GLOBAL,
                    CALL_METHOD_DELETE_GLOBAL,
                    sProviderHolder,
                    Global.class);

        // Certain settings have been moved from global to the per-user secure namespace
        @UnsupportedAppUsage
        private static final HashSet<String> MOVED_TO_SECURE;
        static {
            MOVED_TO_SECURE = new HashSet<>(8);
            MOVED_TO_SECURE.add(Global.INSTALL_NON_MARKET_APPS);
            MOVED_TO_SECURE.add(Global.ZEN_DURATION);
            MOVED_TO_SECURE.add(Global.SHOW_ZEN_UPGRADE_NOTIFICATION);
            MOVED_TO_SECURE.add(Global.SHOW_ZEN_SETTINGS_SUGGESTION);
            MOVED_TO_SECURE.add(Global.ZEN_SETTINGS_UPDATED);
            MOVED_TO_SECURE.add(Global.ZEN_SETTINGS_SUGGESTION_VIEWED);
            MOVED_TO_SECURE.add(Global.CHARGING_SOUNDS_ENABLED);
            MOVED_TO_SECURE.add(Global.CHARGING_VIBRATION_ENABLED);
            MOVED_TO_SECURE.add(Global.NOTIFICATION_BUBBLES);
        }

        // Certain settings have been moved from global to the per-user system namespace
        private static final HashSet<String> MOVED_TO_SYSTEM;
        static {
            MOVED_TO_SYSTEM = new HashSet<>(1);
            MOVED_TO_SYSTEM.add(Global.APPLY_RAMPING_RINGER);
        }

        /** @hide */
        public static void getMovedToSecureSettings(Set<String> outKeySet) {
            outKeySet.addAll(MOVED_TO_SECURE);
        }

        /** @hide */
        public static void getMovedToSystemSettings(Set<String> outKeySet) {
            outKeySet.addAll(MOVED_TO_SYSTEM);
        }

        /** @hide */
        public static void clearProviderForTest() {
            sProviderHolder.clearProviderForTest();
            sNameValueCache.clearGenerationTrackerForTest();
        }

        /** @hide */
        public static void getPublicSettings(Set<String> allKeys, Set<String> readableKeys,
                ArrayMap<String, Integer> readableKeysWithMaxTargetSdk) {
            getPublicSettingsForClass(Global.class, allKeys, readableKeys,
                    readableKeysWithMaxTargetSdk);
        }

        /**
         * Look up a name in the database.
         * @param resolver to access the database with
         * @param name to look up in the table
         * @return the corresponding value, or null if not present
         */
        public static String getString(ContentResolver resolver, String name) {
            return getStringForUser(resolver, name, resolver.getUserId());
        }

        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static String getStringForUser(ContentResolver resolver, String name,
                int userHandle) {
            if (MOVED_TO_SECURE.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.Global"
                        + " to android.provider.Settings.Secure, returning read-only value.");
                return Secure.getStringForUser(resolver, name, userHandle);
            }
            if (MOVED_TO_SYSTEM.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.Global"
                        + " to android.provider.Settings.System, returning read-only value.");
                return System.getStringForUser(resolver, name, userHandle);
            }
            return sNameValueCache.getStringForUser(resolver, name, userHandle);
        }

        /**
         * Store a name/value pair into the database.
         * @param resolver to access the database with
         * @param name to store
         * @param value to associate with the name
         * @return true if the value was set, false on database errors
         */
        public static boolean putString(ContentResolver resolver,
                String name, String value) {
            return putStringForUser(resolver, name, value, null, false, resolver.getUserId(),
                    DEFAULT_OVERRIDEABLE_BY_RESTORE);
        }

        /**
         * Store a name/value pair into the database.
         *
         * @param resolver to access the database with
         * @param name to store
         * @param value to associate with the name
         * @param tag to associated with the setting.
         * @param makeDefault whether to make the value the default one.
         * @param overrideableByRestore whether restore can override this value
         * @return true if the value was set, false on database errors
         *
         * @hide
         */
        @RequiresPermission(Manifest.permission.MODIFY_SETTINGS_OVERRIDEABLE_BY_RESTORE)
        public static boolean putString(@NonNull ContentResolver resolver,
                @NonNull String name, @Nullable String value, @Nullable String tag,
                boolean makeDefault, boolean overrideableByRestore) {
            return putStringForUser(resolver, name, value, tag, makeDefault,
                    resolver.getUserId(), overrideableByRestore);
        }

        /**
         * Store a name/value pair into the database.
         * <p>
         * The method takes an optional tag to associate with the setting
         * which can be used to clear only settings made by your package and
         * associated with this tag by passing the tag to {@link
         * #resetToDefaults(ContentResolver, String)}. Anyone can override
         * the current tag. Also if another package changes the setting
         * then the tag will be set to the one specified in the set call
         * which can be null. Also any of the settings setters that do not
         * take a tag as an argument effectively clears the tag.
         * </p><p>
         * For example, if you set settings A and B with tags T1 and T2 and
         * another app changes setting A (potentially to the same value), it
         * can assign to it a tag T3 (note that now the package that changed
         * the setting is not yours). Now if you reset your changes for T1 and
         * T2 only setting B will be reset and A not (as it was changed by
         * another package) but since A did not change you are in the desired
         * initial state. Now if the other app changes the value of A (assuming
         * you registered an observer in the beginning) you would detect that
         * the setting was changed by another app and handle this appropriately
         * (ignore, set back to some value, etc).
         * </p><p>
         * Also the method takes an argument whether to make the value the
         * default for this setting. If the system already specified a default
         * value, then the one passed in here will <strong>not</strong>
         * be set as the default.
         * </p>
         *
         * @param resolver to access the database with.
         * @param name to store.
         * @param value to associate with the name.
         * @param tag to associated with the setting.
         * @param makeDefault whether to make the value the default one.
         * @return true if the value was set, false on database errors.
         *
         * @see #resetToDefaults(ContentResolver, String)
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
        public static boolean putString(@NonNull ContentResolver resolver,
                @NonNull String name, @Nullable String value, @Nullable String tag,
                boolean makeDefault) {
            return putStringForUser(resolver, name, value, tag, makeDefault,
                    resolver.getUserId(), DEFAULT_OVERRIDEABLE_BY_RESTORE);
        }

        /**
         * Reset the settings to their defaults. This would reset <strong>only</strong>
         * settings set by the caller's package. Think of it of a way to undo your own
         * changes to the secure settings. Passing in the optional tag will reset only
         * settings changed by your package and associated with this tag.
         *
         * @param resolver Handle to the content resolver.
         * @param tag Optional tag which should be associated with the settings to reset.
         *
         * @see #putString(ContentResolver, String, String, String, boolean)
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
        public static void resetToDefaults(@NonNull ContentResolver resolver,
                @Nullable String tag) {
            resetToDefaultsAsUser(resolver, tag, RESET_MODE_PACKAGE_DEFAULTS,
                    resolver.getUserId());
        }

        /**
         * Reset the settings to their defaults for a given user with a specific mode. The
         * optional tag argument is valid only for {@link #RESET_MODE_PACKAGE_DEFAULTS}
         * allowing resetting the settings made by a package and associated with the tag.
         *
         * @param resolver Handle to the content resolver.
         * @param tag Optional tag which should be associated with the settings to reset.
         * @param mode The reset mode.
         * @param userHandle The user for which to reset to defaults.
         *
         * @see #RESET_MODE_PACKAGE_DEFAULTS
         * @see #RESET_MODE_UNTRUSTED_DEFAULTS
         * @see #RESET_MODE_UNTRUSTED_CHANGES
         * @see #RESET_MODE_TRUSTED_DEFAULTS
         *
         * @hide
         */
        public static void resetToDefaultsAsUser(@NonNull ContentResolver resolver,
                @Nullable String tag, @ResetMode int mode, @IntRange(from = 0) int userHandle) {
            try {
                Bundle arg = new Bundle();
                arg.putInt(CALL_METHOD_USER_KEY, userHandle);
                if (tag != null) {
                    arg.putString(CALL_METHOD_TAG_KEY, tag);
                }
                arg.putInt(CALL_METHOD_RESET_MODE_KEY, mode);
                IContentProvider cp = sProviderHolder.getProvider(resolver);
                cp.call(resolver.getAttributionSource(),
                        sProviderHolder.mUri.getAuthority(), CALL_METHOD_RESET_GLOBAL, null, arg);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't reset do defaults for " + CONTENT_URI, e);
            }
        }

        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static boolean putStringForUser(ContentResolver resolver,
                String name, String value, int userHandle) {
            return putStringForUser(resolver, name, value, null, false, userHandle,
                    DEFAULT_OVERRIDEABLE_BY_RESTORE);
        }

        /** @hide */
        public static boolean putStringForUser(@NonNull ContentResolver resolver,
                @NonNull String name, @Nullable String value, @Nullable String tag,
                boolean makeDefault, @UserIdInt int userHandle, boolean overrideableByRestore) {
            if (LOCAL_LOGV) {
                Log.v(TAG, "Global.putString(name=" + name + ", value=" + value
                        + " for " + userHandle);
            }
            // Global and Secure have the same access policy so we can forward writes
            if (MOVED_TO_SECURE.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.Global"
                        + " to android.provider.Settings.Secure, value is unchanged.");
                return Secure.putStringForUser(resolver, name, value, tag,
                        makeDefault, userHandle, overrideableByRestore);
            }
            // Global and System have the same access policy so we can forward writes
            if (MOVED_TO_SYSTEM.contains(name)) {
                Log.w(TAG, "Setting " + name + " has moved from android.provider.Settings.Global"
                        + " to android.provider.Settings.System, value is unchanged.");
                return System.putStringForUser(resolver, name, value, tag,
                        makeDefault, userHandle, overrideableByRestore);
            }
            return sNameValueCache.putStringForUser(resolver, name, value, tag,
                    makeDefault, userHandle, overrideableByRestore);
        }

        /**
         * Construct the content URI for a particular name/value pair,
         * useful for monitoring changes with a ContentObserver.
         * @param name to look up in the table
         * @return the corresponding content URI, or null if not present
         */
        public static Uri getUriFor(String name) {
            return getUriFor(CONTENT_URI, name);
        }

        /**
         * Convenience function for retrieving a single secure settings value
         * as an integer.  Note that internally setting values are always
         * stored as strings; this function converts the string to an integer
         * for you.  The default value will be returned if the setting is
         * not defined or not an integer.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         * @param def Value to return if the setting is not defined.
         *
         * @return The setting's current value, or 'def' if it is not defined
         * or not a valid integer.
         */
        public static int getInt(ContentResolver cr, String name, int def) {
            String v = getString(cr, name);
            return parseIntSettingWithDefault(v, def);
        }

        /**
         * Convenience function for retrieving a single secure settings value
         * as an integer.  Note that internally setting values are always
         * stored as strings; this function converts the string to an integer
         * for you.
         * <p>
         * This version does not take a default value.  If the setting has not
         * been set, or the string value is not a number,
         * it throws {@link SettingNotFoundException}.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         *
         * @throws SettingNotFoundException Thrown if a setting by the given
         * name can't be found or the setting value is not an integer.
         *
         * @return The setting's current value.
         */
        public static int getInt(ContentResolver cr, String name)
                throws SettingNotFoundException {
            String v = getString(cr, name);
            return parseIntSetting(v, name);
        }

        /**
         * Convenience function for updating a single settings value as an
         * integer. This will either create a new entry in the table if the
         * given name does not exist, or modify the value of the existing row
         * with that name.  Note that internally setting values are always
         * stored as strings, so this function converts the given value to a
         * string before storing it.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to modify.
         * @param value The new value for the setting.
         * @return true if the value was set, false on database errors
         */
        public static boolean putInt(ContentResolver cr, String name, int value) {
            return putString(cr, name, Integer.toString(value));
        }

        /**
         * Convenience function for retrieving a single secure settings value
         * as a {@code long}.  Note that internally setting values are always
         * stored as strings; this function converts the string to a {@code long}
         * for you.  The default value will be returned if the setting is
         * not defined or not a {@code long}.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         * @param def Value to return if the setting is not defined.
         *
         * @return The setting's current value, or 'def' if it is not defined
         * or not a valid {@code long}.
         */
        public static long getLong(ContentResolver cr, String name, long def) {
            String v = getString(cr, name);
            return parseLongSettingWithDefault(v, def);
        }

        /**
         * Convenience function for retrieving a single secure settings value
         * as a {@code long}.  Note that internally setting values are always
         * stored as strings; this function converts the string to a {@code long}
         * for you.
         * <p>
         * This version does not take a default value.  If the setting has not
         * been set, or the string value is not a number,
         * it throws {@link SettingNotFoundException}.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         *
         * @return The setting's current value.
         * @throws SettingNotFoundException Thrown if a setting by the given
         * name can't be found or the setting value is not an integer.
         */
        public static long getLong(ContentResolver cr, String name)
                throws SettingNotFoundException {
            String v = getString(cr, name);
            return parseLongSetting(v, name);
        }

        /**
         * Convenience function for updating a secure settings value as a long
         * integer. This will either create a new entry in the table if the
         * given name does not exist, or modify the value of the existing row
         * with that name.  Note that internally setting values are always
         * stored as strings, so this function converts the given value to a
         * string before storing it.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to modify.
         * @param value The new value for the setting.
         * @return true if the value was set, false on database errors
         */
        public static boolean putLong(ContentResolver cr, String name, long value) {
            return putString(cr, name, Long.toString(value));
        }

        /**
         * Convenience function for retrieving a single secure settings value
         * as a floating point number.  Note that internally setting values are
         * always stored as strings; this function converts the string to an
         * float for you. The default value will be returned if the setting
         * is not defined or not a valid float.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         * @param def Value to return if the setting is not defined.
         *
         * @return The setting's current value, or 'def' if it is not defined
         * or not a valid float.
         */
        public static float getFloat(ContentResolver cr, String name, float def) {
            String v = getString(cr, name);
            return parseFloatSettingWithDefault(v, def);
        }

        /**
         * Convenience function for retrieving a single secure settings value
         * as a float.  Note that internally setting values are always
         * stored as strings; this function converts the string to a float
         * for you.
         * <p>
         * This version does not take a default value.  If the setting has not
         * been set, or the string value is not a number,
         * it throws {@link SettingNotFoundException}.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to retrieve.
         *
         * @throws SettingNotFoundException Thrown if a setting by the given
         * name can't be found or the setting value is not a float.
         *
         * @return The setting's current value.
         */
        public static float getFloat(ContentResolver cr, String name)
                throws SettingNotFoundException {
            String v = getString(cr, name);
            return parseFloatSetting(v, name);
        }

        /**
         * Convenience function for updating a single settings value as a
         * floating point number. This will either create a new entry in the
         * table if the given name does not exist, or modify the value of the
         * existing row with that name.  Note that internally setting values
         * are always stored as strings, so this function converts the given
         * value to a string before storing it.
         *
         * @param cr The ContentResolver to access.
         * @param name The name of the setting to modify.
         * @param value The new value for the setting.
         * @return true if the value was set, false on database errors
         */
        public static boolean putFloat(ContentResolver cr, String name, float value) {
            return putString(cr, name, Float.toString(value));
        }

        /**
          * Subscription Id to be used for voice call on a multi sim device.
          * @hide
          */
        @Readable
        public static final String MULTI_SIM_VOICE_CALL_SUBSCRIPTION = "multi_sim_voice_call";

        /**
          * Used to provide option to user to select subscription during dial.
          * The supported values are 0 = disable or 1 = enable prompt.
          * @hide
          */
        @UnsupportedAppUsage
        @Readable
        public static final String MULTI_SIM_VOICE_PROMPT = "multi_sim_voice_prompt";

        /**
          * Subscription Id to be used for data call on a multi sim device.
          * @hide
          */
        @Readable
        public static final String MULTI_SIM_DATA_CALL_SUBSCRIPTION = "multi_sim_data_call";

        /**
          * Subscription Id to be used for SMS on a multi sim device.
          * @hide
          */
        @Readable
        public static final String MULTI_SIM_SMS_SUBSCRIPTION = "multi_sim_sms";

        /**
          * Used to provide option to user to select subscription during send SMS.
          * The value 1 - enable, 0 - disable
          * @hide
          */
        @Readable
        public static final String MULTI_SIM_SMS_PROMPT = "multi_sim_sms_prompt";

        /** User preferred subscriptions setting.
          * This holds the details of the user selected subscription from the card and
          * the activation status. Each settings string have the comma separated values
          * iccId,appType,appId,activationStatus,3gppIndex,3gpp2Index
          * @hide
         */
        @UnsupportedAppUsage
        @Readable
        public static final String[] MULTI_SIM_USER_PREFERRED_SUBS = {"user_preferred_sub1",
                "user_preferred_sub2","user_preferred_sub3"};

        /**
         * Which subscription is enabled for a physical slot.
         * @hide
         */
        @Readable
        public static final String ENABLED_SUBSCRIPTION_FOR_SLOT = "enabled_subscription_for_slot";

        /**
         * Whether corresponding logical modem is enabled for a physical slot.
         * The value 1 - enable, 0 - disable
         * @hide
         */
        @Readable
        public static final String MODEM_STACK_ENABLED_FOR_SLOT = "modem_stack_enabled_for_slot";

        /**
         * Whether to enable new contacts aggregator or not.
         * The value 1 - enable, 0 - disable
         * @hide
         */
        @Readable
        public static final String NEW_CONTACT_AGGREGATOR = "new_contact_aggregator";

        /**
         * Whether to enable contacts metadata syncing or not
         * The value 1 - enable, 0 - disable
         *
         * @removed
         */
        @Deprecated
        @Readable
        public static final String CONTACT_METADATA_SYNC = "contact_metadata_sync";

        /**
         * Whether to enable contacts metadata syncing or not
         * The value 1 - enable, 0 - disable
         */
        @Readable
        public static final String CONTACT_METADATA_SYNC_ENABLED = "contact_metadata_sync_enabled";

        /**
         * Whether to enable cellular on boot.
         * The value 1 - enable, 0 - disable
         * @hide
         */
        @Readable
        public static final String ENABLE_CELLULAR_ON_BOOT = "enable_cellular_on_boot";

        /**
         * The maximum allowed notification enqueue rate in Hertz.
         *
         * Should be a float, and includes updates only.
         * @hide
         */
        @Readable
        public static final String MAX_NOTIFICATION_ENQUEUE_RATE = "max_notification_enqueue_rate";

        /**
         * Displays toasts when an app posts a notification that does not specify a valid channel.
         *
         * The value 1 - enable, 0 - disable
         * @hide
         */
        @Readable
        public static final String SHOW_NOTIFICATION_CHANNEL_WARNINGS =
                "show_notification_channel_warnings";

        /**
         * Whether cell is enabled/disabled
         * @hide
         */
        @Readable
        public static final String CELL_ON = "cell_on";

        /**
         * Global settings which can be accessed by instant apps.
         * @hide
         */
        public static final Set<String> INSTANT_APP_SETTINGS = new ArraySet<>();
        static {
            INSTANT_APP_SETTINGS.add(WAIT_FOR_DEBUGGER);
            INSTANT_APP_SETTINGS.add(DEVICE_PROVISIONED);
            INSTANT_APP_SETTINGS.add(DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES);
            INSTANT_APP_SETTINGS.add(DEVELOPMENT_FORCE_RTL);
            INSTANT_APP_SETTINGS.add(EPHEMERAL_COOKIE_MAX_SIZE_BYTES);
            INSTANT_APP_SETTINGS.add(AIRPLANE_MODE_ON);
            INSTANT_APP_SETTINGS.add(WINDOW_ANIMATION_SCALE);
            INSTANT_APP_SETTINGS.add(TRANSITION_ANIMATION_SCALE);
            INSTANT_APP_SETTINGS.add(ANIMATOR_DURATION_SCALE);
            INSTANT_APP_SETTINGS.add(DEBUG_VIEW_ATTRIBUTES);
            INSTANT_APP_SETTINGS.add(DEBUG_VIEW_ATTRIBUTES_APPLICATION_PACKAGE);
            INSTANT_APP_SETTINGS.add(WTF_IS_FATAL);
            INSTANT_APP_SETTINGS.add(SEND_ACTION_APP_ERROR);
            INSTANT_APP_SETTINGS.add(ZEN_MODE);
        }

        /**
         * Whether to show the high temperature warning notification.
         * @hide
         */
        @Readable
        public static final String SHOW_TEMPERATURE_WARNING = "show_temperature_warning";

        /**
         * Whether to show the usb high temperature alarm notification.
         * @hide
         */
        @Readable
        public static final String SHOW_USB_TEMPERATURE_ALARM = "show_usb_temperature_alarm";

        /**
         * Temperature at which the high temperature warning notification should be shown.
         * @hide
         */
        @Readable
        public static final String WARNING_TEMPERATURE = "warning_temperature";

        /**
         * Whether the diskstats logging task is enabled/disabled.
         * @hide
         */
        @Readable
        public static final String ENABLE_DISKSTATS_LOGGING = "enable_diskstats_logging";

        /**
         * Whether the cache quota calculation task is enabled/disabled.
         * @hide
         */
        @Readable
        public static final String ENABLE_CACHE_QUOTA_CALCULATION =
                "enable_cache_quota_calculation";

        /**
         * Whether the Deletion Helper no threshold toggle is available.
         * @hide
         */
        @Readable
        public static final String ENABLE_DELETION_HELPER_NO_THRESHOLD_TOGGLE =
                "enable_deletion_helper_no_threshold_toggle";

        /**
         * The list of snooze options for notifications
         * This is encoded as a key=value list, separated by commas. Ex:
         *
         * "default=60,options_array=15:30:60:120"
         *
         * The following keys are supported:
         *
         * <pre>
         * default               (int)
         * options_array         (int[])
         * </pre>
         *
         * All delays in integer minutes. Array order is respected.
         * Options will be used in order up to the maximum allowed by the UI.
         * @hide
         */
        @Readable
        public static final String NOTIFICATION_SNOOZE_OPTIONS =
                "notification_snooze_options";

        /**
         * When enabled, notifications the notification assistant service has modified will show an
         * indicator. When tapped, this indicator will describe the adjustment made and solicit
         * feedback. This flag will also add a "automatic" option to the long press menu.
         *
         * The value 1 - enable, 0 - disable
         * @hide
         */
        public static final String NOTIFICATION_FEEDBACK_ENABLED = "notification_feedback_enabled";

        /**
         * Settings key for the ratio of notification dismissals to notification views - one of the
         * criteria for showing the notification blocking helper.
         *
         * <p>The value is a float ranging from 0.0 to 1.0 (the closer to 0.0, the more intrusive
         * the blocking helper will be).
         *
         * @hide
         */
        @Readable
        public static final String BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT =
                "blocking_helper_dismiss_to_view_ratio";

        /**
         * Settings key for the longest streak of dismissals  - one of the criteria for showing the
         * notification blocking helper.
         *
         * <p>The value is an integer greater than 0.
         *
         * @hide
         */
        @Readable
        public static final String BLOCKING_HELPER_STREAK_LIMIT = "blocking_helper_streak_limit";

        /**
         * Configuration flags for SQLite Compatibility WAL. Encoded as a key-value list, separated
         * by commas. E.g.: compatibility_wal_supported=true, wal_syncmode=OFF
         *
         * Supported keys:<br/>
         * <li>
         * <ul> {@code legacy_compatibility_wal_enabled} : A {code boolean} flag that determines
         * whether or not "compatibility WAL" mode is enabled by default. This is a legacy flag
         * and is honoured on Android Q and higher. This flag will be removed in a future release.
         * </ul>
         * <ul> {@code wal_syncmode} : A {@code String} representing the synchronization mode to use
         * when WAL is enabled, either via {@code legacy_compatibility_wal_enabled} or using the
         * obsolete {@code compatibility_wal_supported} flag.
         * </ul>
         * <ul> {@code truncate_size} : A {@code int} flag that specifies the truncate size of the
         * WAL journal.
         * </ul>
         * <ul> {@code compatibility_wal_supported} : A {code boolean} flag that specifies whether
         * the legacy "compatibility WAL" mode is enabled by default. This flag is obsolete and is
         * only supported on Android Pie.
         * </ul>
         * </li>
         *
         * @hide
         */
        @Readable
        public static final String SQLITE_COMPATIBILITY_WAL_FLAGS =
                "sqlite_compatibility_wal_flags";

        /**
         * Enable GNSS Raw Measurements Full Tracking?
         * 0 = no
         * 1 = yes
         * @hide
         */
        @Readable
        public static final String ENABLE_GNSS_RAW_MEAS_FULL_TRACKING =
                "enable_gnss_raw_meas_full_tracking";

        /**
         * Whether the notification should be ongoing (persistent) when a carrier app install is
         * required.
         *
         * The value is a boolean (1 or 0).
         * @hide
         */
        @SystemApi
        @Readable
        public static final String INSTALL_CARRIER_APP_NOTIFICATION_PERSISTENT =
                "install_carrier_app_notification_persistent";

        /**
         * The amount of time (ms) to hide the install carrier app notification after the user has
         * ignored it. After this time passes, the notification will be shown again
         *
         * The value is a long
         * @hide
         */
        @SystemApi
        @Readable
        public static final String INSTALL_CARRIER_APP_NOTIFICATION_SLEEP_MILLIS =
                "install_carrier_app_notification_sleep_millis";

        /**
         * Whether we've enabled zram on this device. Takes effect on
         * reboot. The value "1" enables zram; "0" disables it, and
         * everything else is unspecified.
         * @hide
         */
        @Readable
        public static final String ZRAM_ENABLED =
                "zram_enabled";

        /**
         * Whether the app freezer is enabled on this device.
         * The value of "enabled" enables the app freezer, "disabled" disables it and
         * "device_default" will let the system decide whether to enable the freezer or not
         * @hide
         */
        @Readable
        public static final String CACHED_APPS_FREEZER_ENABLED = "cached_apps_freezer";

        /**
         * Configuration flags for smart replies in notifications.
         * This is encoded as a key=value list, separated by commas. Ex:
         *
         * "enabled=1,max_squeeze_remeasure_count=3"
         *
         * The following keys are supported:
         *
         * <pre>
         * enabled                           (boolean)
         * requires_targeting_p              (boolean)
         * max_squeeze_remeasure_attempts    (int)
         * edit_choices_before_sending       (boolean)
         * show_in_heads_up                  (boolean)
         * min_num_system_generated_replies  (int)
         * max_num_actions                   (int)
         * </pre>
         * @see com.android.systemui.statusbar.policy.SmartReplyConstants
         * @hide
         */
        @Readable
        public static final String SMART_REPLIES_IN_NOTIFICATIONS_FLAGS =
                "smart_replies_in_notifications_flags";

        /**
         * Configuration flags for the automatic generation of smart replies and smart actions in
         * notifications. This is encoded as a key=value list, separated by commas. Ex:
         * "generate_replies=false,generate_actions=true".
         *
         * The following keys are supported:
         *
         * <pre>
         * generate_replies                 (boolean)
         * generate_actions                 (boolean)
         * </pre>
         * @hide
         */
        @Readable
        public static final String SMART_SUGGESTIONS_IN_NOTIFICATIONS_FLAGS =
                "smart_suggestions_in_notifications_flags";

        /**
         * If nonzero, crashes in foreground processes will bring up a dialog.
         * Otherwise, the process will be silently killed.
         * @hide
         */
        @TestApi
        @Readable
        @SuppressLint("NoSettingsProvider")
        public static final String SHOW_FIRST_CRASH_DIALOG = "show_first_crash_dialog";

        /**
         * If nonzero, crash dialogs will show an option to restart the app.
         * @hide
         */
        @Readable
        public static final String SHOW_RESTART_IN_CRASH_DIALOG = "show_restart_in_crash_dialog";

        /**
         * If nonzero, crash dialogs will show an option to mute all future crash dialogs for
         * this app.
         * @hide
         */
        @Readable
        public static final String SHOW_MUTE_IN_CRASH_DIALOG = "show_mute_in_crash_dialog";


        /**
         * If nonzero, will show the zen upgrade notification when the user toggles DND on/off.
         * @hide
         * @deprecated - Use {@link android.provider.Settings.Secure#SHOW_ZEN_UPGRADE_NOTIFICATION}
         */
        @Deprecated
        public static final String SHOW_ZEN_UPGRADE_NOTIFICATION = "show_zen_upgrade_notification";

        /**
         * If nonzero, will show the zen update settings suggestion.
         * @hide
         * @deprecated - Use {@link android.provider.Settings.Secure#SHOW_ZEN_SETTINGS_SUGGESTION}
         */
        @Deprecated
        public static final String SHOW_ZEN_SETTINGS_SUGGESTION = "show_zen_settings_suggestion";

        /**
         * If nonzero, zen has not been updated to reflect new changes.
         * @deprecated - Use {@link android.provider.Settings.Secure#ZEN_SETTINGS_UPDATED}
         * @hide
         */
        @Deprecated
        public static final String ZEN_SETTINGS_UPDATED = "zen_settings_updated";

        /**
         * If nonzero, zen setting suggestion has been viewed by user
         * @hide
         * @deprecated - Use {@link android.provider.Settings.Secure#ZEN_SETTINGS_SUGGESTION_VIEWED}
         */
        @Deprecated
        public static final String ZEN_SETTINGS_SUGGESTION_VIEWED =
                "zen_settings_suggestion_viewed";

        /**
         * Backup and restore agent timeout parameters.
         * These parameters are represented by a comma-delimited key-value list.
         *
         * The following strings are supported as keys:
         * <pre>
         *     kv_backup_agent_timeout_millis         (long)
         *     full_backup_agent_timeout_millis       (long)
         *     shared_backup_agent_timeout_millis     (long)
         *     restore_agent_timeout_millis           (long)
         *     restore_agent_finished_timeout_millis  (long)
         * </pre>
         *
         * They map to milliseconds represented as longs.
         *
         * Ex: "kv_backup_agent_timeout_millis=30000,full_backup_agent_timeout_millis=300000"
         *
         * @hide
         */
        @Readable
        public static final String BACKUP_AGENT_TIMEOUT_PARAMETERS =
                "backup_agent_timeout_parameters";

        /**
         * Blocklist of GNSS satellites.
         *
         * This is a list of integers separated by commas to represent pairs of (constellation,
         * svid). Thus, the number of integers should be even.
         *
         * E.g.: "3,0,5,24" denotes (constellation=3, svid=0) and (constellation=5, svid=24) are
         * blocklisted. Note that svid=0 denotes all svids in the constellation are blocklisted.
         *
         * @hide
         */
        public static final String GNSS_SATELLITE_BLOCKLIST = "gnss_satellite_blocklist";

        /**
         * Duration of updates in millisecond for GNSS location request from HAL to framework.
         *
         * If zero, the GNSS location request feature is disabled.
         *
         * The value is a non-negative long.
         *
         * @hide
         */
        @Readable
        public static final String GNSS_HAL_LOCATION_REQUEST_DURATION_MILLIS =
                "gnss_hal_location_request_duration_millis";

        /**
         * Binder call stats settings.
         *
         * The following strings are supported as keys:
         * <pre>
         *     enabled              (boolean)
         *     detailed_tracking    (boolean)
         *     upload_data          (boolean)
         *     sampling_interval    (int)
         * </pre>
         *
         * @hide
         */
        @Readable
        public static final String BINDER_CALLS_STATS = "binder_calls_stats";

        /**
         * Looper stats settings.
         *
         * The following strings are supported as keys:
         * <pre>
         *     enabled              (boolean)
         *     sampling_interval    (int)
         * </pre>
         *
         * @hide
         */
        @Readable
        public static final String LOOPER_STATS = "looper_stats";

        /**
         * Settings for collecting statistics on CPU usage per thread
         *
         * The following strings are supported as keys:
         * <pre>
         *     num_buckets          (int)
         *     collected_uids       (string)
         *     minimum_total_cpu_usage_millis (int)
         * </pre>
         *
         * @hide
         */
        @Readable
        public static final String KERNEL_CPU_THREAD_READER = "kernel_cpu_thread_reader";

        /**
         * Whether we've enabled native flags health check on this device. Takes effect on
         * reboot. The value "1" enables native flags health check; otherwise it's disabled.
         * @hide
         */
        @Readable
        public static final String NATIVE_FLAGS_HEALTH_CHECK_ENABLED =
                "native_flags_health_check_enabled";

        /**
         * Parameter for {@link #APPOP_HISTORY_PARAMETERS} that controls the mode
         * in which the historical registry operates.
         *
         * @hide
         */
        @Readable
        public static final String APPOP_HISTORY_MODE = "mode";

        /**
         * Parameter for {@link #APPOP_HISTORY_PARAMETERS} that controls how long
         * is the interval between snapshots in the base case i.e. the most recent
         * part of the history.
         *
         * @hide
         */
        @Readable
        public static final String APPOP_HISTORY_BASE_INTERVAL_MILLIS = "baseIntervalMillis";

        /**
         * Parameter for {@link #APPOP_HISTORY_PARAMETERS} that controls the base
         * for the logarithmic step when building app op history.
         *
         * @hide
         */
        @Readable
        public static final String APPOP_HISTORY_INTERVAL_MULTIPLIER = "intervalMultiplier";

        /**
         * Appop history parameters. These parameters are represented by
         * a comma-delimited key-value list.
         *
         * The following strings are supported as keys:
         * <pre>
         *     mode                  (int)
         *     baseIntervalMillis    (long)
         *     intervalMultiplier    (int)
         * </pre>
         *
         * Ex: "mode=HISTORICAL_MODE_ENABLED_ACTIVE,baseIntervalMillis=1000,intervalMultiplier=10"
         *
         * @see #APPOP_HISTORY_MODE
         * @see #APPOP_HISTORY_BASE_INTERVAL_MILLIS
         * @see #APPOP_HISTORY_INTERVAL_MULTIPLIER
         *
         * @hide
         */
        @Readable
        public static final String APPOP_HISTORY_PARAMETERS =
                "appop_history_parameters";

        /**
         * Auto revoke parameters. These parameters are represented by
         * a comma-delimited key-value list.
         *
         * <pre>
         *     enabledForPreRApps    (bolean)
         *     unusedThresholdMs     (long)
         *     checkFrequencyMs      (long)
         * </pre>
         *
         * Ex: "enabledForPreRApps=false,unusedThresholdMs=7776000000,checkFrequencyMs=1296000000"
         *
         * @hide
         */
        @Readable
        public static final String AUTO_REVOKE_PARAMETERS =
                "auto_revoke_parameters";

        /**
         * Delay for sending ACTION_CHARGING after device is plugged in.
         * This is used as an override for constants defined in BatteryStatsImpl for
         * ease of experimentation.
         *
         * @see com.android.internal.os.BatteryStatsImpl.Constants.KEY_BATTERY_CHARGED_DELAY_MS
         * @hide
         */
        @Readable
        public static final String BATTERY_CHARGING_STATE_UPDATE_DELAY =
                "battery_charging_state_update_delay";

        /**
         * A serialized string of params that will be loaded into a text classifier action model.
         *
         * @hide
         */
        @Readable
        public static final String TEXT_CLASSIFIER_ACTION_MODEL_PARAMS =
                "text_classifier_action_model_params";

        /**
         * The amount of time to suppress "power-off" from the power button after the device has
         * woken due to a gesture (lifting the phone).  Since users have learned to hit the power
         * button immediately when lifting their device, it can cause the device to turn off if a
         * gesture has just woken the device. This value tells us the milliseconds to wait after
         * a gesture before "power-off" via power-button is functional again. A value of 0 is no
         * delay, and reverts to the old behavior.
         *
         * @hide
         */
        @Readable
        public static final String POWER_BUTTON_SUPPRESSION_DELAY_AFTER_GESTURE_WAKE =
                "power_button_suppression_delay_after_gesture_wake";

        /**
         * The usage amount of advanced battery. The value is 0~100.
         *
         * @hide
         */
        @Readable
        public static final String ADVANCED_BATTERY_USAGE_AMOUNT = "advanced_battery_usage_amount";

        /**
         * For 5G NSA capable devices, determines whether NR tracking indications are on
         * when the screen is off.
         *
         * Values are:
         * 0: off - All 5G NSA tracking indications are off when the screen is off.
         * 1: extended - All 5G NSA tracking indications are on when the screen is off as long as
         *    the device is camped on 5G NSA (5G icon is showing in status bar).
         *    If the device is not camped on 5G NSA, tracking indications are off.
         * 2: always on - All 5G NSA tracking indications are on whether the screen is on or off.
         * @hide
         */
        @Readable
        public static final String NR_NSA_TRACKING_SCREEN_OFF_MODE =
                "nr_nsa_tracking_screen_off_mode";

        /**
         * Whether to show People Space.
         * Values are:
         * 0: Disabled (default)
         * 1: Enabled
         * @hide
         */
        public static final String SHOW_PEOPLE_SPACE = "show_people_space";

        /**
         * Which types of conversation(s) to show in People Space.
         * Values are:
         * 0: Single user-selected conversation (default)
         * 1: Priority conversations only
         * 2: All conversations
         * @hide
         */
        public static final String PEOPLE_SPACE_CONVERSATION_TYPE =
                "people_space_conversation_type";

        /**
         * Whether to show new notification dismissal.
         * Values are:
         * 0: Disabled
         * 1: Enabled
         * @hide
         */
        public static final String SHOW_NEW_NOTIF_DISMISS = "show_new_notif_dismiss";

        /**
         * Block untrusted touches mode.
         *
         * Can be one of:
         * <ul>
         *      <li>0 = {@link BlockUntrustedTouchesMode#DISABLED}: Feature is off.
         *      <li>1 = {@link BlockUntrustedTouchesMode#PERMISSIVE}: Untrusted touches are flagged
         *          but not blocked
         *      <li>2 = {@link BlockUntrustedTouchesMode#BLOCK}: Untrusted touches are blocked
         * </ul>
         *
         * @hide
         */
        @Readable
        public static final String BLOCK_UNTRUSTED_TOUCHES_MODE = "block_untrusted_touches";

        /**
         * The maximum allowed obscuring opacity by UID to propagate touches.
         *
         * For certain window types (eg. SAWs), the decision of honoring {@link LayoutParams
         * #FLAG_NOT_TOUCHABLE} or not depends on the combined obscuring opacity of the windows
         * above the touch-consuming window.
         *
         * For a certain UID:
         * <ul>
         *     <li>If it's the same as the UID of the touch-consuming window, allow it to propagate
         *     the touch.
         *     <li>Otherwise take all its windows of eligible window types above the touch-consuming
         *     window, compute their combined obscuring opacity considering that {@code
         *     opacity(A, B) = 1 - (1 - opacity(A))*(1 - opacity(B))}. If the computed value is
         *     lesser than or equal to this setting and there are no other windows preventing the
         *     touch, allow the UID to propagate the touch.
         * </ul>
         *
         * @see android.hardware.input.InputManager#getMaximumObscuringOpacityForTouch()
         * @see android.hardware.input.InputManager#setMaximumObscuringOpacityForTouch(float)
         *
         * @hide
         */
        @Readable
        public static final String MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH =
                "maximum_obscuring_opacity_for_touch";

        /**
         * Used to enable / disable the Restricted Networking Mode in which network access is
         * restricted to apps holding the CONNECTIVITY_USE_RESTRICTED_NETWORKS permission.
         *
         * Values are:
         * 0: disabled
         * 1: enabled
         * @hide
         */
        public static final String RESTRICTED_NETWORKING_MODE = "restricted_networking_mode";

        /**
         * Setting indicating whether Low Power Standby is enabled, if supported.
         *
         * Values are:
         * 0: disabled
         * 1: enabled
         *
         * @hide
         */
        public static final String LOW_POWER_STANDBY_ENABLED = "low_power_standby_enabled";

        /**
         * Setting indicating whether Low Power Standby is allowed to be active during doze
         * maintenance mode.
         *
         * Values are:
         * 0: Low Power Standby will be disabled during doze maintenance mode
         * 1: Low Power Standby can be active during doze maintenance mode
         *
         * @hide
         */
        public static final String LOW_POWER_STANDBY_ACTIVE_DURING_MAINTENANCE =
                "low_power_standby_active_during_maintenance";

        /**
         * Timeout for the system server watchdog.
         *
         * @see {@link com.android.server.Watchdog}.
         *
         * @hide
         */
        public static final String WATCHDOG_TIMEOUT_MILLIS =
                "system_server_watchdog_timeout_ms";

        /**
         * Whether to enable managed device provisioning via the role holder.
         *
         * @hide
         */
        public static final String MANAGED_PROVISIONING_DEFER_PROVISIONING_TO_ROLE_HOLDER =
                "managed_provisioning_defer_provisioning_to_role_holder";

        /**
         * Settings migrated from Wear OS settings provider.
         * @hide
         */
        public static class Wearable {
            /**
             * Whether the user has any pay tokens on their watch.
             * @hide
             */
            public static final String HAS_PAY_TOKENS = "has_pay_tokens";

            /**
             * Gcm checkin timeout in minutes.
             * @hide
             */
            public static final String GMS_CHECKIN_TIMEOUT_MIN = "gms_checkin_timeout_min";

            /**
             * If hotword detection should be enabled.
             * @hide
             */
            public static final String HOTWORD_DETECTION_ENABLED = "hotword_detection_enabled";

            /**
             * Whether Smart Replies are enabled within Wear.
             * @hide
             */
            public static final String SMART_REPLIES_ENABLED = "smart_replies_enabled";

            /**
             * The default vibration pattern.
             * @hide
             */
            public static final String DEFAULT_VIBRATION = "default_vibration";

            /**
             * If FLP should obtain location data from the paired device.
             * @hide
             */
            public static final String OBTAIN_PAIRED_DEVICE_LOCATION =
                    "obtain_paired_device_location";

            /**
             * Whether the device is in retail mode.
             * @hide
             */
            public static final String RETAIL_MODE = "retail_mode";

            // Possible retail mode states
            /** @hide */
            public static final int RETAIL_MODE_CONSUMER = 0;
            /** @hide */
            public static final int RETAIL_MODE_RETAIL = 1;

            /**
             * The play store availability on companion phone.
             * @hide
             */
            public static final String PHONE_PLAY_STORE_AVAILABILITY =
                    "phone_play_store_availability";

            // Possible phone play store availability states
            /** @hide */
            public static final int PHONE_PLAY_STORE_AVAILABILITY_UNKNOWN = 0;
            /** @hide */
            public static final int PHONE_PLAY_STORE_AVAILABLE = 1;
            /** @hide */
            public static final int PHONE_PLAY_STORE_UNAVAILABLE = 2;

            /**
             * Whether the bug report is enabled.
             * @hide
             */
            public static final String BUG_REPORT = "bug_report";

            // Possible bug report states
            /** @hide */
            public static final int BUG_REPORT_DISABLED = 0;
            /** @hide */
            public static final int BUG_REPORT_ENABLED = 1;

            /**
             * The enabled/disabled state of the SmartIlluminate.
             * @hide
             */
            public static final String SMART_ILLUMINATE_ENABLED = "smart_illuminate_enabled";

            /**
             * Whether automatic time is enabled on the watch.
             * @hide
             */
            public static final String CLOCKWORK_AUTO_TIME = "clockwork_auto_time";

            // Possible clockwork auto time states
            /** @hide */
            public static final int SYNC_TIME_FROM_PHONE = 0;
            /** @hide */
            public static final int SYNC_TIME_FROM_NETWORK = 1;
            /** @hide */
            public static final int AUTO_TIME_OFF = 2;
            /** @hide */
            public static final int INVALID_AUTO_TIME_STATE = 3;


            /**
             * Whether automatic time zone is enabled on the watch.
             * @hide
             */
            public static final String CLOCKWORK_AUTO_TIME_ZONE = "clockwork_auto_time_zone";

            // Possible clockwork auto time zone states
            /** @hide */
            public static final int SYNC_TIME_ZONE_FROM_PHONE = 0;
            /** @hide */
            public static final int SYNC_TIME_ZONE_FROM_NETWORK = 1;
            /** @hide */
            public static final int AUTO_TIME_ZONE_OFF = 2;
            /** @hide */
            public static final int INVALID_AUTO_TIME_ZONE_STATE = 3;

            /**
             * Whether 24 hour time format is enabled on the watch.
             * @hide
             */
            public static final String CLOCKWORK_24HR_TIME = "clockwork_24hr_time";

            /**
             * Whether the auto wifi toggle setting is enabled.
             * @hide
             */
            public static final String AUTO_WIFI = "auto_wifi";

            // Possible force wifi on states
            /** @hide */
            public static final int AUTO_WIFI_DISABLED = 0;
            /** @hide */
            public static final int AUTO_WIFI_ENABLED = 1;

            /**
             * The number of minutes after the WiFi enters power save mode.
             * @hide
             */
            public static final String WIFI_POWER_SAVE = "wifi_power_save";

            /**
             * The time at which we should no longer skip the wifi requirement check (we skip the
             * wifi requirement until this time). The time is in millis since epoch.
             * @hide
             */
            public static final String ALT_BYPASS_WIFI_REQUIREMENT_TIME_MILLIS =
                    "alt_bypass_wifi_requirement_time_millis";

            /**
             * Whether the setup was skipped.
             * @hide
             */
            public static final String SETUP_SKIPPED = "setup_skipped";

            // Possible setup_skipped states
            /** @hide */
            public static final int SETUP_SKIPPED_UNKNOWN = 0;
            /** @hide */
            public static final int SETUP_SKIPPED_YES = 1;
            /** @hide */
            public static final int SETUP_SKIPPED_NO = 2;

            /**
             * The last requested call forwarding action.
             * @hide
             */
            public static final String LAST_CALL_FORWARD_ACTION = "last_call_forward_action";

            // Possible call forwarding actions
            /** @hide */
            public static final int CALL_FORWARD_ACTION_ON = 1;
            /** @hide */
            public static final int CALL_FORWARD_ACTION_OFF = 2;
            /** @hide */
            public static final int CALL_FORWARD_NO_LAST_ACTION = -1;

            // Stem button settings.
            /** @hide */
            public static final String STEM_1_TYPE = "STEM_1_TYPE";
            /** @hide */
            public static final String STEM_1_DATA = "STEM_1_DATA";
            /** @hide */
            public static final String STEM_1_DEFAULT_DATA = "STEM_1_DEFAULT_DATA";
            /** @hide */
            public static final String STEM_2_TYPE = "STEM_2_TYPE";
            /** @hide */
            public static final String STEM_2_DATA = "STEM_2_DATA";
            /** @hide */
            public static final String STEM_2_DEFAULT_DATA = "STEM_2_DEFAULT_DATA";
            /** @hide */
            public static final String STEM_3_TYPE = "STEM_3_TYPE";
            /** @hide */
            public static final String STEM_3_DATA = "STEM_3_DATA";
            /** @hide */
            public static final String STEM_3_DEFAULT_DATA = "STEM_3_DEFAULT_DATA";

            // Stem types
            /** @hide */
            public static final int STEM_TYPE_UNKNOWN = -1;
            /** @hide */
            public static final int STEM_TYPE_APP_LAUNCH = 0;
            /** @hide */
            public static final int STEM_TYPE_CONTACT_LAUNCH = 1;

            /**
             * If the device should be muted when off body.
             * @hide
             */
            public static final String MUTE_WHEN_OFF_BODY_ENABLED = "obtain_mute_when_off_body";

            /**
             * Wear OS version string.
             * @hide
             */
            public static final String WEAR_OS_VERSION_STRING = "wear_os_version_string";

            /**
             * How round the corners of square screens are.
             * @hide
             */
            public static final String CORNER_ROUNDNESS = "corner_roundness";

            /**
             * Whether the physical button has been set.
             * @hide
             */
            public static final String BUTTON_SET = "button_set";

            /**
             * Whether there is a side button.
             * @hide
             */
            public static final String SIDE_BUTTON = "side_button";

            /**
             * The android wear system version.
             * @hide
             */
            public static final String ANDROID_WEAR_VERSION = "android_wear_version";

            /**
             * The wear system capabiltiies.
             * @hide
             */
            public static final String SYSTEM_CAPABILITIES = "system_capabilities";

            /**
             * The android wear system edition.
             * @hide
             */
            public static final String SYSTEM_EDITION = "android_wear_system_edition";

            /**
             * The Wear platform MR number.
             * @hide
             */
            public static final String WEAR_PLATFORM_MR_NUMBER = "wear_platform_mr_number";

            /**
             * The mobile signal detector setting.
             * @hide
             */
            public static final String MOBILE_SIGNAL_DETECTOR = "mobile_signal_detector";


            /**
             * Whether ambient is currently enabled.
             * @hide
             */
            public static final String AMBIENT_ENABLED = "ambient_enabled";

            /**
             * Whether ambient tilt to wake is enabled.
             * @hide
             */
            public static final String AMBIENT_TILT_TO_WAKE = "ambient_tilt_to_wake";

            /**
             * Whether ambient low bit mode is enabled by developer options.
             * @hide
             */
            public static final String AMBIENT_LOW_BIT_ENABLED_DEV = "ambient_low_bit_enabled_dev";

            /**
             * Whether ambient touch to wake is enabled.
             * @hide
             */
            public static final String AMBIENT_TOUCH_TO_WAKE = "ambient_touch_to_wake";

            /**
             * Whether ambient tilt to bright is enabled.
             * @hide
             */
            public static final String AMBIENT_TILT_TO_BRIGHT = "ambient_tilt_to_bright";

            /**
             * Whether the current watchface is decomposable.
             * @hide
             */
            public static final String DECOMPOSABLE_WATCHFACE = "current_watchface_decomposable";

            /**
             * Whether to force ambient when docked.
             * @hide
             */
            public static final String AMBIENT_FORCE_WHEN_DOCKED = "ambient_force_when_docked";

            /**
             * Whether the ambient low bit mode is enabled.
             * @hide
             */
            public static final String AMBIENT_LOW_BIT_ENABLED = "ambient_low_bit_enabled";

            /**
             * The timeout duration in minutes of ambient mode when plugged in.
             * @hide
             */
            public static final String AMBIENT_PLUGGED_TIMEOUT_MIN = "ambient_plugged_timeout_min";

            /**
             * What OS does paired device has.
             * @hide
             */
            public static final String PAIRED_DEVICE_OS_TYPE = "paired_device_os_type";

            // Possible values of PAIRED_DEVICE_OS_TYPE
            /** @hide */
            public static final int PAIRED_DEVICE_OS_TYPE_UNKNOWN = 0;
            /** @hide */
            public static final int PAIRED_DEVICE_OS_TYPE_ANDROID = 1;
            /** @hide */
            public static final int PAIRED_DEVICE_OS_TYPE_IOS = 2;

            /**
             * The bluetooth settings selected BLE role for the companion.
             * @hide
             */
            public static final String COMPANION_BLE_ROLE = "companion_ble_role";

            // Possible values of COMPANION_BLE_ROLE
            /** @hide */
            public static final int BLUETOOTH_ROLE_CENTRAL = 1;
            /** @hide */
            public static final int BLUETOOTH_ROLE_PERIPHERAL = 2;

            /**
             * The bluetooth settings stored companion device name.
             * @hide
             */
            public static final String COMPANION_NAME = "companion_bt_name";

            /**
             * The user's last setting for hfp client.
             * @hide
             */
            public static final String USER_HFP_CLIENT_SETTING = "user_hfp_client_setting";

            // Possible hfp client user setting values
            /** @hide */
            public static final int HFP_CLIENT_UNSET = 0;
            /** @hide */
            public static final int HFP_CLIENT_ENABLED = 1;
            /** @hide */
            public static final int HFP_CLIENT_DISABLED = 2;

            /**
             * The companion phone's android version.
             * @hide
             */
            public static final String COMPANION_OS_VERSION = "wear_companion_os_version";

            // Companion os version constants
            /** @hide */
            public static final int COMPANION_OS_VERSION_UNDEFINED = -1;

            /**
             * A boolean value to indicate if we want to support all languages in LE edition on
             * wear. 1 for supporting, 0 for not supporting.
             * @hide
             */
            public static final String ENABLE_ALL_LANGUAGES = "enable_all_languages";

            /**
             * The Locale (as language tag) the user chose at startup.
             * @hide
             */
            public static final String SETUP_LOCALE = "setup_locale";

            /**
             * The version of oem setup present.
             * @hide
             */
            public static final String OEM_SETUP_VERSION = "oem_setup_version";

            /**
             * Controls the gestures feature.
             * @hide
             */
            public static final String MASTER_GESTURES_ENABLED = "master_gestures_enabled";

            /**
             * Whether or not ungaze is enabled.
             * @hide
             */
            public static final String UNGAZE_ENABLED = "ungaze_enabled";

            /**
             * The device's battery saver mode, which can be one of the following:
             * -{@link BATTERY_SAVER_MODE_NONE}
             * -{@link BATTERY_SAVER_MODE_LIGHT}
             * -{@link BATTERY_SAVER_MODE_TRADITIONAL_WATCH}
             * -{@link BATTERY_SAVER_MODE_TIME_ONLY}
             * -{@link BATTERY_SAVER_MODE_CUSTOM}
             * @hide
             */
            public static final String BATTERY_SAVER_MODE = "battery_saver_mode";

            /**
             * Not in Battery Saver Mode
             * @hide
             */
            public static final int BATTERY_SAVER_MODE_NONE = 0;
            /**
             * In Lightweight Battery Saver Mode
             * @hide
             */
            public static final int BATTERY_SAVER_MODE_LIGHT = 1;
            /**
             * In Traditional Watch Mode Battery Saver Mode
             * @hide
             */
            public static final int BATTERY_SAVER_MODE_TRADITIONAL_WATCH = 2;
            /**
             * In Time-only Mode Battery Saver Mode
             * @hide
             */
            public static final int BATTERY_SAVER_MODE_TIME_ONLY = 3;
            /**
             * Partner's Battery Saver implementation is being used
             * @hide
             */
            public static final int BATTERY_SAVER_MODE_CUSTOM = 4;

            /**
             * The maximum ambient mode duration when an activity is allowed to auto resume.
             * @hide
             */
            public static final String WEAR_ACTIVITY_AUTO_RESUME_TIMEOUT_MS =
                    "wear_activity_auto_resume_timeout_ms";

            /**
             * If the current {@code WEAR_ACTIVITY_AUTO_RESUME_TIMEOUT_MS} value is set by user.
             * 1 for true, 0 for false.
             * @hide
             */
            public static final String WEAR_ACTIVITY_AUTO_RESUME_TIMEOUT_SET_BY_USER =
                    "wear_activity_auto_resume_timeout_set_by_user";

            /**
             * If burn in protection is enabled.
             * @hide
             */
            public static final String BURN_IN_PROTECTION_ENABLED = "burn_in_protection";

            /**
             * Whether the device has combined location setting enabled.
             * @hide
             */
            public static final String COMBINED_LOCATION_ENABLED = "combined_location_enable";

            /**
             * The wrist orientation mode of the device
             * Valid values - LEFT_WRIST_ROTATION_0 = "0" (default), LEFT_WRIST_ROTATION_180 = "1",
             *          RIGHT_WRIST_ROTATION_0 = "2", RIGHT_WRIST_ROTATION_180 = "3"
             * @hide
             */
            public static final String WRIST_ORIENTATION_MODE = "wear_wrist_orientation_mode";

            /**
             * Setting indicating the name of the Wear OS app package containing the device's sysui.
             *
             * @hide
             */
            public static final String CLOCKWORK_SYSUI_PACKAGE = "clockwork_sysui_package";

            /**
             * Setting indicating the name of the main activity of the Wear OS sysui.
             *
             * @hide
             */
            public static final String CLOCKWORK_SYSUI_MAIN_ACTIVITY =
                    "clockwork_sysui_main_activity";

            /**
             * Setting to disable power button long press launching Assistant. It's boolean, i.e.
             * enabled = 1, disabled = 0. By default, this setting is enabled.
             *
             * @hide
             */
            public static final String CLOCKWORK_LONG_PRESS_TO_ASSISTANT_ENABLED =
                    "clockwork_long_press_to_assistant_enabled";

            /*
             * Whether the device has Cooldown Mode enabled.
             * @hide
             */
            public static final String COOLDOWN_MODE_ON = "cooldown_mode_on";

            /*
             * Whether the device has Wet Mode/ Touch Lock Mode enabled.
             * @hide
             */
            public static final String WET_MODE_ON = "wet_mode_on";
        }
    }

    /**
     * Configuration system settings, containing settings which are applied identically for all
     * defined users. Only Android can read these and only a specific configuration service can
     * write these.
     *
     * @hide
     */
    public static final class Config extends NameValueTable {

        /**
         * The modes that can be used when disabling syncs to the 'config' settings.
         * @hide
         */
        @IntDef(prefix = "DISABLE_SYNC_MODE_",
                value = { SYNC_DISABLED_MODE_NONE, SYNC_DISABLED_MODE_PERSISTENT,
                        SYNC_DISABLED_MODE_UNTIL_REBOOT })
        @Retention(RetentionPolicy.SOURCE)
        @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
        public @interface SyncDisabledMode {}

        /**
         * Sync is not not disabled.
         *
         * @hide
         */
        public static final int SYNC_DISABLED_MODE_NONE = 0;

        /**
         * Disabling of Config bulk update / syncing is persistent, i.e. it survives a device
         * reboot.
         * @hide
         */
        public static final int SYNC_DISABLED_MODE_PERSISTENT = 1;

        /**
         * Disabling of Config bulk update / syncing is not persistent, i.e. it will not survive a
         * device reboot.
         * @hide
         */
        public static final int SYNC_DISABLED_MODE_UNTIL_REBOOT = 2;

        private static final ContentProviderHolder sProviderHolder =
                new ContentProviderHolder(DeviceConfig.CONTENT_URI);

        // Populated lazily, guarded by class object:
        private static final NameValueCache sNameValueCache = new NameValueCache(
                DeviceConfig.CONTENT_URI,
                CALL_METHOD_GET_CONFIG,
                CALL_METHOD_PUT_CONFIG,
                CALL_METHOD_DELETE_CONFIG,
                CALL_METHOD_LIST_CONFIG,
                CALL_METHOD_SET_ALL_CONFIG,
                sProviderHolder,
                Config.class);

        /**
         * Look up a name in the database.
         * @param resolver to access the database with
         * @param name to look up in the table
         * @return the corresponding value, or null if not present
         *
         * @hide
         */
        @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
        static String getString(ContentResolver resolver, String name) {
            return sNameValueCache.getStringForUser(resolver, name, resolver.getUserId());
        }

        /**
         * Look up a list of names in the database, within the specified namespace.
         *
         * @param resolver to access the database with
         * @param namespace to which the names belong
         * @param names to look up in the table
         * @return a non null, but possibly empty, map from name to value for any of the names that
         *         were found during lookup.
         *
         * @hide
         */
        @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
        public static Map<String, String> getStrings(@NonNull ContentResolver resolver,
                @NonNull String namespace, @NonNull List<String> names) {
            List<String> compositeNames = new ArrayList<>(names.size());
            for (String name : names) {
                compositeNames.add(createCompositeName(namespace, name));
            }

            String prefix = createPrefix(namespace);
            ArrayMap<String, String> rawKeyValues = sNameValueCache.getStringsForPrefix(
                    resolver, prefix, compositeNames);
            int size = rawKeyValues.size();
            int substringLength = prefix.length();
            ArrayMap<String, String> keyValues = new ArrayMap<>(size);
            for (int i = 0; i < size; ++i) {
                keyValues.put(rawKeyValues.keyAt(i).substring(substringLength),
                        rawKeyValues.valueAt(i));
            }
            return keyValues;
        }

        /**
         * Store a name/value pair into the database within the specified namespace.
         * <p>
         * Also the method takes an argument whether to make the value the default for this setting.
         * If the system already specified a default value, then the one passed in here will
         * <strong>not</strong> be set as the default.
         * </p>
         *
         * @param resolver to access the database with.
         * @param namespace to store the name/value pair in.
         * @param name to store.
         * @param value to associate with the name.
         * @param makeDefault whether to make the value the default one.
         * @return true if the value was set, false on database errors.
         *
         * @see #resetToDefaults(ContentResolver, int, String)
         *
         * @hide
         */
        @RequiresPermission(Manifest.permission.WRITE_DEVICE_CONFIG)
        static boolean putString(@NonNull ContentResolver resolver, @NonNull String namespace,
                @NonNull String name, @Nullable String value, boolean makeDefault) {
            return sNameValueCache.putStringForUser(resolver, createCompositeName(namespace, name),
                    value, null, makeDefault, resolver.getUserId(),
                    DEFAULT_OVERRIDEABLE_BY_RESTORE);
        }

        /**
         * Clear all name/value pairs for the provided namespace and save new name/value pairs in
         * their place.
         *
         * @param resolver to access the database with.
         * @param namespace to which the names should be set.
         * @param keyValues map of key names (without the prefix) to values.
         * @return true if the name/value pairs were set, false if setting was blocked
         *
         * @hide
         */
        @RequiresPermission(Manifest.permission.WRITE_DEVICE_CONFIG)
        public static boolean setStrings(@NonNull ContentResolver resolver,
                @NonNull String namespace, @NonNull Map<String, String> keyValues)
                throws DeviceConfig.BadConfigException {
            HashMap<String, String> compositeKeyValueMap = new HashMap<>(keyValues.keySet().size());
            for (Map.Entry<String, String> entry : keyValues.entrySet()) {
                compositeKeyValueMap.put(
                        createCompositeName(namespace, entry.getKey()), entry.getValue());
            }
            int result = sNameValueCache.setStringsForPrefix(
                    resolver, createPrefix(namespace), compositeKeyValueMap);
            if (result == SET_ALL_RESULT_SUCCESS) {
                return true;
            } else if (result == SET_ALL_RESULT_DISABLED) {
                return false;
            }
            // If can't set given configuration that means it's bad
            throw new DeviceConfig.BadConfigException();
        }

        /**
         * Delete a name/value pair from the database for the specified namespace.
         *
         * @param resolver to access the database with.
         * @param namespace to delete the name/value pair from.
         * @param name to delete.
         * @return true if the value was deleted, false on database errors. If the name/value pair
         * did not exist, return True.
         *
         * @see #resetToDefaults(ContentResolver, int, String)
         *
         * @hide
         */
        @RequiresPermission(Manifest.permission.WRITE_DEVICE_CONFIG)
        static boolean deleteString(@NonNull ContentResolver resolver, @NonNull String namespace,
                @NonNull String name) {
            return sNameValueCache.deleteStringForUser(resolver,
                    createCompositeName(namespace, name), resolver.getUserId());
        }

        /**
         * Reset the values to their defaults.
         * <p>
         * The method accepts an optional prefix parameter. If provided, only pairs with a name that
         * starts with the exact prefix will be reset. Otherwise all will be reset.
         *
         * @param resolver Handle to the content resolver.
         * @param resetMode The reset mode to use.
         * @param namespace Optionally, to limit which which namespace is reset.
         *
         * @see #putString(ContentResolver, String, String, String, boolean)
         *
         * @hide
         */
        @RequiresPermission(Manifest.permission.WRITE_DEVICE_CONFIG)
        static void resetToDefaults(@NonNull ContentResolver resolver, @ResetMode int resetMode,
                @Nullable String namespace) {
            try {
                Bundle arg = new Bundle();
                arg.putInt(CALL_METHOD_USER_KEY, resolver.getUserId());
                arg.putInt(CALL_METHOD_RESET_MODE_KEY, resetMode);
                if (namespace != null) {
                    arg.putString(Settings.CALL_METHOD_PREFIX_KEY, createPrefix(namespace));
                }
                IContentProvider cp = sProviderHolder.getProvider(resolver);
                cp.call(resolver.getAttributionSource(),
                        sProviderHolder.mUri.getAuthority(), CALL_METHOD_RESET_CONFIG, null, arg);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't reset to defaults for " + DeviceConfig.CONTENT_URI, e);
            }
        }

        /**
         * Bridge method between {@link DeviceConfig#setSyncDisabledMode(int)} and the
         * {@link com.android.providers.settings.SettingsProvider} implementation.
         *
         * @hide
         */
        @SuppressLint("AndroidFrameworkRequiresPermission")
        @RequiresPermission(Manifest.permission.WRITE_DEVICE_CONFIG)
        static void setSyncDisabledMode(
                @NonNull ContentResolver resolver, @SyncDisabledMode int disableSyncMode) {
            try {
                Bundle args = new Bundle();
                args.putInt(CALL_METHOD_SYNC_DISABLED_MODE_KEY, disableSyncMode);
                IContentProvider cp = sProviderHolder.getProvider(resolver);
                cp.call(resolver.getAttributionSource(), sProviderHolder.mUri.getAuthority(),
                        CALL_METHOD_SET_SYNC_DISABLED_MODE_CONFIG, null, args);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't set sync disabled mode " + DeviceConfig.CONTENT_URI, e);
            }
        }

        /**
         * Bridge method between {@link DeviceConfig#getSyncDisabledMode()} and the
         * {@link com.android.providers.settings.SettingsProvider} implementation.
         *
         * @hide
         */
        @SuppressLint("AndroidFrameworkRequiresPermission")
        @RequiresPermission(Manifest.permission.WRITE_DEVICE_CONFIG)
        static int getSyncDisabledMode(@NonNull ContentResolver resolver) {
            try {
                Bundle args = Bundle.EMPTY;
                IContentProvider cp = sProviderHolder.getProvider(resolver);
                Bundle bundle = cp.call(resolver.getAttributionSource(),
                        sProviderHolder.mUri.getAuthority(),
                        CALL_METHOD_GET_SYNC_DISABLED_MODE_CONFIG,
                        null, args);
                return bundle.getInt(KEY_CONFIG_GET_SYNC_DISABLED_MODE_RETURN);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't query sync disabled mode " + DeviceConfig.CONTENT_URI, e);
            }
            return -1;
        }

        /**
         * Register callback for monitoring Config table.
         *
         * @param resolver Handle to the content resolver.
         * @param callback callback to register
         *
         * @hide
         */
        @RequiresPermission(Manifest.permission.MONITOR_DEVICE_CONFIG_ACCESS)
        public static void registerMonitorCallback(@NonNull ContentResolver resolver,
                @NonNull RemoteCallback callback) {
            registerMonitorCallbackAsUser(resolver, resolver.getUserId(), callback);
        }

        private static void registerMonitorCallbackAsUser(
                @NonNull ContentResolver resolver, @UserIdInt int userHandle,
                @NonNull RemoteCallback callback) {
            try {
                Bundle arg = new Bundle();
                arg.putInt(CALL_METHOD_USER_KEY, userHandle);
                arg.putParcelable(CALL_METHOD_MONITOR_CALLBACK_KEY, callback);
                IContentProvider cp = sProviderHolder.getProvider(resolver);
                cp.call(resolver.getAttributionSource(),
                        sProviderHolder.mUri.getAuthority(),
                        CALL_METHOD_REGISTER_MONITOR_CALLBACK_CONFIG, null, arg);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't register config monitor callback", e);
            }
        }

        /** @hide */
        public static void clearProviderForTest() {
            sProviderHolder.clearProviderForTest();
            sNameValueCache.clearGenerationTrackerForTest();
        }

        private static String createCompositeName(@NonNull String namespace, @NonNull String name) {
            Preconditions.checkNotNull(namespace);
            Preconditions.checkNotNull(name);
            return createPrefix(namespace) + name;
        }

        private static String createPrefix(@NonNull String namespace) {
            Preconditions.checkNotNull(namespace);
            return namespace + "/";
        }
    }

    /**
     * User-defined bookmarks and shortcuts.  The target of each bookmark is an
     * Intent URL, allowing it to be either a web page or a particular
     * application activity.
     *
     * @hide
     */
    public static final class Bookmarks implements BaseColumns
    {
        private static final String TAG = "Bookmarks";

        /**
         * The content:// style URL for this table
         */
        @UnsupportedAppUsage
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/bookmarks");

        /**
         * The row ID.
         * <p>Type: INTEGER</p>
         */
        public static final String ID = "_id";

        /**
         * Descriptive name of the bookmark that can be displayed to the user.
         * If this is empty, the title should be resolved at display time (use
         * {@link #getTitle(Context, Cursor)} any time you want to display the
         * title of a bookmark.)
         * <P>
         * Type: TEXT
         * </P>
         */
        public static final String TITLE = "title";

        /**
         * Arbitrary string (displayed to the user) that allows bookmarks to be
         * organized into categories.  There are some special names for
         * standard folders, which all start with '@'.  The label displayed for
         * the folder changes with the locale (via {@link #getLabelForFolder}) but
         * the folder name does not change so you can consistently query for
         * the folder regardless of the current locale.
         *
         * <P>Type: TEXT</P>
         *
         */
        public static final String FOLDER = "folder";

        /**
         * The Intent URL of the bookmark, describing what it points to.  This
         * value is given to {@link android.content.Intent#getIntent} to create
         * an Intent that can be launched.
         * <P>Type: TEXT</P>
         */
        public static final String INTENT = "intent";

        /**
         * Optional shortcut character associated with this bookmark.
         * <P>Type: INTEGER</P>
         */
        public static final String SHORTCUT = "shortcut";

        /**
         * The order in which the bookmark should be displayed
         * <P>Type: INTEGER</P>
         */
        public static final String ORDERING = "ordering";

        private static final String[] sIntentProjection = { INTENT };
        private static final String[] sShortcutProjection = { ID, SHORTCUT };
        private static final String sShortcutSelection = SHORTCUT + "=?";

        /**
         * Convenience function to retrieve the bookmarked Intent for a
         * particular shortcut key.
         *
         * @param cr The ContentResolver to query.
         * @param shortcut The shortcut key.
         *
         * @return Intent The bookmarked URL, or null if there is no bookmark
         *         matching the given shortcut.
         */
        public static Intent getIntentForShortcut(ContentResolver cr, char shortcut)
        {
            Intent intent = null;

            Cursor c = cr.query(CONTENT_URI,
                    sIntentProjection, sShortcutSelection,
                    new String[] { String.valueOf((int) shortcut) }, ORDERING);
            // Keep trying until we find a valid shortcut
            try {
                while (intent == null && c.moveToNext()) {
                    try {
                        String intentURI = c.getString(c.getColumnIndexOrThrow(INTENT));
                        intent = Intent.parseUri(intentURI, 0);
                    } catch (java.net.URISyntaxException e) {
                        // The stored URL is bad...  ignore it.
                    } catch (IllegalArgumentException e) {
                        // Column not found
                        Log.w(TAG, "Intent column not found", e);
                    }
                }
            } finally {
                if (c != null) c.close();
            }

            return intent;
        }

        /**
         * Add a new bookmark to the system.
         *
         * @param cr The ContentResolver to query.
         * @param intent The desired target of the bookmark.
         * @param title Bookmark title that is shown to the user; null if none
         *            or it should be resolved to the intent's title.
         * @param folder Folder in which to place the bookmark; null if none.
         * @param shortcut Shortcut that will invoke the bookmark; 0 if none. If
         *            this is non-zero and there is an existing bookmark entry
         *            with this same shortcut, then that existing shortcut is
         *            cleared (the bookmark is not removed).
         * @return The unique content URL for the new bookmark entry.
         */
        @UnsupportedAppUsage
        public static Uri add(ContentResolver cr,
                                           Intent intent,
                                           String title,
                                           String folder,
                                           char shortcut,
                                           int ordering)
        {
            // If a shortcut is supplied, and it is already defined for
            // another bookmark, then remove the old definition.
            if (shortcut != 0) {
                cr.delete(CONTENT_URI, sShortcutSelection,
                        new String[] { String.valueOf((int) shortcut) });
            }

            ContentValues values = new ContentValues();
            if (title != null) values.put(TITLE, title);
            if (folder != null) values.put(FOLDER, folder);
            values.put(INTENT, intent.toUri(0));
            if (shortcut != 0) values.put(SHORTCUT, (int) shortcut);
            values.put(ORDERING, ordering);
            return cr.insert(CONTENT_URI, values);
        }

        /**
         * Return the folder name as it should be displayed to the user.  This
         * takes care of localizing special folders.
         *
         * @param r Resources object for current locale; only need access to
         *          system resources.
         * @param folder The value found in the {@link #FOLDER} column.
         *
         * @return CharSequence The label for this folder that should be shown
         *         to the user.
         */
        public static CharSequence getLabelForFolder(Resources r, String folder) {
            return folder;
        }

        /**
         * Return the title as it should be displayed to the user. This takes
         * care of localizing bookmarks that point to activities.
         *
         * @param context A context.
         * @param cursor A cursor pointing to the row whose title should be
         *        returned. The cursor must contain at least the {@link #TITLE}
         *        and {@link #INTENT} columns.
         * @return A title that is localized and can be displayed to the user,
         *         or the empty string if one could not be found.
         */
        public static CharSequence getTitle(Context context, Cursor cursor) {
            int titleColumn = cursor.getColumnIndex(TITLE);
            int intentColumn = cursor.getColumnIndex(INTENT);
            if (titleColumn == -1 || intentColumn == -1) {
                throw new IllegalArgumentException(
                        "The cursor must contain the TITLE and INTENT columns.");
            }

            String title = cursor.getString(titleColumn);
            if (!TextUtils.isEmpty(title)) {
                return title;
            }

            String intentUri = cursor.getString(intentColumn);
            if (TextUtils.isEmpty(intentUri)) {
                return "";
            }

            Intent intent;
            try {
                intent = Intent.parseUri(intentUri, 0);
            } catch (URISyntaxException e) {
                return "";
            }

            PackageManager packageManager = context.getPackageManager();
            ResolveInfo info = packageManager.resolveActivity(intent, 0);
            return info != null ? info.loadLabel(packageManager) : "";
        }
    }

    /**
     * <p>
     *     A Settings panel is floating UI that contains a fixed subset of settings to address a
     *     particular user problem. For example, the
     *     {@link #ACTION_INTERNET_CONNECTIVITY Internet Panel} surfaces settings related to
     *     connecting to the internet.
     * <p>
     *     Settings panels appear above the calling app to address the problem without
     *     the user needing to open Settings and thus leave their current screen.
     */
    public static final class Panel {
        private Panel() {
        }

        /**
         * Activity Action: Show a settings dialog containing settings to enable internet
         * connection.
         * <p>
         * Input: Nothing.
         * <p>
         * Output: Nothing.
         */
        @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
        public static final String ACTION_INTERNET_CONNECTIVITY =
                "android.settings.panel.action.INTERNET_CONNECTIVITY";

        /**
         * Activity Action: Show a settings dialog containing NFC-related settings.
         * <p>
         * Input: Nothing.
         * <p>
         * Output: Nothing.
         */
        @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
        public static final String ACTION_NFC =
                "android.settings.panel.action.NFC";

        /**
         * Activity Action: Show a settings dialog containing controls for Wifi.
         * <p>
         * Input: Nothing.
         * <p>
         * Output: Nothing.
         */
        @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
        public static final String ACTION_WIFI =
                "android.settings.panel.action.WIFI";

        /**
         * Activity Action: Show a settings dialog containing all volume streams.
         * <p>
         * Input: Nothing.
         * <p>
         * Output: Nothing.
         */
        @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
        public static final String ACTION_VOLUME =
                "android.settings.panel.action.VOLUME";
    }

    /**
     * Activity Action: Show setting page to process the addition of Wi-Fi networks to the user's
     * saved network list. The app should send a new intent with an extra that holds a maximum
     * of five {@link android.net.wifi.WifiNetworkSuggestion} that specify credentials for the
     * networks to be added to the user's database. The Intent should be sent via the
     * {@link android.app.Activity#startActivityForResult(Intent, int)} API.
     * <p>
     * Note: The app sending the Intent to add the credentials doesn't get any ownership over the
     * newly added network(s). For the Wi-Fi stack, these networks will look like the user
     * manually added them from the Settings UI.
     * <p>
     * Input: The app should put parcelable array list of
     * {@link android.net.wifi.WifiNetworkSuggestion} into the {@link #EXTRA_WIFI_NETWORK_LIST}
     * extra.
     * <p>
     * Output: After {@link android.app.Activity#startActivityForResult(Intent, int)}, the
     * callback {@link android.app.Activity#onActivityResult(int, int, Intent)} will have a
     * result code {@link android.app.Activity#RESULT_OK} to indicate user pressed the save
     * button to save the networks or {@link android.app.Activity#RESULT_CANCELED} to indicate
     * that the user rejected the request. Additionally, an integer array list, stored in
     * {@link #EXTRA_WIFI_NETWORK_RESULT_LIST}, will indicate the process result of each network.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_WIFI_ADD_NETWORKS =
            "android.settings.WIFI_ADD_NETWORKS";

    /**
     * A bundle extra of {@link #ACTION_WIFI_ADD_NETWORKS} intent action that indicates the list
     * of the {@link android.net.wifi.WifiNetworkSuggestion} elements. The maximum count of the
     * {@link android.net.wifi.WifiNetworkSuggestion} elements in the list will be five.
     * <p>
     * For example:
     * To provide credentials for one open and one WPA2 networks:
     *
     * <pre>{@code
     * final WifiNetworkSuggestion suggestion1 =
     *       new WifiNetworkSuggestion.Builder()
     *       .setSsid("test111111")
     *       .build();
     * final WifiNetworkSuggestion suggestion2 =
     *       new WifiNetworkSuggestion.Builder()
     *       .setSsid("test222222")
     *       .setWpa2Passphrase("test123456")
     *       .build();
     * final List<WifiNetworkSuggestion> suggestionsList = new ArrayList<>;
     * suggestionsList.add(suggestion1);
     * suggestionsList.add(suggestion2);
     * Bundle bundle = new Bundle();
     * bundle.putParcelableArrayList(Settings.EXTRA_WIFI_NETWORK_LIST,(ArrayList<? extends
     * Parcelable>) suggestionsList);
     * final Intent intent = new Intent(Settings.ACTION_WIFI_ADD_NETWORKS);
     * intent.putExtras(bundle);
     * startActivityForResult(intent, 0);
     * }</pre>
     */
    public static final String EXTRA_WIFI_NETWORK_LIST =
            "android.provider.extra.WIFI_NETWORK_LIST";

    /**
     * A bundle extra of the result of {@link #ACTION_WIFI_ADD_NETWORKS} intent action that
     * indicates the action result of the saved {@link android.net.wifi.WifiNetworkSuggestion}.
     * Its value is a list of integers, and all the elements will be 1:1 mapping to the elements
     * in {@link #EXTRA_WIFI_NETWORK_LIST}, if user press cancel to cancel the add networks
     * request, then its value will be null.
     * <p>
     * Note: The integer value will be one of the {@link #ADD_WIFI_RESULT_SUCCESS},
     * {@link #ADD_WIFI_RESULT_ADD_OR_UPDATE_FAILED}, or {@link #ADD_WIFI_RESULT_ALREADY_EXISTS}}.
     */
    public static final String EXTRA_WIFI_NETWORK_RESULT_LIST =
            "android.provider.extra.WIFI_NETWORK_RESULT_LIST";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ADD_WIFI_RESULT_"}, value = {
            ADD_WIFI_RESULT_SUCCESS,
            ADD_WIFI_RESULT_ADD_OR_UPDATE_FAILED,
            ADD_WIFI_RESULT_ALREADY_EXISTS
    })
    public @interface AddWifiResult {
    }

    /**
     * A result of {@link #ACTION_WIFI_ADD_NETWORKS} intent action that saving or updating the
     * corresponding Wi-Fi network was successful.
     */
    public static final int ADD_WIFI_RESULT_SUCCESS = 0;

    /**
     * A result of {@link #ACTION_WIFI_ADD_NETWORKS} intent action that saving the corresponding
     * Wi-Fi network failed.
     */
    public static final int ADD_WIFI_RESULT_ADD_OR_UPDATE_FAILED = 1;

    /**
     * A result of {@link #ACTION_WIFI_ADD_NETWORKS} intent action that indicates the Wi-Fi network
     * already exists.
     */
    public static final int ADD_WIFI_RESULT_ALREADY_EXISTS = 2;

    /**
     * Activity Action: Allows user to select current bug report handler.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_BUGREPORT_HANDLER_SETTINGS =
            "android.settings.BUGREPORT_HANDLER_SETTINGS";

    private static final String[] PM_WRITE_SETTINGS = {
        android.Manifest.permission.WRITE_SETTINGS
    };
    private static final String[] PM_CHANGE_NETWORK_STATE = {
        android.Manifest.permission.CHANGE_NETWORK_STATE,
        android.Manifest.permission.WRITE_SETTINGS
    };
    private static final String[] PM_SYSTEM_ALERT_WINDOW = {
        android.Manifest.permission.SYSTEM_ALERT_WINDOW
    };

    /**
     * Activity Action: Show screen for controlling which apps have access to manage external
     * storage.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard against this.
     * <p>
     * If you want to control a specific app's access to manage external storage, use
     * {@link #ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION} instead.
     * <p>
     * Output: Nothing.
     * @see #ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION =
            "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION";

    /**
     * Activity Action: Show screen for controlling if the app specified in the data URI of the
     * intent can manage external storage.
     * <p>
     * Launching the corresponding activity requires the permission
     * {@link Manifest.permission#MANAGE_EXTERNAL_STORAGE}.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard against this.
     * <p>
     * Input: The Intent's data URI MUST specify the application package name whose ability of
     * managing external storage you want to control.
     * For example "package:com.my.app".
     * <p>
     * Output: Nothing.
     * @see #ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION =
            "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION";

    /**
     * Activity Action: For system or preinstalled apps to show their {@link Activity} embedded
     * in Settings app on large screen devices.
     * <p>
     *     Input: {@link #EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI} must be included to
     * specify the intent for the activity which will be embedded in Settings app.
     * It's an intent URI string from {@code intent.toUri(Intent.URI_INTENT_SCHEME)}.
     *
     *     Input: {@link #EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_HIGHLIGHT_MENU_KEY} must be included to
     * specify a key that indicates the menu item which will be highlighted on settings home menu.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY =
            "android.settings.SETTINGS_EMBED_DEEP_LINK_ACTIVITY";

    /**
     * Activity Extra: Specify the intent for the {@link Activity} which will be embedded in
     * Settings app. It's an intent URI string from
     * {@code intent.toUri(Intent.URI_INTENT_SCHEME)}.
     * <p>
     * This must be passed as an extra field to
     * {@link #ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY}.
     */
    public static final String EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI =
            "android.provider.extra.SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI";

    /**
     * Activity Extra: Specify a key that indicates the menu item which should be highlighted on
     * settings home menu.
     * <p>
     * This must be passed as an extra field to
     * {@link #ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY}.
     */
    public static final String EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_HIGHLIGHT_MENU_KEY =
            "android.provider.extra.SETTINGS_EMBEDDED_DEEP_LINK_HIGHLIGHT_MENU_KEY";

    /**
     * Performs a strict and comprehensive check of whether a calling package is allowed to
     * write/modify system settings, as the condition differs for pre-M, M+, and
     * privileged/preinstalled apps. If the provided uid does not match the
     * callingPackage, a negative result will be returned.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static boolean isCallingPackageAllowedToWriteSettings(Context context, int uid,
            String callingPackage, boolean throwException) {
        return isCallingPackageAllowedToPerformAppOpsProtectedOperation(context, uid,
                callingPackage, null /*attribution not needed when not making note */,
                throwException, AppOpsManager.OP_WRITE_SETTINGS, PM_WRITE_SETTINGS,
                false);
    }

    /**
     * @deprecated Use {@link #checkAndNoteWriteSettingsOperation(Context, int, String, String,
     * boolean)} instead.
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    public static boolean checkAndNoteWriteSettingsOperation(@NonNull Context context, int uid,
            @NonNull String callingPackage, boolean throwException) {
        return checkAndNoteWriteSettingsOperation(context, uid, callingPackage, null,
                throwException);
    }

    /**
     * Performs a strict and comprehensive check of whether a calling package is allowed to
     * write/modify system settings, as the condition differs for pre-M, M+, and
     * privileged/preinstalled apps. If the provided uid does not match the
     * callingPackage, a negative result will be returned. The caller is expected to have
     * the WRITE_SETTINGS permission declared.
     *
     * Note: if the check is successful, the operation of this app will be updated to the
     * current time.
     * @hide
     */
    @SystemApi
    public static boolean checkAndNoteWriteSettingsOperation(@NonNull Context context, int uid,
            @NonNull String callingPackage, @Nullable String callingAttributionTag,
            boolean throwException) {
        return isCallingPackageAllowedToPerformAppOpsProtectedOperation(context, uid,
                callingPackage, callingAttributionTag, throwException,
                AppOpsManager.OP_WRITE_SETTINGS, PM_WRITE_SETTINGS, true);
    }

    /**
     * Performs a strict and comprehensive check of whether a calling package is allowed to
     * draw on top of other apps, as the conditions differs for pre-M, M+, and
     * privileged/preinstalled apps. If the provided uid does not match the callingPackage,
     * a negative result will be returned.
     * @hide
     */
    @UnsupportedAppUsage
    public static boolean isCallingPackageAllowedToDrawOverlays(Context context, int uid,
            String callingPackage, boolean throwException) {
        return isCallingPackageAllowedToPerformAppOpsProtectedOperation(context, uid,
                callingPackage, null /*attribution not needed when not making note */,
                throwException, AppOpsManager.OP_SYSTEM_ALERT_WINDOW, PM_SYSTEM_ALERT_WINDOW,
                false);
    }

    /**
     * Performs a strict and comprehensive check of whether a calling package is allowed to
     * draw on top of other apps, as the conditions differs for pre-M, M+, and
     * privileged/preinstalled apps. If the provided uid does not match the callingPackage,
     * a negative result will be returned.
     *
     * Note: if the check is successful, the operation of this app will be updated to the
     * current time.
     * @hide
     */
    public static boolean checkAndNoteDrawOverlaysOperation(Context context, int uid,
            String callingPackage, String callingAttributionTag, boolean throwException) {
        return isCallingPackageAllowedToPerformAppOpsProtectedOperation(context, uid,
                callingPackage, callingAttributionTag, throwException,
                AppOpsManager.OP_SYSTEM_ALERT_WINDOW, PM_SYSTEM_ALERT_WINDOW, true);
    }

    /**
     * @deprecated Use {@link #isCallingPackageAllowedToPerformAppOpsProtectedOperation(Context,
     * int, String, String, boolean, int, String[], boolean)} instead.
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public static boolean isCallingPackageAllowedToPerformAppOpsProtectedOperation(Context context,
            int uid, String callingPackage, boolean throwException, int appOpsOpCode,
            String[] permissions, boolean makeNote) {
        return isCallingPackageAllowedToPerformAppOpsProtectedOperation(context, uid,
                callingPackage, null, throwException, appOpsOpCode, permissions, makeNote);
    }

    /**
     * Helper method to perform a general and comprehensive check of whether an operation that is
     * protected by appops can be performed by a caller or not. e.g. OP_SYSTEM_ALERT_WINDOW and
     * OP_WRITE_SETTINGS
     * @hide
     */
    public static boolean isCallingPackageAllowedToPerformAppOpsProtectedOperation(Context context,
            int uid, String callingPackage, String callingAttributionTag, boolean throwException,
            int appOpsOpCode, String[] permissions, boolean makeNote) {
        if (callingPackage == null) {
            return false;
        }

        AppOpsManager appOpsMgr = (AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = AppOpsManager.MODE_DEFAULT;
        if (makeNote) {
            mode = appOpsMgr.noteOpNoThrow(appOpsOpCode, uid, callingPackage, callingAttributionTag,
                    null);
        } else {
            mode = appOpsMgr.checkOpNoThrow(appOpsOpCode, uid, callingPackage);
        }

        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return true;

            case AppOpsManager.MODE_DEFAULT:
                // this is the default operating mode after an app's installation
                // In this case we will check all associated static permission to see
                // if it is granted during install time.
                for (String permission : permissions) {
                    if (context.checkCallingOrSelfPermission(permission) == PackageManager
                            .PERMISSION_GRANTED) {
                        // if either of the permissions are granted, we will allow it
                        return true;
                    }
                }

            default:
                // this is for all other cases trickled down here...
                if (!throwException) {
                    return false;
                }
        }

        // prepare string to throw SecurityException
        StringBuilder exceptionMessage = new StringBuilder();
        exceptionMessage.append(callingPackage);
        exceptionMessage.append(" was not granted ");
        if (permissions.length > 1) {
            exceptionMessage.append(" either of these permissions: ");
        } else {
            exceptionMessage.append(" this permission: ");
        }
        for (int i = 0; i < permissions.length; i++) {
            exceptionMessage.append(permissions[i]);
            exceptionMessage.append((i == permissions.length - 1) ? "." : ", ");
        }

        throw new SecurityException(exceptionMessage.toString());
    }

    /**
     * Retrieves a correponding package name for a given uid. It will query all
     * packages that are associated with the given uid, but it will return only
     * the zeroth result.
     * Note: If package could not be found, a null is returned.
     * @hide
     */
    public static String getPackageNameForUid(Context context, int uid) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) {
            return null;
        }
        return packages[0];
    }
}
