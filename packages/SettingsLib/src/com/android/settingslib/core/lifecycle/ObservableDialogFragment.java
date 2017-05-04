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

import android.app.DialogFragment;
import android.content.Context;
import android.support.annotation.VisibleForTesting;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

/**
 * {@link DialogFragment} that has hooks to observe fragment lifecycle events.
 */
public class ObservableDialogFragment extends DialogFragment {

    protected final Lifecycle mLifecycle = createLifecycle();

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mLifecycle.onAttach(context);
    }

    @Override
    public void onStart() {
        mLifecycle.onStart();
        super.onStart();
    }

    @Override
    public void onResume() {
        mLifecycle.onResume();
        super.onResume();
    }

    @Override
    public void onPause() {
        mLifecycle.onPause();
        super.onPause();
    }

    @Override
    public void onStop() {
        mLifecycle.onStop();
        super.onStop();
    }

    @Override
    public void onDestroy() {
        mLifecycle.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        mLifecycle.onCreateOptionsMenu(menu, inflater);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onPrepareOptionsMenu(final Menu menu) {
        mLifecycle.onPrepareOptionsMenu(menu);
        super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem menuItem) {
        boolean lifecycleHandled = mLifecycle.onOptionsItemSelected(menuItem);
        if (!lifecycleHandled) {
            return super.onOptionsItemSelected(menuItem);
        }
        return lifecycleHandled;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    /** @return a new lifecycle. */
    public static Lifecycle createLifecycle() {
        return new Lifecycle();
    }
}
