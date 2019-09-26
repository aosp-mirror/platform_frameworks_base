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
package com.android.apkverity;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;

import junit.framework.TestCase;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for invoking the install-multiple command via ADB. Subclass this for less typing:
 *
 * <code> private class InstallMultiple extends BaseInstallMultiple&lt;InstallMultiple&gt; { public
 * InstallMultiple() { super(getDevice(), null); } } </code>
 */
/*package*/ class BaseInstallMultiple<T extends BaseInstallMultiple<?>> {

    private final ITestDevice mDevice;
    private final IBuildInfo mBuild;

    private final List<String> mArgs = new ArrayList<>();
    private final Map<File, String> mFileToRemoteMap = new HashMap<>();

    /*package*/ BaseInstallMultiple(ITestDevice device, IBuildInfo buildInfo) {
        mDevice = device;
        mBuild = buildInfo;
        addArg("-g");
    }

    T addArg(String arg) {
        mArgs.add(arg);
        return (T) this;
    }

    T addFile(String filename) throws FileNotFoundException {
        return addFile(filename, filename);
    }

    T addFile(String filename, String remoteName) throws FileNotFoundException {
        CompatibilityBuildHelper buildHelper = new CompatibilityBuildHelper(mBuild);
        mFileToRemoteMap.put(buildHelper.getTestFile(filename), remoteName);
        return (T) this;
    }

    T inheritFrom(String packageName) {
        addArg("-r");
        addArg("-p " + packageName);
        return (T) this;
    }

    void run() throws DeviceNotAvailableException {
        run(true);
    }

    void runExpectingFailure() throws DeviceNotAvailableException {
        run(false);
    }

    private void run(boolean expectingSuccess) throws DeviceNotAvailableException {
        final ITestDevice device = mDevice;

        // Create an install session
        final StringBuilder cmd = new StringBuilder();
        cmd.append("pm install-create");
        for (String arg : mArgs) {
            cmd.append(' ').append(arg);
        }

        String result = device.executeShellCommand(cmd.toString());
        TestCase.assertTrue(result, result.startsWith("Success"));

        final int start = result.lastIndexOf("[");
        final int end = result.lastIndexOf("]");
        int sessionId = -1;
        try {
            if (start != -1 && end != -1 && start < end) {
                sessionId = Integer.parseInt(result.substring(start + 1, end));
            }
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Failed to parse install session: " + result);
        }
        if (sessionId == -1) {
            throw new IllegalStateException("Failed to create install session: " + result);
        }

        // Push our files into session. Ideally we'd use stdin streaming,
        // but ddmlib doesn't support it yet.
        for (final Map.Entry<File, String> entry : mFileToRemoteMap.entrySet()) {
            final File file = entry.getKey();
            final String remoteName  = entry.getValue();
            final String remotePath = "/data/local/tmp/" + file.getName();
            if (!device.pushFile(file, remotePath)) {
                throw new IllegalStateException("Failed to push " + file);
            }

            cmd.setLength(0);
            cmd.append("pm install-write");
            cmd.append(' ').append(sessionId);
            cmd.append(' ').append(remoteName);
            cmd.append(' ').append(remotePath);

            result = device.executeShellCommand(cmd.toString());
            TestCase.assertTrue(result, result.startsWith("Success"));
        }

        // Everything staged; let's pull trigger
        cmd.setLength(0);
        cmd.append("pm install-commit");
        cmd.append(' ').append(sessionId);

        result = device.executeShellCommand(cmd.toString());
        if (expectingSuccess) {
            TestCase.assertTrue(result, result.contains("Success"));
        } else {
            TestCase.assertFalse(result, result.contains("Success"));
        }
    }
}
