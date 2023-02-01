/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.util;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RunWith(Parameterized.class)
public class AtomicFileTest {
    private static final String BASE_NAME = "base";
    private static final String NEW_NAME = BASE_NAME + ".new";
    private static final String LEGACY_BACKUP_NAME = BASE_NAME + ".bak";
    // The string isn't actually used, but we just need a different identifier.
    private static final String BASE_NAME_DIRECTORY = BASE_NAME + ".dir";

    private enum WriteAction {
        FINISH,
        FAIL,
        ABORT,
        READ_FINISH
    }

    private static final byte[] BASE_BYTES = "base".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EXISTING_NEW_BYTES = "unnew".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW_BYTES = "new".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEGACY_BACKUP_BYTES = "bak".getBytes(StandardCharsets.UTF_8);

    // JUnit wants every parameter to be used so make it happy.
    @Parameterized.Parameter()
    public String mUnusedTestName;
    @Nullable
    @Parameterized.Parameter(1)
    public String[] mExistingFileNames;
    @Nullable
    @Parameterized.Parameter(2)
    public WriteAction mWriteAction;
    @Nullable
    @Parameterized.Parameter(3)
    public byte[] mExpectedBytes;

    private final Instrumentation mInstrumentation =
            InstrumentationRegistry.getInstrumentation();
    private final Context mContext = mInstrumentation.getContext();

    private final File mDirectory = mContext.getFilesDir();
    private final File mBaseFile = new File(mDirectory, BASE_NAME);
    private final File mNewFile = new File(mDirectory, NEW_NAME);
    private final File mLegacyBackupFile = new File(mDirectory, LEGACY_BACKUP_NAME);

    @Parameterized.Parameters(name = "{0}")
    public static Object[][] data() {
        return new Object[][] {
                // Standard tests.
                { "none + none = none", null, null, null },
                { "none + finish = new", null, WriteAction.FINISH, NEW_BYTES },
                { "none + fail = none", null, WriteAction.FAIL, null },
                { "none + abort = none", null, WriteAction.ABORT, null },
                { "base + none = base", new String[] { BASE_NAME }, null, BASE_BYTES },
                { "base + finish = new", new String[] { BASE_NAME }, WriteAction.FINISH,
                        NEW_BYTES },
                { "base + fail = base", new String[] { BASE_NAME }, WriteAction.FAIL, BASE_BYTES },
                { "base + abort = base", new String[] { BASE_NAME }, WriteAction.ABORT,
                        BASE_BYTES },
                { "new + none = none", new String[] { NEW_NAME }, null, null },
                { "new + finish = new", new String[] { NEW_NAME }, WriteAction.FINISH, NEW_BYTES },
                { "new + fail = none", new String[] { NEW_NAME }, WriteAction.FAIL, null },
                { "new + abort = none", new String[] { NEW_NAME }, WriteAction.ABORT, null },
                { "bak + none = bak", new String[] { LEGACY_BACKUP_NAME }, null,
                        LEGACY_BACKUP_BYTES },
                { "bak + finish = new", new String[] { LEGACY_BACKUP_NAME }, WriteAction.FINISH,
                        NEW_BYTES },
                { "bak + fail = bak", new String[] { LEGACY_BACKUP_NAME }, WriteAction.FAIL,
                        LEGACY_BACKUP_BYTES },
                { "bak + abort = bak", new String[] { LEGACY_BACKUP_NAME }, WriteAction.ABORT,
                        LEGACY_BACKUP_BYTES },
                { "base & new + none = base", new String[] { BASE_NAME, NEW_NAME }, null,
                        BASE_BYTES },
                { "base & new + finish = new", new String[] { BASE_NAME, NEW_NAME },
                        WriteAction.FINISH, NEW_BYTES },
                { "base & new + fail = base", new String[] { BASE_NAME, NEW_NAME },
                        WriteAction.FAIL, BASE_BYTES },
                { "base & new + abort = base", new String[] { BASE_NAME, NEW_NAME },
                        WriteAction.ABORT, BASE_BYTES },
                { "base & bak + none = bak", new String[] { BASE_NAME, LEGACY_BACKUP_NAME }, null,
                        LEGACY_BACKUP_BYTES },
                { "base & bak + finish = new", new String[] { BASE_NAME, LEGACY_BACKUP_NAME },
                        WriteAction.FINISH, NEW_BYTES },
                { "base & bak + fail = bak", new String[] { BASE_NAME, LEGACY_BACKUP_NAME },
                        WriteAction.FAIL, LEGACY_BACKUP_BYTES },
                { "base & bak + abort = bak", new String[] { BASE_NAME, LEGACY_BACKUP_NAME },
                        WriteAction.ABORT, LEGACY_BACKUP_BYTES },
                { "new & bak + none = bak", new String[] { NEW_NAME, LEGACY_BACKUP_NAME }, null,
                        LEGACY_BACKUP_BYTES },
                { "new & bak + finish = new", new String[] { NEW_NAME, LEGACY_BACKUP_NAME },
                        WriteAction.FINISH, NEW_BYTES },
                { "new & bak + fail = bak", new String[] { NEW_NAME, LEGACY_BACKUP_NAME },
                        WriteAction.FAIL, LEGACY_BACKUP_BYTES },
                { "new & bak + abort = bak", new String[] { NEW_NAME, LEGACY_BACKUP_NAME },
                        WriteAction.ABORT, LEGACY_BACKUP_BYTES },
                { "base & new & bak + none = bak",
                        new String[] { BASE_NAME, NEW_NAME, LEGACY_BACKUP_NAME }, null,
                        LEGACY_BACKUP_BYTES },
                { "base & new & bak + finish = new",
                        new String[] { BASE_NAME, NEW_NAME, LEGACY_BACKUP_NAME },
                        WriteAction.FINISH, NEW_BYTES },
                { "base & new & bak + fail = bak",
                        new String[] { BASE_NAME, NEW_NAME, LEGACY_BACKUP_NAME }, WriteAction.FAIL,
                        LEGACY_BACKUP_BYTES },
                { "base & new & bak + abort = bak",
                        new String[] { BASE_NAME, NEW_NAME, LEGACY_BACKUP_NAME }, WriteAction.ABORT,
                        LEGACY_BACKUP_BYTES },
                // Compatibility when there is a directory in the place of base file, by replacing
                // no base with base.dir.
                { "base.dir + none = none", new String[] { BASE_NAME_DIRECTORY }, null, null },
                { "base.dir + finish = new", new String[] { BASE_NAME_DIRECTORY },
                        WriteAction.FINISH, NEW_BYTES },
                { "base.dir + fail = none", new String[] { BASE_NAME_DIRECTORY }, WriteAction.FAIL,
                        null },
                { "base.dir + abort = none", new String[] { BASE_NAME_DIRECTORY },
                        WriteAction.ABORT, null },
                { "base.dir & new + none = none", new String[] { BASE_NAME_DIRECTORY, NEW_NAME },
                        null, null },
                { "base.dir & new + finish = new", new String[] { BASE_NAME_DIRECTORY, NEW_NAME },
                        WriteAction.FINISH, NEW_BYTES },
                { "base.dir & new + fail = none", new String[] { BASE_NAME_DIRECTORY, NEW_NAME },
                        WriteAction.FAIL, null },
                { "base.dir & new + abort = none", new String[] { BASE_NAME_DIRECTORY, NEW_NAME },
                        WriteAction.ABORT, null },
                { "base.dir & bak + none = bak",
                        new String[] { BASE_NAME_DIRECTORY, LEGACY_BACKUP_NAME }, null,
                        LEGACY_BACKUP_BYTES },
                { "base.dir & bak + finish = new",
                        new String[] { BASE_NAME_DIRECTORY, LEGACY_BACKUP_NAME },
                        WriteAction.FINISH, NEW_BYTES },
                { "base.dir & bak + fail = bak",
                        new String[] { BASE_NAME_DIRECTORY, LEGACY_BACKUP_NAME }, WriteAction.FAIL,
                        LEGACY_BACKUP_BYTES },
                { "base.dir & bak + abort = bak",
                        new String[] { BASE_NAME_DIRECTORY, LEGACY_BACKUP_NAME }, WriteAction.ABORT,
                        LEGACY_BACKUP_BYTES },
                { "base.dir & new & bak + none = bak",
                        new String[] { BASE_NAME_DIRECTORY, NEW_NAME, LEGACY_BACKUP_NAME }, null,
                        LEGACY_BACKUP_BYTES },
                { "base.dir & new & bak + finish = new",
                        new String[] { BASE_NAME_DIRECTORY, NEW_NAME, LEGACY_BACKUP_NAME },
                        WriteAction.FINISH, NEW_BYTES },
                { "base.dir & new & bak + fail = bak",
                        new String[] { BASE_NAME_DIRECTORY, NEW_NAME, LEGACY_BACKUP_NAME },
                        WriteAction.FAIL, LEGACY_BACKUP_BYTES },
                { "base.dir & new & bak + abort = bak",
                        new String[] { BASE_NAME_DIRECTORY, NEW_NAME, LEGACY_BACKUP_NAME },
                        WriteAction.ABORT, LEGACY_BACKUP_BYTES },
                // Compatibility when openRead() is called between startWrite() and finishWrite() -
                // the write should still succeed if it's the first write.
                { "none + read & finish = new", null, WriteAction.READ_FINISH, NEW_BYTES },
        };
    }

    @Before
    @After
    public void deleteFiles() {
        mBaseFile.delete();
        mNewFile.delete();
        mLegacyBackupFile.delete();
    }

    @Test
    public void testAtomicFile() throws Exception {
        if (mExistingFileNames != null) {
            for (String fileName : mExistingFileNames) {
                switch (fileName) {
                    case BASE_NAME:
                        writeBytes(mBaseFile, BASE_BYTES);
                        break;
                    case NEW_NAME:
                        writeBytes(mNewFile, EXISTING_NEW_BYTES);
                        break;
                    case LEGACY_BACKUP_NAME:
                        writeBytes(mLegacyBackupFile, LEGACY_BACKUP_BYTES);
                        break;
                    case BASE_NAME_DIRECTORY:
                        assertTrue(mBaseFile.mkdir());
                        break;
                    default:
                        throw new AssertionError(fileName);
                }
            }
        }

        AtomicFile atomicFile = new AtomicFile(mBaseFile);
        if (mWriteAction != null) {
            try (FileOutputStream outputStream = atomicFile.startWrite()) {
                outputStream.write(NEW_BYTES);
                switch (mWriteAction) {
                    case FINISH:
                        atomicFile.finishWrite(outputStream);
                        break;
                    case FAIL:
                        atomicFile.failWrite(outputStream);
                        break;
                    case ABORT:
                        // Neither finishing nor failing is called upon abort.
                        break;
                    case READ_FINISH:
                        // We are only using this action when there is no base file.
                        assertThrows(FileNotFoundException.class, atomicFile::openRead);
                        atomicFile.finishWrite(outputStream);
                        break;
                    default:
                        throw new AssertionError(mWriteAction);
                }
            }
        }

        if (mExpectedBytes != null) {
            try (FileInputStream inputStream = atomicFile.openRead()) {
                assertArrayEquals(mExpectedBytes, readAllBytes(inputStream));
            }
        } else {
            assertThrows(FileNotFoundException.class, atomicFile::openRead);
        }
    }

    @Test
    public void testToString() throws Exception {
        AtomicFile atomicFile = new AtomicFile(mBaseFile);

        String toString = atomicFile.toString();

        assertThat(toString).contains("AtomicFile");
        assertThat(toString).contains(mBaseFile.getAbsolutePath());
    }

    private static void writeBytes(@NonNull File file, @NonNull byte[] bytes) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(bytes);
        }
    }

    // InputStream.readAllBytes() is introduced in Java 9. Our files are small enough so that a
    // naive implementation is okay.
    private static byte[] readAllBytes(@NonNull InputStream inputStream) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            int b;
            while ((b = inputStream.read()) != -1) {
                outputStream.write(b);
            }
            return outputStream.toByteArray();
        }
    }

    @NonNull
    public static <T extends Throwable> T assertThrows(@NonNull Class<T> expectedType,
            @NonNull ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            if (!expectedType.isInstance(t)) {
                sneakyThrow(t);
            }
            //noinspection unchecked
            return (T) t;
        }
        throw new AssertionError(String.format("Expected %s wasn't thrown",
                expectedType.getSimpleName()));
    }

    private static <T extends Throwable> void sneakyThrow(@NonNull Throwable throwable) throws T {
        //noinspection unchecked
        throw (T) throwable;
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
