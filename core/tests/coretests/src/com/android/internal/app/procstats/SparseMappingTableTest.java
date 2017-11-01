/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.app.procstats;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;

import android.os.BatteryStats;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.mockito.Mockito;

/**
 * Provides test cases for SparseMappingTable.
 */
public class SparseMappingTableTest extends TestCase {
    private static final String TAG = "SparseMappingTableTest";

    final byte ID1 = 1;
    final byte ID2 = 2;

    final long VALUE1 = 100;
    final long VALUE2 = 10000000000L;

    /**
     * Test the parceling and unparceling logic when there is no data.
     */
    @SmallTest
    public void testParcelingEmpty() throws Exception  {
        final SparseMappingTable data = new SparseMappingTable();
        final SparseMappingTable.Table table = new SparseMappingTable.Table(data);

        final Parcel dataParcel = Parcel.obtain();
        data.writeToParcel(dataParcel);

        final Parcel tableParcel = Parcel.obtain();
        table.writeToParcel(tableParcel);

        dataParcel.setDataPosition(0);
        final SparseMappingTable data1 = new SparseMappingTable();
        data1.readFromParcel(dataParcel);
        Assert.assertEquals(0, dataParcel.dataAvail());
        dataParcel.recycle();

        tableParcel.setDataPosition(0);
        final SparseMappingTable.Table table1 = new SparseMappingTable.Table(data1);
        table1.readFromParcel(tableParcel);
        Assert.assertEquals(0, tableParcel.dataAvail());
        tableParcel.recycle();
    }

    /**
     * Test the parceling and unparceling logic.
     */
    @SmallTest
    public void testParceling() throws Exception  {
        int key;
        final SparseMappingTable data = new SparseMappingTable();
        final SparseMappingTable.Table table = new SparseMappingTable.Table(data);

        key = table.getOrAddKey(ID1, 1);
        table.setValue(key, VALUE1);

        key = table.getOrAddKey(ID2, 1);
        table.setValue(key, VALUE2);

        final Parcel dataParcel = Parcel.obtain();
        data.writeToParcel(dataParcel);

        final Parcel tableParcel = Parcel.obtain();
        table.writeToParcel(tableParcel);

        dataParcel.setDataPosition(0);
        final SparseMappingTable data1 = new SparseMappingTable();
        data1.readFromParcel(dataParcel);
        Assert.assertEquals(0, dataParcel.dataAvail());
        dataParcel.recycle();

        tableParcel.setDataPosition(0);
        final SparseMappingTable.Table table1 = new SparseMappingTable.Table(data1);
        table1.readFromParcel(tableParcel);
        Assert.assertEquals(0, tableParcel.dataAvail());
        tableParcel.recycle();

        key = table1.getKey(ID1);
        Assert.assertEquals(VALUE1, table1.getValue(key));

        key = table1.getKey(ID2);
        Assert.assertEquals(VALUE2, table1.getValue(key));
    }


    /**
     * Test that after resetting you can still read data, you just get no values.
     */
    @SmallTest
    public void testParcelingWithReset() throws Exception  {
        int key;
        final SparseMappingTable data = new SparseMappingTable();
        final SparseMappingTable.Table table = new SparseMappingTable.Table(data);

        key = table.getOrAddKey(ID1, 1);
        table.setValue(key, VALUE1);

        data.reset();
        table.resetTable();

        key = table.getOrAddKey(ID2, 1);
        table.setValue(key, VALUE2);

        Log.d(TAG, "before: " + data.dumpInternalState(true));
        Log.d(TAG, "before: " + table.dumpInternalState());

        final Parcel dataParcel = Parcel.obtain();
        data.writeToParcel(dataParcel);

        final Parcel tableParcel = Parcel.obtain();
        table.writeToParcel(tableParcel);

        dataParcel.setDataPosition(0);
        final SparseMappingTable data1 = new SparseMappingTable();
        data1.readFromParcel(dataParcel);
        Assert.assertEquals(0, dataParcel.dataAvail());
        dataParcel.recycle();

        tableParcel.setDataPosition(0);
        final SparseMappingTable.Table table1 = new SparseMappingTable.Table(data1);
        table1.readFromParcel(tableParcel);
        Assert.assertEquals(0, tableParcel.dataAvail());
        tableParcel.recycle();

        key = table1.getKey(ID1);
        Assert.assertEquals(SparseMappingTable.INVALID_KEY, key);

        key = table1.getKey(ID2);
        Assert.assertEquals(VALUE2, table1.getValue(key));

        Log.d(TAG, " after: " + data1.dumpInternalState(true));
        Log.d(TAG, " after: " + table1.dumpInternalState());
    }

    /**
     * Test that it fails if you reset the data and not the table.
     *
     * Resetting the table and not the data is basically okay. The data in the
     * SparseMappingTable will be leaked.
     */
    @SmallTest
    public void testResetDataOnlyFails() throws Exception {
        int key;
        final SparseMappingTable data = new SparseMappingTable();
        final SparseMappingTable.Table table = new SparseMappingTable.Table(data);

        key = table.getOrAddKey(ID1, 1);
        table.setValue(key, VALUE1);

        Assert.assertEquals(VALUE1, table.getValue(key));

        data.reset();

        try {
            table.getValue(key);
            // Turn off this assertion because the check in SparseMappingTable.assertConsistency
            // is also turned off.
            //throw new Exception("Exception not thrown after mismatched reset calls.");
        } catch (RuntimeException ex) {
            // Good
        }
    }

    /**
     * Test that trying to get data that you didn't add fails correctly.
     */
    @SmallTest
    public void testInvalidKey() throws Exception {
        int key;
        final SparseMappingTable data = new SparseMappingTable();
        final SparseMappingTable.Table table = new SparseMappingTable.Table(data);

        key = table.getKey(ID1);

        // The key should be INVALID_KEY
        Assert.assertEquals(SparseMappingTable.INVALID_KEY, key);

        // If you get the value with getValueForId you get 0.
        Assert.assertEquals(0, table.getValueForId(ID1));
    }
}



