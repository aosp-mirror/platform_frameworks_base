package com.android.server.backup;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

import com.android.internal.annotations.VisibleForTesting;

/**
 * A helper class to decouple this service from {@link DevicePolicyManager} in order to improve
 * testability.
 */
@VisibleForTesting
public class BackupPolicyEnforcer {
    private DevicePolicyManager mDevicePolicyManager;

    public BackupPolicyEnforcer(Context context) {
        mDevicePolicyManager =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    public ComponentName getMandatoryBackupTransport() {
        return mDevicePolicyManager.getMandatoryBackupTransport();
    }
}
