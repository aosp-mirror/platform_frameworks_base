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
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.any;

import android.content.ComponentName;
import android.content.pm.PackageManagerInternal;
import android.graphics.Rect;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.LocalServices;
import com.android.server.pm.PackageList;
import com.android.server.wm.LaunchParamsController.LaunchParams;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Unit tests for {@link LaunchParamsPersister}.
 *
 * Build/Install/Run:
 *   atest WmTests:LaunchParamsPersisterTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class LaunchParamsPersisterTests extends ActivityTestsBase {
    private static final int TEST_USER_ID = 3;
    private static final int ALTERNATIVE_USER_ID = 0;
    private static final ComponentName TEST_COMPONENT =
            ComponentName.createRelative("com.android.foo", ".BarActivity");
    private static final ComponentName ALTERNATIVE_COMPONENT =
            ComponentName.createRelative("com.android.foo", ".AlternativeBarActivity");
    private static final String TEST_WINDOW_LAYOUT_AFFINITY = "135:.Affinity";
    private static final String TEST_ALTERNATIVE_WINDOW_LAYOUT_AFFINITY =
            "246:.AlternativeAffinity";
    private static final String TEST_DIFFERENT_AFFINITY_WITH_SAME_UID = "135:.DifferentAffinity";

    private static final int TEST_WINDOWING_MODE = WINDOWING_MODE_FREEFORM;
    private static final Rect TEST_BOUNDS = new Rect(100, 200, 300, 400);

    private static int sNextUniqueId;

    private TestPersisterQueue mPersisterQueue;
    private File mFolder;
    private DisplayContent mTestDisplay;
    private String mDisplayUniqueId;
    private Task mTestTask;
    private Task mTaskWithDifferentUser;
    private Task mTaskWithDifferentComponent;
    private PackageManagerInternal mMockPmi;
    private PackageManagerInternal.PackageListObserver mObserver;

    private final IntFunction<File> mUserFolderGetter =
            userId -> new File(mFolder, Integer.toString(userId));

    private LaunchParamsPersister mTarget;

    private LaunchParams mResult;

    @Before
    public void setUp() throws Exception {
        mPersisterQueue = new TestPersisterQueue();

        final File cacheFolder =
                InstrumentationRegistry.getInstrumentation().getContext().getCacheDir();
        mFolder = new File(cacheFolder, "launch_params_tests");
        deleteRecursively(mFolder);

        mDisplayUniqueId = "test:" + sNextUniqueId++;
        mTestDisplay = new TestDisplayContent.Builder(mService, 1000, 1500)
                .setUniqueId(mDisplayUniqueId).build();
        when(mRootWindowContainer.getDisplayContent(eq(mDisplayUniqueId)))
                .thenReturn(mTestDisplay);

        ActivityStack stack = mTestDisplay.createStack(TEST_WINDOWING_MODE,
                ACTIVITY_TYPE_STANDARD, /* onTop */ true);
        mTestTask = new TaskBuilder(mSupervisor).setComponent(TEST_COMPONENT).setStack(stack)
                .build();
        mTestTask.mUserId = TEST_USER_ID;
        mTestTask.mLastNonFullscreenBounds = TEST_BOUNDS;
        mTestTask.setHasBeenVisible(true);

        mTaskWithDifferentComponent = new TaskBuilder(mSupervisor)
                .setComponent(ALTERNATIVE_COMPONENT).build();
        mTaskWithDifferentComponent.mUserId = TEST_USER_ID;

        mTaskWithDifferentUser = new TaskBuilder(mSupervisor).setComponent(TEST_COMPONENT).build();
        mTaskWithDifferentUser.mUserId = ALTERNATIVE_USER_ID;

        mTarget = new LaunchParamsPersister(mPersisterQueue, mSupervisor, mUserFolderGetter);

        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        mMockPmi = mock(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mMockPmi);
        when(mMockPmi.getPackageList(any())).thenReturn(new PackageList(
                Collections.singletonList(TEST_COMPONENT.getPackageName()), /* observer */ null));
        when(mMockPmi.getSystemUiServiceComponent()).thenReturn(new ComponentName("", ""));
        mTarget.onSystemReady();

        final ArgumentCaptor<PackageManagerInternal.PackageListObserver> observerCaptor =
                ArgumentCaptor.forClass(PackageManagerInternal.PackageListObserver.class);
        verify(mMockPmi).getPackageList(observerCaptor.capture());
        mObserver = observerCaptor.getValue();

        mResult = new LaunchParams();
        mResult.reset();
    }

    @Test
    public void testReturnsEmptyLaunchParamsByDefault() {
        mResult.mWindowingMode = WINDOWING_MODE_FULLSCREEN;

        mTarget.getLaunchParams(mTestTask, null, mResult);

        assertTrue("Default result should be empty.", mResult.isEmpty());
    }

    @Test
    public void testSavesAndRestoresLaunchParamsInSameInstance() {
        mTarget.saveTask(mTestTask);

        mTarget.getLaunchParams(mTestTask, null, mResult);

        assertEquals(mTestDisplay.mDisplayId, mResult.mPreferredDisplayId);
        assertEquals(TEST_WINDOWING_MODE, mResult.mWindowingMode);
        assertEquals(TEST_BOUNDS, mResult.mBounds);
    }

    @Test
    public void testFetchesSameResultWithActivity() {
        mTarget.saveTask(mTestTask);

        final ActivityRecord activity = new ActivityBuilder(mService).setComponent(TEST_COMPONENT)
                .setUid(TEST_USER_ID * UserHandle.PER_USER_RANGE).build();

        mTarget.getLaunchParams(null, activity, mResult);

        assertEquals(mTestDisplay.mDisplayId, mResult.mPreferredDisplayId);
        assertEquals(TEST_WINDOWING_MODE, mResult.mWindowingMode);
        assertEquals(TEST_BOUNDS, mResult.mBounds);
    }

    @Test
    public void testReturnsEmptyDisplayIfDisplayIsNotFound() {
        mTarget.saveTask(mTestTask);

        when(mRootWindowContainer.getDisplayContent(eq(mDisplayUniqueId))).thenReturn(null);

        mTarget.getLaunchParams(mTestTask, null, mResult);

        assertEquals(INVALID_DISPLAY, mResult.mPreferredDisplayId);
        assertEquals(TEST_WINDOWING_MODE, mResult.mWindowingMode);
        assertEquals(TEST_BOUNDS, mResult.mBounds);
    }

    @Test
    public void testReturnsEmptyLaunchParamsUserIdMismatch() {
        mTarget.saveTask(mTestTask);

        mResult.mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        mTarget.getLaunchParams(mTaskWithDifferentUser, null, mResult);

        assertTrue("Result should be empty.", mResult.isEmpty());
    }

    @Test
    public void testReturnsEmptyLaunchParamsComponentMismatch() {
        mTarget.saveTask(mTestTask);

        mResult.mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        mTarget.getLaunchParams(mTaskWithDifferentComponent, null, mResult);

        assertTrue("Result should be empty.", mResult.isEmpty());
    }

    @Test
    public void testUsesRecordWithSameWindowLayoutAffinityInSameInstance_NoPreviousRecord() {
        mTestTask.mWindowLayoutAffinity = TEST_WINDOW_LAYOUT_AFFINITY;
        mTarget.saveTask(mTestTask);

        mTaskWithDifferentComponent.mWindowLayoutAffinity = TEST_WINDOW_LAYOUT_AFFINITY;
        mTarget.getLaunchParams(mTaskWithDifferentComponent, null, mResult);

        assertEquals(mTestDisplay.mDisplayId, mResult.mPreferredDisplayId);
        assertEquals(TEST_WINDOWING_MODE, mResult.mWindowingMode);
        assertEquals(TEST_BOUNDS, mResult.mBounds);
    }

    @Test
    public void testUsesRecordWithSameWindowLayoutAffinityInSameInstance_HasOldPreviousRecord()
            throws Exception {
        mTaskWithDifferentComponent.mWindowLayoutAffinity = TEST_WINDOW_LAYOUT_AFFINITY;
        mTarget.saveTask(mTaskWithDifferentComponent);

        Thread.sleep(1);  // Sleep 1ms so that the timestamp can for sure increase.

        mTestTask.mWindowLayoutAffinity = TEST_WINDOW_LAYOUT_AFFINITY;
        mTarget.saveTask(mTestTask);

        mTarget.getLaunchParams(mTaskWithDifferentComponent, null, mResult);

        assertEquals(mTestDisplay.mDisplayId, mResult.mPreferredDisplayId);
        assertEquals(TEST_WINDOWING_MODE, mResult.mWindowingMode);
        assertEquals(TEST_BOUNDS, mResult.mBounds);
    }

    @Test
    public void testReturnsEmptyLaunchParamsUidInLaunchAffinityMismatch() {
        mTestTask.mWindowLayoutAffinity = TEST_WINDOW_LAYOUT_AFFINITY;
        mTarget.saveTask(mTestTask);

        mTaskWithDifferentComponent.mWindowLayoutAffinity = TEST_DIFFERENT_AFFINITY_WITH_SAME_UID;
        mResult.mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        mTarget.getLaunchParams(mTaskWithDifferentComponent, null, mResult);

        assertTrue("Result must be empty.", mResult.isEmpty());
    }

    @Test
    public void testReturnsEmptyLaunchParamsWindowLayoutAffinityMismatch() {
        mTestTask.affinity = TEST_WINDOW_LAYOUT_AFFINITY;
        mTarget.saveTask(mTestTask);

        mTaskWithDifferentComponent.mWindowLayoutAffinity = TEST_ALTERNATIVE_WINDOW_LAYOUT_AFFINITY;
        mResult.mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        mTarget.getLaunchParams(mTaskWithDifferentComponent, null, mResult);

        assertTrue("Result must be empty.", mResult.isEmpty());
    }

    @Test
    public void testSavesAndRestoresLaunchParamsAcrossInstances() {
        mTarget.saveTask(mTestTask);
        mPersisterQueue.flush();

        final LaunchParamsPersister target = new LaunchParamsPersister(mPersisterQueue, mSupervisor,
                mUserFolderGetter);
        target.onSystemReady();
        target.onUnlockUser(TEST_USER_ID);

        target.getLaunchParams(mTestTask, null, mResult);

        assertEquals(mTestDisplay.mDisplayId, mResult.mPreferredDisplayId);
        assertEquals(TEST_WINDOWING_MODE, mResult.mWindowingMode);
        assertEquals(TEST_BOUNDS, mResult.mBounds);
    }

    @Test
    public void testUsesRecordWithSameWindowLayoutAffinityAcrossInstances_NoPreviousRecord() {
        mTestTask.mWindowLayoutAffinity = TEST_WINDOW_LAYOUT_AFFINITY;
        mTarget.saveTask(mTestTask);
        mPersisterQueue.flush();

        final LaunchParamsPersister target = new LaunchParamsPersister(mPersisterQueue, mSupervisor,
                mUserFolderGetter);
        target.onSystemReady();
        target.onUnlockUser(TEST_USER_ID);

        mTaskWithDifferentComponent.mWindowLayoutAffinity = TEST_WINDOW_LAYOUT_AFFINITY;
        target.getLaunchParams(mTaskWithDifferentComponent, null, mResult);

        assertEquals(mTestDisplay.mDisplayId, mResult.mPreferredDisplayId);
        assertEquals(TEST_WINDOWING_MODE, mResult.mWindowingMode);
        assertEquals(TEST_BOUNDS, mResult.mBounds);
    }

    @Test
    public void testUsesRecordWithSameWindowLayoutAffinityAcrossInstances_HasOldPreviousRecord()
            throws Exception {
        mTaskWithDifferentComponent.mWindowLayoutAffinity = TEST_WINDOW_LAYOUT_AFFINITY;
        mTarget.saveTask(mTaskWithDifferentComponent);
        mPersisterQueue.flush();

        // Sleep 1s because many file systems only save last modified time as precise as 1s so we
        // can for sure know the last modified time is different.
        Thread.sleep(1000);

        mTestTask.mWindowLayoutAffinity = TEST_WINDOW_LAYOUT_AFFINITY;
        mTarget.saveTask(mTestTask);
        mPersisterQueue.flush();

        final LaunchParamsPersister target = new LaunchParamsPersister(mPersisterQueue, mSupervisor,
                mUserFolderGetter);
        target.onSystemReady();
        target.onUnlockUser(TEST_USER_ID);

        target.getLaunchParams(mTaskWithDifferentComponent, null, mResult);

        assertEquals(mTestDisplay.mDisplayId, mResult.mPreferredDisplayId);
        assertEquals(TEST_WINDOWING_MODE, mResult.mWindowingMode);
        assertEquals(TEST_BOUNDS, mResult.mBounds);
    }

    @Test
    public void testClearsRecordsOfTheUserOnUserCleanUp() {
        mTarget.saveTask(mTestTask);

        ActivityStack stack = mTestDisplay.createStack(TEST_WINDOWING_MODE,
                ACTIVITY_TYPE_STANDARD, /* onTop */ true);
        final Task anotherTaskOfTheSameUser = new TaskBuilder(mSupervisor)
                .setComponent(ALTERNATIVE_COMPONENT)
                .setUserId(TEST_USER_ID)
                .setStack(stack)
                .build();
        anotherTaskOfTheSameUser.setWindowingMode(WINDOWING_MODE_FREEFORM);
        anotherTaskOfTheSameUser.setBounds(200, 300, 400, 500);
        anotherTaskOfTheSameUser.setHasBeenVisible(true);
        mTarget.saveTask(anotherTaskOfTheSameUser);

        stack = mTestDisplay.createStack(TEST_WINDOWING_MODE,
                ACTIVITY_TYPE_STANDARD, /* onTop */ true);
        final Task anotherTaskOfDifferentUser = new TaskBuilder(mSupervisor)
                .setComponent(TEST_COMPONENT)
                .setUserId(ALTERNATIVE_USER_ID)
                .setStack(stack)
                .build();
        anotherTaskOfDifferentUser.setWindowingMode(WINDOWING_MODE_FREEFORM);
        anotherTaskOfDifferentUser.setBounds(300, 400, 500, 600);
        anotherTaskOfDifferentUser.setHasBeenVisible(true);
        mTarget.saveTask(anotherTaskOfDifferentUser);

        mTarget.onCleanupUser(TEST_USER_ID);

        mTarget.getLaunchParams(anotherTaskOfDifferentUser, null, mResult);
        assertFalse("Shouldn't clear record of a different user.", mResult.isEmpty());

        mTarget.getLaunchParams(mTestTask, null, mResult);
        assertTrue("Should have cleaned record for " + TEST_COMPONENT, mResult.isEmpty());

        mTarget.getLaunchParams(anotherTaskOfTheSameUser, null, mResult);
        assertTrue("Should have cleaned record for " + ALTERNATIVE_COMPONENT, mResult.isEmpty());
    }

    @Test
    public void testClearsRecordInMemory() {
        mTarget.saveTask(mTestTask);

        mTarget.removeRecordForPackage(TEST_COMPONENT.getPackageName());

        mTarget.getLaunchParams(mTestTask, null, mResult);

        assertTrue("Result should be empty.", mResult.isEmpty());
    }

    @Test
    public void testClearsWriteQueueItem() {
        mTarget.saveTask(mTestTask);

        mTarget.removeRecordForPackage(TEST_COMPONENT.getPackageName());

        final LaunchParamsPersister target = new LaunchParamsPersister(mPersisterQueue, mSupervisor,
                mUserFolderGetter);
        target.onSystemReady();
        target.onUnlockUser(TEST_USER_ID);

        target.getLaunchParams(mTestTask, null, mResult);

        assertTrue("Result should be empty.", mResult.isEmpty());
    }

    @Test
    public void testClearsFile() {
        mTarget.saveTask(mTestTask);
        mPersisterQueue.flush();

        mTarget.removeRecordForPackage(TEST_COMPONENT.getPackageName());

        final LaunchParamsPersister target = new LaunchParamsPersister(mPersisterQueue, mSupervisor,
                mUserFolderGetter);
        target.onSystemReady();
        target.onUnlockUser(TEST_USER_ID);

        target.getLaunchParams(mTestTask, null, mResult);

        assertTrue("Result should be empty.", mResult.isEmpty());
    }


    @Test
    public void testClearsRecordInMemoryOnPackageUninstalled() {
        mTarget.saveTask(mTestTask);

        mObserver.onPackageRemoved(TEST_COMPONENT.getPackageName(), TEST_USER_ID);

        mTarget.getLaunchParams(mTestTask, null, mResult);

        assertTrue("Result should be empty.", mResult.isEmpty());
    }

    @Test
    public void testClearsWriteQueueItemOnPackageUninstalled() {
        mTarget.saveTask(mTestTask);

        mObserver.onPackageRemoved(TEST_COMPONENT.getPackageName(), TEST_USER_ID);

        final LaunchParamsPersister target = new LaunchParamsPersister(mPersisterQueue, mSupervisor,
                mUserFolderGetter);
        target.onSystemReady();
        target.onUnlockUser(TEST_USER_ID);

        target.getLaunchParams(mTestTask, null, mResult);

        assertTrue("Result should be empty.", mResult.isEmpty());
    }

    @Test
    public void testClearsFileOnPackageUninstalled() {
        mTarget.saveTask(mTestTask);
        mPersisterQueue.flush();

        mObserver.onPackageRemoved(TEST_COMPONENT.getPackageName(), TEST_USER_ID);

        final LaunchParamsPersister target = new LaunchParamsPersister(mPersisterQueue, mSupervisor,
                mUserFolderGetter);
        target.onSystemReady();
        target.onUnlockUser(TEST_USER_ID);

        target.getLaunchParams(mTestTask, null, mResult);

        assertTrue("Result should be empty.", mResult.isEmpty());
    }

    @Test
    public void testClearsRemovedPackageFilesOnStartUp() {
        mTarget.saveTask(mTestTask);
        mPersisterQueue.flush();

        when(mMockPmi.getPackageList(any())).thenReturn(
                new PackageList(Collections.emptyList(), /* observer */ null));

        final LaunchParamsPersister target = new LaunchParamsPersister(mPersisterQueue, mSupervisor,
                mUserFolderGetter);
        target.onSystemReady();
        target.onUnlockUser(TEST_USER_ID);

        target.getLaunchParams(mTestTask, null, mResult);

        assertTrue("Result should be empty.", mResult.isEmpty());
    }

    private static boolean deleteRecursively(File file) {
        boolean result = true;
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                result &= deleteRecursively(child);
            }
        }

        result &= file.delete();
        return result;
    }

    /**
     * Test double to {@link PersisterQueue}. This is not thread-safe and caller should always use
     * {@link #flush()} to execute write items in it.
     */
    static class TestPersisterQueue extends PersisterQueue {
        private List<WriteQueueItem> mWriteQueue = new ArrayList<>();
        private List<Listener> mListeners = new ArrayList<>();

        @Override
        void flush() {
            while (!mWriteQueue.isEmpty()) {
                final WriteQueueItem item = mWriteQueue.remove(0);
                final boolean queueEmpty = mWriteQueue.isEmpty();
                for (Listener listener : mListeners) {
                    listener.onPreProcessItem(queueEmpty);
                }
                item.process();
            }
        }

        @Override
        void startPersisting() {
            // Do nothing. We're not using threading logic.
        }

        @Override
        void stopPersisting() {
            // Do nothing. We're not using threading logic.
        }

        @Override
        void addItem(WriteQueueItem item, boolean flush) {
            mWriteQueue.add(item);
            if (flush) {
                flush();
            }
        }

        @Override
        synchronized <T extends WriteQueueItem> T findLastItem(Predicate<T> predicate,
                Class<T> clazz) {
            for (int i = mWriteQueue.size() - 1; i >= 0; --i) {
                WriteQueueItem writeQueueItem = mWriteQueue.get(i);
                if (clazz.isInstance(writeQueueItem)) {
                    T item = clazz.cast(writeQueueItem);
                    if (predicate.test(item)) {
                        return item;
                    }
                }
            }

            return null;
        }

        @Override
        synchronized <T extends WriteQueueItem> void removeItems(Predicate<T> predicate,
                Class<T> clazz) {
            for (int i = mWriteQueue.size() - 1; i >= 0; --i) {
                WriteQueueItem writeQueueItem = mWriteQueue.get(i);
                if (clazz.isInstance(writeQueueItem)) {
                    T item = clazz.cast(writeQueueItem);
                    if (predicate.test(item)) {
                        mWriteQueue.remove(i);
                    }
                }
            }
        }

        @Override
        void addListener(Listener listener) {
            mListeners.add(listener);
        }

        @Override
        void yieldIfQueueTooDeep() {
            // Do nothing. We're not using threading logic.
        }
    }
}
