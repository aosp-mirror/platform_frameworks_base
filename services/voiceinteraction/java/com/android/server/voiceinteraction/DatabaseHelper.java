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
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.database.sqlite.SQLiteOpenHelper;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.Keyphrase;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @hide
 */
public class DatabaseHelper extends SQLiteOpenHelper {
    static final String TAG = "SoundModelDBHelper";

    private static final String NAME = "sound_model.db";
    private static final int VERSION = 1;

    public static interface KeyphraseContract {
        public static final String TABLE = "keyphrase";
        public static final String KEY_ID = "_id";
        public static final String KEY_RECOGNITION_MODES = "modes";
        public static final String KEY_LOCALE = "locale";
        public static final String KEY_HINT_TEXT = "hint_text";
        public static final String KEY_NUM_USERS = "num_users";
        public static final String KEY_SOUND_MODEL_ID = "sound_model_id";
    }

    public static interface SoundModelContract {
        public static final String TABLE = "sound_model";
        public static final String KEY_ID = "_id";
        public static final String KEY_TYPE = "type";
        public static final String KEY_DATA = "data";
    }

    // Table Create Statements
    private static final String CREATE_TABLE_KEYPRHASES = "CREATE TABLE "
            + KeyphraseContract.TABLE + "("
            + KeyphraseContract.KEY_ID + " INTEGER PRIMARY KEY,"
            + KeyphraseContract.KEY_RECOGNITION_MODES + " INTEGER,"
            + KeyphraseContract.KEY_NUM_USERS + " INTEGER,"
            + KeyphraseContract.KEY_SOUND_MODEL_ID + " TEXT,"
            + KeyphraseContract.KEY_LOCALE + " TEXT,"
            + KeyphraseContract.KEY_HINT_TEXT + " TEXT" + ")";

    private static final String CREATE_TABLE_SOUND_MODEL = "CREATE TABLE "
            + SoundModelContract.TABLE + "("
            + SoundModelContract.KEY_ID + " TEXT PRIMARY KEY,"
            + SoundModelContract.KEY_TYPE + " INTEGER,"
            + SoundModelContract.KEY_DATA + " BLOB" + ")";

    public DatabaseHelper(Context context, CursorFactory factory) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // creating required tables
        db.execSQL(CREATE_TABLE_KEYPRHASES);
        db.execSQL(CREATE_TABLE_SOUND_MODEL);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // TODO(sansid): For now, drop older tables and recreate new ones.
        db.execSQL("DROP TABLE IF EXISTS " + KeyphraseContract.TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + SoundModelContract.TABLE);
        onCreate(db);
    }

    /**
     * TODO(sansid): Change to addOrUpdate to handle changes here.
     */
    public void addKeyphraseSoundModel(KeyphraseSoundModel soundModel) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        // Generate a random ID for the model.
        values.put(SoundModelContract.KEY_ID, soundModel.uuid.toString());
        values.put(SoundModelContract.KEY_DATA, soundModel.data);
        values.put(SoundModelContract.KEY_TYPE, SoundTrigger.SoundModel.TYPE_KEYPHRASE);

        if (db.insert(SoundModelContract.TABLE, null, values) != -1) {
            for (Keyphrase keyphrase : soundModel.keyphrases) {
                addKeyphrase(soundModel.uuid, keyphrase);
            }
        } else {
            Slog.w(TAG, "Failed to persist sound model to database");
        }
    }

    /**
     * TODO(sansid): Change to addOrUpdate to handle changes here.
     */
    private void addKeyphrase(UUID modelId, SoundTrigger.Keyphrase keyphrase) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KeyphraseContract.KEY_ID, keyphrase.id);
        values.put(KeyphraseContract.KEY_RECOGNITION_MODES, keyphrase.recognitionModes);
        values.put(KeyphraseContract.KEY_SOUND_MODEL_ID, keyphrase.id);
        values.put(KeyphraseContract.KEY_HINT_TEXT, keyphrase.text);
        values.put(KeyphraseContract.KEY_LOCALE, keyphrase.locale);
        if (db.insert(KeyphraseContract.TABLE, null, values) == -1) {
            Slog.w(TAG, "Failed to persist keyphrase to database");
        }
    }

    /**
     * Lists all the keyphrase sound models currently registered with the system.
     */
    public List<KeyphraseSoundModel> getKephraseSoundModels() {
        List<KeyphraseSoundModel> models = new ArrayList<>();
        String selectQuery = "SELECT  * FROM " + SoundModelContract.TABLE;
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.rawQuery(selectQuery, null);

        // looping through all rows and adding to list
        if (c.moveToFirst()) {
            do {
                int type = c.getInt(c.getColumnIndex(SoundModelContract.KEY_TYPE));
                if (type != SoundTrigger.SoundModel.TYPE_KEYPHRASE) {
                    // Ignore non-keyphrase sound models.
                    continue;
                }
                String id = c.getString(c.getColumnIndex(SoundModelContract.KEY_ID));
                byte[] data = c.getBlob(c.getColumnIndex(SoundModelContract.KEY_DATA));
                // Get all the keyphrases for this this sound model.
                models.add(new KeyphraseSoundModel(
                        UUID.fromString(id), data, getKeyphrasesForSoundModel(id)));
            } while (c.moveToNext());
        }
        return models;
    }

    private Keyphrase[] getKeyphrasesForSoundModel(String modelId) {
        List<Keyphrase> keyphrases = new ArrayList<>();
        String selectQuery = "SELECT  * FROM " + KeyphraseContract.TABLE
                + " WHERE " + KeyphraseContract.KEY_SOUND_MODEL_ID + " = '" + modelId + "'";
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.rawQuery(selectQuery, null);

        // looping through all rows and adding to list
        if (c.moveToFirst()) {
            do {
                int id = c.getInt(c.getColumnIndex(KeyphraseContract.KEY_ID));
                int modes = c.getInt(c.getColumnIndex(KeyphraseContract.KEY_RECOGNITION_MODES));
                int numUsers = c.getInt(c.getColumnIndex(KeyphraseContract.KEY_NUM_USERS));
                String locale = c.getString(c.getColumnIndex(KeyphraseContract.KEY_LOCALE));
                String hintText = c.getString(c.getColumnIndex(KeyphraseContract.KEY_HINT_TEXT));

                keyphrases.add(new Keyphrase(id, modes, locale, hintText, numUsers));
            } while (c.moveToNext());
        }
        Keyphrase[] keyphraseArr = new Keyphrase[keyphrases.size()];
        keyphrases.toArray(keyphraseArr);
        return keyphraseArr;
    }
}
