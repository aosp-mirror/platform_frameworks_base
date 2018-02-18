package com.android.server.testing.shadows;

import android.annotation.Nullable;
import android.content.ComponentName;

import com.android.server.backup.BackupPolicyEnforcer;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(BackupPolicyEnforcer.class)
public class ShadowBackupPolicyEnforcer {
    @Nullable private static ComponentName sMandatoryBackupTransport;

    public static void setMandatoryBackupTransport(
            @Nullable ComponentName backupTransportComponent) {
        sMandatoryBackupTransport = backupTransportComponent;
    }

    @Implementation
    @Nullable
    public ComponentName getMandatoryBackupTransport() {
        return sMandatoryBackupTransport;
    }
}
