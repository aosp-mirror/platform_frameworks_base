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

package com.android.systemui.qs.customize;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.qs.QSHost;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

@RunWith(AndroidJUnit4.class)
@RunWithLooper
@SmallTest
public class TileAdapterTest extends SysuiTestCase {

    private TileAdapter mTileAdapter;
    @Mock
    private QSHost mQSHost;

    @Before
    public void setup() throws Exception {
        FakeFeatureFlags fakeFeatureFlags = new FakeFeatureFlags();
        fakeFeatureFlags.set(Flags.LOCKSCREEN_ENABLE_LANDSCAPE, false);

        MockitoAnnotations.initMocks(this);

        TestableLooper.get(this).runWithLooper(() -> mTileAdapter =
                new TileAdapter(mContext, mQSHost, new UiEventLoggerFake(), fakeFeatureFlags));
    }

    @Test
    public void testResetNotifiesHost() {
        mTileAdapter.resetTileSpecs(Collections.emptyList());
        verify(mQSHost).changeTilesByUser(any(), any());
    }
}
