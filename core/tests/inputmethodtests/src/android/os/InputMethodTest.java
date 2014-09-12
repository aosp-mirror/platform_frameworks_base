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

    @SmallTest
    public void testDefaultEnabledImesWithDefaultVoiceIme() throws Exception {
        final Context context = getInstrumentation().getTargetContext();
        final ArrayList<InputMethodInfo> imis = new ArrayList<InputMethodInfo>();
        imis.add(createDefaultAutoDummyVoiceIme());
        imis.add(createNonDefaultAutoDummyVoiceIme0());
        imis.add(createNonDefaultAutoDummyVoiceIme1());
        imis.add(createNonDefaultDummyVoiceIme2());
        imis.add(createDefaultDummyLatinKeyboardIme());
        imis.add(createNonDefaultDummyJaJPKeyboardIme());
        imis.add(createNonDefaultDummyJaJPKeyboardImeWithoutSubtypes());

        final ArrayList<InputMethodInfo> enabledImisForSystemNotReady =
                callGetDefaultEnabledImesUnderWithLocale(context, !IS_SYSTEM_READY, imis,
                        LOCALE_EN_US);
        assertEquals(toSet("DummyDefaultAutoVoiceIme", "DummyDefaultEnKeyboardIme",
                "DummyNonDefaultAutoVoiceIme0", "DummyNonDefaultAutoVoiceIme1"),
                getPackageNames(enabledImisForSystemNotReady));

        final ArrayList<InputMethodInfo> enabledImisForSystemReady =
                callGetDefaultEnabledImesUnderWithLocale(context, IS_SYSTEM_READY, imis,
                        LOCALE_EN_US);
        assertEquals(toSet("DummyDefaultAutoVoiceIme", "DummyDefaultEnKeyboardIme"),
                getPackageNames(enabledImisForSystemReady));
    }

    @SmallTest
    public void testDefaultEnabledImesWithOutDefaultVoiceIme() throws Exception {
        final Context context = getInstrumentation().getTargetContext();
        final ArrayList<InputMethodInfo> imis = new ArrayList<InputMethodInfo>();
        imis.add(createNonDefaultAutoDummyVoiceIme0());
        imis.add(createNonDefaultAutoDummyVoiceIme1());
        imis.add(createNonDefaultDummyVoiceIme2());
        imis.add(createDefaultDummyLatinKeyboardIme());
        imis.add(createNonDefaultDummyJaJPKeyboardIme());
        imis.add(createNonDefaultDummyJaJPKeyboardImeWithoutSubtypes());

        final ArrayList<InputMethodInfo> enabledImisForSystemNotReady =
                callGetDefaultEnabledImesUnderWithLocale(context, !IS_SYSTEM_READY, imis,
                        LOCALE_EN_US);
        assertEquals(toSet("DummyNonDefaultAutoVoiceIme0", "DummyNonDefaultAutoVoiceIme1",
                "DummyDefaultEnKeyboardIme"), getPackageNames(enabledImisForSystemNotReady));

        final ArrayList<InputMethodInfo> enabledImisForSystemReady =
                callGetDefaultEnabledImesUnderWithLocale(context, IS_SYSTEM_READY, imis,
                        LOCALE_EN_US);
        assertEquals(toSet("DummyNonDefaultAutoVoiceIme0", "DummyNonDefaultAutoVoiceIme1",
                "DummyDefaultEnKeyboardIme"), getPackageNames(enabledImisForSystemReady));
    }

    @SmallTest
    public void testParcelable() throws Exception {
        final ArrayList<InputMethodInfo> originalList = new ArrayList<InputMethodInfo>();
        originalList.add(createNonDefaultAutoDummyVoiceIme0());
        originalList.add(createNonDefaultAutoDummyVoiceIme1());
        originalList.add(createNonDefaultDummyVoiceIme2());
        originalList.add(createDefaultDummyLatinKeyboardIme());
        originalList.add(createNonDefaultDummyJaJPKeyboardIme());
        originalList.add(createNonDefaultDummyJaJPKeyboardImeWithoutSubtypes());

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

    @SafeVarargs
    private static <T> HashSet<T> toSet(final T... xs) {
        return new HashSet<T>(Arrays.asList(xs));
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

    private static InputMethodInfo createDefaultAutoDummyVoiceIme() {
        final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
        subtypes.add(createDummyInputMethodSubtype("auto", "voice", IS_AUX, IS_AUTO,
                !IS_ASCII_CAPABLE));
        subtypes.add(createDummyInputMethodSubtype("en_US", "voice", IS_AUX, !IS_AUTO,
                !IS_ASCII_CAPABLE));
        return createDummyInputMethodInfo("DummyDefaultAutoVoiceIme", "dummy.voice0",
                "DummyVoice0", IS_AUX, IS_DEFAULT, subtypes);
    }

    private static InputMethodInfo createNonDefaultAutoDummyVoiceIme0() {
        final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
        subtypes.add(createDummyInputMethodSubtype("auto", "voice", IS_AUX, IS_AUTO,
                !IS_ASCII_CAPABLE));
        subtypes.add(createDummyInputMethodSubtype("en_US", "voice", IS_AUX, !IS_AUTO,
                !IS_ASCII_CAPABLE));
        return createDummyInputMethodInfo("DummyNonDefaultAutoVoiceIme0", "dummy.voice1",
                "DummyVoice1", IS_AUX, !IS_DEFAULT, subtypes);
    }

    private static InputMethodInfo createNonDefaultAutoDummyVoiceIme1() {
        final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
        subtypes.add(createDummyInputMethodSubtype("auto", "voice", IS_AUX, IS_AUTO,
                !IS_ASCII_CAPABLE));
        subtypes.add(createDummyInputMethodSubtype("en_US", "voice", IS_AUX, !IS_AUTO,
                !IS_ASCII_CAPABLE));
        return createDummyInputMethodInfo("DummyNonDefaultAutoVoiceIme1", "dummy.voice2",
                "DummyVoice2", IS_AUX, !IS_DEFAULT, subtypes);
    }

    private static InputMethodInfo createNonDefaultDummyVoiceIme2() {
        final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
        subtypes.add(createDummyInputMethodSubtype("en_US", "voice", IS_AUX, !IS_AUTO,
                !IS_ASCII_CAPABLE));
        return createDummyInputMethodInfo("DummyNonDefaultVoiceIme2", "dummy.voice3",
                "DummyVoice3", IS_AUX, !IS_DEFAULT, subtypes);
    }

    private static InputMethodInfo createDefaultDummyLatinKeyboardIme() {
        final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
        subtypes.add(createDummyInputMethodSubtype("en_US", "keyboard", !IS_AUX, !IS_AUTO,
                IS_ASCII_CAPABLE));
        subtypes.add(createDummyInputMethodSubtype("en_GB", "keyboard", !IS_AUX, !IS_AUTO,
                IS_ASCII_CAPABLE));
        subtypes.add(createDummyInputMethodSubtype("en_IN", "keyboard", !IS_AUX, !IS_AUTO,
                IS_ASCII_CAPABLE));
        subtypes.add(createDummyInputMethodSubtype("hi", "keyboard", !IS_AUX, !IS_AUTO,
                !IS_ASCII_CAPABLE));  // not AsciiCapable!
        subtypes.add(createDummyInputMethodSubtype("hi_ZZ", "keyboard", !IS_AUX, !IS_AUTO,
                IS_ASCII_CAPABLE));
        return createDummyInputMethodInfo("DummyDefaultEnKeyboardIme", "dummy.keyboard0",
                "DummyKeyboard0", !IS_AUX, IS_DEFAULT, subtypes);
    }

    private static InputMethodInfo createNonDefaultDummyJaJPKeyboardIme() {
        final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
        subtypes.add(createDummyInputMethodSubtype("ja_JP", "keyboard", !IS_AUX, !IS_AUTO,
                IS_ASCII_CAPABLE));
        return createDummyInputMethodInfo("DummyNonDefaultJaJPKeyboardIme", "dummy.keyboard1",
                "DummyKeyboard1", !IS_AUX, !IS_DEFAULT, subtypes);
    }

    // Although IMEs that have no subtype are considered to be deprecated, the Android framework
    // must still be able to handle such IMEs as well as IMEs that have at least one subtype.
    private static InputMethodInfo createNonDefaultDummyJaJPKeyboardImeWithoutSubtypes() {
        final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
        return createDummyInputMethodInfo("DummyNonDefaultJaJPKeyboardImeWithoutSubtypes",
                "dummy.keyboard2", "DummyKeyboard2", !IS_AUX, !IS_DEFAULT, NO_SUBTYPE);
    }
}
