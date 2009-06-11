package com.android.internal.backup;

import android.backup.RestoreSet;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

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
    private static final String DATA_FILE_NAME = "data";

    private Context mContext;
    private PackageManager mPackageManager;
    private File mDataDir = new File(Environment.getDownloadCacheDirectory(), "backup");
    private FileFilter mDirFileFilter = new FileFilter() {
        public boolean accept(File f) {
            return f.isDirectory();
        }
    };


    public LocalTransport(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    public long requestBackupTime() throws RemoteException {
        // any time is a good time for local backup
        return 0;
    }

    public int startSession() throws RemoteException {
        return 0;
    }

    public int endSession() throws RemoteException {
        return 0;
    }

    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor data)
            throws RemoteException {
        File packageDir = new File(mDataDir, packageInfo.packageName);
        File imageFileName = new File(packageDir, DATA_FILE_NAME);

        //!!! TODO: process the (partial) update into the persistent restore set:
        
        // Parse out the existing image file into the key/value map

        // Parse out the backup data into the key/value updates

        // Apply the backup key/value updates to the image

        // Write out the image in the canonical format

        return -1;
    }

    // Restore handling
    public RestoreSet[] getAvailableRestoreSets() throws android.os.RemoteException {
        // one hardcoded restore set
        RestoreSet[] set = new RestoreSet[1];
        set[0].device = "flash";
        set[0].name = "Local disk image";
        set[0].token = 0;
        return set;
    }

    public PackageInfo[] getAppSet(int token) throws android.os.RemoteException {
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

        Log.v(TAG, "Built app set of " + packages.size() + " entries:");
        for (PackageInfo p : packages) {
            Log.v(TAG, "    + " + p.packageName);
        }

        PackageInfo[] result = new PackageInfo[packages.size()];
        return packages.toArray(result);
    }

    public int getRestoreData(int token, PackageInfo packageInfo, ParcelFileDescriptor output)
            throws android.os.RemoteException {
        // we only support one hardcoded restore set
        if (token != 0) return -1;

        // the data for a given package is at a known location
        File packageDir = new File(mDataDir, packageInfo.packageName);
        File imageFile = new File(packageDir, DATA_FILE_NAME);

        // restore is relatively easy: we already maintain the full data set in
        // the canonical form understandable to the BackupAgent
        return copyFileToFD(imageFile, output);
    }

    private int copyFileToFD(File source, ParcelFileDescriptor dest) {
        try {
            FileInputStream in = new FileInputStream(source);
            FileOutputStream out = new FileOutputStream(dest.getFileDescriptor());
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) >= 0) {
                out.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            // something went wrong; claim failure
            return -1;
        }
        return 0;
    }
}
