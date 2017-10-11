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
    private final long mQuota;
    private long mSize;

    /**
     * Returns the quota in bytes for the application's current backup operation.  The
     * value can vary for each operation.
     *
     * @see BackupDataOutput#getQuota()
     */
    public long getQuota() {
        return mQuota;
    }

    /** @hide - used only in measure operation */
    public FullBackupDataOutput(long quota) {
        mData = null;
        mQuota = quota;
        mSize = 0;
    }

    /** @hide */
    public FullBackupDataOutput(ParcelFileDescriptor fd, long quota) {
        mData = new BackupDataOutput(fd.getFileDescriptor(), quota);
        mQuota = quota;
    }

    /** @hide - used only internally to the backup manager service's stream construction */
    public FullBackupDataOutput(ParcelFileDescriptor fd) {
        this(fd, -1);
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
