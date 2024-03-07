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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import com.android.server.wm.TransitionController.OnStartCollect;
import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/**
 * Tests for the {@link DisplayContent} class when FLAG_DEFER_DISPLAY_UPDATES is enabled.
 *
 * Build/Install/Run:
 * atest WmTests:DisplayContentDeferredUpdateTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DisplayContentDeferredUpdateTests extends WindowTestsBase {

    @Override
    protected void onBeforeSystemServicesCreated() {
        // Set other flags to their default values
        mSetFlagsRule.initAllFlagsToReleaseConfigDefault();

        mSetFlagsRule.enableFlags(Flags.FLAG_DEFER_DISPLAY_UPDATES);
    }

    @Before
    public void before() {
        mockTransitionsController(/* enabled= */ true);
        mockRemoteDisplayChangeController();
    }

    @Test
    public void testUpdate_deferrableFieldChangedTransitionStarted_deferrableFieldUpdated() {
        performInitialDisplayUpdate();

        givenDisplayInfo(/* uniqueId= */ "old");
        Runnable onUpdated = mock(Runnable.class);
        mDisplayContent.requestDisplayUpdate(onUpdated);

        // Emulate that collection has started
        captureStartTransitionCollection().getValue().onCollectStarted(/* deferred= */ true);
        verify(onUpdated).run();
        clearInvocations(mDisplayContent.mTransitionController, onUpdated);

        givenDisplayInfo(/* uniqueId= */ "new");
        mDisplayContent.requestDisplayUpdate(onUpdated);
        captureStartTransitionCollection().getValue().onCollectStarted(/* deferred= */ true);
        verify(onUpdated).run();
        assertThat(mDisplayContent.getDisplayInfo().uniqueId).isEqualTo("new");
    }

    @Test
    public void testUpdate_nonDeferrableUpdateAndTransitionDeferred_nonDeferrableFieldUpdated() {
        performInitialDisplayUpdate();

        // Update only color mode (non-deferrable field) and keep the same unique id
        givenDisplayInfo(/* uniqueId= */ "initial_unique_id", /* colorMode= */ 123);
        Runnable onUpdated = mock(Runnable.class);
        mDisplayContent.requestDisplayUpdate(onUpdated);

        verify(onUpdated).run();
        assertThat(mDisplayContent.getDisplayInfo().colorMode).isEqualTo(123);
    }

    @Test
    public void testUpdate_nonDeferrableUpdateTwiceAndTransitionDeferred_fieldHasLatestValue() {
        performInitialDisplayUpdate();

        // Update only color mode (non-deferrable field) and keep the same unique id
        givenDisplayInfo(/* uniqueId= */ "initial_unique_id", /* colorMode= */ 123);
        mDisplayContent.requestDisplayUpdate(mock(Runnable.class));

        assertThat(mDisplayContent.getDisplayInfo().colorMode).isEqualTo(123);
        assertThat(mDisplayContent.getDisplayInfo().uniqueId)
                .isEqualTo("initial_unique_id");

        // Update unique id (deferrable field), keep the same color mode,
        // this update should be deferred
        givenDisplayInfo(/* uniqueId= */ "new_unique_id", /* colorMode= */ 123);
        mDisplayContent.requestDisplayUpdate(mock(Runnable.class));

        assertThat(mDisplayContent.getDisplayInfo().colorMode).isEqualTo(123);
        assertThat(mDisplayContent.getDisplayInfo().uniqueId)
                .isEqualTo("initial_unique_id");

        // Update color mode again and keep the same unique id, color mode update
        // should not be deferred, unique id update is still deferred as transition
        // has not started collecting yet
        givenDisplayInfo(/* uniqueId= */ "new_unique_id", /* colorMode= */ 456);
        Runnable onUpdated = mock(Runnable.class);
        mDisplayContent.requestDisplayUpdate(onUpdated);

        assertThat(mDisplayContent.getDisplayInfo().colorMode).isEqualTo(456);
        assertThat(mDisplayContent.getDisplayInfo().uniqueId)
                .isEqualTo("initial_unique_id");

        // Mark transition as started collected, so pending changes are applied
        captureStartTransitionCollection().getValue().onCollectStarted(/* deferred= */ true);

        // Verify that all fields have the latest values
        verify(onUpdated).run();
        assertThat(mDisplayContent.getDisplayInfo().colorMode).isEqualTo(456);
        assertThat(mDisplayContent.getDisplayInfo().uniqueId).isEqualTo("new_unique_id");
    }

    @Test
    public void testUpdate_deferrableFieldUpdatedTransitionPending_fieldNotUpdated() {
        performInitialDisplayUpdate();
        givenDisplayInfo(/* uniqueId= */ "old");
        Runnable onUpdated = mock(Runnable.class);
        mDisplayContent.requestDisplayUpdate(onUpdated);
        captureStartTransitionCollection().getValue().onCollectStarted(/* deferred= */ true);
        verify(onUpdated).run();
        clearInvocations(mDisplayContent.mTransitionController, onUpdated);

        givenDisplayInfo(/* uniqueId= */ "new");
        mDisplayContent.requestDisplayUpdate(onUpdated);

        captureStartTransitionCollection(); // do not continue by not starting the collection
        verify(onUpdated, never()).run();
        assertThat(mDisplayContent.getDisplayInfo().uniqueId).isEqualTo("old");
    }

    @Test
    public void testTwoDisplayUpdates_transitionStarted_displayUpdated() {
        performInitialDisplayUpdate();
        givenDisplayInfo(/* uniqueId= */ "old");
        Runnable onUpdated = mock(Runnable.class);
        mDisplayContent.requestDisplayUpdate(onUpdated);
        captureStartTransitionCollection().getValue()
                .onCollectStarted(/* deferred= */ true);
        verify(onUpdated).run();
        clearInvocations(mDisplayContent.mTransitionController, onUpdated);

        // Perform two display updates while WM is 'busy'
        givenDisplayInfo(/* uniqueId= */ "new1");
        Runnable onUpdated1 = mock(Runnable.class);
        mDisplayContent.requestDisplayUpdate(onUpdated1);
        givenDisplayInfo(/* uniqueId= */ "new2");
        Runnable onUpdated2 = mock(Runnable.class);
        mDisplayContent.requestDisplayUpdate(onUpdated2);

        // Continue with the first update
        captureStartTransitionCollection().getAllValues().get(0)
                .onCollectStarted(/* deferred= */ true);
        verify(onUpdated1).run();
        verify(onUpdated2, never()).run();
        assertThat(mDisplayContent.getDisplayInfo().uniqueId).isEqualTo("new1");

        // Continue with the second update
        captureStartTransitionCollection().getAllValues().get(1)
                .onCollectStarted(/* deferred= */ true);
        verify(onUpdated2).run();
        assertThat(mDisplayContent.getDisplayInfo().uniqueId).isEqualTo("new2");
    }

    private void mockTransitionsController(boolean enabled) {
        spyOn(mDisplayContent.mTransitionController);
        when(mDisplayContent.mTransitionController.isShellTransitionsEnabled()).thenReturn(enabled);
        doReturn(true).when(mDisplayContent.mTransitionController).startCollectOrQueue(any(),
                any());
    }

    private void mockRemoteDisplayChangeController() {
        spyOn(mDisplayContent.mRemoteDisplayChangeController);
        doReturn(true).when(mDisplayContent.mRemoteDisplayChangeController)
                .performRemoteDisplayChange(anyInt(), anyInt(), any(), any());
    }

    private ArgumentCaptor<OnStartCollect> captureStartTransitionCollection() {
        ArgumentCaptor<OnStartCollect> callbackCaptor =
                ArgumentCaptor.forClass(OnStartCollect.class);
        verify(mDisplayContent.mTransitionController, atLeast(1)).startCollectOrQueue(any(),
                callbackCaptor.capture());
        return callbackCaptor;
    }

    private void givenDisplayInfo(String uniqueId) {
        givenDisplayInfo(uniqueId, /* colorMode= */ 0);
    }

    private void givenDisplayInfo(String uniqueId, int colorMode) {
        spyOn(mDisplayContent.mDisplay);
        doAnswer(invocation -> {
            DisplayInfo info = invocation.getArgument(0);
            info.uniqueId = uniqueId;
            info.colorMode = colorMode;
            return null;
        }).when(mDisplayContent.mDisplay).getDisplayInfo(any());
    }

    private void performInitialDisplayUpdate() {
        givenDisplayInfo(/* uniqueId= */ "initial_unique_id", /* colorMode= */ 0);
        Runnable onUpdated = mock(Runnable.class);
        mDisplayContent.requestDisplayUpdate(onUpdated);
    }
}
