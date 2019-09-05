/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.server.pm.dex;

import static com.android.server.pm.dex.PackageDynamicCodeLoading.MAX_FILES_PER_OWNER;
import static com.android.server.pm.dex.PackageDynamicCodeLoading.escape;
import static com.android.server.pm.dex.PackageDynamicCodeLoading.unescape;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;

import static java.nio.charset.StandardCharsets.UTF_8;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.pm.dex.PackageDynamicCodeLoading.DynamicCodeFile;
import com.android.server.pm.dex.PackageDynamicCodeLoading.PackageDynamicCode;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageDynamicCodeLoadingTests {

    // Deliberately making a copy here since we're testing identity and
    // string literals have a tendency to be identical.
    private static final String TRIVIAL_STRING = new String("hello/world");
    private static final Entry[] NO_ENTRIES = {};
    private static final String[] NO_PACKAGES = {};

    @Test
    public void testRecord() {
        Entry[] entries = {
                new Entry("owning.package1", "/path/file1", 'D', 10, "loading.package1"),
                new Entry("owning.package1", "/path/file1", 'D', 10, "loading.package2"),
                new Entry("owning.package1", "/path/file2", 'D', 5, "loading.package1"),
                new Entry("owning.package2", "/path/file3", 'D', 0, "loading.package2"),
        };

        PackageDynamicCodeLoading info = makePackageDcl(entries);
        assertHasEntries(info, entries);
    }

    @Test
    public void testRecord_returnsHasChanged() {
        Entry owner1Path1Loader1 =
                new Entry("owning.package1", "/path/file1", 'D', 10, "loading.package1");
        Entry owner1Path1Loader2 =
                new Entry("owning.package1", "/path/file1", 'D', 10, "loading.package2");
        Entry owner1Path2Loader1 =
                new Entry("owning.package1", "/path/file2", 'D', 10, "loading.package1");
        Entry owner2Path1Loader1 =
                new Entry("owning.package2", "/path/file1", 'D', 10, "loading.package2");

        PackageDynamicCodeLoading info = new PackageDynamicCodeLoading();

        assertTrue(record(info, owner1Path1Loader1));
        assertFalse(record(info, owner1Path1Loader1));

        assertTrue(record(info, owner1Path1Loader2));
        assertFalse(record(info, owner1Path1Loader2));

        assertTrue(record(info, owner1Path2Loader1));
        assertFalse(record(info, owner1Path2Loader1));

        assertTrue(record(info, owner2Path1Loader1));
        assertFalse(record(info, owner2Path1Loader1));

        assertHasEntries(info,
                owner1Path1Loader1, owner1Path1Loader2, owner1Path2Loader1, owner2Path1Loader1);
    }

    @Test
    public void testRecord_changeUserForFile_throws() {
        Entry entry1 = new Entry("owning.package1", "/path/file1", 'D', 10, "loading.package1");
        Entry entry2 = new Entry("owning.package1", "/path/file1", 'D', 20, "loading.package1");

        PackageDynamicCodeLoading info = makePackageDcl(entry1);

        assertThrows(() -> record(info, entry2));
        assertHasEntries(info, entry1);
    }

    @Test
    public void testRecord_badFileType_throws() {
        Entry entry = new Entry("owning.package", "/path/file", 'Z', 10, "loading.package");
        PackageDynamicCodeLoading info = new PackageDynamicCodeLoading();

        assertThrows(() -> record(info, entry));
    }

    @Test
    public void testRecord_tooManyFiles_ignored() {
        PackageDynamicCodeLoading info = new PackageDynamicCodeLoading();
        int tooManyFiles = MAX_FILES_PER_OWNER + 1;
        for (int i = 1; i <= tooManyFiles; i++) {
            Entry entry = new Entry("owning.package", "/path/file" + i, 'D', 10, "loading.package");
            boolean added = record(info, entry);
            Set<Entry> entries = entriesFrom(info);
            if (i < tooManyFiles) {
                assertThat(entries).contains(entry);
                assertTrue(added);
            } else {
                assertThat(entries).doesNotContain(entry);
                assertFalse(added);
            }
        }
    }

    @Test
    public void testClear() {
        Entry[] entries = {
                new Entry("owner1", "file1", 'D', 10, "loader1"),
                new Entry("owner2", "file2", 'D', 20, "loader2"),
        };
        PackageDynamicCodeLoading info = makePackageDcl(entries);

        info.clear();
        assertHasEntries(info, NO_ENTRIES);
    }

    @Test
    public void testRemovePackage_present() {
        Entry other = new Entry("other", "file", 'D', 0, "loader");
        Entry[] entries = {
                new Entry("owner", "file1", 'D', 10, "loader1"),
                new Entry("owner", "file2", 'D', 20, "loader2"),
                other
        };
        PackageDynamicCodeLoading info = makePackageDcl(entries);

        assertTrue(info.removePackage("owner"));
        assertHasEntries(info, other);
        assertHasPackages(info, "other");
    }

    @Test
    public void testRemovePackage_notPresent() {
        Entry[] entries = { new Entry("owner", "file", 'D', 0, "loader") };
        PackageDynamicCodeLoading info = makePackageDcl(entries);

        assertFalse(info.removePackage("other"));
        assertHasEntries(info, entries);
    }

    @Test
    public void testRemoveUserPackage_notPresent() {
        Entry[] entries = { new Entry("owner", "file", 'D', 0, "loader") };
        PackageDynamicCodeLoading info = makePackageDcl(entries);

        assertFalse(info.removeUserPackage("other", 0));
        assertHasEntries(info, entries);
    }

    @Test
    public void testRemoveUserPackage_presentWithNoOtherUsers() {
        Entry other = new Entry("other", "file", 'D', 0, "loader");
        Entry[] entries = {
                new Entry("owner", "file1", 'D', 0, "loader1"),
                new Entry("owner", "file2", 'D', 0, "loader2"),
                other
        };
        PackageDynamicCodeLoading info = makePackageDcl(entries);

        assertTrue(info.removeUserPackage("owner", 0));
        assertHasEntries(info, other);
        assertHasPackages(info, "other");
    }

    @Test
    public void testRemoveUserPackage_presentWithUsers() {
        Entry other = new Entry("owner", "file", 'D', 1, "loader");
        Entry[] entries = {
                new Entry("owner", "file1", 'D', 0, "loader1"),
                new Entry("owner", "file2", 'D', 0, "loader2"),
                other
        };
        PackageDynamicCodeLoading info = makePackageDcl(entries);

        assertTrue(info.removeUserPackage("owner", 0));
        assertHasEntries(info, other);
    }

    @Test
    public void testRemoveFile_present() {
        Entry[] entries = {
                new Entry("package1", "file1", 'D', 0, "loader1"),
                new Entry("package1", "file2", 'D', 0, "loader1"),
                new Entry("package2", "file1", 'D', 0, "loader2"),
        };
        Entry[] expectedSurvivors = {
                new Entry("package1", "file2", 'D', 0, "loader1"),
                new Entry("package2", "file1", 'D', 0, "loader2"),
        };
        PackageDynamicCodeLoading info = makePackageDcl(entries);

        assertTrue(info.removeFile("package1", "file1", 0));
        assertHasEntries(info, expectedSurvivors);
    }

    @Test
    public void testRemoveFile_onlyEntry() {
        Entry[] entries = {
                new Entry("package1", "file1", 'D', 0, "loader1"),
        };
        PackageDynamicCodeLoading info = makePackageDcl(entries);

        assertTrue(info.removeFile("package1", "file1", 0));
        assertHasEntries(info, NO_ENTRIES);
        assertHasPackages(info, NO_PACKAGES);
    }

    @Test
    public void testRemoveFile_notPresent() {
        Entry[] entries = {
                new Entry("package1", "file2", 'D', 0, "loader1"),
        };
        PackageDynamicCodeLoading info = makePackageDcl(entries);

        assertFalse(info.removeFile("package1", "file1", 0));
        assertHasEntries(info, entries);
    }

    @Test
    public void testRemoveFile_wrongUser() {
        Entry[] entries = {
                new Entry("package1", "file1", 'D', 10, "loader1"),
        };
        PackageDynamicCodeLoading info = makePackageDcl(entries);

        assertFalse(info.removeFile("package1", "file1", 0));
        assertHasEntries(info, entries);
    }

    @Test
    public void testSyncData() {
        Map<String, Set<Integer>> packageToUsersMap = ImmutableMap.of(
                "package1", ImmutableSet.of(10, 20),
                "package2", ImmutableSet.of(20));

        Entry[] entries = {
                new Entry("deleted.packaged", "file1", 'D', 10, "package1"),
                new Entry("package1", "file2", 'D', 20, "package2"),
                new Entry("package1", "file3", 'D', 10, "package2"),
                new Entry("package1", "file3", 'D', 10, "deleted.package"),
                new Entry("package2", "file4", 'D', 20, "deleted.package"),
        };

        Entry[] expectedSurvivors = {
                new Entry("package1", "file2", 'D', 20, "package2"),
        };

        PackageDynamicCodeLoading info = makePackageDcl(entries);
        info.syncData(packageToUsersMap);
        assertHasEntries(info, expectedSurvivors);
        assertHasPackages(info, "package1");
    }

    @Test
    public void testRead_onlyHeader_emptyResult() throws Exception {
        assertHasEntries(read("DCL1"), NO_ENTRIES);
    }

    @Test
    public void testRead_noHeader_throws() {
        assertThrows(IOException.class, () -> read(""));
    }

    @Test
    public void testRead_wrongHeader_throws() {
        assertThrows(IOException.class, () -> read("DCL2"));
    }

    @Test
    public void testRead_oneEntry() throws Exception {
        String inputText = ""
                + "DCL1\n"
                + "P:owning.package\n"
                + "D:10:loading.package:/path/fi\\\\le\n";
        assertHasEntries(read(inputText),
                new Entry("owning.package", "/path/fi\\le", 'D', 10, "loading.package"));
    }

    @Test
    public void testRead_emptyPackage() throws Exception {
        String inputText = ""
                + "DCL1\n"
                + "P:owning.package\n";
        PackageDynamicCodeLoading info = read(inputText);
        assertHasEntries(info, NO_ENTRIES);
        assertHasPackages(info, NO_PACKAGES);
    }

    @Test
    public void testRead_complex() throws Exception {
        String inputText = ""
                + "DCL1\n"
                + "P:owning.package1\n"
                + "D:10:loading.package1,loading.package2:/path/file1\n"
                + "D:5:loading.package1:/path/file2\n"
                + "P:owning.package2\n"
                + "D:0:loading.package2:/path/file3";
        assertHasEntries(read(inputText),
                new Entry("owning.package1", "/path/file1", 'D', 10, "loading.package1"),
                new Entry("owning.package1", "/path/file1", 'D', 10, "loading.package2"),
                new Entry("owning.package1", "/path/file2", 'D', 5, "loading.package1"),
                new Entry("owning.package2", "/path/file3", 'D', 0, "loading.package2"));
    }

    @Test
    public void testRead_missingPackageLine_throws() {
        String inputText = ""
                + "DCL1\n"
                + "D:10:loading.package:/path/file\n";
        assertThrows(IOException.class, () -> read(inputText));
    }

    @Test
    public void testRead_malformedFile_throws() {
        String inputText = ""
                + "DCL1\n"
                + "P:owning.package\n"
                + "Hello world!\n";
        assertThrows(IOException.class, () -> read(inputText));
    }

    @Test
    public void testRead_badFileType_throws() {
        String inputText = ""
                + "DCL1\n"
                + "P:owning.package\n"
                + "X:10:loading.package:/path/file\n";
        assertThrows(IOException.class, () -> read(inputText));
    }

    @Test
    public void testRead_badUserId_throws() {
        String inputText = ""
                + "DCL1\n"
                + "P:owning.package\n"
                + "D:999999999999999999:loading.package:/path/file\n";
        assertThrows(IOException.class, () -> read(inputText));
    }

    @Test
    public void testRead_missingPackages_throws() {
        String inputText = ""
                + "DCL1\n"
                + "P:owning.package\n"
                + "D:1:,:/path/file\n";
        assertThrows(IOException.class, () -> read(inputText));
    }

    @Test
    public void testWrite_empty() throws Exception {
        assertEquals("DCL1\n", write(NO_ENTRIES));
    }

    @Test
    public void testWrite_oneEntry() throws Exception {
        String expected = ""
                + "DCL1\n"
                + "P:owning.package\n"
                + "D:10:loading.package:/path/fi\\\\le\n";
        String actual = write(
                new Entry("owning.package", "/path/fi\\le", 'D', 10, "loading.package"));
        assertEquals(expected, actual);
    }

    @Test
    public void testWrite_complex_roundTrips() throws Exception {
        // There isn't a canonical order for the output in the presence of multiple items.
        // So we just check that if we read back what we write we end up where we started.
        Entry[] entries = {
            new Entry("owning.package1", "/path/file1", 'D', 10, "loading.package1"),
            new Entry("owning.package1", "/path/file1", 'D', 10, "loading.package2"),
            new Entry("owning.package1", "/path/file2", 'D', 5, "loading.package1"),
            new Entry("owning.package2", "/path/fi\\le3", 'D', 0, "loading.package2")
        };
        assertHasEntries(read(write(entries)), entries);
    }

    @Test
    public void testWrite_failure_throws() {
        PackageDynamicCodeLoading info = makePackageDcl(
                new Entry("owning.package", "/path/fi\\le", 'D', 10, "loading.package"));
        assertThrows(IOException.class, () -> info.write(new ThrowingOutputStream()));
    }

    @Test
    public void testEscape_trivialCase_returnsSameString() {
        assertSame(TRIVIAL_STRING, escape(TRIVIAL_STRING));
    }

    @Test
    public void testEscape() {
        String input = "backslash\\newline\nreturn\r";
        String expected = "backslash\\\\newline\\nreturn\\r";
        assertEquals(expected, escape(input));
    }

    @Test
    public void testUnescape_trivialCase_returnsSameString() throws Exception {
        assertSame(TRIVIAL_STRING, unescape(TRIVIAL_STRING));
    }

    @Test
    public void testUnescape() throws Exception {
        String input = "backslash\\\\newline\\nreturn\\r";
        String expected = "backslash\\newline\nreturn\r";
        assertEquals(expected, unescape(input));
    }

    @Test
    public void testUnescape_badEscape_throws() {
        assertThrows(IOException.class, () -> unescape("this is \\bad"));
    }

    @Test
    public void testUnescape_trailingBackslash_throws() {
        assertThrows(IOException.class, () -> unescape("don't do this\\"));
    }

    @Test
    public void testEscapeUnescape_roundTrips() throws Exception {
        assertRoundTripsWithEscape("foo");
        assertRoundTripsWithEscape("\\\\\n\n\r");
        assertRoundTripsWithEscape("\\a\\b\\");
        assertRoundTripsWithEscape("\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\");
    }

    private void assertRoundTripsWithEscape(String original) throws Exception {
        assertEquals(original, unescape(escape(original)));
    }

    private boolean record(PackageDynamicCodeLoading info, Entry entry) {
        return info.record(entry.mOwningPackage, entry.mPath, entry.mFileType, entry.mUserId,
                entry.mLoadingPackage);
    }

    private PackageDynamicCodeLoading read(String inputText) throws Exception {
        ByteArrayInputStream inputStream =
                new ByteArrayInputStream(inputText.getBytes(UTF_8));

        PackageDynamicCodeLoading info = new PackageDynamicCodeLoading();
        info.read(inputStream);

        return info;
    }

    private String write(Entry... entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        makePackageDcl(entries).write(output);
        return new String(output.toByteArray(), UTF_8);
    }

    private Set<Entry> entriesFrom(PackageDynamicCodeLoading info) {
        ImmutableSet.Builder<Entry> entries = ImmutableSet.builder();
        for (String owningPackage : info.getAllPackagesWithDynamicCodeLoading()) {
            PackageDynamicCode packageInfo = info.getPackageDynamicCodeInfo(owningPackage);
            Map<String, DynamicCodeFile> usageMap = packageInfo.mFileUsageMap;
            for (Map.Entry<String, DynamicCodeFile> fileEntry : usageMap.entrySet()) {
                String path = fileEntry.getKey();
                DynamicCodeFile fileInfo = fileEntry.getValue();
                for (String loadingPackage : fileInfo.mLoadingPackages) {
                    entries.add(new Entry(owningPackage, path, fileInfo.mFileType, fileInfo.mUserId,
                            loadingPackage));
                }
            }
        }

        return entries.build();
    }

    private PackageDynamicCodeLoading makePackageDcl(Entry... entries) {
        PackageDynamicCodeLoading result = new PackageDynamicCodeLoading();
        for (Entry entry : entries) {
            result.record(entry.mOwningPackage, entry.mPath, entry.mFileType, entry.mUserId,
                    entry.mLoadingPackage);
        }
        return result;

    }

    private void assertHasEntries(PackageDynamicCodeLoading info, Entry... expected) {
        assertEquals(ImmutableSet.copyOf(expected), entriesFrom(info));
    }

    private void assertHasPackages(PackageDynamicCodeLoading info, String... expected) {
        assertEquals(ImmutableSet.copyOf(expected), info.getAllPackagesWithDynamicCodeLoading());
    }

    /**
     * Immutable representation of one entry in the dynamic code loading data (one package
     * owning one file loaded by one package). Has well-behaved equality, hash and toString
     * for ease of use in assertions.
     */
    private static class Entry {
        private final String mOwningPackage;
        private final String mPath;
        private final char mFileType;
        private final int mUserId;
        private final String mLoadingPackage;

        private Entry(String owningPackage, String path, char fileType, int userId,
                String loadingPackage) {
            mOwningPackage = owningPackage;
            mPath = path;
            mFileType = fileType;
            mUserId = userId;
            mLoadingPackage = loadingPackage;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Entry that = (Entry) o;
            return mFileType == that.mFileType
                    && mUserId == that.mUserId
                    && Objects.equals(mOwningPackage, that.mOwningPackage)
                    && Objects.equals(mPath, that.mPath)
                    && Objects.equals(mLoadingPackage, that.mLoadingPackage);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mOwningPackage, mPath, mFileType, mUserId, mLoadingPackage);
        }

        @Override
        public String toString() {
            return "Entry("
                    + "\"" + mOwningPackage + '"'
                    + ", \"" + mPath + '"'
                    + ", '" + mFileType + '\''
                    + ", " + mUserId
                    + ", \"" + mLoadingPackage + '\"'
                    + ')';
        }
    }

    private static class ThrowingOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            throw new IOException("Intentional failure");
        }
    }
}
