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
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityThread;
import android.content.Context;
import android.database.ContentObserver;
import android.os.ServiceManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.provider.Settings;
import android.service.textclassifier.TextClassifierService;
import android.view.textclassifier.TextClassifier.TextClassifierType;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.lang.ref.WeakReference;

/**
 * Interface to the text classification service.
 */
@SystemService(Context.TEXT_CLASSIFICATION_SERVICE)
public final class TextClassificationManager {

    private static final String LOG_TAG = "TextClassificationManager";

    private static final TextClassificationConstants sDefaultSettings =
            new TextClassificationConstants(() ->  null);

    private final Object mLock = new Object();
    private final TextClassificationSessionFactory mDefaultSessionFactory =
            classificationContext -> new TextClassificationSession(
                    classificationContext, getTextClassifier());

    private final Context mContext;
    private final SettingsObserver mSettingsObserver;

    @GuardedBy("mLock")
    @Nullable
    private TextClassifier mCustomTextClassifier;
    @GuardedBy("mLock")
    @Nullable
    private TextClassifier mLocalTextClassifier;
    @GuardedBy("mLock")
    @Nullable
    private TextClassifier mSystemTextClassifier;
    @GuardedBy("mLock")
    private TextClassificationSessionFactory mSessionFactory;
    @GuardedBy("mLock")
    private TextClassificationConstants mSettings;

    /** @hide */
    public TextClassificationManager(Context context) {
        mContext = Preconditions.checkNotNull(context);
        mSessionFactory = mDefaultSessionFactory;
        mSettingsObserver = new SettingsObserver(this);
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
            } else if (isSystemTextClassifierEnabled()) {
                return getSystemTextClassifier();
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
     * @hide
     */
    @UnsupportedAppUsage
    public TextClassifier getTextClassifier(@TextClassifierType int type) {
        switch (type) {
            case TextClassifier.LOCAL:
                return getLocalTextClassifier();
            default:
                return getSystemTextClassifier();
        }
    }

    private TextClassificationConstants getSettings() {
        synchronized (mLock) {
            if (mSettings == null) {
                mSettings = new TextClassificationConstants(
                        () ->  Settings.Global.getString(
                                getApplicationContext().getContentResolver(),
                                Settings.Global.TEXT_CLASSIFIER_CONSTANTS));
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
        Preconditions.checkNotNull(classificationContext);
        final TextClassifier textClassifier =
                mSessionFactory.createTextClassificationSession(classificationContext);
        Preconditions.checkNotNull(textClassifier, "Session Factory should never return null");
        return textClassifier;
    }

    /**
     * @see #createTextClassificationSession(TextClassificationContext, TextClassifier)
     * @hide
     */
    public TextClassifier createTextClassificationSession(
            TextClassificationContext classificationContext, TextClassifier textClassifier) {
        Preconditions.checkNotNull(classificationContext);
        Preconditions.checkNotNull(textClassifier);
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

    @Override
    protected void finalize() throws Throwable {
        try {
            // Note that fields could be null if the constructor threw.
            if (mSettingsObserver != null) {
                getApplicationContext().getContentResolver()
                        .unregisterContentObserver(mSettingsObserver);
                if (ConfigParser.ENABLE_DEVICE_CONFIG) {
                    DeviceConfig.removeOnPropertiesChangedListener(mSettingsObserver);
                }
            }
        } finally {
            super.finalize();
        }
    }

    private TextClassifier getSystemTextClassifier() {
        synchronized (mLock) {
            if (mSystemTextClassifier == null && isSystemTextClassifierEnabled()) {
                try {
                    mSystemTextClassifier = new SystemTextClassifier(mContext, getSettings());
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

    /**
     * Returns a local textclassifier, which is running in this process.
     */
    @NonNull
    private TextClassifier getLocalTextClassifier() {
        synchronized (mLock) {
            if (mLocalTextClassifier == null) {
                if (getSettings().isLocalTextClassifierEnabled()) {
                    mLocalTextClassifier =
                            new TextClassifierImpl(mContext, getSettings(), TextClassifier.NO_OP);
                } else {
                    Log.d(LOG_TAG, "Local TextClassifier disabled");
                    mLocalTextClassifier = TextClassifier.NO_OP;
                }
            }
            return mLocalTextClassifier;
        }
    }

    private boolean isSystemTextClassifierEnabled() {
        return getSettings().isSystemTextClassifierEnabled()
                && TextClassifierService.getServiceComponentName(mContext) != null;
    }

    /** @hide */
    @VisibleForTesting
    public void invalidateForTesting() {
        invalidate();
    }

    private void invalidate() {
        synchronized (mLock) {
            mSettings = null;
            mLocalTextClassifier = null;
            mSystemTextClassifier = null;
        }
    }

    Context getApplicationContext() {
        return mContext.getApplicationContext() != null
                ? mContext.getApplicationContext()
                : mContext;
    }

    /** @hide **/
    public void dump(IndentingPrintWriter pw) {
        getLocalTextClassifier().dump(pw);
        getSystemTextClassifier().dump(pw);
        getSettings().dump(pw);
    }

    /** @hide */
    public static TextClassificationConstants getSettings(Context context) {
        Preconditions.checkNotNull(context);
        final TextClassificationManager tcm =
                context.getSystemService(TextClassificationManager.class);
        if (tcm != null) {
            return tcm.getSettings();
        } else {
            // Use default settings if there is no tcm.
            return sDefaultSettings;
        }
    }

    private static final class SettingsObserver extends ContentObserver
            implements DeviceConfig.OnPropertiesChangedListener {

        private final WeakReference<TextClassificationManager> mTcm;

        SettingsObserver(TextClassificationManager tcm) {
            super(null);
            mTcm = new WeakReference<>(tcm);
            tcm.getApplicationContext().getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.TEXT_CLASSIFIER_CONSTANTS),
                    false /* notifyForDescendants */,
                    this);
            if (ConfigParser.ENABLE_DEVICE_CONFIG) {
                DeviceConfig.addOnPropertiesChangedListener(
                        DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                        ActivityThread.currentApplication().getMainExecutor(),
                        this);
            }
        }

        @Override
        public void onChange(boolean selfChange) {
            invalidateSettings();
        }

        @Override
        public void onPropertiesChanged(Properties properties) {
            invalidateSettings();
        }

        private void invalidateSettings() {
            final TextClassificationManager tcm = mTcm.get();
            if (tcm != null) {
                tcm.invalidate();
            }
        }
    }
}
