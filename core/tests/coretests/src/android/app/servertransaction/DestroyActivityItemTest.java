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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link DestroyActivityItem}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:DestroyActivityItemTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class DestroyActivityItemTest {

    @Mock
    private ClientTransactionHandler mHandler;
    @Mock
    private PendingTransactionActions mPendingActions;
    @Mock
    private IBinder mActivityToken;

    // Can't mock final class.
    private ActivityClientRecord mActivityClientRecord;

    private ArrayMap<IBinder, DestroyActivityItem> mActivitiesToBeDestroyed;
    private DestroyActivityItem mItem;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mItem = DestroyActivityItem.obtain(
                mActivityToken, false /* finished */, 123 /* configChanges */);
        mActivityClientRecord = new ActivityClientRecord();
        mActivitiesToBeDestroyed = new ArrayMap<>();

        doReturn(mActivitiesToBeDestroyed).when(mHandler).getActivitiesToBeDestroyed();
    }

    @Test
    public void testPreExecute() {
        mItem.preExecute(mHandler);

        assertEquals(1, mActivitiesToBeDestroyed.size());
        assertEquals(mItem, mActivitiesToBeDestroyed.get(mActivityToken));
    }

    @Test
    public void testPostExecute() {
        mItem.preExecute(mHandler);
        mItem.postExecute(mHandler, mPendingActions);

        assertTrue(mActivitiesToBeDestroyed.isEmpty());
    }

    @Test
    public void testExecute() {
        mItem.execute(mHandler, mActivityClientRecord, mPendingActions);

        verify(mHandler).handleDestroyActivity(eq(mActivityClientRecord), eq(false) /* finishing */,
                eq(123) /* configChanges */, eq(false) /* getNonConfigInstance */, any());
    }
}
