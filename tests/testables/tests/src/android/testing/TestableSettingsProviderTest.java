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

import android.content.ContentResolver;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TestableSettingsProviderTest {

    public static final String NONEXISTENT_SETTING = "nonexistent_setting";
    private static final String TAG = "TestableSettingsProviderTest";
    private ContentResolver mContentResolver;
    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getContext());

    @Before
    public void setup() {
        mContentResolver = mContext.getContentResolver();
        Settings.Secure.putString(mContentResolver, NONEXISTENT_SETTING, null);
        Settings.Global.putString(mContentResolver, NONEXISTENT_SETTING, "initial value");
        Settings.Global.putString(mContentResolver, Global.DEVICE_PROVISIONED, null);
    }

    @Test
    public void testInitialValueSecure() {
        String value = Secure.getString(mContentResolver, NONEXISTENT_SETTING);
        assertNull(value);
    }

    @Test
    public void testInitialValueGlobal() {
        String value = Global.getString(mContentResolver, NONEXISTENT_SETTING);
        assertEquals("initial value", value);
    }

    @Test
    public void testSeparateTables() {
        Secure.putString(mContentResolver, NONEXISTENT_SETTING, "something");
        Global.putString(mContentResolver, NONEXISTENT_SETTING, "else");
        assertEquals("something", Secure.getString(mContentResolver, NONEXISTENT_SETTING));
        assertEquals("else", Global.getString(mContentResolver, NONEXISTENT_SETTING));
    }

    @Test
    public void testSeparateUsers() {
        Secure.putStringForUser(mContentResolver, NONEXISTENT_SETTING, "something", 0);
        Secure.putStringForUser(mContentResolver, NONEXISTENT_SETTING, "else", 1);
        assertEquals("something",
                Secure.getStringForUser(mContentResolver, NONEXISTENT_SETTING, 0));
        assertEquals("else",
                Secure.getStringForUser(mContentResolver, NONEXISTENT_SETTING, 1));
    }

    @Test
    public void testPassThrough() {
        // Grab the value of a setting that is not overridden.
        assertTrue(Secure.getInt(mContentResolver, Secure.USER_SETUP_COMPLETE, 0) != 0);
    }

    @Test
    public void testOverrideExisting() {
        // Grab the value of a setting that is overridden and will be different than the actual
        // value.
        assertNull(Global.getString(mContentResolver, Global.DEVICE_PROVISIONED));
    }

    @Test
    public void testRelease() {
        // Verify different value.
        assertNull(Global.getString(mContentResolver, Global.DEVICE_PROVISIONED));
        mContext.getSettingsProvider().clearValuesAndCheck(mContext);
        // Verify actual value after release.
        assertEquals("1", Global.getString(mContentResolver, Global.DEVICE_PROVISIONED));
    }
}
