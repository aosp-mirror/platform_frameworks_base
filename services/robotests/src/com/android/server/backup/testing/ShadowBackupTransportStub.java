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

package com.android.server.backup.testing;

import android.os.IBinder;

import com.android.internal.backup.IBackupTransport;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.HashMap;
import java.util.Map;

/**
 * Shadow IBackupTransport.Stub, returns a transport corresponding to the binder.
 */
@Implements(IBackupTransport.Stub.class)
public class ShadowBackupTransportStub {
    public static Map<IBinder, IBackupTransport> sBinderTransportMap = new HashMap<>();

    @Implementation
    public static IBackupTransport asInterface(IBinder obj) {
        return sBinderTransportMap.get(obj);
    }
}
