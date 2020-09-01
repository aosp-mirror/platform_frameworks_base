package com.android.server.backup;


import android.app.backup.BlobBackupHelper;
import android.app.usage.UsageStatsManagerInternal;
import android.content.Context;
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

    public UsageStatsBackupHelper(Context context) {
        super(BLOB_VERSION, KEY_USAGE_STATS);
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        if (KEY_USAGE_STATS.equals(key)) {
            UsageStatsManagerInternal localUsageStatsManager =
                    LocalServices.getService(UsageStatsManagerInternal.class);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out  = new DataOutputStream(baos);
            try {
                out.writeInt(UserHandle.USER_SYSTEM);
                out.write(localUsageStatsManager.getBackupPayload(UserHandle.USER_SYSTEM, key));
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
                int user = in.readInt();
                byte[] restoreData = new byte[payload.length - 4];
                in.read(restoreData, 0, restoreData.length);
                localUsageStatsManager.applyRestoredPayload(user, key, restoreData);
            } catch (IOException ioe) {
                if (DEBUG) Log.e(TAG, "Failed to restore Usage Stats", ioe);
            }
        }
    }
}
