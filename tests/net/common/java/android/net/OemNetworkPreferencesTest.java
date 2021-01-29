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

package android.net;

import static com.android.testutils.MiscAsserts.assertThrows;
import static com.android.testutils.ParcelUtils.assertParcelSane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Build;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@IgnoreUpTo(Build.VERSION_CODES.R)
@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
public class OemNetworkPreferencesTest {

    private static final int TEST_PREF = OemNetworkPreferences.OEM_NETWORK_PREFERENCE_DEFAULT;
    private static final String TEST_PACKAGE = "com.google.apps.contacts";

    private final List<String> mPackages = new ArrayList<>();
    private final OemNetworkPreferences.Builder mBuilder = new OemNetworkPreferences.Builder();

    @Before
    public void beforeEachTestMethod() {
        mPackages.add(TEST_PACKAGE);
    }

    @Test
    public void builderAddNetworkPreferenceRequiresNonNullPackages() {
        assertThrows(NullPointerException.class,
                () -> mBuilder.addNetworkPreference(TEST_PREF, null));
    }

    @Test
    public void getNetworkPreferencesReturnsCorrectValue() {
        final int expectedNumberOfMappings = 1;
        mBuilder.addNetworkPreference(TEST_PREF, mPackages);

        final SparseArray<List<String>> networkPreferences =
                mBuilder.build().getNetworkPreferences();

        assertEquals(expectedNumberOfMappings, networkPreferences.size());
        assertEquals(mPackages.size(), networkPreferences.get(TEST_PREF).size());
        assertEquals(mPackages.get(0), networkPreferences.get(TEST_PREF).get(0));
    }

    @Test
    public void getNetworkPreferencesReturnsUnmodifiableValue() {
        final String newPackage = "new.com.google.apps.contacts";
        mBuilder.addNetworkPreference(TEST_PREF, mPackages);

        final SparseArray<List<String>> networkPreferences =
                mBuilder.build().getNetworkPreferences();

        assertThrows(UnsupportedOperationException.class,
                () -> networkPreferences.get(TEST_PREF).set(mPackages.size() - 1, newPackage));

        assertThrows(UnsupportedOperationException.class,
                () -> networkPreferences.get(TEST_PREF).add(newPackage));
    }

    @Test
    public void toStringReturnsCorrectValue() {
        mBuilder.addNetworkPreference(TEST_PREF, mPackages);

        final String networkPreferencesString = mBuilder.build().getNetworkPreferences().toString();

        assertTrue(networkPreferencesString.contains(Integer.toString(TEST_PREF)));
        assertTrue(networkPreferencesString.contains(TEST_PACKAGE));
    }

    @Test
    public void testOemNetworkPreferencesParcelable() {
        mBuilder.addNetworkPreference(TEST_PREF, mPackages);

        final OemNetworkPreferences prefs = mBuilder.build();

        assertParcelSane(prefs, 1 /* fieldCount */);
    }
}
