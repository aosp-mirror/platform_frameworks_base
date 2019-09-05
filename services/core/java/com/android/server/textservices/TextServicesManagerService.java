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

package com.android.server.textservices;

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
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
import android.view.inputmethod.InputMethodSystemProperty;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.SpellCheckerSubtype;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.inputmethod.SubtypeLocaleUtils;
import com.android.internal.textservice.ISpellCheckerService;
import com.android.internal.textservice.ISpellCheckerServiceCallback;
import com.android.internal.textservice.ISpellCheckerSession;
import com.android.internal.textservice.ISpellCheckerSessionListener;
import com.android.internal.textservice.ITextServicesManager;
import com.android.internal.textservice.ITextServicesSessionListener;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public class TextServicesManagerService extends ITextServicesManager.Stub {
    private static final String TAG = TextServicesManagerService.class.getSimpleName();
    private static final boolean DBG = false;

    private final Context mContext;
    private final TextServicesMonitor mMonitor;
    private final SparseArray<TextServicesData> mUserData = new SparseArray<>();
    @NonNull
    private final UserManager mUserManager;
    private final Object mLock = new Object();

    @NonNull
    @GuardedBy("mLock")
    private final LazyIntToIntMap mSpellCheckerOwnerUserIdMap;

    private static class TextServicesData {
        @UserIdInt
        private final int mUserId;
        private final HashMap<String, SpellCheckerInfo> mSpellCheckerMap;
        private final ArrayList<SpellCheckerInfo> mSpellCheckerList;
        private final HashMap<String, SpellCheckerBindGroup> mSpellCheckerBindGroups;
        private final Context mContext;
        private final ContentResolver mResolver;
        public int mUpdateCount = 0;

        public TextServicesData(@UserIdInt int userId, @NonNull Context context) {
            mUserId = userId;
            mSpellCheckerMap = new HashMap<>();
            mSpellCheckerList = new ArrayList<>();
            mSpellCheckerBindGroups = new HashMap<>();
            mContext = context;
            mResolver = context.getContentResolver();
        }

        private void putString(final String key, final String str) {
            Settings.Secure.putStringForUser(mResolver, key, str, mUserId);
        }

        @Nullable
        private String getString(@NonNull final String key, @Nullable final String defaultValue) {
            final String result;
            result = Settings.Secure.getStringForUser(mResolver, key, mUserId);
            return result != null ? result : defaultValue;
        }

        private void putInt(final String key, final int value) {
            Settings.Secure.putIntForUser(mResolver, key, value, mUserId);
        }

        private int getInt(final String key, final int defaultValue) {
            return Settings.Secure.getIntForUser(mResolver, key, defaultValue, mUserId);
        }

        private boolean getBoolean(final String key, final boolean defaultValue) {
            return getInt(key, defaultValue ? 1 : 0) == 1;
        }

        private void putSelectedSpellChecker(@Nullable String sciId) {
            putString(Settings.Secure.SELECTED_SPELL_CHECKER, sciId);
        }

        private void putSelectedSpellCheckerSubtype(int hashCode) {
            putInt(Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE, hashCode);
        }

        @NonNull
        private String getSelectedSpellChecker() {
            return getString(Settings.Secure.SELECTED_SPELL_CHECKER, "");
        }

        public int getSelectedSpellCheckerSubtype(final int defaultValue) {
            return getInt(Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE, defaultValue);
        }

        public boolean isSpellCheckerEnabled() {
            return getBoolean(Settings.Secure.SPELL_CHECKER_ENABLED, true);
        }

        @Nullable
        public SpellCheckerInfo getCurrentSpellChecker() {
            final String curSpellCheckerId = getSelectedSpellChecker();
            if (TextUtils.isEmpty(curSpellCheckerId)) {
                return null;
            }
            return mSpellCheckerMap.get(curSpellCheckerId);
        }

        public void setCurrentSpellChecker(@Nullable SpellCheckerInfo sci) {
            if (sci != null) {
                putSelectedSpellChecker(sci.getId());
            } else {
                putSelectedSpellChecker("");
            }
            putSelectedSpellCheckerSubtype(SpellCheckerSubtype.SUBTYPE_ID_NONE);
        }

        private void initializeTextServicesData() {
            if (DBG) {
                Slog.d(TAG, "initializeTextServicesData for user: " + mUserId);
            }
            mSpellCheckerList.clear();
            mSpellCheckerMap.clear();
            mUpdateCount++;
            final PackageManager pm = mContext.getPackageManager();
            // Note: We do not specify PackageManager.MATCH_ENCRYPTION_* flags here because the
            // default behavior of PackageManager is exactly what we want.  It by default picks up
            // appropriate services depending on the unlock state for the specified user.
            final List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                    new Intent(SpellCheckerService.SERVICE_INTERFACE), PackageManager.GET_META_DATA,
                    mUserId);
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
                if (DBG) Slog.d(TAG, "Add: " + compName + " for user: " + mUserId);
                try {
                    final SpellCheckerInfo sci = new SpellCheckerInfo(mContext, ri);
                    if (sci.getSubtypeCount() <= 0) {
                        Slog.w(TAG, "Skipping text service " + compName
                                + ": it does not contain subtypes.");
                        continue;
                    }
                    mSpellCheckerList.add(sci);
                    mSpellCheckerMap.put(sci.getId(), sci);
                } catch (XmlPullParserException e) {
                    Slog.w(TAG, "Unable to load the spell checker " + compName, e);
                } catch (IOException e) {
                    Slog.w(TAG, "Unable to load the spell checker " + compName, e);
                }
            }
            if (DBG) {
                Slog.d(TAG, "initializeSpellCheckerMap: " + mSpellCheckerList.size() + ","
                        + mSpellCheckerMap.size());
            }
        }

        private void dump(PrintWriter pw) {
            int spellCheckerIndex = 0;
            pw.println("  User #" + mUserId);
            pw.println("  Spell Checkers:");
            pw.println("  Spell Checkers: " +  "mUpdateCount=" + mUpdateCount);
            for (final SpellCheckerInfo info : mSpellCheckerMap.values()) {
                pw.println("  Spell Checker #" + spellCheckerIndex);
                info.dump(pw, "    ");
                ++spellCheckerIndex;
            }

            pw.println("");
            pw.println("  Spell Checker Bind Groups:");
            HashMap<String, SpellCheckerBindGroup> spellCheckerBindGroups = mSpellCheckerBindGroups;
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
                            "        " + "mScLocale=" + req.mLocale + " mUid=" + req.mUid);
                }
                final int numOnGoingSessionRequests = grp.mOnGoingSessionRequests.size();
                for (int j = 0; j < numOnGoingSessionRequests; j++) {
                    final SessionRequest req = grp.mOnGoingSessionRequests.get(j);
                    pw.println("      " + "On going Request #" + j + ":");
                    pw.println("        " + "mTsListener=" + req.mTsListener);
                    pw.println("        " + "mScListener=" + req.mScListener);
                    pw.println(
                            "        " + "mScLocale=" + req.mLocale + " mUid=" + req.mUid);
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
    }

    public static final class Lifecycle extends SystemService {
        private TextServicesManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new TextServicesManagerService(context);
        }

        @Override
        public void onStart() {
            LocalServices.addService(TextServicesManagerInternal.class,
                    new TextServicesManagerInternal() {
                        @Override
                        public SpellCheckerInfo getCurrentSpellCheckerForUser(
                                @UserIdInt int userId) {
                            return mService.getCurrentSpellCheckerForUser(userId);
                        }
                    });
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
            // Clear user ID mapping table.
            mSpellCheckerOwnerUserIdMap.delete(userId);

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
        mSpellCheckerOwnerUserIdMap = new LazyIntToIntMap(callingUserId -> {
            if (!InputMethodSystemProperty.PER_PROFILE_IME_ENABLED) {
                final long token = Binder.clearCallingIdentity();
                try {
                    final UserInfo parent = mUserManager.getProfileParent(callingUserId);
                    return (parent != null) ? parent.id : callingUserId;
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } else {
                return callingUserId;
            }
        });

        mMonitor = new TextServicesMonitor();
        mMonitor.register(context, null, UserHandle.ALL, true);
    }

    @GuardedBy("mLock")
    private void initializeInternalStateLocked(@UserIdInt int userId) {
        // When DISABLE_PER_PROFILE_SPELL_CHECKER is true, we make sure here that work profile users
        // will never have non-null TextServicesData for their user ID.
        if (!InputMethodSystemProperty.PER_PROFILE_IME_ENABLED
                && userId != mSpellCheckerOwnerUserIdMap.get(userId)) {
            return;
        }

        TextServicesData tsd = mUserData.get(userId);
        if (tsd == null) {
            tsd = new TextServicesData(userId, mContext);
            mUserData.put(userId, tsd);
        }

        tsd.initializeTextServicesData();
        SpellCheckerInfo sci = tsd.getCurrentSpellChecker();
        if (sci == null) {
            sci = findAvailSystemSpellCheckerLocked(null, tsd);
            // Set the current spell checker if there is one or more system spell checkers
            // available. In this case, "sci" is the first one in the available spell
            // checkers.
            setCurrentSpellCheckerLocked(sci, tsd);
        }
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
                SpellCheckerInfo sci = tsd.getCurrentSpellChecker();
                tsd.initializeTextServicesData();
                // If spell checker is disabled, just return. The user should explicitly
                // enable the spell checker.
                if (!tsd.isSpellCheckerEnabled()) return;

                if (sci == null) {
                    sci = findAvailSystemSpellCheckerLocked(null, tsd);
                    // Set the current spell checker if there is one or more system spell checkers
                    // available. In this case, "sci" is the first one in the available spell
                    // checkers.
                    setCurrentSpellCheckerLocked(sci, tsd);
                } else {
                    final String packageName = sci.getPackageName();
                    final int change = isPackageDisappearing(packageName);
                    if (DBG) Slog.d(TAG, "Changing package name: " + packageName);
                    if (change == PACKAGE_PERMANENT_CHANGE || change == PACKAGE_TEMPORARY_CHANGE) {
                        SpellCheckerInfo availSci =
                                findAvailSystemSpellCheckerLocked(packageName, tsd);
                        // Set the spell checker settings if different than before
                        if (availSci == null
                                || (availSci != null && !availSci.getId().equals(sci.getId()))) {
                            setCurrentSpellCheckerLocked(availSci, tsd);
                        }
                    }
                }
            }
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

    private SpellCheckerInfo findAvailSystemSpellCheckerLocked(String prefPackage,
            TextServicesData tsd) {
        // Filter the spell checker list to remove spell checker services that are not pre-installed
        ArrayList<SpellCheckerInfo> spellCheckerList = new ArrayList<>();
        for (SpellCheckerInfo sci : tsd.mSpellCheckerList) {
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
                LocaleUtils.getSuitableLocalesForSpellChecker(systemLocal);
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
                    final Locale subtypeLocale = SubtypeLocaleUtils.constructLocaleFromString(
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

    @Nullable
    private SpellCheckerInfo getCurrentSpellCheckerForUser(@UserIdInt int userId) {
        synchronized (mLock) {
            final int spellCheckerOwnerUserId = mSpellCheckerOwnerUserIdMap.get(userId);
            final TextServicesData data = mUserData.get(spellCheckerOwnerUserId);
            return data != null ? data.getCurrentSpellChecker() : null;
        }
    }

    // TODO: Save SpellCheckerService by supported languages. Currently only one spell
    // checker is saved.
    @Override
    public SpellCheckerInfo getCurrentSpellChecker(@UserIdInt int userId, String locale) {
        verifyUser(userId);
        synchronized (mLock) {
            final TextServicesData tsd = getDataFromCallingUserIdLocked(userId);
            if (tsd == null) return null;

            return tsd.getCurrentSpellChecker();
        }
    }

    // TODO: Respect allowImplicitlySelectedSubtype
    // TODO: Save SpellCheckerSubtype by supported languages by looking at "locale".
    @Override
    public SpellCheckerSubtype getCurrentSpellCheckerSubtype(
            @UserIdInt int userId, boolean allowImplicitlySelectedSubtype) {
        verifyUser(userId);

        final int subtypeHashCode;
        final SpellCheckerInfo sci;
        final Locale systemLocale;

        synchronized (mLock) {
            final TextServicesData tsd = getDataFromCallingUserIdLocked(userId);
            if (tsd == null) return null;

            subtypeHashCode =
                    tsd.getSelectedSpellCheckerSubtype(SpellCheckerSubtype.SUBTYPE_ID_NONE);
            if (DBG) {
                Slog.w(TAG, "getCurrentSpellCheckerSubtype: " + subtypeHashCode);
            }
            sci = tsd.getCurrentSpellChecker();
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

        final int numSubtypes = sci.getSubtypeCount();
        if (subtypeHashCode != 0) {
            // Use the user specified spell checker subtype
            for (int i = 0; i < numSubtypes; ++i) {
                final SpellCheckerSubtype scs = sci.getSubtypeAt(i);
                if (scs.hashCode() == subtypeHashCode) {
                    return scs;
                }
            }
            return null;
        }

        // subtypeHashCode == 0 means spell checker language settings is "auto"

        if (systemLocale == null) {
            return null;
        }
        SpellCheckerSubtype firstLanguageMatchingSubtype = null;
        for (int i = 0; i < sci.getSubtypeCount(); ++i) {
            final SpellCheckerSubtype scs = sci.getSubtypeAt(i);
            final Locale scsLocale = scs.getLocaleObject();
            if (Objects.equals(scsLocale, systemLocale)) {
                // Exact match wins.
                return scs;
            }
            if (firstLanguageMatchingSubtype == null && scsLocale != null
                    && TextUtils.equals(systemLocale.getLanguage(), scsLocale.getLanguage())) {
                // Remember as a fall back candidate
                firstLanguageMatchingSubtype = scs;
            }
        }
        return firstLanguageMatchingSubtype;
    }

    @Override
    public void getSpellCheckerService(@UserIdInt int userId, String sciId, String locale,
            ITextServicesSessionListener tsListener, ISpellCheckerSessionListener scListener,
            Bundle bundle) {
        verifyUser(userId);
        if (TextUtils.isEmpty(sciId) || tsListener == null || scListener == null) {
            Slog.e(TAG, "getSpellCheckerService: Invalid input.");
            return;
        }

        synchronized (mLock) {
            final TextServicesData tsd = getDataFromCallingUserIdLocked(userId);
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
    public boolean isSpellCheckerEnabled(@UserIdInt int userId) {
        verifyUser(userId);

        synchronized (mLock) {
            final TextServicesData tsd = getDataFromCallingUserIdLocked(userId);
            if (tsd == null) return false;

            return tsd.isSpellCheckerEnabled();
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
    public SpellCheckerInfo[] getEnabledSpellCheckers(@UserIdInt int userId) {
        verifyUser(userId);

        synchronized (mLock) {
            final TextServicesData tsd = getDataFromCallingUserIdLocked(userId);
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
    public void finishSpellCheckerService(@UserIdInt int userId,
            ISpellCheckerSessionListener listener) {
        if (DBG) {
            Slog.d(TAG, "FinishSpellCheckerService");
        }
        verifyUser(userId);

        synchronized (mLock) {
            final TextServicesData tsd = getDataFromCallingUserIdLocked(userId);
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

    private void verifyUser(@UserIdInt int userId) {
        final int callingUserId = UserHandle.getCallingUserId();
        if (userId != callingUserId) {
            mContext.enforceCallingPermission(INTERACT_ACROSS_USERS_FULL,
                    "Cross-user interaction requires INTERACT_ACROSS_USERS_FULL. userId=" + userId
                            + " callingUserId=" + callingUserId);
        }
    }

    private void setCurrentSpellCheckerLocked(@Nullable SpellCheckerInfo sci, TextServicesData tsd) {
        final String sciId = (sci != null) ? sci.getId() : "";
        if (DBG) {
            Slog.w(TAG, "setCurrentSpellChecker: " + sciId);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            tsd.setCurrentSpellChecker(sci);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        if (args.length == 0 || (args.length == 1 && args[0].equals("-a"))) {
            // Dump all users' data
            synchronized (mLock) {
                pw.println("Current Text Services Manager state:");
                pw.println("  Users:");
                final int numOfUsers = mUserData.size();
                for (int i = 0; i < numOfUsers; i++) {
                    TextServicesData tsd = mUserData.valueAt(i);
                    tsd.dump(pw);
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
                    pw.println("  User " + userId + ":");
                    tsd.dump(pw);
                }
            }
        }
    }

    /**
     * @param callingUserId user ID of the calling process
     * @return {@link TextServicesData} for the given user.  {@code null} if spell checker is not
     *         temporarily / permanently available for the specified user
     */
    @GuardedBy("mLock")
    @Nullable
    private TextServicesData getDataFromCallingUserIdLocked(@UserIdInt int callingUserId) {
        final int spellCheckerOwnerUserId = mSpellCheckerOwnerUserIdMap.get(callingUserId);
        final TextServicesData data = mUserData.get(spellCheckerOwnerUserId);
        if (!InputMethodSystemProperty.PER_PROFILE_IME_ENABLED) {
            if (spellCheckerOwnerUserId != callingUserId) {
                // Calling process is running under child profile.
                if (data == null) {
                    return null;
                }
                final SpellCheckerInfo info = data.getCurrentSpellChecker();
                if (info == null) {
                    return null;
                }
                final ServiceInfo serviceInfo = info.getServiceInfo();
                if ((serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    // To be conservative, non pre-installed spell checker services are not allowed
                    // to be used for child profiles.
                    return null;
                }
            }
        }
        return data;
    }

    private static final class SessionRequest {
        public final int mUid;
        @Nullable
        public final String mLocale;
        @NonNull
        public final ITextServicesSessionListener mTsListener;
        @NonNull
        public final ISpellCheckerSessionListener mScListener;
        @Nullable
        public final Bundle mBundle;

        SessionRequest(int uid, @Nullable String locale,
                @NonNull ITextServicesSessionListener tsListener,
                @NonNull ISpellCheckerSessionListener scListener, @Nullable Bundle bundle) {
            mUid = uid;
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
                final IBinder scListenerBinder = listener.asBinder();
                final Predicate<SessionRequest> removeCondition =
                        request -> request.mScListener.asBinder() == scListenerBinder;
                mPendingSessionRequests.removeIf(removeCondition);
                mOnGoingSessionRequests.removeIf(removeCondition);
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
            mListeners.register(request.mScListener);
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
        private final Object mCallbackLock = new Object();

        @GuardedBy("mCallbackLock")
        @Nullable
        private WeakReference<SpellCheckerBindGroup> mBindGroup;

        /**
         * Original {@link SessionRequest} that is associated with this callback.
         *
         * <p>Note that {@link SpellCheckerBindGroup#mOnGoingSessionRequests} guarantees that this
         * {@link SessionRequest} object is kept alive until the request is canceled.</p>
         */
        @GuardedBy("mCallbackLock")
        @Nullable
        private WeakReference<SessionRequest> mRequest;

        ISpellCheckerServiceCallbackBinder(@NonNull SpellCheckerBindGroup bindGroup,
                @NonNull SessionRequest request) {
            synchronized (mCallbackLock) {
                mBindGroup = new WeakReference<>(bindGroup);
                mRequest = new WeakReference<>(request);
            }
        }

        @Override
        public void onSessionCreated(@Nullable ISpellCheckerSession newSession) {
            final SpellCheckerBindGroup group;
            final SessionRequest request;
            synchronized (mCallbackLock) {
                if (mBindGroup == null || mRequest == null) {
                    return;
                }
                group = mBindGroup.get();
                request = mRequest.get();
                mBindGroup = null;
                mRequest = null;
            }
            if (group != null && request != null) {
                group.onSessionCreated(newSession, request);
            }
        }
    }
}
