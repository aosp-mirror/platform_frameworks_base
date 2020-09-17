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

package com.android.server.soundtrigger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.UUID;

/**
 * Helper to manage the database of the sound models that have been registered on the device.
 *
 * @hide
 */
public class SoundTriggerDbHelper extends SQLiteOpenHelper {
    static final String TAG = "SoundTriggerDbHelper";
    static final boolean DBG = false;

    private static final String NAME = "st_sound_model.db";
    private static final int VERSION = 2;

    // Sound trigger-based sound models.
    public static interface GenericSoundModelContract {
        public static final String TABLE = "st_sound_model";
        public static final String KEY_MODEL_UUID = "model_uuid";
        public static final String KEY_VENDOR_UUID = "vendor_uuid";
        public static final String KEY_DATA = "data";
        public static final String KEY_MODEL_VERSION = "model_version";
    }

    // Table Create Statement for the sound trigger table
    private static final String CREATE_TABLE_ST_SOUND_MODEL = "CREATE TABLE "
            + GenericSoundModelContract.TABLE + "("
            + GenericSoundModelContract.KEY_MODEL_UUID + " TEXT PRIMARY KEY,"
            + GenericSoundModelContract.KEY_VENDOR_UUID + " TEXT,"
            + GenericSoundModelContract.KEY_DATA + " BLOB,"
            + GenericSoundModelContract.KEY_MODEL_VERSION + " INTEGER" + " )";


    public SoundTriggerDbHelper(Context context) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // creating required tables
        db.execSQL(CREATE_TABLE_ST_SOUND_MODEL);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1) {
            // In version 2, a model version number was added.
            Slog.d(TAG, "Adding model version column");
            db.execSQL("ALTER TABLE " + GenericSoundModelContract.TABLE + " ADD COLUMN "
                    + GenericSoundModelContract.KEY_MODEL_VERSION + " INTEGER DEFAULT -1");
            oldVersion++;
        }
    }

    /**
     * Updates the given sound trigger model, adds it, if it doesn't already exist.
     *
     */
    public boolean updateGenericSoundModel(GenericSoundModel soundModel) {
        synchronized(this) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(GenericSoundModelContract.KEY_MODEL_UUID, soundModel.getUuid().toString());
            values.put(GenericSoundModelContract.KEY_VENDOR_UUID,
                    soundModel.getVendorUuid().toString());
            values.put(GenericSoundModelContract.KEY_DATA, soundModel.getData());
            values.put(GenericSoundModelContract.KEY_MODEL_VERSION, soundModel.getVersion());

            try {
                return db.insertWithOnConflict(GenericSoundModelContract.TABLE, null, values,
                        SQLiteDatabase.CONFLICT_REPLACE) != -1;
            } finally {
                db.close();
            }

        }
    }

    public GenericSoundModel getGenericSoundModel(UUID model_uuid) {
        synchronized(this) {

            // Find the corresponding sound model ID for the keyphrase.
            String selectQuery = "SELECT  * FROM " + GenericSoundModelContract.TABLE
                    + " WHERE " + GenericSoundModelContract.KEY_MODEL_UUID + "= '" +
                    model_uuid + "'";
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(selectQuery, null);
            try {
                if (c.moveToFirst()) {
                    do {
                        byte[] data = c.getBlob(c.getColumnIndex(
                                GenericSoundModelContract.KEY_DATA));
                        String vendor_uuid = c.getString(
                                c.getColumnIndex(GenericSoundModelContract.KEY_VENDOR_UUID));
                        int version = c.getInt(
                                c.getColumnIndex(GenericSoundModelContract.KEY_MODEL_VERSION));
                        return new GenericSoundModel(model_uuid, UUID.fromString(vendor_uuid),
                                data, version);
                    } while (c.moveToNext());
                }
            } finally {
                c.close();
                db.close();
            }
        }
        return null;
    }

    public boolean deleteGenericSoundModel(UUID model_uuid) {
        synchronized(this) {
            GenericSoundModel soundModel = getGenericSoundModel(model_uuid);
            if (soundModel == null) {
                return false;
            }
            // Delete all sound models for the given keyphrase and specified user.
            SQLiteDatabase db = getWritableDatabase();
            String soundModelClause = GenericSoundModelContract.KEY_MODEL_UUID
                    + "='" + soundModel.getUuid().toString() + "'";
            try {
                return db.delete(GenericSoundModelContract.TABLE, soundModelClause, null) != 0;
            } finally {
                db.close();
            }
        }
    }

    public void dump(PrintWriter pw) {
        synchronized(this) {
            String selectQuery = "SELECT  * FROM " + GenericSoundModelContract.TABLE;
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(selectQuery, null);
            try {
                pw.println("  Enrolled GenericSoundModels:");
                if (c.moveToFirst()) {
                    String[] columnNames = c.getColumnNames();
                    do {
                        for (String name : columnNames) {
                            int colNameIndex = c.getColumnIndex(name);
                            int type = c.getType(colNameIndex);
                            switch (type) {
                                case Cursor.FIELD_TYPE_STRING:
                                    pw.printf("    %s: %s\n", name,
                                            c.getString(colNameIndex));
                                    break;
                                case Cursor.FIELD_TYPE_BLOB:
                                    pw.printf("    %s: data blob\n", name);
                                    break;
                                case Cursor.FIELD_TYPE_INTEGER:
                                    pw.printf("    %s: %d\n", name,
                                            c.getInt(colNameIndex));
                                    break;
                                case Cursor.FIELD_TYPE_FLOAT:
                                    pw.printf("    %s: %f\n", name,
                                            c.getFloat(colNameIndex));
                                    break;
                                case Cursor.FIELD_TYPE_NULL:
                                    pw.printf("    %s: null\n", name);
                                    break;
                            }
                        }
                        pw.println();
                    } while (c.moveToNext());
                }
            } finally {
                c.close();
                db.close();
            }
        }
    }
}
