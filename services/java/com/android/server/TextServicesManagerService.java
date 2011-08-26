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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.service.textservice.SpellCheckerService;
import android.text.TextUtils;
import android.util.Slog;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.SpellCheckerSubtype;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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

    public void systemReady() {
        if (!mSystemReady) {
            mSystemReady = true;
        }
    }

    public TextServicesManagerService(Context context) {
        mSystemReady = false;
        mContext = context;
        mMonitor = new TextServicesMonitor();
        mMonitor.register(context, true);
        synchronized (mSpellCheckerMap) {
            buildSpellCheckerMapLocked(context, mSpellCheckerList, mSpellCheckerMap);
        }
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
        @Override
        public void onSomePackagesChanged() {
            synchronized (mSpellCheckerMap) {
                buildSpellCheckerMapLocked(mContext, mSpellCheckerList, mSpellCheckerMap);
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
            ArrayList<SpellCheckerInfo> list, HashMap<String, SpellCheckerInfo> map) {
        list.clear();
        map.clear();
        final PackageManager pm = context.getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServices(
                new Intent(SpellCheckerService.SERVICE_INTERFACE), PackageManager.GET_META_DATA);
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
        synchronized (mSpellCheckerMap) {
            final String curSpellCheckerId =
                    Settings.Secure.getString(mContext.getContentResolver(),
                            Settings.Secure.SELECTED_SPELL_CHECKER);
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
    // TODO: Save SpellCheckerSubtype by supported languages.
    @Override
    public SpellCheckerSubtype getCurrentSpellCheckerSubtype(
            String locale, boolean allowImplicitlySelectedSubtype) {
        synchronized (mSpellCheckerMap) {
            final String subtypeHashCodeStr =
                    Settings.Secure.getString(mContext.getContentResolver(),
                            Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE);
            if (DBG) {
                Slog.w(TAG, "getCurrentSpellChecker: " + subtypeHashCodeStr);
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
            final String systemLocale =
                    mContext.getResources().getConfiguration().locale.toString();
            SpellCheckerSubtype candidate = null;
            for (int i = 0; i < sci.getSubtypeCount(); ++i) {
                final SpellCheckerSubtype scs = sci.getSubtypeAt(i);
                if (hashCode == 0) {
                    if (systemLocale.equals(locale)) {
                        return scs;
                    } else if (candidate == null) {
                        final String scsLocale = scs.getLocale();
                        if (systemLocale.length() >= 2
                                && scsLocale.length() >= 2
                                && systemLocale.substring(0, 2).equals(
                                        scsLocale.substring(0, 2))) {
                            candidate = scs;
                        }
                    }
                } else if (scs.hashCode() == hashCode) {
                    if (DBG) {
                        Slog.w(TAG, "Return subtype " + scs.hashCode());
                    }
                    return scs;
                }
            }
            return candidate;
        }
    }

    @Override
    public void getSpellCheckerService(String sciId, String locale,
            ITextServicesSessionListener tsListener, ISpellCheckerSessionListener scListener,
            Bundle bundle) {
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
                sciId, locale, scListener, bundle);
        final Intent serviceIntent = new Intent(SpellCheckerService.SERVICE_INTERFACE);
        serviceIntent.setComponent(info.getComponent());
        if (DBG) {
            Slog.w(TAG, "bind service: " + info.getId());
        }
        if (!mContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
            Slog.e(TAG, "Failed to get a spell checker service.");
            return;
        }
        final SpellCheckerBindGroup group = new SpellCheckerBindGroup(
                connection, tsListener, locale, scListener, uid, bundle);
        mSpellCheckerBindGroups.put(sciId, group);
    }

    @Override
    public SpellCheckerInfo[] getEnabledSpellCheckers() {
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
        if (DBG) {
            Slog.d(TAG, "FinishSpellCheckerService");
        }
        synchronized(mSpellCheckerMap) {
            for (SpellCheckerBindGroup group : mSpellCheckerBindGroups.values()) {
                if (group == null) continue;
                group.removeListener(listener);
            }
        }
    }

    @Override
    public void setCurrentSpellChecker(String locale, String sciId) {
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
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.SELECTED_SPELL_CHECKER, sciId);
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
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE, String.valueOf(tempHashCode));
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
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.SPELL_CHECKER_ENABLED, enabled ? 1 : 0);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean isSpellCheckerEnabledLocked() {
        final long ident = Binder.clearCallingIdentity();
        try {
            final boolean retval = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.SPELL_CHECKER_ENABLED, 1) == 1;
            if (DBG) {
                Slog.w(TAG, "getSpellCheckerEnabled: " + retval);
            }
            return retval;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // SpellCheckerBindGroup contains active text service session listeners.
    // If there are no listeners anymore, the SpellCheckerBindGroup instance will be removed from
    // mSpellCheckerBindGroups
    private class SpellCheckerBindGroup {
        private final String TAG = SpellCheckerBindGroup.class.getSimpleName();
        private final InternalServiceConnection mInternalConnection;
        private final ArrayList<InternalDeathRecipient> mListeners =
                new ArrayList<InternalDeathRecipient>();
        public ISpellCheckerService mSpellChecker;
        public boolean mConnected;

        public SpellCheckerBindGroup(InternalServiceConnection connection,
                ITextServicesSessionListener listener, String locale,
                ISpellCheckerSessionListener scListener, int uid, Bundle bundle) {
            mInternalConnection = connection;
            mConnected = false;
            addListener(listener, locale, scListener, uid, bundle);
        }

        public void onServiceConnected(ISpellCheckerService spellChecker) {
            if (DBG) {
                Slog.d(TAG, "onServiceConnected");
            }
            synchronized(mSpellCheckerMap) {
                for (InternalDeathRecipient listener : mListeners) {
                    try {
                        final ISpellCheckerSession session = spellChecker.getISpellCheckerSession(
                                listener.mScLocale, listener.mScListener, listener.mBundle);
                        listener.mTsListener.onServiceConnected(session);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Exception in getting the spell checker session: " + e);
                        removeAll();
                        return;
                    }
                }
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
                    mListeners.remove(removeList.get(i));
                }
                cleanLocked();
            }
        }

        private void cleanLocked() {
            if (DBG) {
                Slog.d(TAG, "cleanLocked");
            }
            if (mListeners.isEmpty()) {
                if (mSpellCheckerBindGroups.containsKey(this)) {
                    mSpellCheckerBindGroups.remove(this);
                }
                // Unbind service when there is no active clients.
                mContext.unbindService(mInternalConnection);
            }
        }

        public void removeAll() {
            Slog.e(TAG, "Remove the spell checker bind unexpectedly.");
            synchronized(mSpellCheckerMap) {
                mListeners.clear();
                cleanLocked();
            }
        }
    }

    private class InternalServiceConnection implements ServiceConnection {
        private final ISpellCheckerSessionListener mListener;
        private final String mSciId;
        private final String mLocale;
        private final Bundle mBundle;
        public InternalServiceConnection(
                String id, String locale, ISpellCheckerSessionListener listener, Bundle bundle) {
            mSciId = id;
            mLocale = locale;
            mListener = listener;
            mBundle = bundle;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized(mSpellCheckerMap) {
                if (DBG) {
                    Slog.w(TAG, "onServiceConnected: " + name);
                }
                ISpellCheckerService spellChecker = ISpellCheckerService.Stub.asInterface(service);
                final SpellCheckerBindGroup group = mSpellCheckerBindGroups.get(mSciId);
                if (group != null) {
                    group.onServiceConnected(spellChecker);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mSpellCheckerBindGroups.remove(mSciId);
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
}
