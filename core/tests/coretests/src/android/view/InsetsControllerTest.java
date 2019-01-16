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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import static org.mockito.Mockito.mock;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@FlakyTest(detail = "Promote once confirmed non-flaky")
@RunWith(AndroidJUnit4.class)
public class InsetsControllerTest {

    private InsetsController mController = new InsetsController(mock(ViewRootImpl.class));

    private SurfaceSession mSession = new SurfaceSession();
    private SurfaceControl mLeash;

    @Before
    public void setup() {
        mLeash = new SurfaceControl.Builder(mSession)
                .setName("testSurface")
                .build();
    }

    @Test
    public void testControlsChanged() {
        InsetsSourceControl control = new InsetsSourceControl(TYPE_TOP_BAR, mLeash);
        mController.onControlsChanged(new InsetsSourceControl[] { control });
        assertEquals(mLeash,
                mController.getSourceConsumer(TYPE_TOP_BAR).getControl().getLeash());
    }

    @Test
    public void testControlsRevoked() {
        InsetsSourceControl control = new InsetsSourceControl(TYPE_TOP_BAR, mLeash);
        mController.onControlsChanged(new InsetsSourceControl[] { control });
        mController.onControlsChanged(new InsetsSourceControl[0]);
        assertNull(mController.getSourceConsumer(TYPE_TOP_BAR).getControl());
    }
}
