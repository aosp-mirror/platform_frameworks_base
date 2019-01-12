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

package android.view;

import static android.view.InsetsState.TYPE_TOP_BAR;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.graphics.Point;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl.Transaction;

import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@FlakyTest(detail = "Promote once confirmed non-flaky")
@RunWith(AndroidJUnit4.class)
public class InsetsSourceConsumerTest {

    private InsetsSourceConsumer mConsumer;

    private SurfaceSession mSession = new SurfaceSession();
    private SurfaceControl mLeash;
    @Mock Transaction mMockTransaction;
    @Mock InsetsController mMockController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mLeash = new SurfaceControl.Builder(mSession)
                .setName("testSurface")
                .build();
        mConsumer = new InsetsSourceConsumer(TYPE_TOP_BAR, new InsetsState(),
                () -> mMockTransaction, mMockController);
        mConsumer.setControl(new InsetsSourceControl(TYPE_TOP_BAR, mLeash, new Point()));
    }

    @Test
    public void testHide() {
        mConsumer.hide();
        verify(mMockTransaction).hide(eq(mLeash));
    }

    @Test
    public void testShow() {
        mConsumer.hide();
        mConsumer.show();
        verify(mMockTransaction, atLeastOnce()).show(eq(mLeash));
    }

    @Test
    public void testRestore() {
        mConsumer.setControl(null);
        reset(mMockTransaction);
        mConsumer.hide();
        verifyZeroInteractions(mMockTransaction);
        mConsumer.setControl(new InsetsSourceControl(TYPE_TOP_BAR, mLeash, new Point()));
        verify(mMockTransaction).hide(eq(mLeash));
    }
}
