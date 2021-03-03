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

package com.android.updatablesystemfont;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.platform.test.annotations.RootPermissionTest;

import com.android.fsverity.AddFsVerityCertRule;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests if fonts can be updated by 'cmd font'.
 */
@RootPermissionTest
@RunWith(DeviceJUnit4ClassRunner.class)
public class UpdatableSystemFontTest extends BaseHostJUnit4Test {

    private static final String CERT_PATH = "/data/local/tmp/UpdatableSystemFontTestCert.der";

    private static final Pattern PATTERN_FONT = Pattern.compile("path = ([^, \n]*)");
    private static final String NOTO_COLOR_EMOJI_TTF = "NotoColorEmoji.ttf";
    private static final String TEST_NOTO_COLOR_EMOJI_V1_TTF =
            "/data/local/tmp/UpdatableSystemFontTestNotoColorEmojiV1.ttf";
    private static final String TEST_NOTO_COLOR_EMOJI_V1_TTF_FSV_SIG =
            "/data/local/tmp/UpdatableSystemFontTestNotoColorEmojiV1.ttf.fsv_sig";
    private static final String TEST_NOTO_COLOR_EMOJI_V2_TTF =
            "/data/local/tmp/UpdatableSystemFontTestNotoColorEmojiV2.ttf";
    private static final String TEST_NOTO_COLOR_EMOJI_V2_TTF_FSV_SIG =
            "/data/local/tmp/UpdatableSystemFontTestNotoColorEmojiV2.ttf.fsv_sig";
    private static final String ORIGINAL_NOTO_COLOR_EMOJI_TTF =
            "/data/local/tmp/NotoColorEmoji.ttf";
    private static final String ORIGINAL_NOTO_COLOR_EMOJI_TTF_FSV_SIG =
            "/data/local/tmp/UpdatableSystemFontTestNotoColorEmoji.ttf.fsv_sig";

    @Rule
    public final AddFsVerityCertRule mAddFsverityCertRule =
            new AddFsVerityCertRule(this, CERT_PATH);

    @Before
    public void setUp() throws Exception {
        expectRemoteCommandToSucceed("cmd font clear");
    }

    @After
    public void tearDown() throws Exception {
        expectRemoteCommandToSucceed("cmd font clear");
    }

    @Test
    public void updateFont() throws Exception {
        expectRemoteCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_V1_TTF, TEST_NOTO_COLOR_EMOJI_V1_TTF_FSV_SIG));
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(fontPath).startsWith("/data/fonts/files/");
    }

    @Test
    public void updateFont_twice() throws Exception {
        expectRemoteCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_V1_TTF, TEST_NOTO_COLOR_EMOJI_V1_TTF_FSV_SIG));
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_TTF);
        expectRemoteCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_V2_TTF, TEST_NOTO_COLOR_EMOJI_V2_TTF_FSV_SIG));
        String fontPath2 = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(fontPath2).startsWith("/data/fonts/files/");
        assertThat(fontPath2).isNotEqualTo(fontPath);
    }

    @Test
    public void updatedFont_dataFileIsImmutableAndReadable() throws Exception {
        expectRemoteCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_V1_TTF, TEST_NOTO_COLOR_EMOJI_V1_TTF_FSV_SIG));
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(fontPath).startsWith("/data");

        expectRemoteCommandToFail("echo -n '' >> " + fontPath);
        expectRemoteCommandToSucceed("cat " + fontPath + " > /dev/null");
    }

    @Test
    public void updateFont_invalidCert() throws Exception {
        expectRemoteCommandToFail(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_V1_TTF, TEST_NOTO_COLOR_EMOJI_V2_TTF_FSV_SIG));
    }

    @Test
    public void updateFont_downgradeFromSystem() throws Exception {
        expectRemoteCommandToFail(String.format("cmd font update %s %s",
                ORIGINAL_NOTO_COLOR_EMOJI_TTF, ORIGINAL_NOTO_COLOR_EMOJI_TTF_FSV_SIG));
    }

    @Test
    public void updateFont_downgradeFromData() throws Exception {
        expectRemoteCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_V2_TTF, TEST_NOTO_COLOR_EMOJI_V2_TTF_FSV_SIG));
        expectRemoteCommandToFail(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_V1_TTF, TEST_NOTO_COLOR_EMOJI_V1_TTF_FSV_SIG));
    }

    @Test
    public void reboot() throws Exception {
        expectRemoteCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_V1_TTF, TEST_NOTO_COLOR_EMOJI_V1_TTF_FSV_SIG));
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(fontPath).startsWith("/data/fonts/files/");

        expectRemoteCommandToSucceed("stop");
        expectRemoteCommandToSucceed("start");
        waitUntilFontCommandIsReady();
        String fontPathAfterReboot = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(fontPathAfterReboot).isEqualTo(fontPath);
    }

    private String getFontPath(String fontFileName) throws Exception {
        // TODO: add a dedicated command for testing.
        String lines = expectRemoteCommandToSucceed("cmd font dump");
        for (String line : lines.split("\n")) {
            Matcher m = PATTERN_FONT.matcher(line);
            if (m.find() && m.group(1).endsWith(fontFileName)) {
                return m.group(1);
            }
        }
        CLog.e("Font not found: " + fontFileName);
        return null;
    }

    private String expectRemoteCommandToSucceed(String cmd) throws Exception {
        CommandResult result = getDevice().executeShellV2Command(cmd);
        assertWithMessage("`" + cmd + "` failed: " + result.getStderr())
                .that(result.getStatus())
                .isEqualTo(CommandStatus.SUCCESS);
        return result.getStdout();
    }

    private void expectRemoteCommandToFail(String cmd) throws Exception {
        CommandResult result = getDevice().executeShellV2Command(cmd);
        assertWithMessage("Unexpected success from `" + cmd + "`: " + result.getStderr())
                .that(result.getStatus())
                .isNotEqualTo(CommandStatus.SUCCESS);
    }

    private void waitUntilFontCommandIsReady() {
        waitUntil(TimeUnit.SECONDS.toMillis(30), () -> {
            try {
                return getDevice().executeShellV2Command("cmd font status").getStatus()
                        == CommandStatus.SUCCESS;
            } catch (DeviceNotAvailableException e) {
                return false;
            }
        });
    }

    private void waitUntilSystemServerIsGone() {
        waitUntil(TimeUnit.SECONDS.toMillis(30), () -> {
            try {
                return getDevice().executeShellV2Command("pid system_server").getStatus()
                        == CommandStatus.FAILED;
            } catch (DeviceNotAvailableException e) {
                return false;
            }
        });
    }

    private void waitUntil(long timeoutMillis, Supplier<Boolean> func) {
        long untilMillis = System.currentTimeMillis() + timeoutMillis;
        do {
            if (func.get()) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new AssertionError("Interrupted", e);
            }
        } while (System.currentTimeMillis() < untilMillis);
        throw new AssertionError("Timed out");
    }
}
