/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.net;

import static android.Manifest.permission.NETWORK_STACK;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.net.NetworkStack.checkNetworkStackPermission;
import static android.net.NetworkStack.checkNetworkStackPermissionOr;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class NetworkStackTest {
    private static final String [] OTHER_PERMISSION = {"otherpermission1", "otherpermission2"};

    @Mock Context mCtx;

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testCheckNetworkStackPermission() throws Exception {
        when(mCtx.checkCallingOrSelfPermission(eq(NETWORK_STACK))).thenReturn(PERMISSION_GRANTED);
        when(mCtx.checkCallingOrSelfPermission(eq(PERMISSION_MAINLINE_NETWORK_STACK)))
                .thenReturn(PERMISSION_DENIED);
        checkNetworkStackPermission(mCtx);
        checkNetworkStackPermissionOr(mCtx, OTHER_PERMISSION);

        when(mCtx.checkCallingOrSelfPermission(eq(NETWORK_STACK))).thenReturn(PERMISSION_DENIED);
        when(mCtx.checkCallingOrSelfPermission(eq(PERMISSION_MAINLINE_NETWORK_STACK)))
                .thenReturn(PERMISSION_GRANTED);
        checkNetworkStackPermission(mCtx);
        checkNetworkStackPermissionOr(mCtx, OTHER_PERMISSION);

        when(mCtx.checkCallingOrSelfPermission(any())).thenReturn(PERMISSION_DENIED);

        try {
            checkNetworkStackPermissionOr(mCtx, OTHER_PERMISSION);
        } catch (SecurityException e) {
            // Expect to get a SecurityException
            return;
        }

        fail("Expect fail but permission granted.");
    }
}
