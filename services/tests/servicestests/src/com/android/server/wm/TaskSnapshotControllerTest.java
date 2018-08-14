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
 * limitations under the License
 */

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.TRANSIT_UNSET;

import static com.android.server.wm.TaskSnapshotController.*;

import static junit.framework.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.android.collect.Sets;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link TaskSnapshotController}.
 *
 * runtest frameworks-services -c com.android.server.wm.TaskSnapshotControllerTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TaskSnapshotControllerTest extends WindowTestsBase {

    @Test
    public void testGetClosingApps_closing() throws Exception {
        final WindowState closingWindow = createWindow(null, FIRST_APPLICATION_WINDOW,
                "closingWindow");
        closingWindow.mAppToken.setVisibility(null, false /* visible */, TRANSIT_UNSET,
                true /* performLayout */, false /* isVoiceInteraction */);
        final ArraySet<AppWindowToken> closingApps = new ArraySet<>();
        closingApps.add(closingWindow.mAppToken);
        final ArraySet<Task> closingTasks = new ArraySet<>();
        sWm.mTaskSnapshotController.getClosingTasks(closingApps, closingTasks);
        assertEquals(1, closingTasks.size());
        assertEquals(closingWindow.mAppToken.getTask(), closingTasks.valueAt(0));
    }

    @Test
    public void testGetClosingApps_notClosing() throws Exception {
        final WindowState closingWindow = createWindow(null, FIRST_APPLICATION_WINDOW,
                "closingWindow");
        final WindowState openingWindow = createAppWindow(closingWindow.getTask(),
                FIRST_APPLICATION_WINDOW, "openingWindow");
        closingWindow.mAppToken.setVisibility(null, false /* visible */, TRANSIT_UNSET,
                true /* performLayout */, false /* isVoiceInteraction */);
        openingWindow.mAppToken.setVisibility(null, true /* visible */, TRANSIT_UNSET,
                true /* performLayout */, false /* isVoiceInteraction */);
        final ArraySet<AppWindowToken> closingApps = new ArraySet<>();
        closingApps.add(closingWindow.mAppToken);
        final ArraySet<Task> closingTasks = new ArraySet<>();
        sWm.mTaskSnapshotController.getClosingTasks(closingApps, closingTasks);
        assertEquals(0, closingTasks.size());
    }

    @Test
    public void testGetClosingApps_skipClosingAppsSnapshotTasks() throws Exception {
        final WindowState closingWindow = createWindow(null, FIRST_APPLICATION_WINDOW,
                "closingWindow");
        closingWindow.mAppToken.setVisibility(null, false /* visible */, TRANSIT_UNSET,
                true /* performLayout */, false /* isVoiceInteraction */);
        final ArraySet<AppWindowToken> closingApps = new ArraySet<>();
        closingApps.add(closingWindow.mAppToken);
        final ArraySet<Task> closingTasks = new ArraySet<>();
        sWm.mTaskSnapshotController.addSkipClosingAppSnapshotTasks(
                Sets.newArraySet(closingWindow.mAppToken.getTask()));
        sWm.mTaskSnapshotController.getClosingTasks(closingApps, closingTasks);
        assertEquals(0, closingTasks.size());
    }

    @Test
    public void testGetSnapshotMode() throws Exception {
        final WindowState disabledWindow = createWindow(null,
                FIRST_APPLICATION_WINDOW, mDisplayContent, "disabledWindow");
        disabledWindow.mAppToken.setDisablePreviewScreenshots(true);
        assertEquals(SNAPSHOT_MODE_APP_THEME,
                sWm.mTaskSnapshotController.getSnapshotMode(disabledWindow.getTask()));

        final WindowState normalWindow = createWindow(null,
                FIRST_APPLICATION_WINDOW, mDisplayContent, "normalWindow");
        assertEquals(SNAPSHOT_MODE_REAL,
                sWm.mTaskSnapshotController.getSnapshotMode(normalWindow.getTask()));

        final WindowState secureWindow = createWindow(null,
                FIRST_APPLICATION_WINDOW, mDisplayContent, "secureWindow");
        secureWindow.mAttrs.flags |= FLAG_SECURE;
        assertEquals(SNAPSHOT_MODE_APP_THEME,
                sWm.mTaskSnapshotController.getSnapshotMode(secureWindow.getTask()));
    }
}
