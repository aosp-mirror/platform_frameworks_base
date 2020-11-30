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
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Build;
import android.os.ServiceManager;
import android.view.textclassifier.TextClassifier.TextClassifierType;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;

import java.util.Objects;

/**
 * Interface to the text classification service.
 */
@SystemService(Context.TEXT_CLASSIFICATION_SERVICE)
public final class TextClassificationManager {

    private static final String LOG_TAG = TextClassifier.LOG_TAG;

    private static final TextClassificationConstants sDefaultSettings =
            new TextClassificationConstants();

    private final Object mLock = new Object();
    private final TextClassificationSessionFactory mDefaultSessionFactory =
            classificationContext -> new TextClassificationSession(
                    classificationContext, getTextClassifier());

    private final Context mContext;

    @GuardedBy("mLock")
    @Nullable
    private TextClassifier mCustomTextClassifier;
    @GuardedBy("mLock")
    private TextClassificationSessionFactory mSessionFactory;
    @GuardedBy("mLock")
    private TextClassificationConstants mSettings;

    /** @hide */
    public TextClassificationManager(Context context) {
        mContext = Objects.requireNonNull(context);
        mSessionFactory = mDefaultSessionFactory;
    }

    /**
     * Returns the text classifier that was set via {@link #setTextClassifier(TextClassifier)}.
     * If this is null, this method returns a default text classifier (i.e. either the system text
     * classifier if one exists, or a local text classifier running in this process.)
     * <p>
     * Note that requests to the TextClassifier may be handled in an OEM-provided process rather
     * than in the calling app's process.
     *
     * @see #setTextClassifier(TextClassifier)
     */
    @NonNull
    public TextClassifier getTextClassifier() {
        synchronized (mLock) {
            if (mCustomTextClassifier != null) {
                return mCustomTextClassifier;
            } else if (getSettings().isSystemTextClassifierEnabled()) {
                return getSystemTextClassifier(SystemTextClassifier.SYSTEM);
            } else {
                return getLocalTextClassifier();
            }
        }
    }

    /**
     * Sets the text classifier.
     * Set to null to use the system default text classifier.
     * Set to {@link TextClassifier#NO_OP} to disable text classifier features.
     */
    public void setTextClassifier(@Nullable TextClassifier textClassifier) {
        synchronized (mLock) {
            mCustomTextClassifier = textClassifier;
        }
    }

    /**
     * Returns a specific type of text classifier.
     * If the specified text classifier cannot be found, this returns {@link TextClassifier#NO_OP}.
     *
     * @see TextClassifier#LOCAL
     * @see TextClassifier#SYSTEM
     * @see TextClassifier#DEFAULT_SYSTEM
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public TextClassifier getTextClassifier(@TextClassifierType int type) {
        switch (type) {
            case TextClassifier.LOCAL:
                return getLocalTextClassifier();
            default:
                return getSystemTextClassifier(type);
        }
    }

    private TextClassificationConstants getSettings() {
        synchronized (mLock) {
            if (mSettings == null) {
                mSettings = new TextClassificationConstants();
            }
            return mSettings;
        }
    }

    /**
     * Call this method to start a text classification session with the given context.
     * A session is created with a context helping the classifier better understand
     * what the user needs and consists of queries and feedback events. The queries
     * are directly related to providing useful functionality to the user and the events
     * are a feedback loop back to the classifier helping it learn and better serve
     * future queries.
     *
     * <p> All interactions with the returned classifier are considered part of a single
     * session and are logically grouped. For example, when a text widget is focused
     * all user interactions around text editing (selection, editing, etc) can be
     * grouped together to allow the classifier get better.
     *
     * @param classificationContext The context in which classification would occur
     *
     * @return An instance to perform classification in the given context
     */
    @NonNull
    public TextClassifier createTextClassificationSession(
            @NonNull TextClassificationContext classificationContext) {
        Objects.requireNonNull(classificationContext);
        final TextClassifier textClassifier =
                mSessionFactory.createTextClassificationSession(classificationContext);
        Objects.requireNonNull(textClassifier, "Session Factory should never return null");
        return textClassifier;
    }

    /**
     * @see #createTextClassificationSession(TextClassificationContext, TextClassifier)
     * @hide
     */
    public TextClassifier createTextClassificationSession(
            TextClassificationContext classificationContext, TextClassifier textClassifier) {
        Objects.requireNonNull(classificationContext);
        Objects.requireNonNull(textClassifier);
        return new TextClassificationSession(classificationContext, textClassifier);
    }

    /**
     * Sets a TextClassificationSessionFactory to be used to create session-aware TextClassifiers.
     *
     * @param factory the textClassification session factory. If this is null, the default factory
     *      will be used.
     */
    public void setTextClassificationSessionFactory(
            @Nullable TextClassificationSessionFactory factory) {
        synchronized (mLock) {
            if (factory != null) {
                mSessionFactory = factory;
            } else {
                mSessionFactory = mDefaultSessionFactory;
            }
        }
    }

    /** @hide */
    private TextClassifier getSystemTextClassifier(@TextClassifierType int type) {
        synchronized (mLock) {
            if (getSettings().isSystemTextClassifierEnabled()) {
                try {
                    Log.d(LOG_TAG, "Initializing SystemTextClassifier, type = "
                            + TextClassifier.typeToString(type));
                    return new SystemTextClassifier(
                            mContext,
                            getSettings(),
                            /* useDefault= */ type == TextClassifier.DEFAULT_SYSTEM);
                } catch (ServiceManager.ServiceNotFoundException e) {
                    Log.e(LOG_TAG, "Could not initialize SystemTextClassifier", e);
                }
            }
            return TextClassifier.NO_OP;
        }
    }

    /**
     * Returns a local textclassifier, which is running in this process.
     */
    @NonNull
    private TextClassifier getLocalTextClassifier() {
        Log.d(LOG_TAG, "Local text-classifier not supported. Returning a no-op text-classifier.");
        return TextClassifier.NO_OP;
    }

    /** @hide **/
    public void dump(IndentingPrintWriter pw) {
        getSystemTextClassifier(TextClassifier.DEFAULT_SYSTEM).dump(pw);
        getSystemTextClassifier(TextClassifier.SYSTEM).dump(pw);
        getSettings().dump(pw);
    }

    /** @hide */
    public static TextClassificationConstants getSettings(Context context) {
        Objects.requireNonNull(context);
        final TextClassificationManager tcm =
                context.getSystemService(TextClassificationManager.class);
        if (tcm != null) {
            return tcm.getSettings();
        } else {
            // Use default settings if there is no tcm.
            return sDefaultSettings;
        }
    }
}
