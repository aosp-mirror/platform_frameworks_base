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

package com.android.server.am;

import android.content.ComponentName;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserId;
import android.util.Slog;
import android.util.SparseArray;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Keeps track of content providers by authority (name) and class. It separates the mapping by
 * user and ones that are not user-specific (system providers).
 */
public class ProviderMap {

    private static final String TAG = "ProviderMap";

    private static final boolean DBG = false;

    private final HashMap<String, ContentProviderRecord> mGlobalByName
            = new HashMap<String, ContentProviderRecord>();
    private final HashMap<ComponentName, ContentProviderRecord> mGlobalByClass
            = new HashMap<ComponentName, ContentProviderRecord>();

    private final SparseArray<HashMap<String, ContentProviderRecord>> mProvidersByNamePerUser
            = new SparseArray<HashMap<String, ContentProviderRecord>>();
    private final SparseArray<HashMap<ComponentName, ContentProviderRecord>> mProvidersByClassPerUser
            = new SparseArray<HashMap<ComponentName, ContentProviderRecord>>();

    ContentProviderRecord getProviderByName(String name) {
        return getProviderByName(name, -1);
    }

    ContentProviderRecord getProviderByName(String name, int userId) {
        if (DBG) {
            Slog.i(TAG, "getProviderByName: " + name + " , callingUid = " + Binder.getCallingUid());
        }
        // Try to find it in the global list
        ContentProviderRecord record = mGlobalByName.get(name);
        if (record != null) {
            return record;
        }

        // Check the current user's list
        return getProvidersByName(userId).get(name);
    }

    ContentProviderRecord getProviderByClass(ComponentName name) {
        return getProviderByClass(name, -1);
    }

    ContentProviderRecord getProviderByClass(ComponentName name, int userId) {
        if (DBG) {
            Slog.i(TAG, "getProviderByClass: " + name + ", callingUid = " + Binder.getCallingUid());
        }
        // Try to find it in the global list
        ContentProviderRecord record = mGlobalByClass.get(name);
        if (record != null) {
            return record;
        }

        // Check the current user's list
        return getProvidersByClass(userId).get(name);
    }

    void putProviderByName(String name, ContentProviderRecord record) {
        if (DBG) {
            Slog.i(TAG, "putProviderByName: " + name + " , callingUid = " + Binder.getCallingUid()
                + ", record uid = " + record.appInfo.uid);
        }
        if (record.appInfo.uid < Process.FIRST_APPLICATION_UID) {
            mGlobalByName.put(name, record);
        } else {
            final int userId = UserId.getUserId(record.appInfo.uid);
            getProvidersByName(userId).put(name, record);
        }
    }

    void putProviderByClass(ComponentName name, ContentProviderRecord record) {
        if (DBG) {
            Slog.i(TAG, "putProviderByClass: " + name + " , callingUid = " + Binder.getCallingUid()
                + ", record uid = " + record.appInfo.uid);
        }
        if (record.appInfo.uid < Process.FIRST_APPLICATION_UID) {
            mGlobalByClass.put(name, record);
        } else {
            final int userId = UserId.getUserId(record.appInfo.uid);
            getProvidersByClass(userId).put(name, record);
        }
    }

    void removeProviderByName(String name, int optionalUserId) {
        if (mGlobalByName.containsKey(name)) {
            if (DBG)
                Slog.i(TAG, "Removing from globalByName name=" + name);
            mGlobalByName.remove(name);
        } else {
            // TODO: Verify this works, i.e., the caller happens to be from the correct user
            if (DBG)
                Slog.i(TAG,
                        "Removing from providersByName name=" + name + " user="
                        + (optionalUserId == -1 ? Binder.getOrigCallingUser() : optionalUserId));
            getProvidersByName(optionalUserId).remove(name);
        }
    }

    void removeProviderByClass(ComponentName name, int optionalUserId) {
        if (mGlobalByClass.containsKey(name)) {
            if (DBG)
                Slog.i(TAG, "Removing from globalByClass name=" + name);
            mGlobalByClass.remove(name);
        } else {
            if (DBG)
                Slog.i(TAG,
                        "Removing from providersByClass name=" + name + " user="
                        + (optionalUserId == -1 ? Binder.getOrigCallingUser() : optionalUserId));
            getProvidersByClass(optionalUserId).remove(name);
        }
    }

    private HashMap<String, ContentProviderRecord> getProvidersByName(int optionalUserId) {
        final int userId = optionalUserId >= 0
                ? optionalUserId : Binder.getOrigCallingUser();
        final HashMap<String, ContentProviderRecord> map = mProvidersByNamePerUser.get(userId);
        if (map == null) {
            HashMap<String, ContentProviderRecord> newMap = new HashMap<String, ContentProviderRecord>();
            mProvidersByNamePerUser.put(userId, newMap);
            return newMap;
        } else {
            return map;
        }
    }

    HashMap<ComponentName, ContentProviderRecord> getProvidersByClass(int optionalUserId) {
        final int userId = optionalUserId >= 0
                ? optionalUserId : Binder.getOrigCallingUser();
        final HashMap<ComponentName, ContentProviderRecord> map = mProvidersByClassPerUser.get(userId);
        if (map == null) {
            HashMap<ComponentName, ContentProviderRecord> newMap = new HashMap<ComponentName, ContentProviderRecord>();
            mProvidersByClassPerUser.put(userId, newMap);
            return newMap;
        } else {
            return map;
        }
    }

    private void dumpProvidersByClassLocked(PrintWriter pw, boolean dumpAll,
            HashMap<ComponentName, ContentProviderRecord> map) {
        Iterator<Map.Entry<ComponentName, ContentProviderRecord>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ComponentName, ContentProviderRecord> e = it.next();
            ContentProviderRecord r = e.getValue();
            pw.print("  * ");
            pw.println(r);
            r.dump(pw, "    ", dumpAll);
        }
    }

    private void dumpProvidersByNameLocked(PrintWriter pw,
            HashMap<String, ContentProviderRecord> map) {
        Iterator<Map.Entry<String, ContentProviderRecord>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ContentProviderRecord> e = it.next();
            ContentProviderRecord r = e.getValue();
            pw.print("  ");
            pw.print(e.getKey());
            pw.print(": ");
            pw.println(r.toShortString());
        }
    }

    void dumpProvidersLocked(PrintWriter pw, boolean dumpAll) {
        boolean needSep = false;
        if (mGlobalByClass.size() > 0) {
            if (needSep)
                pw.println(" ");
            pw.println("  Published content providers (by class):");
            dumpProvidersByClassLocked(pw, dumpAll, mGlobalByClass);
        }

        if (mProvidersByClassPerUser.size() > 1) {
            pw.println("");
            for (int i = 0; i < mProvidersByClassPerUser.size(); i++) {
                HashMap<ComponentName, ContentProviderRecord> map = mProvidersByClassPerUser.valueAt(i);
                pw.println("  User " + mProvidersByClassPerUser.keyAt(i) + ":");
                dumpProvidersByClassLocked(pw, dumpAll, map);
                pw.println(" ");
            }
        } else if (mProvidersByClassPerUser.size() == 1) {
            HashMap<ComponentName, ContentProviderRecord> map = mProvidersByClassPerUser.valueAt(0);
            dumpProvidersByClassLocked(pw, dumpAll, map);
        }
        needSep = true;

        if (dumpAll) {
            pw.println(" ");
            pw.println("  Authority to provider mappings:");
            dumpProvidersByNameLocked(pw, mGlobalByName);

            for (int i = 0; i < mProvidersByNamePerUser.size(); i++) {
                if (i > 0) {
                    pw.println("  User " + mProvidersByNamePerUser.keyAt(i) + ":");
                }
                dumpProvidersByNameLocked(pw, mProvidersByNamePerUser.valueAt(i));
            }
        }
    }

    protected boolean dumpProvider(FileDescriptor fd, PrintWriter pw, String name, String[] args,
            int opti, boolean dumpAll) {
        ArrayList<ContentProviderRecord> providers = new ArrayList<ContentProviderRecord>();

        if ("all".equals(name)) {
            synchronized (this) {
                for (ContentProviderRecord r1 : getProvidersByClass(-1).values()) {
                    providers.add(r1);
                }
            }
        } else {
            ComponentName componentName = name != null
                    ? ComponentName.unflattenFromString(name) : null;
            int objectId = 0;
            if (componentName == null) {
                // Not a '/' separated full component name; maybe an object ID?
                try {
                    objectId = Integer.parseInt(name, 16);
                    name = null;
                    componentName = null;
                } catch (RuntimeException e) {
                }
            }

            synchronized (this) {
                for (ContentProviderRecord r1 : getProvidersByClass(-1).values()) {
                    if (componentName != null) {
                        if (r1.name.equals(componentName)) {
                            providers.add(r1);
                        }
                    } else if (name != null) {
                        if (r1.name.flattenToString().contains(name)) {
                            providers.add(r1);
                        }
                    } else if (System.identityHashCode(r1) == objectId) {
                        providers.add(r1);
                    }
                }
            }
        }

        if (providers.size() <= 0) {
            return false;
        }

        boolean needSep = false;
        for (int i=0; i<providers.size(); i++) {
            if (needSep) {
                pw.println();
            }
            needSep = true;
            dumpProvider("", fd, pw, providers.get(i), args, dumpAll);
        }
        return true;
    }

    /**
     * Invokes IApplicationThread.dumpProvider() on the thread of the specified provider if
     * there is a thread associated with the provider.
     */
    private void dumpProvider(String prefix, FileDescriptor fd, PrintWriter pw,
            final ContentProviderRecord r, String[] args, boolean dumpAll) {
        String innerPrefix = prefix + "  ";
        synchronized (this) {
            pw.print(prefix); pw.print("PROVIDER ");
                    pw.print(r);
                    pw.print(" pid=");
                    if (r.proc != null) pw.println(r.proc.pid);
                    else pw.println("(not running)");
            if (dumpAll) {
                r.dump(pw, innerPrefix, true);
            }
        }
        if (r.proc != null && r.proc.thread != null) {
            pw.println("    Client:");
            pw.flush();
            try {
                TransferPipe tp = new TransferPipe();
                try {
                    r.proc.thread.dumpProvider(
                            tp.getWriteFd().getFileDescriptor(), r.provider.asBinder(), args);
                    tp.setBufferPrefix("      ");
                    // Short timeout, since blocking here can
                    // deadlock with the application.
                    tp.go(fd, 2000);
                } finally {
                    tp.kill();
                }
            } catch (IOException ex) {
                pw.println("      Failure while dumping the provider: " + ex);
            } catch (RemoteException ex) {
                pw.println("      Got a RemoteException while dumping the service");
            }
        }
    }


}
