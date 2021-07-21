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

package android.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;

import java.util.Arrays;

/**
 * An {@link EditText} component that allows customizing its
 * {@link android.view.inputmethod.InputConnection}.
 */
public class CustomInputConnectionEditText extends EditText {
    private static final String LOG_TAG = "CustomInputConnectionEditText";

    private String[] mContentMimeTypes;
    private InputConnectionWrapper mInputConnectionWrapper;

    public CustomInputConnectionEditText(Context context) {
        super(context);
    }

    public CustomInputConnectionEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomInputConnectionEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CustomInputConnectionEditText(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public void setContentMimeTypes(String[] contentMimeTypes) {
        mContentMimeTypes = contentMimeTypes;
    }

    public void setInputConnectionWrapper(InputConnectionWrapper inputConnectionWrapper) {
        mInputConnectionWrapper = inputConnectionWrapper;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection ic = super.onCreateInputConnection(outAttrs);
        if (ic == null) {
            Log.d(LOG_TAG, "Not wrapping InputConnection, because super returned null");
            return null;
        }
        if (mInputConnectionWrapper == null) {
            Log.d(LOG_TAG, "Not wrapping InputConnection, because wrapper is null");
            return ic;
        }

        Log.d(LOG_TAG, "Wrapping InputConnection");
        mInputConnectionWrapper.setTarget(ic);

        Log.d(LOG_TAG,
                "Setting EditorInfo.contentMimeTypes: " + Arrays.toString(mContentMimeTypes));
        outAttrs.contentMimeTypes = mContentMimeTypes;

        return mInputConnectionWrapper;
    }
}
