/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.autofill;

import static com.android.server.autofill.Helper.DEBUG;

import android.app.Activity;
import android.content.Context;
import android.util.Slog;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.Dataset;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * View responsible for drawing the {@link Dataset} options that can be used to auto-fill an
 * {@link Activity}.
 */
final class DatasetPicker extends LinearLayout {

    private static final String TAG = "DatasetPicker";

    // TODO(b/33197203): use / calculate proper values instead of hardcoding them
    private static final LayoutParams NAME_PARAMS = new LayoutParams(400,
            WindowManager.LayoutParams.WRAP_CONTENT);
    private static final LayoutParams DROP_DOWN_PARAMS = new LayoutParams(100,
            WindowManager.LayoutParams.WRAP_CONTENT);

    private final Line[] mLines;

    private boolean mExpanded;
    private final Listener mListener;

    public DatasetPicker(Context context, Listener listener, List<Dataset> datasets) {
        super(context);

        mListener = listener;

        // TODO(b/33197203): use XML layout
        setOrientation(LinearLayout.VERTICAL);

        final int size = datasets.size();
        mLines = new Line[size];

        for (int i = 0; i < size; i++) {
            final boolean first = i == 0;
            final Line line = new Line(context, datasets.get(i), first);
            mLines[i] = line;
            if (first) {
                addView(line);
            }
        }
        mExpanded = false;
    }

    private void togleDropDown() {
        if (mExpanded) {
            hideDropDown();
            return;
        }
        for (int i = 1; i < mLines.length; i++) {
            addView(mLines[i]);
        }
        mExpanded = true;
    }

    private void hideDropDown() {
        if (!mExpanded) return;
        // TODO(b/33197203): invert order to be less janky?
        for (int i = 1; i < mLines.length; i++) {
            removeView(mLines[i]);
        }
        mExpanded = false;
    }

    private class Line extends LinearLayout {
        final TextView name;
        final ImageView dropDown;

        private Line(Context context, Dataset dataset, boolean first) {
            super(context);

            final View.OnClickListener l = new View.OnClickListener() {

                @Override
                public void onClick(View v) {
                    if (DEBUG) Slog.d(TAG, "dataset picked: " + dataset.getName());
                    mListener.onDatasetPicked(dataset);

                }
            };

            // TODO(b/33197203): use XML layout
            setOrientation(LinearLayout.HORIZONTAL);

            name = new TextView(context);
            name.setLayoutParams(NAME_PARAMS);
            name.setText(dataset.getName());
            name.setOnClickListener(l);

            dropDown = new ImageView(context);
            dropDown.setLayoutParams(DROP_DOWN_PARAMS);
            // TODO(b/33197203): use proper icon
            dropDown.setImageResource(com.android.internal.R.drawable.arrow_down_float);
            dropDown.setOnClickListener((v) -> {
                togleDropDown();
            });

            if (!first) {
                dropDown.setVisibility(View.INVISIBLE);
            }

            addView(name);
            addView(dropDown);
        }
    }

    static interface Listener {
        void onDatasetPicked(Dataset dataset);
    }
}
