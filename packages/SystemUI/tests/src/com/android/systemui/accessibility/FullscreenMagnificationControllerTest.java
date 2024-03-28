/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.SurfaceControlViewHost;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.window.InputTransferToken;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Supplier;

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class FullscreenMagnificationControllerTest extends SysuiTestCase {

    private FullscreenMagnificationController mFullscreenMagnificationController;
    private SurfaceControlViewHost mSurfaceControlViewHost;

    @Before
    public void setUp() {
        getInstrumentation().runOnMainSync(() -> mSurfaceControlViewHost =
                new SurfaceControlViewHost(mContext, mContext.getDisplay(),
                        new InputTransferToken(), "FullscreenMagnification"));

        Supplier<SurfaceControlViewHost> scvhSupplier = () -> mSurfaceControlViewHost;

        mFullscreenMagnificationController = new FullscreenMagnificationController(
                mContext,
                mContext.getSystemService(AccessibilityManager.class),
                mContext.getSystemService(WindowManager.class),
                scvhSupplier);
    }

    @After
    public void tearDown() {
        getInstrumentation().runOnMainSync(
                () -> mFullscreenMagnificationController
                        .onFullscreenMagnificationActivationChanged(false));
    }

    @Test
    public void onFullscreenMagnificationActivationChange_activated_visibleBorder() {
        getInstrumentation().runOnMainSync(
                () -> mFullscreenMagnificationController
                        .onFullscreenMagnificationActivationChanged(true)
        );

        // Wait for Rects updated.
        waitForIdleSync();
        assertThat(mSurfaceControlViewHost.getView().isVisibleToUser()).isTrue();
    }

    @Test
    public void onFullscreenMagnificationActivationChange_deactivated_invisibleBorder() {
        getInstrumentation().runOnMainSync(
                () -> {
                    mFullscreenMagnificationController
                            .onFullscreenMagnificationActivationChanged(true);
                    mFullscreenMagnificationController
                            .onFullscreenMagnificationActivationChanged(false);
                }
        );

        assertThat(mSurfaceControlViewHost.getView()).isNull();
    }

}
