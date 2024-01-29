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

import static android.content.flags.Flags.FLAG_ENABLE_BIND_PACKAGE_ISOLATED_PROCESS;

import android.annotation.AttrRes;
import android.annotation.CallbackExecutor;
import android.annotation.CheckResult;
import android.annotation.ColorInt;
import android.annotation.ColorRes;
import android.annotation.DisplayContext;
import android.annotation.DrawableRes;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.PermissionMethod;
import android.annotation.PermissionName;
import android.annotation.RequiresPermission;
import android.annotation.StringDef;
import android.annotation.StringRes;
import android.annotation.StyleRes;
import android.annotation.StyleableRes;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UiContext;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.BroadcastOptions;
import android.app.GameManager;
import android.app.GrammaticalInflectionManager;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.VrManager;
import android.app.ambientcontext.AmbientContextManager;
import android.app.people.PeopleManager;
import android.app.time.TimeManager;
import android.companion.virtual.VirtualDeviceManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.credentials.CredentialManager;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Flags;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.provider.MediaStore;
import android.telephony.TelephonyRegistryManager;
import android.util.AttributeSet;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams.WindowType;
import android.view.autofill.AutofillManager.AutofillClient;
import android.view.contentcapture.ContentCaptureManager.ContentCaptureClient;
import android.view.textclassifier.TextClassificationManager;
import android.window.WindowContext;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.compat.IPlatformCompatNative;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

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
     * After {@link Build.VERSION_CODES#TIRAMISU},
     * {@link #registerComponentCallbacks(ComponentCallbacks)} will add a {@link ComponentCallbacks}
     * to {@link Activity} or {@link ContextWrapper#getBaseContext()} instead of always adding to
     * {@link #getApplicationContext()}.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    @VisibleForTesting
    public static final long OVERRIDABLE_COMPONENT_CALLBACKS = 193247900L;

    /**
     * The default device ID, which is the ID of the primary (non-virtual) device.
     */
    public static final int DEVICE_ID_DEFAULT = 0;
    /**
     * Invalid device ID.
     */
    public static final int DEVICE_ID_INVALID = -1;

    /** @hide */
    @IntDef(flag = true, prefix = { "MODE_" }, value = {
            MODE_PRIVATE,
            MODE_WORLD_READABLE,
            MODE_WORLD_WRITEABLE,
            MODE_APPEND,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FileMode {}

    /** @hide */
    @IntDef(flag = true, prefix = { "MODE_" }, value = {
            MODE_PRIVATE,
            MODE_WORLD_READABLE,
            MODE_WORLD_WRITEABLE,
            MODE_MULTI_PROCESS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PreferencesMode {}

    /** @hide */
    @IntDef(flag = true, prefix = { "MODE_" }, value = {
            MODE_PRIVATE,
            MODE_WORLD_READABLE,
            MODE_WORLD_WRITEABLE,
            MODE_ENABLE_WRITE_AHEAD_LOGGING,
            MODE_NO_LOCALIZED_COLLATORS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DatabaseMode {}

    /**
     * File creation mode: the default mode, where the created file can only
     * be accessed by the calling application (or all applications sharing the
     * same user ID).
     */
    public static final int MODE_PRIVATE = 0x0000;

    /**
     * File creation mode: allow all other applications to have read access to
     * the created file.
     * <p>
     * Starting from {@link android.os.Build.VERSION_CODES#N}, attempting to use this
     * mode throws a {@link SecurityException}.
     *
     * @deprecated Creating world-readable files is very dangerous, and likely
     *             to cause security holes in applications. It is strongly
     *             discouraged; instead, applications should use more formal
     *             mechanism for interactions such as {@link ContentProvider},
     *             {@link BroadcastReceiver}, and {@link android.app.Service}.
     *             There are no guarantees that this access mode will remain on
     *             a file, such as when it goes through a backup and restore.
     * @see androidx.core.content.FileProvider
     * @see Intent#FLAG_GRANT_WRITE_URI_PERMISSION
     */
    @Deprecated
    public static final int MODE_WORLD_READABLE = 0x0001;

    /**
     * File creation mode: allow all other applications to have write access to
     * the created file.
     * <p>
     * Starting from {@link android.os.Build.VERSION_CODES#N}, attempting to use this
     * mode will throw a {@link SecurityException}.
     *
     * @deprecated Creating world-writable files is very dangerous, and likely
     *             to cause security holes in applications. It is strongly
     *             discouraged; instead, applications should use more formal
     *             mechanism for interactions such as {@link ContentProvider},
     *             {@link BroadcastReceiver}, and {@link android.app.Service}.
     *             There are no guarantees that this access mode will remain on
     *             a file, such as when it goes through a backup and restore.
     * @see androidx.core.content.FileProvider
     * @see Intent#FLAG_GRANT_WRITE_URI_PERMISSION
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
     * targeting such releases.  For applications targeting SDK
     * versions <em>greater than</em> Android 2.3, this flag must be
     * explicitly set if desired.
     *
     * @see #getSharedPreferences
     *
     * @deprecated MODE_MULTI_PROCESS does not work reliably in
     * some versions of Android, and furthermore does not provide any
     * mechanism for reconciling concurrent modifications across
     * processes.  Applications should not attempt to use it.  Instead,
     * they should use an explicit cross-process data management
     * approach such as {@link android.content.ContentProvider ContentProvider}.
     */
    @Deprecated
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

    /**
     * Database open flag: when set, the database is opened without support for
     * localized collators.
     *
     * @see #openOrCreateDatabase(String, int, CursorFactory)
     * @see #openOrCreateDatabase(String, int, CursorFactory, DatabaseErrorHandler)
     * @see SQLiteDatabase#NO_LOCALIZED_COLLATORS
     */
    public static final int MODE_NO_LOCALIZED_COLLATORS = 0x0010;

    /**
     * Flags used for bindService(int) APIs. Note, we now have long BIND_* flags.
     * @hide
     */
    @IntDef(flag = true, prefix = { "BIND_" }, value = {
            BIND_AUTO_CREATE,
            BIND_DEBUG_UNBIND,
            BIND_NOT_FOREGROUND,
            BIND_ABOVE_CLIENT,
            BIND_ALLOW_OOM_MANAGEMENT,
            BIND_WAIVE_PRIORITY,
            BIND_IMPORTANT,
            BIND_ADJUST_WITH_ACTIVITY,
            BIND_NOT_PERCEPTIBLE,
            BIND_ALLOW_ACTIVITY_STARTS,
            BIND_INCLUDE_CAPABILITIES,
            BIND_SHARED_ISOLATED_PROCESS,
            BIND_PACKAGE_ISOLATED_PROCESS,
            BIND_EXTERNAL_SERVICE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BindServiceFlagsBits {}

    /**
     * Long version of BIND_* flags used for bindService(BindServiceFlags) APIs.
     * @hide
     */
    @LongDef(flag = true, prefix = { "BIND_" }, value = {
            BIND_AUTO_CREATE,
            BIND_DEBUG_UNBIND,
            BIND_NOT_FOREGROUND,
            BIND_ABOVE_CLIENT,
            BIND_ALLOW_OOM_MANAGEMENT,
            BIND_WAIVE_PRIORITY,
            BIND_IMPORTANT,
            BIND_ADJUST_WITH_ACTIVITY,
            BIND_NOT_PERCEPTIBLE,
            BIND_ALLOW_ACTIVITY_STARTS,
            BIND_INCLUDE_CAPABILITIES,
            BIND_SHARED_ISOLATED_PROCESS,
            BIND_PACKAGE_ISOLATED_PROCESS,
            // Intentionally not include BIND_EXTERNAL_SERVICE, because it'd cause sign-extension.
            // This would allow Android Studio to show a warning, if someone tries to use
            // BIND_EXTERNAL_SERVICE BindServiceFlags.
            BIND_EXTERNAL_SERVICE_LONG,
            // Make sure no flag uses the sign bit (most significant bit) of the long integer,
            // to avoid future confusion.
            BIND_BYPASS_USER_NETWORK_RESTRICTIONS,
            BIND_MATCH_QUARANTINED_COMPONENTS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BindServiceFlagsLongBits {}

    /**
     * Specific flags used for bindService() call, which encapsulates a 64 bits long integer.
     * Call {@link BindServiceFlags#of(long)} to obtain an
     * object of {@code BindServiceFlags}.
     */
    public final static class BindServiceFlags {
        private final long mValue;

        private BindServiceFlags(@BindServiceFlagsLongBits long value) {
            mValue = value;
        }

        /**
         * @return Return flags in 64 bits long integer.
         * @hide
         */
        public long getValue() {
            return mValue;
        }

        /**
         * Build {@link BindServiceFlags} from BIND_* FLAGS.
         *
         * Note, {@link #BIND_EXTERNAL_SERVICE} is not supported in this method, because
         * it has the highest integer bit set and cause wrong flags to be set. Use
         * {@link #BIND_EXTERNAL_SERVICE_LONG} instead.
         */
        @NonNull
        public static BindServiceFlags of(@BindServiceFlagsLongBits long value) {
            if ((value & Integer.toUnsignedLong(BIND_EXTERNAL_SERVICE)) != 0){
                throw new IllegalArgumentException(
                        "BIND_EXTERNAL_SERVICE is deprecated. Use BIND_EXTERNAL_SERVICE_LONG"
                                + " instead");
            }

            return new BindServiceFlags(value);
        }
    }

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
     * Flag for {@link #bindService}: If binding from an app that is visible or user-perceptible,
     * lower the target service's importance to below the perceptible level. This allows
     * the system to (temporarily) expunge the bound process from memory to make room for more
     * important user-perceptible processes.
     */
    public static final int BIND_NOT_PERCEPTIBLE = 0x00000100;

    /**
     * Flag for {@link #bindService}: If binding from an app that is visible, the bound service is
     * allowed to start an activity from background. This was the default behavior before SDK
     * version {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}. Since then, the default
     * behavior changed to disallow the bound service to start a background activity even if the app
     * bound to it is in foreground, unless this flag is specified when binding.
     */
    public static final int BIND_ALLOW_ACTIVITY_STARTS = 0X000000200;

    /**
     * Flag for {@link #bindService}: If binding from an app that has specific capabilities
     * due to its foreground state such as an activity or foreground service, then this flag will
     * allow the bound app to get the same capabilities, as long as it has the required permissions
     * as well.
     *
     * If binding from a top app and its target SDK version is at or above
     * {@link android.os.Build.VERSION_CODES#R}, the app needs to
     * explicitly use BIND_INCLUDE_CAPABILITIES flag to pass all capabilities to the service so the
     * other app can have while-in-use access such as location, camera, microphone from background.
     * If binding from a top app and its target SDK version is below
     * {@link android.os.Build.VERSION_CODES#R}, BIND_INCLUDE_CAPABILITIES is implicit.
     */
    public static final int BIND_INCLUDE_CAPABILITIES = 0x000001000;

    /**
     * Flag for {@link #bindIsolatedService}: Bind the service into a shared isolated process.
     * Specifying this flag allows multiple isolated services to be running in a single shared
     * isolated process.
     *
     * The shared isolated process instance is identified by the <var>instanceName</var>
     * parameter in {@link #bindIsolatedService(Intent, int, String, Executor, ServiceConnection)}.
     *
     * Subsequent calls to {@link #bindIsolatedService} with the same <var>instanceName</var>
     * will cause the isolated service to be co-located in the same shared isolated process.
     *
     * Note that the shared isolated process is scoped to the calling app; once created, only
     * the calling app can bind additional isolated services into the shared process. However,
     * the services themselves can come from different APKs and therefore different vendors.
     *
     * Only services that set the {@link android.R.attr#allowSharedIsolatedProcess} attribute
     * to {@code true} are allowed to be bound into a shared isolated process.
     *
     */
    public static final int BIND_SHARED_ISOLATED_PROCESS = 0x00002000;

    /**
     * Flag for {@link #bindIsolatedService}: Bind the service into a shared isolated process,
     * but only with other isolated services from the same package that declare the same process
     * name.
     *
     * <p>Specifying this flag allows multiple isolated services defined in the same package to be
     * running in a single shared isolated process. This shared isolated process must be specified
     * since this flag will not work with the default application process.
     *
     * <p>This flag is different from {@link #BIND_SHARED_ISOLATED_PROCESS} since it only
     * allows binding services from the same package in the same shared isolated process. This also
     * means the shared package isolated process is global, and not scoped to each potential
     * calling app.
     *
     * <p>The shared isolated process instance is identified by the "android:process" attribute
     * defined by the service. This flag cannot be used without this attribute set.
     */
    @FlaggedApi(FLAG_ENABLE_BIND_PACKAGE_ISOLATED_PROCESS)
    public static final int BIND_PACKAGE_ISOLATED_PROCESS = 1 << 14;

    /***********    Public flags above this line ***********/
    /***********    Hidden flags below this line ***********/

    /**
     * Flag for {@link #bindService}: This flag is only intended to be used by the system to
     * indicate that a service binding is not considered as real package component usage and should
     * not generate a {@link android.app.usage.UsageEvents.Event#APP_COMPONENT_USED} event in usage
     * stats.
     * @hide
     */
    public static final int BIND_NOT_APP_COMPONENT_USAGE = 0x00008000;

    /**
     * Flag for {@link #bindService}: allow the process hosting the target service to be treated
     * as if it's as important as a perceptible app to the user and avoid the oom killer killing
     * this process in low memory situations until there aren't any other processes left but the
     * ones which are user-perceptible.
     *
     * @hide
     */
    public static final int BIND_ALMOST_PERCEPTIBLE = 0x000010000;

    /**
     * Flag for {@link #bindService}: allow the process hosting the target service to gain
     * {@link ActivityManager#PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK}, which allows it be able
     * to access network regardless of any power saving restrictions.
     *
     * @hide
     */
    public static final int BIND_BYPASS_POWER_NETWORK_RESTRICTIONS = 0x00020000;

    /**
     * Do not use. This flag is no longer needed nor used.
     * @hide
     */
    @SystemApi
    @Deprecated
    public static final int BIND_ALLOW_FOREGROUND_SERVICE_STARTS_FROM_BACKGROUND = 0x00040000;

    /**
     * Flag for {@link #bindService}: This flag is intended to be used only by the system to adjust
     * the scheduling policy for IMEs (and any other out-of-process user-visible components that
     * work closely with the top app) so that UI hosted in such services can have the same
     * scheduling policy (e.g. SCHED_FIFO when it is enabled and TOP_APP_PRIORITY_BOOST otherwise)
     * as the actual top-app.
     * @hide
     */
    public static final int BIND_SCHEDULE_LIKE_TOP_APP = 0x00080000;

    /**
     * Flag for {@link #bindService}: allow background activity starts from the bound service's
     * process.
     * This flag is only respected if the caller is holding
     * {@link android.Manifest.permission#START_ACTIVITIES_FROM_BACKGROUND}.
     * @hide
     */
    @SystemApi
    public static final int BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS = 0x00100000;

    /**
     * @hide Flag for {@link #bindService}: the service being bound to represents a
     * protected system component, so must have association restrictions applied to it.
     * That is, a system config must have one or more allow-association tags limiting
     * which packages it can interact with.  If it does not have any such association
     * restrictions, a default empty set will be created.
     */
    public static final int BIND_RESTRICT_ASSOCIATIONS = 0x00200000;

    /**
     * @hide Flag for {@link #bindService}: allows binding to a service provided
     * by an instant app. Note that the caller may not have access to the instant
     * app providing the service which is a violation of the instant app sandbox.
     * This flag is intended ONLY for development/testing and should be used with
     * great care. Only the system is allowed to use this flag.
     */
    public static final int BIND_ALLOW_INSTANT = 0x00400000;

    /**
     * @hide Flag for {@link #bindService}: like {@link #BIND_NOT_FOREGROUND}, but puts it
     * up in to the important background state (instead of transient).
     */
    public static final int BIND_IMPORTANT_BACKGROUND = 0x00800000;

    /**
     * @hide Flag for {@link #bindService}: allows application hosting service to manage allowlists
     * such as temporary allowing a {@code PendingIntent} to bypass Power Save mode.
     */
    public static final int BIND_ALLOW_WHITELIST_MANAGEMENT = 0x01000000;

    /**
     * @hide Flag for {@link #bindService}: Like {@link #BIND_FOREGROUND_SERVICE},
     * but only applies while the device is awake.
     */
    public static final int BIND_FOREGROUND_SERVICE_WHILE_AWAKE = 0x02000000;

    /**
     * @hide Flag for {@link #bindService}: For only the case where the binding
     * is coming from the system, set the process state to BOUND_FOREGROUND_SERVICE
     * instead of the normal maximum of IMPORTANT_FOREGROUND.  That is, this is
     * saying that the process shouldn't participate in the normal power reduction
     * modes (removing network access etc).
     */
    public static final int BIND_FOREGROUND_SERVICE = 0x04000000;

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
     * tries to balance such requests from one app vs. the importance of
     * keeping other apps around.
     *
     * @deprecated Repurposed to {@link #BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE}.
     */
    @Deprecated
    public static final int BIND_VISIBLE = 0x10000000;

    /**
     * @hide Flag for {@link #bindService}: Treat the binding as hosting a foreground service
     * and also visible to the user. That is, the app hosting the service will get its process state
     * bumped to the {@link android.app.ActivityManager#PROCESS_STATE_FOREGROUND_SERVICE},
     * and it's considered as visible to the user, thus less likely to be expunged from memory
     * on low memory situations. This is intented for use by processes with the process state
     * better than the {@link android.app.ActivityManager#PROCESS_STATE_TOP}.
     */
    public static final int BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE = 0x10000000;

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

    /**
     * Flag for {@link #bindService}: The service being bound is an
     * {@link android.R.attr#isolatedProcess isolated},
     * {@link android.R.attr#externalService external} service.  This binds the service into the
     * calling application's package, rather than the package in which the service is declared.
     * <p>
     * When using this flag, the code for the service being bound will execute under the calling
     * application's package name and user ID.  Because the service must be an isolated process,
     * it will not have direct access to the application's data, though.
     *
     * The purpose of this flag is to allow applications to provide services that are attributed
     * to the app using the service, rather than the application providing the service.
     * </p>
     *
     * <em>This flag is NOT compatible with {@link BindServiceFlags}. If you need to use
     * {@link BindServiceFlags}, you must use {@link #BIND_EXTERNAL_SERVICE_LONG} instead.</em>
     */
    public static final int BIND_EXTERNAL_SERVICE = 0x80000000;

    /**
     * Works in the same way as {@link #BIND_EXTERNAL_SERVICE}, but it's defined as a {@code long}
     * value that is compatible to {@link BindServiceFlags}.
     */
    public static final long BIND_EXTERNAL_SERVICE_LONG = 1L << 62;

    /**
     * Flag for {@link #bindService}: allow the process hosting the target service to gain
     * {@link ActivityManager#PROCESS_CAPABILITY_USER_RESTRICTED_NETWORK}, which allows it be able
     * to access network regardless of any user restrictions.
     *
     * @hide
     */
    public static final long BIND_BYPASS_USER_NETWORK_RESTRICTIONS = 0x1_0000_0000L;

    /**
     * Flag for {@link #bindService}.
     *
     * @hide
     */
    public static final long BIND_MATCH_QUARANTINED_COMPONENTS = 0x2_0000_0000L;


    /**
     * These bind flags reduce the strength of the binding such that we shouldn't
     * consider it as pulling the process up to the level of the one that is bound to it.
     * @hide
     */
    public static final int BIND_REDUCTION_FLAGS =
            Context.BIND_ALLOW_OOM_MANAGEMENT | Context.BIND_WAIVE_PRIORITY
                    | Context.BIND_NOT_PERCEPTIBLE | Context.BIND_NOT_VISIBLE;

    /** @hide */
    @IntDef(flag = true, prefix = { "RECEIVER_VISIBLE" }, value = {
            RECEIVER_VISIBLE_TO_INSTANT_APPS, RECEIVER_EXPORTED, RECEIVER_NOT_EXPORTED,
            RECEIVER_EXPORTED_UNAUDITED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RegisterReceiverFlags {}

    /**
     * Flag for {@link #registerReceiver}: The receiver can receive broadcasts from Instant Apps.
     */
    public static final int RECEIVER_VISIBLE_TO_INSTANT_APPS = 0x1;

    /**
     * Flag for {@link #registerReceiver}: The receiver can receive broadcasts from other Apps.
     * Has the same behavior as marking a statically registered receiver with "exported=true"
     */
    public static final int RECEIVER_EXPORTED = 0x2;

    /**
     * @deprecated Use {@link #RECEIVER_NOT_EXPORTED} or {@link #RECEIVER_EXPORTED} instead.
     * @hide
     */
    @Deprecated
    @TestApi
    public static final int RECEIVER_EXPORTED_UNAUDITED = RECEIVER_EXPORTED;

    /**
     * Flag for {@link #registerReceiver}: The receiver cannot receive broadcasts from other Apps.
     * Has the same behavior as marking a statically registered receiver with "exported=false"
     */
    public static final int RECEIVER_NOT_EXPORTED = 0x4;

    /**
     * Returns an AssetManager instance for the application's package.
     * <p>
     * <strong>Note:</strong> Implementations of this method should return
     * an AssetManager instance that is consistent with the Resources instance
     * returned by {@link #getResources()}. For example, they should share the
     * same {@link Configuration} object.
     *
     * @return an AssetManager instance for the application's package
     * @see #getResources()
     */
    public abstract AssetManager getAssets();

    /**
     * Returns a Resources instance for the application's package.
     * <p>
     * <strong>Note:</strong> Implementations of this method should return
     * a Resources instance that is consistent with the AssetManager instance
     * returned by {@link #getAssets()}. For example, they should share the
     * same {@link Configuration} object.
     *
     * @return a Resources instance for the application's package
     * @see #getAssets()
     */
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
     * Return an {@link Executor} that will run enqueued tasks on the main
     * thread associated with this context. This is the thread used to dispatch
     * calls to application components (activities, services, etc).
     */
    public Executor getMainExecutor() {
        // This is pretty inefficient, which is why ContextImpl overrides it
        return new HandlerExecutor(new Handler(getMainLooper()));
    }

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

    /** Non-activity related autofill ids are unique in the app */
    private static int sLastAutofillId = View.NO_ID;

    /**
     * Gets the next autofill ID.
     *
     * <p>All IDs will be smaller or the same as {@link View#LAST_APP_AUTOFILL_ID}. All IDs
     * returned will be unique.
     *
     * @return A ID that is unique in the process
     *
     * {@hide}
     */
    public int getNextAutofillId() {
        if (sLastAutofillId == View.LAST_APP_AUTOFILL_ID - 1) {
            sLastAutofillId = View.NO_ID;
        }

        sLastAutofillId++;

        return sLastAutofillId;
    }

    /**
     * Add a new {@link ComponentCallbacks} to the base application of the
     * Context, which will be called at the same times as the ComponentCallbacks
     * methods of activities and other components are called. Note that you
     * <em>must</em> be sure to use {@link #unregisterComponentCallbacks} when
     * appropriate in the future; this will not be removed for you.
     * <p>
     * After {@link Build.VERSION_CODES#S}, registering the ComponentCallbacks to Context created
     * via {@link #createWindowContext(int, Bundle)} or
     * {@link #createWindowContext(Display, int, Bundle)} will receive
     * {@link ComponentCallbacks#onConfigurationChanged(Configuration)} from Window Context rather
     * than its base application. It is helpful if you want to handle UI components that
     * associated with the Window Context when the Window Context has configuration changes.</p>
     * <p>
     * After {@link Build.VERSION_CODES#TIRAMISU}, registering the ComponentCallbacks to
     * {@link Activity} context will receive
     * {@link ComponentCallbacks#onConfigurationChanged(Configuration)} from
     * {@link Activity#onConfigurationChanged(Configuration)} rather than its base application.</p>
     * <p>
     * After {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, registering the ComponentCallbacks to
     * {@link android.inputmethodservice.InputMethodService} will receive
     * {@link ComponentCallbacks#onConfigurationChanged(Configuration)} from InputmethodService
     * rather than its base application. It is helpful if you want to handle UI components when the
     * IME has configuration changes.</p>
     *
     * @param callback The interface to call.  This can be either a
     * {@link ComponentCallbacks} or {@link ComponentCallbacks2} interface.
     *
     * @see Context#createWindowContext(int, Bundle)
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
    @NonNull
    public final CharSequence getText(@StringRes int resId) {
        return getResources().getText(resId);
    }

    /**
     * Returns a localized string from the application's package's
     * default string table.
     *
     * @param resId Resource id for the string
     * @return The string data associated with the resource, stripped of styled
     *         text information.
     */
    @NonNull
    public final String getString(@StringRes int resId) {
        return getResources().getString(resId);
    }

    /**
     * Returns a localized formatted string from the application's package's
     * default string table, substituting the format arguments as defined in
     * {@link java.util.Formatter} and {@link java.lang.String#format}.
     *
     * @param resId Resource id for the format string
     * @param formatArgs The format arguments that will be used for
     *                   substitution.
     * @return The string data associated with the resource, formatted and
     *         stripped of styled text information.
     */
    @NonNull
    public final String getString(@StringRes int resId, Object... formatArgs) {
        return getResources().getString(resId, formatArgs);
    }

    /**
     * Returns a color associated with a particular resource ID and styled for
     * the current theme.
     *
     * @param id The desired resource identifier, as generated by the aapt
     *           tool. This integer encodes the package, type, and resource
     *           entry. The value 0 is an invalid identifier.
     * @return A single color value in the form 0xAARRGGBB.
     * @throws android.content.res.Resources.NotFoundException if the given ID
     *         does not exist.
     */
    @ColorInt
    public final int getColor(@ColorRes int id) {
        return getResources().getColor(id, getTheme());
    }

    /**
     * Returns a drawable object associated with a particular resource ID and
     * styled for the current theme.
     *
     * @param id The desired resource identifier, as generated by the aapt
     *           tool. This integer encodes the package, type, and resource
     *           entry. The value 0 is an invalid identifier.
     * @return An object that can be used to draw this resource.
     * @throws android.content.res.Resources.NotFoundException if the given ID
     *         does not exist.
     */
    @Nullable
    public final Drawable getDrawable(@DrawableRes int id) {
        return getResources().getDrawable(id, getTheme());
    }

    /**
     * Returns a color state list associated with a particular resource ID and
     * styled for the current theme.
     *
     * @param id The desired resource identifier, as generated by the aapt
     *           tool. This integer encodes the package, type, and resource
     *           entry. The value 0 is an invalid identifier.
     * @return A color state list.
     * @throws android.content.res.Resources.NotFoundException if the given ID
     *         does not exist.
     */
    @NonNull
    public final ColorStateList getColorStateList(@ColorRes int id) {
        return getResources().getColorStateList(id, getTheme());
    }

     /**
     * Set the base theme for this context.  Note that this should be called
     * before any views are instantiated in the Context (for example before
     * calling {@link android.app.Activity#setContentView} or
     * {@link android.view.LayoutInflater#inflate}).
     *
     * @param resid The style resource describing the theme.
     */
    public abstract void setTheme(@StyleRes int resid);

    /** @hide Needed for some internal implementation...  not public because
     * you can't assume this actually means anything. */
    @UnsupportedAppUsage
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
     * {@link android.content.res.Resources.Theme#obtainStyledAttributes(int[])}
     * for more information.
     *
     * @see android.content.res.Resources.Theme#obtainStyledAttributes(int[])
     */
    @NonNull
    public final TypedArray obtainStyledAttributes(@NonNull @StyleableRes int[] attrs) {
        return getTheme().obtainStyledAttributes(attrs);
    }

    /**
     * Retrieve styled attribute information in this Context's theme.  See
     * {@link android.content.res.Resources.Theme#obtainStyledAttributes(int, int[])}
     * for more information.
     *
     * @see android.content.res.Resources.Theme#obtainStyledAttributes(int, int[])
     */
    @NonNull
    public final TypedArray obtainStyledAttributes(@StyleRes int resid,
            @NonNull @StyleableRes int[] attrs) throws Resources.NotFoundException {
        return getTheme().obtainStyledAttributes(resid, attrs);
    }

    /**
     * Retrieve styled attribute information in this Context's theme.  See
     * {@link android.content.res.Resources.Theme#obtainStyledAttributes(AttributeSet, int[], int, int)}
     * for more information.
     *
     * @see android.content.res.Resources.Theme#obtainStyledAttributes(AttributeSet, int[], int, int)
     */
    @NonNull
    public final TypedArray obtainStyledAttributes(
            @Nullable AttributeSet set, @NonNull @StyleableRes int[] attrs) {
        return getTheme().obtainStyledAttributes(set, attrs, 0, 0);
    }

    /**
     * Retrieve styled attribute information in this Context's theme.  See
     * {@link android.content.res.Resources.Theme#obtainStyledAttributes(AttributeSet, int[], int, int)}
     * for more information.
     *
     * @see android.content.res.Resources.Theme#obtainStyledAttributes(AttributeSet, int[], int, int)
     */
    @NonNull
    public final TypedArray obtainStyledAttributes(@Nullable AttributeSet set,
            @NonNull @StyleableRes int[] attrs, @AttrRes int defStyleAttr,
            @StyleRes int defStyleRes) {
        return getTheme().obtainStyledAttributes(
            set, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Return a class loader you can use to retrieve classes in this package.
     */
    public abstract ClassLoader getClassLoader();

    /** Return the name of this application's package. */
    public abstract String getPackageName();

    /**
     * @hide Return the name of the base context this context is derived from.
     * This is the same as {@link #getOpPackageName()} except in
     * cases where system components are loaded into other app processes, in which
     * case {@link #getOpPackageName()} will be the name of the primary package in
     * that process (so that app ops uid verification will work with the name).
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract String getBasePackageName();

    /**
     * Return the package name that should be used for {@link android.app.AppOpsManager} calls from
     * this context, so that app ops manager's uid verification will work with the name.
     * <p>
     * This is not generally intended for third party application developers.
     */
    @NonNull
    public String getOpPackageName() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * <p>Attribution can be used in complex apps to logically separate parts of the app. E.g. a
     * blogging app might also have a instant messaging app built in. In this case two separate tags
     * can for used each sub-feature.
     *
     * @return the attribution tag this context is for or {@code null} if this is the default.
     */
    public @Nullable String getAttributionTag() {
        return null;
    }

    /**
     * @return The identity of this context for permission purposes.
     *
     * @see AttributionSource
     */
    public @NonNull AttributionSource getAttributionSource() {
        return null;
    }

    // TODO moltmann: Remove
    /**
     * @removed
     */
    @Deprecated
    public @Nullable String getFeatureId() {
        return getAttributionTag();
    }

    /**
     * Return the set of parameters which this Context was created with, if it
     * was created via {@link #createContext(ContextParams)}.
     */
    public @Nullable ContextParams getParams() {
        return null;
    }

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
     * @hide
     * @deprecated use {@link #getSharedPreferencesPath(String)}
     */
    @Deprecated
    @UnsupportedAppUsage
    public File getSharedPrefsFile(String name) {
        return getSharedPreferencesPath(name);
    }

    /**
     * Retrieve and hold the contents of the preferences file 'name', returning
     * a SharedPreferences through which you can retrieve and modify its
     * values.  Only one instance of the SharedPreferences object is returned
     * to any callers for the same name, meaning they will see each other's
     * edits as soon as they are made.
     *
     * <p>This method is thread-safe.
     *
     * <p>If the preferences directory does not already exist, it will be created when this method
     * is called.
     *
     * <p>If a preferences file by this name does not exist, it will be created when you retrieve an
     * editor ({@link SharedPreferences#edit()}) and then commit changes ({@link
     * SharedPreferences.Editor#commit()} or {@link SharedPreferences.Editor#apply()}).
     *
     * @param name Desired preferences file.
     * @param mode Operating mode.
     *
     * @return The single {@link SharedPreferences} instance that can be used
     *         to retrieve and modify the preference values.
     *
     * @see #MODE_PRIVATE
     */
    public abstract SharedPreferences getSharedPreferences(String name, @PreferencesMode int mode);

    /**
     * Retrieve and hold the contents of the preferences file, returning
     * a SharedPreferences through which you can retrieve and modify its
     * values.  Only one instance of the SharedPreferences object is returned
     * to any callers for the same name, meaning they will see each other's
     * edits as soon as they are made.
     *
     * @param file Desired preferences file. If a preferences file by this name
     * does not exist, it will be created when you retrieve an
     * editor (SharedPreferences.edit()) and then commit changes (Editor.commit()).
     * @param mode Operating mode.
     *
     * @return The single {@link SharedPreferences} instance that can be used
     *         to retrieve and modify the preference values.
     *
     * @see #getSharedPreferencesPath(String)
     * @see #MODE_PRIVATE
     * @removed
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract SharedPreferences getSharedPreferences(File file, @PreferencesMode int mode);

    /**
     * Move an existing shared preferences file from the given source storage
     * context to this context. This is typically used to migrate data between
     * storage locations after an upgrade, such as moving to device protected
     * storage.
     *
     * @param sourceContext The source context which contains the existing
     *            shared preferences to move.
     * @param name The name of the shared preferences file.
     * @return {@code true} if the move was successful or if the shared
     *         preferences didn't exist in the source context, otherwise
     *         {@code false}.
     * @see #createDeviceProtectedStorageContext()
     */
    public abstract boolean moveSharedPreferencesFrom(Context sourceContext, String name);

    /**
     * Delete an existing shared preferences file.
     *
     * @param name The name (unique in the application package) of the shared
     *            preferences file.
     * @return {@code true} if the shared preferences file was successfully
     *         deleted; else {@code false}.
     * @see #getSharedPreferences(String, int)
     */
    public abstract boolean deleteSharedPreferences(String name);

    /** @hide */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract void reloadSharedPreferences();

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
     * for writing. Creates the file if it doesn't already exist.
     * <p>
     * No additional permissions are required for the calling app to read or
     * write the returned file.
     *
     * @param name The name of the file to open; can not contain path
     *            separators.
     * @param mode Operating mode.
     * @return The resulting {@link FileOutputStream}.
     * @see #MODE_APPEND
     * @see #MODE_PRIVATE
     * @see #openFileInput
     * @see #fileList
     * @see #deleteFile
     * @see java.io.FileOutputStream#FileOutputStream(String)
     */
    public abstract FileOutputStream openFileOutput(String name, @FileMode int mode)
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
     * <p>
     * The returned path may change over time if the calling app is moved to an
     * adopted storage device, so only relative paths should be persisted.
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
     * Returns the absolute path on the filesystem where a file created with
     * {@link #getSharedPreferences(String, int)} is stored.
     * <p>
     * The returned path may change over time if the calling app is moved to an
     * adopted storage device, so only relative paths should be persisted.
     *
     * @param name The name of the shared preferences for which you would like
     *            to get a path.
     * @return An absolute path to the given file.
     * @see #getSharedPreferences(String, int)
     * @removed
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract File getSharedPreferencesPath(String name);

    /**
     * Returns the absolute path to the directory on the filesystem where all
     * private files belonging to this app are stored. Apps should not use this
     * path directly; they should instead use {@link #getFilesDir()},
     * {@link #getCacheDir()}, {@link #getDir(String, int)}, or other storage
     * APIs on this class.
     * <p>
     * The returned path may change over time if the calling app is moved to an
     * adopted storage device, so only relative paths should be persisted.
     * <p>
     * No additional permissions are required for the calling app to read or
     * write files under the returned path.
     *
     * @see ApplicationInfo#dataDir
     */
    public abstract File getDataDir();

    /**
     * Returns the absolute path to the directory on the filesystem where files
     * created with {@link #openFileOutput} are stored.
     * <p>
     * The returned path may change over time if the calling app is moved to an
     * adopted storage device, so only relative paths should be persisted.
     * <p>
     * No additional permissions are required for the calling app to read or
     * write files under the returned path.
     *
     * @return The path of the directory holding application files.
     * @see #openFileOutput
     * @see #getFileStreamPath
     * @see #getDir
     */
    public abstract File getFilesDir();

    /**
     * Returns the absolute path to the directory that is related to the crate on the filesystem.
     * <p>
     *     The crateId require a validated file name. It can't contain any "..", ".",
     *     {@link File#separatorChar} etc..
     * </p>
     * <p>
     * The returned path may change over time if the calling app is moved to an
     * adopted storage device, so only relative paths should be persisted.
     * </p>
     * <p>
     * No additional permissions are required for the calling app to read or
     * write files under the returned path.
     *</p>
     *
     * @param crateId the relative validated file name under {@link Context#getDataDir()}/crates
     * @return the crate directory file.
     * @hide
     */
    @NonNull
    @TestApi
    public File getCrateDir(@NonNull String crateId) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Returns the absolute path to the directory on the filesystem similar to
     * {@link #getFilesDir()}. The difference is that files placed under this
     * directory will be excluded from automatic backup to remote storage. See
     * {@link android.app.backup.BackupAgent BackupAgent} for a full discussion
     * of the automatic backup mechanism in Android.
     * <p>
     * The returned path may change over time if the calling app is moved to an
     * adopted storage device, so only relative paths should be persisted.
     * <p>
     * No additional permissions are required for the calling app to read or
     * write files under the returned path.
     *
     * @return The path of the directory holding application files that will not
     *         be automatically backed up to remote storage.
     * @see #openFileOutput
     * @see #getFileStreamPath
     * @see #getDir
     * @see android.app.backup.BackupAgent
     */
    public abstract File getNoBackupFilesDir();

    /**
     * Returns the absolute path to the directory on the primary shared/external
     * storage device where the application can place persistent files it owns.
     * These files are internal to the applications, and not typically visible
     * to the user as media.
     * <p>
     * This is like {@link #getFilesDir()} in that these files will be deleted
     * when the application is uninstalled, however there are some important
     * differences:
     * <ul>
     * <li>Shared storage may not always be available, since removable media can
     * be ejected by the user. Media state can be checked using
     * {@link Environment#getExternalStorageState(File)}.
     * <li>There is no security enforced with these files. For example, any
     * application holding
     * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} can write to
     * these files.
     * </ul>
     * <p>
     * If a shared storage device is emulated (as determined by
     * {@link Environment#isExternalStorageEmulated(File)}), its contents are
     * backed by a private user data partition, which means there is little
     * benefit to storing data here instead of the private directories returned
     * by {@link #getFilesDir()}, etc.
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#KITKAT}, no permissions
     * are required to read or write to the returned path; it's always
     * accessible to the calling app. This only applies to paths generated for
     * package name of the calling application. To access paths belonging to
     * other packages,
     * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} and/or
     * {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} are required.
     * <p>
     * On devices with multiple users (as described by {@link UserManager}),
     * each user has their own isolated shared storage. Applications only have
     * access to the shared storage for the user they're running as.
     * <p>
     * The returned path may change over time if different shared storage media
     * is inserted, so only relative paths should be persisted.
     * <p>
     * Here is an example of typical code to manipulate a file in an
     * application's shared storage:
     * </p>
     * {@sample development/samples/ApiDemos/src/com/example/android/apis/content/ExternalStorage.java
     * private_file}
     * <p>
     * If you supply a non-null <var>type</var> to this function, the returned
     * file will be a path to a sub-directory of the given type. Though these
     * files are not automatically scanned by the media scanner, you can
     * explicitly add them to the media database with
     * {@link android.media.MediaScannerConnection#scanFile(Context, String[], String[], android.media.MediaScannerConnection.OnScanCompletedListener)
     * MediaScannerConnection.scanFile}. Note that this is not the same as
     * {@link android.os.Environment#getExternalStoragePublicDirectory
     * Environment.getExternalStoragePublicDirectory()}, which provides
     * directories of media shared by all applications. The directories returned
     * here are owned by the application, and their contents will be removed
     * when the application is uninstalled. Unlike
     * {@link android.os.Environment#getExternalStoragePublicDirectory
     * Environment.getExternalStoragePublicDirectory()}, the directory returned
     * here will be automatically created for you.
     * <p>
     * Here is an example of typical code to manipulate a picture in an
     * application's shared storage and add it to the media database:
     * </p>
     * {@sample development/samples/ApiDemos/src/com/example/android/apis/content/ExternalStorage.java
     * private_picture}
     *
     * @param type The type of files directory to return. May be {@code null}
     *            for the root of the files directory or one of the following
     *            constants for a subdirectory:
     *            {@link android.os.Environment#DIRECTORY_MUSIC},
     *            {@link android.os.Environment#DIRECTORY_PODCASTS},
     *            {@link android.os.Environment#DIRECTORY_RINGTONES},
     *            {@link android.os.Environment#DIRECTORY_ALARMS},
     *            {@link android.os.Environment#DIRECTORY_NOTIFICATIONS},
     *            {@link android.os.Environment#DIRECTORY_PICTURES}, or
     *            {@link android.os.Environment#DIRECTORY_MOVIES}.
     * @return the absolute path to application-specific directory. May return
     *         {@code null} if shared storage is not currently available.
     * @see #getFilesDir
     * @see #getExternalFilesDirs(String)
     * @see Environment#getExternalStorageState(File)
     * @see Environment#isExternalStorageEmulated(File)
     * @see Environment#isExternalStorageRemovable(File)
     */
    @Nullable
    public abstract File getExternalFilesDir(@Nullable String type);

    /**
     * Returns absolute paths to application-specific directories on all
     * shared/external storage devices where the application can place
     * persistent files it owns. These files are internal to the application,
     * and not typically visible to the user as media.
     * <p>
     * This is like {@link #getFilesDir()} in that these files will be deleted
     * when the application is uninstalled, however there are some important
     * differences:
     * <ul>
     * <li>Shared storage may not always be available, since removable media can
     * be ejected by the user. Media state can be checked using
     * {@link Environment#getExternalStorageState(File)}.
     * <li>There is no security enforced with these files. For example, any
     * application holding
     * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} can write to
     * these files.
     * </ul>
     * <p>
     * If a shared storage device is emulated (as determined by
     * {@link Environment#isExternalStorageEmulated(File)}), its contents are
     * backed by a private user data partition, which means there is little
     * benefit to storing data here instead of the private directories returned
     * by {@link #getFilesDir()}, etc.
     * <p>
     * Shared storage devices returned here are considered a stable part of the
     * device, including physical media slots under a protective cover. The
     * returned paths do not include transient devices, such as USB flash drives
     * connected to handheld devices.
     * <p>
     * An application may store data on any or all of the returned devices. For
     * example, an app may choose to store large files on the device with the
     * most available space, as measured by {@link StatFs}.
     * <p>
     * No additional permissions are required for the calling app to read or
     * write files under the returned path. Write access outside of these paths
     * on secondary external storage devices is not available.
     * <p>
     * The returned path may change over time if different shared storage media
     * is inserted, so only relative paths should be persisted.
     *
     * @param type The type of files directory to return. May be {@code null}
     *            for the root of the files directory or one of the following
     *            constants for a subdirectory:
     *            {@link android.os.Environment#DIRECTORY_MUSIC},
     *            {@link android.os.Environment#DIRECTORY_PODCASTS},
     *            {@link android.os.Environment#DIRECTORY_RINGTONES},
     *            {@link android.os.Environment#DIRECTORY_ALARMS},
     *            {@link android.os.Environment#DIRECTORY_NOTIFICATIONS},
     *            {@link android.os.Environment#DIRECTORY_PICTURES}, or
     *            {@link android.os.Environment#DIRECTORY_MOVIES}.
     * @return the absolute paths to application-specific directories. Some
     *         individual paths may be {@code null} if that shared storage is
     *         not currently available. The first path returned is the same as
     *         {@link #getExternalFilesDir(String)}.
     * @see #getExternalFilesDir(String)
     * @see Environment#getExternalStorageState(File)
     * @see Environment#isExternalStorageEmulated(File)
     * @see Environment#isExternalStorageRemovable(File)
     */
    public abstract File[] getExternalFilesDirs(String type);

    /**
     * Return the primary shared/external storage directory where this
     * application's OBB files (if there are any) can be found. Note if the
     * application does not have any OBB files, this directory may not exist.
     * <p>
     * This is like {@link #getFilesDir()} in that these files will be deleted
     * when the application is uninstalled, however there are some important
     * differences:
     * <ul>
     * <li>Shared storage may not always be available, since removable media can
     * be ejected by the user. Media state can be checked using
     * {@link Environment#getExternalStorageState(File)}.
     * <li>There is no security enforced with these files. For example, any
     * application holding
     * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} can write to
     * these files.
     * </ul>
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#KITKAT}, no permissions
     * are required to read or write to the path that this method returns.
     * However, starting from {@link android.os.Build.VERSION_CODES#M},
     * to read the OBB expansion files, you must declare the
     * {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} permission in the app manifest and ask for
     * permission at runtime as follows:
     * </p>
     * <p>
     * {@code <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
     * android:maxSdkVersion="23" />}
     * </p>
     * <p>
     * Starting from {@link android.os.Build.VERSION_CODES#N},
     * {@link android.Manifest.permission#READ_EXTERNAL_STORAGE}
     * permission is not required, so don’t ask for this
     * permission at runtime. To handle both cases, your app must first try to read the OBB file,
     * and if it fails, you must request
     * {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} permission at runtime.
     * </p>
     *
     * <p>
     * The following code snippet shows how to do this:
     * </p>
     *
     * <pre>
     * File obb = new File(obb_filename);
     * boolean open_failed = false;
     *
     * try {
     *     BufferedReader br = new BufferedReader(new FileReader(obb));
     *     open_failed = false;
     *     ReadObbFile(br);
     * } catch (IOException e) {
     *     open_failed = true;
     * }
     *
     * if (open_failed) {
     *     // request READ_EXTERNAL_STORAGE permission before reading OBB file
     *     ReadObbFileWithPermission();
     * }
     * </pre>
     *
     * On devices with multiple users (as described by {@link UserManager}),
     * multiple users may share the same OBB storage location. Applications
     * should ensure that multiple instances running under different users don't
     * interfere with each other.
     *
     * @return the absolute path to application-specific directory. May return
     *         {@code null} if shared storage is not currently available.
     * @see #getObbDirs()
     * @see Environment#getExternalStorageState(File)
     * @see Environment#isExternalStorageEmulated(File)
     * @see Environment#isExternalStorageRemovable(File)
     */
    public abstract File getObbDir();

    /**
     * Returns absolute paths to application-specific directories on all
     * shared/external storage devices where the application's OBB files (if
     * there are any) can be found. Note if the application does not have any
     * OBB files, these directories may not exist.
     * <p>
     * This is like {@link #getFilesDir()} in that these files will be deleted
     * when the application is uninstalled, however there are some important
     * differences:
     * <ul>
     * <li>Shared storage may not always be available, since removable media can
     * be ejected by the user. Media state can be checked using
     * {@link Environment#getExternalStorageState(File)}.
     * <li>There is no security enforced with these files. For example, any
     * application holding
     * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} can write to
     * these files.
     * </ul>
     * <p>
     * Shared storage devices returned here are considered a stable part of the
     * device, including physical media slots under a protective cover. The
     * returned paths do not include transient devices, such as USB flash drives
     * connected to handheld devices.
     * <p>
     * An application may store data on any or all of the returned devices. For
     * example, an app may choose to store large files on the device with the
     * most available space, as measured by {@link StatFs}.
     * <p>
     * No additional permissions are required for the calling app to read or
     * write files under the returned path. Write access outside of these paths
     * on secondary external storage devices is not available.
     *
     * @return the absolute paths to application-specific directories. Some
     *         individual paths may be {@code null} if that shared storage is
     *         not currently available. The first path returned is the same as
     *         {@link #getObbDir()}
     * @see #getObbDir()
     * @see Environment#getExternalStorageState(File)
     * @see Environment#isExternalStorageEmulated(File)
     * @see Environment#isExternalStorageRemovable(File)
     */
    public abstract File[] getObbDirs();

    /**
     * Returns the absolute path to the application specific cache directory on
     * the filesystem.
     * <p>
     * The system will automatically delete files in this directory as disk
     * space is needed elsewhere on the device. The system will always delete
     * older files first, as reported by {@link File#lastModified()}. If
     * desired, you can exert more control over how files are deleted using
     * {@link StorageManager#setCacheBehaviorGroup(File, boolean)} and
     * {@link StorageManager#setCacheBehaviorTombstone(File, boolean)}.
     * <p>
     * Apps are strongly encouraged to keep their usage of cache space below the
     * quota returned by
     * {@link StorageManager#getCacheQuotaBytes(java.util.UUID)}. If your app
     * goes above this quota, your cached files will be some of the first to be
     * deleted when additional disk space is needed. Conversely, if your app
     * stays under this quota, your cached files will be some of the last to be
     * deleted when additional disk space is needed.
     * <p>
     * Note that your cache quota will change over time depending on how
     * frequently the user interacts with your app, and depending on how much
     * system-wide disk space is used.
     * <p>
     * The returned path may change over time if the calling app is moved to an
     * adopted storage device, so only relative paths should be persisted.
     * <p>
     * Apps require no extra permissions to read or write to the returned path,
     * since this path lives in their private storage.
     *
     * @return The path of the directory holding application cache files.
     * @see #openFileOutput
     * @see #getFileStreamPath
     * @see #getDir
     * @see #getExternalCacheDir
     */
    public abstract File getCacheDir();

    /**
     * Returns the absolute path to the application specific cache directory on
     * the filesystem designed for storing cached code.
     * <p>
     * The system will delete any files stored in this location both when your
     * specific application is upgraded, and when the entire platform is
     * upgraded.
     * <p>
     * This location is optimal for storing compiled or optimized code generated
     * by your application at runtime.
     * <p>
     * The returned path may change over time if the calling app is moved to an
     * adopted storage device, so only relative paths should be persisted.
     * <p>
     * Apps require no extra permissions to read or write to the returned path,
     * since this path lives in their private storage.
     *
     * @return The path of the directory holding application code cache files.
     */
    public abstract File getCodeCacheDir();

    /**
     * Returns absolute path to application-specific directory on the primary
     * shared/external storage device where the application can place cache
     * files it owns. These files are internal to the application, and not
     * typically visible to the user as media.
     * <p>
     * This is like {@link #getCacheDir()} in that these files will be deleted
     * when the application is uninstalled, however there are some important
     * differences:
     * <ul>
     * <li>The platform does not always monitor the space available in shared
     * storage, and thus may not automatically delete these files. Apps should
     * always manage the maximum space used in this location. Currently the only
     * time files here will be deleted by the platform is when running on
     * {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1} or later and
     * {@link Environment#isExternalStorageEmulated(File)} returns true.
     * <li>Shared storage may not always be available, since removable media can
     * be ejected by the user. Media state can be checked using
     * {@link Environment#getExternalStorageState(File)}.
     * <li>There is no security enforced with these files. For example, any
     * application holding
     * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} can write to
     * these files.
     * </ul>
     * <p>
     * If a shared storage device is emulated (as determined by
     * {@link Environment#isExternalStorageEmulated(File)}), its contents are
     * backed by a private user data partition, which means there is little
     * benefit to storing data here instead of the private directory returned by
     * {@link #getCacheDir()}.
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#KITKAT}, no permissions
     * are required to read or write to the returned path; it's always
     * accessible to the calling app. This only applies to paths generated for
     * package name of the calling application. To access paths belonging to
     * other packages,
     * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} and/or
     * {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} are required.
     * <p>
     * On devices with multiple users (as described by {@link UserManager}),
     * each user has their own isolated shared storage. Applications only have
     * access to the shared storage for the user they're running as.
     * <p>
     * The returned path may change over time if different shared storage media
     * is inserted, so only relative paths should be persisted.
     *
     * @return the absolute path to application-specific directory. May return
     *         {@code null} if shared storage is not currently available.
     * @see #getCacheDir
     * @see #getExternalCacheDirs()
     * @see Environment#getExternalStorageState(File)
     * @see Environment#isExternalStorageEmulated(File)
     * @see Environment#isExternalStorageRemovable(File)
     */
    @Nullable
    public abstract File getExternalCacheDir();

    /**
     * Returns absolute path to application-specific directory in the preloaded cache.
     * <p>Files stored in the cache directory can be deleted when the device runs low on storage.
     * There is no guarantee when these files will be deleted.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    @SystemApi
    public abstract File getPreloadsFileCache();

    /**
     * Returns absolute paths to application-specific directories on all
     * shared/external storage devices where the application can place cache
     * files it owns. These files are internal to the application, and not
     * typically visible to the user as media.
     * <p>
     * This is like {@link #getCacheDir()} in that these files will be deleted
     * when the application is uninstalled, however there are some important
     * differences:
     * <ul>
     * <li>The platform does not always monitor the space available in shared
     * storage, and thus may not automatically delete these files. Apps should
     * always manage the maximum space used in this location. Currently the only
     * time files here will be deleted by the platform is when running on
     * {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1} or later and
     * {@link Environment#isExternalStorageEmulated(File)} returns true.
     * <li>Shared storage may not always be available, since removable media can
     * be ejected by the user. Media state can be checked using
     * {@link Environment#getExternalStorageState(File)}.
     * <li>There is no security enforced with these files. For example, any
     * application holding
     * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} can write to
     * these files.
     * </ul>
     * <p>
     * If a shared storage device is emulated (as determined by
     * {@link Environment#isExternalStorageEmulated(File)}), its contents are
     * backed by a private user data partition, which means there is little
     * benefit to storing data here instead of the private directory returned by
     * {@link #getCacheDir()}.
     * <p>
     * Shared storage devices returned here are considered a stable part of the
     * device, including physical media slots under a protective cover. The
     * returned paths do not include transient devices, such as USB flash drives
     * connected to handheld devices.
     * <p>
     * An application may store data on any or all of the returned devices. For
     * example, an app may choose to store large files on the device with the
     * most available space, as measured by {@link StatFs}.
     * <p>
     * No additional permissions are required for the calling app to read or
     * write files under the returned path. Write access outside of these paths
     * on secondary external storage devices is not available.
     * <p>
     * The returned paths may change over time if different shared storage media
     * is inserted, so only relative paths should be persisted.
     *
     * @return the absolute paths to application-specific directories. Some
     *         individual paths may be {@code null} if that shared storage is
     *         not currently available. The first path returned is the same as
     *         {@link #getExternalCacheDir()}.
     * @see #getExternalCacheDir()
     * @see Environment#getExternalStorageState(File)
     * @see Environment#isExternalStorageEmulated(File)
     * @see Environment#isExternalStorageRemovable(File)
     */
    public abstract File[] getExternalCacheDirs();

    /**
     * Returns absolute paths to application-specific directories on all
     * shared/external storage devices where the application can place media
     * files. These files are scanned and made available to other apps through
     * {@link MediaStore}.
     * <p>
     * This is like {@link #getExternalFilesDirs} in that these files will be
     * deleted when the application is uninstalled, however there are some
     * important differences:
     * <ul>
     * <li>Shared storage may not always be available, since removable media can
     * be ejected by the user. Media state can be checked using
     * {@link Environment#getExternalStorageState(File)}.
     * <li>There is no security enforced with these files. For example, any
     * application holding
     * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} can write to
     * these files.
     * </ul>
     * <p>
     * Shared storage devices returned here are considered a stable part of the
     * device, including physical media slots under a protective cover. The
     * returned paths do not include transient devices, such as USB flash drives
     * connected to handheld devices.
     * <p>
     * An application may store data on any or all of the returned devices. For
     * example, an app may choose to store large files on the device with the
     * most available space, as measured by {@link StatFs}.
     * <p>
     * No additional permissions are required for the calling app to read or
     * write files under the returned path. Write access outside of these paths
     * on secondary external storage devices is not available.
     * <p>
     * The returned paths may change over time if different shared storage media
     * is inserted, so only relative paths should be persisted.
     *
     * @return the absolute paths to application-specific directories. Some
     *         individual paths may be {@code null} if that shared storage is
     *         not currently available.
     * @see Environment#getExternalStorageState(File)
     * @see Environment#isExternalStorageEmulated(File)
     * @see Environment#isExternalStorageRemovable(File)
     * @deprecated These directories still exist and are scanned, but developers
     *             are encouraged to migrate to inserting content into a
     *             {@link MediaStore} collection directly, as any app can
     *             contribute new media to {@link MediaStore} with no
     *             permissions required, starting in
     *             {@link android.os.Build.VERSION_CODES#Q}.
     */
    @Deprecated
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
     * <p>
     * The returned path may change over time if the calling app is moved to an
     * adopted storage device, so only relative paths should be persisted.
     * <p>
     * Apps require no extra permissions to read or write to the returned path,
     * since this path lives in their private storage.
     *
     * @param name Name of the directory to retrieve.  This is a directory
     * that is created as part of your application data.
     * @param mode Operating mode.
     *
     * @return A {@link File} object for the requested directory.  The directory
     * will have been created if it does not already exist.
     *
     * @see #openFileOutput(String, int)
     */
    public abstract File getDir(String name, @FileMode int mode);

    /**
     * Open a new private SQLiteDatabase associated with this Context's
     * application package. Create the database file if it doesn't exist.
     *
     * @param name The name (unique in the application package) of the database.
     * @param mode Operating mode.
     * @param factory An optional factory class that is called to instantiate a
     *            cursor when query is called.
     * @return The contents of a newly created database with the given name.
     * @throws android.database.sqlite.SQLiteException if the database file
     *             could not be opened.
     * @see #MODE_PRIVATE
     * @see #MODE_ENABLE_WRITE_AHEAD_LOGGING
     * @see #MODE_NO_LOCALIZED_COLLATORS
     * @see #deleteDatabase
     */
    public abstract SQLiteDatabase openOrCreateDatabase(String name,
            @DatabaseMode int mode, CursorFactory factory);

    /**
     * Open a new private SQLiteDatabase associated with this Context's
     * application package. Creates the database file if it doesn't exist.
     * <p>
     * Accepts input param: a concrete instance of {@link DatabaseErrorHandler}
     * to be used to handle corruption when sqlite reports database corruption.
     * </p>
     *
     * @param name The name (unique in the application package) of the database.
     * @param mode Operating mode.
     * @param factory An optional factory class that is called to instantiate a
     *            cursor when query is called.
     * @param errorHandler the {@link DatabaseErrorHandler} to be used when
     *            sqlite reports database corruption. if null,
     *            {@link android.database.DefaultDatabaseErrorHandler} is
     *            assumed.
     * @return The contents of a newly created database with the given name.
     * @throws android.database.sqlite.SQLiteException if the database file
     *             could not be opened.
     * @see #MODE_PRIVATE
     * @see #MODE_ENABLE_WRITE_AHEAD_LOGGING
     * @see #MODE_NO_LOCALIZED_COLLATORS
     * @see #deleteDatabase
     */
    public abstract SQLiteDatabase openOrCreateDatabase(String name,
            @DatabaseMode int mode, CursorFactory factory,
            @Nullable DatabaseErrorHandler errorHandler);

    /**
     * Move an existing database file from the given source storage context to
     * this context. This is typically used to migrate data between storage
     * locations after an upgrade, such as migrating to device protected
     * storage.
     * <p>
     * The database must be closed before being moved.
     *
     * @param sourceContext The source context which contains the existing
     *            database to move.
     * @param name The name of the database file.
     * @return {@code true} if the move was successful or if the database didn't
     *         exist in the source context, otherwise {@code false}.
     * @see #createDeviceProtectedStorageContext()
     */
    public abstract boolean moveDatabaseFrom(Context sourceContext, String name);

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
     * <p>
     * The returned path may change over time if the calling app is moved to an
     * adopted storage device, so only relative paths should be persisted.
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
     *`
     * @see #startActivity(Intent, Bundle)
     * @see PackageManager#resolveActivity
     */
    public abstract void startActivity(@RequiresPermission Intent intent);

    /**
     * Version of {@link #startActivity(Intent)} that allows you to specify the
     * user the activity will be started for.  This is not available to applications
     * that are not pre-installed on the system image.
     * @param intent The description of the activity to start.
     * @param user The UserHandle of the user to start this activity for.
     * @throws ActivityNotFoundException &nbsp;
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public void startActivityAsUser(@RequiresPermission @NonNull Intent intent,
            @NonNull UserHandle user) {
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
    public abstract void startActivity(@RequiresPermission Intent intent,
            @Nullable Bundle options);

    /**
     * Version of {@link #startActivity(Intent, Bundle)} that allows you to specify the
     * user the activity will be started for.  This is not available to applications
     * that are not pre-installed on the system image.
     * @param intent The description of the activity to start.
     * @param options Additional options for how the Activity should be started.
     * May be null if there are no options.  See {@link android.app.ActivityOptions}
     * for how to build the Bundle supplied here; there are no supported definitions
     * for building it manually.
     * @param userId The UserHandle of the user to start this activity for.
     * @throws ActivityNotFoundException &nbsp;
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public void startActivityAsUser(@RequiresPermission @NonNull Intent intent,
            @Nullable Bundle options, @NonNull UserHandle userId) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Version of {@link #startActivity(Intent, Bundle)} that returns a result to the caller. This
     * is only supported for Views and Fragments.
     * @param who The identifier for the calling element that will receive the result.
     * @param intent The intent to start.
     * @param requestCode The code that will be returned with onActivityResult() identifying this
     *          request.
     * @param options Additional options for how the Activity should be started.
     *          May be null if there are no options.  See {@link android.app.ActivityOptions}
     *          for how to build the Bundle supplied here; there are no supported definitions
     *          for building it manually.
     * @hide
     */
    @UnsupportedAppUsage
    public void startActivityForResult(
            @NonNull String who, Intent intent, int requestCode, @Nullable Bundle options) {
        throw new RuntimeException("This method is only implemented for Activity-based Contexts. "
                + "Check canStartActivityForResult() before calling.");
    }

    /**
     * Identifies whether this Context instance will be able to process calls to
     * {@link #startActivityForResult(String, Intent, int, Bundle)}.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean canStartActivityForResult() {
        return false;
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
    public abstract void startActivities(@RequiresPermission Intent[] intents);

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
     * See {@link android.content.Context#startActivity(Intent, Bundle)}
     * Context.startActivity(Intent, Bundle)} for more details.
     *
     * @throws ActivityNotFoundException &nbsp;
     *
     * @see #startActivities(Intent[])
     * @see PackageManager#resolveActivity
     */
    public abstract void startActivities(@RequiresPermission Intent[] intents, Bundle options);

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
     * See {@link android.content.Context#startActivity(Intent, Bundle)}
     * Context.startActivity(Intent, Bundle)} for more details.
     *
     * @return The corresponding flag {@link ActivityManager#START_CANCELED},
     *         {@link ActivityManager#START_SUCCESS} etc. indicating whether the launch was
     *         successful.
     *
     * @throws ActivityNotFoundException &nbsp;
     *
     * @see #startActivities(Intent[])
     * @see PackageManager#resolveActivity
     */
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    public int startActivitiesAsUser(Intent[] intents, Bundle options, UserHandle userHandle) {
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
    public abstract void startIntentSender(IntentSender intent, @Nullable Intent fillInIntent,
            @Intent.MutableFlags int flagsMask, @Intent.MutableFlags int flagsValues,
            int extraFlags) throws IntentSender.SendIntentException;

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
     * See {@link android.content.Context#startActivity(Intent, Bundle)}
     * Context.startActivity(Intent, Bundle)} for more details.  If options
     * have also been supplied by the IntentSender, options given here will
     * override any that conflict with those given by the IntentSender.
     *
     * @see #startActivity(Intent, Bundle)
     * @see #startIntentSender(IntentSender, Intent, int, int, int)
     */
    public abstract void startIntentSender(IntentSender intent, @Nullable Intent fillInIntent,
            @Intent.MutableFlags int flagsMask, @Intent.MutableFlags int flagsValues,
            int extraFlags, @Nullable Bundle options) throws IntentSender.SendIntentException;

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
    public abstract void sendBroadcast(@RequiresPermission Intent intent);

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
    public abstract void sendBroadcast(@RequiresPermission Intent intent,
            @Nullable String receiverPermission);


    /**
     * Broadcast the given intent to all interested BroadcastReceivers, allowing
     * an array of required permissions to be enforced.  This call is asynchronous; it returns
     * immediately, and you will continue executing while the receivers are run.  No results are
     * propagated from receivers and receivers can not abort the broadcast. If you want to allow
     * receivers to propagate results or abort the broadcast, you must send an ordered broadcast
     * using {@link #sendOrderedBroadcast(Intent, String)}.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param receiverPermissions Array of names of permissions that a receiver must hold
     *                            in order to receive your broadcast.
     *                            If empty, no permissions are required.
     *
     * @see android.content.BroadcastReceiver
     * @see #registerReceiver
     * @see #sendBroadcast(Intent)
     * @see #sendOrderedBroadcast(Intent, String)
     * @see #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)
     * @hide
     */
    public void sendBroadcastMultiplePermissions(@NonNull Intent intent,
            @NonNull String[] receiverPermissions) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Like {@link #sendBroadcastMultiplePermissions(Intent, String[])}, but also allows
     * specification of a list of excluded permissions. This allows sending a broadcast to an
     * app that has the permissions in `receiverPermissions` but not `excludedPermissions`.
     * @hide
     */
    public void sendBroadcastMultiplePermissions(@NonNull Intent intent,
            @NonNull String[] receiverPermissions, @Nullable String[] excludedPermissions) {
        sendBroadcastMultiplePermissions(intent, receiverPermissions, excludedPermissions, null);
    }

    /**
     * Like {@link #sendBroadcastMultiplePermissions(Intent, String[], String[])}, but also allows
     * specification of a list of excluded packages.
     *
     * @hide
     */
    public void sendBroadcastMultiplePermissions(@NonNull Intent intent,
            @NonNull String[] receiverPermissions, @Nullable String[] excludedPermissions,
            @Nullable String[] excludedPackages) {
        sendBroadcastMultiplePermissions(intent, receiverPermissions, excludedPermissions,
                excludedPackages, null);
    }

    /**
     * Like {@link #sendBroadcastMultiplePermissions(Intent, String[], String[], String[])}, but
     * also allows specification of options generated from {@link android.app.BroadcastOptions}.
     *
     * @hide
     */
    public void sendBroadcastMultiplePermissions(@NonNull Intent intent,
            @NonNull String[] receiverPermissions, @Nullable String[] excludedPermissions,
            @Nullable String[] excludedPackages, @Nullable BroadcastOptions options) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Version of {@link #sendBroadcastMultiplePermissions(Intent, String[])} that allows you to
     * specify the {@link android.app.BroadcastOptions}.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param receiverPermissions Array of names of permissions that a receiver must hold
     *                            in order to receive your broadcast.
     *                            If empty, no permissions are required.
     * @param options Additional sending options, generated from a
     *                {@link android.app.BroadcastOptions}.
     * @see #sendBroadcastMultiplePermissions(Intent, String[])
     * @see android.app.BroadcastOptions
     * @hide
     */
    public void sendBroadcastMultiplePermissions(@NonNull Intent intent,
            @NonNull String[] receiverPermissions, @Nullable Bundle options) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Version of {@link #sendBroadcastMultiplePermissions(Intent, String[])} that allows you to
     * specify the {@link android.app.BroadcastOptions}.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param receiverPermissions Array of names of permissions that a receiver must hold
     *                            in order to receive your broadcast.
     *                            If empty, no permissions are required.
     * @param options Additional sending options, generated from a
     *                {@link android.app.BroadcastOptions}.
     * @see #sendBroadcastMultiplePermissions(Intent, String[])
     * @see android.app.BroadcastOptions
     * @hide
     */
    @SystemApi
    public void sendBroadcastMultiplePermissions(@NonNull Intent intent,
            @NonNull String[] receiverPermissions, @Nullable BroadcastOptions options) {
        sendBroadcastMultiplePermissions(intent, receiverPermissions,
                (options == null ? null : options.toBundle()));
    }

    /**
     * Broadcast the given intent to all interested BroadcastReceivers, allowing
     * an array of required permissions to be enforced.  This call is asynchronous; it returns
     * immediately, and you will continue executing while the receivers are run.  No results are
     * propagated from receivers and receivers can not abort the broadcast. If you want to allow
     * receivers to propagate results or abort the broadcast, you must send an ordered broadcast
     * using {@link #sendOrderedBroadcast(Intent, String)}.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param receiverPermissions Array of names of permissions that a receiver must hold
     *                            in order to receive your broadcast.
     *                            If empty, no permissions are required.
     *
     * @see android.content.BroadcastReceiver
     * @see #registerReceiver
     * @see #sendBroadcast(Intent)
     * @see #sendOrderedBroadcast(Intent, String)
     * @see #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)
     */
    public void sendBroadcastWithMultiplePermissions(@NonNull Intent intent,
            @NonNull String[] receiverPermissions) {
        sendBroadcastMultiplePermissions(intent, receiverPermissions);
    }

    /**
     * Broadcast the given intent to all interested BroadcastReceivers, allowing
     * an array of required permissions to be enforced.  This call is asynchronous; it returns
     * immediately, and you will continue executing while the receivers are run.  No results are
     * propagated from receivers and receivers can not abort the broadcast. If you want to allow
     * receivers to propagate results or abort the broadcast, you must send an ordered broadcast
     * using {@link #sendOrderedBroadcast(Intent, String)}.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param user The user to send the broadcast to.
     * @param receiverPermissions Array of names of permissions that a receiver must hold
     *                            in order to receive your broadcast.
     *                            If null or empty, no permissions are required.
     *
     * @see android.content.BroadcastReceiver
     * @see #registerReceiver
     * @see #sendBroadcast(Intent)
     * @see #sendOrderedBroadcast(Intent, String)
     * @see #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract void sendBroadcastAsUserMultiplePermissions(Intent intent, UserHandle user,
            String[] receiverPermissions);

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
     * @param options (optional) Additional sending options, generated from a
     * {@link android.app.BroadcastOptions}.
     *
     * @see android.content.BroadcastReceiver
     * @see #registerReceiver
     * @see #sendBroadcast(Intent)
     * @see #sendOrderedBroadcast(Intent, String)
     * @see #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)
     */
    public void sendBroadcast(@NonNull Intent intent,
            @Nullable String receiverPermission,
            @Nullable Bundle options) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Like {@link #sendBroadcast(Intent, String)}, but also allows specification
     * of an associated app op as per {@link android.app.AppOpsManager}.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
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
    public abstract void sendOrderedBroadcast(@RequiresPermission Intent intent,
            @Nullable String receiverPermission);

    /**
     * Broadcast the given intent to all interested BroadcastReceivers, delivering
     * them one at a time to allow more preferred receivers to consume the
     * broadcast before it is delivered to less preferred receivers.  This
     * call is asynchronous; it returns immediately, and you will continue
     * executing while the receivers are run.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent             The Intent to broadcast; all receivers matching this
     *                           Intent will receive the broadcast.
     * @param receiverPermission (optional) String naming a permissions that
     *                           a receiver must hold in order to receive your broadcast.
     *                           If null, no permission is required.
     * @param options            (optional) Additional sending options, generated from a
     *                           {@link android.app.BroadcastOptions}.
     * @see android.content.BroadcastReceiver
     * @see #registerReceiver
     * @see #sendBroadcast(Intent)
     * @see #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)
     */
    public void sendOrderedBroadcast(@NonNull Intent intent, @Nullable String receiverPermission,
            @Nullable Bundle options) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

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
     * @see android.content.BroadcastReceiver
     * @see #registerReceiver
     * @see android.app.Activity#RESULT_OK
     */
    public abstract void sendOrderedBroadcast(@RequiresPermission @NonNull Intent intent,
            @Nullable String receiverPermission, @Nullable BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable Bundle initialExtras);

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
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param receiverPermission String naming a permissions that
     *               a receiver must hold in order to receive your broadcast.
     *               If null, no permission is required.
     * @param options (optional) Additional sending options, generated from a
     * {@link android.app.BroadcastOptions}.
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
     * @see #sendBroadcast(Intent)
     * @see #sendBroadcast(Intent, String)
     * @see #sendOrderedBroadcast(Intent, String)
     * @see android.content.BroadcastReceiver
     * @see #registerReceiver
     * @see android.app.Activity#RESULT_OK
     */
    public void sendOrderedBroadcast(@NonNull Intent intent,
            @Nullable String receiverPermission, @Nullable Bundle options,
            @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler,
            int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Like {@link #sendOrderedBroadcast(Intent, String, BroadcastReceiver, android.os.Handler,
     * int, String, android.os.Bundle)}, but also allows specification
     * of an associated app op as per {@link android.app.AppOpsManager}.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract void sendOrderedBroadcast(Intent intent,
            String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras);

    /**
     * Version of {@link #sendBroadcast(Intent)} that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.
     * @param intent The intent to broadcast
     * @param user UserHandle to send the intent to.
     * @see #sendBroadcast(Intent)
     */
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    public abstract void sendBroadcastAsUser(@RequiresPermission Intent intent,
            UserHandle user);

    /**
     * Version of {@link #sendBroadcast(Intent, String)} that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.
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
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    public abstract void sendBroadcastAsUser(@RequiresPermission Intent intent,
            UserHandle user, @Nullable String receiverPermission);

    /**
     * Version of {@link #sendBroadcast(Intent, String, Bundle)} that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param user UserHandle to send the intent to.
     * @param receiverPermission (optional) String naming a permission that
     *               a receiver must hold in order to receive your broadcast.
     *               If null, no permission is required.
     * @param options (optional) Additional sending options, generated from a
     * {@link android.app.BroadcastOptions}.
     *
     * @see #sendBroadcast(Intent, String, Bundle)
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    public abstract void sendBroadcastAsUser(@RequiresPermission Intent intent,
            UserHandle user, @Nullable String receiverPermission, @Nullable Bundle options);

    /**
     * Version of {@link #sendBroadcast(Intent, String)} that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param user UserHandle to send the intent to.
     * @param receiverPermission (optional) String naming a permission that
     *               a receiver must hold in order to receive your broadcast.
     *               If null, no permission is required.
     * @param appOp The app op associated with the broadcast.
     *
     * @see #sendBroadcast(Intent, String)
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public abstract void sendBroadcastAsUser(@RequiresPermission Intent intent,
            UserHandle user, @Nullable String receiverPermission, int appOp);

    /**
     * Version of
     * {@link #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)}
     * that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.
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
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    public abstract void sendOrderedBroadcastAsUser(@RequiresPermission Intent intent,
            UserHandle user, @Nullable String receiverPermission, BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable  Bundle initialExtras);

    /**
     * Similar to above but takes an appOp as well, to enforce restrictions.
     * @see #sendOrderedBroadcastAsUser(Intent, UserHandle, String,
     *       BroadcastReceiver, Handler, int, String, Bundle)
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public abstract void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            @Nullable String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable  Bundle initialExtras);

    /**
     * Similar to above but takes an appOp as well, to enforce restrictions, and an options Bundle.
     * @see #sendOrderedBroadcastAsUser(Intent, UserHandle, String,
     *       BroadcastReceiver, Handler, int, String, Bundle)
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    @UnsupportedAppUsage
    public abstract void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            @Nullable String receiverPermission, int appOp, @Nullable Bundle options,
            BroadcastReceiver resultReceiver, @Nullable Handler scheduler, int initialCode,
            @Nullable String initialData, @Nullable  Bundle initialExtras);

    /**
     * Version of
     * {@link #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String,
     * Bundle)} that allows you to specify the App Op to enforce restrictions on which receivers
     * the broadcast will be sent to.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param receiverPermission String naming a permissions that
     *               a receiver must hold in order to receive your broadcast.
     *               If null, no permission is required.
     * @param receiverAppOp The app op associated with the broadcast. If null, no appOp is
     *                      required. If both receiverAppOp and receiverPermission are non-null,
     *                      a receiver must have both of them to
     *                      receive the broadcast
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
    public void sendOrderedBroadcast(@NonNull Intent intent, @Nullable String receiverPermission,
            @Nullable String receiverAppOp, @Nullable BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable Bundle initialExtras) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Version of
     * {@link #sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String,
     * Bundle)} that allows you to specify the App Op to enforce restrictions on which receivers
     * the broadcast will be sent to as well as supply an optional sending options
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     *               Intent will receive the broadcast.
     * @param receiverPermission String naming a permissions that
     *               a receiver must hold in order to receive your broadcast.
     *               If null, no permission is required.
     * @param receiverAppOp The app op associated with the broadcast. If null, no appOp is
     *                      required. If both receiverAppOp and receiverPermission are non-null,
     *                      a receiver must have both of them to
     *                      receive the broadcast
     * @param options (optional) Additional sending options, generated from a
     * {@link android.app.BroadcastOptions}.
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
     * @see android.app.BroadcastOptions
     * @hide
     */
    public void sendOrderedBroadcast(@RequiresPermission @NonNull Intent intent, int initialCode,
            @Nullable String receiverPermission, @Nullable String receiverAppOp,
            @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler,
            @Nullable String initialData, @Nullable Bundle initialExtras,
            @Nullable Bundle options) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * <p>Perform a {@link #sendBroadcast(Intent)} that is "sticky," meaning the
     * Intent you are sending stays around after the broadcast is complete,
     * so that others can quickly retrieve that data through the return
     * value of {@link #registerReceiver(BroadcastReceiver, IntentFilter)}.  In
     * all other ways, this behaves the same as
     * {@link #sendBroadcast(Intent)}.
     *
     * @deprecated Sticky broadcasts should not be used.  They provide no security (anyone
     * can access them), no protection (anyone can modify them), and many other problems.
     * The recommended pattern is to use a non-sticky broadcast to report that <em>something</em>
     * has changed, with another mechanism for apps to retrieve the current value whenever
     * desired.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     * Intent will receive the broadcast, and the Intent will be held to
     * be re-broadcast to future receivers.
     *
     * @see #sendBroadcast(Intent)
     * @see #sendStickyOrderedBroadcast(Intent, BroadcastReceiver, Handler, int, String, Bundle)
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.BROADCAST_STICKY)
    public abstract void sendStickyBroadcast(@RequiresPermission Intent intent);

    /**
     * <p>Perform a {@link #sendBroadcast(Intent)} that is "sticky," meaning the
     * Intent you are sending stays around after the broadcast is complete,
     * so that others can quickly retrieve that data through the return
     * value of {@link #registerReceiver(BroadcastReceiver, IntentFilter)}.  In
     * all other ways, this behaves the same as
     * {@link #sendBroadcast(Intent)}.
     *
     * @deprecated Sticky broadcasts should not be used.  They provide no security (anyone
     * can access them), no protection (anyone can modify them), and many other problems.
     * The recommended pattern is to use a non-sticky broadcast to report that <em>something</em>
     * has changed, with another mechanism for apps to retrieve the current value whenever
     * desired.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     * Intent will receive the broadcast, and the Intent will be held to
     * be re-broadcast to future receivers.
     * @param options (optional) Additional sending options, generated from a
     * {@link android.app.BroadcastOptions}.
     *
     * @see #sendBroadcast(Intent)
     * @see #sendStickyOrderedBroadcast(Intent, BroadcastReceiver, Handler, int, String, Bundle)
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.BROADCAST_STICKY)
    public void sendStickyBroadcast(@RequiresPermission @NonNull Intent intent,
            @Nullable Bundle options) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * <p>Version of {@link #sendStickyBroadcast} that allows you to
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
     * @deprecated Sticky broadcasts should not be used.  They provide no security (anyone
     * can access them), no protection (anyone can modify them), and many other problems.
     * The recommended pattern is to use a non-sticky broadcast to report that <em>something</em>
     * has changed, with another mechanism for apps to retrieve the current value whenever
     * desired.
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
    @Deprecated
    @RequiresPermission(android.Manifest.permission.BROADCAST_STICKY)
    public abstract void sendStickyOrderedBroadcast(@RequiresPermission Intent intent,
            BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable Bundle initialExtras);

    /**
     * <p>Remove the data previously sent with {@link #sendStickyBroadcast},
     * so that it is as if the sticky broadcast had never happened.
     *
     * @deprecated Sticky broadcasts should not be used.  They provide no security (anyone
     * can access them), no protection (anyone can modify them), and many other problems.
     * The recommended pattern is to use a non-sticky broadcast to report that <em>something</em>
     * has changed, with another mechanism for apps to retrieve the current value whenever
     * desired.
     *
     * @param intent The Intent that was previously broadcast.
     *
     * @see #sendStickyBroadcast
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.BROADCAST_STICKY)
    public abstract void removeStickyBroadcast(@RequiresPermission Intent intent);

    /**
     * <p>Version of {@link #sendStickyBroadcast(Intent)} that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.
     *
     * @deprecated Sticky broadcasts should not be used.  They provide no security (anyone
     * can access them), no protection (anyone can modify them), and many other problems.
     * The recommended pattern is to use a non-sticky broadcast to report that <em>something</em>
     * has changed, with another mechanism for apps to retrieve the current value whenever
     * desired.
     *
     * @param intent The Intent to broadcast; all receivers matching this
     * Intent will receive the broadcast, and the Intent will be held to
     * be re-broadcast to future receivers.
     * @param user UserHandle to send the intent to.
     *
     * @see #sendBroadcast(Intent)
     */
    @Deprecated
    @RequiresPermission(allOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.BROADCAST_STICKY
    })
    public abstract void sendStickyBroadcastAsUser(@RequiresPermission Intent intent,
            UserHandle user);

    /**
     * @hide
     * This is just here for sending CONNECTIVITY_ACTION.
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Deprecated
    @RequiresPermission(allOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.BROADCAST_STICKY
    })
    public abstract void sendStickyBroadcastAsUser(@RequiresPermission Intent intent,
            UserHandle user, Bundle options);

    /**
     * <p>Version of
     * {@link #sendStickyOrderedBroadcast(Intent, BroadcastReceiver, Handler, int, String, Bundle)}
     * that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.
     *
     * <p>See {@link BroadcastReceiver} for more information on Intent broadcasts.
     *
     * @deprecated Sticky broadcasts should not be used.  They provide no security (anyone
     * can access them), no protection (anyone can modify them), and many other problems.
     * The recommended pattern is to use a non-sticky broadcast to report that <em>something</em>
     * has changed, with another mechanism for apps to retrieve the current value whenever
     * desired.
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
    @Deprecated
    @RequiresPermission(allOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.BROADCAST_STICKY
    })
    public abstract void sendStickyOrderedBroadcastAsUser(@RequiresPermission Intent intent,
            UserHandle user, BroadcastReceiver resultReceiver,
            @Nullable Handler scheduler, int initialCode, @Nullable String initialData,
            @Nullable Bundle initialExtras);

    /**
     * <p>Version of {@link #removeStickyBroadcast(Intent)} that allows you to specify the
     * user the broadcast will be sent to.  This is not available to applications
     * that are not pre-installed on the system image.
     *
     * <p>You must hold the {@link android.Manifest.permission#BROADCAST_STICKY}
     * permission in order to use this API.  If you do not hold that
     * permission, {@link SecurityException} will be thrown.
     *
     * @deprecated Sticky broadcasts should not be used.  They provide no security (anyone
     * can access them), no protection (anyone can modify them), and many other problems.
     * The recommended pattern is to use a non-sticky broadcast to report that <em>something</em>
     * has changed, with another mechanism for apps to retrieve the current value whenever
     * desired.
     *
     * @param intent The Intent that was previously broadcast.
     * @param user UserHandle to remove the sticky broadcast from.
     *
     * @see #sendStickyBroadcastAsUser
     */
    @Deprecated
    @RequiresPermission(allOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.BROADCAST_STICKY
    })
    public abstract void removeStickyBroadcastAsUser(@RequiresPermission Intent intent,
            UserHandle user);

    /**
     * Register a BroadcastReceiver to be run in the main activity thread.  The
     * <var>receiver</var> will be called with any broadcast Intent that
     * matches <var>filter</var>, in the main application thread.
     *
     * <p>The system may broadcast Intents that are "sticky" -- these stay
     * around after the broadcast has finished, to be sent to any later
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
     * <p>For apps targeting {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * either {@link #RECEIVER_EXPORTED} or {@link #RECEIVER_NOT_EXPORTED} must be
     * specified if the receiver is not being registered for <a
     * href="{@docRoot}guide/components/broadcasts#system-broadcasts">system broadcasts</a>
     * or a {@link SecurityException} will be thrown. See {@link
     * #registerReceiver(BroadcastReceiver, IntentFilter, int)} to register a receiver with
     * flags.
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
     * Register to receive intent broadcasts, with the receiver optionally being
     * exposed to Instant Apps. See
     * {@link #registerReceiver(BroadcastReceiver, IntentFilter)} for more
     * information. By default Instant Apps cannot interact with receivers in other
     * applications, this allows you to expose a receiver that Instant Apps can
     * interact with.
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
     * @param flags Additional options for the receiver. For apps targeting {@link
     *      android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} either {@link #RECEIVER_EXPORTED} or
     *      {@link #RECEIVER_NOT_EXPORTED} must be specified if the receiver isn't being registered
     *      for <a href="{@docRoot}guide/components/broadcasts#system-broadcasts">system
     *      broadcasts</a> or a {@link SecurityException} will be thrown. If {@link
     *      #RECEIVER_EXPORTED} is specified, a receiver may additionally specify {@link
     *      #RECEIVER_VISIBLE_TO_INSTANT_APPS}. For a complete list of system broadcast actions,
     *      see the BROADCAST_ACTIONS.TXT file in the Android SDK. If both {@link
     *      #RECEIVER_EXPORTED} and {@link #RECEIVER_NOT_EXPORTED} are specified, an {@link
     *      IllegalArgumentException} will be thrown.
     *
     * @return The first sticky intent found that matches <var>filter</var>,
     *         or null if there are none.
     *
     * @see #registerReceiver(BroadcastReceiver, IntentFilter)
     * @see #sendBroadcast
     * @see #unregisterReceiver
     */
    @Nullable
    public abstract Intent registerReceiver(@Nullable BroadcastReceiver receiver,
                                            IntentFilter filter,
                                            @RegisterReceiverFlags int flags);

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
     * <p>For apps targeting {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * either {@link #RECEIVER_EXPORTED} or {@link #RECEIVER_NOT_EXPORTED} must be
     * specified if the receiver is not being registered for <a
     * href="{@docRoot}guide/components/broadcasts#system-broadcasts">system broadcasts</a>
     * or a {@link SecurityException} will be thrown. See {@link
     * #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler, int)} to register a
     * receiver with flags.
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
     * Register to receive intent broadcasts, to run in the context of
     * <var>scheduler</var>. See
     * {@link #registerReceiver(BroadcastReceiver, IntentFilter, int)} and
     * {@link #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler)}
     * for more information.
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
     * @param flags Additional options for the receiver. For apps targeting {@link
     *      android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} either {@link #RECEIVER_EXPORTED} or
     *      {@link #RECEIVER_NOT_EXPORTED} must be specified if the receiver isn't being registered
     *      for <a href="{@docRoot}guide/components/broadcasts#system-broadcasts">system
     *      broadcasts</a> or a {@link SecurityException} will be thrown. If {@link
     *      #RECEIVER_EXPORTED} is specified, a receiver may additionally specify {@link
     *      #RECEIVER_VISIBLE_TO_INSTANT_APPS}. For a complete list of system broadcast actions,
     *      see the BROADCAST_ACTIONS.TXT file in the Android SDK. If both {@link
     *      #RECEIVER_EXPORTED} and {@link #RECEIVER_NOT_EXPORTED} are specified, an {@link
     *      IllegalArgumentException} will be thrown.
     * @return The first sticky intent found that matches <var>filter</var>,
     *         or null if there are none.
     *
     * @see #registerReceiver(BroadcastReceiver, IntentFilter, int)
     * @see #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler)
     * @see #sendBroadcast
     * @see #unregisterReceiver
     */
    @Nullable
    public abstract Intent registerReceiver(BroadcastReceiver receiver,
            IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler, @RegisterReceiverFlags int flags);

    /**
     * Same as {@link #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler)}
     * but this receiver will receive broadcasts that are sent to all users. The receiver can
     * use {@link BroadcastReceiver#getSendingUser} to determine on which user the broadcast
     * was sent.
     *
     * @param receiver The BroadcastReceiver to handle the broadcast.
     * @param filter Selects the Intent broadcasts to be received.
     * @param broadcastPermission String naming a permissions that a
     *      broadcaster must hold in order to send an Intent to you. If {@code null},
     *      no permission is required.
     * @param scheduler Handler identifying the thread that will receive
     *      the Intent. If {@code null}, the main thread of the process will be used.
     *
     * @return The first sticky intent found that matches <var>filter</var>,
     *         or {@code null} if there are none.
     *
     * @see #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler)
     * @see #sendBroadcast
     * @see #unregisterReceiver
     * @hide
     */
    @Nullable
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    @SystemApi
    public Intent registerReceiverForAllUsers(@Nullable BroadcastReceiver receiver,
            @NonNull IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Same as {@link #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler, int)}
     * but this receiver will receive broadcasts that are sent to all users. The receiver can
     * use {@link BroadcastReceiver#getSendingUser} to determine on which user the broadcast
     * was sent.
     *
     * @param receiver The BroadcastReceiver to handle the broadcast.
     * @param filter Selects the Intent broadcasts to be received.
     * @param broadcastPermission String naming a permissions that a
     *      broadcaster must hold in order to send an Intent to you. If {@code null},
     *      no permission is required.
     * @param scheduler Handler identifying the thread that will receive
     *      the Intent. If {@code null}, the main thread of the process will be used.
     * @param flags Additional options for the receiver. For apps targeting {@link
     *      android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} either {@link #RECEIVER_EXPORTED} or
     *      {@link #RECEIVER_NOT_EXPORTED} must be specified if the receiver isn't being registered
     *      for <a href="{@docRoot}guide/components/broadcasts#system-broadcasts">system
     *      broadcasts</a> or a {@link SecurityException} will be thrown. If {@link
     *      #RECEIVER_EXPORTED} is specified, a receiver may additionally specify {@link
     *      #RECEIVER_VISIBLE_TO_INSTANT_APPS}. For a complete list of system broadcast actions,
     *      see the BROADCAST_ACTIONS.TXT file in the Android SDK. If both {@link
     *      #RECEIVER_EXPORTED} and {@link #RECEIVER_NOT_EXPORTED} are specified, an {@link
     *      IllegalArgumentException} will be thrown.
     *
     * @return The first sticky intent found that matches <var>filter</var>,
     *         or {@code null} if there are none.
     *
     * @see #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler, int)
     * @see #sendBroadcast
     * @see #unregisterReceiver
     * @hide
     */
    @SuppressLint("IntentBuilderName")
    @Nullable
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    @SystemApi
    public Intent registerReceiverForAllUsers(@Nullable BroadcastReceiver receiver,
            @NonNull IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler, @RegisterReceiverFlags int flags) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * @hide
     * Same as {@link #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler)
     * but for a specific user.  This receiver will receiver broadcasts that
     * are sent to the requested user.
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
     * @see #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler)
     * @see #sendBroadcast
     * @see #unregisterReceiver
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    @UnsupportedAppUsage
    public abstract Intent registerReceiverAsUser(BroadcastReceiver receiver,
            UserHandle user, IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler);

    /**
     * @hide
     * Same as {@link #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler, int)
     * but for a specific user.  This receiver will receiver broadcasts that
     * are sent to the requested user.
     *
     * @param receiver The BroadcastReceiver to handle the broadcast.
     * @param user UserHandle to send the intent to.
     * @param filter Selects the Intent broadcasts to be received.
     * @param broadcastPermission String naming a permissions that a
     *      broadcaster must hold in order to send an Intent to you.  If null,
     *      no permission is required.
     * @param scheduler Handler identifying the thread that will receive
     *      the Intent.  If null, the main thread of the process will be used.
     * @param flags Additional options for the receiver. For apps targeting {@link
     *      android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} either {@link #RECEIVER_EXPORTED} or
     *      {@link #RECEIVER_NOT_EXPORTED} must be specified if the receiver isn't being registered
     *      for <a href="{@docRoot}guide/components/broadcasts#system-broadcasts">system
     *      broadcasts</a> or a {@link SecurityException} will be thrown. If {@link
     *      #RECEIVER_EXPORTED} is specified, a receiver may additionally specify {@link
     *      #RECEIVER_VISIBLE_TO_INSTANT_APPS}. For a complete list of system broadcast actions,
     *      see the BROADCAST_ACTIONS.TXT file in the Android SDK. If both {@link
     *      #RECEIVER_EXPORTED} and {@link #RECEIVER_NOT_EXPORTED} are specified, an {@link
     *      IllegalArgumentException} will be thrown.
     *
     * @return The first sticky intent found that matches <var>filter</var>,
     *         or null if there are none.
     *
     * @see #registerReceiver(BroadcastReceiver, IntentFilter, String, Handler, int)
     * @see #sendBroadcast
     * @see #unregisterReceiver
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SuppressLint("IntentBuilderName")
    @Nullable
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    @UnsupportedAppUsage
    public abstract Intent registerReceiverAsUser(BroadcastReceiver receiver,
            UserHandle user, IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler, @RegisterReceiverFlags int flags);

    /**
     * Unregister a previously registered BroadcastReceiver.  <em>All</em>
     * filters that have been registered for this BroadcastReceiver will be
     * removed.
     *
     * @param receiver The BroadcastReceiver to unregister.
     *
     * @throws IllegalArgumentException if the {@code receiver} was not previously registered or
     *                                  already unregistered.
     * @see #registerReceiver
     */
    public abstract void unregisterReceiver(BroadcastReceiver receiver);

    /**
     * Request that a given application service be started.  The Intent
     * should either contain the complete class name of a specific service
     * implementation to start, or a specific package name to target.  If the
     * Intent is less specified, it logs a warning about this.  In this case any of the
     * multiple matching services may be used.  If this service
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
     * do not nest: no matter how many times you call startService(),
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
     * <div class="caution">
     * <p><strong>Note:</strong> Each call to startService()
     * results in significant work done by the system to manage service
     * lifecycle surrounding the processing of the intent, which can take
     * multiple milliseconds of CPU time. Due to this cost, startService()
     * should not be used for frequent intent delivery to a service, and only
     * for scheduling significant work. Use {@link #bindService bound services}
     * for high frequency calls.
     * </p>
     *
     * Beginning with SDK Version {@link android.os.Build.VERSION_CODES#O},
     * apps targeting SDK Version {@link android.os.Build.VERSION_CODES#O}
     * or higher are not allowed to start background services from the background.
     * See
     * <a href="/about/versions/oreo/background">
     * Background Execution Limits</a>
     * for more details.
     *
     * <p><strong>Note:</strong>
     * Beginning with SDK Version {@link android.os.Build.VERSION_CODES#S},
     * apps targeting SDK Version {@link android.os.Build.VERSION_CODES#S}
     * or higher are not allowed to start foreground services from the background.
     * See
     * <a href="/about/versions/12/behavior-changes-12">
     * Behavior changes: Apps targeting Android 12
     * </a>
     * for more details.
     * </div>
     *
     * @param service Identifies the service to be started.  The Intent must be
     *      fully explicit (supplying a component name).  Additional values
     *      may be included in the Intent extras to supply arguments along with
     *      this specific start call.
     *
     * @return If the service is being started or is already running, the
     * {@link ComponentName} of the actual service that was started is
     * returned; else if the service does not exist null is returned.
     *
     * @throws SecurityException If the caller does not have permission to access the service
     * or the service can not be found.
     * @throws IllegalStateException
     * Before Android {@link android.os.Build.VERSION_CODES#S},
     * if the application is in a state where the service
     * can not be started (such as not in the foreground in a state when services are allowed),
     * {@link IllegalStateException} was thrown.
     * @throws android.app.BackgroundServiceStartNotAllowedException
     * On Android {@link android.os.Build.VERSION_CODES#S} and later,
     * if the application is in a state where the service
     * can not be started (such as not in the foreground in a state when services are allowed),
     * {@link android.app.BackgroundServiceStartNotAllowedException} is thrown.
     * This exception extends {@link IllegalStateException}, so apps can
     * use {@code catch (IllegalStateException)} to catch both.
     *
     * @see #startForegroundService(Intent)
     * @see #stopService
     * @see #bindService
     */
    @Nullable
    public abstract ComponentName startService(Intent service);

    /**
     * Similar to {@link #startService(Intent)}, but with an implicit promise that the
     * Service will call {@link android.app.Service#startForeground(int, android.app.Notification)
     * startForeground(int, android.app.Notification)} once it begins running.  The service is given
     * an amount of time comparable to the ANR interval to do this, otherwise the system
     * will automatically crash the process, in which case an internal exception
     * {@code ForegroundServiceDidNotStartInTimeException} is logged on logcat on devices
     * running SDK Version {@link android.os.Build.VERSION_CODES#S} or later. On older Android
     * versions, an internal exception {@code RemoteServiceException} is logged instead, with
     * a corresponding message.
     *
     * <p>Unlike the ordinary {@link #startService(Intent)}, this method can be used
     * at any time, regardless of whether the app hosting the service is in a foreground
     * state.
     *
     * <div class="caution">
     * <p><strong>Note:</strong>
     * Beginning with SDK Version {@link android.os.Build.VERSION_CODES#S},
     * apps targeting SDK Version {@link android.os.Build.VERSION_CODES#S}
     * or higher are not allowed to start foreground services from the background.
     * See
     * <a href="/about/versions/12/behavior-changes-12">
     * Behavior changes: Apps targeting Android 12
     * </a>
     * for more details.
     * </div>
     *
     * @param service Identifies the service to be started.  The Intent must be
     *      fully explicit (supplying a component name).  Additional values
     *      may be included in the Intent extras to supply arguments along with
     *      this specific start call.
     *
     * @return If the service is being started or is already running, the
     * {@link ComponentName} of the actual service that was started is
     * returned; else if the service does not exist null is returned.
     *
     * @throws SecurityException If the caller does not have permission to access the service
     * or the service can not be found.
     *
     * @throws android.app.ForegroundServiceStartNotAllowedException
     * If the caller app's targeting API is
     * {@link android.os.Build.VERSION_CODES#S} or later, and the foreground service is restricted
     * from start due to background restriction.
     *
     * @see #stopService
     * @see android.app.Service#startForeground(int, android.app.Notification)
     */
    @Nullable
    public abstract ComponentName startForegroundService(Intent service);

    /**
     * @hide like {@link #startForegroundService(Intent)} but for a specific user.
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    public abstract ComponentName startForegroundServiceAsUser(Intent service, UserHandle user);

    /**
     * Request that a given application service be stopped.  If the service is
     * not running, nothing happens.  Otherwise it is stopped.  Note that calls
     * to startService() are not counted -- this stops the service no matter
     * how many times it was started.
     *
     * <p>If the service is running as a foreground service when it is
     * stopped, its associated notification will be removed.  To avoid this,
     * apps can use {@link android.app.Service#stopForeground(int)
     * stopForeground(STOP_FOREGROUND_DETACH)} to decouple the notification
     * from the service's lifecycle before stopping it.</p>
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
     *      name it is targeted to.
     *
     * @return If there is a service matching the given Intent that is already
     * running, then it is stopped and {@code true} is returned; else {@code false} is returned.
     *
     * @throws SecurityException If the caller does not have permission to access the service
     * or the service can not be found.
     * @throws IllegalStateException If the application is in a state where the service
     * can not be started (such as not in the foreground in a state when services are allowed).
     *
     * @see #startService
     */
    public abstract boolean stopService(Intent service);

    /**
     * @hide like {@link #startService(Intent)} but for a specific user.
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @Nullable
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    @UnsupportedAppUsage
    public abstract ComponentName startServiceAsUser(Intent service, UserHandle user);

    /**
     * @hide like {@link #stopService(Intent)} but for a specific user.
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    public abstract boolean stopServiceAsUser(Intent service, UserHandle user);

    /**
     * Connects to an application service, creating it if needed.  This defines
     * a dependency between your application and the service.  The given
     * <var>conn</var> will receive the service object when it is created and be
     * told if it dies and restarts.  The service will be considered required
     * by the system only for as long as the calling context exists.  For
     * example, if this Context is an Activity that is stopped, the service will
     * not be required to continue running until the Activity is resumed.
     *
     * <p>If the service does not support binding, it may return {@code null} from
     * its {@link android.app.Service#onBind(Intent) onBind()} method.  If it does, then
     * the ServiceConnection's
     * {@link ServiceConnection#onNullBinding(ComponentName) onNullBinding()} method
     * will be invoked instead of
     * {@link ServiceConnection#onServiceConnected(ComponentName, IBinder) onServiceConnected()}.
     *
     * <p class="note"><b>Note:</b> This method <em>cannot</em> be called from a
     * {@link BroadcastReceiver} component.  A pattern you can use to
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
     * <p>This method only accepts a int type flag, to pass in a long type flag, call
     * {@link #bindService(Intent, ServiceConnection, BindServiceFlags)} instead.</p>
     *
     * @param service Identifies the service to connect to.  The Intent must
     *      specify an explicit component name.
     * @param conn Receives information as the service is started and stopped.
     *      This must be a valid ServiceConnection object; it must not be null.
     * @param flags Operation options for the binding. Can be:
     *      <ul>
     *          <li>0
     *          <li>{@link #BIND_AUTO_CREATE}
     *          <li>{@link #BIND_DEBUG_UNBIND}
     *          <li>{@link #BIND_NOT_FOREGROUND}
     *          <li>{@link #BIND_ABOVE_CLIENT}
     *          <li>{@link #BIND_ALLOW_OOM_MANAGEMENT}
     *          <li>{@link #BIND_WAIVE_PRIORITY}
     *          <li>{@link #BIND_IMPORTANT}
     *          <li>{@link #BIND_ADJUST_WITH_ACTIVITY}
     *          <li>{@link #BIND_NOT_PERCEPTIBLE}
     *          <li>{@link #BIND_INCLUDE_CAPABILITIES}
     *      </ul>
     *
     * @return {@code true} if the system is in the process of bringing up a
     *      service that your client has permission to bind to; {@code false}
     *      if the system couldn't find the service or if your client doesn't
     *      have permission to bind to it. Regardless of the return value, you
     *      should later call {@link #unbindService} to release the connection.
     *
     * @throws SecurityException If the caller does not have permission to
     *      access the service or the service cannot be found. Call
     *      {@link #unbindService} to release the connection when this exception
     *      is thrown.
     *
     * @see #unbindService
     * @see #startService
     */
    public abstract boolean bindService(@RequiresPermission @NonNull Intent service,
            @NonNull ServiceConnection conn, int flags);

    /**
     * See {@link #bindService(Intent, ServiceConnection, int)}
     * Call {@link BindServiceFlags#of(long)} to obtain a BindServiceFlags object.
     */
    public boolean bindService(@RequiresPermission @NonNull Intent service,
            @NonNull ServiceConnection conn, @NonNull BindServiceFlags flags) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Same as {@link #bindService(Intent, ServiceConnection, int)
     * bindService(Intent, ServiceConnection, int)} with executor to control ServiceConnection
     * callbacks.
     *
     * <p>This method only accepts a 32 bits flag, to pass in a 64 bits flag, call
     * {@link #bindService(Intent, BindServiceFlags, Executor, ServiceConnection)} instead.</p>
     *
     * @param executor Callbacks on ServiceConnection will be called on executor. Must use same
     *      instance for the same instance of ServiceConnection.
     *
     * @return The result of the binding as described in
     *      {@link #bindService(Intent, ServiceConnection, int)
     *      bindService(Intent, ServiceConnection, int)}.
     */
    public boolean bindService(@RequiresPermission @NonNull Intent service,
            @BindServiceFlagsBits int flags, @NonNull @CallbackExecutor Executor executor,
            @NonNull ServiceConnection conn) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * See {@link #bindService(Intent, int, Executor, ServiceConnection)}
     * Call {@link BindServiceFlags#of(long)} to obtain a BindServiceFlags object.
     */
    public boolean bindService(@RequiresPermission @NonNull Intent service,
            @NonNull BindServiceFlags flags, @NonNull @CallbackExecutor Executor executor,
            @NonNull ServiceConnection conn) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Variation of {@link #bindService} that, in the specific case of isolated
     * services, allows the caller to generate multiple instances of a service
     * from a single component declaration.  In other words, you can use this to bind
     * to a service that has specified {@link android.R.attr#isolatedProcess} and, in
     * addition to the existing behavior of running in an isolated process, you can
     * also through the arguments here have the system bring up multiple concurrent
     * processes hosting their own instances of that service.  The <var>instanceName</var>
     * you provide here identifies the different instances, and you can use
     * {@link #updateServiceGroup(ServiceConnection, int, int)} to tell the system how it
     * should manage each of these instances.
     *
     * @param service Identifies the service to connect to.  The Intent must
     *      specify an explicit component name.
     * @param flags Operation options for the binding as per {@link #bindService}.
     * @param instanceName Unique identifier for the service instance.  Each unique
     *      name here will result in a different service instance being created.  Identifiers
     *      must only contain ASCII letters, digits, underscores, and periods.
     * @param executor Callbacks on ServiceConnection will be called on executor.
     *      Must use same instance for the same instance of ServiceConnection.
     * @param conn Receives information as the service is started and stopped.
     *      This must be a valid ServiceConnection object; it must not be null.
     *
     * @return Returns success of binding as per {@link #bindService}.
     *
     * @throws SecurityException If the caller does not have permission to access the service
     * @throws IllegalArgumentException If the instanceName is invalid.
     *
     * @see #bindService
     * @see #updateServiceGroup
     * @see android.R.attr#isolatedProcess
     */
    public boolean bindIsolatedService(@RequiresPermission @NonNull Intent service,
            int flags, @NonNull String instanceName,
            @NonNull @CallbackExecutor Executor executor, @NonNull ServiceConnection conn) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * See {@link #bindIsolatedService(Intent, int, String, Executor,ServiceConnection)}
     * Call {@link BindServiceFlags#of(long)} to obtain a BindServiceFlags object.
     */
    public boolean bindIsolatedService(@RequiresPermission @NonNull Intent service,
            @NonNull BindServiceFlags flags, @NonNull String instanceName,
            @NonNull @CallbackExecutor Executor executor, @NonNull ServiceConnection conn) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Binds to a service in the given {@code user} in the same manner as {@link #bindService}.
     *
     * <p>Requires that one of the following conditions are met:
     * <ul>
     *  <li>caller has {@code android.Manifest.permission.INTERACT_ACROSS_USERS_FULL}</li>
     *  <li>caller has {@code android.Manifest.permission.INTERACT_ACROSS_USERS} and is the same
     *      package as the {@code service} (determined by its component's package) and the Android
     *      version is at least {@link android.os.Build.VERSION_CODES#TIRAMISU}</li>
     *  <li>caller has {@code android.Manifest.permission.INTERACT_ACROSS_USERS} and is in same
     *      profile group as the given {@code user}</li>
     *  <li>caller has {@code android.Manifest.permission.INTERACT_ACROSS_PROFILES} and is in same
     *      profile group as the given {@code user} and is the same package as the {@code service}
     *      </li>
     * </ul>
     *
     * @param service Identifies the service to connect to.  The Intent must
     *      specify an explicit component name.
     * @param conn Receives information as the service is started and stopped.
     *      This must be a valid ServiceConnection object; it must not be null.
     * @param flags Operation options for the binding.  May be 0,
     *          {@link #BIND_AUTO_CREATE}, {@link #BIND_DEBUG_UNBIND},
     *          {@link #BIND_NOT_FOREGROUND}, {@link #BIND_ABOVE_CLIENT},
     *          {@link #BIND_ALLOW_OOM_MANAGEMENT}, {@link #BIND_WAIVE_PRIORITY}.
     *          {@link #BIND_IMPORTANT}, or
     *          {@link #BIND_ADJUST_WITH_ACTIVITY}.
     * @return {@code true} if the system is in the process of bringing up a
     *         service that your client has permission to bind to; {@code false}
     *         if the system couldn't find the service. You should call {@link #unbindService}
     *         to release the connection even if this method returned {@code false}.
     *
     * @throws SecurityException if the client does not have the required permission to bind.
     */
    @SuppressWarnings("unused")
    @RequiresPermission(anyOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            android.Manifest.permission.INTERACT_ACROSS_PROFILES
            }, conditional = true)
    public boolean bindServiceAsUser(
            @NonNull @RequiresPermission Intent service, @NonNull ServiceConnection conn, int flags,
            @NonNull UserHandle user) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * See {@link #bindServiceAsUser(Intent, ServiceConnection, int, UserHandle)}
     * Call {@link BindServiceFlags#of(long)} to obtain a BindServiceFlags object.
     */
    @SuppressWarnings("unused")
    @RequiresPermission(anyOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            android.Manifest.permission.INTERACT_ACROSS_PROFILES
    }, conditional = true)
    public boolean bindServiceAsUser(
            @NonNull @RequiresPermission Intent service, @NonNull ServiceConnection conn,
            @NonNull BindServiceFlags flags, @NonNull UserHandle user) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Same as {@link #bindServiceAsUser(Intent, ServiceConnection, int, UserHandle)}, but with an
     * explicit non-null Handler to run the ServiceConnection callbacks on.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            android.Manifest.permission.INTERACT_ACROSS_PROFILES
            }, conditional = true)
    @UnsupportedAppUsage(trackingBug = 136728678)
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            Handler handler, UserHandle user) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * See {@link #bindServiceAsUser(Intent, ServiceConnection, int, Handler, UserHandle)}
     * Call {@link BindServiceFlags#of(long)} to obtain a BindServiceFlags object.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.INTERACT_ACROSS_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            android.Manifest.permission.INTERACT_ACROSS_PROFILES
    }, conditional = true)
    @UnsupportedAppUsage(trackingBug = 136728678)
    public boolean bindServiceAsUser(@NonNull Intent service, @NonNull ServiceConnection conn,
            @NonNull BindServiceFlags flags, @NonNull Handler handler, @NonNull UserHandle user) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * For a service previously bound with {@link #bindService} or a related method, change
     * how the system manages that service's process in relation to other processes.  This
     * doesn't modify the original bind flags that were passed in when binding, but adjusts
     * how the process will be managed in some cases based on those flags.  Currently only
     * works on isolated processes (will be ignored for non-isolated processes).
     *
     * <p>Note that this call does not take immediate effect, but will be applied the next
     * time the impacted process is adjusted for some other reason.  Typically you would
     * call this before then calling a new {@link #bindIsolatedService} on the service
     * of interest, with that binding causing the process to be shuffled accordingly.</p>
     *
     * @param conn The connection interface previously supplied to bindService().  This
     *             parameter must not be null.
     * @param group A group to put this connection's process in.  Upon calling here, this
     *              will override any previous group that was set for that process.  The group
     *              tells the system about processes that are logically grouped together, so
     *              should be managed as one unit of importance (such as when being considered
     *              a recently used app).  All processes in the same app with the same group
     *              are considered to be related.  Supplying 0 reverts to the default behavior
     *              of not grouping.
     * @param importance Additional importance of the processes within a group.  Upon calling
     *                   here, this will override any previous importance that was set for that
     *                   process.  The most important process is 0, and higher values are
     *                   successively less important.  You can view this as describing how
     *                   to order the processes in an array, with the processes at the end of
     *                   the array being the least important.  This value has no meaning besides
     *                   indicating how processes should be ordered in that array one after the
     *                   other.  This provides a way to fine-tune the system's process killing,
     *                   guiding it to kill processes at the end of the array first.
     *
     * @see #bindIsolatedService
     */
    public void updateServiceGroup(@NonNull ServiceConnection conn, int group,
            int importance) {
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
    @StringDef(suffix = { "_SERVICE" }, value = {
            POWER_SERVICE,
            //@hide: POWER_STATS_SERVICE,
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
            HEALTHCONNECT_SERVICE,
            //@hide: COUNTRY_DETECTOR,
            SEARCH_SERVICE,
            SENSOR_SERVICE,
            SENSOR_PRIVACY_SERVICE,
            STORAGE_SERVICE,
            STORAGE_STATS_SERVICE,
            WALLPAPER_SERVICE,
            VIBRATOR_MANAGER_SERVICE,
            VIBRATOR_SERVICE,
            //@hide: STATUS_BAR_SERVICE,
            THREAD_NETWORK_SERVICE,
            CONNECTIVITY_SERVICE,
            PAC_PROXY_SERVICE,
            VCN_MANAGEMENT_SERVICE,
            //@hide: IP_MEMORY_STORE_SERVICE,
            IPSEC_SERVICE,
            VPN_MANAGEMENT_SERVICE,
            TEST_NETWORK_SERVICE,
            //@hide: UPDATE_LOCK_SERVICE,
            //@hide: NETWORKMANAGEMENT_SERVICE,
            NETWORK_STATS_SERVICE,
            //@hide: NETWORK_POLICY_SERVICE,
            WIFI_SERVICE,
            WIFI_AWARE_SERVICE,
            WIFI_P2P_SERVICE,
            WIFI_SCANNING_SERVICE,
            //@hide: LOWPAN_SERVICE,
            //@hide: WIFI_RTT_SERVICE,
            //@hide: ETHERNET_SERVICE,
            WIFI_RTT_RANGING_SERVICE,
            NSD_SERVICE,
            AUDIO_SERVICE,
            AUDIO_DEVICE_VOLUME_SERVICE,
            AUTH_SERVICE,
            FINGERPRINT_SERVICE,
            //@hide: FACE_SERVICE,
            BIOMETRIC_SERVICE,
            MEDIA_ROUTER_SERVICE,
            TELEPHONY_SERVICE,
            TELEPHONY_SUBSCRIPTION_SERVICE,
            CARRIER_CONFIG_SERVICE,
            EUICC_SERVICE,
            //@hide: MMS_SERVICE,
            TELECOM_SERVICE,
            CLIPBOARD_SERVICE,
            INPUT_METHOD_SERVICE,
            TEXT_SERVICES_MANAGER_SERVICE,
            TEXT_CLASSIFICATION_SERVICE,
            APPWIDGET_SERVICE,
            //@hide: VOICE_INTERACTION_MANAGER_SERVICE,
            //@hide: BACKUP_SERVICE,
            REBOOT_READINESS_SERVICE,
            ROLLBACK_SERVICE,
            DROPBOX_SERVICE,
            //@hide: DEVICE_IDLE_CONTROLLER,
            //@hide: POWER_WHITELIST_MANAGER,
            DEVICE_POLICY_SERVICE,
            UI_MODE_SERVICE,
            DOWNLOAD_SERVICE,
            NFC_SERVICE,
            BLUETOOTH_SERVICE,
            //@hide: SIP_SERVICE,
            USB_SERVICE,
            LAUNCHER_APPS_SERVICE,
            //@hide: SERIAL_SERVICE,
            //@hide: HDMI_CONTROL_SERVICE,
            INPUT_SERVICE,
            DISPLAY_SERVICE,
            //@hide COLOR_DISPLAY_SERVICE,
            USER_SERVICE,
            RESTRICTIONS_SERVICE,
            APP_OPS_SERVICE,
            ROLE_SERVICE,
            //@hide ROLE_CONTROLLER_SERVICE,
            CAMERA_SERVICE,
            //@hide: PLATFORM_COMPAT_SERVICE,
            //@hide: PLATFORM_COMPAT_NATIVE_SERVICE,
            PRINT_SERVICE,
            CONSUMER_IR_SERVICE,
            //@hide: TRUST_SERVICE,
            TV_INTERACTIVE_APP_SERVICE,
            TV_INPUT_SERVICE,
            //@hide: TV_TUNER_RESOURCE_MGR_SERVICE,
            //@hide: NETWORK_SCORE_SERVICE,
            USAGE_STATS_SERVICE,
            MEDIA_SESSION_SERVICE,
            MEDIA_COMMUNICATION_SERVICE,
            BATTERY_SERVICE,
            JOB_SCHEDULER_SERVICE,
            //@hide: PERSISTENT_DATA_BLOCK_SERVICE,
            //@hide: OEM_LOCK_SERVICE,
            MEDIA_PROJECTION_SERVICE,
            MIDI_SERVICE,
            RADIO_SERVICE,
            HARDWARE_PROPERTIES_SERVICE,
            //@hide: SOUND_TRIGGER_SERVICE,
            SHORTCUT_SERVICE,
            //@hide: CONTEXTHUB_SERVICE,
            SYSTEM_HEALTH_SERVICE,
            //@hide: INCIDENT_SERVICE,
            //@hide: INCIDENT_COMPANION_SERVICE,
            //@hide: STATS_COMPANION_SERVICE,
            COMPANION_DEVICE_SERVICE,
            VIRTUAL_DEVICE_SERVICE,
            CROSS_PROFILE_APPS_SERVICE,
            //@hide: SYSTEM_UPDATE_SERVICE,
            //@hide: TIME_DETECTOR_SERVICE,
            //@hide: TIME_ZONE_DETECTOR_SERVICE,
            PERMISSION_SERVICE,
            LIGHTS_SERVICE,
            LOCALE_SERVICE,
            //@hide: PEOPLE_SERVICE,
            //@hide: DEVICE_STATE_SERVICE,
            //@hide: SPEECH_RECOGNITION_SERVICE,
            UWB_SERVICE,
            MEDIA_METRICS_SERVICE,
            //@hide: ATTESTATION_VERIFICATION_SERVICE,
            //@hide: SAFETY_CENTER_SERVICE,
            DISPLAY_HASH_SERVICE,
            CREDENTIAL_SERVICE,
            DEVICE_LOCK_SERVICE,
            VIRTUALIZATION_SERVICE,
            GRAMMATICAL_INFLECTION_SERVICE,
            SECURITY_STATE_SERVICE,
           //@hide: ECM_ENHANCED_CONFIRMATION_SERVICE,
            CONTACT_KEYS_SERVICE,

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
     *  windows.  The returned object is a {@link android.view.WindowManager}. Must only be obtained
     *  from a visual context such as Activity or a Context created with
     *  {@link #createWindowContext(int, Bundle)}, which are adjusted to the configuration and
     *  visual bounds of an area on screen.
     *  <dt> {@link #LAYOUT_INFLATER_SERVICE} ("layout_inflater")
     *  <dd> A {@link android.view.LayoutInflater} for inflating layout resources
     *  in this context. Must only be obtained from a visual context such as Activity or a Context
     *  created with {@link #createWindowContext(int, Bundle)}, which are adjusted to the
     *  configuration and visual bounds of an area on screen.
     *  <dt> {@link #ACTIVITY_SERVICE} ("activity")
     *  <dd> A {@link android.app.ActivityManager} for interacting with the
     *  global activity state of the system.
     *  <dt> {@link #WALLPAPER_SERVICE} ("wallpaper")
     *  <dd> A {@link android.service.wallpaper.WallpaperService} for accessing wallpapers in this
     *  context. Must only be obtained from a visual context such as Activity or a Context created
     *  with {@link #createWindowContext(int, Bundle)}, which are adjusted to the configuration and
     *  visual bounds of an area on screen.
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
     *  <dt> {@link #VIBRATOR_MANAGER_SERVICE} ("vibrator_manager")
     *  <dd> A {@link android.os.VibratorManager} for accessing the device vibrators, interacting
     *  with individual ones and playing synchronized effects on multiple vibrators.
     *  <dt> {@link #VIBRATOR_SERVICE} ("vibrator")
     *  <dd> A {@link android.os.Vibrator} for interacting with the vibrator hardware.
     *  <dt> {@link #CONNECTIVITY_SERVICE} ("connectivity")
     *  <dd> A {@link android.net.ConnectivityManager ConnectivityManager} for
     *  handling management of network connections.
     *  <dt> {@link #IPSEC_SERVICE} ("ipsec")
     *  <dd> A {@link android.net.IpSecManager IpSecManager} for managing IPSec on
     *  sockets and networks.
     *  <dt> {@link #WIFI_SERVICE} ("wifi")
     *  <dd> A {@link android.net.wifi.WifiManager WifiManager} for management of Wi-Fi
     *  connectivity.  On releases before Android 7, it should only be obtained from an application
     *  context, and not from any other derived context to avoid memory leaks within the calling
     *  process.
     *  <dt> {@link #WIFI_AWARE_SERVICE} ("wifiaware")
     *  <dd> A {@link android.net.wifi.aware.WifiAwareManager WifiAwareManager} for management of
     * Wi-Fi Aware discovery and connectivity.
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
     * <dt> {@link #NETWORK_STATS_SERVICE} ("netstats")
     * <dd> A {@link android.app.usage.NetworkStatsManager NetworkStatsManager} for querying network
     * usage statistics.
     * <dt> {@link #HARDWARE_PROPERTIES_SERVICE} ("hardware_properties")
     * <dd> A {@link android.os.HardwarePropertiesManager} for accessing hardware properties.
     * <dt> {@link #DOMAIN_VERIFICATION_SERVICE} ("domain_verification")
     * <dd> A {@link android.content.pm.verify.domain.DomainVerificationManager} for accessing
     * web domain approval state.
     * <dt> {@link #DISPLAY_HASH_SERVICE} ("display_hash")
     * <dd> A {@link android.view.displayhash.DisplayHashManager} for management of display hashes.
     * </dl>
     *
     * <p>Note:  System services obtained via this API may be closely associated with
     * the Context in which they are obtained from.  In general, do not share the
     * service objects between various different contexts (Activities, Applications,
     * Services, Providers, etc.)
     *
     * <p>Note: Instant apps, for which {@link PackageManager#isInstantApp()} returns true,
     * don't have access to the following system services: {@link #DEVICE_POLICY_SERVICE},
     * {@link #FINGERPRINT_SERVICE}, {@link #KEYGUARD_SERVICE}, {@link #SHORTCUT_SERVICE},
     * {@link #USB_SERVICE}, {@link #WALLPAPER_SERVICE}, {@link #WIFI_P2P_SERVICE},
     * {@link #WIFI_SERVICE}, {@link #WIFI_AWARE_SERVICE}. For these services this method will
     * return <code>null</code>.  Generally, if you are running as an instant app you should always
     * check whether the result of this method is {@code null}.
     *
     * <p>Note: When implementing this method, keep in mind that new services can be added on newer
     * Android releases, so if you're looking for just the explicit names mentioned above, make sure
     * to return {@code null} when you don't recognize the name &mdash; if you throw a
     * {@link RuntimeException} exception instead, your app might break on new Android releases.
     *
     * @param name The name of the desired service.
     *
     * @return The service or {@code null} if the name does not exist.
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
     * @see #VIBRATOR_MANAGER_SERVICE
     * @see android.os.VibratorManager
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
     * @see #TELEPHONY_SUBSCRIPTION_SERVICE
     * @see android.telephony.SubscriptionManager
     * @see #CARRIER_CONFIG_SERVICE
     * @see android.telephony.CarrierConfigManager
     * @see #EUICC_SERVICE
     * @see android.telephony.euicc.EuiccManager
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
     * @see #NETWORK_STATS_SERVICE
     * @see android.app.usage.NetworkStatsManager
     * @see android.os.HardwarePropertiesManager
     * @see #HARDWARE_PROPERTIES_SERVICE
     * @see #DOMAIN_VERIFICATION_SERVICE
     * @see android.content.pm.verify.domain.DomainVerificationManager
     * @see #DISPLAY_HASH_SERVICE
     * @see android.view.displayhash.DisplayHashManager
     */
    public abstract @Nullable Object getSystemService(@ServiceName @NonNull String name);

    /**
     * Return the handle to a system-level service by class.
     * <p>
     * Currently available classes are:
     * {@link android.view.WindowManager}, {@link android.view.LayoutInflater},
     * {@link android.app.ActivityManager}, {@link android.os.PowerManager},
     * {@link android.app.AlarmManager}, {@link android.app.NotificationManager},
     * {@link android.app.KeyguardManager}, {@link android.location.LocationManager},
     * {@link android.app.SearchManager}, {@link android.os.Vibrator},
     * {@link android.net.ConnectivityManager},
     * {@link android.net.wifi.WifiManager},
     * {@link android.media.AudioManager}, {@link android.media.MediaRouter},
     * {@link android.telephony.TelephonyManager}, {@link android.telephony.SubscriptionManager},
     * {@link android.view.inputmethod.InputMethodManager},
     * {@link android.app.UiModeManager}, {@link android.app.DownloadManager},
     * {@link android.os.BatteryManager}, {@link android.app.job.JobScheduler},
     * {@link android.app.usage.NetworkStatsManager},
     * {@link android.content.pm.verify.domain.DomainVerificationManager},
     * {@link android.view.displayhash.DisplayHashManager}.
     * </p>
     *
     * <p>
     * Note: System services obtained via this API may be closely associated with
     * the Context in which they are obtained from.  In general, do not share the
     * service objects between various different contexts (Activities, Applications,
     * Services, Providers, etc.)
     * </p>
     *
     * <p>Note: Instant apps, for which {@link PackageManager#isInstantApp()} returns true,
     * don't have access to the following system services: {@link #DEVICE_POLICY_SERVICE},
     * {@link #FINGERPRINT_SERVICE}, {@link #KEYGUARD_SERVICE}, {@link #SHORTCUT_SERVICE},
     * {@link #USB_SERVICE}, {@link #WALLPAPER_SERVICE}, {@link #WIFI_P2P_SERVICE},
     * {@link #WIFI_SERVICE}, {@link #WIFI_AWARE_SERVICE}. For these services this method will
     * return {@code null}. Generally, if you are running as an instant app you should always
     * check whether the result of this method is {@code null}.
     * </p>
     *
     * @param serviceClass The class of the desired service.
     * @return The service or {@code null} if the class is not a supported system service. Note:
     * <b>never</b> throw a {@link RuntimeException} if the name is not supported.
     */
    @SuppressWarnings("unchecked")
    public final @Nullable <T> T getSystemService(@NonNull Class<T> serviceClass) {
        // Because subclasses may override getSystemService(String) we cannot
        // perform a lookup by class alone.  We must first map the class to its
        // service name then invoke the string-based method.
        String serviceName = getSystemServiceName(serviceClass);
        return serviceName != null ? (T)getSystemService(serviceName) : null;
    }

    /**
     * Gets the name of the system-level service that is represented by the specified class.
     *
     * @param serviceClass The class of the desired service.
     * @return The service name or null if the class is not a supported system service.
     */
    public abstract @Nullable String getSystemServiceName(@NonNull Class<?> serviceClass);

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.os.PowerManager} for controlling power management,
     * including "wake locks," which let you keep the device on while
     * you're running long tasks.
     */
    public static final String POWER_SERVICE = "power";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.os.PowerStatsService} for accessing power stats
     * service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    public static final String POWER_STATS_SERVICE = "powerstats";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.os.RecoverySystem} for accessing the recovery system
     * service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    public static final String RECOVERY_SERVICE = "recovery";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.os.SystemUpdateManager} for accessing the system update
     * manager service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    @SystemApi
    public static final String SYSTEM_UPDATE_SERVICE = "system_update";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.view.WindowManager} for accessing the system's window
     * manager.
     *
     * @see #getSystemService(String)
     * @see android.view.WindowManager
     */
    @UiContext
    public static final String WINDOW_SERVICE = "window";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.view.LayoutInflater} for inflating layout resources in this
     * context.
     *
     * @see #getSystemService(String)
     * @see android.view.LayoutInflater
     */
    @UiContext
    public static final String LAYOUT_INFLATER_SERVICE = "layout_inflater";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.accounts.AccountManager} for receiving intents at a
     * time of your choosing.
     *
     * @see #getSystemService(String)
     * @see android.accounts.AccountManager
     */
    public static final String ACCOUNT_SERVICE = "account";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.ActivityManager} for interacting with the global
     * system state.
     *
     * @see #getSystemService(String)
     * @see android.app.ActivityManager
     */
    public static final String ACTIVITY_SERVICE = "activity";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.ActivityTaskManager} for interacting with the global system state.
     *
     * @see #getSystemService(String)
     * @see android.app.ActivityTaskManager
     * @hide
     */
    public static final String ACTIVITY_TASK_SERVICE = "activity_task";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.UriGrantsManager} for interacting with the global system state.
     *
     * @see #getSystemService(String)
     * @see android.app.UriGrantsManager
     * @hide
     */
    public static final String URI_GRANTS_SERVICE = "uri_grants";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.AlarmManager} for receiving intents at a
     * time of your choosing.
     *
     * @see #getSystemService(String)
     * @see android.app.AlarmManager
     */
    public static final String ALARM_SERVICE = "alarm";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.NotificationManager} for informing the user of
     * background events.
     *
     * @see #getSystemService(String)
     * @see android.app.NotificationManager
     */
    public static final String NOTIFICATION_SERVICE = "notification";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.view.accessibility.AccessibilityManager} for giving the user
     * feedback for UI events through the registered event listeners.
     *
     * @see #getSystemService(String)
     * @see android.view.accessibility.AccessibilityManager
     */
    public static final String ACCESSIBILITY_SERVICE = "accessibility";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.view.accessibility.CaptioningManager} for obtaining
     * captioning properties and listening for changes in captioning
     * preferences.
     *
     * @see #getSystemService(String)
     * @see android.view.accessibility.CaptioningManager
     */
    public static final String CAPTIONING_SERVICE = "captioning";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.KeyguardManager} for controlling keyguard.
     *
     * @see #getSystemService(String)
     * @see android.app.KeyguardManager
     */
    public static final String KEYGUARD_SERVICE = "keyguard";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.location.LocationManager} for controlling location
     * updates.
     *
     * @see #getSystemService(String)
     * @see android.location.LocationManager
     */
    public static final String LOCATION_SERVICE = "location";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.location.CountryDetector} for detecting the country that
     * the user is in.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static final String COUNTRY_DETECTOR = "country_detector";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.app.SearchManager} for handling searches.
     *
     * <p>
     * {@link Configuration#UI_MODE_TYPE_WATCH} does not support
     * {@link android.app.SearchManager}.
     *
     * @see #getSystemService
     * @see android.app.SearchManager
     */
    public static final String SEARCH_SERVICE = "search";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.hardware.SensorManager} for accessing sensors.
     *
     * @see #getSystemService(String)
     * @see android.hardware.SensorManager
     */
    public static final String SENSOR_SERVICE = "sensor";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.hardware.SensorPrivacyManager} for accessing sensor privacy
     * functions.
     *
     * @see #getSystemService(String)
     * @see android.hardware.SensorPrivacyManager
     *
     * @hide
     */
    public static final String SENSOR_PRIVACY_SERVICE = "sensor_privacy";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.os.storage.StorageManager} for accessing system storage
     * functions.
     *
     * @see #getSystemService(String)
     * @see android.os.storage.StorageManager
     */
    public static final String STORAGE_SERVICE = "storage";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.app.usage.StorageStatsManager} for accessing system storage
     * statistics.
     *
     * @see #getSystemService(String)
     * @see android.app.usage.StorageStatsManager
     */
    public static final String STORAGE_STATS_SERVICE = "storagestats";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * com.android.server.WallpaperService for accessing wallpapers.
     *
     * @see #getSystemService(String)
     */
    @UiContext
    public static final String WALLPAPER_SERVICE = "wallpaper";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link android.os.VibratorManager}
     * for accessing the device vibrators, interacting with individual ones and playing synchronized
     * effects on multiple vibrators.
     *
     * @see #getSystemService(String)
     * @see android.os.VibratorManager
     */
    @SuppressLint("ServiceName")
    public static final String VIBRATOR_MANAGER_SERVICE = "vibrator_manager";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link android.os.Vibrator} for
     * interacting with the vibration hardware.
     *
     * @deprecated Use {@link android.os.VibratorManager} to retrieve the default system vibrator.
     * @see #getSystemService(String)
     * @see android.os.Vibrator
     */
    @Deprecated
    public static final String VIBRATOR_SERVICE = "vibrator";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.app.StatusBarManager} for interacting with the status bar and quick settings.
     *
     * @see #getSystemService(String)
     * @see android.app.StatusBarManager
     *
     */
    @SuppressLint("ServiceName")
    public static final String STATUS_BAR_SERVICE = "statusbar";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.net.ConnectivityManager} for handling management of
     * network connections.
     *
     * @see #getSystemService(String)
     * @see android.net.ConnectivityManager
     */
    public static final String CONNECTIVITY_SERVICE = "connectivity";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.net.PacProxyManager} for handling management of
     * pac proxy information.
     *
     * @see #getSystemService(String)
     * @see android.net.PacProxyManager
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final String PAC_PROXY_SERVICE = "pac_proxy";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link android.net.vcn.VcnManager}
     * for managing Virtual Carrier Networks
     *
     * @see #getSystemService(String)
     * @see android.net.vcn.VcnManager
     * @hide
     */
    public static final String VCN_MANAGEMENT_SERVICE = "vcn_management";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.net.INetd} for communicating with the network stack
     * @hide
     * @see #getSystemService(String)
     * @hide
     */
    @SystemApi
    public static final String NETD_SERVICE = "netd";

    /**
     * Use with {@link android.os.ServiceManager.getService()} to retrieve a
     * {@link INetworkStackConnector} IBinder for communicating with the network stack
     * @hide
     * @see NetworkStackClient
     */
    public static final String NETWORK_STACK_SERVICE = "network_stack";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link android.net.TetheringManager}
     * for managing tethering functions.
     * @hide
     * @see android.net.TetheringManager
     */
    @SystemApi
    public static final String TETHERING_SERVICE = "tethering";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.net.thread.ThreadNetworkManager}.
     *
     * <p>On devices without {@link PackageManager#FEATURE_THREAD_NETWORK} system feature
     * the {@link #getSystemService(String)} will return {@code null}.
     *
     * @see #getSystemService(String)
     * @see android.net.thread.ThreadNetworkManager
     * @hide
     */
    @FlaggedApi("com.android.net.thread.flags.thread_enabled")
    @SystemApi
    public static final String THREAD_NETWORK_SERVICE = "thread_network";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.net.IpSecManager} for encrypting Sockets or Networks with
     * IPSec.
     *
     * @see #getSystemService(String)
     */
    public static final String IPSEC_SERVICE = "ipsec";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link android.net.VpnManager} to
     * manage profiles for the platform built-in VPN.
     *
     * @see #getSystemService(String)
     */
    public static final String VPN_MANAGEMENT_SERVICE = "vpn_management";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.net.ConnectivityDiagnosticsManager} for performing network connectivity diagnostics
     * as well as receiving network connectivity information from the system.
     *
     * @see #getSystemService(String)
     * @see android.net.ConnectivityDiagnosticsManager
     */
    public static final String CONNECTIVITY_DIAGNOSTICS_SERVICE = "connectivity_diagnostics";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.net.TestNetworkManager} for building TUNs and limited-use Networks
     *
     * @see #getSystemService(String)
     * @hide
     */
    @TestApi @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final String TEST_NETWORK_SERVICE = "test_network";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.os.IUpdateLock} for managing runtime sequences that
     * must not be interrupted by headless OTA application or similar.
     *
     * @hide
     * @see #getSystemService(String)
     * @see android.os.UpdateLock
     */
    public static final String UPDATE_LOCK_SERVICE = "updatelock";

    /**
     * Constant for the internal network management service, not really a Context service.
     * @hide
     */
    public static final String NETWORKMANAGEMENT_SERVICE = "network_management";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link com.android.server.slice.SliceManagerService} for managing slices.
     * @hide
     * @see #getSystemService(String)
     */
    public static final String SLICE_SERVICE = "slice";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.app.usage.NetworkStatsManager} for querying network usage stats.
     *
     * @see #getSystemService(String)
     * @see android.app.usage.NetworkStatsManager
     */
    public static final String NETWORK_STATS_SERVICE = "netstats";
    /** {@hide} */
    public static final String NETWORK_POLICY_SERVICE = "netpolicy";
    /** {@hide} */
    public static final String NETWORK_WATCHLIST_SERVICE = "network_watchlist";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.net.wifi.WifiManager} for handling management of
     * Wi-Fi access.
     *
     * @see #getSystemService(String)
     * @see android.net.wifi.WifiManager
     */
    public static final String WIFI_SERVICE = "wifi";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.net.wifi.wificond.WifiNl80211Manager} for handling management of the
     * Wi-Fi nl802.11 daemon (wificond).
     *
     * @see #getSystemService(String)
     * @see android.net.wifi.wificond.WifiNl80211Manager
     * @hide
     */
    @SystemApi
    @SuppressLint("ServiceName")
    public static final String WIFI_NL80211_SERVICE = "wifinl80211";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.net.wifi.p2p.WifiP2pManager} for handling management of
     * Wi-Fi peer-to-peer connections.
     *
     * @see #getSystemService(String)
     * @see android.net.wifi.p2p.WifiP2pManager
     */
    public static final String WIFI_P2P_SERVICE = "wifip2p";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.net.wifi.aware.WifiAwareManager} for handling management of
     * Wi-Fi Aware.
     *
     * @see #getSystemService(String)
     * @see android.net.wifi.aware.WifiAwareManager
     */
    public static final String WIFI_AWARE_SERVICE = "wifiaware";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.net.wifi.WifiScanner} for scanning the wifi universe
     *
     * @see #getSystemService(String)
     * @see android.net.wifi.WifiScanner
     * @hide
     */
    @SystemApi
    public static final String WIFI_SCANNING_SERVICE = "wifiscanner";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.net.wifi.RttManager} for ranging devices with wifi
     *
     * @see #getSystemService(String)
     * @see android.net.wifi.RttManager
     * @hide
     */
    @SystemApi
    @Deprecated
    public static final String WIFI_RTT_SERVICE = "rttmanager";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.net.wifi.rtt.WifiRttManager} for ranging devices with wifi.
     *
     * @see #getSystemService(String)
     * @see android.net.wifi.rtt.WifiRttManager
     */
    public static final String WIFI_RTT_RANGING_SERVICE = "wifirtt";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.net.lowpan.LowpanManager} for handling management of
     * LoWPAN access.
     *
     * @see #getSystemService(String)
     * @see android.net.lowpan.LowpanManager
     *
     * @hide
     */
    public static final String LOWPAN_SERVICE = "lowpan";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link android.net.EthernetManager}
     * for handling management of Ethernet access.
     *
     * @see #getSystemService(String)
     * @see android.net.EthernetManager
     *
     * @hide
     */
    @SystemApi
    public static final String ETHERNET_SERVICE = "ethernet";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.net.nsd.NsdManager} for handling management of network service
     * discovery
     *
     * @see #getSystemService(String)
     * @see android.net.nsd.NsdManager
     */
    public static final String NSD_SERVICE = "servicediscovery";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.media.AudioManager} for handling management of volume,
     * ringer modes and audio routing.
     *
     * @see #getSystemService(String)
     * @see android.media.AudioManager
     */
    public static final String AUDIO_SERVICE = "audio";

    /**
     * @hide
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.media.AudioDeviceVolumeManager} for handling management of audio device
     * (e.g. speaker, USB headset) volume.
     *
     * @see #getSystemService(String)
     * @see android.media.AudioDeviceVolumeManager
     */
    @SystemApi
    public static final String AUDIO_DEVICE_VOLUME_SERVICE = "audio_device_volume";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.media.MediaTranscodingManager} for transcoding media.
     *
     * @hide
     * @see #getSystemService(String)
     * @see android.media.MediaTranscodingManager
     */
    @SystemApi
    public static final String MEDIA_TRANSCODING_SERVICE = "media_transcoding";

    /**
     * AuthService orchestrates biometric and PIN/pattern/password authentication.
     *
     * BiometricService was split into two services, AuthService and BiometricService, where
     * AuthService is the high level service that orchestrates all types of authentication, and
     * BiometricService is a lower layer responsible only for biometric authentication.
     *
     * Ideally we should have renamed BiometricManager to AuthManager, because it logically
     * corresponds to AuthService. However, because BiometricManager is a public API, we kept
     * the old name but changed the internal implementation to use AuthService.
     *
     * As of now, the AUTH_SERVICE constant is only used to identify the service in
     * SystemServiceRegistry and SELinux. To obtain the manager for AUTH_SERVICE, one should use
     * BIOMETRIC_SERVICE with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.biometrics.BiometricManager}
     *
     * Map of the two services and their managers:
     * [Service]            [Manager]
     * AuthService          BiometricManager
     * BiometricService     N/A
     *
     * @hide
     */
    public static final String AUTH_SERVICE = "auth";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.fingerprint.FingerprintManager} for handling management
     * of fingerprints.
     *
     * @see #getSystemService(String)
     * @see android.hardware.fingerprint.FingerprintManager
     */
    public static final String FINGERPRINT_SERVICE = "fingerprint";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.face.FaceManager} for handling management
     * of face authentication.
     *
     * @hide
     * @see #getSystemService
     * @see android.hardware.face.FaceManager
     */
    @FlaggedApi(android.hardware.biometrics.Flags.FLAG_FACE_BACKGROUND_AUTHENTICATION)
    @SystemApi
    public static final String FACE_SERVICE = "face";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.iris.IrisManager} for handling management
     * of iris authentication.
     *
     * @hide
     * @see #getSystemService
     * @see android.hardware.iris.IrisManager
     */
    public static final String IRIS_SERVICE = "iris";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.biometrics.BiometricManager} for handling
     * biometric and PIN/pattern/password authentication.
     *
     * @see #getSystemService
     * @see android.hardware.biometrics.BiometricManager
     */
    public static final String BIOMETRIC_SERVICE = "biometric";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.media.MediaCommunicationManager}
     * for managing {@link android.media.MediaSession2}.
     *
     * @see #getSystemService(String)
     * @see android.media.MediaCommunicationManager
     */
    public static final String MEDIA_COMMUNICATION_SERVICE = "media_communication";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.media.MediaRouter} for controlling and managing
     * routing of media.
     *
     * @see #getSystemService(String)
     * @see android.media.MediaRouter
     */
    public static final String MEDIA_ROUTER_SERVICE = "media_router";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.media.session.MediaSessionManager} for managing media Sessions.
     *
     * @see #getSystemService(String)
     * @see android.media.session.MediaSessionManager
     */
    public static final String MEDIA_SESSION_SERVICE = "media_session";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.telephony.TelephonyManager} for handling management the
     * telephony features of the device.
     *
     * @see #getSystemService(String)
     * @see android.telephony.TelephonyManager
     */
    public static final String TELEPHONY_SERVICE = "phone";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.telephony.SubscriptionManager} for handling management the
     * telephony subscriptions of the device.
     *
     * @see #getSystemService(String)
     * @see android.telephony.SubscriptionManager
     */
    public static final String TELEPHONY_SUBSCRIPTION_SERVICE = "telephony_subscription_service";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.telecom.TelecomManager} to manage telecom-related features
     * of the device.
     *
     * @see #getSystemService(String)
     * @see android.telecom.TelecomManager
     */
    public static final String TELECOM_SERVICE = "telecom";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.telephony.CarrierConfigManager} for reading carrier configuration values.
     *
     * @see #getSystemService(String)
     * @see android.telephony.CarrierConfigManager
     */
    public static final String CARRIER_CONFIG_SERVICE = "carrier_config";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.telephony.euicc.EuiccManager} to manage the device eUICC (embedded SIM).
     *
     * @see #getSystemService(String)
     * @see android.telephony.euicc.EuiccManager
     */
    public static final String EUICC_SERVICE = "euicc";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.telephony.euicc.EuiccCardManager} to access the device eUICC (embedded SIM).
     *
     * @see #getSystemService(String)
     * @see android.telephony.euicc.EuiccCardManager
     * @hide
     */
    @SystemApi
    public static final String EUICC_CARD_SERVICE = "euicc_card";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.telephony.MmsManager} to send/receive MMS messages.
     *
     * @see #getSystemService(String)
     * @see android.telephony.MmsManager
     * @hide
     */
    public static final String MMS_SERVICE = "mms";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.content.ClipboardManager} for accessing and modifying
     * the contents of the global clipboard.
     *
     * @see #getSystemService(String)
     * @see android.content.ClipboardManager
     */
    public static final String CLIPBOARD_SERVICE = "clipboard";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link TextClassificationManager} for text classification services.
     *
     * @see #getSystemService(String)
     * @see TextClassificationManager
     */
    public static final String TEXT_CLASSIFICATION_SERVICE = "textclassification";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.view.selectiontoolbar.SelectionToolbarManager} for selection toolbar service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    public static final String SELECTION_TOOLBAR_SERVICE = "selection_toolbar";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.graphics.fonts.FontManager} for font services.
     *
     * @see #getSystemService(String)
     * @see android.graphics.fonts.FontManager
     * @hide
     */
    @SystemApi
    @TestApi
    public static final String FONT_SERVICE = "font";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link com.android.server.attention.AttentionManagerService} for attention services.
     *
     * @see #getSystemService(String)
     * @see android.server.attention.AttentionManagerService
     * @hide
     */
    @TestApi
    public static final String ATTENTION_SERVICE = "attention";

    /**
     * Official published name of the (internal) rotation resolver service.
     *
     * // TODO(b/178151184): change it back to rotation resolver before S release.
     *
     * @see #getSystemService(String)
     * @hide
     */
    public static final String ROTATION_RESOLVER_SERVICE = "resolver";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.view.inputmethod.InputMethodManager} for accessing input
     * methods.
     *
     * @see #getSystemService(String)
     */
    public static final String INPUT_METHOD_SERVICE = "input_method";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.view.textservice.TextServicesManager} for accessing
     * text services.
     *
     * @see #getSystemService(String)
     */
    public static final String TEXT_SERVICES_MANAGER_SERVICE = "textservices";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.appwidget.AppWidgetManager} for accessing AppWidgets.
     *
     * @see #getSystemService(String)
     */
    public static final String APPWIDGET_SERVICE = "appwidget";

    /**
     * Official published name of the (internal) voice interaction manager service.
     *
     * @hide
     * @see #getSystemService(String)
     */
    public static final String VOICE_INTERACTION_MANAGER_SERVICE = "voiceinteraction";

    /**
     * Official published name of the (internal) autofill service.
     *
     * @hide
     * @see #getSystemService(String)
     */
    public static final String AUTOFILL_MANAGER_SERVICE = "autofill";

    /**
     * Official published name of the (internal) text to speech manager service.
     *
     * @hide
     * @see #getSystemService(String)
     */
    public static final String TEXT_TO_SPEECH_MANAGER_SERVICE = "texttospeech";

    /**
     * Official published name of the content capture service.
     *
     * @hide
     * @see #getSystemService(String)
     */
    @TestApi
    @SuppressLint("ServiceName")  // TODO: This should be renamed to CONTENT_CAPTURE_SERVICE
    public static final String CONTENT_CAPTURE_MANAGER_SERVICE = "content_capture";

    /**
     * Official published name of the translation service.
     *
     * @hide
     * @see #getSystemService(String)
     */
    @SystemApi
    @SuppressLint("ServiceName")
    public static final String TRANSLATION_MANAGER_SERVICE = "translation";

    /**
     * Official published name of the translation service which supports ui translation function.
     *
     * @hide
     * @see #getSystemService(String)
     */
    @SystemApi
    public static final String UI_TRANSLATION_SERVICE = "ui_translation";

    /**
     * Used for getting content selections and classifications for task snapshots.
     *
     * @hide
     * @see #getSystemService(String)
     */
    @SystemApi
    public static final String CONTENT_SUGGESTIONS_SERVICE = "content_suggestions";

    /**
     * Official published name of the app prediction service.
     *
     * <p><b>NOTE: </b> this service is optional; callers of
     * {@code Context.getSystemServiceName(APP_PREDICTION_SERVICE)} should check for {@code null}.
     *
     * @hide
     * @see #getSystemService(String)
     */
    @SystemApi
    public static final String APP_PREDICTION_SERVICE = "app_prediction";

    /**
     * Used for reading system-wide, overridable flags.
     *
     * @hide
     */
    public static final String FEATURE_FLAGS_SERVICE = "feature_flags";

    /**
     * Official published name of the search ui service.
     *
     * <p><b>NOTE: </b> this service is optional; callers of
     * {@code Context.getSystemServiceName(SEARCH_UI_SERVICE)} should check for {@code null}.
     *
     * @hide
     * @see #getSystemService(String)
     */
    @SystemApi
    public static final String SEARCH_UI_SERVICE = "search_ui";

    /**
     * Used for getting the smartspace service.
     *
     * <p><b>NOTE: </b> this service is optional; callers of
     * {@code Context.getSystemServiceName(SMARTSPACE_SERVICE)} should check for {@code null}.
     *
     * @hide
     * @see #getSystemService(String)
     */
    @SystemApi
    public static final String SMARTSPACE_SERVICE = "smartspace";

    /**
     * Used for getting the cloudsearch service.
     *
     * <p><b>NOTE: </b> this service is optional; callers of
     * {@code Context.getSystemServiceName(CLOUDSEARCH_SERVICE)} should check for {@code null}.
     *
     * @hide
     * @see #getSystemService(String)
     */
    @SystemApi
    public static final String CLOUDSEARCH_SERVICE = "cloudsearch";

    /**
     * Use with {@link #getSystemService(String)} to access the
     * {@link com.android.server.voiceinteraction.SoundTriggerService}.
     *
     * @hide
     * @see #getSystemService(String)
     */
    public static final String SOUND_TRIGGER_SERVICE = "soundtrigger";

    /**
     * Use with {@link #getSystemService(String)} to access the
     * {@link com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareService}.
     *
     * @hide
     * @see #getSystemService(String)
     */
    public static final String SOUND_TRIGGER_MIDDLEWARE_SERVICE = "soundtrigger_middleware";

    /**
     * Used for getting the wallpaper effects generation service.
     *
     * <p><b>NOTE: </b> this service is optional; callers of
     * {@code Context.getSystemServiceName(WALLPAPER_EFFECTS_GENERATION_SERVICE)} should check for
     * {@code null}.
     *
     * @hide
     * @see #getSystemService(String)
     */
    @SystemApi
    public static final String WALLPAPER_EFFECTS_GENERATION_SERVICE =
            "wallpaper_effects_generation";

    /**
     * Used to access {@link MusicRecognitionManagerService}.
     *
     * @hide
     * @see #getSystemService(String)
     */
    @SystemApi
    public static final String MUSIC_RECOGNITION_SERVICE = "music_recognition";

    /**
     * Official published name of the (internal) permission service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_SERVICE = "permission";

    /**
     * Official published name of the legacy (internal) permission service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    //@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final String LEGACY_PERMISSION_SERVICE = "legacy_permission";

    /**
     * Official published name of the (internal) permission controller service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROLLER_SERVICE = "permission_controller";

    /**
     * Official published name of the (internal) permission checker service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    public static final String PERMISSION_CHECKER_SERVICE = "permission_checker";

    /**
     * Official published name of the (internal) permission enforcer service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    public static final String PERMISSION_ENFORCER_SERVICE = "permission_enforcer";

    /**
     * Use with {@link #getSystemService(String) to retrieve an
     * {@link android.apphibernation.AppHibernationManager}} for
     * communicating with the hibernation service.
     * @hide
     *
     * @see #getSystemService(String)
     */
    @SystemApi
    public static final String APP_HIBERNATION_SERVICE = "app_hibernation";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.app.backup.IBackupManager IBackupManager} for communicating
     * with the backup mechanism.
     * @hide
     *
     * @see #getSystemService(String)
     */
    @SystemApi
    public static final String BACKUP_SERVICE = "backup";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.content.rollback.RollbackManager} for communicating
     * with the rollback manager
     *
     * @see #getSystemService(String)
     * @hide
     */
    @SystemApi
    public static final String ROLLBACK_SERVICE = "rollback";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.scheduling.RebootReadinessManager} for communicating
     * with the reboot readiness detector.
     *
     * @see #getSystemService(String)
     * @hide
     */
    @SystemApi
    public static final String REBOOT_READINESS_SERVICE = "reboot_readiness";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.os.DropBoxManager} instance for recording
     * diagnostic logs.
     * @see #getSystemService(String)
     */
    public static final String DROPBOX_SERVICE = "dropbox";

    /**
     * System service name for BackgroundInstallControlService. This service supervises the MBAs
     * on device and provides the related metadata of the MBAs.
     *
     * @hide
     */
    @SuppressLint("ServiceName")
    public static final String BACKGROUND_INSTALL_CONTROL_SERVICE = "background_install_control";

    /**
     * System service name for BinaryTransparencyService. This is used to retrieve measurements
     * pertaining to various pre-installed and system binaries on device for the purposes of
     * providing transparency to the user.
     *
     * @hide
     */
    @SuppressLint("ServiceName")
    public static final String BINARY_TRANSPARENCY_SERVICE = "transparency";

    /**
     * System service name for the DeviceIdleManager.
     * @see #getSystemService(String)
     * @hide
     */
    @TestApi
    @SuppressLint("ServiceName")  // TODO: This should be renamed to DEVICE_IDLE_SERVICE
    public static final String DEVICE_IDLE_CONTROLLER = "deviceidle";

    /**
     * System service name for the PowerWhitelistManager.
     *
     * @see #getSystemService(String)
     * @hide
     */
    @TestApi
    @Deprecated
    @SuppressLint("ServiceName")
    public static final String POWER_WHITELIST_MANAGER = "power_whitelist";

    /**
     * System service name for the PowerExemptionManager.
     *
     * @see #getSystemService(String)
     * @hide
     */
    @TestApi
    public static final String POWER_EXEMPTION_SERVICE = "power_exemption";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.admin.DevicePolicyManager} for working with global
     * device policy management.
     *
     * @see #getSystemService(String)
     */
    public static final String DEVICE_POLICY_SERVICE = "device_policy";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.UiModeManager} for controlling UI modes.
     *
     * @see #getSystemService(String)
     */
    public static final String UI_MODE_SERVICE = "uimode";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.DownloadManager} for requesting HTTP downloads.
     *
     * @see #getSystemService(String)
     */
    public static final String DOWNLOAD_SERVICE = "download";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.os.BatteryManager} for managing battery state.
     *
     * @see #getSystemService(String)
     */
    public static final String BATTERY_SERVICE = "batterymanager";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.nfc.NfcManager} for using NFC.
     *
     * @see #getSystemService(String)
     */
    public static final String NFC_SERVICE = "nfc";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.bluetooth.BluetoothManager} for using Bluetooth.
     *
     * @see #getSystemService(String)
     */
    public static final String BLUETOOTH_SERVICE = "bluetooth";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.net.sip.SipManager} for accessing the SIP related service.
     *
     * @see #getSystemService(String)
     */
    /** @hide */
    public static final String SIP_SERVICE = "sip";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.hardware.usb.UsbManager} for access to USB devices (as a USB host)
     * and for controlling this device's behavior as a USB device.
     *
     * @see #getSystemService(String)
     * @see android.hardware.usb.UsbManager
     */
    public static final String USB_SERVICE = "usb";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * Use with {@link #getSystemService} to retrieve a {@link
     * android.debug.AdbManager} for access to ADB debug functions.
     *
     * @see #getSystemService(String)
     * @see android.debug.AdbManager
     *
     * @hide
     */
    public static final String ADB_SERVICE = "adb";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.hardware.SerialManager} for access to serial ports.
     *
     * @see #getSystemService(String)
     * @see android.hardware.SerialManager
     *
     * @hide
     */
    public static final String SERIAL_SERVICE = "serial";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.hdmi.HdmiControlManager} for controlling and managing
     * HDMI-CEC protocol.
     *
     * @see #getSystemService(String)
     * @see android.hardware.hdmi.HdmiControlManager
     * @hide
     */
    @SystemApi
    public static final String HDMI_CONTROL_SERVICE = "hdmi_control";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.input.InputManager} for interacting with input devices.
     *
     * @see #getSystemService(String)
     * @see android.hardware.input.InputManager
     */
    public static final String INPUT_SERVICE = "input";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.display.DisplayManager} for interacting with display devices.
     *
     * @see #getSystemService(String)
     * @see android.hardware.display.DisplayManager
     */
    public static final String DISPLAY_SERVICE = "display";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.display.ColorDisplayManager} for controlling color transforms.
     *
     * @see #getSystemService(String)
     * @see android.hardware.display.ColorDisplayManager
     * @hide
     */
    public static final String COLOR_DISPLAY_SERVICE = "color_display";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.os.UserManager} for managing users on devices that support multiple users.
     *
     * @see #getSystemService(String)
     * @see android.os.UserManager
     */
    public static final String USER_SERVICE = "user";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.content.pm.LauncherApps} for querying and monitoring launchable apps across
     * profiles of a user.
     *
     * @see #getSystemService(String)
     * @see android.content.pm.LauncherApps
     */
    public static final String LAUNCHER_APPS_SERVICE = "launcherapps";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.content.RestrictionsManager} for retrieving application restrictions
     * and requesting permissions for restricted operations.
     * @see #getSystemService(String)
     * @see android.content.RestrictionsManager
     */
    public static final String RESTRICTIONS_SERVICE = "restrictions";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.AppOpsManager} for tracking application operations
     * on the device.
     *
     * @see #getSystemService(String)
     * @see android.app.AppOpsManager
     */
    public static final String APP_OPS_SERVICE = "appops";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link android.app.role.RoleManager}
     * for managing roles.
     *
     * @see #getSystemService(String)
     * @see android.app.role.RoleManager
     */
    public static final String ROLE_SERVICE = "role";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.camera2.CameraManager} for interacting with
     * camera devices.
     *
     * @see #getSystemService(String)
     * @see android.hardware.camera2.CameraManager
     */
    public static final String CAMERA_SERVICE = "camera";

    /**
     * {@link android.print.PrintManager} for printing and managing
     * printers and print tasks.
     *
     * @see #getSystemService(String)
     * @see android.print.PrintManager
     */
    public static final String PRINT_SERVICE = "print";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.companion.CompanionDeviceManager} for managing companion devices
     *
     * @see #getSystemService(String)
     * @see android.companion.CompanionDeviceManager
     */
    public static final String COMPANION_DEVICE_SERVICE = "companiondevice";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.companion.virtual.VirtualDeviceManager} for managing virtual devices.
     *
     * On devices without {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP}
     * system feature the {@link #getSystemService(String)} will return {@code null}.
     *
     * @see #getSystemService(String)
     * @see android.companion.virtual.VirtualDeviceManager
     */
    @SuppressLint("ServiceName")
    public static final String VIRTUAL_DEVICE_SERVICE = "virtualdevice";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.ConsumerIrManager} for transmitting infrared
     * signals from the device.
     *
     * @see #getSystemService(String)
     * @see android.hardware.ConsumerIrManager
     */
    public static final String CONSUMER_IR_SERVICE = "consumer_ir";

    /**
     * {@link android.app.trust.TrustManager} for managing trust agents.
     * @see #getSystemService(String)
     * @see android.app.trust.TrustManager
     * @hide
     */
    public static final String TRUST_SERVICE = "trust";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.media.tv.interactive.TvInteractiveAppManager} for interacting with TV
     * interactive applications on the device.
     *
     * @see #getSystemService(String)
     * @see android.media.tv.interactive.TvInteractiveAppManager
     */
    public static final String TV_INTERACTIVE_APP_SERVICE = "tv_interactive_app";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.media.tv.TvInputManager} for interacting with TV inputs
     * on the device.
     *
     * @see #getSystemService(String)
     * @see android.media.tv.TvInputManager
     */
    public static final String TV_INPUT_SERVICE = "tv_input";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.media.tv.ad.TvAdManager} for interacting with TV client-side advertisement
     * services on the device.
     *
     * @see #getSystemService(String)
     * @see android.media.tv.ad.TvAdManager
     */
    @FlaggedApi(android.media.tv.flags.Flags.FLAG_ENABLE_AD_SERVICE_FW)
    public static final String TV_AD_SERVICE = "tv_ad";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.media.tv.TunerResourceManager} for interacting with TV
     * tuner resources on the device.
     *
     * @see #getSystemService(String)
     * @see android.media.tv.TunerResourceManager
     * @hide
     */
    public static final String TV_TUNER_RESOURCE_MGR_SERVICE = "tv_tuner_resource_mgr";

    /**
     * {@link android.net.NetworkScoreManager} for managing network scoring.
     * @see #getSystemService(String)
     * @see android.net.NetworkScoreManager
     * @deprecated see
     * <a href="{@docRoot}guide/topics/connectivity/wifi-suggest">Wi-Fi Suggestion API</a>
     * for alternative API to propose WiFi networks.
     * @hide
     */
    @SystemApi
    @Deprecated
    public static final String NETWORK_SCORE_SERVICE = "network_score";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.app.usage.UsageStatsManager} for querying device usage stats.
     *
     * @see #getSystemService(String)
     * @see android.app.usage.UsageStatsManager
     */
    public static final String USAGE_STATS_SERVICE = "usagestats";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.app.job.JobScheduler} instance for managing occasional
     * background tasks.
     * @see #getSystemService(String)
     * @see android.app.job.JobScheduler
     */
    public static final String JOB_SCHEDULER_SERVICE = "jobscheduler";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.tare.EconomyManager} instance for understanding economic standing.
     * @see #getSystemService(String)
     * @hide
     * @see android.app.tare.EconomyManager
     */
    public static final String RESOURCE_ECONOMY_SERVICE = "tare";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.service.persistentdata.PersistentDataBlockManager} instance
     * for interacting with a storage device that lives across factory resets.
     *
     * @see #getSystemService(String)
     * @see android.service.persistentdata.PersistentDataBlockManager
     * @hide
     */
    @SystemApi
    public static final String PERSISTENT_DATA_BLOCK_SERVICE = "persistent_data_block";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.service.oemlock.OemLockManager} instance for managing the OEM lock.
     *
     * @see #getSystemService(String)
     * @see android.service.oemlock.OemLockManager
     * @hide
     */
    @SystemApi
    public static final String OEM_LOCK_SERVICE = "oem_lock";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.media.projection.MediaProjectionManager} instance for managing
     * media projection sessions.
     * @see #getSystemService(String)
     * @see android.media.projection.MediaProjectionManager
     */
    public static final String MEDIA_PROJECTION_SERVICE = "media_projection";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.media.midi.MidiManager} for accessing the MIDI service.
     *
     * @see #getSystemService(String)
     */
    public static final String MIDI_SERVICE = "midi";


    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.radio.RadioManager} for accessing the broadcast radio service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    public static final String RADIO_SERVICE = "broadcastradio";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.os.HardwarePropertiesManager} for accessing the hardware properties service.
     *
     * @see #getSystemService(String)
     */
    public static final String HARDWARE_PROPERTIES_SERVICE = "hardware_properties";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.os.ThermalService} for accessing the thermal service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    public static final String THERMAL_SERVICE = "thermalservice";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.os.PerformanceHintManager} for accessing the performance hinting service.
     *
     * @see #getSystemService(String)
     */
    public static final String PERFORMANCE_HINT_SERVICE = "performance_hint";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.content.pm.ShortcutManager} for accessing the launcher shortcut service.
     *
     * @see #getSystemService(String)
     * @see android.content.pm.ShortcutManager
     */
    public static final String SHORTCUT_SERVICE = "shortcut";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.hardware.location.ContextHubManager} for accessing context hubs.
     *
     * @see #getSystemService(String)
     * @see android.hardware.location.ContextHubManager
     *
     * @hide
     */
    @SystemApi
    public static final String CONTEXTHUB_SERVICE = "contexthub";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.os.health.SystemHealthManager} for accessing system health (battery, power,
     * memory, etc) metrics.
     *
     * @see #getSystemService(String)
     */
    public static final String SYSTEM_HEALTH_SERVICE = "systemhealth";

    /**
     * Gatekeeper Service.
     * @hide
     */
    public static final String GATEKEEPER_SERVICE = "android.service.gatekeeper.IGateKeeperService";

    /**
     * Service defining the policy for access to device identifiers.
     * @hide
     */
    public static final String DEVICE_IDENTIFIERS_SERVICE = "device_identifiers";

    /**
     * Service to report a system health "incident"
     * @hide
     */
    public static final String INCIDENT_SERVICE = "incident";

    /**
     * Service to assist incidentd and dumpstated in reporting status to the user
     * and in confirming authorization to take an incident report or bugreport
     * @hide
     */
    public static final String INCIDENT_COMPANION_SERVICE = "incidentcompanion";

    /**
     * Service to assist {@link android.app.StatsManager} that lives in system server.
     * @hide
     */
    public static final String STATS_MANAGER_SERVICE = "statsmanager";

    /**
     * Service to assist statsd in obtaining general stats.
     * @hide
     */
    public static final String STATS_COMPANION_SERVICE = "statscompanion";

    /**
     * Service to assist statsd in logging atoms from bootstrap atoms.
     * @hide
     */
    public static final String STATS_BOOTSTRAP_ATOM_SERVICE = "statsbootstrap";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an {@link android.app.StatsManager}.
     * @hide
     */
    @SystemApi
    public static final String STATS_MANAGER = "stats";

    /**
     * Use with {@link android.os.ServiceManager.getService()} to retrieve a
     * {@link IPlatformCompat} IBinder for communicating with the platform compat service.
     * @hide
     */
    public static final String PLATFORM_COMPAT_SERVICE = "platform_compat";

    /**
     * Use with {@link android.os.ServiceManager.getService()} to retrieve a
     * {@link IPlatformCompatNative} IBinder for native code communicating with the platform compat
     * service.
     * @hide
     */
    public static final String PLATFORM_COMPAT_NATIVE_SERVICE = "platform_compat_native";

    /**
     * Service to capture a bugreport.
     * @see #getSystemService(String)
     * @see android.os.BugreportManager
     */
    public static final String BUGREPORT_SERVICE = "bugreport";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.content.om.OverlayManager} for managing overlay packages.
     *
     * @see #getSystemService(String)
     * @see android.content.om.OverlayManager
     */
    public static final String OVERLAY_SERVICE = "overlay";

    /**
     * Use with {@link #getSystemService(String)} to manage resources.
     *
     * @see #getSystemService(String)
     * @see com.android.server.resources.ResourcesManagerService
     * @hide
     */
    public static final String RESOURCES_SERVICE = "resources";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {android.os.IIdmap2} for managing idmap files (used by overlay
     * packages).
     *
     * @see #getSystemService(String)
     * @hide
     */
    public static final String IDMAP_SERVICE = "idmap";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link VrManager} for accessing the VR service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    @SystemApi
    public static final String VR_SERVICE = "vrmanager";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.content.pm.CrossProfileApps} for cross profile operations.
     *
     * @see #getSystemService(String)
     */
    public static final String CROSS_PROFILE_APPS_SERVICE = "crossprofileapps";

    /**
     * Use with {@link #getSystemService} to retrieve a
     * {@link android.se.omapi.ISecureElementService}
     * for accessing the SecureElementService.
     *
     * @hide
     */
    @SystemApi
    public static final String SECURE_ELEMENT_SERVICE = "secure_element";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.timedetector.TimeDetector}.
     * @hide
     *
     * @see #getSystemService(String)
     */
    public static final String TIME_DETECTOR_SERVICE = "time_detector";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.timezonedetector.TimeZoneDetector}.
     * @hide
     *
     * @see #getSystemService(String)
     */
    public static final String TIME_ZONE_DETECTOR_SERVICE = "time_zone_detector";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link TimeManager}.
     * @hide
     *
     * @see #getSystemService(String)
     */
    @SystemApi
    @SuppressLint("ServiceName")
    public static final String TIME_MANAGER_SERVICE = "time_manager";

    /**
     * Binder service name for {@link AppBindingService}.
     * @hide
     */
    public static final String APP_BINDING_SERVICE = "app_binding";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.telephony.ims.ImsManager}.
     */
    public static final String TELEPHONY_IMS_SERVICE = "telephony_ims";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.os.SystemConfigManager}.
     * @hide
     */
    @SystemApi
    public static final String SYSTEM_CONFIG_SERVICE = "system_config";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.telephony.ims.RcsMessageManager}.
     * @hide
     */
    public static final String TELEPHONY_RCS_MESSAGE_SERVICE = "ircsmessage";

     /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.os.image.DynamicSystemManager}.
     * @hide
     */
    public static final String DYNAMIC_SYSTEM_SERVICE = "dynamic_system";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.app.blob.BlobStoreManager} for contributing and accessing data blobs
     * from the blob store maintained by the system.
     *
     * @see #getSystemService(String)
     * @see android.app.blob.BlobStoreManager
     */
    public static final String BLOB_STORE_SERVICE = "blob_store";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link TelephonyRegistryManager}.
     * @hide
     */
    public static final String TELEPHONY_REGISTRY_SERVICE = "telephony_registry";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.os.BatteryStatsManager}.
     * @hide
     */
    @SystemApi
    @SuppressLint("ServiceName")
    public static final String BATTERY_STATS_SERVICE = "batterystats";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.app.appsearch.AppSearchManager} for
     * indexing and querying app data managed by the system.
     *
     * @see #getSystemService(String)
     */
    public static final String APP_SEARCH_SERVICE = "app_search";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.content.integrity.AppIntegrityManager}.
     * @hide
     */
    @SystemApi
    public static final String APP_INTEGRITY_SERVICE = "app_integrity";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.content.pm.DataLoaderManager}.
     * @hide
     */
    public static final String DATA_LOADER_MANAGER_SERVICE = "dataloader_manager";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.os.incremental.IncrementalManager}.
     * @hide
     */
    public static final String INCREMENTAL_SERVICE = "incremental";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.security.attestationverification.AttestationVerificationManager}.
     * @see #getSystemService(String)
     * @see android.security.attestationverification.AttestationVerificationManager
     * @hide
     */
    public static final String ATTESTATION_VERIFICATION_SERVICE = "attestation_verification";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.security.FileIntegrityManager}.
     * @see #getSystemService(String)
     * @see android.security.FileIntegrityManager
     */
    public static final String FILE_INTEGRITY_SERVICE = "file_integrity";

    /**
     * Binder service for remote key provisioning.
     *
     * @see android.frameworks.rkp.IRemoteProvisioning
     * @hide
     */
    public static final String REMOTE_PROVISIONING_SERVICE = "remote_provisioning";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.hardware.lights.LightsManager} for controlling device lights.
     *
     * @see #getSystemService(String)
     * @hide
     */
    public static final String LIGHTS_SERVICE = "lights";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.uwb.UwbManager}.
     *
     * @see #getSystemService(String)
     * @hide
     */
    @SystemApi
    public static final String UWB_SERVICE = "uwb";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.DreamManager} for controlling Dream states.
     *
     * @see #getSystemService(String)

     * @hide
     */
    @TestApi
    public static final String DREAM_SERVICE = "dream";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.telephony.SmsManager} for accessing Sms functionality.
     *
     * @see #getSystemService(String)

     * @hide
     */
    public static final String SMS_SERVICE = "sms";

    /**
     * Use with {@link #getSystemService(String)} to access a {@link PeopleManager} to interact
     * with your published conversations.
     *
     * @see #getSystemService(String)
     */
    public static final String PEOPLE_SERVICE = "people";

    /**
     * Use with {@link #getSystemService(String)} to access device state service.
     *
     * @see #getSystemService(String)
     * @hide
     */
    public static final String DEVICE_STATE_SERVICE = "device_state";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.media.metrics.MediaMetricsManager} for interacting with media metrics
     * on the device.
     *
     * @see #getSystemService(String)
     * @see android.media.metrics.MediaMetricsManager
     */
    public static final String MEDIA_METRICS_SERVICE = "media_metrics";

    /**
     * Use with {@link #getSystemService(String)} to access system speech recognition service.
     *
     * @see #getSystemService(String)
     * @hide
    */
    public static final String SPEECH_RECOGNITION_SERVICE = "speech_recognition";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link GameManager}.
     *
     * @see #getSystemService(String)
     */
    public static final String GAME_SERVICE = "game";

    /**
     * Use with {@link #getSystemService(String)} to access
     * {@link android.content.pm.verify.domain.DomainVerificationManager} to retrieve approval and
     * user state for declared web domains.
     *
     * @see #getSystemService(String)
     * @see android.content.pm.verify.domain.DomainVerificationManager
     */
    public static final String DOMAIN_VERIFICATION_SERVICE = "domain_verification";

    /**
     * Use with {@link #getSystemService(String)} to access
     * {@link android.view.displayhash.DisplayHashManager} to handle display hashes.
     *
     * @see #getSystemService(String)
     */
    public static final String DISPLAY_HASH_SERVICE = "display_hash";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.LocaleManager}.
     *
     * @see #getSystemService(String)
     */
    public static final String LOCALE_SERVICE = "locale";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.safetycenter.SafetyCenterManager} instance for interacting with the safety center.
     *
     * @see #getSystemService(String)
     * @see android.safetycenter.SafetyCenterManager
     * @hide
     */
    @SystemApi
    public static final String SAFETY_CENTER_SERVICE = "safety_center";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.nearby.NearbyManager} to discover nearby devices.
     *
     * @see #getSystemService(String)
     * @see android.nearby.NearbyManager
     * @hide
     */
    @SystemApi
    public static final String NEARBY_SERVICE = "nearby";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.remoteauth.RemoteAuthManager} to discover,
     * register and authenticate via remote authenticator  devices.
     *
     * @see #getSystemService(String)
     * @see android.remoteauth.RemoteAuthManager
     * @hide
     */
    public static final String REMOTE_AUTH_SERVICE = "remote_auth";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.ambientcontext.AmbientContextManager}.
     *
     * @see #getSystemService(String)
     * @see AmbientContextManager
     * @hide
     */
    @SystemApi
    public static final String AMBIENT_CONTEXT_SERVICE = "ambient_context";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.app.wearable.WearableSensingManager}.
     *
     * @see #getSystemService(String)
     * @see WearableSensingManager
     * @hide
     */
    @SystemApi
    public static final String WEARABLE_SENSING_SERVICE = "wearable_sensing";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.health.connect.HealthConnectManager}.
     *
     * @see #getSystemService(String)
     * @see android.health.connect.HealthConnectManager
     */
    public static final String HEALTHCONNECT_SERVICE = "healthconnect";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.credentials.CredentialManager} to authenticate a user to your app.
     *
     * @see #getSystemService(String)
     * @see CredentialManager
     */
    public static final String CREDENTIAL_SERVICE = "credential";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.devicelock.DeviceLockManager}.
     *
     * @see #getSystemService(String)
     */
    public static final String DEVICE_LOCK_SERVICE = "device_lock";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.system.virtualmachine.VirtualMachineManager}.
     *
     * <p>On devices without {@link PackageManager#FEATURE_VIRTUALIZATION_FRAMEWORK} system feature
     * the {@link #getSystemService(String)} will return {@code null}.
     *
     * @see #getSystemService(String)
     * @see android.system.virtualmachine.VirtualMachineManager
     * @hide
     */
    @SystemApi
    public static final String VIRTUALIZATION_SERVICE = "virtualization";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link GrammaticalInflectionManager}.
     *
     * @see #getSystemService(String)
     */
    public static final String GRAMMATICAL_INFLECTION_SERVICE = "grammatical_inflection";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.telephony.satellite.SatelliteManager} for accessing satellite functionality.
     *
     * @see #getSystemService(String)
     * @see android.telephony.satellite.SatelliteManager
     * @hide
     */
    public static final String SATELLITE_SERVICE = "satellite";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.net.wifi.sharedconnectivity.app.SharedConnectivityManager} for accessing
     * shared connectivity services.
     *
     * @see #getSystemService(String)
     * @see android.net.wifi.sharedconnectivity.app.SharedConnectivityManager
     * @hide
     */
    @SystemApi
    public static final String SHARED_CONNECTIVITY_SERVICE = "shared_connectivity";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.os.SecurityStateManager} for accessing the security state manager service.
     *
     * @see #getSystemService(String)
     * @see android.os.SecurityStateManager
     */
    @FlaggedApi(Flags.FLAG_SECURITY_STATE_SERVICE)
    public static final String SECURITY_STATE_SERVICE = "security_state";

    /**
     * Use with {@link #getSystemService(String)} to retrieve an
     * {@link android.app.ecm.EnhancedConfirmationManager}.
     *
     * @see #getSystemService(String)
     * @see android.app.ecm.EnhancedConfirmationManager
     * @hide
     */
    @FlaggedApi(android.permission.flags.Flags.FLAG_ENHANCED_CONFIRMATION_MODE_APIS_ENABLED)
    @SystemApi
    public static final String ECM_ENHANCED_CONFIRMATION_SERVICE = "ecm_enhanced_confirmation";

    /**
     * Use with {@link #getSystemService(String)} to retrieve a
     * {@link android.provider.ContactKeysManager} to managing contact keys.
     *
     * @see #getSystemService(String)
     * @see android.provider.ContactKeysManager
     */
    @FlaggedApi(android.provider.Flags.FLAG_USER_KEYS)
    public static final String CONTACT_KEYS_SERVICE = "contact_keys";

    /**
     * Determine whether the given permission is allowed for a particular
     * process and user ID running in the system.
     *
     * @param permission The name of the permission being checked.
     * @param pid The process ID being checked against.  Must be > 0.
     * @param uid The UID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} if the given
     * pid/uid is allowed that permission, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see PackageManager#checkPermission(String, String)
     * @see #checkCallingPermission
     */
    @CheckResult(suggest="#enforcePermission(String,int,int,String)")
    @PackageManager.PermissionResult
    @PermissionMethod
    public abstract int checkPermission(
            @NonNull @PermissionName String permission, int pid, int uid);

    /** @hide */
    @SuppressWarnings("HiddenAbstractMethod")
    @PackageManager.PermissionResult
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public abstract int checkPermission(@NonNull String permission, int pid, int uid,
            IBinder callerToken);

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
    @CheckResult(suggest="#enforceCallingPermission(String,String)")
    @PackageManager.PermissionResult
    @PermissionMethod
    public abstract int checkCallingPermission(@NonNull @PermissionName String permission);

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
    @CheckResult(suggest="#enforceCallingOrSelfPermission(String,String)")
    @PackageManager.PermissionResult
    @PermissionMethod(orSelf = true)
    public abstract int checkCallingOrSelfPermission(@NonNull @PermissionName String permission);

    /**
     * Determine whether <em>you</em> have been granted a particular permission.
     *
     * @param permission The name of the permission being checked.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} if you have the
     * permission, or {@link PackageManager#PERMISSION_DENIED} if not.
     *
     * @see PackageManager#checkPermission(String, String)
     * @see #checkCallingPermission(String)
     */
    @PackageManager.PermissionResult
    public abstract int checkSelfPermission(@NonNull String permission);

    /**
     * If the given permission is not allowed for a particular process
     * and user ID running in the system, throw a {@link SecurityException}.
     *
     * @param permission The name of the permission being checked.
     * @param pid The process ID being checked against.  Must be &gt; 0.
     * @param uid The UID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param message A message to include in the exception if it is thrown.
     *
     * @see #checkPermission(String, int, int)
     */
    @PermissionMethod
    public abstract void enforcePermission(
            @NonNull @PermissionName String permission, int pid, int uid, @Nullable String message);

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
    @PermissionMethod
    public abstract void enforceCallingPermission(
            @NonNull @PermissionName String permission, @Nullable String message);

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
    @PermissionMethod(orSelf = true)
    public abstract void enforceCallingOrSelfPermission(
            @NonNull @PermissionName String permission, @Nullable String message);

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
     * @param modeFlags The desired access modes.
     *
     * @see #revokeUriPermission
     */
    public abstract void grantUriPermission(String toPackage, Uri uri,
            @Intent.GrantUriMode int modeFlags);

    /**
     * Remove all permissions to access a particular content provider Uri
     * that were previously added with {@link #grantUriPermission} or <em>any other</em> mechanism.
     * The given Uri will match all previously granted Uris that are the same or a
     * sub-path of the given Uri.  That is, revoking "content://foo/target" will
     * revoke both "content://foo/target" and "content://foo/target/sub", but not
     * "content://foo".  It will not remove any prefix grants that exist at a
     * higher level.
     *
     * <p>Prior to {@link android.os.Build.VERSION_CODES#LOLLIPOP}, if you did not have
     * regular permission access to a Uri, but had received access to it through
     * a specific Uri permission grant, you could not revoke that grant with this
     * function and a {@link SecurityException} would be thrown.  As of
     * {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this function will not throw a security
     * exception, but will remove whatever permission grants to the Uri had been given to the app
     * (or none).</p>
     *
     * <p>Unlike {@link #revokeUriPermission(String, Uri, int)}, this method impacts all permission
     * grants matching the given Uri, for any package they had been granted to, through any
     * mechanism this had happened (such as indirectly through the clipboard, activity launch,
     * service start, etc).  That means this can be potentially dangerous to use, as it can
     * revoke grants that another app could be strongly expecting to stick around.</p>
     *
     * @param uri The Uri you would like to revoke access to.
     * @param modeFlags The access modes to revoke.
     *
     * @see #grantUriPermission
     */
    public abstract void revokeUriPermission(Uri uri, @Intent.AccessUriMode int modeFlags);

    /**
     * Remove permissions to access a particular content provider Uri
     * that were previously added with {@link #grantUriPermission} for a specific target
     * package.  The given Uri will match all previously granted Uris that are the same or a
     * sub-path of the given Uri.  That is, revoking "content://foo/target" will
     * revoke both "content://foo/target" and "content://foo/target/sub", but not
     * "content://foo".  It will not remove any prefix grants that exist at a
     * higher level.
     *
     * <p>Unlike {@link #revokeUriPermission(Uri, int)}, this method will <em>only</em>
     * revoke permissions that had been explicitly granted through {@link #grantUriPermission}
     * and only for the package specified.  Any matching grants that have happened through
     * other mechanisms (clipboard, activity launching, service starting, etc) will not be
     * removed.</p>
     *
     * @param toPackage The package you had previously granted access to.
     * @param uri The Uri you would like to revoke access to.
     * @param modeFlags The access modes to revoke.
     *
     * @see #grantUriPermission
     */
    public abstract void revokeUriPermission(String toPackage, Uri uri,
            @Intent.AccessUriMode int modeFlags);

    /**
     * Determine whether a particular process and uid has been granted
     * permission to access a specific URI.  This only checks for permissions
     * that have been explicitly granted -- if the given process/uid has
     * more general access to the URI's content provider then this check will
     * always fail.
     *
     * @param uri The uri that is being checked.
     * @param pid The process ID being checked against.  Must be &gt; 0.
     * @param uid The UID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param modeFlags The access modes to check.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} if the given
     * pid/uid is allowed to access that uri, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkCallingUriPermission
     */
    @CheckResult(suggest="#enforceUriPermission(Uri,int,int,String)")
    @PackageManager.PermissionResult
    public abstract int checkUriPermission(Uri uri, int pid, int uid,
            @Intent.AccessUriMode int modeFlags);

    /**
     * Determine whether a particular process and uid has been granted
     * permission to access a specific content URI.
     *
     * <p>Unlike {@link #checkUriPermission(Uri, int, int, int)}, this method
     * checks for general access to the URI's content provider, as well as
     * explicitly granted permissions.</p>
     *
     * <p>Note, this check will throw an {@link IllegalArgumentException}
     * for non-content URIs.</p>
     *
     * @param uri The content uri that is being checked.
     * @param pid (Optional) The process ID being checked against. If the
     * pid is unknown, pass -1.
     * @param uid The UID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param modeFlags The access modes to check.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} if the given
     * pid/uid is allowed to access that uri, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkUriPermission(Uri, int, int, int)
     */
    @FlaggedApi(android.security.Flags.FLAG_CONTENT_URI_PERMISSION_APIS)
    @PackageManager.PermissionResult
    public int checkContentUriPermissionFull(@NonNull Uri uri, int pid, int uid,
            @Intent.AccessUriMode int modeFlags) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Determine whether a particular process and uid has been granted
     * permission to access a list of URIs.  This only checks for permissions
     * that have been explicitly granted -- if the given process/uid has
     * more general access to the URI's content provider then this check will
     * always fail.
     *
     * <strong>Note:</strong> On SDK Version {@link android.os.Build.VERSION_CODES#S},
     * calling this method from a secondary-user's context will incorrectly return
     * {@link PackageManager#PERMISSION_DENIED} for all {code uris}.
     *
     * @param uris The list of URIs that is being checked.
     * @param pid The process ID being checked against.  Must be &gt; 0.
     * @param uid The UID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param modeFlags The access modes to check for the list of uris
     *
     * @return Array of permission grants corresponding to each entry in the list of uris.
     * {@link PackageManager#PERMISSION_GRANTED} if the given pid/uid is allowed to access that uri,
     * or {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkCallingUriPermission
     */
    @NonNull
    @PackageManager.PermissionResult
    public int[] checkUriPermissions(@NonNull List<Uri> uris, int pid, int uid,
            @Intent.AccessUriMode int modeFlags) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /** @hide */
    @SuppressWarnings("HiddenAbstractMethod")
    @PackageManager.PermissionResult
    public abstract int checkUriPermission(Uri uri, int pid, int uid,
            @Intent.AccessUriMode int modeFlags, IBinder callerToken);

    /**
     * Determine whether the calling process and uid has been
     * granted permission to access a specific URI.  This is basically
     * the same as calling {@link #checkUriPermission(Uri, int, int,
     * int)} with the pid and uid returned by {@link
     * android.os.Binder#getCallingPid} and {@link
     * android.os.Binder#getCallingUid}.  One important difference is
     * that if you are not currently processing an IPC, this function
     * will always fail.
     *
     * @param uri The uri that is being checked.
     * @param modeFlags The access modes to check.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} if the caller
     * is allowed to access that uri, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkUriPermission(Uri, int, int, int)
     */
    @CheckResult(suggest="#enforceCallingUriPermission(Uri,int,String)")
    @PackageManager.PermissionResult
    public abstract int checkCallingUriPermission(Uri uri, @Intent.AccessUriMode int modeFlags);

    /**
     * Determine whether the calling process and uid has been
     * granted permission to access a list of URIs.  This is basically
     * the same as calling {@link #checkUriPermissions(List, int, int, int)}
     * with the pid and uid returned by {@link
     * android.os.Binder#getCallingPid} and {@link
     * android.os.Binder#getCallingUid}.  One important difference is
     * that if you are not currently processing an IPC, this function
     * will always fail.
     *
     * @param uris The list of URIs that is being checked.
     * @param modeFlags The access modes to check.
     *
     * @return Array of permission grants corresponding to each entry in the list of uris.
     * {@link PackageManager#PERMISSION_GRANTED} if the given pid/uid is allowed to access that uri,
     * or {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkUriPermission(Uri, int, int, int)
     */
    @NonNull
    @PackageManager.PermissionResult
    public int[] checkCallingUriPermissions(@NonNull List<Uri> uris,
            @Intent.AccessUriMode int modeFlags) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Determine whether the calling process of an IPC <em>or you</em> has been granted
     * permission to access a specific URI.  This is the same as
     * {@link #checkCallingUriPermission}, except it grants your own permissions
     * if you are not currently processing an IPC.  Use with care!
     *
     * @param uri The uri that is being checked.
     * @param modeFlags The access modes to check.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} if the caller
     * is allowed to access that uri, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkCallingUriPermission
     */
    @CheckResult(suggest="#enforceCallingOrSelfUriPermission(Uri,int,String)")
    @PackageManager.PermissionResult
    public abstract int checkCallingOrSelfUriPermission(Uri uri,
            @Intent.AccessUriMode int modeFlags);

    /**
     * Determine whether the calling process of an IPC <em>or you</em> has been granted
     * permission to access a list of URIs.  This is the same as
     * {@link #checkCallingUriPermission}, except it grants your own permissions
     * if you are not currently processing an IPC.  Use with care!
     *
     * @param uris The list of URIs that is being checked.
     * @param modeFlags The access modes to check.
     *
     * @return Array of permission grants corresponding to each entry in the list of uris.
     * {@link PackageManager#PERMISSION_GRANTED} if the given pid/uid is allowed to access that uri,
     * or {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #checkCallingUriPermission
     */
    @NonNull
    @PackageManager.PermissionResult
    public int[] checkCallingOrSelfUriPermissions(@NonNull List<Uri> uris,
            @Intent.AccessUriMode int modeFlags) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

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
     * @param uid The UID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param modeFlags The access modes to check.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} if the caller
     * is allowed to access that uri or holds one of the given permissions, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     */
    @CheckResult(suggest="#enforceUriPermission(Uri,String,String,int,int,int,String)")
    @PackageManager.PermissionResult
    public abstract int checkUriPermission(@Nullable Uri uri, @Nullable String readPermission,
            @Nullable String writePermission, int pid, int uid,
            @Intent.AccessUriMode int modeFlags);

    /**
     * If a particular process and uid has not been granted
     * permission to access a specific URI, throw {@link
     * SecurityException}.  This only checks for permissions that have
     * been explicitly granted -- if the given process/uid has more
     * general access to the URI's content provider then this check
     * will always fail.
     *
     * @param uri The uri that is being checked.
     * @param pid The process ID being checked against.  Must be &gt; 0.
     * @param uid The UID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param modeFlags The access modes to enforce.
     * @param message A message to include in the exception if it is thrown.
     *
     * @see #checkUriPermission(Uri, int, int, int)
     */
    public abstract void enforceUriPermission(
            Uri uri, int pid, int uid, @Intent.AccessUriMode int modeFlags, String message);

    /**
     * If the calling process and uid has not been granted
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
     * @param modeFlags The access modes to enforce.
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
     * @param modeFlags The access modes to enforce.
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
     * @param uid The UID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     * @param modeFlags The access modes to enforce.
     * @param message A message to include in the exception if it is thrown.
     *
     * @see #checkUriPermission(Uri, String, String, int, int, int)
     */
    public abstract void enforceUriPermission(
            @Nullable Uri uri, @Nullable String readPermission,
            @Nullable String writePermission, int pid, int uid, @Intent.AccessUriMode int modeFlags,
            @Nullable String message);


    /**
     * Triggers the asynchronous revocation of a runtime permission. If the permission is not
     * currently granted, nothing happens (even if later granted by the user).
     *
     * @param permName The name of the permission to be revoked.
     * @see #revokeSelfPermissionsOnKill(Collection)
     * @throws IllegalArgumentException if the permission is not a runtime permission
     */
    public void revokeSelfPermissionOnKill(@NonNull String permName) {
        revokeSelfPermissionsOnKill(Collections.singletonList(permName));
    }

    /**
     * Triggers the revocation of one or more permissions for the calling package. A package is only
     * able to revoke runtime permissions. If a permission is not currently granted, it is ignored
     * and will not get revoked (even if later granted by the user). Ultimately, you should never
     * make assumptions about a permission status as users may grant or revoke them at any time.
     * <p>
     * Background permissions which have no corresponding foreground permission still granted once
     * the revocation is effective will also be revoked.
     * <p>
     * The revocation happens asynchronously and kills all processes running in the calling UID. It
     * will be triggered once it is safe to do so. In particular, it will not be triggered as long
     * as the package remains in the foreground, or has any active manifest components (e.g. when
     * another app is accessing a content provider in the package).
     * <p>
     * If you want to revoke the permissions right away, you could call {@code System.exit()}, but
     * this could affect other apps that are accessing your app at the moment. For example, apps
     * accessing a content provider in your app will all crash.
     * <p>
     * Note that the settings UI shows a permission group as granted as long as at least one
     * permission in the group is granted. If you want the user to observe the revocation in the
     * settings, you should revoke every permission in the target group. To learn the current list
     * of permissions in a group, you may use
     * {@link PackageManager#getGroupOfPlatformPermission(String, Executor, Consumer)} and
     * {@link PackageManager#getPlatformPermissionsForGroup(String, Executor, Consumer)}. This list
     * of permissions may evolve over time, so it is recommended to check whether it contains any
     * permission you wish to retain before trying to revoke an entire group.
     *
     * @param permissions Collection of permissions to be revoked.
     * @see PackageManager#getGroupOfPlatformPermission(String, Executor, Consumer)
     * @see PackageManager#getPlatformPermissionsForGroup(String, Executor, Consumer)
     * @throws IllegalArgumentException if any of the permissions is not a runtime permission
     */
    public void revokeSelfPermissionsOnKill(@NonNull Collection<String> permissions) {
        throw new AbstractMethodError("Must be overridden in implementing class");
    }

    /** @hide */
    @IntDef(flag = true, prefix = { "CONTEXT_" }, value = {
            CONTEXT_INCLUDE_CODE,
            CONTEXT_IGNORE_SECURITY,
            CONTEXT_RESTRICTED,
            CONTEXT_DEVICE_PROTECTED_STORAGE,
            CONTEXT_CREDENTIAL_PROTECTED_STORAGE,
            CONTEXT_REGISTER_PACKAGE,
    })
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
     * Flag for use with {@link #createPackageContext}: point all file APIs at
     * device-protected storage.
     *
     * @hide
     */
    public static final int CONTEXT_DEVICE_PROTECTED_STORAGE = 0x00000008;

    /**
     * Flag for use with {@link #createPackageContext}: point all file APIs at
     * credential-protected storage.
     *
     * @hide
     */
    public static final int CONTEXT_CREDENTIAL_PROTECTED_STORAGE = 0x00000010;

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
     * <p>Throws {@link android.content.pm.PackageManager.NameNotFoundException} if there is no
     * application with the given package name.
     *
     * <p>Throws {@link java.lang.SecurityException} if the Context requested
     * can not be loaded into the caller's process for security reasons (see
     * {@link #CONTEXT_INCLUDE_CODE} for more information}.
     *
     * @param packageName Name of the application's package.
     * @param flags Option flags.
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
    @SystemApi
    @NonNull
    public Context createPackageContextAsUser(
            @NonNull String packageName, @CreatePackageOptions int flags, @NonNull UserHandle user)
            throws PackageManager.NameNotFoundException {
        if (Build.IS_ENG) {
            throw new IllegalStateException("createPackageContextAsUser not overridden!");
        }
        return this;
    }

    /**
     * Similar to {@link #createPackageContext(String, int)}, but for the own package with a
     * different {@link UserHandle}. For example, {@link #getContentResolver()}
     * will open any {@link Uri} as the given user.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public Context createContextAsUser(@NonNull UserHandle user, @CreatePackageOptions int flags) {
        if (Build.IS_ENG) {
            throw new IllegalStateException("createContextAsUser not overridden!");
        }
        return this;
    }

    /**
     * Creates a context given an {@link android.content.pm.ApplicationInfo}.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract Context createApplicationContext(ApplicationInfo application,
            @CreatePackageOptions int flags) throws PackageManager.NameNotFoundException;

    /**
     * Creates a context given an {@link android.content.pm.ApplicationInfo}.
     *
     * Context created is for an sdk library that is being loaded in sdk sandbox.
     *
     * @param sdkInfo information regarding the sdk library being loaded.
     *
     * @throws PackageManager.NameNotFoundException if there is no application with
     * the given package name.
     * @throws SecurityException if caller is not a SdkSandbox process.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @NonNull
    public Context createContextForSdkInSandbox(@NonNull ApplicationInfo sdkInfo,
            @CreatePackageOptions int flags) throws PackageManager.NameNotFoundException {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Return a new Context object for the given split name. The new Context has a ClassLoader and
     * Resources object that can access the split's and all of its dependencies' code/resources.
     * Each call to this method returns a new instance of a Context object;
     * Context objects are not shared, however common state (ClassLoader, other Resources for
     * the same split) may be so the Context itself can be fairly lightweight.
     *
     * @param splitName The name of the split to include, as declared in the split's
     *                  <code>AndroidManifest.xml</code>.
     * @return A {@link Context} with the given split's code and/or resources loaded.
     */
    public abstract Context createContextForSplit(String splitName)
            throws PackageManager.NameNotFoundException;

    /**
     * Get the user associated with this context.
     *
     * @return the user associated with this context
     *
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public UserHandle getUser() {
        return android.os.Process.myUserHandle();
    }

    /**
     * Get the user associated with this context
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public @UserIdInt int getUserId() {
        return android.os.UserHandle.myUserId();
    }

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
     * Returns a new {@code Context} object from the current context but with resources
     * adjusted to match the metrics of {@code display}. Each call to this method
     * returns a new instance of a context object. Context objects are not shared; however,
     * common state (such as the {@link ClassLoader} and other resources for the same
     * configuration) can be shared, so the {@code Context} itself is lightweight.
     *
     * <p><b>Note:</b>
     * This {@code Context} is <b>not</b> expected to be updated with new configuration if the
     * underlying display configuration changes and the cached {@code Resources} it returns
     * could be stale. It is suggested to use
     * {@link android.hardware.display.DisplayManager.DisplayListener} to listen for
     * changes and re-create an instance if necessary. </p>
     * <p>
     * This {@code Context} is <b>not</b> a UI context, do not use it to access UI components
     * or obtain a {@link WindowManager} instance.
     * </p><p>
     * To obtain an instance of {@link WindowManager} configured to show windows on the given
     * display, call {@link #createWindowContext(int, Bundle)} on the returned display context,
     * then call {@link #getSystemService(String)} or {@link #getSystemService(Class)} on the
     * returned window context.
     * </p>
     * @param display The display to which the current context's resources are adjusted.
     *
     * @return A context for the display.
     */
    @DisplayContext
    public abstract Context createDisplayContext(@NonNull Display display);

    /**
     * Returns a new {@code Context} object from the current context but with device association
     * given by the {@code deviceId}. Each call to this method returns a new instance of a context
     * object. Context objects are not shared; however, common state (such as the
     * {@link ClassLoader} and other resources for the same configuration) can be shared, so the
     * {@code Context} itself is lightweight.
     * <p>
     * Applications that run on virtual devices may use this method to access the default device
     * capabilities and functionality (by passing
     * {@link Context#DEVICE_ID_DEFAULT}. Similarly,
     * applications running on the default device may access the functionality of virtual devices.
     * </p>
     * <p>
     * Note that the newly created instance will be associated with the same display as the parent
     * Context, regardless of the device ID passed here.
     * </p>
     * @param deviceId The ID of the device to associate with this context.
     * @return A context associated with the given device ID.
     *
     * @see #getDeviceId()
     * @see VirtualDeviceManager#getVirtualDevices()
     * @throws IllegalArgumentException if the given device ID is not a valid ID of the default
     * device or a virtual device.
     */
    public @NonNull Context createDeviceContext(int deviceId) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Creates a Context for a non-activity window.
     *
     * <p>
     * A window context is a context that can be used to add non-activity windows, such as
     * {@link android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY}. A window context
     * must be created from a context that has an associated {@link Display}, such as
     * {@link android.app.Activity Activity} or a context created with
     * {@link #createDisplayContext(Display)}.
     *
     * <p>
     * The window context is created with the appropriate {@link Configuration} for the area of the
     * display that the windows created with it can occupy; it must be used when
     * {@link android.view.LayoutInflater inflating} views, such that they can be inflated with
     * proper {@link Resources}.
     *
     * Below is a sample code to <b>add an application overlay window on the primary display:</b>
     * <pre class="prettyprint">
     * ...
     * final DisplayManager dm = anyContext.getSystemService(DisplayManager.class);
     * final Display primaryDisplay = dm.getDisplay(DEFAULT_DISPLAY);
     * final Context windowContext = anyContext.createDisplayContext(primaryDisplay)
     *         .createWindowContext(TYPE_APPLICATION_OVERLAY, null);
     * final View overlayView = Inflater.from(windowContext).inflate(someLayoutXml, null);
     *
     * // WindowManager.LayoutParams initialization
     * ...
     * // The types used in addView and createWindowContext must match.
     * mParams.type = TYPE_APPLICATION_OVERLAY;
     * ...
     *
     * windowContext.getSystemService(WindowManager.class).addView(overlayView, mParams);
     * </pre>
     *
     * <p>
     * This context's configuration and resources are adjusted to an area of the display where
     * the windows with provided type will be added. <b>Note that all windows associated with the
     * same context will have an affinity and can only be moved together between different displays
     * or areas on a display.</b> If there is a need to add different window types, or
     * non-associated windows, separate Contexts should be used.
     * </p>
     * <p>
     * Creating a window context is an expensive operation. Misuse of this API may lead to a huge
     * performance drop. The best practice is to use the same window context when possible.
     * An approach is to create one window context with specific window type and display and
     * use it everywhere it's needed.
     * </p>
     * <p>
     * After {@link Build.VERSION_CODES#S}, window context provides the capability to receive
     * configuration changes for existing token by overriding the
     * {@link android.view.WindowManager.LayoutParams#token token} of the
     * {@link android.view.WindowManager.LayoutParams} passed in
     * {@link WindowManager#addView(View, LayoutParams)}. This is useful when an application needs
     * to attach its window to an existing activity for window token sharing use-case.
     * </p>
     * <p>
     * Note that the window context in {@link Build.VERSION_CODES#R} didn't have this
     * capability. This is a no-op for the window context in {@link Build.VERSION_CODES#R}.
     * </p>
     * Below is sample code to <b>attach an existing token to a window context:</b>
     * <pre class="prettyprint">
     * final DisplayManager dm = anyContext.getSystemService(DisplayManager.class);
     * final Display primaryDisplay = dm.getDisplay(DEFAULT_DISPLAY);
     * final Context windowContext = anyContext.createWindowContext(primaryDisplay,
     *         TYPE_APPLICATION, null);
     *
     * // Get an existing token.
     * final IBinder existingToken = activity.getWindow().getAttributes().token;
     *
     * // The types used in addView() and createWindowContext() must match.
     * final WindowManager.LayoutParams params = new WindowManager.LayoutParams(TYPE_APPLICATION);
     * params.token = existingToken;
     *
     * // After WindowManager#addView(), the server side will extract the provided token from
     * // LayoutParams#token (existingToken in the sample code), and switch to propagate
     * // configuration changes from the node associated with the provided token.
     * windowContext.getSystemService(WindowManager.class).addView(overlayView, mParams);
     * </pre>
     * <p>
     * After {@link Build.VERSION_CODES#S}, window context provides the capability to listen to its
     * {@link Configuration} changes by calling
     * {@link #registerComponentCallbacks(ComponentCallbacks)}, while other kinds of {@link Context}
     * will register the {@link ComponentCallbacks} to {@link #getApplicationContext() its
     * Application context}. Note that window context only propagate
     * {@link ComponentCallbacks#onConfigurationChanged(Configuration)} callback.
     * {@link ComponentCallbacks#onLowMemory()} or other callbacks in {@link ComponentCallbacks2}
     * won't be invoked.
     * </p>
     * <p>
     * Note that using {@link android.app.Application} or {@link android.app.Service} context for
     * UI-related queries may result in layout or continuity issues on devices with variable screen
     * sizes (e.g. foldables) or in multi-window modes, since these non-UI contexts may not reflect
     * the {@link Configuration} changes for the visual container.
     * </p>
     * @param type Window type in {@link WindowManager.LayoutParams}
     * @param options A bundle used to pass window-related options
     * @return A {@link Context} that can be used to create
     *         non-{@link android.app.Activity activity} windows.
     *
     * @see #getSystemService(String)
     * @see #getSystemService(Class)
     * @see #WINDOW_SERVICE
     * @see #LAYOUT_INFLATER_SERVICE
     * @see #WALLPAPER_SERVICE
     * @throws UnsupportedOperationException if this {@link Context} does not attach to a display,
     * such as {@link android.app.Application Application} or {@link android.app.Service Service}.
     */
    @UiContext
    @NonNull
    public Context createWindowContext(@WindowType int type, @Nullable Bundle options)  {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Creates a {@code Context} for a non-{@link android.app.Activity activity} window on the given
     * {@link Display}.
     *
     * <p>
     * Similar to {@link #createWindowContext(int, Bundle)}, but the {@code display} is passed in,
     * instead of implicitly using the {@link #getDisplay() original Context's Display}.
     * </p>
     *
     * @param display The {@link Display} to associate with
     * @param type Window type in {@link WindowManager.LayoutParams}
     * @param options A bundle used to pass window-related options.
     * @return A {@link Context} that can be used to create
     *         non-{@link android.app.Activity activity} windows.
     * @throws IllegalArgumentException if the {@link Display} is {@code null}.
     *
     * @see #getSystemService(String)
     * @see #getSystemService(Class)
     * @see #WINDOW_SERVICE
     * @see #LAYOUT_INFLATER_SERVICE
     * @see #WALLPAPER_SERVICE
     */
    @UiContext
    @NonNull
    public Context createWindowContext(@NonNull Display display, @WindowType int type,
            @SuppressLint("NullableCollection")
            @Nullable Bundle options) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Creates a context with specific properties and behaviors.
     *
     * @param contextParams Parameters for how the new context should behave.
     * @return A context with the specified behaviors.
     *
     * @see ContextParams
     */
    @NonNull
    public Context createContext(@NonNull ContextParams contextParams) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Return a new Context object for the current Context but attribute to a different tag.
     * In complex apps attribution tagging can be used to distinguish between separate logical
     * parts.
     *
     * @param attributionTag The tag or {@code null} to create a context for the default.
     *
     * @return A {@link Context} that is tagged for the new attribution
     *
     * @see #getAttributionTag()
     */
    public @NonNull Context createAttributionContext(@Nullable String attributionTag) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    // TODO moltmann: remove
    /**
     * @removed
     */
    @Deprecated
    public @NonNull Context createFeatureContext(@Nullable String attributionTag) {
        return createContext(new ContextParams.Builder(getParams())
                .setAttributionTag(attributionTag)
                .build());
    }

    /**
     * Return a new Context object for the current Context but whose storage
     * APIs are backed by device-protected storage.
     * <p>
     * On devices with direct boot, data stored in this location is encrypted
     * with a key tied to the physical device, and it can be accessed
     * immediately after the device has booted successfully, both
     * <em>before and after</em> the user has authenticated with their
     * credentials (such as a lock pattern or PIN).
     * <p>
     * Because device-protected data is available without user authentication,
     * you should carefully limit the data you store using this Context. For
     * example, storing sensitive authentication tokens or passwords in the
     * device-protected area is strongly discouraged.
     * <p>
     * If the underlying device does not have the ability to store
     * device-protected and credential-protected data using different keys, then
     * both storage areas will become available at the same time. They remain as
     * two distinct storage locations on disk, and only the window of
     * availability changes.
     * <p>
     * Each call to this method returns a new instance of a Context object;
     * Context objects are not shared, however common state (ClassLoader, other
     * Resources for the same configuration) may be so the Context itself can be
     * fairly lightweight.
     *
     * @see #isDeviceProtectedStorage()
     */
    public abstract Context createDeviceProtectedStorageContext();

    /**
     * Return a new Context object for the current Context but whose storage
     * APIs are backed by credential-protected storage. This is the default
     * storage area for apps unless
     * {@link android.R.attr#defaultToDeviceProtectedStorage} was requested.
     * <p>
     * On devices with direct boot, data stored in this location is encrypted
     * with a key tied to user credentials, which can be accessed
     * <em>only after</em> the user has entered their credentials (such as a
     * lock pattern or PIN).
     * <p>
     * If the underlying device does not have the ability to store
     * device-protected and credential-protected data using different keys, then
     * both storage areas will become available at the same time. They remain as
     * two distinct storage locations on disk, and only the window of
     * availability changes.
     * <p>
     * Each call to this method returns a new instance of a Context object;
     * Context objects are not shared, however common state (ClassLoader, other
     * Resources for the same configuration) may be so the Context itself can be
     * fairly lightweight.
     *
     * @see #isCredentialProtectedStorage()
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    public abstract Context createCredentialProtectedStorageContext();

    /**
     * Creates a UI context with a {@code token}. The users of this API should handle this context's
     * configuration changes.
     *
     * @param token The token to associate with the {@link Resources}
     * @param display The display to associate with the token context
     *
     * @hide
     */
    @UiContext
    @NonNull
    public Context createTokenContext(@NonNull IBinder token, @NonNull Display display) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Gets the display adjustments holder for this context.  This information
     * is provided on a per-application or activity basis and is used to simulate lower density
     * display metrics for legacy applications and restricted screen sizes.
     *
     * @param displayId The display id for which to get compatibility info.
     * @return The compatibility info holder, or null if not required by the application.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract DisplayAdjustments getDisplayAdjustments(int displayId);

    /**
     * Get the display this context is associated with. Applications should use this method with
     * {@link android.app.Activity} or a context associated with a {@link Display} via
     * {@link #createDisplayContext(Display)} to get a display object associated with a Context, or
     * {@link android.hardware.display.DisplayManager#getDisplay} to get a display object by id.
     * @return Returns the {@link Display} object this context is associated with.
     * @throws UnsupportedOperationException if the method is called on an instance that is not
     *         associated with any display.
     */
    @NonNull
    public Display getDisplay() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * A version of {@link #getDisplay()} that does not perform a Context misuse check to be used by
     * legacy APIs.
     * TODO(b/149790106): Fix usages and remove.
     * @hide
     */
    @Nullable
    public Display getDisplayNoVerify() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Gets the ID of the display this context is associated with.
     *
     * @return display ID associated with this {@link Context}.
     * @see #getDisplay()
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @TestApi
    public abstract int getDisplayId();

    /**
     * @return Returns the id of the Display object associated with this Context or
     * {@link Display#INVALID_DISPLAY} if no Display has been associated.
     * @see #getDisplay()
     * @see #getDisplayId()
     *
     * @hide
     */
    public int getAssociatedDisplayId() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract void updateDisplay(int displayId);

    /**
     * Updates the device ID association of this Context. Since a Context created with
     * {@link #createDeviceContext} cannot change its device association, this method must
     * not be called for instances created with {@link #createDeviceContext}.
     *<p>
     * Note that updating the deviceId of the Context will not update its associated display.
     *</p>
     * @param deviceId The new device ID to assign to this Context.
     * @throws UnsupportedOperationException if the method is called on an instance that was
     *         created with {@link Context#createDeviceContext(int)}
     * @throws IllegalArgumentException if the given device ID is not a valid ID of the default
     *         device or a virtual device.
     *
     * @see #createDeviceContext(int)
     * @hide
     */
    @TestApi
    public void updateDeviceId(int deviceId) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Gets the device ID this context is associated with. Applications can use this method to
     * determine whether they are running on a virtual device and identify that device.
     *
     * The device ID of the host device is
     * {@link Context#DEVICE_ID_DEFAULT}
     *
     * <p>
     * If the underlying device ID is changed by the system, for example, when an
     * {@link Activity} is moved to a different virtual device, applications can register to listen
     * to changes by calling
     * {@link Context#registerDeviceIdChangeListener(Executor, IntConsumer)}.
     * </p>
     *
     * <p>
     * This method will only return a reliable value for this instance if it was created with
     * {@link Context#createDeviceContext(int)}, or if this instance is a UI or Display Context.
     * Contexts created with {@link Context#createDeviceContext(int)} will have an explicit
     * device association, which will never change, even if the underlying device is closed or is
     * removed. UI Contexts and Display Contexts are
     * already associated with a display, so if the device association is not explicitly
     * given, {@link Context#getDeviceId()} will return the ID of the device associated with
     * the associated display. The system can assign an arbitrary device id value for Contexts not
     * logically associated with a device.
     * </p>
     *
     * @return the ID of the device this context is associated with.
     * @see #createDeviceContext(int)
     * @see #registerDeviceIdChangeListener(Executor, IntConsumer)
     * @see #isUiContext()
     */
    public int getDeviceId() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Adds a new device ID changed listener to the {@code Context}, which will be called when
     * the device association is changed by the system.
     * <p>
     * The callback can be called when an app is moved to a different device and the {@code Context}
     * is not explicitly associated with a specific device.
     * </p>
     * <p> When an application receives a device id update callback, this Context is guaranteed to
     * also have an updated display ID(if any) and {@link Configuration}.
     * <p/>
     * @param executor The Executor on whose thread to execute the callbacks of the {@code listener}
     *                 object.
     * @param listener The listener {@code IntConsumer} to call which will receive the updated
     *                 device ID.
     *
     * @see Context#getDeviceId()
     * @see Context#createDeviceContext(int)
     */
    public void registerDeviceIdChangeListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull IntConsumer listener) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Removes a device ID changed listener from the Context. It's a no-op if
     * the listener is not already registered.
     *
     * @param listener The {@code Consumer} to remove.
     *
     * @see #getDeviceId()
     * @see #registerDeviceIdChangeListener(Executor, IntConsumer)
     */
    public void unregisterDeviceIdChangeListener(@NonNull IntConsumer listener) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

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

    /**
     * Indicates if the storage APIs of this Context are backed by
     * device-protected storage.
     *
     * @see #createDeviceProtectedStorageContext()
     */
    public abstract boolean isDeviceProtectedStorage();

    /**
     * Indicates if the storage APIs of this Context are backed by
     * credential-protected storage.
     *
     * @see #createCredentialProtectedStorageContext()
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    public abstract boolean isCredentialProtectedStorage();

    /**
     * Returns true if the context can load unsafe resources, e.g. fonts.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract boolean canLoadUnsafeResources();

    /**
     * Returns token if the {@link Context} is a {@link android.app.Activity}. Returns
     * {@code null} otherwise.
     *
     * @hide
     */
    @Nullable
    public IBinder getActivityToken() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Returns the {@link IBinder} representing the associated
     * {@link com.android.server.wm.WindowToken} if the {@link Context} is a
     * {@link android.app.WindowContext}. Returns {@code null} otherwise.
     *
     * @hide
     */
    @Nullable
    public IBinder getWindowContextToken() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Returns the proper token of a {@link Context}.
     *
     * If the {@link Context} is an {@link android.app.Activity}, returns
     * {@link #getActivityToken()}. If the {@lijnk Context} is a {@link android.app.WindowContext},
     * returns {@link #getWindowContextToken()}. Returns {@code null}, otherwise.
     *
     * @hide
     */
    @Nullable
    public static IBinder getToken(@NonNull Context context) {
        return context.getActivityToken() != null ? context.getActivityToken()
                : context.getWindowContextToken();
    }

    /**
     * @hide
     */
    @Nullable
    public IServiceConnection getServiceDispatcher(ServiceConnection conn, Handler handler,
            long flags) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * @hide
     */
    public IApplicationThread getIApplicationThread() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Used by a mainline module to uniquely identify a specific app process.
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public IBinder getProcessToken() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * @hide
     */
    public Handler getMainThreadHandler() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * @hide
     */
    public AutofillClient getAutofillClient() {
        return null;
    }

    /**
     * @hide
     */
    public void setAutofillClient(@SuppressWarnings("unused") AutofillClient client) {
    }

    /**
     * @hide
     */
    @Nullable
    public ContentCaptureClient getContentCaptureClient() {
        return null;
    }

    /**
     * @hide
     */
    public final boolean isAutofillCompatibilityEnabled() {
        final AutofillOptions options = getAutofillOptions();
        return options != null && options.compatModeEnabled;
    }

    /**
     * @hide
     */
    @Nullable
    public AutofillOptions getAutofillOptions() {
        return null;
    }

    /**
     * @hide
     */
    @TestApi
    public void setAutofillOptions(@SuppressWarnings("unused") @Nullable AutofillOptions options) {
    }

    /**
     * Gets the Content Capture options for this context, or {@code null} if it's not allowlisted.
     *
     * @hide
     */
    @Nullable
    public ContentCaptureOptions getContentCaptureOptions() {
        return null;
    }

    /**
     * @hide
     */
    @TestApi
    public void setContentCaptureOptions(
            @SuppressWarnings("unused") @Nullable ContentCaptureOptions options) {
    }

    /**
     * Throws an exception if the Context is using system resources,
     * which are non-runtime-overlay-themable and may show inconsistent UI.
     * @hide
     */
    public void assertRuntimeOverlayThemable() {
        // Resources.getSystem() is a singleton and the only Resources not managed by
        // ResourcesManager; therefore Resources.getSystem() is not themable.
        if (getResources() == Resources.getSystem()) {
            throw new IllegalArgumentException("Non-UI context used to display UI; "
                    + "get a UI context from ActivityThread#getSystemUiContext()");
        }
    }

    /**
     * Returns {@code true} if the context is a UI context which can access UI components such as
     * {@link WindowManager}, {@link android.view.LayoutInflater LayoutInflater} or
     * {@link android.app.WallpaperManager WallpaperManager}. Accessing UI components from non-UI
     * contexts throws {@link android.os.strictmode.Violation} if
     * {@link android.os.StrictMode.VmPolicy.Builder#detectIncorrectContextUse()} is enabled.
     * <p>
     * Examples of UI contexts are
     * an {@link android.app.Activity Activity}, a context created from
     * {@link #createWindowContext(int, Bundle)} or
     * {@link android.inputmethodservice.InputMethodService InputMethodService}
     * </p>
     * <p>
     * Note that even if it is allowed programmatically, it is not suggested to override this
     * method to bypass {@link android.os.strictmode.IncorrectContextUseViolation} verification.
     * </p>
     *
     * @see #getDisplay()
     * @see #getSystemService(String)
     * @see android.os.StrictMode.VmPolicy.Builder#detectIncorrectContextUse()
     */
    public boolean isUiContext() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Called when a {@link Context} is going to be released.
     * This method can be overridden to perform the final cleanups, such as release
     * {@link BroadcastReceiver} registrations.
     *
     * @see WindowContext#destroy()
     *
     * @hide
     */
    public void destroy() { }

    /**
     * Indicates this {@link Context} has the proper {@link Configuration} to obtain
     * {@link android.view.LayoutInflater}, {@link android.view.ViewConfiguration} and
     * {@link android.view.GestureDetector}. Generally, all UI contexts, such as
     * {@link android.app.Activity} or {@link android.app.WindowContext}, are initialized with base
     * configuration.
     * <p>
     * Note that the context created via {@link Context#createConfigurationContext(Configuration)}
     * is also regarded as a context that is based on a configuration because the
     * configuration is explicitly provided via the API.
     * </p>
     *
     * @see #isUiContext()
     * @see #createConfigurationContext(Configuration)
     *
     * @hide
     */
    public boolean isConfigurationContext() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Closes temporary system dialogs. Some examples of temporary system dialogs are the
     * notification window-shade and the recent tasks dialog.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS)
    public void closeSystemDialogs() {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }
}
