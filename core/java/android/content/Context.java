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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
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
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.view.DisplayAdjustments;
import android.view.Display;
import android.view.ViewDebug;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
     * @deprecated Creating world-readable files is very dangerous, and likely
     * to cause security holes in applications.  It is strongly discouraged;
     * instead, applications should use more formal mechanism for interactions
     * such as {@link ContentProvider}, {@link BroadcastReceiver}, and
     * {@link android.app.Service}.  There are no guarantees that this
     * access mode will remain on a file, such as when it goes through a
     * backup and restore.
     * File creation mode: allow all other applications to have read access
     * to the created file.
     * @see #MODE_PRIVATE
     * @see #MODE_WORLD_WRITEABLE
     */
    @Deprecated
    public static final int MODE_WORLD_READABLE = 0x0001;
    /**
     * @deprecated Creating world-writable files is very dangerous, and likely
     * to cause security holes in applications.  It is strongly discouraged;
     * instead, applications should use more formal mechanism for interactions
     * such as {@link ContentProvider}, {@link BroadcastReceiver}, and
     * {@link android.app.Service}.  There are no guarantees that this
     * access mode will remain on a file, such as when it goes through a
     * backup and restore.
     * File creation mode: allow all other applications to have write access
     * to the created file.
     * @see #MODE_PRIVATE
     * @see #MODE_WORLD_READABLE
     */
    @Deprecated
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
     * Database open flag: when set, the database is opened with write-ahead
     * logging enabled by default.
     *
     * @see #openOrCreateDatabase(String, int, CursorFactory)
     * @see #openOrCreateDatabase(String, int, CursorFactory, DatabaseErrorHandler)
     * @see SQLiteDatabase#enableWriteAheadLogging
     */
    public static final int MODE_ENABLE_WRITE_AHEAD_LOGGING = 0x0008;

    /** @hide */
    @IntDef(flag = true,
            value = {
                BIND_AUTO_CREATE,
                BIND_AUTO_CREATE,
                BIND_DEBUG_UNBIND,
                BIND_NOT_FOREGROUND,
                BIND_ABOVE_CLIENT,
                BIND_ALLOW_OOM_MANAGEMENT,
                BIND_WAIVE_PRIORITY
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BindServiceFlags {}

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
     * memory killer kill the app before it kills the service it is bound to, though
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
    public static final int BIND_ADJUST_WITH_ACTIVITY = 0x0080;

    /**
     * @hide Flag for {@link #bindService}: Treat the binding as hosting
     * an activity, an unbinding as the activity going in the background.
     * That is, when unbinding, the process when empty will go on the activity
     * LRU list instead of the regular one, keeping it around more aggressively
     * than it otherwise would be.  This is intended for use with IMEs to try
     * to keep IME processes around for faster keyboard switching.
     */
    public static final int BIND_TREAT_LIKE_ACTIVITY = 0x08000000;

    /**
     * @hide An idea that is not yet implemented.
     * Flag for {@link #bindService}: If binding from an activity, consider
     * this service to be visible like the binding activity is.  That is,
     * it will be treated as something more important to keep around than
     * invisible background activities.  This will impact the number of
     * recent activities the user can switch between without having them
     * restart.  There is no guarantee this will be respected, as the system
     * tries to balance such requests from one app vs. the importantance of
     * keeping other apps around.
     */
    public static final int BIND_VISIBLE = 0x10000000;

    /**
     * @hide
     * Flag for {@link #bindService}: Consider this binding to be causing the target
     * process to be showing UI, so it will be do a UI_HIDDEN memory trim when it goes
     * away.
     */
    public static final int BIND_SHOWING_UI = 0x20000000;

    /**
     * Flag for {@link #bindService}: Don't consider the bound service to be
     * visible, even if the caller is visible.
     * @hide
     */
    public static final int BIND_NOT_VISIBLE = 0x40000000;

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
     * <p>
     * By definition, this method returns the same result as would be obtained
     * by calling {@link Looper#getMainLooper() Looper.getMainLooper()}.
     * </p>
     *
     * @return The main looper.
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
     * Remove a {@link ComponentCallbacks} object that was previously registered
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
     * Return a drawable object associated with a particular resource ID and
     * styled for the current theme.
     *
     * @param id The desired resource identifier, as generated by the aapt
     *           tool. This integer encodes the package, type, and resource
     *           entry. The value 0 is an invalid identifier.
     * @return Drawable An object that can be used to draw this resource.
     */
    public final Drawable getDrawable(int id) {
        return getResources().getDrawable(id, getTheme());
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
    @ViewDebug.ExportedProperty(deepExport = true)
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

    /** @hide Return the name of the base context this context is derived from. */
    public abstract String getBasePackageName();

    /** @hide Return the package name that should be used for app ops calls from
     * this context.  This is the same as {@link #getBasePackageName()} except in
     * cases where system components are loaded into other app processes, in which
     * case this will be the name of the primary package in that process (so that app
     * ops uid verification will work with the name). */
    public abstract String getOpPackageName();

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
     * is always on in apps targeting Gingerbread (Android 2.3) and below, and
     * off by default in later versions.
     *
     * @return The single {@link SharedPreferences} instance that can be used
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
     * @return The resulting {@link FileInputStream}.
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
     * <p>No permissions are required to invoke this method, since it uses internal
     * storage.
     *
     * @param name The name of the file to open; can not contain path
     *             separators.
     * @param mode Operating mode.  Use 0 or {@link #MODE_PRIVATE} for the
     * default operation, {@link #MODE_APPEND} to append to an existing file,
     * {@link #MODE_WORLD_READABLE} and {@link #MODE_WORLD_WRITEABLE} to control
     * permissions.
     *
     * @return The resulting {@link FileOutputStream}.
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
     * @return {@code true} if the file was successfully deleted; else
     *         {@code false}.
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
     * @return An absolute path to the given file.
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
     * <p>No permissions are required to read or write to the returned path, since this
     * path is internal storage.
     *
     * @return The path of the directory holding application files.
     *
     * @see #openFileOutput
     * @see #getFileStreamPath
     * @see #getDir
     */
    public abstract File getFilesDir();

    /**
     * Returns the absolute path to the directory on the filesystem similar to
     * {@link #getFilesDir()}.  The difference is that files placed under this
     * directory will be excluded from automatic backup to remote storage.  See
     * {@link android.app.backup.BackupAgent BackupAgent} for a full discussion
     * of the automatic backup mechanism in Android.
     *
     * <p>No permissions are required to read or write to the returned path, since this
     * path is internal storage.
     *
     * @return The path of the directory holding application files that will not be
     *         automatically backed up to remote storage.
     *
     * @see #openFileOutput
     * @see #getFileStreamPath
     * @see #getDir
     * @see android.app.backup.BackupAgent
     */
    public abstract File getNoBackupFilesDir();

    /**
     * Returns the absolute path to the directory on the primary external filesystem
     * (that is somewhere on {@link android.os.Environment#getExternalStorageDirectory()
     * Environment.getExternalStorageDirectory()}) where the application can
     * place persistent files it owns.  These files are internal to the
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
     * <li>There is no security enforced with these files.  For example, any application
     * holding {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} can write to
     * these files.
     * </ul>
     *
     * <p>Starting in {@link android.os.Build.VERSION_CODES#KITKAT}, no permissions
     * are required to read or write to the returned path; it's always
     * accessible to the calling app.  This only applies to paths generated for
     * package name of the calling application.  To access paths belonging
     * to other packages, {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE}
     * and/or {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} are required.
     *
     * <p>On devices with multiple users (as described by {@link UserManager}),
     * each user has their own isolated external storage. Applications only
     * have access to the external storage for the user they're running as.</p>
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
     * @return The path of the directory holding application files
     * on external storage.  Returns null if external storage is not currently
     * mounted so it could not ensure the path exists; you will need to call
     * this method again when it is available.
     *
     * @see #getFilesDir
     * @see android.os.Environment#getExternalStoragePublicDirectory
     */
    @Nullable
    public abstract File getExternalFilesDir(@Nullable String type);

    /**
     * Returns absolute paths to application-specific directories on all
     * external storage devices where the application can place persistent files
     * it owns. These files are internal to the application, and not typically
     * visible to the user as media.
     * <p>
     * This is like {@link #getFilesDir()} in that these files will be deleted when
     * the application is uninstalled, however there are some important differences:
     * <ul>
     * <li>External files are not always available: they will disappear if the
     * user mounts the external storage on a computer or removes it.
     * <li>There is no security enforced with these files.
     * </ul>
     * <p>
     * External storage devices returned here are considered a permanent part of
     * the device, including both emulated external storage and physical media
     * slots, such as SD cards in a battery compartment. The returned paths do
     * not include transient devices, such as USB flash drives.
     * <p>
     * An application may store data on any or all of the returned devices.  For
     * example, an app may choose to store large files on the device with the
     * most available space, as measured by {@link StatFs}.
     * <p>
     * No permissions are required to read or write to the returned paths; they
     * are always accessible to the calling app.  Write access outside of these
     * paths on secondary external storage devices is not available.
     * <p>
     * The first path returned is the same as {@link #getExternalFilesDir(String)}.
     * Returned paths may be {@code null} if a storage device is unavailable.
     *
     * @see #getExternalFilesDir(String)
     * @see Environment#getExternalStorageState(File)
     */
    public abstract File[] getExternalFilesDirs(String type);

    /**
     * Return the primary external storage directory where this application's OBB
     * files (if there are any) can be found. Note if the application does not have
     * any OBB files, this directory may not exist.
     * <p>
     * This is like {@link #getFilesDir()} in that these files will be deleted when
     * the application is uninstalled, however there are some important differences:
     * <ul>
     * <li>External files are not always available: they will disappear if the
     * user mounts the external storage on a computer or removes it.
     * <li>There is no security enforced with these files.  For example, any application
     * holding {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} can write to
     * these files.
     * </ul>
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#KITKAT}, no permissions
     * are required to read or write to the returned path; it's always
     * accessible to the calling app.  This only applies to paths generated for
     * package name of the calling application.  To access paths belonging
     * to other packages, {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE}
     * and/or {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} are required.
     * <p>
     * On devices with multiple users (as described by {@link UserManager}),
     * multiple users may share the same OBB storage location. Applications
     * should ensure that multiple instances running under different users don't
     * interfere with each other.
     */
    public abstract File getObbDir();

    /**
     * Returns absolute paths to application-specific directories on all
     * external storage devices where the application's OBB files (if there are
     * any) can be found. Note if the application does not have any OBB files,
     * these directories may not exist.
     * <p>
     * This is like {@link #getFilesDir()} in that these files will be deleted when
     * the application is uninstalled, however there are some important differences:
     * <ul>
     * <li>External files are not always available: they will disappear if the
     * user mounts the external storage on a computer or removes it.
     * <li>There is no security enforced with these files.
     * </ul>
     * <p>
     * External storage devices returned here are considered a permanent part of
     * the device, including both emulated external storage and physical media
     * slots, such as SD cards in a battery compartment. The returned paths do
     * not include transient devices, such as USB flash drives.
     * <p>
     * An application may store data on any or all of the returned devices.  For
     * example, an app may choose to store large files on the device with the
     * most available space, as measured by {@link StatFs}.
     * <p>
     * No permissions are required to read or write to the returned paths; they
     * are always accessible to the calling app.  Write access outside of these
     * paths on secondary external storage devices is not available.
     * <p>
     * The first path returned is the same as {@link #getObbDir()}.
     * Returned paths may be {@code null} if a storage device is unavailable.
     *
     * @see #getObbDir()
     * @see Environment#getExternalStorageState(File)
     */
    public abstract File[] getObbDirs();

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
     * @return The path of the directory holding application cache files.
     *
     * @see #openFileOutput
     * @see #getFileStreamPath
     * @see #getDir
     */
    public abstract File getCacheDir();

    /**
     * Returns the absolute path to the application specific cache directory on
     * the filesystem designed for storing cached code. The system will delete
     * any files stored in this location both when your specific application is
     * upgraded, and when the entire platform is upgraded.
     * <p>
     * This location is optimal for storing compiled or optimized code generated
     * by your application at runtime.
     * <p>
     * Apps require no extra permissions to read or write to the returned path,
     * since this path lives in their private storage.
     *
     * @return The path of the directory holding application code cache files.
     */
    public abstract File getCodeCacheDir();

    /**
     * Returns the absolute path to the directory on the primary external filesystem
     * (that is somewhere on {@link android.os.Environment#getExternalStorageDirectory()
     * Environment.getExternalStorageDirectory()} where the application can
     * place cache files it owns. These files are internal to the application, and
     * not typically visible to the user as media.
     *
     * <p>This is like {@link #getCacheDir()} in that these
     * files will be deleted when the application is uninstalled, however there
     * are some important differences:
     *
     * <ul>
     * <li>The platform does not always monitor the space available in external
     * storage, and thus may not automatically delete these files.  Currently
     * the only time files here will be deleted by the platform is when running
     * on {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1} or later and
     * {@link android.os.Environment#isExternalStorageEmulated()
     * Environment.isExternalStorageEmulated()} returns true.  Note that you should
     * be managing the maximum space you will use for these anyway, just like
     * with {@link #getCacheDir()}.
     * <li>External files are not always available: they will disappear if the
     * user mounts the external storage on a computer or removes it.  See the
     * APIs on {@link android.os.Environment} for information in the storage state.
     * <li>There is no security enforced with these files.  For example, any application
     * holding {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} can write to
     * these files.
     * </ul>
     *
     * <p>Starting in {@link android.os.Build.VERSION_CODES#KITKAT}, no permissions
     * are required to read or write to the returned path; it's always
     * accessible to the calling app.  This only applies to paths generated for
     * package name of the calling application.  To access paths belonging
     * to other packages, {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE}
     * and/or {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} are required.
     *
     * <p>On devices with multiple users (as described by {@link UserManager}),
     * each user has their own isolated external storage. Applications only
     * have access to the external storage for the user they're running as.</p>
     *
     * @return The path of the directory holding application cache files
     * on external storage.  Returns null if external storage is not currently
     * mounted so it could not ensure the path exists; you will need to call
     * this method again when it is available.
     *
     * @see #getCacheDir
     */
    @Nullable
    public abstract File getExternalCacheDir();

    /**
     * Returns absolute paths to application-specific directories on all
     * external storage devices where the application can place cache files it
     * owns. These files are internal to the application, and not typically
     * visible to the user as media.
     * <p>
     * This is like {@link #getCacheDir()} in that these files will be deleted when
     * the application is uninstalled, however there are some important differences:
     * <ul>
     * <li>External files are not always available: they will disappear if the
     * user mounts the external storage on a computer or removes it.
     * <li>There is no security enforced with these files.
     * </ul>
     * <p>
     * External storage devices returned here are considered a permanent part of
     * the device, including both emulated external storage and physical media
     * slots, such as SD cards in a battery compartment. The returned paths do
     * not include transient devices, such as USB flash drives.
     * <p>
     * An application may store data on any or all of the returned devices.  For
     * example, an app may choose to store large files on the device with the
     * most available space, as measured by {@link StatFs}.
     * <p>
     * No permissions are required to read or write to the returned paths; they
     * are always accessible to the calling app.  Write access outside of these
     * paths on secondary external storage devices is not available.
     * <p>
     * The first path returned is the same as {@link #getExternalCacheDir()}.
     * Returned paths may be {@code null} if a storage device is unavailable.
     *
     * @see #getExternalCacheDir()
     * @see Environment#getExternalStorageState(File)
     */
    public abstract File[] getExternalCacheDirs();

    /**
     * Returns absolute paths to application-specific directories on all
     * external storage devices where the application can place media files.
     * These files are scanned and made available to other apps through
     * {@link MediaStore}.
     * <p>
     * This is like {@link #getExternalFilesDirs} in that these files will be
     * deleted when the application is uninstalled, however there are some
     * important differences:
     * <ul>
     * <li>External files are not always available: they will disappear if the
     * user mounts the external storage on a computer or removes it.
     * <li>There is no security enforced with these files.
     * </ul>
     * <p>
     * External storage devices returned here are considered a permanent part of
     * the device, including both emulated external storage and physical media
     * slots, such as SD cards in a battery compartment. The returned paths do
     * not include transient devices, such as USB flash drives.
     * <p>
     * An application may store data on any or all of the returned devices. For
     * example, an app may choose to store large files on the device with the
     * most available space, as measured by {@link StatFs}.
     * <p>
     * No permissions are required to read or write to the returned paths; they
     * are always accessible to the calling app. Write access outside of these
     * paths on secondary external storage devices is not available.
     * <p>
     * Returned paths may be {@code null} if a storage device is unavailable.
     *
     * @see Environment#getExternalStorageState(File)
     */
    public abstract File[] getExternalMediaDirs();

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
     * @return A {@link File} object for the requested directory.  The directory
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
     *     Use {@link #MODE_ENABLE_WRITE_AHEAD_LOGGING} to enable write-ahead logging by default.
     * @param factory An optional factory class that is called to instantiate a
     *     cursor when query is called.
     *
     * @return The contents of a newly created database with the given name.
     * @throws android.database.sqlite.SQLiteException if the database file could not be opened.
     *
     * @see #MODE_PRIVATE
     * @see #MODE_WORLD_READABLE
     * @see #MODE_WORLD_WRITEABLE
     * @see #MODE_ENABLE_WRITE_AHEAD_LOGGING
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
     *     Use {@link #MODE_ENABLE_WRITE_AHEAD_LOGGING} to enable write-ahead logging by default.
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
     * @see #MODE_ENABLE_WRITE_AHEAD_LOGGING
     * @see #deleteDatabase
     */
    public abstract SQLiteDatabase openOrCreateDatabase(String name,
            int mode, CursorFactory factory,
            @Nullable DatabaseErrorHandler errorHandler);

    /**
     * Delete an existing private SQLiteDatabase associated with this Context's
     * application package.
     *
     * @param name The name (unique in the application package) of the
     *             database.
     *
     * @return {@code true} if the database was successfully deleted; else {@code false}.
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
     * @return An absolute path to the given database.
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
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#SET_WALLPAPER}.
     */
    @Deprecated
    public abstract void setWallpaper(Bitmap bitmap) throws IOException;

    /**
     * @deprecated Use {@link android.app.WallpaperManager#setStream(InputStream)
     * WallpaperManager.set()} instead.
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#SET_WALLPAPER}.
     */
    @Deprecated
    public abstract void setWallpaper(InputStream data) throws IOException;

    /**
     * @deprecated Use {@link android.app.WallpaperManager#clear
     * WallpaperManager.clear()} instead.
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#SET_WALLPAPER}.
     */
    @Deprecated
    public abstract void clearWallpaper() throws IOException;

    /**
     * Same as {@link #startActivity(Intent, Bundle)} with no options
     * specified.
     *
     * @param intent The description of the activity to start.
     *
     * @throws ActivityNotFoundException &nbsp;
     *
     * @see #startActivity(Intent, Bundle)
     * @see PackageManager#resolveActivity
     */
    public abstract void startActivity(Intent intent);

    /**
     * Version of {@link #startActivity(Intent)} that allows you to specify the
     * user the activity will be started for.  This is not available to applications
     * that are not pre-installed on the system image.  Using it requires holding
     * the INTERACT_ACROSS_USERS_FULL permission.
     * @param intent The description of the activity to start.
     * @param user The UserHandle of the user to start this activity for.
     * @throws ActivityNotFoundException &nbsp;
     * @hide
     */
    public void startActivityAsUser(Intent intent, UserHandle user) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

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
     * @param options Additional options for how the Activity should be started.
     * May be null if there are no options.  See {@link android.app.ActivityOptions}
     * for how to build the Bundle supplied here; there are no supported definitions
     * for building it manually.
     *
     * @throws ActivityNotFoundException &nbsp;
     *
     * @see #startActivity(Intent)
     * @see PackageManager#resolveActivity
     */
    public abstract void startActivity(Intent intent, @Nullable Bundle options);

    /**
     * Version of {@link #startActivity(Intent, Bundle)} that allows you to specify the
     * user the activity will be started for.  This is not available to applications
     * that are not pre-installed on the system image.  Using it requires holding
     * the INTERACT_ACROSS_USERS_FULL permission.
     * @param intent The description of the activity to start.
     * @param options Additional options for how the Activity should be started.
     * May be null if there are no options.  See {@link android.app.ActivityOptions}
     * for how to build the Bundle supplied here; there are no supported definitions
     * for building it manually.
     * @param userId The UserHandle of the user to start this activity for.
     * @throws ActivityNotFoundException &nbsp;
     * @hide
     */
    public void startActivityAsUser(Intent intent, @Nullable Bundle options, UserHandle userId) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Same as {@link #startActivities(Intent[], Bundle)} with no options
     * specified.
     *
     * @param intents An array of Intents to be started.
     *
     * @throws ActivityNotFoundException &nbsp;
     *
     * @see #startActivities(Intent[], Bundle)
     * @see PackageManager#resolveActivity
     */
    public abstract void startActivities(Intent[] intents);

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
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)
     * Context.startActivity(Intent, Bundle)} for more details.
     *
     * @throws ActivityNotFoundException &nbsp;
     *
     * @see #startActivities(Intent[])
     * @see PackageManager#resolveActivity
     */
    public abstract void startActivities(Intent[] intents, Bundle options);

    /**
     * @hide
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
     * @param options Additional options for how the Activity should be started.
     * @param userHandle The user for whom to launch the activities
     * See {@link android.content.Context#startActivity(Intent, Bundle)
     * Context.startActivity(Intent, Bundle)} for more details.
     *
     * @throws ActivityNotFoundException &nbsp;
     *
     * @see #startActivities(Intent[])
     * @see PackageManager#resolveActivity
     */
    public void startActivitiesAsUser(Intent[] intents, Bundle options, UserHandle userHandle) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Same as {@link #startIntentSender(IntentSender, Intent, int, int, int, Bundle)}
     * with no options specified.
     *
     * @param intent The IntentSender to launch.
     * @param fillInIntent If non-null, this will be provided as the
     * intent parameter to {@link IntentSender#sendIntent}.
     * @param flagsMask Intent flags in the original IntentSender that you
     * would like to change.
     * @param flagsValues Desired values for any bits set in
     * <var>flagsMask</var>
     * @param extraFlags Always set to 0.
     *
     * @see #startActivity(Intent)
     * @see #startIntentSender(IntentSender, Intent, int, int, int, Bundle)
     */
    public abstract void startIntentSender(IntentSender intent,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException;

    /**
     * Like {@link #startActivity(Intent, Bundle)}, but taking a IntentSender
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
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)
     * Context.startActivity(Intent, Bundle)} for more details.  If options
     * have also been supplied by the IntentSender, options given here will
     * override any that conflict with those given by the IntentSender.
     *
     * @see #startActivity(Intent, Bundle)
     * @see #startIntentSender(IntentSender, Intent, int, int, int)
     */
    public abstract void startIntentSender(IntentSender intent,
            @Nullable Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags,
            Bundle options) throws IntentSender.SendIntentException;

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
            @Nullable String receiverPermission);

    /**
     * Like {@link #sendBroadcast(Intent, String)}, but also allows specification
     * of an associated app op as per {@link android.app.AppOpsManager}.
     * @hide
     */
    public abstract void sendBroadcast(Intent intent,
            String receiverPermission, int appOp);

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
            @Nullable String receiverPermission);

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
    public abstract void sendOrderedBroadcast(@NonNull Intent intent,
            @Nullable String receiverPermission, BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable Bundle initialExtras);

    /**
     * Like {@link #sendOrderedBroadcast(Intent, String, BroadcastReceiver, android.os.Handler,
     * int, String, android.os.Bundle)}, but also allows specification
     * of an associated app op as per {@link android.app.AppOpsManager}.
     * @hide
     */
    public abstract void sendOrderedBroadcast(Intent intent,
            String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras);

    /**
     * Version of {@link #sendBroadcast(Intent)} that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.  Using it requires holding
     * the INTERACT_ACROSS_USERS permission.
     * @param intent The intent to broadcast
     * @param user UserHandle to send the intent to.
     * @see #sendBroadcast(Intent)
     */
    public abstract void sendBroadcastAsUser(Intent intent, UserHandle user);

    /**
     * Version of {@link #sendBroadcast(Intent, String)} that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.  Using it requires holding
     * the INTERACT_ACROSS_USERS permission.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param user UserHandle to send the intent to.
     * @param receiverPermission (optional) String naming a permission that
     *               a receiver must hold in order to receive your broadcast.
     *               If null, no permission is required.
     *
     * @see #sendBroadcast(Intent, String)
     */
    public abstract void sendBroadcastAsUser(Intent intent, UserHandle user,
            @Nullable String receiverPermission);

    /**
     * Version of
     * {@link #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)}
     * that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.  Using it requires holding
     * the INTERACT_ACROSS_USERS permission.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param user UserHandle to send the intent to.
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
     * @see #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)
     */
    public abstract void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            @Nullable String receiverPermission, BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable  Bundle initialExtras);

    /**
     * Similar to above but takes an appOp as well, to enforce restrictions.
     * @see #sendOrderedBroadcastAsUser(Intent, UserHandle, String,
     *       BroadcastReceiver, Handler, int, String, Bundle)
     * @hide
     */
    public abstract void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            @Nullable String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable  Bundle initialExtras);

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
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable Bundle initialExtras);

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
     * Version of {@link #sendStickyBroadcast(Intent)} that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.  Using it requires holding
     * the INTERACT_ACROSS_USERS permission.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     * Intent will receive the broadcast, and the Intent will be held to
     * be re-broadcast to future receivers.
     * @param user UserHandle to send the intent to.
     *
     * @see #sendBroadcast(Intent)
     */
    public abstract void sendStickyBroadcastAsUser(Intent intent, UserHandle user);

    /**
     * Version of
     * {@link #sendStickyOrderedBroadcast(Intent, BroadcastReceiver, Handler, int, String, Bundle)}
     * that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.  Using it requires holding
     * the INTERACT_ACROSS_USERS permission.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param user UserHandle to send the intent to.
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
     * @see #sendStickyOrderedBroadcast(Intent, BroadcastReceiver, Handler, int, String, Bundle)
     */
    public abstract void sendStickyOrderedBroadcastAsUser(Intent intent,
            UserHandle user, BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable Bundle initialExtras);

    /**
     * Version of {@link #removeStickyBroadcast(Intent)} that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.  Using it requires holding
     * the INTERACT_ACROSS_USERS permission.
     *
     * <p>You must hold the {@link android.Manifest.permission#BROADCAST_STICKY}
     * permission in order to use this API.  If you do not hold that
     * permission, {@link SecurityException} will be thrown.
     *
     * @param intent The Intent that was previously broadcast.
     * @param user UserHandle to remove the sticky broadcast from.
     *
     * @see #sendStickyBroadcastAsUser
     */
    public abstract void removeStickyBroadcastAsUser(Intent intent, UserHandle user);

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
    @Nullable
    public abstract Intent registerReceiver(@Nullable BroadcastReceiver receiver,
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
    @Nullable
    public abstract Intent registerReceiver(BroadcastReceiver receiver,
            IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler);

    /**
     * @hide
     * Same as {@link #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler)
     * but for a specific user.  This receiver will receiver broadcasts that
     * are sent to the requested user.  It
     * requires holding the {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL}
     * permission.
     *
     * @param receiver The BroadcastReceiver to handle the broadcast.
     * @param user UserHandle to send the intent to.
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
     * @see #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler
     * @see #sendBroadcast
     * @see #unregisterReceiver
     */
    @Nullable
    public abstract Intent registerReceiverAsUser(BroadcastReceiver receiver,
            UserHandle user, IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler);

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
     * should contain either contain the complete class name of a specific service
     * implementation to start or a specific package name to target.  If the
     * Intent is less specified, it log a warning about this and which of the
     * multiple matching services it finds and uses will be undefined.  If this service
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
     * @param service Identifies the service to be started.  The Intent must be either
     *      fully explicit (supplying a component name) or specify a specific package
     *      name it is targetted to.  Additional values
     *      may be included in the Intent extras to supply arguments along with
     *      this specific start call.
     *
     * @return If the service is being started or is already running, the
     * {@link ComponentName} of the actual service that was started is
     * returned; else if the service does not exist null is returned.
     *
     * @throws SecurityException &nbsp;
     *
     * @see #stopService
     * @see #bindService
     */
    @Nullable
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
     * @param service Description of the service to be stopped.  The Intent must be either
     *      fully explicit (supplying a component name) or specify a specific package
     *      name it is targetted to.
     *
     * @return If there is a service matching the given Intent that is already
     * running, then it is stopped and {@code true} is returned; else {@code false} is returned.
     *
     * @throws SecurityException &nbsp;
     *
     * @see #startService
     */
    public abstract boolean stopService(Intent service);

    /**
     * @hide like {@link #startService(Intent)} but for a specific user.
     */
    public abstract ComponentName startServiceAsUser(Intent service, UserHandle user);

    /**
     * @hide like {@link #stopService(Intent)} but for a specific user.
     */
    public abstract boolean stopServiceAsUser(Intent service, UserHandle user);

    /**
     * Connect to an application service, creating it if needed.  This defines
     * a dependency between your application and the service.  The given
     * <var>conn</var> will receive the service object when it is created and be
     * told if it dies and restarts.  The service will be considered required
     * by the system only for as long as the calling context exists.  For
     * example, if this Context is an Activity that is stopped, the service will
     * not be required to continue running until the Activity is resumed.
     *
     * <p>This function will throw {@link SecurityException} if you do not
     * have permission to bind to the given service.
     *
     * <p class="note">Note: this method <em>can not be called from a
     * {@link BroadcastReceiver} component</em>.  A pattern you can use to
     * communicate from a BroadcastReceiver to a Service is to call
     * {@link #startService} with the arguments containing the command to be
     * sent, with the service calling its
     * {@link android.app.Service#stopSelf(int)} method when done executing
     * that command.  See the API demo App/Service/Service Start Arguments
     * Controller for an illustration of this.  It is okay, however, to use
     * this method from a BroadcastReceiver that has been registered with
     * {@link #registerReceiver}, since the lifetime of this BroadcastReceiver
     * is tied to another object (the one that registered it).</p>
     *
     * @param service Identifies the service to connect to.  The Intent may
     *      specify either an explicit component name, or a logical
     *      description (action, category, etc) to match an
     *      {@link IntentFilter} published by a service.
     * @param conn Receives information as the service is started and stopped.
     *      This must be a valid ServiceConnection object; it must not be null.
     * @param flags Operation options for the binding.  May be 0,
     *          {@link #BIND_AUTO_CREATE}, {@link #BIND_DEBUG_UNBIND},
     *          {@link #BIND_NOT_FOREGROUND}, {@link #BIND_ABOVE_CLIENT},
     *          {@link #BIND_ALLOW_OOM_MANAGEMENT}, or
     *          {@link #BIND_WAIVE_PRIORITY}.
     * @return If you have successfully bound to the service, {@code true} is returned;
     *         {@code false} is returned if the connection is not made so you will not
     *         receive the service object.
     *
     * @throws SecurityException &nbsp;
     *
     * @see #unbindService
     * @see #startService
     * @see #BIND_AUTO_CREATE
     * @see #BIND_DEBUG_UNBIND
     * @see #BIND_NOT_FOREGROUND
     */
    public abstract boolean bindService(Intent service, @NonNull ServiceConnection conn,
            @BindServiceFlags int flags);

    /**
     * Same as {@link #bindService(Intent, ServiceConnection, int)}, but with an explicit userHandle
     * argument for use by system server and other multi-user aware code.
     * @hide
     */
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags, UserHandle user) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Disconnect from an application service.  You will no longer receive
     * calls as the service is restarted, and the service is now allowed to
     * stop at any time.
     *
     * @param conn The connection interface previously supplied to
     *             bindService().  This parameter must not be null.
     *
     * @see #bindService
     */
    public abstract void unbindService(@NonNull ServiceConnection conn);

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
     * @return {@code true} if the instrumentation was successfully started,
     * else {@code false} if it could not be found.
     */
    public abstract boolean startInstrumentation(@NonNull ComponentName className,
            @Nullable String profileFile, @Nullable Bundle arguments);

    /** @hide */
    @StringDef({
            POWER_SERVICE,
            WINDOW_SERVICE,
            LAYOUT_INFLATER_SERVICE,
            ACCOUNT_SERVICE,
            ACTIVITY_SERVICE,
            ALARM_SERVICE,
            NOTIFICATION_SERVICE,
            ACCESSIBILITY_SERVICE,
            CAPTIONING_SERVICE,
            KEYGUARD_SERVICE,
            LOCATION_SERVICE,
            //@hide: COUNTRY_DETECTOR,
            SEARCH_SERVICE,
            SENSOR_SERVICE,
            STORAGE_SERVICE,
            WALLPAPER_SERVICE,
            VIBRATOR_SERVICE,
            //@hide: STATUS_BAR_SERVICE,
            CONNECTIVITY_SERVICE,
            //@hide: UPDATE_LOCK_SERVICE,
            //@hide: NETWORKMANAGEMENT_SERVICE,
            //@hide: NETWORK_STATS_SERVICE,
            //@hide: NETWORK_POLICY_SERVICE,
            WIFI_SERVICE,
            WIFI_PASSPOINT_SERVICE,
            WIFI_P2P_SERVICE,
            WIFI_SCANNING_SERVICE,
            //@hide: ETHERNET_SERVICE,
            WIFI_RTT_SERVICE,
            NSD_SERVICE,
            AUDIO_SERVICE,
            MEDIA_ROUTER_SERVICE,
            TELEPHONY_SERVICE,
            TELECOMM_SERVICE,
            CLIPBOARD_SERVICE,
            INPUT_METHOD_SERVICE,
            TEXT_SERVICES_MANAGER_SERVICE,
            APPWIDGET_SERVICE,
            //@hide: BACKUP_SERVICE,
            DROPBOX_SERVICE,
            DEVICE_POLICY_SERVICE,
            UI_MODE_SERVICE,
            DOWNLOAD_SERVICE,
            NFC_SERVICE,
            BLUETOOTH_SERVICE,
            //@hide: SIP_SERVICE,
            USB_SERVICE,
            LAUNCHER_APPS_SERVICE,
            //@hide: SERIAL_SERVICE,
            INPUT_SERVICE,
            DISPLAY_SERVICE,
            //@hide: SCHEDULING_POLICY_SERVICE,
            USER_SERVICE,
            //@hide: APP_OPS_SERVICE
            CAMERA_SERVICE,
            PRINT_SERVICE,
            MEDIA_SESSION_SERVICE,
            BATTERY_SERVICE,
            JOB_SCHEDULER_SERVICE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceName {}

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
     *  <dt> {@link #WIFI_P2P_SERVICE} ("wifip2p")
     *  <dd> A {@link android.net.wifi.p2p.WifiP2pManager WifiP2pManager} for management of
     * Wi-Fi Direct connectivity.
     * <dt> {@link #INPUT_METHOD_SERVICE} ("input_method")
     * <dd> An {@link android.view.inputmethod.InputMethodManager InputMethodManager}
     * for management of input methods.
     * <dt> {@link #UI_MODE_SERVICE} ("uimode")
     * <dd> An {@link android.app.UiModeManager} for controlling UI modes.
     * <dt> {@link #DOWNLOAD_SERVICE} ("download")
     * <dd> A {@link android.app.DownloadManager} for requesting HTTP downloads
     * <dt> {@link #BATTERY_SERVICE} ("batterymanager")
     * <dd> A {@link android.os.BatteryManager} for managing battery state
     * <dt> {@link #JOB_SCHEDULER_SERVICE} ("taskmanager")
     * <dd>  A {@link android.app.job.JobScheduler} for managing scheduled tasks
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
     * @see #MEDIA_ROUTER_SERVICE
     * @see android.media.MediaRouter
     * @see #TELEPHONY_SERVICE
     * @see android.telephony.TelephonyManager
     * @see #PHONE_SERVICE
     * @see android.phone.PhoneManager
     * @see #INPUT_METHOD_SERVICE
     * @see android.view.inputmethod.InputMethodManager
     * @see #UI_MODE_SERVICE
     * @see android.app.UiModeManager
     * @see #DOWNLOAD_SERVICE
     * @see android.app.DownloadManager
     * @see #BATTERY_SERVICE
     * @see android.os.BatteryManager
     * @see #JOB_SCHEDULER_SERVICE
     * @see android.app.job.JobScheduler
     */
    public abstract Object getSystemService(@ServiceName @NonNull String name);

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
     * {@link android.view.accessibility.CaptioningManager} for obtaining
     * captioning properties and listening for changes in captioning
     * preferences.
     *
     * @see #getSystemService
     * @see android.view.accessibility.CaptioningManager
     */
    public static final String CAPTIONING_SERVICE = "captioning";

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
     * android.os.IUpdateLock} for managing runtime sequences that
     * must not be interrupted by headless OTA application or similar.
     *
     * @hide
     * @see #getSystemService
     * @see android.os.UpdateLock
     */
    public static final String UPDATE_LOCK_SERVICE = "updatelock";

    /**
     * Constant for the internal network management service, not really a Context service.
     * @hide
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
     * android.net.wifi.passpoint.WifiPasspointManager} for handling management of
     * Wi-Fi passpoint access.
     *
     * @see #getSystemService
     * @see android.net.wifi.passpoint.WifiPasspointManager
     * @hide
     */
    public static final String WIFI_PASSPOINT_SERVICE = "wifipasspoint";

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
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.net.wifi.WifiScanner} for scanning the wifi universe
     *
     * @see #getSystemService
     * @see android.net.wifi.WifiScanner
     * @hide
     */
    @SystemApi
    public static final String WIFI_SCANNING_SERVICE = "wifiscanner";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.net.wifi.RttManager} for ranging devices with wifi
     *
     * @see #getSystemService
     * @see android.net.wifi.RttManager
     * @hide
     */
    @SystemApi
    public static final String WIFI_RTT_SERVICE = "rttmanager";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.net.EthernetManager} for handling management of
     * Ethernet access.
     *
     * @see #getSystemService
     * @see android.net.EthernetManager
     *
     * @hide
     */
    public static final String ETHERNET_SERVICE = "ethernet";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.net.nsd.NsdManager} for handling management of network service
     * discovery
     *
     * @see #getSystemService
     * @see android.net.nsd.NsdManager
     */
    public static final String NSD_SERVICE = "servicediscovery";

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
     * {@link android.service.fingerprint.FingerprintManager} for handling management
     * of fingerprints.
     *
     * @see #getSystemService
     * @see android.app.FingerprintManager
     * @hide
     */
    public static final String FINGERPRINT_SERVICE = "fingerprint";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.media.MediaRouter} for controlling and managing
     * routing of media.
     *
     * @see #getSystemService
     * @see android.media.MediaRouter
     */
    public static final String MEDIA_ROUTER_SERVICE = "media_router";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.media.session.MediaSessionManager} for managing media Sessions.
     *
     * @see #getSystemService
     * @see android.media.session.MediaSessionManager
     */
    public static final String MEDIA_SESSION_SERVICE = "media_session";

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
     * {@link android.telecomm.TelecommManager} to manage telecomm-related features
     * of the device.
     *
     * @see #getSystemService
     * @see android.telecomm.TelecommManager
     */
    public static final String TELECOMM_SERVICE = "telecomm";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.phone.PhoneManager} to manage phone-related features
     * of the device.
     *
     * @see #getSystemService
     * @see android.phone.PhoneManager
     */
    public static final String PHONE_SERVICE = "phone_service";  // "phone" used by telephony.

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
     * @see #getSystemService
     */
    public static final String APPWIDGET_SERVICE = "appwidget";

    /**
     * Official published name of the (internal) voice interaction manager service.
     *
     * @hide
     * @see #getSystemService
     */
    public static final String VOICE_INTERACTION_MANAGER_SERVICE = "voiceinteraction";

    /**
     * Use with {@link #getSystemService} to retrieve an
     * {@link android.app.backup.IBackupManager IBackupManager} for communicating
     * with the backup mechanism.
     * @hide
     *
     * @see #getSystemService
     */
    @SystemApi
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
     * {@link android.os.BatteryManager} for managing battery state.
     *
     * @see #getSystemService
     */
    public static final String BATTERY_SERVICE = "batterymanager";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.nfc.NfcManager} for using NFC.
     *
     * @see #getSystemService
     */
    public static final String NFC_SERVICE = "nfc";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.bluetooth.BluetoothAdapter} for using Bluetooth.
     *
     * @see #getSystemService
     */
    public static final String BLUETOOTH_SERVICE = "bluetooth";

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
     * @see android.hardware.usb.UsbManager
     */
    public static final String USB_SERVICE = "usb";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.hardware.SerialManager} for access to serial ports.
     *
     * @see #getSystemService
     * @see android.hardware.SerialManager
     *
     * @hide
     */
    public static final String SERIAL_SERVICE = "serial";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.hardware.hdmi.HdmiControlManager} for controlling and managing
     * HDMI-CEC protocol.
     *
     * @see #getSystemService
     * @see android.hardware.hdmi.HdmiControlManager
     * @hide
     */
    @SystemApi
    public static final String HDMI_CONTROL_SERVICE = "hdmi_control";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.hardware.input.InputManager} for interacting with input devices.
     *
     * @see #getSystemService
     * @see android.hardware.input.InputManager
     */
    public static final String INPUT_SERVICE = "input";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.hardware.display.DisplayManager} for interacting with display devices.
     *
     * @see #getSystemService
     * @see android.hardware.display.DisplayManager
     */
    public static final String DISPLAY_SERVICE = "display";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.os.UserManager} for managing users on devices that support multiple users.
     *
     * @see #getSystemService
     * @see android.os.UserManager
     */
    public static final String USER_SERVICE = "user";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.content.pm.LauncherApps} for querying and monitoring launchable apps across
     * profiles of a user.
     *
     * @see #getSystemService
     * @see android.content.pm.LauncherApps
     */
    public static final String LAUNCHER_APPS_SERVICE = "launcherapps";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.content.RestrictionsManager} for retrieving application restrictions
     * and requesting permissions for restricted operations.
     * @see #getSystemService
     * @see android.content.RestrictionsManager
     */
    public static final String RESTRICTIONS_SERVICE = "restrictions";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.app.AppOpsManager} for tracking application operations
     * on the device.
     *
     * @see #getSystemService
     * @see android.app.AppOpsManager
     */
    public static final String APP_OPS_SERVICE = "appops";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.hardware.camera2.CameraManager} for interacting with
     * camera devices.
     *
     * @see #getSystemService
     * @see android.hardware.camera2.CameraManager
     */
    public static final String CAMERA_SERVICE = "camera";

    /**
     * {@link android.print.PrintManager} for printing and managing
     * printers and print tasks.
     *
     * @see #getSystemService
     * @see android.print.PrintManager
     */
    public static final String PRINT_SERVICE = "print";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.hardware.ConsumerIrManager} for transmitting infrared
     * signals from the device.
     *
     * @see #getSystemService
     * @see android.hardware.ConsumerIrManager
     */
    public static final String CONSUMER_IR_SERVICE = "consumer_ir";

    /**
     * {@link android.app.trust.TrustManager} for managing trust agents.
     * @see #getSystemService
     * @see android.app.trust.TrustManager
     * @hide
     */
    public static final String TRUST_SERVICE = "trust";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.media.tv.TvInputManager} for interacting with TV inputs
     * on the device.
     *
     * @see #getSystemService
     * @see android.media.tv.TvInputManager
     */
    public static final String TV_INPUT_SERVICE = "tv_input";

    /**
     * {@link android.net.NetworkScoreManager} for managing network scoring.
     * @see #getSystemService
     * @see android.net.NetworkScoreManager
     * @hide
     */
    @SystemApi
    public static final String NETWORK_SCORE_SERVICE = "network_score";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.app.UsageStatsManager} for interacting with the status bar.
     *
     * @see #getSystemService
     * @see android.app.UsageStatsManager
     * @hide
     */
    public static final String USAGE_STATS_SERVICE = "usagestats";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.app.job.JobScheduler} instance for managing occasional
     * background tasks.
     * @see #getSystemService
     * @see android.app.job.JobScheduler
     */
    public static final String JOB_SCHEDULER_SERVICE = "jobscheduler";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.service.persistentdata.PersistentDataBlockManager} instance retrieving
     * a file descriptor for a persistent data block.
     * @see #getSystemService
     * @see android.service.persistentdata.PersistentDataBlockManager
     * @hide
     */
    public static final String PERSISTENT_DATA_BLOCK_SERVICE = "persistent_data_block";

    /**
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.media.projection.MediaProjectionManager} instance for managing
     * media projection sessions.
     * @see #getSystemService
     * @see android.media.projection.ProjectionManager
     */
    public static final String MEDIA_PROJECTION_SERVICE = "media_projection";

    /**
     * Determine whether the given permission is allowed for a particular
     * process and user ID running in the system.
     *
     * @param permission The name of the permission being checked.
     * @param pid The process ID being checked against.  Must be > 0.
     * @param uid The user ID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} if the given
     * pid/uid is allowed that permission, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see PackageManager#checkPermission(String, String)
     * @see #checkCallingPermission
     */
    @PackageManager.PermissionResult
    public abstract int checkPermission(@NonNull String permission, int pid, int uid);

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
     * @return {@link PackageManager#PERMISSION_GRANTED} if the calling
     * pid/uid is allowed that permission, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see PackageManager#checkPermission(String, String)
     * @see #checkPermission
     * @see #checkCallingOrSelfPermission
     */
    @PackageManager.PermissionResult
    public abstract int checkCallingPermission(@NonNull String permission);

    /**
     * Determine whether the calling process of an IPC <em>or you</em> have been
     * granted a particular permission.  This is the same as
     * {@link #checkCallingPermission}, except it grants your own permissions
     * if you are not currently processing an IPC.  Use with care!
     *
     * @param permission The name of the permission being checked.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} if the calling
     * pid/uid is allowed that permission, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see PackageManager#checkPermission(String, String)
     * @see #checkPermission
     * @see #checkCallingPermission
     */
    @PackageManager.PermissionResult
    public abstract int checkCallingOrSelfPermission(@NonNull String permission);

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
            @NonNull String permission, int pid, int uid, @Nullable String message);

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
            @NonNull String permission, @Nullable String message);

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
            @NonNull String permission, @Nullable String message);

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
     * Intent.FLAG_GRANT_READ_URI_PERMISSION},
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION
     * Intent.FLAG_GRANT_WRITE_URI_PERMISSION},
     * {@link Intent#FLAG_GRANT_PERSISTABLE_URI_PERMISSION
     * Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION}, or
     * {@link Intent#FLAG_GRANT_PREFIX_URI_PERMISSION
     * Intent.FLAG_GRANT_PREFIX_URI_PERMISSION}.
     *
     * @see #revokeUriPermission
     */
    public abstract void grantUriPermission(String toPackage, Uri uri,
            @Intent.GrantUriMode int modeFlags);

    /**
     * Remove all permissions to access a particular content provider Uri
     * that were previously added with {@link #grantUriPermission}.  The given
     * Uri will match all previously granted Uris that are the same or a
     * sub-path of the given Uri.  That is, revoking "content://foo/target" will
     * revoke both "content://foo/target" and "content://foo/target/sub", but not
     * "content://foo".  It will not remove any prefix grants that exist at a
     * higher level.
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
    public abstract void revokeUriPermission(Uri uri, @Intent.AccessUriMode int modeFlags);

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
     * @return {@link PackageManager#PERMISSION_GRANTED} if the given
     * pid/uid is allowed to access that uri, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkCallingUriPermission
     */
    public abstract int checkUriPermission(Uri uri, int pid, int uid,
            @Intent.AccessUriMode int modeFlags);

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
     * @return {@link PackageManager#PERMISSION_GRANTED} if the caller
     * is allowed to access that uri, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkUriPermission(Uri, int, int, int)
     */
    public abstract int checkCallingUriPermission(Uri uri, @Intent.AccessUriMode int modeFlags);

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
     * @return {@link PackageManager#PERMISSION_GRANTED} if the caller
     * is allowed to access that uri, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkCallingUriPermission
     */
    public abstract int checkCallingOrSelfUriPermission(Uri uri,
            @Intent.AccessUriMode int modeFlags);

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
     * access, or null to not do this check.
     * @param pid The process ID being checked against.  Must be &gt; 0.
     * @param uid The user ID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param modeFlags The type of access to grant.  May be one or both of
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION Intent.FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION Intent.FLAG_GRANT_WRITE_URI_PERMISSION}.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} if the caller
     * is allowed to access that uri or holds one of the given permissions, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     */
    public abstract int checkUriPermission(@Nullable Uri uri, @Nullable String readPermission,
            @Nullable String writePermission, int pid, int uid,
            @Intent.AccessUriMode int modeFlags);

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
            Uri uri, int pid, int uid, @Intent.AccessUriMode int modeFlags, String message);

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
            Uri uri, @Intent.AccessUriMode int modeFlags, String message);

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
            Uri uri, @Intent.AccessUriMode int modeFlags, String message);

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
     * access, or null to not do this check.
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
            @Nullable Uri uri, @Nullable String readPermission,
            @Nullable String writePermission, int pid, int uid, @Intent.AccessUriMode int modeFlags,
            @Nullable String message);

    /** @hide */
    @IntDef(flag = true,
            value = {CONTEXT_INCLUDE_CODE, CONTEXT_IGNORE_SECURITY, CONTEXT_RESTRICTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CreatePackageOptions {}

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
     * @hide Used to indicate we should tell the activity manager about the process
     * loading this code.
     */
    public static final int CONTEXT_REGISTER_PACKAGE = 0x40000000;

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
     * @return A {@link Context} for the application.
     *
     * @throws SecurityException &nbsp;
     * @throws PackageManager.NameNotFoundException if there is no application with
     * the given package name.
     */
    public abstract Context createPackageContext(String packageName,
            @CreatePackageOptions int flags) throws PackageManager.NameNotFoundException;

    /**
     * Similar to {@link #createPackageContext(String, int)}, but with a
     * different {@link UserHandle}. For example, {@link #getContentResolver()}
     * will open any {@link Uri} as the given user.
     *
     * @hide
     */
    public abstract Context createPackageContextAsUser(
            String packageName, int flags, UserHandle user)
            throws PackageManager.NameNotFoundException;

    /**
     * Creates a context given an {@link android.content.pm.ApplicationInfo}.
     *
     * @hide
     */
    public abstract Context createApplicationContext(ApplicationInfo application,
            int flags) throws PackageManager.NameNotFoundException;

    /**
     * Get the userId associated with this context
     * @return user id
     *
     * @hide
     */
    public abstract int getUserId();

    /**
     * Return a new Context object for the current Context but whose resources
     * are adjusted to match the given Configuration.  Each call to this method
     * returns a new instance of a Context object; Context objects are not
     * shared, however common state (ClassLoader, other Resources for the
     * same configuration) may be so the Context itself can be fairly lightweight.
     *
     * @param overrideConfiguration A {@link Configuration} specifying what
     * values to modify in the base Configuration of the original Context's
     * resources.  If the base configuration changes (such as due to an
     * orientation change), the resources of this context will also change except
     * for those that have been explicitly overridden with a value here.
     *
     * @return A {@link Context} with the given configuration override.
     */
    public abstract Context createConfigurationContext(
            @NonNull Configuration overrideConfiguration);

    /**
     * Return a new Context object for the current Context but whose resources
     * are adjusted to match the metrics of the given Display.  Each call to this method
     * returns a new instance of a Context object; Context objects are not
     * shared, however common state (ClassLoader, other Resources for the
     * same configuration) may be so the Context itself can be fairly lightweight.
     *
     * The returned display Context provides a {@link WindowManager}
     * (see {@link #getSystemService(String)}) that is configured to show windows
     * on the given display.  The WindowManager's {@link WindowManager#getDefaultDisplay}
     * method can be used to retrieve the Display from the returned Context.
     *
     * @param display A {@link Display} object specifying the display
     * for whose metrics the Context's resources should be tailored and upon which
     * new windows should be shown.
     *
     * @return A {@link Context} for the display.
     */
    public abstract Context createDisplayContext(@NonNull Display display);

    /**
     * Gets the display adjustments holder for this context.  This information
     * is provided on a per-application or activity basis and is used to simulate lower density
     * display metrics for legacy applications and restricted screen sizes.
     *
     * @param displayId The display id for which to get compatibility info.
     * @return The compatibility info holder, or null if not required by the application.
     * @hide
     */
    public abstract DisplayAdjustments getDisplayAdjustments(int displayId);

    /**
     * Indicates whether this Context is restricted.
     *
     * @return {@code true} if this Context is restricted, {@code false} otherwise.
     *
     * @see #CONTEXT_RESTRICTED
     */
    public boolean isRestricted() {
        return false;
    }
}
