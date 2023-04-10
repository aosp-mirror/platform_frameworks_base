/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.rollback;

import static com.google.common.truth.Truth.assertThat;

import android.content.pm.VersionedPackage;
import android.util.Log;
import android.util.Xml;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.SystemConfig;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Scanner;

@RunWith(AndroidJUnit4.class)
public class RollbackPackageHealthObserverTest {
    private static final String LOG_TAG = "RollbackPackageHealthObserverTest";

    private SystemConfig mSysConfig;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Before
    public void setup() {
        mSysConfig = new SystemConfigTestClass();
    }

    /**
    * Subclass of SystemConfig without running the constructor.
    */
    private class SystemConfigTestClass extends SystemConfig {
        SystemConfigTestClass() {
          super(false);
        }
    }

    /**
     * Test that isAutomaticRollbackDenied works correctly when packages that are not
     * denied are sent.
     */
    @Test
    public void isRollbackAllowedTest_false() throws IOException {
        final String contents =
                "<config>\n"
                + "    <automatic-rollback-denylisted-app package=\"com.android.vending\" />\n"
                + "</config>";
        final File folder = createTempSubfolder("folder");
        createTempFile(folder, "automatic-rollback-denylisted-app.xml", contents);

        readPermissions(folder, /* Grant all permission flags */ ~0);

        assertThat(RollbackPackageHealthObserver.isAutomaticRollbackDenied(mSysConfig,
            new VersionedPackage("com.test.package", 1))).isEqualTo(false);
    }

    /**
     * Test that isAutomaticRollbackDenied works correctly when packages that are
     * denied are sent.
     */
    @Test
    public void isRollbackAllowedTest_true() throws IOException {
        final String contents =
                "<config>\n"
                + "    <automatic-rollback-denylisted-app package=\"com.android.vending\" />\n"
                + "</config>";
        final File folder = createTempSubfolder("folder");
        createTempFile(folder, "automatic-rollback-denylisted-app.xml", contents);

        readPermissions(folder, /* Grant all permission flags */ ~0);

        assertThat(RollbackPackageHealthObserver.isAutomaticRollbackDenied(mSysConfig,
            new VersionedPackage("com.android.vending", 1))).isEqualTo(true);
    }

    /**
     * Test that isAutomaticRollbackDenied works correctly when no config is present
     */
    @Test
    public void isRollbackAllowedTest_noConfig() throws IOException {
        final File folder = createTempSubfolder("folder");

        readPermissions(folder, /* Grant all permission flags */ ~0);

        assertThat(RollbackPackageHealthObserver.isAutomaticRollbackDenied(mSysConfig,
            new VersionedPackage("com.android.vending", 1))).isEqualTo(false);
    }

    /**
     * Creates folderName/fileName in the mTemporaryFolder and fills it with the contents.
     *
     * @param folder   pre-existing subdirectory of mTemporaryFolder to put the file
     * @param fileName name of the file (e.g. filename.xml) to create
     * @param contents contents to write to the file
     * @return the newly created file
     */
    private File createTempFile(File folder, String fileName, String contents)
            throws IOException {
        File file = new File(folder, fileName);
        BufferedWriter bw = new BufferedWriter(new FileWriter(file));
        bw.write(contents);
        bw.close();

        // Print to logcat for test debugging.
        Log.d(LOG_TAG, "Contents of file " + file.getAbsolutePath());
        Scanner input = new Scanner(file);
        while (input.hasNextLine()) {
            Log.d(LOG_TAG, input.nextLine());
        }

        return file;
    }

    private void readPermissions(File libraryDir, int permissionFlag) {
        final XmlPullParser parser = Xml.newPullParser();
        mSysConfig.readPermissions(parser, libraryDir, permissionFlag);
    }

    /**
     * Creates folderName/fileName in the mTemporaryFolder and fills it with the contents.
     *
     * @param folderName subdirectory of mTemporaryFolder to put the file, creating if needed
     * @return the folder
     */
    private File createTempSubfolder(String folderName)
            throws IOException {
        File folder = new File(mTemporaryFolder.getRoot(), folderName);
        folder.mkdirs();
        return folder;
    }
}
