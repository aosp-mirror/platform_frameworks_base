/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.app.servertransaction.TestUtils.config;

import static org.junit.Assert.assertNotSame;

import android.annotation.NonNull;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.function.Supplier;

/**
 * Tests for {@link ObjectPool}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:ObjectPoolTests
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ObjectPoolTests {

    @Rule
    public final MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private IBinder mActivityToken;

    // 1. Check if two obtained objects from pool are not the same.
    // 2. Check if the state of the object is cleared after recycling.
    // 3. Check if the same object is obtained from pool after recycling.

    @Test
    public void testRecycleConfigurationChangeItem() {
        testRecycle(() -> ConfigurationChangeItem.obtain(config(), 1));
    }

    private void testRecycle(@NonNull Supplier<? extends ObjectPoolItem> obtain) {
        // Reuse the same object after recycle.
        final ObjectPoolItem item = obtain.get();
        item.recycle();
        final ObjectPoolItem item2 = obtain.get();

        assertNotSame(item, item2);  // Different instance.

        // Create new object when the pool is empty.
        final ObjectPoolItem item3 = obtain.get();

        assertNotSame(item, item3);
    }
}
