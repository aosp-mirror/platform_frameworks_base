/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import com.android.internal.inputmethod.InputMethodSubtypeSwitchingController.ControllerImpl;
import com.android.internal.inputmethod.InputMethodSubtypeSwitchingController.ImeSubtypeListItem;
import com.android.internal.inputmethod.InputMethodUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InputMethodSubtypeSwitchingControllerTest {
    private static final String DUMMY_PACKAGE_NAME = "dummy package name";
    private static final String DUMMY_IME_LABEL = "dummy ime label";
    private static final String DUMMY_SETTING_ACTIVITY_NAME = "";
    private static final boolean DUMMY_IS_AUX_IME = false;
    private static final boolean DUMMY_FORCE_DEFAULT = false;
    private static final boolean DUMMY_IS_VR_IME = false;
    private static final int DUMMY_IS_DEFAULT_RES_ID = 0;
    private static final String SYSTEM_LOCALE = "en_US";
    private static final int NOT_A_SUBTYPE_ID = InputMethodUtils.NOT_A_SUBTYPE_ID;

    private static InputMethodSubtype createDummySubtype(final String locale) {
        final InputMethodSubtypeBuilder builder = new InputMethodSubtypeBuilder();
        return builder.setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setSubtypeLocale(locale)
                .setIsAsciiCapable(true)
                .build();
    }

    private static void addDummyImeSubtypeListItems(List<ImeSubtypeListItem> items,
            String imeName, String imeLabel, List<String> subtypeLocales,
            boolean supportsSwitchingToNextInputMethod) {
        final ResolveInfo ri = new ResolveInfo();
        final ServiceInfo si = new ServiceInfo();
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = DUMMY_PACKAGE_NAME;
        ai.enabled = true;
        si.applicationInfo = ai;
        si.enabled = true;
        si.packageName = DUMMY_PACKAGE_NAME;
        si.name = imeName;
        si.exported = true;
        si.nonLocalizedLabel = imeLabel;
        ri.serviceInfo = si;
        List<InputMethodSubtype> subtypes = null;
        if (subtypeLocales != null) {
            subtypes = new ArrayList<InputMethodSubtype>();
            for (String subtypeLocale : subtypeLocales) {
                subtypes.add(createDummySubtype(subtypeLocale));
            }
        }
        final InputMethodInfo imi = new InputMethodInfo(ri, DUMMY_IS_AUX_IME,
                DUMMY_SETTING_ACTIVITY_NAME, subtypes, DUMMY_IS_DEFAULT_RES_ID,
                DUMMY_FORCE_DEFAULT, supportsSwitchingToNextInputMethod, DUMMY_IS_VR_IME);
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

    private static ImeSubtypeListItem createDummyItem(String imeName,
            String subtypeName, String subtypeLocale, int subtypeIndex, String systemLocale) {
        final ResolveInfo ri = new ResolveInfo();
        final ServiceInfo si = new ServiceInfo();
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = DUMMY_PACKAGE_NAME;
        ai.enabled = true;
        si.applicationInfo = ai;
        si.enabled = true;
        si.packageName = DUMMY_PACKAGE_NAME;
        si.name = imeName;
        si.exported = true;
        si.nonLocalizedLabel = DUMMY_IME_LABEL;
        ri.serviceInfo = si;
        ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
        subtypes.add(new InputMethodSubtypeBuilder()
                .setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setSubtypeLocale(subtypeLocale)
                .setIsAsciiCapable(true)
                .build());
        final InputMethodInfo imi = new InputMethodInfo(ri, DUMMY_IS_AUX_IME,
                DUMMY_SETTING_ACTIVITY_NAME, subtypes, DUMMY_IS_DEFAULT_RES_ID,
                DUMMY_FORCE_DEFAULT, true /* supportsSwitchingToNextInputMethod */,
                DUMMY_IS_VR_IME);
        return new ImeSubtypeListItem(imeName, subtypeName, imi, subtypeIndex, subtypeLocale,
                systemLocale);
    }

    private static List<ImeSubtypeListItem> createEnabledImeSubtypes() {
        final List<ImeSubtypeListItem> items = new ArrayList<ImeSubtypeListItem>();
        addDummyImeSubtypeListItems(items, "LatinIme", "LatinIme", Arrays.asList("en_US", "fr"),
                true /* supportsSwitchingToNextInputMethod*/);
        addDummyImeSubtypeListItems(items, "switchUnawareLatinIme", "switchUnawareLatinIme",
                Arrays.asList("en_UK", "hi"),
                false /* supportsSwitchingToNextInputMethod*/);
        addDummyImeSubtypeListItems(items, "subtypeUnawareIme", "subtypeUnawareIme", null,
                false /* supportsSwitchingToNextInputMethod*/);
        addDummyImeSubtypeListItems(items, "JapaneseIme", "JapaneseIme", Arrays.asList("ja_JP"),
                true /* supportsSwitchingToNextInputMethod*/);
        addDummyImeSubtypeListItems(items, "switchUnawareJapaneseIme", "switchUnawareJapaneseIme",
                Arrays.asList("ja_JP"), false /* supportsSwitchingToNextInputMethod*/);
        return items;
    }

    private static List<ImeSubtypeListItem> createDisabledImeSubtypes() {
        final List<ImeSubtypeListItem> items = new ArrayList<ImeSubtypeListItem>();
        addDummyImeSubtypeListItems(items,
                "UnknownIme", "UnknownIme",
                Arrays.asList("en_US", "hi"),
                true /* supportsSwitchingToNextInputMethod*/);
        addDummyImeSubtypeListItems(items,
                "UnknownSwitchingUnawareIme", "UnknownSwitchingUnawareIme",
                Arrays.asList("en_US"),
                false /* supportsSwitchingToNextInputMethod*/);
        addDummyImeSubtypeListItems(items, "UnknownSubtypeUnawareIme",
                "UnknownSubtypeUnawareIme", null,
                false /* supportsSwitchingToNextInputMethod*/);
        return items;
    }

    private void assertNextInputMethod(final ControllerImpl controller,
            final boolean onlyCurrentIme,
            final ImeSubtypeListItem currentItem, final ImeSubtypeListItem nextItem) {
        InputMethodSubtype subtype = null;
        if (currentItem.mSubtypeName != null) {
            subtype = createDummySubtype(currentItem.mSubtypeName.toString());
        }
        final ImeSubtypeListItem nextIme = controller.getNextInputMethod(onlyCurrentIme,
                currentItem.mImi, subtype);
        assertEquals(nextItem, nextIme);
    }

    private void assertRotationOrder(final ControllerImpl controller,
            final boolean onlyCurrentIme,
            final ImeSubtypeListItem... expectedRotationOrderOfImeSubtypeList) {
        final int N = expectedRotationOrderOfImeSubtypeList.length;
        for (int i = 0; i < N; i++) {
            final int currentIndex = i;
            final int nextIndex = (currentIndex + 1) % N;
            final ImeSubtypeListItem currentItem =
                    expectedRotationOrderOfImeSubtypeList[currentIndex];
            final ImeSubtypeListItem nextItem = expectedRotationOrderOfImeSubtypeList[nextIndex];
            assertNextInputMethod(controller, onlyCurrentIme, currentItem, nextItem);
        }
    }

    private void onUserAction(final ControllerImpl controller,
            final ImeSubtypeListItem subtypeListItem) {
        InputMethodSubtype subtype = null;
        if (subtypeListItem.mSubtypeName != null) {
            subtype = createDummySubtype(subtypeListItem.mSubtypeName.toString());
        }
        controller.onUserActionLocked(subtypeListItem.mImi, subtype);
    }

    @Test
    public void testControllerImpl() throws Exception {
        final List<ImeSubtypeListItem> disabledItems = createDisabledImeSubtypes();
        final ImeSubtypeListItem disabledIme_en_US = disabledItems.get(0);
        final ImeSubtypeListItem disabledIme_hi = disabledItems.get(1);
        final ImeSubtypeListItem disabledSwitchingUnawareIme = disabledItems.get(2);
        final ImeSubtypeListItem disabledSubtypeUnawareIme = disabledItems.get(3);

        final List<ImeSubtypeListItem> enabledItems = createEnabledImeSubtypes();
        final ImeSubtypeListItem latinIme_en_US = enabledItems.get(0);
        final ImeSubtypeListItem latinIme_fr = enabledItems.get(1);
        final ImeSubtypeListItem switchingUnawarelatinIme_en_UK = enabledItems.get(2);
        final ImeSubtypeListItem switchingUnawarelatinIme_hi = enabledItems.get(3);
        final ImeSubtypeListItem subtypeUnawareIme = enabledItems.get(4);
        final ImeSubtypeListItem japaneseIme_ja_JP = enabledItems.get(5);
        final ImeSubtypeListItem switchUnawareJapaneseIme_ja_JP = enabledItems.get(6);

        final ControllerImpl controller = ControllerImpl.createFrom(
                null /* currentInstance */, enabledItems);

        // switching-aware loop
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                latinIme_en_US, latinIme_fr, japaneseIme_ja_JP);

        // switching-unaware loop
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                switchingUnawarelatinIme_en_UK, switchingUnawarelatinIme_hi, subtypeUnawareIme,
                switchUnawareJapaneseIme_ja_JP);

        // test onlyCurrentIme == true
        assertRotationOrder(controller, true /* onlyCurrentIme */,
                latinIme_en_US, latinIme_fr);
        assertRotationOrder(controller, true /* onlyCurrentIme */,
                switchingUnawarelatinIme_en_UK, switchingUnawarelatinIme_hi);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                subtypeUnawareIme, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                japaneseIme_ja_JP, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                switchUnawareJapaneseIme_ja_JP, null);

        // Make sure that disabled IMEs are not accepted.
        assertNextInputMethod(controller, false /* onlyCurrentIme */,
                disabledIme_en_US, null);
        assertNextInputMethod(controller, false /* onlyCurrentIme */,
                disabledIme_hi, null);
        assertNextInputMethod(controller, false /* onlyCurrentIme */,
                disabledSwitchingUnawareIme, null);
        assertNextInputMethod(controller, false /* onlyCurrentIme */,
                disabledSubtypeUnawareIme, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                disabledIme_en_US, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                disabledIme_hi, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                disabledSwitchingUnawareIme, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                disabledSubtypeUnawareIme, null);
    }

    @Test
    public void testControllerImplWithUserAction() throws Exception {
        final List<ImeSubtypeListItem> enabledItems = createEnabledImeSubtypes();
        final ImeSubtypeListItem latinIme_en_US = enabledItems.get(0);
        final ImeSubtypeListItem latinIme_fr = enabledItems.get(1);
        final ImeSubtypeListItem switchingUnawarelatinIme_en_UK = enabledItems.get(2);
        final ImeSubtypeListItem switchingUnawarelatinIme_hi = enabledItems.get(3);
        final ImeSubtypeListItem subtypeUnawareIme = enabledItems.get(4);
        final ImeSubtypeListItem japaneseIme_ja_JP = enabledItems.get(5);
        final ImeSubtypeListItem switchUnawareJapaneseIme_ja_JP = enabledItems.get(6);

        final ControllerImpl controller = ControllerImpl.createFrom(
                null /* currentInstance */, enabledItems);

        // === switching-aware loop ===
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                latinIme_en_US, latinIme_fr, japaneseIme_ja_JP);
        // Then notify that a user did something for latinIme_fr.
        onUserAction(controller, latinIme_fr);
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                latinIme_fr, latinIme_en_US, japaneseIme_ja_JP);
        // Then notify that a user did something for latinIme_fr again.
        onUserAction(controller, latinIme_fr);
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                latinIme_fr, latinIme_en_US, japaneseIme_ja_JP);
        // Then notify that a user did something for japaneseIme_ja_JP.
        onUserAction(controller, latinIme_fr);
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                japaneseIme_ja_JP, latinIme_fr, latinIme_en_US);
        // Check onlyCurrentIme == true.
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                japaneseIme_ja_JP, null);
        assertRotationOrder(controller, true /* onlyCurrentIme */,
                latinIme_fr, latinIme_en_US);
        assertRotationOrder(controller, true /* onlyCurrentIme */,
                latinIme_en_US, latinIme_fr);

        // === switching-unaware loop ===
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                switchingUnawarelatinIme_en_UK, switchingUnawarelatinIme_hi, subtypeUnawareIme,
                switchUnawareJapaneseIme_ja_JP);
        // User action should be ignored for switching unaware IMEs.
        onUserAction(controller, switchingUnawarelatinIme_hi);
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                switchingUnawarelatinIme_en_UK, switchingUnawarelatinIme_hi, subtypeUnawareIme,
                switchUnawareJapaneseIme_ja_JP);
        // User action should be ignored for switching unaware IMEs.
        onUserAction(controller, switchUnawareJapaneseIme_ja_JP);
        assertRotationOrder(controller, false /* onlyCurrentIme */,
                switchingUnawarelatinIme_en_UK, switchingUnawarelatinIme_hi, subtypeUnawareIme,
                switchUnawareJapaneseIme_ja_JP);
        // Check onlyCurrentIme == true.
        assertRotationOrder(controller, true /* onlyCurrentIme */,
                switchingUnawarelatinIme_en_UK, switchingUnawarelatinIme_hi);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                subtypeUnawareIme, null);
        assertNextInputMethod(controller, true /* onlyCurrentIme */,
                switchUnawareJapaneseIme_ja_JP, null);

        // Rotation order should be preserved when created with the same subtype list.
        final List<ImeSubtypeListItem> sameEnabledItems = createEnabledImeSubtypes();
        final ControllerImpl newController = ControllerImpl.createFrom(controller,
                sameEnabledItems);
        assertRotationOrder(newController, false /* onlyCurrentIme */,
                japaneseIme_ja_JP, latinIme_fr, latinIme_en_US);
        assertRotationOrder(newController, false /* onlyCurrentIme */,
                switchingUnawarelatinIme_en_UK, switchingUnawarelatinIme_hi, subtypeUnawareIme,
                switchUnawareJapaneseIme_ja_JP);

        // Rotation order should be initialized when created with a different subtype list.
        final List<ImeSubtypeListItem> differentEnabledItems = Arrays.asList(
                latinIme_en_US, latinIme_fr, switchingUnawarelatinIme_en_UK,
                switchUnawareJapaneseIme_ja_JP);
        final ControllerImpl anotherController = ControllerImpl.createFrom(controller,
                differentEnabledItems);
        assertRotationOrder(anotherController, false /* onlyCurrentIme */,
                latinIme_en_US, latinIme_fr);
        assertRotationOrder(anotherController, false /* onlyCurrentIme */,
                switchingUnawarelatinIme_en_UK, switchUnawareJapaneseIme_ja_JP);
    }

    @Test
    public void testImeSubtypeListItem() throws Exception {
        final List<ImeSubtypeListItem> items = new ArrayList<ImeSubtypeListItem>();
        addDummyImeSubtypeListItems(items, "LatinIme", "LatinIme",
                Arrays.asList("en_US", "fr", "en", "en_uk", "enn", "e", "EN_US"),
                true /* supportsSwitchingToNextInputMethod*/);
        final ImeSubtypeListItem item_en_US = items.get(0);
        final ImeSubtypeListItem item_fr = items.get(1);
        final ImeSubtypeListItem item_en = items.get(2);
        final ImeSubtypeListItem item_enn = items.get(3);
        final ImeSubtypeListItem item_e = items.get(4);
        final ImeSubtypeListItem item_EN_US = items.get(5);

        assertTrue(item_en_US.mIsSystemLocale);
        assertFalse(item_fr.mIsSystemLocale);
        assertFalse(item_en.mIsSystemLocale);
        assertFalse(item_en.mIsSystemLocale);
        assertFalse(item_enn.mIsSystemLocale);
        assertFalse(item_e.mIsSystemLocale);
        assertFalse(item_EN_US.mIsSystemLocale);

        assertTrue(item_en_US.mIsSystemLanguage);
        assertFalse(item_fr.mIsSystemLanguage);
        assertTrue(item_en.mIsSystemLanguage);
        assertFalse(item_enn.mIsSystemLocale);
        assertFalse(item_e.mIsSystemLocale);
        assertFalse(item_EN_US.mIsSystemLocale);
    }

    @Test
    public void testImeSubtypeListComparator() throws Exception {
        {
            final List<ImeSubtypeListItem> items = Arrays.asList(
                    // Subtypes of IME "X".
                    // Subtypes that has the same locale of the system's.
                    createDummyItem("X", "E", "en_US", 0, "en_US"),
                    createDummyItem("X", "Z", "en_US", 3, "en_US"),
                    createDummyItem("X", "", "en_US", 6, "en_US"),
                    // Subtypes that has the same language of the system's.
                    createDummyItem("X", "E", "en", 1, "en_US"),
                    createDummyItem("X", "Z", "en", 4, "en_US"),
                    createDummyItem("X", "", "en", 7, "en_US"),
                    // Subtypes that has different language than the system's.
                    createDummyItem("X", "A", "hi_IN", 27, "en_US"),
                    createDummyItem("X", "E", "ja", 2, "en_US"),
                    createDummyItem("X", "Z", "ja", 5, "en_US"),
                    createDummyItem("X", "", "ja", 8, "en_US"),

                    // Subtypes of IME "Y".
                    // Subtypes that has the same locale of the system's.
                    createDummyItem("Y", "E", "en_US", 9, "en_US"),
                    createDummyItem("Y", "Z", "en_US", 12, "en_US"),
                    createDummyItem("Y", "", "en_US", 15, "en_US"),
                    // Subtypes that has the same language of the system's.
                    createDummyItem("Y", "E", "en", 10, "en_US"),
                    createDummyItem("Y", "Z", "en", 13, "en_US"),
                    createDummyItem("Y", "", "en", 16, "en_US"),
                    // Subtypes that has different language than the system's.
                    createDummyItem("Y", "A", "hi_IN", 28, "en_US"),
                    createDummyItem("Y", "E", "ja", 11, "en_US"),
                    createDummyItem("Y", "Z", "ja", 14, "en_US"),
                    createDummyItem("Y", "", "ja", 17, "en_US"),

                    // Subtypes of IME "".
                    // Subtypes that has the same locale of the system's.
                    createDummyItem("", "E", "en_US", 18, "en_US"),
                    createDummyItem("", "Z", "en_US", 21, "en_US"),
                    createDummyItem("", "", "en_US", 24, "en_US"),
                    // Subtypes that has the same language of the system's.
                    createDummyItem("", "E", "en", 19, "en_US"),
                    createDummyItem("", "Z", "en", 22, "en_US"),
                    createDummyItem("", "", "en", 25, "en_US"),
                    // Subtypes that has different language than the system's.
                    createDummyItem("", "A", "hi_IN", 29, "en_US"),
                    createDummyItem("", "E", "ja", 20, "en_US"),
                    createDummyItem("", "Z", "ja", 23, "en_US"),
                    createDummyItem("", "", "ja", 26, "en_US"));

            // Ensure {@link java.lang.Comparable#compareTo} contracts are satisfied.
            for (int i = 0; i < items.size(); ++i) {
                final ImeSubtypeListItem item1 = items.get(i);
                // Ensures sgn(x.compareTo(y)) == -sgn(y.compareTo(x)).
                assertTrue(item1 + " has the same order of itself", item1.compareTo(item1) == 0);
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
            final ImeSubtypeListItem nonSystemLocale1 =
                    createDummyItem("X", "A", "ja_JP", 0, "en_US");
            final ImeSubtypeListItem nonSystemLocale2 =
                    createDummyItem("X", "A", "hi_IN", 1, "en_US");
            assertTrue(nonSystemLocale1.compareTo(nonSystemLocale2) == 0);
            assertTrue(nonSystemLocale2.compareTo(nonSystemLocale1) == 0);
            // But those aren't equal to each other.
            assertFalse(nonSystemLocale1.equals(nonSystemLocale2));
            assertFalse(nonSystemLocale2.equals(nonSystemLocale1));
        }
    }
}
