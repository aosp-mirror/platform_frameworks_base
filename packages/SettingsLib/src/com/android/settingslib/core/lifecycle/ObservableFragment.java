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

package com.android.settingslib.core.lifecycle;

import static androidx.lifecycle.Lifecycle.Event.ON_CREATE;
import static androidx.lifecycle.Lifecycle.Event.ON_DESTROY;
import static androidx.lifecycle.Lifecycle.Event.ON_PAUSE;
import static androidx.lifecycle.Lifecycle.Event.ON_RESUME;
import static androidx.lifecycle.Lifecycle.Event.ON_START;
import static androidx.lifecycle.Lifecycle.Event.ON_STOP;

import android.annotation.CallSuper;
import android.app.Fragment;
import androidx.lifecycle.LifecycleOwner;
import android.content.Context;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

public class ObservableFragment extends Fragment implements LifecycleOwner {

    private final Lifecycle mLifecycle = new Lifecycle(this);

    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @CallSuper
    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mLifecycle.onAttach(context);
    }

    @CallSuper
    @Override
    public void onCreate(Bundle savedInstanceState) {
        mLifecycle.onCreate(savedInstanceState);
        mLifecycle.handleLifecycleEvent(ON_CREATE);
        super.onCreate(savedInstanceState);
    }

    @CallSuper
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mLifecycle.onSaveInstanceState(outState);
    }

    @CallSuper
    @Override
    public void onStart() {
        mLifecycle.handleLifecycleEvent(ON_START);
        super.onStart();
    }

    @CallSuper
    @Override
    public void onResume() {
        mLifecycle.handleLifecycleEvent(ON_RESUME);
        super.onResume();
    }

    @CallSuper
    @Override
    public void onPause() {
        mLifecycle.handleLifecycleEvent(ON_PAUSE);
        super.onPause();
    }

    @CallSuper
    @Override
    public void onStop() {
        mLifecycle.handleLifecycleEvent(ON_STOP);
        super.onStop();
    }

    @CallSuper
    @Override
    public void onDestroy() {
        mLifecycle.handleLifecycleEvent(ON_DESTROY);
        super.onDestroy();
    }

    @CallSuper
    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        mLifecycle.onCreateOptionsMenu(menu, inflater);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @CallSuper
    @Override
    public void onPrepareOptionsMenu(final Menu menu) {
        mLifecycle.onPrepareOptionsMenu(menu);
        super.onPrepareOptionsMenu(menu);
    }

    @CallSuper
    @Override
    public boolean onOptionsItemSelected(final MenuItem menuItem) {
        boolean lifecycleHandled = mLifecycle.onOptionsItemSelected(menuItem);
        if (!lifecycleHandled) {
            return super.onOptionsItemSelected(menuItem);
        }
        return lifecycleHandled;
    }
}
