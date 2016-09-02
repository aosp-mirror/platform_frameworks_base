/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.am;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.pm.ActivityInfo;
import android.content.pm.UserInfo;
import android.os.Environment;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.server.am.TaskPersister;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Random;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

public class TaskPersisterTest extends AndroidTestCase {
    private static final String TEST_USER_NAME = "AM-Test-User";

    private TaskPersister mTaskPersister;
    private int testUserId;
    private UserManager mUserManager;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mUserManager = UserManager.get(getContext());
        mTaskPersister = new TaskPersister(getContext().getFilesDir());
        testUserId = createUser(TEST_USER_NAME, 0);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        mTaskPersister.unloadUserDataFromMemory(testUserId);
        removeUser(testUserId);
    }

    private int getRandomTaskIdForUser(int userId) {
        int taskId = (int) (Math.random() * UserHandle.PER_USER_RANGE);
        taskId += UserHandle.PER_USER_RANGE * userId;
        return taskId;
    }

    public void testTaskIdsPersistence() {
        SparseBooleanArray taskIdsOnFile = mTaskPersister.loadPersistedTaskIdsForUser(testUserId);
        for (int i = 0; i < 100; i++) {
            taskIdsOnFile.put(getRandomTaskIdForUser(testUserId), true);
        }
        mTaskPersister.writePersistedTaskIdsForUser(taskIdsOnFile, testUserId);
        SparseBooleanArray newTaskIdsOnFile = mTaskPersister
                .loadPersistedTaskIdsForUser(testUserId);
        assertTrue("TaskIds written differ from TaskIds read back from file",
                taskIdsOnFile.equals(newTaskIdsOnFile));
    }

    public void testActiveTimeMigration() {
        // Simulate a migration scenario by setting the last write uptime to zero
        ContentResolver cr = getContext().getContentResolver();
        Settings.Secure.putLong(cr,
                Settings.Secure.TASK_PERSISTER_LAST_WRITE_UPTIME, 0);

        // Create a dummy task record with an absolute time 1s before now
        long pastOffset = 1000;
        long activeTime = System.currentTimeMillis() - pastOffset;
        TaskRecord tr0 = createDummyTaskRecordWithActiveTime(activeTime, activeTime);

        // Save and load the tasks with no last persist uptime (0)
        String tr0XmlStr = serializeTaskRecordToXmlString(tr0);
        TaskRecord xtr0 = unserializeTaskRecordFromXmlString(tr0XmlStr, 0);

        // Ensure that the absolute time has been migrated to be relative to the current elapsed
        // time
        assertTrue("Expected firstActiveTime to be migrated from: " + tr0.firstActiveTime +
                " instead found: " + xtr0.firstActiveTime,
                        xtr0.firstActiveTime <= -pastOffset);
        assertTrue("Expected lastActiveTime to be migrated from: " + tr0.lastActiveTime +
                " instead found: " + xtr0.lastActiveTime,
                        xtr0.lastActiveTime <= -pastOffset);

        // Ensure that the last active uptime is not set so that SystemUI can migrate it itself
        // assuming that the last persist time is zero
        Settings.Secure.putLongForUser(cr,
                Settings.Secure.OVERVIEW_LAST_VISIBLE_TASK_ACTIVE_UPTIME, 0, testUserId);
        mTaskPersister.restoreTasksForUserLocked(testUserId);
        long lastVisTaskActiveTime = Settings.Secure.getLongForUser(cr,
                Settings.Secure.OVERVIEW_LAST_VISIBLE_TASK_ACTIVE_UPTIME, -1, testUserId);
        assertTrue("Expected last visible task active time is zero", lastVisTaskActiveTime == 0);
    }

    public void testActiveTimeOffsets() {
        // Simulate a normal boot scenario by setting the last write uptime
        long lastWritePastOffset = 1000;
        long lastVisActivePastOffset = 500;
        ContentResolver cr = getContext().getContentResolver();
        Settings.Secure.putLong(cr,
                Settings.Secure.TASK_PERSISTER_LAST_WRITE_UPTIME, lastWritePastOffset);

        // Create a dummy task record with an absolute time 1s before now
        long activeTime = 250;
        TaskRecord tr0 = createDummyTaskRecordWithActiveTime(activeTime, activeTime);

        // Save and load the tasks with the last persist time
        String tr0XmlStr = serializeTaskRecordToXmlString(tr0);
        TaskRecord xtr0 = unserializeTaskRecordFromXmlString(tr0XmlStr, lastWritePastOffset);

        // Ensure that the prior elapsed time has been offset to be relative to the current boot
        // time
        assertTrue("Expected firstActiveTime to be offset from: " + tr0.firstActiveTime +
                " instead found: " + xtr0.firstActiveTime,
                        xtr0.firstActiveTime <= (-lastWritePastOffset + activeTime));
        assertTrue("Expected lastActiveTime to be offset from: " + tr0.lastActiveTime +
                " instead found: " + xtr0.lastActiveTime,
                        xtr0.lastActiveTime <= (-lastWritePastOffset + activeTime));

        // Ensure that we update the last active uptime as well by simulating a restoreTasks call
        Settings.Secure.putLongForUser(cr,
                Settings.Secure.OVERVIEW_LAST_VISIBLE_TASK_ACTIVE_UPTIME, lastVisActivePastOffset,
                        testUserId);
        mTaskPersister.restoreTasksForUserLocked(testUserId);
        long lastVisTaskActiveTime = Settings.Secure.getLongForUser(cr,
                Settings.Secure.OVERVIEW_LAST_VISIBLE_TASK_ACTIVE_UPTIME, Long.MAX_VALUE,
                        testUserId);
        assertTrue("Expected last visible task active time to be offset", lastVisTaskActiveTime <=
                (-lastWritePastOffset + lastVisActivePastOffset));
    }

    private TaskRecord createDummyTaskRecordWithActiveTime(long firstActiveTime,
            long lastActiveTime) {
        ActivityInfo info = createDummyActivityInfo();
        ActivityManager.TaskDescription td = new ActivityManager.TaskDescription();
        TaskRecord t = new TaskRecord(null, 0, info, null, td, null);
        t.firstActiveTime = firstActiveTime;
        t.lastActiveTime = lastActiveTime;
        return t;
    }

    private ActivityInfo createDummyActivityInfo() {
        ActivityInfo info = new ActivityInfo();
        info.applicationInfo = getContext().getApplicationInfo();
        return info;
    }

    private String serializeTaskRecordToXmlString(TaskRecord tr) {
        StringWriter stringWriter = new StringWriter();

        try {
            final XmlSerializer xmlSerializer = new FastXmlSerializer();
            xmlSerializer.setOutput(stringWriter);

            xmlSerializer.startDocument(null, true);
            xmlSerializer.startTag(null, TaskPersister.TAG_TASK);
            tr.saveToXml(xmlSerializer);
            xmlSerializer.endTag(null, TaskPersister.TAG_TASK);
            xmlSerializer.endDocument();
            xmlSerializer.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return stringWriter.toString();
    }

    private TaskRecord unserializeTaskRecordFromXmlString(String xmlStr, long lastPersistUptime) {
        StringReader reader = null;
        TaskRecord task = null;
        try {
            reader = new StringReader(xmlStr);
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(reader);

            int event;
            while (((event = in.next()) != XmlPullParser.END_DOCUMENT) &&
                    event != XmlPullParser.END_TAG) {
                final String name = in.getName();
                if (event == XmlPullParser.START_TAG) {
                    if (TaskPersister.TAG_TASK.equals(name)) {
                        task = TaskRecord.restoreFromXml(in, null, null, lastPersistUptime);
                    }
                }
                XmlUtils.skipCurrentTag(in);
            }
        } catch (Exception e) {
            return null;
        } finally {
            IoUtils.closeQuietly(reader);
        }
        return task;
    }

    private int createUser(String name, int flags) {
        UserInfo user = mUserManager.createUser(name, flags);
        if (user == null) {
            fail("Error while creating the test user: " + TEST_USER_NAME);
        }
        return user.id;
    }

    private void removeUser(int userId) {
        if (!mUserManager.removeUser(userId)) {
            fail("Error while removing the test user: " + TEST_USER_NAME);
        }
    }
}