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

import android.app.Activity;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LifecycleOwner;

/**
 * {@link Activity} that has hooks to observe activity lifecycle events.
 */
public class ObservableActivity extends FragmentActivity implements LifecycleOwner {

    private final Lifecycle mLifecycle = new Lifecycle(this);

    public Lifecycle getSettingsLifecycle() {
        return mLifecycle;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        mLifecycle.onAttach(this);
        mLifecycle.onCreate(savedInstanceState);
        mLifecycle.handleLifecycleEvent(ON_CREATE);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState,
            @Nullable PersistableBundle persistentState) {
        mLifecycle.onAttach(this);
        mLifecycle.onCreate(savedInstanceState);
        mLifecycle.handleLifecycleEvent(ON_CREATE);
        super.onCreate(savedInstanceState, persistentState);
    }

    @Override
    protected void onStart() {
        mLifecycle.handleLifecycleEvent(ON_START);
        super.onStart();
    }

    @Override
    protected void onResume() {
        mLifecycle.handleLifecycleEvent(ON_RESUME);
        super.onResume();
    }

    @Override
    protected void onPause() {
        mLifecycle.handleLifecycleEvent(ON_PAUSE);
        super.onPause();
    }

    @Override
    protected void onStop() {
        mLifecycle.handleLifecycleEvent(ON_STOP);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mLifecycle.handleLifecycleEvent(ON_DESTROY);
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        if (super.onCreateOptionsMenu(menu)) {
            mLifecycle.onCreateOptionsMenu(menu, null);
            return true;
        }
        return false;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        if (super.onPrepareOptionsMenu(menu)) {
            mLifecycle.onPrepareOptionsMenu(menu);
            return true;
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem menuItem) {
        boolean lifecycleHandled = mLifecycle.onOptionsItemSelected(menuItem);
        if (!lifecycleHandled) {
            return super.onOptionsItemSelected(menuItem);
        }
        return lifecycleHandled;
    }
}
