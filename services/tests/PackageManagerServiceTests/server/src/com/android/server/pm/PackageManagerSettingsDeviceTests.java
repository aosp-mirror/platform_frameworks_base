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

package com.android.server.pm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.PropertyInvalidatedCache;
import android.os.Message;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.permission.persistence.RuntimePermissionsPersistence;
import com.android.server.LocalServices;
import com.android.server.pm.permission.LegacyPermissionDataProvider;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageManagerSettingsDeviceTests {
    @Mock
    RuntimePermissionsPersistence mRuntimePermissionsPersistence;
    @Mock
    LegacyPermissionDataProvider mPermissionDataProvider;
    @Mock
    DomainVerificationManagerInternal mDomainVerificationManager;
    @Mock
    Computer mComputer;

    final TestHandler mHandler = new TestHandler((@NonNull Message msg) -> {
        return true;
    });

    @Before
    public void initializeMocks() {
        MockitoAnnotations.initMocks(this);
        when(mDomainVerificationManager.generateNewId())
                .thenAnswer(invocation -> UUID.randomUUID());
    }

    @Before
    public void setup() {
        // Disable binder caches in this process.
        PropertyInvalidatedCache.disableForTestMode();
    }

    @Before
    public void createUserManagerServiceRef() throws ReflectiveOperationException {
        InstrumentationRegistry.getInstrumentation().runOnMainSync((Runnable) () -> {
            try {
                // unregister the user manager from the local service
                LocalServices.removeServiceForTest(UserManagerInternal.class);
                new UserManagerService(InstrumentationRegistry.getContext());
            } catch (Exception e) {
                e.printStackTrace();
                fail("Could not create user manager service; " + e);
            }
        });
    }

    // Write valid packages.xml, compare with the reserve copy.
    @Test
    public void testWriteBinaryXmlSettings() throws Exception {
        // write out files and read
        PackageManagerSettingsTests.writeOldFiles();
        Settings settings = makeSettings();
        assertTrue(settings.readLPw(mComputer, PackageManagerSettingsTests.createFakeUsers()));

        // write out
        settings.writeLPr(mComputer, /*sync=*/true);

        File filesDir = InstrumentationRegistry.getContext().getFilesDir();
        File packageXml = new File(filesDir, "system/packages.xml");
        File packagesReserveCopyXml = new File(filesDir, "system/packages.xml.reservecopy");
        // Primary.
        assertTrue(packageXml.exists());
        // For the test, we need a file with at least 2 pages.
        assertThat(packageXml.length(), greaterThan(4096L));
        // Reserve copy.
        assertTrue(packagesReserveCopyXml.exists());
        // Temporary backup.
        assertFalse(new File(filesDir, "packages-backup.xml").exists());

        // compare two copies, make sure they are the same
        assertTrue(Arrays.equals(Files.readAllBytes(Path.of(packageXml.getAbsolutePath())),
                Files.readAllBytes(Path.of(packagesReserveCopyXml.getAbsolutePath()))));
    }

    @Test
    public void testWriteTextXmlSettings() throws Exception {
        testWriteBinaryXmlSettings();

        PackageManagerSettingsTests.writePackagesXml("system/packages.xml");

        File filesDir = InstrumentationRegistry.getContext().getFilesDir();
        File packageXml = new File(filesDir, "system/packages.xml");

        assertTrue(packageXml.exists());
        // For the test, we need a file with at least 2 pages.
        assertThat(packageXml.length(), greaterThan(4096L));
    }

    // Read settings, verify.
    @Test
    public void testReadSettings() throws Exception {
        // This test can be run separately. In this case packages.xml is missing.
        assumeTrue(new File(InstrumentationRegistry.getContext().getFilesDir(),
                "system/packages.xml").exists());
        Settings settings = makeSettings();
        assertTrue(settings.readLPw(mComputer, PackageManagerSettingsTests.createFakeUsers()));
        PackageManagerSettingsTests.verifyKeySetMetaData(settings);
    }

    private Settings makeSettings() {
        return new Settings(InstrumentationRegistry.getContext().getFilesDir(),
                mRuntimePermissionsPersistence, mPermissionDataProvider,
                mDomainVerificationManager, mHandler,
                new PackageManagerTracedLock());
    }
}
