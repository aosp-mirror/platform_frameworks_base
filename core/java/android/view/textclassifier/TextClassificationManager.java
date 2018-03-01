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
import android.view.textclassifier.TextClassifier.TextClassifierType;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

/**
 * Interface to the text classification service.
 */
@SystemService(Context.TEXT_CLASSIFICATION_SERVICE)
public final class TextClassificationManager {

    private static final String LOG_TAG = "TextClassificationManager";

    private final Object mLock = new Object();

    private final Context mContext;
    private final TextClassificationConstants mSettings;

    @GuardedBy("mLock")
    private TextClassifier mTextClassifier;
    @GuardedBy("mLock")
    private TextClassifier mLocalTextClassifier;
    @GuardedBy("mLock")
    private TextClassifier mSystemTextClassifier;

    /** @hide */
    public TextClassificationManager(Context context) {
        mContext = Preconditions.checkNotNull(context);
        mSettings = TextClassificationConstants.loadFromString(Settings.Global.getString(
                context.getContentResolver(), Settings.Global.TEXT_CLASSIFIER_CONSTANTS));
    }

    /**
     * Returns the text classifier that was set via {@link #setTextClassifier(TextClassifier)}.
     * If this is null, this method returns a default text classifier (i.e. either the system text
     * classifier if one exists, or a local text classifier running in this app.)
     *
     * @see #setTextClassifier(TextClassifier)
     */
    public TextClassifier getTextClassifier() {
        synchronized (mLock) {
            if (mTextClassifier == null) {
                if (isSystemTextClassifierEnabled()) {
                    mTextClassifier = getSystemTextClassifier();
                } else {
                    mTextClassifier = getLocalTextClassifier();
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

    /**
     * Returns a specific type of text classifier.
     * If the specified text classifier cannot be found, this returns {@link TextClassifier#NO_OP}.
     *
     * @see TextClassifier#LOCAL
     * @see TextClassifier#SYSTEM
     * @hide
     */
    // TODO: Expose as system API.
    public TextClassifier getTextClassifier(@TextClassifierType int type) {
        switch (type) {
            case TextClassifier.LOCAL:
                return getLocalTextClassifier();
            default:
                return getSystemTextClassifier();
        }
    }

    /** @hide */
    public TextClassificationConstants getSettings() {
        return mSettings;
    }

    private TextClassifier getSystemTextClassifier() {
        synchronized (mLock) {
            if (mSystemTextClassifier == null && isSystemTextClassifierEnabled()) {
                try {
                    mSystemTextClassifier = new SystemTextClassifier(mContext, mSettings);
                    Log.d(LOG_TAG, "Initialized SystemTextClassifier");
                } catch (ServiceManager.ServiceNotFoundException e) {
                    Log.e(LOG_TAG, "Could not initialize SystemTextClassifier", e);
                }
            }
        }
        if (mSystemTextClassifier != null) {
            return mSystemTextClassifier;
        }
        return TextClassifier.NO_OP;
    }

    private TextClassifier getLocalTextClassifier() {
        synchronized (mLock) {
            if (mLocalTextClassifier == null) {
                if (mSettings.isLocalTextClassifierEnabled()) {
                    mLocalTextClassifier = new TextClassifierImpl(mContext, mSettings);
                } else {
                    Log.d(LOG_TAG, "Local TextClassifier disabled");
                    mLocalTextClassifier = TextClassifierImpl.NO_OP;
                }
            }
            return mLocalTextClassifier;
        }
    }

    private boolean isSystemTextClassifierEnabled() {
        return mSettings.isSystemTextClassifierEnabled()
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
