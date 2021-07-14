/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server;

import android.test.AndroidTestCase;

/**
 * Tests for {@link com.android.server.BootReceiver}
 */
public class BootReceiverTest extends AndroidTestCase {
    public void testLogLinePotentiallySensitive() throws Exception {
        /*
         * Strings to be dropped from the log as potentially sensitive: register dumps, process
         * names, hardware info.
         */
        final String[] becomeNull = {
            "CPU: 4 PID: 120 Comm: kunit_try_catch Tainted: G        W         5.8.0-rc6+ #7",
            "Hardware name: QEMU Standard PC (i440FX + PIIX, 1996), BIOS 1.13.0-1 04/01/2014",
            "[    0.083207] RSP: 0000:ffffffff8fe07ca8 EFLAGS: 00010046 ORIG_RAX: 0000000000000000",
            "[    0.084709] RAX: 0000000000000000 RBX: ffffffffff240000 RCX: ffffffff815fcf01",
            "[    0.086109] RDX: dffffc0000000000 RSI: 0000000000000001 RDI: ffffffffff240004",
            "[    0.087509] RBP: ffffffff8fe07d60 R08: fffffbfff1fc0f21 R09: fffffbfff1fc0f21",
            "[    0.088911] R10: ffffffff8fe07907 R11: fffffbfff1fc0f20 R12: ffffffff8fe07d38",
            "R13: 0000000000000001 R14: 0000000000000001 R15: ffffffff8fe07e80",
            "x29: ffff00003ce07150 x28: ffff80001aa29cc0",
            "x1 : 0000000000000000 x0 : ffff00000f628000",
        };

        /* Strings to be left unchanged, including non-sensitive registers and parts of reports. */
        final String[] leftAsIs = {
            "FS:  0000000000000000(0000) GS:ffffffff92409000(0000) knlGS:0000000000000000",
            "[ 69.2366] [ T6006]c7   6006  =======================================================",
            "[ 69.245688] [ T6006] BUG: KFENCE: out-of-bounds in kfence_handle_page_fault",
            "[ 69.257816] [ T6006]c7   6006  Out-of-bounds access at 0xffffffca75c45000 ",
            "[ 69.273536] [ T6006]c7   6006   __do_kernel_fault+0xa8/0x11c",
            "pc : __mutex_lock+0x428/0x99c ",
            "sp : ffff00003ce07150",
            "Call trace:",
            "",
        };

        final String[][] stripped = {
            { "Detected corrupted memory at 0xffffffffb6797ff9 [ 0xac . . . . . . ]:",
              "Detected corrupted memory at 0xffffffffb6797ff9" },
        };
        for (int i = 0; i < becomeNull.length; i++) {
            assertEquals(BootReceiver.stripSensitiveData(becomeNull[i]), null);
        }

        for (int i = 0; i < leftAsIs.length; i++) {
            assertEquals(BootReceiver.stripSensitiveData(leftAsIs[i]), leftAsIs[i]);
        }

        for (int i = 0; i < stripped.length; i++) {
            assertEquals(BootReceiver.stripSensitiveData(stripped[i][0]), stripped[i][1]);
        }
    }
}
