/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view.inputmethod;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.SuggestionSpan;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SurroundingTextTest {

    @Test
    public void testSurroundingTextBasicCreation() {
        SurroundingText surroundingText1 = new SurroundingText("test", 0, 0, 0);
        assertThat(surroundingText1.getText().toString()).isEqualTo("test");
        assertThat(surroundingText1.getSelectionStart()).isEqualTo(0);
        assertThat(surroundingText1.getSelectionEnd()).isEqualTo(0);
        assertThat(surroundingText1.getOffset()).isEqualTo(0);

        SurroundingText surroundingText2 = new SurroundingText("", -1, -1, -1);
        assertThat(surroundingText2.getText().toString()).isEmpty();
        assertThat(surroundingText2.getSelectionStart()).isEqualTo(-1);
        assertThat(surroundingText2.getSelectionEnd()).isEqualTo(-1);
        assertThat(surroundingText2.getOffset()).isEqualTo(-1);

        SurroundingText surroundingText3 = new SurroundingText("hello", 0, 5, 0);
        assertThat(surroundingText3.getText().toString()).isEqualTo("hello");
        assertThat(surroundingText3.getSelectionStart()).isEqualTo(0);
        assertThat(surroundingText3.getSelectionEnd()).isEqualTo(5);
        assertThat(surroundingText3.getOffset()).isEqualTo(0);
    }

    @Test
    public void testSurroundingTextWriteToParcel() {
        SurroundingText surroundingText = new SurroundingText("text", 0, 1, 2);
        Parcel parcel = Parcel.obtain();
        surroundingText.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);
        SurroundingText surroundingTextFromParcel =
                SurroundingText.CREATOR.createFromParcel(parcel);
        assertThat(surroundingText.getText().toString()).isEqualTo("text");
        assertThat(surroundingText.getSelectionStart()).isEqualTo(0);
        assertThat(surroundingText.getSelectionEnd()).isEqualTo(1);
        assertThat(surroundingText.getOffset()).isEqualTo(2);
        assertThat(surroundingTextFromParcel.getText().toString()).isEqualTo("text");
        assertThat(surroundingTextFromParcel.getSelectionStart()).isEqualTo(0);
        assertThat(surroundingTextFromParcel.getSelectionEnd()).isEqualTo(1);
        assertThat(surroundingTextFromParcel.getOffset()).isEqualTo(2);
    }

    @Test
    public void testIsEqualComparesText_isNotEqualTo() {
        final SurroundingText text = new SurroundingText("hello", 0, 1, 0);

        verifySurroundingTextNotEquals(text, new SurroundingText("there", 0, 1, 0));
        verifySurroundingTextNotEquals(text, new SurroundingText("hello", 0, 1, -1));
        verifySurroundingTextNotEquals(text, new SurroundingText("hello", 0, 0, 0));
        verifySurroundingTextNotEquals(text, new SurroundingText("hello", 1, 1, 0));

        SpannableString spannableString = new SpannableString("hello");
        spannableString.setSpan(
                new SuggestionSpan(
                        Locale.US, new String[] {"Hello"}, SuggestionSpan.FLAG_EASY_CORRECT),
                0,
                5,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        verifySurroundingTextNotEquals(text, new SurroundingText(spannableString, 1, 1, 0));
    }

    private void verifySurroundingTextNotEquals(SurroundingText text1, SurroundingText text2) {
        assertThat(text1.isEqualTo(text2)).isFalse();
        assertThat(text1).isNotEqualTo(text2);
        assertThat(text1.equals(text2)).isFalse();
        assertThat(text1.hashCode()).isNotEqualTo(text2.hashCode());
        assertThat(text1 == text2).isFalse();
    }

    @Test
    public void testIsEqualComparesText_isEqualTo() {
        final SurroundingText text = new SurroundingText("hello", 0, 1, 0);

        verifySurroundingTextEquals(
                text,
                new SurroundingText("hello", 0, 1, 0),
                /*equals=*/ false,
                /*isSameInstance=*/ false);
        verifySurroundingTextEquals(text, text, /*equals=*/ true, /*isSameInstance=*/ true);
    }

    private void verifySurroundingTextEquals(
            SurroundingText text1, SurroundingText text2, boolean equals, boolean isSameInstance) {
        assertThat(text1.isEqualTo(text2)).isTrue();
        if (equals) {
            assertThat(text1).isEqualTo(text2);
            assertThat(text1.equals(text2)).isTrue();
            assertThat(text1.hashCode()).isEqualTo(text2.hashCode());
        } else {
            assertThat(text1).isNotEqualTo(text2);
            assertThat(text1.equals(text2)).isFalse();
            assertThat(text1.hashCode()).isNotEqualTo(text2.hashCode());
        }
        if (isSameInstance) {
            assertThat(text1 == text2).isTrue();
            assertThat(text1).isSameInstanceAs(text2);
        } else {
            assertThat(text1 == text2).isFalse();
            assertThat(text1).isNotSameInstanceAs(text2);
        }
    }
}
