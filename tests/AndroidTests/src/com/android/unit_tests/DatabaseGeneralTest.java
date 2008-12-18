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

package com.android.unit_tests;

import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.CharArrayBuffer;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Handler;
import android.os.Parcel;
import android.test.PerformanceTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.text.Collator;
import java.util.Arrays;

import junit.framework.Assert;
import junit.framework.TestCase;

import static android.database.DatabaseUtils.InsertHelper.TABLE_INFO_PRAGMA_COLUMNNAME_INDEX;
import static android.database.DatabaseUtils.InsertHelper.TABLE_INFO_PRAGMA_DEFAULT_INDEX;

public class DatabaseGeneralTest extends TestCase implements PerformanceTestCase {

    private static final String sString1 = "this is a test";
    private static final String sString2 = "and yet another test";
    private static final String sString3 = "this string is a little longer, but still a test";
    private static final String PHONE_NUMBER = "16175551212";

    private static final int CURRENT_DATABASE_VERSION = 42;
    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mDatabaseFile = new File("/sqlite_stmt_journals", "database_test.db");
        if (mDatabaseFile.exists()) {
            mDatabaseFile.delete();
        }
        mDatabase = SQLiteDatabase.openOrCreateDatabase(mDatabaseFile.getPath(), null);
        assertNotNull(mDatabase);
        mDatabase.setVersion(CURRENT_DATABASE_VERSION);
    }

    @Override
    protected void tearDown() throws Exception {
        mDatabase.close();
        mDatabaseFile.delete();
        super.tearDown();
    }

    public boolean isPerformanceOnly() {
        return false;
    }

    // These test can only be run once.
    public int startPerformance(Intermediates intermediates) {
        return 1;
    }

    private void populateDefaultTable() {
        mDatabase.execSQL("CREATE TABLE test (_id INTEGER PRIMARY KEY, data TEXT);");

        mDatabase.execSQL("INSERT INTO test (data) VALUES ('" + sString1 + "');");
        mDatabase.execSQL("INSERT INTO test (data) VALUES ('" + sString2 + "');");
        mDatabase.execSQL("INSERT INTO test (data) VALUES ('" + sString3 + "');");
    }

    @MediumTest
    public void testVersion() throws Exception {
        assertEquals(CURRENT_DATABASE_VERSION, mDatabase.getVersion());
        mDatabase.setVersion(11);
        assertEquals(11, mDatabase.getVersion());
    }

    @MediumTest
    public void testUpdate() throws Exception {
        populateDefaultTable();

        ContentValues values = new ContentValues(1);
        values.put("data", "this is an updated test");
        assertEquals(1, mDatabase.update("test", values, "_id=1", null));
        Cursor c = mDatabase.query("test", null, "_id=1", null, null, null, null);
        assertNotNull(c);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        String value = c.getString(c.getColumnIndexOrThrow("data"));
        assertEquals("this is an updated test", value);
    }

    @MediumTest
    public void testPhoneNumbersEqual() throws Exception {
        mDatabase.execSQL("CREATE TABLE phones (num TEXT);");
        mDatabase.execSQL("INSERT INTO phones (num) VALUES ('911');");
        mDatabase.execSQL("INSERT INTO phones (num) VALUES ('5555');");
        mDatabase.execSQL("INSERT INTO phones (num) VALUES ('+" + PHONE_NUMBER + "');");

        String number;
        Cursor c;

        c = mDatabase.query("phones", null,
                "PHONE_NUMBERS_EQUAL(num, '504-555-7683')", null, null, null, null);
        assertTrue(c == null || c.getCount() == 0);
        c.close();

        c = mDatabase.query("phones", null,
                "PHONE_NUMBERS_EQUAL(num, '911')", null, null, null, null);
        assertNotNull(c);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        number = c.getString(c.getColumnIndexOrThrow("num"));
        assertEquals("911", number);
        c.close();

        c = mDatabase.query("phones", null,
                "PHONE_NUMBERS_EQUAL(num, '5555')", null, null, null, null);
        assertNotNull(c);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        number = c.getString(c.getColumnIndexOrThrow("num"));
        assertEquals("5555", number);
        c.close();

        c = mDatabase.query("phones", null,
                "PHONE_NUMBERS_EQUAL(num, '180055555555')", null, null, null, null);
        assertTrue(c == null || c.getCount() == 0);
        c.close();

        c = mDatabase.query("phones", null,
                "PHONE_NUMBERS_EQUAL(num, '+" + PHONE_NUMBER + "')", null, null, null, null);
        assertNotNull(c);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        number = c.getString(c.getColumnIndexOrThrow("num"));
        assertEquals("+" + PHONE_NUMBER, number);
        c.close();

        c = mDatabase.query("phones", null,
                "PHONE_NUMBERS_EQUAL(num, '+1 (617).555-1212')", null, null, null, null);
        assertNotNull(c);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        number = c.getString(c.getColumnIndexOrThrow("num"));
        assertEquals("+" + PHONE_NUMBER, number);
        c.close();

        c = mDatabase.query("phones", null,
                "PHONE_NUMBERS_EQUAL(num, '+1 (617).555-1212p1234')", null, null, null, null);
        assertNotNull(c);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        number = c.getString(c.getColumnIndexOrThrow("num"));
        assertEquals("+" + PHONE_NUMBER, number);
        c.close();

        c = mDatabase.query("phones", null,
                "PHONE_NUMBERS_EQUAL(num, '" + PHONE_NUMBER + "')", null, null, null, null);
        assertNotNull(c);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        number = c.getString(c.getColumnIndexOrThrow("num"));
        assertEquals("+" + PHONE_NUMBER, number);
        c.close();

        c = mDatabase.query("phones", null,
                "PHONE_NUMBERS_EQUAL(num, '5551212')", null, null, null, null);
        assertNotNull(c);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        number = c.getString(c.getColumnIndexOrThrow("num"));
        assertEquals("+" + PHONE_NUMBER, number);
        c.close();

        c = mDatabase.query("phones", null,
                "PHONE_NUMBERS_EQUAL(num, '011" + PHONE_NUMBER + "')", null, null, null, null);
        assertNotNull(c);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        number = c.getString(c.getColumnIndexOrThrow("num"));
        assertEquals("+" + PHONE_NUMBER, number);
        c.close();

        c = mDatabase.query("phones", null,
                "PHONE_NUMBERS_EQUAL(num, '00" + PHONE_NUMBER + "')", null, null, null, null);
        assertNotNull(c);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        number = c.getString(c.getColumnIndexOrThrow("num"));
        assertEquals("+" + PHONE_NUMBER, number);
        c.close();
    }

    /**
     * Tests international matching issues for the PHONE_NUMBERS_EQUAL function.
     * 
     * @throws Exception
     */
    public void testPhoneNumbersEqualInternationl() throws Exception {
        Cursor c;
        String[] phoneNumbers = new String[2];

        // Russian trunk digit
        phoneNumbers[0] = "+79161234567"; // globablly dialable number
        phoneNumbers[1] = "89161234567"; // in-country dialable number
        c = mDatabase.rawQuery(
                "SELECT CASE WHEN PHONE_NUMBERS_EQUAL(?, ?) THEN 'equal' ELSE 'not equal' END",
                phoneNumbers);
        assertTrue(c.moveToFirst());
        assertEquals("equal", c.getString(0));
        c.close();

        // French trunk digit
        phoneNumbers[0] = "+33123456789"; // globablly dialable number
        phoneNumbers[1] = "0123456789"; // in-country dialable number
        c = mDatabase.rawQuery(
                "SELECT CASE WHEN PHONE_NUMBERS_EQUAL(?, ?) THEN 'equal' ELSE 'not equal' END",
                phoneNumbers);
        assertTrue(c.moveToFirst());
        assertEquals("equal", c.getString(0));
        c.close();


        // Trunk digit for city codes in the Netherlands
        phoneNumbers[0] = "+31771234567"; // globablly dialable number
        phoneNumbers[1] = "0771234567"; // in-country dialable number
        c = mDatabase.rawQuery(
                "SELECT CASE WHEN PHONE_NUMBERS_EQUAL(?, ?) THEN 'equal' ELSE 'not equal' END",
                phoneNumbers);
        assertTrue(c.moveToFirst());
        assertEquals("equal", c.getString(0));
        c.close();

        // Test broken caller ID seen on call from Thailand to the US
        phoneNumbers[0] = "+66811234567"; // in address book
        phoneNumbers[1] = "166811234567"; // came in from the network
        c = mDatabase.rawQuery(
                "SELECT CASE WHEN PHONE_NUMBERS_EQUAL(?, ?) THEN 'equal' ELSE 'not equal' END",
                phoneNumbers);
        assertTrue(c.moveToFirst());
        assertEquals("equal", c.getString(0));
        c.close();

        // Test the same in-country number with different country codes
        phoneNumbers[0] = "+33123456789";
        phoneNumbers[1] = "+1123456789";
        c = mDatabase.rawQuery(
                "SELECT CASE WHEN PHONE_NUMBERS_EQUAL(?, ?) THEN 'equal' ELSE 'not equal' END",
                phoneNumbers);
        assertTrue(c.moveToFirst());
        assertEquals("not equal", c.getString(0));
        c.close();

        // Test one number with country code and the other without
        phoneNumbers[0] = "5125551212";
        phoneNumbers[1] = "+15125551212";
        c = mDatabase.rawQuery(
                "SELECT CASE WHEN PHONE_NUMBERS_EQUAL(?, ?) THEN 'equal' ELSE 'not equal' END",
                phoneNumbers);
        assertTrue(c.moveToFirst());
        assertEquals("equal", c.getString(0));
        c.close();

        // Test two NANP numbers that only differ in the area code
        phoneNumbers[0] = "5125551212";
        phoneNumbers[1] = "6505551212";
        c = mDatabase.rawQuery(
                "SELECT CASE WHEN PHONE_NUMBERS_EQUAL(?, ?) THEN 'equal' ELSE 'not equal' END",
                phoneNumbers);
        assertTrue(c.moveToFirst());
        assertEquals("not equal", c.getString(0));
        c.close();
    }

    @MediumTest
    public void testCopyString() throws Exception {
        mDatabase.execSQL("CREATE TABLE guess (numi INTEGER, numf FLOAT, str TEXT);");
        mDatabase.execSQL(
                "INSERT INTO guess (numi,numf,str) VALUES (0,0.0,'ZoomZoomZoomZoom');");
        mDatabase.execSQL("INSERT INTO guess (numi,numf,str) VALUES (2000000000,3.1415926535,'');");
        String chinese = "\u4eac\u4ec5 \u5c3d\u5f84\u60ca";
        String[] arr = new String[1];
        arr[0] = chinese;
        mDatabase.execSQL("INSERT INTO guess (numi,numf,str) VALUES (-32768,-1.0,?)", arr);

        Cursor c;

        c = mDatabase.rawQuery("SELECT * FROM guess", null);
        
        c.moveToFirst();
        
        CharArrayBuffer buf = new CharArrayBuffer(14);
        
        String compareTo = c.getString(c.getColumnIndexOrThrow("numi"));
        int numiIdx = c.getColumnIndexOrThrow("numi");
        int numfIdx = c.getColumnIndexOrThrow("numf");
        int strIdx = c.getColumnIndexOrThrow("str");
        
        c.copyStringToBuffer(numiIdx, buf);
        assertEquals(1, buf.sizeCopied);
        assertEquals(compareTo, new String(buf.data, 0, buf.sizeCopied));
        
        c.copyStringToBuffer(strIdx, buf);
        assertEquals("ZoomZoomZoomZoom", new String(buf.data, 0, buf.sizeCopied));
        
        c.moveToNext();
        compareTo = c.getString(numfIdx);
        
        c.copyStringToBuffer(numfIdx, buf);
        assertEquals(compareTo, new String(buf.data, 0, buf.sizeCopied));
        c.copyStringToBuffer(strIdx, buf);
        assertEquals(0, buf.sizeCopied);
        
        c.moveToNext();
        c.copyStringToBuffer(numfIdx, buf);
        assertEquals(-1.0, Double.valueOf(
                new String(buf.data, 0, buf.sizeCopied)).doubleValue());
        
        c.copyStringToBuffer(strIdx, buf);
        compareTo = c.getString(strIdx);
        assertEquals(chinese, compareTo);
       
        assertEquals(chinese, new String(buf.data, 0, buf.sizeCopied));
        c.close();
    }
    
    @MediumTest
    public void testSchemaChange1() throws Exception {
        SQLiteDatabase db1 = mDatabase;
        Cursor cursor;

        db1.execSQL("CREATE TABLE db1 (_id INTEGER PRIMARY KEY, data TEXT);");

        cursor = db1.query("db1", null, null, null, null, null, null);
        assertNotNull("Cursor is null", cursor);

        db1.execSQL("CREATE TABLE db2 (_id INTEGER PRIMARY KEY, data TEXT);");

        assertEquals(0, cursor.getCount());
        cursor.deactivate();
    }

    @MediumTest
    public void testSchemaChange2() throws Exception {
        SQLiteDatabase db1 = mDatabase;
        SQLiteDatabase db2 = SQLiteDatabase.openOrCreateDatabase(mDatabaseFile, null);
        Cursor cursor;

        db1.execSQL("CREATE TABLE db1 (_id INTEGER PRIMARY KEY, data TEXT);");

        cursor = db1.query("db1", null, null, null, null, null, null);
        assertNotNull("Cursor is null", cursor);
        assertEquals(0, cursor.getCount());
        cursor.deactivate();
        // this cause exception because we're still using sqlite_prepate16 and not
        // sqlite_prepare16_v2. The v2 variant added the ability to check the
        // schema version and handle the case when the schema has changed
        // Marco Nelissen claim it was 2x slower to compile SQL statements so
        // I reverted back to the v1 variant.
        /* db2.execSQL("CREATE TABLE db2 (_id INTEGER PRIMARY KEY, data TEXT);");

        cursor = db1.query("db1", null, null, null, null, null, null);
        assertNotNull("Cursor is null", cursor);
        assertEquals(0, cursor.count());
        cursor.deactivate();
        */
    }

    @MediumTest
    public void testSchemaChange3() throws Exception {
        SQLiteDatabase db1 = mDatabase;
        SQLiteDatabase db2 = SQLiteDatabase.openOrCreateDatabase(mDatabaseFile, null);
        Cursor cursor;


        db1.execSQL("CREATE TABLE db1 (_id INTEGER PRIMARY KEY, data TEXT);");
        db1.execSQL("INSERT INTO db1 (data) VALUES ('test');");

        cursor = db1.query("db1", null, null, null, null, null, null);
        // this cause exception because we're still using sqlite_prepate16 and not
        // sqlite_prepare16_v2. The v2 variant added the ability to check the
        // schema version and handle the case when the schema has changed
        // Marco Nelissen claim it was 2x slower to compile SQL statements so
        // I reverted back to the v1 variant.
        /* db2.execSQL("CREATE TABLE db2 (_id INTEGER PRIMARY KEY, data TEXT);");

        assertNotNull("Cursor is null", cursor);
        assertEquals(1, cursor.count());
        assertTrue(cursor.first());
        assertEquals("test", cursor.getString(cursor.getColumnIndexOrThrow("data")));
        cursor.deactivate();
        */
    }

    private class ChangeObserver extends ContentObserver {
        private int mCursorNotificationCount = 0;
        private int mNotificationCount = 0;

        public int getCursorNotificationCount() {
            return mCursorNotificationCount;
        }

        public int getNotificationCount() {
            return mNotificationCount;
        }

        public ChangeObserver(boolean cursor) {
            super(new Handler());
            mCursor = cursor;
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }

        @Override
        public void onChange(boolean selfChange) {
            if (mCursor) {
                mCursorNotificationCount++;
            } else {
                mNotificationCount++;
            }
        }

        boolean mCursor;
    }

    @MediumTest
    public void testNotificationTest1() throws Exception {
        /*
        Cursor c = mContentResolver.query(Notes.CONTENT_URI,
                new String[] {Notes._ID, Notes.NOTE},
                null, null);
        c.registerContentObserver(new MyContentObserver(true));
        int count = c.count();

        MyContentObserver observer = new MyContentObserver(false);
        mContentResolver.registerContentObserver(Notes.CONTENT_URI, true, observer);

        Uri uri;

        HashMap<String, String> values = new HashMap<String, String>();
        values.put(Notes.NOTE, "test note1");
        uri = mContentResolver.insert(Notes.CONTENT_URI, values);
        assertEquals(1, mCursorNotificationCount);
        assertEquals(1, mNotificationCount);

        c.requery();
        assertEquals(count + 1, c.count());
        c.first();
        assertEquals("test note1", c.getString(c.getColumnIndex(Notes.NOTE)));
        c.updateString(c.getColumnIndex(Notes.NOTE), "test note2");
        c.commitUpdates();

        assertEquals(2, mCursorNotificationCount);
        assertEquals(2, mNotificationCount);

        mContentResolver.delete(uri, null);

        assertEquals(3, mCursorNotificationCount);
        assertEquals(3, mNotificationCount);

        mContentResolver.unregisterContentObserver(observer);
        */
    }

    @MediumTest
    public void testSelectionArgs() throws Exception {
        mDatabase.execSQL("CREATE TABLE test (_id INTEGER PRIMARY KEY, data TEXT);");
        ContentValues values = new ContentValues(1);
        values.put("data", "don't forget to handled 's");
        mDatabase.insert("test", "data", values);
        values.clear();
        values.put("data", "no apostrophes here");
        mDatabase.insert("test", "data", values);
        Cursor c = mDatabase.query(
                "test", null, "data GLOB ?", new String[]{"*'*"}, null, null, null);
        assertEquals(1, c.getCount());
        assertTrue(c.moveToFirst());
        assertEquals("don't forget to handled 's", c.getString(1));
        c.deactivate();

        // make sure code should checking null string properly so that
        // it won't crash
        try {
            mDatabase.query("test", new String[]{"_id"},
                    "_id=?", new String[]{null}, null, null, null);
            fail("expected exception not thrown");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }
    
    @MediumTest
    public void testTokenize() throws Exception {
        Cursor c;
        mDatabase.execSQL("CREATE TABLE tokens (" +
                "token TEXT COLLATE unicode," +
                "source INTEGER " +
                ");");
        String[] cols =  new String[]{"token", "source"};
        
        Assert.assertEquals(0, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT _TOKENIZE(NULL, NULL, NULL, NULL)", null));
        Assert.assertEquals(0, DatabaseUtils.longForQuery(mDatabase,
                "SELECT _TOKENIZE('tokens', NULL, NULL, NULL)", null));
        Assert.assertEquals(0, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT _TOKENIZE('tokens', 10, NULL, NULL)", null));
        Assert.assertEquals(0, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT _TOKENIZE('tokens', 10, 'some string', NULL)", null));
     
        Assert.assertEquals(3, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT _TOKENIZE('tokens', 1, 'some string ok', ' ')", null)); 
        
        // test Chinese
        String chinese = new String("\u4eac\u4ec5 \u5c3d\u5f84\u60ca"); 
        Assert.assertEquals(2, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT _TOKENIZE('tokens', 1,'" + chinese + "', ' ')", null));
        
        String icustr = new String("Fr\u00e9d\u00e9ric Hj\u00f8nnev\u00e5g");
        
        Assert.assertEquals(2, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT _TOKENIZE('tokens', 1, '" + icustr + "', ' ')", null));   
        
        Assert.assertEquals(7, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT count(*) from tokens;", null));      

        String key = DatabaseUtils.getHexCollationKey("Frederic Hjonneva");
        Assert.assertEquals(1, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT count(*) from tokens where token GLOB '" + key + "*'", null));      
        key = DatabaseUtils.getHexCollationKey("Hjonneva");
        Assert.assertEquals(1, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT count(*) from tokens where token GLOB '" + key + "*'", null));
        
        key = DatabaseUtils.getHexCollationKey("some string ok");
        Assert.assertEquals(1, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT count(*) from tokens where token GLOB '" + key + "*'", null));
        key = DatabaseUtils.getHexCollationKey("string");
        Assert.assertEquals(1, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT count(*) from tokens where token GLOB '" + key + "*'", null));
        key = DatabaseUtils.getHexCollationKey("ok");
        Assert.assertEquals(1, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT count(*) from tokens where token GLOB '" + key + "*'", null));
        
        key = DatabaseUtils.getHexCollationKey(chinese);
        String[] a = new String[1];
        a[0] = key;
        Assert.assertEquals(1, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT count(*) from tokens where token= ?", a));
        a[0] += "*";
        Assert.assertEquals(1, DatabaseUtils.longForQuery(mDatabase, 
             "SELECT count(*) from tokens where token GLOB ?", a));        

       Assert.assertEquals(1, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT count(*) from tokens where token= '" + key + "'", null));
        
        Assert.assertEquals(1, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT count(*) from tokens where token GLOB '" + key + "*'", null));        
        
        key = DatabaseUtils.getHexCollationKey("\u4eac\u4ec5");
        Assert.assertEquals(1, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT count(*) from tokens where token GLOB '" + key + "*'", null));
        
        
        Assert.assertEquals(0, DatabaseUtils.longForQuery(mDatabase, 
                "SELECT count(*) from tokens where token GLOB 'ab*'", null));        
    }
    
    @MediumTest
    public void testTransactions() throws Exception {
        mDatabase.execSQL("CREATE TABLE test (num INTEGER);");
        mDatabase.execSQL("INSERT INTO test (num) VALUES (0)");

        // Make sure that things work outside an explicit transaction.
        setNum(1);
        checkNum(1);

        // Test a single-level transaction.
        setNum(0);
        mDatabase.beginTransaction();
        setNum(1);
        mDatabase.setTransactionSuccessful();
        mDatabase.endTransaction();
        checkNum(1);
        Assert.assertFalse(mDatabase.isDbLockedByCurrentThread());

        // Test a rolled-back transaction.
        setNum(0);
        mDatabase.beginTransaction();
        setNum(1);
        mDatabase.endTransaction();
        checkNum(0);
        Assert.assertFalse(mDatabase.isDbLockedByCurrentThread());

        // We should get an error if we end a non-existent transaction.
        assertThrowsIllegalState(new Runnable() { public void run() {
            mDatabase.endTransaction();
        }});

        // We should get an error if a set a non-existent transaction as clean.
        assertThrowsIllegalState(new Runnable() { public void run() {
            mDatabase.setTransactionSuccessful();
        }});

        mDatabase.beginTransaction();
        mDatabase.setTransactionSuccessful();
        // We should get an error if we mark a transaction as clean twice.
        assertThrowsIllegalState(new Runnable() { public void run() {
            mDatabase.setTransactionSuccessful();
        }});
        // We should get an error if we begin a transaction after marking the parent as clean.
        assertThrowsIllegalState(new Runnable() { public void run() {
            mDatabase.beginTransaction();
        }});
        mDatabase.endTransaction();
        Assert.assertFalse(mDatabase.isDbLockedByCurrentThread());

        // Test a two-level transaction.
        setNum(0);
        mDatabase.beginTransaction();
        mDatabase.beginTransaction();
        setNum(1);
        mDatabase.setTransactionSuccessful();
        mDatabase.endTransaction();
        mDatabase.setTransactionSuccessful();
        mDatabase.endTransaction();
        checkNum(1);
        Assert.assertFalse(mDatabase.isDbLockedByCurrentThread());

        // Test rolling back an inner transaction.
        setNum(0);
        mDatabase.beginTransaction();
        mDatabase.beginTransaction();
        setNum(1);
        mDatabase.endTransaction();
        mDatabase.setTransactionSuccessful();
        mDatabase.endTransaction();
        checkNum(0);
        Assert.assertFalse(mDatabase.isDbLockedByCurrentThread());

        // Test rolling back an outer transaction.
        setNum(0);
        mDatabase.beginTransaction();
        mDatabase.beginTransaction();
        setNum(1);
        mDatabase.setTransactionSuccessful();
        mDatabase.endTransaction();
        mDatabase.endTransaction();
        checkNum(0);
        Assert.assertFalse(mDatabase.isDbLockedByCurrentThread());
    }

    private void setNum(int num) {
        mDatabase.execSQL("UPDATE test SET num = " + num);
    }

    private void checkNum(int num) {
        Assert.assertEquals(
                num, DatabaseUtils.longForQuery(mDatabase, "SELECT num FROM test", null));
    }

    private void assertThrowsIllegalState(Runnable r) {
        boolean ok = false;
        try {
            r.run();
        } catch (IllegalStateException e) {
            ok = true;
        }
        Assert.assertTrue(ok);
    }

    // Disable these until we can explicitly mark them as stress tests
    public void xxtestMem1() throws Exception {
        populateDefaultTable();

        for (int i = 0; i < 50000; i++) {
            Cursor cursor = mDatabase.query("test", null, null, null, null, null, null);
            cursor.moveToFirst();
            cursor.close();
//                Log.i("~~~~", "Finished round " + i);
        }
    }

    // Disable these until we can explicitly mark them as stress tests
    public void xxtestMem2() throws Exception {
        populateDefaultTable();

        for (int i = 0; i < 50000; i++) {
            Cursor cursor = mDatabase.query("test", null, null, null, null, null, null);
            cursor.close();
//                Log.i("~~~~", "Finished round " + i);
        }
    }

    // Disable these until we can explicitly mark them as stress tests
    public void xxtestMem3() throws Exception {
        populateDefaultTable();

        for (int i = 0; i < 50000; i++) {
            Cursor cursor = mDatabase.query("test", null, null, null, null, null, null);
            cursor.deactivate();
//                Log.i("~~~~", "Finished round " + i);
        }
    }

    @MediumTest
    public void testContentValues() throws Exception {
        ContentValues values = new ContentValues();
        values.put("string", "value");
        assertEquals("value", values.getAsString("string"));
        byte[] bytes = new byte[42];
        Arrays.fill(bytes, (byte) 0x28);
        values.put("byteArray", bytes);
        assertTrue(Arrays.equals(bytes, values.getAsByteArray("byteArray")));

        // Write the ContentValues to a Parcel and then read them out
        Parcel p = Parcel.obtain();
        values.writeToParcel(p, 0);
        p.setDataPosition(0);
        values = ContentValues.CREATOR.createFromParcel(p);

        // Read the values out again and make sure they're the same
        assertTrue(Arrays.equals(bytes, values.getAsByteArray("byteArray")));
        assertEquals("value", values.get("string"));
    }

    @MediumTest
    public void testTableInfoPragma() throws Exception {
        mDatabase.execSQL("CREATE TABLE pragma_test (" +
                "i INTEGER DEFAULT 1234, " +
                "j INTEGER, " +
                "s TEXT DEFAULT 'hello', " +
                "t TEXT, " +
                "'select' TEXT DEFAULT \"hello\")");
        try {
            Cursor cur = mDatabase.rawQuery("PRAGMA table_info(pragma_test)", null);
            Assert.assertEquals(5, cur.getCount());

            Assert.assertTrue(cur.moveToNext());
            Assert.assertEquals("i",
                    cur.getString(TABLE_INFO_PRAGMA_COLUMNNAME_INDEX));
            Assert.assertEquals("1234",
                    cur.getString(TABLE_INFO_PRAGMA_DEFAULT_INDEX));

            Assert.assertTrue(cur.moveToNext());
            Assert.assertEquals("j",
                    cur.getString(TABLE_INFO_PRAGMA_COLUMNNAME_INDEX));
            Assert.assertNull(cur.getString(TABLE_INFO_PRAGMA_DEFAULT_INDEX));

            Assert.assertTrue(cur.moveToNext());
            Assert.assertEquals("s",
                    cur.getString(TABLE_INFO_PRAGMA_COLUMNNAME_INDEX));
            Assert.assertEquals("'hello'",
                    cur.getString(TABLE_INFO_PRAGMA_DEFAULT_INDEX));

            Assert.assertTrue(cur.moveToNext());
            Assert.assertEquals("t",
                    cur.getString(TABLE_INFO_PRAGMA_COLUMNNAME_INDEX));
            Assert.assertNull(cur.getString(TABLE_INFO_PRAGMA_DEFAULT_INDEX));

            Assert.assertTrue(cur.moveToNext());
            Assert.assertEquals("select",
                    cur.getString(TABLE_INFO_PRAGMA_COLUMNNAME_INDEX));
            Assert.assertEquals("\"hello\"",
                    cur.getString(TABLE_INFO_PRAGMA_DEFAULT_INDEX));

            cur.close();
        } catch (Throwable t) {
            throw new RuntimeException(
                    "If you see this test fail, it's likely that something about " +
                    "sqlite's PRAGMA table_info(...) command has changed.", t);
        }
    }

    @MediumTest
    public void testInsertHelper() throws Exception {
        Cursor cur;
        ContentValues cv;
        long row;

        mDatabase.execSQL("CREATE TABLE insert_test (" +
                "_id INTEGER PRIMARY KEY, " +
                "s TEXT NOT NULL UNIQUE, " +
                "t TEXT NOT NULL DEFAULT 'hello world', " +
                "i INTEGER, " +
                "j INTEGER NOT NULL DEFAULT 1234, " +
                "'select' TEXT)");

        DatabaseUtils.InsertHelper ih =
            new DatabaseUtils.InsertHelper(mDatabase, "insert_test");

        cv = new ContentValues();
        cv.put("s", "one");
        row = ih.insert(cv);
        cur = mDatabase.rawQuery("SELECT * FROM insert_test WHERE _id == " + row, null);
        Assert.assertTrue(cur.moveToFirst());
        Assert.assertEquals("one", cur.getString(1));
        Assert.assertEquals("hello world", cur.getString(2));
        Assert.assertNull(cur.getString(3));
        Assert.assertEquals(1234, cur.getLong(4));
        Assert.assertNull(cur.getString(5));

        cv = new ContentValues();
        cv.put("s", "two");
        cv.put("t", "goodbye world");
        row = ih.insert(cv);
        cur = mDatabase.rawQuery("SELECT * FROM insert_test WHERE _id == " + row, null);
        Assert.assertTrue(cur.moveToFirst());
        Assert.assertEquals("two", cur.getString(1));
        Assert.assertEquals("goodbye world", cur.getString(2));
        Assert.assertNull(cur.getString(3));
        Assert.assertEquals(1234, cur.getLong(4));
        Assert.assertNull(cur.getString(5));

        cv = new ContentValues();
        cv.put("t", "goodbye world");
        row = ih.insert(cv);
        Assert.assertEquals(-1, row);

        cv = new ContentValues();
        cv.put("s", "three");
        cv.put("i", 2345);
        cv.put("j", 3456);
        cv.put("select", "tricky");
        row = ih.insert(cv);
        cur = mDatabase.rawQuery("SELECT * FROM insert_test WHERE _id == " + row, null);
        Assert.assertTrue(cur.moveToFirst());
        Assert.assertEquals("three", cur.getString(1));
        Assert.assertEquals("hello world", cur.getString(2));
        Assert.assertEquals(2345, cur.getLong(3));
        Assert.assertEquals(3456, cur.getLong(4));
        Assert.assertEquals("tricky", cur.getString(5));

        cv = new ContentValues();
        cv.put("s", "three");
        cv.put("i", 6789);
        row = ih.insert(cv);
        Assert.assertEquals(-1, row);
        row = ih.replace(cv);
        cur = mDatabase.rawQuery("SELECT * FROM insert_test WHERE _id == " + row, null);
        Assert.assertTrue(cur.moveToFirst());
        Assert.assertEquals("three", cur.getString(1));
        Assert.assertEquals("hello world", cur.getString(2));
        Assert.assertEquals(6789, cur.getLong(3));

        ih.close();
    }

}
