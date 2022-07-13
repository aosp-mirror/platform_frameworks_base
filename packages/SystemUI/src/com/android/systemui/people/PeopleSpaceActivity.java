/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.people;

import static android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID;
import static android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.ComponentActivity;
import androidx.lifecycle.ViewModelProvider;

import com.android.systemui.people.ui.view.PeopleViewBinder;
import com.android.systemui.people.ui.viewmodel.PeopleViewModel;

import javax.inject.Inject;

/** People Tile Widget configuration activity that shows the user their conversation tiles. */
public class PeopleSpaceActivity extends ComponentActivity {

    private static final String TAG = "PeopleSpaceActivity";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    private final PeopleViewModel.Factory mViewModelFactory;
    private PeopleViewModel mViewModel;

    @Inject
    public PeopleSpaceActivity(PeopleViewModel.Factory viewModelFactory) {
        super();
        mViewModelFactory = viewModelFactory;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        mViewModel = new ViewModelProvider(this, mViewModelFactory).get(PeopleViewModel.class);

        // Update the widget ID coming from the intent.
        int widgetId = getIntent().getIntExtra(EXTRA_APPWIDGET_ID, INVALID_APPWIDGET_ID);
        mViewModel.onWidgetIdChanged(widgetId);

        ViewGroup view = PeopleViewBinder.create(this);
        PeopleViewBinder.bind(view, mViewModel, /* lifecycleOwner= */ this,
                () -> {
                    finishActivity();
                    return null;
                });
        setContentView(view);
    }

    /** Finish activity with a successful widget configuration result. */
    private void finishActivity() {
        if (DEBUG) Log.d(TAG, "Widget added!");
        setActivityResult(RESULT_OK);
        finish();
    }

    /** Finish activity without choosing a widget. */
    public void dismissActivity(View v) {
        if (DEBUG) Log.d(TAG, "Activity dismissed with no widgets added!");
        setResult(RESULT_CANCELED);
        finish();
    }

    private void setActivityResult(int result) {
        Intent resultValue = new Intent();
        resultValue.putExtra(EXTRA_APPWIDGET_ID, mViewModel.getAppWidgetId().getValue());
        setResult(result, resultValue);
    }
}
