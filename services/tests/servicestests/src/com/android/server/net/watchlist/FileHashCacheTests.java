/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.net.watchlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.system.Os;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.HexDump;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

/**
 * atest frameworks-services -c com.android.server.net.watchlist.FileHashCacheTests
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class FileHashCacheTests {

    private static final String APK_A = "A.apk";
    private static final String APK_B = "B.apk";
    private static final String APK_A_CONTENT = "AAA";
    private static final String APK_A_ALT_CONTENT = "AAA_ALT";
    private static final String APK_B_CONTENT = "BBB";

    private static final String PERSIST_FILE_NAME_FOR_TEST = "file_hash_cache";

    // Sha256 of "AAA"
    private static final String APK_A_CONTENT_HASH =
            "CB1AD2119D8FAFB69566510EE712661F9F14B83385006EF92AEC47F523A38358";
    // Sha256 of "AAA_ALT"
    private static final String APK_A_ALT_CONTENT_HASH =
            "2AB726E3C5B316F4C7507BFCCC3861F0473523D572E0C62BA21601C20693AEF0";
    // Sha256 of "BBB"
    private static final String APK_B_CONTENT_HASH =
            "DCDB704109A454784B81229D2B05F368692E758BFA33CB61D04C1B93791B0273";

    @Before
    public void setUp() throws Exception {
        final File persistFile = getFile(PERSIST_FILE_NAME_FOR_TEST);
        persistFile.delete();
        FileHashCache.sPersistFileName = persistFile.getAbsolutePath();
        getFile(APK_A).delete();
        getFile(APK_B).delete();
        FileHashCache.sSaveDeferredDelayMillis = 0;
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testFileHashCache_generic() throws Exception {
        final File apkA = getFile(APK_A);
        final File apkB = getFile(APK_B);

        Looper.prepare();
        FileHashCache fileHashCache = new FileHashCache(new InlineHandler());

        assertFalse(getFile(PERSIST_FILE_NAME_FOR_TEST).exists());

        // No hash for non-existing files.
        assertNull("Found existing entry in the cache",
                fileHashCache.getSha256HashFromCache(apkA));
        assertNull("Found existing entry in the cache",
                fileHashCache.getSha256HashFromCache(apkB));
        try {
            fileHashCache.getSha256Hash(apkA);
            fail("Not reached");
        } catch (IOException e) { }
        try {
            fileHashCache.getSha256Hash(apkB);
            fail("Not reached");
        } catch (IOException e) { }

        assertFalse(getFile(PERSIST_FILE_NAME_FOR_TEST).exists());
        FileUtils.stringToFile(apkA, APK_A_CONTENT);
        FileUtils.stringToFile(apkB, APK_B_CONTENT);

        assertEquals(APK_A_CONTENT_HASH, HexDump.toHexString(fileHashCache.getSha256Hash(apkA)));
        assertTrue(getFile(PERSIST_FILE_NAME_FOR_TEST).exists());
        assertEquals(APK_B_CONTENT_HASH, HexDump.toHexString(fileHashCache.getSha256Hash(apkB)));
        assertEquals(APK_A_CONTENT_HASH,
                HexDump.toHexString(fileHashCache.getSha256HashFromCache(apkA)));
        assertEquals(APK_B_CONTENT_HASH,
                HexDump.toHexString(fileHashCache.getSha256HashFromCache(apkB)));

        // Recreate handler. It should read persistent state.
        fileHashCache = new FileHashCache(new InlineHandler());
        assertEquals(APK_A_CONTENT_HASH,
                HexDump.toHexString(fileHashCache.getSha256HashFromCache(apkA)));
        assertEquals(APK_B_CONTENT_HASH,
                HexDump.toHexString(fileHashCache.getSha256HashFromCache(apkB)));

        // Modify one APK. Cache entry should be invalidated. Make sure that FS timestamp resolution
        // allows us to detect update.
        final long before = Os.stat(apkA.getAbsolutePath()).st_ctime;
        do {
            FileUtils.stringToFile(apkA, APK_A_ALT_CONTENT);
        } while (android.system.Os.stat(apkA.getAbsolutePath()).st_ctime == before);

        assertNull("Found stale entry in the cache", fileHashCache.getSha256HashFromCache(apkA));
        assertEquals(APK_A_ALT_CONTENT_HASH,
                HexDump.toHexString(fileHashCache.getSha256Hash(apkA)));
    }

    // Helper handler that executes tasks inline in context of current thread if time is good for
    // this.
    private static class InlineHandler extends Handler {
        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            if (SystemClock.uptimeMillis() >= uptimeMillis && getLooper().isCurrentThread()) {
                dispatchMessage(msg);
                return true;
            }
            return super.sendMessageAtTime(msg, uptimeMillis);
        }
    }

    private File getFile(@NonNull String name) {
        return new File(InstrumentationRegistry.getContext().getFilesDir(), name);
    }
}
