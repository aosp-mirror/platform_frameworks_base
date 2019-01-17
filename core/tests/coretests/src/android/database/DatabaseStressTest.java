/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;
import android.util.Log;

import androidx.test.filters.Suppress;

import java.io.File;

// This test suite is too desctructive and takes too long to be included in the
// automated suite.
@Suppress
public class DatabaseStressTest extends AndroidTestCase {
    private static final String TAG = "DatabaseStressTest";    
    private static final int CURRENT_DATABASE_VERSION = 1;
    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Context c = getContext();
        
        mDatabaseFile = c.getDatabasePath("database_test.db");
        if (mDatabaseFile.exists()) {
            mDatabaseFile.delete();
        }
                
        mDatabase = c.openOrCreateDatabase("database_test.db", 0, null);            
       
        assertNotNull(mDatabase);
        mDatabase.setVersion(CURRENT_DATABASE_VERSION);
        
        mDatabase.execSQL("CREATE TABLE IF NOT EXISTS test (_id INTEGER PRIMARY KEY, data TEXT);");

    }

    @Override
    protected void tearDown() throws Exception {
        mDatabase.close();
        mDatabaseFile.delete();
        super.tearDown();
    }

    public void testSingleThreadInsertDelete() {        
        int i = 0;
        char[] ch = new char[100000];
        String str = new String(ch);
        String[] strArr = new String[1];
        strArr[0] = str;
        for (; i < 10000; ++i) {
            try {
                mDatabase.execSQL("INSERT INTO test (data) VALUES (?)", strArr);
                mDatabase.execSQL("delete from test;");
            } catch (Exception e) {
                Log.e(TAG, "exception " + e.getMessage());                
            }
        }        
    }
   
    /**
     * use fillup -p 90 before run the test
     * and when disk run out
     * start delete some fillup files
     * and see if db recover
     */
    public void testOutOfSpace() {
        int i = 0;
        char[] ch = new char[100000];
        String str = new String(ch);
        String[] strArr = new String[1];
        strArr[0] = str;
        for (; i < 10000; ++i) {
            try {
                mDatabase.execSQL("INSERT INTO test (data) VALUES (?)", strArr);
            } catch (Exception e) {
                Log.e(TAG, "exception " + e.getMessage());                
            }
        }        
    }
}
