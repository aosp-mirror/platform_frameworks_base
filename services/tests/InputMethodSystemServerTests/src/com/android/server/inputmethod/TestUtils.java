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

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Objects;

public final class TestUtils {
    /**
     * {@link ComponentName} for fake {@link InputMethodInfo}.
     */
    @NonNull
    public static final ComponentName TEST_IME_ID1 = Objects.requireNonNull(
            ComponentName.unflattenFromString("com.android.test.testime1/.InputMethod"));

    /**
     * {@link ComponentName} for fake {@link InputMethodInfo}.
     */
    @NonNull
    public static final ComponentName TEST_IME_ID2 = Objects.requireNonNull(
            ComponentName.unflattenFromString("com.android.test.testime2/.InputMethod"));

    /**
     * {@link ComponentName} for fake {@link InputMethodInfo}.
     */
    @NonNull
    public static final ComponentName TEST_IME_ID3 = Objects.requireNonNull(
            ComponentName.unflattenFromString("com.android.test.testime3/.InputMethod"));

    /**
     * Creates a list of fake {@link InputMethodSubtype} for unit testing for the given number.
     *
     * @param count The number of fake {@link InputMethodSubtype} objects
     * @return The list of fake {@link InputMethodSubtype} objects
     */
    @NonNull
    public static ArrayList<InputMethodSubtype> createFakeSubtypes(int count) {
        final ArrayList<InputMethodSubtype> subtypes = new ArrayList<>(count);
        for (int i = 0; i < count; ++i) {
            subtypes.add(
                    new InputMethodSubtype.InputMethodSubtypeBuilder()
                            .setSubtypeId(i + 0x100)
                            .setLanguageTag("en-US")
                            .setSubtypeNameOverride("TestSubtype" + i)
                            .build());
        }
        return subtypes;
    }

    /**
     * Creates a fake {@link InputMethodInfo} for unit testing.
     *
     * @param componentName {@link ComponentName} of the fake {@link InputMethodInfo}
     * @param subtypes A list of (fake) {@link InputMethodSubtype}
     * @return a fake {@link InputMethodInfo} object
     */
    @NonNull
    public static InputMethodInfo createFakeInputMethodInfo(
            @NonNull ComponentName componentName, @NonNull ArrayList<InputMethodSubtype> subtypes) {
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = componentName.getPackageName();
        ai.enabled = true;

        final ServiceInfo si = new ServiceInfo();
        si.applicationInfo = ai;
        si.enabled = true;
        si.packageName = componentName.getPackageName();
        si.name = componentName.getClassName();
        si.exported = true;
        si.nonLocalizedLabel = "Fake Label";

        final ResolveInfo ri = new ResolveInfo();
        ri.serviceInfo = si;

        return new InputMethodInfo(ri, false /* isAuxIme */, null /* settingsActivity */,
                subtypes, 0 /* isDefaultResId */, false /* forceDefault */);
    }
}
