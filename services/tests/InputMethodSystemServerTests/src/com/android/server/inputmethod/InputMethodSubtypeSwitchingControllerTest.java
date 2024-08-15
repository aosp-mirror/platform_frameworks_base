/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;

import static com.android.server.inputmethod.InputMethodSubtypeSwitchingController.MODE_AUTO;
import static com.android.server.inputmethod.InputMethodSubtypeSwitchingController.MODE_RECENT;
import static com.android.server.inputmethod.InputMethodSubtypeSwitchingController.MODE_STATIC;
import static com.android.server.inputmethod.InputMethodSubtypeSwitchingController.SwitchMode;
import static com.android.server.inputmethod.InputMethodUtils.NOT_A_SUBTYPE_INDEX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.inputmethod.Flags;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.server.inputmethod.InputMethodSubtypeSwitchingController.ImeSubtypeListItem;

import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class InputMethodSubtypeSwitchingControllerTest {
    private static final String TEST_PACKAGE_NAME = "test package name";
    private static final String TEST_IME_LABEL = "test ime label";
    private static final String TEST_SETTING_ACTIVITY_NAME = "";
    private static final boolean TEST_IS_AUX_IME = false;
    private static final boolean TEST_FORCE_DEFAULT = false;
    private static final boolean TEST_IS_VR_IME = false;
    private static final int TEST_IS_DEFAULT_RES_ID = 0;
    private static final String SYSTEM_LOCALE = "en_US";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @NonNull
    private static InputMethodSubtype createTestSubtype(@NonNull String locale) {
        return new InputMethodSubtypeBuilder()
                .setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setSubtypeLocale(locale)
                .setIsAsciiCapable(true)
                .build();
    }

    private static void addTestImeSubtypeListItems(@NonNull List<ImeSubtypeListItem> items,
            @NonNull String imeName, @NonNull String imeLabel,
            @Nullable List<String> subtypeLocales, boolean supportsSwitchingToNextInputMethod) {
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = TEST_PACKAGE_NAME;
        ai.enabled = true;
        final ServiceInfo si = new ServiceInfo();
        si.applicationInfo = ai;
        si.enabled = true;
        si.packageName = TEST_PACKAGE_NAME;
        si.name = imeName;
        si.exported = true;
        si.nonLocalizedLabel = imeLabel;
        final ResolveInfo ri = new ResolveInfo();
        ri.serviceInfo = si;
        List<InputMethodSubtype> subtypes = null;
        if (subtypeLocales != null) {
            subtypes = new ArrayList<>();
            for (String subtypeLocale : subtypeLocales) {
                subtypes.add(createTestSubtype(subtypeLocale));
            }
        }
        final InputMethodInfo imi = new InputMethodInfo(ri, TEST_IS_AUX_IME,
                TEST_SETTING_ACTIVITY_NAME, subtypes, TEST_IS_DEFAULT_RES_ID,
                TEST_FORCE_DEFAULT, supportsSwitchingToNextInputMethod, TEST_IS_VR_IME);
        if (subtypes == null) {
            items.add(new ImeSubtypeListItem(imeName, null /* variableName */, imi,
                    NOT_A_SUBTYPE_INDEX, null, SYSTEM_LOCALE));
        } else {
            for (int i = 0; i < subtypes.size(); ++i) {
                final String subtypeLocale = subtypeLocales.get(i);
                items.add(new ImeSubtypeListItem(imeName, subtypeLocale, imi, i, subtypeLocale,
                        SYSTEM_LOCALE));
            }
        }
    }

    @NonNull
    private static ImeSubtypeListItem createTestItem(@NonNull ComponentName imeComponentName,
            @NonNull String imeName, @NonNull String subtypeName,
            @NonNull String subtypeLocale, int subtypeIndex) {
        final var ai = new ApplicationInfo();
        ai.packageName = imeComponentName.getPackageName();
        ai.enabled = true;
        final var si = new ServiceInfo();
        si.applicationInfo = ai;
        si.enabled = true;
        si.packageName = imeComponentName.getPackageName();
        si.name = imeComponentName.getClassName();
        si.exported = true;
        si.nonLocalizedLabel = TEST_IME_LABEL;
        final var ri = new ResolveInfo();
        ri.serviceInfo = si;
        final var subtypes = new ArrayList<InputMethodSubtype>();
        subtypes.add(new InputMethodSubtypeBuilder()
                .setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setSubtypeLocale(subtypeLocale)
                .setIsAsciiCapable(true)
                .build());
        final InputMethodInfo imi = new InputMethodInfo(ri, TEST_IS_AUX_IME,
                TEST_SETTING_ACTIVITY_NAME, subtypes, TEST_IS_DEFAULT_RES_ID,
                TEST_FORCE_DEFAULT, true /* supportsSwitchingToNextInputMethod */, TEST_IS_VR_IME);
        return new ImeSubtypeListItem(imeName, subtypeName, imi, subtypeIndex, subtypeLocale,
                SYSTEM_LOCALE);
    }

    @NonNull
    private static List<ImeSubtypeListItem> createEnabledImeSubtypes() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme", List.of("en_US", "fr"),
                true /* supportsSwitchingToNextInputMethod*/);
        addTestImeSubtypeListItems(items, "switchUnawareLatinIme", "switchUnawareLatinIme",
                List.of("en_UK", "hi"), false /* supportsSwitchingToNextInputMethod*/);
        addTestImeSubtypeListItems(items, "subtypeAwareIme", "subtypeAwareIme", null,
                true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "subtypeUnawareIme", "subtypeUnawareIme", null,
                false /* supportsSwitchingToNextInputMethod*/);
        addTestImeSubtypeListItems(items, "JapaneseIme", "JapaneseIme", List.of("ja_JP"),
                true /* supportsSwitchingToNextInputMethod*/);
        addTestImeSubtypeListItems(items, "switchUnawareJapaneseIme", "switchUnawareJapaneseIme",
                List.of("ja_JP"), false /* supportsSwitchingToNextInputMethod*/);
        return items;
    }

    @NonNull
    private static List<ImeSubtypeListItem> createDisabledImeSubtypes() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items,
                "UnknownIme", "UnknownIme",
                List.of("en_US", "hi"),
                true /* supportsSwitchingToNextInputMethod*/);
        addTestImeSubtypeListItems(items,
                "UnknownSwitchingUnawareIme", "UnknownSwitchingUnawareIme",
                List.of("en_US"),
                false /* supportsSwitchingToNextInputMethod*/);
        addTestImeSubtypeListItems(items, "UnknownSubtypeUnawareIme",
                "UnknownSubtypeUnawareIme", null,
                false /* supportsSwitchingToNextInputMethod*/);
        return items;
    }

    private void assertNextInputMethod(@NonNull InputMethodSubtypeSwitchingController controller,
            boolean onlyCurrentIme, @NonNull ImeSubtypeListItem currentItem,
            @Nullable ImeSubtypeListItem nextItem) {
        InputMethodSubtype subtype = null;
        if (currentItem.mSubtypeName != null) {
            subtype = createTestSubtype(currentItem.mSubtypeName.toString());
        }
        final ImeSubtypeListItem nextIme = controller.getNextInputMethodLocked(onlyCurrentIme,
                currentItem.mImi, subtype, MODE_STATIC, true /* forward */);
        assertEquals(nextItem, nextIme);
    }

    private void assertRotationOrder(@NonNull InputMethodSubtypeSwitchingController controller,
            boolean onlyCurrentIme, ImeSubtypeListItem... expectedRotationOrderOfImeSubtypeList) {
        final int numItems = expectedRotationOrderOfImeSubtypeList.length;
        for (int i = 0; i < numItems; i++) {
            final int nextIndex = (i + 1) % numItems;
            final ImeSubtypeListItem currentItem = expectedRotationOrderOfImeSubtypeList[i];
            final ImeSubtypeListItem nextItem = expectedRotationOrderOfImeSubtypeList[nextIndex];
            assertNextInputMethod(controller, onlyCurrentIme, currentItem, nextItem);
        }
    }

    private boolean onUserAction(@NonNull InputMethodSubtypeSwitchingController controller,
            @NonNull ImeSubtypeListItem subtypeListItem) {
        InputMethodSubtype subtype = null;
        if (subtypeListItem.mSubtypeName != null) {
            subtype = createTestSubtype(subtypeListItem.mSubtypeName.toString());
        }
        return controller.onUserActionLocked(subtypeListItem.mImi, subtype);
    }

    @RequiresFlagsDisabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testControllerImpl() {
        final List<ImeSubtypeListItem> disabledItems = createDisabledImeSubtypes();
        final ImeSubtypeListItem disabledIme_en_us = disabledItems.get(0);
        final ImeSubtypeListItem disabledIme_hi = disabledItems.get(1);
        final ImeSubtypeListItem disabledSwitchingUnawareIme = disabledItems.get(2);
        final ImeSubtypeListItem disabledSubtypeUnawareIme = disabledItems.get(3);

        final List<ImeSubtypeListItem> enabledItems = createEnabledImeSubtypes();
        final ImeSubtypeListItem latinIme_en_us = enabledItems.get(0);
        final ImeSubtypeListItem latinIme_fr = enabledItems.get(1);
        final ImeSubtypeListItem switchingUnawareLatinIme_en_uk = enabledItems.get(2);
        final ImeSubtypeListItem switchingUnawareLatinIme_hi = enabledItems.get(3);
        final ImeSubtypeListItem subtypeAwareIme = enabledItems.get(4);
        final ImeSubtypeListItem subtypeUnawareIme = enabledItems.get(5);
        final ImeSubtypeListItem japaneseIme_ja_jp = enabledItems.get(6);
        final ImeSubtypeListItem switchUnawareJapaneseIme_ja_jp = enabledItems.get(7);

        final var controller = new InputMethodSubtypeSwitchingController();
        controller.update(enabledItems, new ArrayList<>());

        // switching-aware loop
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                latinIme_en_us, latinIme_fr, subtypeAwareIme, japaneseIme_ja_jp);

        // switching-unaware loop
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                switchingUnawareLatinIme_en_uk, switchingUnawareLatinIme_hi, subtypeUnawareIme,
                switchUnawareJapaneseIme_ja_jp);

        // test onlyCurrentIme == true
        assertRotationOrder(controller, true /* onlyCurrentIme */,
                latinIme_en_us, latinIme_fr);
        assertRotationOrder(controller, true /* onlyCurrentIme */,
                switchingUnawareLatinIme_en_uk, switchingUnawareLatinIme_hi);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                subtypeAwareIme, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                subtypeUnawareIme, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                japaneseIme_ja_jp, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                switchUnawareJapaneseIme_ja_jp, null);

        // Make sure that disabled IMEs are not accepted.
        assertNextInputMethod(controller, false /* onlyCurrentIme */,
                disabledIme_en_us, null);
        assertNextInputMethod(controller, false /* onlyCurrentIme */,
                disabledIme_hi, null);
        assertNextInputMethod(controller, false /* onlyCurrentIme */,
                disabledSwitchingUnawareIme, null);
        assertNextInputMethod(controller, false /* onlyCurrentIme */,
                disabledSubtypeUnawareIme, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                disabledIme_en_us, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                disabledIme_hi, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                disabledSwitchingUnawareIme, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                disabledSubtypeUnawareIme, null);
    }

    @RequiresFlagsDisabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testControllerImplWithUserAction() {
        final List<ImeSubtypeListItem> enabledItems = createEnabledImeSubtypes();
        final ImeSubtypeListItem latinIme_en_us = enabledItems.get(0);
        final ImeSubtypeListItem latinIme_fr = enabledItems.get(1);
        final ImeSubtypeListItem switchingUnawareLatinIme_en_uk = enabledItems.get(2);
        final ImeSubtypeListItem switchingUnawareLatinIme_hi = enabledItems.get(3);
        final ImeSubtypeListItem subtypeAwareIme = enabledItems.get(4);
        final ImeSubtypeListItem subtypeUnawareIme = enabledItems.get(5);
        final ImeSubtypeListItem japaneseIme_ja_jp = enabledItems.get(6);
        final ImeSubtypeListItem switchUnawareJapaneseIme_ja_jp = enabledItems.get(7);

        final var controller = new InputMethodSubtypeSwitchingController();
        controller.update(enabledItems, new ArrayList<>());

        // === switching-aware loop ===
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                latinIme_en_us, latinIme_fr, subtypeAwareIme, japaneseIme_ja_jp);
        // Then notify that a user did something for latinIme_fr.
        onUserAction(controller, latinIme_fr);
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                latinIme_fr, latinIme_en_us, subtypeAwareIme, japaneseIme_ja_jp);
        // Then notify that a user did something for latinIme_fr again.
        onUserAction(controller, latinIme_fr);
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                latinIme_fr, latinIme_en_us, subtypeAwareIme, japaneseIme_ja_jp);
        // Then notify that a user did something for subtypeAwareIme.
        onUserAction(controller, subtypeAwareIme);
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                subtypeAwareIme, latinIme_fr, latinIme_en_us, japaneseIme_ja_jp);
        // Check onlyCurrentIme == true.
        assertRotationOrder(controller, true /* onlyCurrentIme */,
                latinIme_fr, latinIme_en_us);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                subtypeAwareIme, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                japaneseIme_ja_jp, null);

        // === switching-unaware loop ===
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                switchingUnawareLatinIme_en_uk, switchingUnawareLatinIme_hi, subtypeUnawareIme,
                switchUnawareJapaneseIme_ja_jp);
        // User action should be ignored for switching unaware IMEs.
        onUserAction(controller, switchingUnawareLatinIme_hi);
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                switchingUnawareLatinIme_en_uk, switchingUnawareLatinIme_hi, subtypeUnawareIme,
                switchUnawareJapaneseIme_ja_jp);
        // User action should be ignored for switching unaware IMEs.
        onUserAction(controller, subtypeUnawareIme);
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                switchingUnawareLatinIme_en_uk, switchingUnawareLatinIme_hi, subtypeUnawareIme,
                switchUnawareJapaneseIme_ja_jp);
        // Check onlyCurrentIme == true.
        assertRotationOrder(controller, true /* onlyCurrentIme */,
                switchingUnawareLatinIme_en_uk, switchingUnawareLatinIme_hi);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                subtypeUnawareIme, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                switchUnawareJapaneseIme_ja_jp, null);

        // Rotation order should be preserved when created with the same subtype list.
        final List<ImeSubtypeListItem> sameEnabledItems = createEnabledImeSubtypes();
        controller.update(sameEnabledItems, new ArrayList<>());
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                subtypeAwareIme, latinIme_fr, latinIme_en_us, japaneseIme_ja_jp);
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                switchingUnawareLatinIme_en_uk, switchingUnawareLatinIme_hi, subtypeUnawareIme,
                switchUnawareJapaneseIme_ja_jp);

        // Rotation order should be initialized when created with a different subtype list.
        final List<ImeSubtypeListItem> differentEnabledItems = List.of(
                latinIme_en_us, latinIme_fr, subtypeAwareIme, switchingUnawareLatinIme_en_uk,
                switchUnawareJapaneseIme_ja_jp, subtypeUnawareIme);
        controller.update(differentEnabledItems, new ArrayList<>());
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                latinIme_en_us, latinIme_fr, subtypeAwareIme);
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                switchingUnawareLatinIme_en_uk, switchUnawareJapaneseIme_ja_jp, subtypeUnawareIme);
    }

    @Test
    public void testImeSubtypeListItem() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en_US", "fr", "en", "en_uk", "enn", "e", "EN_US"),
                true /* supportsSwitchingToNextInputMethod*/);
        final ImeSubtypeListItem item_en_us = items.get(0);
        final ImeSubtypeListItem item_fr = items.get(1);
        final ImeSubtypeListItem item_en = items.get(2);
        final ImeSubtypeListItem item_en_uk = items.get(3);
        final ImeSubtypeListItem item_enn = items.get(4);
        final ImeSubtypeListItem item_e = items.get(5);
        final ImeSubtypeListItem item_en_us_allcaps = items.get(6);

        assertTrue(item_en_us.mIsSystemLocale);
        assertFalse(item_fr.mIsSystemLocale);
        assertFalse(item_en.mIsSystemLocale);
        assertFalse(item_en_uk.mIsSystemLocale);
        assertFalse(item_enn.mIsSystemLocale);
        assertFalse(item_e.mIsSystemLocale);
        assertFalse(item_en_us_allcaps.mIsSystemLocale);

        assertTrue(item_en_us.mIsSystemLanguage);
        assertFalse(item_fr.mIsSystemLanguage);
        assertTrue(item_en.mIsSystemLanguage);
        assertTrue(item_en_uk.mIsSystemLanguage);
        assertFalse(item_enn.mIsSystemLanguage);
        assertFalse(item_e.mIsSystemLanguage);
        assertFalse(item_en_us_allcaps.mIsSystemLanguage);
    }

    @RequiresFlagsDisabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @SuppressWarnings("SelfComparison")
    @Test
    public void testImeSubtypeListComparator() {
        final ComponentName imeX1 = new ComponentName("com.example.imeX", "Ime1");
        final ComponentName imeX2 = new ComponentName("com.example.imeX", "Ime2");
        final ComponentName imeY1 = new ComponentName("com.example.imeY", "Ime1");
        final ComponentName imeZ1 = new ComponentName("com.example.imeZ", "Ime1");
        {
            final List<ImeSubtypeListItem> items = List.of(
                    // Subtypes of two IMEs that have the same display name "X".
                    // Subtypes that has the same locale of the system's.
                    createTestItem(imeX1, "X", "E", "en_US", 0),
                    createTestItem(imeX2, "X", "E", "en_US", 0),
                    createTestItem(imeX1, "X", "Z", "en_US", 3),
                    createTestItem(imeX2, "X", "Z", "en_US", 3),
                    createTestItem(imeX1, "X", "", "en_US", 6),
                    createTestItem(imeX2, "X", "", "en_US", 6),
                    // Subtypes that has the same language of the system's.
                    createTestItem(imeX1, "X", "E", "en", 1),
                    createTestItem(imeX2, "X", "E", "en", 1),
                    createTestItem(imeX1, "X", "Z", "en", 4),
                    createTestItem(imeX2, "X", "Z", "en", 4),
                    createTestItem(imeX1, "X", "", "en", 7),
                    createTestItem(imeX2, "X", "", "en", 7),
                    // Subtypes that has different language than the system's.
                    createTestItem(imeX1, "X", "A", "hi_IN", 27),
                    createTestItem(imeX2, "X", "A", "hi_IN", 27),
                    createTestItem(imeX1, "X", "E", "ja", 2),
                    createTestItem(imeX2, "X", "E", "ja", 2),
                    createTestItem(imeX1, "X", "Z", "ja", 5),
                    createTestItem(imeX2, "X", "Z", "ja", 5),
                    createTestItem(imeX1, "X", "", "ja", 8),
                    createTestItem(imeX2, "X", "", "ja", 8),

                    // Subtypes of IME "Y".
                    // Subtypes that has the same locale of the system's.
                    createTestItem(imeY1, "Y", "E", "en_US", 9),
                    createTestItem(imeY1, "Y", "Z", "en_US", 12),
                    createTestItem(imeY1, "Y", "", "en_US", 15),
                    // Subtypes that has the same language of the system's.
                    createTestItem(imeY1, "Y", "E", "en", 10),
                    createTestItem(imeY1, "Y", "Z", "en", 13),
                    createTestItem(imeY1, "Y", "", "en", 16),
                    // Subtypes that has different language than the system's.
                    createTestItem(imeY1, "Y", "A", "hi_IN", 28),
                    createTestItem(imeY1, "Y", "E", "ja", 11),
                    createTestItem(imeY1, "Y", "Z", "ja", 14),
                    createTestItem(imeY1, "Y", "", "ja", 17),

                    // Subtypes of IME Z.
                    // Subtypes that has the same locale of the system's.
                    createTestItem(imeZ1, "", "E", "en_US", 18),
                    createTestItem(imeZ1, "", "Z", "en_US", 21),
                    createTestItem(imeZ1, "", "", "en_US", 24),
                    // Subtypes that has the same language of the system's.
                    createTestItem(imeZ1, "", "E", "en", 19),
                    createTestItem(imeZ1, "", "Z", "en", 22),
                    createTestItem(imeZ1, "", "", "en", 25),
                    // Subtypes that has different language than the system's.
                    createTestItem(imeZ1, "", "A", "hi_IN", 29),
                    createTestItem(imeZ1, "", "E", "ja", 20),
                    createTestItem(imeZ1, "", "Z", "ja", 23),
                    createTestItem(imeZ1, "", "", "ja", 26));

            // Ensure {@link java.lang.Comparable#compareTo} contracts are satisfied.
            for (int i = 0; i < items.size(); ++i) {
                final ImeSubtypeListItem item1 = items.get(i);
                // Ensures sgn(x.compareTo(y)) == -sgn(y.compareTo(x)).
                assertEquals(item1 + " has the same order of itself", 0, item1.compareTo(item1));
                // Ensures (x.compareTo(y) > 0 && y.compareTo(z) > 0) implies x.compareTo(z) > 0.
                for (int j = i + 1; j < items.size(); ++j) {
                    final ImeSubtypeListItem item2 = items.get(j);
                    // Ensures sgn(x.compareTo(y)) == -sgn(y.compareTo(x)).
                    assertTrue(item1 + " is less than " + item2, item1.compareTo(item2) < 0);
                    assertTrue(item2 + " is greater than " + item1, item2.compareTo(item1) > 0);
                }
            }
        }

        {
            // Following two items have the same priority.
            final ImeSubtypeListItem nonSystemLocale1 = createTestItem(imeX1, "X", "A", "ja_JP", 0);
            final ImeSubtypeListItem nonSystemLocale2 = createTestItem(imeX1, "X", "A", "hi_IN", 1);
            assertEquals(0, nonSystemLocale1.compareTo(nonSystemLocale2));
            assertEquals(0, nonSystemLocale2.compareTo(nonSystemLocale1));
            // But those aren't equal to each other.
            assertNotEquals(nonSystemLocale1, nonSystemLocale2);
            assertNotEquals(nonSystemLocale2, nonSystemLocale1);
        }

        {
            // Check if ComponentName is also taken into account when comparing two items.
            final ImeSubtypeListItem ime1 = createTestItem(imeX1, "X", "A", "ja_JP", 0);
            final ImeSubtypeListItem ime2 = createTestItem(imeX2, "X", "A", "ja_JP", 0);
            assertTrue(ime1.compareTo(ime2) < 0);
            assertTrue(ime2.compareTo(ime1) > 0);
            // But those aren't equal to each other.
            assertNotEquals(ime1, ime2);
            assertNotEquals(ime2, ime1);
        }
    }

    /** Verifies the static mode. */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testModeStatic() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "SimpleIme", "SimpleIme",
                null, true /* supportsSwitchingToNextInputMethod */);

        final var english = items.get(0);
        final var french = items.get(1);
        final var italian = items.get(2);
        final var simple = items.get(3);
        final var latinIme = List.of(english, french, italian);
        final var simpleIme = List.of(simple);

        final var hardwareItems = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(hardwareItems, "HardwareLatinIme", "HardwareLatinIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(hardwareItems, "HardwareSimpleIme", "HardwareSimpleIme",
                null, true /* supportsSwitchingToNextInputMethod */);

        final var hardwareEnglish = hardwareItems.get(0);
        final var hardwareFrench = hardwareItems.get(1);
        final var hardwareItalian = hardwareItems.get(2);
        final var hardwareSimple = hardwareItems.get(3);
        final var hardwareLatinIme = List.of(hardwareEnglish, hardwareFrench, hardwareItalian);
        final var hardwareSimpleIme = List.of(hardwareSimple);

        final var controller = new InputMethodSubtypeSwitchingController();
        controller.update(items, hardwareItems);

        final int mode = MODE_STATIC;

        // Static mode matches the given items order.
        assertNextOrder(controller, false /* forHardware */, mode,
                items, List.of(latinIme, simpleIme));

        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));

        // Set french IME as most recent.
        assertTrue("Recency updated for french IME", onUserAction(controller, french));

        // Static mode is not influenced by recency updates on non-hardware item.
        assertNextOrder(controller, false /* forHardware */, mode,
                items, List.of(latinIme, simpleIme));

        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));

        assertTrue("Recency updated for french hardware IME",
                onUserAction(controller, hardwareFrench));

        // Static mode is not influenced by recency updates on hardware item.
        assertNextOrder(controller, false /* forHardware */, mode,
                items, List.of(latinIme, simpleIme));

        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));
    }

    /** Verifies the recency mode. */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testModeRecent() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "SimpleIme", "SimpleIme",
                null, true /* supportsSwitchingToNextInputMethod */);

        final var english = items.get(0);
        final var french = items.get(1);
        final var italian = items.get(2);
        final var simple = items.get(3);
        final var latinIme = List.of(english, french, italian);
        final var simpleIme = List.of(simple);

        final var hardwareItems = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(hardwareItems, "HardwareLatinIme", "HardwareLatinIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(hardwareItems, "HardwareSimpleIme", "HardwareSimpleIme",
                null, true /* supportsSwitchingToNextInputMethod */);

        final var hardwareEnglish = hardwareItems.get(0);
        final var hardwareFrench = hardwareItems.get(1);
        final var hardwareItalian = hardwareItems.get(2);
        final var hardwareSimple = hardwareItems.get(3);
        final var hardwareLatinIme = List.of(hardwareEnglish, hardwareFrench, hardwareItalian);
        final var hardwareSimpleIme = List.of(hardwareSimple);

        final var controller = new InputMethodSubtypeSwitchingController();
        controller.update(items, hardwareItems);

        final int mode = MODE_RECENT;

        // Recency order is initialized to static order.
        assertNextOrder(controller, false /* forHardware */, mode,
                items, List.of(latinIme, simpleIme));

        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));

        assertTrue("Recency updated for french IME", onUserAction(controller, french));
        final var recencyItems = List.of(french, english, italian, simple);
        final var recencyLatinIme = List.of(french, english, italian);
        final var recencySimpleIme = List.of(simple);

        // The order of non-hardware items is updated.
        assertNextOrder(controller, false /* forHardware */, mode,
                recencyItems, List.of(recencyLatinIme, recencySimpleIme));

        // The order of hardware items remains unchanged for an action on a non-hardware item.
        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));

        assertFalse("Recency not updated again for same IME", onUserAction(controller, french));

        // The order of non-hardware items remains unchanged.
        assertNextOrder(controller, false /* forHardware */, mode,
                recencyItems, List.of(recencyLatinIme, recencySimpleIme));

        // The order of hardware items remains unchanged.
        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));

        assertTrue("Recency updated for french hardware IME",
                onUserAction(controller, hardwareFrench));

        final var recencyHardwareItems =
                List.of(hardwareFrench, hardwareEnglish, hardwareItalian, hardwareSimple);
        final var recencyHardwareLatinIme =
                List.of(hardwareFrench, hardwareEnglish, hardwareItalian);
        final var recencyHardwareSimpleIme = List.of(hardwareSimple);

        // The order of non-hardware items is unchanged.
        assertNextOrder(controller, false /* forHardware */, mode,
                recencyItems, List.of(recencyLatinIme, recencySimpleIme));

        // The order of hardware items is updated.
        assertNextOrder(controller, true /* forHardware */, mode,
                recencyHardwareItems, List.of(recencyHardwareLatinIme, recencyHardwareSimpleIme));
    }

    /** Verifies the auto mode. */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testModeAuto() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "SimpleIme", "SimpleIme",
                null, true /* supportsSwitchingToNextInputMethod */);

        final var english = items.get(0);
        final var french = items.get(1);
        final var italian = items.get(2);
        final var simple = items.get(3);
        final var latinIme = List.of(english, french, italian);
        final var simpleIme = List.of(simple);

        final var hardwareItems = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(hardwareItems, "HardwareLatinIme", "HardwareLatinIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(hardwareItems, "HardwareSimpleIme", "HardwareSimpleIme",
                null, true /* supportsSwitchingToNextInputMethod */);

        final var hardwareEnglish = hardwareItems.get(0);
        final var hardwareFrench = hardwareItems.get(1);
        final var hardwareItalian = hardwareItems.get(2);
        final var hardwareSimple = hardwareItems.get(3);
        final var hardwareLatinIme = List.of(hardwareEnglish, hardwareFrench, hardwareItalian);
        final var hardwareSimpleIme = List.of(hardwareSimple);

        final var controller = new InputMethodSubtypeSwitchingController();
        controller.update(items, hardwareItems);

        final int mode = MODE_AUTO;

        // Auto mode resolves to static order initially.
        assertNextOrder(controller, false /* forHardware */, mode,
                items, List.of(latinIme, simpleIme));

        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));

        // User action on french IME.
        assertTrue("Recency updated for french IME", onUserAction(controller, french));

        final var recencyItems = List.of(french, english, italian, simple);
        final var recencyLatinIme = List.of(french, english, italian);
        final var recencySimpleIme = List.of(simple);

        // Auto mode resolves to recency order for the first forward after user action, and to
        // static order for the backwards direction.
        assertNextOrder(controller, false /* forHardware */, mode, true /* forward */,
                recencyItems, List.of(recencyLatinIme, recencySimpleIme));
        assertNextOrder(controller, false /* forHardware */, mode, false /* forward */,
                items.reversed(), List.of(latinIme.reversed(), simpleIme.reversed()));

        // Auto mode resolves to recency order for the first forward after user action,
        // but the recency was not updated for hardware items, so it's equivalent to static order.
        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));

        // Change IME, reset user action having happened.
        controller.onInputMethodSubtypeChanged();

        // Auto mode resolves to static order as there was no user action since changing IMEs.
        assertNextOrder(controller, false /* forHardware */, mode,
                items, List.of(latinIme, simpleIme));

        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));

        // User action on french IME again.
        assertFalse("Recency not updated again for same IME", onUserAction(controller, french));

        // Auto mode still resolves to static order, as a user action on the currently most
        // recent IME has no effect.
        assertNextOrder(controller, false /* forHardware */, mode,
                items, List.of(latinIme, simpleIme));

        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));

        // User action on hardware french IME.
        assertTrue("Recency updated for french hardware IME",
                onUserAction(controller, hardwareFrench));

        final var recencyHardware =
                List.of(hardwareFrench, hardwareEnglish, hardwareItalian, hardwareSimple);
        final var recencyHardwareLatin =
                List.of(hardwareFrench, hardwareEnglish, hardwareItalian);
        final var recencyHardwareSimple = List.of(hardwareSimple);

        // Auto mode resolves to recency order for the first forward direction after a user action
        // on a hardware IME, and to static order for the backwards direction.
        assertNextOrder(controller, false /* forHardware */, mode, true /* forward */,
                recencyItems, List.of(recencyLatinIme, recencySimpleIme));
        assertNextOrder(controller, false /* forHardware */, mode, false /* forward */,
                items.reversed(), List.of(latinIme.reversed(), simpleIme.reversed()));

        assertNextOrder(controller, true /* forHardware */, mode, true /* forward */,
                recencyHardware, List.of(recencyHardwareLatin, recencyHardwareSimple));

        assertNextOrder(controller, true /* forHardware */, mode, false /* forward */,
                hardwareItems.reversed(),
                List.of(hardwareLatinIme.reversed(), hardwareSimpleIme.reversed()));
    }

    /**
     * Verifies that the recency order is preserved only when updating with an equal list of items.
     */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testUpdateList() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(items, "SimpleIme", "SimpleIme",
                null, true /* supportsSwitchingToNextInputMethod */);

        final var english = items.get(0);
        final var french = items.get(1);
        final var italian = items.get(2);
        final var simple = items.get(3);

        final var latinIme = List.of(english, french, italian);
        final var simpleIme = List.of(simple);

        final var hardwareItems = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(hardwareItems, "HardwareLatinIme", "HardwareLatinIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);
        addTestImeSubtypeListItems(hardwareItems, "HardwareSimpleIme", "HardwareSimpleIme",
                null, true /* supportsSwitchingToNextInputMethod */);

        final var hardwareEnglish = hardwareItems.get(0);
        final var hardwareFrench = hardwareItems.get(1);
        final var hardwareItalian = hardwareItems.get(2);
        final var hardwareSimple = hardwareItems.get(3);

        final var hardwareLatinIme = List.of(hardwareEnglish, hardwareFrench, hardwareItalian);
        final var hardwareSimpleIme = List.of(hardwareSimple);

        final var controller = new InputMethodSubtypeSwitchingController();
        controller.update(items, hardwareItems);

        final int mode = MODE_RECENT;

        // Recency order is initialized to static order.
        assertNextOrder(controller, false /* forHardware */, mode,
                items, List.of(latinIme, simpleIme));
        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));

        // User action on french IME.
        assertTrue("Recency updated for french IME", onUserAction(controller, french));

        final var recencyItems = List.of(french, english, italian, simple);
        final var recencyLatinIme = List.of(french, english, italian);
        final var recencySimpleIme = List.of(simple);

        final var equalItems = new ArrayList<>(items);
        controller.update(equalItems, hardwareItems);

        // The order of non-hardware items remains unchanged when updated with equal items.
        assertNextOrder(controller, false /* forHardware */, mode,
                recencyItems, List.of(recencyLatinIme, recencySimpleIme));
        // The order of hardware items remains unchanged when only non-hardware items are updated.
        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));

        final var otherItems = new ArrayList<>(items);
        otherItems.remove(simple);
        controller.update(otherItems, hardwareItems);

        // The order of non-hardware items is reset when updated with other items.
        assertNextOrder(controller, false /* forHardware */, mode,
                latinIme, List.of(latinIme));
        // The order of hardware items remains unchanged when only non-hardware items are updated.
        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareItems, List.of(hardwareLatinIme, hardwareSimpleIme));

        assertTrue("Recency updated for french hardware IME",
                onUserAction(controller, hardwareFrench));

        final var recencyHardwareItems =
                List.of(hardwareFrench, hardwareEnglish, hardwareItalian, hardwareSimple);
        final var recencyHardwareLatinIme =
                List.of(hardwareFrench, hardwareEnglish, hardwareItalian);
        final var recencyHardwareSimpleIme = List.of(hardwareSimple);

        final var equalHardwareItems = new ArrayList<>(hardwareItems);
        controller.update(otherItems, equalHardwareItems);

        // The order of non-hardware items remains unchanged when only hardware items are updated.
        assertNextOrder(controller, false /* forHardware */, mode,
                latinIme, List.of(latinIme));
        // The order of hardware items remains unchanged when updated with equal items.
        assertNextOrder(controller, true /* forHardware */, mode,
                recencyHardwareItems, List.of(recencyHardwareLatinIme, recencyHardwareSimpleIme));

        final var otherHardwareItems = new ArrayList<>(hardwareItems);
        otherHardwareItems.remove(hardwareSimple);
        controller.update(otherItems, otherHardwareItems);

        // The order of non-hardware items remains unchanged when only hardware items are updated.
        assertNextOrder(controller, false /* forHardware */, mode,
                latinIme, List.of(latinIme));
        // The order of hardware items is reset when updated with other items.
        assertNextOrder(controller, true /* forHardware */, mode,
                hardwareLatinIme, List.of(hardwareLatinIme));
    }

    /** Verifies that switch aware and switch unaware IMEs are combined together. */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testSwitchAwareAndUnawareCombined() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "switchAware", "switchAware",
                null, true /* supportsSwitchingToNextInputMethod*/);
        addTestImeSubtypeListItems(items, "switchUnaware", "switchUnaware",
                null, false /* supportsSwitchingToNextInputMethod*/);

        final var hardwareItems = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(hardwareItems, "hardwareSwitchAware", "hardwareSwitchAware",
                null, true /* supportsSwitchingToNextInputMethod*/);
        addTestImeSubtypeListItems(hardwareItems, "hardwareSwitchUnaware", "hardwareSwitchUnaware",
                null, false /* supportsSwitchingToNextInputMethod*/);

        final var controller = new InputMethodSubtypeSwitchingController();
        controller.update(items, hardwareItems);

        for (int mode = MODE_STATIC; mode <= MODE_AUTO; mode++) {
            assertNextOrder(controller, false /* forHardware */, false /* onlyCurrentIme */,
                    mode, true /* forward */, items);
            assertNextOrder(controller, false /* forHardware */, false /* onlyCurrentIme */,
                    mode, false /* forward */, items.reversed());

            assertNextOrder(controller, true /* forHardware */, false /* onlyCurrentIme */,
                    mode, true /* forward */, hardwareItems);
            assertNextOrder(controller, true /* forHardware */, false /* onlyCurrentIme */,
                    mode, false /* forward */, hardwareItems.reversed());
        }
    }

    /** Verifies that an empty controller can't take any actions. */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testEmptyList() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr"), true /* supportsSwitchingToNextInputMethod */);

        final var hardwareItems = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(hardwareItems, "HardwareIme", "HardwareIme",
                List.of("en", "fr"), true /* supportsSwitchingToNextInputMethod */);

        final var controller = new InputMethodSubtypeSwitchingController();

        assertNextItemNoAction(controller, false /* forHardware */, items,
                null /* expectedNext */);
        assertNextItemNoAction(controller, true /* forHardware */, hardwareItems,
                null /* expectedNext */);
    }

    /**
     * Verifies that a controller with a single item can't update the recency, and cannot switch
     * away from the item, but allows switching from unknown items to the single item.
     */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testSingleItemList() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                null, true /* supportsSwitchingToNextInputMethod */);
        final var unknownItems = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(unknownItems, "UnknownIme", "UnknownIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);

        final var hardwareItems = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(hardwareItems, "HardwareIme", "HardwareIme",
                null, true /* supportsSwitchingToNextInputMethod */);
        final var unknownHardwareItems = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(unknownHardwareItems, "HardwareUnknownIme", "HardwareUnknownIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);

        final var controller = new InputMethodSubtypeSwitchingController();
        controller.update(items, hardwareItems);

        assertNextItemNoAction(controller, false /* forHardware */, items,
                null /* expectedNext */);
        assertNextItemNoAction(controller, false /* forHardware */, unknownItems,
                items.get(0));
        assertNextItemNoAction(controller, true /* forHardware */, hardwareItems,
                null /* expectedNext */);
        assertNextItemNoAction(controller, true /* forHardware */, unknownHardwareItems,
                hardwareItems.get(0));
    }

    /**
     * Verifies that the recency cannot be updated for unknown items, but switching from unknown
     * items reaches the most recent known item.
     */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testUnknownItems() {
        final var items = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(items, "LatinIme", "LatinIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);

        final var english = items.get(0);
        final var french = items.get(1);
        final var italian = items.get(2);

        final var unknownItems = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(unknownItems, "UnknownIme", "UnknownIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);

        final var hardwareItems = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(hardwareItems, "HardwareIme", "HardwareIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);
        final var unknownHardwareItems = new ArrayList<ImeSubtypeListItem>();
        addTestImeSubtypeListItems(unknownHardwareItems, "HardwareUnknownIme", "HardwareUnknownIme",
                List.of("en", "fr", "it"), true /* supportsSwitchingToNextInputMethod */);

        final var controller = new InputMethodSubtypeSwitchingController();
        controller.update(items, hardwareItems);

        assertTrue("Recency updated for french IME", onUserAction(controller, french));

        final var recencyItems = List.of(french, english, italian);

        assertNextItemNoAction(controller, false /* forHardware */, unknownItems,
                french);
        assertNextItemNoAction(controller, true /* forHardware */, unknownHardwareItems,
                hardwareItems.get(0));

        // Known items must not be able to switch to unknown items.
        assertNextOrder(controller, false /* forHardware */, MODE_STATIC, items,
                List.of(items));
        assertNextOrder(controller, false /* forHardware */, MODE_RECENT, recencyItems,
                List.of(recencyItems));
        assertNextOrder(controller, false /* forHardware */, MODE_AUTO, true /* forward */,
                recencyItems, List.of(recencyItems));
        assertNextOrder(controller, false /* forHardware */, MODE_AUTO, false /* forward */,
                items.reversed(), List.of(items.reversed()));

        assertNextOrder(controller, true /* forHardware */, MODE_STATIC, hardwareItems,
                List.of(hardwareItems));
        assertNextOrder(controller, true /* forHardware */, MODE_RECENT, hardwareItems,
                List.of(hardwareItems));
        assertNextOrder(controller, true /* forHardware */, MODE_AUTO, hardwareItems,
                List.of(hardwareItems));
    }

    /** Verifies that the IME name does influence the comparison order. */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testCompareImeName() {
        final var component = new ComponentName("com.example.ime", "Ime");
        final var imeX = createTestItem(component, "ImeX", "A", "en_US", 0);
        final var imeY = createTestItem(component, "ImeY", "A", "en_US", 0);

        assertTrue("Smaller IME name should be smaller.", imeX.compareTo(imeY) < 0);
        assertTrue("Larger IME name should be larger.", imeY.compareTo(imeX) > 0);
    }

    /** Verifies that the IME ID does influence the comparison order. */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testCompareImeId() {
        final var component1 = new ComponentName("com.example.ime1", "Ime");
        final var component2 = new ComponentName("com.example.ime2", "Ime");
        final var ime1 = createTestItem(component1, "Ime", "A", "en_US", 0);
        final var ime2 = createTestItem(component2, "Ime", "A", "en_US", 0);

        assertTrue("Smaller IME ID should be smaller.", ime1.compareTo(ime2) < 0);
        assertTrue("Larger IME ID should be larger.", ime2.compareTo(ime1) > 0);
    }

    /** Verifies that comparison on self returns an equal order. */
    @SuppressWarnings("SelfComparison")
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testCompareSelf() {
        final var component = new ComponentName("com.example.ime", "Ime");
        final var item = createTestItem(component, "Ime", "A", "en_US", 0);

        assertEquals("Item should have the same order to itself.", 0, item.compareTo(item));
    }

    /** Verifies that comparison on an equivalent item returns an equal order. */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testCompareEquivalent() {
        final var component = new ComponentName("com.example.ime", "Ime");
        final var item = createTestItem(component, "Ime", "A", "en_US", 0);
        final var equivalent = createTestItem(component, "Ime", "A", "en_US", 0);

        assertEquals("Equivalent items should have the same order.", 0, item.compareTo(equivalent));
    }

    /**
     * Verifies that the system locale and system language do not the influence comparison order.
     */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testCompareSystemLocaleSystemLanguage() {
        final var component = new ComponentName("com.example.ime", "Ime");
        final var japanese = createTestItem(component, "Ime", "A", "ja_JP", 0);
        final var systemLanguage = createTestItem(component, "Ime", "A", "en_GB", 0);
        final var systemLocale = createTestItem(component, "Ime", "A", "en_US", 0);

        assertFalse(japanese.mIsSystemLanguage);
        assertFalse(japanese.mIsSystemLocale);
        assertTrue(systemLanguage.mIsSystemLanguage);
        assertFalse(systemLanguage.mIsSystemLocale);
        assertTrue(systemLocale.mIsSystemLanguage);
        assertTrue(systemLocale.mIsSystemLocale);

        assertEquals("System language shouldn't influence comparison over non-system language.",
                0, japanese.compareTo(systemLanguage));
        assertEquals("System locale shouldn't influence comparison over non-system locale.",
                0, japanese.compareTo(systemLocale));
        assertEquals("System locale shouldn't influence comparison over system language.",
                0, systemLanguage.compareTo(systemLocale));
    }

    /** Verifies that the subtype name does not influence the comparison order. */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testCompareSubtypeName() {
        final var component = new ComponentName("com.example.ime", "Ime");
        final var subtypeA = createTestItem(component, "Ime", "A", "en_US", 0);
        final var subtypeB = createTestItem(component, "Ime", "B", "en_US", 0);

        assertEquals("Subtype name shouldn't influence comparison.",
                0, subtypeA.compareTo(subtypeB));
    }

    /** Verifies that the subtype index does not influence the comparison order. */
    @RequiresFlagsEnabled(Flags.FLAG_IME_SWITCHER_REVAMP)
    @Test
    public void testCompareSubtypeIndex() {
        final var component = new ComponentName("com.example.ime", "Ime");
        final var subtype0 = createTestItem(component, "Ime1", "A", "en_US", 0);
        final var subtype1 = createTestItem(component, "Ime1", "A", "en_US", 1);

        assertEquals("Subtype index shouldn't influence comparison.",
                0, subtype0.compareTo(subtype1));
    }

    /**
     * Verifies that the controller's next item order matches the given one, and cycles back at
     * the end, both across all IMEs, and also per each IME. If a single item is given, verifies
     * that no next item is returned.
     *
     * @param controller  the controller to use for finding the next items.
     * @param forHardware whether to find the next hardware item, or software item.
     * @param mode        the switching mode.
     * @param forward     whether to search forwards or backwards in the list.
     * @param allItems    the list of items across all IMEs.
     * @param perImeItems the list of lists of items per IME.
     */
    private static void assertNextOrder(@NonNull InputMethodSubtypeSwitchingController controller,
            boolean forHardware, @SwitchMode int mode, boolean forward,
            @NonNull List<ImeSubtypeListItem> allItems,
            @NonNull List<List<ImeSubtypeListItem>> perImeItems) {
        assertNextOrder(controller, forHardware, false /* onlyCurrentIme */, mode,
                forward, allItems);

        for (var imeItems : perImeItems) {
            assertNextOrder(controller, forHardware, true /* onlyCurrentIme */, mode,
                    forward, imeItems);
        }
    }

    /**
     * Verifies that the controller's next item order matches the given one, and cycles back at
     * the end, both across all IMEs, and also per each IME. This checks the forward direction
     * with the given items, and the backwards order with the items reversed. If a single item is
     * given, verifies that no next item is returned.
     *
     * @param controller  the controller to use for finding the next items.
     * @param forHardware whether to find the next hardware item, or software item.
     * @param mode        the switching mode.
     * @param allItems    the list of items across all IMEs.
     * @param perImeItems the list of lists of items per IME.
     */
    private static void assertNextOrder(@NonNull InputMethodSubtypeSwitchingController controller,
            boolean forHardware, @SwitchMode int mode, @NonNull List<ImeSubtypeListItem> allItems,
            @NonNull List<List<ImeSubtypeListItem>> perImeItems) {
        assertNextOrder(controller, forHardware, false /* onlyCurrentIme */, mode,
                true /* forward */, allItems);
        assertNextOrder(controller, forHardware, false /* onlyCurrentIme */, mode,
                false /* forward */, allItems.reversed());

        for (var imeItems : perImeItems) {
            assertNextOrder(controller, forHardware, true /* onlyCurrentIme */, mode,
                    true /* forward */, imeItems);
            assertNextOrder(controller, forHardware, true /* onlyCurrentIme */, mode,
                    false /* forward */, imeItems.reversed());
        }
    }

    /**
     * Verifies that the controller's next item order (starting from the first one in {@code items}
     * matches the given on, and cycles back at the end. If a single item is given, verifies that
     * no next item is returned.
     *
     * @param controller     the controller to use for finding the next items.
     * @param forHardware    whether to find the next hardware item, or software item.
     * @param onlyCurrentIme whether to consider only subtypes of the current input method.
     * @param mode           the switching mode.
     * @param forward        whether to search forwards or backwards in the list.
     * @param items          the list of items to verify, in the expected order.
     */
    private static void assertNextOrder(@NonNull InputMethodSubtypeSwitchingController controller,
            boolean forHardware, boolean onlyCurrentIme, @SwitchMode int mode, boolean forward,
            @NonNull List<ImeSubtypeListItem> items) {
        final int numItems = items.size();
        if (numItems == 0) {
            return;
        } else if (numItems == 1) {
            // Single item controllers should never return a next item.
            assertNextItem(controller, forHardware, onlyCurrentIme, mode, forward, items.get(0),
                    null /* expectedNext*/);
            return;
        }

        var item = items.get(0);

        final var expectedNextItems = new ArrayList<>(items);
        // Add first item in the last position of expected order, to ensure the order is cyclic.
        expectedNextItems.add(item);

        final var nextItems = new ArrayList<>();
        // Add first item in the first position of actual order, to ensure the order is cyclic.
        nextItems.add(item);

        // Compute the nextItems starting from the first given item, and compare the order.
        for (int i = 0; i < numItems; i++) {
            item = getNextItem(controller, forHardware, onlyCurrentIme, mode, forward, item);
            assertNotNull("Next item shouldn't be null.", item);
            nextItems.add(item);
        }

        assertEquals("Rotation order doesn't match.", expectedNextItems, nextItems);
    }

    /**
     * Verifies that the controller gets the expected next value from the given item.
     *
     * @param controller     the controller to sue for finding the next value.
     * @param forHardware    whether to find the next hardware item, or software item.
     * @param onlyCurrentIme whether to consider only subtypes of the current input method.
     * @param mode           the switching mode.
     * @param forward        whether to search forwards or backwards in the list.
     * @param item           the item to find the next value from.
     * @param expectedNext   the expected next value.
     */
    private static void assertNextItem(@NonNull InputMethodSubtypeSwitchingController controller,
            boolean forHardware, boolean onlyCurrentIme, @SwitchMode int mode, boolean forward,
            @NonNull ImeSubtypeListItem item, @Nullable ImeSubtypeListItem expectedNext) {
        final var nextItem = getNextItem(controller, forHardware, onlyCurrentIme, mode, forward,
                item);
        assertEquals("Next item doesn't match.", expectedNext, nextItem);
    }

    /**
     * Gets the next value from the given item.
     *
     * @param controller     the controller to use for finding the next value.
     * @param forHardware    whether to find the next hardware item, or software item.
     * @param onlyCurrentIme whether to consider only subtypes of the current input method.
     * @param mode           the switching mode.
     * @param forward        whether to search forwards or backwards in the list.
     * @param item           the item to find the next value from.
     * @return the next item found, otherwise {@code null}.
     */
    @Nullable
    private static ImeSubtypeListItem getNextItem(
            @NonNull InputMethodSubtypeSwitchingController controller, boolean forHardware,
            boolean onlyCurrentIme, @SwitchMode int mode, boolean forward,
            @NonNull ImeSubtypeListItem item) {
        final var subtype = item.mSubtypeName != null
                ? createTestSubtype(item.mSubtypeName.toString()) : null;
        return forHardware
                ? controller.getNextInputMethodForHardware(
                        onlyCurrentIme, item.mImi, subtype, mode, forward)
                : controller.getNextInputMethodLocked(
                        onlyCurrentIme, item.mImi, subtype, mode, forward);
    }

    /**
     * Verifies that the expected next item is returned, and the recency cannot be updated for the
     * given items.
     *
     * @param controller   the controller to verify the items on.
     * @param forHardware  whether to try finding the next hardware item, or software item.
     * @param items        the list of items to verify.
     * @param expectedNext the expected next item.
     */
    private void assertNextItemNoAction(@NonNull InputMethodSubtypeSwitchingController controller,
            boolean forHardware, @NonNull List<ImeSubtypeListItem> items,
            @Nullable ImeSubtypeListItem expectedNext) {
        for (var item : items) {
            for (int mode = MODE_STATIC; mode <= MODE_AUTO; mode++) {
                assertNextItem(controller, forHardware, false /* onlyCurrentIme */, mode,
                        false /* forward */, item, expectedNext);
                assertNextItem(controller, forHardware, false /* onlyCurrentIme */, mode,
                        true /* forward */, item, expectedNext);
                assertNextItem(controller, forHardware, true /* onlyCurrentIme */, mode,
                        false /* forward */, item, expectedNext);
                assertNextItem(controller, forHardware, true /* onlyCurrentIme */, mode,
                        true /* forward */, item, expectedNext);
            }

            assertFalse("User action shouldn't have updated the recency.",
                    onUserAction(controller, item));
        }
    }
}
