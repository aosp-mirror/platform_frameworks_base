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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.server.inputmethod.InputMethodSubtypeSwitchingController.ControllerImpl;
import com.android.server.inputmethod.InputMethodSubtypeSwitchingController.ImeSubtypeListItem;

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
    private static final int NOT_A_SUBTYPE_ID = InputMethodUtils.NOT_A_SUBTYPE_ID;

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
                    NOT_A_SUBTYPE_ID, null, SYSTEM_LOCALE));
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

    private void assertNextInputMethod(@NonNull ControllerImpl controller, boolean onlyCurrentIme,
            @NonNull ImeSubtypeListItem currentItem, @Nullable ImeSubtypeListItem nextItem) {
        InputMethodSubtype subtype = null;
        if (currentItem.mSubtypeName != null) {
            subtype = createTestSubtype(currentItem.mSubtypeName.toString());
        }
        final ImeSubtypeListItem nextIme = controller.getNextInputMethod(onlyCurrentIme,
                currentItem.mImi, subtype);
        assertEquals(nextItem, nextIme);
    }

    private void assertRotationOrder(@NonNull ControllerImpl controller, boolean onlyCurrentIme,
            ImeSubtypeListItem... expectedRotationOrderOfImeSubtypeList) {
        final int numItems = expectedRotationOrderOfImeSubtypeList.length;
        for (int i = 0; i < numItems; i++) {
            final int nextIndex = (i + 1) % numItems;
            final ImeSubtypeListItem currentItem = expectedRotationOrderOfImeSubtypeList[i];
            final ImeSubtypeListItem nextItem = expectedRotationOrderOfImeSubtypeList[nextIndex];
            assertNextInputMethod(controller, onlyCurrentIme, currentItem, nextItem);
        }
    }

    private void onUserAction(@NonNull ControllerImpl controller,
            @NonNull ImeSubtypeListItem subtypeListItem) {
        InputMethodSubtype subtype = null;
        if (subtypeListItem.mSubtypeName != null) {
            subtype = createTestSubtype(subtypeListItem.mSubtypeName.toString());
        }
        controller.onUserActionLocked(subtypeListItem.mImi, subtype);
    }

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

        final ControllerImpl controller = ControllerImpl.createFrom(
                null /* currentInstance */, enabledItems);

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

        final ControllerImpl controller = ControllerImpl.createFrom(
                null /* currentInstance */, enabledItems);

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
        final ControllerImpl newController = ControllerImpl.createFrom(controller,
                sameEnabledItems);
        assertRotationOrder(newController, false /* onlyCurrentIme */,
                subtypeAwareIme, latinIme_fr, latinIme_en_us, japaneseIme_ja_jp);
        assertRotationOrder(newController, false /* onlyCurrentIme */,
                switchingUnawareLatinIme_en_uk, switchingUnawareLatinIme_hi, subtypeUnawareIme,
                switchUnawareJapaneseIme_ja_jp);

        // Rotation order should be initialized when created with a different subtype list.
        final List<ImeSubtypeListItem> differentEnabledItems = List.of(
                latinIme_en_us, latinIme_fr, subtypeAwareIme, switchingUnawareLatinIme_en_uk,
                switchUnawareJapaneseIme_ja_jp, subtypeUnawareIme);
        final ControllerImpl anotherController = ControllerImpl.createFrom(controller,
                differentEnabledItems);
        assertRotationOrder(anotherController, false /* onlyCurrentIme */,
                latinIme_en_us, latinIme_fr, subtypeAwareIme);
        assertRotationOrder(anotherController, false /* onlyCurrentIme */,
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
}
