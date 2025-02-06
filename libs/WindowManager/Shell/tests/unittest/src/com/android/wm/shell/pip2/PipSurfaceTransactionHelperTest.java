/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.pip2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit test against {@link PipSurfaceTransactionHelper}.
 */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class PipSurfaceTransactionHelperTest {

    private static final int CORNER_RADIUS = 10;
    private static final int SHADOW_RADIUS = 20;

    @Mock private Context mMockContext;
    @Mock private Resources mMockResources;
    @Mock private SurfaceControl.Transaction mMockTransaction;

    private PipSurfaceTransactionHelper mPipSurfaceTransactionHelper;
    private SurfaceControl mTestLeash;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getDimensionPixelSize(eq(R.dimen.pip_corner_radius)))
                .thenReturn(CORNER_RADIUS);
        when(mMockResources.getDimensionPixelSize(eq(R.dimen.pip_shadow_radius)))
                .thenReturn(SHADOW_RADIUS);
        when(mMockTransaction.setCornerRadius(any(SurfaceControl.class), anyFloat()))
                .thenReturn(mMockTransaction);
        when(mMockTransaction.setShadowRadius(any(SurfaceControl.class), anyFloat()))
                .thenReturn(mMockTransaction);

        mPipSurfaceTransactionHelper = new PipSurfaceTransactionHelper(mMockContext);
        mTestLeash = new SurfaceControl.Builder()
                .setContainerLayer()
                .setName("PipSurfaceTransactionHelperTest")
                .setCallsite("PipSurfaceTransactionHelperTest")
                .build();
    }

    @Test
    public void round_doNotApply_setZeroCornerRadius() {
        mPipSurfaceTransactionHelper.round(mMockTransaction, mTestLeash, false /* apply */);

        verify(mMockTransaction).setCornerRadius(eq(mTestLeash), eq(0f));
    }

    @Test
    public void round_doApply_setExactCornerRadius() {
        mPipSurfaceTransactionHelper.round(mMockTransaction, mTestLeash, true /* apply */);

        verify(mMockTransaction).setCornerRadius(eq(mTestLeash), eq((float) CORNER_RADIUS));
    }

    @Test
    public void shadow_doNotApply_setZeroShadowRadius() {
        mPipSurfaceTransactionHelper.shadow(mMockTransaction, mTestLeash, false /* apply */);

        verify(mMockTransaction).setShadowRadius(eq(mTestLeash), eq(0f));
    }

    @Test
    public void shadow_doApply_setExactShadowRadius() {
        mPipSurfaceTransactionHelper.shadow(mMockTransaction, mTestLeash, true /* apply */);

        verify(mMockTransaction).setShadowRadius(eq(mTestLeash), eq((float) SHADOW_RADIUS));
    }
}
