package com.android.internal.backup;

import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/**
 * Backup transport for full backup over adb.  This transport pipes everything to
 * a file in a known location in /cache, which 'adb backup' then pulls to the desktop
 * (deleting it afterwards).
 */

public class AdbTransport extends IBackupTransport.Stub {

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
}
