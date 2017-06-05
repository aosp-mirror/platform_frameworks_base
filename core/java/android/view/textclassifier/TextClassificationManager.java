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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Interface to the text classification service.
 */
@SystemService(Context.TEXT_CLASSIFICATION_SERVICE)
public final class TextClassificationManager {

    private static final String LOG_TAG = "TextClassificationManager";

    private final Object mTextClassifierLock = new Object();
    private final Object mLangIdLock = new Object();

    private final Context mContext;
    private ParcelFileDescriptor mLangIdFd;
    private TextClassifier mTextClassifier;
    private LangId mLangId;

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

    /**
     * Returns information containing languages that were detected in the provided text.
     * This is a blocking operation you should avoid calling it on the UI thread.
     *
     * @throws IllegalArgumentException if text is null
     * @hide
     */
    public List<TextLanguage> detectLanguages(@NonNull CharSequence text) {
        Preconditions.checkArgument(text != null);
        try {
            if (text.length() > 0) {
                final LangId.ClassificationResult[] results =
                        getLanguageDetector().findLanguages(text.toString());
                final TextLanguage.Builder tlBuilder = new TextLanguage.Builder(0, text.length());
                final int size = results.length;
                for (int i = 0; i < size; i++) {
                    tlBuilder.setLanguage(
                            new Locale.Builder().setLanguageTag(results[i].mLanguage).build(),
                            results[i].mScore);
                }

                return Collections.unmodifiableList(Arrays.asList(tlBuilder.build()));
            }
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error detecting languages for text. Returning empty result.", t);
        }
        // Getting here means something went wrong. Return an empty result.
        return Collections.emptyList();
    }

    private LangId getLanguageDetector() throws FileNotFoundException {
        synchronized (mLangIdLock) {
            if (mLangId == null) {
                mLangIdFd = ParcelFileDescriptor.open(
                        new File("/etc/textclassifier/textclassifier.langid.model"),
                        ParcelFileDescriptor.MODE_READ_ONLY);
                mLangId = new LangId(mLangIdFd.getFd());
            }
            return mLangId;
        }
    }
}
