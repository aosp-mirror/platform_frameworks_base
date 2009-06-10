package com.android.internal.backup;

import android.backup.RestoreSet;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/**
 * Backup transport for full backup over adb.  This transport pipes everything to
 * a file in a known location in /cache, which 'adb backup' then pulls to the desktop
 * (deleting it afterwards).
 */

public class AdbTransport extends IBackupTransport.Stub {

    public long requestBackupTime() throws RemoteException {
        return 0;
    }

    public int startSession() throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public int endSession() throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor data)
            throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    // Restore handling
    public RestoreSet[] getAvailableRestoreSets() throws android.os.RemoteException {
        RestoreSet[] set = new RestoreSet[1];
        set[0].device = "USB";
        set[0].name = "adb";
        set[0].token = 0;
        return set;
    }

    public PackageInfo[] getAppSet(int token) throws android.os.RemoteException {
        // !!! TODO: real implementation
        return new PackageInfo[0];
    }

    public int getRestoreData(int token, PackageInfo packageInfo, ParcelFileDescriptor data)
            throws android.os.RemoteException {
        // !!! TODO: real implementation
        return 0;
    }
}
