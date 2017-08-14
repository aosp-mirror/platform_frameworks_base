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
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.textservice.SpellCheckerService;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
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
    private final TextServicesMonitor mMonitor;
    private final SparseArray<TextServicesData> mUserData = new SparseArray<>();
    private final TextServicesSettings mSettings;
    @NonNull
    private final UserManager mUserManager;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private int mSpellCheckerMapUpdateCount = 0;

    private static class TextServicesData {
        @UserIdInt
        private final int mUserId;
        private final HashMap<String, SpellCheckerInfo> mSpellCheckerMap;
        private final ArrayList<SpellCheckerInfo> mSpellCheckerList;
        private final HashMap<String, SpellCheckerBindGroup> mSpellCheckerBindGroups;

        public TextServicesData(@UserIdInt int userId) {
            mUserId = userId;
            mSpellCheckerMap = new HashMap<>();
            mSpellCheckerList = new ArrayList<>();
            mSpellCheckerBindGroups = new HashMap<>();
        }
    }

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
        public void onStopUser(@UserIdInt int userHandle) {
            if (DBG) {
                Slog.d(TAG, "onStopUser userId: " + userHandle);
            }
            mService.onStopUser(userHandle);
        }

        @Override
        public void onUnlockUser(@UserIdInt int userHandle) {
            if(DBG) {
                Slog.d(TAG, "onUnlockUser userId: " + userHandle);
            }
            // Called on the system server's main looper thread.
            // TODO: Dispatch this to a worker thread as needed.
            mService.onUnlockUser(userHandle);
        }
    }

    void onStopUser(@UserIdInt int userId) {
        synchronized (mLock) {
            // Clean per-user data
            TextServicesData tsd = mUserData.get(userId);
            if (tsd == null) return;

            unbindServiceLocked(tsd);  // Remove bind groups first
            mUserData.remove(userId);  // This needs to be done after bind groups are all removed
        }
    }

    void onUnlockUser(@UserIdInt int userId) {
        synchronized (mLock) {
            // Initialize internal state for the given user
            initializeInternalStateLocked(userId);
        }
    }

    public TextServicesManagerService(Context context) {
        mContext = context;
        mUserManager = mContext.getSystemService(UserManager.class);

        mMonitor = new TextServicesMonitor();
        mMonitor.register(context, null, UserHandle.ALL, true);
        mSettings = new TextServicesSettings(context.getContentResolver());
    }

    private void initializeInternalStateLocked(@UserIdInt int userId) {
        TextServicesData tsd = mUserData.get(userId);
        if (tsd == null) {
            tsd = new TextServicesData(userId);
            mUserData.put(userId, tsd);
        }

        updateTextServiceDataLocked(tsd);
        SpellCheckerInfo sci = getCurrentSpellCheckerInternalLocked(tsd);
        if (sci == null) {
            sci = findAvailSpellCheckerLocked(null, tsd);
            if (sci != null) {
                // Set the current spell checker if there is one or more spell checkers
                // available. In this case, "sci" is the first one in the available spell
                // checkers.
                setCurrentSpellCheckerLocked(sci, tsd);
            }
        }
    }

    private void updateTextServiceDataLocked(TextServicesData tsd) {
        if (DBG) {
            Slog.d(TAG, "updateTextServiceDataLocked for user: " + tsd.mUserId);
        }
        buildSpellCheckerMapLocked(mContext, tsd.mSpellCheckerList, tsd.mSpellCheckerMap,
                tsd.mUserId);
    }

    private final class TextServicesMonitor extends PackageMonitor {
        @Override
        public void onSomePackagesChanged() {
            int userId = getChangingUserId();
            if(DBG) {
                Slog.d(TAG, "onSomePackagesChanged: " + userId);
            }

            synchronized (mLock) {
                TextServicesData tsd = mUserData.get(userId);
                if (tsd == null) return;

                // TODO: Update for each locale
                SpellCheckerInfo sci = getCurrentSpellCheckerInternalLocked(tsd);
                updateTextServiceDataLocked(tsd);
                // If no spell checker is enabled, just return. The user should explicitly
                // enable the spell checker.
                if (sci == null) return;
                final String packageName = sci.getPackageName();
                final int change = isPackageDisappearing(packageName);
                if (DBG) Slog.d(TAG, "Changing package name: " + packageName);
                if (// Package disappearing
                        change == PACKAGE_PERMANENT_CHANGE || change == PACKAGE_TEMPORARY_CHANGE
                                // Package modified
                                || isPackageModified(packageName)) {
                    SpellCheckerInfo availSci = findAvailSpellCheckerLocked(packageName, tsd);
                    // Set the spell checker settings if different than before
                    if (availSci != null && !availSci.getId().equals(sci.getId())) {
                        setCurrentSpellCheckerLocked(availSci, tsd);
                    }
                }
            }
        }
    }

    private void buildSpellCheckerMapLocked(Context context,
            ArrayList<SpellCheckerInfo> list, HashMap<String, SpellCheckerInfo> map,
            @UserIdInt int userId) {
        list.clear();
        map.clear();
        mSpellCheckerMapUpdateCount++;
        final PackageManager pm = context.getPackageManager();
        // Note: We do not specify PackageManager.MATCH_ENCRYPTION_* flags here because the default
        // behavior of PackageManager is exactly what we want.  It by default picks up appropriate
        // services depending on the unlock state for the specified user.
        final List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                new Intent(SpellCheckerService.SERVICE_INTERFACE), PackageManager.GET_META_DATA,
                userId);
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
            if (DBG) Slog.d(TAG, "Add: " + compName + " for user: " + userId);
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

    private boolean bindCurrentSpellCheckerService(
            Intent service, ServiceConnection conn, int flags, @UserIdInt int userId) {
        if (service == null || conn == null) {
            Slog.e(TAG, "--- bind failed: service = " + service + ", conn = " + conn +
                    ", userId =" + userId);
            return false;
        }
        return mContext.bindServiceAsUser(service, conn, flags, UserHandle.of(userId));
    }

    private void unbindServiceLocked(TextServicesData tsd) {
        HashMap<String, SpellCheckerBindGroup> spellCheckerBindGroups = tsd.mSpellCheckerBindGroups;
        for (SpellCheckerBindGroup scbg : spellCheckerBindGroups.values()) {
            scbg.removeAllLocked();
        }
        spellCheckerBindGroups.clear();
    }

    private SpellCheckerInfo findAvailSpellCheckerLocked(String prefPackage,
            TextServicesData tsd) {
        ArrayList<SpellCheckerInfo> spellCheckerList = tsd.mSpellCheckerList;
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
                        Slog.d(TAG, "findAvailSpellCheckerLocked: " + sci.getPackageName());
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
            Slog.w(TAG, "findAvailSpellCheckerLocked suitableLocales="
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
        int userId = UserHandle.getCallingUserId();
        synchronized (mLock) {
            TextServicesData tsd = mUserData.get(userId);
            if (tsd == null) return null;

            return getCurrentSpellCheckerInternalLocked(tsd);
        }
    }

    private SpellCheckerInfo getCurrentSpellCheckerInternalLocked(TextServicesData tsd) {
        final String curSpellCheckerId = mSettings.getSelectedSpellChecker(tsd.mUserId);
        if (DBG) {
            Slog.w(TAG, "getCurrentSpellChecker: " + curSpellCheckerId);
        }
        if (TextUtils.isEmpty(curSpellCheckerId)) {
            return null;
        }
        return tsd.mSpellCheckerMap.get(curSpellCheckerId);
    }

    // TODO: Respect allowImplicitlySelectedSubtype
    // TODO: Save SpellCheckerSubtype by supported languages by looking at "locale".
    @Override
    public SpellCheckerSubtype getCurrentSpellCheckerSubtype(
            String locale, boolean allowImplicitlySelectedSubtype) {
        final int subtypeHashCode;
        final SpellCheckerInfo sci;
        final Locale systemLocale;
        final int userId = UserHandle.getCallingUserId();

        synchronized (mLock) {
            TextServicesData tsd = mUserData.get(userId);
            if (tsd == null) return null;

            subtypeHashCode =
                    mSettings.getSelectedSpellCheckerSubtype(SpellCheckerSubtype.SUBTYPE_ID_NONE,
                            userId);
            if (DBG) {
                Slog.w(TAG, "getCurrentSpellCheckerSubtype: " + subtypeHashCode);
            }
            sci = getCurrentSpellCheckerInternalLocked(tsd);
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
        if (TextUtils.isEmpty(sciId) || tsListener == null || scListener == null) {
            Slog.e(TAG, "getSpellCheckerService: Invalid input.");
            return;
        }
        int callingUserId = UserHandle.getCallingUserId();

        synchronized (mLock) {
            TextServicesData tsd = mUserData.get(callingUserId);
            if (tsd == null) return;

            HashMap<String, SpellCheckerInfo> spellCheckerMap = tsd.mSpellCheckerMap;
            if (!spellCheckerMap.containsKey(sciId)) {
                return;
            }
            final SpellCheckerInfo sci = spellCheckerMap.get(sciId);
            HashMap<String, SpellCheckerBindGroup> spellCheckerBindGroups =
                    tsd.mSpellCheckerBindGroups;
            SpellCheckerBindGroup bindGroup = spellCheckerBindGroups.get(sciId);
            final int uid = Binder.getCallingUid();
            if (bindGroup == null) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    bindGroup = startSpellCheckerServiceInnerLocked(sci, tsd);
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
        int userId = UserHandle.getCallingUserId();

        synchronized (mLock) {
            TextServicesData tsd = mUserData.get(userId);
            if (tsd == null) return false;

            return isSpellCheckerEnabledLocked(userId);
        }
    }

    @Nullable
    private SpellCheckerBindGroup startSpellCheckerServiceInnerLocked(SpellCheckerInfo info,
            TextServicesData tsd) {
        if (DBG) {
            Slog.w(TAG, "Start spell checker session inner locked.");
        }
        final String sciId = info.getId();
        final InternalServiceConnection connection = new InternalServiceConnection(sciId,
                tsd.mSpellCheckerBindGroups);
        final Intent serviceIntent = new Intent(SpellCheckerService.SERVICE_INTERFACE);
        serviceIntent.setComponent(info.getComponent());
        if (DBG) {
            Slog.w(TAG, "bind service: " + info.getId());
        }
        if (!bindCurrentSpellCheckerService(serviceIntent, connection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT_BACKGROUND, tsd.mUserId)) {
            Slog.e(TAG, "Failed to get a spell checker service.");
            return null;
        }
        final SpellCheckerBindGroup group = new SpellCheckerBindGroup(connection);

        tsd.mSpellCheckerBindGroups.put(sciId, group);
        return group;
    }

    @Override
    public SpellCheckerInfo[] getEnabledSpellCheckers() {
        int callingUserId = UserHandle.getCallingUserId();

        synchronized (mLock) {
            TextServicesData tsd = mUserData.get(callingUserId);
            if (tsd == null) return null;

            ArrayList<SpellCheckerInfo> spellCheckerList = tsd.mSpellCheckerList;
            if (DBG) {
                Slog.d(TAG, "getEnabledSpellCheckers: " + spellCheckerList.size());
                for (int i = 0; i < spellCheckerList.size(); ++i) {
                    Slog.d(TAG,
                            "EnabledSpellCheckers: " + spellCheckerList.get(i).getPackageName());
                }
            }
            return spellCheckerList.toArray(new SpellCheckerInfo[spellCheckerList.size()]);
        }
    }

    @Override
    public void finishSpellCheckerService(ISpellCheckerSessionListener listener) {
        if (DBG) {
            Slog.d(TAG, "FinishSpellCheckerService");
        }
        int userId = UserHandle.getCallingUserId();

        synchronized (mLock) {
            TextServicesData tsd = mUserData.get(userId);
            if (tsd == null) return;

            final ArrayList<SpellCheckerBindGroup> removeList = new ArrayList<>();
            HashMap<String, SpellCheckerBindGroup> spellCheckerBindGroups =
                    tsd.mSpellCheckerBindGroups;
            for (SpellCheckerBindGroup group : spellCheckerBindGroups.values()) {
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

    private void setCurrentSpellCheckerLocked(SpellCheckerInfo sci, TextServicesData tsd) {
        final String sciId = sci.getId();
        if (DBG) {
            Slog.w(TAG, "setCurrentSpellChecker: " + sciId);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mSettings.putSelectedSpellChecker(sciId, tsd.mUserId);
            setCurrentSpellCheckerSubtypeLocked(0, tsd);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setCurrentSpellCheckerSubtypeLocked(int hashCode, TextServicesData tsd) {
        if (DBG) {
            Slog.w(TAG, "setCurrentSpellCheckerSubtype: " + hashCode);
        }

        final SpellCheckerInfo sci = getCurrentSpellCheckerInternalLocked(tsd);
        int tempHashCode = 0;
        for (int i = 0; sci != null && i < sci.getSubtypeCount(); ++i) {
            if(sci.getSubtypeAt(i).hashCode() == hashCode) {
                tempHashCode = hashCode;
                break;
            }
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mSettings.putSelectedSpellCheckerSubtype(tempHashCode, tsd.mUserId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean isSpellCheckerEnabledLocked(@UserIdInt int userId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            final boolean retval = mSettings.isSpellCheckerEnabled(userId);
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

        if (args.length == 0) {  // Dump all users' data
            synchronized (mLock) {
                pw.println("Current Text Services Manager state:");
                pw.println("  Text Services: mSpellCheckerMapUpdateCount="
                        + mSpellCheckerMapUpdateCount);
                pw.println("  Users:");
                final int numOfUsers = mUserData.size();
                for (int i = 0; i < numOfUsers; i++) {
                    int userId = mUserData.keyAt(i);
                    pw.println("  User #" + userId);
                    dumpUserDataLocked(pw, userId);
                }
            }
        } else {  // Dump a given user's data
            if (args.length != 2 || !args[0].equals("--user")) {
                pw.println("Invalid arguments to text services." );
                return;
            } else {
                int userId = Integer.parseInt(args[1]);
                UserInfo userInfo = mUserManager.getUserInfo(userId);
                if (userInfo == null) {
                    pw.println("Non-existent user.");
                    return;
                }
                TextServicesData tsd = mUserData.get(userId);
                if (tsd == null) {
                    pw.println("User needs to unlock first." );
                    return;
                }
                synchronized (mLock) {
                    pw.println("Current Text Services Manager state:");
                    pw.println("  Text Services: mSpellCheckerMapUpdateCount="
                            + mSpellCheckerMapUpdateCount);
                    pw.println("  User " + userId + ":");
                    dumpUserDataLocked(pw, userId);
                }
            }
        }
    }

    private void dumpUserDataLocked(PrintWriter pw, @UserIdInt int userId) {
        TextServicesData tsd = mUserData.get(userId);
        HashMap<String, SpellCheckerInfo> spellCheckerMap = tsd.mSpellCheckerMap;
        int spellCheckerIndex = 0;
        pw.println("  Spell Checkers:");
        for (final SpellCheckerInfo info : spellCheckerMap.values()) {
            pw.println("  Spell Checker #" + spellCheckerIndex);
            info.dump(pw, "    ");
            ++spellCheckerIndex;
        }

        pw.println("");
        pw.println("  Spell Checker Bind Groups:");
        HashMap<String, SpellCheckerBindGroup> spellCheckerBindGroups =
                tsd.mSpellCheckerBindGroups;
        for (final Map.Entry<String, SpellCheckerBindGroup> ent
                : spellCheckerBindGroups.entrySet()) {
            final SpellCheckerBindGroup grp = ent.getValue();
            pw.println("    " + ent.getKey() + " " + grp + ":");
            pw.println("      " + "mInternalConnection=" + grp.mInternalConnection);
            pw.println("      " + "mSpellChecker=" + grp.mSpellChecker);
            pw.println("      " + "mUnbindCalled=" + grp.mUnbindCalled);
            pw.println("      " + "mConnected=" + grp.mConnected);
            final int numPendingSessionRequests = grp.mPendingSessionRequests.size();
            for (int j = 0; j < numPendingSessionRequests; j++) {
                final SessionRequest req = grp.mPendingSessionRequests.get(j);
                pw.println("      " + "Pending Request #" + j + ":");
                pw.println("        " + "mTsListener=" + req.mTsListener);
                pw.println("        " + "mScListener=" + req.mScListener);
                pw.println(
                        "        " + "mScLocale=" + req.mLocale + " mUid=" + req.mUserId);
            }
            final int numOnGoingSessionRequests = grp.mOnGoingSessionRequests.size();
            for (int j = 0; j < numOnGoingSessionRequests; j++) {
                final SessionRequest req = grp.mOnGoingSessionRequests.get(j);
                pw.println("      " + "On going Request #" + j + ":");
                ++j;
                pw.println("        " + "mTsListener=" + req.mTsListener);
                pw.println("        " + "mScListener=" + req.mScListener);
                pw.println(
                        "        " + "mScLocale=" + req.mLocale + " mUid=" + req.mUserId);
            }
            final int N = grp.mListeners.getRegisteredCallbackCount();
            for (int j = 0; j < N; j++) {
                final ISpellCheckerSessionListener mScListener =
                        grp.mListeners.getRegisteredCallbackItem(j);
                pw.println("      " + "Listener #" + j + ":");
                pw.println("        " + "mScListener=" + mScListener);
                pw.println("        " + "mGroup=" + grp);
            }
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
        @NonNull
        HashMap<String, SpellCheckerBindGroup> mSpellCheckerBindGroups;


        public SpellCheckerBindGroup(InternalServiceConnection connection) {
            mInternalConnection = connection;
            mListeners = new InternalDeathRecipients(this);
            mSpellCheckerBindGroups = connection.mSpellCheckerBindGroups;
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
        @NonNull
        private final HashMap<String, SpellCheckerBindGroup> mSpellCheckerBindGroups;
        public InternalServiceConnection(String id,
                @NonNull HashMap<String, SpellCheckerBindGroup> spellCheckerBindGroups) {
            mSciId = id;
            mSpellCheckerBindGroups = spellCheckerBindGroups;
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

        public TextServicesSettings(ContentResolver resolver) {
            mResolver = resolver;
        }

        private void putString(final String key, final String str, @UserIdInt int userId) {
            Settings.Secure.putStringForUser(mResolver, key, str, userId);
        }

        @Nullable
        private String getString(@NonNull final String key, @Nullable final String defaultValue,
                @UserIdInt int userId) {
            final String result;
            result = Settings.Secure.getStringForUser(mResolver, key, userId);
            return result != null ? result : defaultValue;
        }

        private void putInt(final String key, final int value, @UserIdInt int userId) {
            Settings.Secure.putIntForUser(mResolver, key, value, userId);
        }

        private int getInt(final String key, final int defaultValue, @UserIdInt int userId) {
            return Settings.Secure.getIntForUser(mResolver, key, defaultValue, userId);
        }

        private boolean getBoolean(final String key, final boolean defaultValue,
                @UserIdInt int userId) {
            return getInt(key, defaultValue ? 1 : 0, userId) == 1;
        }

        public void putSelectedSpellChecker(@Nullable String sciId, @UserIdInt int userId) {
            if (TextUtils.isEmpty(sciId)) {
                // OK to coalesce to null, since getSelectedSpellChecker() can take care of the
                // empty data scenario.
                putString(Settings.Secure.SELECTED_SPELL_CHECKER, null, userId);
            } else {
                putString(Settings.Secure.SELECTED_SPELL_CHECKER, sciId, userId);
            }
        }

        public void putSelectedSpellCheckerSubtype(int hashCode, @UserIdInt int userId) {
            putInt(Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE, hashCode, userId);
        }

        @NonNull
        public String getSelectedSpellChecker(@UserIdInt int userId) {
            return getString(Settings.Secure.SELECTED_SPELL_CHECKER, "", userId);
        }

        public int getSelectedSpellCheckerSubtype(final int defaultValue, @UserIdInt int userId) {
            return getInt(Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE, defaultValue, userId);
        }

        public boolean isSpellCheckerEnabled(@UserIdInt int userId) {
            return getBoolean(Settings.Secure.SPELL_CHECKER_ENABLED, true, userId);
        }
    }
}
