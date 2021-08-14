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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SurroundingTextTest {

    @Test
    public void testSurroundingTextBasicCreation() {
        SurroundingText surroundingText1 = new SurroundingText("test", 0, 0, 0);
        assertThat(surroundingText1.getText(), is("test"));
        assertThat(surroundingText1.getSelectionStart(), is(0));
        assertThat(surroundingText1.getSelectionEnd(), is(0));
        assertThat(surroundingText1.getOffset(), is(0));

        SurroundingText surroundingText2 = new SurroundingText("", -1, -1, -1);
        assertThat(surroundingText2.getText(), is(""));
        assertThat(surroundingText2.getSelectionStart(), is(-1));
        assertThat(surroundingText2.getSelectionEnd(), is(-1));
        assertThat(surroundingText2.getOffset(), is(-1));

        SurroundingText surroundingText3 = new SurroundingText("hello", 0, 5, 0);
        assertThat(surroundingText3.getText(), is("hello"));
        assertThat(surroundingText3.getSelectionStart(), is(0));
        assertThat(surroundingText3.getSelectionEnd(), is(5));
        assertThat(surroundingText3.getOffset(), is(0));
    }

    @Test
    public void testSurroundingTextWriteToParcel() {
        SurroundingText surroundingText = new SurroundingText("text", 0, 1, 2);
        Parcel parcel = Parcel.obtain();
        surroundingText.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);
        SurroundingText surroundingTextFromParcel =
                SurroundingText.CREATOR.createFromParcel(parcel);
        assertThat(surroundingText.getText(), is("text"));
        assertThat(surroundingText.getSelectionStart(), is(0));
        assertThat(surroundingText.getSelectionEnd(), is(1));
        assertThat(surroundingText.getOffset(), is(2));
        assertThat(surroundingTextFromParcel.getText(), is("text"));
        assertThat(surroundingTextFromParcel.getSelectionStart(), is(0));
        assertThat(surroundingTextFromParcel.getSelectionEnd(), is(1));
        assertThat(surroundingTextFromParcel.getOffset(), is(2));
    }
}
