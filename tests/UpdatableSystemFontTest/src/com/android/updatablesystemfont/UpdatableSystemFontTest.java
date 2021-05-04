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

import static org.junit.Assume.assumeTrue;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.app.UiAutomation;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.RootPermissionTest;
import android.security.FileIntegrityManager;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.StreamUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests if fonts can be updated by 'cmd font'.
 */
@RootPermissionTest
@RunWith(AndroidJUnit4.class)
public class UpdatableSystemFontTest {

    private static final String TAG = "UpdatableSystemFontTest";
    private static final String SYSTEM_FONTS_DIR = "/system/fonts/";
    private static final String DATA_FONTS_DIR = "/data/fonts/files/";

    private static final String CERT_PATH = "/data/local/tmp/UpdatableSystemFontTestCert.der";

    private static final Pattern PATTERN_FONT = Pattern.compile("path = ([^, \n]*)");
    private static final String NOTO_COLOR_EMOJI_TTF = "NotoColorEmoji.ttf";

    private static final String ORIGINAL_NOTO_COLOR_EMOJI_TTF =
            "/data/local/tmp/NotoColorEmoji.ttf";
    private static final String ORIGINAL_NOTO_COLOR_EMOJI_TTF_FSV_SIG =
            "/data/local/tmp/UpdatableSystemFontTestNotoColorEmoji.ttf.fsv_sig";
    // A font with revision == 0.
    private static final String TEST_NOTO_COLOR_EMOJI_V0_TTF =
            "/data/local/tmp/UpdatableSystemFontTestNotoColorEmojiV0.ttf";
    private static final String TEST_NOTO_COLOR_EMOJI_V0_TTF_FSV_SIG =
            "/data/local/tmp/UpdatableSystemFontTestNotoColorEmojiV0.ttf.fsv_sig";
    // A font with revision == original + 1
    private static final String TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF =
            "/data/local/tmp/UpdatableSystemFontTestNotoColorEmojiVPlus1.ttf";
    private static final String TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG =
            "/data/local/tmp/UpdatableSystemFontTestNotoColorEmojiVPlus1.ttf.fsv_sig";
    // A font with revision == original + 2
    private static final String TEST_NOTO_COLOR_EMOJI_VPLUS2_TTF =
            "/data/local/tmp/UpdatableSystemFontTestNotoColorEmojiVPlus2.ttf";
    private static final String TEST_NOTO_COLOR_EMOJI_VPLUS2_TTF_FSV_SIG =
            "/data/local/tmp/UpdatableSystemFontTestNotoColorEmojiVPlus2.ttf.fsv_sig";

    private static final String EMOJI_RENDERING_TEST_APP_ID = "com.android.emojirenderingtestapp";
    private static final String EMOJI_RENDERING_TEST_ACTIVITY =
            EMOJI_RENDERING_TEST_APP_ID + "/.EmojiRenderingTestActivity";
    private static final long ACTIVITY_TIMEOUT_MILLIS = SECONDS.toMillis(10);

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private String mKeyId;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Run tests only if updatable system font is enabled.
        FileIntegrityManager fim = context.getSystemService(FileIntegrityManager.class);
        assumeTrue(fim != null);
        assumeTrue(fim.isApkVeritySupported());
        mKeyId = insertCert(CERT_PATH);
        expectCommandToSucceed("cmd font clear");
    }

    @After
    public void tearDown() throws Exception {
        expectCommandToSucceed("cmd font clear");
        if (mKeyId != null) {
            expectCommandToSucceed("mini-keyctl unlink " + mKeyId + " .fs-verity");
        }
    }

    @Test
    public void updateFont() throws Exception {
        expectCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG));
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(fontPath).startsWith(DATA_FONTS_DIR);
        // The updated font should be readable and unmodifiable.
        expectCommandToSucceed("dd status=none if=" + fontPath + " of=/dev/null");
        expectCommandToFail("dd status=none if=" + CERT_PATH + " of=" + fontPath);
    }

    @Test
    public void updateFont_twice() throws Exception {
        expectCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG));
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_TTF);
        expectCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_VPLUS2_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS2_TTF_FSV_SIG));
        String fontPath2 = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(fontPath2).startsWith(DATA_FONTS_DIR);
        assertThat(fontPath2).isNotEqualTo(fontPath);
        // The new file should be readable.
        expectCommandToSucceed("dd status=none if=" + fontPath2 + " of=/dev/null");
        // The old file should be still readable.
        expectCommandToSucceed("dd status=none if=" + fontPath + " of=/dev/null");
    }

    @Test
    public void updateFont_allowSameVersion() throws Exception {
        // Update original font to the same version
        expectCommandToSucceed(String.format("cmd font update %s %s",
                ORIGINAL_NOTO_COLOR_EMOJI_TTF, ORIGINAL_NOTO_COLOR_EMOJI_TTF_FSV_SIG));
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_TTF);
        expectCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG));
        String fontPath2 = getFontPath(NOTO_COLOR_EMOJI_TTF);
        // Update updated font to the same version
        expectCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG));
        String fontPath3 = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(fontPath).startsWith(DATA_FONTS_DIR);
        assertThat(fontPath2).isNotEqualTo(fontPath);
        assertThat(fontPath2).startsWith(DATA_FONTS_DIR);
        assertThat(fontPath3).startsWith(DATA_FONTS_DIR);
        assertThat(fontPath3).isNotEqualTo(fontPath);
    }

    @Test
    public void updateFont_invalidCert() throws Exception {
        expectCommandToFail(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS2_TTF_FSV_SIG));
    }

    @Test
    public void updateFont_downgradeFromSystem() throws Exception {
        expectCommandToFail(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_V0_TTF, TEST_NOTO_COLOR_EMOJI_V0_TTF_FSV_SIG));
    }

    @Test
    public void updateFont_downgradeFromData() throws Exception {
        expectCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_VPLUS2_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS2_TTF_FSV_SIG));
        expectCommandToFail(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG));
    }

    @Test
    public void launchApp() throws Exception {
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(fontPath).startsWith(SYSTEM_FONTS_DIR);
        startActivity(EMOJI_RENDERING_TEST_APP_ID, EMOJI_RENDERING_TEST_ACTIVITY);
        waitUntil(ACTIVITY_TIMEOUT_MILLIS, () ->
                isFileOpenedBy(fontPath, EMOJI_RENDERING_TEST_APP_ID));
    }

    @Test
    public void launchApp_afterUpdateFont() throws Exception {
        String originalFontPath = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(originalFontPath).startsWith(SYSTEM_FONTS_DIR);
        expectCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG));
        String updatedFontPath = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(updatedFontPath).startsWith(DATA_FONTS_DIR);
        startActivity(EMOJI_RENDERING_TEST_APP_ID, EMOJI_RENDERING_TEST_ACTIVITY);
        // The original font should NOT be opened by the app.
        waitUntil(ACTIVITY_TIMEOUT_MILLIS, () ->
                isFileOpenedBy(updatedFontPath, EMOJI_RENDERING_TEST_APP_ID)
                        && !isFileOpenedBy(originalFontPath, EMOJI_RENDERING_TEST_APP_ID));
    }

    @Test
    public void reboot() throws Exception {
        expectCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG));
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(fontPath).startsWith(DATA_FONTS_DIR);

        // Emulate reboot by 'cmd font restart'.
        expectCommandToSucceed("cmd font restart");
        String fontPathAfterReboot = getFontPath(NOTO_COLOR_EMOJI_TTF);
        assertThat(fontPathAfterReboot).isEqualTo(fontPath);
    }

    private static String insertCert(String certPath) throws Exception {
        Pair<String, String> result;
        try (InputStream is = new FileInputStream(certPath)) {
            result = runShellCommand("mini-keyctl padd asymmetric fsv_test .fs-verity", is);
        }
        // Assert that there are no errors.
        assertThat(result.second).isEmpty();
        String keyId = result.first.trim();
        assertThat(keyId).matches("^\\d+$");
        return keyId;
    }

    private static String getFontPath(String fontFileName) throws Exception {
        // TODO: add a dedicated command for testing.
        String lines = expectCommandToSucceed("cmd font dump");
        for (String line : lines.split("\n")) {
            Matcher m = PATTERN_FONT.matcher(line);
            if (m.find() && m.group(1).endsWith(fontFileName)) {
                return m.group(1);
            }
        }
        throw new AssertionError("Font not found: " + fontFileName);
    }

    private static void startActivity(String appId, String activityId) throws Exception {
        // Make sure that the app is installed and enabled.
        waitUntil(ACTIVITY_TIMEOUT_MILLIS, () -> {
            String packageInfo = expectCommandToSucceed("pm list packages -e " + appId);
            return !packageInfo.isEmpty();
        });
        expectCommandToSucceed("am force-stop " + appId);
        expectCommandToSucceed("am start-activity -n " + activityId);
    }

    private static String expectCommandToSucceed(String cmd) throws IOException {
        Pair<String, String> result = runShellCommand(cmd, null);
        // UiAutomation.runShellCommand() does not return exit code.
        // Assume that the command fails if stderr is not empty.
        assertThat(result.second.trim()).isEmpty();
        return result.first;
    }

    private static void expectCommandToFail(String cmd) throws IOException {
        Pair<String, String> result = runShellCommand(cmd, null);
        // UiAutomation.runShellCommand() does not return exit code.
        // Assume that the command fails if stderr is not empty.
        assertThat(result.second.trim()).isNotEmpty();
    }

    /** Runs a command and returns (stdout, stderr). */
    private static Pair<String, String> runShellCommand(String cmd, @Nullable InputStream input)
            throws IOException  {
        Log.i(TAG, "runShellCommand: " + cmd);
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        ParcelFileDescriptor[] rwe = automation.executeShellCommandRwe(cmd);
        // executeShellCommandRwe returns [stdout, stdin, stderr].
        try (ParcelFileDescriptor outFd = rwe[0];
             ParcelFileDescriptor inFd = rwe[1];
             ParcelFileDescriptor errFd = rwe[2]) {
            if (input != null) {
                try (OutputStream os = new FileOutputStream(inFd.getFileDescriptor())) {
                    StreamUtil.copyStreams(input, os);
                }
            }
            // We have to close stdin before reading stdout and stderr.
            // It's safe to close ParcelFileDescriptor multiple times.
            inFd.close();
            String stdout;
            try (InputStream is = new FileInputStream(outFd.getFileDescriptor())) {
                stdout = StreamUtil.readInputStream(is);
            }
            Log.i(TAG, "stdout =  " + stdout);
            String stderr;
            try (InputStream is = new FileInputStream(errFd.getFileDescriptor())) {
                stderr = StreamUtil.readInputStream(is);
            }
            Log.i(TAG, "stderr =  " + stderr);
            return new Pair<>(stdout, stderr);
        }
    }

    private static void waitUntil(long timeoutMillis, ThrowingSupplier<Boolean> func) {
        long untilMillis = System.currentTimeMillis() + timeoutMillis;
        do {
            try {
                if (func.get()) return;
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new AssertionError("Interrupted", e);
            } catch (Exception e) {
                throw new AssertionError("Unexpected exception", e);
            }
        } while (System.currentTimeMillis() < untilMillis);
        throw new AssertionError("Timed out");
    }

    private static boolean isFileOpenedBy(String path, String appId) throws Exception {
        String pid = pidOf(appId);
        if (pid.isEmpty()) {
            return false;
        }
        String cmd = String.format("lsof -t -p %s %s", pid, path);
        return !expectCommandToSucceed(cmd).trim().isEmpty();
    }

    private static String pidOf(String appId) throws Exception {
        return expectCommandToSucceed("pidof " + appId).trim();
    }
}
