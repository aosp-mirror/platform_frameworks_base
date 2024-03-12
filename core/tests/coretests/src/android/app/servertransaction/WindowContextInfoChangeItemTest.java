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

import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.app.ClientTransactionHandler;
import android.content.Context;
import android.content.res.Configuration;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.window.WindowContext;
import android.window.WindowContextInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link WindowContextInfoChangeItem}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:WindowContextInfoChangeItemTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class WindowContextInfoChangeItemTest {

    @Mock
    private ClientTransactionHandler mHandler;
    @Mock
    private PendingTransactionActions mPendingActions;
    @Mock
    private IBinder mClientToken;
    @Mock
    private WindowContext mWindowContext;
    // Can't mock final class.
    private final Configuration mConfiguration = new Configuration();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testExecute() {
        final WindowContextInfoChangeItem item = WindowContextInfoChangeItem
                .obtain(mClientToken, mConfiguration, DEFAULT_DISPLAY);
        item.execute(mHandler, mPendingActions);

        verify(mHandler).handleWindowContextInfoChanged(mClientToken,
                new WindowContextInfo(mConfiguration, DEFAULT_DISPLAY));
    }

    @Test
    public void testGetContextToUpdate() {
        doReturn(mWindowContext).when(mHandler).getWindowContext(mClientToken);

        final WindowContextInfoChangeItem item = WindowContextInfoChangeItem
                .obtain(mClientToken, mConfiguration, DEFAULT_DISPLAY);
        final Context context = item.getContextToUpdate(mHandler);

        assertEquals(mWindowContext, context);
    }
}
