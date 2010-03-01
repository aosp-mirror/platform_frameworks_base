/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.provider;

/**
 * Columns for tables that are synced to a server.
 * @deprecated
 * @hide
 */
public interface SyncConstValue
{
    /**
     * The account that was used to sync the entry to the device.
     * <P>Type: TEXT</P>
     */
    public static final String _SYNC_ACCOUNT = "_sync_account";

    /**
     * The type of the account that was used to sync the entry to the device.
     * <P>Type: TEXT</P>
     */
    public static final String _SYNC_ACCOUNT_TYPE = "_sync_account_type";

    /**
     * The unique ID for a row assigned by the sync source. NULL if the row has never been synced.
     * <P>Type: TEXT</P>
     */
    public static final String _SYNC_ID = "_sync_id";

    /**
     * The last time, from the sync source's point of view, that this row has been synchronized.
     * <P>Type: INTEGER (long)</P>
     */
    public static final String _SYNC_TIME = "_sync_time";

    /**
     * The version of the row, as assigned by the server.
     * <P>Type: TEXT</P>
     */
    public static final String _SYNC_VERSION = "_sync_version";

    /**
     * Used in temporary provider while syncing, always NULL for rows in persistent providers.
     * <P>Type: INTEGER (long)</P>
     */
    public static final String _SYNC_LOCAL_ID = "_sync_local_id";

    /**
     * Used only in persistent providers, and only during merging.
     * <P>Type: INTEGER (long)</P>
     */
    public static final String _SYNC_MARK = "_sync_mark";

    /**
     * Used to indicate that local, unsynced, changes are present.
     * <P>Type: INTEGER (long)</P>
     */
    public static final String _SYNC_DIRTY = "_sync_dirty";

    /**
     * Used to indicate that this account is not synced
     */
    public static final String NON_SYNCABLE_ACCOUNT = "non_syncable";

    /**
     * Used to indicate that this account is not synced
     */
    public static final String NON_SYNCABLE_ACCOUNT_TYPE = "android.local";
}
