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

package android.widget;

import android.test.suitebuilder.annotation.Suppress;
import com.google.android.collect.Lists;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.ArrayList;
import java.util.Random;

/**
 * This is a series of tests of basic API contracts for SimpleCursorAdapter.  It is
 * incomplete and can use work.
 * 
 * NOTE:  This contract holds for underlying cursor types too and these should
 * be extracted into a set of tests that can be run on any descendant of CursorAdapter.
 */
@Suppress // Failing.
public class SimpleCursorAdapterTest extends AndroidTestCase {
    
    String[] mFrom;
    int[] mTo;
    int mLayout;
    Context mContext;
    
    ArrayList<ArrayList> mData2x2;
    Cursor mCursor2x2;
    
    /**
     * Set up basic columns and cursor for the tests
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        
        // all the pieces needed for the various tests
        mFrom = new String[]{"Column1", "Column2", "_id"};
        mTo = new int[]{com.android.internal.R.id.text1, com.android.internal.R.id.text2};
        mLayout = com.android.internal.R.layout.simple_list_item_2;
        mContext = getContext();

        // raw data for building a basic test cursor
        mData2x2 = createTestList(2, 2);
        mCursor2x2 = createCursor(mFrom, mData2x2);
    }
    
    /**
     * Borrowed from CursorWindowTest.java
     */
    private ArrayList<ArrayList> createTestList(int rows, int cols) {
        ArrayList<ArrayList> list = Lists.newArrayList();
        Random generator = new Random();

        for (int i = 0; i < rows; i++) {
            ArrayList<Integer> col = Lists.newArrayList();
            list.add(col);
            for (int j = 0; j < cols; j++) {
                // generate random number
                Integer r = generator.nextInt();
                col.add(r);
            }
            col.add(i);
        }
        return list;
    }

    /**
     * Test creating with a live cursor
     */
    @SmallTest
    public void testCreateLive() {
        SimpleCursorAdapter ca = new SimpleCursorAdapter(mContext, mLayout, mCursor2x2, mFrom, mTo);
        
        // Now see if we can pull 2 rows from the adapter
        assertEquals(2, ca.getCount());
    }
    
    /**
     * Test creating with a null cursor
     */
    @SmallTest
    public void testCreateNull() {
        SimpleCursorAdapter ca = new SimpleCursorAdapter(mContext, mLayout, null, mFrom, mTo);
        
        // The adapter should report zero rows
        assertEquals(0, ca.getCount());
    }
    
    /**
     * Test changeCursor() with live cursor
     */
    @SmallTest
    public void testChangeCursorLive() {
        SimpleCursorAdapter ca = new SimpleCursorAdapter(mContext, mLayout, mCursor2x2, mFrom, mTo);
        
        // Now see if we can pull 2 rows from the adapter
        assertEquals(2, ca.getCount());
        
        // now put in a different cursor (5 rows)
        ArrayList<ArrayList> data2 = createTestList(5, 2);
        Cursor c2 = createCursor(mFrom, data2);
        ca.changeCursor(c2);
        
        // Now see if we can pull 5 rows from the adapter
        assertEquals(5, ca.getCount());
    }
    
    /**
     * Test changeCursor() with null cursor
     */
    @SmallTest
    public void testChangeCursorNull() {
        SimpleCursorAdapter ca = new SimpleCursorAdapter(mContext, mLayout, mCursor2x2, mFrom, mTo);
        
        // Now see if we can pull 2 rows from the adapter
        assertEquals(2, ca.getCount());
        
        // now put in null
        ca.changeCursor(null);
        
        // The adapter should report zero rows
        assertEquals(0, ca.getCount());
    }
    
    /**
     * Test changeCursor() with differing column layout.  This confirms that the Adapter can
     * deal with cursors that have the same essential data (as defined by the original mFrom
     * array) but it's OK if the physical structure of the cursor changes (columns rearranged).
     */
    @SmallTest
    public void testChangeCursorColumns() {
        TestSimpleCursorAdapter ca = new TestSimpleCursorAdapter(mContext, mLayout, mCursor2x2, 
                mFrom, mTo);
        
        // check columns of original - mFrom and mTo should line up
        int[] columns = ca.getConvertedFrom();
        assertEquals(columns[0], 0);
        assertEquals(columns[1], 1);

        // Now make a new cursor with similar data but rearrange the columns
        String[] swappedFrom = new String[]{"Column2", "Column1", "_id"};
        Cursor c2 = createCursor(swappedFrom, mData2x2);
        ca.changeCursor(c2);
        assertEquals(2, ca.getCount());

        // check columns to see if rearrangement tracked (should be swapped now)
        columns = ca.getConvertedFrom();
        assertEquals(columns[0], 1);
        assertEquals(columns[1], 0);
    }
    
    /**
     * Test that you can safely construct with a null cursor *and* null to/from arrays.
     * This is new functionality added in 12/2008.
     */
    @SmallTest
    public void testNullConstructor() {
        SimpleCursorAdapter ca = new SimpleCursorAdapter(mContext, mLayout, null, null, null);
        assertEquals(0, ca.getCount());
    }
    
    /**
     * Test going from a null cursor to a non-null cursor *and* setting the to/from arrays
     * This is new functionality added in 12/2008.
     */
    @SmallTest
    public void testChangeNullToMapped() {
        TestSimpleCursorAdapter ca = new TestSimpleCursorAdapter(mContext, mLayout, null, null, null);
        assertEquals(0, ca.getCount());

        ca.changeCursorAndColumns(mCursor2x2, mFrom, mTo);
        assertEquals(2, ca.getCount());
        
        // check columns of original - mFrom and mTo should line up
        int[] columns = ca.getConvertedFrom();
        assertEquals(2, columns.length);
        assertEquals(0, columns[0]);
        assertEquals(1, columns[1]);
        int[] viewIds = ca.getTo();
        assertEquals(2, viewIds.length);
        assertEquals(com.android.internal.R.id.text1, viewIds[0]);
        assertEquals(com.android.internal.R.id.text2, viewIds[1]);
    }
    
    /**
     * Test going from one mapping to a different mapping
     * This is new functionality added in 12/2008.
     */
    @SmallTest
    public void testChangeMapping() {
        TestSimpleCursorAdapter ca = new TestSimpleCursorAdapter(mContext, mLayout, mCursor2x2, 
                mFrom, mTo);
        assertEquals(2, ca.getCount());

        // Now create a new configuration with same cursor and just one column mapped
        String[] singleFrom = new String[]{"Column1"};
        int[] singleTo = new int[]{com.android.internal.R.id.text1};
        ca.changeCursorAndColumns(mCursor2x2, singleFrom, singleTo);

        // And examine the results, make sure they're still consistent
        int[] columns = ca.getConvertedFrom();
        assertEquals(1, columns.length);
        assertEquals(0, columns[0]);
        int[] viewIds = ca.getTo();
        assertEquals(1, viewIds.length);
        assertEquals(com.android.internal.R.id.text1, viewIds[0]);
        
        // And again, same cursor, different map
        singleFrom = new String[]{"Column2"};
        singleTo = new int[]{com.android.internal.R.id.text2};
        ca.changeCursorAndColumns(mCursor2x2, singleFrom, singleTo);

        // And examine the results, make sure they're still consistent
        columns = ca.getConvertedFrom();
        assertEquals(1, columns.length);
        assertEquals(1, columns[0]);
        viewIds = ca.getTo();
        assertEquals(1, viewIds.length);
        assertEquals(com.android.internal.R.id.text2, viewIds[0]);
    }

    private static MatrixCursor createCursor(String[] columns, ArrayList<ArrayList> list) {
        MatrixCursor cursor = new MatrixCursor(columns, list.size());
        for (ArrayList row : list) {
            cursor.addRow(row);
        }
        return cursor;
    }

    /**
     * This is simply a way to sneak a look at the protected mFrom() array.  A more API-
     * friendly way to do this would be to mock out a View and a ViewBinder and exercise
     * it via those seams.
     */
    private static class TestSimpleCursorAdapter extends SimpleCursorAdapter {
        
        public TestSimpleCursorAdapter(Context context, int layout, Cursor c,
                String[] from, int[] to) {
            super(context, layout, c, from, to);
        }

        int[] getConvertedFrom() {
            return mFrom;
        }
        
        int[] getTo() {
            return mTo;
        }
    }
}
