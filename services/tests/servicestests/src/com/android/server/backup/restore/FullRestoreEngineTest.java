/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.backup.restore;

import static com.google.common.truth.Truth.assertWithMessage;

import android.app.backup.BackupAgent;
import android.platform.test.annotations.Presubmit;
import android.system.OsConstants;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.FileMetadata;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class FullRestoreEngineTest {
    private static final String DEFAULT_PACKAGE_NAME = "package";
    private static final String DEFAULT_DOMAIN_NAME = "domain";
    private static final String NEW_PACKAGE_NAME = "new_package";
    private static final String NEW_DOMAIN_NAME = "new_domain";

    private FullRestoreEngine mRestoreEngine;

    @Before
    public void setUp() {
        mRestoreEngine = new FullRestoreEngine();
    }

    @Test
    public void shouldSkipReadOnlyDir_skipsAllReadonlyDirsAndTheirChildren() {
        // Create the file tree.
        TestFile[] testFiles = new TestFile[] {
                TestFile.dir("root"),
                TestFile.file("root/auth_token"),
                TestFile.dir("root/media"),
                TestFile.file("root/media/picture1.png"),
                TestFile.file("root/push_token.txt"),
                TestFile.dir("root/read-only-dir-1").markReadOnly().expectSkipped(),
                TestFile.dir("root/read-only-dir-1/writable-subdir").expectSkipped(),
                TestFile.file("root/read-only-dir-1/writable-subdir/writable-file").expectSkipped(),
                TestFile.dir("root/read-only-dir-1/writable-subdir/read-only-subdir-2")
                        .markReadOnly().expectSkipped(),
                TestFile.file("root/read-only-dir-1/writable-file").expectSkipped(),
                TestFile.file("root/random-stuff.txt"),
                TestFile.dir("root/database"),
                TestFile.file("root/database/users.db"),
                TestFile.dir("root/read-only-dir-2").markReadOnly().expectSkipped(),
                TestFile.file("root/read-only-dir-2/writable-file-1").expectSkipped(),
                TestFile.file("root/read-only-dir-2/writable-file-2").expectSkipped(),
        };

        assertCorrectItemsAreSkipped(testFiles);
    }

    @Test
    public void shouldSkipReadOnlyDir_onlySkipsChildrenUnderTheSamePackage() {
        TestFile[] testFiles = new TestFile[]{
                TestFile.dir("read-only-dir").markReadOnly().expectSkipped(),
                TestFile.file("read-only-dir/file").expectSkipped(),
                TestFile.file("read-only-dir/file-from-different-package")
                        .setPackage(NEW_PACKAGE_NAME),
        };

        assertCorrectItemsAreSkipped(testFiles);
    }

    @Test
    public void shouldSkipReadOnlyDir_onlySkipsChildrenUnderTheSameDomain() {
        TestFile[] testFiles = new TestFile[]{
                TestFile.dir("read-only-dir").markReadOnly().expectSkipped(),
                TestFile.file("read-only-dir/file").expectSkipped(),
                TestFile.file("read-only-dir/file-from-different-domain")
                        .setDomain(NEW_DOMAIN_NAME),
        };

        assertCorrectItemsAreSkipped(testFiles);
    }

    private void assertCorrectItemsAreSkipped(TestFile[] testFiles) {
        // Verify all directories marked with .expectSkipped are skipped.
        for (TestFile testFile : testFiles) {
            boolean actualExcluded = mRestoreEngine.shouldSkipReadOnlyDir(testFile.mMetadata);
            boolean expectedExcluded = testFile.mShouldSkip;
            assertWithMessage(testFile.mMetadata.path).that(actualExcluded).isEqualTo(
                    expectedExcluded);
        }
    }

    private static class TestFile {
        private final FileMetadata mMetadata;
        private boolean mShouldSkip;

        static TestFile dir(String path) {
            return new TestFile(path, BackupAgent.TYPE_DIRECTORY);
        }

        static TestFile file(String path) {
            return new TestFile(path, BackupAgent.TYPE_FILE);
        }

        TestFile markReadOnly() {
            mMetadata.mode = 0;
            return this;
        }

        TestFile expectSkipped() {
            mShouldSkip = true;
            return this;
        }

        TestFile setPackage(String packageName) {
            mMetadata.packageName = packageName;
            return this;
        }

        TestFile setDomain(String domain) {
            mMetadata.domain = domain;
            return this;
        }

        private TestFile(String path, int type) {
            FileMetadata metadata = new FileMetadata();
            metadata.path = path;
            metadata.type = type;
            metadata.packageName = DEFAULT_PACKAGE_NAME;
            metadata.domain = DEFAULT_DOMAIN_NAME;
            metadata.mode = OsConstants.S_IWUSR; // Mark as writable.
            mMetadata = metadata;
        }
    }
}
