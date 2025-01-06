/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.content.Intent.LOCAL_FLAG_FROM_SYSTEM;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.Notification;
import android.app.usage.UsageStatsManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * <p><code>ShortcutManager</code> executes operations on an app's set of <i>shortcuts</i>, which
 * represent specific tasks and actions that users can perform within your app. This page lists
 * components of the <code>ShortcutManager</code> class that you can use to create and manage
 * sets of shortcuts.
 *
 * <p>To learn about methods that retrieve information about a single shortcut&mdash;including
 * identifiers, type, and status&mdash;read the <code>
 * <a href="/reference/android/content/pm/ShortcutInfo.html">ShortcutInfo</a></code> reference.
 *
 * <p>For guidance about using shortcuts, see
 * <a href="/guide/topics/ui/shortcuts/index.html">App shortcuts</a>.
 *
 * <h3>Retrieving class instances</h3>
 * <!-- Provides a heading for the content filled in by the @SystemService annotation below -->
 */
@SystemService(Context.SHORTCUT_SERVICE)
public class ShortcutManager {
    private static final String TAG = "ShortcutManager";

    /**
     * Include manifest shortcuts in the result.
     *
     * @see #getShortcuts(int)
     */
    public static final int FLAG_MATCH_MANIFEST = 1 << 0;

    /**
     * Include dynamic shortcuts in the result.
     *
     * @see #getShortcuts(int)
     */
    public static final int FLAG_MATCH_DYNAMIC = 1 << 1;

    /**
     * Include pinned shortcuts in the result.
     *
     * @see #getShortcuts(int)
     */
    public static final int FLAG_MATCH_PINNED = 1 << 2;

    /**
     * Include cached shortcuts in the result.
     *
     * @see #getShortcuts(int)
     */
    public static final int FLAG_MATCH_CACHED = 1 << 3;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_MATCH_" }, value = {
            FLAG_MATCH_MANIFEST,
            FLAG_MATCH_DYNAMIC,
            FLAG_MATCH_PINNED,
            FLAG_MATCH_CACHED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShortcutMatchFlags {}

    private final Context mContext;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final IShortcutService mService;

    /**
     * @hide
     */
    public ShortcutManager(Context context, IShortcutService service) {
        mContext = context;
        mService = service;
    }

    /**
     * @hide
     */
    @TestApi
    public ShortcutManager(Context context) {
        this(context, IShortcutService.Stub.asInterface(
                ServiceManager.getService(Context.SHORTCUT_SERVICE)));
    }

    /**
     * Publish the list of shortcuts.  All existing dynamic shortcuts from the caller app
     * will be replaced.  If there are already pinned shortcuts with the same IDs,
     * the mutable pinned shortcuts are updated.
     *
     * <p>This API will be rate-limited.
     *
     * @return {@code true} if the call has succeeded. {@code false} if the call is rate-limited.
     *
     * @throws IllegalArgumentException if {@link #getMaxShortcutCountPerActivity()} is exceeded,
     * or when trying to update immutable shortcuts.
     *
     * @throws IllegalStateException when the user is locked.
     */
    @WorkerThread
    public boolean setDynamicShortcuts(@NonNull List<ShortcutInfo> shortcutInfoList) {
        try {
            return mService.setDynamicShortcuts(mContext.getPackageName(), new ParceledListSlice(
                    shortcutInfoList), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return all dynamic shortcuts from the caller app.
     *
     * <p>This API is intended to be used for examining what shortcuts are currently published.
     * Re-publishing returned {@link ShortcutInfo}s via APIs such as
     * {@link #setDynamicShortcuts(List)} may cause loss of information such as icons.
     *
     * @throws IllegalStateException when the user is locked.
     */
    @WorkerThread
    @NonNull
    public List<ShortcutInfo> getDynamicShortcuts() {
        try {
            return mService.getShortcuts(mContext.getPackageName(),
                    FLAG_MATCH_DYNAMIC, injectMyUserId()).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return all static (manifest) shortcuts from the caller app.
     *
     * <p>This API is intended to be used for examining what shortcuts are currently published.
     * Re-publishing returned {@link ShortcutInfo}s via APIs such as
     * {@link #setDynamicShortcuts(List)} may cause loss of information such as icons.
     *
     * @throws IllegalStateException when the user is locked.
     */
    @WorkerThread
    @NonNull
    public List<ShortcutInfo> getManifestShortcuts() {
        try {
            return mService.getShortcuts(mContext.getPackageName(),
                    FLAG_MATCH_MANIFEST, injectMyUserId()).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@link ShortcutInfo}s that match {@code matchFlags}.
     *
     * @param matchFlags result includes shortcuts matching this flags. Any combination of:
     * <ul>
     *     <li>{@link #FLAG_MATCH_MANIFEST}
     *     <li>{@link #FLAG_MATCH_DYNAMIC}
     *     <li>{@link #FLAG_MATCH_PINNED}
     *     <li>{@link #FLAG_MATCH_CACHED}
     * </ul>

     * @return list of {@link ShortcutInfo}s that match the flag.
     *
     * <p>At least one of the {@code MATCH} flags should be set. Otherwise no shortcuts will be
     * returned.
     *
     * @throws IllegalStateException when the user is locked.
     */
    @WorkerThread
    @NonNull
    public List<ShortcutInfo> getShortcuts(@ShortcutMatchFlags int matchFlags) {
        try {
            return mService.getShortcuts(mContext.getPackageName(), matchFlags,
                    injectMyUserId()).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Publish the list of dynamic shortcuts.  If there are already dynamic or pinned shortcuts with
     * the same IDs, each mutable shortcut is updated.
     *
     * <p>This API will be rate-limited.
     *
     * @return {@code true} if the call has succeeded. {@code false} if the call is rate-limited.
     *
     * @throws IllegalArgumentException if {@link #getMaxShortcutCountPerActivity()} is exceeded,
     * or when trying to update immutable shortcuts.
     *
     * @throws IllegalStateException when the user is locked.
     */
    @WorkerThread
    public boolean addDynamicShortcuts(@NonNull List<ShortcutInfo> shortcutInfoList) {
        try {
            return mService.addDynamicShortcuts(mContext.getPackageName(),
                    new ParceledListSlice(shortcutInfoList), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Delete dynamic shortcuts by ID.
     *
     * @throws IllegalStateException when the user is locked.
     */
    public void removeDynamicShortcuts(@NonNull List<String> shortcutIds) {
        try {
            mService.removeDynamicShortcuts(mContext.getPackageName(), shortcutIds,
                    injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Delete all dynamic shortcuts from the caller app.
     *
     * @throws IllegalStateException when the user is locked.
     */
    public void removeAllDynamicShortcuts() {
        try {
            mService.removeAllDynamicShortcuts(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Delete long lived shortcuts by ID.
     *
     * @throws IllegalStateException when the user is locked.
     */
    public void removeLongLivedShortcuts(@NonNull List<String> shortcutIds) {
        try {
            mService.removeLongLivedShortcuts(mContext.getPackageName(), shortcutIds,
                    injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return all pinned shortcuts from the caller app.
     *
     * <p>This API is intended to be used for examining what shortcuts are currently published.
     * Re-publishing returned {@link ShortcutInfo}s via APIs such as
     * {@link #setDynamicShortcuts(List)} may cause loss of information such as icons.
     *
     * @throws IllegalStateException when the user is locked.
     */
    @WorkerThread
    @NonNull
    public List<ShortcutInfo> getPinnedShortcuts() {
        try {
            return mService.getShortcuts(mContext.getPackageName(), FLAG_MATCH_PINNED,
                    injectMyUserId()).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Update all existing shortcuts with the same IDs.  Target shortcuts may be pinned and/or
     * dynamic, but they must not be immutable.
     *
     * <p>This API will be rate-limited.
     *
     * @return {@code true} if the call has succeeded. {@code false} if the call is rate-limited.
     *
     * @throws IllegalArgumentException If trying to update immutable shortcuts.
     *
     * @throws IllegalStateException when the user is locked.
     */
    @WorkerThread
    public boolean updateShortcuts(@NonNull List<ShortcutInfo> shortcutInfoList) {
        try {
            return mService.updateShortcuts(mContext.getPackageName(),
                    new ParceledListSlice(shortcutInfoList), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Disable pinned shortcuts.  For more details, read
     * <a href="/guide/topics/ui/shortcuts/managing-shortcuts.html#disable-shortcuts">
     * Disable shortcuts</a>.
     *
     * @throws IllegalArgumentException If trying to disable immutable shortcuts.
     *
     * @throws IllegalStateException when the user is locked.
     */
    public void disableShortcuts(@NonNull List<String> shortcutIds) {
        try {
            mService.disableShortcuts(mContext.getPackageName(), shortcutIds,
                    /* disabledMessage =*/ null, /* disabledMessageResId =*/ 0,
                    injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide old signature, kept for unit testing.
     */
    public void disableShortcuts(@NonNull List<String> shortcutIds, int disabledMessageResId) {
        try {
            mService.disableShortcuts(mContext.getPackageName(), shortcutIds,
                    /* disabledMessage =*/ null, disabledMessageResId,
                    injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide old signature, kept for unit testing.
     */
    public void disableShortcuts(@NonNull List<String> shortcutIds, String disabledMessage) {
        disableShortcuts(shortcutIds, (CharSequence) disabledMessage);
    }

    /**
     * Disable pinned shortcuts, showing the user a custom error message when they try to select
     * the disabled shortcuts.
     * For more details, read
     * <a href="/guide/topics/ui/shortcuts/managing-shortcuts.html#disable-shortcuts">
     * Disable shortcuts</a>.
     *
     * @throws IllegalArgumentException If trying to disable immutable shortcuts.
     *
     * @throws IllegalStateException when the user is locked.
     */
    public void disableShortcuts(@NonNull List<String> shortcutIds, CharSequence disabledMessage) {
        try {
            mService.disableShortcuts(mContext.getPackageName(), shortcutIds,
                    disabledMessage, /* disabledMessageResId =*/ 0,
                    injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Re-enable pinned shortcuts that were previously disabled.  If the target shortcuts
     * are already enabled, this method does nothing.
     *
     * @throws IllegalArgumentException If trying to enable immutable shortcuts.
     *
     * @throws IllegalStateException when the user is locked.
     */
    public void enableShortcuts(@NonNull List<String> shortcutIds) {
        try {
            mService.enableShortcuts(mContext.getPackageName(), shortcutIds, injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * @hide old signature, kept for unit testing.
     */
    public int getMaxShortcutCountForActivity() {
        return getMaxShortcutCountPerActivity();
    }

    /**
     * Return the maximum number of static and dynamic shortcuts that each launcher icon
     * can have at a time.
     */
    public int getMaxShortcutCountPerActivity() {
        try {
            return mService.getMaxShortcutCountPerActivity(
                    mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the number of times the caller app can call the rate-limited APIs
     * before the rate limit counter is reset.
     *
     * @see #getRateLimitResetTime()
     *
     * @hide
     */
    public int getRemainingCallCount() {
        try {
            return mService.getRemainingCallCount(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return when the rate limit count will be reset next time, in milliseconds since the epoch.
     *
     * @see #getRemainingCallCount()
     * @see System#currentTimeMillis()
     *
     * @hide
     */
    public long getRateLimitResetTime() {
        try {
            return mService.getRateLimitResetTime(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return {@code true} when rate-limiting is active for the caller app.
     *
     * <p>For details, see <a href="/guide/topics/ui/shortcuts/managing-shortcuts#rate-limiting">
     * Rate limiting</a>.
     *
     * @throws IllegalStateException when the user is locked.
     */
    public boolean isRateLimitingActive() {
        try {
            return mService.getRemainingCallCount(mContext.getPackageName(), injectMyUserId())
                    == 0;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the max width for icons, in pixels.
     *
     * <p> Note that this method returns max width of icon's visible part. Hence, it does not take
     * into account the inset introduced by {@link AdaptiveIconDrawable}. To calculate bitmap image
     * to function as {@link AdaptiveIconDrawable}, multiply
     * 1 + 2 * {@link AdaptiveIconDrawable#getExtraInsetFraction()} to the returned size.
     */
    public int getIconMaxWidth() {
        try {
            // TODO Implement it properly using xdpi.
            return mService.getIconMaxDimensions(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the max height for icons, in pixels.
     */
    public int getIconMaxHeight() {
        try {
            // TODO Implement it properly using ydpi.
            return mService.getIconMaxDimensions(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Apps that publish shortcuts should call this method whenever the user
     * selects the shortcut containing the given ID or when the user completes
     * an action in the app that is equivalent to selecting the shortcut.
     * For more details, read about
     * <a href="/guide/topics/ui/shortcuts/managing-shortcuts.html#track-usage">
     * tracking shortcut usage</a>.
     *
     * <p>The information is accessible via {@link UsageStatsManager#queryEvents}
     * Typically, launcher apps use this information to build a prediction model
     * so that they can promote the shortcuts that are likely to be used at the moment.
     *
     * @throws IllegalStateException when the user is locked.
     */
    public void reportShortcutUsed(String shortcutId) {
        try {
            mService.reportShortcutUsed(mContext.getPackageName(), shortcutId, injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return {@code TRUE} if the app is running on a device whose default launcher supports
     * {@link #requestPinShortcut(ShortcutInfo, IntentSender)}.
     *
     * <p>The return value may change in subsequent calls if the user changes the default launcher
     * app.
     *
     * <p><b>Note:</b> See also the support library counterpart
     * {@link androidx.core.content.pm.ShortcutManagerCompat#isRequestPinShortcutSupported(
     * Context)}, which supports Android versions lower than {@link VERSION_CODES#O} using the
     * legacy private intent {@code com.android.launcher.action.INSTALL_SHORTCUT}.
     *
     * @see #requestPinShortcut(ShortcutInfo, IntentSender)
     */
    public boolean isRequestPinShortcutSupported() {
        try {
            return mService.isRequestPinItemSupported(injectMyUserId(),
                    LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to create a pinned shortcut.  The default launcher will receive this request and
     * ask the user for approval.  If the user approves it, the shortcut will be created, and
     * {@code resultIntent} will be sent. If a request is denied by the user, however, no response
     * will be sent to the caller.
     *
     * <p>Only apps with a foreground activity or a foreground service can call this method.
     * Otherwise, it'll throw {@link IllegalStateException}.
     *
     * <p>It's up to the launcher to decide how to handle previous pending requests when the same
     * package calls this API multiple times in a row. One possible strategy is to ignore any
     * previous requests.
     *
     * <p><b>Note:</b> See also the support library counterpart
     * {@link androidx.core.content.pm.ShortcutManagerCompat#requestPinShortcut(
     * Context, ShortcutInfoCompat, IntentSender)},
     * which supports Android versions lower than {@link VERSION_CODES#O} using the
     * legacy private intent {@code com.android.launcher.action.INSTALL_SHORTCUT}.
     *
     * @param shortcut Shortcut to pin.  If an app wants to pin an existing (either static
     *     or dynamic) shortcut, then it only needs to have an ID. Although other fields don't have
     *     to be set, the target shortcut must be enabled.
     *
     *     <p>If it's a new shortcut, all the mandatory fields, such as a short label, must be
     *     set.
     * @param resultIntent If not null, this intent will be sent when the shortcut is pinned.
     *    Use {@link android.app.PendingIntent#getIntentSender()} to create an {@link IntentSender}.
     *    To avoid background execution limits, use an unexported, manifest-declared receiver.
     *    For more details, see
     *    <a href="/guide/topics/ui/shortcuts/creating-shortcuts.html#pinned">
     *    Creating pinned shortcuts</a>.
     *
     * @return {@code TRUE} if the launcher supports this feature.  Note the API will return without
     *    waiting for the user to respond, so getting {@code TRUE} from this API does *not* mean
     *    the shortcut was pinned successfully. {@code FALSE} if the launcher doesn't support this
     *    feature or if calling app belongs to a user-profile with items restricted on home screen.
     *
     * @see #isRequestPinShortcutSupported()
     * @see IntentSender
     * @see android.app.PendingIntent#getIntentSender()
     *
     * @throws IllegalArgumentException if a shortcut with the same ID exists and is disabled.
     * @throws IllegalStateException The caller doesn't have a foreground activity or a foreground
     * service, or the device is locked.
     */
    @WorkerThread
    public boolean requestPinShortcut(@NonNull ShortcutInfo shortcut,
            @Nullable IntentSender resultIntent) {
        try {
            return mService.requestPinShortcut(mContext.getPackageName(), shortcut, resultIntent,
                    injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns an Intent which can be used by the default launcher to pin a shortcut containing the
     * given {@link ShortcutInfo}. This method should be used by an Activity to set a result in
     * response to {@link Intent#ACTION_CREATE_SHORTCUT}.
     *
     * @param shortcut New shortcut to pin.  If an app wants to pin an existing (either dynamic
     *     or manifest) shortcut, then it only needs to have an ID, and other fields don't have to
     *     be set, in which case, the target shortcut must be enabled.
     *     If it's a new shortcut, all the mandatory fields, such as a short label, must be
     *     set.
     * @return The intent that should be set as the result for the calling activity, or
     *     <code>null</code> if the current launcher doesn't support shortcuts.
     *
     * @see Intent#ACTION_CREATE_SHORTCUT
     *
     * @throws IllegalArgumentException if a shortcut with the same ID exists and is disabled.
     */
    @WorkerThread
    public Intent createShortcutResultIntent(@NonNull ShortcutInfo shortcut) {
        try {
            Intent result = mService.createShortcutResultIntent(mContext.getPackageName(),
                    shortcut, injectMyUserId());
            if (result != null) {
                result.prepareToEnterProcess(LOCAL_FLAG_FROM_SYSTEM,
                        mContext.getAttributionSource());
            }
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called internally when an app is considered to have come to the foreground
     * even when technically it's not.  This method resets the throttling for this package.
     * For example, when the user sends an "inline reply" on a notification, the system UI will
     * call it.
     *
     * @hide
     */
    public void onApplicationActive(@NonNull String packageName, @UserIdInt int userId) {
        try {
            mService.onApplicationActive(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide injection point */
    @VisibleForTesting
    protected int injectMyUserId() {
        return mContext.getUserId();
    }

    /**
     * Used by framework's ShareSheet (ChooserActivity.java) to retrieve all of the direct share
     * targets that match the given IntentFilter.
     *
     * @param filter IntentFilter that will be used to retrieve the matching {@link ShortcutInfo}s.
     * @return List of {@link ShareShortcutInfo}s that match the given IntentFilter.
     * @hide
     */
    @WorkerThread
    @NonNull
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_APP_PREDICTIONS)
    public List<ShareShortcutInfo> getShareTargets(@NonNull IntentFilter filter) {
        try {
            return mService.getShareTargets(
                    mContext.getPackageName(), filter, injectMyUserId()).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Represents the result of a query return by {@link #getShareTargets(IntentFilter)}.
     *
     * @hide
     */
    @SystemApi
    public static final class ShareShortcutInfo implements Parcelable {
        private final ShortcutInfo mShortcutInfo;
        private final ComponentName mTargetComponent;

        /**
         * @hide
         */
        public ShareShortcutInfo(@NonNull ShortcutInfo shortcutInfo,
                @NonNull ComponentName targetComponent) {
            if (shortcutInfo == null) {
                throw new NullPointerException("shortcut info is null");
            }
            if (targetComponent == null) {
                throw new NullPointerException("target component is null");
            }

            mShortcutInfo = shortcutInfo;
            mTargetComponent = targetComponent;
        }

        private ShareShortcutInfo(@NonNull Parcel in) {
            mShortcutInfo = in.readParcelable(ShortcutInfo.class.getClassLoader(), android.content.pm.ShortcutInfo.class);
            mTargetComponent = in.readParcelable(ComponentName.class.getClassLoader(), android.content.ComponentName.class);
        }

        @NonNull
        public ShortcutInfo getShortcutInfo() {
            return mShortcutInfo;
        }

        @NonNull
        public ComponentName getTargetComponent() {
            return mTargetComponent;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeParcelable(mShortcutInfo, flags);
            dest.writeParcelable(mTargetComponent, flags);
        }

        public static final @NonNull Parcelable.Creator<ShareShortcutInfo> CREATOR =
                new Parcelable.Creator<ShareShortcutInfo>() {
                    public ShareShortcutInfo createFromParcel(Parcel in) {
                        return new ShareShortcutInfo(in);
                    }

                    public ShareShortcutInfo[] newArray(int size) {
                        return new ShareShortcutInfo[size];
                    }
                };
    }

    /**
     * Used by framework's ShareSheet (ChooserActivity.java) to check if a given package has share
     * target definitions in it's resources.
     *
     * @param packageName Package to check for share targets.
     * @return True if the package has any share target definitions, False otherwise.
     * @hide
     */
    @SystemApi
    public boolean hasShareTargets(@NonNull String packageName) {
        try {
            return mService.hasShareTargets(mContext.getPackageName(), packageName,
                    injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Publish a single dynamic shortcut. If there are already dynamic or pinned shortcuts with the
     * same ID, each mutable shortcut is updated.
     *
     * <p>This method is useful when posting notifications which are tagged with shortcut IDs; In
     * order to make sure shortcuts exist and are up-to-date, without the need to explicitly handle
     * the shortcut count limit.
     * @see android.app.NotificationManager#notify(int, Notification)
     * @see android.app.Notification.Builder#setShortcutId(String)
     *
     * <p>If {@link #getMaxShortcutCountPerActivity()} is already reached, an existing shortcut with
     * the lowest rank will be removed to add space for the new shortcut.
     *
     * <p>If the rank of the shortcut is not explicitly set, it will be set to zero, and shortcut
     * will be added to the top of the list.
     *
     * @throws IllegalArgumentException if trying to update an immutable shortcut.
     *
     * @throws IllegalStateException when the user is locked.
     */
    public void pushDynamicShortcut(@NonNull ShortcutInfo shortcut) {
        try {
            mService.pushDynamicShortcut(mContext.getPackageName(), shortcut, injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
