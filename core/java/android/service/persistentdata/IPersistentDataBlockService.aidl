package android.service.persistentdata;

import android.os.ParcelFileDescriptor;

/**
 * Internal interface through which to communicate to the
 * PersistentDataBlockService. The persistent data block allows writing
 * raw data and setting the OEM unlock enabled/disabled bit contained
 * in the partition.
 *
 * @hide
 */
interface IPersistentDataBlockService {
    int write(in byte[] data);
    int read(out byte[] data);
    int getDataBlockSize();

    void setOemUnlockEnabled(boolean enabled);
    boolean getOemUnlockEnabled();
}
