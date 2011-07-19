/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.backup;

import android.app.IBackupAgent;
import android.app.backup.IBackupManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;

import libcore.io.ErrnoException;
import libcore.io.Libcore;
import libcore.io.OsConstants;
import libcore.io.StructStat;

/**
 * Provides the central interface between an
 * application and Android's data backup infrastructure.  An application that wishes
 * to participate in the backup and restore mechanism will declare a subclass of
 * {@link android.app.backup.BackupAgent}, implement the
 * {@link #onBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor) onBackup()}
 * and {@link #onRestore(BackupDataInput, int, ParcelFileDescriptor) onRestore()} methods,
 * and provide the name of its backup agent class in its {@code AndroidManifest.xml} file via
 * the <code><a
 * href="{@docRoot}guide/topics/manifest/application-element.html">&lt;application&gt;</a></code>
 * tag's {@code android:backupAgent} attribute.
 * <h3>Basic Operation</h3>
 * <p>
 * When the application makes changes to data that it wishes to keep backed up,
 * it should call the
 * {@link android.app.backup.BackupManager#dataChanged() BackupManager.dataChanged()} method.
 * This notifies the Android Backup Manager that the application needs an opportunity
 * to update its backup image.  The Backup Manager, in turn, schedules a
 * backup pass to be performed at an opportune time.
 * <p>
 * Restore operations are typically performed only when applications are first
 * installed on a device.  At that time, the operating system checks to see whether
 * there is a previously-saved data set available for the application being installed, and if so,
 * begins an immediate restore pass to deliver the backup data as part of the installation
 * process.
 * <p>
 * When a backup or restore pass is run, the application's process is launched
 * (if not already running), the manifest-declared backup agent class (in the {@code
 * android:backupAgent} attribute) is instantiated within
 * that process, and the agent's {@link #onCreate()} method is invoked.  This prepares the
 * agent instance to run the actual backup or restore logic.  At this point the
 * agent's
 * {@link #onBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor) onBackup()} or
 * {@link #onRestore(BackupDataInput, int, ParcelFileDescriptor) onRestore()} method will be
 * invoked as appropriate for the operation being performed.
 * <p>
 * A backup data set consists of one or more "entities," flattened binary data
 * records that are each identified with a key string unique within the data set.  Adding a
 * record to the active data set or updating an existing record is done by simply
 * writing new entity data under the desired key.  Deleting an entity from the data set
 * is done by writing an entity under that key with header specifying a negative data
 * size, and no actual entity data.
 * <p>
 * <b>Helper Classes</b>
 * <p>
 * An extensible agent based on convenient helper classes is available in
 * {@link android.app.backup.BackupAgentHelper}.  That class is particularly
 * suited to handling of simple file or {@link android.content.SharedPreferences}
 * backup and restore.
 *
 * @see android.app.backup.BackupManager
 * @see android.app.backup.BackupAgentHelper
 * @see android.app.backup.BackupDataInput
 * @see android.app.backup.BackupDataOutput
 */
public abstract class BackupAgent extends ContextWrapper {
    private static final String TAG = "BackupAgent";
    private static final boolean DEBUG = true;

    /** @hide */
    public static final int TYPE_EOF = 0;

    /**
     * During a full restore, indicates that the file system object being restored
     * is an ordinary file.
     */
    public static final int TYPE_FILE = 1;

    /**
     * During a full restore, indicates that the file system object being restored
     * is a directory.
     */
    public static final int TYPE_DIRECTORY = 2;

    /** @hide */
    public static final int TYPE_SYMLINK = 3;

    public BackupAgent() {
        super(null);
    }

    /**
     * Provided as a convenience for agent implementations that need an opportunity
     * to do one-time initialization before the actual backup or restore operation
     * is begun.
     * <p>
     * Agents do not need to override this method.
     */
    public void onCreate() {
    }

    /**
     * Provided as a convenience for agent implementations that need to do some
     * sort of shutdown process after backup or restore is completed.
     * <p>
     * Agents do not need to override this method.
     */
    public void onDestroy() {
    }

    /**
     * The application is being asked to write any data changed since the last
     * time it performed a backup operation. The state data recorded during the
     * last backup pass is provided in the <code>oldState</code> file
     * descriptor. If <code>oldState</code> is <code>null</code>, no old state
     * is available and the application should perform a full backup. In both
     * cases, a representation of the final backup state after this pass should
     * be written to the file pointed to by the file descriptor wrapped in
     * <code>newState</code>.
     * <p>
     * Each entity written to the {@link android.app.backup.BackupDataOutput}
     * <code>data</code> stream will be transmitted
     * over the current backup transport and stored in the remote data set under
     * the key supplied as part of the entity.  Writing an entity with a negative
     * data size instructs the transport to delete whatever entity currently exists
     * under that key from the remote data set.
     * 
     * @param oldState An open, read-only ParcelFileDescriptor pointing to the
     *            last backup state provided by the application. May be
     *            <code>null</code>, in which case no prior state is being
     *            provided and the application should perform a full backup.
     * @param data A structured wrapper around an open, read/write
     *            file descriptor pointing to the backup data destination.
     *            Typically the application will use backup helper classes to
     *            write to this file.
     * @param newState An open, read/write ParcelFileDescriptor pointing to an
     *            empty file. The application should record the final backup
     *            state here after writing the requested data to the <code>data</code>
     *            output stream.
     */
    public abstract void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
             ParcelFileDescriptor newState) throws IOException;

    /**
     * The application is being restored from backup and should replace any
     * existing data with the contents of the backup. The backup data is
     * provided through the <code>data</code> parameter. Once
     * the restore is finished, the application should write a representation of
     * the final state to the <code>newState</code> file descriptor.
     * <p>
     * The application is responsible for properly erasing its old data and
     * replacing it with the data supplied to this method. No "clear user data"
     * operation will be performed automatically by the operating system. The
     * exception to this is in the case of a failed restore attempt: if
     * onRestore() throws an exception, the OS will assume that the
     * application's data may now be in an incoherent state, and will clear it
     * before proceeding.
     * 
     * @param data A structured wrapper around an open, read-only
     *            file descriptor pointing to a full snapshot of the
     *            application's data.  The application should consume every
     *            entity represented in this data stream.
     * @param appVersionCode The value of the <a
     * href="{@docRoot}guide/topics/manifest/manifest-element.html#vcode">{@code
     *            android:versionCode}</a> manifest attribute,
     *            from the application that backed up this particular data set. This
     *            makes it possible for an application's agent to distinguish among any
     *            possible older data versions when asked to perform the restore
     *            operation.
     * @param newState An open, read/write ParcelFileDescriptor pointing to an
     *            empty file. The application should record the final backup
     *            state here after restoring its data from the <code>data</code> stream.
     *            When a full-backup dataset is being restored, this will be <code>null</code>.
     */
    public abstract void onRestore(BackupDataInput data, int appVersionCode,
            ParcelFileDescriptor newState)
            throws IOException;

    /**
     * The default implementation backs up the entirety of the application's "owned"
     * file system trees to the output.
     */
    public void onFullBackup(FullBackupDataOutput data) throws IOException {
        ApplicationInfo appInfo = getApplicationInfo();

        String rootDir = new File(appInfo.dataDir).getCanonicalPath();
        String filesDir = getFilesDir().getCanonicalPath();
        String databaseDir = getDatabasePath("foo").getParentFile().getCanonicalPath();
        String sharedPrefsDir = getSharedPrefsFile("foo").getParentFile().getCanonicalPath();
        String cacheDir = getCacheDir().getCanonicalPath();
        String libDir = (appInfo.nativeLibraryDir != null)
                ? new File(appInfo.nativeLibraryDir).getCanonicalPath()
                : null;

        // Filters, the scan queue, and the set of resulting entities
        HashSet<String> filterSet = new HashSet<String>();
        String packageName = getPackageName();

        // Okay, start with the app's root tree, but exclude all of the canonical subdirs
        if (libDir != null) {
            filterSet.add(libDir);
        }
        filterSet.add(cacheDir);
        filterSet.add(databaseDir);
        filterSet.add(sharedPrefsDir);
        filterSet.add(filesDir);
        fullBackupFileTree(packageName, FullBackup.ROOT_TREE_TOKEN, rootDir, filterSet, data);

        // Now do the same for the files dir, db dir, and shared prefs dir
        filterSet.add(rootDir);
        filterSet.remove(filesDir);
        fullBackupFileTree(packageName, FullBackup.DATA_TREE_TOKEN, filesDir, filterSet, data);

        filterSet.add(filesDir);
        filterSet.remove(databaseDir);
        fullBackupFileTree(packageName, FullBackup.DATABASE_TREE_TOKEN, databaseDir, filterSet, data);

        filterSet.add(databaseDir);
        filterSet.remove(sharedPrefsDir);
        fullBackupFileTree(packageName, FullBackup.SHAREDPREFS_TREE_TOKEN, sharedPrefsDir, filterSet, data);
    }

    /**
     * Write an entire file as part of a full-backup operation.  The file's contents
     * will be delivered to the backup destination along with the metadata necessary
     * to place it with the proper location and permissions on the device where the
     * data is restored.
     * @hide
     *
     * @param context The BackupAgent that is calling this method.  It is an error to
     *     call it from something other than a running BackupAgent instance.
     * @param file The file to be backed up.  The file must exist and be readable by
     *     the caller.
     * @param output The destination to which the backed-up file data will be sent.
     */
    public final void fullBackupFile(File file, FullBackupDataOutput output) {
        // Look up where all of our various well-defined dir trees live on this device
        String mainDir;
        String filesDir;
        String dbDir;
        String spDir;
        String cacheDir;
        String libDir;
        String filePath;

        ApplicationInfo appInfo = getApplicationInfo();

        try {
            mainDir = new File(appInfo.dataDir).getCanonicalPath();
            filesDir = getFilesDir().getCanonicalPath();
            dbDir = getDatabasePath("foo").getParentFile().getCanonicalPath();
            spDir = getSharedPrefsFile("foo").getParentFile().getCanonicalPath();
            cacheDir = getCacheDir().getCanonicalPath();
            libDir = (appInfo.nativeLibraryDir == null)
                    ? null
                    : new File(appInfo.nativeLibraryDir).getCanonicalPath();

            // Now figure out which well-defined tree the file is placed in, working from
            // most to least specific.  We also specifically exclude the lib and cache dirs.
            filePath = file.getCanonicalPath();
        } catch (IOException e) {
            Log.w(TAG, "Unable to obtain canonical paths");
            return;
        }

        if (filePath.startsWith(cacheDir) || filePath.startsWith(libDir)) {
            Log.w(TAG, "lib and cache files are not backed up");
            return;
        }

        final String domain;
        String rootpath = null;
        if (filePath.startsWith(dbDir)) {
            domain = FullBackup.DATABASE_TREE_TOKEN;
            rootpath = dbDir;
        } else if (filePath.startsWith(spDir)) {
            domain = FullBackup.SHAREDPREFS_TREE_TOKEN;
            rootpath = spDir;
        } else if (filePath.startsWith(filesDir)) {
            domain = FullBackup.DATA_TREE_TOKEN;
            rootpath = filesDir;
        } else if (filePath.startsWith(mainDir)) {
            domain = FullBackup.ROOT_TREE_TOKEN;
            rootpath = mainDir;
        } else {
            Log.w(TAG, "File " + filePath + " is in an unsupported location; skipping");
            return;
        }

        // And now that we know where it lives, semantically, back it up appropriately
        Log.i(TAG, "backupFile() of " + filePath + " => domain=" + domain
                + " rootpath=" + rootpath);
        FullBackup.backupToTar(getPackageName(), domain, null, rootpath, filePath,
                output.getData());
    }

    /**
     * Scan the dir tree (if it actually exists) and process each entry we find.  If the
     * 'excludes' parameter is non-null, it is consulted each time a new file system entity
     * is visited to see whether that entity (and its subtree, if appropriate) should be
     * omitted from the backup process.
     *
     * @hide
     */
    protected final void fullBackupFileTree(String packageName, String domain, String rootPath,
            HashSet<String> excludes, FullBackupDataOutput output) {
        File rootFile = new File(rootPath);
        if (rootFile.exists()) {
            LinkedList<File> scanQueue = new LinkedList<File>();
            scanQueue.add(rootFile);

            while (scanQueue.size() > 0) {
                File file = scanQueue.remove(0);
                String filePath;
                try {
                    filePath = file.getCanonicalPath();

                    // prune this subtree?
                    if (excludes != null && excludes.contains(filePath)) {
                        continue;
                    }

                    // If it's a directory, enqueue its contents for scanning.
                    StructStat stat = Libcore.os.lstat(filePath);
                    if (OsConstants.S_ISLNK(stat.st_mode)) {
                        if (DEBUG) Log.i(TAG, "Symlink (skipping)!: " + file);
                        continue;
                    } else if (OsConstants.S_ISDIR(stat.st_mode)) {
                        File[] contents = file.listFiles();
                        if (contents != null) {
                            for (File entry : contents) {
                                scanQueue.add(0, entry);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (DEBUG) Log.w(TAG, "Error canonicalizing path of " + file);
                    continue;
                } catch (ErrnoException e) {
                    if (DEBUG) Log.w(TAG, "Error scanning file " + file + " : " + e);
                    continue;
                }

                // Finally, back this file up before proceeding
                FullBackup.backupToTar(packageName, domain, null, rootPath, filePath,
                        output.getData());
            }
        }
    }

    /**
     * Handle the data delivered via the given file descriptor during a full restore
     * operation.  The agent is given the path to the file's original location as well
     * as its size and metadata.
     * <p>
     * The file descriptor can only be read for {@code size} bytes; attempting to read
     * more data has undefined behavior.
     * <p>
     * The default implementation creates the destination file/directory and populates it
     * with the data from the file descriptor, then sets the file's access mode and
     * modification time to match the restore arguments.
     *
     * @param data A read-only file descriptor from which the agent can read {@code size}
     *     bytes of file data.
     * @param size The number of bytes of file content to be restored to the given
     *     destination.  If the file system object being restored is a directory, {@code size}
     *     will be zero.
     * @param destination The File on disk to be restored with the given data.
     * @param type The kind of file system object being restored.  This will be either
     *     {@link BackupAgent#TYPE_FILE} or {@link BackupAgent#TYPE_DIRECTORY}.
     * @param mode The access mode to be assigned to the destination after its data is
     *     written.  This is in the standard format used by {@code chmod()}.
     * @param mtime The modification time of the file when it was backed up, suitable to
     *     be assigned to the file after its data is written.
     * @throws IOException
     */
    public void onRestoreFile(ParcelFileDescriptor data, long size,
            File destination, int type, long mode, long mtime)
            throws IOException {
        FullBackup.restoreFile(data, size, type, mode, mtime, destination);
    }

    /**
     * Only specialized platform agents should overload this entry point to support
     * restores to crazy non-app locations.
     * @hide
     */
    protected void onRestoreFile(ParcelFileDescriptor data, long size,
            int type, String domain, String path, long mode, long mtime)
            throws IOException {
        String basePath = null;

        if (DEBUG) Log.d(TAG, "onRestoreFile() size=" + size + " type=" + type
                + " domain=" + domain + " relpath=" + path + " mode=" + mode
                + " mtime=" + mtime);

        // Parse out the semantic domains into the correct physical location
        if (domain.equals(FullBackup.DATA_TREE_TOKEN)) {
            basePath = getFilesDir().getCanonicalPath();
        } else if (domain.equals(FullBackup.DATABASE_TREE_TOKEN)) {
            basePath = getDatabasePath("foo").getParentFile().getCanonicalPath();
        } else if (domain.equals(FullBackup.ROOT_TREE_TOKEN)) {
            basePath = new File(getApplicationInfo().dataDir).getCanonicalPath();
        } else if (domain.equals(FullBackup.SHAREDPREFS_TREE_TOKEN)) {
            basePath = getSharedPrefsFile("foo").getParentFile().getCanonicalPath();
        } else if (domain.equals(FullBackup.CACHE_TREE_TOKEN)) {
            basePath = getCacheDir().getCanonicalPath();
        } else {
            // Not a supported location
            Log.i(TAG, "Data restored from non-app domain " + domain + ", ignoring");
        }

        // Now that we've figured out where the data goes, send it on its way
        if (basePath != null) {
            File outFile = new File(basePath, path);
            if (DEBUG) Log.i(TAG, "[" + domain + " : " + path + "] mapped to " + outFile.getPath());
            onRestoreFile(data, size, outFile, type, mode, mtime);
        } else {
            // Not a supported output location?  We need to consume the data
            // anyway, so just use the default "copy the data out" implementation
            // with a null destination.
            if (DEBUG) Log.i(TAG, "[ skipping data from unsupported domain " + domain + "]");
            FullBackup.restoreFile(data, size, type, mode, mtime, null);
        }
    }

    // ----- Core implementation -----

    /** @hide */
    public final IBinder onBind() {
        return mBinder;
    }

    private final IBinder mBinder = new BackupServiceBinder().asBinder();

    /** @hide */
    public void attach(Context context) {
        attachBaseContext(context);
    }

    // ----- IBackupService binder interface -----
    private class BackupServiceBinder extends IBackupAgent.Stub {
        private static final String TAG = "BackupServiceBinder";

        @Override
        public void doBackup(ParcelFileDescriptor oldState,
                ParcelFileDescriptor data,
                ParcelFileDescriptor newState,
                int token, IBackupManager callbackBinder) throws RemoteException {
            // Ensure that we're running with the app's normal permission level
            long ident = Binder.clearCallingIdentity();

            if (DEBUG) Log.v(TAG, "doBackup() invoked");
            BackupDataOutput output = new BackupDataOutput(data.getFileDescriptor());

            try {
                BackupAgent.this.onBackup(oldState, output, newState);
            } catch (IOException ex) {
                Log.d(TAG, "onBackup (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw new RuntimeException(ex);
            } catch (RuntimeException ex) {
                Log.d(TAG, "onBackup (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw ex;
            } finally {
                Binder.restoreCallingIdentity(ident);
                try {
                    callbackBinder.opComplete(token);
                } catch (RemoteException e) {
                    // we'll time out anyway, so we're safe
                }
            }
        }

        @Override
        public void doRestore(ParcelFileDescriptor data, int appVersionCode,
                ParcelFileDescriptor newState,
                int token, IBackupManager callbackBinder) throws RemoteException {
            // Ensure that we're running with the app's normal permission level
            long ident = Binder.clearCallingIdentity();

            if (DEBUG) Log.v(TAG, "doRestore() invoked");
            BackupDataInput input = new BackupDataInput(data.getFileDescriptor());
            try {
                BackupAgent.this.onRestore(input, appVersionCode, newState);
            } catch (IOException ex) {
                Log.d(TAG, "onRestore (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw new RuntimeException(ex);
            } catch (RuntimeException ex) {
                Log.d(TAG, "onRestore (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw ex;
            } finally {
                Binder.restoreCallingIdentity(ident);
                try {
                    callbackBinder.opComplete(token);
                } catch (RemoteException e) {
                    // we'll time out anyway, so we're safe
                }
            }
        }

        @Override
        public void doFullBackup(ParcelFileDescriptor data,
                int token, IBackupManager callbackBinder) {
            // Ensure that we're running with the app's normal permission level
            long ident = Binder.clearCallingIdentity();

            if (DEBUG) Log.v(TAG, "doFullBackup() invoked");

            try {
                BackupAgent.this.onFullBackup(new FullBackupDataOutput(data));
            } catch (IOException ex) {
                Log.d(TAG, "onBackup (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw new RuntimeException(ex);
            } catch (RuntimeException ex) {
                Log.d(TAG, "onBackup (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw ex;
            } finally {
                // Send the EOD marker indicating that there is no more data
                // forthcoming from this agent.
                try {
                    FileOutputStream out = new FileOutputStream(data.getFileDescriptor());
                    byte[] buf = new byte[4];
                    out.write(buf);
                } catch (IOException e) {
                    Log.e(TAG, "Unable to finalize backup stream!");
                }

                Binder.restoreCallingIdentity(ident);
                try {
                    callbackBinder.opComplete(token);
                } catch (RemoteException e) {
                    // we'll time out anyway, so we're safe
                }
            }
        }

        @Override
        public void doRestoreFile(ParcelFileDescriptor data, long size,
                int type, String domain, String path, long mode, long mtime,
                int token, IBackupManager callbackBinder) throws RemoteException {
            long ident = Binder.clearCallingIdentity();
            try {
                BackupAgent.this.onRestoreFile(data, size, type, domain, path, mode, mtime);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                Binder.restoreCallingIdentity(ident);
                try {
                    callbackBinder.opComplete(token);
                } catch (RemoteException e) {
                    // we'll time out anyway, so we're safe
                }
            }
        }
    }
}
