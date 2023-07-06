/*
 * Copyright (C) 2023 The Android Open Source Project
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

package org.robolectric.shadows.androidx.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ComponentController;
import org.robolectric.util.ReflectionHelpers;

/** A Controller that can be used to drive the lifecycle of a {@link Fragment} */
public class FragmentController<F extends Fragment>
        extends ComponentController<FragmentController<F>, F> {

    private final F mFragment;
    private final ActivityController<? extends FragmentActivity> mActivityController;

    private FragmentController(F fragment, Class<? extends FragmentActivity> activityClass) {
        this(fragment, activityClass, null /*intent*/, null /*arguments*/);
    }

    private FragmentController(
            F fragment, Class<? extends FragmentActivity> activityClass, Intent intent) {
        this(fragment, activityClass, intent, null /*arguments*/);
    }

    private FragmentController(
            F fragment, Class<? extends FragmentActivity> activityClass, Bundle arguments) {
        this(fragment, activityClass, null /*intent*/, arguments);
    }

    private FragmentController(
            F fragment,
            Class<? extends FragmentActivity> activityClass,
            Intent intent,
            Bundle arguments) {
        super(fragment, intent);
        this.mFragment = fragment;
        if (arguments != null) {
            this.mFragment.setArguments(arguments);
        }
        this.mActivityController =
                ActivityController.of(ReflectionHelpers.callConstructor(activityClass), intent);
    }

    /**
     * Generate the {@link FragmentController} for specific fragment.
     *
     * @param fragment the fragment which you'd like to drive lifecycle
     * @return {@link FragmentController}
     */
    public static <F extends Fragment> FragmentController<F> of(F fragment) {
        return new FragmentController<>(fragment, FragmentControllerActivity.class);
    }

    /**
     * Generate the {@link FragmentController} for specific fragment and intent.
     *
     * @param fragment the fragment which you'd like to drive lifecycle
     * @param intent   the intent which will be retained by activity
     * @return {@link FragmentController}
     */
    public static <F extends Fragment> FragmentController<F> of(F fragment, Intent intent) {
        return new FragmentController<>(fragment, FragmentControllerActivity.class, intent);
    }

    /**
     * Generate the {@link FragmentController} for specific fragment and arguments.
     *
     * @param fragment  the fragment which you'd like to drive lifecycle
     * @param arguments the arguments which will be retained by fragment
     * @return {@link FragmentController}
     */
    public static <F extends Fragment> FragmentController<F> of(F fragment, Bundle arguments) {
        return new FragmentController<>(fragment, FragmentControllerActivity.class, arguments);
    }

    /**
     * Generate the {@link FragmentController} for specific fragment and activity class.
     *
     * @param fragment      the fragment which you'd like to drive lifecycle
     * @param activityClass the activity which will be attached by fragment
     * @return {@link FragmentController}
     */
    public static <F extends Fragment> FragmentController<F> of(
            F fragment, Class<? extends FragmentActivity> activityClass) {
        return new FragmentController<>(fragment, activityClass);
    }

    /**
     * Generate the {@link FragmentController} for specific fragment, intent and arguments.
     *
     * @param fragment  the fragment which you'd like to drive lifecycle
     * @param intent    the intent which will be retained by activity
     * @param arguments the arguments which will be retained by fragment
     * @return {@link FragmentController}
     */
    public static <F extends Fragment> FragmentController<F> of(
            F fragment, Intent intent, Bundle arguments) {
        return new FragmentController<>(fragment, FragmentControllerActivity.class, intent,
                arguments);
    }

    /**
     * Generate the {@link FragmentController} for specific fragment, activity class and intent.
     *
     * @param fragment      the fragment which you'd like to drive lifecycle
     * @param activityClass the activity which will be attached by fragment
     * @param intent        the intent which will be retained by activity
     * @return {@link FragmentController}
     */
    public static <F extends Fragment> FragmentController<F> of(
            F fragment, Class<? extends FragmentActivity> activityClass, Intent intent) {
        return new FragmentController<>(fragment, activityClass, intent);
    }

    /**
     * Generate the {@link FragmentController} for specific fragment, activity class and arguments.
     *
     * @param fragment      the fragment which you'd like to drive lifecycle
     * @param activityClass the activity which will be attached by fragment
     * @param arguments     the arguments which will be retained by fragment
     * @return {@link FragmentController}
     */
    public static <F extends Fragment> FragmentController<F> of(
            F fragment, Class<? extends FragmentActivity> activityClass, Bundle arguments) {
        return new FragmentController<>(fragment, activityClass, arguments);
    }

    /**
     * Generate the {@link FragmentController} for specific fragment, activity class, intent and
     * arguments.
     *
     * @param fragment      the fragment which you'd like to drive lifecycle
     * @param activityClass the activity which will be attached by fragment
     * @param intent        the intent which will be retained by activity
     * @param arguments     the arguments which will be retained by fragment
     * @return {@link FragmentController}
     */
    public static <F extends Fragment> FragmentController<F> of(
            F fragment,
            Class<? extends FragmentActivity> activityClass,
            Intent intent,
            Bundle arguments) {
        return new FragmentController<>(fragment, activityClass, intent, arguments);
    }

    /**
     * Sets up the given fragment by attaching it to an activity, calling its onCreate() through
     * onResume() lifecycle methods, and then making it visible. Note that the fragment will be
     * added
     * to the view with ID 1.
     */
    public static <F extends Fragment> F setupFragment(F fragment) {
        return FragmentController.of(fragment).create().start().resume().visible().get();
    }

    /**
     * Sets up the given fragment by attaching it to an activity, calling its onCreate() through
     * onResume() lifecycle methods, and then making it visible. Note that the fragment will be
     * added
     * to the view with ID 1.
     */
    public static <F extends Fragment> F setupFragment(
            F fragment, Class<? extends FragmentActivity> fragmentActivityClass) {
        return FragmentController.of(fragment, fragmentActivityClass)
                .create()
                .start()
                .resume()
                .visible()
                .get();
    }

    /**
     * Sets up the given fragment by attaching it to an activity created with the given bundle,
     * calling its onCreate() through onResume() lifecycle methods, and then making it visible. Note
     * that the fragment will be added to the view with ID 1.
     */
    public static <F extends Fragment> F setupFragment(
            F fragment, Class<? extends FragmentActivity> fragmentActivityClass, Bundle bundle) {
        return FragmentController.of(fragment, fragmentActivityClass)
                .create(bundle)
                .start()
                .resume()
                .visible()
                .get();
    }

    /**
     * Sets up the given fragment by attaching it to an activity created with the given bundle and
     * container id, calling its onCreate() through onResume() lifecycle methods, and then making it
     * visible.
     */
    public static <F extends Fragment> F setupFragment(
            F fragment,
            Class<? extends FragmentActivity> fragmentActivityClass,
            int containerViewId,
            Bundle bundle) {
        return FragmentController.of(fragment, fragmentActivityClass)
                .create(containerViewId, bundle)
                .start()
                .resume()
                .visible()
                .get();
    }

    /**
     * Creates the activity with {@link Bundle} and adds the fragment to the view with ID {@code
     * contentViewId}.
     */
    public FragmentController<F> create(final int contentViewId, final Bundle bundle) {
        shadowMainLooper.runPaused(
                new Runnable() {
                    @Override
                    public void run() {
                        mActivityController
                                .create(bundle)
                                .get()
                                .getSupportFragmentManager()
                                .beginTransaction()
                                .add(contentViewId, mFragment)
                                .commit();
                    }
                });
        return this;
    }

    /**
     * Creates the activity with {@link Bundle} and adds the fragment to it. Note that the fragment
     * will be added to the view with ID 1.
     */
    public FragmentController<F> create(final Bundle bundle) {
        return create(1, bundle);
    }

    /**
     * Creates the {@link Fragment} in a newly initialized state and hence will receive a null
     * savedInstanceState {@link Bundle parameter}
     */
    @Override
    public FragmentController<F> create() {
        return create(null);
    }

    /** Drive lifecycle of activity to Start lifetime */
    public FragmentController<F> start() {
        shadowMainLooper.runPaused(
                new Runnable() {
                    @Override
                    public void run() {
                        mActivityController.start();
                    }
                });
        return this;
    }

    /** Drive lifecycle of activity to Resume lifetime */
    public FragmentController<F> resume() {
        shadowMainLooper.runPaused(
                new Runnable() {
                    @Override
                    public void run() {
                        mActivityController.resume();
                    }
                });
        return this;
    }

    /** Drive lifecycle of activity to Pause lifetime */
    public FragmentController<F> pause() {
        shadowMainLooper.runPaused(
                new Runnable() {
                    @Override
                    public void run() {
                        mActivityController.pause();
                    }
                });
        return this;
    }

    /** Drive lifecycle of activity to Stop lifetime */
    public FragmentController<F> stop() {
        shadowMainLooper.runPaused(
                new Runnable() {
                    @Override
                    public void run() {
                        mActivityController.stop();
                    }
                });
        return this;
    }

    /** Drive lifecycle of activity to Destroy lifetime */
    @Override
    public FragmentController<F> destroy() {
        shadowMainLooper.runPaused(
                new Runnable() {
                    @Override
                    public void run() {
                        mActivityController.destroy();
                    }
                });
        return this;
    }

    /** Let activity can be visible lifetime */
    public FragmentController<F> visible() {
        shadowMainLooper.runPaused(
                new Runnable() {
                    @Override
                    public void run() {
                        mActivityController.visible();
                    }
                });
        return this;
    }

    private static class FragmentControllerActivity extends FragmentActivity {

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            LinearLayout view = new LinearLayout(this);
            view.setId(1);

            setContentView(view);
        }
    }
}
