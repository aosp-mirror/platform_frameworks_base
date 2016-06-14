/**
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.voiceinteraction;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.Keyphrase;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.text.TextUtils;
import android.util.Slog;

import java.util.Locale;
import java.util.UUID;

/**
 * Helper to manage the database of the sound models that have been registered on the device.
 *
 * @hide
 */
public class DatabaseHelper extends SQLiteOpenHelper {
    static final String TAG = "SoundModelDBHelper";
    static final boolean DBG = false;

    private static final String NAME = "sound_model.db";
    private static final int VERSION = 5;

    public static interface SoundModelContract {
        public static final String TABLE = "sound_model";
        public static final String KEY_MODEL_UUID = "model_uuid";
        public static final String KEY_VENDOR_UUID = "vendor_uuid";
        public static final String KEY_KEYPHRASE_ID = "keyphrase_id";
        public static final String KEY_TYPE = "type";
        public static final String KEY_DATA = "data";
        public static final String KEY_RECOGNITION_MODES = "recognition_modes";
        public static final String KEY_LOCALE = "locale";
        public static final String KEY_HINT_TEXT = "hint_text";
        public static final String KEY_USERS = "users";
    }

    // Table Create Statement
    private static final String CREATE_TABLE_SOUND_MODEL = "CREATE TABLE "
            + SoundModelContract.TABLE + "("
            + SoundModelContract.KEY_MODEL_UUID + " TEXT PRIMARY KEY,"
            + SoundModelContract.KEY_VENDOR_UUID + " TEXT, "
            + SoundModelContract.KEY_KEYPHRASE_ID + " INTEGER,"
            + SoundModelContract.KEY_TYPE + " INTEGER,"
            + SoundModelContract.KEY_DATA + " BLOB,"
            + SoundModelContract.KEY_RECOGNITION_MODES + " INTEGER,"
            + SoundModelContract.KEY_LOCALE + " TEXT,"
            + SoundModelContract.KEY_HINT_TEXT + " TEXT,"
            + SoundModelContract.KEY_USERS + " TEXT" + ")";

    public DatabaseHelper(Context context) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // creating required tables
        db.execSQL(CREATE_TABLE_SOUND_MODEL);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 4) {
            // For old versions just drop the tables and recreate new ones.
            db.execSQL("DROP TABLE IF EXISTS " + SoundModelContract.TABLE);
            onCreate(db);
        } else {
            // In the jump to version 5, we added support for the vendor UUID.
            if (oldVersion == 4) {
                Slog.d(TAG, "Adding vendor UUID column");
                db.execSQL("ALTER TABLE " + SoundModelContract.TABLE + " ADD COLUMN "
                        + SoundModelContract.KEY_VENDOR_UUID + " TEXT");
                oldVersion++;
            }
        }
    }

    /**
     * Updates the given keyphrase model, adds it, if it doesn't already exist.
     *
     * TODO: We only support one keyphrase currently.
     */
    public boolean updateKeyphraseSoundModel(KeyphraseSoundModel soundModel) {
        synchronized(this) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(SoundModelContract.KEY_MODEL_UUID, soundModel.uuid.toString());
            if (soundModel.vendorUuid != null) {
                values.put(SoundModelContract.KEY_VENDOR_UUID, soundModel.vendorUuid.toString());
            }
            values.put(SoundModelContract.KEY_TYPE, SoundTrigger.SoundModel.TYPE_KEYPHRASE);
            values.put(SoundModelContract.KEY_DATA, soundModel.data);

            if (soundModel.keyphrases != null && soundModel.keyphrases.length == 1) {
                values.put(SoundModelContract.KEY_KEYPHRASE_ID, soundModel.keyphrases[0].id);
                values.put(SoundModelContract.KEY_RECOGNITION_MODES,
                        soundModel.keyphrases[0].recognitionModes);
                values.put(SoundModelContract.KEY_USERS,
                        getCommaSeparatedString(soundModel.keyphrases[0].users));
                values.put(SoundModelContract.KEY_LOCALE, soundModel.keyphrases[0].locale);
                values.put(SoundModelContract.KEY_HINT_TEXT, soundModel.keyphrases[0].text);
                try {
                    return db.insertWithOnConflict(SoundModelContract.TABLE, null, values,
                            SQLiteDatabase.CONFLICT_REPLACE) != -1;
                } finally {
                    db.close();
                }
            }
            return false;
        }
    }

    /**
     * Deletes the sound model and associated keyphrases.
     */
    public boolean deleteKeyphraseSoundModel(int keyphraseId, int userHandle, String bcp47Locale) {
        // Sanitize the locale to guard against SQL injection.
        bcp47Locale = Locale.forLanguageTag(bcp47Locale).toLanguageTag();
        synchronized(this) {
            KeyphraseSoundModel soundModel = getKeyphraseSoundModel(keyphraseId, userHandle,
                    bcp47Locale);
            if (soundModel == null) {
                return false;
            }

            // Delete all sound models for the given keyphrase and specified user.
            SQLiteDatabase db = getWritableDatabase();
            String soundModelClause = SoundModelContract.KEY_MODEL_UUID
                    + "='" + soundModel.uuid.toString() + "'";
            try {
                return db.delete(SoundModelContract.TABLE, soundModelClause, null) != 0;
            } finally {
                db.close();
            }
        }
    }

    /**
     * Returns a matching {@link KeyphraseSoundModel} for the keyphrase ID.
     * Returns null if a match isn't found.
     *
     * TODO: We only support one keyphrase currently.
     */
    public KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId, int userHandle,
            String bcp47Locale) {
        // Sanitize the locale to guard against SQL injection.
        bcp47Locale = Locale.forLanguageTag(bcp47Locale).toLanguageTag();
        synchronized(this) {
            // Find the corresponding sound model ID for the keyphrase.
            String selectQuery = "SELECT  * FROM " + SoundModelContract.TABLE
                    + " WHERE " + SoundModelContract.KEY_KEYPHRASE_ID + "= '" + keyphraseId
                    + "' AND " + SoundModelContract.KEY_LOCALE + "='" + bcp47Locale + "'";
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(selectQuery, null);

            try {
                if (c.moveToFirst()) {
                    do {
                        int type = c.getInt(c.getColumnIndex(SoundModelContract.KEY_TYPE));
                        if (type != SoundTrigger.SoundModel.TYPE_KEYPHRASE) {
                            if (DBG) {
                                Slog.w(TAG, "Ignoring SoundModel since it's type is incorrect");
                            }
                            continue;
                        }

                        String modelUuid = c.getString(
                                c.getColumnIndex(SoundModelContract.KEY_MODEL_UUID));
                        if (modelUuid == null) {
                            Slog.w(TAG, "Ignoring SoundModel since it doesn't specify an ID");
                            continue;
                        }

                        String vendorUuidString = null;
                        int vendorUuidColumn = c.getColumnIndex(SoundModelContract.KEY_VENDOR_UUID);
                        if (vendorUuidColumn != -1) {
                            vendorUuidString = c.getString(vendorUuidColumn);
                        }
                        byte[] data = c.getBlob(c.getColumnIndex(SoundModelContract.KEY_DATA));
                        int recognitionModes = c.getInt(
                                c.getColumnIndex(SoundModelContract.KEY_RECOGNITION_MODES));
                        int[] users = getArrayForCommaSeparatedString(
                                c.getString(c.getColumnIndex(SoundModelContract.KEY_USERS)));
                        String modelLocale = c.getString(
                                c.getColumnIndex(SoundModelContract.KEY_LOCALE));
                        String text = c.getString(
                                c.getColumnIndex(SoundModelContract.KEY_HINT_TEXT));

                        // Only add keyphrases meant for the current user.
                        if (users == null) {
                            // No users present in the keyphrase.
                            Slog.w(TAG, "Ignoring SoundModel since it doesn't specify users");
                            continue;
                        }

                        boolean isAvailableForCurrentUser = false;
                        for (int user : users) {
                            if (userHandle == user) {
                                isAvailableForCurrentUser = true;
                                break;
                            }
                        }
                        if (!isAvailableForCurrentUser) {
                            if (DBG) {
                                Slog.w(TAG, "Ignoring SoundModel since user handles don't match");
                            }
                            continue;
                        } else {
                            if (DBG) Slog.d(TAG, "Found a SoundModel for user: " + userHandle);
                        }

                        Keyphrase[] keyphrases = new Keyphrase[1];
                        keyphrases[0] = new Keyphrase(
                                keyphraseId, recognitionModes, modelLocale, text, users);
                        UUID vendorUuid = null;
                        if (vendorUuidString != null) {
                            vendorUuid = UUID.fromString(vendorUuidString);
                        }
                        KeyphraseSoundModel model = new KeyphraseSoundModel(
                                UUID.fromString(modelUuid), vendorUuid, data, keyphrases);
                        if (DBG) {
                            Slog.d(TAG, "Found SoundModel for the given keyphrase/locale/user: "
                                    + model);
                        }
                        return model;
                    } while (c.moveToNext());
                }
                Slog.w(TAG, "No SoundModel available for the given keyphrase");
            } finally {
                c.close();
                db.close();
            }
            return null;
        }
    }

    private static String getCommaSeparatedString(int[] users) {
        if (users == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < users.length; i++) {
            if (i != 0) {
                sb.append(',');
            }
            sb.append(users[i]);
        }
        return sb.toString();
    }

    private static int[] getArrayForCommaSeparatedString(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String[] usersStr = text.split(",");
        int[] users = new int[usersStr.length];
        for (int i = 0; i < usersStr.length; i++) {
            users[i] = Integer.parseInt(usersStr[i]);
        }
        return users;
    }
}
