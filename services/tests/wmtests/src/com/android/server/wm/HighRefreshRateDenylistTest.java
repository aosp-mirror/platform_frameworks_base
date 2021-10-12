/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm;

import static android.hardware.display.DisplayManager.DeviceConfig.KEY_HIGH_REFRESH_RATE_BLACKLIST;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.util.Preconditions;
import com.android.server.testutils.FakeDeviceConfigInterface;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.Executor;

/**
 * Build/Install/Run:
 * atest WmTests:HighRefreshRateDenylistTest
 */
@SmallTest
@Presubmit
public class HighRefreshRateDenylistTest {
    private static final String APP1 = "com.android.sample1";
    private static final String APP2 = "com.android.sample2";
    private static final String APP3 = "com.android.sample3";

    private HighRefreshRateDenylist mDenylist;

    @After
    public void tearDown() {
        mDenylist.dispose();
    }

    @Test
    public void testDefaultDenylist() {
        final Resources r = createResources(APP1, APP2);
        mDenylist = new HighRefreshRateDenylist(r, new FakeDeviceConfig());

        assertTrue(mDenylist.isDenylisted(APP1));
        assertTrue(mDenylist.isDenylisted(APP2));
        assertFalse(mDenylist.isDenylisted(APP3));
    }

    @Test
    public void testNoDefaultDenylist() {
        final Resources r = createResources();
        mDenylist = new HighRefreshRateDenylist(r, new FakeDeviceConfig());

        assertFalse(mDenylist.isDenylisted(APP1));
    }

    @Test
    public void testDefaultDenylistIsOverriddenByDeviceConfig() {
        final Resources r = createResources(APP1);
        final FakeDeviceConfig config = new FakeDeviceConfig();
        config.setDenylist(APP2 + "," + APP3);
        mDenylist = new HighRefreshRateDenylist(r, config);

        assertFalse(mDenylist.isDenylisted(APP1));
        assertTrue(mDenylist.isDenylisted(APP2));
        assertTrue(mDenylist.isDenylisted(APP3));
    }

    @Test
    public void testDefaultDenylistIsOverriddenByEmptyDeviceConfig() {
        final Resources r = createResources(APP1);
        final FakeDeviceConfig config = new FakeDeviceConfig();
        config.setDenylist("");
        mDenylist = new HighRefreshRateDenylist(r, config);

        assertFalse(mDenylist.isDenylisted(APP1));
    }

    @Test
    public void testDefaultDenylistIsOverriddenByDeviceConfigUpdate() {
        final Resources r = createResources(APP1);
        final FakeDeviceConfig config = new FakeDeviceConfig();
        mDenylist = new HighRefreshRateDenylist(r, config);

        // First check that the default denylist is in effect
        assertTrue(mDenylist.isDenylisted(APP1));
        assertFalse(mDenylist.isDenylisted(APP2));
        assertFalse(mDenylist.isDenylisted(APP3));

        //  Then confirm that the DeviceConfig list has propagated and taken effect.
        config.setDenylist(APP2 + "," + APP3);
        assertFalse(mDenylist.isDenylisted(APP1));
        assertTrue(mDenylist.isDenylisted(APP2));
        assertTrue(mDenylist.isDenylisted(APP3));

        //  Finally make sure we go back to the default list if the DeviceConfig gets deleted.
        config.setDenylist(null);
        assertTrue(mDenylist.isDenylisted(APP1));
        assertFalse(mDenylist.isDenylisted(APP2));
        assertFalse(mDenylist.isDenylisted(APP3));
    }

    @Test
    public void testOverriddenByDeviceConfigUnrelatedFlagChanged() {
        final Resources r = createResources(APP1);
        final FakeDeviceConfig config = new FakeDeviceConfig();
        mDenylist = new HighRefreshRateDenylist(r, config);
        config.setDenylist(APP2 + "," + APP3);
        assertFalse(mDenylist.isDenylisted(APP1));
        assertTrue(mDenylist.isDenylisted(APP2));
        assertTrue(mDenylist.isDenylisted(APP3));

        //  Change an unrelated flag in our namespace and verify that the denylist is intact
        config.putPropertyAndNotify(DeviceConfig.NAMESPACE_DISPLAY_MANAGER, "someKey", "someValue");
        assertFalse(mDenylist.isDenylisted(APP1));
        assertTrue(mDenylist.isDenylisted(APP2));
        assertTrue(mDenylist.isDenylisted(APP3));
    }

    private Resources createResources(String... defaultDenylist) {
        Resources r = mock(Resources.class);
        when(r.getStringArray(R.array.config_highRefreshRateBlacklist))
                .thenReturn(defaultDenylist);
        return r;
    }

    private static class FakeDeviceConfig extends FakeDeviceConfigInterface {

        @Override
        public String getProperty(String namespace, String name) {
            Preconditions.checkArgument(DeviceConfig.NAMESPACE_DISPLAY_MANAGER.equals(namespace));
            Preconditions.checkArgument(KEY_HIGH_REFRESH_RATE_BLACKLIST.equals(name));
            return super.getProperty(namespace, name);
        }

        @Override
        public void addOnPropertiesChangedListener(String namespace, Executor executor,
                DeviceConfig.OnPropertiesChangedListener listener) {
            Preconditions.checkArgument(DeviceConfig.NAMESPACE_DISPLAY_MANAGER.equals(namespace));
            super.addOnPropertiesChangedListener(namespace, executor, listener);
        }

        void setDenylist(String denylist) {
            putPropertyAndNotify(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    KEY_HIGH_REFRESH_RATE_BLACKLIST, denylist);
        }
    }
}
