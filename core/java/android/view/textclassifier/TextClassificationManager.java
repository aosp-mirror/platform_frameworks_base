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

package android.view.textclassifier;

import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;

import com.android.internal.util.Preconditions;

/**
 * Interface to the text classification service.
 */
@SystemService(Context.TEXT_CLASSIFICATION_SERVICE)
public final class TextClassificationManager {

    private final Object mTextClassifierLock = new Object();

    private final Context mContext;
    private TextClassifier mTextClassifier;

    /** @hide */
    public TextClassificationManager(Context context) {
        mContext = Preconditions.checkNotNull(context);
    }

    /**
     * Returns the text classifier.
     */
    public TextClassifier getTextClassifier() {
        synchronized (mTextClassifierLock) {
            if (mTextClassifier == null) {
                mTextClassifier = new TextClassifierImpl(mContext);
            }
            return mTextClassifier;
        }
    }

    /**
     * Sets the text classifier.
     * Set to null to use the system default text classifier.
     * Set to {@link TextClassifier#NO_OP} to disable text classifier features.
     */
    public void setTextClassifier(@Nullable TextClassifier textClassifier) {
        synchronized (mTextClassifierLock) {
            mTextClassifier = textClassifier;
        }
    }
}
