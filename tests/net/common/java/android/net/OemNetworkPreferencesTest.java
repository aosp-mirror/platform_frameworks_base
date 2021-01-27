/*
 * Copyright (C) 2021 The Android Open Source Project
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

@IgnoreUpTo(Build.VERSION_CODES.R)
@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
public class OemNetworkPreferencesTest {

    private static final int TEST_PREF = OemNetworkPreferences.OEM_NETWORK_PREFERENCE_UNINITIALIZED;
    private static final String TEST_PACKAGE = "com.google.apps.contacts";

    private final OemNetworkPreferences.Builder mBuilder = new OemNetworkPreferences.Builder();

    @Test
    public void testBuilderAddNetworkPreferenceRequiresNonNullPackageName() {
        assertThrows(NullPointerException.class,
                () -> mBuilder.addNetworkPreference(null, TEST_PREF));
    }

    @Test
    public void testBuilderRemoveNetworkPreferenceRequiresNonNullPackageName() {
        assertThrows(NullPointerException.class,
                () -> mBuilder.clearNetworkPreference(null));
    }

    @Test
    public void testGetNetworkPreferenceReturnsCorrectValue() {
        final int expectedNumberOfMappings = 1;
        mBuilder.addNetworkPreference(TEST_PACKAGE, TEST_PREF);

        final Map<String, Integer> networkPreferences =
                mBuilder.build().getNetworkPreferences();

        assertEquals(expectedNumberOfMappings, networkPreferences.size());
        assertTrue(networkPreferences.containsKey(TEST_PACKAGE));
    }

    @Test
    public void testGetNetworkPreferenceReturnsUnmodifiableValue() {
        final String newPackage = "new.com.google.apps.contacts";
        mBuilder.addNetworkPreference(TEST_PACKAGE, TEST_PREF);

        final Map<String, Integer> networkPreferences =
                mBuilder.build().getNetworkPreferences();

        assertThrows(UnsupportedOperationException.class,
                () -> networkPreferences.put(newPackage, TEST_PREF));

        assertThrows(UnsupportedOperationException.class,
                () -> networkPreferences.remove(TEST_PACKAGE));

    }

    @Test
    public void testToStringReturnsCorrectValue() {
        mBuilder.addNetworkPreference(TEST_PACKAGE, TEST_PREF);

        final String networkPreferencesString = mBuilder.build().getNetworkPreferences().toString();

        assertTrue(networkPreferencesString.contains(Integer.toString(TEST_PREF)));
        assertTrue(networkPreferencesString.contains(TEST_PACKAGE));
    }

    @Test
    public void testOemNetworkPreferencesParcelable() {
        mBuilder.addNetworkPreference(TEST_PACKAGE, TEST_PREF);

        final OemNetworkPreferences prefs = mBuilder.build();

        assertParcelSane(prefs, 1 /* fieldCount */);
    }

    @Test
    public void testAddNetworkPreferenceOverwritesPriorPreference() {
        final int newPref = OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID;
        mBuilder.addNetworkPreference(TEST_PACKAGE, TEST_PREF);
        Map<String, Integer> networkPreferences =
                mBuilder.build().getNetworkPreferences();

        assertTrue(networkPreferences.containsKey(TEST_PACKAGE));
        assertEquals(networkPreferences.get(TEST_PACKAGE).intValue(), TEST_PREF);

        mBuilder.addNetworkPreference(TEST_PACKAGE, newPref);
        networkPreferences = mBuilder.build().getNetworkPreferences();

        assertTrue(networkPreferences.containsKey(TEST_PACKAGE));
        assertEquals(networkPreferences.get(TEST_PACKAGE).intValue(), newPref);
    }

    @Test
    public void testRemoveNetworkPreferenceRemovesValue() {
        mBuilder.addNetworkPreference(TEST_PACKAGE, TEST_PREF);
        Map<String, Integer> networkPreferences =
                mBuilder.build().getNetworkPreferences();

        assertTrue(networkPreferences.containsKey(TEST_PACKAGE));

        mBuilder.clearNetworkPreference(TEST_PACKAGE);
        networkPreferences = mBuilder.build().getNetworkPreferences();

        assertFalse(networkPreferences.containsKey(TEST_PACKAGE));
    }

    @Test
    public void testConstructorByOemNetworkPreferencesSetsValue() {
        mBuilder.addNetworkPreference(TEST_PACKAGE, TEST_PREF);
        OemNetworkPreferences networkPreference = mBuilder.build();

        final Map<String, Integer> networkPreferences =
                new OemNetworkPreferences
                        .Builder(networkPreference)
                        .build()
                        .getNetworkPreferences();

        assertTrue(networkPreferences.containsKey(TEST_PACKAGE));
        assertEquals(networkPreferences.get(TEST_PACKAGE).intValue(), TEST_PREF);
    }
}
