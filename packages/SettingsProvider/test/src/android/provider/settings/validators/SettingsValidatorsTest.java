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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.provider.settings.backup.GlobalSettings;
import android.provider.settings.backup.SecureSettings;
import android.provider.settings.backup.SystemSettings;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

/**
 * Tests that ensure all backed up settings have non-null validators. Also, common validators
 * are tested.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class SettingsValidatorsTest {

    @Test
    public void testNonNegativeIntegerValidator() {
        assertTrue(SettingsValidators.NON_NEGATIVE_INTEGER_VALIDATOR.validate("1"));
        assertTrue(SettingsValidators.NON_NEGATIVE_INTEGER_VALIDATOR.validate("0"));
        assertFalse(SettingsValidators.NON_NEGATIVE_INTEGER_VALIDATOR.validate("-1"));
        assertFalse(SettingsValidators.NON_NEGATIVE_INTEGER_VALIDATOR.validate("rectangle"));
    }

    @Test
    public void testNonNegativeIntegerValidator_onNullValue_returnsTrue() {
        assertTrue(SettingsValidators.NON_NEGATIVE_INTEGER_VALIDATOR.validate(null));
    }

    @Test
    public void testAnyIntegerValidator() {
        assertTrue(SettingsValidators.ANY_INTEGER_VALIDATOR.validate("1"));
        assertTrue(SettingsValidators.ANY_INTEGER_VALIDATOR.validate("0"));
        assertTrue(SettingsValidators.ANY_INTEGER_VALIDATOR.validate("-1"));
        assertFalse(SettingsValidators.ANY_INTEGER_VALIDATOR.validate("rectangle"));
    }

    @Test
    public void testAnyIntegerValidator_onNullValue_returnsTrue() {
        assertTrue(SettingsValidators.ANY_INTEGER_VALIDATOR.validate(null));
    }

    @Test
    public void testUriValidator_onNullValue_returnsTrue() {
        assertTrue(SettingsValidators.URI_VALIDATOR.validate(null));
    }

    @Test
    public void testComponentNameValidator() {
        assertTrue(SettingsValidators.COMPONENT_NAME_VALIDATOR.validate(
                "com.android.localtransport/.LocalTransport"));
        assertFalse(SettingsValidators.COMPONENT_NAME_VALIDATOR.validate("rectangle"));
    }

    @Test
    public void testComponentNameValidator_onNullValue_returnsFalse() {
        assertFalse(SettingsValidators.COMPONENT_NAME_VALIDATOR.validate(null));
    }

    @Test
    public void testLenientIpAddressValidator_onNullValue_returnsTrue() {
        assertTrue(SettingsValidators.LENIENT_IP_ADDRESS_VALIDATOR.validate(null));
    }

    @Test
    public void testNullableComponentNameValidator_onValidComponentName_returnsTrue() {
        assertTrue(SettingsValidators.NULLABLE_COMPONENT_NAME_VALIDATOR.validate(
                "com.android.localtransport/.LocalTransport"));
    }

    @Test
    public void testNullableComponentNameValidator_onInvalidComponentName_returnsFalse() {
        assertFalse(SettingsValidators.NULLABLE_COMPONENT_NAME_VALIDATOR.validate(
                "rectangle"));
    }

    @Test
    public void testNullableComponentNameValidator_onNullValue_returnsTrue() {
        assertTrue(SettingsValidators.NULLABLE_COMPONENT_NAME_VALIDATOR.validate(null));
    }

    @Test
    public void testLocaleValidator() {
        assertTrue(SettingsValidators.LOCALE_VALIDATOR.validate("en_US"));
        assertTrue(SettingsValidators.LOCALE_VALIDATOR.validate("es"));
        assertFalse(SettingsValidators.LOCALE_VALIDATOR.validate("rectangle"));
    }

    @Test
    public void testLocaleValidator_onNullValue_returnsTrue() {
        assertTrue(SettingsValidators.LOCALE_VALIDATOR.validate(null));
    }

    @Test
    public void testPackageNameValidator() {
        assertTrue(SettingsValidators.PACKAGE_NAME_VALIDATOR.validate(
                "com.google.android"));
        assertFalse(SettingsValidators.PACKAGE_NAME_VALIDATOR.validate("com.google.@android"));
        assertFalse(SettingsValidators.PACKAGE_NAME_VALIDATOR.validate(".com.google.android"));
        assertFalse(SettingsValidators.PACKAGE_NAME_VALIDATOR.validate(".com.google.5android"));
    }

    @Test
    public void testPackageNameValidator_onNullValue_returnsFalse() {
        assertFalse(SettingsValidators.PACKAGE_NAME_VALIDATOR.validate(null));
    }

    @Test
    public void testDiscreteValueValidator() {
        String[] beerTypes = new String[]{"Ale", "American IPA", "Stout"};
        Validator v = new DiscreteValueValidator(beerTypes);
        assertTrue(v.validate("Ale"));
        assertTrue(v.validate("American IPA"));
        assertTrue(v.validate("Stout"));
        assertFalse(v.validate("Cider")); // just juice pretending to be beer
    }

    @Test
    public void testDiscreteValueValidator_onNullValue_returnsTrue() {
        String[] discreteTypes = new String[]{"Type1", "Type2"};
        Validator v = new DiscreteValueValidator(discreteTypes);

        assertTrue(v.validate(null));
    }

    @Test
    public void testInclusiveIntegerRangeValidator() {
        Validator v = new InclusiveIntegerRangeValidator(0, 5);
        assertTrue(v.validate("0"));
        assertTrue(v.validate("2"));
        assertTrue(v.validate("5"));
        assertFalse(v.validate("-1"));
        assertFalse(v.validate("6"));
    }

    @Test
    public void testInclusiveIntegerRangeValidator_onNullValue_returnsTrue() {
        Validator v = new InclusiveIntegerRangeValidator(0, 5);

        assertTrue(v.validate(null));
    }

    @Test
    public void testInclusiveFloatRangeValidator() {
        Validator v = new InclusiveFloatRangeValidator(0.0f, 5.0f);
        assertTrue(v.validate("0.0"));
        assertTrue(v.validate("2.0"));
        assertTrue(v.validate("5.0"));
        assertFalse(v.validate("-1.0"));
        assertFalse(v.validate("6.0"));
    }

    @Test
    public void testInclusiveFloatRangeValidator_onNullValue_returnsTrue() {
        Validator v = new InclusiveFloatRangeValidator(0.0f, 5.0f);

        assertTrue(v.validate(null));
    }

    @Test
    public void testComponentNameListValidator() {
        Validator v = new ComponentNameListValidator(",");
        assertTrue(v.validate("com.android.localtransport/.LocalTransport,"
                + "com.google.android.gms/.backup.migrate.service.D2dTransport"));
        assertFalse(v.validate("com.google.5android,android"));
    }

    @Test
    public void testComponentNameListValidator_onNullValue_returnsFalse() {
        Validator v = new ComponentNameListValidator(",");

        assertFalse(v.validate(null));
    }

    @Test
    public void testPackageNameListValidator() {
        Validator v = new PackageNameListValidator(",");
        assertTrue(v.validate("com.android.localtransport.LocalTransport,com.google.android.gms"));
        assertFalse(v.validate("5com.android.internal.backup.LocalTransport,android"));
    }

    @Test
    public void testPackageNameListValidator_onNullValue_returnsFalse() {
        Validator v = new PackageNameListValidator(",");

        assertFalse(v.validate(null));
    }

    @Test
    public void testJSONObjectValidator() throws JSONException {
        Validator v = SettingsValidators.JSON_OBJECT_VALIDATOR;

        assertThat(v.validate(new JSONObject().toString())).isTrue();
        assertThat(v.validate("{}")).isTrue();
        assertThat(v.validate(new JSONObject().put("foo", "bar").toString()))
                .isTrue();
        assertThat(v.validate("{\"foo\": \"bar\"}")).isTrue();

        assertThat(v.validate("random string")).isFalse();
        assertThat(v.validate("random: string")).isFalse();
        assertThat(v.validate("{random: }")).isFalse();
    }

    @Test
    public void testJSONObjectValidator_onNullValue_returnsTrue() {
        assertTrue(SettingsValidators.JSON_OBJECT_VALIDATOR.validate(null));
    }

    @Test
    public void testJSONObjectValidator_onEmptyString_returnsFalse() {
        assertFalse(SettingsValidators.JSON_OBJECT_VALIDATOR.validate(""));
    }

    @Test
    public void ensureAllBackedUpSystemSettingsHaveValidators() {
        String offenders = getOffenders(concat(SystemSettings.SETTINGS_TO_BACKUP,
                Settings.System.LEGACY_RESTORE_SETTINGS), SystemSettingsValidators.VALIDATORS);

        failIfOffendersPresent(offenders, "Settings.System");
    }

    @Test
    public void testTTSListValidator_withValidInput_returnsTrue() {
        assertTrue(
                SettingsValidators.TTS_LIST_VALIDATOR.validate(
                        "com.foo.ttsengine:en-US,com.bar.ttsengine:es_ES"));
    }

    @Test
    public void testTTSListValidator_withInvalidInput_returnsFalse() {
        assertFalse(
                SettingsValidators.TTS_LIST_VALIDATOR.validate(
                        "com.foo.ttsengine:eng-USA,INVALID"));
    }

    @Test
    public void testTTSListValidator_withEmptyInput_returnsFalse() {
        assertFalse(SettingsValidators.TTS_LIST_VALIDATOR.validate(""));
    }

    @Test
    public void testPositiveLongValidator_zero() {
        assertTrue(SettingsValidators.NONE_NEGATIVE_LONG_VALIDATOR.validate("0"));
    }

    @Test
    public void testPositiveLongValidator_negative() {
        assertFalse(SettingsValidators.NONE_NEGATIVE_LONG_VALIDATOR.validate("-5"));
    }


    @Test
    public void testPositiveLongValidator_positive() {
        assertTrue(SettingsValidators.NONE_NEGATIVE_LONG_VALIDATOR.validate("5"));
    }

    @Test
    public void testPositiveLongValidator_floatFormat() {
        assertFalse(SettingsValidators.NONE_NEGATIVE_LONG_VALIDATOR.validate("4.4756"));
    }

    @Test
    public void testTTSListValidator_withNullInput_returnsFalse() {
        assertFalse(SettingsValidators.TTS_LIST_VALIDATOR.validate(null));
    }

    @Test
    public void testTileListValidator_withValidInput_returnsTrue() {
        assertTrue(SettingsValidators.TILE_LIST_VALIDATOR.validate("1,2,3,4"));
    }

    @Test
    public void testTileListValidator_withMissingValue_returnsFalse() {
        assertFalse(SettingsValidators.TILE_LIST_VALIDATOR.validate("1,,3"));
    }

    @Test
    public void testTileListValidator_withNullInput_returnsFalse() {
        assertFalse(SettingsValidators.TILE_LIST_VALIDATOR.validate(null));
    }

    @Test
    public void testAccessibilityShortcutTargetValidator() {
        assertTrue(SettingsValidators.ACCESSIBILITY_SHORTCUT_TARGET_LIST_VALIDATOR.validate(
                "com.google.android/.AccessibilityShortcutTarget"));
        assertTrue(SettingsValidators.ACCESSIBILITY_SHORTCUT_TARGET_LIST_VALIDATOR.validate(
                "com.google.android"));
        assertTrue(SettingsValidators.ACCESSIBILITY_SHORTCUT_TARGET_LIST_VALIDATOR.validate(
                "com.google.android/.AccessibilityShortcutTarget:com.google.android"));
        assertFalse(SettingsValidators.ACCESSIBILITY_SHORTCUT_TARGET_LIST_VALIDATOR.validate(
                "com.google.android/.AccessibilityShortcutTarget:com.google.@android"));
    }

    @Test
    public void ensureAllBackedUpGlobalSettingsHaveValidators() {
        String offenders = getOffenders(concat(GlobalSettings.SETTINGS_TO_BACKUP,
                Settings.Global.LEGACY_RESTORE_SETTINGS), GlobalSettingsValidators.VALIDATORS);

        failIfOffendersPresent(offenders, "Settings.Global");
    }

    @Test
    public void ensureAllBackedUpSecureSettingsHaveValidators() {
        String offenders = getOffenders(concat(SecureSettings.SETTINGS_TO_BACKUP,
                Settings.Secure.LEGACY_RESTORE_SETTINGS), SecureSettingsValidators.VALIDATORS);

        failIfOffendersPresent(offenders, "Settings.Secure");
    }

    private void failIfOffendersPresent(String offenders, String settingsType) {
        if (offenders.length() > 0) {
            fail("All " + settingsType + " settings that are backed up have to have a non-null"
                    + " validator, but those don't: " + offenders);
        }
    }

    private String getOffenders(String[] settingsToBackup, Map<String, Validator> validators) {
        StringBuilder offenders = new StringBuilder();
        for (String setting : settingsToBackup) {
            if (validators.get(setting) == null) {
                offenders.append(setting).append(" ");
            }
        }
        return offenders.toString();
    }

    private String[] concat(String[] first, String[] second) {
        if (second == null || second.length == 0) {
            return first;
        }
        final int firstLen = first.length;
        final int secondLen = second.length;
        String[] both = new String[firstLen + secondLen];
        System.arraycopy(first, 0, both, 0, firstLen);
        System.arraycopy(second, 0, both, firstLen, secondLen);
        return both;
    }
}
