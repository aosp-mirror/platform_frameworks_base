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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.IntConsumer;

/**
 * Tests for {@link ClientTransactionListenerController}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:ClientTransactionListenerControllerTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ClientTransactionListenerControllerTest {
    @Mock
    private IntConsumer mDisplayChangeListener;

    private ClientTransactionListenerController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mController = spy(ClientTransactionListenerController.createInstanceForTesting());
        doReturn(true).when(mController).isSyncWindowConfigUpdateFlagEnabled();
    }

    @Test
    public void testRegisterDisplayChangeListener() {
        mController.registerDisplayChangeListener(mDisplayChangeListener, Runnable::run);

        mController.onDisplayChanged(123);

        verify(mDisplayChangeListener).accept(123);

        clearInvocations(mDisplayChangeListener);
        mController.unregisterDisplayChangeListener(mDisplayChangeListener);

        mController.onDisplayChanged(321);

        verify(mDisplayChangeListener, never()).accept(anyInt());
    }
}
