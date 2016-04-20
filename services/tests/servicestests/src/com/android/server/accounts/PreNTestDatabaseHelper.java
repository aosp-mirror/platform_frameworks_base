/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.accounts;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Helper class for emulating pre-N database
 */
class PreNTestDatabaseHelper extends SQLiteOpenHelper {

    public static final String TOKEN_STRING = "token-string-123";
    public static final String ACCOUNT_TYPE = "type1";
    public static final String ACCOUNT_NAME = "account@" + ACCOUNT_TYPE;
    public static final String ACCOUNT_PASSWORD = "Password";
    public static final String TOKEN_TYPE = "SID";

    public PreNTestDatabaseHelper(Context context, String name) {
        super(context, name, null, 4);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE accounts ( "
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT NOT NULL, "
                + "type TEXT NOT NULL, "
                + "password TEXT, "
                + "UNIQUE(name, type))");
        db.execSQL("INSERT INTO accounts (name, type, password) VALUES "
                + "('" + ACCOUNT_NAME + "', '" + ACCOUNT_TYPE + "', '" + ACCOUNT_PASSWORD + "')");

        db.execSQL("CREATE TABLE authtokens (  "
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,  "
                + "accounts_id INTEGER NOT NULL, "
                + "type TEXT NOT NULL,  "
                + "authtoken TEXT, "
                + "UNIQUE (accounts_id, type ))");
        db.execSQL("INSERT INTO authtokens (accounts_id, type, authtoken) VALUES "
                + "(1, '" + TOKEN_TYPE + "', '" + TOKEN_STRING + "')");

        db.execSQL("CREATE TABLE grants (  "
                + "accounts_id INTEGER NOT NULL, "
                + "auth_token_type STRING NOT NULL,  "
                + "uid INTEGER NOT NULL,  "
                + "UNIQUE (accounts_id,auth_token_type,uid))");

        db.execSQL("CREATE TABLE extras ( "
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "accounts_id INTEGER, "
                + "key TEXT NOT NULL, "
                + "value TEXT, "
                + "UNIQUE(accounts_id , key))");

        db.execSQL("CREATE TABLE meta ( "
                + "key TEXT PRIMARY KEY NOT NULL, "
                + "value TEXT)");

        db.execSQL(""
                + " CREATE TRIGGER accountsDelete DELETE ON accounts "
                + " BEGIN"
                + "   DELETE FROM authtokens"
                + "     WHERE accounts_id=OLD._id;"
                + "   DELETE FROM extras"
                + "     WHERE accounts_id=OLD._id;"
                + "   DELETE FROM grants"
                + "     WHERE accounts_id=OLD._id;"
                + " END");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new UnsupportedOperationException("Upgrade of test database is not supported");
    }

    public static void createV4Database(Context context, String name) {
        PreNTestDatabaseHelper helper = new PreNTestDatabaseHelper(context, name);
        helper.getWritableDatabase();
        helper.close();
    }

}
