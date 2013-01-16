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

import com.android.internal.content.PackageMonitor;
import com.android.internal.textservice.ISpellCheckerService;
import com.android.internal.textservice.ISpellCheckerSession;
import com.android.internal.textservice.ISpellCheckerSessionListener;
import com.android.internal.textservice.ITextServicesManager;
import com.android.internal.textservice.ITextServicesSessionListener;

import org.xmlpull.v1.XmlPullParserException;

import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.IUserSwitchObserver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class TextServicesManagerService extends ITextServicesManager.Stub {
    private static final String TAG = TextServicesManagerService.class.getSimpleName();
    private static final boolean DBG = false;

    private final Context mContext;
    private boolean mSystemReady;
    private final TextServicesMonitor mMonitor;
    private final HashMap<String, SpellCheckerInfo> mSpellCheckerMap =
            new HashMap<String, SpellCheckerInfo>();
    private final ArrayList<SpellCheckerInfo> mSpellCheckerList = new ArrayList<SpellCheckerInfo>();
    private final HashMap<String, SpellCheckerBindGroup> mSpellCheckerBindGroups =
            new HashMap<String, SpellCheckerBindGroup>();
    private final TextServicesSettings mSettings;

    public void systemReady() {
        if (!mSystemReady) {
            mSystemReady = true;
        }
    }

    public TextServicesManagerService(Context context) {
        mSystemReady = false;
        mContext = context;
        int userId = UserHandle.USER_OWNER;
        try {
            ActivityManagerNative.getDefault().registerUserSwitchObserver(
                    new IUserSwitchObserver.Stub() {
                        @Override
                        public void onUserSwitching(int newUserId, IRemoteCallback reply) {
                            synchronized(mSpellCheckerMap) {
                                switchUserLocked(newUserId);
                            }
                            if (reply != null) {
                                try {
                                    reply.sendResult(null);
                                } catch (RemoteException e) {
                                }
                            }
                        }

                        @Override
                        public void onUserSwitchComplete(int newUserId) throws RemoteException {
                        }
                    });
            userId = ActivityManagerNative.getDefault().getCurrentUser().id;
        } catch (RemoteException e) {
            Slog.w(TAG, "Couldn't get current user ID; guessing it's 0", e);
        }
        mMonitor = new TextServicesMonitor();
        mMonitor.register(context, null, true);
        mSettings = new TextServicesSettings(context.getContentResolver(), userId);

        // "switchUserLocked" initializes the states for the foreground user
        switchUserLocked(userId);
    }

    private void switchUserLocked(int userId) {
        mSettings.setCurrentUserId(userId);
        unbindServiceLocked();
        buildSpellCheckerMapLocked(mContext, mSpellCheckerList, mSpellCheckerMap, mSettings);
        SpellCheckerInfo sci = getCurrentSpellChecker(null);
        if (sci == null) {
            sci = findAvailSpellCheckerLocked(null, null);
            if (sci != null) {
                // Set the current spell checker if there is one or more spell checkers
                // available. In this case, "sci" is the first one in the available spell
                // checkers.
                setCurrentSpellCheckerLocked(sci.getId());
            }
        }
    }

    private class TextServicesMonitor extends PackageMonitor {
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
            synchronized (mSpellCheckerMap) {
                buildSpellCheckerMapLocked(
                        mContext, mSpellCheckerList, mSpellCheckerMap, mSettings);
                // TODO: Update for each locale
                SpellCheckerInfo sci = getCurrentSpellChecker(null);
                if (sci == null) return;
                final String packageName = sci.getPackageName();
                final int change = isPackageDisappearing(packageName);
                if (// Package disappearing
                        change == PACKAGE_PERMANENT_CHANGE || change == PACKAGE_TEMPORARY_CHANGE
                        // Package modified
                        || isPackageModified(packageName)) {
                    sci = findAvailSpellCheckerLocked(null, packageName);
                    if (sci != null) {
                        setCurrentSpellCheckerLocked(sci.getId());
                    }
                }
            }
        }
    }

    private static void buildSpellCheckerMapLocked(Context context,
            ArrayList<SpellCheckerInfo> list, HashMap<String, SpellCheckerInfo> map,
            TextServicesSettings settings) {
        list.clear();
        map.clear();
        final PackageManager pm = context.getPackageManager();
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
                    + mSettings.getCurrentUserId());
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
        } else {
            Slog.w(TAG, "--- IPC called from background users. Ignore. \n" + getStackTrace());
            return false;
        }
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
            scbg.removeAll();
        }
        mSpellCheckerBindGroups.clear();
    }

    // TODO: find an appropriate spell checker for specified locale
    private SpellCheckerInfo findAvailSpellCheckerLocked(String locale, String prefPackage) {
        final int spellCheckersCount = mSpellCheckerList.size();
        if (spellCheckersCount == 0) {
            Slog.w(TAG, "no available spell checker services found");
            return null;
        }
        if (prefPackage != null) {
            for (int i = 0; i < spellCheckersCount; ++i) {
                final SpellCheckerInfo sci = mSpellCheckerList.get(i);
                if (prefPackage.equals(sci.getPackageName())) {
                    if (DBG) {
                        Slog.d(TAG, "findAvailSpellCheckerLocked: " + sci.getPackageName());
                    }
                    return sci;
                }
            }
        }
        if (spellCheckersCount > 1) {
            Slog.w(TAG, "more than one spell checker service found, picking first");
        }
        return mSpellCheckerList.get(0);
    }

    // TODO: Save SpellCheckerService by supported languages. Currently only one spell
    // checker is saved.
    @Override
    public SpellCheckerInfo getCurrentSpellChecker(String locale) {
        // TODO: Make this work even for non-current users?
        if (!calledFromValidUser()) {
            return null;
        }
        synchronized (mSpellCheckerMap) {
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
        synchronized (mSpellCheckerMap) {
            final String subtypeHashCodeStr = mSettings.getSelectedSpellCheckerSubtype();
            if (DBG) {
                Slog.w(TAG, "getCurrentSpellCheckerSubtype: " + subtypeHashCodeStr);
            }
            final SpellCheckerInfo sci = getCurrentSpellChecker(null);
            if (sci == null || sci.getSubtypeCount() == 0) {
                if (DBG) {
                    Slog.w(TAG, "Subtype not found.");
                }
                return null;
            }
            final int hashCode;
            if (!TextUtils.isEmpty(subtypeHashCodeStr)) {
                hashCode = Integer.valueOf(subtypeHashCodeStr);
            } else {
                hashCode = 0;
            }
            if (hashCode == 0 && !allowImplicitlySelectedSubtype) {
                return null;
            }
            String candidateLocale = null;
            if (hashCode == 0) {
                // Spell checker language settings == "auto"
                final InputMethodManager imm =
                        (InputMethodManager)mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
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
                    candidateLocale = mContext.getResources().getConfiguration().locale.toString();
                }
            }
            SpellCheckerSubtype candidate = null;
            for (int i = 0; i < sci.getSubtypeCount(); ++i) {
                final SpellCheckerSubtype scs = sci.getSubtypeAt(i);
                if (hashCode == 0) {
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
                } else if (scs.hashCode() == hashCode) {
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
        synchronized(mSpellCheckerMap) {
            if (!mSpellCheckerMap.containsKey(sciId)) {
                return;
            }
            final SpellCheckerInfo sci = mSpellCheckerMap.get(sciId);
            final int uid = Binder.getCallingUid();
            if (mSpellCheckerBindGroups.containsKey(sciId)) {
                final SpellCheckerBindGroup bindGroup = mSpellCheckerBindGroups.get(sciId);
                if (bindGroup != null) {
                    final InternalDeathRecipient recipient =
                            mSpellCheckerBindGroups.get(sciId).addListener(
                                    tsListener, locale, scListener, uid, bundle);
                    if (recipient == null) {
                        if (DBG) {
                            Slog.w(TAG, "Didn't create a death recipient.");
                        }
                        return;
                    }
                    if (bindGroup.mSpellChecker == null & bindGroup.mConnected) {
                        Slog.e(TAG, "The state of the spell checker bind group is illegal.");
                        bindGroup.removeAll();
                    } else if (bindGroup.mSpellChecker != null) {
                        if (DBG) {
                            Slog.w(TAG, "Existing bind found. Return a spell checker session now. "
                                    + "Listeners count = " + bindGroup.mListeners.size());
                        }
                        try {
                            final ISpellCheckerSession session =
                                    bindGroup.mSpellChecker.getISpellCheckerSession(
                                            recipient.mScLocale, recipient.mScListener, bundle);
                            if (session != null) {
                                tsListener.onServiceConnected(session);
                                return;
                            } else {
                                if (DBG) {
                                    Slog.w(TAG, "Existing bind already expired. ");
                                }
                                bindGroup.removeAll();
                            }
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Exception in getting spell checker session: " + e);
                            bindGroup.removeAll();
                        }
                    }
                }
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                startSpellCheckerServiceInnerLocked(
                        sci, locale, tsListener, scListener, uid, bundle);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        return;
    }

    @Override
    public boolean isSpellCheckerEnabled() {
        if (!calledFromValidUser()) {
            return false;
        }
        synchronized(mSpellCheckerMap) {
            return isSpellCheckerEnabledLocked();
        }
    }

    private void startSpellCheckerServiceInnerLocked(SpellCheckerInfo info, String locale,
            ITextServicesSessionListener tsListener, ISpellCheckerSessionListener scListener,
            int uid, Bundle bundle) {
        if (DBG) {
            Slog.w(TAG, "Start spell checker session inner locked.");
        }
        final String sciId = info.getId();
        final InternalServiceConnection connection = new InternalServiceConnection(
                sciId, locale, bundle);
        final Intent serviceIntent = new Intent(SpellCheckerService.SERVICE_INTERFACE);
        serviceIntent.setComponent(info.getComponent());
        if (DBG) {
            Slog.w(TAG, "bind service: " + info.getId());
        }
        if (!bindCurrentSpellCheckerService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
            Slog.e(TAG, "Failed to get a spell checker service.");
            return;
        }
        final SpellCheckerBindGroup group = new SpellCheckerBindGroup(
                connection, tsListener, locale, scListener, uid, bundle);
        mSpellCheckerBindGroups.put(sciId, group);
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
        synchronized(mSpellCheckerMap) {
            final ArrayList<SpellCheckerBindGroup> removeList =
                    new ArrayList<SpellCheckerBindGroup>();
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

    @Override
    public void setCurrentSpellChecker(String locale, String sciId) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized(mSpellCheckerMap) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Requires permission "
                        + android.Manifest.permission.WRITE_SECURE_SETTINGS);
            }
            setCurrentSpellCheckerLocked(sciId);
        }
    }

    @Override
    public void setCurrentSpellCheckerSubtype(String locale, int hashCode) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized(mSpellCheckerMap) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Requires permission "
                        + android.Manifest.permission.WRITE_SECURE_SETTINGS);
            }
            setCurrentSpellCheckerSubtypeLocked(hashCode);
        }
    }

    @Override
    public void setSpellCheckerEnabled(boolean enabled) {
        if (!calledFromValidUser()) {
            return;
        }
        synchronized(mSpellCheckerMap) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Requires permission "
                        + android.Manifest.permission.WRITE_SECURE_SETTINGS);
            }
            setSpellCheckerEnabledLocked(enabled);
        }
    }

    private void setCurrentSpellCheckerLocked(String sciId) {
        if (DBG) {
            Slog.w(TAG, "setCurrentSpellChecker: " + sciId);
        }
        if (TextUtils.isEmpty(sciId) || !mSpellCheckerMap.containsKey(sciId)) return;
        final SpellCheckerInfo currentSci = getCurrentSpellChecker(null);
        if (currentSci != null && currentSci.getId().equals(sciId)) {
            // Do nothing if the current spell checker is same as new spell checker.
            return;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mSettings.putSelectedSpellChecker(sciId);
            setCurrentSpellCheckerSubtypeLocked(0);
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

    private void setSpellCheckerEnabledLocked(boolean enabled) {
        if (DBG) {
            Slog.w(TAG, "setSpellCheckerEnabled: " + enabled);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mSettings.setSpellCheckerEnabled(enabled);
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
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {

            pw.println("Permission Denial: can't dump TextServicesManagerService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized(mSpellCheckerMap) {
            pw.println("Current Text Services Manager state:");
            pw.println("  Spell Checker Map:");
            for (Map.Entry<String, SpellCheckerInfo> ent : mSpellCheckerMap.entrySet()) {
                pw.print("    "); pw.print(ent.getKey()); pw.println(":");
                SpellCheckerInfo info = ent.getValue();
                pw.print("      "); pw.print("id="); pw.println(info.getId());
                pw.print("      "); pw.print("comp=");
                        pw.println(info.getComponent().toShortString());
                int NS = info.getSubtypeCount();
                for (int i=0; i<NS; i++) {
                    SpellCheckerSubtype st = info.getSubtypeAt(i);
                    pw.print("      "); pw.print("Subtype #"); pw.print(i); pw.println(":");
                    pw.print("        "); pw.print("locale="); pw.println(st.getLocale());
                    pw.print("        "); pw.print("extraValue=");
                            pw.println(st.getExtraValue());
                }
            }
            pw.println("");
            pw.println("  Spell Checker Bind Groups:");
            for (Map.Entry<String, SpellCheckerBindGroup> ent
                    : mSpellCheckerBindGroups.entrySet()) {
                SpellCheckerBindGroup grp = ent.getValue();
                pw.print("    "); pw.print(ent.getKey()); pw.print(" ");
                        pw.print(grp); pw.println(":");
                pw.print("      "); pw.print("mInternalConnection=");
                        pw.println(grp.mInternalConnection);
                pw.print("      "); pw.print("mSpellChecker=");
                        pw.println(grp.mSpellChecker);
                pw.print("      "); pw.print("mBound="); pw.print(grp.mBound);
                        pw.print(" mConnected="); pw.println(grp.mConnected);
                int NL = grp.mListeners.size();
                for (int i=0; i<NL; i++) {
                    InternalDeathRecipient listener = grp.mListeners.get(i);
                    pw.print("      "); pw.print("Listener #"); pw.print(i); pw.println(":");
                    pw.print("        "); pw.print("mTsListener=");
                            pw.println(listener.mTsListener);
                    pw.print("        "); pw.print("mScListener=");
                            pw.println(listener.mScListener);
                    pw.print("        "); pw.print("mGroup=");
                            pw.println(listener.mGroup);
                    pw.print("        "); pw.print("mScLocale=");
                            pw.print(listener.mScLocale);
                            pw.print(" mUid="); pw.println(listener.mUid);
                }
            }
        }
    }

    // SpellCheckerBindGroup contains active text service session listeners.
    // If there are no listeners anymore, the SpellCheckerBindGroup instance will be removed from
    // mSpellCheckerBindGroups
    private class SpellCheckerBindGroup {
        private final String TAG = SpellCheckerBindGroup.class.getSimpleName();
        private final InternalServiceConnection mInternalConnection;
        private final CopyOnWriteArrayList<InternalDeathRecipient> mListeners =
                new CopyOnWriteArrayList<InternalDeathRecipient>();
        public boolean mBound;
        public ISpellCheckerService mSpellChecker;
        public boolean mConnected;

        public SpellCheckerBindGroup(InternalServiceConnection connection,
                ITextServicesSessionListener listener, String locale,
                ISpellCheckerSessionListener scListener, int uid, Bundle bundle) {
            mInternalConnection = connection;
            mBound = true;
            mConnected = false;
            addListener(listener, locale, scListener, uid, bundle);
        }

        public void onServiceConnected(ISpellCheckerService spellChecker) {
            if (DBG) {
                Slog.d(TAG, "onServiceConnected");
            }

            for (InternalDeathRecipient listener : mListeners) {
                try {
                    final ISpellCheckerSession session = spellChecker.getISpellCheckerSession(
                            listener.mScLocale, listener.mScListener, listener.mBundle);
                    synchronized(mSpellCheckerMap) {
                        if (mListeners.contains(listener)) {
                            listener.mTsListener.onServiceConnected(session);
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception in getting the spell checker session."
                            + "Reconnect to the spellchecker. ", e);
                    removeAll();
                    return;
                }
            }
            synchronized(mSpellCheckerMap) {
                mSpellChecker = spellChecker;
                mConnected = true;
            }
        }

        public InternalDeathRecipient addListener(ITextServicesSessionListener tsListener,
                String locale, ISpellCheckerSessionListener scListener, int uid, Bundle bundle) {
            if (DBG) {
                Slog.d(TAG, "addListener: " + locale);
            }
            InternalDeathRecipient recipient = null;
            synchronized(mSpellCheckerMap) {
                try {
                    final int size = mListeners.size();
                    for (int i = 0; i < size; ++i) {
                        if (mListeners.get(i).hasSpellCheckerListener(scListener)) {
                            // do not add the lister if the group already contains this.
                            return null;
                        }
                    }
                    recipient = new InternalDeathRecipient(
                            this, tsListener, locale, scListener, uid, bundle);
                    scListener.asBinder().linkToDeath(recipient, 0);
                    mListeners.add(recipient);
                } catch(RemoteException e) {
                    // do nothing
                }
                cleanLocked();
            }
            return recipient;
        }

        public void removeListener(ISpellCheckerSessionListener listener) {
            if (DBG) {
                Slog.w(TAG, "remove listener: " + listener.hashCode());
            }
            synchronized(mSpellCheckerMap) {
                final int size = mListeners.size();
                final ArrayList<InternalDeathRecipient> removeList =
                        new ArrayList<InternalDeathRecipient>();
                for (int i = 0; i < size; ++i) {
                    final InternalDeathRecipient tempRecipient = mListeners.get(i);
                    if(tempRecipient.hasSpellCheckerListener(listener)) {
                        if (DBG) {
                            Slog.w(TAG, "found existing listener.");
                        }
                        removeList.add(tempRecipient);
                    }
                }
                final int removeSize = removeList.size();
                for (int i = 0; i < removeSize; ++i) {
                    if (DBG) {
                        Slog.w(TAG, "Remove " + removeList.get(i));
                    }
                    final InternalDeathRecipient idr = removeList.get(i);
                    idr.mScListener.asBinder().unlinkToDeath(idr, 0);
                    mListeners.remove(idr);
                }
                cleanLocked();
            }
        }

        // cleanLocked may remove elements from mSpellCheckerBindGroups
        private void cleanLocked() {
            if (DBG) {
                Slog.d(TAG, "cleanLocked");
            }
            // If there are no more active listeners, clean up.  Only do this
            // once.
            if (mBound && mListeners.isEmpty()) {
                mBound = false;
                final String sciId = mInternalConnection.mSciId;
                SpellCheckerBindGroup cur = mSpellCheckerBindGroups.get(sciId);
                if (cur == this) {
                    if (DBG) {
                        Slog.d(TAG, "Remove bind group.");
                    }
                    mSpellCheckerBindGroups.remove(sciId);
                }
                mContext.unbindService(mInternalConnection);
            }
        }

        public void removeAll() {
            Slog.e(TAG, "Remove the spell checker bind unexpectedly.");
            synchronized(mSpellCheckerMap) {
                final int size = mListeners.size();
                for (int i = 0; i < size; ++i) {
                    final InternalDeathRecipient idr = mListeners.get(i);
                    idr.mScListener.asBinder().unlinkToDeath(idr, 0);
                }
                mListeners.clear();
                cleanLocked();
            }
        }
    }

    private class InternalServiceConnection implements ServiceConnection {
        private final String mSciId;
        private final String mLocale;
        private final Bundle mBundle;
        public InternalServiceConnection(
                String id, String locale, Bundle bundle) {
            mSciId = id;
            mLocale = locale;
            mBundle = bundle;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized(mSpellCheckerMap) {
                onServiceConnectedInnerLocked(name, service);
            }
        }

        private void onServiceConnectedInnerLocked(ComponentName name, IBinder service) {
            if (DBG) {
                Slog.w(TAG, "onServiceConnected: " + name);
            }
            final ISpellCheckerService spellChecker =
                    ISpellCheckerService.Stub.asInterface(service);
            final SpellCheckerBindGroup group = mSpellCheckerBindGroups.get(mSciId);
            if (group != null && this == group.mInternalConnection) {
                group.onServiceConnected(spellChecker);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized(mSpellCheckerMap) {
                final SpellCheckerBindGroup group = mSpellCheckerBindGroups.get(mSciId);
                if (group != null && this == group.mInternalConnection) {
                    mSpellCheckerBindGroups.remove(mSciId);
                }
            }
        }
    }

    private class InternalDeathRecipient implements IBinder.DeathRecipient {
        public final ITextServicesSessionListener mTsListener;
        public final ISpellCheckerSessionListener mScListener;
        public final String mScLocale;
        private final SpellCheckerBindGroup mGroup;
        public final int mUid;
        public final Bundle mBundle;
        public InternalDeathRecipient(SpellCheckerBindGroup group,
                ITextServicesSessionListener tsListener, String scLocale,
                ISpellCheckerSessionListener scListener, int uid, Bundle bundle) {
            mTsListener = tsListener;
            mScListener = scListener;
            mScLocale = scLocale;
            mGroup = group;
            mUid = uid;
            mBundle = bundle;
        }

        public boolean hasSpellCheckerListener(ISpellCheckerSessionListener listener) {
            return listener.asBinder().equals(mScListener.asBinder());
        }

        @Override
        public void binderDied() {
            mGroup.removeListener(mScListener);
        }
    }

    private static class TextServicesSettings {
        private final ContentResolver mResolver;
        private int mCurrentUserId;
        public TextServicesSettings(ContentResolver resolver, int userId) {
            mResolver = resolver;
            mCurrentUserId = userId;
        }

        public void setCurrentUserId(int userId) {
            if (DBG) {
                Slog.d(TAG, "--- Swtich the current user from " + mCurrentUserId + " to "
                        + userId + ", new ime = " + getSelectedSpellChecker());
            }
            // TSMS settings are kept per user, so keep track of current user
            mCurrentUserId = userId;
        }

        public int getCurrentUserId() {
            return mCurrentUserId;
        }

        public void putSelectedSpellChecker(String sciId) {
            Settings.Secure.putStringForUser(mResolver,
                    Settings.Secure.SELECTED_SPELL_CHECKER, sciId, mCurrentUserId);
        }

        public void putSelectedSpellCheckerSubtype(int hashCode) {
            Settings.Secure.putStringForUser(mResolver,
                    Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE, String.valueOf(hashCode),
                    mCurrentUserId);
        }

        public void setSpellCheckerEnabled(boolean enabled) {
            Settings.Secure.putIntForUser(mResolver,
                    Settings.Secure.SPELL_CHECKER_ENABLED, enabled ? 1 : 0, mCurrentUserId);
        }

        public String getSelectedSpellChecker() {
            return Settings.Secure.getStringForUser(mResolver,
                    Settings.Secure.SELECTED_SPELL_CHECKER, mCurrentUserId);
        }

        public String getSelectedSpellCheckerSubtype() {
            return Settings.Secure.getStringForUser(mResolver,
                    Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE, mCurrentUserId);
        }

        public boolean isSpellCheckerEnabled() {
            return Settings.Secure.getIntForUser(mResolver,
                    Settings.Secure.SPELL_CHECKER_ENABLED, 1, mCurrentUserId) == 1;
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
