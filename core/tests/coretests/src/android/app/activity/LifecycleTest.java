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

package android.app.activity;

import android.content.ComponentName;
import android.content.Intent;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;

public class LifecycleTest extends ActivityTestsBase {
    private Intent mTopIntent;
    private Intent mTabIntent;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTopIntent = mIntent;
        mTabIntent = new Intent(mContext, LaunchpadTabActivity.class);
        mTabIntent.putExtra("tab", new ComponentName(mContext,
                LaunchpadActivity.class));
    }

    @MediumTest
    public void testBasic() throws Exception {
        mIntent = mTopIntent;
        runLaunchpad(LaunchpadActivity.LIFECYCLE_BASIC);
    }

    //Suppressing until 1285425 is fixed.
    @Suppress
    public void testTabBasic() throws Exception {
        mIntent = mTabIntent;
        runLaunchpad(LaunchpadActivity.LIFECYCLE_BASIC);
    }

    //Marking flaky until bug 1164344 is fixed.
    // @FlakyTest(tolerance=2)
    // @LargeTest
    public void testScreen() throws Exception {
        mIntent = mTopIntent;
        runLaunchpad(LaunchpadActivity.LIFECYCLE_SCREEN);
    }

    //Marking flaky until bug 1164344 is fixed.
    //@FlakyTest(tolerance=2)
    //Suppressing until 1285425 is fixed.
    @Suppress
    public void testTabScreen() throws Exception {
        mIntent = mTabIntent;
        runLaunchpad(LaunchpadActivity.LIFECYCLE_SCREEN);
    }

    //flaky test, removing from large suite until 1866891 is fixed
    //@LargeTest
    public void testDialog() throws Exception {
        mIntent = mTopIntent;
        runLaunchpad(LaunchpadActivity.LIFECYCLE_DIALOG);
    }

    //Suppressing until 1285425 is fixed.
    @Suppress
    public void testTabDialog() throws Exception {
        mIntent = mTabIntent;
        runLaunchpad(LaunchpadActivity.LIFECYCLE_DIALOG);
    }

    @MediumTest
    public void testFinishCreate() throws Exception {
        mIntent = mTopIntent;
        runLaunchpad(LaunchpadActivity.LIFECYCLE_FINISH_CREATE);
    }

    //Suppressing until 1285425 is fixed.
    @Suppress
    public void testTabFinishCreate() throws Exception {
        mIntent = mTabIntent;
        runLaunchpad(LaunchpadActivity.LIFECYCLE_FINISH_CREATE);
    }

    @MediumTest
    public void testFinishStart() throws Exception {
        mIntent = mTopIntent;
        runLaunchpad(LaunchpadActivity.LIFECYCLE_FINISH_START);
    }
    
    //Suppressing until 1285425 is fixed.
    @Suppress
    public void testTabFinishStart() throws Exception {
        mIntent = mTabIntent;
        runLaunchpad(LaunchpadActivity.LIFECYCLE_FINISH_START);
    }
}
