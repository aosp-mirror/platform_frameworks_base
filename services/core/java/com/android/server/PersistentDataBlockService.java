package com.android.server;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.service.persistentdata.IPersistentDataBlockService;
import android.util.Log;
import com.android.internal.R;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Service for reading and writing blocks to a persistent partition.
 * This data will live across factory resets.
 *
 * Allows writing one block at a time. Namely, each time
 * {@link android.service.persistentdata.IPersistentDataBlockService}.write(byte[] data)
 * is called, it will overwite the data that was previously written on the block.
 *
 * Clients can query the size of the currently written block via
 * {@link android.service.persistentdata.IPersistentDataBlockService}.getTotalDataSize().
 *
 * Clients can any number of bytes from the currently written block up to its total size by invoking
 * {@link android.service.persistentdata.IPersistentDataBlockService}.read(byte[] data)
 */
public class PersistentDataBlockService extends SystemService {
    private static final String TAG = PersistentDataBlockService.class.getSimpleName();

    private static final String PERSISTENT_DATA_BLOCK_PROP = "ro.frp.pst";
    private static final int HEADER_SIZE = 8;
    private static final int BLOCK_ID = 0x1990;

    private final Context mContext;
    private final String mDataBlockFile;
    private long mBlockDeviceSize;

    private final int mAllowedUid;

    public PersistentDataBlockService(Context context) {
        super(context);
        mContext = context;
        mDataBlockFile = SystemProperties.get(PERSISTENT_DATA_BLOCK_PROP);
        mBlockDeviceSize = 0; // Load lazily
        String allowedPackage = context.getResources()
                .getString(R.string.config_persistentDataPackageName);
        PackageManager pm = mContext.getPackageManager();
        int allowedUid = -1;
        try {
            allowedUid = pm.getPackageUid(allowedPackage,
                    Binder.getCallingUserHandle().getIdentifier());
        } catch (PackageManager.NameNotFoundException e) {
            // not expected
            Log.e(TAG, "not able to find package " + allowedPackage, e);
        }

        mAllowedUid = allowedUid;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.PERSISTENT_DATA_BLOCK_SERVICE, mService);
    }

    private void enforceOemUnlockPermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.OEM_UNLOCK_STATE,
                "Can't access OEM unlock state");
    }

    private void enforceUid(int callingUid) {
        if (callingUid != mAllowedUid) {
            throw new SecurityException("uid " + callingUid + " not allowed to access PST");
        }
    }

    private int getTotalDataSize(DataInputStream inputStream) throws IOException {
        int totalDataSize;
        int blockId = inputStream.readInt();
        if (blockId == BLOCK_ID) {
            totalDataSize = inputStream.readInt();
        } else {
            totalDataSize = 0;
        }
        return totalDataSize;
    }

    private long maybeReadBlockDeviceSize() {
        synchronized (this) {
            if (mBlockDeviceSize == 0) {
                mBlockDeviceSize = getBlockDeviceSize(mDataBlockFile);
            }
        }

        return mBlockDeviceSize;
    }

    private native long getBlockDeviceSize(String path);

    private final IBinder mService = new IPersistentDataBlockService.Stub() {
        @Override
        public int write(byte[] data) throws RemoteException {
            enforceUid(Binder.getCallingUid());

            // Need to ensure we don't write over the last byte
            if (data.length > maybeReadBlockDeviceSize() - HEADER_SIZE - 1) {
                return -1;
            }

            DataOutputStream outputStream;
            try {
                outputStream = new DataOutputStream(new FileOutputStream(new File(mDataBlockFile)));
            } catch (FileNotFoundException e) {
                Log.e(TAG, "partition not available?", e);
                return -1;
            }

            ByteBuffer headerAndData = ByteBuffer.allocate(data.length + HEADER_SIZE);
            headerAndData.putInt(BLOCK_ID);
            headerAndData.putInt(data.length);
            headerAndData.put(data);

            try {
                outputStream.write(headerAndData.array());
                return data.length;
            } catch (IOException e) {
                Log.e(TAG, "failed writing to the persistent data block", e);
                return -1;
            } finally {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "failed closing output stream", e);
                }
            }
        }

        @Override
        public int read(byte[] data) {
            enforceUid(Binder.getCallingUid());

            DataInputStream inputStream;
            try {
                inputStream = new DataInputStream(new FileInputStream(new File(mDataBlockFile)));
            } catch (FileNotFoundException e) {
                Log.e(TAG, "partition not available?", e);
                return -1;
            }

            try {
                int totalDataSize = getTotalDataSize(inputStream);
                return inputStream.read(data, 0,
                        (data.length > totalDataSize) ? totalDataSize : data.length);
            } catch (IOException e) {
                Log.e(TAG, "failed to read data", e);
                return -1;
            } finally {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "failed to close OutputStream");
                }
            }
        }

        @Override
        public void setOemUnlockEnabled(boolean enabled) {
            enforceOemUnlockPermission();
            FileOutputStream outputStream;
            try {
                outputStream = new FileOutputStream(new File(mDataBlockFile));
            } catch (FileNotFoundException e) {
                Log.e(TAG, "parition not available", e);
                return;
            }

            try {
                FileChannel channel = outputStream.getChannel();

                channel.position(maybeReadBlockDeviceSize() - 1);

                ByteBuffer data = ByteBuffer.allocate(1);
                data.put(enabled ? (byte) 1 : (byte) 0);
                data.flip();

                channel.write(data);
            } catch (IOException e) {
                Log.e(TAG, "unable to access persistent partition", e);
            } finally {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "failed to close OutputStream");
                }
            }
        }

        @Override
        public boolean getOemUnlockEnabled() {
            enforceOemUnlockPermission();
            DataInputStream inputStream;
            try {
                inputStream = new DataInputStream(new FileInputStream(new File(mDataBlockFile)));
            } catch (FileNotFoundException e) {
                Log.e(TAG, "partition not available");
                return false;
            }

            try {
                inputStream.skip(maybeReadBlockDeviceSize() - 1);
                return inputStream.readByte() != 0;
            } catch (IOException e) {
                Log.e(TAG, "unable to access persistent partition", e);
                return false;
            } finally {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "failed to close OutputStream");
                }
            }
        }

        @Override
        public int getDataBlockSize() {
            enforceUid(Binder.getCallingUid());

            DataInputStream inputStream;
            try {
                inputStream = new DataInputStream(new FileInputStream(new File(mDataBlockFile)));
            } catch (FileNotFoundException e) {
                Log.e(TAG, "partition not available");
                return 0;
            }

            try {
                return getTotalDataSize(inputStream);
            } catch (IOException e) {
                Log.e(TAG, "error reading data block size");
                return 0;
            } finally {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "failed to close OutputStream");
                }
            }
        }
    };
}
