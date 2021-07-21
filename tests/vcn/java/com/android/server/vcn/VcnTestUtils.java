/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vcn;

import static org.mockito.Mockito.doReturn;

import android.content.Context;
import android.net.IpSecManager;

import com.android.server.IpSecService;

public class VcnTestUtils {
    /** Mock system services by directly mocking the *Manager interface. */
    public static void setupSystemService(
            Context mockContext, Object service, String name, Class<?> serviceClass) {
        doReturn(name).when(mockContext).getSystemServiceName(serviceClass);
        doReturn(service).when(mockContext).getSystemService(name);
    }

    /** Mock IpSecService by mocking the underlying service binder. */
    public static IpSecManager setupIpSecManager(Context mockContext, IpSecService service) {
        doReturn(Context.IPSEC_SERVICE).when(mockContext).getSystemServiceName(IpSecManager.class);

        final IpSecManager ipSecMgr = new IpSecManager(mockContext, service);
        doReturn(ipSecMgr).when(mockContext).getSystemService(Context.IPSEC_SERVICE);

        // Return to ensure this doesn't get reaped.
        return ipSecMgr;
    }
}
