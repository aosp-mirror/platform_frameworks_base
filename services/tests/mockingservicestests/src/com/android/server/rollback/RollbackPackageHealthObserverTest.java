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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.util.Log;
import android.util.Xml;

import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.PackageWatchdog;
import com.android.server.SystemConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Scanner;


@RunWith(AndroidJUnit4.class)
public class RollbackPackageHealthObserverTest {
    @Mock
    private Context mMockContext;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private PackageWatchdog mMockPackageWatchdog;
    @Mock
    RollbackManager mRollbackManager;
    @Mock
    RollbackInfo mRollbackInfo;
    @Mock
    PackageRollbackInfo mPackageRollbackInfo;

    private MockitoSession mSession;
    private static final String APP_A = "com.package.a";
    private static final long VERSION_CODE = 1L;
    private static final String LOG_TAG = "RollbackPackageHealthObserverTest";

    private SystemConfig mSysConfig;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Before
    public void setup() {
        mSysConfig = new SystemConfigTestClass();

        mSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(PackageWatchdog.class)
                .startMocking();

        // Mock PackageWatchdog
        doAnswer((Answer<PackageWatchdog>) invocationOnMock -> mMockPackageWatchdog)
                .when(() -> PackageWatchdog.getInstance(mMockContext));

    }

    @After
    public void tearDown() throws Exception {
        mSession.finishMocking();
    }

    /**
     * Subclass of SystemConfig without running the constructor.
     */
    private class SystemConfigTestClass extends SystemConfig {
        SystemConfigTestClass() {
            super(false);
        }
    }

    @Test
    public void testHealthCheckLevels() {
        RollbackPackageHealthObserver observer =
                spy(new RollbackPackageHealthObserver(mMockContext));
        VersionedPackage testFailedPackage = new VersionedPackage(APP_A, VERSION_CODE);


        when(mMockContext.getSystemService(RollbackManager.class)).thenReturn(mRollbackManager);

        // Crashes with no rollbacks available
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_0,
                observer.onHealthCheckFailed(null,
                        PackageWatchdog.FAILURE_REASON_NATIVE_CRASH, 1));
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_0,
                observer.onHealthCheckFailed(null,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 1));

        // Make the rollbacks available
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of(mRollbackInfo));
        when(mRollbackInfo.getPackages()).thenReturn(List.of(mPackageRollbackInfo));
        when(mPackageRollbackInfo.getVersionRolledBackFrom()).thenReturn(testFailedPackage);

        // native crash
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_30,
                observer.onHealthCheckFailed(null,
                        PackageWatchdog.FAILURE_REASON_NATIVE_CRASH, 1));
        // non-native crash
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_30,
                observer.onHealthCheckFailed(testFailedPackage,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 1));
        // Second non-native crash again
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_70,
                observer.onHealthCheckFailed(testFailedPackage,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 2));
        // Subsequent crashes when rollbacks have completed
        when(mRollbackManager.getAvailableRollbacks()).thenReturn(List.of());
        assertEquals(PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_LEVEL_0,
                observer.onHealthCheckFailed(testFailedPackage,
                        PackageWatchdog.FAILURE_REASON_APP_CRASH, 3));
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
