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

import android.content.pm.PackageInfo;

import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.transport.TransportConnection;

public class ClearParams {
    public TransportConnection mTransportConnection;
    public PackageInfo packageInfo;
    public OnTaskFinishedListener listener;

    public ClearParams(
            TransportConnection transportConnection,
            PackageInfo packageInfo,
            OnTaskFinishedListener listener) {
        this.mTransportConnection = transportConnection;
        this.packageInfo = packageInfo;
        this.listener = listener;
    }
}
