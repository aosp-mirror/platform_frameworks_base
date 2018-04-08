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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view.textclassifier;

import static org.junit.Assert.assertEquals;

import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SelectionEventTest {

    @Test
    public void testParcel() {
        final SelectionEvent event = SelectionEvent.createSelectionStartedEvent(
                SelectionEvent.INVOCATION_MANUAL, 0);
        final Parcel parcel = Parcel.obtain();
        event.writeToParcel(parcel, event.describeContents());
        parcel.setDataPosition(0);
        assertEquals(event, SelectionEvent.CREATOR.createFromParcel(parcel));
    }
}
