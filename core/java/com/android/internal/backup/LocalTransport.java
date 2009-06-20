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

    private Context mContext;
    private PackageManager mPackageManager;
    private File mDataDir = new File(Environment.getDownloadCacheDirectory(), "backup");
    private FileFilter mDirFileFilter = new FileFilter() {
        public boolean accept(File f) {
            return f.isDirectory();
        }
    };


    public LocalTransport(Context context) {
        if (DEBUG) Log.v(TAG, "Transport constructed");
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    public long requestBackupTime() throws RemoteException {
        // any time is a good time for local backup
        return 0;
    }

    public int startSession() throws RemoteException {
        if (DEBUG) Log.v(TAG, "session started");
        mDataDir.mkdirs();
        return 0;
    }

    public int endSession() throws RemoteException {
        if (DEBUG) Log.v(TAG, "session ended");
        return 0;
    }

    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor data)
            throws RemoteException {
        if (DEBUG) Log.v(TAG, "performBackup() pkg=" + packageInfo.packageName);
        int err = 0;

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
                        Log.e(TAG, "Unable to update key file "
                                + entityFile.getAbsolutePath());
                        err = -1;
                    } finally {
                        entity.close();
                    }
                } else {
                    entityFile.delete();
                }
            }
        } catch (IOException e) {
            // oops, something went wrong.  abort the operation and return error.
            Log.v(TAG, "Exception reading backup input:");
            e.printStackTrace();
            err = -1;
        }

        return err;
    }

    // Restore handling
    public RestoreSet[] getAvailableRestoreSets() throws android.os.RemoteException {
        // one hardcoded restore set
        RestoreSet set = new RestoreSet("Local disk image", "flash", 0);
        RestoreSet[] array = { set };
        return array;
    }

    public PackageInfo[] getAppSet(int token) throws android.os.RemoteException {
        if (DEBUG) Log.v(TAG, "getting app set " + token);
        // the available packages are the extant subdirs of mDatadir
        File[] packageDirs = mDataDir.listFiles(mDirFileFilter);
        ArrayList<PackageInfo> packages = new ArrayList<PackageInfo>();
        for (File dir : packageDirs) {
            try {
                PackageInfo pkg = mPackageManager.getPackageInfo(dir.getName(),
                        PackageManager.GET_SIGNATURES);
                if (pkg != null) {
                    packages.add(pkg);
                }
            } catch (NameNotFoundException e) {
                // restore set contains data for a package not installed on the
                // phone -- just ignore it.
            }
        }

        if (DEBUG) {
            Log.v(TAG, "Built app set of " + packages.size() + " entries:");
            for (PackageInfo p : packages) {
                Log.v(TAG, "    + " + p.packageName);
            }
        }

        PackageInfo[] result = new PackageInfo[packages.size()];
        return packages.toArray(result);
    }

    public int getRestoreData(int token, PackageInfo packageInfo, ParcelFileDescriptor outFd)
            throws android.os.RemoteException {
        if (DEBUG) Log.v(TAG, "getting restore data " + token + " : " + packageInfo.packageName);
        // we only support one hardcoded restore set
        if (token != 0) return -1;

        // the data for a given package is at a known location
        File packageDir = new File(mDataDir, packageInfo.packageName);

        // The restore set is the concatenation of the individual record blobs,
        // each of which is a file in the package's directory
        File[] blobs = packageDir.listFiles();
        int err = 0;
        if (blobs != null && blobs.length > 0) {
            BackupDataOutput out = new BackupDataOutput(outFd.getFileDescriptor());
            try {
                for (File f : blobs) {
                    FileInputStream in = new FileInputStream(f);
                    int size = (int) f.length();
                    byte[] buf = new byte[size];
                    in.read(buf);
                    String key = new String(Base64.decode(f.getName()));
                    out.writeEntityHeader(key, size);
                    out.writeEntityData(buf, size);
                }
            } catch (Exception e) {
                Log.e(TAG, "Unable to read backup records");
                err = -1;
            }
        }
        return err;
    }
}
