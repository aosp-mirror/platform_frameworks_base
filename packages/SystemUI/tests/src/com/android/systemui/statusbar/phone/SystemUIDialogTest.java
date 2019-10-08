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

package com.android.systemui.statusbar.phone;

import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class SystemUIDialogTest extends SysuiTestCase {

    private SystemUIDialog mDialog;

    Context mContextSpy;

    @Before
    public void setup() {
        mContextSpy = spy(mContext);
        mDialog = new SystemUIDialog(mContextSpy);
    }

    @Test
    public void testRegisterReceiver() {
        final ArgumentCaptor<IntentFilter> intentFilterCaptor =
                ArgumentCaptor.forClass(IntentFilter.class);

        verify(mContextSpy).registerReceiverAsUser(any(), any(),
                intentFilterCaptor.capture(), any(), any());

        assertTrue(intentFilterCaptor.getValue().hasAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
    }

}
