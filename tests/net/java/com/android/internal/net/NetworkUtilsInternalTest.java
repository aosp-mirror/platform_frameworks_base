/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.net;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.AF_UNIX;
import static android.system.OsConstants.EPERM;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_STREAM;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.fail;

import android.system.ErrnoException;
import android.system.Os;

import androidx.test.runner.AndroidJUnit4;

import libcore.io.IoUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SmallTest
public class NetworkUtilsInternalTest {

    private static void expectSocketSuccess(String msg, int domain, int type) {
        try {
            IoUtils.closeQuietly(Os.socket(domain, type, 0));
        } catch (ErrnoException e) {
            fail(msg + e.getMessage());
        }
    }

    private static void expectSocketPemissionError(String msg, int domain, int type) {
        try {
            IoUtils.closeQuietly(Os.socket(domain, type, 0));
            fail(msg);
        } catch (ErrnoException e) {
            assertEquals(msg, e.errno, EPERM);
        }
    }

    private static void expectHasNetworking() {
        expectSocketSuccess("Creating a UNIX socket should not have thrown ErrnoException",
                AF_UNIX, SOCK_STREAM);
        expectSocketSuccess("Creating a AF_INET socket shouldn't have thrown ErrnoException",
                AF_INET, SOCK_DGRAM);
        expectSocketSuccess("Creating a AF_INET6 socket shouldn't have thrown ErrnoException",
                AF_INET6, SOCK_DGRAM);
    }

    private static void expectNoNetworking() {
        expectSocketSuccess("Creating a UNIX socket should not have thrown ErrnoException",
                AF_UNIX, SOCK_STREAM);
        expectSocketPemissionError(
                "Creating a AF_INET socket should have thrown ErrnoException(EPERM)",
                AF_INET, SOCK_DGRAM);
        expectSocketPemissionError(
                "Creating a AF_INET6 socket should have thrown ErrnoException(EPERM)",
                AF_INET6, SOCK_DGRAM);
    }

    @Test
    public void testSetAllowNetworkingForProcess() {
        expectHasNetworking();
        NetworkUtilsInternal.setAllowNetworkingForProcess(false);
        expectNoNetworking();
        NetworkUtilsInternal.setAllowNetworkingForProcess(true);
        expectHasNetworking();
    }
}
