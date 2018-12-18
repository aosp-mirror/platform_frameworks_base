/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.os.Binder;
import android.os.SystemClock;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.app.procstats.AssociationState;
import com.android.internal.app.procstats.ProcessStats;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;

/**
 * Represents a link between a content provider and client.
 */
public final class ContentProviderConnection extends Binder {
    public final ContentProviderRecord provider;
    public final ProcessRecord client;
    public final String clientPackage;
    public AssociationState.SourceState association;
    public final long createTime;
    public int stableCount;
    public int unstableCount;
    // The client of this connection is currently waiting for the provider to appear.
    // Protected by the provider lock.
    public boolean waiting;
    // The provider of this connection is now dead.
    public boolean dead;

    // For debugging.
    public int numStableIncs;
    public int numUnstableIncs;

    public ContentProviderConnection(ContentProviderRecord _provider, ProcessRecord _client,
            String _clientPackage) {
        provider = _provider;
        client = _client;
        clientPackage = _clientPackage;
        createTime = SystemClock.elapsedRealtime();
    }

    public void startAssociationIfNeeded() {
        // If we don't already have an active association, create one...  but only if this
        // is an association between two different processes.
        if (ActivityManagerService.TRACK_PROCSTATS_ASSOCIATIONS
                && association == null && provider.proc != null
                && (provider.appInfo.uid != client.uid
                        || !provider.info.processName.equals(client.processName))) {
            ProcessStats.ProcessStateHolder holder = provider.proc.pkgList.get(
                    provider.name.getPackageName());
            if (holder == null) {
                Slog.wtf(TAG_AM, "No package in referenced provider "
                        + provider.name.toShortString() + ": proc=" + provider.proc);
            } else if (holder.pkg == null) {
                Slog.wtf(TAG_AM, "Inactive holder in referenced provider "
                        + provider.name.toShortString() + ": proc=" + provider.proc);
            } else {
                association = holder.pkg.getAssociationStateLocked(holder.state,
                        provider.name.getClassName()).startSource(client.uid, client.processName,
                        clientPackage);

            }
        }
    }

    public void trackProcState(int procState, int seq, long now) {
        if (association != null) {
            association.trackProcState(procState, seq, now);
        }
    }

    public void stopAssociation() {
        if (association != null) {
            association.stop();
            association = null;
        }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("ContentProviderConnection{");
        toShortString(sb);
        sb.append('}');
        return sb.toString();
    }

    public String toShortString() {
        StringBuilder sb = new StringBuilder(128);
        toShortString(sb);
        return sb.toString();
    }

    public String toClientString() {
        StringBuilder sb = new StringBuilder(128);
        toClientString(sb);
        return sb.toString();
    }

    public void toShortString(StringBuilder sb) {
        sb.append(provider.toShortString());
        sb.append("->");
        toClientString(sb);
    }

    public void toClientString(StringBuilder sb) {
        sb.append(client.toShortString());
        sb.append(" s");
        sb.append(stableCount);
        sb.append("/");
        sb.append(numStableIncs);
        sb.append(" u");
        sb.append(unstableCount);
        sb.append("/");
        sb.append(numUnstableIncs);
        if (waiting) {
            sb.append(" WAITING");
        }
        if (dead) {
            sb.append(" DEAD");
        }
        long nowReal = SystemClock.elapsedRealtime();
        sb.append(" ");
        TimeUtils.formatDuration(nowReal-createTime, sb);
    }
}
