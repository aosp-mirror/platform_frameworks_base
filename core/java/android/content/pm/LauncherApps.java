/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.content.pm;

import static android.Manifest.permission;
import static android.Manifest.permission.ACCESS_HIDDEN_PROFILES;
import static android.Manifest.permission.ACCESS_HIDDEN_PROFILES_FULL;
import static android.Manifest.permission.READ_FRAME_BUFFER;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.LocusId;
import android.content.pm.PackageInstaller.SessionCallback;
import android.content.pm.PackageInstaller.SessionCallbackDelegate;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager.ApplicationInfoFlagsBits;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Flags;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.window.IDumpCallback;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * Class for retrieving a list of launchable activities for the current user and any associated
 * managed profiles that are visible to the current user, which can be retrieved with
 * {@link #getProfiles}. This is mainly for use by launchers.
 *
 * Apps can be queried for each user profile.
 * Since the PackageManager will not deliver package broadcasts for other profiles, you can register
 * for package changes here.
 * <p>
 * To watch for managed profiles being added or removed, register for the following broadcasts:
 * {@link Intent#ACTION_MANAGED_PROFILE_ADDED} and {@link Intent#ACTION_MANAGED_PROFILE_REMOVED}.
 * <p>
 * Note as of Android O, apps on a managed profile are no longer allowed to access apps on the
 * main profile.  Apps can only access profiles returned by {@link #getProfiles()}.
 */
@SystemService(Context.LAUNCHER_APPS_SERVICE)
public class LauncherApps {

    static final String TAG = "LauncherApps";
    static final boolean DEBUG = false;

    /**
     * Activity Action: For the default launcher to show the confirmation dialog to create
     * a pinned shortcut.
     *
     * <p>See the {@link ShortcutManager} javadoc for details.
     *
     * <p>
     * Use {@link #getPinItemRequest(Intent)} to get a {@link PinItemRequest} object,
     * and call {@link PinItemRequest#accept(Bundle)}
     * if the user accepts.  If the user doesn't accept, no further action is required.
     *
     * @see #EXTRA_PIN_ITEM_REQUEST
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CONFIRM_PIN_SHORTCUT =
            "android.content.pm.action.CONFIRM_PIN_SHORTCUT";

    /**
     * Activity Action: For the default launcher to show the confirmation dialog to create
     * a pinned app widget.
     *
     * <p>See the {@link android.appwidget.AppWidgetManager#requestPinAppWidget} javadoc for
     * details.
     *
     * <p>
     * Use {@link #getPinItemRequest(Intent)} to get a {@link PinItemRequest} object,
     * and call {@link PinItemRequest#accept(Bundle)}
     * if the user accepts.  If the user doesn't accept, no further action is required.
     *
     * @see #EXTRA_PIN_ITEM_REQUEST
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CONFIRM_PIN_APPWIDGET =
            "android.content.pm.action.CONFIRM_PIN_APPWIDGET";

    /**
     * An extra for {@link #ACTION_CONFIRM_PIN_SHORTCUT} &amp; {@link #ACTION_CONFIRM_PIN_APPWIDGET}
     * containing a {@link PinItemRequest} of appropriate type asked to pin.
     *
     * <p>A helper function {@link #getPinItemRequest(Intent)} can be used
     * instead of using this constant directly.
     *
     * @see #ACTION_CONFIRM_PIN_SHORTCUT
     * @see #ACTION_CONFIRM_PIN_APPWIDGET
     */
    public static final String EXTRA_PIN_ITEM_REQUEST =
            "android.content.pm.extra.PIN_ITEM_REQUEST";

    /**
     * Cache shortcuts which are used in notifications.
     * @hide
     */
    public static final int FLAG_CACHE_NOTIFICATION_SHORTCUTS = 0;

    /**
     * Cache shortcuts which are used in bubbles.
     * @hide
     */
    public static final int FLAG_CACHE_BUBBLE_SHORTCUTS = 1;

    /**
     * Cache shortcuts which are used in People Tile.
     * @hide
     */
    public static final int FLAG_CACHE_PEOPLE_TILE_SHORTCUTS = 2;

    /** @hide */
    @IntDef(flag = false, prefix = { "FLAG_CACHE_" }, value = {
            FLAG_CACHE_NOTIFICATION_SHORTCUTS,
            FLAG_CACHE_BUBBLE_SHORTCUTS,
            FLAG_CACHE_PEOPLE_TILE_SHORTCUTS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShortcutCacheFlags {}

    private final Context mContext;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final ILauncherApps mService;
    @UnsupportedAppUsage
    private final PackageManager mPm;
    private final UserManager mUserManager;

    private final List<CallbackMessageHandler> mCallbacks = new ArrayList<>();
    private final List<SessionCallbackDelegate> mDelegates = new ArrayList<>();

    private final Map<ShortcutChangeCallback, Pair<Executor, IShortcutChangeCallback>>
            mShortcutChangeCallbacks = new HashMap<>();

    /**
     * Callbacks for package changes to this and related managed profiles.
     */
    public static abstract class Callback {
        /**
         * Indicates that a package was removed from the specified profile.
         *
         * If a package is removed while being updated onPackageChanged will be
         * called instead.
         *
         * @param packageName The name of the package that was removed.
         * @param user The UserHandle of the profile that generated the change.
         */
        abstract public void onPackageRemoved(String packageName, UserHandle user);

        /**
         * Indicates that a package was added to the specified profile.
         *
         * If a package is added while being updated then onPackageChanged will be
         * called instead.
         *
         * @param packageName The name of the package that was added.
         * @param user The UserHandle of the profile that generated the change.
         */
        abstract public void onPackageAdded(String packageName, UserHandle user);

        /**
         * Indicates that a package was modified in the specified profile.
         * This can happen, for example, when the package is updated or when
         * one or more components are enabled or disabled.
         *
         * @param packageName The name of the package that has changed.
         * @param user The UserHandle of the profile that generated the change.
         */
        abstract public void onPackageChanged(String packageName, UserHandle user);

        /**
         * Indicates that one or more packages have become available. For
         * example, this can happen when a removable storage card has
         * reappeared.
         *
         * @param packageNames The names of the packages that have become
         *            available.
         * @param user The UserHandle of the profile that generated the change.
         * @param replacing Indicates whether these packages are replacing
         *            existing ones.
         */
        abstract public void onPackagesAvailable(String[] packageNames, UserHandle user,
                boolean replacing);

        /**
         * Indicates that one or more packages have become unavailable. For
         * example, this can happen when a removable storage card has been
         * removed.
         *
         * @param packageNames The names of the packages that have become
         *            unavailable.
         * @param user The UserHandle of the profile that generated the change.
         * @param replacing Indicates whether the packages are about to be
         *            replaced with new versions.
         */
        abstract public void onPackagesUnavailable(String[] packageNames, UserHandle user,
                boolean replacing);

        /**
         * Indicates that one or more packages have been suspended. For
         * example, this can happen when a Device Administrator suspends
         * an applicaton.
         *
         * <p>Note: On devices running {@link android.os.Build.VERSION_CODES#P Android P} or higher,
         * any apps that override {@link #onPackagesSuspended(String[], UserHandle, Bundle)} will
         * not receive this callback.
         *
         * @param packageNames The names of the packages that have just been
         *            suspended.
         * @param user The UserHandle of the profile that generated the change.
         */
        public void onPackagesSuspended(String[] packageNames, UserHandle user) {
        }

        /**
         * Indicates that one or more packages have been suspended. A device administrator or an app
         * with {@code android.permission.SUSPEND_APPS} can do this.
         *
         * <p>A suspending app with the permission {@code android.permission.SUSPEND_APPS} can
         * optionally provide a {@link Bundle} of extra information that it deems helpful for the
         * launcher to handle the suspended state of these packages. The contents of this
         * {@link Bundle} are supposed to be a contract between the suspending app and the launcher.
         *
         * @param packageNames The names of the packages that have just been suspended.
         * @param user the user for which the given packages were suspended.
         * @param launcherExtras A {@link Bundle} of extras for the launcher, if provided to the
         *                      system, {@code null} otherwise.
         * @see PackageManager#isPackageSuspended()
         * @see #getSuspendedPackageLauncherExtras(String, UserHandle)
         * @deprecated {@code launcherExtras} should be obtained by using
         * {@link #getSuspendedPackageLauncherExtras(String, UserHandle)}. For all other cases,
         * {@link #onPackagesSuspended(String[], UserHandle)} should be used.
         */
        @Deprecated
        public void onPackagesSuspended(String[] packageNames, UserHandle user,
                @Nullable Bundle launcherExtras) {
            onPackagesSuspended(packageNames, user);
        }

        /**
         * Indicates that one or more packages have been unsuspended. For
         * example, this can happen when a Device Administrator unsuspends
         * an applicaton.
         *
         * @param packageNames The names of the packages that have just been
         *            unsuspended.
         * @param user The UserHandle of the profile that generated the change.
         */
        public void onPackagesUnsuspended(String[] packageNames, UserHandle user) {
        }

        /**
         * Indicates that one or more shortcuts of any kind (dynamic, pinned, or manifest)
         * have been added, updated or removed.
         *
         * <p>Only the applications that are allowed to access the shortcut information,
         * as defined in {@link #hasShortcutHostPermission()}, will receive it.
         *
         * @param packageName The name of the package that has the shortcuts.
         * @param shortcuts All shortcuts from the package (dynamic, manifest and/or pinned).
         *    Only "key" information will be provided, as defined in
         *    {@link ShortcutInfo#hasKeyFieldsOnly()}.
         * @param user The UserHandle of the profile that generated the change.
         *
         * @see ShortcutManager
         */
        public void onShortcutsChanged(@NonNull String packageName,
                @NonNull List<ShortcutInfo> shortcuts, @NonNull UserHandle user) {
        }

        /**
         * Indicates that the loading progress of an installed package has changed.
         *
         * @param packageName The name of the package that has changed.
         * @param user The UserHandle of the profile that generated the change.
         * @param progress The new progress value, between [0, 1].
         */
        public void onPackageLoadingProgressChanged(@NonNull String packageName,
                @NonNull UserHandle user, float progress) {}
    }

    /**
     * Represents a query passed to {@link #getShortcuts(ShortcutQuery, UserHandle)}.
     */
    public static class ShortcutQuery {
        /**
         * Include dynamic shortcuts in the result.
         */
        public static final int FLAG_MATCH_DYNAMIC = 1 << 0;

        /** @hide kept for unit tests */
        @Deprecated
        public static final int FLAG_GET_DYNAMIC = FLAG_MATCH_DYNAMIC;

        /**
         * Include pinned shortcuts in the result.
         *
         * <p>If you are the selected assistant app, and wishes to fetch all shortcuts that the
         * user owns on the launcher (or by other launchers, in case the user has multiple), use
         * {@link #FLAG_MATCH_PINNED_BY_ANY_LAUNCHER} instead.
         *
         * <p>If you're a regular launcher app, there's no way to get shortcuts pinned by other
         * launchers, and {@link #FLAG_MATCH_PINNED_BY_ANY_LAUNCHER} will be ignored. So use this
         * flag to get own pinned shortcuts.
         */
        public static final int FLAG_MATCH_PINNED = 1 << 1;

        /** @hide kept for unit tests */
        @Deprecated
        public static final int FLAG_GET_PINNED = FLAG_MATCH_PINNED;

        /**
         * Include manifest shortcuts in the result.
         */
        public static final int FLAG_MATCH_MANIFEST = 1 << 3;

        /**
         * Include cached shortcuts in the result.
         */
        public static final int FLAG_MATCH_CACHED = 1 << 4;

        /** @hide kept for unit tests */
        @Deprecated
        public static final int FLAG_GET_MANIFEST = FLAG_MATCH_MANIFEST;

        /**
         * Include all pinned shortcuts by any launchers, not just by the caller,
         * in the result.
         *
         * <p>The caller must be the selected assistant app to use this flag, or have the system
         * {@code ACCESS_SHORTCUTS} permission.
         *
         * <p>If you are the selected assistant app, and wishes to fetch all shortcuts that the
         * user owns on the launcher (or by other launchers, in case the user has multiple), use
         * {@link #FLAG_MATCH_PINNED_BY_ANY_LAUNCHER} instead.
         *
         * <p>If you're a regular launcher app (or any app that's not the selected assistant app)
         * then this flag will be ignored.
         */
        public static final int FLAG_MATCH_PINNED_BY_ANY_LAUNCHER = 1 << 10;

        /**
         * FLAG_MATCH_DYNAMIC | FLAG_MATCH_PINNED | FLAG_MATCH_MANIFEST | FLAG_MATCH_CACHED
         * @hide
         */
        public static final int FLAG_MATCH_ALL_KINDS =
                FLAG_MATCH_DYNAMIC | FLAG_MATCH_PINNED | FLAG_MATCH_MANIFEST | FLAG_MATCH_CACHED;

        /**
         * FLAG_MATCH_DYNAMIC | FLAG_MATCH_PINNED | FLAG_MATCH_MANIFEST | FLAG_MATCH_ALL_PINNED
         * @hide
         */
        public static final int FLAG_MATCH_ALL_KINDS_WITH_ALL_PINNED =
                FLAG_MATCH_ALL_KINDS | FLAG_MATCH_PINNED_BY_ANY_LAUNCHER;

        /** @hide kept for unit tests */
        @Deprecated
        public static final int FLAG_GET_ALL_KINDS = FLAG_MATCH_ALL_KINDS;

        /**
         * Requests "key" fields only.  See {@link ShortcutInfo#hasKeyFieldsOnly()}'s javadoc to
         * see which fields fields "key".
         * This allows quicker access to shortcut information in order to
         * determine whether the caller's in-memory cache needs to be updated.
         *
         * <p>Typically, launcher applications cache all or most shortcut information
         * in memory in order to show shortcuts without a delay.
         *
         * When a given launcher application wants to update its cache, such as when its process
         * restarts, it can fetch shortcut information with this flag.
         * The application can then check {@link ShortcutInfo#getLastChangedTimestamp()} for each
         * shortcut, fetching a shortcut's non-key information only if that shortcut has been
         * updated.
         *
         * @see ShortcutManager
         */
        public static final int FLAG_GET_KEY_FIELDS_ONLY = 1 << 2;

        /**
         * Includes shortcuts from persistence layer in the search result.
         *
         * <p>The caller should make the query on a worker thread since accessing persistence layer
         * is considered asynchronous.
         *
         * @hide
         */
        @SystemApi
        public static final int FLAG_GET_PERSISTED_DATA = 1 << 12;

        /**
         * Populate the persons field in the result. See {@link ShortcutInfo#getPersons()}.
         *
         * <p>The caller must have the system {@code ACCESS_SHORTCUTS} permission.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.ACCESS_SHORTCUTS)
        public static final int FLAG_GET_PERSONS_DATA = 1 << 11;

        /** @hide */
        @IntDef(flag = true, prefix = { "FLAG_" }, value = {
                FLAG_MATCH_DYNAMIC,
                FLAG_MATCH_PINNED,
                FLAG_MATCH_MANIFEST,
                FLAG_MATCH_CACHED,
                FLAG_MATCH_PINNED_BY_ANY_LAUNCHER,
                FLAG_GET_KEY_FIELDS_ONLY,
                FLAG_GET_PERSONS_DATA,
                FLAG_GET_PERSISTED_DATA
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface QueryFlags {}

        long mChangedSince;

        @Nullable
        String mPackage;

        @Nullable
        List<String> mShortcutIds;

        @Nullable
        List<LocusId> mLocusIds;

        @Nullable
        ComponentName mActivity;

        @QueryFlags
        int mQueryFlags;

        public ShortcutQuery() {
        }

        /**
         * If non-zero, returns only shortcuts that have been added or updated
         * since the given timestamp, expressed in milliseconds since the Epoch&mdash;see
         * {@link System#currentTimeMillis()}.
         */
        public ShortcutQuery setChangedSince(long changedSince) {
            mChangedSince = changedSince;
            return this;
        }

        /**
         * If non-null, returns only shortcuts from the package.
         */
        public ShortcutQuery setPackage(@Nullable String packageName) {
            mPackage = packageName;
            return this;
        }

        /**
         * If non-null, return only the specified shortcuts by ID.  When setting this field,
         * a package name must also be set with {@link #setPackage}.
         */
        public ShortcutQuery setShortcutIds(@Nullable List<String> shortcutIds) {
            mShortcutIds = shortcutIds;
            return this;
        }

        /**
         * If non-null, return only the specified shortcuts by locus ID.  When setting this field,
         * a package name must also be set with {@link #setPackage}.
         */
        @NonNull
        public ShortcutQuery setLocusIds(@Nullable List<LocusId> locusIds) {
            mLocusIds = locusIds;
            return this;
        }

        /**
         * If non-null, returns only shortcuts associated with the activity; i.e.
         * {@link ShortcutInfo}s whose {@link ShortcutInfo#getActivity()} are equal
         * to {@code activity}.
         */
        public ShortcutQuery setActivity(@Nullable ComponentName activity) {
            mActivity = activity;
            return this;
        }

        /**
         * Set query options.  At least one of the {@code MATCH} flags should be set.  Otherwise,
         * no shortcuts will be returned.
         *
         * <ul>
         *     <li>{@link #FLAG_MATCH_DYNAMIC}
         *     <li>{@link #FLAG_MATCH_PINNED}
         *     <li>{@link #FLAG_MATCH_MANIFEST}
         *     <li>{@link #FLAG_MATCH_CACHED}
         *     <li>{@link #FLAG_GET_KEY_FIELDS_ONLY}
         * </ul>
         */
        public ShortcutQuery setQueryFlags(@QueryFlags int queryFlags) {
            mQueryFlags = queryFlags;
            return this;
        }
    }

    /**
     * Callbacks for shortcut changes to this and related managed profiles.
     *
     * @hide
     */
    public interface ShortcutChangeCallback {
        /**
         * Indicates that one or more shortcuts, that match the {@link ShortcutQuery} used to
         * register this callback, have been added or updated.
         * @see LauncherApps#registerShortcutChangeCallback(ShortcutChangeCallback, ShortcutQuery,
         * Executor)
         *
         * <p>Only the applications that are allowed to access the shortcut information,
         * as defined in {@link #hasShortcutHostPermission()}, will receive it.
         *
         * @param packageName The name of the package that has the shortcuts.
         * @param shortcuts Shortcuts from the package that have updated or added. Only "key"
         *    information will be provided, as defined in {@link ShortcutInfo#hasKeyFieldsOnly()}.
         * @param user The UserHandle of the profile that generated the change.
         *
         * @see ShortcutManager
         */
        default void onShortcutsAddedOrUpdated(@NonNull String packageName,
                @NonNull List<ShortcutInfo> shortcuts, @NonNull UserHandle user) {}

        /**
         * Indicates that one or more shortcuts, that match the {@link ShortcutQuery} used to
         * register this callback, have been removed.
         * @see LauncherApps#registerShortcutChangeCallback(ShortcutChangeCallback, ShortcutQuery,
         * Executor)
         *
         * <p>Only the applications that are allowed to access the shortcut information,
         * as defined in {@link #hasShortcutHostPermission()}, will receive it.
         *
         * @param packageName The name of the package that has the shortcuts.
         * @param shortcuts Shortcuts from the package that have been removed. Only "key"
         *    information will be provided, as defined in {@link ShortcutInfo#hasKeyFieldsOnly()}.
         * @param user The UserHandle of the profile that generated the change.
         *
         * @see ShortcutManager
         */
        default void onShortcutsRemoved(@NonNull String packageName,
                @NonNull List<ShortcutInfo> shortcuts, @NonNull UserHandle user) {}
    }

    /**
     * Callback proxy class for {@link ShortcutChangeCallback}
     *
     * @hide
     */
    private static class ShortcutChangeCallbackProxy extends
            android.content.pm.IShortcutChangeCallback.Stub {
        private final WeakReference<Pair<Executor, ShortcutChangeCallback>> mRemoteReferences;

        ShortcutChangeCallbackProxy(Executor executor, ShortcutChangeCallback callback) {
            mRemoteReferences = new WeakReference<>(new Pair<>(executor, callback));
        }

        @Override
        public void onShortcutsAddedOrUpdated(@NonNull String packageName,
                @NonNull List<ShortcutInfo> shortcuts, @NonNull UserHandle user) {
            Pair<Executor, ShortcutChangeCallback> remoteReferences = mRemoteReferences.get();
            if (remoteReferences == null) {
                // Binder is dead.
                return;
            }

            final Executor executor = remoteReferences.first;
            final ShortcutChangeCallback callback = remoteReferences.second;
            executor.execute(
                    PooledLambda.obtainRunnable(ShortcutChangeCallback::onShortcutsAddedOrUpdated,
                            callback, packageName, shortcuts, user).recycleOnUse());
        }

        @Override
        public void onShortcutsRemoved(@NonNull String packageName,
                @NonNull List<ShortcutInfo> shortcuts, @NonNull UserHandle user) {
            Pair<Executor, ShortcutChangeCallback> remoteReferences = mRemoteReferences.get();
            if (remoteReferences == null) {
                // Binder is dead.
                return;
            }

            final Executor executor = remoteReferences.first;
            final ShortcutChangeCallback callback = remoteReferences.second;
            executor.execute(
                    PooledLambda.obtainRunnable(ShortcutChangeCallback::onShortcutsRemoved,
                            callback, packageName, shortcuts, user).recycleOnUse());
        }
    }

    /** @hide */
    public LauncherApps(Context context, ILauncherApps service) {
        mContext = context;
        mService = service;
        mPm = context.getPackageManager();
        mUserManager = context.getSystemService(UserManager.class);
    }

    /** @hide */
    @TestApi
    public LauncherApps(Context context) {
        this(context, ILauncherApps.Stub.asInterface(
                ServiceManager.getService(Context.LAUNCHER_APPS_SERVICE)));
    }

    /**
     * Show an error log on logcat, when the calling user is a managed profile, the target
     * user is different from the calling user, and it is not called from a package that has the
     * {@link permission.INTERACT_ACROSS_USERS_FULL} permission, in order to help
     * developers to detect it.
     */
    private void logErrorForInvalidProfileAccess(@NonNull UserHandle target) {
        if (UserHandle.myUserId() != target.getIdentifier() && mUserManager.isManagedProfile()
                    && mContext.checkSelfPermission(permission.INTERACT_ACROSS_USERS_FULL)
                            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Accessing other profiles/users from managed profile is no longer allowed.");
        }
    }

    /**
     * Return a list of profiles that the caller can access via the {@link LauncherApps} APIs.
     *
     * <p>If the caller is running on a managed profile, it'll return only the current profile.
     * Otherwise it'll return the same list as {@link UserManager#getUserProfiles()} would.
     */
    public List<UserHandle> getProfiles() {
        if (mUserManager.isManagedProfile()
                || (android.multiuser.Flags.enableLauncherAppsHiddenProfileChecks()
                    && android.os.Flags.allowPrivateProfile()
                    && android.multiuser.Flags.enablePrivateSpaceFeatures()
                    && mUserManager.isPrivateProfile())) {
            // If it's a managed or private profile, only return the current profile.
            final List result = new ArrayList(1);
            result.add(android.os.Process.myUserHandle());
            return result;
        } else {
            if (android.multiuser.Flags.enableLauncherAppsHiddenProfileChecks()) {
                try {
                    return mService.getUserProfiles();
                } catch (RemoteException re) {
                    throw re.rethrowFromSystemServer();
                }
            }

            return mUserManager.getUserProfiles();
        }
    }

    /**
     * Retrieves a list of activities that specify {@link Intent#ACTION_MAIN} and
     * {@link Intent#CATEGORY_LAUNCHER}, across all apps, for a specified user. If an app doesn't
     * have any activities that specify <code>ACTION_MAIN</code> or <code>CATEGORY_LAUNCHER</code>,
     * the system adds a synthesized activity to the list. This synthesized activity represents the
     * app's details page within system settings.
     *
     * <p class="note"><b>Note: </b>It's possible for system apps, such as app stores, to prevent
     * the system from adding synthesized activities to the returned list.</p>
     *
     * <p>As of <a href="/reference/android/os/Build.VERSION_CODES.html#Q">Android Q</a>, at least
     * one of the app's activities or synthesized activities appears in the returned list unless the
     * app satisfies at least one of the following conditions:</p>
     * <ul>
     * <li>The app is a system app.</li>
     * <li>The app doesn't request any <a href="/guide/topics/permissions/overview">permissions</a>.
     * </li>
     * <li>The app doesn't have a <em>launcher activity</em> that is enabled by default. A launcher
     * activity has an intent containing the <code>ACTION_MAIN</code> action and the
     * <code>CATEGORY_LAUNCHER</code> category.</li>
     * </ul>
     *
     * <p>Additionally, the system hides synthesized activities for some or all apps in the
     * following enterprise-related cases:</p>
     * <ul>
     * <li>If the device is a
     * <a href="https://developers.google.com/android/work/overview#company-owned-devices-for-knowledge-workers">fully
     * managed device</a>, no synthesized activities for any app appear in the returned list.</li>
     * <li>If the current user has a
     * <a href="https://developers.google.com/android/work/overview#employee-owned-devices-byod">work
     * profile</a>, no synthesized activities for the user's work apps appear in the returned
     * list.</li>
     * </ul>
     *
     * @param packageName The specific package to query. If null, it checks all installed packages
     *            in the profile.
     * @param user The UserHandle of the profile.
     * @return List of launchable activities. Can be an empty list but will not be null.
     */
    public List<LauncherActivityInfo> getActivityList(String packageName, UserHandle user) {
        logErrorForInvalidProfileAccess(user);
        try {
            return convertToActivityList(mService.getLauncherActivities(mContext.getPackageName(),
                    packageName, user), user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a mutable PendingIntent that would start the same activity started from
     * {@link #startMainActivity(ComponentName, UserHandle, Rect, Bundle)}.  The caller needs to
     * take care in ensuring that the mutable intent returned is not passed to untrusted parties.
     *
     * @param component The ComponentName of the activity to launch
     * @param startActivityOptions This parameter is no longer supported
     * @param user The UserHandle of the profile
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.START_TASKS_FROM_RECENTS)
    @Nullable
    public PendingIntent getMainActivityLaunchIntent(@NonNull ComponentName component,
            @Nullable Bundle startActivityOptions, @NonNull UserHandle user) {
        logErrorForInvalidProfileAccess(user);
        if (DEBUG) {
            Log.i(TAG, "GetMainActivityLaunchIntent " + component + " " + user);
        }
        try {
            return mService.getActivityLaunchIntent(mContext.getPackageName(), component, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns information related to a user which is useful for displaying UI elements
     * to distinguish it from other users (eg, badges).
     *
     * <p>If the user in question is a hidden profile like
     * {@link UserManager.USER_TYPE_PROFILE_PRIVATE}, caller should have
     * {@link android.app.role.RoleManager.ROLE_HOME} and either of the permissions required.
     *
     * @param userHandle user handle of the user for which LauncherUserInfo is requested.
     * @return the {@link LauncherUserInfo} object related to the user specified, null in case
     * the user is inaccessible.
     */
    @Nullable
    @FlaggedApi(Flags.FLAG_ALLOW_PRIVATE_PROFILE)
    @RequiresPermission(conditional = true,
            anyOf = {ACCESS_HIDDEN_PROFILES_FULL, ACCESS_HIDDEN_PROFILES})
    public final LauncherUserInfo getLauncherUserInfo(@NonNull UserHandle userHandle) {
        if (DEBUG) {
            Log.i(TAG, "getLauncherUserInfo " + userHandle);
        }
        try {
            return mService.getLauncherUserInfo(userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }


    /**
     * Returns an intent sender which can be used to start the App Market activity (Installer
     * Activity).
     * This method is primarily used to get an intent sender which starts App Market activity for
     * another profile, if the caller is not otherwise allowed to start activity in that profile.
     *
     * <p>When packageName is set, intent sender to start the App Market Activity which installed
     * the package in calling user will be returned, but for the profile passed.
     *
     * <p>When packageName is not set, intent sender to launch the default App Market Activity for
     * the profile will be returned. In case there are multiple App Market Activities available for
     * the profile, IntentPicker will be started, allowing user to choose the preferred activity.
     *
     * <p>The method will fall back to the behaviour of not having the packageName set, in case:
     * <ul>
     *     <li>No activity for the packageName is found in calling user-space.</li>
     *     <li>The App Market Activity which installed the package in calling user-space is not
     *         present.</li>
     *     <li>The App Market Activity which installed the package in calling user-space is not
     *         present in the profile passed.</li>
     * </ul>
     * </p>
     *
     * <p>If the user in question is a hidden profile
     * {@link UserManager.USER_TYPE_PROFILE_PRIVATE}, caller should have
     * {@link android.app.role.RoleManager.ROLE_HOME} and either of the permissions required.
     *
     * @param packageName the package for which intent sender to launch App Market Activity is
     *                    required.
     * @param user the profile for which intent sender to launch App Market Activity is required.
     * @return {@link IntentSender} object which launches the App Market Activity, null in case
     *         there is no such activity.
     */
    @Nullable
    @FlaggedApi(Flags.FLAG_ALLOW_PRIVATE_PROFILE)
    @RequiresPermission(conditional = true,
            anyOf = {ACCESS_HIDDEN_PROFILES_FULL, ACCESS_HIDDEN_PROFILES})
    public IntentSender getAppMarketActivityIntent(@Nullable String packageName,
            @NonNull UserHandle user) {
        if (DEBUG) {
            Log.i(TAG, "getAppMarketActivityIntent for package: " + packageName
                    + " user: " + user);
        }
        try {
            return mService.getAppMarketActivityIntent(mContext.getPackageName(),
                    packageName, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of the system packages that are installed at user creation.
     *
     * <p>An empty list denotes that all system packages should be treated as pre-installed for that
     * user at creation.
     *
     * <p>If the user in question is a hidden profile like
     * {@link UserManager.USER_TYPE_PROFILE_PRIVATE}, caller should have
     * {@link android.app.role.RoleManager.ROLE_HOME} and either of the permissions required.
     *
     * @param userHandle the user for which installed system packages are required.
     * @return {@link List} of {@link String}, representing the package name of the installed
     *        package. Can be empty but not null.
     */
    @FlaggedApi(Flags.FLAG_ALLOW_PRIVATE_PROFILE)
    @NonNull
    @RequiresPermission(conditional = true,
            anyOf = {ACCESS_HIDDEN_PROFILES_FULL, ACCESS_HIDDEN_PROFILES})
    public List<String> getPreInstalledSystemPackages(@NonNull UserHandle userHandle) {
        if (DEBUG) {
            Log.i(TAG, "getPreInstalledSystemPackages for user: " + userHandle);
        }
        try {
            return mService.getPreInstalledSystemPackages(userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@link IntentSender} which can be used to start the Private Space Settings Activity.
     *
     * <p> Caller should have {@link android.app.role.RoleManager.ROLE_HOME} and either of the
     * permissions required.</p>
     *
     * @return {@link IntentSender} object which launches the Private Space Settings Activity, if
     * successful, null otherwise.
     * @hide
     */
    @Nullable
    @FlaggedApi(Flags.FLAG_ALLOW_PRIVATE_PROFILE)
    @RequiresPermission(conditional = true,
            anyOf = {ACCESS_HIDDEN_PROFILES_FULL, ACCESS_HIDDEN_PROFILES})
    public IntentSender getPrivateSpaceSettingsIntent() {
        try {
            return mService.getPrivateSpaceSettingsIntent();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the activity info for a given intent and user handle, if it resolves. Otherwise it
     * returns null.
     *
     * @param intent The intent to find a match for.
     * @param user The profile to look in for a match.
     * @return An activity info object if there is a match.
     */
    public LauncherActivityInfo resolveActivity(Intent intent, UserHandle user) {
        logErrorForInvalidProfileAccess(user);
        try {
            LauncherActivityInfoInternal ai = mService.resolveLauncherActivityInternal(
                    mContext.getPackageName(), intent.getComponent(), user);
            if (ai == null) {
                return null;
            }
            return new LauncherActivityInfo(mContext, ai);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns overrides for the activities that should be launched for the shortcuts of certain
     * package names.
     *
     * @return {@link Map} whose keys are package names and whose values are the
     * {@link LauncherActivityInfo}s that should be used for those packages' shortcuts. If there are
     * no activity overrides, an empty {@link Map} will be returned.
     *
     * @hide
     */
    @NonNull
    public Map<String, LauncherActivityInfo> getActivityOverrides() {
        Map<String, LauncherActivityInfo> activityOverrides = new ArrayMap<>();
        try {
            Map<String, LauncherActivityInfoInternal> activityOverridesInternal =
                    mService.getActivityOverrides(mContext.getPackageName(), mContext.getUserId());
            for (Map.Entry<String, LauncherActivityInfoInternal> packageToOverride :
                    activityOverridesInternal.entrySet()) {
                activityOverrides.put(
                        packageToOverride.getKey(),
                        new LauncherActivityInfo(
                                mContext,
                                packageToOverride.getValue()
                        )
                );
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
        return activityOverrides;
    }

    /**
     * Starts a Main activity in the specified profile.
     *
     * @param component The ComponentName of the activity to launch
     * @param user The UserHandle of the profile
     * @param sourceBounds The Rect containing the source bounds of the clicked icon
     * @param opts Options to pass to startActivity
     */
    public void startMainActivity(ComponentName component, UserHandle user, Rect sourceBounds,
            Bundle opts) {
        logErrorForInvalidProfileAccess(user);
        if (DEBUG) {
            Log.i(TAG, "StartMainActivity " + component + " " + user.getIdentifier());
        }
        try {
            mService.startActivityAsUser(mContext.getIApplicationThread(),
                    mContext.getPackageName(), mContext.getAttributionTag(),
                    component, sourceBounds, opts, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Starts an activity to show the details of the specified session.
     *
     * @param sessionInfo The SessionInfo of the session
     * @param sourceBounds The Rect containing the source bounds of the clicked icon
     * @param opts Options to pass to startActivity
     */
    public void startPackageInstallerSessionDetailsActivity(@NonNull SessionInfo sessionInfo,
            @Nullable Rect sourceBounds, @Nullable Bundle opts) {
        try {
            mService.startSessionDetailsActivityAsUser(mContext.getIApplicationThread(),
                    mContext.getPackageName(), mContext.getAttributionTag(), sessionInfo,
                    sourceBounds, opts, sessionInfo.getUser());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Starts the settings activity to show the application details for a
     * package in the specified profile.
     *
     * @param component The ComponentName of the package to launch settings for.
     * @param user The UserHandle of the profile
     * @param sourceBounds The Rect containing the source bounds of the clicked icon
     * @param opts Options to pass to startActivity
     */
    public void startAppDetailsActivity(ComponentName component, UserHandle user,
            Rect sourceBounds, Bundle opts) {
        logErrorForInvalidProfileAccess(user);
        try {
            mService.showAppDetailsAsUser(mContext.getIApplicationThread(),
                    mContext.getPackageName(), mContext.getAttributionTag(),
                    component, sourceBounds, opts, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns PendingIntent associated with specified shortcut.
     *
     * @param packageName The packageName of the shortcut
     * @param shortcutId The id of the shortcut
     * @param opts This parameter is no longer supported
     * @param user The UserHandle of the profile
     */
    @Nullable
    public PendingIntent getShortcutIntent(@NonNull final String packageName,
            @NonNull final String shortcutId, @Nullable final Bundle opts,
            @NonNull final UserHandle user) {
        logErrorForInvalidProfileAccess(user);
        if (DEBUG) {
            Log.i(TAG, "GetShortcutIntent " + packageName + "/" + shortcutId + " " + user);
        }
        try {
            // due to b/209607104, opts will be ignored
            return mService.getShortcutIntent(
                    mContext.getPackageName(), packageName, shortcutId, null /* opts */, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves a list of config activities for creating {@link ShortcutInfo}.
     *
     * @param packageName The specific package to query. If null, it checks all installed packages
     *            in the profile.
     * @param user The UserHandle of the profile.
     * @return List of config activities. Can be an empty list but will not be null.
     *
     * @see Intent#ACTION_CREATE_SHORTCUT
     * @see #getShortcutConfigActivityIntent(LauncherActivityInfo)
     */
    public List<LauncherActivityInfo> getShortcutConfigActivityList(@Nullable String packageName,
            @NonNull UserHandle user) {
        logErrorForInvalidProfileAccess(user);
        try {
            return convertToActivityList(mService.getShortcutConfigActivities(
                    mContext.getPackageName(), packageName, user),
                    user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    private List<LauncherActivityInfo> convertToActivityList(
            @Nullable ParceledListSlice<LauncherActivityInfoInternal> internals, UserHandle user) {
        if (internals == null || internals.getList().isEmpty()) {
            return Collections.EMPTY_LIST;
        }
        ArrayList<LauncherActivityInfo> lais = new ArrayList<>();
        for (LauncherActivityInfoInternal internal : internals.getList()) {
            LauncherActivityInfo lai = new LauncherActivityInfo(mContext, internal);
            if (DEBUG) {
                Log.v(TAG, "Returning activity for profile " + user + " : "
                        + lai.getComponentName());
            }
            lais.add(lai);
        }
        return lais;
    }

    /**
     * Returns an intent sender which can be used to start the configure activity for creating
     * custom shortcuts. Use this method if the provider is in another profile as you are not
     * allowed to start an activity in another profile.
     *
     * <p>The caller should receive {@link PinItemRequest} in onActivityResult on
     * {@link android.app.Activity#RESULT_OK}.
     *
     * <p>Callers must be allowed to access the shortcut information, as defined in {@link
     * #hasShortcutHostPermission()}.
     *
     * @param info a configuration activity returned by {@link #getShortcutConfigActivityList}
     *
     * @throws IllegalStateException when the user is locked or not running.
     * @throws SecurityException if {@link #hasShortcutHostPermission()} is false.
     *
     * @see #getPinItemRequest(Intent)
     * @see Intent#ACTION_CREATE_SHORTCUT
     * @see android.app.Activity#startIntentSenderForResult
     */
    @Nullable
    public IntentSender getShortcutConfigActivityIntent(@NonNull LauncherActivityInfo info) {
        try {
            return mService.getShortcutConfigActivityIntent(
                    mContext.getPackageName(), info.getComponentName(), info.getUser());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the package is installed and enabled for a profile.
     *
     * @param packageName The package to check.
     * @param user The UserHandle of the profile.
     *
     * @return true if the package exists and is enabled.
     */
    public boolean isPackageEnabled(String packageName, UserHandle user) {
        logErrorForInvalidProfileAccess(user);
        try {
            return mService.isPackageEnabled(mContext.getPackageName(), packageName, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the launcher extras supplied to the system when the given package was suspended via
     * {@code PackageManager#setPackagesSuspended(String[], boolean, PersistableBundle,
     * PersistableBundle, String)}.
     *
     * <p>The contents of this {@link Bundle} are supposed to be a contract between the suspending
     * app and the launcher.
     *
     * <p>Note: This just returns whatever extras were provided to the system, <em>which might
     * even be {@code null}.</em>
     *
     * @param packageName The package for which to fetch the launcher extras.
     * @param user The {@link UserHandle} of the profile.
     * @return A {@link Bundle} of launcher extras. Or {@code null} if the package is not currently
     *         suspended.
     *
     * @see Callback#onPackagesSuspended(String[], UserHandle, Bundle)
     * @see PackageManager#isPackageSuspended()
     */
    public @Nullable Bundle getSuspendedPackageLauncherExtras(String packageName, UserHandle user) {
        logErrorForInvalidProfileAccess(user);
        try {
            return mService.getSuspendedPackageLauncherExtras(packageName, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether a package should be hidden from suggestions to the user. Currently, this
     * could be done because the package was marked as distracting to the user via
     * {@code PackageManager.setDistractingPackageRestrictions(String[], int)}.
     *
     * @param packageName The package for which to check.
     * @param user the {@link UserHandle} of the profile.
     * @return
     */
    public boolean shouldHideFromSuggestions(@NonNull String packageName,
            @NonNull UserHandle user) {
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(user, "user");
        try {
            return mService.shouldHideFromSuggestions(packageName, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@link ApplicationInfo} about an application installed for a specific user profile.
     *
     * @param packageName The package name of the application
     * @param flags Additional option flags {@link PackageManager#getApplicationInfo}
     * @param user The UserHandle of the profile.
     *
     * @return {@link ApplicationInfo} containing information about the package. Returns
     *         {@code null} if the package isn't installed for the given profile, or the profile
     *         isn't enabled.
     */
    public ApplicationInfo getApplicationInfo(@NonNull String packageName,
            @ApplicationInfoFlagsBits int flags, @NonNull UserHandle user)
            throws PackageManager.NameNotFoundException {
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(user, "user");
        logErrorForInvalidProfileAccess(user);
        try {
            final ApplicationInfo ai = mService
                    .getApplicationInfo(mContext.getPackageName(), packageName, flags, user);
            if (ai == null) {
                throw new NameNotFoundException("Package " + packageName + " not found for user "
                        + user.getIdentifier());
            }
            return ai;
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns an object describing the app usage limit for the given package.
     * If there are multiple limits that apply to the package, the one with the smallest
     * time remaining will be returned.
     *
     * @param packageName name of the package whose app usage limit will be returned
     * @param user the user of the package
     *
     * @return an {@link AppUsageLimit} object describing the app time limit containing
     * the given package with the smallest time remaining, or {@code null} if none exist.
     * @throws SecurityException when the caller is not the recents app.
     * @hide
     */
    @Nullable
    @SystemApi
    public LauncherApps.AppUsageLimit getAppUsageLimit(@NonNull String packageName,
            @NonNull UserHandle user) {
        try {
            return mService.getAppUsageLimit(mContext.getPackageName(), packageName, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the activity exists and it enabled for a profile.
     *
     * <p>The activity may still not be exported, in which case {@link #startMainActivity} will
     * throw a {@link SecurityException} unless the caller has the same UID as the target app's.
     *
     * @param component The activity to check.
     * @param user The UserHandle of the profile.
     *
     * @return true if the activity exists and is enabled.
     */
    public boolean isActivityEnabled(ComponentName component, UserHandle user) {
        logErrorForInvalidProfileAccess(user);
        try {
            return mService.isActivityEnabled(mContext.getPackageName(), component, user);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the caller can access the shortcut information.  Access is currently
     * available to:
     *
     * <ul>
     *     <li>The current launcher (or default launcher if there is no set current launcher).</li>
     *     <li>The currently active voice interaction service.</li>
     * </ul>
     *
     * <p>Note when this method returns {@code false}, it may be a temporary situation because
     * the user is trying a new launcher application.  The user may decide to change the default
     * launcher back to the calling application again, so even if a launcher application loses
     * this permission, it does <b>not</b> have to purge pinned shortcut information.
     * If the calling launcher application contains pinned shortcuts, they will still work,
     * even though the caller no longer has the shortcut host permission.
     *
     * @throws IllegalStateException when the user is locked.
     *
     * @see ShortcutManager
     */
    public boolean hasShortcutHostPermission() {
        try {
            return mService.hasShortcutHostPermission(mContext.getPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    private List<ShortcutInfo> maybeUpdateDisabledMessage(List<ShortcutInfo> shortcuts) {
        if (shortcuts == null) {
            return null;
        }
        for (int i = shortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = shortcuts.get(i);
            final String message = ShortcutInfo.getDisabledReasonForRestoreIssue(mContext,
                    si.getDisabledReason());
            if (message != null) {
                si.setDisabledMessage(message);
            }
        }
        return shortcuts;
    }

    /**
     * Register a callback to be called right before the wmtrace data is moved to the bugreport.
     * @hide
     */
    @RequiresPermission(READ_FRAME_BUFFER)
    public void registerDumpCallback(IDumpCallback cb) {
        try {
            mService.registerDumpCallback(cb);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Saves view capture data to the default location.
     * @hide
     */
    @RequiresPermission(READ_FRAME_BUFFER)
    public void saveViewCaptureData() {
        try {
            mService.saveViewCaptureData();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Unregister a callback, so that it won't be called when LauncherApps dumps.
     * @hide
     */
    @RequiresPermission(READ_FRAME_BUFFER)
    public void unRegisterDumpCallback(IDumpCallback cb) {
        try {
            mService.unRegisterDumpCallback(cb);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Returns {@link ShortcutInfo}s that match {@code query}.
     *
     * <p>Callers must be allowed to access the shortcut information, as defined in {@link
     * #hasShortcutHostPermission()}.
     *
     * @param query result includes shortcuts matching this query.
     * @param user The UserHandle of the profile.
     *
     * @return the IDs of {@link ShortcutInfo}s that match the query.
     * @throws IllegalStateException when the user is locked, or when the {@code user} user
     * is locked or not running.
     *
     * @see ShortcutManager
     */
    @Nullable
    public List<ShortcutInfo> getShortcuts(@NonNull ShortcutQuery query,
            @NonNull UserHandle user) {
        logErrorForInvalidProfileAccess(user);
        try {
            if ((query.mQueryFlags & ShortcutQuery.FLAG_GET_PERSISTED_DATA) != 0) {
                return getShortcutsBlocked(query, user);
            }
            // Note this is the only case we need to update the disabled message for shortcuts
            // that weren't restored.
            // The restore problem messages are only shown by the user, and publishers will never
            // see them. The only other API that the launcher gets shortcuts is the shortcut
            // changed callback, but that only returns shortcuts with the "key" information, so
            // that won't return disabled message.
            return maybeUpdateDisabledMessage(mService.getShortcuts(mContext.getPackageName(),
                                new ShortcutQueryWrapper(query), user)
                        .getList());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private List<ShortcutInfo> getShortcutsBlocked(@NonNull ShortcutQuery query,
            @NonNull UserHandle user) {
        logErrorForInvalidProfileAccess(user);
        final AndroidFuture<List<ShortcutInfo>> future = new AndroidFuture<>();
        future.thenApply(this::maybeUpdateDisabledMessage);
        try {
            mService.getShortcutsAsync(mContext.getPackageName(),
                            new ShortcutQueryWrapper(query), user, future);
            return future.get();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @hide // No longer used.  Use getShortcuts() instead.  Kept for unit tests.
     */
    @Nullable
    @Deprecated
    public List<ShortcutInfo> getShortcutInfo(@NonNull String packageName,
            @NonNull List<String> ids, @NonNull UserHandle user) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setPackage(packageName);
        q.setShortcutIds(ids);
        q.setQueryFlags(ShortcutQuery.FLAG_GET_ALL_KINDS);
        return getShortcuts(q, user);
    }

    /**
     * Pin shortcuts on a package.
     *
     * <p>This API is <b>NOT</b> cumulative; this will replace all pinned shortcuts for the package.
     * However, different launchers may have different set of pinned shortcuts.
     *
     * <p>The calling launcher application must be allowed to access the shortcut information,
     * as defined in {@link #hasShortcutHostPermission()}.
     *
     * @param packageName The target package name.
     * @param shortcutIds The IDs of the shortcut to be pinned.
     * @param user The UserHandle of the profile.
     * @throws IllegalStateException when the user is locked, or when the {@code user} user
     * is locked or not running.
     *
     * @see ShortcutManager
     */
    public void pinShortcuts(@NonNull String packageName, @NonNull List<String> shortcutIds,
            @NonNull UserHandle user) {
        logErrorForInvalidProfileAccess(user);
        try {
            mService.pinShortcuts(mContext.getPackageName(), packageName, shortcutIds, user);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Mark shortcuts as cached for a package.
     *
     * <p>Only dynamic long lived shortcuts can be cached. None dynamic or non long lived shortcuts
     * in the list will be ignored.
     *
     * <p>Unlike pinned shortcuts, where different callers can have different sets of pinned
     * shortcuts, cached state is per shortcut only, and even if multiple callers cache the same
     * shortcut, it can be uncached by any valid caller.
     *
     * @param packageName The target package name.
     * @param shortcutIds The IDs of the shortcut to be cached.
     * @param user The UserHandle of the profile.
     * @param cacheFlags One of the values in:
     * <ul>
     *     <li>{@link #FLAG_CACHE_NOTIFICATION_SHORTCUTS}
     *     <li>{@link #FLAG_CACHE_BUBBLE_SHORTCUTS}
     *     <li>{@link #FLAG_CACHE_PEOPLE_TILE_SHORTCUTS}
     * </ul>
     * @throws IllegalStateException when the user is locked, or when the {@code user} user
     * is locked or not running.
     *
     * @see ShortcutManager
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_SHORTCUTS)
    public void cacheShortcuts(@NonNull String packageName, @NonNull List<String> shortcutIds,
            @NonNull UserHandle user, @ShortcutCacheFlags int cacheFlags) {
        logErrorForInvalidProfileAccess(user);
        try {
            mService.cacheShortcuts(
                    mContext.getPackageName(), packageName, shortcutIds, user, cacheFlags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove cached flag from shortcuts for a package.
     *
     * @param packageName The target package name.
     * @param shortcutIds The IDs of the shortcut to be uncached.
     * @param user The UserHandle of the profile.
     * @param cacheFlags One of the values in:
     * <ul>
     *     <li>{@link #FLAG_CACHE_NOTIFICATION_SHORTCUTS}
     *     <li>{@link #FLAG_CACHE_BUBBLE_SHORTCUTS}
     *     <li>{@link #FLAG_CACHE_PEOPLE_TILE_SHORTCUTS}
     * </ul>
     * @throws IllegalStateException when the user is locked, or when the {@code user} user
     * is locked or not running.
     *
     * @see ShortcutManager
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_SHORTCUTS)
    public void uncacheShortcuts(@NonNull String packageName, @NonNull List<String> shortcutIds,
            @NonNull UserHandle user, @ShortcutCacheFlags int cacheFlags) {
        logErrorForInvalidProfileAccess(user);
        try {
            mService.uncacheShortcuts(
                    mContext.getPackageName(), packageName, shortcutIds, user, cacheFlags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide kept for testing.
     */
    @Deprecated
    public int getShortcutIconResId(@NonNull ShortcutInfo shortcut) {
        return shortcut.getIconResourceId();
    }

    /**
     * @hide kept for testing.
     */
    @Deprecated
    public int getShortcutIconResId(@NonNull String packageName, @NonNull String shortcutId,
            @NonNull UserHandle user) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setPackage(packageName);
        q.setShortcutIds(Arrays.asList(shortcutId));
        q.setQueryFlags(ShortcutQuery.FLAG_GET_ALL_KINDS);
        final List<ShortcutInfo> shortcuts = getShortcuts(q, user);

        return shortcuts.size() > 0 ? shortcuts.get(0).getIconResourceId() : 0;
    }

    /**
     * @hide internal/unit tests only
     */
    public ParcelFileDescriptor getShortcutIconFd(
            @NonNull ShortcutInfo shortcut) {
        return getShortcutIconFd(shortcut.getPackage(), shortcut.getId(),
                shortcut.getUserId());
    }

    /**
     * @hide internal/unit tests only
     */
    public ParcelFileDescriptor getShortcutIconFd(
            @NonNull String packageName, @NonNull String shortcutId, @NonNull UserHandle user) {
        return getShortcutIconFd(packageName, shortcutId, user.getIdentifier());
    }

    private ParcelFileDescriptor getShortcutIconFd(
            @NonNull String packageName, @NonNull String shortcutId, int userId) {
        try {
            return mService.getShortcutIconFd(mContext.getPackageName(),
                    packageName, shortcutId, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide internal/unit tests only
     */
    @VisibleForTesting
    public ParcelFileDescriptor getUriShortcutIconFd(@NonNull ShortcutInfo shortcut) {
        return getUriShortcutIconFd(shortcut.getPackage(), shortcut.getId(), shortcut.getUserId());
    }

    private ParcelFileDescriptor getUriShortcutIconFd(@NonNull String packageName,
            @NonNull String shortcutId, int userId) {
        String uri = getShortcutIconUri(packageName, shortcutId, userId);
        if (uri == null) {
            return null;
        }
        try {
            return mContext.getContentResolver().openFileDescriptor(Uri.parse(uri), "r");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open icon file: " + uri, e);
            return null;
        }
    }

    private String getShortcutIconUri(@NonNull String packageName,
            @NonNull String shortcutId, int userId) {
        String uri = null;
        try {
            uri = mService.getShortcutIconUri(mContext.getPackageName(), packageName, shortcutId,
                    userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return uri;
    }

    /**
     * Returns the icon for this shortcut, without any badging for the profile.
     *
     * <p>The calling launcher application must be allowed to access the shortcut information,
     * as defined in {@link #hasShortcutHostPermission()}.
     *
     * @param density The preferred density of the icon, zero for default density. Use
     * density DPI values from {@link DisplayMetrics}.
     *
     * @return The drawable associated with the shortcut.
     * @throws IllegalStateException when the user is locked, or when the {@code user} user
     * is locked or not running.
     *
     * @see ShortcutManager
     * @see #getShortcutBadgedIconDrawable(ShortcutInfo, int)
     * @see DisplayMetrics
     */
    public Drawable getShortcutIconDrawable(@NonNull ShortcutInfo shortcut, int density) {
        if (shortcut.hasIconFile()) {
            final ParcelFileDescriptor pfd = getShortcutIconFd(shortcut);
            return loadDrawableFromFileDescriptor(pfd, shortcut.hasAdaptiveBitmap());
        } else if (shortcut.hasIconUri()) {
            final ParcelFileDescriptor pfd = getUriShortcutIconFd(shortcut);
            return loadDrawableFromFileDescriptor(pfd, shortcut.hasAdaptiveBitmap());
        } else if (shortcut.hasIconResource()) {
            return loadDrawableResourceFromPackage(shortcut.getPackage(),
                    shortcut.getIconResourceId(), shortcut.getUserHandle(), density);
        } else if (shortcut.getIcon() != null) {
            // This happens if a shortcut is pending-approval.
            final Icon icon = shortcut.getIcon();
            switch (icon.getType()) {
                case Icon.TYPE_RESOURCE: {
                    return loadDrawableResourceFromPackage(shortcut.getPackage(),
                            icon.getResId(), shortcut.getUserHandle(), density);
                }
                case Icon.TYPE_BITMAP:
                case Icon.TYPE_ADAPTIVE_BITMAP: {
                    return icon.loadDrawable(mContext);
                }
                default:
                    return null; // Shouldn't happen though.
            }
        } else {
            return null; // Has no icon.
        }
    }

    private Drawable loadDrawableFromFileDescriptor(ParcelFileDescriptor pfd, boolean adaptive) {
        if (pfd == null) {
            return null;
        }
        try {
            final Bitmap bmp = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
            if (bmp != null) {
                BitmapDrawable dr = new BitmapDrawable(mContext.getResources(), bmp);
                if (adaptive) {
                    return new AdaptiveIconDrawable(null, dr);
                } else {
                    return dr;
                }
            }
            return null;
        } finally {
            try {
                pfd.close();
            } catch (IOException ignore) {
            }
        }
    }

    /**
     * @hide
     */
    public Icon getShortcutIcon(@NonNull ShortcutInfo shortcut) {
        if (shortcut.hasIconFile()) {
            final ParcelFileDescriptor pfd = getShortcutIconFd(shortcut);
            if (pfd == null) {
                return null;
            }
            try {
                final Bitmap bmp = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                if (bmp != null) {
                    if (shortcut.hasAdaptiveBitmap()) {
                        return Icon.createWithAdaptiveBitmap(bmp);
                    } else {
                        return Icon.createWithBitmap(bmp);
                    }
                }
                return null;
            } finally {
                try {
                    pfd.close();
                } catch (IOException ignore) {
                }
            }
        } else if (shortcut.hasIconUri()) {
            String uri = getShortcutIconUri(shortcut.getPackage(), shortcut.getId(),
                    shortcut.getUserId());
            if (uri == null) {
                return null;
            }
            if (shortcut.hasAdaptiveBitmap()) {
                return Icon.createWithAdaptiveBitmapContentUri(uri);
            } else {
                return Icon.createWithContentUri(uri);
            }
        } else if (shortcut.hasIconResource()) {
            return Icon.createWithResource(shortcut.getPackage(), shortcut.getIconResourceId());
        } else {
            return shortcut.getIcon();
        }
    }

    private Drawable loadDrawableResourceFromPackage(String packageName, int resId,
            UserHandle user, int density) {
        try {
            if (resId == 0) {
                return null; // Shouldn't happen but just in case.
            }
            final ApplicationInfo ai = getApplicationInfo(packageName, /* flags =*/ 0, user);
            final Resources res = mContext.getPackageManager().getResourcesForApplication(ai);
            return res.getDrawableForDensity(resId, density);
        } catch (NameNotFoundException | Resources.NotFoundException e) {
            return null;
        }
    }

    /**
     * Returns the shortcut icon with badging appropriate for the profile.
     *
     * <p>The calling launcher application must be allowed to access the shortcut information,
     * as defined in {@link #hasShortcutHostPermission()}.
     *
     * @param density Optional density for the icon, or 0 to use the default density. Use
     * @return A badged icon for the shortcut.
     * @throws IllegalStateException when the user is locked, or when the {@code user} user
     * is locked or not running.
     *
     * @see ShortcutManager
     * @see #getShortcutIconDrawable(ShortcutInfo, int)
     * @see DisplayMetrics
     */
    public Drawable getShortcutBadgedIconDrawable(ShortcutInfo shortcut, int density) {
        final Drawable originalIcon = getShortcutIconDrawable(shortcut, density);

        return (originalIcon == null) ? null : mContext.getPackageManager().getUserBadgedIcon(
                originalIcon, shortcut.getUserHandle());
    }

    /**
     * Starts a shortcut.
     *
     * <p>The calling launcher application must be allowed to access the shortcut information,
     * as defined in {@link #hasShortcutHostPermission()}.
     *
     * @param packageName The target shortcut package name.
     * @param shortcutId The target shortcut ID.
     * @param sourceBounds The Rect containing the source bounds of the clicked icon.
     * @param startActivityOptions Options to pass to startActivity.
     * @param user The UserHandle of the profile.
     * @throws IllegalStateException when the user is locked, or when the {@code user} user
     * is locked or not running.
     *
     * @throws android.content.ActivityNotFoundException failed to start shortcut. (e.g.
     * the shortcut no longer exists, is disabled, the intent receiver activity doesn't exist, etc)
     */
    public void startShortcut(@NonNull String packageName, @NonNull String shortcutId,
            @Nullable Rect sourceBounds, @Nullable Bundle startActivityOptions,
            @NonNull UserHandle user) {
        logErrorForInvalidProfileAccess(user);

        startShortcut(packageName, shortcutId, sourceBounds, startActivityOptions,
                user.getIdentifier());
    }

    /**
     * Launches a shortcut.
     *
     * <p>The calling launcher application must be allowed to access the shortcut information,
     * as defined in {@link #hasShortcutHostPermission()}.
     *
     * @param shortcut The target shortcut.
     * @param sourceBounds The Rect containing the source bounds of the clicked icon.
     * @param startActivityOptions Options to pass to startActivity.
     * @throws IllegalStateException when the user is locked, or when the {@code user} user
     * is locked or not running.
     *
     * @throws android.content.ActivityNotFoundException failed to start shortcut. (e.g.
     * the shortcut no longer exists, is disabled, the intent receiver activity doesn't exist, etc)
     */
    public void startShortcut(@NonNull ShortcutInfo shortcut,
            @Nullable Rect sourceBounds, @Nullable Bundle startActivityOptions) {
        startShortcut(shortcut.getPackage(), shortcut.getId(),
                sourceBounds, startActivityOptions,
                shortcut.getUserId());
    }

    @UnsupportedAppUsage
    private void startShortcut(@NonNull String packageName, @NonNull String shortcutId,
            @Nullable Rect sourceBounds, @Nullable Bundle startActivityOptions,
            int userId) {
        try {
            final boolean success = mService.startShortcut(mContext.getPackageName(), packageName,
                    null /* default featureId */, shortcutId, sourceBounds, startActivityOptions,
                    userId);
            if (!success) {
                throw new ActivityNotFoundException("Shortcut could not be started");
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a callback for changes to packages in this user and managed profiles.
     *
     * @param callback The callback to register.
     */
    public void registerCallback(Callback callback) {
        registerCallback(callback, null);
    }

    /**
     * Registers a callback for changes to packages in this user and managed profiles.
     *
     * @param callback The callback to register.
     * @param handler that should be used to post callbacks on, may be null.
     */
    public void registerCallback(Callback callback, Handler handler) {
        synchronized (this) {
            if (callback != null && findCallbackLocked(callback) < 0) {
                boolean addedFirstCallback = mCallbacks.size() == 0;
                addCallbackLocked(callback, handler);
                if (addedFirstCallback) {
                    try {
                        mService.addOnAppsChangedListener(mContext.getPackageName(),
                                mAppsChangedListener);
                    } catch (RemoteException re) {
                        throw re.rethrowFromSystemServer();
                    }
                }
            }
        }
    }

    /**
     * Unregisters a callback that was previously registered.
     *
     * @param callback The callback to unregister.
     * @see #registerCallback(Callback)
     */
    public void unregisterCallback(Callback callback) {
        synchronized (this) {
            removeCallbackLocked(callback);
            if (mCallbacks.size() == 0) {
                try {
                    mService.removeOnAppsChangedListener(mAppsChangedListener);
                } catch (RemoteException re) {
                    throw re.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Disable different archive compatibility options of the launcher for the caller of this
     * method.
     *
     * @see ArchiveCompatibilityParams for individual options.
     */
    @FlaggedApi(android.content.pm.Flags.FLAG_ARCHIVING)
    public void setArchiveCompatibility(@NonNull ArchiveCompatibilityParams params) {
        try {
            mService.setArchiveCompatibilityOptions(params.isEnableIconOverlay(),
                    params.isEnableUnarchivalConfirmation());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /** @return position in mCallbacks for callback or -1 if not present. */
    private int findCallbackLocked(Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        final int size = mCallbacks.size();
        for (int i = 0; i < size; ++i) {
            if (mCallbacks.get(i).mCallback == callback) {
                return i;
            }
        }
        return -1;
    }

    private void removeCallbackLocked(Callback callback) {
        int pos = findCallbackLocked(callback);
        if (pos >= 0) {
            mCallbacks.remove(pos);
        }
    }

    private void addCallbackLocked(Callback callback, Handler handler) {
        // Remove if already present.
        removeCallbackLocked(callback);
        if (handler == null) {
            handler = new Handler();
        }
        CallbackMessageHandler toAdd = new CallbackMessageHandler(handler.getLooper(), callback);
        mCallbacks.add(toAdd);
    }

    private final IOnAppsChangedListener.Stub mAppsChangedListener =
            new IOnAppsChangedListener.Stub() {

        @Override
        public void onPackageRemoved(UserHandle user, String packageName)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackageRemoved " + user.getIdentifier() + "," + packageName);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackageRemoved(packageName, user);
                }
            }
        }

        @Override
        public void onPackageChanged(UserHandle user, String packageName) throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackageChanged " + user.getIdentifier() + "," + packageName);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackageChanged(packageName, user);
                }
            }
        }

        @Override
        public void onPackageAdded(UserHandle user, String packageName) throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackageAdded " + user.getIdentifier() + "," + packageName);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackageAdded(packageName, user);
                }
            }
        }

        @Override
        public void onPackagesAvailable(UserHandle user, String[] packageNames, boolean replacing)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackagesAvailable " + user.getIdentifier() + ","
                        + Arrays.toString(packageNames));
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackagesAvailable(packageNames, user, replacing);
                }
            }
        }

        @Override
        public void onPackagesUnavailable(UserHandle user, String[] packageNames, boolean replacing)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackagesUnavailable " + user.getIdentifier() + ","
                        + Arrays.toString(packageNames));
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackagesUnavailable(packageNames, user, replacing);
                }
            }
        }

        @Override
        public void onPackagesSuspended(UserHandle user, String[] packageNames,
                Bundle launcherExtras)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackagesSuspended " + user.getIdentifier() + ","
                        + Arrays.toString(packageNames));
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackagesSuspended(packageNames, launcherExtras, user);
                }
            }
        }

        @Override
        public void onPackagesUnsuspended(UserHandle user, String[] packageNames)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackagesUnsuspended " + user.getIdentifier() + ","
                        + Arrays.toString(packageNames));
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackagesUnsuspended(packageNames, user);
                }
            }
        }

        @Override
        public void onShortcutChanged(UserHandle user, String packageName,
                ParceledListSlice shortcuts) {
            if (DEBUG) {
                Log.d(TAG, "onShortcutChanged " + user.getIdentifier() + "," + packageName);
            }
            final List<ShortcutInfo> list = shortcuts.getList();
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnShortcutChanged(packageName, user, list);
                }
            }
        }

        public void onPackageLoadingProgressChanged(UserHandle user, String packageName,
                float progress) {
            if (DEBUG) {
                Log.d(TAG, "onPackageLoadingProgressChanged " + user.getIdentifier() + ","
                        + packageName + "," + progress);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackageLoadingProgressChanged(user, packageName, progress);
                }
            }
        }
    };

    /**
     * Used to enable Archiving compatibility options with {@link #setArchiveCompatibility}.
     */
    @FlaggedApi(android.content.pm.Flags.FLAG_ARCHIVING)
    public static class ArchiveCompatibilityParams {
        private boolean mEnableIconOverlay = true;

        private boolean mEnableUnarchivalConfirmation = true;

        /** @hide */
        public boolean isEnableIconOverlay() {
            return mEnableIconOverlay;
        }

        /** @hide */
        public boolean isEnableUnarchivalConfirmation() {
            return mEnableUnarchivalConfirmation;
        }

        /**
         * If true, provides a cloud overlay for archived apps to ensure users are aware that a
         * certain app is archived. True by default.
         *
         * <p> Launchers might want to disable this operation if they want to provide custom user
         * experience to differentiate archived apps.
         */
        public void setEnableIconOverlay(boolean enableIconOverlay) {
            this.mEnableIconOverlay = enableIconOverlay;
        }

        /**
         * If true, the user is shown a confirmation dialog when they click an archived app, which
         * explains that the app will be downloaded and restored in the background. True by default.
         *
         * <p> Launchers might want to disable this operation if they provide sufficient,
         * alternative user guidance to highlight that an unarchival is starting and ongoing once an
         * archived app is tapped. E.g., this could be achieved by showing the unarchival progress
         * around the icon.
         */
        public void setEnableUnarchivalConfirmation(boolean enableUnarchivalConfirmation) {
            this.mEnableUnarchivalConfirmation = enableUnarchivalConfirmation;
        }
    }

    private static class CallbackMessageHandler extends Handler {
        private static final int MSG_ADDED = 1;
        private static final int MSG_REMOVED = 2;
        private static final int MSG_CHANGED = 3;
        private static final int MSG_AVAILABLE = 4;
        private static final int MSG_UNAVAILABLE = 5;
        private static final int MSG_SUSPENDED = 6;
        private static final int MSG_UNSUSPENDED = 7;
        private static final int MSG_SHORTCUT_CHANGED = 8;
        private static final int MSG_LOADING_PROGRESS_CHANGED = 9;

        private final LauncherApps.Callback mCallback;

        private static class CallbackInfo {
            String[] packageNames;
            String packageName;
            Bundle launcherExtras;
            boolean replacing;
            UserHandle user;
            List<ShortcutInfo> shortcuts;
            float mLoadingProgress;
        }

        public CallbackMessageHandler(Looper looper, LauncherApps.Callback callback) {
            super(looper, null, true);
            mCallback = callback;
        }

        @Override
        public void handleMessage(Message msg) {
            if (mCallback == null || !(msg.obj instanceof CallbackInfo)) {
                return;
            }
            CallbackInfo info = (CallbackInfo) msg.obj;
            switch (msg.what) {
                case MSG_ADDED:
                    mCallback.onPackageAdded(info.packageName, info.user);
                    break;
                case MSG_REMOVED:
                    mCallback.onPackageRemoved(info.packageName, info.user);
                    break;
                case MSG_CHANGED:
                    mCallback.onPackageChanged(info.packageName, info.user);
                    break;
                case MSG_AVAILABLE:
                    mCallback.onPackagesAvailable(info.packageNames, info.user, info.replacing);
                    break;
                case MSG_UNAVAILABLE:
                    mCallback.onPackagesUnavailable(info.packageNames, info.user, info.replacing);
                    break;
                case MSG_SUSPENDED:
                    mCallback.onPackagesSuspended(info.packageNames, info.user, info.launcherExtras
                    );
                    break;
                case MSG_UNSUSPENDED:
                    mCallback.onPackagesUnsuspended(info.packageNames, info.user);
                    break;
                case MSG_SHORTCUT_CHANGED:
                    mCallback.onShortcutsChanged(info.packageName, info.shortcuts, info.user);
                    break;
                case MSG_LOADING_PROGRESS_CHANGED:
                    mCallback.onPackageLoadingProgressChanged(info.packageName, info.user,
                            info.mLoadingProgress);
                    break;
            }
        }

        public void postOnPackageAdded(String packageName, UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageName = packageName;
            info.user = user;
            obtainMessage(MSG_ADDED, info).sendToTarget();
        }

        public void postOnPackageRemoved(String packageName, UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageName = packageName;
            info.user = user;
            obtainMessage(MSG_REMOVED, info).sendToTarget();
        }

        public void postOnPackageChanged(String packageName, UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageName = packageName;
            info.user = user;
            obtainMessage(MSG_CHANGED, info).sendToTarget();
        }

        public void postOnPackagesAvailable(String[] packageNames, UserHandle user,
                boolean replacing) {
            CallbackInfo info = new CallbackInfo();
            info.packageNames = packageNames;
            info.replacing = replacing;
            info.user = user;
            obtainMessage(MSG_AVAILABLE, info).sendToTarget();
        }

        public void postOnPackagesUnavailable(String[] packageNames, UserHandle user,
                boolean replacing) {
            CallbackInfo info = new CallbackInfo();
            info.packageNames = packageNames;
            info.replacing = replacing;
            info.user = user;
            obtainMessage(MSG_UNAVAILABLE, info).sendToTarget();
        }

        public void postOnPackagesSuspended(String[] packageNames, Bundle launcherExtras,
                UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageNames = packageNames;
            info.user = user;
            info.launcherExtras = launcherExtras;
            obtainMessage(MSG_SUSPENDED, info).sendToTarget();
        }

        public void postOnPackagesUnsuspended(String[] packageNames, UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageNames = packageNames;
            info.user = user;
            obtainMessage(MSG_UNSUSPENDED, info).sendToTarget();
        }

        public void postOnShortcutChanged(String packageName, UserHandle user,
                List<ShortcutInfo> shortcuts) {
            CallbackInfo info = new CallbackInfo();
            info.packageName = packageName;
            info.user = user;
            info.shortcuts = shortcuts;
            obtainMessage(MSG_SHORTCUT_CHANGED, info).sendToTarget();
        }

        public void postOnPackageLoadingProgressChanged(UserHandle user, String packageName,
                float progress) {
            CallbackInfo info = new CallbackInfo();
            info.packageName = packageName;
            info.user = user;
            info.mLoadingProgress = progress;
            obtainMessage(MSG_LOADING_PROGRESS_CHANGED, info).sendToTarget();
        }
    }

    /**
     * Register a callback to watch for session lifecycle events in this user and managed profiles.
     * Callers need to either declare &lt;queries&gt; element with the specific package name in the
     * app's manifest, have the android.permission.QUERY_ALL_PACKAGES, or be the session owner to
     * watch for these events.
     *
     * @param callback The callback to register.
     * @param executor {@link Executor} to handle the callbacks, cannot be null.
     *
     * @see PackageInstaller#registerSessionCallback(SessionCallback)
     */
    public void registerPackageInstallerSessionCallback(
            @NonNull @CallbackExecutor Executor executor, @NonNull SessionCallback callback) {
        if (executor == null) {
            throw new NullPointerException("Executor must not be null");
        }

        synchronized (mDelegates) {
            final SessionCallbackDelegate delegate = new SessionCallbackDelegate(callback,
                    executor);
            try {
                mService.registerPackageInstallerCallback(mContext.getPackageName(),
                        delegate);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mDelegates.add(delegate);
        }
    }

    /**
     * Unregisters a callback that was previously registered.
     *
     * @param callback The callback to unregister.
     * @see #registerPackageInstallerSessionCallback(Executor, SessionCallback)
     */
    public void unregisterPackageInstallerSessionCallback(@NonNull SessionCallback callback) {
        synchronized (mDelegates) {
            for (Iterator<SessionCallbackDelegate> i = mDelegates.iterator(); i.hasNext();) {
                final SessionCallbackDelegate delegate = i.next();
                if (delegate.mCallback == callback) {
                    mPm.getPackageInstaller().unregisterSessionCallback(delegate.mCallback);
                    i.remove();
                }
            }
        }
    }

    /**
     * Return list of all known install sessions in this user and managed profiles, regardless
     * of the installer. Callers need to either declare &lt;queries&gt; element with the specific
     * package name in the app's manifest, have the android.permission.QUERY_ALL_PACKAGES, or be
     * the session owner to retrieve these details.
     *
     * @see PackageInstaller#getAllSessions()
     */
    public @NonNull List<SessionInfo> getAllPackageInstallerSessions() {
        try {
            return mService.getAllSessions(mContext.getPackageName()).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register a callback to watch for shortcut change events in this user and managed profiles.
     *
     * @param callback The callback to register.
     * @param query {@link ShortcutQuery} to match and filter the shortcut events. Only matching
     * shortcuts will be returned by the callback.
     * @param executor {@link Executor} to handle the callbacks. To dispatch callbacks to the main
     * thread of your application, you can use {@link android.content.Context#getMainExecutor()}.
     *
     * @hide
     */
    public void registerShortcutChangeCallback(@NonNull ShortcutChangeCallback callback,
            @NonNull ShortcutQuery query, @NonNull @CallbackExecutor Executor executor) {
        Objects.requireNonNull(callback, "Callback cannot be null");
        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");

        synchronized (mShortcutChangeCallbacks) {
            IShortcutChangeCallback proxy = new ShortcutChangeCallbackProxy(executor, callback);
            mShortcutChangeCallbacks.put(callback, new Pair<>(executor, proxy));
            try {
                mService.registerShortcutChangeCallback(mContext.getPackageName(),
                        new ShortcutQueryWrapper(query), proxy);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters a callback that was previously registered.
     * @see #registerShortcutChangeCallback(ShortcutChangeCallback, ShortcutQuery, Executor)
     *
     * @param callback Callback to be unregistered.
     *
     * @hide
     */
    public void unregisterShortcutChangeCallback(@NonNull ShortcutChangeCallback callback) {
        Objects.requireNonNull(callback, "Callback cannot be null");

        synchronized (mShortcutChangeCallbacks) {
            if (mShortcutChangeCallbacks.containsKey(callback)) {
                IShortcutChangeCallback proxy = mShortcutChangeCallbacks.remove(callback).second;
                try {
                    mService.unregisterShortcutChangeCallback(mContext.getPackageName(), proxy);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * A helper method to extract a {@link PinItemRequest} set to
     * the {@link #EXTRA_PIN_ITEM_REQUEST} extra.
     */
    public PinItemRequest getPinItemRequest(Intent intent) {
        return intent.getParcelableExtra(EXTRA_PIN_ITEM_REQUEST, android.content.pm.LauncherApps.PinItemRequest.class);
    }

    /**
     * Represents a "pin shortcut" or a "pin appwidget" request made by an app, which is sent with
     * an {@link #ACTION_CONFIRM_PIN_SHORTCUT} or {@link #ACTION_CONFIRM_PIN_APPWIDGET} intent
     * respectively to the default launcher app.
     *
     * <h3>Request of the {@link #REQUEST_TYPE_SHORTCUT} type.</h3>
     *
     * <p>A {@link #REQUEST_TYPE_SHORTCUT} request represents a request to pin a
     * {@link ShortcutInfo}.  If the launcher accepts a request, call {@link #accept()},
     * or {@link #accept(Bundle)} with a null or empty Bundle.  No options are defined for
     * pin-shortcuts requests.
     *
     * <p>{@link #getShortcutInfo()} always returns a non-null {@link ShortcutInfo} for this type.
     *
     * <p>The launcher may receive a request with a {@link ShortcutInfo} that is already pinned, in
     * which case {@link ShortcutInfo#isPinned()} returns true.  This means the user wants to create
     * another pinned shortcut for a shortcut that's already pinned.  If the launcher accepts it,
     * {@link #accept()} must still be called even though the shortcut is already pinned, and
     * create a new pinned shortcut icon for it.
     *
     * <p>See also {@link ShortcutManager} for more details.
     *
     * <h3>Request of the {@link #REQUEST_TYPE_APPWIDGET} type.</h3>
     *
     * <p>A {@link #REQUEST_TYPE_SHORTCUT} request represents a request to pin a
     * an AppWidget.  If the launcher accepts a request, call {@link #accept(Bundle)} with
     * the appwidget integer ID set to the
     * {@link android.appwidget.AppWidgetManager#EXTRA_APPWIDGET_ID} extra.
     *
     * <p>{@link #getAppWidgetProviderInfo(Context)} always returns a non-null
     * {@link AppWidgetProviderInfo} for this type.
     *
     * <p>See also {@link AppWidgetManager} for more details.
     *
     * @see #EXTRA_PIN_ITEM_REQUEST
     * @see #getPinItemRequest(Intent)
     */
    public static final class PinItemRequest implements Parcelable {

        /** This is a request to pin shortcut. */
        public static final int REQUEST_TYPE_SHORTCUT = 1;

        /** This is a request to pin app widget. */
        public static final int REQUEST_TYPE_APPWIDGET = 2;

        /** @hide */
        @IntDef(prefix = { "REQUEST_TYPE_" }, value = {
                REQUEST_TYPE_SHORTCUT,
                REQUEST_TYPE_APPWIDGET
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface RequestType {}

        private final int mRequestType;
        private final IPinItemRequest mInner;

        /**
         * @hide
         */
        public PinItemRequest(IPinItemRequest inner, int type) {
            mInner = inner;
            mRequestType = type;
        }

        /**
         * Represents the type of a request, which is one of the {@code REQUEST_TYPE_} constants.
         *
         * @return one of the {@code REQUEST_TYPE_} constants.
         */
        @RequestType
        public int getRequestType() {
            return mRequestType;
        }

        /**
         * {@link ShortcutInfo} sent by the requesting app.
         * Always non-null for a {@link #REQUEST_TYPE_SHORTCUT} request, and always null for a
         * different request type.
         *
         * @return requested {@link ShortcutInfo} when a request is of the
         * {@link #REQUEST_TYPE_SHORTCUT} type.  Null otherwise.
         */
        @Nullable
        public ShortcutInfo getShortcutInfo() {
            try {
                return mInner.getShortcutInfo();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * {@link AppWidgetProviderInfo} sent by the requesting app.
         * Always non-null for a {@link #REQUEST_TYPE_APPWIDGET} request, and always null for a
         * different request type.
         *
         * <p>Launcher should not show any configuration activity associated with the provider, and
         * assume that the widget is already fully configured. Upon accepting the widget, it should
         * pass the widgetId in {@link #accept(Bundle)}.
         *
         * @return requested {@link AppWidgetProviderInfo} when a request is of the
         * {@link #REQUEST_TYPE_APPWIDGET} type.  Null otherwise.
         */
        @Nullable
        public AppWidgetProviderInfo getAppWidgetProviderInfo(Context context) {
            try {
                final AppWidgetProviderInfo info = mInner.getAppWidgetProviderInfo();
                if (info == null) {
                    return null;
                }
                info.updateDimensions(context.getResources().getDisplayMetrics());
                return info;
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Any extras sent by the requesting app.
         *
         * @return For a shortcut request, this method always return null.  For an AppWidget
         * request, this method returns the extras passed to the
         * {@link android.appwidget.AppWidgetManager#requestPinAppWidget(
         * ComponentName, Bundle, PendingIntent)} API.  See {@link AppWidgetManager} for details.
         */
        @Nullable
        public Bundle getExtras() {
            try {
                return mInner.getExtras();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Return whether a request is still valid.
         *
         * @return {@code TRUE} if a request is valid and {@link #accept(Bundle)} may be called.
         */
        public boolean isValid() {
            try {
                return mInner.isValid();
            } catch (RemoteException e) {
                return false;
            }
        }

        /**
         * Called by the receiving launcher app when the user accepts the request.
         *
         * @param options must be set for a {@link #REQUEST_TYPE_APPWIDGET} request.
         *
         * @return {@code TRUE} if the shortcut or the AppWidget has actually been pinned.
         * {@code FALSE} if the item hasn't been pinned, for example, because the request had
         * already been canceled, in which case the launcher must not pin the requested item.
         */
        public boolean accept(@Nullable Bundle options) {
            try {
                return mInner.accept(options);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Called by the receiving launcher app when the user accepts the request, with no options.
         *
         * @return {@code TRUE} if the shortcut or the AppWidget has actually been pinned.
         * {@code FALSE} if the item hasn't been pinned, for example, because the request had
         * already been canceled, in which case the launcher must not pin the requested item.
         */
        public boolean accept() {
            return accept(/* options= */ null);
        }

        private PinItemRequest(Parcel source) {
            final ClassLoader cl = getClass().getClassLoader();

            mRequestType = source.readInt();
            mInner = IPinItemRequest.Stub.asInterface(source.readStrongBinder());
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mRequestType);
            dest.writeStrongBinder(mInner.asBinder());
        }

        public static final @android.annotation.NonNull Creator<PinItemRequest> CREATOR =
                new Creator<PinItemRequest>() {
                    public PinItemRequest createFromParcel(Parcel source) {
                        return new PinItemRequest(source);
                    }
                    public PinItemRequest[] newArray(int size) {
                        return new PinItemRequest[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }
    }

    /**
     * A class that encapsulates information about the usage limit set for an app or
     * a group of apps.
     *
     * <p>The launcher can query specifics about the usage limit such as how much usage time
     * the limit has and how much of the total usage time is remaining via the APIs available
     * in this class.
     *
     * @see #getAppUsageLimit(String, UserHandle)
     * @hide
     */
    @SystemApi
    public static final class AppUsageLimit implements Parcelable {
        private final long mTotalUsageLimit;
        private final long mUsageRemaining;

        /** @hide */
        public AppUsageLimit(long totalUsageLimit, long usageRemaining) {
            this.mTotalUsageLimit = totalUsageLimit;
            this.mUsageRemaining = usageRemaining;
        }

        /**
         * Returns the total usage limit in milliseconds set for an app or a group of apps.
         *
         * @return the total usage limit in milliseconds
         */
        public long getTotalUsageLimit() {
            return mTotalUsageLimit;
        }

        /**
         * Returns the usage remaining in milliseconds for an app or the group of apps
         * this limit refers to.
         *
         * @return the usage remaining in milliseconds
         */
        public long getUsageRemaining() {
            return mUsageRemaining;
        }

        private AppUsageLimit(Parcel source) {
            mTotalUsageLimit = source.readLong();
            mUsageRemaining = source.readLong();
        }

        public static final @android.annotation.NonNull Creator<AppUsageLimit> CREATOR = new Creator<AppUsageLimit>() {
            @Override
            public AppUsageLimit createFromParcel(Parcel source) {
                return new AppUsageLimit(source);
            }

            @Override
            public AppUsageLimit[] newArray(int size) {
                return new AppUsageLimit[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(mTotalUsageLimit);
            dest.writeLong(mUsageRemaining);
        }
    }
}
