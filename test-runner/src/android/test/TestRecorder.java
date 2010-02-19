/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.test;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.os.FileUtils;
import android.test.TestRunner.IntermediateTime;
import android.util.Log;
import junit.framework.Test;
import junit.framework.TestListener;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@hide} Not needed for 1.0 SDK.
 */
public class TestRecorder implements TestRunner.Listener, TestListener {
    private static final int DATABASE_VERSION = 1;
    private static SQLiteDatabase sDb;
    private Set<String> mFailedTests = new HashSet<String>();

    private static SQLiteDatabase getDatabase() {
        if (sDb == null) {
            File dir = new File(Environment.getDataDirectory(), "test_results");

            /* TODO: add a DB version number and bootstrap/upgrade methods
            * if the format of the table changes.
            */
            String dbName = "TestHarness.db";
            File file = new File(dir, dbName);
            sDb = SQLiteDatabase.openOrCreateDatabase(file.getPath(), null);

            if (sDb.getVersion() == 0) {
                int code = FileUtils.setPermissions(file.getPath(),
                        FileUtils.S_IRUSR | FileUtils.S_IWUSR |
                                FileUtils.S_IRGRP | FileUtils.S_IWGRP |
                                FileUtils.S_IROTH | FileUtils.S_IWOTH, -1, -1);
    
                if (code != 0) {
                    Log.w("TestRecorder",
                            "Set permissions for " + file.getPath() + " returned = " + code);
                }
    
                try {
                    sDb.execSQL("CREATE TABLE IF NOT EXISTS tests (_id INT PRIMARY KEY," +
                            "name TEXT," +
                            "result TEXT," +
                            "exception TEXT," +
                            "started INTEGER," +
                            "finished INTEGER," +
                            "time INTEGER," +
                            "iterations INTEGER," +
                            "allocations INTEGER," +
                            "parent INTEGER);");
                    sDb.setVersion(DATABASE_VERSION);
                } catch (Exception e) {
                    Log.e("TestRecorder", "failed to create table 'tests'", e);
                    sDb = null;
                }
            }
        }

        return sDb;
    }

    public TestRecorder() {
    }

    public void started(String className) {
        ContentValues map = new ContentValues(2);
        map.put("name", className);
        map.put("started", System.currentTimeMillis());

        // try to update the row first in case we've ran this test before.
        int rowsAffected = getDatabase().update("tests", map, "name = '" + className + "'", null);

        if (rowsAffected == 0) {
            getDatabase().insert("tests", null, map);
        }
    }

    public void finished(String className) {
        ContentValues map = new ContentValues(1);
        map.put("finished", System.currentTimeMillis());

        getDatabase().update("tests", map, "name = '" + className + "'", null);
    }

    public void performance(String className, long itemTimeNS, int iterations, List<IntermediateTime> intermediates) {
        ContentValues map = new ContentValues();
        map.put("time", itemTimeNS);
        map.put("iterations", iterations);

        getDatabase().update("tests", map, "name = '" + className + "'", null);

        if (intermediates != null && intermediates.size() > 0) {
            int n = intermediates.size();
            for (int i = 0; i < n; i++) {
                TestRunner.IntermediateTime time = intermediates.get(i);

                getDatabase().execSQL("INSERT INTO tests (name, time, parent) VALUES ('" +
                        time.name + "', " + time.timeInNS + ", " +
                        "(SELECT _id FROM tests WHERE name = '" + className + "'));");
            }
        }
    }

    public void passed(String className) {
        ContentValues map = new ContentValues();
        map.put("result", "passed");

        getDatabase().update("tests", map, "name = '" + className + "'", null);
    }

    public void failed(String className, Throwable exception) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        try {
            exception.printStackTrace(printWriter);
        } finally {
            printWriter.close();
        }
        ContentValues map = new ContentValues();
        map.put("result", "failed");
        map.put("exception", stringWriter.toString());

        getDatabase().update("tests", map, "name = '" + className + "'", null);
    }

    /**
     * Reports a test case failure.
     *
     * @param className Name of the class/test.
     * @param reason    Reason for failure.
     */
    public void failed(String className, String reason) {
        ContentValues map = new ContentValues();
        map.put("result", "failed");
        // The reason is put as the exception.
        map.put("exception", reason);
        getDatabase().update("tests", map, "name = '" + className + "'", null);
    }

    public void addError(Test test, Throwable t) {
        mFailedTests.add(test.toString());
        failed(test.toString(), t);
    }

    public void addFailure(Test test, junit.framework.AssertionFailedError t) {
        mFailedTests.add(test.toString());
        failed(test.toString(), t.getMessage());
    }

    public void endTest(Test test) {
        finished(test.toString());
        if (!mFailedTests.contains(test.toString())) {
            passed(test.toString());
        }
        mFailedTests.remove(test.toString());
    }

    public void startTest(Test test) {
        started(test.toString());
    }
}
