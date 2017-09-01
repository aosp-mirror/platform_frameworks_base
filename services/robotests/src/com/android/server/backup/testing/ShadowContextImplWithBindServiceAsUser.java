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

import android.annotation.RequiresPermission;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.UserHandle;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowContextImpl;

import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of ContextImpl shadow, handling bindServiceAsUser().
 */
@Implements(className = ShadowContextImpl.CLASS_NAME)
public class ShadowContextImplWithBindServiceAsUser extends ShadowContextImpl {
    public static Map<ComponentName, IBinder> sComponentBinderMap = new HashMap<>();

    @Implementation
    public boolean bindServiceAsUser(@RequiresPermission Intent service, ServiceConnection conn,
            int flags, UserHandle user) {
        ShadowApplication.getInstance().setComponentNameAndServiceForBindService(
                service.getComponent(), sComponentBinderMap.get(service.getComponent()));
        return bindService(service, conn, flags);
    }
}
