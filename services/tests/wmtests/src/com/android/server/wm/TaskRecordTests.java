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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.service.voice.IVoiceInteractionSession;
import android.util.Xml;
import android.view.DisplayInfo;

import androidx.test.filters.MediumTest;

import com.android.internal.app.IVoiceInteractor;
import com.android.server.wm.TaskRecord.TaskRecordFactory;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;

/**
 * Tests for exercising {@link TaskRecord}.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskRecordTests
 */
@MediumTest
@Presubmit
public class TaskRecordTests extends ActivityTestsBase {

    private static final String TASK_TAG = "task";

    private Rect mParentBounds;

    @Before
    public void setUp() throws Exception {
        TaskRecord.setTaskRecordFactory(null);
        mParentBounds = new Rect(10 /*left*/, 30 /*top*/, 80 /*right*/, 60 /*bottom*/);
    }

    @Test
    public void testRestoreWindowedTask() throws Exception {
        final TaskRecord expected = createTaskRecord(64);
        expected.mLastNonFullscreenBounds = new Rect(50, 50, 100, 100);

        final byte[] serializedBytes = serializeToBytes(expected);
        final TaskRecord actual = restoreFromBytes(serializedBytes);
        assertEquals(expected.taskId, actual.taskId);
        assertEquals(expected.mLastNonFullscreenBounds, actual.mLastNonFullscreenBounds);
    }

    @Test
    public void testDefaultTaskFactoryNotNull() throws Exception {
        assertNotNull(TaskRecord.getTaskRecordFactory());
    }

    /** Ensure we have no chance to modify the original intent. */
    @Test
    public void testCopyBaseIntentForTaskInfo() {
        final TaskRecord task = createTaskRecord(1);
        task.lastTaskDescription = new ActivityManager.TaskDescription();
        final ActivityManager.RecentTaskInfo info = new ActivityManager.RecentTaskInfo();
        task.fillTaskInfo(info, new TaskRecord.TaskActivitiesReport());

        // The intent of info should be a copy so assert that they are different instances.
        assertThat(info.baseIntent, not(sameInstance(task.getBaseIntent())));
    }

    @Test
    public void testCreateTestRecordUsingCustomizedFactory() throws Exception {
        TestTaskRecordFactory factory = new TestTaskRecordFactory();
        TaskRecord.setTaskRecordFactory(factory);

        assertFalse(factory.mCreated);

        TaskRecord.create(null, 0, null, null, null, null);

        assertTrue(factory.mCreated);
    }

    @Test
    public void testReturnsToHomeStack() throws Exception {
        final TaskRecord task = createTaskRecord(1);
        assertFalse(task.returnsToHomeStack());
        task.intent = null;
        assertFalse(task.returnsToHomeStack());
        task.intent = new Intent();
        assertFalse(task.returnsToHomeStack());
        task.intent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME);
        assertTrue(task.returnsToHomeStack());
    }

    /** Ensures that empty bounds are not propagated to the configuration. */
    @Test
    public void testAppBounds_EmptyBounds() {
        final Rect emptyBounds = new Rect();
        testStackBoundsConfiguration(WINDOWING_MODE_FULLSCREEN, mParentBounds, emptyBounds,
                null /*ExpectedBounds*/);
    }

    /** Ensures that bounds on freeform stacks are not clipped. */
    @Test
    public void testAppBounds_FreeFormBounds() {
        final Rect freeFormBounds = new Rect(mParentBounds);
        freeFormBounds.offset(10, 10);
        testStackBoundsConfiguration(WINDOWING_MODE_FREEFORM, mParentBounds, freeFormBounds,
                freeFormBounds);
    }

    /** Ensures that fully contained bounds are not clipped. */
    @Test
    public void testAppBounds_ContainedBounds() {
        final Rect insetBounds = new Rect(mParentBounds);
        insetBounds.inset(5, 5, 5, 5);
        testStackBoundsConfiguration(
                WINDOWING_MODE_FREEFORM, mParentBounds, insetBounds, insetBounds);
    }

    /** Tests that the task bounds adjust properly to changes between FULLSCREEN and FREEFORM */
    @Test
    public void testBoundsOnModeChangeFreeformToFullscreen() {
        ActivityDisplay display = mService.mRootActivityContainer.getDefaultDisplay();
        ActivityStack stack = new StackBuilder(mRootActivityContainer).setDisplay(display)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        TaskRecord task = stack.getChildAt(0);
        task.getRootActivity().mAppWindowToken.setOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
        DisplayInfo info = new DisplayInfo();
        display.mDisplay.getDisplayInfo(info);
        final Rect fullScreenBounds = new Rect(0, 0, info.logicalWidth, info.logicalHeight);
        final Rect freeformBounds = new Rect(fullScreenBounds);
        freeformBounds.inset((int) (freeformBounds.width() * 0.2),
                (int) (freeformBounds.height() * 0.2));
        task.setBounds(freeformBounds);

        assertEquals(freeformBounds, task.getBounds());

        // FULLSCREEN inherits bounds
        stack.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertEquals(fullScreenBounds, task.getBounds());
        assertEquals(freeformBounds, task.mLastNonFullscreenBounds);

        // FREEFORM restores bounds
        stack.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(freeformBounds, task.getBounds());
    }

    /**
     * This is a temporary hack to trigger an onConfigurationChange at the task level after an
     * orientation is requested. Normally this is done by the onDescendentOrientationChanged call
     * up the WM hierarchy, but since the WM hierarchy is mocked out, it doesn't happen here.
     * TODO: remove this when we either get a WM hierarchy or when hierarchies are merged.
     */
    private void setActivityRequestedOrientation(ActivityRecord activity, int orientation) {
        activity.setRequestedOrientation(orientation);
        ConfigurationContainer taskRecord = activity.getParent();
        taskRecord.onConfigurationChanged(taskRecord.getParent().getConfiguration());
    }

    /**
     * Tests that a task with forced orientation has orientation-consistent bounds within the
     * parent.
     */
    @Test
    public void testFullscreenBoundsForcedOrientation() {
        final Rect fullScreenBounds = new Rect(0, 0, 1920, 1080);
        final Rect fullScreenBoundsPort = new Rect(0, 0, 1080, 1920);
        DisplayInfo info = new DisplayInfo();
        info.logicalWidth = fullScreenBounds.width();
        info.logicalHeight = fullScreenBounds.height();
        ActivityDisplay display = addNewActivityDisplayAt(info, POSITION_TOP);
        assertTrue(mRootActivityContainer.getActivityDisplay(display.mDisplayId) != null);
        // Override display orientation. Normally this is available via DisplayContent, but DC
        // is mocked-out.
        display.getRequestedOverrideConfiguration().orientation =
                Configuration.ORIENTATION_LANDSCAPE;
        display.onRequestedOverrideConfigurationChanged(
                display.getRequestedOverrideConfiguration());
        ActivityStack stack = new StackBuilder(mRootActivityContainer)
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN).setDisplay(display).build();
        TaskRecord task = stack.getChildAt(0);
        ActivityRecord root = task.getTopActivity();
        assertEquals(root, task.getTopActivity());

        assertEquals(fullScreenBounds, task.getBounds());

        // Setting app to fixed portrait fits within parent
        setActivityRequestedOrientation(root, SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(root, task.getRootActivity());
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, task.getRootActivity().getOrientation());
        assertTrue(task.getBounds().width() < task.getBounds().height());
        assertEquals(fullScreenBounds.height(), task.getBounds().height());

        // Top activity gets used
        ActivityRecord top = new ActivityBuilder(mService).setTask(task).setStack(stack).build();
        assertEquals(top, task.getTopActivity());
        setActivityRequestedOrientation(top, SCREEN_ORIENTATION_LANDSCAPE);
        assertTrue(task.getBounds().width() > task.getBounds().height());
        assertEquals(task.getBounds().width(), fullScreenBounds.width());

        // Setting app to unspecified restores
        setActivityRequestedOrientation(top, SCREEN_ORIENTATION_UNSPECIFIED);
        assertEquals(fullScreenBounds, task.getBounds());

        // Setting app to fixed landscape and changing display
        setActivityRequestedOrientation(top, SCREEN_ORIENTATION_LANDSCAPE);
        // simulate display orientation changing (normally done via DisplayContent)
        display.getRequestedOverrideConfiguration().orientation =
                Configuration.ORIENTATION_PORTRAIT;
        display.setBounds(fullScreenBoundsPort);
        assertTrue(task.getBounds().width() > task.getBounds().height());
        assertEquals(fullScreenBoundsPort.width(), task.getBounds().width());

        // in FREEFORM, no constraint
        final Rect freeformBounds = new Rect(display.getBounds());
        freeformBounds.inset((int) (freeformBounds.width() * 0.2),
                (int) (freeformBounds.height() * 0.2));
        stack.setWindowingMode(WINDOWING_MODE_FREEFORM);
        task.setBounds(freeformBounds);
        assertEquals(freeformBounds, task.getBounds());

        // FULLSCREEN letterboxes bounds
        stack.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertTrue(task.getBounds().width() > task.getBounds().height());
        assertEquals(fullScreenBoundsPort.width(), task.getBounds().width());

        // FREEFORM restores bounds as before
        stack.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(freeformBounds, task.getBounds());
    }

    @Test
    public void testIgnoresForcedOrientationWhenParentHandles() {
        final Rect fullScreenBounds = new Rect(0, 0, 1920, 1080);
        DisplayInfo info = new DisplayInfo();
        info.logicalWidth = fullScreenBounds.width();
        info.logicalHeight = fullScreenBounds.height();
        ActivityDisplay display = addNewActivityDisplayAt(info, POSITION_TOP);

        display.getRequestedOverrideConfiguration().orientation =
                Configuration.ORIENTATION_LANDSCAPE;
        display.onRequestedOverrideConfigurationChanged(
                display.getRequestedOverrideConfiguration());
        ActivityStack stack = new StackBuilder(mRootActivityContainer)
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN).setDisplay(display).build();
        TaskRecord task = stack.getChildAt(0);
        ActivityRecord root = task.getTopActivity();

        final WindowContainer parentWindowContainer = mock(WindowContainer.class);
        Mockito.doReturn(parentWindowContainer).when(task.mTask).getParent();
        Mockito.doReturn(true).when(parentWindowContainer)
                .handlesOrientationChangeFromDescendant();

        // Setting app to fixed portrait fits within parent, but TaskRecord shouldn't adjust the
        // bounds because its parent says it will handle it at a later time.
        setActivityRequestedOrientation(root, SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(root, task.getRootActivity());
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, task.getRootActivity().getOrientation());
        assertEquals(fullScreenBounds, task.getBounds());
    }

    /** Ensures that the alias intent won't have target component resolved. */
    @Test
    public void testTaskIntentActivityAlias() {
        final String aliasClassName = DEFAULT_COMPONENT_PACKAGE_NAME + ".aliasActivity";
        final String targetClassName = DEFAULT_COMPONENT_PACKAGE_NAME + ".targetActivity";
        final ComponentName aliasComponent =
                new ComponentName(DEFAULT_COMPONENT_PACKAGE_NAME, aliasClassName);
        final ComponentName targetComponent =
                new ComponentName(DEFAULT_COMPONENT_PACKAGE_NAME, targetClassName);

        final Intent intent = new Intent();
        intent.setComponent(aliasComponent);
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.packageName = DEFAULT_COMPONENT_PACKAGE_NAME;
        info.targetActivity = targetClassName;

        final TaskRecord task = TaskRecord.create(mService, 1 /* taskId */, info, intent,
                null /* taskDescription */);
        assertEquals("The alias activity component should be saved in task intent.", aliasClassName,
                task.intent.getComponent().getClassName());

        ActivityRecord aliasActivity = new ActivityBuilder(mService).setComponent(
                aliasComponent).setTargetActivity(targetClassName).build();
        assertEquals("Should be the same intent filter.", true,
                task.isSameIntentFilter(aliasActivity));

        ActivityRecord targetActivity = new ActivityBuilder(mService).setComponent(
                targetComponent).build();
        assertEquals("Should be the same intent filter.", true,
                task.isSameIntentFilter(targetActivity));

        ActivityRecord defaultActivity = new ActivityBuilder(mService).build();
        assertEquals("Should not be the same intent filter.", false,
                task.isSameIntentFilter(defaultActivity));
    }

    private void testStackBoundsConfiguration(int windowingMode, Rect parentBounds, Rect bounds,
            Rect expectedConfigBounds) {

        ActivityDisplay display = mService.mRootActivityContainer.getDefaultDisplay();
        ActivityStack stack = display.createStack(windowingMode, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        TaskRecord task = new TaskBuilder(mSupervisor).setStack(stack).build();

        final Configuration parentConfig = stack.getConfiguration();
        parentConfig.windowConfiguration.setAppBounds(parentBounds);
        task.setBounds(bounds);

        task.resolveOverrideConfiguration(parentConfig);
        // Assert that both expected and actual are null or are equal to each other
        assertEquals(expectedConfigBounds,
                task.getResolvedOverrideConfiguration().windowConfiguration.getAppBounds());
    }

    private byte[] serializeToBytes(TaskRecord r) throws IOException, XmlPullParserException {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            final XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(os, "UTF-8");
            serializer.startDocument(null, true);
            serializer.startTag(null, TASK_TAG);
            r.saveToXml(serializer);
            serializer.endTag(null, TASK_TAG);
            serializer.endDocument();

            os.flush();
            return os.toByteArray();
        }
    }

    private TaskRecord restoreFromBytes(byte[] in) throws IOException, XmlPullParserException {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(in))) {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(reader);
            assertEquals(XmlPullParser.START_TAG, parser.next());
            assertEquals(TASK_TAG, parser.getName());
            return TaskRecord.restoreFromXml(parser, mService.mStackSupervisor);
        }
    }

    private TaskRecord createTaskRecord(int taskId) {
        return new TaskRecord(mService, taskId, new Intent(), null, null, null,
                ActivityBuilder.getDefaultComponent(), null, false, false, false, 0, 10050, null,
                new ArrayList<>(), 0, false, null, 0, 0, 0, 0, 0, null, 0, false, false, false, 0, 0
        );
    }

    private static class TestTaskRecordFactory extends TaskRecordFactory {
        private boolean mCreated = false;

        @Override
        TaskRecord create(ActivityTaskManagerService service, int taskId, ActivityInfo info,
                Intent intent,
                IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor) {
            mCreated = true;
            return null;
        }

        @Override
        TaskRecord create(ActivityTaskManagerService service, int taskId, ActivityInfo info,
                Intent intent,
                ActivityManager.TaskDescription taskDescription) {
            mCreated = true;
            return null;
        }

        @Override
        TaskRecord create(ActivityTaskManagerService service, int taskId, Intent intent,
                Intent affinityIntent, String affinity, String rootAffinity,
                ComponentName realActivity,
                ComponentName origActivity, boolean rootWasReset, boolean autoRemoveRecents,
                boolean askedCompatMode, int userId, int effectiveUid, String lastDescription,
                ArrayList<ActivityRecord> activities, long lastTimeMoved,
                boolean neverRelinquishIdentity,
                ActivityManager.TaskDescription lastTaskDescription,
                int taskAffiliation, int prevTaskId, int nextTaskId, int taskAffiliationColor,
                int callingUid, String callingPackage, int resizeMode,
                boolean supportsPictureInPicture,
                boolean realActivitySuspended, boolean userSetupComplete, int minWidth,
                int minHeight) {
            mCreated = true;
            return null;
        }

        @Override
        TaskRecord restoreFromXml(XmlPullParser in, ActivityStackSupervisor stackSupervisor)
                throws IOException, XmlPullParserException {
            mCreated = true;
            return null;
        }
    }
}
