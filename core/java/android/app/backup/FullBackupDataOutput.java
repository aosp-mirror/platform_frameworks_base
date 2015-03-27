package android.app.backup;

import android.os.ParcelFileDescriptor;

/**
 * Provides the interface through which a {@link BackupAgent} writes entire files
 * to a full backup data set, via its {@link BackupAgent#onFullBackup(FullBackupDataOutput)}
 * method.
 */
public class FullBackupDataOutput {
    // Currently a name-scoping shim around BackupDataOutput
    private final BackupDataOutput mData;
    private long mSize;

    /** @hide - used only in measure operation */
    public FullBackupDataOutput() {
        mData = null;
        mSize = 0;
    }

    /** @hide */
    public FullBackupDataOutput(ParcelFileDescriptor fd) {
        mData = new BackupDataOutput(fd.getFileDescriptor());
    }

    /** @hide */
    public BackupDataOutput getData() { return mData; }

    /** @hide - used for measurement pass */
    public void addSize(long size) {
        if (size > 0) {
            mSize += size;
        }
    }

    /** @hide - used for measurement pass */
    public long getSize() { return mSize; }
}
