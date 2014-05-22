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

package android.os;

import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import com.android.internal.inputmethod.InputMethodSubtypeSwitchingController;
import com.android.internal.inputmethod.InputMethodSubtypeSwitchingController.ImeSubtypeListItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InputMethodSubtypeSwitchingControllerTest extends InstrumentationTestCase {
    private static final String DUMMY_PACKAGE_NAME = "dymmy package name";
    private static final String DUMMY_SETTING_ACTIVITY_NAME = "";
    private static final boolean DUMMY_IS_AUX_IME = false;
    private static final boolean DUMMY_FORCE_DEFAULT = false;
    private static final int DUMMY_IS_DEFAULT_RES_ID = 0;
    private static final String SYSTEM_LOCALE = "en_US";

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
        final List<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
        for (String subtypeLocale : subtypeLocales) {
            subtypes.add(createDummySubtype(subtypeLocale));
        }
        final InputMethodInfo imi = new InputMethodInfo(ri, DUMMY_IS_AUX_IME,
                DUMMY_SETTING_ACTIVITY_NAME, subtypes, DUMMY_IS_DEFAULT_RES_ID,
                DUMMY_FORCE_DEFAULT, supportsSwitchingToNextInputMethod);
        for (int i = 0; i < subtypes.size(); ++i) {
            final String subtypeLocale = subtypeLocales.get(i);
            items.add(new ImeSubtypeListItem(imeName, subtypeLocale, imi, i, subtypeLocale,
                    SYSTEM_LOCALE));
        }
    }

    private static List<ImeSubtypeListItem> createTestData() {
        final List<ImeSubtypeListItem> items = new ArrayList<ImeSubtypeListItem>();
        addDummyImeSubtypeListItems(items, "switchAwareLatinIme", "switchAwareLatinIme",
                Arrays.asList("en_US", "es_US", "fr"),
                true /* supportsSwitchingToNextInputMethod*/);
        addDummyImeSubtypeListItems(items, "nonSwitchAwareLatinIme", "nonSwitchAwareLatinIme",
                Arrays.asList("en_UK", "hi"),
                false /* supportsSwitchingToNextInputMethod*/);
        addDummyImeSubtypeListItems(items, "switchAwareJapaneseIme", "switchAwareJapaneseIme",
                Arrays.asList("ja_JP"),
                true /* supportsSwitchingToNextInputMethod*/);
        addDummyImeSubtypeListItems(items, "nonSwitchAwareJapaneseIme", "nonSwitchAwareJapaneseIme",
                Arrays.asList("ja_JP"),
                false /* supportsSwitchingToNextInputMethod*/);
        return items;
    }

    @SmallTest
    public void testGetNextInputMethodImplWithNotOnlyCurrentIme() throws Exception {
        final List<ImeSubtypeListItem> imList = createTestData();

        final boolean ONLY_CURRENT_IME = false;
        ImeSubtypeListItem currentIme;
        ImeSubtypeListItem nextIme;

        // "switchAwareLatinIme/en_US" -> "switchAwareLatinIme/es_US"
        currentIme = imList.get(0);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertEquals(imList.get(1), nextIme);
        // "switchAwareLatinIme/es_US" -> "switchAwareLatinIme/fr"
        currentIme = imList.get(1);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertEquals(imList.get(2), nextIme);
        // "switchAwareLatinIme/fr" -> "switchAwareJapaneseIme/ja_JP"
        currentIme = imList.get(2);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertEquals(imList.get(5), nextIme);
        // "switchAwareJapaneseIme/ja_JP" -> "switchAwareLatinIme/en_US"
        currentIme = imList.get(5);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertEquals(imList.get(0), nextIme);

        // "nonSwitchAwareLatinIme/en_UK" -> "nonSwitchAwareLatinIme/hi"
        currentIme = imList.get(3);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertEquals(imList.get(4), nextIme);
        // "nonSwitchAwareLatinIme/hi" -> "nonSwitchAwareJapaneseIme/ja_JP"
        currentIme = imList.get(4);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertEquals(imList.get(6), nextIme);
        // "nonSwitchAwareJapaneseIme/ja_JP" -> "nonSwitchAwareLatinIme/en_UK"
        currentIme = imList.get(6);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertEquals(imList.get(3), nextIme);
    }

    @SmallTest
    public void testGetNextInputMethodImplWithOnlyCurrentIme() throws Exception {
        final List<ImeSubtypeListItem> imList = createTestData();

        final boolean ONLY_CURRENT_IME = true;
        ImeSubtypeListItem currentIme;
        ImeSubtypeListItem nextIme;

        // "switchAwareLatinIme/en_US" -> "switchAwareLatinIme/es_US"
        currentIme = imList.get(0);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertEquals(imList.get(1), nextIme);
        // "switchAwareLatinIme/es_US" -> "switchAwareLatinIme/fr"
        currentIme = imList.get(1);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertEquals(imList.get(2), nextIme);
        // "switchAwareLatinIme/fr" -> "switchAwareLatinIme/en_US"
        currentIme = imList.get(2);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertEquals(imList.get(0), nextIme);

        // "nonSwitchAwareLatinIme/en_UK" -> "nonSwitchAwareLatinIme/hi"
        currentIme = imList.get(3);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertEquals(imList.get(4), nextIme);
        // "nonSwitchAwareLatinIme/hi" -> "switchAwareLatinIme/en_UK"
        currentIme = imList.get(4);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertEquals(imList.get(3), nextIme);

        // "switchAwareJapaneseIme/ja_JP" -> null
        currentIme = imList.get(5);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertNull(nextIme);

        // "nonSwitchAwareJapaneseIme/ja_JP" -> null
        currentIme = imList.get(6);
        nextIme = InputMethodSubtypeSwitchingController.getNextInputMethodLockedImpl(
                imList, ONLY_CURRENT_IME, currentIme.mImi, createDummySubtype(
                        currentIme.mSubtypeName.toString()));
        assertNull(nextIme);
    }
 }
