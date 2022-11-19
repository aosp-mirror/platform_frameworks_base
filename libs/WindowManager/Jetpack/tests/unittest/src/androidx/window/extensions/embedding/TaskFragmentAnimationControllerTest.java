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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.Mockito.never;

import android.platform.test.annotations.Presubmit;
import android.window.TaskFragmentOrganizer;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link TaskFragmentAnimationController}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:TaskFragmentAnimationControllerTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TaskFragmentAnimationControllerTest {
    @Mock
    private TaskFragmentOrganizer mOrganizer;
    private TaskFragmentAnimationController mAnimationController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mAnimationController = new TaskFragmentAnimationController(mOrganizer);
    }

    @Test
    public void testRegisterRemoteAnimations() {
        mAnimationController.registerRemoteAnimations();

        verify(mOrganizer).registerRemoteAnimations(mAnimationController.mDefinition);

        mAnimationController.registerRemoteAnimations();

        // No extra call if it has been registered.
        verify(mOrganizer).registerRemoteAnimations(mAnimationController.mDefinition);
    }

    @Test
    public void testUnregisterRemoteAnimations() {
        mAnimationController.unregisterRemoteAnimations();

        // No call if it is not registered.
        verify(mOrganizer, never()).unregisterRemoteAnimations();

        mAnimationController.registerRemoteAnimations();
        mAnimationController.unregisterRemoteAnimations();

        verify(mOrganizer).unregisterRemoteAnimations();

        mAnimationController.unregisterRemoteAnimations();

        // No extra call if it has been unregistered.
        verify(mOrganizer).unregisterRemoteAnimations();
    }
}
