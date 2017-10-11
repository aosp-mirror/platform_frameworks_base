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

import static junit.framework.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.systemui.Dependency;
import android.testing.AndroidTestingRunner;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.QSTileHost;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.os.Message;
import android.test.suitebuilder.annotation.SmallTest;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class TileQueryHelperTest extends SysuiTestCase {
    private TestableLooper mBGLooper;
    private Runnable mLastCallback;

    @Before
    public void setup() {
        mBGLooper = TestableLooper.get(this);
        mDependency.injectTestDependency(Dependency.BG_LOOPER, mBGLooper.getLooper());
    }

    @Test
    public void testCompletionCalled() {
        QSTileHost mockHost = mock(QSTileHost.class);
        TileAdapter mockAdapter = mock(TileAdapter.class);
        Runnable mockCompletion = mock(Runnable.class);
        new TileQueryHelper(mContext, mockHost, mockAdapter, mockCompletion);
        mBGLooper.processAllMessages();
        verify(mockCompletion).run();
    }

    @Test
    public void testCompletionCalledAfterTilesFetched() {
        QSTile mockTile = mock(QSTile.class);
        State mockState = mock(State.class);
        when(mockState.copy()).thenReturn(mockState);
        when(mockTile.getState()).thenReturn(mockState);
        when(mockTile.isAvailable()).thenReturn(true);

        QSTileHost mockHost = mock(QSTileHost.class);
        when(mockHost.createTile(any())).thenReturn(mockTile);

        mBGLooper.setMessageHandler((Message m) -> {
            mLastCallback = m.getCallback();
            return true;
        });
        TileAdapter mockAdapter = mock(TileAdapter.class);
        Runnable mockCompletion = mock(Runnable.class);
        new TileQueryHelper(mContext, mockHost, mockAdapter, mockCompletion);

        // Verify that the last thing in the queue was our callback
        mBGLooper.processAllMessages();
        assertEquals(mockCompletion, mLastCallback);
    }
}
