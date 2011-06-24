package android.app.backup;

import android.os.ParcelFileDescriptor;

/**
 * Provides the interface through which a {@link BackupAgent} writes entire files
 * to a full backup data set, via its {@link BackupAgent#onFullBackup(FullBackupDataOutput)}
 * method.
 */
public class FullBackupDataOutput {
    // Currently a name-scoping shim around BackupDataOutput
    private BackupDataOutput mData;

    /** @hide */
    public FullBackupDataOutput(ParcelFileDescriptor fd) {
        mData = new BackupDataOutput(fd.getFileDescriptor());
    }

    /** @hide */
    public BackupDataOutput getData() { return mData; }
}
