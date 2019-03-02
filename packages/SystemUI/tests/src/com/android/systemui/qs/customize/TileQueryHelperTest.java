/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.qs.QSTileHost;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class TileQueryHelperTest extends SysuiTestCase {
    @Mock private TileQueryHelper.TileStateListener mListener;
    @Mock private QSTileHost mQSTileHost;

    private TestableLooper mBGLooper;

    private TileQueryHelper mTileQueryHelper;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mBGLooper = TestableLooper.get(this);
        mDependency.injectTestDependency(Dependency.BG_LOOPER, mBGLooper.getLooper());
        mTileQueryHelper = new TileQueryHelper(mContext, mListener);
    }

    @Test
    public void testIsFinished_falseBeforeQuerying() {
        assertFalse(mTileQueryHelper.isFinished());
    }

    @Test
    public void testIsFinished_trueAfterQuerying() {
        mTileQueryHelper.queryTiles(mQSTileHost);

        mBGLooper.processAllMessages();
        waitForIdleSync(Dependency.get(Dependency.MAIN_HANDLER));

        assertTrue(mTileQueryHelper.isFinished());
    }

    @Test
    public void testQueryTiles_callsListenerTwice() {
        mTileQueryHelper.queryTiles(mQSTileHost);

        mBGLooper.processAllMessages();
        waitForIdleSync(Dependency.get(Dependency.MAIN_HANDLER));

        verify(mListener, times(2)).onTilesChanged(any());
    }

    @Test
    public void testQueryTiles_isFinishedFalseOnListenerCalls_thenTrueAfterCompletion() {
        doAnswer(invocation -> {
            assertFalse(mTileQueryHelper.isFinished());
            return null;
        }).when(mListener).onTilesChanged(any());

        mTileQueryHelper.queryTiles(mQSTileHost);

        mBGLooper.processAllMessages();
        waitForIdleSync(Dependency.get(Dependency.MAIN_HANDLER));

        assertTrue(mTileQueryHelper.isFinished());
    }
}
