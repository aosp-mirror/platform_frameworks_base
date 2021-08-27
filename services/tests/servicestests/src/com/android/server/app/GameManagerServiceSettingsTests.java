/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.app;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.util.AtomicFile;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class GameManagerServiceSettingsTests {

    private static final String TAG = "GameServiceSettingsTests";
    private static final String PACKAGE_NAME_1 = "com.android.app1";
    private static final String PACKAGE_NAME_2 = "com.android.app2";
    private static final String PACKAGE_NAME_3 = "com.android.app3";

    private void writeFile(File file, byte[] data) {
        file.mkdirs();
        try {
            AtomicFile aFile = new AtomicFile(file);
            FileOutputStream fos = aFile.startWrite();
            fos.write(data);
            aFile.finishWrite(fos);
        } catch (IOException ioe) {
            Log.e(TAG, "Cannot write file " + file.getPath());
        }
    }

    private void writeGameServiceXml() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(),
                        "system/game-manager-service.xml"),
                ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                        + "<packages>\n"
                        + "  <package name=\"com.android.app1\" gameMode=\"1\">\n"
                        + "  </package>\n"
                        + "  <package name=\"com.android.app2\" gameMode=\"2\">\n"
                        + "  </package>\n"
                        + "  <package name=\"com.android.app3\" gameMode=\"3\">\n"
                        + "  </package>\n"
                        + "</packages>\n").getBytes());
    }

    private void deleteSystemFolder() {
        File systemFolder = new File(InstrumentationRegistry.getContext().getFilesDir(), "system");
        deleteFolder(systemFolder);
    }

    private static void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFolder(file);
            }
        }
        folder.delete();
    }

    private void writeOldFiles() {
        deleteSystemFolder();
        writeGameServiceXml();
    }

    private void verifyGameServiceSettingsData(GameManagerSettings settings) {
        assertThat(settings.getGameModeLocked(PACKAGE_NAME_1), is(1));
        assertThat(settings.getGameModeLocked(PACKAGE_NAME_2), is(2));
        assertThat(settings.getGameModeLocked(PACKAGE_NAME_3), is(3));
    }

    @After
    public void tearDown() throws Exception {
        deleteFolder(InstrumentationRegistry.getTargetContext().getFilesDir());
    }

    /** read in data and verify */
    @Test
    public void testReadGameServiceSettings() {
        /* write out files and read */
        writeOldFiles();
        final Context context = InstrumentationRegistry.getContext();
        GameManagerSettings settings = new GameManagerSettings(context.getFilesDir());
        assertThat(settings.readPersistentDataLocked(), is(true));
        verifyGameServiceSettingsData(settings);
    }

    /** read in data, write it out, and read it back in.  Verify same. */
    @Test
    public void testWriteGameServiceSettings() {
        // write out files and read
        writeOldFiles();
        final Context context = InstrumentationRegistry.getContext();
        GameManagerSettings settings = new GameManagerSettings(context.getFilesDir());
        assertThat(settings.readPersistentDataLocked(), is(true));

        // write out, read back in and verify the same
        settings.writePersistentDataLocked();
        assertThat(settings.readPersistentDataLocked(), is(true));
        verifyGameServiceSettingsData(settings);
    }
}
