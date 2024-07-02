/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.view.autofill;

import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArraySet;
import android.view.View;

import com.android.internal.util.ArrayUtils;

import java.util.Arrays;
import java.util.Set;

/**
 * Feature flags associated with autofill.
 * @hide
 */
@TestApi
public class AutofillFeatureFlags {

    /**
     * {@code DeviceConfig} property used to set which Smart Suggestion modes for Augmented Autofill
     * are available.
     */
    public static final String DEVICE_CONFIG_AUTOFILL_SMART_SUGGESTION_SUPPORTED_MODES =
            "smart_suggestion_supported_modes";

    /**
     * Sets how long (in ms) the augmented autofill service is bound while idle.
     *
     * <p>Use {@code 0} to keep it permanently bound.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_AUGMENTED_SERVICE_IDLE_UNBIND_TIMEOUT =
            "augmented_service_idle_unbind_timeout";

    /**
     * Sets how long (in ms) the augmented autofill service request is killed if not replied.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_AUGMENTED_SERVICE_REQUEST_TIMEOUT =
            "augmented_service_request_timeout";

    /**
     * Sets allowed list for the autofill compatibility mode.
     *
     * The list of packages is {@code ":"} colon delimited, and each entry has the name of the
     * package and an optional list of url bar resource ids (the list is delimited by
     * brackets&mdash{@code [} and {@code ]}&mdash and is also comma delimited).
     *
     * <p>For example, a list with 3 packages {@code p1}, {@code p2}, and {@code p3}, where
     * package {@code p1} have one id ({@code url_bar}, {@code p2} has none, and {@code p3 }
     * have 2 ids {@code url_foo} and {@code url_bas}) would be
     * {@code p1[url_bar]:p2:p3[url_foo,url_bas]}
     */
    public static final String DEVICE_CONFIG_AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES =
            "compat_mode_allowed_packages";

    /**
     * Indicates Fill dialog feature enabled or not.
     */
    public static final String DEVICE_CONFIG_AUTOFILL_DIALOG_ENABLED =
            "autofill_dialog_enabled";

    /**
     * Sets the autofill hints allowed list for the fields that can trigger the fill dialog
     * feature at Activity starting.
     *
     * The list of autofill hints is {@code ":"} colon delimited.
     *
     *  <p>For example, a list with 3 hints {@code password}, {@code phone}, and
     * { @code emailAddress}, would be {@code password:phone:emailAddress}
     *
     * Note: By default the password field is enabled even there is no password hint in the list
     *
     * @see View#setAutofillHints(String...)
     * @hide
     */
    public static final String DEVICE_CONFIG_AUTOFILL_DIALOG_HINTS =
            "autofill_dialog_hints";

    // START CREDENTIAL MANAGER FLAGS //
    /**
     * Indicates whether credential manager tagged views should be ignored from autofill structures.
     * This flag is further gated by {@link #DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_ENABLED}
     */
    public static final String DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_IGNORE_VIEWS =
            "autofill_credential_manager_ignore_views";

    /**
     * Indicates CredentialManager feature enabled or not.
     * This is the overall feature flag. Individual behavior of credential manager may be controlled
     * via a different flag, but gated by this flag.
     */
    public static final String DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_ENABLED =
            "autofill_credential_manager_enabled";

    /**
     * Indicates whether credential manager tagged views should suppress fill and save dialog.
     * This flag is further gated by {@link #DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_ENABLED}
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_SUPPRESS_FILL_AND_SAVE_DIALOG =
            "autofill_credential_manager_suppress_fill_and_save_dialog";
    // END CREDENTIAL MANAGER FLAGS //

    // START AUTOFILL FOR ALL APPS FLAGS //
    /**
     * Sets the list of activities and packages denied for autofill
     *
     * The list is {@code ";"} colon delimited. Activities under a package is separated by
     * {@code ","}. Each package name much be followed by a {@code ":"}. Each package entry must be
     * ends with a {@code ";"}
     *
     * <p>For example, a list with only 1 package would be, {@code Package1:;}. A list with one
     * denied activity {@code Activity1} under {@code Package1} and a full denied package
     * {@code Package2} would be {@code Package1:Activity1;Package2:;}
     */
    public static final String DEVICE_CONFIG_PACKAGE_DENYLIST_FOR_UNIMPORTANT_VIEW =
            "package_deny_list_for_unimportant_view";

    /**
     * Sets the list of activities and packages allowed for autofill. The format is same with
     * {@link #DEVICE_CONFIG_PACKAGE_DENYLIST_FOR_UNIMPORTANT_VIEW}
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_PACKAGE_AND_ACTIVITY_ALLOWLIST_FOR_TRIGGERING_FILL_REQUEST =
            "package_and_activity_allowlist_for_triggering_fill_request";

    /**
     * Whether the heuristics check for view is enabled
     */
    public static final String DEVICE_CONFIG_TRIGGER_FILL_REQUEST_ON_UNIMPORTANT_VIEW =
            "trigger_fill_request_on_unimportant_view";

    /**
     * Whether to apply heuristic check on important views.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_TRIGGER_FILL_REQUEST_ON_FILTERED_IMPORTANT_VIEWS =
            "trigger_fill_request_on_filtered_important_views";

    /**
     * Continas imeAction ids that is irrelevant for autofill. For example, ime_action_search. We
     * use this to avoid trigger fill request on unimportant views.
     *
     * The list is {@code ","} delimited.
     *
     * <p> For example, a imeAction list could be "2,3,4", corresponding to ime_action definition
     * in {@link android.view.inputmethod.EditorInfo.java}</p>
     */
    @SuppressLint("IntentName")
    public static final String DEVICE_CONFIG_NON_AUTOFILLABLE_IME_ACTION_IDS =
            "non_autofillable_ime_action_ids";

    /**
     * Whether to enable autofill on all view types (not just checkbox, spinner, datepicker etc...)
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_SHOULD_ENABLE_AUTOFILL_ON_ALL_VIEW_TYPES =
            "should_enable_autofill_on_all_view_types";

    /**
     * Whether to enable multi-line filter when checking if view is autofillable
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_MULTILINE_FILTER_ENABLED =
            "multiline_filter_enabled";

    /**
     * Whether include all autofill type not none views in assist structure
     *
     * @hide
     */
    public static final String
        DEVICE_CONFIG_INCLUDE_ALL_AUTOFILL_TYPE_NOT_NONE_VIEWS_IN_ASSIST_STRUCTURE =
            "include_all_autofill_type_not_none_views_in_assist_structure";

    /**
     * Whether include all views in assist structure
     *
     * @hide
     */
    public static final String
        DEVICE_CONFIG_INCLUDE_ALL_VIEWS_IN_ASSIST_STRUCTURE =
            "include_all_views_in_assist_structure";

    /**
     * Whether to always include WebView in assist structure. WebView is a container view that
     * providers "virtual" views. We want to always include such a container view since it can
     * contain arbitrary views in it, some of which could be fillable.
     *
     * @hide
     */
    public static final String
            DEVICE_CONFIG_ALWAYS_INCLUDE_WEBVIEW_IN_ASSIST_STRUCTURE =
            "always_include_webview_in_assist_structure";

    /**
     * Whether to include invisible views in the assist structure. Including invisible views can fix
     * some cases in which Session is destroyed earlier than it is suppose to.
     *
     * <p>See
     * frameworks/base/services/autofill/bugfixes.aconfig#include_invisible_view_group_in_assist_structure
     * for more information.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_INCLUDE_INVISIBLE_VIEW_GROUP_IN_ASSIST_STRUCTURE =
            "include_invisible_view_group_in_assist_structure";

    /**
     * Bugfix flag, Autofill should ignore views resetting to empty states.
     *
     * See frameworks/base/services/autofill/bugfixes.aconfig#ignore_view_state_reset_to_empty
     * for more information.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_IGNORE_VIEW_STATE_RESET_TO_EMPTY =
            "ignore_view_state_reset_to_empty";

    /**
     * Bugfix flag, Autofill should ignore view updates if an Auth intent is showing.
     *
     * See frameworks/base/services/autofill/bugfixes.aconfig#relayout
     * for more information.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_IGNORE_RELAYOUT_WHEN_AUTH_PENDING =
            "ignore_relayout_auth_pending";

    /**
     * Bugfix flag, Autofill should only fill in value from current session.
     *
     * See frameworks/base/services/autofill/bugfixes.aconfig#fill_fields_from_current_session_only
     * for more information
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_FILL_FIELDS_FROM_CURRENT_SESSION_ONLY =
            "fill_fields_from_current_session_only";

    // END AUTOFILL FOR ALL APPS FLAGS //


    // START AUTOFILL PCC CLASSIFICATION FLAGS

    /**
     * Sets the fill dialog feature enabled or not.
     */
    public static final String DEVICE_CONFIG_AUTOFILL_PCC_CLASSIFICATION_ENABLED =
            "pcc_classification_enabled";

    /**
     * Give preference to autofill provider's detection.
     * @hide
     */
    public static final String DEVICE_CONFIG_PREFER_PROVIDER_OVER_PCC = "prefer_provider_over_pcc";

    /**
     * Indicates the Autofill Hints that would be requested by the service from the Autofill
     * Provider.
     */
    public static final String DEVICE_CONFIG_AUTOFILL_PCC_FEATURE_PROVIDER_HINTS =
            "pcc_classification_hints";

    /**
     * Use data from secondary source if primary not present .
     * For eg: if we prefer PCC over provider, and PCC detection didn't classify a field, however,
     * autofill provider did, this flag would decide whether we use that result, and show some
     * presentation for that particular field.
     * @hide
     */
    public static final String DEVICE_CONFIG_PCC_USE_FALLBACK = "pcc_use_fallback";

    // END AUTOFILL PCC CLASSIFICATION FLAGS

    /**
     * Define the max input length for autofill to show suggesiton UI
     *
     * E.g. if flag is set to 3, autofill will only show suggestions when user inputs less than 3
     * characters
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_MAX_INPUT_LENGTH_FOR_AUTOFILL =
            "max_input_length_for_autofill";

    /**
     * Sets a value of delay time to show up the inline tooltip view.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_AUTOFILL_TOOLTIP_SHOW_UP_DELAY =
            "autofill_inline_tooltip_first_show_delay";

    private static final String DIALOG_HINTS_DELIMITER = ":";

    private static final boolean DEFAULT_HAS_FILL_DIALOG_UI_FEATURE = false;
    private static final String DEFAULT_FILL_DIALOG_ENABLED_HINTS = "";


    // CREDENTIAL MANAGER DEFAULTS
    // Credential manager is enabled by default so as to allow testing by app developers
    private static final boolean DEFAULT_CREDENTIAL_MANAGER_ENABLED = true;
    private static final boolean DEFAULT_CREDENTIAL_MANAGER_SUPPRESS_FILL_AND_SAVE_DIALOG = true;
    // END CREDENTIAL MANAGER DEFAULTS


    // AUTOFILL PCC CLASSIFICATION FLAGS DEFAULTS
    // Default for whether the pcc classification is enabled for autofill.
    /** @hide */
    public static final boolean DEFAULT_AUTOFILL_PCC_CLASSIFICATION_ENABLED = false;
    // END AUTOFILL PCC CLASSIFICATION FLAGS DEFAULTS

    // AUTOFILL FOR ALL APPS DEFAULTS
    private static final boolean DEFAULT_AFAA_ON_UNIMPORTANT_VIEW_ENABLED = true;
    private static final boolean DEFAULT_AFAA_ON_IMPORTANT_VIEW_ENABLED = true;
    private static final String DEFAULT_AFAA_DENYLIST = "";
    private static final String DEFAULT_AFAA_ALLOWLIST = "";
    private static final String DEFAULT_AFAA_NON_AUTOFILLABLE_IME_ACTIONS = "3,4";
    private static final boolean DEFAULT_AFAA_SHOULD_ENABLE_AUTOFILL_ON_ALL_VIEW_TYPES = true;
    private static final boolean DEFAULT_AFAA_SHOULD_ENABLE_MULTILINE_FILTER = true;
    private static final boolean
            DEFAULT_AFAA_SHOULD_INCLUDE_ALL_AUTOFILL_TYPE_NOT_NONE_VIEWS_IN_ASSIST_STRUCTURE = true;
    // END AUTOFILL FOR ALL APPS DEFAULTS

    /**
     * @hide
     */
    public static final int DEFAULT_MAX_INPUT_LENGTH_FOR_AUTOFILL = 3;
    private AutofillFeatureFlags() {};

    /**
     * Whether the fill dialog feature is enabled or not
     *
     * @hide
     */
    public static boolean isFillDialogEnabled() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_AUTOFILL_DIALOG_ENABLED,
                DEFAULT_HAS_FILL_DIALOG_UI_FEATURE);
    }

    /**
     * Gets fill dialog enabled hints.
     *
     * @hide
     */
    public static String[] getFillDialogEnabledHints() {
        final String dialogHints = DeviceConfig.getString(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_AUTOFILL_DIALOG_HINTS,
                DEFAULT_FILL_DIALOG_ENABLED_HINTS);
        if (TextUtils.isEmpty(dialogHints)) {
            return new String[0];
        }

        return ArrayUtils.filter(dialogHints.split(DIALOG_HINTS_DELIMITER), String[]::new,
                (str) -> !TextUtils.isEmpty(str));
    }

    /* starts credman flag getter function */
    /**
     * Whether the Credential Manager feature is enabled or not
     *
     * @hide
     */
    public static boolean isCredentialManagerEnabled() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_ENABLED,
                DEFAULT_CREDENTIAL_MANAGER_ENABLED);
    }

    /**
     * Whether credential manager tagged views should not trigger fill dialog requests.
     *
     * @hide
     */
    public static boolean isFillAndSaveDialogDisabledForCredentialManager() {
        return isCredentialManagerEnabled() && DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_AUTOFILL,
                    DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_SUPPRESS_FILL_AND_SAVE_DIALOG,
                    DEFAULT_CREDENTIAL_MANAGER_SUPPRESS_FILL_AND_SAVE_DIALOG);
    }
    /* ends credman flag getter function */

    /**
     * Whether triggering fill request on unimportant view is enabled.
     *
     * @hide
     */
    public static boolean isTriggerFillRequestOnUnimportantViewEnabled() {
        return DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_AUTOFILL,
            DEVICE_CONFIG_TRIGGER_FILL_REQUEST_ON_UNIMPORTANT_VIEW,
            DEFAULT_AFAA_ON_UNIMPORTANT_VIEW_ENABLED);
    }

    /**
     * Whether to apply heuristic check on important views before triggering fill request
     *
     * @hide
     */
    public static boolean isTriggerFillRequestOnFilteredImportantViewsEnabled() {
        return DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_AUTOFILL,
            DEVICE_CONFIG_TRIGGER_FILL_REQUEST_ON_FILTERED_IMPORTANT_VIEWS,
            DEFAULT_AFAA_ON_IMPORTANT_VIEW_ENABLED);
    }

    /**
     * Whether to enable autofill on all view types.
     *
     * @hide
     */
    public static boolean shouldEnableAutofillOnAllViewTypes(){
        return DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_AUTOFILL,
            DEVICE_CONFIG_SHOULD_ENABLE_AUTOFILL_ON_ALL_VIEW_TYPES,
            DEFAULT_AFAA_SHOULD_ENABLE_AUTOFILL_ON_ALL_VIEW_TYPES);
    }

    /**
     * Get the non-autofillable ime actions from flag. This will be used in filtering
     * condition to trigger fill request.
     *
     * @hide
     */
    public static Set<String> getNonAutofillableImeActionIdSetFromFlag() {
        final String mNonAutofillableImeActions = DeviceConfig.getString(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_NON_AUTOFILLABLE_IME_ACTION_IDS,
                DEFAULT_AFAA_NON_AUTOFILLABLE_IME_ACTIONS);
        return new ArraySet<>(Arrays.asList(mNonAutofillableImeActions.split(",")));
    }

    /**
     * Get denylist string from flag.
     *
     * Note: This denylist works both on important view and not important views. The flag used here
     * is legacy flag which will be replaced with soon.
     *
     * @hide
     */
    public static String getDenylistStringFromFlag() {
        return DeviceConfig.getString(
            DeviceConfig.NAMESPACE_AUTOFILL,
            DEVICE_CONFIG_PACKAGE_DENYLIST_FOR_UNIMPORTANT_VIEW,
            DEFAULT_AFAA_DENYLIST);
    }

    /**
     * Get autofill allowlist from flag
     *
     * @hide
     */
    public static String getAllowlistStringFromFlag() {
        return DeviceConfig.getString(
            DeviceConfig.NAMESPACE_AUTOFILL,
            DEVICE_CONFIG_PACKAGE_AND_ACTIVITY_ALLOWLIST_FOR_TRIGGERING_FILL_REQUEST,
            DEFAULT_AFAA_ALLOWLIST);
    }
    /**
     * Whether include all views that have autofill type not none in assist structure.
     *
     * @hide
     */
    public static boolean shouldIncludeAllViewsAutofillTypeNotNoneInAssistStructrue() {
        return DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_AUTOFILL,
            DEVICE_CONFIG_INCLUDE_ALL_AUTOFILL_TYPE_NOT_NONE_VIEWS_IN_ASSIST_STRUCTURE,
            DEFAULT_AFAA_SHOULD_INCLUDE_ALL_AUTOFILL_TYPE_NOT_NONE_VIEWS_IN_ASSIST_STRUCTURE);
    }

    /**
     * Whether include all views in assist structure.
     *
     * @hide
     */
    public static boolean shouldIncludeAllChildrenViewInAssistStructure() {
        return DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_AUTOFILL,
            DEVICE_CONFIG_INCLUDE_ALL_VIEWS_IN_ASSIST_STRUCTURE, false);
    }

    /** @hide */
    public static boolean shouldAlwaysIncludeWebviewInAssistStructure() {
        return DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_ALWAYS_INCLUDE_WEBVIEW_IN_ASSIST_STRUCTURE, true);
    }

    /** @hide */
    public static boolean shouldIncludeInvisibleViewInAssistStructure() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_INCLUDE_INVISIBLE_VIEW_GROUP_IN_ASSIST_STRUCTURE,
                true);
    }

    /** @hide */
    public static boolean shouldIgnoreViewStateResetToEmpty() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_IGNORE_VIEW_STATE_RESET_TO_EMPTY,
                true);
    }

    /** @hide */
    public static boolean shouldIgnoreRelayoutWhenAuthPending() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_IGNORE_RELAYOUT_WHEN_AUTH_PENDING,
                false);
    }

    /** @hide **/
    public static boolean shouldFillFieldsFromCurrentSessionOnly() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_FILL_FIELDS_FROM_CURRENT_SESSION_ONLY,
                true);
    }

    /**
     * Whether should enable multi-line filter
     *
     * @hide
     */
    public static boolean shouldEnableMultilineFilter() {
        return DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_AUTOFILL,
            DEVICE_CONFIG_MULTILINE_FILTER_ENABLED,
            DEFAULT_AFAA_SHOULD_ENABLE_MULTILINE_FILTER);
    }

    // START AUTOFILL PCC CLASSIFICATION FUNCTIONS

    /**
     * Whether Autofill PCC Detection is enabled.
     *
     * @hide
     */
    public static boolean isAutofillPccClassificationEnabled() {
        // TODO(b/266379948): Add condition for checking whether device has PCC first

        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_AUTOFILL_PCC_CLASSIFICATION_ENABLED,
                DEFAULT_AUTOFILL_PCC_CLASSIFICATION_ENABLED);
    }

    // END AUTOFILL PCC CLASSIFICATION FUNCTIONS
}
