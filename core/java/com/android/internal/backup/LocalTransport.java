package com.android.internal.backup;

import android.backup.BackupDataInput;
import android.backup.BackupDataOutput;
import android.backup.RestoreSet;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.bouncycastle.util.encoders.Base64;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Backup transport for stashing stuff into a known location on disk, and
 * later restoring from there.  For testing only.
 */

public class LocalTransport extends IBackupTransport.Stub {
    private static final String TAG = "LocalTransport";
    private static final boolean DEBUG = true;

    private static final String TRANSPORT_DIR_NAME
            = "com.android.internal.backup.LocalTransport";

    private Context mContext;
    private PackageManager mPackageManager;
    private File mDataDir = new File(Environment.getDownloadCacheDirectory(), "backup");
    private PackageInfo[] mRestorePackages = null;
    private int mRestorePackage = -1;  // Index into mRestorePackages


    public LocalTransport(Context context) {
        if (DEBUG) Log.v(TAG, "Transport constructed");
        mContext = context;
        mPackageManager = context.getPackageManager();
    }


    public String transportDirName() throws RemoteException {
        return TRANSPORT_DIR_NAME;
    }

    public long requestBackupTime() throws RemoteException {
        // any time is a good time for local backup
        return 0;
    }

    public boolean performBackup(PackageInfo packageInfo, ParcelFileDescriptor data)
            throws RemoteException {
        if (DEBUG) Log.v(TAG, "performBackup() pkg=" + packageInfo.packageName);

        File packageDir = new File(mDataDir, packageInfo.packageName);
        packageDir.mkdirs();

        // Each 'record' in the restore set is kept in its own file, named by
        // the record key.  Wind through the data file, extracting individual
        // record operations and building a set of all the updates to apply
        // in this update.
        BackupDataInput changeSet = new BackupDataInput(data.getFileDescriptor());
        try {
            int bufSize = 512;
            byte[] buf = new byte[bufSize];
            while (changeSet.readNextHeader()) {
                String key = changeSet.getKey();
                String base64Key = new String(Base64.encode(key.getBytes()));
                File entityFile = new File(packageDir, base64Key);

                int dataSize = changeSet.getDataSize();

                if (DEBUG) Log.v(TAG, "Got change set key=" + key + " size=" + dataSize
                        + " key64=" + base64Key);

                if (dataSize >= 0) {
                    FileOutputStream entity = new FileOutputStream(entityFile);

                    if (dataSize > bufSize) {
                        bufSize = dataSize;
                        buf = new byte[bufSize];
                    }
                    changeSet.readEntityData(buf, 0, dataSize);
                    if (DEBUG) Log.v(TAG, "  data size " + dataSize);

                    try {
                        entity.write(buf, 0, dataSize);
                    } catch (IOException e) {
                        Log.e(TAG, "Unable to update key file " + entityFile.getAbsolutePath());
                        return false;
                    } finally {
                        entity.close();
                    }
                } else {
                    entityFile.delete();
                }
            }
            return true;
        } catch (IOException e) {
            // oops, something went wrong.  abort the operation and return error.
            Log.v(TAG, "Exception reading backup input:", e);
            return false;
        }
    }

    public boolean clearBackupData(PackageInfo packageInfo) {
        if (DEBUG) Log.v(TAG, "clearBackupData() pkg=" + packageInfo.packageName);

        File packageDir = new File(mDataDir, packageInfo.packageName);
        for (File f : packageDir.listFiles()) {
            f.delete();
        }
        packageDir.delete();
        return true;
    }

    public boolean finishBackup() throws RemoteException {
        if (DEBUG) Log.v(TAG, "finishBackup()");
        return true;
    }

    // Restore handling
    public RestoreSet[] getAvailableRestoreSets() throws android.os.RemoteException {
        // one hardcoded restore set
        RestoreSet set = new RestoreSet("Local disk image", "flash", 0);
        RestoreSet[] array = { set };
        return array;
    }

    public boolean startRestore(long token, PackageInfo[] packages) {
        if (DEBUG) Log.v(TAG, "start restore " + token);
        mRestorePackages = packages;
        mRestorePackage = -1;
        return true;
    }

    public String nextRestorePackage() {
        if (mRestorePackages == null) throw new IllegalStateException("startRestore not called");
        while (++mRestorePackage < mRestorePackages.length) {
            String name = mRestorePackages[mRestorePackage].packageName;
            if (new File(mDataDir, name).isDirectory()) {
                if (DEBUG) Log.v(TAG, "  nextRestorePackage() = " + name);
                return name;
            }
        }

        if (DEBUG) Log.v(TAG, "  no more packages to restore");
        return "";
    }

    public boolean getRestoreData(ParcelFileDescriptor outFd) {
        if (mRestorePackages == null) throw new IllegalStateException("startRestore not called");
        if (mRestorePackage < 0) throw new IllegalStateException("nextRestorePackage not called");
        File packageDir = new File(mDataDir, mRestorePackages[mRestorePackage].packageName);

        // The restore set is the concatenation of the individual record blobs,
        // each of which is a file in the package's directory
        File[] blobs = packageDir.listFiles();
        if (blobs == null) {
            Log.e(TAG, "Error listing directory: " + packageDir);
            return false;  // nextRestorePackage() ensures the dir exists, so this is an error
        }

        // We expect at least some data if the directory exists in the first place
        if (DEBUG) Log.v(TAG, "  getRestoreData() found " + blobs.length + " key files");
        BackupDataOutput out = new BackupDataOutput(outFd.getFileDescriptor());
        try {
            for (File f : blobs) {
                FileInputStream in = new FileInputStream(f);
                try {
                    int size = (int) f.length();
                    byte[] buf = new byte[size];
                    in.read(buf);
                    String key = new String(Base64.decode(f.getName()));
                    if (DEBUG) Log.v(TAG, "    ... key=" + key + " size=" + size);
                    out.writeEntityHeader(key, size);
                    out.writeEntityData(buf, size);
                } finally {
                    in.close();
                }
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Unable to read backup records", e);
            return false;
        }
    }

    public void finishRestore() {
        if (DEBUG) Log.v(TAG, "finishRestore()");
    }
}
