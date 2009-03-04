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

import android.app.Activity;
import android.view.IWindowManager;
import android.os.ServiceManager;

/**
 * If you would like to test a single activity with an
 * {@link android.test.InstrumentationTestCase}, this provides some of the boiler plate to
 * launch and finish the activity in {@link #setUp} and {@link #tearDown}.
 * 
 * This launches the activity only once for the entire class instead of doing it 
 * in every setup / teardown call.
 */
public abstract class SingleLaunchActivityTestCase<T extends Activity>
        extends InstrumentationTestCase {
    
    String mPackage;
    Class<T> mActivityClass;
    private static int sTestCaseCounter = 0;
    private static boolean sActivityLaunchedFlag = false;

    /**
     * @param pkg The package of the instrumentation.
     * @param activityClass The activity to test.
     */
    public SingleLaunchActivityTestCase(String pkg, Class<T> activityClass) {
        mPackage = pkg;
        mActivityClass = activityClass;        
        sTestCaseCounter ++;                
    }
    
    /**
     * The activity that will be set up for use in each test method.
     */
    private static Activity sActivity;

    public T getActivity() {
        return (T) sActivity;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // If it is the first test case, launch the activity.
        if (!sActivityLaunchedFlag) {
            // by default, not in touch mode
            getInstrumentation().setInTouchMode(false);
            sActivity = launchActivity(mPackage, mActivityClass, null);
            sActivityLaunchedFlag = true;
        }                        
    }

    @Override
    protected void tearDown() throws Exception {
        // If it is the last test case, call finish on the activity.
        sTestCaseCounter --;
        if (sTestCaseCounter == 1) {
            sActivity.finish();
        }        
        super.tearDown();
    }

    public void testActivityTestCaseSetUpProperly() throws Exception {
        assertNotNull("activity should be launched successfully", sActivity);
    }
}
