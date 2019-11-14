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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.server.wm.HighRefreshRateBlacklist.DeviceConfigInterface;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Build/Install/Run:
 * atest WmTests:HighRefreshRateBlacklistTest
 */
@SmallTest
@Presubmit
public class HighRefreshRateBlacklistTest {
    private static final String APP1 = "com.android.sample1";
    private static final String APP2 = "com.android.sample2";
    private static final String APP3 = "com.android.sample3";

    private HighRefreshRateBlacklist mBlacklist;

    @After
    public void tearDown() {
        mBlacklist.dispose();
    }

    @Test
    public void testDefaultBlacklist() {
        final Resources r = createResources(APP1, APP2);
        mBlacklist = new HighRefreshRateBlacklist(r, new FakeDeviceConfigInterface());

        assertTrue(mBlacklist.isBlacklisted(APP1));
        assertTrue(mBlacklist.isBlacklisted(APP2));
        assertFalse(mBlacklist.isBlacklisted(APP3));
    }

    @Test
    public void testNoDefaultBlacklist() {
        final Resources r = createResources();
        mBlacklist = new HighRefreshRateBlacklist(r, new FakeDeviceConfigInterface());

        assertFalse(mBlacklist.isBlacklisted(APP1));
    }

    @Test
    public void testDefaultBlacklistIsOverriddenByDeviceConfig() {
        final Resources r = createResources(APP1);
        final FakeDeviceConfigInterface config = new FakeDeviceConfigInterface();
        config.setBlacklist(APP2 + "," + APP3);
        mBlacklist = new HighRefreshRateBlacklist(r, config);

        assertFalse(mBlacklist.isBlacklisted(APP1));
        assertTrue(mBlacklist.isBlacklisted(APP2));
        assertTrue(mBlacklist.isBlacklisted(APP3));
    }

    @Test
    public void testDefaultBlacklistIsOverriddenByEmptyDeviceConfig() {
        final Resources r = createResources(APP1);
        final FakeDeviceConfigInterface config = new FakeDeviceConfigInterface();
        config.setBlacklist("");
        mBlacklist = new HighRefreshRateBlacklist(r, config);

        assertFalse(mBlacklist.isBlacklisted(APP1));
    }

    @Test
    public void testDefaultBlacklistIsOverriddenByDeviceConfigUpdate() {
        final Resources r = createResources(APP1);
        final FakeDeviceConfigInterface config = new FakeDeviceConfigInterface();
        mBlacklist = new HighRefreshRateBlacklist(r, config);

        // First check that the default blacklist is in effect
        assertTrue(mBlacklist.isBlacklisted(APP1));
        assertFalse(mBlacklist.isBlacklisted(APP2));
        assertFalse(mBlacklist.isBlacklisted(APP3));

        //  Then confirm that the DeviceConfig list has propagated and taken effect.
        config.setBlacklist(APP2 + "," + APP3);
        assertFalse(mBlacklist.isBlacklisted(APP1));
        assertTrue(mBlacklist.isBlacklisted(APP2));
        assertTrue(mBlacklist.isBlacklisted(APP3));

        //  Finally make sure we go back to the default list if the DeviceConfig gets deleted.
        config.setBlacklist(null);
        assertTrue(mBlacklist.isBlacklisted(APP1));
        assertFalse(mBlacklist.isBlacklisted(APP2));
        assertFalse(mBlacklist.isBlacklisted(APP3));
    }

    private Resources createResources(String... defaultBlacklist) {
        Resources r = mock(Resources.class);
        when(r.getStringArray(R.array.config_highRefreshRateBlacklist))
                .thenReturn(defaultBlacklist);
        return r;
    }

    private static class FakeDeviceConfigInterface implements DeviceConfigInterface {
        private List<Pair<DeviceConfig.OnPropertiesChangedListener, Executor>> mListeners =
                new ArrayList<>();
        private String mBlacklist;

        @Override
        public String getProperty(String namespace, String name) {
            if (!DeviceConfig.NAMESPACE_DISPLAY_MANAGER.equals(namespace)
                    || !KEY_HIGH_REFRESH_RATE_BLACKLIST.equals(name)) {
                throw new IllegalArgumentException("Only things in NAMESPACE_DISPLAY_MANAGER "
                        + "supported.");
            }
            return mBlacklist;
        }

        @Override
        public void addOnPropertiesChangedListener(String namespace, Executor executor,
                DeviceConfig.OnPropertiesChangedListener listener) {

            if (!DeviceConfig.NAMESPACE_DISPLAY_MANAGER.equals(namespace)) {
                throw new IllegalArgumentException("Only things in NAMESPACE_DISPLAY_MANAGER "
                        + "supported.");
            }
            mListeners.add(new Pair<>(listener, executor));
        }

        @Override
        public void removePropertiesChangedListener(
                DeviceConfig.OnPropertiesChangedListener listener) {
            // Don't need to implement since we don't invoke real DeviceConfig and we clear
            // HighRefreshRateBlacklist#mDeviceConfig in HighRefreshRateBlacklist#dispose.
        }

        void setBlacklist(String blacklist) {
            mBlacklist = blacklist;
            CountDownLatch latch = new CountDownLatch(mListeners.size());
            for (Pair<DeviceConfig.OnPropertiesChangedListener, Executor> listenerInfo :
                    mListeners) {
                final Executor executor = listenerInfo.second;
                final DeviceConfig.OnPropertiesChangedListener listener = listenerInfo.first;
                DeviceConfig.Properties properties = createBlacklistProperties(blacklist);
                executor.execute(() -> {
                    listener.onPropertiesChanged(properties);
                    latch.countDown();
                });
            }
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to notify all blacklist listeners in time.", e);
            }
        }

        private DeviceConfig.Properties createBlacklistProperties(final String blacklist) {
            DeviceConfig.Properties properties = mock(DeviceConfig.Properties.class);
            when(properties.getString(anyString(), any())).thenAnswer(invocation -> {
                final Object[] args = invocation.getArguments();
                if (KEY_HIGH_REFRESH_RATE_BLACKLIST.equals(args[0])) {
                    return blacklist;
                } else {
                    return args[1];
                }
            });
            return properties;
        }
    }
}
