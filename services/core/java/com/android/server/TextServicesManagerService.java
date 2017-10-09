/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.inputmethod.InputMethodUtils;
import com.android.internal.textservice.ISpellCheckerService;
import com.android.internal.textservice.ISpellCheckerServiceCallback;
import com.android.internal.textservice.ISpellCheckerSession;
import com.android.internal.textservice.ISpellCheckerSessionListener;
import com.android.internal.textservice.ITextServicesManager;
import com.android.internal.textservice.ITextServicesSessionListener;
import com.android.internal.util.DumpUtils;

import org.xmlpull.v1.XmlPullParserException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.textservice.SpellCheckerService;
import android.text.TextUtils;
import android.util.Slog;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.SpellCheckerSubtype;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TextServicesManagerService extends ITextServicesManager.Stub {
    private static final String TAG = TextServicesManagerService.class.getSimpleName();
    private static final boolean DBG = false;

    private final Context mContext;
    private boolean mSystemReady;
    private final TextServicesMonitor mMonitor;
    private final HashMap<String, SpellCheckerInfo> mSpellCheckerMap = new HashMap<>();
    private final ArrayList<SpellCheckerInfo> mSpellCheckerList = new ArrayList<>();
    private final HashMap<String, SpellCheckerBindGroup> mSpellCheckerBindGroups = new HashMap<>();
    private final TextServicesSettings mSettings;
    @NonNull
    private final UserManager mUserManager;
    private final Object mLock = new Object();

    public static final class Lifecycle extends SystemService {
        private TextServicesManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new TextServicesManagerService(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.TEXT_SERVICES_MANAGER_SERVICE, mService);
        }

        @Override
        public void onSwitchUser(@UserIdInt int userHandle) {
            // Called on the system server's main looper thread.
            // TODO: Dispatch this to a worker thread as needed.
            mService.onSwitchUser(userHandle);
        }

        @Override
        public void onBootPhase(int phase) {
            // Called on the system server's main looper thread.
            // TODO: Dispatch this to a worker thread as needed.
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mService.systemRunning();
            }
        }

        @Override
        public void onUnlockUser(@UserIdInt int userHandle) {
            // Called on the system server's main looper thread.
            // TODO: Dispatch this to a worker thread as needed.
            mService.onUnlockUser(userHandle);
        }
    }

    void systemRunning() {
        synchronized (mLock) {
            if (!mSystemReady) {
                mSystemReady = true;
                resetInternalState(mSettings.getCurrentUserId());
            }
        }
    }

    void onSwitchUser(@UserIdInt int userId) {
        synchronized (mLock) {
            resetInternalState(userId);
        }
    }

    void onUnlockUser(@UserIdInt int userId) {
        synchronized (mLock) {
            final int currentUserId = mSettings.getCurrentUserId();
            if (userId != currentUserId) {
                return;
            }
            resetInternalState(currentUserId);
        }
    }

    public TextServicesManagerService(Context context) {
        mSystemReady = false;
        mContext = context;

        mUserManager = mContext.getSystemService(UserManager.class);

        final IntentFilter broadcastFilter = new IntentFilter();
        broadcastFilter.addAction(Intent.ACTION_USER_ADDED);
        broadcastFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(new TextServicesBroadcastReceiver(), broadcastFilter);

        int userId = UserHandle.USER_SYSTEM;
        try {
            userId = ActivityManager.getService().getCurrentUser().id;
        } catch (RemoteException e) {
            Slog.w(TAG, "Couldn't get current user ID; guessing it's 0", e);
        }
        mMonitor = new TextServicesMonitor();
        mMonitor.register(context, null, true);
        final boolean useCopyOnWriteSettings =
                !mSystemReady || !mUserManager.isUserUnlockingOrUnlocked(userId);
        mSettings = new TextServicesSettings(context.getContentResolver(), userId,
                useCopyOnWriteSettings);

        // "resetInternalState" initializes the states for the foreground user
        resetInternalState(userId);
    }

    private void resetInternalState(@UserIdInt int userId) {
        final boolean useCopyOnWriteSettings =
                !mSystemReady || !mUserManager.isUserUnlockingOrUnlocked(userId);
        mSettings.switchCurrentUser(userId, useCopyOnWriteSettings);
        updateCurrentProfileIds();
        unbindServiceLocked();
        buildSpellCheckerMapLocked(mContext, mSpellCheckerList, mSpellCheckerMap, mSettings);
        SpellCheckerInfo sci = getCurrentSpellChecker(null);
        if (sci == null) {
            sci = findAvailSystemSpellCheckerLocked(null);
            // Set the current spell checker if there is one or more system spell checkers
            // available. In this case, "sci" is the first one in the available spell
            // checkers.
            setCurrentSpellCheckerLocked(sci);
        }
    }

    void updateCurrentProfileIds() {
        mSettings.setCurrentProfileIds(
                mUserManager.getProfileIdsWithDisabled(mSettings.getCurrentUserId()));
    }

    private final class TextServicesMonitor extends PackageMonitor {
        private boolean isChangingPackagesOfCurrentUser() {
            final int userId = getChangingUserId();
            final boolean retval = userId == mSettings.getCurrentUserId();
            if (DBG) {
                Slog.d(TAG, "--- ignore this call back from a background user: " + userId);
            }
            return retval;
        }

        @Override
        public void onSomePackagesChanged() {
            if (!isChangingPackagesOfCurrentUser()) {
                return;
            }
            synchronized (mLock) {
                // TODO: Update for each locale
                SpellCheckerInfo sci = getCurrentSpellChecker(null);
                buildSpellCheckerMapLocked(
                        mContext, mSpellCheckerList, mSpellCheckerMap, mSettings);
                // If spell checker is disabled, just return. The user should explicitly
                // enable the spell checker.
                if (!isSpellCheckerEnabledLocked()) return;

                if (sci == null) {
                    sci = findAvailSystemSpellCheckerLocked(null);
                    // Set the current spell checker if there is one or more system spell checkers
                    // available. In this case, "sci" is the first one in the available spell
                    // checkers.
                    setCurrentSpellCheckerLocked(sci);
                } else {
                    final String packageName = sci.getPackageName();
                    final int change = isPackageDisappearing(packageName);
                    if (// Package disappearing
                            change == PACKAGE_PERMANENT_CHANGE || change == PACKAGE_TEMPORARY_CHANGE
                                    // Package modified
                                    || isPackageModified(packageName)) {
                        SpellCheckerInfo availSci = findAvailSystemSpellCheckerLocked(packageName);
                        // Set the spell checker settings if different than before
                        if (availSci != null && !availSci.getId().equals(sci.getId())) {
                            setCurrentSpellCheckerLocked(availSci);
                        }
                    }
                }
            }
        }
    }

    private final class TextServicesBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_USER_ADDED.equals(action)
                    || Intent.ACTION_USER_REMOVED.equals(action)) {
                updateCurrentProfileIds();
                return;
            }
            Slog.w(TAG, "Unexpected intent " + intent);
        }
    }

    private static void buildSpellCheckerMapLocked(Context context,
            ArrayList<SpellCheckerInfo> list, HashMap<String, SpellCheckerInfo> map,
            TextServicesSettings settings) {
        list.clear();
        map.clear();
        final PackageManager pm = context.getPackageManager();
        // Note: We do not specify PackageManager.MATCH_ENCRYPTION_* flags here because the default
        // behavior of PackageManager is exactly what we want.  It by default picks up appropriate
        // services depending on the unlock state for the specified user.
        final List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                new Intent(SpellCheckerService.SERVICE_INTERFACE), PackageManager.GET_META_DATA,
                settings.getCurrentUserId());
        final int N = services.size();
        for (int i = 0; i < N; ++i) {
            final ResolveInfo ri = services.get(i);
            final ServiceInfo si = ri.serviceInfo;
            final ComponentName compName = new ComponentName(si.packageName, si.name);
            if (!android.Manifest.permission.BIND_TEXT_SERVICE.equals(si.permission)) {
                Slog.w(TAG, "Skipping text service " + compName
                        + ": it does not require the permission "
                        + android.Manifest.permission.BIND_TEXT_SERVICE);
                continue;
            }
            if (DBG) Slog.d(TAG, "Add: " + compName);
            try {
                final SpellCheckerInfo sci = new SpellCheckerInfo(context, ri);
                if (sci.getSubtypeCount() <= 0) {
                    Slog.w(TAG, "Skipping text service " + compName
                            + ": it does not contain subtypes.");
                    continue;
                }
                list.add(sci);
                map.put(sci.getId(), sci);
            } catch (XmlPullParserException e) {
                Slog.w(TAG, "Unable to load the spell checker " + compName, e);
            } catch (IOException e) {
                Slog.w(TAG, "Unable to load the spell checker " + compName, e);
            }
        }
        if (DBG) {
            Slog.d(TAG, "buildSpellCheckerMapLocked: " + list.size() + "," + map.size());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Check whether or not this is a valid IPC. Assumes an IPC is valid when either
    // 1) it comes from the system process
    // 2) the calling process' user id is identical to the current user id TSMS thinks.
    private boolean calledFromValidUser() {
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(uid);
        if (DBG) {
            Slog.d(TAG, "--- calledFromForegroundUserOrSystemProcess ? "
                    + "calling uid = " + uid + " system uid = " + Process.SYSTEM_UID
                    + " calling userId = " + userId + ", foreground user id = "
                    + mSettings.getCurrentUserId() + ", calling pid = " + Binder.getCallingPid());
            try {
                final String[] packageNames = AppGlobals.getPackageManager().getPackagesForUid(uid);
                for (int i = 0; i < packageNames.length; ++i) {
                    if (DBG) {
                        Slog.d(TAG, "--- process name for "+ uid + " = " + packageNames[i]);
                    }
                }
            } catch (RemoteException e) {
            }
        }

        if (uid == Process.SYSTEM_UID || userId == mSettings.getCurrentUserId()) {
            return true;
        }

        // Permits current profile to use TSFM as long as the current text service is the system's
        // one. This is a tentative solution and should be replaced with fully functional multiuser
        // support.
        // TODO: Implement multiuser support in TSMS.
        final boolean isCurrentProfile = mSettings.isCurrentProfile(userId);
        if (DBG) {
            Slog.d(TAG, "--- userId = "+ userId + " isCurrentProfile = " + isCurrentProfile);
        }
        if (mSettings.isCurrentProfile(userId)) {
            final SpellCheckerInfo spellCheckerInfo = getCurrentSpellCheckerWithoutVerification();
            if (spellCheckerInfo != null) {
                final ServiceInfo serviceInfo = spellCheckerInfo.getServiceInfo();
                final boolean isSystemSpellChecker =
                        (serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (DBG) {
                    Slog.d(TAG, "--- current spell checker = "+ spellCheckerInfo.getPackageName()
                            + " isSystem = " + isSystemSpellChecker);
                }
                if (isSystemSpellChecker) {
                    return true;
                }
            }
        }

        // Unlike InputMethodManagerService#calledFromValidUser, INTERACT_ACROSS_USERS_FULL isn't
        // taken into account here.  Anyway this method is supposed to be removed once multiuser
        // support is implemented.
        if (DBG) {
            Slog.d(TAG, "--- IPC from userId:" + userId + " is being ignored. \n"
                    + getStackTrace());
        }
        return false;
    }

    private boolean bindCurrentSpellCheckerService(
            Intent service, ServiceConnection conn, int flags) {
        if (service == null || conn == null) {
            Slog.e(TAG, "--- bind failed: service = " + service + ", conn = " + conn);
            return false;
        }
        return mContext.bindServiceAsUser(service, conn, flags,
                new UserHandle(mSettings.getCurrentUserId()));
    }

    private void unbindServiceLocked() {
        for (SpellCheckerBindGroup scbg : mSpellCheckerBindGroups.values()) {
            scbg.removeAllLocked();
        }
        mSpellCheckerBindGroups.clear();
    }

    private SpellCheckerInfo findAvailSystemSpellCheckerLocked(String prefPackage) {
        // Filter the spell checker list to remove spell checker services that are not pre-installed
        ArrayList<SpellCheckerInfo> spellCheckerList = new ArrayList<>();
        for (SpellCheckerInfo sci : mSpellCheckerList) {
            if ((sci.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                spellCheckerList.add(sci);
            }
        }

        final int spellCheckersCount = spellCheckerList.size();
        if (spellCheckersCount == 0) {
            Slog.w(TAG, "no available spell checker services found");
            return null;
        }
        if (prefPackage != null) {
            for (int i = 0; i < spellCheckersCount; ++i) {
                final SpellCheckerInfo sci = spellCheckerList.get(i);
                if (prefPackage.equals(sci.getPackageName())) {
                    if (DBG) {
                        Slog.d(TAG, "findAvailSystemSpellCheckerLocked: " + sci.getPackageName());
                    }
                    return sci;
                }
            }
        }

        // Look up a spell checker based on the system locale.
        // TODO: Still there is a room to improve in the following logic: e.g., check if the package
        // is pre-installed or not.
        final Locale systemLocal = mContext.getResources().getConfiguration().locale;
        final ArrayList<Locale> suitableLocales =
                InputMethodUtils.getSuitableLocalesForSpellChecker(systemLocal);
        if (DBG) {
            Slog.w(TAG, "findAvailSystemSpellCheckerLocked suitableLocales="
                    + Arrays.toString(suitableLocales.toArray(new Locale[suitableLocales.size()])));
        }
        final int localeCount = suitableLocales.size();
        for (int localeIndex = 0; localeIndex < localeCount; ++localeIndex) {
            final Locale locale = suitableLocales.get(localeIndex);
            for (int spellCheckersIndex = 0; spellCheckersIndex < spellCheckersCount;
                    ++spellCheckersIndex) {
                final SpellCheckerInfo info = spellCheckerList.get(spellCheckersIndex);
                final int subtypeCount = info.getSubtypeCount();
                for (int subtypeIndex = 0; subtypeIndex < subtypeCount; ++subtypeIndex) {
                    final SpellCheckerSubtype subtype = info.getSubtypeAt(subtypeIndex);
                    final Locale subtypeLocale = InputMethodUtils.constructLocaleFromString(
                            subtype.getLocale());
                    if (locale.equals(subtypeLocale)) {
                        // TODO: We may have more spell checkers that fall into this category.
                        // Ideally we should pick up the most suitable one instead of simply
                        // returning the first found one.
                        return info;
                    }
                }
            }
        }

        if (spellCheckersCount > 1) {
            Slog.w(TAG, "more than one spell checker service found, picking first");
        }
        return spellCheckerList.get(0);
    }

    // TODO: Save SpellCheckerService by supported languages. Currently only one spell
    // checker is saved.
    @Override
    public SpellCheckerInfo getCurrentSpellChecker(String locale) {
        // TODO: Make this work even for non-current users?
        if (!calledFromValidUser()) {
            return null;
        }
        return getCurrentSpellCheckerWithoutVerification();
    }

    private SpellCheckerInfo getCurrentSpellCheckerWithoutVerification() {
        synchronized (mLock) {
            final String curSpellCheckerId = mSettings.getSelectedSpellChecker();
            if (DBG) {
                Slog.w(TAG, "getCurrentSpellChecker: " + curSpellCheckerId);
            }
            if (TextUtils.isEmpty(curSpellCheckerId)) {
                return null;
            }
            return mSpellCheckerMap.get(curSpellCheckerId);
        }
    }

    // TODO: Respect allowImplicitlySelectedSubtype
    // TODO: Save SpellCheckerSubtype by supported languages by looking at "locale".
    @Override
    public SpellCheckerSubtype getCurrentSpellCheckerSubtype(
            String locale, boolean allowImplicitlySelectedSubtype) {
        // TODO: Make this work even for non-current users?
        if (!calledFromValidUser()) {
            return null;
        }
        final int subtypeHashCode;
        final SpellCheckerInfo sci;
        final Locale systemLocale;
        synchronized (mLock) {
            subtypeHashCode =
                    mSettings.getSelectedSpellCheckerSubtype(SpellCheckerSubtype.SUBTYPE_ID_NONE);
            if (DBG) {
                Slog.w(TAG, "getCurrentSpellCheckerSubtype: " + subtypeHashCode);
            }
            sci = getCurrentSpellChecker(null);
            systemLocale = mContext.getResources().getConfiguration().locale;
        }
        if (sci == null || sci.getSubtypeCount() == 0) {
            if (DBG) {
                Slog.w(TAG, "Subtype not found.");
            }
            return null;
        }
        if (subtypeHashCode == SpellCheckerSubtype.SUBTYPE_ID_NONE
                && !allowImplicitlySelectedSubtype) {
            return null;
        }
        String candidateLocale = null;
        if (subtypeHashCode == 0) {
            // Spell checker language settings == "auto"
            final InputMethodManager imm = mContext.getSystemService(InputMethodManager.class);
            if (imm != null) {
                final InputMethodSubtype currentInputMethodSubtype =
                        imm.getCurrentInputMethodSubtype();
                if (currentInputMethodSubtype != null) {
                    final String localeString = currentInputMethodSubtype.getLocale();
                    if (!TextUtils.isEmpty(localeString)) {
                        // 1. Use keyboard locale if available in the spell checker
                        candidateLocale = localeString;
                    }
                }
            }
            if (candidateLocale == null) {
                // 2. Use System locale if available in the spell checker
                candidateLocale = systemLocale.toString();
            }
        }
        SpellCheckerSubtype candidate = null;
        for (int i = 0; i < sci.getSubtypeCount(); ++i) {
            final SpellCheckerSubtype scs = sci.getSubtypeAt(i);
            if (subtypeHashCode == 0) {
                final String scsLocale = scs.getLocale();
                if (candidateLocale.equals(scsLocale)) {
                    return scs;
                } else if (candidate == null) {
                    if (candidateLocale.length() >= 2 && scsLocale.length() >= 2
                            && candidateLocale.startsWith(scsLocale)) {
                        // Fall back to the applicable language
                        candidate = scs;
                    }
                }
            } else if (scs.hashCode() == subtypeHashCode) {
                if (DBG) {
                    Slog.w(TAG, "Return subtype " + scs.hashCode() + ", input= " + locale
                            + ", " + scs.getLocale());
                }
                // 3. Use the user specified spell check language
                return scs;
            }
        }
        // 4. Fall back to the applicable language and return it if not null
        // 5. Simply just return it even if it's null which means we could find no suitable
        // spell check languages
        return candidate;
    }

    @Override
    public void getSpellCheckerService(String sciId, String locale,
            ITextServicesSessionListener tsListener, ISpellCheckerSessionListener scListener,
            Bundle bundle) {
        if (!calledFromValidUser()) {
            return;
        }
        if (!mSystemReady) {
            return;
        }
        if (TextUtils.isEmpty(sciId) || tsListener == null || scListener == null) {
            Slog.e(TAG, "getSpellCheckerService: Invalid input.");
            return;
        }
        synchronized (mLock) {
            if (!mSpellCheckerMap.containsKey(sciId)) {
                return;
            }
            final SpellCheckerInfo sci = mSpellCheckerMap.get(sciId);
            SpellCheckerBindGroup bindGroup = mSpellCheckerBindGroups.get(sciId);
            final int uid = Binder.getCallingUid();
            if (bindGroup == null) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    bindGroup = startSpellCheckerServiceInnerLocked(sci);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
                if (bindGroup == null) {
                    // startSpellCheckerServiceInnerLocked failed.
                    return;
                }
            }

            // Start getISpellCheckerSession async IPC, or just queue the request until the spell
            // checker service is bound.
            bindGroup.getISpellCheckerSessionOrQueueLocked(
                     new SessionRequest(uid, locale, tsListener, scListener, bundle));
        }
    }

    @Override
    public boolean isSpellCheckerEnabled() {
        if (!calledFromValidUser()) {
            return false;
        }
        synchronized (mLock) {
            return isSpellCheckerEnabledLocked();
        }
    }

    @Nullable
    private SpellCheckerBindGroup startSpellCheckerServiceInnerLocked(SpellCheckerInfo info) {
        if (DBG) {
            Slog.w(TAG, "Start spell checker session inner locked.");
        }
        final String sciId = info.getId();
        final InternalServiceConnection connection = new InternalServiceConnection(sciId);
        final Intent serviceIntent = new Intent(SpellCheckerService.SERVICE_INTERFACE);
        serviceIntent.setComponent(info.getComponent());
        if (DBG) {
            Slog.w(TAG, "bind service: " + info.getId());
        }
        if (!bindCurrentSpellCheckerService(serviceIntent, connection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT_BACKGROUND)) {
            Slog.e(TAG, "Failed to get a spell checker service.");
            return null;
        }
        final SpellCheckerBindGroup group = new SpellCheckerBindGroup(connection);
        mSpellCheckerBindGroups.put(sciId, group);
        return group;
    }

    @Override
    public SpellCheckerInfo[] getEnabledSpellCheckers() {
        // TODO: Make this work even for non-current users?
        if (!calledFromValidUser()) {
            return null;
        }
        if (DBG) {
            Slog.d(TAG, "getEnabledSpellCheckers: " + mSpellCheckerList.size());
            for (int i = 0; i < mSpellCheckerList.size(); ++i) {
                Slog.d(TAG, "EnabledSpellCheckers: " + mSpellCheckerList.get(i).getPackageName());
            }
        }
        return mSpellCheckerList.toArray(new SpellCheckerInfo[mSpellCheckerList.size()]);
    }

    @Override
    public void finishSpellCheckerService(ISpellCheckerSessionListener listener) {
        if (!calledFromValidUser()) {
            return;
        }
        if (DBG) {
            Slog.d(TAG, "FinishSpellCheckerService");
        }
        synchronized (mLock) {
            final ArrayList<SpellCheckerBindGroup> removeList = new ArrayList<>();
            for (SpellCheckerBindGroup group : mSpellCheckerBindGroups.values()) {
                if (group == null) continue;
                // Use removeList to avoid modifying mSpellCheckerBindGroups in this loop.
                removeList.add(group);
            }
            final int removeSize = removeList.size();
            for (int i = 0; i < removeSize; ++i) {
                removeList.get(i).removeListener(listener);
            }
        }
    }

    private void setCurrentSpellCheckerLocked(@Nullable SpellCheckerInfo sci) {
        final String sciId = (sci != null) ? sci.getId() : "";
        if (DBG) {
            Slog.w(TAG, "setCurrentSpellChecker: " + sciId);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mSettings.putSelectedSpellChecker(sciId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setCurrentSpellCheckerSubtypeLocked(int hashCode) {
        if (DBG) {
            Slog.w(TAG, "setCurrentSpellCheckerSubtype: " + hashCode);
        }
        final SpellCheckerInfo sci = getCurrentSpellChecker(null);
        int tempHashCode = 0;
        for (int i = 0; sci != null && i < sci.getSubtypeCount(); ++i) {
            if(sci.getSubtypeAt(i).hashCode() == hashCode) {
                tempHashCode = hashCode;
                break;
            }
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mSettings.putSelectedSpellCheckerSubtype(tempHashCode);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean isSpellCheckerEnabledLocked() {
        final long ident = Binder.clearCallingIdentity();
        try {
            final boolean retval = mSettings.isSpellCheckerEnabled();
            if (DBG) {
                Slog.w(TAG, "getSpellCheckerEnabled: " + retval);
            }
            return retval;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        synchronized (mLock) {
            pw.println("Current Text Services Manager state:");
            pw.println("  Spell Checkers:");
            int spellCheckerIndex = 0;
            for (final SpellCheckerInfo info : mSpellCheckerMap.values()) {
                pw.println("  Spell Checker #" + spellCheckerIndex);
                info.dump(pw, "    ");
                ++spellCheckerIndex;
            }
            pw.println("");
            pw.println("  Spell Checker Bind Groups:");
            for (final Map.Entry<String, SpellCheckerBindGroup> ent
                    : mSpellCheckerBindGroups.entrySet()) {
                final SpellCheckerBindGroup grp = ent.getValue();
                pw.println("    " + ent.getKey() + " " + grp + ":");
                pw.println("      " + "mInternalConnection=" + grp.mInternalConnection);
                pw.println("      " + "mSpellChecker=" + grp.mSpellChecker);
                pw.println("      " + "mUnbindCalled=" + grp.mUnbindCalled);
                pw.println("      " + "mConnected=" + grp.mConnected);
                final int numPendingSessionRequests = grp.mPendingSessionRequests.size();
                for (int i = 0; i < numPendingSessionRequests; i++) {
                    final SessionRequest req = grp.mPendingSessionRequests.get(i);
                    pw.println("      " + "Pending Request #" + i + ":");
                    pw.println("        " + "mTsListener=" + req.mTsListener);
                    pw.println("        " + "mScListener=" + req.mScListener);
                    pw.println("        " + "mScLocale=" + req.mLocale + " mUid=" + req.mUserId);
                }
                final int numOnGoingSessionRequests = grp.mOnGoingSessionRequests.size();
                for (int i = 0; i < numOnGoingSessionRequests; i++) {
                    final SessionRequest req = grp.mOnGoingSessionRequests.get(i);
                    pw.println("      " + "On going Request #" + i + ":");
                    ++i;
                    pw.println("        " + "mTsListener=" + req.mTsListener);
                    pw.println("        " + "mScListener=" + req.mScListener);
                    pw.println(
                            "        " + "mScLocale=" + req.mLocale + " mUid=" + req.mUserId);
                }
                final int N = grp.mListeners.getRegisteredCallbackCount();
                for (int i = 0; i < N; i++) {
                    final ISpellCheckerSessionListener mScListener =
                            grp.mListeners.getRegisteredCallbackItem(i);
                    pw.println("      " + "Listener #" + i + ":");
                    pw.println("        " + "mScListener=" + mScListener);
                    pw.println("        " + "mGroup=" + grp);
                }
            }
            pw.println("");
            pw.println("  mSettings:");
            mSettings.dumpLocked(pw, "    ");
        }
    }

    private static final class SessionRequest {
        @UserIdInt
        public final int mUserId;
        @Nullable
        public final String mLocale;
        @NonNull
        public final ITextServicesSessionListener mTsListener;
        @NonNull
        public final ISpellCheckerSessionListener mScListener;
        @Nullable
        public final Bundle mBundle;

        SessionRequest(@UserIdInt final int userId, @Nullable String locale,
                @NonNull ITextServicesSessionListener tsListener,
                @NonNull ISpellCheckerSessionListener scListener, @Nullable Bundle bundle) {
            mUserId = userId;
            mLocale = locale;
            mTsListener = tsListener;
            mScListener = scListener;
            mBundle = bundle;
        }
    }

    // SpellCheckerBindGroup contains active text service session listeners.
    // If there are no listeners anymore, the SpellCheckerBindGroup instance will be removed from
    // mSpellCheckerBindGroups
    private final class SpellCheckerBindGroup {
        private final String TAG = SpellCheckerBindGroup.class.getSimpleName();
        private final InternalServiceConnection mInternalConnection;
        private final InternalDeathRecipients mListeners;
        private boolean mUnbindCalled;
        private ISpellCheckerService mSpellChecker;
        private boolean mConnected;
        private final ArrayList<SessionRequest> mPendingSessionRequests = new ArrayList<>();
        private final ArrayList<SessionRequest> mOnGoingSessionRequests = new ArrayList<>();

        public SpellCheckerBindGroup(InternalServiceConnection connection) {
            mInternalConnection = connection;
            mListeners = new InternalDeathRecipients(this);
        }

        public void onServiceConnectedLocked(ISpellCheckerService spellChecker) {
            if (DBG) {
                Slog.d(TAG, "onServiceConnectedLocked");
            }

            if (mUnbindCalled) {
                return;
            }
            mSpellChecker = spellChecker;
            mConnected = true;
            // Dispatch pending getISpellCheckerSession requests.
            try {
                final int size = mPendingSessionRequests.size();
                for (int i = 0; i < size; ++i) {
                    final SessionRequest request = mPendingSessionRequests.get(i);
                    mSpellChecker.getISpellCheckerSession(
                            request.mLocale, request.mScListener, request.mBundle,
                            new ISpellCheckerServiceCallbackBinder(this, request));
                    mOnGoingSessionRequests.add(request);
                }
                mPendingSessionRequests.clear();
            } catch(RemoteException e) {
                // The target spell checker service is not available.  Better to reset the state.
                removeAllLocked();
            }
            cleanLocked();
        }

        public void onServiceDisconnectedLocked() {
            if (DBG) {
                Slog.d(TAG, "onServiceDisconnectedLocked");
            }

            mSpellChecker = null;
            mConnected = false;
        }

        public void removeListener(ISpellCheckerSessionListener listener) {
            if (DBG) {
                Slog.w(TAG, "remove listener: " + listener.hashCode());
            }
            synchronized (mLock) {
                mListeners.unregister(listener);
                cleanLocked();
            }
        }

        // cleanLocked may remove elements from mSpellCheckerBindGroups
        private void cleanLocked() {
            if (DBG) {
                Slog.d(TAG, "cleanLocked");
            }
            if (mUnbindCalled) {
                return;
            }
            // If there are no more active listeners, clean up.  Only do this once.
            if (mListeners.getRegisteredCallbackCount() > 0) {
                return;
            }
            if (!mPendingSessionRequests.isEmpty()) {
                return;
            }
            if (!mOnGoingSessionRequests.isEmpty()) {
                return;
            }
            final String sciId = mInternalConnection.mSciId;
            final SpellCheckerBindGroup cur = mSpellCheckerBindGroups.get(sciId);
            if (cur == this) {
                if (DBG) {
                    Slog.d(TAG, "Remove bind group.");
                }
                mSpellCheckerBindGroups.remove(sciId);
            }
            mContext.unbindService(mInternalConnection);
            mUnbindCalled = true;
        }

        public void removeAllLocked() {
            Slog.e(TAG, "Remove the spell checker bind unexpectedly.");
            final int size = mListeners.getRegisteredCallbackCount();
            for (int i = size - 1; i >= 0; --i) {
                mListeners.unregister(mListeners.getRegisteredCallbackItem(i));
            }
            mPendingSessionRequests.clear();
            mOnGoingSessionRequests.clear();
            cleanLocked();
        }

        public void getISpellCheckerSessionOrQueueLocked(@NonNull SessionRequest request) {
            if (mUnbindCalled) {
                return;
            }
            if (!mConnected) {
                mPendingSessionRequests.add(request);
                return;
            }
            try {
                mSpellChecker.getISpellCheckerSession(
                        request.mLocale, request.mScListener, request.mBundle,
                        new ISpellCheckerServiceCallbackBinder(this, request));
                mOnGoingSessionRequests.add(request);
            } catch(RemoteException e) {
                // The target spell checker service is not available.  Better to reset the state.
                removeAllLocked();
            }
            cleanLocked();
        }

        void onSessionCreated(@Nullable final ISpellCheckerSession newSession,
                @NonNull final SessionRequest request) {
            synchronized (mLock) {
                if (mUnbindCalled) {
                    return;
                }
                if (mOnGoingSessionRequests.remove(request)) {
                    try {
                        request.mTsListener.onServiceConnected(newSession);
                        mListeners.register(request.mScListener);
                    } catch (RemoteException e) {
                        // Technically this can happen if the spell checker client app is already
                        // dead.  We can just forget about this request; the request is already
                        // removed from mOnGoingSessionRequests and the death recipient listener is
                        // not yet added to mListeners. There is nothing to release further.
                    }
                }
                cleanLocked();
            }
        }
    }

    private final class InternalServiceConnection implements ServiceConnection {
        private final String mSciId;
        public InternalServiceConnection(String id) {
            mSciId = id;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                onServiceConnectedInnerLocked(name, service);
            }
        }

        private void onServiceConnectedInnerLocked(ComponentName name, IBinder service) {
            if (DBG) {
                Slog.w(TAG, "onServiceConnectedInnerLocked: " + name);
            }
            final ISpellCheckerService spellChecker =
                    ISpellCheckerService.Stub.asInterface(service);
            final SpellCheckerBindGroup group = mSpellCheckerBindGroups.get(mSciId);
            if (group != null && this == group.mInternalConnection) {
                group.onServiceConnectedLocked(spellChecker);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                onServiceDisconnectedInnerLocked(name);
            }
        }

        private void onServiceDisconnectedInnerLocked(ComponentName name) {
            if (DBG) {
                Slog.w(TAG, "onServiceDisconnectedInnerLocked: " + name);
            }
            final SpellCheckerBindGroup group = mSpellCheckerBindGroups.get(mSciId);
            if (group != null && this == group.mInternalConnection) {
                group.onServiceDisconnectedLocked();
            }
        }
    }

    private static final class InternalDeathRecipients extends
            RemoteCallbackList<ISpellCheckerSessionListener> {
        private final SpellCheckerBindGroup mGroup;

        public InternalDeathRecipients(SpellCheckerBindGroup group) {
            mGroup = group;
        }

        @Override
        public void onCallbackDied(ISpellCheckerSessionListener listener) {
            mGroup.removeListener(listener);
        }
    }

    private static final class ISpellCheckerServiceCallbackBinder
            extends ISpellCheckerServiceCallback.Stub {
        @NonNull
        private final SpellCheckerBindGroup mBindGroup;
        @NonNull
        private final SessionRequest mRequest;

        ISpellCheckerServiceCallbackBinder(@NonNull final SpellCheckerBindGroup bindGroup,
                @NonNull final SessionRequest request) {
            mBindGroup = bindGroup;
            mRequest = request;
        }

        @Override
        public void onSessionCreated(@Nullable ISpellCheckerSession newSession) {
            mBindGroup.onSessionCreated(newSession, mRequest);
        }
    }

    private static final class TextServicesSettings {
        private final ContentResolver mResolver;
        @UserIdInt
        private int mCurrentUserId;
        @GuardedBy("mLock")
        private int[] mCurrentProfileIds = new int[0];
        private Object mLock = new Object();

        /**
         * On-memory data store to emulate when {@link #mCopyOnWrite} is {@code true}.
         */
        private final HashMap<String, String> mCopyOnWriteDataStore = new HashMap<>();
        private boolean mCopyOnWrite = false;

        public TextServicesSettings(ContentResolver resolver, @UserIdInt int userId,
                boolean copyOnWrite) {
            mResolver = resolver;
            switchCurrentUser(userId, copyOnWrite);
        }

        /**
         * Must be called when the current user is changed.
         *
         * @param userId The user ID.
         * @param copyOnWrite If {@code true}, for each settings key
         * (e.g. {@link Settings.Secure#SELECTED_SPELL_CHECKER}) we use the actual settings on the
         * {@link Settings.Secure} until we do the first write operation.
         */
        public void switchCurrentUser(@UserIdInt int userId, boolean copyOnWrite) {
            if (DBG) {
                Slog.d(TAG, "--- Swtich the current user from " + mCurrentUserId + " to "
                        + userId + ", new ime = " + getSelectedSpellChecker());
            }
            if (mCurrentUserId != userId || mCopyOnWrite != copyOnWrite) {
                mCopyOnWriteDataStore.clear();
                // TODO: mCurrentProfileIds should be cleared here.
            }
            // TSMS settings are kept per user, so keep track of current user
            mCurrentUserId = userId;
            mCopyOnWrite = copyOnWrite;
            // TODO: mCurrentProfileIds should be updated here.
        }

        private void putString(final String key, final String str) {
            if (mCopyOnWrite) {
                mCopyOnWriteDataStore.put(key, str);
            } else {
                Settings.Secure.putStringForUser(mResolver, key, str, mCurrentUserId);
            }
        }

        @Nullable
        private String getString(@NonNull final String key, @Nullable final String defaultValue) {
            final String result;
            if (mCopyOnWrite && mCopyOnWriteDataStore.containsKey(key)) {
                result = mCopyOnWriteDataStore.get(key);
            } else {
                result = Settings.Secure.getStringForUser(mResolver, key, mCurrentUserId);
            }
            return result != null ? result : defaultValue;
        }

        private void putInt(final String key, final int value) {
            if (mCopyOnWrite) {
                mCopyOnWriteDataStore.put(key, String.valueOf(value));
            } else {
                Settings.Secure.putIntForUser(mResolver, key, value, mCurrentUserId);
            }
        }

        private int getInt(final String key, final int defaultValue) {
            if (mCopyOnWrite && mCopyOnWriteDataStore.containsKey(key)) {
                final String result = mCopyOnWriteDataStore.get(key);
                return result != null ? Integer.parseInt(result) : 0;
            }
            return Settings.Secure.getIntForUser(mResolver, key, defaultValue, mCurrentUserId);
        }

        private boolean getBoolean(final String key, final boolean defaultValue) {
            return getInt(key, defaultValue ? 1 : 0) == 1;
        }

        public void setCurrentProfileIds(int[] currentProfileIds) {
            synchronized (mLock) {
                mCurrentProfileIds = currentProfileIds;
            }
        }

        public boolean isCurrentProfile(@UserIdInt int userId) {
            synchronized (mLock) {
                if (userId == mCurrentUserId) return true;
                for (int i = 0; i < mCurrentProfileIds.length; i++) {
                    if (userId == mCurrentProfileIds[i]) return true;
                }
                return false;
            }
        }

        @UserIdInt
        public int getCurrentUserId() {
            return mCurrentUserId;
        }

        public void putSelectedSpellChecker(@Nullable String sciId) {
            putString(Settings.Secure.SELECTED_SPELL_CHECKER, sciId);
        }

        public void putSelectedSpellCheckerSubtype(int hashCode) {
            putInt(Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE, hashCode);
        }

        @NonNull
        public String getSelectedSpellChecker() {
            return getString(Settings.Secure.SELECTED_SPELL_CHECKER, "");
        }

        public int getSelectedSpellCheckerSubtype(final int defaultValue) {
            return getInt(Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE, defaultValue);
        }

        public boolean isSpellCheckerEnabled() {
            return getBoolean(Settings.Secure.SPELL_CHECKER_ENABLED, true);
        }

        public void dumpLocked(final PrintWriter pw, final String prefix) {
            pw.println(prefix + "mCurrentUserId=" + mCurrentUserId);
            pw.println(prefix + "mCurrentProfileIds=" + Arrays.toString(mCurrentProfileIds));
            pw.println(prefix + "mCopyOnWrite=" + mCopyOnWrite);
        }
    }

    // ----------------------------------------------------------------------
    // Utilities for debug
    private static String getStackTrace() {
        final StringBuilder sb = new StringBuilder();
        try {
            throw new RuntimeException();
        } catch (RuntimeException e) {
            final StackTraceElement[] frames = e.getStackTrace();
            // Start at 1 because the first frame is here and we don't care about it
            for (int j = 1; j < frames.length; ++j) {
                sb.append(frames[j].toString() + "\n");
            }
        }
        return sb.toString();
    }
}
