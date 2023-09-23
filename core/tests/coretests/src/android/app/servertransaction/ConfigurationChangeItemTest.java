/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.content.Context.DEVICE_ID_DEFAULT;

import static org.junit.Assert.assertEquals;

import android.app.ActivityThread;
import android.app.ClientTransactionHandler;
import android.content.Context;
import android.content.res.Configuration;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link ConfigurationChangeItem}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:ConfigurationChangeItemTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ConfigurationChangeItemTest {

    @Mock
    private ClientTransactionHandler mHandler;
    // Can't mock final class.
    private final Configuration mConfiguration = new Configuration();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testGetContextToUpdate() {
        final ConfigurationChangeItem item = ConfigurationChangeItem
                .obtain(mConfiguration, DEVICE_ID_DEFAULT);
        final Context context = item.getContextToUpdate(mHandler);

        assertEquals(ActivityThread.currentApplication(), context);
    }
}
