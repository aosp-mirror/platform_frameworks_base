/*
 * Copyright (C) 2007 The Android Open Source Project
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

import junit.framework.Assert;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.Contacts;
import android.provider.Contacts.People;
import android.test.PerformanceTestCase;
import android.test.TestCase;

import java.io.File;
import java.util.Random;

/**
 * Database Performance Tests
 * 
 */

@SuppressWarnings("deprecation")
public class DatabasePerformanceTests {

    public static String[] children() {
        return new String[] {
            ContactReadingTest1.class.getName(),
            Perf1Test.class.getName(),
            Perf2Test.class.getName(),
            Perf3Test.class.getName(),
            Perf4Test.class.getName(),
            Perf5Test.class.getName(),
            Perf6Test.class.getName(),
            Perf7Test.class.getName(),
            Perf8Test.class.getName(),
            Perf9Test.class.getName(),
            Perf10Test.class.getName(),
            Perf11Test.class.getName(),
            Perf12Test.class.getName(),
            Perf13Test.class.getName(),
            Perf14Test.class.getName(),
            Perf15Test.class.getName(),
            Perf16Test.class.getName(),
            Perf17Test.class.getName(),
            Perf18Test.class.getName(),
            Perf19Test.class.getName(),
            Perf20Test.class.getName(),
            Perf21Test.class.getName(),
            Perf22Test.class.getName(),
            Perf23Test.class.getName(),
            Perf24Test.class.getName(),
            Perf25Test.class.getName(),
            Perf26Test.class.getName(),
            Perf27Test.class.getName(),
            Perf28Test.class.getName(),
            Perf29Test.class.getName(),
            Perf30Test.class.getName(),
            Perf31Test.class.getName(),
            };
    }
       
    public static abstract class PerformanceBase implements TestCase,
            PerformanceTestCase {
        protected static final int CURRENT_DATABASE_VERSION = 42;
        protected SQLiteDatabase mDatabase;
        protected File mDatabaseFile;
        protected Context mContext;

        public void setUp(Context c) {
            mContext = c;
            mDatabaseFile = new File("/tmp", "perf_database_test.db");
            if (mDatabaseFile.exists()) {
                mDatabaseFile.delete();
            }
            mDatabase = SQLiteDatabase.openOrCreateDatabase(mDatabaseFile.getPath(), null);
            Assert.assertTrue(mDatabase != null);
            mDatabase.setVersion(CURRENT_DATABASE_VERSION);
        }

        public void tearDown() {
            mDatabase.close();
            mDatabaseFile.delete();
        }

        public boolean isPerformanceOnly() {
            return true;
        }

        // These test can only be run once.
        public int startPerformance(Intermediates intermediates) {
            return 0;
        }

        public void run() {
        }

        public String numberName(int number) {
            String result = "";

            if (number >= 1000) {
                result += numberName((number / 1000)) + " thousand";
                number = (number % 1000);

                if (number > 0) result += " ";
            }

            if (number >= 100) {
                result += ONES[(number / 100)] + " hundred";
                number = (number % 100);

                if (number > 0) result += " ";
            }

            if (number >= 20) {
                result += TENS[(number / 10)];
                number = (number % 10);

                if (number > 0) result += " ";
            }

            if (number > 0) {
                result += ONES[number];
            }

            return result;
        }
    }

    /**
     * Test reading all contact data.
     */
    public static class ContactReadingTest1 implements TestCase, PerformanceTestCase {
        private static final String[] PEOPLE_PROJECTION = new String[] {
               Contacts.People._ID, // 0
               Contacts.People.PRIMARY_PHONE_ID, // 1
               Contacts.People.TYPE, // 2
               Contacts.People.NUMBER, // 3
               Contacts.People.LABEL, // 4
               Contacts.People.NAME, // 5
               Contacts.People.PRESENCE_STATUS, // 6
        };

        private Cursor mCursor;

        public void setUp(Context c) {
            mCursor = c.getContentResolver().query(People.CONTENT_URI, PEOPLE_PROJECTION, null,
                    null, People.DEFAULT_SORT_ORDER);
        }
        
        public void tearDown() {
            mCursor.close();
        }

        public boolean isPerformanceOnly() {
            return true;
        }

        public int startPerformance(Intermediates intermediates) {
            // This test can only be run once.
            return 0;
        }

        public void run() {
            while (mCursor.moveToNext()) {
                // Read out all of the data
                mCursor.getLong(0);
                mCursor.getLong(1);
                mCursor.getLong(2);
                mCursor.getString(3);
                mCursor.getString(4);
                mCursor.getString(5);
                mCursor.getLong(6);
            }
        }
    }
    
    /**
     * Test 1000 inserts
     */
    
    public static class Perf1Test extends PerformanceBase {
        private static final int SIZE = 1000;

        private String[] statements = new String[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                statements[i] =
                        "INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                                + numberName(r) + "')";
            }

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.execSQL(statements[i]);
            }
        }
    }

    /**
     * Test 1000 inserts into and indexed table
     */
    
    public static class Perf2Test extends PerformanceBase {
        private static final int SIZE = 1000;

        private String[] statements = new String[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                statements[i] =
                        "INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                                + numberName(r) + "')";
            }

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1c ON t1(c)");
        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.execSQL(statements[i]);
            }
        }
    }

    /**
     * 100 SELECTs without an index
     */
      
    public static class Perf3Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"count(*)", "avg(b)"};

        private String[] where = new String[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                int lower = i * 100;
                int upper = (i + 10) * 100;
                where[i] = "b >= " + lower + " AND b < " + upper;
            }
        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase
                        .query("t1", COLUMNS, where[i], null, null, null, null);
            }
        }
    }

    /**
     * 100 SELECTs on a string comparison
     */
    
    public static class Perf4Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"count(*)", "avg(b)"};

        private String[] where = new String[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                where[i] = "c LIKE '" + numberName(i) + "'";
            }
        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase
                        .query("t1", COLUMNS, where[i], null, null, null, null);
            }
        }
    }

    /**
     * 100 SELECTs with an index
     */
    
    public static class Perf5Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"count(*)", "avg(b)"};

        private String[] where = new String[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1b ON t1(b)");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                int lower = i * 100;
                int upper = (i + 10) * 100;
                where[i] = "b >= " + lower + " AND b < " + upper;
            }
        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase
                        .query("t1", COLUMNS, where[i], null, null, null, null);
            }
        }
    }

    /**
     *  INNER JOIN without an index
     */
    
    public static class Perf6Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"t1.a"};

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase
              .execSQL("CREATE TABLE t2(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t2 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }
        }

        @Override
        public void run() {
            mDatabase.query("t1 INNER JOIN t2 ON t1.b = t2.b", COLUMNS, null,
                    null, null, null, null);
        }
    }

    /**
     *  INNER JOIN without an index on one side
     */
    
    public static class Perf7Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"t1.a"};

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase
              .execSQL("CREATE TABLE t2(a INTEGER, b INTEGER, c VARCHAR(100))");

            mDatabase.execSQL("CREATE INDEX i1b ON t1(b)");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t2 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }
        }

        @Override
        public void run() {
            mDatabase.query("t1 INNER JOIN t2 ON t1.b = t2.b", COLUMNS, null,
                    null, null, null, null);
        }
    }

    /**
     *  INNER JOIN without an index on one side
     */
    
    public static class Perf8Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"t1.a"};

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase
              .execSQL("CREATE TABLE t2(a INTEGER, b INTEGER, c VARCHAR(100))");

            mDatabase.execSQL("CREATE INDEX i1b ON t1(b)");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t2 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }
        }

        @Override
        public void run() {
            mDatabase.query("t1 INNER JOIN t2 ON t1.c = t2.c", COLUMNS, null,
                    null, null, null, null);
        }
    }

    /**
     *  100 SELECTs with subqueries. Subquery is using an index
     */
    
    public static class Perf9Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"t1.a"};

        private String[] where = new String[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase
              .execSQL("CREATE TABLE t2(a INTEGER, b INTEGER, c VARCHAR(100))");

            mDatabase.execSQL("CREATE INDEX i2b ON t2(b)");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t2 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                int lower = i * 100;
                int upper = (i + 10) * 100;
                where[i] =
                        "t1.b IN (SELECT t2.b FROM t2 WHERE t2.b >= " + lower
                                + " AND t2.b < " + upper + ")";
            }
        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase
                        .query("t1", COLUMNS, where[i], null, null, null, null);
            }
        }
    }

    /**
     *  100 SELECTs on string comparison with Index
     */

    public static class Perf10Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"count(*)", "avg(b)"};

        private String[] where = new String[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i3c ON t1(c)");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                where[i] = "c LIKE '" + numberName(i) + "'";
            }
        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase
                        .query("t1", COLUMNS, where[i], null, null, null, null);
            }
        }
    }

    /**
     *  100 SELECTs on integer 
     */
    
    public static class Perf11Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"b"};

        private String[] where = new String[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.query("t1", COLUMNS, null, null, null, null, null);
            }
        }
    }

    /**
     *  100 SELECTs on String
     */

    public static class Perf12Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"c"};

        private String[] where = new String[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.query("t1", COLUMNS, null, null, null, null, null);
            }
        }
    }

    /**
     *  100 SELECTs on integer with index
     */
    
    public static class Perf13Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"b"};

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1b on t1(b)");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.query("t1", COLUMNS, null, null, null, null, null);
            }
        }
    }

    /**
     *  100 SELECTs on String with index
     */

    public static class Perf14Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"c"};      

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1c ON t1(c)");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.query("t1", COLUMNS, null, null, null, null, null);
            }
        }
    }

    /**
     *  100 SELECTs on String with starts with
     */

    public static class Perf15Test extends PerformanceBase {
        private static final int SIZE = 100;
        private static final String[] COLUMNS = {"c"};
        private String[] where = new String[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1c ON t1(c)");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                where[i] = "c LIKE '" + numberName(r).substring(0, 1) + "*'";

            }

        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase
                        .query("t1", COLUMNS, where[i], null, null, null, null);
            }
        }
    }

    /**
     *  1000  Deletes on an indexed table
     */
    
    public static class Perf16Test extends PerformanceBase {
        private static final int SIZE = 1000;
        private static final String[] COLUMNS = {"c"};
        
        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i3c ON t1(c)");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.delete("t1", null, null);
            }
        }
    }

    /**
     *  1000  Deletes
     */
    
    public static class Perf17Test extends PerformanceBase {
        private static final int SIZE = 1000;
        private static final String[] COLUMNS = {"c"};       

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.delete("t1", null, null);
            }
        }
    }

    /**
     *  1000 DELETE's without an index with where clause 
     */
    
    public static class Perf18Test extends PerformanceBase {
        private static final int SIZE = 1000;
        private String[] where = new String[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                int lower = i * 100;
                int upper = (i + 10) * 100;
                where[i] = "b >= " + lower + " AND b < " + upper;
            }
        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.delete("t1", where[i], null);
            }
        }
    }

    /**
     *  1000 DELETE's with an index with where clause 
     */
    
    public static class Perf19Test extends PerformanceBase {
        private static final int SIZE = 1000;
        private String[] where = new String[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1b ON t1(b)");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                int lower = i * 100;
                int upper = (i + 10) * 100;
                where[i] = "b >= " + lower + " AND b < " + upper;
            }
        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.delete("t1", where[i], null);
            }
        }
    }

    /**
     *  1000 update's with an index with where clause 
     */
    
    public static class Perf20Test extends PerformanceBase {
        private static final int SIZE = 1000;
        private String[] where = new String[SIZE];
        ContentValues[] mValues = new ContentValues[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1b ON t1(b)");

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {

                int lower = i * 100;
                int upper = (i + 10) * 100;
                where[i] = "b >= " + lower + " AND b < " + upper;
                ContentValues b = new ContentValues(1);
                b.put("b", upper);
                mValues[i] = b;
               
            }
        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.update("t1", mValues[i], where[i], null);
            }
        }
    }

    /**
     *  1000 update's without an index with where clause 
     */
    
    public static class Perf21Test extends PerformanceBase {
        private static final int SIZE = 1000;       
        private String[] where = new String[SIZE];
        ContentValues[] mValues = new ContentValues[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
           
            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {

                int lower = i * 100;
                int upper = (i + 10) * 100;
                where[i] = "b >= " + lower + " AND b < " + upper;
                ContentValues b = new ContentValues(1);
                b.put("b", upper);
                mValues[i] = b;
            }
        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.update("t1", mValues[i], where[i], null);
            }
        }
    }
    
    /**
     *  10000 inserts for an integer 
     */
    
    public static class Perf22Test extends PerformanceBase {
        private static final int SIZE = 10000;
        ContentValues[] mValues = new ContentValues[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER)");
           
            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                ContentValues b = new ContentValues(1);
                b.put("a", r);
                mValues[i] = b;
            }
        }        

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.insert("t1", null, mValues[i]);
            }
        }
    }
    
    /**
     *  10000 inserts for an integer -indexed table
     */
    
    public static class Perf23Test extends PerformanceBase {
        private static final int SIZE = 10000;
        ContentValues[] mValues = new ContentValues[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a INTEGER)");
            mDatabase.execSQL("CREATE INDEX i1a ON t1(a)");
           
            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                ContentValues b = new ContentValues(1);
                b.put("a", r);
                mValues[i] = b;
            }
        }        

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.insert("t1", null, mValues[i]);
            }
        }
    }
    
    /**
     *  10000 inserts for a String 
     */
    
    public static class Perf24Test extends PerformanceBase {
        private static final int SIZE = 10000;
        ContentValues[] mValues = new ContentValues[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a VARCHAR(100))");
           
            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                ContentValues b = new ContentValues(1);
                b.put("a", numberName(r));
                mValues[i] = b;
            }
        }        

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.insert("t1", null, mValues[i]);
            }
        }
    }
    
    /**
     *  10000 inserts for a String - indexed table 
     */
    
    public static class Perf25Test extends PerformanceBase {
        private static final int SIZE = 10000;       
        ContentValues[] mValues = new ContentValues[SIZE];

        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t1(a VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1a ON t1(a)");
                       
            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                ContentValues b = new ContentValues(1);
                b.put("a", numberName(r));
                mValues[i] = b; 
            }
        }

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.insert("t1", null, mValues[i]);
            }
        }
    }
    
    
    /**
     *  10000 selects for a String -starts with
     */
    
    public static class Perf26Test extends PerformanceBase {
        private static final int SIZE = 10000;
        private static final String[] COLUMNS = {"t3.a"};
        private String[] where = new String[SIZE];
        
        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t3(a VARCHAR(100))");
                                  
            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t3 VALUES('"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                where[i] = "a LIKE '" + numberName(r).substring(0, 1) + "*'";

            }
        }        

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.query("t3", COLUMNS, where[i], null, null, null, null);
            }
        }
    }
    
    /**
     *  10000 selects for a String - indexed table -starts with
     */
    
    public static class Perf27Test extends PerformanceBase {
        private static final int SIZE = 10000;
        private static final String[] COLUMNS = {"t3.a"};
        private String[] where = new String[SIZE];
        
        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t3(a VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i3a ON t3(a)");
                       
            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t3 VALUES('"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                where[i] = "a LIKE '" + numberName(r).substring(0, 1) + "*'";

            }                              
           }        

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.query("t3", COLUMNS, where[i], null, null, null, null);
            }
        }
    }
    
    /**
     *  10000 selects for an integer -
     */
    
    public static class Perf28Test extends PerformanceBase {
        private static final int SIZE = 10000;
        private static final String[] COLUMNS = {"t4.a"};
        private String[] where = new String[SIZE];
        
        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t4(a INTEGER)");
           
            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t4 VALUES(" + r + ")");
                int lower = i * 100;
                int upper = (i + 10) * 100;
                where[i] = "a >= " + lower + " AND a < " + upper;
            }
           }        

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.query("t4", COLUMNS, where[i], null, null, null, null);
            }
        }
    }
    
    /**
     *  10000 selects for an integer -indexed table
     */
    
    public static class Perf29Test extends PerformanceBase {
        private static final int SIZE = 10000;
        private static final String[] COLUMNS = {"t4.a"};
        private String[] where = new String[SIZE];
       
        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t4(a INTEGER)");
           mDatabase.execSQL("CREATE INDEX i4a ON t4(a)");
           
            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t4 VALUES(" + r + ")");
                
                int lower = i * 100;
                int upper = (i + 10) * 100;
                where[i] = "a >= " + lower + " AND a < " + upper;
            }
           
           }        

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.query("t4", COLUMNS, where[i], null, null, null, null);
            }
        }
    }
    
    
    /**
     *  10000 selects for a String - contains 'e'
     */
    
    public static class Perf30Test extends PerformanceBase {
        private static final int SIZE = 10000;
        private static final String[] COLUMNS = {"t3.a"};
        private String[] where = new String[SIZE];
        
        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t3(a VARCHAR(100))");
            
            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t3 VALUES('"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                 where[i] = "a LIKE '*e*'";

            }                              
           }        

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.query("t3", COLUMNS, where[i], null, null, null, null);
            }
        }
    }
    
    /**
     *  10000 selects for a String - contains 'e'-indexed table
     */
    
    public static class Perf31Test extends PerformanceBase {
        private static final int SIZE = 10000;
        private static final String[] COLUMNS = {"t3.a"};
        private String[] where = new String[SIZE];
        
        @Override
        public void setUp(Context c) {
            super.setUp(c);
            Random random = new Random(42);

            mDatabase
              .execSQL("CREATE TABLE t3(a VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i3a ON t3(a)");
            
            for (int i = 0; i < SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t3 VALUES('"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < SIZE; i++) {
                where[i] = "a LIKE '*e*'";

            }                              
            
           }        

        @Override
        public void run() {
            for (int i = 0; i < SIZE; i++) {
                mDatabase.query("t3", COLUMNS, where[i], null, null, null, null);
            }
        }
    }
    
    public static final String[] ONES =
            {"zero", "one", "two", "three", "four", "five", "six", "seven",
                "eight", "nine", "ten", "eleven", "twelve", "thirteen",
                "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
                "nineteen"};

    public static final String[] TENS =
            {"", "ten", "twenty", "thirty", "forty", "fifty", "sixty",
                "seventy", "eighty", "ninety"};
}
