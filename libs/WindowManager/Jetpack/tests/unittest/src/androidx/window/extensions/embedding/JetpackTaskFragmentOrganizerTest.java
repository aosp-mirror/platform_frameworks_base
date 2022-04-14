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

package androidx.window.extensions.embedding;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link JetpackTaskFragmentOrganizer}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:JetpackTaskFragmentOrganizerTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class JetpackTaskFragmentOrganizerTest {
    private static final int TASK_ID = 10;

    @Mock
    private JetpackTaskFragmentOrganizer.TaskFragmentCallback mCallback;
    private JetpackTaskFragmentOrganizer mOrganizer;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mOrganizer = new JetpackTaskFragmentOrganizer(Runnable::run, mCallback);
        mOrganizer.registerOrganizer();
        spyOn(mOrganizer);
    }

    @Test
    public void testUnregisterOrganizer() {
        mOrganizer.startOverrideSplitAnimation(TASK_ID);
        mOrganizer.startOverrideSplitAnimation(TASK_ID + 1);
        mOrganizer.unregisterOrganizer();

        verify(mOrganizer).unregisterRemoteAnimations(TASK_ID);
        verify(mOrganizer).unregisterRemoteAnimations(TASK_ID + 1);
    }

    @Test
    public void testStartOverrideSplitAnimation() {
        assertNull(mOrganizer.mAnimationController);

        mOrganizer.startOverrideSplitAnimation(TASK_ID);

        assertNotNull(mOrganizer.mAnimationController);
        verify(mOrganizer).registerRemoteAnimations(TASK_ID,
                mOrganizer.mAnimationController.mDefinition);
    }

    @Test
    public void testStopOverrideSplitAnimation() {
        mOrganizer.stopOverrideSplitAnimation(TASK_ID);

        verify(mOrganizer, never()).unregisterRemoteAnimations(anyInt());

        mOrganizer.startOverrideSplitAnimation(TASK_ID);
        mOrganizer.stopOverrideSplitAnimation(TASK_ID);

        verify(mOrganizer).unregisterRemoteAnimations(TASK_ID);
    }
}
