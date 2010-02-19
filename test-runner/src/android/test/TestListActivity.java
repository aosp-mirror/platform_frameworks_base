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

package android.test;

import android.app.ListActivity;
import android.content.Intent;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Activity base class to use to implement your application's tests.
 *
 * <p>Implement the getTestSuite() method to return the name of your
 * test suite class.
 *
 * <p>See the android.test package documentation (click the more... link)
 * for a full description
 * 
 * {@hide} Not needed for SDK
 */
public abstract class TestListActivity extends ListActivity {
    /** Supplied in the intent extras if we are running performance tests. */
    public static final String PERFORMANCE_TESTS = "android.test.performance";

    /** "Mode" group in the menu. */
    static final int MODE_GROUP = Menu.FIRST;

    /** Our suite */
    String mSuite;

    /** Our children tests */
    String[] mTests;

    /** which mode, REGRESSION, PERFORMANCE or PROFILING */
    private int mMode = TestRunner.REGRESSION;

    /** "Regression" menu item */
    private MenuItem mRegressionItem;

    /** "Performance" menu item */
    private MenuItem mPerformanceItem;

    /** "Profiling" menu item */
    private MenuItem mProfilingItem;

    private final Comparator<String> sComparator = new Comparator<String>() {
        public final int compare(String a, String b) {
            String s1 = makeCompareName(a);
            String s2 = makeCompareName(b);
            
            return s1.compareToIgnoreCase(s2);
        }
    };

    /**
     * Constructor that doesn't do much.
     */
    public TestListActivity() {
        super();
    }

    /**
     * Subclasses should implement this to return the names of the classes
     * of their tests.
     *
     * @return test suite class name
     */
    public abstract String getTestSuite();

    /**
     * Typical onCreate(Bundle icicle) implementation.
     */
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Intent intent = getIntent();

        mMode = intent.getIntExtra(TestListActivity.PERFORMANCE_TESTS, mMode);


        if (intent.getAction().equals(Intent.ACTION_MAIN)) {
            // if we were called as MAIN, get the test suites,
            mSuite = getTestSuite();
        } else if (intent.getAction().equals(Intent.ACTION_RUN)) {
            // We should have been provided a status channel.  Bail out and
            // run the test instead.  This is how the TestHarness gets us
            // loaded in our process for "Run All Tests."
            Intent ntent = new Intent(Intent.ACTION_RUN,
                    intent.getData() != null
                            ? intent.getData()
                            : Uri.parse(getTestSuite()));
            ntent.setClassName("com.android.testharness",
                    "com.android.testharness.RunTest");
            ntent.putExtras(intent);
            ntent.putExtra("package", getPackageName());
            startActivity(ntent);
            finish();
            return;
        } else if (intent.getAction().equals(Intent.ACTION_VIEW)) {
            // otherwise use the one in the intent
            mSuite = intent.getData() != null ? intent.getData().toString()
                    : null;
        }

        String[] children = TestRunner.getChildren(this, mSuite);

        Arrays.sort(children, sComparator);

        int len = children.length;
        mTests = new String[len];
        System.arraycopy(children, 0, mTests, 0, len);

        setTitle(TestRunner.getTitle(mSuite));

        MatrixCursor cursor = new MatrixCursor(new String[] { "name", "_id" });
        addTestRows(cursor);

        CursorAdapter adapter = new SimpleCursorAdapter(
                this,
                com.android.internal.R.layout.simple_list_item_1,
                cursor,
                new String[] {"name"},
                new int[] {com.android.internal.R.id.text1});
        
        setListAdapter(adapter);
    }

    private void addTestRows(MatrixCursor cursor) {
        int id = 0;
        cursor.newRow().add("Run All").add(id++);       
        for (String test : mTests) {
            String title = TestRunner.getTitle(test);
            String prefix = TestRunner.isTestSuite(this, test)
                    ? "Browse " : "Run ";

            // I'd rather do this with an icon column, but I don't know how
            cursor.newRow().add(prefix + title).add(id++);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        mRegressionItem = menu.add(MODE_GROUP, -1, 0, "Regression Mode");
        mPerformanceItem = menu.add(MODE_GROUP, -1, 0, "Performance Mode");
        mProfilingItem = menu.add(MODE_GROUP, -1, 0, "Profiling Mode");
        menu.setGroupCheckable(MODE_GROUP, true, true);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item == mRegressionItem) {
            mMode = TestRunner.REGRESSION;
        } else if (item == mPerformanceItem) {
            mMode = TestRunner.PERFORMANCE;
        } else if (item == mProfilingItem) {
            mMode = TestRunner.PROFILING;
        }
        
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        switch (mMode) {
        case TestRunner.REGRESSION:
            mRegressionItem.setChecked(true);
            break;

        case TestRunner.PERFORMANCE:
            mPerformanceItem.setChecked(true);
            break;

        case TestRunner.PROFILING:
            mProfilingItem.setChecked(true);
            break;
        }
        return true;
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Intent intent = new Intent();

        if (position == 0) {
            if (false) {
                intent.setClassName("com.android.testharness",
                        "com.android.testharness.RunAll");
                intent.putExtra("tests", new String[]{mSuite});
            } else {
                intent.setClassName("com.android.testharness",
                        "com.android.testharness.RunTest");
                intent.setAction(Intent.ACTION_RUN);
                intent.setData(Uri.parse(mSuite));
            }
        } else {
            String test = mTests[position - 1];
            if (TestRunner.isTestSuite(this, test)) {
                intent.setClassName(getPackageName(), this.getClass().getName());
                intent.setAction(Intent.ACTION_VIEW);
            } else {
                intent.setClassName("com.android.testharness",
                        "com.android.testharness.RunTest");
            }
            intent.setData(Uri.parse(test));
        }

        intent.putExtra(PERFORMANCE_TESTS, mMode);
        intent.putExtra("package", getPackageName());
        startActivity(intent);
    }

    private String makeCompareName(String s) {
        int index = s.lastIndexOf('.');
        
        if (index == -1) {
            return s;
        }
        
        return s.substring(index + 1);
    }
}
