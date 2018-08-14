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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.function.Consumer;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class DataChangedJournalTest {
    private static final String GMAIL = "com.google.gmail";
    private static final String DOCS = "com.google.docs";
    private static final String GOOGLE_PLUS = "com.google.plus";

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Mock private Consumer<String> mConsumer;

    private File mFile;
    private DataChangedJournal mJournal;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mFile = mTemporaryFolder.newFile();
        mJournal = new DataChangedJournal(mFile);
    }

    @Test
    public void addPackage_addsPackagesToEndOfFile() throws Exception {
        mJournal.addPackage(GMAIL);
        mJournal.addPackage(DOCS);
        mJournal.addPackage(GOOGLE_PLUS);

        FileInputStream fos = new FileInputStream(mFile);
        DataInputStream dos = new DataInputStream(fos);
        assertThat(dos.readUTF()).isEqualTo(GMAIL);
        assertThat(dos.readUTF()).isEqualTo(DOCS);
        assertThat(dos.readUTF()).isEqualTo(GOOGLE_PLUS);
        assertThat(dos.available()).isEqualTo(0);
    }

    @Test
    public void delete_deletesTheFile() throws Exception {
        mJournal.addPackage(GMAIL);

        mJournal.delete();

        assertThat(mFile.exists()).isFalse();
    }

    @Test
    public void equals_isTrueForTheSameFile() throws Exception {
        assertThat(mJournal.equals(new DataChangedJournal(mFile))).isTrue();
    }

    @Test
    public void equals_isFalseForDifferentFiles() throws Exception {
        assertThat(mJournal.equals(new DataChangedJournal(mTemporaryFolder.newFile()))).isFalse();
    }

    @Test
    public void forEach_iteratesThroughPackagesInFileInOrder() throws Exception {
        mJournal.addPackage(GMAIL);
        mJournal.addPackage(DOCS);

        mJournal.forEach(mConsumer);

        InOrder inOrder = Mockito.inOrder(mConsumer);
        inOrder.verify(mConsumer).accept(GMAIL);
        inOrder.verify(mConsumer).accept(DOCS);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void listJournals_returnsJournalsForEveryFileInDirectory() throws Exception {
        File folder = mTemporaryFolder.newFolder();
        DataChangedJournal.newJournal(folder);
        DataChangedJournal.newJournal(folder);

        ArrayList<DataChangedJournal> journals = DataChangedJournal.listJournals(folder);

        assertThat(journals).hasSize(2);
    }

    @Test
    public void newJournal_createsANewTemporaryFile() throws Exception {
        File folder = mTemporaryFolder.newFolder();

        DataChangedJournal.newJournal(folder);

        assertThat(folder.listFiles()).hasLength(1);
    }

    @Test
    public void toString_isSameAsFileToString() throws Exception {
        assertThat(mJournal.toString()).isEqualTo(mFile.toString());
    }
}
