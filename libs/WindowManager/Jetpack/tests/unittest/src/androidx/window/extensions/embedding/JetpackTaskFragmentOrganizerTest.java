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

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static androidx.window.extensions.embedding.EmbeddingTestUtils.createTestTaskContainer;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentTransaction;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;

/**
 * Test class for {@link JetpackTaskFragmentOrganizer}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:JetpackTaskFragmentOrganizerTest
 */
// Suppress GuardedBy warning on unit tests
@SuppressWarnings("GuardedBy")
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class JetpackTaskFragmentOrganizerTest {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    private WindowContainerTransaction mTransaction;
    @Mock
    private JetpackTaskFragmentOrganizer.TaskFragmentCallback mCallback;
    @Mock
    private SplitController mSplitController;
    @Mock
    private Handler mHandler;
    private JetpackTaskFragmentOrganizer mOrganizer;

    @Before
    public void setup() {
        mOrganizer = new JetpackTaskFragmentOrganizer(Runnable::run, mCallback);
        mOrganizer.registerOrganizer();
        spyOn(mOrganizer);
        doReturn(mHandler).when(mSplitController).getHandler();
    }

    @Test
    public void testUnregisterOrganizer() {
        mOrganizer.overrideSplitAnimation();
        mOrganizer.unregisterOrganizer();

        verify(mOrganizer).unregisterRemoteAnimations();
    }

    @Test
    public void testOverrideSplitAnimation() {
        assertNull(mOrganizer.mAnimationController);

        mOrganizer.overrideSplitAnimation();

        assertNotNull(mOrganizer.mAnimationController);
        verify(mOrganizer).registerRemoteAnimations(mOrganizer.mAnimationController.mDefinition);
    }

    @Test
    public void testExpandTaskFragment() {
        final TaskContainer taskContainer = createTestTaskContainer();
        doReturn(taskContainer).when(mSplitController).getTaskContainer(anyInt());
        final TaskFragmentContainer container =  new TaskFragmentContainer.Builder(mSplitController,
                taskContainer.getTaskId(), null /* activityInTask */)
                .setPendingAppearedIntent(new Intent())
                .build();
        final TaskFragmentInfo info = createMockInfo(container);
        mOrganizer.mFragmentInfos.put(container.getTaskFragmentToken(), info);
        container.setInfo(mTransaction, info);

        mOrganizer.expandTaskFragment(mTransaction, container);

        verify(mTransaction).setWindowingMode(container.getInfo().getToken(),
                WINDOWING_MODE_UNDEFINED);
    }

    @Test
    public void testOnTransactionReady() {
        final TaskFragmentTransaction transaction = new TaskFragmentTransaction();
        mOrganizer.onTransactionReady(transaction);

        verify(mCallback).onTransactionReady(transaction);
    }

    private TaskFragmentInfo createMockInfo(TaskFragmentContainer container) {
        return new TaskFragmentInfo(container.getTaskFragmentToken(),
                mock(WindowContainerToken.class), new Configuration(), 0 /* runningActivityCount */,
                false /* isVisible */, new ArrayList<>(), new ArrayList<>(), new Point(),
                false /* isTaskClearedForReuse */, false /* isTaskFragmentClearedForPip */,
                false /* isClearedForReorderActivityToFront */, new Point());
    }
}
