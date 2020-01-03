/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.autofill.ui;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.service.autofill.Dataset;
import android.util.Log;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.TextView;

/**
 * This is a temporary inline suggestion UI inflater which will be replaced by the ExtServices
 * implementation.
 *
 * TODO(b/146453086): remove this class once autofill ext service is implemented.
 *
 * @hide
 */
public class InlineSuggestionUi {

    private static final String TAG = "InlineSuggestionUi";

    private final Context mContext;

    public InlineSuggestionUi(Context context) {
        this.mContext = context;
    }

    /**
     * Returns a {@link SurfaceControl} with the inflated content embedded in it.
     */
    @MainThread
    @Nullable
    public SurfaceControl inflate(@NonNull Dataset dataset, @NonNull AutofillId autofillId,
            int width, int height, @Nullable View.OnClickListener onClickListener) {
        Log.d(TAG, "Inflating the inline suggestion UI");
        final int index = dataset.getFieldIds().indexOf(autofillId);
        if (index < 0) {
            Slog.w(TAG, "inflateInlineSuggestion(): AutofillId=" + autofillId
                    + " not found in dataset");
            return null;
        }
        final AutofillValue datasetValue = dataset.getFieldValues().get(index);
        //TODO(b/137800469): Pass in inputToken from IME.
        final SurfaceControlViewHost wvr = new SurfaceControlViewHost(mContext,
                mContext.getDisplay(), (IBinder) null);
        final SurfaceControl sc = wvr.getSurfacePackage().getSurfaceControl();

        TextView textView = new TextView(mContext);
        textView.setText(datasetValue.getTextValue());
        textView.setBackgroundColor(Color.WHITE);
        textView.setTextColor(Color.BLACK);
        if (onClickListener != null) {
            textView.setOnClickListener(onClickListener);
        }

        WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION, 0, PixelFormat.TRANSPARENT);
        wvr.addView(textView, lp);
        return sc;
    }
}
