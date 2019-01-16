/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,d
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.text;

import static org.junit.Assert.assertArrayEquals;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@Presubmit
@SmallTest
public class AndroidCharacterTest {

    @Test
    public void testGetDirectionalities_nonSupplementaryCharacters() {
        int size = Character.MAX_VALUE + 1
                - (Character.MAX_SURROGATE - Character.MIN_SURROGATE + 1);
        char[] chars = new char[size];
        byte[] java_lang_results = new byte[size];
        int index = 0;
        for (int cp = 0; cp <= Character.MAX_VALUE; cp++) {
            if (cp < Character.MIN_SURROGATE || cp > Character.MAX_SURROGATE) {
                chars[index] = (char) cp;
                java_lang_results[index] = Character.getDirectionality(cp);
                index++;
            }
        }

        byte[] android_text_results = new byte[size];
        AndroidCharacter.getDirectionalities(chars, android_text_results, index);
        assertArrayEquals(java_lang_results, android_text_results);
    }

    @Test
    public void testGetDirectionalities_supplementaryCharacters() {
        int maxNumberOfChars = Character.MAX_CODE_POINT - Character.MIN_SUPPLEMENTARY_CODE_POINT
                + 1;
        int size = maxNumberOfChars * 2;
        char[] chars = new char[size];
        byte[] java_lang_results = new byte[size];
        int index = 0;
        for (int cp = Character.MIN_SUPPLEMENTARY_CODE_POINT; cp <= Character.MAX_CODE_POINT;
                cp++) {
            chars[index] = Character.highSurrogate(cp);
            chars[index + 1] = Character.lowSurrogate(cp);
            java_lang_results[index] = java_lang_results[index + 1] = Character
                    .getDirectionality(cp);
            index += 2;
        }

        byte[] android_text_results = new byte[size];
        AndroidCharacter.getDirectionalities(chars, android_text_results, index);
        assertArrayEquals(java_lang_results, android_text_results);
    }
}
