/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.test.AndroidTestCase;

import com.android.server.os.TombstoneProtos;
import com.android.server.os.TombstoneProtos.Tombstone;

public class BootReceiverTest extends AndroidTestCase {
    private static final String TAG = "BootReceiverTest";

    public void testRemoveMemoryFromTombstone() {
        Tombstone tombstoneBase = Tombstone.newBuilder()
                .setBuildFingerprint("build_fingerprint")
                .setRevision("revision")
                .setPid(123)
                .setTid(23)
                .setUid(34)
                .setSelinuxLabel("selinux_label")
                .addCommandLine("cmd1")
                .addCommandLine("cmd2")
                .addCommandLine("cmd3")
                .setProcessUptime(300)
                .setAbortMessage("abort")
                .addCauses(TombstoneProtos.Cause.newBuilder()
                        .setHumanReadable("cause1")
                        .setMemoryError(TombstoneProtos.MemoryError.newBuilder()
                                .setTool(TombstoneProtos.MemoryError.Tool.SCUDO)
                                .setType(TombstoneProtos.MemoryError.Type.DOUBLE_FREE)))
                .addLogBuffers(TombstoneProtos.LogBuffer.newBuilder().setName("name").addLogs(
                        TombstoneProtos.LogMessage.newBuilder()
                                .setTimestamp("123")
                                .setMessage("message")))
                .addOpenFds(TombstoneProtos.FD.newBuilder().setFd(1).setPath("path"))
                .build();

        Tombstone tombstoneWithoutMemory = tombstoneBase.toBuilder()
                .putThreads(1, TombstoneProtos.Thread.newBuilder()
                        .setId(1)
                        .setName("thread1")
                        .addRegisters(TombstoneProtos.Register.newBuilder().setName("r1").setU64(1))
                        .addRegisters(TombstoneProtos.Register.newBuilder().setName("r2").setU64(2))
                        .addBacktraceNote("backtracenote1")
                        .addUnreadableElfFiles("files1")
                        .setTaggedAddrCtrl(1)
                        .setPacEnabledKeys(10)
                        .build())
                .build();

        Tombstone tombstoneWithMemory = tombstoneBase.toBuilder()
                .addMemoryMappings(TombstoneProtos.MemoryMapping.newBuilder()
                        .setBeginAddress(1)
                        .setEndAddress(100)
                        .setOffset(10)
                        .setRead(true)
                        .setWrite(true)
                        .setExecute(false)
                        .setMappingName("mapping")
                        .setBuildId("build")
                        .setLoadBias(70))
                .putThreads(1, TombstoneProtos.Thread.newBuilder()
                        .setId(1)
                        .setName("thread1")
                        .addRegisters(TombstoneProtos.Register.newBuilder().setName("r1").setU64(1))
                        .addRegisters(TombstoneProtos.Register.newBuilder().setName("r2").setU64(2))
                        .addBacktraceNote("backtracenote1")
                        .addUnreadableElfFiles("files1")
                        .addMemoryDump(TombstoneProtos.MemoryDump.newBuilder()
                                .setRegisterName("register1")
                                .setMappingName("mapping")
                                .setBeginAddress(10))
                        .setTaggedAddrCtrl(1)
                        .setPacEnabledKeys(10)
                        .build())
                .build();

        assertThat(BootReceiver.removeMemoryFromTombstone(tombstoneWithMemory))
                .isEqualTo(tombstoneWithoutMemory);
    }
}
