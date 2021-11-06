/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collection;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamOverlayStateControllerTest extends SysuiTestCase {
    @Mock
    DreamOverlayStateController.Callback mCallback;

    @Mock
    OverlayProvider mProvider;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testCallback() {
        final DreamOverlayStateController stateController = new DreamOverlayStateController();
        stateController.addCallback(mCallback);

        // Add overlay and verify callback is notified.
        final DreamOverlayStateController.OverlayToken token =
                stateController.addOverlay(mProvider);

        verify(mCallback, times(1)).onOverlayChanged();

        final Collection<OverlayProvider> providers = stateController.getOverlays();
        assertEquals(providers.size(), 1);
        assertTrue(providers.contains(mProvider));

        clearInvocations(mCallback);

        // Remove overlay and verify callback is notified.
        stateController.removeOverlay(token);
        verify(mCallback, times(1)).onOverlayChanged();
        assertTrue(providers.isEmpty());
    }

    @Test
    public void testNotifyOnCallbackAdd() {
        final DreamOverlayStateController stateController = new DreamOverlayStateController();
        final DreamOverlayStateController.OverlayToken token =
                stateController.addOverlay(mProvider);

        // Verify callback occurs on add when an overlay is already present.
        stateController.addCallback(mCallback);
        verify(mCallback, times(1)).onOverlayChanged();
    }
}
