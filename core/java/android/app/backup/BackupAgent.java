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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.IBackupAgent;
import android.app.QueuedWork;
import android.app.backup.BackupAnnotations.BackupDestination;
import android.app.backup.BackupAnnotations.OperationType;
import android.app.backup.FullBackup.BackupScheme.PathWithRequiredFlags;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;
import com.android.server.backup.Flags;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Provides the central interface between an
 * application and Android's data backup infrastructure.  An application that wishes
 * to participate in the backup and restore mechanism will declare a subclass of
 * {@link android.app.backup.BackupAgent}, implement the
 * {@link #onBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor) onBackup()}
 * and {@link #onRestore(BackupDataInput, int, ParcelFileDescriptor) onRestore()} methods,
 * and provide the name of its backup agent class in its {@code AndroidManifest.xml} file via
 * the <code>
 * <a href="{@docRoot}guide/topics/manifest/application-element.html">&lt;application&gt;</a></code>
 * tag's {@code android:backupAgent} attribute.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using BackupAgent, read the
 * <a href="{@docRoot}guide/topics/data/backup.html">Data Backup</a> developer guide.</p></div>
 *
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
 * <p>
 * <b>Threading</b>
 * <p>
 * The constructor, as well as {@link #onCreate()} and {@link #onDestroy()} lifecycle callbacks run
 * on the main thread (UI thread) of the application that implements the BackupAgent.
 * The data-handling callbacks:
 * {@link #onBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor) onBackup()},
 * {@link #onFullBackup(FullBackupDataOutput)},
 * {@link #onRestore(BackupDataInput, int, ParcelFileDescriptor) onRestore()},
 * {@link #onRestoreFile(ParcelFileDescriptor, long, File, int, long, long) onRestoreFile()},
 * {@link #onRestoreFinished()}, and {@link #onQuotaExceeded(long, long) onQuotaExceeded()}
 * run on binder pool threads.
 *
 * @see android.app.backup.BackupManager
 * @see android.app.backup.BackupAgentHelper
 * @see android.app.backup.BackupDataInput
 * @see android.app.backup.BackupDataOutput
 */
public abstract class BackupAgent extends ContextWrapper {
    private static final String TAG = "BackupAgent";
    private static final boolean DEBUG = false;
    private static final int DEFAULT_BACKUP_DESTINATION = BackupDestination.CLOUD;

    /** @hide */
    public static final int RESULT_SUCCESS = 0;
    /** @hide */
    public static final int RESULT_ERROR = -1;

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

    /**
     * Flag for {@link BackupDataOutput#getTransportFlags()} and
     * {@link FullBackupDataOutput#getTransportFlags()} only.
     *
     * <p>The transport has client-side encryption enabled. i.e., the user's backup has been
     * encrypted with a key known only to the device, and not to the remote storage solution. Even
     * if an attacker had root access to the remote storage provider they should not be able to
     * decrypt the user's backup data.
     */
    public static final int FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED = 1;

    /**
     * Flag for {@link BackupDataOutput#getTransportFlags()} and
     * {@link FullBackupDataOutput#getTransportFlags()} only.
     *
     * <p>The transport is for a device-to-device transfer. There is no third party or intermediate
     * storage. The user's backup data is sent directly to another device over e.g., USB or WiFi.
     */
    public static final int FLAG_DEVICE_TO_DEVICE_TRANSFER = 2;

    /**
     * Flag for {@link RestoreSet#backupTransportFlags} to indicate if restore should be skipped
     * for apps that have already been launched.
     *
     * @hide
     */
    public static final int FLAG_SKIP_RESTORE_FOR_LAUNCHED_APPS = 1 << 2;

    /**
     * Flag for {@link BackupDataOutput#getTransportFlags()} and
     * {@link FullBackupDataOutput#getTransportFlags()} only.
     *
     * <p>Used for internal testing only. Do not check this flag in production code.
     *
     * @hide
     */
    public static final int FLAG_FAKE_CLIENT_SIDE_ENCRYPTION_ENABLED = 1 << 31;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED,
            FLAG_DEVICE_TO_DEVICE_TRANSFER,
            FLAG_FAKE_CLIENT_SIDE_ENCRYPTION_ENABLED
    })
    public @interface BackupTransportFlags {}

    Handler mHandler = null;

    @Nullable private volatile BackupRestoreEventLogger mLogger = null;
    @Nullable private UserHandle mUser;
     // This field is written from the main thread (in onCreate), and read in a Binder thread (in
     // onFullBackup that is called from system_server via Binder).
    @BackupDestination private volatile int mBackupDestination = DEFAULT_BACKUP_DESTINATION;

    Handler getHandler() {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        return mHandler;
    }

    class SharedPrefsSynchronizer implements Runnable {
        public final CountDownLatch mLatch = new CountDownLatch(1);

        @Override
        public void run() {
            QueuedWork.waitToFinish();
            mLatch.countDown();
        }
    };

    // Syncing shared preferences deferred writes needs to happen on the main looper thread
    private void waitForSharedPrefs() {
        Handler h = getHandler();
        final SharedPrefsSynchronizer s = new SharedPrefsSynchronizer();
        h.postAtFrontOfQueue(s);
        try {
            s.mLatch.await();
        } catch (InterruptedException e) { /* ignored */ }
    }

    /**
     * Get a logger to record app-specific backup and restore events that are happening during a
     * backup or restore operation.
     *
     * <p>The logger instance had been created by the system with the correct {@link
     * BackupRestoreEventLogger.OperationType} that corresponds to the operation the {@code
     * BackupAgent} is currently handling.
     *
     * @hide
     */
    @Nullable
    public BackupRestoreEventLogger getBackupRestoreEventLogger() {
        return mLogger;
    }

    public BackupAgent() {
        super(null);
    }

    /**
     * Provided as a convenience for agent implementations that need an opportunity
     * to do one-time initialization before the actual backup or restore operation
     * is begun.
     * <p>
     */
    public void onCreate() {
    }

    /** @hide */
    public void onCreate(UserHandle user) {
        mUser = user;
        onCreate();
    }

    /**
     * @deprecated Use {@link BackupAgent#onCreate(UserHandle, int, int)} instead.
     *
     * @hide
     */
    @Deprecated
    public void onCreate(UserHandle user, @BackupDestination int backupDestination) {
        mBackupDestination = backupDestination;

        onCreate(user);
    }

    /**
    * @hide
    */
    public void onCreate(UserHandle user, @BackupDestination int backupDestination,
            @OperationType int operationType) {
        mBackupDestination = backupDestination;
        mLogger = new BackupRestoreEventLogger(operationType);

        onCreate(user, backupDestination);
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
            ParcelFileDescriptor newState) throws IOException;

    /**
     * New version of {@link #onRestore(BackupDataInput, int, android.os.ParcelFileDescriptor)}
     * that handles a long app version code.  Default implementation casts the version code to
     * an int and calls {@link #onRestore(BackupDataInput, int, android.os.ParcelFileDescriptor)}.
     */
    public void onRestore(BackupDataInput data, long appVersionCode,
            ParcelFileDescriptor newState)
            throws IOException {
        onRestore(data, (int) appVersionCode, newState);
    }

    /**
     * New version of {@link #onRestore(BackupDataInput, long, android.os.ParcelFileDescriptor)}
     * that has a list of keys to be excluded from the restore. Key/value pairs for which the key
     * is present in {@code excludedKeys} have already been excluded from the restore data by the
     * system. The list is passed to the agent to make it aware of what data has been removed (in
     * case it has any application-level consequences) as well as the data that should be removed
     * by the agent itself.
     *
     * The default implementation calls {@link #onRestore(BackupDataInput, long,
     * android.os.ParcelFileDescriptor)}.
     *
     * @param excludedKeys A list of keys to be excluded from restore.
     *
     * @hide
     */
    public void onRestore(BackupDataInput data, long appVersionCode,
            ParcelFileDescriptor newState,
            Set<String> excludedKeys)
            throws IOException {
        onRestore(data, appVersionCode, newState);
    }

    /**
     * The application is having its entire file system contents backed up.  {@code data}
     * points to the backup destination, and the app has the opportunity to choose which
     * files are to be stored.  To commit a file as part of the backup, call the
     * {@link #fullBackupFile(File, FullBackupDataOutput)} helper method.  After all file
     * data is written to the output, the agent returns from this method and the backup
     * operation concludes.
     *
     * <p>Certain parts of the app's data are never backed up even if the app explicitly
     * sends them to the output:
     *
     * <ul>
     * <li>The contents of the {@link #getCacheDir()} directory</li>
     * <li>The contents of the {@link #getCodeCacheDir()} directory</li>
     * <li>The contents of the {@link #getNoBackupFilesDir()} directory</li>
     * <li>The contents of the app's shared library directory</li>
     * </ul>
     *
     * <p>The default implementation of this method backs up the entirety of the
     * application's "owned" file system trees to the output other than the few exceptions
     * listed above.  Apps only need to override this method if they need to impose special
     * limitations on which files are being stored beyond the control that
     * {@link #getNoBackupFilesDir()} offers.
     * Alternatively they can provide an xml resource to specify what data to include or exclude.
     *
     *
     * @param data A structured wrapper pointing to the backup destination.
     * @throws IOException
     *
     * @see Context#getNoBackupFilesDir()
     * @see #fullBackupFile(File, FullBackupDataOutput)
     * @see #onRestoreFile(ParcelFileDescriptor, long, File, int, long, long)
     */
    public void onFullBackup(FullBackupDataOutput data) throws IOException {
        FullBackup.BackupScheme backupScheme = FullBackup.getBackupScheme(this,
                mBackupDestination);
        if (!backupScheme.isFullBackupEnabled(data.getTransportFlags())) {
            return;
        }

        IncludeExcludeRules includeExcludeRules;
        try {
            includeExcludeRules = getIncludeExcludeRules(backupScheme);
        } catch (IOException | XmlPullParserException e) {
            if (Log.isLoggable(FullBackup.TAG_XML_PARSER, Log.VERBOSE)) {
                Log.v(FullBackup.TAG_XML_PARSER,
                        "Exception trying to parse fullBackupContent xml file!"
                                + " Aborting full backup.", e);
            }
            return;
        }
        Map<String, Set<PathWithRequiredFlags>> manifestIncludeMap
                = includeExcludeRules.getIncludeMap();
        Set<PathWithRequiredFlags> manifestExcludeSet
                = includeExcludeRules.getExcludeSet();

        final String packageName = getPackageName();
        final ApplicationInfo appInfo = getApplicationInfo();

        // System apps have control over where their default storage context
        // is pointed, so we're always explicit when building paths.
        final Context ceContext = createCredentialProtectedStorageContext();
        final String rootDir = ceContext.getDataDir().getCanonicalPath();
        final String filesDir = ceContext.getFilesDir().getCanonicalPath();
        final String databaseDir = ceContext.getDatabasePath("foo").getParentFile()
                .getCanonicalPath();
        final String sharedPrefsDir = ceContext.getSharedPreferencesPath("foo").getParentFile()
                .getCanonicalPath();

        final Context deContext = createDeviceProtectedStorageContext();
        final String deviceRootDir = deContext.getDataDir().getCanonicalPath();
        final String deviceFilesDir = deContext.getFilesDir().getCanonicalPath();
        final String deviceDatabaseDir = deContext.getDatabasePath("foo").getParentFile()
                .getCanonicalPath();
        final String deviceSharedPrefsDir = deContext.getSharedPreferencesPath("foo")
                .getParentFile().getCanonicalPath();

        final String libDir = (appInfo.nativeLibraryDir != null)
                ? new File(appInfo.nativeLibraryDir).getCanonicalPath()
                : null;

        // Maintain a set of excluded directories so that as we traverse the tree we know we're not
        // going places we don't expect, and so the manifest includes can't take precedence over
        // what the framework decides is not to be included.
        final ArraySet<String> traversalExcludeSet = new ArraySet<String>();

        // Add the directories we always exclude.
        traversalExcludeSet.add(filesDir);
        traversalExcludeSet.add(databaseDir);
        traversalExcludeSet.add(sharedPrefsDir);

        traversalExcludeSet.add(deviceFilesDir);
        traversalExcludeSet.add(deviceDatabaseDir);
        traversalExcludeSet.add(deviceSharedPrefsDir);

        if (libDir != null) {
            traversalExcludeSet.add(libDir);
        }

        Set<String> extraExcludedDirs = getExtraExcludeDirsIfAny(ceContext);
        Set<String> extraExcludedDeviceDirs = getExtraExcludeDirsIfAny(deContext);
        traversalExcludeSet.addAll(extraExcludedDirs);
        traversalExcludeSet.addAll(extraExcludedDeviceDirs);

        // Root dir first.
        applyXmlFiltersAndDoFullBackupForDomain(
                packageName, FullBackup.ROOT_TREE_TOKEN, manifestIncludeMap,
                manifestExcludeSet, traversalExcludeSet, data);
        traversalExcludeSet.add(rootDir);
        // Exclude the extra directories anyway, since we've already covered them if it was needed.
        traversalExcludeSet.addAll(extraExcludedDirs);

        applyXmlFiltersAndDoFullBackupForDomain(
                packageName, FullBackup.DEVICE_ROOT_TREE_TOKEN, manifestIncludeMap,
                manifestExcludeSet, traversalExcludeSet, data);
        traversalExcludeSet.add(deviceRootDir);
        // Exclude the extra directories anyway, since we've already covered them if it was needed.
        traversalExcludeSet.addAll(extraExcludedDeviceDirs);

        // Data dir next.
        traversalExcludeSet.remove(filesDir);
        applyXmlFiltersAndDoFullBackupForDomain(
                packageName, FullBackup.FILES_TREE_TOKEN, manifestIncludeMap,
                manifestExcludeSet, traversalExcludeSet, data);
        traversalExcludeSet.add(filesDir);

        traversalExcludeSet.remove(deviceFilesDir);
        applyXmlFiltersAndDoFullBackupForDomain(
                packageName, FullBackup.DEVICE_FILES_TREE_TOKEN, manifestIncludeMap,
                manifestExcludeSet, traversalExcludeSet, data);
        traversalExcludeSet.add(deviceFilesDir);

        // Database directory.
        traversalExcludeSet.remove(databaseDir);
        applyXmlFiltersAndDoFullBackupForDomain(
                packageName, FullBackup.DATABASE_TREE_TOKEN, manifestIncludeMap,
                manifestExcludeSet, traversalExcludeSet, data);
        traversalExcludeSet.add(databaseDir);

        traversalExcludeSet.remove(deviceDatabaseDir);
        applyXmlFiltersAndDoFullBackupForDomain(
                packageName, FullBackup.DEVICE_DATABASE_TREE_TOKEN, manifestIncludeMap,
                manifestExcludeSet, traversalExcludeSet, data);
        traversalExcludeSet.add(deviceDatabaseDir);

        // SharedPrefs.
        traversalExcludeSet.remove(sharedPrefsDir);
        applyXmlFiltersAndDoFullBackupForDomain(
                packageName, FullBackup.SHAREDPREFS_TREE_TOKEN, manifestIncludeMap,
                manifestExcludeSet, traversalExcludeSet, data);
        traversalExcludeSet.add(sharedPrefsDir);

        traversalExcludeSet.remove(deviceSharedPrefsDir);
        applyXmlFiltersAndDoFullBackupForDomain(
                packageName, FullBackup.DEVICE_SHAREDPREFS_TREE_TOKEN, manifestIncludeMap,
                manifestExcludeSet, traversalExcludeSet, data);
        traversalExcludeSet.add(deviceSharedPrefsDir);

        // getExternalFilesDir() location associated with this app.  Technically there should
        // not be any files here if the app does not properly have permission to access
        // external storage, but edge cases happen. fullBackupFileTree() catches
        // IOExceptions and similar, and treats them as non-fatal, so we rely on that; and
        // we know a priori that processes running as the system UID are not permitted to
        // access external storage, so we check for that as well to avoid nastygrams in
        // the log.
        if (Process.myUid() != Process.SYSTEM_UID) {
            File efLocation = getExternalFilesDir(null);
            if (efLocation != null) {
                applyXmlFiltersAndDoFullBackupForDomain(
                        packageName, FullBackup.MANAGED_EXTERNAL_TREE_TOKEN, manifestIncludeMap,
                        manifestExcludeSet, traversalExcludeSet, data);
            }

        }
    }

    private Set<String> getExtraExcludeDirsIfAny(Context context) throws IOException {
        Set<String> excludedDirs = new HashSet<>();
        excludedDirs.add(context.getCacheDir().getCanonicalPath());
        excludedDirs.add(context.getCodeCacheDir().getCanonicalPath());
        excludedDirs.add(context.getNoBackupFilesDir().getCanonicalPath());
        return Collections.unmodifiableSet(excludedDirs);
    }

    /** @hide */
    @VisibleForTesting
    public IncludeExcludeRules getIncludeExcludeRules(FullBackup.BackupScheme backupScheme)
            throws IOException, XmlPullParserException {
        Map<String, Set<PathWithRequiredFlags>> manifestIncludeMap;
        ArraySet<PathWithRequiredFlags> manifestExcludeSet;

        manifestIncludeMap =
                backupScheme.maybeParseAndGetCanonicalIncludePaths();
        manifestExcludeSet = backupScheme.maybeParseAndGetCanonicalExcludePaths();

        return new IncludeExcludeRules(manifestIncludeMap, manifestExcludeSet);
    }

    /**
     * Notification that the application's current backup operation causes it to exceed
     * the maximum size permitted by the transport.  The ongoing backup operation is
     * halted and rolled back: any data that had been stored by a previous backup operation
     * is still intact.  Typically the quota-exceeded state will be detected before any data
     * is actually transmitted over the network.
     *
     * <p>The {@code quotaBytes} value is the total data size currently permitted for this
     * application.  If desired, the application can use this as a hint for determining
     * how much data to store.  For example, a messaging application might choose to
     * store only the newest messages, dropping enough older content to stay under
     * the quota.
     *
     * <p class="note">Note that the maximum quota for the application can change over
     * time.  In particular, in the future the quota may grow.  Applications that adapt
     * to the quota when deciding what data to store should be aware of this and implement
     * their data storage mechanisms in a way that can take advantage of additional
     * quota.
     *
     * @param backupDataBytes The amount of data measured while initializing the backup
     *    operation, if the total exceeds the app's alloted quota.  If initial measurement
     *    suggested that the data would fit but then too much data was actually submitted
     *    as part of the operation, then this value is the amount of data that had been
     *    streamed into the transport at the time the quota was reached.
     * @param quotaBytes The maximum data size that the transport currently permits
     *    this application to store as a backup.
     */
    public void onQuotaExceeded(long backupDataBytes, long quotaBytes) {
    }

    private int getBackupUserId() {
        return mUser == null ? super.getUserId() : mUser.getIdentifier();
    }

    /**
     * Check whether the xml yielded any <include/> tag for the provided <code>domainToken</code>.
     * If so, perform a {@link #fullBackupFileTree} which backs up the file or recurses if the path
     * is a directory, but only if all the required flags of the include rule are satisfied by
     * the transport.
     */
    private void applyXmlFiltersAndDoFullBackupForDomain(String packageName, String domainToken,
            Map<String, Set<PathWithRequiredFlags>> includeMap,
            Set<PathWithRequiredFlags> filterSet, ArraySet<String> traversalExcludeSet,
            FullBackupDataOutput data) throws IOException {
        if (includeMap == null || includeMap.size() == 0) {
            // Do entire sub-tree for the provided token.
            fullBackupFileTree(packageName, domainToken,
                    FullBackup.getBackupScheme(this, mBackupDestination)
                            .tokenToDirectoryPath(domainToken),
                    filterSet, traversalExcludeSet, data);
        } else if (includeMap.get(domainToken) != null) {
            // This will be null if the xml parsing didn't yield any rules for
            // this domain (there may still be rules for other domains).
            for (PathWithRequiredFlags includeFile : includeMap.get(domainToken)) {
                if (areIncludeRequiredTransportFlagsSatisfied(includeFile.getRequiredFlags(),
                        data.getTransportFlags())) {
                    fullBackupFileTree(packageName, domainToken, includeFile.getPath(), filterSet,
                            traversalExcludeSet, data);
                }
            }
        }
    }

    private boolean areIncludeRequiredTransportFlagsSatisfied(int includeFlags,
            int transportFlags) {
        // all bits that are set in includeFlags must also be set in transportFlags
        return (transportFlags & includeFlags) == includeFlags;
    }

    /**
     * Write an entire file as part of a full-backup operation.  The file's contents
     * will be delivered to the backup destination along with the metadata necessary
     * to place it with the proper location and permissions on the device where the
     * data is restored.
     *
     * <p class="note">Attempting to back up files in directories that are ignored by
     * the backup system will have no effect.  For example, if the app calls this method
     * with a file inside the {@link #getNoBackupFilesDir()} directory, it will be ignored.
     * See {@link #onFullBackup(FullBackupDataOutput)} for details on what directories
     * are excluded from backups.
     *
     * @param file The file to be backed up.  The file must exist and be readable by
     *     the caller.
     * @param output The destination to which the backed-up file data will be sent.
     */
    public final void fullBackupFile(File file, FullBackupDataOutput output) {
        // Look up where all of our various well-defined dir trees live on this device
        final String rootDir;
        final String filesDir;
        final String nbFilesDir;
        final String dbDir;
        final String spDir;
        final String cacheDir;
        final String codeCacheDir;
        final String deviceRootDir;
        final String deviceFilesDir;
        final String deviceNbFilesDir;
        final String deviceDbDir;
        final String deviceSpDir;
        final String deviceCacheDir;
        final String deviceCodeCacheDir;
        final String libDir;

        String efDir = null;
        String filePath;

        ApplicationInfo appInfo = getApplicationInfo();

        try {
            // System apps have control over where their default storage context
            // is pointed, so we're always explicit when building paths.
            final Context ceContext = createCredentialProtectedStorageContext();
            rootDir = ceContext.getDataDir().getCanonicalPath();
            filesDir = ceContext.getFilesDir().getCanonicalPath();
            nbFilesDir = ceContext.getNoBackupFilesDir().getCanonicalPath();
            dbDir = ceContext.getDatabasePath("foo").getParentFile().getCanonicalPath();
            spDir = ceContext.getSharedPreferencesPath("foo").getParentFile().getCanonicalPath();
            cacheDir = ceContext.getCacheDir().getCanonicalPath();
            codeCacheDir = ceContext.getCodeCacheDir().getCanonicalPath();

            final Context deContext = createDeviceProtectedStorageContext();
            deviceRootDir = deContext.getDataDir().getCanonicalPath();
            deviceFilesDir = deContext.getFilesDir().getCanonicalPath();
            deviceNbFilesDir = deContext.getNoBackupFilesDir().getCanonicalPath();
            deviceDbDir = deContext.getDatabasePath("foo").getParentFile().getCanonicalPath();
            deviceSpDir = deContext.getSharedPreferencesPath("foo").getParentFile()
                    .getCanonicalPath();
            deviceCacheDir = deContext.getCacheDir().getCanonicalPath();
            deviceCodeCacheDir = deContext.getCodeCacheDir().getCanonicalPath();

            libDir = (appInfo.nativeLibraryDir == null)
                    ? null
                    : new File(appInfo.nativeLibraryDir).getCanonicalPath();

            // may or may not have external files access to attempt backup/restore there
            if (Process.myUid() != Process.SYSTEM_UID) {
                File efLocation = getExternalFilesDir(null);
                if (efLocation != null) {
                    efDir = efLocation.getCanonicalPath();
                }
            }

            // Now figure out which well-defined tree the file is placed in, working from
            // most to least specific.  We also specifically exclude the lib, cache,
            // and code_cache dirs.
            filePath = file.getCanonicalPath();
        } catch (IOException e) {
            Log.w(TAG, "Unable to obtain canonical paths");
            return;
        }

        if (filePath.startsWith(cacheDir)
                || filePath.startsWith(codeCacheDir)
                || filePath.startsWith(nbFilesDir)
                || filePath.startsWith(deviceCacheDir)
                || filePath.startsWith(deviceCodeCacheDir)
                || filePath.startsWith(deviceNbFilesDir)
                || filePath.startsWith(libDir)) {
            Log.w(TAG, "lib, cache, code_cache, and no_backup files are not backed up");
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
            domain = FullBackup.FILES_TREE_TOKEN;
            rootpath = filesDir;
        } else if (filePath.startsWith(rootDir)) {
            domain = FullBackup.ROOT_TREE_TOKEN;
            rootpath = rootDir;
        } else if (filePath.startsWith(deviceDbDir)) {
            domain = FullBackup.DEVICE_DATABASE_TREE_TOKEN;
            rootpath = deviceDbDir;
        } else if (filePath.startsWith(deviceSpDir)) {
            domain = FullBackup.DEVICE_SHAREDPREFS_TREE_TOKEN;
            rootpath = deviceSpDir;
        } else if (filePath.startsWith(deviceFilesDir)) {
            domain = FullBackup.DEVICE_FILES_TREE_TOKEN;
            rootpath = deviceFilesDir;
        } else if (filePath.startsWith(deviceRootDir)) {
            domain = FullBackup.DEVICE_ROOT_TREE_TOKEN;
            rootpath = deviceRootDir;
        } else if ((efDir != null) && filePath.startsWith(efDir)) {
            domain = FullBackup.MANAGED_EXTERNAL_TREE_TOKEN;
            rootpath = efDir;
        } else {
            Log.w(TAG, "File " + filePath + " is in an unsupported location; skipping");
            return;
        }

        // And now that we know where it lives, semantically, back it up appropriately
        // In the measurement case, backupToTar() updates the size in output and returns
        // without transmitting any file data.
        if (DEBUG) Log.i(TAG, "backupFile() of " + filePath + " => domain=" + domain
                + " rootpath=" + rootpath);

        FullBackup.backupToTar(getPackageName(), domain, null, rootpath, filePath, output);
    }

    /**
     * Scan the dir tree (if it actually exists) and process each entry we find.  If the
     * 'excludes' parameters are non-null, they are consulted each time a new file system entity
     * is visited to see whether that entity (and its subtree, if appropriate) should be
     * omitted from the backup process.
     *
     * @param systemExcludes An optional list of excludes.
     * @hide
     */
    protected final void fullBackupFileTree(String packageName, String domain, String startingPath,
                                            Set<PathWithRequiredFlags> manifestExcludes,
                                            ArraySet<String> systemExcludes,
            FullBackupDataOutput output) {
        // Pull out the domain and set it aside to use when making the tarball.
        String domainPath = FullBackup.getBackupScheme(this, mBackupDestination)
                .tokenToDirectoryPath(domain);
        if (domainPath == null) {
            // Should never happen.
            return;
        }

        File rootFile = new File(startingPath);
        if (rootFile.exists()) {
            LinkedList<File> scanQueue = new LinkedList<File>();
            scanQueue.add(rootFile);

            while (scanQueue.size() > 0) {
                File file = scanQueue.remove(0);
                String filePath;
                try {
                    // Ignore things that aren't "real" files or dirs
                    StructStat stat = Os.lstat(file.getPath());
                    if (!OsConstants.S_ISREG(stat.st_mode)
                            && !OsConstants.S_ISDIR(stat.st_mode)) {
                        if (DEBUG) Log.i(TAG, "Not a file/dir (skipping)!: " + file);
                        continue;
                    }

                    // For all other verification, look at the canonicalized path
                    filePath = file.getCanonicalPath();

                    // prune this subtree?
                    if (manifestExcludes != null
                            && manifestExcludesContainFilePath(manifestExcludes, filePath)) {
                        continue;
                    }
                    if (systemExcludes != null && systemExcludes.contains(filePath)) {
                        continue;
                    }

                    // If it's a directory, enqueue its contents for scanning.
                    if (OsConstants.S_ISDIR(stat.st_mode)) {
                        File[] contents = file.listFiles();
                        if (contents != null) {
                            for (File entry : contents) {
                                scanQueue.add(0, entry);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (DEBUG) Log.w(TAG, "Error canonicalizing path of " + file);
                    if (Log.isLoggable(FullBackup.TAG_XML_PARSER, Log.VERBOSE)) {
                        Log.v(FullBackup.TAG_XML_PARSER, "Error canonicalizing path of " + file);
                    }
                    continue;
                } catch (ErrnoException e) {
                    if (DEBUG) Log.w(TAG, "Error scanning file " + file + " : " + e);
                    if (Log.isLoggable(FullBackup.TAG_XML_PARSER, Log.VERBOSE)) {
                        Log.v(FullBackup.TAG_XML_PARSER, "Error scanning file " + file + " : " + e);
                    }
                    continue;
                }

                // Finally, back this file up (or measure it) before proceeding
                FullBackup.backupToTar(packageName, domain, null, domainPath, filePath, output);
            }
        }
    }

    private boolean manifestExcludesContainFilePath(
        Set<PathWithRequiredFlags> manifestExcludes, String filePath) {
        for (PathWithRequiredFlags exclude : manifestExcludes) {
            String excludePath = exclude.getPath();
            if (excludePath != null && excludePath.equals(filePath)) {
                return true;
            }
        }
        return false;
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

        final boolean accept = isFileEligibleForRestore(destination);
        // If we don't accept the file, consume the bytes from the pipe anyway.
        FullBackup.restoreFile(data, size, type, mode, mtime, accept ? destination : null);
    }

    private boolean isFileEligibleForRestore(File destination) throws IOException {
        FullBackup.BackupScheme bs = FullBackup.getBackupScheme(this, mBackupDestination);
        if (!bs.isFullRestoreEnabled()) {
            if (Log.isLoggable(FullBackup.TAG_XML_PARSER, Log.VERBOSE)) {
                Log.v(FullBackup.TAG_XML_PARSER,
                        "onRestoreFile \"" + destination.getCanonicalPath()
                                + "\" : fullBackupContent not enabled for " + getPackageName());
            }
            return false;
        }

        Map<String, Set<PathWithRequiredFlags>> includes = null;
        ArraySet<PathWithRequiredFlags> excludes = null;
        final String destinationCanonicalPath = destination.getCanonicalPath();
        try {
            includes = bs.maybeParseAndGetCanonicalIncludePaths();
            excludes = bs.maybeParseAndGetCanonicalExcludePaths();
        } catch (XmlPullParserException e) {
            if (Log.isLoggable(FullBackup.TAG_XML_PARSER, Log.VERBOSE)) {
                Log.v(FullBackup.TAG_XML_PARSER,
                        "onRestoreFile \"" + destinationCanonicalPath
                                + "\" : Exception trying to parse fullBackupContent xml file!"
                                + " Aborting onRestoreFile.", e);
            }
            return false;
        }

        if (excludes != null &&
                BackupUtils.isFileSpecifiedInPathList(destination, excludes)) {
            if (Log.isLoggable(FullBackup.TAG_XML_PARSER, Log.VERBOSE)) {
                Log.v(FullBackup.TAG_XML_PARSER,
                        "onRestoreFile: \"" + destinationCanonicalPath + "\": listed in"
                                + " excludes; skipping.");
            }
            return false;
        }

        if (includes != null && !includes.isEmpty()) {
            // Rather than figure out the <include/> domain based on the path (a lot of code, and
            // it's a small list), we'll go through and look for it.
            boolean explicitlyIncluded = false;
            for (Set<PathWithRequiredFlags> domainIncludes : includes.values()) {
                explicitlyIncluded |=
                        BackupUtils.isFileSpecifiedInPathList(destination, domainIncludes);
                if (explicitlyIncluded) {
                    break;
                }
            }
            if (!explicitlyIncluded) {
                if (Log.isLoggable(FullBackup.TAG_XML_PARSER, Log.VERBOSE)) {
                    Log.v(FullBackup.TAG_XML_PARSER,
                            "onRestoreFile: Trying to restore \""
                                    + destinationCanonicalPath + "\" but it isn't specified"
                                    + " in the included files; skipping.");
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Only specialized platform agents should overload this entry point to support
     * restores to non-app locations.
     * @hide
     */
    protected void onRestoreFile(ParcelFileDescriptor data, long size,
            int type, String domain, String path, long mode, long mtime)
            throws IOException {
        String basePath = null;

        if (DEBUG) Log.d(TAG, "onRestoreFile() size=" + size + " type=" + type
                + " domain=" + domain + " relpath=" + path + " mode=" + mode
                + " mtime=" + mtime);

        basePath = FullBackup.getBackupScheme(this, mBackupDestination).tokenToDirectoryPath(
                domain);
        if (domain.equals(FullBackup.MANAGED_EXTERNAL_TREE_TOKEN)) {
            mode = -1;  // < 0 is a token to skip attempting a chmod()
        }

        // Now that we've figured out where the data goes, send it on its way
        if (basePath != null) {
            // Canonicalize the nominal path and verify that it lies within the stated domain
            File outFile = new File(basePath, path);
            String outPath = outFile.getCanonicalPath();
            if (outPath.startsWith(basePath + File.separatorChar)) {
                if (DEBUG) Log.i(TAG, "[" + domain + " : " + path + "] mapped to " + outPath);
                onRestoreFile(data, size, outFile, type, mode, mtime);
                return;
            } else {
                // Attempt to restore to a path outside the file's nominal domain.
                if (DEBUG) {
                    Log.e(TAG, "Cross-domain restore attempt: " + outPath);
                }
            }
        }

        // Not a supported output location, or bad path:  we need to consume the data
        // anyway, so just use the default "copy the data out" implementation
        // with a null destination.
        if (DEBUG) Log.i(TAG, "[ skipping file " + path + "]");
        FullBackup.restoreFile(data, size, type, mode, mtime, null);
    }

    /**
     * The application's restore operation has completed.  This method is called after
     * all available data has been delivered to the application for restore (via either
     * the {@link #onRestore(BackupDataInput, int, ParcelFileDescriptor) onRestore()} or
     * {@link #onRestoreFile(ParcelFileDescriptor, long, File, int, long, long) onRestoreFile()}
     * callbacks).  This provides the app with a stable end-of-restore opportunity to
     * perform any appropriate post-processing on the data that was just delivered.
     *
     * @see #onRestore(BackupDataInput, int, ParcelFileDescriptor)
     * @see #onRestoreFile(ParcelFileDescriptor, long, File, int, long, long)
     */
    public void onRestoreFinished() {
    }

    /**
     * Clears all pending logs currently stored in the agent's event logger.
     *
     * @hide
     */
    @VisibleForTesting
    public final void clearBackupRestoreEventLogger() {
        if (mLogger != null) {
            mLogger.clearData();
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
        public void doBackup(
                ParcelFileDescriptor oldState,
                ParcelFileDescriptor data,
                ParcelFileDescriptor newState,
                long quotaBytes,
                IBackupCallback callbackBinder,
                int transportFlags) throws RemoteException {
            if (DEBUG) Log.v(TAG, "doBackup() invoked");

            BackupDataOutput output = new BackupDataOutput(
                    data.getFileDescriptor(), quotaBytes, transportFlags);

            long result = RESULT_ERROR;

            // Ensure that we're running with the app's normal permission level
            final long ident = Binder.clearCallingIdentity();
            try {
                BackupAgent.this.onBackup(oldState, output, newState);
                result = RESULT_SUCCESS;
            } catch (IOException ex) {
                Log.d(TAG, "onBackup (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw new RuntimeException(ex);
            } catch (RuntimeException ex) {
                Log.d(TAG, "onBackup (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw ex;
            } finally {
                // Ensure that any SharedPreferences writes have landed after the backup,
                // in case the app code has side effects (since apps cannot provide this
                // guarantee themselves).
                waitForSharedPrefs();

                Binder.restoreCallingIdentity(ident);
                try {
                    callbackBinder.operationComplete(result);
                } catch (RemoteException e) {
                    // We will time out anyway.
                }

                // Don't close the fd out from under the system service if this was local
                if (Binder.getCallingPid() != Process.myPid()) {
                    IoUtils.closeQuietly(oldState);
                    IoUtils.closeQuietly(data);
                    IoUtils.closeQuietly(newState);
                }
            }
        }

        @Override
        public void doRestore(ParcelFileDescriptor data, long appVersionCode,
                ParcelFileDescriptor newState, int token, IBackupManager callbackBinder)
                throws RemoteException {
            doRestoreInternal(data, appVersionCode, newState, token, callbackBinder,
                    /* excludedKeys */ null);
        }

        @Override
        public void doRestoreWithExcludedKeys(ParcelFileDescriptor data, long appVersionCode,
                ParcelFileDescriptor newState, int token, IBackupManager callbackBinder,
                List<String> excludedKeys) throws RemoteException {
            doRestoreInternal(data, appVersionCode, newState, token, callbackBinder, excludedKeys);
        }

        private void doRestoreInternal(ParcelFileDescriptor data, long appVersionCode,
                ParcelFileDescriptor newState, int token, IBackupManager callbackBinder,
                List<String> excludedKeys) throws RemoteException {
            if (DEBUG) Log.v(TAG, "doRestore() invoked");

            // Ensure that any side-effect SharedPreferences writes have landed *before*
            // we may be about to rewrite the file out from underneath
            waitForSharedPrefs();

            BackupDataInput input = new BackupDataInput(data.getFileDescriptor());

            // Ensure that we're running with the app's normal permission level
            final long ident = Binder.clearCallingIdentity();
            try {
                BackupAgent.this.onRestore(input, appVersionCode, newState,
                        excludedKeys != null ? new HashSet<>(excludedKeys)
                                : Collections.emptySet());
            } catch (IOException ex) {
                Log.d(TAG, "onRestore (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw new RuntimeException(ex);
            } catch (RuntimeException ex) {
                Log.d(TAG, "onRestore (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw ex;
            } finally {
                // And bring live SharedPreferences instances up to date
                reloadSharedPreferences();

                Binder.restoreCallingIdentity(ident);
                try {
                    callbackBinder.opCompleteForUser(getBackupUserId(), token, 0);
                } catch (RemoteException e) {
                    // we'll time out anyway, so we're safe
                }

                if (Binder.getCallingPid() != Process.myPid()) {
                    IoUtils.closeQuietly(data);
                    IoUtils.closeQuietly(newState);
                }
            }
        }

        @Override
        public void doFullBackup(ParcelFileDescriptor data,
                long quotaBytes, int token, IBackupManager callbackBinder, int transportFlags) {
            if (DEBUG) Log.v(TAG, "doFullBackup() invoked");

            // Ensure that any SharedPreferences writes have landed *before*
            // we potentially try to back up the underlying files directly.
            waitForSharedPrefs();

            // Ensure that we're running with the app's normal permission level
            final long ident = Binder.clearCallingIdentity();
            try {
                BackupAgent.this.onFullBackup(new FullBackupDataOutput(
                        data, quotaBytes, transportFlags));
            } catch (IOException ex) {
                Log.d(TAG, "onFullBackup (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw new RuntimeException(ex);
            } catch (RuntimeException ex) {
                Log.d(TAG, "onFullBackup (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw ex;
            } finally {
                // ... and then again after, as in the doBackup() case
                waitForSharedPrefs();

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
                    callbackBinder.opCompleteForUser(getBackupUserId(), token, 0);
                } catch (RemoteException e) {
                    // we'll time out anyway, so we're safe
                }

                if (Binder.getCallingPid() != Process.myPid()) {
                    IoUtils.closeQuietly(data);
                }
            }
        }

        public void doMeasureFullBackup(long quotaBytes, int token, IBackupManager callbackBinder,
                int transportFlags) {
            FullBackupDataOutput measureOutput =
                    new FullBackupDataOutput(quotaBytes, transportFlags);

            waitForSharedPrefs();

            // Ensure that we're running with the app's normal permission level
            final long ident = Binder.clearCallingIdentity();
            try {
                BackupAgent.this.onFullBackup(measureOutput);
            } catch (IOException ex) {
                Log.d(TAG, "onFullBackup[M] (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw new RuntimeException(ex);
            } catch (RuntimeException ex) {
                Log.d(TAG, "onFullBackup[M] (" + BackupAgent.this.getClass().getName() + ") threw", ex);
                throw ex;
            } finally {
                Binder.restoreCallingIdentity(ident);
                try {
                    callbackBinder.opCompleteForUser(getBackupUserId(), token,
                            measureOutput.getSize());
                } catch (RemoteException e) {
                    // timeout, so we're safe
                }
            }
        }

        @Override
        public void doRestoreFile(ParcelFileDescriptor data, long size,
                int type, String domain, String path, long mode, long mtime,
                int token, IBackupManager callbackBinder) throws RemoteException {
            final long ident = Binder.clearCallingIdentity();
            try {
                BackupAgent.this.onRestoreFile(data, size, type, domain, path, mode, mtime);
            } catch (IOException e) {
                Log.d(TAG, "onRestoreFile (" + BackupAgent.this.getClass().getName() + ") threw", e);
                throw new RuntimeException(e);
            } finally {
                // Ensure that any side-effect SharedPreferences writes have landed
                waitForSharedPrefs();
                // And bring live SharedPreferences instances up to date
                reloadSharedPreferences();

                // It's possible that onRestoreFile was overridden and that the agent did not
                // consume all the data for this file from the pipe. We need to clear the pipe,
                // otherwise the framework can get stuck trying to write to a full pipe or
                // onRestoreFile could be called with the previous file's data left in the pipe.
                if (Flags.enableClearPipeAfterRestoreFile()) {
                    clearUnconsumedDataFromPipe(data, size);
                }

                Binder.restoreCallingIdentity(ident);
                try {
                    callbackBinder.opCompleteForUser(getBackupUserId(), token, 0);
                } catch (RemoteException e) {
                    // we'll time out anyway, so we're safe
                }

                if (Binder.getCallingPid() != Process.myPid()) {
                    IoUtils.closeQuietly(data);
                }
            }
        }

        private static void clearUnconsumedDataFromPipe(ParcelFileDescriptor data, long size) {
            try (FileInputStream in = new FileInputStream(data.getFileDescriptor())) {
                if (in.available() > 0) {
                    in.skip(size);
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to clear unconsumed data from pipe.", e);
            }
        }

        @Override
        public void doRestoreFinished(int token, IBackupManager callbackBinder) {
            final long ident = Binder.clearCallingIdentity();
            try {
                BackupAgent.this.onRestoreFinished();
            } catch (Exception e) {
                Log.d(TAG, "onRestoreFinished (" + BackupAgent.this.getClass().getName() + ") threw", e);
                throw e;
            } finally {
                // Ensure that any side-effect SharedPreferences writes have landed
                waitForSharedPrefs();

                Binder.restoreCallingIdentity(ident);
                try {
                    callbackBinder.opCompleteForUser(getBackupUserId(), token, 0);
                } catch (RemoteException e) {
                    // we'll time out anyway, so we're safe
                }
            }
        }

        @Override
        public void fail(String message) {
            getHandler().post(new FailRunnable(message));
        }

        @Override
        public void doQuotaExceeded(
                long backupDataBytes,
                long quotaBytes,
                IBackupCallback callbackBinder) {
            long result = RESULT_ERROR;

            // Ensure that we're running with the app's normal permission level
            final long ident = Binder.clearCallingIdentity();
            try {
                BackupAgent.this.onQuotaExceeded(backupDataBytes, quotaBytes);
                result = RESULT_SUCCESS;
            } catch (Exception e) {
                Log.d(TAG, "onQuotaExceeded(" + BackupAgent.this.getClass().getName() + ") threw",
                        e);
                throw e;
            } finally {
                waitForSharedPrefs();
                Binder.restoreCallingIdentity(ident);

                try {
                    callbackBinder.operationComplete(result);
                } catch (RemoteException e) {
                    // We will time out anyway.
                }
            }
        }

        @Override
        public void getLoggerResults(
                AndroidFuture<List<BackupRestoreEventLogger.DataTypeResult>> in) {
            if (mLogger != null) {
                in.complete(mLogger.getLoggingResults());
            } else {
                in.complete(Collections.emptyList());
            }
        }

        @Override
        public void getOperationType(
                AndroidFuture<Integer> in) {
            in.complete(mLogger == null ? OperationType.UNKNOWN : mLogger.getOperationType());
        }

        @Override
        public void clearBackupRestoreEventLogger() {
            final long ident = Binder.clearCallingIdentity();
            try {
                BackupAgent.this.clearBackupRestoreEventLogger();
            } catch (Exception e) {
                Log.d(TAG, "clearBackupRestoreEventLogger (" + BackupAgent.this.getClass().getName()
                        + ") threw", e);
                throw e;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    static class FailRunnable implements Runnable {
        private String mMessage;

        FailRunnable(String message) {
            mMessage = message;
        }

        @Override
        public void run() {
            throw new IllegalStateException(mMessage);
        }
    }

    /**  @hide */
    @VisibleForTesting
    public static class IncludeExcludeRules {
        private final Map<String, Set<PathWithRequiredFlags>> mManifestIncludeMap;
        private final Set<PathWithRequiredFlags> mManifestExcludeSet;

        /** @hide */
        public IncludeExcludeRules(
                Map<String, Set<PathWithRequiredFlags>> manifestIncludeMap,
                Set<PathWithRequiredFlags> manifestExcludeSet) {
            mManifestIncludeMap = manifestIncludeMap;
            mManifestExcludeSet = manifestExcludeSet;
        }

        /**  @hide */
        @VisibleForTesting
        public static IncludeExcludeRules emptyRules() {
            return new IncludeExcludeRules(Collections.emptyMap(), new ArraySet<>());
        }

        private Map<String, Set<PathWithRequiredFlags>> getIncludeMap() {
            return mManifestIncludeMap;
        }

        private Set<PathWithRequiredFlags> getExcludeSet() {
            return mManifestExcludeSet;
        }

        /**  @hide */
        @Override
        public int hashCode() {
            return Objects.hash(mManifestIncludeMap, mManifestExcludeSet);
        }

        /**  @hide */
        @Override
        public boolean equals(@Nullable Object object) {
            if (this == object) {
                return true;
            }
            if (object == null || getClass() != object.getClass()) {
                return false;
            }
            IncludeExcludeRules that = (IncludeExcludeRules) object;
            return Objects.equals(mManifestIncludeMap, that.mManifestIncludeMap) &&
                    Objects.equals(mManifestExcludeSet, that.mManifestExcludeSet);
        }
    }
}
