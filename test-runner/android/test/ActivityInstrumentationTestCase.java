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

package android.test;

import android.app.Activity;

import java.lang.reflect.Field;

/**
 * This class provides functional testing of a single activity.  The activity under test will
 * be created using the system infrastructure (by calling InstrumentationTestCase.launchActivity())
 * and you will then be able to manipulate your Activity directly.  Most of the work is handled
 * automatically here by {@link #setUp} and {@link #tearDown}.
 * 
 * <p>If you prefer an isolated unit test, see {@link android.test.ActivityUnitTestCase}.
 * 
 * @deprecated new tests should be written using 
 * {@link android.test.ActivityInstrumentationTestCase2}, which provides more options for
 * configuring the Activity under test
 */
@Deprecated
public abstract class ActivityInstrumentationTestCase<T extends Activity> 
        extends ActivityTestCase {
    String mPackage;
    Class<T> mActivityClass;
    boolean mInitialTouchMode = false;

    /**
     * @param pkg The package of the instrumentation.
     * @param activityClass The activity to test.
     */
    public ActivityInstrumentationTestCase(String pkg, Class<T> activityClass) {
        this(pkg, activityClass, false);
    }

    /**
     * @param pkg The package of the instrumentation.
     * @param activityClass The activity to test.
     * @param initialTouchMode true = in touch mode
     */
    public ActivityInstrumentationTestCase(String pkg, Class<T> activityClass, 
            boolean initialTouchMode) {
        mPackage = pkg;
        mActivityClass = activityClass;
        mInitialTouchMode = initialTouchMode;
    }

    @Override
    public T getActivity() {
        return (T) super.getActivity();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // set initial touch mode
        getInstrumentation().setInTouchMode(mInitialTouchMode);
        setActivity(launchActivity(mPackage, mActivityClass, null));
    }

    @Override
    protected void tearDown() throws Exception {
        getActivity().finish();
        setActivity(null);
        
        // Scrub out members - protects against memory leaks in the case where someone 
        // creates a non-static inner class (thus referencing the test case) and gives it to
        // someone else to hold onto
        scrubClass(ActivityInstrumentationTestCase.class);

        super.tearDown();
    }

    public void testActivityTestCaseSetUpProperly() throws Exception {
        assertNotNull("activity should be launched successfully", getActivity());
    }
}
