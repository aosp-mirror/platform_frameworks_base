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

import android.annotation.UiThread;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.preference.PreferenceScreen;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.android.settingslib.core.lifecycle.events.OnAttach;
import com.android.settingslib.core.lifecycle.events.OnCreate;
import com.android.settingslib.core.lifecycle.events.OnCreateOptionsMenu;
import com.android.settingslib.core.lifecycle.events.OnDestroy;
import com.android.settingslib.core.lifecycle.events.OnOptionsItemSelected;
import com.android.settingslib.core.lifecycle.events.OnPause;
import com.android.settingslib.core.lifecycle.events.OnPrepareOptionsMenu;
import com.android.settingslib.core.lifecycle.events.OnResume;
import com.android.settingslib.core.lifecycle.events.OnSaveInstanceState;
import com.android.settingslib.core.lifecycle.events.OnStart;
import com.android.settingslib.core.lifecycle.events.OnStop;
import com.android.settingslib.core.lifecycle.events.SetPreferenceScreen;
import com.android.settingslib.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Dispatcher for lifecycle events.
 */
public class Lifecycle {

    protected final List<LifecycleObserver> mObservers = new ArrayList<>();

    /**
     * Registers a new observer of lifecycle events.
     */
    @UiThread
    public <T extends LifecycleObserver> T addObserver(T observer) {
        ThreadUtils.ensureMainThread();
        mObservers.add(observer);
        return observer;
    }

    public void onAttach(Context context) {
        for (int i = 0, size = mObservers.size(); i < size; i++) {
            final LifecycleObserver observer = mObservers.get(i);
            if (observer instanceof OnAttach) {
                ((OnAttach) observer).onAttach(context);
            }
        }
    }

    public void onCreate(Bundle savedInstanceState) {
        for (int i = 0, size = mObservers.size(); i < size; i++) {
            final LifecycleObserver observer = mObservers.get(i);
            if (observer instanceof OnCreate) {
                ((OnCreate) observer).onCreate(savedInstanceState);
            }
        }
    }

    public void onStart() {
        for (int i = 0, size = mObservers.size(); i < size; i++) {
            final LifecycleObserver observer = mObservers.get(i);
            if (observer instanceof OnStart) {
                ((OnStart) observer).onStart();
            }
        }
    }

    public void setPreferenceScreen(PreferenceScreen preferenceScreen) {
        for (int i = 0, size = mObservers.size(); i < size; i++) {
            final LifecycleObserver observer = mObservers.get(i);
            if (observer instanceof SetPreferenceScreen) {
                ((SetPreferenceScreen) observer).setPreferenceScreen(preferenceScreen);
            }
        }
    }

    public void onResume() {
        for (int i = 0, size = mObservers.size(); i < size; i++) {
            final LifecycleObserver observer = mObservers.get(i);
            if (observer instanceof OnResume) {
                ((OnResume) observer).onResume();
            }
        }
    }

    public void onPause() {
        for (int i = 0, size = mObservers.size(); i < size; i++) {
            final LifecycleObserver observer = mObservers.get(i);
            if (observer instanceof OnPause) {
                ((OnPause) observer).onPause();
            }
        }
    }

    public void onSaveInstanceState(Bundle outState) {
        for (int i = 0, size = mObservers.size(); i < size; i++) {
            final LifecycleObserver observer = mObservers.get(i);
            if (observer instanceof OnSaveInstanceState) {
                ((OnSaveInstanceState) observer).onSaveInstanceState(outState);
            }
        }
    }

    public void onStop() {
        for (int i = 0, size = mObservers.size(); i < size; i++) {
            final LifecycleObserver observer = mObservers.get(i);
            if (observer instanceof OnStop) {
                ((OnStop) observer).onStop();
            }
        }
    }

    public void onDestroy() {
        for (int i = 0, size = mObservers.size(); i < size; i++) {
            final LifecycleObserver observer = mObservers.get(i);
            if (observer instanceof OnDestroy) {
                ((OnDestroy) observer).onDestroy();
            }
        }
    }

    public void onCreateOptionsMenu(final Menu menu, final @Nullable MenuInflater inflater) {
        for (int i = 0, size = mObservers.size(); i < size; i++) {
            final LifecycleObserver observer = mObservers.get(i);
            if (observer instanceof OnCreateOptionsMenu) {
                ((OnCreateOptionsMenu) observer).onCreateOptionsMenu(menu, inflater);
            }
        }
    }

    public void onPrepareOptionsMenu(final Menu menu) {
        for (int i = 0, size = mObservers.size(); i < size; i++) {
            final LifecycleObserver observer = mObservers.get(i);
            if (observer instanceof OnPrepareOptionsMenu) {
                ((OnPrepareOptionsMenu) observer).onPrepareOptionsMenu(menu);
            }
        }
    }

    public boolean onOptionsItemSelected(final MenuItem menuItem) {
        for (int i = 0, size = mObservers.size(); i < size; i++) {
            final LifecycleObserver observer = mObservers.get(i);
            if (observer instanceof OnOptionsItemSelected) {
                if (((OnOptionsItemSelected) observer).onOptionsItemSelected(menuItem)) {
                    return true;
                }
            }
        }
        return false;
    }
}
