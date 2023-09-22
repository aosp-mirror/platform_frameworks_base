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

import static org.mockito.Mockito.verify;

import android.app.ClientTransactionHandler;
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
 * Tests for {@link WindowContextWindowRemovalItem}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:WindowContextWindowRemovalItemTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class WindowContextWindowRemovalItemTest {

    @Mock
    private ClientTransactionHandler mHandler;
    @Mock
    private PendingTransactionActions mPendingActions;
    @Mock
    private IBinder mClientToken;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testExecute() {
        final WindowContextWindowRemovalItem item = WindowContextWindowRemovalItem.obtain(
                mClientToken);
        item.execute(mHandler, mPendingActions);

        verify(mHandler).handleWindowContextWindowRemoval(mClientToken);
    }
}
