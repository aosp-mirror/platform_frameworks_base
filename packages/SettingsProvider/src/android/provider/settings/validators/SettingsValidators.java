/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.provider.settings.validators;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * This class provides both interface for validation and common validators
 * used to ensure Settings have meaningful values.
 *
 * @hide
 */
public class SettingsValidators {

    public static final Validator BOOLEAN_VALIDATOR =
            new DiscreteValueValidator(new String[] {"0", "1"});

    public static final Validator ANY_STRING_VALIDATOR = new Validator() {
        @Override
        public boolean validate(@Nullable String value) {
            return true;
        }
    };

    public static final Validator NON_NEGATIVE_INTEGER_VALIDATOR = new Validator() {
        @Override
        public boolean validate(@Nullable String value) {
            try {
                return Integer.parseInt(value) >= 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    };

    public static final Validator ANY_INTEGER_VALIDATOR = new Validator() {
        @Override
        public boolean validate(@Nullable String value) {
            try {
                Integer.parseInt(value);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    };

    public static final Validator URI_VALIDATOR = new Validator() {
        @Override
        public boolean validate(@Nullable String value) {
            try {
                Uri.decode(value);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    };

    /**
     * Does not allow a setting to have a null {@link ComponentName}. Use {@link
     * SettingsValidators#NULLABLE_COMPONENT_NAME_VALIDATOR} instead if a setting can have a
     * nullable {@link ComponentName}.
     */
    public static final Validator COMPONENT_NAME_VALIDATOR = new Validator() {
        @Override
        public boolean validate(@Nullable String value) {
            return value != null && ComponentName.unflattenFromString(value) != null;
        }
    };

    /**
     * Allows a setting to have a null {@link ComponentName}.
     */
    public static final Validator NULLABLE_COMPONENT_NAME_VALIDATOR = new Validator() {
        @Override
        public boolean validate(@Nullable String value) {
            return value == null || COMPONENT_NAME_VALIDATOR.validate(value);
        }
    };

    public static final Validator PACKAGE_NAME_VALIDATOR = new Validator() {
        @Override
        public boolean validate(@Nullable String value) {
            return value != null && isStringPackageName(value);
        }

        private boolean isStringPackageName(String value) {
            // The name may contain uppercase or lowercase letters ('A' through 'Z'), numbers,
            // and underscores ('_'). However, individual package name parts may only
            // start with letters.
            // (https://developer.android.com/guide/topics/manifest/manifest-element.html#package)
            if (value == null) {
                return false;
            }
            String[] subparts = value.split("\\.");
            boolean isValidPackageName = true;
            for (String subpart : subparts) {
                isValidPackageName &= isSubpartValidForPackageName(subpart);
                if (!isValidPackageName) break;
            }
            return isValidPackageName;
        }

        private boolean isSubpartValidForPackageName(String subpart) {
            if (subpart.length() == 0) return false;
            boolean isValidSubpart = Character.isLetter(subpart.charAt(0));
            for (int i = 1; i < subpart.length(); i++) {
                isValidSubpart &= (Character.isLetterOrDigit(subpart.charAt(i))
                                || (subpart.charAt(i) == '_'));
                if (!isValidSubpart) break;
            }
            return isValidSubpart;
        }
    };

    public static final Validator LENIENT_IP_ADDRESS_VALIDATOR = new Validator() {
        private static final int MAX_IPV6_LENGTH = 45;

        @Override
        public boolean validate(@Nullable String value) {
            if (value == null) {
                return false;
            }
            return value.length() <= MAX_IPV6_LENGTH;
        }
    };

    public static final Validator LOCALE_VALIDATOR = new Validator() {
        @Override
        public boolean validate(@Nullable String value) {
            if (value == null) {
                return false;
            }
            Locale[] validLocales = Locale.getAvailableLocales();
            for (Locale locale : validLocales) {
                if (value.equals(locale.toString())) {
                    return true;
                }
            }
            return false;
        }
    };

    /** {@link Validator} that checks whether a value is a valid {@link JSONObject}. */
    public static final Validator JSON_OBJECT_VALIDATOR = (value) -> {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        try {
            new JSONObject(value);
            return true;
        } catch (JSONException e) {
            return false;
        }
    };

    public static final Validator TTS_LIST_VALIDATOR = new TTSListValidator();

    public static final Validator TILE_LIST_VALIDATOR = new TileListValidator();

    static final Validator DATE_FORMAT_VALIDATOR = value -> {
        try {
            new SimpleDateFormat(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    };

    static final Validator COLON_SEPARATED_COMPONENT_LIST_VALIDATOR =
            new ComponentNameListValidator(":");

    static final Validator COLON_SEPARATED_PACKAGE_LIST_VALIDATOR =
            new PackageNameListValidator(":");

    static final Validator COMMA_SEPARATED_COMPONENT_LIST_VALIDATOR =
            new ComponentNameListValidator(",");

    static final Validator PERCENTAGE_INTEGER_VALIDATOR =
            new InclusiveIntegerRangeValidator(0, 100);

    static final Validator VIBRATION_INTENSITY_VALIDATOR = new InclusiveIntegerRangeValidator(0, 3);

    static final Validator ACCESSIBILITY_SHORTCUT_TARGET_LIST_VALIDATOR =
            new AccessibilityShortcutTargetListValidator();

    static final Validator NONE_NEGATIVE_LONG_VALIDATOR = new Validator() {
        @Override
        public boolean validate(String value) {
            try {
                return Long.parseLong(value) >= 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    };
}
