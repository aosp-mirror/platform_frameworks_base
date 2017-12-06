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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.platform.test.annotations.Presubmit;
import android.service.voice.IVoiceInteractionSession;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.internal.app.IVoiceInteractor;
import com.android.server.am.TaskRecord.TaskRecordFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Tests for exercising {@link TaskRecord}.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.am.TaskRecordTests
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TaskRecordTests {

    @Before
    public void setUp() throws Exception {
        TaskRecord.setTaskRecordFactory(null);
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
