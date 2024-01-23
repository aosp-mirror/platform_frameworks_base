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

import android.text.TextUtils;
import android.util.IntArray;

import androidx.annotation.NonNull;

import org.junit.Test;

public final class InputMethodSettingsTest {
    private static void verifyUpdateEnabledImeString(@NonNull String expectedEnabledImeStr,
            @NonNull String initialEnabledImeStr, @NonNull String imeId,
            @NonNull String enabledSubtypeHashCodesStr) {
        assertEquals(expectedEnabledImeStr,
                InputMethodSettings.updateEnabledImeString(initialEnabledImeStr,
                        imeId, createSubtypeHashCodeArrayFromStr(enabledSubtypeHashCodesStr)));
    }

    private static IntArray createSubtypeHashCodeArrayFromStr(String subtypeHashCodesStr) {
        final IntArray subtypes = new IntArray();
        final TextUtils.SimpleStringSplitter imeSubtypeSplitter =
                new TextUtils.SimpleStringSplitter(';');
        if (TextUtils.isEmpty(subtypeHashCodesStr)) {
            return subtypes;
        }
        imeSubtypeSplitter.setString(subtypeHashCodesStr);
        while (imeSubtypeSplitter.hasNext()) {
            subtypes.add(Integer.parseInt(imeSubtypeSplitter.next()));
        }
        return subtypes;
    }

    @Test
    public void updateEnabledImeStringTest() {
        // No change cases
        verifyUpdateEnabledImeString(
                "com.android/.ime1",
                "com.android/.ime1", "com.android/.ime1", "");
        verifyUpdateEnabledImeString(
                "com.android/.ime1",
                "com.android/.ime1", "com.android/.ime2", "");

        // To enable subtypes
        verifyUpdateEnabledImeString(
                "com.android/.ime1",
                "com.android/.ime1", "com.android/.ime2", "");
        verifyUpdateEnabledImeString(
                "com.android/.ime1;1",
                "com.android/.ime1", "com.android/.ime1", "1");

        verifyUpdateEnabledImeString(
                "com.android/.ime1;1;2;3",
                "com.android/.ime1", "com.android/.ime1", "1;2;3");

        verifyUpdateEnabledImeString(
                "com.android/.ime1;1;2;3:com.android/.ime2",
                "com.android/.ime1:com.android/.ime2", "com.android/.ime1", "1;2;3");
        verifyUpdateEnabledImeString(
                "com.android/.ime0:com.android/.ime1;1;2;3",
                "com.android/.ime0:com.android/.ime1", "com.android/.ime1", "1;2;3");
        verifyUpdateEnabledImeString(
                "com.android/.ime0:com.android/.ime1;1;2;3:com.android/.ime2",
                "com.android/.ime0:com.android/.ime1:com.android/.ime2", "com.android/.ime1",
                "1;2;3");

        // To reset enabled subtypes
        verifyUpdateEnabledImeString(
                "com.android/.ime1",
                "com.android/.ime1;1", "com.android/.ime1", "");
        verifyUpdateEnabledImeString(
                "com.android/.ime1",
                "com.android/.ime1;1;2;3", "com.android/.ime1", "");
        verifyUpdateEnabledImeString(
                "com.android/.ime1:com.android/.ime2",
                "com.android/.ime1;1;2;3:com.android/.ime2", "com.android/.ime1", "");

        verifyUpdateEnabledImeString(
                "com.android/.ime0:com.android/.ime1",
                "com.android/.ime0:com.android/.ime1;1;2;3", "com.android/.ime1", "");
        verifyUpdateEnabledImeString(
                "com.android/.ime0:com.android/.ime1:com.android/.ime2",
                "com.android/.ime0:com.android/.ime1;1;2;3:com.android/.ime2", "com.android/.ime1",
                "");
    }
}
