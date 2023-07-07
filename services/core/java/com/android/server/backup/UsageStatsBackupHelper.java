package com.android.server.backup;


import android.annotation.UserIdInt;
import android.app.backup.BlobBackupHelper;
import android.app.usage.UsageStatsManagerInternal;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.LocalServices;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class UsageStatsBackupHelper extends BlobBackupHelper {
    static final String TAG = "UsgStatsBackupHelper";   // must be < 23 chars
    static final boolean DEBUG = false;

    // Current version of the blob schema
    static final int BLOB_VERSION = 1;

    // Key under which the payload blob is stored
    // same as UsageStatsDatabase.KEY_USAGE_STATS
    static final String KEY_USAGE_STATS = "usage_stats";

    private final @UserIdInt int mUserId;

    /**
     * Marshall/unmarshall the usagestats data for the given user
     *
     * @param userId The userId to backup/restore
     */
    public UsageStatsBackupHelper(@UserIdInt int userId) {
        super(BLOB_VERSION, KEY_USAGE_STATS);
        mUserId = userId;
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        if (KEY_USAGE_STATS.equals(key)) {
            UsageStatsManagerInternal localUsageStatsManager =
                    LocalServices.getService(UsageStatsManagerInternal.class);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out  = new DataOutputStream(baos);
            try {
                // Note: Write 0 here deliberately so that a backup from a secondary user
                // can still be restored to an older OS where the restore was always to user 0
                // Writing the actual userId here would result in restores not working on pre-U.
                out.writeInt(UserHandle.USER_SYSTEM);
                out.write(localUsageStatsManager.getBackupPayload(mUserId, key));
            } catch (IOException ioe) {
                if (DEBUG) Log.e(TAG, "Failed to backup Usage Stats", ioe);
                baos.reset();
            }
            return baos.toByteArray();
        }
        return null;
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload)  {
        if (KEY_USAGE_STATS.equals(key)) {
            UsageStatsManagerInternal localUsageStatsManager =
                    LocalServices.getService(UsageStatsManagerInternal.class);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            try {
                in.readInt(); // Legacy userId parameter, read and ignore
                byte[] restoreData = new byte[payload.length - 4];
                in.read(restoreData, 0, restoreData.length);
                localUsageStatsManager.applyRestoredPayload(mUserId, key, restoreData);
            } catch (IOException ioe) {
                if (DEBUG) Log.e(TAG, "Failed to restore Usage Stats", ioe);
            }
        }
    }
}
