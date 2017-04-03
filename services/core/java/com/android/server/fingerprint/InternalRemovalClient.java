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

package com.android.server.fingerprint;

import android.content.Context;
import android.os.IBinder;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import com.android.server.fingerprint.RemovalClient;

public abstract class InternalRemovalClient extends RemovalClient {

    public InternalRemovalClient(Context context, long halDeviceId, IBinder token,
            IFingerprintServiceReceiver receiver, int fingerId, int groupId, int userId,
            boolean restricted, String owner) {

        super(context, halDeviceId, token, receiver, fingerId, groupId, userId, restricted, owner);

    }
}
