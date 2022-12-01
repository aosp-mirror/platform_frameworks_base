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

import android.annotation.TestApi;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.view.View;

import com.android.internal.util.ArrayUtils;

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
     * Indicates whether credential manager tagged views should suppress fill dialog.
     * This flag is further gated by {@link #DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_ENABLED}
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_SUPPRESS_FILL_DIALOG =
            "autofill_credential_manager_suppress_fill_dialog";



    /**
     * Indicates whether credential manager tagged views should suppress save dialog.
     * This flag is further gated by {@link #DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_ENABLED}
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_SUPPRESS_SAVE_DIALOG =
            "autofill_credential_manager_suppress_save_dialog";
    // END CREDENTIAL MANAGER FLAGS //

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
    private static final boolean DEFAULT_CREDENTIAL_MANAGER_IGNORE_VIEWS = true;
    private static final boolean DEFAULT_CREDENTIAL_MANAGER_SUPPRESS_FILL_DIALOG = false;
    private static final boolean DEFAULT_CREDENTIAL_MANAGER_SUPPRESS_SAVE_DIALOG = false;
    // END CREDENTIAL MANAGER DEFAULTS

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
     * Whether credential manager tagged views should be ignored for autofill structure.
     *
     * @hide
     */
    public static boolean shouldIgnoreCredentialViews() {
        return isCredentialManagerEnabled()
                && DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_IGNORE_VIEWS,
                DEFAULT_CREDENTIAL_MANAGER_IGNORE_VIEWS);
    }

    /**
     * Whether credential manager tagged views should not trigger fill dialog requests.
     *
     * @hide
     */
    public static boolean isFillDialogDisabledForCredentialManager() {
        return isCredentialManagerEnabled()
                && DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_SUPPRESS_FILL_DIALOG,
                DEFAULT_CREDENTIAL_MANAGER_SUPPRESS_FILL_DIALOG);
    }
}
