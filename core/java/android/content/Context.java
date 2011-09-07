/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection.OnScanCompletedListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Interface to global information about an application environment.  This is
 * an abstract class whose implementation is provided by
 * the Android system.  It
 * allows access to application-specific resources and classes, as well as
 * up-calls for application-level operations such as launching activities,
 * broadcasting and receiving intents, etc.
 */
public abstract class Context {
    /**
     * File creation mode: the default mode, where the created file can only
     * be accessed by the calling application (or all applications sharing the
     * same user ID).
     * @see #MODE_WORLD_READABLE
     * @see #MODE_WORLD_WRITEABLE
     */
    public static final int MODE_PRIVATE = 0x0000;
    /**
     * File creation mode: allow all other applications to have read access
     * to the created file.
     * @see #MODE_PRIVATE
     * @see #MODE_WORLD_WRITEABLE
     */
    public static final int MODE_WORLD_READABLE = 0x0001;
    /**
     * File creation mode: allow all other applications to have write access
     * to the created file.
     * @see #MODE_PRIVATE
     * @see #MODE_WORLD_READABLE
     */
    public static final int MODE_WORLD_WRITEABLE = 0x0002;
    /**
     * File creation mode: for use with {@link #openFileOutput}, if the file
     * already exists then write data to the end of the existing file
     * instead of erasing it.
     * @see #openFileOutput
     */
    public static final int MODE_APPEND = 0x8000;

    /**
     * SharedPreference loading flag: when set, the file on disk will
     * be checked for modification even if the shared preferences
     * instance is already loaded in this process.  This behavior is
     * sometimes desired in cases where the application has multiple
     * processes, all writing to the same SharedPreferences file.
     * Generally there are better forms of communication between
     * processes, though.
     *
     * <p>This was the legacy (but undocumented) behavior in and
     * before Gingerbread (Android 2.3) and this flag is implied when
     * targetting such releases.  For applications targetting SDK
     * versions <em>greater than</em> Android 2.3, this flag must be
     * explicitly set if desired.
     *
     * @see #getSharedPreferences
     */
    public static final int MODE_MULTI_PROCESS = 0x0004;

    /**
     * Flag for {@link #bindService}: automatically create the service as long
     * as the binding exists.  Note that while this will create the service,
     * its {@link android.app.Service#onStartCommand}
     * method will still only be called due to an
     * explicit call to {@link #startService}.  Even without that, though,
     * this still provides you with access to the service object while the
     * service is created.
     *
     * <p>Note that prior to {@link android.os.Build.VERSION_CODES#ICE_CREAM_SANDWICH},
     * not supplying this flag would also impact how important the system
     * consider's the target service's process to be.  When set, the only way
     * for it to be raised was by binding from a service in which case it will
     * only be important when that activity is in the foreground.  Now to
     * achieve this behavior you must explicitly supply the new flag
     * {@link #BIND_ADJUST_WITH_ACTIVITY}.  For compatibility, old applications
     * that don't specify {@link #BIND_AUTO_CREATE} will automatically have
     * the flags {@link #BIND_WAIVE_PRIORITY} and
     * {@link #BIND_ADJUST_WITH_ACTIVITY} set for them in order to achieve
     * the same result.
     */
    public static final int BIND_AUTO_CREATE = 0x0001;

    /**
     * Flag for {@link #bindService}: include debugging help for mismatched
     * calls to unbind.  When this flag is set, the callstack of the following
     * {@link #unbindService} call is retained, to be printed if a later
     * incorrect unbind call is made.  Note that doing this requires retaining
     * information about the binding that was made for the lifetime of the app,
     * resulting in a leak -- this should only be used for debugging.
     */
    public static final int BIND_DEBUG_UNBIND = 0x0002;

    /**
     * Flag for {@link #bindService}: don't allow this binding to raise
     * the target service's process to the foreground scheduling priority.
     * It will still be raised to at least the same memory priority
     * as the client (so that its process will not be killable in any
     * situation where the client is not killable), but for CPU scheduling
     * purposes it may be left in the background.  This only has an impact
     * in the situation where the binding client is a foreground process
     * and the target service is in a background process.
     */
    public static final int BIND_NOT_FOREGROUND = 0x0004;

    /**
     * Flag for {@link #bindService}: indicates that the client application
     * binding to this service considers the service to be more important than
     * the app itself.  When set, the platform will try to have the out of
     * memory kill the app before it kills the service it is bound to, though
     * this is not guaranteed to be the case.
     */
    public static final int BIND_ABOVE_CLIENT = 0x0008;

    /**
     * Flag for {@link #bindService}: allow the process hosting the bound
     * service to go through its normal memory management.  It will be
     * treated more like a running service, allowing the system to
     * (temporarily) expunge the process if low on memory or for some other
     * whim it may have, and being more aggressive about making it a candidate
     * to be killed (and restarted) if running for a long time.
     */
    public static final int BIND_ALLOW_OOM_MANAGEMENT = 0x0010;

    /**
     * Flag for {@link #bindService}: don't impact the scheduling or
     * memory management priority of the target service's hosting process.
     * Allows the service's process to be managed on the background LRU list
     * just like a regular application process in the background.
     */
    public static final int BIND_WAIVE_PRIORITY = 0x0020;

    /**
     * Flag for {@link #bindService}: this service is very important to
     * the client, so should be brought to the foreground process level
     * when the client is.  Normally a process can only be raised to the
     * visibility level by a client, even if that client is in the foreground.
     */
    public static final int BIND_IMPORTANT = 0x0040;

    /**
     * Flag for {@link #bindService}: If binding from an activity, allow the
     * target service's process importance to be raised based on whether the
     * activity is visible to the user, regardless whether another flag is
     * used to reduce the amount that the client process's overall importance
     * is used to impact it.
     */
    public static final int BIND_ADJUST_WITH_ACTIVITY = 0x0040;

    /** Return an AssetManager instance for your application's package. */
    public abstract AssetManager getAssets();

    /** Return a Resources instance for your application's package. */
    public abstract Resources getResources();

    /** Return PackageManager instance to find global package information. */
    public abstract PackageManager getPackageManager();

    /** Return a ContentResolver instance for your application's package. */
    public abstract ContentResolver getContentResolver();

    /**
     * Return the Looper for the main thread of the current process.  This is
     * the thread used to dispatch calls to application components (activities,
     * services, etc).
     */
    public abstract Looper getMainLooper();

    /**
     * Return the context of the single, global Application object of the
     * current process.  This generally should only be used if you need a
     * Context whose lifecycle is separate from the current context, that is
     * tied to the lifetime of the process rather than the current component.
     *
     * <p>Consider for example how this interacts with
     * {@link #registerReceiver(BroadcastReceiver, IntentFilter)}:
     * <ul>
     * <li> <p>If used from an Activity context, the receiver is being registered
     * within that activity.  This means that you are expected to unregister
     * before the activity is done being destroyed; in fact if you do not do
     * so, the framework will clean up your leaked registration as it removes
     * the activity and log an error.  Thus, if you use the Activity context
     * to register a receiver that is static (global to the process, not
     * associated with an Activity instance) then that registration will be
     * removed on you at whatever point the activity you used is destroyed.
     * <li> <p>If used from the Context returned here, the receiver is being
     * registered with the global state associated with your application.  Thus
     * it will never be unregistered for you.  This is necessary if the receiver
     * is associated with static data, not a particular component.  However
     * using the ApplicationContext elsewhere can easily lead to serious leaks
     * if you forget to unregister, unbind, etc.
     * </ul>
     */
    public abstract Context getApplicationContext();

    /**
     * Add a new {@link ComponentCallbacks} to the base application of the
     * Context, which will be called at the same times as the ComponentCallbacks
     * methods of activities and other components are called.  Note that you
     * <em>must</em> be sure to use {@link #unregisterComponentCallbacks} when
     * appropriate in the future; this will not be removed for you.
     *
     * @param callback The interface to call.  This can be either a
     * {@link ComponentCallbacks} or {@link ComponentCallbacks2} interface.
     */
    public void registerComponentCallbacks(ComponentCallbacks callback) {
        getApplicationContext().registerComponentCallbacks(callback);
    }

    /**
     * Remove a {@link ComponentCallbacks} objec that was previously registered
     * with {@link #registerComponentCallbacks(ComponentCallbacks)}.
     */
    public void unregisterComponentCallbacks(ComponentCallbacks callback) {
        getApplicationContext().unregisterComponentCallbacks(callback);
    }

    /**
     * Return a localized, styled CharSequence from the application's package's
     * default string table.
     *
     * @param resId Resource id for the CharSequence text
     */
    public final CharSequence getText(int resId) {
        return getResources().getText(resId);
    }

    /**
     * Return a localized string from the application's package's
     * default string table.
     *
     * @param resId Resource id for the string
     */
    public final String getString(int resId) {
        return getResources().getString(resId);
    }

    /**
     * Return a localized formatted string from the application's package's
     * default string table, substituting the format arguments as defined in
     * {@link java.util.Formatter} and {@link java.lang.String#format}.
     *
     * @param resId Resource id for the format string
     * @param formatArgs The format arguments that will be used for substitution.
     */

    public final String getString(int resId, Object... formatArgs) {
        return getResources().getString(resId, formatArgs);
    }

     /**
     * Set the base theme for this context.  Note that this should be called
     * before any views are instantiated in the Context (for example before
     * calling {@link android.app.Activity#setContentView} or
     * {@link android.view.LayoutInflater#inflate}).
     *
     * @param resid The style resource describing the theme.
     */
    public abstract void setTheme(int resid);

    /** @hide Needed for some internal implementation...  not public because
     * you can't assume this actually means anything. */
    public int getThemeResId() {
        return 0;
    }

    /**
     * Return the Theme object associated with this Context.
     */
    public abstract Resources.Theme getTheme();

    /**
     * Retrieve styled attribute information in this Context's theme.  See
     * {@link Resources.Theme#obtainStyledAttributes(int[])}
     * for more information.
     *
     * @see Resources.Theme#obtainStyledAttributes(int[])
     */
    public final TypedArray obtainStyledAttributes(
            int[] attrs) {
        return getTheme().obtainStyledAttributes(attrs);
    }

    /**
     * Retrieve styled attribute information in this Context's theme.  See
     * {@link Resources.Theme#obtainStyledAttributes(int, int[])}
     * for more information.
     *
     * @see Resources.Theme#obtainStyledAttributes(int, int[])
     */
    public final TypedArray obtainStyledAttributes(
            int resid, int[] attrs) throws Resources.NotFoundException {
        return getTheme().obtainStyledAttributes(resid, attrs);
    }

    /**
     * Retrieve styled attribute information in this Context's theme.  See
     * {@link Resources.Theme#obtainStyledAttributes(AttributeSet, int[], int, int)}
     * for more information.
     *
     * @see Resources.Theme#obtainStyledAttributes(AttributeSet, int[], int, int)
     */
    public final TypedArray obtainStyledAttributes(
            AttributeSet set, int[] attrs) {
        return getTheme().obtainStyledAttributes(set, attrs, 0, 0);
    }

    /**
     * Retrieve styled attribute information in this Context's theme.  See
     * {@link Resources.Theme#obtainStyledAttributes(AttributeSet, int[], int, int)}
     * for more information.
     *
     * @see Resources.Theme#obtainStyledAttributes(AttributeSet, int[], int, int)
     */
    public final TypedArray obtainStyledAttributes(
            AttributeSet set, int[] attrs, int defStyleAttr, int defStyleRes) {
        return getTheme().obtainStyledAttributes(
            set, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Return a class loader you can use to retrieve classes in this package.
     */
    public abstract ClassLoader getClassLoader();

    /** Return the name of this application's package. */
    public abstract String getPackageName();

    /** Return the full application info for this context's package. */
    public abstract ApplicationInfo getApplicationInfo();

    /**
     * Return the full path to this context's primary Android package.
     * The Android package is a ZIP file which contains the application's
     * primary resources.
     *
     * <p>Note: this is not generally useful for applications, since they should
     * not be directly accessing the file system.
     *
     * @return String Path to the resources.
     */
    public abstract String getPackageResourcePath();

    /**
     * Return the full path to this context's primary Android package.
     * The Android package is a ZIP file which contains application's
     * primary code and assets.
     *
     * <p>Note: this is not generally useful for applications, since they should
     * not be directly accessing the file system.
     *
     * @return String Path to the code and assets.
     */
    public abstract String getPackageCodePath();

    /**
     * {@hide}
     * Return the full path to the shared prefs file for the given prefs group name.
     *
     * <p>Note: this is not generally useful for applications, since they should
     * not be directly accessing the file system.
     */
    public abstract File getSharedPrefsFile(String name);

    /**
     * Retrieve and hold the contents of the preferences file 'name', returning
     * a SharedPreferences through which you can retrieve and modify its
     * values.  Only one instance of the SharedPreferences object is returned
     * to any callers for the same name, meaning they will see each other's
     * edits as soon as they are made.
     *
     * @param name Desired preferences file. If a preferences file by this name
     * does not exist, it will be created when you retrieve an
     * editor (SharedPreferences.edit()) and then commit changes (Editor.commit()).
     * @param mode Operating mode.  Use 0 or {@link #MODE_PRIVATE} for the
     * default operation, {@link #MODE_WORLD_READABLE}
     * and {@link #MODE_WORLD_WRITEABLE} to control permissions.  The bit
     * {@link #MODE_MULTI_PROCESS} can also be used if multiple processes
     * are mutating the same SharedPreferences file.  {@link #MODE_MULTI_PROCESS}
     * is always on in apps targetting Gingerbread (Android 2.3) and below, and
     * off by default in later versions.
     *
     * @return Returns the single SharedPreferences instance that can be used
     *         to retrieve and modify the preference values.
     *
     * @see #MODE_PRIVATE
     * @see #MODE_WORLD_READABLE
     * @see #MODE_WORLD_WRITEABLE
     * @see #MODE_MULTI_PROCESS
     */
    public abstract SharedPreferences getSharedPreferences(String name,
            int mode);

    /**
     * Open a private file associated with this Context's application package
     * for reading.
     *
     * @param name The name of the file to open; can not contain path
     *             separators.
     *
     * @return FileInputStream Resulting input stream.
     *
     * @see #openFileOutput
     * @see #fileList
     * @see #deleteFile
     * @see java.io.FileInputStream#FileInputStream(String)
     */
    public abstract FileInputStream openFileInput(String name)
        throws FileNotFoundException;

    /**
     * Open a private file associated with this Context's application package
     * for writing.  Creates the file if it doesn't already exist.
     *
     * @param name The name of the file to open; can not contain path
     *             separators.
     * @param mode Operating mode.  Use 0 or {@link #MODE_PRIVATE} for the
     * default operation, {@link #MODE_APPEND} to append to an existing file,
     * {@link #MODE_WORLD_READABLE} and {@link #MODE_WORLD_WRITEABLE} to control
     * permissions.
     *
     * @return FileOutputStream Resulting output stream.
     *
     * @see #MODE_APPEND
     * @see #MODE_PRIVATE
     * @see #MODE_WORLD_READABLE
     * @see #MODE_WORLD_WRITEABLE
     * @see #openFileInput
     * @see #fileList
     * @see #deleteFile
     * @see java.io.FileOutputStream#FileOutputStream(String)
     */
    public abstract FileOutputStream openFileOutput(String name, int mode)
        throws FileNotFoundException;

    /**
     * Delete the given private file associated with this Context's
     * application package.
     *
     * @param name The name of the file to delete; can not contain path
     *             separators.
     *
     * @return True if the file was successfully deleted; else
     *         false.
     *
     * @see #openFileInput
     * @see #openFileOutput
     * @see #fileList
     * @see java.io.File#delete()
     */
    public abstract boolean deleteFile(String name);

    /**
     * Returns the absolute path on the filesystem where a file created with
     * {@link #openFileOutput} is stored.
     *
     * @param name The name of the file for which you would like to get
     *          its path.
     *
     * @return Returns an absolute path to the given file.
     *
     * @see #openFileOutput
     * @see #getFilesDir
     * @see #getDir
     */
    public abstract File getFileStreamPath(String name);

    /**
     * Returns the absolute path to the directory on the filesystem where
     * files created with {@link #openFileOutput} are stored.
     *
     * @return Returns the path of the directory holding application files.
     *
     * @see #openFileOutput
     * @see #getFileStreamPath
     * @see #getDir
     */
    public abstract File getFilesDir();

    /**
     * Returns the absolute path to the directory on the external filesystem
     * (that is somewhere on {@link android.os.Environment#getExternalStorageDirectory()
     * Environment.getExternalStorageDirectory()}) where the application can
     * place persistent files it owns.  These files are private to the
     * applications, and not typically visible to the user as media.
     *
     * <p>This is like {@link #getFilesDir()} in that these
     * files will be deleted when the application is uninstalled, however there
     * are some important differences:
     *
     * <ul>
     * <li>External files are not always available: they will disappear if the
     * user mounts the external storage on a computer or removes it.  See the
     * APIs on {@link android.os.Environment} for information in the storage state.
     * <li>There is no security enforced with these files.  All applications
     * can read and write files placed here.
     * </ul>
     *
     * <p>Here is an example of typical code to manipulate a file in
     * an application's private storage:</p>
     *
     * {@sample development/samples/ApiDemos/src/com/example/android/apis/content/ExternalStorage.java
     * private_file}
     *
     * <p>If you supply a non-null <var>type</var> to this function, the returned
     * file will be a path to a sub-directory of the given type.  Though these files
     * are not automatically scanned by the media scanner, you can explicitly
     * add them to the media database with
     * {@link android.media.MediaScannerConnection#scanFile(Context, String[], String[],
     *      OnScanCompletedListener) MediaScannerConnection.scanFile}.
     * Note that this is not the same as
     * {@link android.os.Environment#getExternalStoragePublicDirectory
     * Environment.getExternalStoragePublicDirectory()}, which provides
     * directories of media shared by all applications.  The
     * directories returned here are
     * owned by the application, and their contents will be removed when the
     * application is uninstalled.  Unlike
     * {@link android.os.Environment#getExternalStoragePublicDirectory
     * Environment.getExternalStoragePublicDirectory()}, the directory
     * returned here will be automatically created for you.
     *
     * <p>Here is an example of typical code to manipulate a picture in
     * an application's private storage and add it to the media database:</p>
     *
     * {@sample development/samples/ApiDemos/src/com/example/android/apis/content/ExternalStorage.java
     * private_picture}
     *
     * @param type The type of files directory to return.  May be null for
     * the root of the files directory or one of
     * the following Environment constants for a subdirectory:
     * {@link android.os.Environment#DIRECTORY_MUSIC},
     * {@link android.os.Environment#DIRECTORY_PODCASTS},
     * {@link android.os.Environment#DIRECTORY_RINGTONES},
     * {@link android.os.Environment#DIRECTORY_ALARMS},
     * {@link android.os.Environment#DIRECTORY_NOTIFICATIONS},
     * {@link android.os.Environment#DIRECTORY_PICTURES}, or
     * {@link android.os.Environment#DIRECTORY_MOVIES}.
     *
     * @return Returns the path of the directory holding application files
     * on external storage.  Returns null if external storage is not currently
     * mounted so it could not ensure the path exists; you will need to call
     * this method again when it is available.
     *
     * @see #getFilesDir
     * @see android.os.Environment#getExternalStoragePublicDirectory
     */
    public abstract File getExternalFilesDir(String type);

    /**
     * Return the directory where this application's OBB files (if there
     * are any) can be found.  Note if the application does not have any OBB
     * files, this directory may not exist.
     */
    public abstract File getObbDir();

    /**
     * Returns the absolute path to the application specific cache directory
     * on the filesystem. These files will be ones that get deleted first when the
     * device runs low on storage.
     * There is no guarantee when these files will be deleted.
     *
     * <strong>Note: you should not <em>rely</em> on the system deleting these
     * files for you; you should always have a reasonable maximum, such as 1 MB,
     * for the amount of space you consume with cache files, and prune those
     * files when exceeding that space.</strong>
     *
     * @return Returns the path of the directory holding application cache files.
     *
     * @see #openFileOutput
     * @see #getFileStreamPath
     * @see #getDir
     */
    public abstract File getCacheDir();

    /**
     * Returns the absolute path to the directory on the external filesystem
     * (that is somewhere on {@link android.os.Environment#getExternalStorageDirectory()
     * Environment.getExternalStorageDirectory()} where the application can
     * place cache files it owns.
     *
     * <p>This is like {@link #getCacheDir()} in that these
     * files will be deleted when the application is uninstalled, however there
     * are some important differences:
     *
     * <ul>
     * <li>The platform does not monitor the space available in external storage,
     * and thus will not automatically delete these files.  Note that you should
     * be managing the maximum space you will use for these anyway, just like
     * with {@link #getCacheDir()}.
     * <li>External files are not always available: they will disappear if the
     * user mounts the external storage on a computer or removes it.  See the
     * APIs on {@link android.os.Environment} for information in the storage state.
     * <li>There is no security enforced with these files.  All applications
     * can read and write files placed here.
     * </ul>
     *
     * @return Returns the path of the directory holding application cache files
     * on external storage.  Returns null if external storage is not currently
     * mounted so it could not ensure the path exists; you will need to call
     * this method again when it is available.
     *
     * @see #getCacheDir
     */
    public abstract File getExternalCacheDir();

    /**
     * Returns an array of strings naming the private files associated with
     * this Context's application package.
     *
     * @return Array of strings naming the private files.
     *
     * @see #openFileInput
     * @see #openFileOutput
     * @see #deleteFile
     */
    public abstract String[] fileList();

    /**
     * Retrieve, creating if needed, a new directory in which the application
     * can place its own custom data files.  You can use the returned File
     * object to create and access files in this directory.  Note that files
     * created through a File object will only be accessible by your own
     * application; you can only set the mode of the entire directory, not
     * of individual files.
     *
     * @param name Name of the directory to retrieve.  This is a directory
     * that is created as part of your application data.
     * @param mode Operating mode.  Use 0 or {@link #MODE_PRIVATE} for the
     * default operation, {@link #MODE_WORLD_READABLE} and
     * {@link #MODE_WORLD_WRITEABLE} to control permissions.
     *
     * @return Returns a File object for the requested directory.  The directory
     * will have been created if it does not already exist.
     *
     * @see #openFileOutput(String, int)
     */
    public abstract File getDir(String name, int mode);

    /**
     * Open a new private SQLiteDatabase associated with this Context's
     * application package.  Create the database file if it doesn't exist.
     *
     * @param name The name (unique in the application package) of the database.
     * @param mode Operating mode.  Use 0 or {@link #MODE_PRIVATE} for the
     *     default operation, {@link #MODE_WORLD_READABLE}
     *     and {@link #MODE_WORLD_WRITEABLE} to control permissions.
     * @param factory An optional factory class that is called to instantiate a
     *     cursor when query is called.
     *
     * @return The contents of a newly created database with the given name.
     * @throws android.database.sqlite.SQLiteException if the database file could not be opened.
     *
     * @see #MODE_PRIVATE
     * @see #MODE_WORLD_READABLE
     * @see #MODE_WORLD_WRITEABLE
     * @see #deleteDatabase
     */
    public abstract SQLiteDatabase openOrCreateDatabase(String name,
            int mode, CursorFactory factory);

    /**
     * Open a new private SQLiteDatabase associated with this Context's
     * application package.  Creates the database file if it doesn't exist.
     *
     * <p>Accepts input param: a concrete instance of {@link DatabaseErrorHandler} to be
     * used to handle corruption when sqlite reports database corruption.</p>
     *
     * @param name The name (unique in the application package) of the database.
     * @param mode Operating mode.  Use 0 or {@link #MODE_PRIVATE} for the
     *     default operation, {@link #MODE_WORLD_READABLE}
     *     and {@link #MODE_WORLD_WRITEABLE} to control permissions.
     * @param factory An optional factory class that is called to instantiate a
     *     cursor when query is called.
     * @param errorHandler the {@link DatabaseErrorHandler} to be used when sqlite reports database
     * corruption. if null, {@link android.database.DefaultDatabaseErrorHandler} is assumed.
     * @return The contents of a newly created database with the given name.
     * @throws android.database.sqlite.SQLiteException if the database file could not be opened.
     *
     * @see #MODE_PRIVATE
     * @see #MODE_WORLD_READABLE
     * @see #MODE_WORLD_WRITEABLE
     * @see #deleteDatabase
     */
    public abstract SQLiteDatabase openOrCreateDatabase(String name,
            int mode, CursorFactory factory, DatabaseErrorHandler errorHandler);

    /**
     * Delete an existing private SQLiteDatabase associated with this Context's
     * application package.
     *
     * @param name The name (unique in the application package) of the
     *             database.
     *
     * @return True if the database was successfully deleted; else false.
     *
     * @see #openOrCreateDatabase
     */
    public abstract boolean deleteDatabase(String name);

    /**
     * Returns the absolute path on the filesystem where a database created with
     * {@link #openOrCreateDatabase} is stored.
     *
     * @param name The name of the database for which you would like to get
     *          its path.
     *
     * @return Returns an absolute path to the given database.
     *
     * @see #openOrCreateDatabase
     */
    public abstract File getDatabasePath(String name);

    /**
     * Returns an array of strings naming the private databases associated with
     * this Context's application package.
     *
     * @return Array of strings naming the private databases.
     *
     * @see #openOrCreateDatabase
     * @see #deleteDatabase
     */
    public abstract String[] databaseList();

    /**
     * @deprecated Use {@link android.app.WallpaperManager#getDrawable
     * WallpaperManager.get()} instead.
     */
    @Deprecated
    public abstract Drawable getWallpaper();

    /**
     * @deprecated Use {@link android.app.WallpaperManager#peekDrawable
     * WallpaperManager.peek()} instead.
     */
    @Deprecated
    public abstract Drawable peekWallpaper();

    /**
     * @deprecated Use {@link android.app.WallpaperManager#getDesiredMinimumWidth()
     * WallpaperManager.getDesiredMinimumWidth()} instead.
     */
    @Deprecated
    public abstract int getWallpaperDesiredMinimumWidth();

    /**
     * @deprecated Use {@link android.app.WallpaperManager#getDesiredMinimumHeight()
     * WallpaperManager.getDesiredMinimumHeight()} instead.
     */
    @Deprecated
    public abstract int getWallpaperDesiredMinimumHeight();

    /**
     * @deprecated Use {@link android.app.WallpaperManager#setBitmap(Bitmap)
     * WallpaperManager.set()} instead.
     */
    @Deprecated
    public abstract void setWallpaper(Bitmap bitmap) throws IOException;

    /**
     * @deprecated Use {@link android.app.WallpaperManager#setStream(InputStream)
     * WallpaperManager.set()} instead.
     */
    @Deprecated
    public abstract void setWallpaper(InputStream data) throws IOException;

    /**
     * @deprecated Use {@link android.app.WallpaperManager#clear
     * WallpaperManager.clear()} instead.
     */
    @Deprecated
    public abstract void clearWallpaper() throws IOException;

    /**
     * Launch a new activity.  You will not receive any information about when
     * the activity exits.
     *
     * <p>Note that if this method is being called from outside of an
     * {@link android.app.Activity} Context, then the Intent must include
     * the {@link Intent#FLAG_ACTIVITY_NEW_TASK} launch flag.  This is because,
     * without being started from an existing Activity, there is no existing
     * task in which to place the new activity and thus it needs to be placed
     * in its own separate task.
     *
     * <p>This method throws {@link ActivityNotFoundException}
     * if there was no Activity found to run the given Intent.
     *
     * @param intent The description of the activity to start.
     *
     * @throws ActivityNotFoundException
     *
     * @see PackageManager#resolveActivity
     */
    public abstract void startActivity(Intent intent);

    /**
     * Launch multiple new activities.  This is generally the same as calling
     * {@link #startActivity(Intent)} for the first Intent in the array,
     * that activity during its creation calling {@link #startActivity(Intent)}
     * for the second entry, etc.  Note that unlike that approach, generally
     * none of the activities except the last in the array will be created
     * at this point, but rather will be created when the user first visits
     * them (due to pressing back from the activity on top).
     *
     * <p>This method throws {@link ActivityNotFoundException}
     * if there was no Activity found for <em>any</em> given Intent.  In this
     * case the state of the activity stack is undefined (some Intents in the
     * list may be on it, some not), so you probably want to avoid such situations.
     *
     * @param intents An array of Intents to be started.
     *
     * @throws ActivityNotFoundException
     *
     * @see PackageManager#resolveActivity
     */
    public abstract void startActivities(Intent[] intents);

    /**
     * Like {@link #startActivity(Intent)}, but taking a IntentSender
     * to start.  If the IntentSender is for an activity, that activity will be started
     * as if you had called the regular {@link #startActivity(Intent)}
     * here; otherwise, its associated action will be executed (such as
     * sending a broadcast) as if you had called
     * {@link IntentSender#sendIntent IntentSender.sendIntent} on it.
     *
     * @param intent The IntentSender to launch.
     * @param fillInIntent If non-null, this will be provided as the
     * intent parameter to {@link IntentSender#sendIntent}.
     * @param flagsMask Intent flags in the original IntentSender that you
     * would like to change.
     * @param flagsValues Desired values for any bits set in
     * <var>flagsMask</var>
     * @param extraFlags Always set to 0.
     */
    public abstract void startIntentSender(IntentSender intent,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException;

    /**
     * Broadcast the given intent to all interested BroadcastReceivers.  This
     * call is asynchronous; it returns immediately, and you will continue
     * executing while the receivers are run.  No results are propagated from
     * receivers and receivers can not abort the broadcast. If you want
     * to allow receivers to propagate results or abort the broadcast, you must
     * send an ordered broadcast using
     * {@link #sendOrderedBroadcast(Intent, String)}.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     *
     * @see android.content.BroadcastReceiver
     * @see #registerReceiver
     * @see #sendBroadcast(Intent, String)
     * @see #sendOrderedBroadcast(Intent, String)
     * @see #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)
     */
    public abstract void sendBroadcast(Intent intent);

    /**
     * Broadcast the given intent to all interested BroadcastReceivers, allowing
     * an optional required permission to be enforced.  This
     * call is asynchronous; it returns immediately, and you will continue
     * executing while the receivers are run.  No results are propagated from
     * receivers and receivers can not abort the broadcast. If you want
     * to allow receivers to propagate results or abort the broadcast, you must
     * send an ordered broadcast using
     * {@link #sendOrderedBroadcast(Intent, String)}.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param receiverPermission (optional) String naming a permission that
     *               a receiver must hold in order to receive your broadcast.
     *               If null, no permission is required.
     *
     * @see android.content.BroadcastReceiver
     * @see #registerReceiver
     * @see #sendBroadcast(Intent)
     * @see #sendOrderedBroadcast(Intent, String)
     * @see #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)
     */
    public abstract void sendBroadcast(Intent intent,
            String receiverPermission);

    /**
     * Broadcast the given intent to all interested BroadcastReceivers, delivering
     * them one at a time to allow more preferred receivers to consume the
     * broadcast before it is delivered to less preferred receivers.  This
     * call is asynchronous; it returns immediately, and you will continue
     * executing while the receivers are run.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param receiverPermission (optional) String naming a permissions that
     *               a receiver must hold in order to receive your broadcast.
     *               If null, no permission is required.
     *
     * @see android.content.BroadcastReceiver
     * @see #registerReceiver
     * @see #sendBroadcast(Intent)
     * @see #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)
     */
    public abstract void sendOrderedBroadcast(Intent intent,
            String receiverPermission);

    /**
     * Version of {@link #sendBroadcast(Intent)} that allows you to
     * receive data back from the broadcast.  This is accomplished by
     * supplying your own BroadcastReceiver when calling, which will be
     * treated as a final receiver at the end of the broadcast -- its
     * {@link BroadcastReceiver#onReceive} method will be called with
     * the result values collected from the other receivers.  The broadcast will
     * be serialized in the same way as calling
     * {@link #sendOrderedBroadcast(Intent, String)}.
     *
     * <p>Like {@link #sendBroadcast(Intent)}, this method is
     * asynchronous; it will return before
     * resultReceiver.onReceive() is called.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param receiverPermission String naming a permissions that
     *               a receiver must hold in order to receive your broadcast.
     *               If null, no permission is required.
     * @param resultReceiver Your own BroadcastReceiver to treat as the final
     *                       receiver of the broadcast.
     * @param scheduler A custom Handler with which to schedule the
     *                  resultReceiver callback; if null it will be
     *                  scheduled in the Context's main thread.
     * @param initialCode An initial value for the result code.  Often
     *                    Activity.RESULT_OK.
     * @param initialData An initial value for the result data.  Often
     *                    null.
     * @param initialExtras An initial value for the result extras.  Often
     *                      null.
     *
     * @see #sendBroadcast(Intent)
     * @see #sendBroadcast(Intent, String)
     * @see #sendOrderedBroadcast(Intent, String)
     * @see #sendStickyBroadcast(Intent)
     * @see #sendStickyOrderedBroadcast(Intent, BroadcastReceiver, Handler, int, String, Bundle)
     * @see android.content.BroadcastReceiver
     * @see #registerReceiver
     * @see android.app.Activity#RESULT_OK
     */
    public abstract void sendOrderedBroadcast(Intent intent,
            String receiverPermission, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras);

    /**
     * Perform a {@link #sendBroadcast(Intent)} that is "sticky," meaning the
     * Intent you are sending stays around after the broadcast is complete,
     * so that others can quickly retrieve that data through the return
     * value of {@link #registerReceiver(BroadcastReceiver, IntentFilter)}.  In
     * all other ways, this behaves the same as
     * {@link #sendBroadcast(Intent)}.
     *
     * <p>You must hold the {@link android.Manifest.permission#BROADCAST_STICKY}
     * permission in order to use this API.  If you do not hold that
     * permission, {@link SecurityException} will be thrown.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     * Intent will receive the broadcast, and the Intent will be held to
     * be re-broadcast to future receivers.
     *
     * @see #sendBroadcast(Intent)
     * @see #sendStickyOrderedBroadcast(Intent, BroadcastReceiver, Handler, int, String, Bundle)
     */
    public abstract void sendStickyBroadcast(Intent intent);

    /**
     * Version of {@link #sendStickyBroadcast} that allows you to
     * receive data back from the broadcast.  This is accomplished by
     * supplying your own BroadcastReceiver when calling, which will be
     * treated as a final receiver at the end of the broadcast -- its
     * {@link BroadcastReceiver#onReceive} method will be called with
     * the result values collected from the other receivers.  The broadcast will
     * be serialized in the same way as calling
     * {@link #sendOrderedBroadcast(Intent, String)}.
     *
     * <p>Like {@link #sendBroadcast(Intent)}, this method is
     * asynchronous; it will return before
     * resultReceiver.onReceive() is called.  Note that the sticky data
     * stored is only the data you initially supply to the broadcast, not
     * the result of any changes made by the receivers.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param resultReceiver Your own BroadcastReceiver to treat as the final
     *                       receiver of the broadcast.
     * @param scheduler A custom Handler with which to schedule the
     *                  resultReceiver callback; if null it will be
     *                  scheduled in the Context's main thread.
     * @param initialCode An initial value for the result code.  Often
     *                    Activity.RESULT_OK.
     * @param initialData An initial value for the result data.  Often
     *                    null.
     * @param initialExtras An initial value for the result extras.  Often
     *                      null.
     *
     * @see #sendBroadcast(Intent)
     * @see #sendBroadcast(Intent, String)
     * @see #sendOrderedBroadcast(Intent, String)
     * @see #sendStickyBroadcast(Intent)
     * @see android.content.BroadcastReceiver
     * @see #registerReceiver
     * @see android.app.Activity#RESULT_OK
     */
    public abstract void sendStickyOrderedBroadcast(Intent intent,
            BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras);


    /**
     * Remove the data previously sent with {@link #sendStickyBroadcast},
     * so that it is as if the sticky broadcast had never happened.
     *
     * <p>You must hold the {@link android.Manifest.permission#BROADCAST_STICKY}
     * permission in order to use this API.  If you do not hold that
     * permission, {@link SecurityException} will be thrown.
     *
     * @param intent The Intent that was previously broadcast.
     *
     * @see #sendStickyBroadcast
     */
    public abstract void removeStickyBroadcast(Intent intent);

    /**
     * Register a BroadcastReceiver to be run in the main activity thread.  The
     * <var>receiver</var> will be called with any broadcast Intent that
     * matches <var>filter</var>, in the main application thread.
     *
     * <p>The system may broadcast Intents that are "sticky" -- these stay
     * around after the broadcast as finished, to be sent to any later
     * registrations. If your IntentFilter matches one of these sticky
     * Intents, that Intent will be returned by this function
     * <strong>and</strong> sent to your <var>receiver</var> as if it had just
     * been broadcast.
     *
     * <p>There may be multiple sticky Intents that match <var>filter</var>,
     * in which case each of these will be sent to <var>receiver</var>.  In
     * this case, only one of these can be returned directly by the function;
     * which of these that is returned is arbitrarily decided by the system.
     *
     * <p>If you know the Intent your are registering for is sticky, you can
     * supply null for your <var>receiver</var>.  In this case, no receiver is
     * registered -- the function simply returns the sticky Intent that
     * matches <var>filter</var>.  In the case of multiple matches, the same
     * rules as described above apply.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * <p>As of {@link android.os.Build.VERSION_CODES#ICE_CREAM_SANDWICH}, receivers
     * registered with this method will correctly respect the
     * {@link Intent#setPackage(String)} specified for an Intent being broadcast.
     * Prior to that, it would be ignored and delivered to all matching registered
     * receivers.  Be careful if using this for security.</p>
     *
     * <p class="note">Note: this method <em>cannot be called from a
     * {@link BroadcastReceiver} component;</em> that is, from a BroadcastReceiver
     * that is declared in an application's manifest.  It is okay, however, to call
     * this method from another BroadcastReceiver that has itself been registered
     * at run time with {@link #registerReceiver}, since the lifetime of such a
     * registered BroadcastReceiver is tied to the object that registered it.</p>
     *
     * @param receiver The BroadcastReceiver to handle the broadcast.
     * @param filter Selects the Intent broadcasts to be received.
     *
     * @return The first sticky intent found that matches <var>filter</var>,
     *         or null if there are none.
     *
     * @see #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler)
     * @see #sendBroadcast
     * @see #unregisterReceiver
     */
    public abstract Intent registerReceiver(BroadcastReceiver receiver,
                                            IntentFilter filter);

    /**
     * Register to receive intent broadcasts, to run in the context of
     * <var>scheduler</var>.  See
     * {@link #registerReceiver(BroadcastReceiver, IntentFilter)} for more
     * information.  This allows you to enforce permissions on who can
     * broadcast intents to your receiver, or have the receiver run in
     * a different thread than the main application thread.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * <p>As of {@link android.os.Build.VERSION_CODES#ICE_CREAM_SANDWICH}, receivers
     * registered with this method will correctly respect the
     * {@link Intent#setPackage(String)} specified for an Intent being broadcast.
     * Prior to that, it would be ignored and delivered to all matching registered
     * receivers.  Be careful if using this for security.</p>
     *
     * @param receiver The BroadcastReceiver to handle the broadcast.
     * @param filter Selects the Intent broadcasts to be received.
     * @param broadcastPermission String naming a permissions that a
     *      broadcaster must hold in order to send an Intent to you.  If null,
     *      no permission is required.
     * @param scheduler Handler identifying the thread that will receive
     *      the Intent.  If null, the main thread of the process will be used.
     *
     * @return The first sticky intent found that matches <var>filter</var>,
     *         or null if there are none.
     *
     * @see #registerReceiver(BroadcastReceiver, IntentFilter)
     * @see #sendBroadcast
     * @see #unregisterReceiver
     */
    public abstract Intent registerReceiver(BroadcastReceiver receiver,
                                            IntentFilter filter,
                                            String broadcastPermission,
                                            Handler scheduler);

    /**
     * Unregister a previously registered BroadcastReceiver.  <em>All</em>
     * filters that have been registered for this BroadcastReceiver will be
     * removed.
     *
     * @param receiver The BroadcastReceiver to unregister.
     *
     * @see #registerReceiver
     */
    public abstract void unregisterReceiver(BroadcastReceiver receiver);

    /**
     * Request that a given application service be started.  The Intent
     * can either contain the complete class name of a specific service
     * implementation to start, or an abstract definition through the
     * action and other fields of the kind of service to start.  If this service
     * is not already running, it will be instantiated and started (creating a
     * process for it if needed); if it is running then it remains running.
     *
     * <p>Every call to this method will result in a corresponding call to
     * the target service's {@link android.app.Service#onStartCommand} method,
     * with the <var>intent</var> given here.  This provides a convenient way
     * to submit jobs to a service without having to bind and call on to its
     * interface.
     *
     * <p>Using startService() overrides the default service lifetime that is
     * managed by {@link #bindService}: it requires the service to remain
     * running until {@link #stopService} is called, regardless of whether
     * any clients are connected to it.  Note that calls to startService()
     * are not nesting: no matter how many times you call startService(),
     * a single call to {@link #stopService} will stop it.
     *
     * <p>The system attempts to keep running services around as much as
     * possible.  The only time they should be stopped is if the current
     * foreground application is using so many resources that the service needs
     * to be killed.  If any errors happen in the service's process, it will
     * automatically be restarted.
     *
     * <p>This function will throw {@link SecurityException} if you do not
     * have permission to start the given service.
     *
     * @param service Identifies the service to be started.  The Intent may
     *      specify either an explicit component name to start, or a logical
     *      description (action, category, etc) to match an
     *      {@link IntentFilter} published by a service.  Additional values
     *      may be included in the Intent extras to supply arguments along with
     *      this specific start call.
     *
     * @return If the service is being started or is already running, the
     * {@link ComponentName} of the actual service that was started is
     * returned; else if the service does not exist null is returned.
     *
     * @throws SecurityException
     *
     * @see #stopService
     * @see #bindService
     */
    public abstract ComponentName startService(Intent service);

    /**
     * Request that a given application service be stopped.  If the service is
     * not running, nothing happens.  Otherwise it is stopped.  Note that calls
     * to startService() are not counted -- this stops the service no matter
     * how many times it was started.
     *
     * <p>Note that if a stopped service still has {@link ServiceConnection}
     * objects bound to it with the {@link #BIND_AUTO_CREATE} set, it will
     * not be destroyed until all of these bindings are removed.  See
     * the {@link android.app.Service} documentation for more details on a
     * service's lifecycle.
     *
     * <p>This function will throw {@link SecurityException} if you do not
     * have permission to stop the given service.
     *
     * @param service Description of the service to be stopped.  The Intent may
     *      specify either an explicit component name to start, or a logical
     *      description (action, category, etc) to match an
     *      {@link IntentFilter} published by a service.
     *
     * @return If there is a service matching the given Intent that is already
     * running, then it is stopped and true is returned; else false is returned.
     *
     * @throws SecurityException
     *
     * @see #startService
     */
    public abstract boolean stopService(Intent service);

    /**
     * Connect to an application service, creating it if needed.  This defines
     * a dependency between your application and the service.  The given
     * <var>conn</var> will receive the service object when its created and be
     * told if it dies and restarts.  The service will be considered required
     * by the system only for as long as the calling context exists.  For
     * example, if this Context is an Activity that is stopped, the service will
     * not be required to continue running until the Activity is resumed.
     *
     * <p>This function will throw {@link SecurityException} if you do not
     * have permission to bind to the given service.
     *
     * <p class="note">Note: this method <em>can not be called from an
     * {@link BroadcastReceiver} component</em>.  A pattern you can use to
     * communicate from an BroadcastReceiver to a Service is to call
     * {@link #startService} with the arguments containing the command to be
     * sent, with the service calling its
     * {@link android.app.Service#stopSelf(int)} method when done executing
     * that command.  See the API demo App/Service/Service Start Arguments
     * Controller for an illustration of this.  It is okay, however, to use
     * this method from an BroadcastReceiver that has been registered with
     * {@link #registerReceiver}, since the lifetime of this BroadcastReceiver
     * is tied to another object (the one that registered it).</p>
     *
     * @param service Identifies the service to connect to.  The Intent may
     *      specify either an explicit component name, or a logical
     *      description (action, category, etc) to match an
     *      {@link IntentFilter} published by a service.
     * @param conn Receives information as the service is started and stopped.
     * @param flags Operation options for the binding.  May be 0,
     *          {@link #BIND_AUTO_CREATE}, {@link #BIND_DEBUG_UNBIND},
     *          {@link #BIND_NOT_FOREGROUND}, {@link #BIND_ABOVE_CLIENT},
     *          {@link #BIND_ALLOW_OOM_MANAGEMENT}, or
     *          {@link #BIND_WAIVE_PRIORITY}.
     * @return If you have successfully bound to the service, true is returned;
     *         false is returned if the connection is not made so you will not
     *         receive the service object.
     *
     * @throws SecurityException
     *
     * @see #unbindService
     * @see #startService
     * @see #BIND_AUTO_CREATE
     * @see #BIND_DEBUG_UNBIND
     * @see #BIND_NOT_FOREGROUND
     */
    public abstract boolean bindService(Intent service, ServiceConnection conn,
            int flags);

    /**
     * Disconnect from an application service.  You will no longer receive
     * calls as the service is restarted, and the service is now allowed to
     * stop at any time.
     *
     * @param conn The connection interface previously supplied to
     *             bindService().
     *
     * @see #bindService
     */
    public abstract void unbindService(ServiceConnection conn);

    /**
     * Start executing an {@link android.app.Instrumentation} class.  The given
     * Instrumentation component will be run by killing its target application
     * (if currently running), starting the target process, instantiating the
     * instrumentation component, and then letting it drive the application.
     *
     * <p>This function is not synchronous -- it returns as soon as the
     * instrumentation has started and while it is running.
     *
     * <p>Instrumentation is normally only allowed to run against a package
     * that is either unsigned or signed with a signature that the
     * the instrumentation package is also signed with (ensuring the target
     * trusts the instrumentation).
     *
     * @param className Name of the Instrumentation component to be run.
     * @param profileFile Optional path to write profiling data as the
     * instrumentation runs, or null for no profiling.
     * @param arguments Additional optional arguments to pass to the
     * instrumentation, or null.
     *
     * @return Returns true if the instrumentation was successfully started,
     * else false if it could not be found.
     */
    public abstract boolean startInstrumentation(ComponentName className,
            String profileFile, Bundle arguments);

    /**
     * Return the handle to a system-level service by name. The class of the
     * returned object varies by the requested name. Currently available names
     * are:
     *
     * <dl>
     *  <dt> {@link #WINDOW_SERVICE} ("window")
     *  <dd> The top-level window manager in which you can place custom
     *  windows.  The returned object is a {@link android.view.WindowManager}.
     *  <dt> {@link #LAYOUT_INFLATER_SERVICE} ("layout_inflater")
     *  <dd> A {@link android.view.LayoutInflater} for inflating layout resources
     *  in this context.
     *  <dt> {@link #ACTIVITY_SERVICE} ("activity")
     *  <dd> A {@link android.app.ActivityManager} for interacting with the
     *  global activity state of the system.
     *  <dt> {@link #POWER_SERVICE} ("power")
     *  <dd> A {@link android.os.PowerManager} for controlling power
     *  management.
     *  <dt> {@link #ALARM_SERVICE} ("alarm")
     *  <dd> A {@link android.app.AlarmManager} for receiving intents at the
     *  time of your choosing.
     *  <dt> {@link #NOTIFICATION_SERVICE} ("notification")
     *  <dd> A {@link android.app.NotificationManager} for informing the user
     *   of background events.
     *  <dt> {@link #KEYGUARD_SERVICE} ("keyguard")
     *  <dd> A {@link android.app.KeyguardManager} for controlling keyguard.
     *  <dt> {@link #LOCATION_SERVICE} ("location")
     *  <dd> A {@link android.location.LocationManager} for controlling location
     *   (e.g., GPS) updates.
     *  <dt> {@link #SEARCH_SERVICE} ("search")
     *  <dd> A {@link android.app.SearchManager} for handling search.
     *  <dt> {@link #VIBRATOR_SERVICE} ("vibrator")
     *  <dd> A {@link android.os.Vibrator} for interacting with the vibrator
     *  hardware.
     *  <dt> {@link #CONNECTIVITY_SERVICE} ("connection")
     *  <dd> A {@link android.net.ConnectivityManager ConnectivityManager} for
     *  handling management of network connections.
     *  <dt> {@link #WIFI_SERVICE} ("wifi")
     *  <dd> A {@link android.net.wifi.WifiManager WifiManager} for management of
     * Wi-Fi connectivity.
     * <dt> {@link #INPUT_METHOD_SERVICE} ("input_method")
     * <dd> An {@link android.view.inputmethod.InputMethodManager InputMethodManager}
     * for management of input methods.
     * <dt> {@link #UI_MODE_SERVICE} ("uimode")
     * <dd> An {@link android.app.UiModeManager} for controlling UI modes.
     * <dt> {@link #DOWNLOAD_SERVICE} ("download")
     * <dd> A {@link android.app.DownloadManager} for requesting HTTP downloads
     * </dl>
     *
     * <p>Note:  System services obtained via this API may be closely associated with
     * the Context in which they are obtained from.  In general, do not share the
     * service objects between various different contexts (Activities, Applications,
     * Services, Providers, etc.)
     *
     * @param name The name of the desired service.
     *
     * @return The service or null if the name does not exist.
     *
     * @see #WINDOW_SERVICE
     * @see android.view.WindowManager
     * @see #LAYOUT_INFLATER_SERVICE
     * @see android.view.LayoutInflater
     * @see #ACTIVITY_SERVICE
     * @see android.app.ActivityManager
     * @see #POWER_SERVICE
     * @see android.os.PowerManager
     * @see #ALARM_SERVICE
     * @see android.app.AlarmManager
     * @see #NOTIFICATION_SERVICE
     * @see android.app.NotificationManager
     * @see #KEYGUARD_SERVICE
     * @see android.app.KeyguardManager
     * @see #LOCATION_SERVICE
     * @see android.location.LocationManager
     * @see #SEARCH_SERVICE
     * @see android.app.SearchManager
     * @see #SENSOR_SERVICE
     * @see android.hardware.SensorManager
     * @see #STORAGE_SERVICE
     * @see android.os.storage.StorageManager
     * @see #VIBRATOR_SERVICE
     * @see android.os.Vibrator
     * @see #CONNECTIVITY_SERVICE
     * @see android.net.ConnectivityManager
     * @see #WIFI_SERVICE
     * @see android.net.wifi.WifiManager
     * @see #AUDIO_SERVICE
     * @see android.media.AudioManager
     * @see #TELEPHONY_SERVICE
     * @see android.telephony.TelephonyManager
     * @see #INPUT_METHOD_SERVICE
     * @see android.view.inputmethod.InputMethodManager
     * @see #UI_MODE_SERVICE
     * @see android.app.UiModeManager
     * @see #DOWNLOAD_SERVICE
     * @see android.app.DownloadManager
     */
    public abstract Object getSystemService(String name);

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.os.PowerManager} for controlling power management,
     * including "wake locks," which let you keep the device on while
     * you're running long tasks.
     */
    public static final String POWER_SERVICE = "power";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.view.WindowManager} for accessing the system's window
     * manager.
     *
     * @see #getSystemService
     * @see android.view.WindowManager
     */
    public static final String WINDOW_SERVICE = "window";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.view.LayoutInflater} for inflating layout resources in this
     * context.
     *
     * @see #getSystemService
     * @see android.view.LayoutInflater
     */
    public static final String LAYOUT_INFLATER_SERVICE = "layout_inflater";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.accounts.AccountManager} for receiving intents at a
     * time of your choosing.
     *
     * @see #getSystemService
     * @see android.accounts.AccountManager
     */
    public static final String ACCOUNT_SERVICE = "account";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.app.ActivityManager} for interacting with the global
     * system state.
     *
     * @see #getSystemService
     * @see android.app.ActivityManager
     */
    public static final String ACTIVITY_SERVICE = "activity";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.app.AlarmManager} for receiving intents at a
     * time of your choosing.
     *
     * @see #getSystemService
     * @see android.app.AlarmManager
     */
    public static final String ALARM_SERVICE = "alarm";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.app.NotificationManager} for informing the user of
     * background events.
     *
     * @see #getSystemService
     * @see android.app.NotificationManager
     */
    public static final String NOTIFICATION_SERVICE = "notification";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.view.accessibility.AccessibilityManager} for giving the user
     * feedback for UI events through the registered event listeners.
     *
     * @see #getSystemService
     * @see android.view.accessibility.AccessibilityManager
     */
    public static final String ACCESSIBILITY_SERVICE = "accessibility";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.app.NotificationManager} for controlling keyguard.
     *
     * @see #getSystemService
     * @see android.app.KeyguardManager
     */
    public static final String KEYGUARD_SERVICE = "keyguard";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.location.LocationManager} for controlling location
     * updates.
     *
     * @see #getSystemService
     * @see android.location.LocationManager
     */
    public static final String LOCATION_SERVICE = "location";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.location.CountryDetector} for detecting the country that
     * the user is in.
     *
     * @hide
     */
    public static final String COUNTRY_DETECTOR = "country_detector";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.app.SearchManager} for handling searches.
     *
     * @see #getSystemService
     * @see android.app.SearchManager
     */
    public static final String SEARCH_SERVICE = "search";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.hardware.SensorManager} for accessing sensors.
     *
     * @see #getSystemService
     * @see android.hardware.SensorManager
     */
    public static final String SENSOR_SERVICE = "sensor";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.os.storage.StorageManager} for accessing system storage
     * functions.
     *
     * @see #getSystemService
     * @see android.os.storage.StorageManager
     */
    public static final String STORAGE_SERVICE = "storage";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * com.android.server.WallpaperService for accessing wallpapers.
     *
     * @see #getSystemService
     */
    public static final String WALLPAPER_SERVICE = "wallpaper";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.os.Vibrator} for interacting with the vibration hardware.
     *
     * @see #getSystemService
     * @see android.os.Vibrator
     */
    public static final String VIBRATOR_SERVICE = "vibrator";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.app.StatusBarManager} for interacting with the status bar.
     *
     * @see #getSystemService
     * @see android.app.StatusBarManager
     * @hide
     */
    public static final String STATUS_BAR_SERVICE = "statusbar";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.net.ConnectivityManager} for handling management of
     * network connections.
     *
     * @see #getSystemService
     * @see android.net.ConnectivityManager
     */
    public static final String CONNECTIVITY_SERVICE = "connectivity";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.net.ThrottleManager} for handling management of
     * throttling.
     *
     * @hide
     * @see #getSystemService
     * @see android.net.ThrottleManager
     */
    public static final String THROTTLE_SERVICE = "throttle";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.net.NetworkManagementService} for handling management of
     * system network services
     *
     * @hide
     * @see #getSystemService
     * @see android.net.NetworkManagementService
     */
    public static final String NETWORKMANAGEMENT_SERVICE = "network_management";

    /** {@hide} */
    public static final String NETWORK_STATS_SERVICE = "netstats";
    /** {@hide} */
    public static final String NETWORK_POLICY_SERVICE = "netpolicy";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.net.wifi.WifiManager} for handling management of
     * Wi-Fi access.
     *
     * @see #getSystemService
     * @see android.net.wifi.WifiManager
     */
    public static final String WIFI_SERVICE = "wifi";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.net.wifi.p2p.WifiP2pManager} for handling management of
     * Wi-Fi peer-to-peer connections.
     *
     * @see #getSystemService
     * @see android.net.wifi.p2p.WifiP2pManager
     */
    public static final String WIFI_P2P_SERVICE = "wifip2p";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.media.AudioManager} for handling management of volume,
     * ringer modes and audio routing.
     *
     * @see #getSystemService
     * @see android.media.AudioManager
     */
    public static final String AUDIO_SERVICE = "audio";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.telephony.TelephonyManager} for handling management the
     * telephony features of the device.
     *
     * @see #getSystemService
     * @see android.telephony.TelephonyManager
     */
    public static final String TELEPHONY_SERVICE = "phone";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.text.ClipboardManager} for accessing and modifying
     * the contents of the global clipboard.
     *
     * @see #getSystemService
     * @see android.text.ClipboardManager
     */
    public static final String CLIPBOARD_SERVICE = "clipboard";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.view.inputmethod.InputMethodManager} for accessing input
     * methods.
     *
     * @see #getSystemService
     */
    public static final String INPUT_METHOD_SERVICE = "input_method";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.view.textservice.TextServicesManager} for accessing
     * text services.
     *
     * @see #getSystemService
     */
    public static final String TEXT_SERVICES_MANAGER_SERVICE = "textservices";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.appwidget.AppWidgetManager} for accessing AppWidgets.
     *
     * @hide
     * @see #getSystemService
     */
    public static final String APPWIDGET_SERVICE = "appwidget";

    /**
     * Use with {@link #getSystemService} to retrieve an
     * {@link android.app.backup.IBackupManager IBackupManager} for communicating
     * with the backup mechanism.
     * @hide
     *
     * @see #getSystemService
     */
    public static final String BACKUP_SERVICE = "backup";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.os.DropBoxManager} instance for recording
     * diagnostic logs.
     * @see #getSystemService
     */
    public static final String DROPBOX_SERVICE = "dropbox";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.app.admin.DevicePolicyManager} for working with global
     * device policy management.
     *
     * @see #getSystemService
     */
    public static final String DEVICE_POLICY_SERVICE = "device_policy";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.app.UiModeManager} for controlling UI modes.
     *
     * @see #getSystemService
     */
    public static final String UI_MODE_SERVICE = "uimode";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.app.DownloadManager} for requesting HTTP downloads.
     *
     * @see #getSystemService
     */
    public static final String DOWNLOAD_SERVICE = "download";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.nfc.NfcManager} for using NFC.
     *
     * @see #getSystemService
     */
    public static final String NFC_SERVICE = "nfc";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.net.sip.SipManager} for accessing the SIP related service.
     *
     * @see #getSystemService
     */
    /** @hide */
    public static final String SIP_SERVICE = "sip";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.hardware.usb.UsbManager} for access to USB devices (as a USB host)
     * and for controlling this device's behavior as a USB device.
     *
     * @see #getSystemService
     * @see android.harware.usb.UsbManager
     */
    public static final String USB_SERVICE = "usb";

    /**
     * Determine whether the given permission is allowed for a particular
     * process and user ID running in the system.
     *
     * @param permission The name of the permission being checked.
     * @param pid The process ID being checked against.  Must be > 0.
     * @param uid The user ID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     *
     * @return Returns {@link PackageManager#PERMISSION_GRANTED} if the given
     * pid/uid is allowed that permission, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see PackageManager#checkPermission(String, String)
     * @see #checkCallingPermission
     */
    public abstract int checkPermission(String permission, int pid, int uid);

    /**
     * Determine whether the calling process of an IPC you are handling has been
     * granted a particular permission.  This is basically the same as calling
     * {@link #checkPermission(String, int, int)} with the pid and uid returned
     * by {@link android.os.Binder#getCallingPid} and
     * {@link android.os.Binder#getCallingUid}.  One important difference
     * is that if you are not currently processing an IPC, this function
     * will always fail.  This is done to protect against accidentally
     * leaking permissions; you can use {@link #checkCallingOrSelfPermission}
     * to avoid this protection.
     *
     * @param permission The name of the permission being checked.
     *
     * @return Returns {@link PackageManager#PERMISSION_GRANTED} if the calling
     * pid/uid is allowed that permission, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see PackageManager#checkPermission(String, String)
     * @see #checkPermission
     * @see #checkCallingOrSelfPermission
     */
    public abstract int checkCallingPermission(String permission);

    /**
     * Determine whether the calling process of an IPC <em>or you</em> have been
     * granted a particular permission.  This is the same as
     * {@link #checkCallingPermission}, except it grants your own permissions
     * if you are not currently processing an IPC.  Use with care!
     *
     * @param permission The name of the permission being checked.
     *
     * @return Returns {@link PackageManager#PERMISSION_GRANTED} if the calling
     * pid/uid is allowed that permission, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see PackageManager#checkPermission(String, String)
     * @see #checkPermission
     * @see #checkCallingPermission
     */
    public abstract int checkCallingOrSelfPermission(String permission);

    /**
     * If the given permission is not allowed for a particular process
     * and user ID running in the system, throw a {@link SecurityException}.
     *
     * @param permission The name of the permission being checked.
     * @param pid The process ID being checked against.  Must be &gt; 0.
     * @param uid The user ID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param message A message to include in the exception if it is thrown.
     *
     * @see #checkPermission(String, int, int)
     */
    public abstract void enforcePermission(
            String permission, int pid, int uid, String message);

    /**
     * If the calling process of an IPC you are handling has not been
     * granted a particular permission, throw a {@link
     * SecurityException}.  This is basically the same as calling
     * {@link #enforcePermission(String, int, int, String)} with the
     * pid and uid returned by {@link android.os.Binder#getCallingPid}
     * and {@link android.os.Binder#getCallingUid}.  One important
     * difference is that if you are not currently processing an IPC,
     * this function will always throw the SecurityException.  This is
     * done to protect against accidentally leaking permissions; you
     * can use {@link #enforceCallingOrSelfPermission} to avoid this
     * protection.
     *
     * @param permission The name of the permission being checked.
     * @param message A message to include in the exception if it is thrown.
     *
     * @see #checkCallingPermission(String)
     */
    public abstract void enforceCallingPermission(
            String permission, String message);

    /**
     * If neither you nor the calling process of an IPC you are
     * handling has been granted a particular permission, throw a
     * {@link SecurityException}.  This is the same as {@link
     * #enforceCallingPermission}, except it grants your own
     * permissions if you are not currently processing an IPC.  Use
     * with care!
     *
     * @param permission The name of the permission being checked.
     * @param message A message to include in the exception if it is thrown.
     *
     * @see #checkCallingOrSelfPermission(String)
     */
    public abstract void enforceCallingOrSelfPermission(
            String permission, String message);

    /**
     * Grant permission to access a specific Uri to another package, regardless
     * of whether that package has general permission to access the Uri's
     * content provider.  This can be used to grant specific, temporary
     * permissions, typically in response to user interaction (such as the
     * user opening an attachment that you would like someone else to
     * display).
     *
     * <p>Normally you should use {@link Intent#FLAG_GRANT_READ_URI_PERMISSION
     * Intent.FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION
     * Intent.FLAG_GRANT_WRITE_URI_PERMISSION} with the Intent being used to
     * start an activity instead of this function directly.  If you use this
     * function directly, you should be sure to call
     * {@link #revokeUriPermission} when the target should no longer be allowed
     * to access it.
     *
     * <p>To succeed, the content provider owning the Uri must have set the
     * {@link android.R.styleable#AndroidManifestProvider_grantUriPermissions
     * grantUriPermissions} attribute in its manifest or included the
     * {@link android.R.styleable#AndroidManifestGrantUriPermission
     * &lt;grant-uri-permissions&gt;} tag.
     *
     * @param toPackage The package you would like to allow to access the Uri.
     * @param uri The Uri you would like to grant access to.
     * @param modeFlags The desired access modes.  Any combination of
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION
     * Intent.FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION
     * Intent.FLAG_GRANT_WRITE_URI_PERMISSION}.
     *
     * @see #revokeUriPermission
     */
    public abstract void grantUriPermission(String toPackage, Uri uri,
            int modeFlags);

    /**
     * Remove all permissions to access a particular content provider Uri
     * that were previously added with {@link #grantUriPermission}.  The given
     * Uri will match all previously granted Uris that are the same or a
     * sub-path of the given Uri.  That is, revoking "content://foo/one" will
     * revoke both "content://foo/target" and "content://foo/target/sub", but not
     * "content://foo".
     *
     * @param uri The Uri you would like to revoke access to.
     * @param modeFlags The desired access modes.  Any combination of
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION
     * Intent.FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION
     * Intent.FLAG_GRANT_WRITE_URI_PERMISSION}.
     *
     * @see #grantUriPermission
     */
    public abstract void revokeUriPermission(Uri uri, int modeFlags);

    /**
     * Determine whether a particular process and user ID has been granted
     * permission to access a specific URI.  This only checks for permissions
     * that have been explicitly granted -- if the given process/uid has
     * more general access to the URI's content provider then this check will
     * always fail.
     *
     * @param uri The uri that is being checked.
     * @param pid The process ID being checked against.  Must be &gt; 0.
     * @param uid The user ID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param modeFlags The type of access to grant.  May be one or both of
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION Intent.FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION Intent.FLAG_GRANT_WRITE_URI_PERMISSION}.
     *
     * @return Returns {@link PackageManager#PERMISSION_GRANTED} if the given
     * pid/uid is allowed to access that uri, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkCallingUriPermission
     */
    public abstract int checkUriPermission(Uri uri, int pid, int uid, int modeFlags);

    /**
     * Determine whether the calling process and user ID has been
     * granted permission to access a specific URI.  This is basically
     * the same as calling {@link #checkUriPermission(Uri, int, int,
     * int)} with the pid and uid returned by {@link
     * android.os.Binder#getCallingPid} and {@link
     * android.os.Binder#getCallingUid}.  One important difference is
     * that if you are not currently processing an IPC, this function
     * will always fail.
     *
     * @param uri The uri that is being checked.
     * @param modeFlags The type of access to grant.  May be one or both of
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION Intent.FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION Intent.FLAG_GRANT_WRITE_URI_PERMISSION}.
     *
     * @return Returns {@link PackageManager#PERMISSION_GRANTED} if the caller
     * is allowed to access that uri, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkUriPermission(Uri, int, int, int)
     */
    public abstract int checkCallingUriPermission(Uri uri, int modeFlags);

    /**
     * Determine whether the calling process of an IPC <em>or you</em> has been granted
     * permission to access a specific URI.  This is the same as
     * {@link #checkCallingUriPermission}, except it grants your own permissions
     * if you are not currently processing an IPC.  Use with care!
     *
     * @param uri The uri that is being checked.
     * @param modeFlags The type of access to grant.  May be one or both of
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION Intent.FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION Intent.FLAG_GRANT_WRITE_URI_PERMISSION}.
     *
     * @return Returns {@link PackageManager#PERMISSION_GRANTED} if the caller
     * is allowed to access that uri, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkCallingUriPermission
     */
    public abstract int checkCallingOrSelfUriPermission(Uri uri, int modeFlags);

    /**
     * Check both a Uri and normal permission.  This allows you to perform
     * both {@link #checkPermission} and {@link #checkUriPermission} in one
     * call.
     *
     * @param uri The Uri whose permission is to be checked, or null to not
     * do this check.
     * @param readPermission The permission that provides overall read access,
     * or null to not do this check.
     * @param writePermission The permission that provides overall write
     * acess, or null to not do this check.
     * @param pid The process ID being checked against.  Must be &gt; 0.
     * @param uid The user ID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param modeFlags The type of access to grant.  May be one or both of
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION Intent.FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION Intent.FLAG_GRANT_WRITE_URI_PERMISSION}.
     *
     * @return Returns {@link PackageManager#PERMISSION_GRANTED} if the caller
     * is allowed to access that uri or holds one of the given permissions, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     */
    public abstract int checkUriPermission(Uri uri, String readPermission,
            String writePermission, int pid, int uid, int modeFlags);

    /**
     * If a particular process and user ID has not been granted
     * permission to access a specific URI, throw {@link
     * SecurityException}.  This only checks for permissions that have
     * been explicitly granted -- if the given process/uid has more
     * general access to the URI's content provider then this check
     * will always fail.
     *
     * @param uri The uri that is being checked.
     * @param pid The process ID being checked against.  Must be &gt; 0.
     * @param uid The user ID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param modeFlags The type of access to grant.  May be one or both of
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION Intent.FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION Intent.FLAG_GRANT_WRITE_URI_PERMISSION}.
     * @param message A message to include in the exception if it is thrown.
     *
     * @see #checkUriPermission(Uri, int, int, int)
     */
    public abstract void enforceUriPermission(
            Uri uri, int pid, int uid, int modeFlags, String message);

    /**
     * If the calling process and user ID has not been granted
     * permission to access a specific URI, throw {@link
     * SecurityException}.  This is basically the same as calling
     * {@link #enforceUriPermission(Uri, int, int, int, String)} with
     * the pid and uid returned by {@link
     * android.os.Binder#getCallingPid} and {@link
     * android.os.Binder#getCallingUid}.  One important difference is
     * that if you are not currently processing an IPC, this function
     * will always throw a SecurityException.
     *
     * @param uri The uri that is being checked.
     * @param modeFlags The type of access to grant.  May be one or both of
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION Intent.FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION Intent.FLAG_GRANT_WRITE_URI_PERMISSION}.
     * @param message A message to include in the exception if it is thrown.
     *
     * @see #checkCallingUriPermission(Uri, int)
     */
    public abstract void enforceCallingUriPermission(
            Uri uri, int modeFlags, String message);

    /**
     * If the calling process of an IPC <em>or you</em> has not been
     * granted permission to access a specific URI, throw {@link
     * SecurityException}.  This is the same as {@link
     * #enforceCallingUriPermission}, except it grants your own
     * permissions if you are not currently processing an IPC.  Use
     * with care!
     *
     * @param uri The uri that is being checked.
     * @param modeFlags The type of access to grant.  May be one or both of
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION Intent.FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION Intent.FLAG_GRANT_WRITE_URI_PERMISSION}.
     * @param message A message to include in the exception if it is thrown.
     *
     * @see #checkCallingOrSelfUriPermission(Uri, int)
     */
    public abstract void enforceCallingOrSelfUriPermission(
            Uri uri, int modeFlags, String message);

    /**
     * Enforce both a Uri and normal permission.  This allows you to perform
     * both {@link #enforcePermission} and {@link #enforceUriPermission} in one
     * call.
     *
     * @param uri The Uri whose permission is to be checked, or null to not
     * do this check.
     * @param readPermission The permission that provides overall read access,
     * or null to not do this check.
     * @param writePermission The permission that provides overall write
     * acess, or null to not do this check.
     * @param pid The process ID being checked against.  Must be &gt; 0.
     * @param uid The user ID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param modeFlags The type of access to grant.  May be one or both of
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION Intent.FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION Intent.FLAG_GRANT_WRITE_URI_PERMISSION}.
     * @param message A message to include in the exception if it is thrown.
     *
     * @see #checkUriPermission(Uri, String, String, int, int, int)
     */
    public abstract void enforceUriPermission(
            Uri uri, String readPermission, String writePermission,
            int pid, int uid, int modeFlags, String message);

    /**
     * Flag for use with {@link #createPackageContext}: include the application
     * code with the context.  This means loading code into the caller's
     * process, so that {@link #getClassLoader()} can be used to instantiate
     * the application's classes.  Setting this flags imposes security
     * restrictions on what application context you can access; if the
     * requested application can not be safely loaded into your process,
     * java.lang.SecurityException will be thrown.  If this flag is not set,
     * there will be no restrictions on the packages that can be loaded,
     * but {@link #getClassLoader} will always return the default system
     * class loader.
     */
    public static final int CONTEXT_INCLUDE_CODE = 0x00000001;

    /**
     * Flag for use with {@link #createPackageContext}: ignore any security
     * restrictions on the Context being requested, allowing it to always
     * be loaded.  For use with {@link #CONTEXT_INCLUDE_CODE} to allow code
     * to be loaded into a process even when it isn't safe to do so.  Use
     * with extreme care!
     */
    public static final int CONTEXT_IGNORE_SECURITY = 0x00000002;

    /**
     * Flag for use with {@link #createPackageContext}: a restricted context may
     * disable specific features. For instance, a View associated with a restricted
     * context would ignore particular XML attributes.
     */
    public static final int CONTEXT_RESTRICTED = 0x00000004;

    /**
     * Return a new Context object for the given application name.  This
     * Context is the same as what the named application gets when it is
     * launched, containing the same resources and class loader.  Each call to
     * this method returns a new instance of a Context object; Context objects
     * are not shared, however they share common state (Resources, ClassLoader,
     * etc) so the Context instance itself is fairly lightweight.
     *
     * <p>Throws {@link PackageManager.NameNotFoundException} if there is no
     * application with the given package name.
     *
     * <p>Throws {@link java.lang.SecurityException} if the Context requested
     * can not be loaded into the caller's process for security reasons (see
     * {@link #CONTEXT_INCLUDE_CODE} for more information}.
     *
     * @param packageName Name of the application's package.
     * @param flags Option flags, one of {@link #CONTEXT_INCLUDE_CODE}
     *              or {@link #CONTEXT_IGNORE_SECURITY}.
     *
     * @return A Context for the application.
     *
     * @throws java.lang.SecurityException
     * @throws PackageManager.NameNotFoundException if there is no application with
     * the given package name
     */
    public abstract Context createPackageContext(String packageName,
            int flags) throws PackageManager.NameNotFoundException;

    /**
     * Indicates whether this Context is restricted.
     *
     * @return True if this Context is restricted, false otherwise.
     *
     * @see #CONTEXT_RESTRICTED
     */
    public boolean isRestricted() {
        return false;
    }
}
