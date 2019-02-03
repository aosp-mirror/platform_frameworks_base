/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.view.WindowManager.TRANSIT_TASK_CHANGE_WINDOWING_MODE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.os.IBinder;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationTarget;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for change transitions
 *
 * Build/Install/Run:
 *  atest WmTests:AppChangeTransitionTests
 */
@FlakyTest(detail = "Promote when shown to be stable.")
@SmallTest
public class AppChangeTransitionTests extends WindowTestsBase {

    private TaskStack mStack;
    private Task mTask;
    private WindowTestUtils.TestAppWindowToken mToken;

    @Before
    public void setUp() throws Exception {
        mStack = createTaskStackOnDisplay(mDisplayContent);
        mTask = createTaskInStack(mStack, 0 /* userId */);
        mToken = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        mToken.mSkipOnParentChanged = false;

        mTask.addChild(mToken, 0);
    }

    class TestRemoteAnimationRunner implements IRemoteAnimationRunner {
        @Override
        public void onAnimationStart(RemoteAnimationTarget[] apps,
                IRemoteAnimationFinishedCallback finishedCallback) {
            for (RemoteAnimationTarget target : apps) {
                assertNotNull(target.startBounds);
            }
            try {
                finishedCallback.onAnimationFinished();
            } catch (Exception e) {
                throw new RuntimeException("Something went wrong");
            }
        }

        @Override
        public void onAnimationCancelled() {
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }

    @Test
    public void testModeChangeRemoteAnimatorNoSnapshot() {
        RemoteAnimationDefinition definition = new RemoteAnimationDefinition();
        RemoteAnimationAdapter adapter =
                new RemoteAnimationAdapter(new TestRemoteAnimationRunner(), 10, 1, false);
        definition.addRemoteAnimation(TRANSIT_TASK_CHANGE_WINDOWING_MODE, adapter);
        mDisplayContent.registerRemoteAnimations(definition);

        mTask.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(1, mDisplayContent.mChangingApps.size());
        assertNull(mToken.getThumbnail());

        waitUntilHandlersIdle();
        mToken.removeImmediately();
    }
}
