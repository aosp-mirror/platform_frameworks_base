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


package android.app;

import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertNotSame;
import static junit.framework.TestCase.assertSame;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.Handler;
import android.os.Parcelable;
import android.support.test.filters.MediumTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArrayMap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LoaderLifecycleTest {
    @Rule
    public ActivityTestRule<EmptyActivity> mActivityRule =
            new ActivityTestRule<>(EmptyActivity.class);
    @Test
    @MediumTest
    public void loaderIdentityTest() throws Throwable{
        mActivityRule.runOnUiThread(() -> {
            final Handler h = new Handler();
            final FragmentController fc1 = FragmentController.createController(
                    new TestFragmentHostCallback(mActivityRule.getActivity(), h, 0));

            fc1.attachHost(null);
            fc1.dispatchCreate();

            final FragmentManager fm1 = fc1.getFragmentManager();

            final Fragment f1 = new Fragment();
            fm1.beginTransaction().add(f1, "one").commitNow();

            // Removing and re-adding a fragment completely will destroy its LoaderManager.
            // Keep the first one here to confirm this later.
            final LoaderManager lm1 = f1.getLoaderManager();

            // Remove the fragment, add a second one, and re-add the first to
            // force its internal index to change. The tests below should still remain consistent.
            final Fragment f2 = new Fragment();
            fm1.beginTransaction().remove(f1).commitNow();
            fm1.beginTransaction().add(f2, "two").commitNow();
            fm1.beginTransaction().add(f1, "one").commitNow();

            // We'll check this to see if we get the same instance back later
            // as passed through NonConfigurationInstance. If the keys stay consistent
            // across fragment remove/re-add, this will be consistent.
            final LoaderManager lm12 = f1.getLoaderManager();

            assertNotSame("fully removed and re-added fragment got same LoaderManager", lm1, lm12);

            fc1.dispatchActivityCreated();
            fc1.noteStateNotSaved();
            fc1.execPendingActions();
            fc1.doLoaderStart();
            fc1.dispatchStart();
            fc1.reportLoaderStart();
            fc1.dispatchResume();
            fc1.execPendingActions();

            // Bring the state back down to destroyed, simulating an activity restart
            fc1.dispatchPause();
            final Parcelable savedState = fc1.saveAllState();
            fc1.doLoaderStop(true);
            fc1.dispatchStop();
            final FragmentManagerNonConfig nonconf = fc1.retainNestedNonConfig();

            final ArrayMap<String, LoaderManager> loaderNonConfig = fc1.retainLoaderNonConfig();
            assertNotNull("loaderNonConfig was null", loaderNonConfig);

            fc1.dispatchDestroy();

            // Create the new controller and restore state
            final FragmentController fc2 = FragmentController.createController(
                    new TestFragmentHostCallback(mActivityRule.getActivity(), h, 0));

            final FragmentManager fm2 = fc2.getFragmentManager();

            fc2.attachHost(null);
            // Make sure nothing blows up on a null here
            fc2.restoreLoaderNonConfig(null);
            // for real this time
            fc2.restoreLoaderNonConfig(loaderNonConfig);
            fc2.restoreAllState(savedState, nonconf);
            fc2.dispatchCreate();


            fc2.dispatchActivityCreated();
            fc2.noteStateNotSaved();
            fc2.execPendingActions();
            fc2.doLoaderStart();
            fc2.dispatchStart();
            fc2.reportLoaderStart();
            fc2.dispatchResume();
            fc2.execPendingActions();

            // Test that the fragments are in the configuration we expect
            final Fragment restoredOne = fm2.findFragmentByTag("one");
            final LoaderManager lm2 = restoredOne.getLoaderManager();

            assertSame("didn't get same LoaderManager instance back", lm2, lm12);

            // Bring the state back down to destroyed before we finish the test
            fc2.dispatchPause();
            fc2.saveAllState();
            fc2.dispatchStop();
            fc2.dispatchDestroy();
        });
    }

    @Test
    @MediumTest
    public void backStackLoaderIdentityTest() throws Throwable{
        mActivityRule.runOnUiThread(() -> {
            final Handler h = new Handler();
            final FragmentHostCallback host1 =
                    new TestFragmentHostCallback(mActivityRule.getActivity(), h, 0);
            final FragmentController fc1 = FragmentController.createController(host1);

            fc1.attachHost(null);
            fc1.dispatchCreate();

            final FragmentManager fm1 = fc1.getFragmentManager();

            final Fragment f1 = new Fragment();
            fm1.beginTransaction().add(f1, "one").commitNow();

            final LoaderManager lm1 = f1.getLoaderManager();

            // Put the fragment on the back stack.
            fm1.beginTransaction().remove(f1).addToBackStack("backentry").commit();
            fm1.executePendingTransactions();

            fc1.dispatchActivityCreated();
            fc1.noteStateNotSaved();
            fc1.execPendingActions();
            fc1.doLoaderStart();
            fc1.dispatchStart();
            fc1.reportLoaderStart();
            fc1.dispatchResume();
            fc1.execPendingActions();

            // Bring the state back down to destroyed, simulating an activity restart
            fc1.dispatchPause();
            final Parcelable savedState = fc1.saveAllState();
            fc1.doLoaderStop(true);
            fc1.dispatchStop();
            final FragmentManagerNonConfig nonconf = fc1.retainNestedNonConfig();

            final ArrayMap<String, LoaderManager> loaderNonConfig = fc1.retainLoaderNonConfig();
            assertNotNull("loaderNonConfig was null", loaderNonConfig);

            fc1.dispatchDestroy();

            // Create the new controller and restore state
            final FragmentHostCallback host2 =
                    new TestFragmentHostCallback(mActivityRule.getActivity(), h, 0);
            final FragmentController fc2 = FragmentController.createController(host2);

            final FragmentManager fm2 = fc2.getFragmentManager();

            fc2.attachHost(null);
            fc2.restoreLoaderNonConfig(loaderNonConfig);
            fc2.restoreAllState(savedState, nonconf);
            fc2.dispatchCreate();


            fc2.dispatchActivityCreated();
            fc2.noteStateNotSaved();
            fc2.execPendingActions();
            fc2.doLoaderStart();
            fc2.dispatchStart();
            fc2.reportLoaderStart();
            fc2.dispatchResume();
            fc2.execPendingActions();

            assertNotSame("LoaderManager kept reference to old FragmentHostCallback",
                    host1, lm1.getFragmentHostCallback());
            assertSame("LoaderManager did not refrence new FragmentHostCallback",
                    host2, lm1.getFragmentHostCallback());

            // Test that the fragments are in the configuration we expect
            final Fragment restoredOne = fm2.findFragmentByTag("one");
            try {
                restoredOne.getLoaderManager();
                fail("A restored fragment on the back stack doesn't have a host, so it should "
                        + "throw an exception");
            } catch (IllegalStateException e) {
                // expected
            }
            fm2.popBackStackImmediate();
            // Now restoredOne should be added and should be in a good state.
            assertTrue(restoredOne.isAdded());
            final LoaderManager lm2 = restoredOne.getLoaderManager();

            assertSame("didn't get same LoaderManager instance back", lm2, lm1);

            // Bring the state back down to destroyed before we finish the test
            fc2.dispatchPause();
            fc2.saveAllState();
            fc2.dispatchStop();
            fc2.dispatchDestroy();
        });
    }

    public class TestFragmentHostCallback extends FragmentHostCallback<LoaderLifecycleTest> {
        public TestFragmentHostCallback(Context context, Handler handler, int windowAnimations) {
            super(context, handler, windowAnimations);
        }

        @Override
        public LoaderLifecycleTest onGetHost() {
            return LoaderLifecycleTest.this;
        }
    }
}
