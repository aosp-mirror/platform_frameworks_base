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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;

import android.app.Activity;
import android.app.ClientTransactionHandler;
import android.content.Context;
import android.content.res.Configuration;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link ActivityConfigurationChangeItem}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:ActivityConfigurationChangeItemTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ActivityConfigurationChangeItemTest {

    @Mock
    private ClientTransactionHandler mHandler;
    @Mock
    private IBinder mToken;
    @Mock
    private Activity mActivity;
    // Can't mock final class.
    private final Configuration mConfiguration = new Configuration();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testGetContextToUpdate() {
        doReturn(mActivity).when(mHandler).getActivity(mToken);

        final ActivityConfigurationChangeItem item = ActivityConfigurationChangeItem
                .obtain(mToken, mConfiguration);
        final Context context = item.getContextToUpdate(mHandler);

        assertEquals(mActivity, context);
    }
}
