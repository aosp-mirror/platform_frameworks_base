/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.dreams;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class SmartSpaceComplicationTest extends SysuiTestCase {
    @Mock
    private Context mContext;

    @Mock
    private LockscreenSmartspaceController mSmartspaceController;

    @Mock
    private DreamOverlayStateController mDreamOverlayStateController;

    @Mock
    private SmartSpaceComplication mComplication;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Ensures {@link SmartSpaceComplication} is only registered when it is available.
     */
    @Test
    public void testAvailability() {
        when(mSmartspaceController.isEnabled()).thenReturn(false);

        final SmartSpaceComplication.Registrant registrant = new SmartSpaceComplication.Registrant(
                mContext,
                mDreamOverlayStateController,
                mComplication,
                mSmartspaceController);
        registrant.start();
        verify(mDreamOverlayStateController, never()).addComplication(any());

        when(mSmartspaceController.isEnabled()).thenReturn(true);
        registrant.start();
        verify(mDreamOverlayStateController).addComplication(eq(mComplication));
    }
}
