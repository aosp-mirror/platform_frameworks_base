package android.service.persistentdata;

import android.os.RemoteException;
import android.util.Slog;

/**
 * Interface for reading and writing data blocks to a persistent partition.
 *
 * Allows writing one block at a time. Namely, each time
 * {@link android.service.persistentdata.PersistentDataBlockManager}.write(byte[] data)
 * is called, it will overwite the data that was previously written on the block.
 *
 * Clients can query the size of the currently written block via
 * {@link android.service.persistentdata.PersistentDataBlockManager}.getTotalDataSize().
 *
 * Clients can any number of bytes from the currently written block up to its total size by invoking
 * {@link android.service.persistentdata.PersistentDataBlockManager}.read(byte[] data).
 *
 * @hide
 */
public class PersistentDataBlockManager {
    private static final String TAG = PersistentDataBlockManager.class.getSimpleName();
    private IPersistentDataBlockService sService;

    public PersistentDataBlockManager(IPersistentDataBlockService service) {
        sService = service;
    }

    /**
     * Writes {@code data} to the persistent partition. Previously written data
     * will be overwritten. This data will persist across factory resets.
     *
     * @param data the data to write
     */
    public void write(byte[] data) {
        try {
            sService.write(data);
        } catch (RemoteException e) {
            onError("writing data");
        }
    }

    /**
     * Tries to read {@code data.length} bytes into {@code data}. Call {@code getDataBlockSize()}
     * to determine the total size of the block currently residing in the persistent partition.
     *
     * @param data the buffer in which to read the data
     * @return the actual number of bytes read
     */
    public int read(byte[] data) {
        try {
            return sService.read(data);
        } catch (RemoteException e) {
            onError("reading data");
            return -1;
        }
    }

    /**
     * Retrieves the size of the block currently written to the persistent partition.
     */
    public int getDataBlockSize() {
        try {
            return sService.getDataBlockSize();
        } catch (RemoteException e) {
            onError("getting data block size");
            return 0;
        }
    }

    /**
     * Writes a byte enabling or disabling the ability to "OEM unlock" the device.
     */
    public void setOemUnlockEnabled(boolean enabled) {
        try {
            sService.setOemUnlockEnabled(enabled);
        } catch (RemoteException e) {
            onError("setting OEM unlock enabled to " + enabled);
        }
    }

    /**
     * Returns whether or not "OEM unlock" is enabled or disabled on this device.
     */
    public boolean getOemUnlockEnabled() {
        try {
            return sService.getOemUnlockEnabled();
        } catch (RemoteException e) {
            onError("getting OEM unlock enabled bit");
            return false;
        }
    }

    private void onError(String msg) {
        Slog.v(TAG, "Remote exception while " + msg);
    }
}
