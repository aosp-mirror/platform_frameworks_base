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

package com.android.systemui.communal;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class CommunalStateControllerTest extends SysuiTestCase {
    @Mock
    private CommunalStateController.Callback mCallback;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testDefaultCommunalViewShowingState() {
        // The state controller should report the communal view as not showing by default.
        final CommunalStateController stateController = new CommunalStateController();
        assertThat(stateController.getCommunalViewShowing()).isFalse();
    }

    @Test
    public void testNotifyCommunalSurfaceShow() {
        final CommunalStateController stateController = new CommunalStateController();
        stateController.addCallback(mCallback);

        // Verify setting communal view to showing propagates to callback.
        stateController.setCommunalViewShowing(true);
        verify(mCallback).onCommunalViewShowingChanged();
        assertThat(stateController.getCommunalViewShowing()).isTrue();

        clearInvocations(mCallback);

        // Verify setting communal view to not showing propagates to callback.
        stateController.setCommunalViewShowing(false);
        verify(mCallback).onCommunalViewShowingChanged();
        assertThat(stateController.getCommunalViewShowing()).isFalse();
    }

    @Test
    public void testCallbackRegistration() {
        final CommunalStateController stateController = new CommunalStateController();
        stateController.addCallback(mCallback);

        // Verify setting communal view to showing propagates to callback.
        stateController.setCommunalViewShowing(true);
        verify(mCallback).onCommunalViewShowingChanged();

        clearInvocations(mCallback);

        stateController.removeCallback(mCallback);
        clearInvocations(mCallback);

        // Verify callback not invoked after removing from state controller.
        stateController.setCommunalViewShowing(false);
        verify(mCallback, never()).onCommunalViewShowingChanged();
    }
}
