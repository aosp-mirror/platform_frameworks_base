/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.server.search;

import com.android.internal.content.PackageMonitor;
import com.android.internal.util.IndentingPrintWriter;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.ISearchManager;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * The search manager service handles the search UI, and maintains a registry of searchable
 * activities.
 */
public class SearchManagerService extends ISearchManager.Stub {

    // general debugging support
    private static final String TAG = "SearchManagerService";

    // Context that the service is running in.
    private final Context mContext;

    // This field is initialized lazily in getSearchables(), and then never modified.
    private final SparseArray<Searchables> mSearchables = new SparseArray<Searchables>();

    /**
     * Initializes the Search Manager service in the provided system context.
     * Only one instance of this object should be created!
     *
     * @param context to use for accessing DB, window manager, etc.
     */
    public SearchManagerService(Context context)  {
        mContext = context;
        mContext.registerReceiver(new BootCompletedReceiver(),
                new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
        mContext.registerReceiver(new UserReceiver(),
                new IntentFilter(Intent.ACTION_USER_REMOVED));
        new MyPackageMonitor().register(context, null, UserHandle.ALL, true);
    }

    private Searchables getSearchables(int userId) {
        long origId = Binder.clearCallingIdentity();
        try {
            boolean userExists = ((UserManager) mContext.getSystemService(Context.USER_SERVICE))
                    .getUserInfo(userId) != null;
            if (!userExists) return null;
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
        synchronized (mSearchables) {
            Searchables searchables = mSearchables.get(userId);

            if (searchables == null) {
                Log.i(TAG, "Building list of searchable activities for userId=" + userId);
                searchables = new Searchables(mContext, userId);
                searchables.buildSearchableList();
                mSearchables.append(userId, searchables);
            }
            return searchables;
        }
    }

    private void onUserRemoved(int userId) {
        if (userId != UserHandle.USER_OWNER) {
            synchronized (mSearchables) {
                mSearchables.remove(userId);
            }
        }
    }

    /**
     * Creates the initial searchables list after boot.
     */
    private final class BootCompletedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            new Thread() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    mContext.unregisterReceiver(BootCompletedReceiver.this);
                    getSearchables(0);
                }
            }.start();
        }
    }

    private final class UserReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            onUserRemoved(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_OWNER));
        }
    }

    /**
     * Refreshes the "searchables" list when packages are added/removed.
     */
    class MyPackageMonitor extends PackageMonitor {

        @Override
        public void onSomePackagesChanged() {
            updateSearchables();
        }

        @Override
        public void onPackageModified(String pkg) {
            updateSearchables();
        }

        private void updateSearchables() {
            final int changingUserId = getChangingUserId();
            synchronized (mSearchables) {
                // Update list of searchable activities
                for (int i = 0; i < mSearchables.size(); i++) {
                    if (changingUserId == mSearchables.keyAt(i)) {
                        getSearchables(mSearchables.keyAt(i)).buildSearchableList();
                        break;
                    }
                }
            }
            // Inform all listeners that the list of searchables has been updated.
            Intent intent = new Intent(SearchManager.INTENT_ACTION_SEARCHABLES_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                    | Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendBroadcastAsUser(intent, new UserHandle(changingUserId));
        }
    }

    class GlobalSearchProviderObserver extends ContentObserver {
        private final ContentResolver mResolver;

        public GlobalSearchProviderObserver(ContentResolver resolver) {
            super(null);
            mResolver = resolver;
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.SEARCH_GLOBAL_SEARCH_ACTIVITY),
                    false /* notifyDescendants */,
                    this);
        }

        @Override
        public void onChange(boolean selfChange) {
            synchronized (mSearchables) {
                for (int i = 0; i < mSearchables.size(); i++) {
                    getSearchables(mSearchables.keyAt(i)).buildSearchableList();
                }
            }
            Intent intent = new Intent(SearchManager.INTENT_GLOBAL_SEARCH_ACTIVITY_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

    }

    //
    // Searchable activities API
    //

    /**
     * Returns the SearchableInfo for a given activity.
     *
     * @param launchActivity The activity from which we're launching this search.
     * @return Returns a SearchableInfo record describing the parameters of the search,
     * or null if no searchable metadata was available.
     */
    public SearchableInfo getSearchableInfo(final ComponentName launchActivity) {
        if (launchActivity == null) {
            Log.e(TAG, "getSearchableInfo(), activity == null");
            return null;
        }
        return getSearchables(UserHandle.getCallingUserId()).getSearchableInfo(launchActivity);
    }

    /**
     * Returns a list of the searchable activities that can be included in global search.
     */
    public List<SearchableInfo> getSearchablesInGlobalSearch() {
        return getSearchables(UserHandle.getCallingUserId()).getSearchablesInGlobalSearchList();
    }

    public List<ResolveInfo> getGlobalSearchActivities() {
        return getSearchables(UserHandle.getCallingUserId()).getGlobalSearchActivities();
    }

    /**
     * Gets the name of the global search activity.
     */
    public ComponentName getGlobalSearchActivity() {
        return getSearchables(UserHandle.getCallingUserId()).getGlobalSearchActivity();
    }

    /**
     * Gets the name of the web search activity.
     */
    public ComponentName getWebSearchActivity() {
        return getSearchables(UserHandle.getCallingUserId()).getWebSearchActivity();
    }

    @Override
    public ComponentName getAssistIntent(int userHandle) {
        try {
            if (userHandle != UserHandle.getCallingUserId()) {
                // Requesting a different user, make sure that they have the permission
                if (ActivityManager.checkComponentPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                        Binder.getCallingUid(), -1, true)
                        == PackageManager.PERMISSION_GRANTED) {
                    // Translate to the current user id, if caller wasn't aware
                    if (userHandle == UserHandle.USER_CURRENT) {
                        long identity = Binder.clearCallingIdentity();
                        userHandle = ActivityManagerNative.getDefault().getCurrentUser().id;
                        Binder.restoreCallingIdentity(identity);
                    }
                } else {
                    String msg = "Permission Denial: "
                            + "Request to getAssistIntent for " + userHandle
                            + " but is calling from user " + UserHandle.getCallingUserId()
                            + "; this requires "
                            + android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
                    Slog.w(TAG, msg);
                    return null;
                }
            }
            IPackageManager pm = AppGlobals.getPackageManager();
            Intent assistIntent = new Intent(Intent.ACTION_ASSIST);
            ResolveInfo info =
                    pm.resolveIntent(assistIntent,
                    assistIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    PackageManager.MATCH_DEFAULT_ONLY, userHandle);
            if (info != null) {
                return new ComponentName(
                        info.activityInfo.applicationInfo.packageName,
                        info.activityInfo.name);
            }
        } catch (RemoteException re) {
            // Local call
            Log.e(TAG, "RemoteException in getAssistIntent: " + re);
        } catch (Exception e) {
            Log.e(TAG, "Exception in getAssistIntent: " + e);
        }
        return null;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        synchronized (mSearchables) {
            for (int i = 0; i < mSearchables.size(); i++) {
                ipw.print("\nUser: "); ipw.println(mSearchables.keyAt(i));
                ipw.increaseIndent();
                mSearchables.valueAt(i).dump(fd, ipw, args);
                ipw.decreaseIndent();
            }
        }
    }
}
