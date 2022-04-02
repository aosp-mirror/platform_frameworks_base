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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;

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
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TaskFragmentAnimationControllerTest {
    private static final int TASK_ID = 10;

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
        mAnimationController.registerRemoteAnimations(TASK_ID);

        verify(mOrganizer).registerRemoteAnimations(TASK_ID, mAnimationController.mDefinition);

        mAnimationController.registerRemoteAnimations(TASK_ID);

        // No extra call if it has been registered.
        verify(mOrganizer).registerRemoteAnimations(TASK_ID, mAnimationController.mDefinition);
    }

    @Test
    public void testUnregisterRemoteAnimations() {
        mAnimationController.unregisterRemoteAnimations(TASK_ID);

        // No call if it is not registered.
        verify(mOrganizer, never()).unregisterRemoteAnimations(anyInt());

        mAnimationController.registerRemoteAnimations(TASK_ID);
        mAnimationController.unregisterRemoteAnimations(TASK_ID);

        verify(mOrganizer).unregisterRemoteAnimations(TASK_ID);

        mAnimationController.unregisterRemoteAnimations(TASK_ID);

        // No extra call if it has been unregistered.
        verify(mOrganizer).unregisterRemoteAnimations(TASK_ID);
    }

    @Test
    public void testUnregisterAllRemoteAnimations() {
        mAnimationController.registerRemoteAnimations(TASK_ID);
        mAnimationController.registerRemoteAnimations(TASK_ID + 1);
        mAnimationController.unregisterAllRemoteAnimations();

        verify(mOrganizer).unregisterRemoteAnimations(TASK_ID);
        verify(mOrganizer).unregisterRemoteAnimations(TASK_ID + 1);
    }
}
