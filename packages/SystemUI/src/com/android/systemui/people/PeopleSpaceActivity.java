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
import android.view.ViewGroup;

import androidx.activity.ComponentActivity;
import androidx.lifecycle.ViewModelProvider;

import com.android.systemui.compose.ComposeFacade;
import com.android.systemui.people.ui.view.PeopleViewBinder;
import com.android.systemui.people.ui.viewmodel.PeopleViewModel;

import javax.inject.Inject;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** People Tile Widget configuration activity that shows the user their conversation tiles. */
public class PeopleSpaceActivity extends ComponentActivity {

    private static final String TAG = "PeopleSpaceActivity";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    private final PeopleViewModel.Factory mViewModelFactory;

    @Inject
    public PeopleSpaceActivity(PeopleViewModel.Factory viewModelFactory) {
        super();
        mViewModelFactory = viewModelFactory;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);

        PeopleViewModel viewModel = new ViewModelProvider(this, mViewModelFactory).get(
                PeopleViewModel.class);

        // Update the widget ID coming from the intent.
        int widgetId = getIntent().getIntExtra(EXTRA_APPWIDGET_ID, INVALID_APPWIDGET_ID);
        viewModel.onWidgetIdChanged(widgetId);

        Function1<PeopleViewModel.Result, Unit> onResult = (result) -> {
            finishActivity(result);
            return null;
        };

        if (ComposeFacade.INSTANCE.isComposeAvailable()) {
            Log.d(TAG, "Using the Compose implementation of the PeopleSpaceActivity");
            ComposeFacade.INSTANCE.setPeopleSpaceActivityContent(this, viewModel, onResult);
        } else {
            Log.d(TAG, "Using the View implementation of the PeopleSpaceActivity");
            ViewGroup view = PeopleViewBinder.create(this);
            PeopleViewBinder.bind(view, viewModel, /* lifecycleOwner= */ this, onResult);
            setContentView(view);
        }
    }

    private void finishActivity(PeopleViewModel.Result result) {
        if (result instanceof PeopleViewModel.Result.Success) {
            if (DEBUG) Log.d(TAG, "Widget added!");
            Intent data = ((PeopleViewModel.Result.Success) result).getData();
            setResult(RESULT_OK, data);
        } else {
            if (DEBUG) Log.d(TAG, "Activity dismissed with no widgets added!");
            setResult(RESULT_CANCELED);
        }
        finish();
    }
}
