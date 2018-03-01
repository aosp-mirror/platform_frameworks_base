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
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.textclassifier.TextClassifierService;

import com.android.internal.util.Preconditions;

/**
 * Interface to the text classification service.
 */
@SystemService(Context.TEXT_CLASSIFICATION_SERVICE)
public final class TextClassificationManager {

    // TODO: Make this a configurable flag.
    private static final boolean SYSTEM_TEXT_CLASSIFIER_ENABLED = true;

    private static final String LOG_TAG = "TextClassificationManager";

    private final Object mLock = new Object();

    private final Context mContext;
    private final TextClassificationConstants mSettings;
    private TextClassifier mTextClassifier;
    private TextClassifier mSystemTextClassifier;

    /** @hide */
    public TextClassificationManager(Context context) {
        mContext = Preconditions.checkNotNull(context);
        mSettings = TextClassificationConstants.loadFromString(Settings.Global.getString(
                context.getContentResolver(), Settings.Global.TEXT_CLASSIFIER_CONSTANTS));
    }

    /**
     * Returns the system's default TextClassifier.
     * @hide
     */
    // TODO: Unhide when this is ready.
    public TextClassifier getSystemDefaultTextClassifier() {
        synchronized (mLock) {
            if (mSystemTextClassifier == null && isSystemTextClassifierEnabled()) {
                try {
                    Log.d(LOG_TAG, "Initialized SystemTextClassifier");
                    mSystemTextClassifier = new SystemTextClassifier(mContext, mSettings);
                } catch (ServiceManager.ServiceNotFoundException e) {
                    Log.e(LOG_TAG, "Could not initialize SystemTextClassifier", e);
                }
            }
            if (mSystemTextClassifier == null) {
                Log.d(LOG_TAG, "Using an in-process TextClassifier as the system default");
                mSystemTextClassifier = new TextClassifierImpl(mContext, mSettings);
            }
        }
        return mSystemTextClassifier;
    }

    /**
     * Returns the text classifier.
     */
    public TextClassifier getTextClassifier() {
        synchronized (mLock) {
            if (mTextClassifier == null) {
                if (isSystemTextClassifierEnabled()) {
                    mTextClassifier = getSystemDefaultTextClassifier();
                } else {
                    mTextClassifier = new TextClassifierImpl(mContext, mSettings);
                }
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
        synchronized (mLock) {
            mTextClassifier = textClassifier;
        }
    }

    private boolean isSystemTextClassifierEnabled() {
        return SYSTEM_TEXT_CLASSIFIER_ENABLED
                && TextClassifierService.getServiceComponentName(mContext) != null;
    }

    /** @hide */
    public static TextClassificationConstants getSettings(Context context) {
        Preconditions.checkNotNull(context);
        final TextClassificationManager tcm =
                context.getSystemService(TextClassificationManager.class);
        if (tcm != null) {
            return tcm.mSettings;
        } else {
            return TextClassificationConstants.loadFromString(Settings.Global.getString(
                    context.getContentResolver(), Settings.Global.TEXT_CLASSIFIER_CONSTANTS));
        }
    }
}
