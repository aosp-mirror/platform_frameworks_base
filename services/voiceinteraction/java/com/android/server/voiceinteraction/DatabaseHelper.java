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
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Slog;

import java.util.UUID;

/**
 * Helper to manage the database of the sound models that have been registered on the device.
 *
 * @hide
 */
public class DatabaseHelper extends SQLiteOpenHelper {
    static final String TAG = "SoundModelDBHelper";
    // TODO: Set to false.
    static final boolean DBG = true;

    private static final String NAME = "sound_model.db";
    private static final int VERSION = 4;

    public static interface SoundModelContract {
        public static final String TABLE = "sound_model";
        public static final String KEY_MODEL_UUID = "model_uuid";
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
            + SoundModelContract.KEY_KEYPHRASE_ID + " INTEGER,"
            + SoundModelContract.KEY_TYPE + " INTEGER,"
            + SoundModelContract.KEY_DATA + " BLOB,"
            + SoundModelContract.KEY_RECOGNITION_MODES + " INTEGER,"
            + SoundModelContract.KEY_LOCALE + " TEXT,"
            + SoundModelContract.KEY_HINT_TEXT + " TEXT,"
            + SoundModelContract.KEY_USERS + " TEXT" + ")";

    private final UserManager mUserManager;

    public DatabaseHelper(Context context) {
        super(context, NAME, null, VERSION);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // creating required tables
        db.execSQL(CREATE_TABLE_SOUND_MODEL);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // TODO: For now, drop older tables and recreate new ones.
        db.execSQL("DROP TABLE IF EXISTS " + SoundModelContract.TABLE);
        onCreate(db);
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
    public boolean deleteKeyphraseSoundModel(UUID modelUuid) {
        if (modelUuid == null) {
            Slog.w(TAG, "Model UUID must be specified for deletion");
            return false;
        }

        synchronized(this) {
            SQLiteDatabase db = getWritableDatabase();
            String soundModelClause = SoundModelContract.KEY_MODEL_UUID + "='"
                    + modelUuid.toString() + "'";

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
    public KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId) {
        synchronized(this) {
            // Find the corresponding sound model ID for the keyphrase.
            String selectQuery = "SELECT  * FROM " + SoundModelContract.TABLE
                    + " WHERE " + SoundModelContract.KEY_KEYPHRASE_ID + " = '" + keyphraseId + "'";
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(selectQuery, null);

            try {
                if (c.moveToFirst()) {
                    do {
                        int type = c.getInt(c.getColumnIndex(SoundModelContract.KEY_TYPE));
                        if (type != SoundTrigger.SoundModel.TYPE_KEYPHRASE) {
                            Slog.w(TAG, "Ignoring sound model since it's type is incorrect");
                            continue;
                        }

                        String modelUuid = c.getString(
                                c.getColumnIndex(SoundModelContract.KEY_MODEL_UUID));
                        if (modelUuid == null) {
                            Slog.w(TAG, "Ignoring sound model since it doesn't specify an ID");
                            continue;
                        }

                        byte[] data = c.getBlob(c.getColumnIndex(SoundModelContract.KEY_DATA));
                        int recognitionModes = c.getInt(
                                c.getColumnIndex(SoundModelContract.KEY_RECOGNITION_MODES));
                        int[] users = getArrayForCommaSeparatedString(
                                c.getString(c.getColumnIndex(SoundModelContract.KEY_USERS)));
                        String locale = c.getString(
                                c.getColumnIndex(SoundModelContract.KEY_LOCALE));
                        String text = c.getString(
                                c.getColumnIndex(SoundModelContract.KEY_HINT_TEXT));

                        // Only add keyphrases meant for the current user.
                        if (users == null) {
                            // No users present in the keyphrase.
                            Slog.w(TAG, "Ignoring keyphrase since it doesn't specify users");
                            continue;
                        }

                        boolean isAvailableForCurrentUser = false;
                        int currentUser = mUserManager.getUserHandle();
                        for (int user : users) {
                            if (currentUser == user) {
                                isAvailableForCurrentUser = true;
                                break;
                            }
                        }
                        if (!isAvailableForCurrentUser) {
                            Slog.w(TAG, "Ignoring keyphrase since it's not for the current user");
                            continue;
                        }

                        Keyphrase[] keyphrases = new Keyphrase[1];
                        keyphrases[0] = new Keyphrase(
                                keyphraseId, recognitionModes, locale, text, users);
                        return new KeyphraseSoundModel(UUID.fromString(modelUuid),
                                null /* FIXME use vendor UUID */, data, keyphrases);
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
        if (users == null || users.length == 0) {
            return "";
        }
        String csv = "";
        for (int user : users) {
            csv += String.valueOf(user);
            csv += ",";
        }
        return csv.substring(0, csv.length() - 1);
    }

    private static int[] getArrayForCommaSeparatedString(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String[] usersStr = text.split(",");
        int[] users = new int[usersStr.length];
        for (int i = 0; i < usersStr.length; i++) {
            users[i] = Integer.valueOf(usersStr[i]);
        }
        return users;
    }
}
