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

package android.database.sqlite;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.sqlite.SQLiteCantOpenDatabaseException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.OpenParams;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SQLiteCantOpenDatabaseExceptionTest {
    private static final String TAG = "SQLiteCantOpenDatabaseExceptionTest";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private File getDatabaseFile(String name) {
        mContext.deleteDatabase(name);

        // getDatabasePath() doesn't like a filename with a / in it, so we can't use it directly.
        return new File(mContext.getDatabasePath("a").getParentFile(), name);
    }

    private void callWithExpectedMessage(File file, String expectedMessagePattern) {
        try {
            SQLiteDatabase.openDatabase(file, new OpenParams.Builder().build());
            fail("SQLiteCantOpenDatabaseException was not thrown");
        } catch (SQLiteCantOpenDatabaseException e) {
            Log.i(TAG, "Caught expected exception: " + e.getMessage());
            assertTrue(e.getMessage().matches(expectedMessagePattern));
        }
    }

    /** DB's directory doesn't exist. */
    @Test
    public void testDirectoryDoesNotExist() {
        final File file = getDatabaseFile("nonexisitentdir/mydb.db");

        callWithExpectedMessage(file, ".*: Directory .* doesn't exist");
    }

    /** File doesn't exist */
    @Test
    public void testFileDoesNotExist() {
        final File file = getDatabaseFile("mydb.db");

        callWithExpectedMessage(file, ".*: File .* doesn't exist");
    }

    /** File exists, but not readable. */
    @Test
    public void testFileNotReadable() {
        final File file = getDatabaseFile("mydb.db");

        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        db.close();

        file.setReadable(false);

        callWithExpectedMessage(file, ".*: File .* not readable");
    }

    /** Directory with the given name exists already. */
    @Test
    public void testPathIsADirectory() {
        final File file = getDatabaseFile("mydb.db");

        file.mkdirs();

        callWithExpectedMessage(file, ".*: Path .* is a directory");
    }
}
