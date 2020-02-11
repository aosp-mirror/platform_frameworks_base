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
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.server.wm.HighRefreshRateBlacklist.DeviceConfigInterface;

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

    @Test
    public void testDefaultBlacklist() {
        final Resources r = createResources("com.android.sample1", "com.android.sample2");
        HighRefreshRateBlacklist blacklist =
                new HighRefreshRateBlacklist(r, new FakeDeviceConfigInterface());
        assertTrue(blacklist.isBlacklisted("com.android.sample1"));
        assertTrue(blacklist.isBlacklisted("com.android.sample2"));
        assertFalse(blacklist.isBlacklisted("com.android.sample3"));
    }

    @Test
    public void testNoDefaultBlacklist() {
        final Resources r = createResources();
        HighRefreshRateBlacklist blacklist =
                new HighRefreshRateBlacklist(r, new FakeDeviceConfigInterface());
        assertFalse(blacklist.isBlacklisted("com.android.sample1"));
    }

    @Test
    public void testDefaultBlacklistIsOverriddenByDeviceConfig() {
        final Resources r = createResources("com.android.sample1");
        final FakeDeviceConfigInterface config = new FakeDeviceConfigInterface();
        config.setBlacklist("com.android.sample2,com.android.sample3");
        HighRefreshRateBlacklist blacklist = new HighRefreshRateBlacklist(r, config);
        assertFalse(blacklist.isBlacklisted("com.android.sample1"));
        assertTrue(blacklist.isBlacklisted("com.android.sample2"));
        assertTrue(blacklist.isBlacklisted("com.android.sample3"));
    }

    @Test
    public void testDefaultBlacklistIsOverriddenByEmptyDeviceConfig() {
        final Resources r = createResources("com.android.sample1");
        final FakeDeviceConfigInterface config = new FakeDeviceConfigInterface();
        config.setBlacklist("");
        HighRefreshRateBlacklist blacklist = new HighRefreshRateBlacklist(r, config);
        assertFalse(blacklist.isBlacklisted("com.android.sample1"));
    }

    @Test
    public void testDefaultBlacklistIsOverriddenByDeviceConfigUpdate() {
        final Resources r = createResources("com.android.sample1");
        final FakeDeviceConfigInterface config = new FakeDeviceConfigInterface();
        HighRefreshRateBlacklist blacklist = new HighRefreshRateBlacklist(r, config);

        // First check that the default blacklist is in effect
        assertTrue(blacklist.isBlacklisted("com.android.sample1"));
        assertFalse(blacklist.isBlacklisted("com.android.sample2"));
        assertFalse(blacklist.isBlacklisted("com.android.sample3"));

        //  Then confirm that the DeviceConfig list has propagated and taken effect.
        config.setBlacklist("com.android.sample2,com.android.sample3");
        assertFalse(blacklist.isBlacklisted("com.android.sample1"));
        assertTrue(blacklist.isBlacklisted("com.android.sample2"));
        assertTrue(blacklist.isBlacklisted("com.android.sample3"));

        //  Finally make sure we go back to the default list if the DeviceConfig gets deleted.
        config.setBlacklist(null);
        assertTrue(blacklist.isBlacklisted("com.android.sample1"));
        assertFalse(blacklist.isBlacklisted("com.android.sample2"));
        assertFalse(blacklist.isBlacklisted("com.android.sample3"));
    }

    private Resources createResources(String... defaultBlacklist) {
        Resources r = mock(Resources.class);
        when(r.getStringArray(R.array.config_highRefreshRateBlacklist))
                .thenReturn(defaultBlacklist);
        return r;
    }


    class FakeDeviceConfigInterface implements DeviceConfigInterface {
        private List<Pair<DeviceConfig.OnPropertyChangedListener, Executor>> mListeners =
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
        public void addOnPropertyChangedListener(String namespace, Executor executor,
                DeviceConfig.OnPropertyChangedListener listener) {

            if (!DeviceConfig.NAMESPACE_DISPLAY_MANAGER.equals(namespace)) {
                throw new IllegalArgumentException("Only things in NAMESPACE_DISPLAY_MANAGER "
                        + "supported.");
            }
            mListeners.add(new Pair<>(listener, executor));
        }

        void setBlacklist(String blacklist) {
            mBlacklist = blacklist;
            CountDownLatch latch = new CountDownLatch(mListeners.size());
            for (Pair<DeviceConfig.OnPropertyChangedListener, Executor> listenerInfo :
                    mListeners) {
                final Executor executor = listenerInfo.second;
                final DeviceConfig.OnPropertyChangedListener listener = listenerInfo.first;
                executor.execute(() -> {
                    listener.onPropertyChanged(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                            KEY_HIGH_REFRESH_RATE_BLACKLIST, blacklist);
                    latch.countDown();
                });
            }
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to notify all blacklist listeners in time.", e);
            }
        }
    }
}
