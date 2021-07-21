/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import android.content.Intent;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.wm.ActivityStarter.Factory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link ActivityStartController} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityStartControllerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityStartControllerTests extends WindowTestsBase {
    private ActivityStartController mController;
    private Factory mFactory;
    private ActivityStarter mStarter;

    @Before
    public void setUp() throws Exception {
        mFactory = mock(Factory.class);
        mController = new ActivityStartController(mAtm, mAtm.mTaskSupervisor, mFactory);
        mStarter = spy(new ActivityStarter(mController, mAtm,
                mAtm.mTaskSupervisor, mock(ActivityStartInterceptor.class)));
        doReturn(mStarter).when(mFactory).obtain();
    }

    /**
     * Ensures instances are recycled after execution.
     */
    @Test
    public void testRecycling() {
        final Intent intent = new Intent();
        final ActivityStarter optionStarter = new ActivityStarter(mController, mAtm,
                mAtm.mTaskSupervisor, mock(ActivityStartInterceptor.class));
        optionStarter
                .setIntent(intent)
                .setReason("Test")
                .execute();
        verify(mFactory, times(1)).recycle(eq(optionStarter));
    }
}
