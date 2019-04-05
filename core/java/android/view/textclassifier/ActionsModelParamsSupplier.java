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
package android.view.textclassifier;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.KeyValueListParser;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Parses the {@link Settings.Global#TEXT_CLASSIFIER_ACTION_MODEL_PARAMS} flag.
 *
 * @hide
 */
public final class ActionsModelParamsSupplier implements
        Supplier<ActionsModelParamsSupplier.ActionsModelParams> {
    private static final String TAG = TextClassifier.DEFAULT_LOG_TAG;

    @VisibleForTesting
    static final String KEY_REQUIRED_MODEL_VERSION = "required_model_version";
    @VisibleForTesting
    static final String KEY_REQUIRED_LOCALES = "required_locales";
    @VisibleForTesting
    static final String KEY_SERIALIZED_PRECONDITIONS = "serialized_preconditions";

    private final Context mAppContext;
    private final SettingsObserver mSettingsObserver;

    private final Object mLock = new Object();
    private final Runnable mOnChangedListener;
    @Nullable
    @GuardedBy("mLock")
    private ActionsModelParams mActionsModelParams;
    @GuardedBy("mLock")
    private boolean mParsed = true;

    public ActionsModelParamsSupplier(Context context, @Nullable Runnable onChangedListener) {
        mAppContext = Preconditions.checkNotNull(context).getApplicationContext();
        mOnChangedListener = onChangedListener == null ? () -> {} : onChangedListener;
        mSettingsObserver = new SettingsObserver(mAppContext, () -> {
            synchronized (mLock) {
                Log.v(TAG, "Settings.Global.TEXT_CLASSIFIER_ACTION_MODEL_PARAMS is updated");
                mParsed = true;
                mOnChangedListener.run();
            }
        });
    }

    /**
     * Returns the parsed actions params or {@link ActionsModelParams#INVALID} if the value is
     * invalid.
     */
    @Override
    public ActionsModelParams get() {
        synchronized (mLock) {
            if (mParsed) {
                mActionsModelParams = parse(mAppContext.getContentResolver());
                mParsed = false;
            }
        }
        return mActionsModelParams;
    }

    private ActionsModelParams parse(ContentResolver contentResolver) {
        String settingStr = Settings.Global.getString(contentResolver,
                Settings.Global.TEXT_CLASSIFIER_ACTION_MODEL_PARAMS);
        if (TextUtils.isEmpty(settingStr)) {
            return ActionsModelParams.INVALID;
        }
        try {
            KeyValueListParser keyValueListParser = new KeyValueListParser(',');
            keyValueListParser.setString(settingStr);
            int version = keyValueListParser.getInt(KEY_REQUIRED_MODEL_VERSION, -1);
            if (version == -1) {
                Log.w(TAG, "ActionsModelParams.Parse, invalid model version");
                return ActionsModelParams.INVALID;
            }
            String locales = keyValueListParser.getString(KEY_REQUIRED_LOCALES, null);
            if (locales == null) {
                Log.w(TAG, "ActionsModelParams.Parse, invalid locales");
                return ActionsModelParams.INVALID;
            }
            String serializedPreconditionsStr =
                    keyValueListParser.getString(KEY_SERIALIZED_PRECONDITIONS, null);
            if (serializedPreconditionsStr == null) {
                Log.w(TAG, "ActionsModelParams.Parse, invalid preconditions");
                return ActionsModelParams.INVALID;
            }
            byte[] serializedPreconditions =
                    Base64.decode(serializedPreconditionsStr, Base64.NO_WRAP);
            return new ActionsModelParams(version, locales, serializedPreconditions);
        } catch (Throwable t) {
            Log.e(TAG, "Invalid TEXT_CLASSIFIER_ACTION_MODEL_PARAMS, ignore", t);
        }
        return ActionsModelParams.INVALID;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mAppContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        } finally {
            super.finalize();
        }
    }

    /**
     * Represents the parsed result.
     */
    public static final class ActionsModelParams {

        public static final ActionsModelParams INVALID =
                new ActionsModelParams(-1, "", new byte[0]);

        /**
         * The required model version to apply {@code mSerializedPreconditions}.
         */
        private final int mRequiredModelVersion;

        /**
         * The required model locales to apply {@code mSerializedPreconditions}.
         */
        private final String mRequiredModelLocales;

        /**
         * The serialized params that will be applied to the model file, if all requirements are
         * met. Do not modify.
         */
        private final byte[] mSerializedPreconditions;

        public ActionsModelParams(int requiredModelVersion, String requiredModelLocales,
                byte[] serializedPreconditions) {
            mRequiredModelVersion = requiredModelVersion;
            mRequiredModelLocales = Preconditions.checkNotNull(requiredModelLocales);
            mSerializedPreconditions = Preconditions.checkNotNull(serializedPreconditions);
        }

        /**
         * Returns the serialized preconditions. Returns {@code null} if the the model in use does
         * not meet all the requirements listed in the {@code ActionsModelParams} or the params
         * are invalid.
         */
        @Nullable
        public byte[] getSerializedPreconditions(ModelFileManager.ModelFile modelInUse) {
            if (this == INVALID) {
                return null;
            }
            if (modelInUse.getVersion() != mRequiredModelVersion) {
                Log.w(TAG, String.format(
                        "Not applying mSerializedPreconditions, required version=%d, actual=%d",
                        mRequiredModelVersion, modelInUse.getVersion()));
                return null;
            }
            if (!Objects.equals(modelInUse.getSupportedLocalesStr(), mRequiredModelLocales)) {
                Log.w(TAG, String.format(
                        "Not applying mSerializedPreconditions, required locales=%s, actual=%s",
                        mRequiredModelLocales, modelInUse.getSupportedLocalesStr()));
                return null;
            }
            return mSerializedPreconditions;
        }
    }

    private static final class SettingsObserver extends ContentObserver {

        private final WeakReference<Runnable> mOnChangedListener;

        SettingsObserver(Context appContext, Runnable listener) {
            super(null);
            mOnChangedListener = new WeakReference<>(listener);
            appContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.TEXT_CLASSIFIER_ACTION_MODEL_PARAMS),
                    false /* notifyForDescendants */,
                    this);
        }

        public void onChange(boolean selfChange) {
            if (mOnChangedListener.get() != null) {
                mOnChangedListener.get().run();
            }
        }
    }
}
