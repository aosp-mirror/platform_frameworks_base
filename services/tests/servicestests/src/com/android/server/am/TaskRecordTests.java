/*
 * Copyright (C) 2017 The Android Open Source Project
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
 *
 */

package com.android.server.am;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.service.voice.IVoiceInteractionSession;
import android.util.Xml;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.IVoiceInteractor;
import com.android.server.am.TaskRecord.TaskRecordFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.util.ArrayList;

/**
 * Tests for exercising {@link TaskRecord}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:com.android.server.am.TaskRecordTests
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TaskRecordTests extends ActivityTestsBase {

    private static final String TASK_TAG = "task";

    private ActivityManagerService mService;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        TaskRecord.setTaskRecordFactory(null);
        mService = createActivityManagerService();
    }

    @Test
    public void testRestoreWindowedTask() throws Exception {
        final TaskRecord expected = createTaskRecord(64);
        expected.mLastNonFullscreenBounds = new Rect(50, 50, 100, 100);

        final File serializedFile = serializeToFile(expected);

        try {
            final TaskRecord actual = restoreFromFile(serializedFile);
            assertEquals(expected.taskId, actual.taskId);
            assertEquals(expected.mLastNonFullscreenBounds, actual.mLastNonFullscreenBounds);
        } finally {
            serializedFile.delete();
        }
    }

    @Test
    public void testDefaultTaskFactoryNotNull() throws Exception {
        assertNotNull(TaskRecord.getTaskRecordFactory());
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

    private File serializeToFile(TaskRecord r) throws IOException, XmlPullParserException {
        final File tmpFile = File.createTempFile(r.taskId + "_task_", "xml");

        try (final OutputStream os = new FileOutputStream(tmpFile)) {
            final XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(os, "UTF-8");
            serializer.startDocument(null, true);
            serializer.startTag(null, TASK_TAG);
            r.saveToXml(serializer);
            serializer.endTag(null, TASK_TAG);
            serializer.endDocument();
        }

        return tmpFile;
    }

    private TaskRecord restoreFromFile(File file) throws IOException, XmlPullParserException {
        try (final Reader reader = new BufferedReader(new FileReader(file))) {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(reader);
            assertEquals(XmlPullParser.START_TAG, parser.next());
            assertEquals(TASK_TAG, parser.getName());
            return TaskRecord.restoreFromXml(parser, mService.mStackSupervisor);
        }
    }

    private TaskRecord createTaskRecord(int taskId) {
        return new TaskRecord(mService, taskId, new Intent(), null, null, null, null, null, false,
                false, false, 0, 10050, null, new ArrayList<>(), 0, false, null, 0, 0, 0, 0, 0,
                null, 0, false, false, false, 0, 0);
    }

    private static class TestTaskRecordFactory extends TaskRecordFactory {
        private boolean mCreated = false;

        @Override
        TaskRecord create(ActivityManagerService service, int taskId, ActivityInfo info,
                Intent intent,
                IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor) {
            mCreated = true;
            return null;
        }

        @Override
        TaskRecord create(ActivityManagerService service, int taskId, ActivityInfo info,
                Intent intent,
                ActivityManager.TaskDescription taskDescription) {
            mCreated = true;
            return null;
        }

        @Override
        TaskRecord create(ActivityManagerService service, int taskId, Intent intent,
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
