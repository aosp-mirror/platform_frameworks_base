/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.app;

import static android.content.Intent.ACTION_BATTERY_CHANGED;
import static android.content.Intent.ACTION_DEVICE_STORAGE_LOW;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.SystemProperties;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArrayMap;

import androidx.annotation.GuardedBy;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.modules.utils.testing.ExtendedMockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@EnableFlags(Flags.FLAG_USE_STICKY_BCAST_CACHE)
@RunWith(AndroidJUnit4.class)
@SmallTest
public class BroadcastStickyCacheTest {
    @ClassRule
    public static final SetFlagsRule.ClassRule mClassRule = new SetFlagsRule.ClassRule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = mClassRule.createSetFlagsRule();

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .mockStatic(SystemProperties.class)
            .build();

    private static final String PROP_KEY_BATTERY_CHANGED = BroadcastStickyCache.getKey(
            ACTION_BATTERY_CHANGED);

    private final TestSystemProps mTestSystemProps = new TestSystemProps();

    @Before
    public void setUp() {
        doAnswer(invocation -> {
            final String name = invocation.getArgument(0);
            final long value = Long.parseLong(invocation.getArgument(1));
            mTestSystemProps.add(name, value);
            return null;
        }).when(() -> SystemProperties.set(anyString(), anyString()));
        doAnswer(invocation -> {
            final String name = invocation.getArgument(0);
            final TestSystemProps.Handle testHandle = mTestSystemProps.query(name);
            if (testHandle == null) {
                return null;
            }
            final SystemProperties.Handle handle = Mockito.mock(SystemProperties.Handle.class);
            doAnswer(handleInvocation -> testHandle.getLong(-1)).when(handle).getLong(anyLong());
            return handle;
        }).when(() -> SystemProperties.find(anyString()));
    }

    @After
    public void tearDown() {
        mTestSystemProps.clear();
        BroadcastStickyCache.clearForTest();
    }

    @Test
    public void testUseCache_nullFilter() {
        assertThat(BroadcastStickyCache.useCache(null)).isEqualTo(false);
    }

    @Test
    public void testUseCache_noActions() {
        final IntentFilter filter = new IntentFilter();
        assertThat(BroadcastStickyCache.useCache(filter)).isEqualTo(false);
    }

    @Test
    public void testUseCache_multipleActions() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_DEVICE_STORAGE_LOW);
        filter.addAction(ACTION_BATTERY_CHANGED);
        assertThat(BroadcastStickyCache.useCache(filter)).isEqualTo(false);
    }

    @Test
    public void testUseCache_valueNotSet() {
        final IntentFilter filter = new IntentFilter(ACTION_BATTERY_CHANGED);
        assertThat(BroadcastStickyCache.useCache(filter)).isEqualTo(false);
    }

    @Test
    public void testUseCache() {
        final IntentFilter filter = new IntentFilter(ACTION_BATTERY_CHANGED);
        final Intent intent = new Intent(ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 90);
        BroadcastStickyCache.incrementVersion(ACTION_BATTERY_CHANGED);
        BroadcastStickyCache.add(filter, intent);
        assertThat(BroadcastStickyCache.useCache(filter)).isEqualTo(true);
    }

    @Test
    public void testUseCache_versionMismatch() {
        final IntentFilter filter = new IntentFilter(ACTION_BATTERY_CHANGED);
        final Intent intent = new Intent(ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 90);
        BroadcastStickyCache.incrementVersion(ACTION_BATTERY_CHANGED);
        BroadcastStickyCache.add(filter, intent);
        BroadcastStickyCache.incrementVersion(ACTION_BATTERY_CHANGED);

        assertThat(BroadcastStickyCache.useCache(filter)).isEqualTo(false);
    }

    @Test
    public void testAdd() {
        final IntentFilter filter = new IntentFilter(ACTION_BATTERY_CHANGED);
        Intent intent = new Intent(ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 90);
        BroadcastStickyCache.incrementVersion(ACTION_BATTERY_CHANGED);
        BroadcastStickyCache.add(filter, intent);
        assertThat(BroadcastStickyCache.useCache(filter)).isEqualTo(true);
        Intent actualIntent = BroadcastStickyCache.getIntentUnchecked(filter);
        assertThat(actualIntent).isNotNull();
        assertEquals(actualIntent, intent);

        intent = new Intent(ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 99);
        BroadcastStickyCache.add(filter, intent);
        actualIntent = BroadcastStickyCache.getIntentUnchecked(filter);
        assertThat(actualIntent).isNotNull();
        assertEquals(actualIntent, intent);
    }

    @Test
    public void testIncrementVersion_propExists() {
        SystemProperties.set(PROP_KEY_BATTERY_CHANGED, String.valueOf(100));

        BroadcastStickyCache.incrementVersion(ACTION_BATTERY_CHANGED);
        assertThat(mTestSystemProps.get(PROP_KEY_BATTERY_CHANGED, -1 /* def */)).isEqualTo(101);
        BroadcastStickyCache.incrementVersion(ACTION_BATTERY_CHANGED);
        assertThat(mTestSystemProps.get(PROP_KEY_BATTERY_CHANGED, -1 /* def */)).isEqualTo(102);
    }

    @Test
    public void testIncrementVersion_propNotExists() {
        // Verify that the property doesn't exist
        assertThat(mTestSystemProps.get(PROP_KEY_BATTERY_CHANGED, -1 /* def */)).isEqualTo(-1);

        BroadcastStickyCache.incrementVersion(ACTION_BATTERY_CHANGED);
        assertThat(mTestSystemProps.get(PROP_KEY_BATTERY_CHANGED, -1 /* def */)).isEqualTo(1);
        BroadcastStickyCache.incrementVersion(ACTION_BATTERY_CHANGED);
        assertThat(mTestSystemProps.get(PROP_KEY_BATTERY_CHANGED, -1 /* def */)).isEqualTo(2);
    }

    @Test
    public void testIncrementVersionIfExists_propExists() {
        BroadcastStickyCache.incrementVersion(ACTION_BATTERY_CHANGED);

        BroadcastStickyCache.incrementVersionIfExists(ACTION_BATTERY_CHANGED);
        assertThat(mTestSystemProps.get(PROP_KEY_BATTERY_CHANGED, -1 /* def */)).isEqualTo(2);
        BroadcastStickyCache.incrementVersionIfExists(ACTION_BATTERY_CHANGED);
        assertThat(mTestSystemProps.get(PROP_KEY_BATTERY_CHANGED, -1 /* def */)).isEqualTo(3);
    }

    @Test
    public void testIncrementVersionIfExists_propNotExists() {
        // Verify that the property doesn't exist
        assertThat(mTestSystemProps.get(PROP_KEY_BATTERY_CHANGED, -1 /* def */)).isEqualTo(-1);

        BroadcastStickyCache.incrementVersionIfExists(ACTION_BATTERY_CHANGED);
        assertThat(mTestSystemProps.get(PROP_KEY_BATTERY_CHANGED, -1 /* def */)).isEqualTo(-1);
        // Verify that property is not added as part of the querying.
        BroadcastStickyCache.incrementVersionIfExists(ACTION_BATTERY_CHANGED);
        assertThat(mTestSystemProps.get(PROP_KEY_BATTERY_CHANGED, -1 /* def */)).isEqualTo(-1);
    }

    private void assertEquals(Intent actualIntent, Intent expectedIntent) {
        assertThat(actualIntent.getAction()).isEqualTo(expectedIntent.getAction());
        assertEquals(actualIntent.getExtras(), expectedIntent.getExtras());
    }

    private void assertEquals(Bundle actualExtras, Bundle expectedExtras) {
        assertWithMessage("Extras expected=%s, actual=%s", expectedExtras, actualExtras)
                .that(actualExtras.kindofEquals(expectedExtras)).isTrue();
    }

    private static final class TestSystemProps {
        @GuardedBy("mSysProps")
        private final ArrayMap<String, Long> mSysProps = new ArrayMap<>();

        public void add(String name, long value) {
            synchronized (mSysProps) {
                mSysProps.put(name, value);
            }
        }

        public long get(String name, long defaultValue) {
            synchronized (mSysProps) {
                final int idx = mSysProps.indexOfKey(name);
                return idx >= 0 ? mSysProps.valueAt(idx) : defaultValue;
            }
        }

        public Handle query(String name) {
            synchronized (mSysProps) {
                return mSysProps.containsKey(name) ? new Handle(name) : null;
            }
        }

        public void clear() {
            synchronized (mSysProps) {
                mSysProps.clear();
            }
        }

        public class Handle {
            private final String mName;

            Handle(String name) {
                mName = name;
            }

            public long getLong(long defaultValue) {
                return get(mName, defaultValue);
            }
        }
    }
}
