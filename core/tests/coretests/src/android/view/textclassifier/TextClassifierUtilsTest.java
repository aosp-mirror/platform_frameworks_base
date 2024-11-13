/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view.textclassifier;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassifierUtilsTest {

    @Test
    public void testGetSubString() {
        final String text = "Yakuza call themselves 任侠団体";
        int start;
        int end;
        int minimumLength;

        // End index at end of text.
        start = text.indexOf("任侠団体");
        end = text.length();
        minimumLength = 20;
        assertThat(TextClassifier.Utils.getSubString(text, start, end, minimumLength))
                .isEqualTo("call themselves 任侠団体");

        // Start index at beginning of text.
        start = 0;
        end = "Yakuza".length();
        minimumLength = 15;
        assertThat(TextClassifier.Utils.getSubString(text, start, end, minimumLength))
                .isEqualTo("Yakuza call themselves");

        // Text in the middle
        start = text.indexOf("all");
        end = start + 1;
        minimumLength = 10;
        assertThat(TextClassifier.Utils.getSubString(text, start, end, minimumLength))
                .isEqualTo("Yakuza call themselves");

        // Selection >= minimumLength.
        start = text.indexOf("themselves");
        end = start + "themselves".length();
        minimumLength = end - start;
        assertThat(TextClassifier.Utils.getSubString(text, start, end, minimumLength))
                .isEqualTo("themselves");

        // text.length < minimumLength.
        minimumLength = text.length() + 1;
        assertThat(TextClassifier.Utils.getSubString(text, start, end, minimumLength))
                .isEqualTo(text);
    }

    @Test
    public void testGetSubString_invalidParams() {
        final String text = "The Yoruba regard Olodumare as the principal agent of creation";
        final int length = text.length();
        final int minimumLength = 10;

        // Null text
        assertThrows(() -> TextClassifier.Utils.getSubString(null, 0, 1, minimumLength));
        // start > end
        assertThrows(() -> TextClassifier.Utils.getSubString(text, 6, 5, minimumLength));
        // start < 0
        assertThrows(() -> TextClassifier.Utils.getSubString(text, -1, 5, minimumLength));
        // end > text.length
        assertThrows(() -> TextClassifier.Utils.getSubString(text, 6, length + 1, minimumLength));
    }
}
