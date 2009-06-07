package com.android.internal.backup;

import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/**
 * Backup transport for saving data to Google cloud storage.
 */

public class GoogleTransport extends IBackupTransport.Stub {

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
