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
 * limitations under the License
 */

package com.android.server.backup;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.android.collect.Sets;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ProcessedPackagesJournalTest {
    private static final String JOURNAL_FILE_NAME = "processed";

    private static final String GOOGLE_PHOTOS = "com.google.photos";
    private static final String GMAIL = "com.google.gmail";
    private static final String GOOGLE_PLUS = "com.google.plus";

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private File mStateDirectory;
    private ProcessedPackagesJournal mProcessedPackagesJournal;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mStateDirectory = mTemporaryFolder.newFolder();
        mProcessedPackagesJournal = new ProcessedPackagesJournal(mStateDirectory);
        mProcessedPackagesJournal.init();
    }

    @Test
    public void constructor_loadsAnyPreviousJournalFromDisk() throws Exception {
        writePermanentJournalPackages(Sets.newHashSet(GOOGLE_PHOTOS, GMAIL));

        ProcessedPackagesJournal journalFromDisk =
                new ProcessedPackagesJournal(mStateDirectory);
        journalFromDisk.init();

        assertThat(journalFromDisk.hasBeenProcessed(GOOGLE_PHOTOS)).isTrue();
        assertThat(journalFromDisk.hasBeenProcessed(GMAIL)).isTrue();
    }

    @Test
    public void hasBeenProcessed_isFalseForAnyPackageFromBlankInit() {
        assertThat(mProcessedPackagesJournal.hasBeenProcessed(GOOGLE_PHOTOS)).isFalse();
        assertThat(mProcessedPackagesJournal.hasBeenProcessed(GMAIL)).isFalse();
        assertThat(mProcessedPackagesJournal.hasBeenProcessed(GOOGLE_PLUS)).isFalse();
    }

    @Test
    public void addPackage_addsPackageToObjectState() {
        mProcessedPackagesJournal.addPackage(GOOGLE_PHOTOS);

        assertThat(mProcessedPackagesJournal.hasBeenProcessed(GOOGLE_PHOTOS)).isTrue();
    }

    @Test
    public void addPackage_addsPackageToFileSystem() throws Exception {
        mProcessedPackagesJournal.addPackage(GOOGLE_PHOTOS);

        assertThat(readJournalPackages()).contains(GOOGLE_PHOTOS);
    }

    @Test
    public void getPackagesCopy_returnsTheCurrentState() throws Exception {
        mProcessedPackagesJournal.addPackage(GOOGLE_PHOTOS);
        mProcessedPackagesJournal.addPackage(GMAIL);

        assertThat(mProcessedPackagesJournal.getPackagesCopy())
                .isEqualTo(Sets.newHashSet(GOOGLE_PHOTOS, GMAIL));
    }

    @Test
    public void getPackagesCopy_returnsACopy() throws Exception {
        mProcessedPackagesJournal.getPackagesCopy().add(GMAIL);

        assertThat(mProcessedPackagesJournal.hasBeenProcessed(GMAIL)).isFalse();
    }

    @Test
    public void reset_removesAllPackagesFromObjectState() {
        mProcessedPackagesJournal.addPackage(GOOGLE_PHOTOS);
        mProcessedPackagesJournal.addPackage(GOOGLE_PLUS);
        mProcessedPackagesJournal.addPackage(GMAIL);

        mProcessedPackagesJournal.reset();

        assertThat(mProcessedPackagesJournal.hasBeenProcessed(GOOGLE_PHOTOS)).isFalse();
        assertThat(mProcessedPackagesJournal.hasBeenProcessed(GMAIL)).isFalse();
        assertThat(mProcessedPackagesJournal.hasBeenProcessed(GOOGLE_PLUS)).isFalse();
    }

    @Test
    public void reset_removesAllPackagesFromFileSystem() throws Exception {
        mProcessedPackagesJournal.addPackage(GOOGLE_PHOTOS);
        mProcessedPackagesJournal.addPackage(GOOGLE_PLUS);
        mProcessedPackagesJournal.addPackage(GMAIL);

        mProcessedPackagesJournal.reset();

        assertThat(readJournalPackages()).isEmpty();
    }

    private HashSet<String> readJournalPackages() throws Exception {
        File journal = new File(mStateDirectory, JOURNAL_FILE_NAME);
        HashSet<String> packages = new HashSet<>();

        try (FileInputStream fis = new FileInputStream(journal);
             DataInputStream dis = new DataInputStream(fis)) {
            while (dis.available() > 0) {
                packages.add(dis.readUTF());
            }
        } catch (FileNotFoundException e) {
            return new HashSet<>();
        }

        return packages;
    }

    private void writePermanentJournalPackages(Set<String> packages) throws Exception {
        File journal = new File(mStateDirectory, JOURNAL_FILE_NAME);

        try (FileOutputStream fos = new FileOutputStream(journal);
             DataOutputStream dos = new DataOutputStream(fos)) {
            for (String packageName : packages) {
                dos.writeUTF(packageName);
            }
        }
    }
}
