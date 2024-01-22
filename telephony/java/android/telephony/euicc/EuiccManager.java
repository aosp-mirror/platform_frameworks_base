/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.telephony.euicc;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SuppressAutoDoc;
import android.annotation.SystemApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.telephony.euicc.EuiccCardManager.ResetOption;
import android.util.Log;

import com.android.internal.telephony.euicc.IEuiccController;
import com.android.internal.telephony.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * EuiccManager is the application interface to eUICCs, or eSIMs/embedded SIMs.
 *
 * <p>You do not instantiate this class directly; instead, you retrieve an instance through
 * {@link Context#getSystemService(String)} and {@link Context#EUICC_SERVICE}. This instance will be
 * created using the default eUICC.
 *
 * <p>On a device with multiple eUICCs, you may want to create multiple EuiccManagers. To do this
 * you can call {@link #createForCardId}.
 *
 * <p>See {@link #isEnabled} before attempting to use these APIs.
 */
@RequiresFeature(PackageManager.FEATURE_TELEPHONY_EUICC)
public class EuiccManager {
    private static final String TAG = "EuiccManager";

    /**
     * Intent action to launch the embedded SIM (eUICC) management settings screen.
     *
     * <p>This screen shows a list of embedded profiles and offers the user the ability to switch
     * between them, download new profiles, and delete unused profiles.
     *
     * <p>The activity will immediately finish with {@link android.app.Activity#RESULT_CANCELED} if
     * {@link #isEnabled} is false.
     *
     * This is ued by non-LPA app to bring up LUI.
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS =
            "android.telephony.euicc.action.MANAGE_EMBEDDED_SUBSCRIPTIONS";


    /**
     * Intent action to transfer an embedded subscriptions.
     *
     * <p> Action sent by apps (such as the Settings app) to the Telephony framework to transfer an
     * embedded subscription.
     *
     * <p> Requires that the calling app has the
     * {@code android.Manifest.permission#MODIFY_PHONE_STATE} permission.
     *
     * <p>The activity will immediately finish with {@link android.app.Activity#RESULT_CANCELED} if
     * {@link #isEnabled} is false.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public static final String ACTION_TRANSFER_EMBEDDED_SUBSCRIPTIONS =
            "android.telephony.euicc.action.TRANSFER_EMBEDDED_SUBSCRIPTIONS";

    /**
     * Intent action to convert the physical subscription to an embedded subscription.
     *
     * <p> Action sent by apps (such as the Settings app) to the Telephony framework to convert
     * physical sim to embedded sim.
     *
     * <p> Requires that the calling app has the
     * {@code android.Manifest.permission#MODIFY_PHONE_STATE} permission.
     *
     * <p>The activity will immediately finish with {@link android.app.Activity#RESULT_CANCELED} if
     * {@link #isEnabled} is false.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public static final String ACTION_CONVERT_TO_EMBEDDED_SUBSCRIPTION =
            "android.telephony.euicc.action.CONVERT_TO_EMBEDDED_SUBSCRIPTION";

    /**
     * Broadcast Action: The eUICC OTA status is changed.
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public static final String ACTION_OTA_STATUS_CHANGED =
            "android.telephony.euicc.action.OTA_STATUS_CHANGED";

    /**
     * Broadcast Action: The action sent to carrier app so it knows the carrier setup is not
     * completed.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NOTIFY_CARRIER_SETUP_INCOMPLETE =
            "android.telephony.euicc.action.NOTIFY_CARRIER_SETUP_INCOMPLETE";

    /**
     * Intent action to provision an embedded subscription.
     *
     * <p>May be called during device provisioning to launch a screen to perform embedded SIM
     * provisioning, e.g. if no physical SIM is present and the user elects to configure their
     * embedded SIM.
     *
     * <p>The activity will immediately finish with {@link android.app.Activity#RESULT_CANCELED} if
     * {@link #isEnabled} is false.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_EMBEDDED_SUBSCRIPTION =
            "android.telephony.euicc.action.PROVISION_EMBEDDED_SUBSCRIPTION";

    /**
     * Intent action to handle a resolvable error.
     * @hide
     */
    public static final String ACTION_RESOLVE_ERROR =
            "android.telephony.euicc.action.RESOLVE_ERROR";

    /**
     * Intent action sent by system apps (such as the Settings app) to the Telephony framework to
     * enable or disable a subscription. Must be accompanied with {@link #EXTRA_SUBSCRIPTION_ID} and
     * {@link #EXTRA_ENABLE_SUBSCRIPTION}, and optionally {@link #EXTRA_FROM_SUBSCRIPTION_ID}.
     *
     * <p>Requires the caller to be a privileged process with the
     * {@link android.permission#CALL_PRIVILEGED} permission for the intent to reach the Telephony
     * stack.
     *
     * <p>Unlike {@link #switchToSubscription(int, PendingIntent)}, using this action allows the
     * underlying eUICC service (i.e. the LPA app) to control the UI experience during this
     * operation. The action is received by the Telephony framework, which in turn selects and
     * launches an appropriate LPA activity to present UI to the user. For example, the activity may
     * show a confirmation dialog, a progress dialog, or an error dialog when necessary.
     *
     * <p>The launched activity will immediately finish with
     * {@link android.app.Activity#RESULT_CANCELED} if {@link #isEnabled} is false.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_TOGGLE_SUBSCRIPTION_PRIVILEGED =
            "android.telephony.euicc.action.TOGGLE_SUBSCRIPTION_PRIVILEGED";

    /**
     * Intent action sent by system apps (such as the Settings app) to the Telephony framework to
     * delete a subscription. Must be accompanied with {@link #EXTRA_SUBSCRIPTION_ID}.
     *
     * <p>Requires the caller to be a privileged process with the
     * {@link android.permission#CALL_PRIVILEGED} permission for the intent to reach the Telephony
     * stack.
     *
     * <p>Unlike {@link #deleteSubscription(int, PendingIntent)}, using this action allows the
     * underlying eUICC service (i.e. the LPA app) to control the UI experience during this
     * operation. The action is received by the Telephony framework, which in turn selects and
     * launches an appropriate LPA activity to present UI to the user. For example, the activity may
     * show a confirmation dialog, a progress dialog, or an error dialog when necessary.
     *
     * <p>The launched activity will immediately finish with
     * {@link android.app.Activity#RESULT_CANCELED} if {@link #isEnabled} is false.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_DELETE_SUBSCRIPTION_PRIVILEGED =
            "android.telephony.euicc.action.DELETE_SUBSCRIPTION_PRIVILEGED";

    /**
     * Intent action sent by system apps (such as the Settings app) to the Telephony framework to
     * rename a subscription. Must be accompanied with {@link #EXTRA_SUBSCRIPTION_ID} and
     * {@link #EXTRA_SUBSCRIPTION_NICKNAME}.
     *
     * <p>Requires the caller to be a privileged process with the
     * {@link android.permission#CALL_PRIVILEGED} permission for the intent to reach the Telephony
     * stack.
     *
     * <p>Unlike {@link #updateSubscriptionNickname(int, String, PendingIntent)}, using this action
     * allows the the underlying eUICC service (i.e. the LPA app) to control the UI experience
     * during this operation. The action is received by the Telephony framework, which in turn
     * selects and launches an appropriate LPA activity to present UI to the user. For example, the
     * activity may show a confirmation dialog, a progress dialog, or an error dialog when
     * necessary.
     *
     * <p>The launched activity will immediately finish with
     * {@link android.app.Activity#RESULT_CANCELED} if {@link #isEnabled} is false.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_RENAME_SUBSCRIPTION_PRIVILEGED =
            "android.telephony.euicc.action.RENAME_SUBSCRIPTION_PRIVILEGED";

    /**
     * Intent action sent by a carrier app to launch the eSIM activation flow provided by the LPA UI
     * (LUI). The carrier app must send this intent with one of the following:
     *
     * <p>{@link #EXTRA_USE_QR_SCANNER} not set or set to false: The LPA should try to get an
     * activation code from the carrier app by binding to the carrier app service implementing
     * {@code android.service.euicc.EuiccService#ACTION_BIND_CARRIER_PROVISIONING_SERVICE}.
     * <p>{@link #EXTRA_USE_QR_SCANNER} set to true: The LPA should launch a QR scanner for the user
     * to scan an eSIM profile QR code.
     *
     * <p>Upon completion, the LPA should return one of the following results to the carrier app:
     *
     * <p>{@code Activity.RESULT_OK}: The LPA has succeeded in downloading the new eSIM profile.
     * <p>{@code Activity.RESULT_CANCELED}: The carrier app should treat this as if the user pressed
     * the back button.
     * <p>Anything else: The carrier app should treat this as an error.
     *
     * <p>LPA needs to check if caller's package name is allowed to perform this action.
     **/
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_START_EUICC_ACTIVATION =
            "android.telephony.euicc.action.START_EUICC_ACTIVATION";

    /**
     * Result code for an operation indicating that the operation succeeded.
     */
    public static final int EMBEDDED_SUBSCRIPTION_RESULT_OK = 0;

    /**
     * Result code for an operation indicating that the user must take some action before the
     * operation can continue.
     *
     * @see #startResolutionActivity
     */
    public static final int EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR = 1;

    /**
     * Result code for an operation indicating that an unresolvable error occurred.
     *
     * {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE} will be populated with a detailed error
     * code for logging/debugging purposes only.
     */
    public static final int EMBEDDED_SUBSCRIPTION_RESULT_ERROR = 2;

    /**
     * Key for an extra set on the {@link #ACTION_PROVISION_EMBEDDED_SUBSCRIPTION} intent for which
     * kind of activation flow will be evolved. (see {@code EUICC_ACTIVATION_})
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ACTIVATION_TYPE =
            "android.telephony.euicc.extra.ACTIVATION_TYPE";

    /**
     * Key for an extra set on {@link PendingIntent} result callbacks providing a detailed result
     * code.
     *
     * <p>The value of this key is an integer and contains two portions. The first byte is
     * OperationCode and the reaming three bytes is the ErrorCode.
     *
     * OperationCode is the first byte of the result code and is a categorization which defines what
     * type of operation took place when an error occurred. e.g {@link #OPERATION_DOWNLOAD} means
     * the error is related to download.Since the OperationCode only uses at most one byte, the
     * maximum allowed quantity is 255(0xFF).
     *
     * ErrorCode is the remaining three bytes of the result code, and it denotes what happened.
     * e.g a combination of {@link #OPERATION_DOWNLOAD} and {@link #ERROR_TIME_OUT} will suggest the
     * download operation has timed out. The only exception here is
     * {@link #OPERATION_SMDX_SUBJECT_REASON_CODE}, where instead of ErrorCode, SubjectCode[5.2.6.1
     * from GSMA (SGP.22 v2.2) and ReasonCode[5.2.6.2] from GSMA (SGP.22 v2.2) are encoded. @see
     * {@link #EXTRA_EMBEDDED_SUBSCRIPTION_SMDX_SUBJECT_CODE} and
     * {@link #EXTRA_EMBEDDED_SUBSCRIPTION_SMDX_REASON_CODE}
     *
     * In the case where ErrorCode contains a value of 0, it means it's an unknown error. E.g Intent
     * only contains {@link #OPERATION_DOWNLOAD} and ErrorCode is 0 implies this is an unknown
     * Download error.
     *
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_OPERATION_CODE
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_ERROR_CODE
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_SMDX_SUBJECT_CODE
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_SMDX_REASON_CODE
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DETAILED_CODE";

    /**
     * Key for an extra set on {@link PendingIntent} result callbacks providing a
     * OperationCode of {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE},
     * value will be an int.
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_OPERATION_CODE =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_OPERATION_CODE";

    /**
     * Key for an extra set on {@link PendingIntent} result callbacks providing a
     * ErrorCode of {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE},
     * value will be an int.
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_ERROR_CODE =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_ERROR_CODE";

    /**
     * Key for an extra set on {@link PendingIntent} result callbacks providing a
     * SubjectCode[5.2.6.1] from GSMA (SGP.22 v2.2) decoded from
     * {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE}.
     * The value of this extra will be a String.
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_SMDX_SUBJECT_CODE =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_SMDX_SUBJECT_CODE";

    /**
     * Key for an extra set on {@link PendingIntent} result callbacks providing a
     * ReasonCode[5.2.6.2] from GSMA (SGP.22 v2.2) decoded from
     * {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE}.
     * The value of this extra will be a String.
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_SMDX_REASON_CODE =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_SMDX_REASON_CODE";

    /**
     * Key for an extra set on {@code #getDownloadableSubscriptionMetadata} PendingIntent result
     * callbacks providing the downloadable subscription metadata.
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION";

    /**
     * Key for an extra set on {@link #getDefaultDownloadableSubscriptionList} PendingIntent result
     * callbacks providing the list of available downloadable subscriptions.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTIONS =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTIONS";

    /**
     * Key for an extra set on {@link PendingIntent} result callbacks providing the resolution
     * pending intent for {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR}s.
     * @hide
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT";

    /**
     * Key for an extra set on the {@link #EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT} intent
     * containing the EuiccService action to launch for resolution.
     * @hide
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_ACTION =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_RESOLUTION_ACTION";

    /**
     * Key for an extra set on the {@link #EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT} intent
     * providing the callback to execute after resolution is completed.
     * @hide
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_CALLBACK_INTENT =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_RESOLUTION_CALLBACK_INTENT";

    /**
     * Key for an extra set on the {@link #ACTION_PROVISION_EMBEDDED_SUBSCRIPTION} intent for
     * whether eSIM provisioning flow is forced to be started or not. If this extra hasn't been
     * set, eSIM provisioning flow may be skipped and the corresponding carrier's app will be
     * notified. Otherwise, eSIM provisioning flow will be started when
     * {@link #ACTION_PROVISION_EMBEDDED_SUBSCRIPTION} has been received.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_FORCE_PROVISION =
            "android.telephony.euicc.extra.FORCE_PROVISION";

    /**
     * Key for an extra set on privileged actions {@link #ACTION_TOGGLE_SUBSCRIPTION_PRIVILEGED},
     * {@link #ACTION_DELETE_SUBSCRIPTION_PRIVILEGED}, and
     * {@link #ACTION_RENAME_SUBSCRIPTION_PRIVILEGED} providing the ID of the targeted subscription.
     *
     * <p>Expected type of the extra data: int
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_SUBSCRIPTION_ID =
            "android.telephony.euicc.extra.SUBSCRIPTION_ID";

    /**
     * Key for an extra set on {@link #ACTION_TOGGLE_SUBSCRIPTION_PRIVILEGED} providing a boolean
     * value of whether to enable or disable the targeted subscription.
     *
     * <p>Expected type of the extra data: boolean
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ENABLE_SUBSCRIPTION =
            "android.telephony.euicc.extra.ENABLE_SUBSCRIPTION";

    /**
     * Key for an extra set on {@link #ACTION_RENAME_SUBSCRIPTION_PRIVILEGED} providing a new
     * nickname for the targeted subscription.
     *
     * <p>Expected type of the extra data: String
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_SUBSCRIPTION_NICKNAME =
            "android.telephony.euicc.extra.SUBSCRIPTION_NICKNAME";

    /**
     * Key for an extra set on {@link #ACTION_TOGGLE_SUBSCRIPTION_PRIVILEGED} providing the ID of
     * the subscription we're toggling from. This extra is optional and is only used for UI
     * purposes by the underlying eUICC service (i.e. the LPA app), such as displaying a dialog
     * titled "Switch X with Y". If set, the provided subscription will be used as the "from"
     * subscription in UI (the "X" in the dialog example). Otherwise, the currently active
     * subscription that will be disabled is the "from" subscription.
     *
     * <p>Expected type of the extra data: int
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_FROM_SUBSCRIPTION_ID =
            "android.telephony.euicc.extra.FROM_SUBSCRIPTION_ID";

    /**
     * Key for an extra set on privileged actions {@link #ACTION_TOGGLE_SUBSCRIPTION_PRIVILEGED}
     * providing the physical slot ID of the target slot.
     *
     * <p>Expected type of the extra data: int
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PHYSICAL_SLOT_ID =
            "android.telephony.euicc.extra.PHYSICAL_SLOT_ID";


    /**
     * Key for an extra set on actions {@link #ACTION_START_EUICC_ACTIVATION} providing a boolean
     * value of whether to start eSIM activation with QR scanner.
     *
     * <p>Expected type of the extra data: boolean
     **/
    public static final String EXTRA_USE_QR_SCANNER =
            "android.telephony.euicc.extra.USE_QR_SCANNER";

    /**
     * Optional meta-data attribute for a carrier app providing an icon to use to represent the
     * carrier. If not provided, the app's launcher icon will be used as a fallback.
     */
    public static final String META_DATA_CARRIER_ICON = "android.telephony.euicc.carriericon";

    /**
     * Euicc activation type which will be included in {@link #EXTRA_ACTIVATION_TYPE} and used to
     * decide which kind of activation flow should be lauched.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"EUICC_ACTIVATION_"}, value = {
            EUICC_ACTIVATION_TYPE_DEFAULT,
            EUICC_ACTIVATION_TYPE_BACKUP,
            EUICC_ACTIVATION_TYPE_TRANSFER,
            EUICC_ACTIVATION_TYPE_ACCOUNT_REQUIRED,
    })
    public @interface EuiccActivationType{}


    /**
     * The default euicc activation type which includes checking server side and downloading the
     * profile based on carrier's download configuration.
     *
     * @hide
     */
    @SystemApi
    public static final int EUICC_ACTIVATION_TYPE_DEFAULT = 1;

    /**
     * The euicc activation type used when the default download process failed. LPA will start the
     * backup flow and try to download the profile for the carrier.
     *
     * @hide
     */
    @SystemApi
    public static final int EUICC_ACTIVATION_TYPE_BACKUP = 2;

    /**
     * The activation flow of eSIM seamless transfer will be used. LPA will start normal eSIM
     * activation flow and if it's failed, the name of the carrier selected will be recorded. After
     * the future device pairing, LPA will contact this carrier to transfer it from the other device
     * to this device.
     *
     * @hide
     */
    @SystemApi
    public static final int EUICC_ACTIVATION_TYPE_TRANSFER = 3;

    /**
     * The activation flow of eSIM requiring user account will be started. This can only be used
     * when there is user account signed in. Otherwise, the flow will be failed.
     *
     * @hide
     */
    @SystemApi
    public static final int EUICC_ACTIVATION_TYPE_ACCOUNT_REQUIRED = 4;

    /**
     * Euicc OTA update status which can be got by {@link #getOtaStatus}
     * @removed mistakenly exposed as system-api previously
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"EUICC_OTA_"}, value = {
            EUICC_OTA_IN_PROGRESS,
            EUICC_OTA_FAILED,
            EUICC_OTA_SUCCEEDED,
            EUICC_OTA_NOT_NEEDED,
            EUICC_OTA_STATUS_UNAVAILABLE

    })
    public @interface OtaStatus{}

    /**
     * An OTA is in progress. During this time, the eUICC is not available and the user may lose
     * network access.
     * @hide
     */
    @SystemApi
    public static final int EUICC_OTA_IN_PROGRESS = 1;

    /**
     * The OTA update failed.
     * @hide
     */
    @SystemApi
    public static final int EUICC_OTA_FAILED = 2;

    /**
     * The OTA update finished successfully.
     * @hide
     */
    @SystemApi
    public static final int EUICC_OTA_SUCCEEDED = 3;

    /**
     * The OTA update not needed since current eUICC OS is latest.
     * @hide
     */
    @SystemApi
    public static final int EUICC_OTA_NOT_NEEDED = 4;

    /**
     * The OTA status is unavailable since eUICC service is unavailable.
     * @hide
     */
    @SystemApi
    public static final int EUICC_OTA_STATUS_UNAVAILABLE = 5;

    /**
     * List of OperationCode corresponding to {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE}'s
     * value, an integer. @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"OPERATION_"}, value = {
            OPERATION_SYSTEM,
            OPERATION_SIM_SLOT,
            OPERATION_EUICC_CARD,
            OPERATION_SWITCH,
            OPERATION_DOWNLOAD,
            OPERATION_METADATA,
            OPERATION_EUICC_GSMA,
            OPERATION_APDU,
            OPERATION_SMDX,
            OPERATION_HTTP,
            OPERATION_SMDX_SUBJECT_REASON_CODE,
    })
    public @interface OperationCode {
    }

    /**
     * Internal system error.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int OPERATION_SYSTEM = 1;

    /**
     * SIM slot error. Failed to switch slot, failed to access the physical slot etc.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int OPERATION_SIM_SLOT = 2;

    /**
     * eUICC card error.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int OPERATION_EUICC_CARD = 3;

    /**
     * Generic switching profile error
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int OPERATION_SWITCH = 4;

    /**
     * Download profile error.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int OPERATION_DOWNLOAD = 5;

    /**
     * Subscription's metadata error
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int OPERATION_METADATA = 6;

    /**
     * eUICC returned an error defined in GSMA (SGP.22 v2.2) while running one of the ES10x
     * functions.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int OPERATION_EUICC_GSMA = 7;

    /**
     * The exception of failing to execute an APDU command. It can be caused by an error
     * happening on opening the basic or logical channel, or the response of the APDU command is
     * not success (0x9000).
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int OPERATION_APDU = 8;

    /**
     * SMDX(SMDP/SMDS) error
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int OPERATION_SMDX = 9;

    /**
     * SubjectCode[5.2.6.1] and ReasonCode[5.2.6.2] error from GSMA (SGP.22 v2.2)
     * When {@link #OPERATION_SMDX_SUBJECT_REASON_CODE} is used as the
     * {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE}, the remaining three bytes of the integer
     * result from {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE} will be used to stored the
     * SubjectCode and ReasonCode from the GSMA spec and NOT ErrorCode.
     *
     * The encoding will follow the format of:
     * 1. The first byte of the result will be 255(0xFF).
     * 2. Remaining three bytes(24 bits) will be split into six sections, 4 bits in each section.
     * 3. A SubjectCode/ReasonCode will take 12 bits each.
     * 4. The maximum number can be represented per section is 15, as that is the maximum number
     * allowed to be stored into 4 bits
     * 5. Maximum supported nested category from GSMA is three layers. E.g 8.11.1.2 is not
     * supported.
     *
     * E.g given SubjectCode(8.11.1) and ReasonCode(5.1)
     *
     * Base10:  0       10      8       11      1       0       5       1
     * Base2:   0000    1010    1000    1011    0001    0000    0101    0001
     * Base16:  0       A       8       B       1       0       5       1
     *
     * Thus the integer stored in {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE} is
     * 0xA8B1051(176885841)
     *
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int OPERATION_SMDX_SUBJECT_REASON_CODE = 10;

    /**
     * HTTP error
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int OPERATION_HTTP = 11;

    /**
     * List of ErrorCode corresponding to {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE}
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ERROR_"}, value = {
            ERROR_CARRIER_LOCKED,
            ERROR_INVALID_ACTIVATION_CODE,
            ERROR_INVALID_CONFIRMATION_CODE,
            ERROR_INCOMPATIBLE_CARRIER,
            ERROR_EUICC_INSUFFICIENT_MEMORY,
            ERROR_TIME_OUT,
            ERROR_EUICC_MISSING,
            ERROR_UNSUPPORTED_VERSION,
            ERROR_SIM_MISSING,
            ERROR_INSTALL_PROFILE,
            ERROR_DISALLOWED_BY_PPR,
            ERROR_ADDRESS_MISSING,
            ERROR_CERTIFICATE_ERROR,
            ERROR_NO_PROFILES_AVAILABLE,
            ERROR_CONNECTION_ERROR,
            ERROR_INVALID_RESPONSE,
            ERROR_OPERATION_BUSY,
    })
    public @interface ErrorCode{}

    /**
     * Operation such as downloading/switching to another profile failed due to device being
     * carrier locked.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_CARRIER_LOCKED = 10000;

    /**
     * The activation code(SGP.22 v2.2 section[4.1]) is invalid.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_INVALID_ACTIVATION_CODE = 10001;

    /**
     * The confirmation code(SGP.22 v2.2 section[4.7]) is invalid.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_INVALID_CONFIRMATION_CODE = 10002;

    /**
     * The profile's carrier is incompatible with the LPA.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_INCOMPATIBLE_CARRIER = 10003;

    /**
     * There is no more space available on the eUICC for new profiles.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_EUICC_INSUFFICIENT_MEMORY = 10004;

    /**
     * Timed out while waiting for an operation to complete. i.e restart, disable,
     * switch reset etc.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_TIME_OUT = 10005;

    /**
     * eUICC is missing or defective on the device.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_EUICC_MISSING = 10006;

    /**
     * The eUICC card(hardware) version is incompatible with the software
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_UNSUPPORTED_VERSION = 10007;

    /**
     * No SIM card is available in the device.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_SIM_MISSING = 10008;

    /**
     * Failure to load the profile onto the eUICC card. e.g
     * 1. iccid of the profile already exists on the eUICC.
     * 2. GSMA(.22 v2.2) Profile Install Result - installFailedDueToDataMismatch
     * 3. operation was interrupted
     * 4. SIMalliance error in PEStatus(SGP.22 v2.2 section 2.5.6.1)
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_INSTALL_PROFILE = 10009;

    /**
     * Failed to load profile onto eUICC due to Profile Policy Rules.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_DISALLOWED_BY_PPR = 10010;


    /**
     * Address is missing e.g SMDS/SMDP address is missing.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_ADDRESS_MISSING = 10011;

    /**
     * Certificate needed for authentication is not valid or missing. E.g  SMDP/SMDS authentication
     * failed.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_CERTIFICATE_ERROR = 10012;


    /**
     * No profiles available.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_NO_PROFILES_AVAILABLE = 10013;

    /**
     * Failure to create a connection.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_CONNECTION_ERROR = 10014;

    /**
     * Response format is invalid. e.g SMDP/SMDS response contains invalid json, header or/and ASN1.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_INVALID_RESPONSE = 10015;

    /**
     * The operation is currently busy, try again later.
     * @see #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE for details
     */
    public static final int ERROR_OPERATION_BUSY = 10016;

    /**
     * Failure due to target port is not supported.
     * @see #switchToSubscription(int, int, PendingIntent)
     */
    public static final int ERROR_INVALID_PORT = 10017;

    /** Temporary failure to retrieve available memory because eUICC is not ready. */
    @FlaggedApi(Flags.FLAG_ESIM_AVAILABLE_MEMORY)
    public static final long EUICC_MEMORY_FIELD_UNAVAILABLE = -1L;

    /**
     * Apps targeting on Android T and beyond will get exception whenever switchToSubscription
     * without portIndex is called for disable subscription.
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long SWITCH_WITHOUT_PORT_INDEX_EXCEPTION_ON_DISABLE = 218393363L;

    /**
     * With support for MEP(multiple enabled profile) in Android T, a SIM card can enable multiple
     * profile on different port. If apps are not target SDK T yet and keep calling the
     * switchToSubscription or download API without specifying the port index, we should
     * keep the existing behaviour by always use port index 0 even the device itself has MEP eUICC,
     * this is for carrier app's backward compatibility.
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long SHOULD_RESOLVE_PORT_INDEX_FOR_APPS = 224562872L;

    /**
     * Starting with Android U, a port is available if it is active without an enabled profile
     * on it or calling app can activate a new profile on the selected port without any user
     * interaction.
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final long INACTIVE_PORT_AVAILABILITY_CHECK = 240273417L;

    private final Context mContext;
    private int mCardId;

    /** @hide */
    public EuiccManager(Context context) {
        mContext = context;
        mCardId = getCardIdForDefaultEuicc();
    }

    /** @hide */
    private EuiccManager(Context context, int cardId) {
        mContext = context;
        mCardId = cardId;
    }

    /**
     * Create a new EuiccManager object pinned to the given card ID.
     *
     * @return an EuiccManager that uses the given card ID for all calls.
     */
    @NonNull
    public EuiccManager createForCardId(int cardId) {
        return new EuiccManager(mContext, cardId);
    }

    /**
     * Whether embedded subscriptions are currently enabled.
     *
     * <p>Even on devices with the {@link PackageManager#FEATURE_TELEPHONY_EUICC} feature, embedded
     * subscriptions may be turned off, e.g. because of a carrier restriction from an inserted
     * physical SIM. Therefore, this runtime check should be used before accessing embedded
     * subscription APIs.
     *
     * @return true if embedded subscriptions are currently enabled.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    public boolean isEnabled() {
        // In the future, this may reach out to IEuiccController (if non-null) to check any dynamic
        // restrictions.
        return getIEuiccController() != null && refreshCardIdIfUninitialized();
    }

    /**
     * Returns the EID identifying the eUICC hardware.
     *
     * <p>Requires that the calling app has carrier privileges on the active subscription on the
     * current eUICC. A calling app with carrier privileges for one eUICC may not necessarily have
     * access to the EID of another eUICC.
     *
     * @return the EID. May be null if the eUICC is not ready.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    @Nullable
    public String getEid() {
        if (!isEnabled()) {
            return null;
        }
        try {
            return getIEuiccController().getEid(mCardId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the available memory in bytes of the eUICC.
     *
     * @return the available memory in bytes. May be {@link #EUICC_MEMORY_FIELD_UNAVAILABLE} if the
     *     eUICC is not ready. Check {@link #isEnabled} for more information.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC} or
     *          device doesn't support querying this information from the eUICC.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @FlaggedApi(Flags.FLAG_ESIM_AVAILABLE_MEMORY)
    @RequiresPermission(
            anyOf = {
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                "carrier privileges"
            })
    public long getAvailableMemoryInBytes() {
        if (!isEnabled()) {
            return EUICC_MEMORY_FIELD_UNAVAILABLE;
        }
        try {
            return getIEuiccController()
                    .getAvailableMemoryInBytes(mCardId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current status of eUICC OTA.
     *
     * <p>Requires the {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @return the status of eUICC OTA. If the eUICC is not ready,
     *         {@link OtaStatus#EUICC_OTA_STATUS_UNAVAILABLE} will be returned.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public int getOtaStatus() {
        if (!isEnabled()) {
            return EUICC_OTA_STATUS_UNAVAILABLE;
        }
        try {
            return getIEuiccController().getOtaStatus(mCardId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Attempt to download the given {@link DownloadableSubscription}.
     *
     * <p>Requires the {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission,
     * or the calling app must be authorized to manage both the currently-active subscription on the
     * current eUICC and the subscription to be downloaded according to the subscription metadata.
     * Without the former, an {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} will be
     * returned in the callback intent to prompt the user to accept the download.
     *
     * <p>On a multi-active SIM device, requires the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission, or a calling app
     * only if the targeted eUICC does not currently have an active subscription or the calling app
     * is authorized to manage the active subscription on the target eUICC, and the calling app is
     * authorized to manage any active subscription on any SIM. Without it, an
     * {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} will be returned in the callback
     * intent to prompt the user to accept the download. The caller should also be authorized to
     * manage the subscription to be downloaded.
     *
     * <p>If device support {@link PackageManager#FEATURE_TELEPHONY_EUICC_MEP} and
     * switchAfterDownload is {@code true}, the subscription will be enabled on an esim port based
     * on the following selection rules:
     * <ul>
     *    <li>In SS(Single SIM) mode, if the embedded slot already has an active port, then download
     *    and enable the subscription on this port.
     *    <li>In SS mode, if the embedded slot is not active, then try to download and enable the
     *    subscription on the default port 0 of eUICC.
     *    <li>In DSDS mode, find first available port to download and enable the subscription.
     *    (see {@link #isSimPortAvailable(int)})
     *</ul>
     * If there is no available port, an {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR}
     * will be returned in the callback intent to prompt the user to disable an already-active
     * subscription.
     *
     * @param subscription the subscription to download.
     * @param switchAfterDownload if true, the profile will be activated upon successful download.
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void downloadSubscription(DownloadableSubscription subscription,
            boolean switchAfterDownload, PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().downloadSubscription(mCardId, subscription, switchAfterDownload,
                    mContext.getOpPackageName(), null /* resolvedBundle */, callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start an activity to resolve a user-resolvable error.
     *
     * <p>If an operation returns {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR}, this
     * method may be called to prompt the user to resolve the issue.
     *
     * <p>This method may only be called once for a particular error.
     *
     * @param activity the calling activity (which should be in the foreground).
     * @param requestCode an application-specific request code which will be provided to
     *     {@link Activity#onActivityResult} upon completion. Note that the operation may still be
     *     in progress when the resolution activity completes; it is not fully finished until the
     *     callback intent is triggered.
     * @param resultIntent the Intent provided to the initial callback intent which failed with
     *     {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR}.
     * @param callbackIntent a PendingIntent to launch when the operation completes. This is
     *     trigered upon completion of the original operation that required user resolution.
     * @throws android.content.IntentSender.SendIntentException if called more than once.
     */
    public void startResolutionActivity(Activity activity, int requestCode, Intent resultIntent,
            PendingIntent callbackIntent) throws IntentSender.SendIntentException {
        PendingIntent resolutionIntent =
                resultIntent.getParcelableExtra(EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT, android.app.PendingIntent.class);
        if (resolutionIntent == null) {
            throw new IllegalArgumentException("Invalid result intent");
        }
        Intent fillInIntent = new Intent();
        fillInIntent.putExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_CALLBACK_INTENT,
                callbackIntent);
        activity.startIntentSenderForResult(resolutionIntent.getIntentSender(), requestCode,
                fillInIntent, 0 /* flagsMask */, 0 /* flagsValues */, 0 /* extraFlags */);
    }

    /**
     * Continue an operation after the user resolves an error.
     *
     * <p>To be called by the LUI upon completion of a resolvable error flow.
     *
     * <p>Requires that the calling app has the
     * {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @param resolutionIntent The original intent used to start the LUI.
     * @param resolutionExtras Resolution-specific extras depending on the result of the resolution.
     *     For example, this may indicate whether the user has consented or may include the input
     *     they provided.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void continueOperation(Intent resolutionIntent, Bundle resolutionExtras) {
        if (!isEnabled()) {
            PendingIntent callbackIntent =
                    resolutionIntent.getParcelableExtra(
                            EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_CALLBACK_INTENT, android.app.PendingIntent.class);
            if (callbackIntent != null) {
                sendUnavailableError(callbackIntent);
            }
            return;
        }
        try {
            getIEuiccController().continueOperation(mCardId, resolutionIntent, resolutionExtras);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Fills in the metadata for a DownloadableSubscription.
     *
     * <p>May be used in cases that a DownloadableSubscription was constructed to download a
     * profile, but the metadata for the profile is unknown (e.g. we only know the activation code).
     * The callback will be triggered with an Intent with
     * {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION} set to the
     * downloadable subscription metadata upon success.
     *
     * <p>Requires that the calling app has the
     * {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission. This is for
     * internal system use only.
     *
     * @param subscription the subscription which needs metadata filled in
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void getDownloadableSubscriptionMetadata(
            DownloadableSubscription subscription, PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().getDownloadableSubscriptionMetadata(mCardId, subscription,
                    mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets metadata for subscription which are available for download on this device.
     *
     * <p>Subscriptions returned here may be passed to {@link #downloadSubscription}. They may have
     * been pre-assigned to this particular device, for example. The callback will be triggered with
     * an Intent with {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTIONS} set to the
     * list of available subscriptions upon success.
     *
     * <p>Requires that the calling app has the
     * {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission. This is for
     * internal system use only.
     *
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void getDefaultDownloadableSubscriptionList(PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().getDefaultDownloadableSubscriptionList(mCardId,
                    mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns information about the eUICC chip/device.
     *
     * @return the {@link EuiccInfo}. May be null if the eUICC is not ready.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    @Nullable
    public EuiccInfo getEuiccInfo() {
        if (!isEnabled()) {
            return null;
        }
        try {
            return getIEuiccController().getEuiccInfo(mCardId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes the given subscription.
     *
     * <p>If this subscription is currently active, the device will first switch away from it onto
     * an "empty" subscription.
     *
     * <p>Requires that the calling app has carrier privileges according to the metadata of the
     * profile to be deleted, or the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @param subscriptionId the ID of the subscription to delete.
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void deleteSubscription(int subscriptionId, PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().deleteSubscription(mCardId,
                    subscriptionId, mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Switch to (enable) the given subscription.
     *
     * <p>Requires the {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission,
     * or the calling app must be authorized to manage both the currently-active subscription and
     * the subscription to be enabled according to the subscription metadata. Without the former,
     * an {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} will be returned in the callback
     * intent to prompt the user to accept the download.
     *
     * <p>On a multi-active SIM device, requires the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission, or a calling app
     *  only if the targeted eUICC does not currently have an active subscription or the calling app
     * is authorized to manage the active subscription on the target eUICC, and the calling app is
     * authorized to manage any active subscription on any SIM. Without it, an
     * {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} will be returned in the callback
     * intent to prompt the user to accept the download. The caller should also be authorized to
     * manage the subscription to be enabled.
     *
     * <p> From Android T, devices might support {@link PackageManager#FEATURE_TELEPHONY_EUICC_MEP},
     * the subscription can be installed on different port from the eUICC. Calling apps with
     * carrier privilege (see {@link TelephonyManager#hasCarrierPrivileges}) over the currently
     * active subscriptions can use {@link #switchToSubscription(int, int, PendingIntent)} to
     * specify which port to enable the subscription. Otherwise, use this API to enable the
     * subscription on the eUICC and the platform will internally resolve a port based on following
     * rules:
     * <ul>
     *    <li>always use the default port 0 is eUICC does not support MEP or the apps are
     *    not targeting on Android T.
     *    <li>In SS(Single SIM) mode, if the embedded slot already has an active port, then enable
     *    the subscription on this port.
     *    <li>In SS mode, if the embedded slot is not active, then try to enable the subscription on
     *    the default port 0 of eUICC.
     *    <li>In DSDS mode, find first available port to enable the subscription.
     *    (see {@link #isSimPortAvailable(int)})
     *</ul>
     * If there is no available port, an {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR}
     * will be returned in the callback intent to prompt the user to disable an already-active
     * subscription.
     *
     * @param subscriptionId the ID of the subscription to enable. May be
     *     {@link android.telephony.SubscriptionManager#INVALID_SUBSCRIPTION_ID} to deactivate the
     *     current profile without activating another profile to replace it. Calling apps targeting
     *     on android T must use {@link #switchToSubscription(int, int, PendingIntent)} API for
     *     disable profile, port index can be found from {@link SubscriptionInfo#getPortIndex()}.
     *     If it's a disable operation, requires the
     *     {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission, or the
     *     calling app must be authorized to manage the active subscription on the target eUICC.
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void switchToSubscription(int subscriptionId, PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                     && getIEuiccController().isCompatChangeEnabled(mContext.getOpPackageName(),
                     SWITCH_WITHOUT_PORT_INDEX_EXCEPTION_ON_DISABLE)) {
                // Apps targeting on Android T and beyond will get exception whenever
                // switchToSubscription without portIndex is called with INVALID_SUBSCRIPTION_ID.
                Log.e(TAG, "switchToSubscription without portIndex is not allowed for"
                        + " disable operation");
                throw new IllegalArgumentException("Must use switchToSubscription with portIndex"
                        + " API for disable operation");
            }
            getIEuiccController().switchToSubscription(mCardId,
                    subscriptionId, mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Switch to (enable) the given subscription.
     *
     * <p> Requires the {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission,
     * or the caller must be having both the carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}) over any currently active subscriptions
     * and the subscription to be enabled according to the subscription metadata.
     * Without the former permissions, an SecurityException is thrown.
     *
     * <p> If the caller is passing invalid port index,
     * an {@link #EMBEDDED_SUBSCRIPTION_RESULT_ERROR} with detailed error code
     * {@link #ERROR_INVALID_PORT} will be returned. The port index is invalid if one of the
     * following requirements is met:
     * <ul>
     *     <li>index is beyond the range of {@link UiccCardInfo#getPorts()}.
     *     <li>In SS(Single SIM) mode, the embedded slot already has an active port with different
     *     port index.
     *     <li>In DSDS mode, if the psim slot is active and the embedded slot already has an active
     *     empty port with different port index.
     * </ul>
     *
     * <p> Depending on the target port and permission check,
     * an {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} might be returned to the callback
     * intent to prompt the user to authorize before the switch.
     *
     * @param subscriptionId the ID of the subscription to enable. May be
     *     {@link android.telephony.SubscriptionManager#INVALID_SUBSCRIPTION_ID} to deactivate the
     *     current profile without activating another profile to replace it. If it's a disable
     *     operation, requires the {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS}
     *     permission, or the calling app must be authorized to manage the active subscription on
     *     the target eUICC. From Android T, multiple enabled profiles is supported. Calling apps
     *     targeting on android T must use {@link #switchToSubscription(int, int, PendingIntent)}
     *     API for disable profile, port index can be found from
     *     {@link SubscriptionInfo#getPortIndex()}.
     * @param portIndex the index of the port to target for the enabled subscription
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void switchToSubscription(int subscriptionId, int portIndex,
            @NonNull PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            boolean canWriteEmbeddedSubscriptions = mContext.checkCallingOrSelfPermission(
                    Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
                    == PackageManager.PERMISSION_GRANTED;
            // If the caller is not privileged caller and does not have the carrier privilege over
            // any active subscription, do not continue.
            if (!canWriteEmbeddedSubscriptions && !getIEuiccController()
                    .hasCarrierPrivilegesForPackageOnAnyPhone(mContext.getOpPackageName())) {
                Log.e(TAG, "Not permitted to use switchToSubscription with portIndex");
                throw new SecurityException(
                        "Must have carrier privileges to use switchToSubscription with portIndex");
            }
            getIEuiccController().switchToSubscriptionWithPort(mCardId,
                    subscriptionId, portIndex, mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Update the nickname for the given subscription.
     *
     * <p>Requires that the calling app has carrier privileges according to the metadata of the
     * profile to be updated, or the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @param subscriptionId the ID of the subscription to update.
     * @param nickname the new nickname to apply.
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void updateSubscriptionNickname(
            int subscriptionId, @Nullable String nickname, @NonNull PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().updateSubscriptionNickname(mCardId,
                    subscriptionId, nickname, mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Erase all operational subscriptions and reset the eUICC.
     *
     * <p>Requires that the calling app has the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     *
     * @deprecated From R, callers should specify a flag for specific set of subscriptions to erase
     * and use {@link #eraseSubscriptions(int, PendingIntent)} instead
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    @Deprecated
    public void eraseSubscriptions(@NonNull PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().eraseSubscriptions(mCardId, callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Erase all specific subscriptions and reset the eUICC.
     *
     * <p>Requires that the calling app has the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @param options flag indicating specific set of subscriptions to erase
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void eraseSubscriptions(
            @ResetOption int options, @NonNull PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().eraseSubscriptionsWithOptions(mCardId, options, callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Ensure that subscriptions will be retained on the next factory reset.
     *
     * <p>By default, all subscriptions on the eUICC are erased the first time a device boots (ever
     * and after factory resets). This ensures that the data is wiped after a factory reset is
     * performed via fastboot or recovery mode, as these modes do not support the necessary radio
     * communication needed to wipe the eSIM.
     *
     * <p>However, this method may be called right before a factory reset issued via settings when
     * the user elects to retain subscriptions. Doing so will mark them for retention so that they
     * are not cleared after the ensuing reset.
     *
     * <p>Requires that the calling app has the {@link android.Manifest.permission#MASTER_CLEAR}
     * permission. This is for internal system use only.
     *
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     * @hide
     */
    public void retainSubscriptionsForFactoryReset(PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().retainSubscriptionsForFactoryReset(mCardId, callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the supported countries for eUICC.
     *
     * <p>Requires that the calling app has the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * <p>The supported country list will be replaced by {@code supportedCountries}. For how we
     * determine whether a country is supported please check {@link #isSupportedCountry}.
     *
     * @param supportedCountries is a list of strings contains country ISO codes in uppercase.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void setSupportedCountries(@NonNull List<String> supportedCountries) {
        if (!isEnabled()) {
            return;
        }
        try {
            getIEuiccController().setSupportedCountries(
                    true /* isSupported */,
                    supportedCountries.stream()
                        .map(String::toUpperCase).collect(Collectors.toList()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the unsupported countries for eUICC.
     *
     * <p>Requires that the calling app has the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * <p>The unsupported country list will be replaced by {@code unsupportedCountries}. For how we
     * determine whether a country is supported please check {@link #isSupportedCountry}.
     *
     * @param unsupportedCountries is a list of strings contains country ISO codes in uppercase.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void setUnsupportedCountries(@NonNull List<String> unsupportedCountries) {
        if (!isEnabled()) {
            return;
        }
        try {
            getIEuiccController().setSupportedCountries(
                    false /* isSupported */,
                    unsupportedCountries.stream()
                        .map(String::toUpperCase).collect(Collectors.toList()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the supported countries for eUICC.
     *
     * <p>Requires that the calling app has the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @return list of strings contains country ISO codes in uppercase.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    @NonNull
    public List<String> getSupportedCountries() {
        if (!isEnabled()) {
            return Collections.emptyList();
        }
        try {
            return getIEuiccController().getSupportedCountries(true /* isSupported */);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the unsupported countries for eUICC.
     *
     * <p>Requires that the calling app has the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @return list of strings contains country ISO codes in uppercase.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    @NonNull
    public List<String> getUnsupportedCountries() {
        if (!isEnabled()) {
            return Collections.emptyList();
        }
        try {
            return getIEuiccController().getSupportedCountries(false /* isSupported */);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the given country supports eUICC.
     *
     * <p>Supported country list has a higher prority than unsupported country list. If the
     * supported country list is not empty, {@code countryIso} will be considered as supported when
     * it exists in the supported country list. Otherwise {@code countryIso} is not supported. If
     * the supported country list is empty, {@code countryIso} will be considered as supported if it
     * does not exist in the unsupported country list. Otherwise {@code countryIso} is not
     * supported. If both supported and unsupported country lists are empty, then all countries are
     * consider be supported. For how to set supported and unsupported country list, please check
     * {@link #setSupportedCountries} and {@link #setUnsupportedCountries}.
     *
     * @param countryIso should be the ISO-3166 country code is provided in uppercase 2 character
     * format.
     * @return whether the given country supports eUICC or not.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public boolean isSupportedCountry(@NonNull String countryIso) {
        if (!isEnabled()) {
            return false;
        }
        try {
            return getIEuiccController().isSupportedCountry(countryIso.toUpperCase(Locale.ROOT));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Refreshes the cardId if its uninitialized, and returns whether we should continue the
     * operation.
     * <p>
     * Note that after a successful refresh, the mCardId may be TelephonyManager.UNSUPPORTED_CARD_ID
     * on older HALs. For backwards compatability, we continue to the LPA and let it decide which
     * card to use.
     */
    private boolean refreshCardIdIfUninitialized() {
        // Refresh mCardId if its UNINITIALIZED_CARD_ID
        if (mCardId == TelephonyManager.UNINITIALIZED_CARD_ID) {
            mCardId = getCardIdForDefaultEuicc();
        }
        return mCardId != TelephonyManager.UNINITIALIZED_CARD_ID;
    }

    private static void sendUnavailableError(PendingIntent callbackIntent) {
        try {
            callbackIntent.send(EMBEDDED_SUBSCRIPTION_RESULT_ERROR);
        } catch (PendingIntent.CanceledException e) {
            // Caller canceled the callback; do nothing.
        }
    }

    private static IEuiccController getIEuiccController() {
        return IEuiccController.Stub.asInterface(
                TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getEuiccControllerService()
                        .get());
    }

    private int getCardIdForDefaultEuicc() {
        int cardId = TelephonyManager.UNINITIALIZED_CARD_ID;

        if (Flags.enforceTelephonyFeatureMappingForPublicApis()) {
            PackageManager pm = mContext.getPackageManager();
            if (pm != null && pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC)) {
                TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
                cardId = tm.getCardIdForDefaultEuicc();
            }
        } else {
            TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
            cardId = tm.getCardIdForDefaultEuicc();
        }

        return cardId;
    }

    /**
     * Returns whether the passing portIndex is available.
     * A port is available if it is active without enabled profile on it or
     * calling app has carrier privilege over the profile installed on the selected port.
     *
     * <p> From Android U, a port is available if it is active without an enabled profile on it or
     * calling app can activate a new profile on the selected port without any user interaction.
     *
     * Always returns false if the cardId is a physical card.
     *
     * @param portIndex is an enumeration of the ports available on the UICC.
     * @return {@code true} if port is available
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    public boolean isSimPortAvailable(int portIndex) {
        try {
            return getIEuiccController().isSimPortAvailable(mCardId, portIndex,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the supported carrier ids for pSIM conversion.
     *
     * <p>Any existing pSIM conversion supported carrier list will be replaced
     * by the {@code carrierIds} set here.
     *
     * @param carrierIds is a list of carrierIds that supports pSIM conversion
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @throws IllegalStateException if this method is called when {@link #isEnabled} is false.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_PSIM_TO_ESIM_CONVERSION)
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void setPsimConversionSupportedCarriers(@NonNull Set<Integer> carrierIds) {
        if (!isEnabled()) {
            throw new IllegalStateException("Euicc is not enabled");
        }
        try {
            int[] arr = carrierIds.stream().mapToInt(Integer::intValue).toArray();
            getIEuiccController().setPsimConversionSupportedCarriers(arr);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Returns whether the given carrier id supports pSIM conversion or not.
     *
     * @param carrierId to check whether pSIM conversion is supported or not
     * @return whether the given carrier id supports pSIM conversion or not,
     *         or false if {@link #isEnabled} is false
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_PSIM_TO_ESIM_CONVERSION)
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public boolean isPsimConversionSupported(int carrierId) {
        if (!isEnabled()) {
            return false;
        }
        try {
            return getIEuiccController().isPsimConversionSupported(carrierId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }
}
