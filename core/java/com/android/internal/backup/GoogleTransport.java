package com.android.internal.backup;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/**
 * Backup transport for saving data to Google cloud storage.
 */

public class GoogleTransport extends IBackupTransport.Stub {

    public int endSession() throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public int performBackup(String packageName, ParcelFileDescriptor data)
            throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    public int startSession() throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

}
