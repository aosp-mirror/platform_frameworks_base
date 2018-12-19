/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app.backup;

import static com.google.common.truth.Truth.assertThat;

import android.app.backup.FullBackup.BackupScheme.PathWithRequiredFlags;
import android.content.Context;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.internal.DoNotInstrument;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RunWith(RobolectricTestRunner.class)
@Presubmit
@DoNotInstrument
public class BackupUtilsTest {
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext = RuntimeEnvironment.application;
    }

    @Test
    public void testIsFileSpecifiedInPathList_whenFileAndPathListHasIt() throws Exception {
        boolean isSpecified =
                BackupUtils.isFileSpecifiedInPathList(file("a/b.txt"), paths(file("a/b.txt")));

        assertThat(isSpecified).isTrue();
    }

    @Test
    public void testIsFileSpecifiedInPathList_whenFileAndPathListHasItsDirectory()
            throws Exception {
        boolean isSpecified =
                BackupUtils.isFileSpecifiedInPathList(file("a/b.txt"), paths(directory("a")));

        assertThat(isSpecified).isTrue();
    }

    @Test
    public void testIsFileSpecifiedInPathList_whenFileAndPathListHasOtherFile() throws Exception {
        boolean isSpecified =
                BackupUtils.isFileSpecifiedInPathList(file("a/b.txt"), paths(file("a/c.txt")));

        assertThat(isSpecified).isFalse();
    }

    @Test
    public void testIsFileSpecifiedInPathList_whenFileAndPathListEmpty() throws Exception {
        boolean isSpecified = BackupUtils.isFileSpecifiedInPathList(file("a/b.txt"), paths());

        assertThat(isSpecified).isFalse();
    }

    @Test
    public void testIsFileSpecifiedInPathList_whenDirectoryAndPathListHasIt() throws Exception {
        boolean isSpecified =
                BackupUtils.isFileSpecifiedInPathList(directory("a"), paths(directory("a")));

        assertThat(isSpecified).isTrue();
    }

    @Test
    public void testIsFileSpecifiedInPathList_whenDirectoryAndPathListEmpty() throws Exception {
        boolean isSpecified = BackupUtils.isFileSpecifiedInPathList(directory("a"), paths());

        assertThat(isSpecified).isFalse();
    }

    @Test
    public void testIsFileSpecifiedInPathList_whenDirectoryAndPathListHasParent() throws Exception {
        boolean isSpecified =
                BackupUtils.isFileSpecifiedInPathList(directory("a/b"), paths(directory("a")));

        assertThat(isSpecified).isFalse();
    }

    @Test
    public void testIsFileSpecifiedInPathList_whenFileAndPathListDoesntContainDirectory()
            throws Exception {
        boolean isSpecified =
                BackupUtils.isFileSpecifiedInPathList(file("a/b.txt"), paths(directory("c")));

        assertThat(isSpecified).isFalse();
    }

    @Test
    public void testIsFileSpecifiedInPathList_whenFileAndPathListHasDirectoryWhoseNameIsPrefix()
            throws Exception {
        boolean isSpecified =
                BackupUtils.isFileSpecifiedInPathList(file("a/b.txt"), paths(directory("a/b")));

        assertThat(isSpecified).isFalse();
    }

    @Test
    public void testIsFileSpecifiedInPathList_whenFileAndPathListHasDirectoryWhoseNameIsPrefix2()
            throws Exception {
        boolean isSpecified =
                BackupUtils.isFileSpecifiedInPathList(
                        file("name/subname.txt"), paths(directory("nam")));

        assertThat(isSpecified).isFalse();
    }

    @Test
    public void
            testIsFileSpecifiedInPathList_whenFileAndPathListContainsFirstNotRelatedAndSecondContainingDirectory()
                    throws Exception {
        boolean isSpecified =
                BackupUtils.isFileSpecifiedInPathList(
                        file("a/b.txt"), paths(directory("b"), directory("a")));

        assertThat(isSpecified).isTrue();
    }

    @Test
    public void
            testIsFileSpecifiedInPathList_whenDirectoryAndPathListContainsFirstNotRelatedAndSecondSameDirectory()
                    throws Exception {
        boolean isSpecified =
                BackupUtils.isFileSpecifiedInPathList(
                        directory("a/b"), paths(directory("b"), directory("a/b")));

        assertThat(isSpecified).isTrue();
    }

    @Test
    public void
            testIsFileSpecifiedInPathList_whenFileAndPathListContainsFirstNotRelatedFileAndSecondSameFile()
                    throws Exception {
        boolean isSpecified =
                BackupUtils.isFileSpecifiedInPathList(
                        file("a/b.txt"), paths(directory("b"), file("a/b.txt")));

        assertThat(isSpecified).isTrue();
    }

    private File file(String path) throws IOException {
        File file = new File(mContext.getDataDir(), path);
        File parent = file.getParentFile();
        parent.mkdirs();
        file.createNewFile();
        if (!file.isFile()) {
            throw new IOException("Couldn't create file");
        }
        return file;
    }

    private File directory(String path) throws IOException {
        File directory = new File(mContext.getDataDir(), path);
        directory.mkdirs();
        if (!directory.isDirectory()) {
            throw new IOException("Couldn't create directory");
        }
        return directory;
    }

    private Collection<PathWithRequiredFlags> paths(File... files) {
        return Stream.of(files)
                .map(file -> new PathWithRequiredFlags(file.getPath(), 0))
                .collect(Collectors.toList());
    }
}
