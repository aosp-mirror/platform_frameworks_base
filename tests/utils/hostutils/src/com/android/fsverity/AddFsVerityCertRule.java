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

package com.android.fsverity;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assume.assumeTrue;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.rules.ExternalResource;

public final class AddFsVerityCertRule extends ExternalResource {

    private static final String APK_VERITY_STANDARD_MODE = "2";

    private final BaseHostJUnit4Test mHost;
    private final String mCertPath;
    private String mKeyId;

    public AddFsVerityCertRule(BaseHostJUnit4Test host, String certPath) {
        mHost = host;
        mCertPath = certPath;
    }

    @Override
    protected void before() throws Throwable {
        ITestDevice device = mHost.getDevice();
        String apkVerityMode = device.getProperty("ro.apk_verity.mode");
        assumeTrue(device.getLaunchApiLevel() >= 30
                || APK_VERITY_STANDARD_MODE.equals(apkVerityMode));

        String keyId = executeCommand(
                "mini-keyctl padd asymmetric fsv_test .fs-verity < " + mCertPath).trim();
        assertThat(keyId).matches("^\\d+$");
        mKeyId = keyId;
    }

    @Override
    protected void after() {
        if (mKeyId == null) return;
        try {
            executeCommand("mini-keyctl unlink " + mKeyId + " .fs-verity");
        } catch (DeviceNotAvailableException e) {
            LogUtil.CLog.e(e);
        }
        mKeyId = null;
    }

    private String executeCommand(String cmd) throws DeviceNotAvailableException {
        CommandResult result = mHost.getDevice().executeShellV2Command(cmd);
        assertWithMessage("`" + cmd + "` failed: " + result.getStderr())
                .that(result.getStatus())
                .isEqualTo(CommandStatus.SUCCESS);
        return result.getStdout();
    }
}
