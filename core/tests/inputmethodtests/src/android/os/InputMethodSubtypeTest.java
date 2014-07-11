/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.os;

import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

public class InputMethodSubtypeTest extends InstrumentationTestCase {
    @SmallTest
    public void testLocale() throws Exception {
        // Compare locale
        assertEquals(createDummySubtype("en_US").getLocale(),
                createDummySubtype("en_US").getLocale());
        assertEquals(createDummySubtype("en_US").getLocale(),
                cloneViaParcel(createDummySubtype("en_US")).getLocale());
        assertEquals(createDummySubtype("en_US").getLocale(),
                cloneViaParcel(cloneViaParcel(createDummySubtype("en_US"))).getLocale());
    }

    @SmallTest
    public void testHashCode() throws Exception {
        assertEquals(createDummySubtype("en_US").hashCode(),
                createDummySubtype("en_US").hashCode());
        assertEquals(createDummySubtype("en_US").hashCode(),
                cloneViaParcel(createDummySubtype("en_US")).hashCode());
        assertEquals(createDummySubtype("en_US").hashCode(),
                cloneViaParcel(cloneViaParcel(createDummySubtype("en_US"))).hashCode());
    }

    private static final InputMethodSubtype cloneViaParcel(final InputMethodSubtype original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return InputMethodSubtype.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }

    private static final InputMethodSubtype createDummySubtype(final String locale) {
        final InputMethodSubtypeBuilder builder = new InputMethodSubtypeBuilder();
        return builder.setSubtypeNameResId(0)
                .setSubtypeIconResId(0)
                .setSubtypeLocale(locale)
                .setIsAsciiCapable(true)
                .build();
    }
}
