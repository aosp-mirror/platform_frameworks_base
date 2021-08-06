/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;

import android.app.ContentProviderHolder;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.IContentProvider;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.app.procstats.AssociationState;
import com.android.internal.app.procstats.ProcessStats;

import java.io.PrintWriter;
import java.util.ArrayList;

final class ContentProviderRecord implements ComponentName.WithComponentName {
    // Maximum attempts to bring up the content provider before giving up.
    static final int MAX_RETRY_COUNT = 3;

    final ActivityManagerService service;
    public final ProviderInfo info;
    final int uid;
    final ApplicationInfo appInfo;
    final ComponentName name;
    final boolean singleton;
    public IContentProvider provider;
    public boolean noReleaseNeeded;
    // All attached clients
    final ArrayList<ContentProviderConnection> connections
            = new ArrayList<ContentProviderConnection>();
    //final HashSet<ProcessRecord> clients = new HashSet<ProcessRecord>();
    // Handles for non-framework processes supported by this provider
    ArrayMap<IBinder, ExternalProcessHandle> externalProcessTokenToHandle;
    // Count for external process for which we have no handles.
    int externalProcessNoHandleCount;
    int mRestartCount; // number of times we tried before bringing up it successfully.
    ProcessRecord proc; // if non-null, hosting process.
    ProcessRecord launchingApp; // if non-null, waiting for this app to be launched.
    String stringName;
    String shortStringName;

    public ContentProviderRecord(ActivityManagerService _service, ProviderInfo _info,
            ApplicationInfo ai, ComponentName _name, boolean _singleton) {
        service = _service;
        info = _info;
        uid = ai.uid;
        appInfo = ai;
        name = _name;
        singleton = _singleton;
        noReleaseNeeded = (uid == 0 || uid == Process.SYSTEM_UID)
                && (_name == null || !"com.android.settings".equals(_name.getPackageName()));
    }

    public ContentProviderRecord(ContentProviderRecord cpr) {
        service = cpr.service;
        info = cpr.info;
        uid = cpr.uid;
        appInfo = cpr.appInfo;
        name = cpr.name;
        singleton = cpr.singleton;
        noReleaseNeeded = cpr.noReleaseNeeded;
    }

    public ContentProviderHolder newHolder(ContentProviderConnection conn, boolean local) {
        ContentProviderHolder holder = new ContentProviderHolder(info);
        holder.provider = provider;
        holder.noReleaseNeeded = noReleaseNeeded;
        holder.connection = conn;
        holder.mLocal = local;
        return holder;
    }

    public void setProcess(ProcessRecord proc) {
        this.proc = proc;
        if (ActivityManagerService.TRACK_PROCSTATS_ASSOCIATIONS) {
            for (int iconn = connections.size() - 1; iconn >= 0; iconn--) {
                final ContentProviderConnection conn = connections.get(iconn);
                if (proc != null) {
                    conn.startAssociationIfNeeded();
                } else {
                    conn.stopAssociation();
                }
            }
            if (externalProcessTokenToHandle != null) {
                for (int iext = externalProcessTokenToHandle.size() - 1; iext >= 0; iext--) {
                    final ExternalProcessHandle handle = externalProcessTokenToHandle.valueAt(iext);
                    if (proc != null) {
                        handle.startAssociationIfNeeded(this);
                    } else {
                        handle.stopAssociation();
                    }
                }
            }
        }
    }

    public boolean canRunHere(ProcessRecord app) {
        return (info.multiprocess || info.processName.equals(app.processName))
                && uid == app.info.uid;
    }

    public void addExternalProcessHandleLocked(IBinder token, int callingUid, String callingTag) {
        if (token == null) {
            externalProcessNoHandleCount++;
        } else {
            if (externalProcessTokenToHandle == null) {
                externalProcessTokenToHandle = new ArrayMap<>();
            }
            ExternalProcessHandle handle = externalProcessTokenToHandle.get(token);
            if (handle == null) {
                handle = new ExternalProcessHandle(token, callingUid, callingTag);
                externalProcessTokenToHandle.put(token, handle);
                handle.startAssociationIfNeeded(this);
            }
            handle.mAcquisitionCount++;
        }
    }

    public boolean removeExternalProcessHandleLocked(IBinder token) {
        if (hasExternalProcessHandles()) {
            boolean hasHandle = false;
            if (externalProcessTokenToHandle != null) {
                ExternalProcessHandle handle = externalProcessTokenToHandle.get(token);
                if (handle != null) {
                    hasHandle = true;
                    handle.mAcquisitionCount--;
                    if (handle.mAcquisitionCount == 0) {
                        removeExternalProcessHandleInternalLocked(token);
                        return true;
                    }
                }
            }
            if (!hasHandle) {
                externalProcessNoHandleCount--;
                return true;
            }
        }
        return false;
    }

    private void removeExternalProcessHandleInternalLocked(IBinder token) {
        ExternalProcessHandle handle = externalProcessTokenToHandle.get(token);
        handle.unlinkFromOwnDeathLocked();
        handle.stopAssociation();
        externalProcessTokenToHandle.remove(token);
        if (externalProcessTokenToHandle.size() == 0) {
            externalProcessTokenToHandle = null;
        }
    }

    public boolean hasExternalProcessHandles() {
        return (externalProcessTokenToHandle != null || externalProcessNoHandleCount > 0);
    }

    public boolean hasConnectionOrHandle() {
        return !connections.isEmpty() || hasExternalProcessHandles();
    }

    /**
     * Notify all clients that the provider has been published and ready to use,
     * or timed out.
     *
     * @param status true: successfully published; false: timed out
     */
    void onProviderPublishStatusLocked(boolean status) {
        final int numOfConns = connections.size();
        for (int i = 0; i < numOfConns; i++) {
            final ContentProviderConnection conn = connections.get(i);
            if (conn.waiting && conn.client != null) {
                final ProcessRecord client = conn.client;
                if (!status) {
                    if (launchingApp == null) {
                        Slog.w(TAG_AM, "Unable to launch app "
                                + appInfo.packageName + "/"
                                + appInfo.uid + " for provider "
                                + info.authority + ": launching app became null");
                        EventLogTags.writeAmProviderLostProcess(
                                UserHandle.getUserId(appInfo.uid),
                                appInfo.packageName,
                                appInfo.uid, info.authority);
                    } else {
                        Slog.wtf(TAG_AM, "Timeout waiting for provider "
                                + appInfo.packageName + "/"
                                + appInfo.uid + " for provider "
                                + info.authority
                                + " caller=" + client);
                    }
                }
                final IApplicationThread thread = client.getThread();
                if (thread != null) {
                    try {
                        thread.notifyContentProviderPublishStatus(
                                newHolder(status ? conn : null, false),
                                info.authority, conn.mExpectedUserId, status);
                    } catch (RemoteException e) {
                    }
                }
            }
            conn.waiting = false;
        }
    }

    void dump(PrintWriter pw, String prefix, boolean full) {
        if (full) {
            pw.print(prefix); pw.print("package=");
                    pw.print(info.applicationInfo.packageName);
                    pw.print(" process="); pw.println(info.processName);
        }
        pw.print(prefix); pw.print("proc="); pw.println(proc);
        if (launchingApp != null) {
            pw.print(prefix); pw.print("launchingApp="); pw.println(launchingApp);
        }
        if (full) {
            pw.print(prefix); pw.print("uid="); pw.print(uid);
                    pw.print(" provider="); pw.println(provider);
        }
        if (singleton) {
            pw.print(prefix); pw.print("singleton="); pw.println(singleton);
        }
        pw.print(prefix); pw.print("authority="); pw.println(info.authority);
        if (full) {
            if (info.isSyncable || info.multiprocess || info.initOrder != 0) {
                pw.print(prefix); pw.print("isSyncable="); pw.print(info.isSyncable);
                        pw.print(" multiprocess="); pw.print(info.multiprocess);
                        pw.print(" initOrder="); pw.println(info.initOrder);
            }
        }
        if (full) {
            if (hasExternalProcessHandles()) {
                pw.print(prefix); pw.print("externals:");
                if (externalProcessTokenToHandle != null) {
                    pw.print(" w/token=");
                    pw.print(externalProcessTokenToHandle.size());
                }
                if (externalProcessNoHandleCount > 0) {
                    pw.print(" notoken=");
                    pw.print(externalProcessNoHandleCount);
                }
                pw.println();
            }
        } else {
            if (connections.size() > 0 || externalProcessNoHandleCount > 0) {
                pw.print(prefix); pw.print(connections.size());
                        pw.print(" connections, "); pw.print(externalProcessNoHandleCount);
                        pw.println(" external handles");
            }
        }
        if (connections.size() > 0) {
            if (full) {
                pw.print(prefix); pw.println("Connections:");
            }
            for (int i=0; i<connections.size(); i++) {
                ContentProviderConnection conn = connections.get(i);
                pw.print(prefix); pw.print("  -> "); pw.println(conn.toClientString());
                if (conn.provider != this) {
                    pw.print(prefix); pw.print("    *** WRONG PROVIDER: ");
                            pw.println(conn.provider);
                }
            }
        }
    }

    @Override
    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ContentProviderRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" u");
        sb.append(UserHandle.getUserId(uid));
        sb.append(' ');
        sb.append(name.flattenToShortString());
        sb.append('}');
        return stringName = sb.toString();
    }

    public String toShortString() {
        if (shortStringName != null) {
            return shortStringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append('/');
        sb.append(name.flattenToShortString());
        return shortStringName = sb.toString();
    }

    // This class represents a handle from an external process to a provider.
    private class ExternalProcessHandle implements DeathRecipient {
        private static final String LOG_TAG = "ExternalProcessHanldle";

        final IBinder mToken;
        final int mOwningUid;
        final String mOwningProcessName;
        int mAcquisitionCount;
        AssociationState.SourceState mAssociation;
        private Object mProcStatsLock;  // Internal lock for accessing AssociationState

        public ExternalProcessHandle(IBinder token, int owningUid, String owningProcessName) {
            mToken = token;
            mOwningUid = owningUid;
            mOwningProcessName = owningProcessName;
            try {
                token.linkToDeath(this, 0);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Couldn't register for death for token: " + mToken, re);
            }
        }

        public void unlinkFromOwnDeathLocked() {
            mToken.unlinkToDeath(this, 0);
        }

        public void startAssociationIfNeeded(ContentProviderRecord provider) {
            // If we don't already have an active association, create one...  but only if this
            // is an association between two different processes.
            if (ActivityManagerService.TRACK_PROCSTATS_ASSOCIATIONS
                    && mAssociation == null && provider.proc != null
                    && (provider.appInfo.uid != mOwningUid
                            || !provider.info.processName.equals(mOwningProcessName))) {
                ProcessStats.ProcessStateHolder holder =
                        provider.proc.getPkgList().get(provider.name.getPackageName());
                if (holder == null) {
                    Slog.wtf(TAG_AM, "No package in referenced provider "
                            + provider.name.toShortString() + ": proc=" + provider.proc);
                } else if (holder.pkg == null) {
                    Slog.wtf(TAG_AM, "Inactive holder in referenced provider "
                            + provider.name.toShortString() + ": proc=" + provider.proc);
                } else {
                    mProcStatsLock = provider.proc.mService.mProcessStats.mLock;
                    synchronized (mProcStatsLock) {
                        mAssociation = holder.pkg.getAssociationStateLocked(holder.state,
                                provider.name.getClassName()).startSource(mOwningUid,
                                mOwningProcessName, null);
                    }
                }
            }
        }

        public void stopAssociation() {
            if (mAssociation != null) {
                synchronized (mProcStatsLock) {
                    mAssociation.stop();
                }
                mAssociation = null;
            }
        }

        @Override
        public void binderDied() {
            synchronized (service) {
                if (hasExternalProcessHandles() &&
                        externalProcessTokenToHandle.get(mToken) != null) {
                    removeExternalProcessHandleInternalLocked(mToken);
                }
            }
        }
    }

    public ComponentName getComponentName() {
        return name;
    }
}
