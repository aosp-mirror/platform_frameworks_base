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

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

// TODO Enhance javadoc
/**
 * {@link ShortcutManager} manages shortcuts created by applications.
 *
 * <h3>Dynamic shortcuts and pinned shortcuts</h3>
 *
 * An application can publish shortcuts with {@link #setDynamicShortcuts(List)} and
 * {@link #addDynamicShortcuts(List)}.  There can be at most
 * {@link #getMaxDynamicShortcutCount()} number of dynamic shortcuts at a time from the same
 * application.
 * A dynamic shortcut can be deleted with {@link #removeDynamicShortcuts(List)}, and apps
 * can also use {@link #removeAllDynamicShortcuts()} to delete all dynamic shortcuts.
 *
 * <p>The shortcuts that are currently published by the above APIs are called "dynamic", because
 * they can be removed by the creator application at any time.  The user may "pin" dynamic shortcuts
 * on Launcher to make "pinned" shortcuts.  Pinned shortcuts <b>cannot</b> be removed by the creator
 * app.  An application can obtain all pinned shortcuts from itself with
 * {@link #getPinnedShortcuts()}.  Applications should keep the pinned shortcut information
 * up-to-date using {@link #updateShortcuts(List)}.
 *
 * <p>The number of pinned shortcuts does not affect the number of dynamic shortcuts that can be
 * published by an application at a time.
 * No matter how many pinned shortcuts that Launcher has for an application, the
 * application can still always publish {@link #getMaxDynamicShortcutCount()} number of dynamic
 * shortcuts.
 *
 * <h3>Shortcut IDs</h3>
 *
 * Each shortcut must have an ID, which must be unique within each application.  When a shortcut is
 * published, existing shortcuts with the same ID will be updated.  Note this may include a
 * pinned shortcut.
 *
 * <h3>Rate limiting</h3>
 *
 * Calls to {@link #setDynamicShortcuts(List)}, {@link #addDynamicShortcuts(List)},
 * and {@link #updateShortcuts(List)} from <b>background applications</b> will be
 * rate-limited.  An application can call these methods at most
 * {@link #getRemainingCallCount()} times until the rate-limiting counter is reset,
 * which happens at a certain time every day.
 *
 * <p>An application can use {@link #getRateLimitResetTime()} to get the next reset time.
 *
 * <p>Foreground applications (i.e. ones with a foreground activity or a foreground services)
 * will not be throttled. Also, when an application comes to foreground,
 * {@link #getRemainingCallCount()} will be reset to the initial value.
 *
 * <p>For testing purposes, use "Developer Options" (found in the Settings menu) to reset the
 * internal rate-limiting counter.  Automated tests can use the following ADB shell command to
 * achieve the same effect:</p>
 * <pre>adb shell cmd shortcut reset-throttling</pre>
 *
 * <h3>Backup and Restore</h3>
 *
 * Shortcuts will be backed up and restored across devices.  This means all information, including
 * IDs, must be meaningful on a different device.
 *
 * <h3>APIs for launcher</h3>
 *
 * Launcher applications should use {@link LauncherApps} to get shortcuts that are published from
 * applications.  Launcher applications can also pin shortcuts with
 * {@link LauncherApps#pinShortcuts(String, List, UserHandle)}.
 *
 * @hide
 */
public class ShortcutManager {
    private static final String TAG = "ShortcutManager";

    private final Context mContext;
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
     * Publish a list of shortcuts.  All existing dynamic shortcuts from the caller application
     * will be replaced.
     *
     * <p>This API will be rate-limited.
     *
     * @return {@code true} if the call has succeeded. {@code false} if the call is rate-limited.
     *
     * @throws IllegalArgumentException if {@code shortcutInfoList} contains more than
     * {@link #getMaxDynamicShortcutCount()} shortcuts.
     */
    public boolean setDynamicShortcuts(@NonNull List<ShortcutInfo> shortcutInfoList) {
        try {
            return mService.setDynamicShortcuts(mContext.getPackageName(),
                    new ParceledListSlice(shortcutInfoList), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return all dynamic shortcuts from the caller application.  The number of result items
     * will not exceed the value returned by {@link #getMaxDynamicShortcutCount()}.
     */
    @NonNull
    public List<ShortcutInfo> getDynamicShortcuts() {
        try {
            return mService.getDynamicShortcuts(mContext.getPackageName(), injectMyUserId())
                    .getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Publish a single dynamic shortcut.  If there's already dynamic or pinned shortcuts with
     * the same ID, they will all be updated.
     *
     * <p>This API will be rate-limited.
     *
     * @return {@code true} if the call has succeeded. {@code false} if the call is rate-limited.
     *
     * @throws IllegalArgumentException if the caller application has already published the
     * max number of dynamic shortcuts.
     */
    public boolean addDynamicShortcuts(@NonNull List<ShortcutInfo> shortcutInfoList) {
        try {
            return mService.addDynamicShortcuts(mContext.getPackageName(),
                    new ParceledListSlice(shortcutInfoList), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Delete a single dynamic shortcut by ID.
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
     * Delete all dynamic shortcuts from the caller application.
     */
    public void removeAllDynamicShortcuts() {
        try {
            mService.removeAllDynamicShortcuts(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return all pinned shortcuts from the caller application.
     */
    @NonNull
    public List<ShortcutInfo> getPinnedShortcuts() {
        try {
            return mService.getPinnedShortcuts(mContext.getPackageName(), injectMyUserId())
                    .getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Update all existing shortcuts with the same IDs.  Shortcuts may be pinned and/or dynamic.
     *
     * <p>This API will be rate-limited.
     *
     * @return {@code true} if the call has succeeded. {@code false} if the call is rate-limited.
     */
    public boolean updateShortcuts(List<ShortcutInfo> shortcutInfoList) {
        try {
            return mService.updateShortcuts(mContext.getPackageName(),
                    new ParceledListSlice(shortcutInfoList), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the max number of dynamic shortcuts that each application can have at a time.
     */
    public int getMaxDynamicShortcutCount() {
        try {
            return mService.getMaxDynamicShortcutCount(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the number of times the caller application can call the rate-limited APIs
     * before the rate limit counter is reset.
     *
     * @see #getRateLimitResetTime()
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
     */
    public long getRateLimitResetTime() {
        try {
            return mService.getRateLimitResetTime(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the max width and height for icons, in pixels.
     */
    public int getIconMaxDimensions() {
        try {
            return mService.getIconMaxDimensions(mContext.getPackageName(), injectMyUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide injection point */
    @VisibleForTesting
    protected int injectMyUserId() {
        return UserHandle.myUserId();
    }
}
