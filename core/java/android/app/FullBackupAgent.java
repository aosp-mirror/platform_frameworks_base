package android.app;

import android.backup.BackupDataInput;
import android.backup.BackupDataOutput;
import android.backup.FileBackupHelper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * Backs up an application's entire /data/data/&lt;package&gt;/... file system.  This
 * class is used by the desktop full backup mechanism and is not intended for direct
 * use by applications.
 * 
 * {@hide}
 */

public class FullBackupAgent extends BackupAgent {
    // !!! TODO: turn off debugging
    private static final String TAG = "FullBackupAgent";
    private static final boolean DEBUG = true;

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        LinkedList<File> dirsToScan = new LinkedList<File>();
        ArrayList<String> allFiles = new ArrayList<String>();

        // build the list of files in the app's /data/data tree
        dirsToScan.add(getFilesDir());
        if (DEBUG) Log.v(TAG, "Backing up dir tree @ " + getFilesDir().getAbsolutePath() + " :");
        while (dirsToScan.size() > 0) {
            File dir = dirsToScan.removeFirst();
            File[] contents = dir.listFiles();
            if (contents != null) {
                for (File f : contents) {
                    if (f.isDirectory()) {
                        dirsToScan.add(f);
                    } else if (f.isFile()) {
                        if (DEBUG) Log.v(TAG, "    " + f.getAbsolutePath());
                        allFiles.add(f.getAbsolutePath());
                    }
                }
            }
        }

        // That's the file set; now back it all up
        FileBackupHelper helper = new FileBackupHelper(this, (String[])allFiles.toArray());
        helper.performBackup(oldState, data, newState);
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState) {
    }
}
