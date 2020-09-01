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

package com.android.server.backup.encryption;

import static com.android.internal.util.Preconditions.checkState;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.security.keystore.recovery.InternalRecoveryServiceException;
import android.security.keystore.recovery.RecoveryController;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.security.KeyStoreException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * State about encrypted backups that needs to be remembered.
 */
public class CryptoSettings {

    private static final String TAG = "CryptoSettings";

    private static final String SHARED_PREFERENCES_NAME = "crypto_settings";

    private static final String KEY_IS_INITIALIZED = "isInitialized";
    private static final String KEY_ACTIVE_SECONDARY_ALIAS = "activeSecondary";
    private static final String KEY_NEXT_SECONDARY_ALIAS = "nextSecondary";
    private static final String SECONDARY_KEY_LAST_ROTATED_AT = "secondaryKeyLastRotatedAt";
    private static final String[] SETTINGS_FOR_BACKUP = {
        KEY_IS_INITIALIZED,
        KEY_ACTIVE_SECONDARY_ALIAS,
        KEY_NEXT_SECONDARY_ALIAS,
        SECONDARY_KEY_LAST_ROTATED_AT
    };

    private static final long DEFAULT_SECONDARY_KEY_ROTATION_PERIOD =
            TimeUnit.MILLISECONDS.convert(31, TimeUnit.DAYS);

    private static final String KEY_ANCESTRAL_SECONDARY_KEY_VERSION =
            "ancestral_secondary_key_version";

    private final SharedPreferences mSharedPreferences;
    private final Context mContext;

    /**
     * A new instance.
     *
     * @param context For looking up the {@link SharedPreferences}, for storing state.
     * @return The instance.
     */
    public static CryptoSettings getInstance(Context context) {
        // We need single process mode because CryptoSettings may be used from several processes
        // simultaneously.
        SharedPreferences sharedPreferences =
                context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        return new CryptoSettings(sharedPreferences, context);
    }

    /**
     * A new instance using {@link SharedPreferences} in the default mode.
     *
     * <p>This will not work across multiple processes but will work in tests.
     */
    @VisibleForTesting
    public static CryptoSettings getInstanceForTesting(Context context) {
        SharedPreferences sharedPreferences =
                context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        return new CryptoSettings(sharedPreferences, context);
    }

    private CryptoSettings(SharedPreferences sharedPreferences, Context context) {
        mSharedPreferences = Objects.requireNonNull(sharedPreferences);
        mContext = Objects.requireNonNull(context);
    }

    /**
     * The alias of the current active secondary key. This should be used to retrieve the key from
     * AndroidKeyStore.
     */
    public Optional<String> getActiveSecondaryKeyAlias() {
        return getStringInSharedPrefs(KEY_ACTIVE_SECONDARY_ALIAS);
    }

    /**
     * The alias of the secondary key to which the client is rotating. The rotation is not
     * immediate, which is why this setting is needed. Once the next key is created, it can take up
     * to 72 hours potentially (or longer if the user has no network) for the next key to be synced
     * with the keystore. Only after that has happened does the client attempt to re-wrap all
     * tertiary keys and commit the rotation.
     */
    public Optional<String> getNextSecondaryKeyAlias() {
        return getStringInSharedPrefs(KEY_NEXT_SECONDARY_ALIAS);
    }

    /**
     * If the settings have been initialized.
     */
    public boolean getIsInitialized() {
        return mSharedPreferences.getBoolean(KEY_IS_INITIALIZED, false);
    }

    /**
     * Sets the alias of the currently active secondary key.
     *
     * @param activeAlias The alias, as in AndroidKeyStore.
     * @throws IllegalArgumentException if the alias is not in the user's keystore.
     */
    public void setActiveSecondaryKeyAlias(String activeAlias) throws IllegalArgumentException {
        assertIsValidAlias(activeAlias);
        mSharedPreferences.edit().putString(KEY_ACTIVE_SECONDARY_ALIAS, activeAlias).apply();
    }

    /**
     * Sets the alias of the secondary key to which the client is rotating.
     *
     * @param nextAlias The alias, as in AndroidKeyStore.
     * @throws KeyStoreException if unable to check whether alias is valid in the keystore.
     * @throws IllegalArgumentException if the alias is not in the user's keystore.
     */
    public void setNextSecondaryAlias(String nextAlias) throws IllegalArgumentException {
        assertIsValidAlias(nextAlias);
        mSharedPreferences.edit().putString(KEY_NEXT_SECONDARY_ALIAS, nextAlias).apply();
    }

    /**
     * Unsets the alias of the key to which the client is rotating. This is generally performed once
     * a rotation is complete.
     */
    public void removeNextSecondaryKeyAlias() {
        mSharedPreferences.edit().remove(KEY_NEXT_SECONDARY_ALIAS).apply();
    }

    /**
     * Sets the timestamp of when the secondary key was last rotated.
     *
     * @param timestamp The timestamp to set.
     */
    public void setSecondaryLastRotated(long timestamp) {
        mSharedPreferences.edit().putLong(SECONDARY_KEY_LAST_ROTATED_AT, timestamp).apply();
    }

    /**
     * Returns a timestamp of when the secondary key was last rotated.
     *
     * @return The timestamp.
     */
    public Optional<Long> getSecondaryLastRotated() {
        if (!mSharedPreferences.contains(SECONDARY_KEY_LAST_ROTATED_AT)) {
            return Optional.empty();
        }
        return Optional.of(mSharedPreferences.getLong(SECONDARY_KEY_LAST_ROTATED_AT, -1));
    }

    /**
     * Sets the settings to have been initialized. (Otherwise loading should try to initialize
     * again.)
     */
    private void setIsInitialized() {
        mSharedPreferences.edit().putBoolean(KEY_IS_INITIALIZED, true).apply();
    }

    /**
     * Initializes with the given key alias.
     *
     * @param alias The secondary key alias to be set as active.
     * @throws IllegalArgumentException if the alias does not reference a valid key.
     * @throws IllegalStateException if attempting to initialize an already initialized settings.
     */
    public void initializeWithKeyAlias(String alias) throws IllegalArgumentException {
        checkState(
                !getIsInitialized(), "Attempting to initialize an already initialized settings.");
        setActiveSecondaryKeyAlias(alias);
        setIsInitialized();
    }

    /** Returns the secondary key version of the encrypted backup set to restore from (if set). */
    public Optional<String> getAncestralSecondaryKeyVersion() {
        return Optional.ofNullable(
                mSharedPreferences.getString(KEY_ANCESTRAL_SECONDARY_KEY_VERSION, null));
    }

    /** Sets the secondary key version of the encrypted backup set to restore from. */
    public void setAncestralSecondaryKeyVersion(String ancestralSecondaryKeyVersion) {
        mSharedPreferences
                .edit()
                .putString(KEY_ANCESTRAL_SECONDARY_KEY_VERSION, ancestralSecondaryKeyVersion)
                .apply();
    }

    /** The number of milliseconds between secondary key rotation */
    public long backupSecondaryKeyRotationIntervalMs() {
        return DEFAULT_SECONDARY_KEY_ROTATION_PERIOD;
    }

    /** Deletes all crypto settings related to backup (as opposed to restore). */
    public void clearAllSettingsForBackup() {
        Editor sharedPrefsEditor = mSharedPreferences.edit();
        for (String backupSettingKey : SETTINGS_FOR_BACKUP) {
            sharedPrefsEditor.remove(backupSettingKey);
        }
        sharedPrefsEditor.apply();

        Slog.d(TAG, "Cleared crypto settings for backup");
    }

    /**
     * Throws {@link IllegalArgumentException} if the alias does not refer to a key that is in
     * the {@link RecoveryController}.
     */
    private void assertIsValidAlias(String alias) throws IllegalArgumentException {
        try {
            if (!RecoveryController.getInstance(mContext).getAliases().contains(alias)) {
                throw new IllegalArgumentException(alias + " is not in RecoveryController");
            }
        } catch (InternalRecoveryServiceException e) {
            throw new IllegalArgumentException("Problem accessing recovery service", e);
        }
    }

    private Optional<String> getStringInSharedPrefs(String key) {
        return Optional.ofNullable(mSharedPreferences.getString(key, null));
    }
}
