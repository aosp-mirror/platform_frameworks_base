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
import com.android.internal.view.ITextServiceManager;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.SpellCheckerInfo;
import android.view.inputmethod.SpellCheckerService;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class TextServiceManagerService extends ITextServiceManager.Stub {
    private static final String TAG = TextServiceManagerService.class.getSimpleName();
    private static final boolean DBG = false;

    private final Context mContext;
    private boolean mSystemReady;
    private final TextServiceMonitor mMonitor;
    private final HashMap<String, SpellCheckerInfo> mSpellCheckerMap =
            new HashMap<String, SpellCheckerInfo>();
    private final ArrayList<SpellCheckerInfo> mSpellCheckerList = new ArrayList<SpellCheckerInfo>();

    public void systemReady() {
        if (!mSystemReady) {
            mSystemReady = true;
        }
    }

    public TextServiceManagerService(Context context) {
        mSystemReady = false;
        mContext = context;
        mMonitor = new TextServiceMonitor();
        mMonitor.register(context, true);
        synchronized (mSpellCheckerMap) {
            buildSpellCheckerMapLocked(context, mSpellCheckerList, mSpellCheckerMap);
        }
    }

    private class TextServiceMonitor extends PackageMonitor {
        @Override
        public void onSomePackagesChanged() {
            synchronized (mSpellCheckerMap) {
                buildSpellCheckerMapLocked(mContext, mSpellCheckerList, mSpellCheckerMap);
                // TODO: Update for each locale
                final SpellCheckerInfo sci = getCurrentSpellChecker(null);
                final String packageName = sci.getPackageName();
                final int change = isPackageDisappearing(packageName);
                if (change == PACKAGE_PERMANENT_CHANGE || change == PACKAGE_TEMPORARY_CHANGE) {
                    // Package disappearing
                    setCurSpellChecker(findAvailSpellCheckerLocked(null, packageName));
                } else if (isPackageModified(packageName)) {
                    // Package modified
                    setCurSpellChecker(findAvailSpellCheckerLocked(null, packageName));
                }
            }
        }
    }

    // Not used for now
    private SpellCheckerInfo getAppearedPackageLocked(Context context, PackageMonitor monitor) {
        final int N = mSpellCheckerList.size();
        for (int i = 0; i < N; ++i) {
            final SpellCheckerInfo sci = mSpellCheckerList.get(i);
            String packageName = sci.getPackageName();
            if (monitor.isPackageAppearing(packageName)
                    == PackageMonitor.PACKAGE_PERMANENT_CHANGE) {
                return sci;
            }
        }
        return null;
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
            final SpellCheckerInfo sci = new SpellCheckerInfo(context, ri);
            list.add(sci);
            map.put(sci.getId(), sci);
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
                            Settings.Secure.SPELL_CHECKER_SERVICE);
            if (TextUtils.isEmpty(curSpellCheckerId)) {
                return null;
            }
            return mSpellCheckerMap.get(curSpellCheckerId);
        }
    }

    private void setCurSpellChecker(SpellCheckerInfo sci) {
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.SPELL_CHECKER_SERVICE, sci == null ? "" : sci.getId());
    }
}
