/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package android.content;

import android.os.Bundle;
import android.os.Parcelable;
import android.test.AndroidTestCase;

import androidx.test.filters.LargeTest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@LargeTest
public class RestrictionsManagerTest extends AndroidTestCase {
    private RestrictionsManager mRm;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mRm = (RestrictionsManager) mContext.getSystemService(Context.RESTRICTIONS_SERVICE);
    }

    public void testGetManifestRestrictions() {
        String packageName = getContext().getPackageName();
        List<RestrictionEntry> manifestRestrictions = mRm.getManifestRestrictions(packageName);
        assertEquals(6, manifestRestrictions.size());
        Set<String> verifiedKeys = new HashSet<>(Arrays.asList("bundle_key", "bundle_array_key",
                "bundle_array_bundle_key"));
        for (RestrictionEntry entry : manifestRestrictions) {
            if ("bundle_key".equals(entry.getKey())) {
                assertEquals("bundle_key entry should have 2 children entries",
                        2, entry.getRestrictions().length);
                verifiedKeys.remove(entry.getKey());
            } else if ("bundle_array_key".equals(entry.getKey())) {
                assertEquals("bundle_array_key should have 2 children entries",
                        2, entry.getRestrictions().length);
                assertNotNull(entry.getRestrictions());
                for (RestrictionEntry childEntry : entry.getRestrictions()) {
                    if ("bundle_array_bundle_key".equals(childEntry.getKey())) {
                        assertNotNull(childEntry.getRestrictions());
                        assertEquals("bundle_array_bundle_key should have 1 child entry",
                                1, childEntry.getRestrictions().length);
                        verifiedKeys.remove(childEntry.getKey());
                    }
                }
                verifiedKeys.remove(entry.getKey());
            }
        }
        assertTrue("Entries" + verifiedKeys + " were not found", verifiedKeys.isEmpty());
    }

    public void testConvertRestrictionsToBundle() {
        String packageName = getContext().getPackageName();
        List<RestrictionEntry> manifestRestrictions = mRm.getManifestRestrictions(packageName);
        Bundle bundle = RestrictionsManager.convertRestrictionsToBundle(manifestRestrictions);
        assertEquals(6, bundle.size());
        Bundle childBundle = bundle.getBundle("bundle_key");
        assertNotNull(childBundle);
        assertEquals(2, childBundle.size());
        Parcelable[] childBundleArray = bundle.getParcelableArray("bundle_array_key");
        assertEquals(2, childBundleArray.length);
    }

    public void testConvertRestrictionsToBundle_bundleArray() {
        String packageName = getContext().getPackageName();
        List<RestrictionEntry> manifestRestrictions = mRm.getManifestRestrictions(packageName);
        Bundle bundle = RestrictionsManager.convertRestrictionsToBundle(manifestRestrictions);
        assertEquals(6, bundle.size());
        Parcelable[] array = bundle.getParcelableArray("bundle_array_key");
        assertNotNull(array);
        assertEquals(2, array.length);
        Bundle firstBundle = (Bundle) array[0];
        assertEquals(0, firstBundle.size());
        Bundle secondBundle = (Bundle) array[1];
        assertEquals(1, secondBundle.size());
        assertTrue(secondBundle.containsKey("bundle_array_bundle_int_key"));
    }
}
