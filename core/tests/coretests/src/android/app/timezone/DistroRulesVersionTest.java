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
 * Tests for {@link DistroRulesVersion}.
 */
@LargeTest
public class DistroRulesVersionTest {

    @Test
    public void equalsAndHashCode() {
        DistroRulesVersion one = new DistroRulesVersion("2016a", 2);
        assertEqualsContract(one, one);

        DistroRulesVersion two = new DistroRulesVersion("2016a", 2);
        assertEqualsContract(one, two);

        DistroRulesVersion three = new DistroRulesVersion("2016b", 1);
        assertFalse(one.equals(three));
    }

    @Test
    public void parcelable() {
        DistroRulesVersion version = new DistroRulesVersion("2016a", 2);

        Parcel parcel = Parcel.obtain();
        version.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);

        DistroRulesVersion newVersion = DistroRulesVersion.CREATOR.createFromParcel(parcel);

        assertEquals(version, newVersion);
    }

    @Test
    public void isOlderThan() {
        DistroRulesVersion deviceVersion = new DistroRulesVersion("2016b", 2);
        assertFalse(deviceVersion.isOlderThan(deviceVersion));

        DistroRulesVersion sameVersion = new DistroRulesVersion("2016b", 2);
        assertFalse(deviceVersion.isOlderThan(sameVersion));

        DistroRulesVersion sameRulesNewerRevision = new DistroRulesVersion("2016b", 3);
        assertTrue(deviceVersion.isOlderThan(sameRulesNewerRevision));

        DistroRulesVersion sameRulesOlderRevision = new DistroRulesVersion("2016b", 1);
        assertFalse(deviceVersion.isOlderThan(sameRulesOlderRevision));

        DistroRulesVersion newerRules = new DistroRulesVersion("2016c", 2);
        assertTrue(deviceVersion.isOlderThan(newerRules));

        DistroRulesVersion olderRules = new DistroRulesVersion("2016a", 2);
        assertFalse(deviceVersion.isOlderThan(olderRules));
    }

    private static void assertEqualsContract(DistroRulesVersion one, DistroRulesVersion two) {
        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
    }
}
