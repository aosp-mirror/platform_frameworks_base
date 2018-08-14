/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app.timezone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.support.test.filters.LargeTest;

import org.junit.Test;

/**
 * Tests for {@link DistroFormatVersion}.
 */
@LargeTest
public class DistroFormatVersionTest {

    @Test
    public void equalsAndHashCode() {
        DistroFormatVersion one = new DistroFormatVersion(1, 2);
        assertEqualsContract(one, one);

        DistroFormatVersion two = new DistroFormatVersion(1, 2);
        assertEqualsContract(one, two);

        DistroFormatVersion three = new DistroFormatVersion(2, 1);
        assertFalse(one.equals(three));
    }

    @Test
    public void parcelable() {
        DistroFormatVersion version = new DistroFormatVersion(2, 3);

        Parcel parcel = Parcel.obtain();
        version.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);

        DistroFormatVersion newVersion = DistroFormatVersion.CREATOR.createFromParcel(parcel);

        assertEquals(version, newVersion);
    }

    @Test
    public void supportsVersion() {
        DistroFormatVersion deviceVersion = new DistroFormatVersion(2, 2);
        assertTrue(deviceVersion.supports(deviceVersion));

        DistroFormatVersion sameVersion = new DistroFormatVersion(2, 2);
        assertTrue(deviceVersion.supports(sameVersion));

        // Minor versions are backwards compatible.
        DistroFormatVersion sameMajorNewerMinor = new DistroFormatVersion(2, 3);
        assertTrue(deviceVersion.supports(sameMajorNewerMinor));
        DistroFormatVersion sameMajorOlderMinor = new DistroFormatVersion(2, 1);
        assertFalse(deviceVersion.supports(sameMajorOlderMinor));

        // Major versions are not backwards compatible.
        DistroFormatVersion newerMajor = new DistroFormatVersion(1, 2);
        assertFalse(deviceVersion.supports(newerMajor));
        DistroFormatVersion olderMajor = new DistroFormatVersion(3, 2);
        assertFalse(deviceVersion.supports(olderMajor));
    }

    private static void assertEqualsContract(DistroFormatVersion one, DistroFormatVersion two) {
        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
    }
}
