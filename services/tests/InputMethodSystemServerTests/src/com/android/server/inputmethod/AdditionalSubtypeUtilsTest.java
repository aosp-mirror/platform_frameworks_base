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
import static org.junit.Assert.assertNotNull;

import android.icu.util.ULocale;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import androidx.test.InstrumentationRegistry;

import org.junit.Test;

import java.io.File;
import java.util.List;

public final class AdditionalSubtypeUtilsTest {

    @Test
    public void testSaveAndLoad() throws Exception {
        // Prepares the data to be saved.
        InputMethodSubtype subtype1 = new InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeNameOverride("Subtype1")
                .setLanguageTag("en-US")
                .build();
        InputMethodSubtype subtype2 = new InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeNameOverride("Subtype2")
                .setLanguageTag("zh-CN")
                .setPhysicalKeyboardHint(new ULocale("en_US"), "qwerty")
                .build();
        String fakeImeId = "fakeImeId";
        ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
        methodMap.put(fakeImeId, new InputMethodInfo("", "", "", ""));
        ArrayMap<String, List<InputMethodSubtype>> allSubtypes = new ArrayMap<>();
        allSubtypes.put(fakeImeId, List.of(subtype1, subtype2));

        // Save & load.
        AtomicFile atomicFile = new AtomicFile(
                new File(InstrumentationRegistry.getContext().getCacheDir(), "subtypes.xml"));
        AdditionalSubtypeUtils.saveToFile(AdditionalSubtypeMap.of(allSubtypes),
                InputMethodMap.of(methodMap), atomicFile);
        AdditionalSubtypeMap loadedSubtypes = AdditionalSubtypeUtils.loadFromFile(atomicFile);

        // Verifies the loaded data.
        assertEquals(1, loadedSubtypes.size());
        List<InputMethodSubtype> subtypes = loadedSubtypes.get(fakeImeId);
        assertNotNull(subtypes);
        assertEquals(2, subtypes.size());

        verifySubtype(subtypes.get(0), subtype1);
        verifySubtype(subtypes.get(1), subtype2);
    }

    private void verifySubtype(InputMethodSubtype subtype, InputMethodSubtype expectedSubtype) {
        assertEquals(expectedSubtype.getLanguageTag(), subtype.getLanguageTag());
        assertEquals(expectedSubtype.getPhysicalKeyboardHintLanguageTag(),
                subtype.getPhysicalKeyboardHintLanguageTag());
        assertEquals(expectedSubtype.getPhysicalKeyboardHintLayoutType(),
                subtype.getPhysicalKeyboardHintLayoutType());
    }
}
