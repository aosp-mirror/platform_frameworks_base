/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.inputmethod;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.UserHandle;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.Collator;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InputMethodPreferenceTest {

    private static final Collator COLLATOR = Collator.getInstance(Locale.US);

    @Test
    public void testComparableOrdering() throws Exception {
        final List<InputMethodPreference> itemsInAscendingOrder = Arrays.asList(
                createPreference("", true, "no_title-system"),
                createPreference("E", true, "E-system"),
                createPreference("Z", true, "Z-system"),
                createPreference("", false, "no_title-non_system"),
                createPreference("E", false, "E-non_system"),
                createPreference("Z", false, "Z-non_system")
        );
        ComparableUtils.assertAscendingOrdering(
                itemsInAscendingOrder,
                (x, y) -> x.compareTo(y, COLLATOR),
                x -> x.getInputMethodInfo().getServiceName());
    }

    @Test
    public void testComparableEquality() {
        final List<InputMethodPreference> itemsInSameOrder1 = Arrays.asList(
                createPreference("", true, "no_title-system-1"),
                createPreference("", true, "no_title-system-2")
        );
        ComparableUtils.assertSameOrdering(
                itemsInSameOrder1,
                (x, y) -> x.compareTo(y, COLLATOR),
                x -> x.getInputMethodInfo().getServiceName());

        final List<InputMethodPreference> itemsInSameOrder2 = Arrays.asList(
                createPreference("A", false, "A-non_system-1"),
                createPreference("A", false, "A-non_system-2")
        );
        ComparableUtils.assertSameOrdering(
                itemsInSameOrder2,
                (x, y) -> x.compareTo(y, COLLATOR),
                x -> x.getInputMethodInfo().getServiceName());
    }

    @Test
    public void testComparableContracts() {
        final List<InputMethodPreference> items = Arrays.asList(
                // itemsInAscendingOrder.
                createPreference("", true, "no_title-system"),
                createPreference("E", true, "E-system"),
                createPreference("Z", true, "Z-system"),
                createPreference("", false, "no_title-non_system"),
                createPreference("E", false, "E-non_system"),
                createPreference("Z", false, "Z-non_system"),
                // itemsInSameOrder1.
                createPreference("", true, "no_title-system-1"),
                createPreference("", true, "no_title-system-2"),
                // itemsInSameOrder2.
                createPreference("A", false, "A-non_system-1"),
                createPreference("A", false, "A-non_system-2")
        );

        ComparableUtils.assertComparableContracts(
                items,
                (x, y) -> x.compareTo(y, COLLATOR),
                x -> x.getInputMethodInfo().getServiceName());
    }

    private static InputMethodPreference createPreference(
            final CharSequence title,
            final boolean systemIme,
            final String name) {
        return new InputMethodPreference(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                createInputMethodInfo(systemIme, name),
                title,
                true /* isAllowedByOrganization */,
                p -> {} /* onSavePreferenceListener */,
                UserHandle.myUserId());
    }

    private static InputMethodInfo createInputMethodInfo(
            final boolean systemIme, final String name) {
        final Context targetContext =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Locale systemLocale = targetContext
                .getResources()
                .getConfiguration()
                .getLocales()
                .get(0);
        final InputMethodSubtype systemLocaleSubtype =
                new InputMethodSubtype.InputMethodSubtypeBuilder()
                        .setIsAsciiCapable(true)
                        .setSubtypeMode("keyboard")
                        .setSubtypeLocale(systemLocale.getLanguage())
                        .build();

        final ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.packageName = "com.android.ime";
        resolveInfo.serviceInfo.name = name;
        resolveInfo.serviceInfo.applicationInfo = new ApplicationInfo();
        resolveInfo.serviceInfo.applicationInfo.enabled = true;
        if (systemIme) {
            resolveInfo.serviceInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        } else {
            resolveInfo.serviceInfo.applicationInfo.flags &= ~ApplicationInfo.FLAG_SYSTEM;
        }
        return new InputMethodInfo(
                resolveInfo,
                false /* isAuxIme */,
                "SettingsActivity",
                Collections.singletonList(systemLocaleSubtype),
                0 /* isDefaultResId */,
                true /* forceDefault */);
    }
}
