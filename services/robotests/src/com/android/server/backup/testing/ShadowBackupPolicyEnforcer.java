package com.android.server.backup.testing;

import android.content.ComponentName;

import com.android.server.backup.BackupPolicyEnforcer;
import com.android.server.backup.RefactoredBackupManagerService;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(BackupPolicyEnforcer.class)
public class ShadowBackupPolicyEnforcer {

    private static ComponentName sMandatoryBackupTransport;

    public static void setMandatoryBackupTransport(ComponentName backupTransportComponent) {
        sMandatoryBackupTransport = backupTransportComponent;
    }

    @Implementation
    public ComponentName getMandatoryBackupTransport() {
        return sMandatoryBackupTransport;
    }
}
