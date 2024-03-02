/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.content.res.Resources;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.testables.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class TestableResourcesTest {

    @Rule
    public TestableContext mContext = new TestableContext(InstrumentationRegistry.getContext());

    @Test
    public void testLazyInit() {
        Resources before = mContext.getResources();
        mContext.ensureTestableResources();
        Resources after = mContext.getResources();
        assertNotEquals(before, after);
    }

    @Test
    public void testAddingResource() {
        final int nonExistentId = 3; // Ids don't go this low.

        try {
            mContext.getColor(nonExistentId);
            fail("Should throw NotFoundException");
        } catch (Resources.NotFoundException e) {
        }
        mContext.getOrCreateTestableResources().addOverride(nonExistentId, 0xffffff);

        assertEquals(0xffffff, mContext.getColor(nonExistentId));
    }

    @Test
    public void testClearingResource() {
        final int nonExistentId = 3; // Ids don't go this low.

        mContext.getOrCreateTestableResources().addOverride(nonExistentId, 0xffffff);
        assertEquals(0xffffff, mContext.getColor(nonExistentId));
        mContext.getOrCreateTestableResources().removeOverride(nonExistentId);
        try {
            mContext.getColor(nonExistentId);
            fail("Should throw NotFoundException");
        } catch (Resources.NotFoundException e) {
        }
    }

    @Test
    public void testOverrideExisting() {
        int existentId = R.string.test_string;

        assertNotNull(mContext.getString(existentId));
        mContext.getOrCreateTestableResources().addOverride(existentId, "Other strings");

        assertEquals("Other strings", mContext.getString(existentId));
    }

    @Test(expected = Resources.NotFoundException.class)
    public void testNonExistentException() {
        int existentId = R.string.test_string;

        assertNotNull(mContext.getString(existentId));
        mContext.getOrCreateTestableResources().addOverride(existentId, null);

        assertNull(mContext.getString(existentId));
    }
}
