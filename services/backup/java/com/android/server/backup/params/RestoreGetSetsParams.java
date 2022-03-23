/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.params;

import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IRestoreObserver;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.restore.ActiveRestoreSession;
import com.android.server.backup.transport.TransportClient;

public class RestoreGetSetsParams {
    public final TransportClient transportClient;
    public final ActiveRestoreSession session;
    public final IRestoreObserver observer;
    public final IBackupManagerMonitor monitor;
    public final OnTaskFinishedListener listener;

    public RestoreGetSetsParams(TransportClient _transportClient, ActiveRestoreSession _session,
            IRestoreObserver _observer, IBackupManagerMonitor _monitor,
            OnTaskFinishedListener _listener) {
        transportClient = _transportClient;
        session = _session;
        observer = _observer;
        monitor = _monitor;
        listener = _listener;
    }
}
