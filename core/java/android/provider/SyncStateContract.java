/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.provider;

import android.net.Uri;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.accounts.Account;
import android.database.Cursor;
import android.os.RemoteException;
import android.util.Pair;

/**
 * The ContentProvider contract for associating data with ana data array account.
 * This may be used by providers that want to store this data in a standard way.
 */
public class SyncStateContract {
    public interface Columns extends BaseColumns {
        /**
         * A reference to the name of the account to which this data belongs
         * <P>Type: STRING</P>
         */
        public static final String ACCOUNT_NAME = "account_name";

        /**
         * A reference to the type of the account to which this data belongs
         * <P>Type: STRING</P>
         */
        public static final String ACCOUNT_TYPE = "account_type";

        /**
         * The sync data associated with this account.
         * <P>Type: NONE</P>
         */
        public static final String DATA = "data";
    }

    public static class Constants implements Columns {
        public static final String CONTENT_DIRECTORY = "syncstate";
    }

    public static final class Helpers {
        private static final String[] DATA_PROJECTION = new String[]{Columns.DATA, Columns._ID};
        private static final String SELECT_BY_ACCOUNT =
                Columns.ACCOUNT_NAME + "=? AND " + Columns.ACCOUNT_TYPE + "=?";

        /**
         * Get the sync state that is associated with the account or null.
         * @param provider the {@link ContentProviderClient} that is to be used to communicate
         * with the {@link android.content.ContentProvider} that contains the sync state.
         * @param uri the uri of the sync state
         * @param account the {@link Account} whose sync state should be returned
         * @return the sync state or null if there is no sync state associated with the account
         * @throws RemoteException if there is a failure communicating with the remote
         * {@link android.content.ContentProvider}
         */
        public static byte[] get(ContentProviderClient provider, Uri uri,
                Account account) throws RemoteException {
            Cursor c = provider.query(uri, DATA_PROJECTION, SELECT_BY_ACCOUNT,
                    new String[]{account.name, account.type}, null);
            try {
                if (c.moveToNext()) {
                    return c.getBlob(c.getColumnIndexOrThrow(Columns.DATA));
                }
            } finally {
                c.close();
            }
            return null;
        }

        /**
         * Assigns the data array as the sync state for the given account.
         * @param provider the {@link ContentProviderClient} that is to be used to communicate
         * with the {@link android.content.ContentProvider} that contains the sync state.
         * @param uri the uri of the sync state
         * @param account the {@link Account} whose sync state should be set
         * @param data the byte[] that contains the sync state
         * @throws RemoteException if there is a failure communicating with the remote
         * {@link android.content.ContentProvider}
         */
        public static void set(ContentProviderClient provider, Uri uri,
                Account account, byte[] data) throws RemoteException {
            ContentValues values = new ContentValues();
            values.put(Columns.DATA, data);
            values.put(Columns.ACCOUNT_NAME, account.name);
            values.put(Columns.ACCOUNT_TYPE, account.type);
            provider.insert(uri, values);
        }

        public static Uri insert(ContentProviderClient provider, Uri uri,
                Account account, byte[] data) throws RemoteException {
            ContentValues values = new ContentValues();
            values.put(Columns.DATA, data);
            values.put(Columns.ACCOUNT_NAME, account.name);
            values.put(Columns.ACCOUNT_TYPE, account.type);
            return provider.insert(uri, values);
        }

        public static void update(ContentProviderClient provider, Uri uri, byte[] data)
                throws RemoteException {
            ContentValues values = new ContentValues();
            values.put(Columns.DATA, data);
            provider.update(uri, values, null, null);
        }

        public static Pair<Uri, byte[]> getWithUri(ContentProviderClient provider, Uri uri,
                Account account) throws RemoteException {
            Cursor c = provider.query(uri, DATA_PROJECTION, SELECT_BY_ACCOUNT,
                    new String[]{account.name, account.type}, null);
            try {
                if (c.moveToNext()) {
                    long rowId = c.getLong(1);
                    byte[] blob = c.getBlob(c.getColumnIndexOrThrow(Columns.DATA));
                    return Pair.create(ContentUris.withAppendedId(uri, rowId), blob);
                }
            } finally {
                c.close();
            }
            return null;
        }

        /**
         * Creates and returns a ContentProviderOperation that assigns the data array as the
         * sync state for the given account.
         * @param uri the uri of the sync state
         * @param account the {@link Account} whose sync state should be set
         * @param data the byte[] that contains the sync state
         * @return the new ContentProviderOperation that assigns the data array as the
         * account's sync state
         */
        public static ContentProviderOperation newSetOperation(Uri uri,
                Account account, byte[] data) {
            ContentValues values = new ContentValues();
            values.put(Columns.DATA, data);
            return ContentProviderOperation
                    .newInsert(uri)
                    .withValue(Columns.ACCOUNT_NAME, account.name)
                    .withValue(Columns.ACCOUNT_TYPE, account.type)
                    .withValues(values)
                    .build();
        }

        /**
         * Creates and returns a ContentProviderOperation that assigns the data array as the
         * sync state for the given account.
         * @param uri the uri of the specific sync state to set
         * @param data the byte[] that contains the sync state
         * @return the new ContentProviderOperation that assigns the data array as the
         * account's sync state
         */
        public static ContentProviderOperation newUpdateOperation(Uri uri, byte[] data) {
            ContentValues values = new ContentValues();
            values.put(Columns.DATA, data);
            return ContentProviderOperation
                    .newUpdate(uri)
                    .withValues(values)
                    .build();
        }
    }
}
