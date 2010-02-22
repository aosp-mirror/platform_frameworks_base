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

import com.android.internal.R;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import junit.framework.Test;
import junit.framework.TestSuite;

import java.util.List;

/**
 * @hide - This is part of a framework that is under development and should not be used for
 * active development.
 */
public abstract class TestBrowserActivity extends ListActivity
        implements android.test.TestBrowserView, AdapterView.OnItemClickListener,
        TestSuiteProvider {

    private TestBrowserController mTestBrowserController;
    public static final String BUNDLE_EXTRA_PACKAGE = "package";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getListView().setOnItemClickListener(this);

        mTestBrowserController = ServiceLocator.getTestBrowserController();
        mTestBrowserController.setTargetPackageName(getPackageName());
        mTestBrowserController.registerView(this);
        mTestBrowserController.setTargetBrowserActivityClassName(this.getClass().getName());

        // Apk paths used to search for test classes when using TestSuiteBuilders.
        String[] apkPaths = {getPackageCodePath()};
        ClassPathPackageInfoSource.setApkPaths(apkPaths);
    }

    @Override
    protected void onStart() {
        super.onStart();
        TestSuite testSuite = getTestSuiteToBrowse();
        mTestBrowserController.setTestSuite(testSuite);
        
        String name = testSuite.getName();
        if (name != null) {
            setTitle(name.substring(name.lastIndexOf(".") + 1));
        }
    }

    /**
     * Subclasses will override this method and return the TestSuite specific to their .apk.
     * When this method is invoked due to an intent fired from
     * {@link #onItemClick(android.widget.AdapterView, android.view.View, int, long)} then get the
     * targeted TestSuite from the intent.
     *
     * @return testSuite to browse
     */
    @SuppressWarnings("unchecked")
    private TestSuite getTestSuiteToBrowse() {
        Intent intent = getIntent();
        if (Intent.ACTION_RUN.equals(intent.getAction())) {
            String testClassName = intent.getData().toString();

            try {
                Class<Test> testClass = (Class<Test>) getClassLoader().loadClass(testClassName);
                return TestCaseUtil.createTestSuite(testClass);
            } catch (ClassNotFoundException e) {
                Log.e("TestBrowserActivity", "ClassNotFoundException for " + testClassName, e);
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                Log.e("TestBrowserActivity", "IllegalAccessException for " + testClassName, e);
                throw new RuntimeException(e);
            } catch (InstantiationException e) {
                Log.e("TestBrowserActivity", "InstantiationException for " + testClassName, e);
                throw new RuntimeException(e);
            }
        } else {
            // get test classes to browwes from subclass
            return getTopTestSuite();
        }

    }

    public TestSuite getTestSuite() {
        return getTopTestSuite();
    }

    /**
     * @return A TestSuite that should be run for a given application.
     */
    public abstract TestSuite getTopTestSuite();

    public void onItemClick(AdapterView parent, View v, int position, long id) {
        Intent intent = mTestBrowserController.getIntentForTestAt(position);
        intent.putExtra(BUNDLE_EXTRA_PACKAGE, getPackageName());
        startActivity(intent);
    }

    public void setTestNames(List<String> testNames) {
        ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(this,
                R.layout.test_list_item, testNames);
        setListAdapter(arrayAdapter);
    }
}

