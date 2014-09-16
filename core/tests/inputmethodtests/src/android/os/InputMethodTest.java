/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import com.android.internal.inputmethod.InputMethodUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class InputMethodTest extends InstrumentationTestCase {
    private static final boolean IS_AUX = true;
    private static final boolean IS_DEFAULT = true;
    private static final boolean IS_AUTO = true;
    private static final boolean IS_ASCII_CAPABLE = true;
    private static final boolean IS_SYSTEM_READY = true;
    private static final ArrayList<InputMethodSubtype> NO_SUBTYPE = null;
    private static final Locale LOCALE_EN_US = new Locale("en", "US");
    private static final Locale LOCALE_EN_GB = new Locale("en", "GB");
    private static final Locale LOCALE_EN_IN = new Locale("en", "IN");
    private static final Locale LOCALE_HI = new Locale("hi");
    private static final Locale LOCALE_JA_JP = new Locale("ja", "JP");
    private static final String SUBTYPE_MODE_KEYBOARD = "keyboard";
    private static final String SUBTYPE_MODE_VOICE = "voice";

    @SmallTest
    public void testVoiceImes() throws Exception {
        // locale: en_US
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_EN_US, !IS_SYSTEM_READY,
                "DummyDefaultEnKeyboardIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_EN_US, !IS_SYSTEM_READY,
                "DummyDefaultEnKeyboardIme");
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_EN_US, IS_SYSTEM_READY,
                "DummyDefaultAutoVoiceIme", "DummyDefaultEnKeyboardIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_EN_US, IS_SYSTEM_READY,
                "DummyNonDefaultAutoVoiceIme0", "DummyNonDefaultAutoVoiceIme1",
                "DummyDefaultEnKeyboardIme");

        // locale: en_GB
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_EN_GB, !IS_SYSTEM_READY,
                "DummyDefaultEnKeyboardIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_EN_GB, !IS_SYSTEM_READY,
                "DummyDefaultEnKeyboardIme");
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_EN_GB, IS_SYSTEM_READY,
                "DummyDefaultEnKeyboardIme", "DummyDefaultAutoVoiceIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_EN_GB, IS_SYSTEM_READY,
                "DummyNonDefaultAutoVoiceIme0", "DummyNonDefaultAutoVoiceIme1",
                "DummyDefaultEnKeyboardIme");

        // locale: ja_JP
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_JA_JP, !IS_SYSTEM_READY,
                "DummyDefaultEnKeyboardIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_JA_JP, !IS_SYSTEM_READY,
                "DummyDefaultEnKeyboardIme");
        assertDefaultEnabledImes(getImesWithDefaultVoiceIme(), LOCALE_JA_JP, IS_SYSTEM_READY,
                "DummyDefaultEnKeyboardIme", "DummyDefaultAutoVoiceIme");
        assertDefaultEnabledImes(getImesWithoutDefaultVoiceIme(), LOCALE_JA_JP, IS_SYSTEM_READY,
                "DummyNonDefaultAutoVoiceIme0", "DummyNonDefaultAutoVoiceIme1",
                "DummyDefaultEnKeyboardIme");
    }

    @SmallTest
    public void testKeyboardImes() throws Exception {
        // locale: en_US
        assertDefaultEnabledImes(getSamplePreinstalledImes(), LOCALE_EN_US, !IS_SYSTEM_READY,
                "com.android.apps.inputmethod.latin");
        assertDefaultEnabledImes(getSamplePreinstalledImes(), LOCALE_EN_US, IS_SYSTEM_READY,
                "com.android.apps.inputmethod.voice", "com.android.apps.inputmethod.latin");

        // locale: en_GB
        assertDefaultEnabledImes(getSamplePreinstalledImes(), LOCALE_EN_GB, !IS_SYSTEM_READY,
                "com.android.apps.inputmethod.latin");
        assertDefaultEnabledImes(getSamplePreinstalledImes(), LOCALE_EN_GB, IS_SYSTEM_READY,
                "com.android.apps.inputmethod.voice", "com.android.apps.inputmethod.latin");

        // locale: en_IN
        assertDefaultEnabledImes(getSamplePreinstalledImes(), LOCALE_EN_IN, !IS_SYSTEM_READY,
                "com.android.apps.inputmethod.latin");
        assertDefaultEnabledImes(getSamplePreinstalledImes(), LOCALE_EN_IN, IS_SYSTEM_READY,
                "com.android.apps.inputmethod.voice", "com.android.apps.inputmethod.latin",
                "com.android.apps.inputmethod.hindi");

        // locale: hi
        assertDefaultEnabledImes(getSamplePreinstalledImes(), LOCALE_HI, !IS_SYSTEM_READY,
                "com.android.apps.inputmethod.latin");
        assertDefaultEnabledImes(getSamplePreinstalledImes(), LOCALE_HI, IS_SYSTEM_READY,
                "com.android.apps.inputmethod.voice", "com.android.apps.inputmethod.latin",
                "com.android.apps.inputmethod.hindi");

        // locale: ja_JP
        assertDefaultEnabledImes(getSamplePreinstalledImes(), LOCALE_JA_JP, !IS_SYSTEM_READY,
                "com.android.apps.inputmethod.latin");
        assertDefaultEnabledImes(getSamplePreinstalledImes(), LOCALE_JA_JP, IS_SYSTEM_READY,
                "com.android.apps.inputmethod.voice", "com.android.apps.inputmethod.latin",
                "com.android.apps.inputmethod.japanese");
    }

    @SmallTest
    public void testParcelable() throws Exception {
        final ArrayList<InputMethodInfo> originalList = getSamplePreinstalledImes();
        final List<InputMethodInfo> clonedList = cloneViaParcel(originalList);
        assertNotNull(clonedList);
        final List<InputMethodInfo> clonedClonedList = cloneViaParcel(clonedList);
        assertNotNull(clonedClonedList);
        assertEquals(originalList, clonedList);
        assertEquals(clonedList, clonedClonedList);
        assertEquals(originalList.size(), clonedList.size());
        assertEquals(clonedList.size(), clonedClonedList.size());
        for (int imeIndex = 0; imeIndex < originalList.size(); ++imeIndex) {
            verifyEquality(originalList.get(imeIndex), clonedList.get(imeIndex));
            verifyEquality(clonedList.get(imeIndex), clonedClonedList.get(imeIndex));
        }
    }

    private void assertDefaultEnabledImes(final ArrayList<InputMethodInfo> preinstalledImes,
            final Locale systemLocale, final boolean isSystemReady, String... imeNames) {
        final Context context = getInstrumentation().getTargetContext();
        assertEquals(new HashSet<String>(Arrays.asList(imeNames)),
                getPackageNames(callGetDefaultEnabledImesUnderWithLocale(context,
                        isSystemReady, preinstalledImes, systemLocale)));
    }

    private static List<InputMethodInfo> cloneViaParcel(final List<InputMethodInfo> list) {
        Parcel p = null;
        try {
            p = Parcel.obtain();
            p.writeTypedList(list);
            p.setDataPosition(0);
            return p.createTypedArrayList(InputMethodInfo.CREATOR);
        } finally {
            if (p != null) {
                p.recycle();
            }
        }
    }

    private static ArrayList<InputMethodInfo> callGetDefaultEnabledImesUnderWithLocale(
            final Context context, final boolean isSystemReady,
            final ArrayList<InputMethodInfo> imis, final Locale locale) {
        final Locale initialLocale = context.getResources().getConfiguration().locale;
        try {
            context.getResources().getConfiguration().setLocale(locale);
            return InputMethodUtils.getDefaultEnabledImes(context, isSystemReady, imis);
        } finally {
            context.getResources().getConfiguration().setLocale(initialLocale);
        }
    }

    private HashSet<String> getPackageNames(final ArrayList<InputMethodInfo> imis) {
        final HashSet<String> packageNames = new HashSet<>();
        for (final InputMethodInfo imi : imis) {
            final String actualPackageName = imi.getPackageName();
            packageNames.add(actualPackageName);
        }
        return packageNames;
    }

    private static void verifyEquality(InputMethodInfo expected, InputMethodInfo actual) {
        assertEquals(expected, actual);
        assertEquals(expected.getSubtypeCount(), actual.getSubtypeCount());
        for (int subtypeIndex = 0; subtypeIndex < expected.getSubtypeCount(); ++subtypeIndex) {
            final InputMethodSubtype expectedSubtype = expected.getSubtypeAt(subtypeIndex);
            final InputMethodSubtype actualSubtype = actual.getSubtypeAt(subtypeIndex);
            assertEquals(expectedSubtype, actualSubtype);
            assertEquals(expectedSubtype.hashCode(), actualSubtype.hashCode());
        }
    }

    private static InputMethodInfo createDummyInputMethodInfo(String packageName, String name,
            CharSequence label, boolean isAuxIme, boolean isDefault,
            List<InputMethodSubtype> subtypes) {
        final ResolveInfo ri = new ResolveInfo();
        final ServiceInfo si = new ServiceInfo();
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.enabled = true;
        ai.flags |= ApplicationInfo.FLAG_SYSTEM;
        si.applicationInfo = ai;
        si.enabled = true;
        si.packageName = packageName;
        si.name = name;
        si.exported = true;
        si.nonLocalizedLabel = label;
        ri.serviceInfo = si;
        return new InputMethodInfo(ri, isAuxIme, "", subtypes, 1, isDefault);
    }

    private static InputMethodSubtype createDummyInputMethodSubtype(String locale, String mode,
            boolean isAuxiliary, boolean overridesImplicitlyEnabledSubtype,
            boolean isAsciiCapable) {
        return new InputMethodSubtypeBuilder()
                .setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setSubtypeLocale(locale)
                .setSubtypeMode(mode)
                .setSubtypeExtraValue("")
                .setIsAuxiliary(isAuxiliary)
                .setOverridesImplicitlyEnabledSubtype(overridesImplicitlyEnabledSubtype)
                .setIsAsciiCapable(isAsciiCapable)
                .build();
    }

    private static ArrayList<InputMethodInfo> getImesWithDefaultVoiceIme() {
        ArrayList<InputMethodInfo> preinstalledImes = new ArrayList<>();
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("auto", SUBTYPE_MODE_VOICE, IS_AUX, IS_AUTO,
                    !IS_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("en_US", SUBTYPE_MODE_VOICE, IS_AUX,
                    !IS_AUTO, !IS_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("DummyDefaultAutoVoiceIme",
                    "dummy.voice0", "DummyVoice0", IS_AUX, IS_DEFAULT, subtypes));
        }
        preinstalledImes.addAll(getImesWithoutDefaultVoiceIme());
        return preinstalledImes;
    }

    private static ArrayList<InputMethodInfo> getImesWithoutDefaultVoiceIme() {
        ArrayList<InputMethodInfo> preinstalledImes = new ArrayList<>();
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("auto", SUBTYPE_MODE_VOICE, IS_AUX, IS_AUTO,
                    !IS_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("en_US", SUBTYPE_MODE_VOICE, IS_AUX,
                    !IS_AUTO, !IS_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("DummyNonDefaultAutoVoiceIme0",
                    "dummy.voice1", "DummyVoice1", IS_AUX, !IS_DEFAULT, subtypes));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("auto", SUBTYPE_MODE_VOICE, IS_AUX, IS_AUTO,
                    !IS_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("en_US", SUBTYPE_MODE_VOICE, IS_AUX,
                    !IS_AUTO, !IS_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("DummyNonDefaultAutoVoiceIme1",
                    "dummy.voice2", "DummyVoice2", IS_AUX, !IS_DEFAULT, subtypes));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("en_US", SUBTYPE_MODE_VOICE, IS_AUX,
                    !IS_AUTO, !IS_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("DummyNonDefaultVoiceIme2",
                    "dummy.voice3", "DummyVoice3", IS_AUX, !IS_DEFAULT, subtypes));
        }
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("en_US", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_AUTO, IS_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("DummyDefaultEnKeyboardIme",
                    "dummy.keyboard0", "DummyKeyboard0", !IS_AUX, IS_DEFAULT, subtypes));
        }
        return preinstalledImes;
    }

    private static ArrayList<InputMethodInfo> getSamplePreinstalledImes() {
        ArrayList<InputMethodInfo> preinstalledImes = new ArrayList<>();

        // a dummy Voice IME
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("", SUBTYPE_MODE_VOICE, IS_AUX,
                    IS_AUTO, !IS_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("com.android.apps.inputmethod.voice",
                    "com.android.inputmethod.voice", "DummyVoiceIme", IS_AUX, IS_DEFAULT,
                    subtypes));
        }

        // a dummy Hindi IME
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            // TODO: This subtype should be marked as IS_ASCII_CAPABLE
            subtypes.add(createDummyInputMethodSubtype("en_IN", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_AUTO, !IS_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("hi", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_AUTO, !IS_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("com.android.apps.inputmethod.hindi",
                    "com.android.inputmethod.hindi", "DummyHindiIme", !IS_AUX, IS_DEFAULT,
                    subtypes));
        }

        // a dummy Pinyin IME
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("zh_CN", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_AUTO, !IS_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("ccom.android.apps.inputmethod.pinyin",
                    "com.android.apps.inputmethod.pinyin", "DummyPinyinIme", !IS_AUX, IS_DEFAULT,
                    subtypes));
        }

        // a dummy Korian IME
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("ko", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_AUTO, !IS_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("com.android.apps.inputmethod.korean",
                    "com.android.apps.inputmethod.korean", "DummyKorianIme", !IS_AUX, IS_DEFAULT,
                    subtypes));
        }

        // a dummy Latin IME
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("en_US", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_AUTO, IS_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("en_GB", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_AUTO, IS_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("en_IN", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_AUTO, IS_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("hi", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_AUTO, IS_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("com.android.apps.inputmethod.latin",
                    "com.android.apps.inputmethod.latin", "DummyLatinIme", !IS_AUX, IS_DEFAULT,
                    subtypes));
        }

        // a dummy Japanese IME
        {
            final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
            subtypes.add(createDummyInputMethodSubtype("ja", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_AUTO, !IS_ASCII_CAPABLE));
            subtypes.add(createDummyInputMethodSubtype("emoji", SUBTYPE_MODE_KEYBOARD, !IS_AUX,
                    !IS_AUTO, !IS_ASCII_CAPABLE));
            preinstalledImes.add(createDummyInputMethodInfo("com.android.apps.inputmethod.japanese",
                    "com.android.apps.inputmethod.japanese", "DummyJapaneseIme", !IS_AUX,
                    IS_DEFAULT, subtypes));
        }

        return preinstalledImes;
    }
}
