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

package com.android.blockdevicewriter;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import java.util.ArrayList;

/**
 * Wrapper for block_device_writer command.
 *
 * <p>To use this class, please push block_device_writer binary to /data/local/tmp.
 * 1. In Android.bp, add:
 * <pre>
 *     target_required: ["block_device_writer_module"],
 * </pre>
 * 2. In AndroidText.xml, add:
 * <pre>
 *     <target_preparer class="com.android.tradefed.targetprep.PushFilePreparer">
 *         <option name="push" value="block_device_writer->/data/local/tmp/block_device_writer" />
 *     </target_preparer>
 * </pre>
 */
public final class BlockDeviceWriter {
    private static final String EXECUTABLE = "/data/local/tmp/block_device_writer";

    /**
     * Modifies a byte of the file directly against the backing block storage.
     *
     * The effect can only be observed when the page cache is read from disk again. See
     * {@link #dropCaches} for details.
     */
    public static void damageFileAgainstBlockDevice(ITestDevice device, String path,
            long offsetOfTargetingByte)
            throws DeviceNotAvailableException {
        assertThat(path).startsWith("/data/");
        ITestDevice.MountPointInfo mountPoint = device.getMountPointInfo("/data");
        ArrayList<String> args = new ArrayList<>();
        args.add(EXECUTABLE);
        if ("f2fs".equals(mountPoint.type)) {
            args.add("--use-f2fs-pinning");
        }
        args.add(mountPoint.filesystem);
        args.add(path);
        args.add(Long.toString(offsetOfTargetingByte));
        CommandResult result = device.executeShellV2Command(String.join(" ", args));
        assertWithMessage(
                String.format("stdout=%s\nstderr=%s", result.getStdout(), result.getStderr()))
                .that(result.getStatus()).isEqualTo(CommandStatus.SUCCESS);
    }

    /**
     * Drops file caches so that the result of {@link #damageFileAgainstBlockDevice} can be
     * observed. If a process has an open FD or memory map of the damaged file, cache eviction won't
     * happen and the damage cannot be observed.
     */
    public static void dropCaches(ITestDevice device) throws DeviceNotAvailableException {
        CommandResult result = device.executeShellV2Command(
                "sync && echo 1 > /proc/sys/vm/drop_caches");
        assertThat(result.getStatus()).isEqualTo(CommandStatus.SUCCESS);
    }

    public static void assertFileNotOpen(ITestDevice device, String path)
            throws DeviceNotAvailableException {
        CommandResult result = device.executeShellV2Command("lsof " + path);
        assertThat(result.getStatus()).isEqualTo(CommandStatus.SUCCESS);
        assertThat(result.getStdout()).isEmpty();
    }

    /**
     * Checks if the give offset of a file can be read.
     * This method will return false if the file has fs-verity enabled and is damaged at the offset.
     */
    public static boolean canReadByte(ITestDevice device, String filePath, long offset)
            throws DeviceNotAvailableException {
        CommandResult result = device.executeShellV2Command(
                "dd if=" + filePath + " bs=1 count=1 skip=" + Long.toString(offset));
        return result.getStatus() == CommandStatus.SUCCESS;
    }
}
