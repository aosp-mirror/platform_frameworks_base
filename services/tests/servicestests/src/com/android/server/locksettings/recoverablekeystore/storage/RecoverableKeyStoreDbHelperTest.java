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

package com.android.server.locksettings.recoverablekeystore.storage;

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.KeysEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.RecoveryServiceMetadataEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.RootOfTrustEntry;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDbContract.UserMetadataEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static java.nio.charset.StandardCharsets.UTF_8;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverableKeyStoreDbHelperTest {

    private static final long TEST_USER_ID = 10L;
    private static final long TEST_UID = 60001L;
    private static final String TEST_ALIAS = "test-alias";
    private static final byte[] TEST_NONCE = "test-nonce".getBytes(UTF_8);
    private static final byte[] TEST_WRAPPED_KEY = "test-wrapped-key".getBytes(UTF_8);
    private static final long TEST_GENERATION_ID = 13L;
    private static final long TEST_LAST_SYNCED_AT = 1517990732000L;
    private static final int TEST_RECOVERY_STATUS = 3;
    private static final int TEST_PLATFORM_KEY_GENERATION_ID = 11;
    private static final int TEST_SNAPSHOT_VERSION = 31;
    private static final int TEST_SHOULD_CREATE_SNAPSHOT = 1;
    private static final byte[] TEST_PUBLIC_KEY = "test-public-key".getBytes(UTF_8);
    private static final String TEST_SECRET_TYPES = "test-secret-types";
    private static final long TEST_COUNTER_ID = -3981205205038476415L;
    private static final byte[] TEST_SERVER_PARAMS = "test-server-params".getBytes(UTF_8);
    private static final String TEST_ROOT_ALIAS = "root_cert_alias";
    private static final byte[] TEST_CERT_PATH = "test-cert-path".getBytes(UTF_8);
    private static final long TEST_CERT_SERIAL = 1000L;

    private static final String SQL_CREATE_V2_TABLE_KEYS =
            "CREATE TABLE " + KeysEntry.TABLE_NAME + "( "
                    + KeysEntry._ID + " INTEGER PRIMARY KEY,"
                    + KeysEntry.COLUMN_NAME_USER_ID + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_UID + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_ALIAS + " TEXT,"
                    + KeysEntry.COLUMN_NAME_NONCE + " BLOB,"
                    + KeysEntry.COLUMN_NAME_WRAPPED_KEY + " BLOB,"
                    + KeysEntry.COLUMN_NAME_GENERATION_ID + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_LAST_SYNCED_AT + " INTEGER,"
                    + KeysEntry.COLUMN_NAME_RECOVERY_STATUS + " INTEGER,"
                    + "UNIQUE(" + KeysEntry.COLUMN_NAME_UID + ","
                    + KeysEntry.COLUMN_NAME_ALIAS + "))";

    private static final String SQL_CREATE_V2_TABLE_USER_METADATA =
            "CREATE TABLE " + UserMetadataEntry.TABLE_NAME + "( "
                    + UserMetadataEntry._ID + " INTEGER PRIMARY KEY,"
                    + UserMetadataEntry.COLUMN_NAME_USER_ID + " INTEGER UNIQUE,"
                    + UserMetadataEntry.COLUMN_NAME_PLATFORM_KEY_GENERATION_ID + " INTEGER)";

    private static final String SQL_CREATE_V2_TABLE_RECOVERY_SERVICE_METADATA =
            "CREATE TABLE " + RecoveryServiceMetadataEntry.TABLE_NAME + " ("
                    + RecoveryServiceMetadataEntry._ID + " INTEGER PRIMARY KEY,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_SNAPSHOT_VERSION + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_SHOULD_CREATE_SNAPSHOT + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_PUBLIC_KEY + " BLOB,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_SECRET_TYPES + " TEXT,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_COUNTER_ID + " INTEGER,"
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_SERVER_PARAMS + " BLOB,"
                    + "UNIQUE("
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID  + ","
                    + RecoveryServiceMetadataEntry.COLUMN_NAME_UID + "))";

    private SQLiteDatabase mDatabase;
    private RecoverableKeyStoreDbHelper mDatabaseHelper;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        mDatabaseHelper = new RecoverableKeyStoreDbHelper(context);
        mDatabase = SQLiteDatabase.create(null);
    }

    @After
    public void tearDown() throws Exception {
        mDatabase.close();
    }

    private void createV2Tables() throws Exception {
        mDatabase.execSQL(SQL_CREATE_V2_TABLE_KEYS);
        mDatabase.execSQL(SQL_CREATE_V2_TABLE_USER_METADATA);
        mDatabase.execSQL(SQL_CREATE_V2_TABLE_RECOVERY_SERVICE_METADATA);
    }

    @Test
    public void onCreate() throws Exception {
        mDatabaseHelper.onCreate(mDatabase);
        checkAllColumns();
    }

    @Test
    public void onUpgrade_beforeV2() throws Exception {
        mDatabaseHelper.onUpgrade(mDatabase, /*oldVersion=*/ 1,
                RecoverableKeyStoreDbHelper.DATABASE_VERSION);
        checkAllColumns();
    }

    @Test
    public void onUpgrade_fromV2() throws Exception {
        createV2Tables();
        mDatabaseHelper.onUpgrade(mDatabase, /*oldVersion=*/ 2,
                RecoverableKeyStoreDbHelper.DATABASE_VERSION);
        checkAllColumns();
    }

    @Test
    public void onUpgrade_v2_to_v3_to_v4() throws Exception {
        createV2Tables();

        assertThat(isRootOfTrustTableAvailable()).isFalse(); // V2 doesn't have the table;

        mDatabaseHelper.onUpgrade(mDatabase, /*oldVersion=*/ 2, /*newVersion=*/ 3);

        assertThat(isRootOfTrustTableAvailable()).isFalse(); // V3 doesn't have the table;

        mDatabaseHelper.onUpgrade(mDatabase, /*oldVersion=*/ 3,
                RecoverableKeyStoreDbHelper.DATABASE_VERSION);
        checkAllColumns();
    }

    private boolean isRootOfTrustTableAvailable() {
        ContentValues values = new ContentValues();
        values.put(RootOfTrustEntry.COLUMN_NAME_USER_ID, TEST_USER_ID);
        values.put(RootOfTrustEntry.COLUMN_NAME_UID, TEST_UID);
        values.put(RootOfTrustEntry.COLUMN_NAME_ROOT_ALIAS, TEST_ROOT_ALIAS);
        values.put(RootOfTrustEntry.COLUMN_NAME_CERT_PATH, TEST_CERT_PATH);
        values.put(RootOfTrustEntry.COLUMN_NAME_CERT_SERIAL, TEST_CERT_SERIAL);
        return mDatabase.insert(RootOfTrustEntry.TABLE_NAME, /*nullColumnHack=*/ null, values)
                > -1;
    }

    private void checkAllColumns() throws Exception {
        // Check the table containing encrypted application keys
        ContentValues values = new ContentValues();
        values.put(KeysEntry.COLUMN_NAME_USER_ID, TEST_USER_ID);
        values.put(KeysEntry.COLUMN_NAME_UID, TEST_UID);
        values.put(KeysEntry.COLUMN_NAME_ALIAS, TEST_ALIAS);
        values.put(KeysEntry.COLUMN_NAME_NONCE, TEST_NONCE);
        values.put(KeysEntry.COLUMN_NAME_WRAPPED_KEY, TEST_WRAPPED_KEY);
        values.put(KeysEntry.COLUMN_NAME_GENERATION_ID, TEST_GENERATION_ID);
        values.put(KeysEntry.COLUMN_NAME_LAST_SYNCED_AT, TEST_LAST_SYNCED_AT);
        values.put(KeysEntry.COLUMN_NAME_RECOVERY_STATUS, TEST_RECOVERY_STATUS);
        assertThat(mDatabase.insert(KeysEntry.TABLE_NAME, /*nullColumnHack=*/ null, values))
                .isGreaterThan(-1L);

        // Check the table about user metadata
        values = new ContentValues();
        values.put(UserMetadataEntry.COLUMN_NAME_USER_ID, TEST_USER_ID);
        values.put(UserMetadataEntry.COLUMN_NAME_PLATFORM_KEY_GENERATION_ID,
                TEST_PLATFORM_KEY_GENERATION_ID);
        assertThat(mDatabase.insert(UserMetadataEntry.TABLE_NAME, /*nullColumnHack=*/ null, values))
                .isGreaterThan(-1L);

        // Check the table about recovery service metadata
        values = new ContentValues();
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_USER_ID, TEST_USER_ID);
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_UID, TEST_UID);
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_SNAPSHOT_VERSION,
                TEST_SNAPSHOT_VERSION);
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_SHOULD_CREATE_SNAPSHOT,
                TEST_SHOULD_CREATE_SNAPSHOT);
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_ACTIVE_ROOT_OF_TRUST, TEST_ROOT_ALIAS);
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_PUBLIC_KEY, TEST_PUBLIC_KEY);
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_SECRET_TYPES, TEST_SECRET_TYPES);
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_COUNTER_ID, TEST_COUNTER_ID);
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_SERVER_PARAMS, TEST_SERVER_PARAMS);
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_CERT_PATH, TEST_CERT_PATH);
        values.put(RecoveryServiceMetadataEntry.COLUMN_NAME_CERT_SERIAL, TEST_CERT_SERIAL);
        assertThat(
                mDatabase.insert(RecoveryServiceMetadataEntry.TABLE_NAME, /*nullColumnHack=*/ null,
                        values))
                .isGreaterThan(-1L);

        // Check the table about recovery service and root of trust data introduced in V4
        assertThat(isRootOfTrustTableAvailable()).isTrue();
    }
}
