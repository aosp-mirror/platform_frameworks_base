/*
 * Copyright 2021 The Android Open Source Project
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

package android.app.appsearch.util;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class IndentingStringBuilderTest {
    @Test
    public void testAppendIndentedStrings() {
        IndentingStringBuilder stringBuilder = new IndentingStringBuilder();
        stringBuilder
                .increaseIndentLevel()
                .append("\nIndentLevel1\nIndentLevel1\n")
                .decreaseIndentLevel()
                .append("IndentLevel0,\n");

        String str = stringBuilder.toString();
        String expectedString = "\n  IndentLevel1\n  IndentLevel1\nIndentLevel0,\n";

        assertThat(str).isEqualTo(expectedString);
    }

    @Test
    public void testDecreaseIndentLevel_throwsException() {
        IndentingStringBuilder stringBuilder = new IndentingStringBuilder();

        Exception e =
                assertThrows(
                        IllegalStateException.class, () -> stringBuilder.decreaseIndentLevel());
        assertThat(e).hasMessageThat().contains("Cannot set indent level below 0.");
    }

    @Test
    public void testAppendIndentedObjects() {
        IndentingStringBuilder stringBuilder = new IndentingStringBuilder();
        Object stringProperty = "String";
        Object longProperty = 1L;
        Object booleanProperty = true;

        stringBuilder
                .append(stringProperty)
                .append("\n")
                .increaseIndentLevel()
                .append(longProperty)
                .append("\n")
                .decreaseIndentLevel()
                .append(booleanProperty);

        String str = stringBuilder.toString();
        String expectedString = "String\n  1\ntrue";

        assertThat(str).isEqualTo(expectedString);
    }

    @Test
    public void testAppendIndentedStrings_doesNotIndentLineBreak() {
        IndentingStringBuilder stringBuilder = new IndentingStringBuilder();

        stringBuilder
                .append("\n")
                .increaseIndentLevel()
                .append("\n\n")
                .decreaseIndentLevel()
                .append("\n");

        String str = stringBuilder.toString();
        String expectedString = "\n\n\n\n";

        assertThat(str).isEqualTo(expectedString);
    }
}
