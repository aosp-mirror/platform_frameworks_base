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
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.text.LangId;
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
 *
 * <p>You do not instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService}.
 */
public final class TextClassificationManager {

    private static final String LOG_TAG = "TextClassificationManager";

    private final Object mTextClassifierLock = new Object();
    private final Object mLangIdLock = new Object();

    private final Context mContext;
    // TODO: Implement a way to close the file descriptor.
    private ParcelFileDescriptor mFd;
    private TextClassifier mDefault;
    private LangId mLangId;

    /** @hide */
    public TextClassificationManager(Context context) {
        mContext = Preconditions.checkNotNull(context);
    }

    /**
     * Returns the default text classifier.
     */
    public TextClassifier getDefaultTextClassifier() {
        synchronized (mTextClassifierLock) {
            if (mDefault == null) {
                try {
                    mFd = ParcelFileDescriptor.open(
                            new File("/etc/assistant/smart-selection.model"),
                            ParcelFileDescriptor.MODE_READ_ONLY);
                    mDefault = new TextClassifierImpl(mContext, mFd);
                } catch (FileNotFoundException e) {
                    Log.e(LOG_TAG, "Error accessing 'text classifier selection' model file.", e);
                    mDefault = TextClassifier.NO_OP;
                }
            }
            return mDefault;
        }
    }

    /**
     * Returns information containing languages that were detected in the provided text.
     * This is a blocking operation you should avoid calling it on the UI thread.
     *
     * @throws IllegalArgumentException if text is null
     */
    public List<TextLanguage> detectLanguages(@NonNull CharSequence text) {
        Preconditions.checkArgument(text != null);
        try {
            if (text.length() > 0) {
                final String language = getLanguageDetector().findLanguage(text.toString());
                final Locale locale = new Locale.Builder().setLanguageTag(language).build();
                return Collections.unmodifiableList(Arrays.asList(
                        new TextLanguage.Builder(0, text.length())
                                .setLanguage(locale, 1.0f /* confidence */)
                                .build()));
            }
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error detecting languages for text. Returning empty result.", t);
        }
        // Getting here means something went wrong. Return an empty result.
        return Collections.emptyList();
    }

    private LangId getLanguageDetector() {
        synchronized (mLangIdLock) {
            if (mLangId == null) {
                // TODO: Use a file descriptor as soon as we start to depend on a model file
                // for language detection.
                mLangId = new LangId(0);
            }
            return mLangId;
        }
    }
}
