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

import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.app.UiAutomation;
import android.content.Context;
import android.graphics.fonts.FontFamilyUpdateRequest;
import android.graphics.fonts.FontFileUpdateRequest;
import android.graphics.fonts.FontManager;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.RootPermissionTest;
import android.security.FileIntegrityManager;
import android.text.FontConfig;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import com.android.compatibility.common.util.StreamUtil;
import com.android.compatibility.common.util.SystemUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tests if fonts can be updated by {@link FontManager} API.
 */
@RootPermissionTest
@RunWith(AndroidJUnit4.class)
public class UpdatableSystemFontTest {

    private static final String TAG = "UpdatableSystemFontTest";
    private static final String SYSTEM_FONTS_DIR = "/system/fonts/";
    private static final String DATA_FONTS_DIR = "/data/fonts/files/";
    private static final String CERT_PATH = "/data/local/tmp/UpdatableSystemFontTestCert.der";
    private static final String NOTO_COLOR_EMOJI_POSTSCRIPT_NAME = "NotoColorEmoji";

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

    private static final String GET_AVAILABLE_FONTS_TEST_ACTIVITY =
            EMOJI_RENDERING_TEST_APP_ID + "/.GetAvailableFontsTestActivity";

    private static final Pattern PATTERN_FONT_FILES = Pattern.compile("\\.(ttf|otf|ttc|otc)$");
    private static final Pattern PATTERN_TMP_FILES = Pattern.compile("^/data/local/tmp/");
    private static final Pattern PATTERN_DATA_FONT_FILES = Pattern.compile("^/data/fonts/files/");
    private static final Pattern PATTERN_SYSTEM_FONT_FILES =
            Pattern.compile("^/(system|product)/fonts/");

    private String mKeyId;
    private FontManager mFontManager;
    private UiDevice mUiDevice;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Run tests only if updatable system font is enabled.
        FileIntegrityManager fim = context.getSystemService(FileIntegrityManager.class);
        assumeTrue(fim != null);
        assumeTrue(fim.isApkVeritySupported());
        mKeyId = insertCert(CERT_PATH);
        mFontManager = context.getSystemService(FontManager.class);
        expectCommandToSucceed("cmd font clear");
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    @After
    public void tearDown() throws Exception {
        // Ignore errors because this may fail if updatable system font is not enabled.
        runShellCommand("cmd font clear", null);
        if (mKeyId != null) {
            expectCommandToSucceed("mini-keyctl unlink " + mKeyId + " .fs-verity");
        }
    }

    @Test
    public void updateFont() throws Exception {
        assertThat(updateFontFile(
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG))
                .isEqualTo(FontManager.RESULT_SUCCESS);
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_POSTSCRIPT_NAME);
        assertThat(fontPath).startsWith(DATA_FONTS_DIR);
        // The updated font should be readable and unmodifiable.
        expectCommandToSucceed("dd status=none if=" + fontPath + " of=/dev/null");
        expectCommandToFail("dd status=none if=" + CERT_PATH + " of=" + fontPath);
    }

    @Test
    public void updateFont_twice() throws Exception {
        assertThat(updateFontFile(
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG))
                .isEqualTo(FontManager.RESULT_SUCCESS);
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_POSTSCRIPT_NAME);
        assertThat(updateFontFile(
                TEST_NOTO_COLOR_EMOJI_VPLUS2_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS2_TTF_FSV_SIG))
                .isEqualTo(FontManager.RESULT_SUCCESS);
        String fontPath2 = getFontPath(NOTO_COLOR_EMOJI_POSTSCRIPT_NAME);
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
        assertThat(updateFontFile(
                ORIGINAL_NOTO_COLOR_EMOJI_TTF, ORIGINAL_NOTO_COLOR_EMOJI_TTF_FSV_SIG))
                .isEqualTo(FontManager.RESULT_SUCCESS);
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_POSTSCRIPT_NAME);
        assertThat(updateFontFile(
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG))
                .isEqualTo(FontManager.RESULT_SUCCESS);
        String fontPath2 = getFontPath(NOTO_COLOR_EMOJI_POSTSCRIPT_NAME);
        // Update updated font to the same version
        assertThat(updateFontFile(
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG))
                .isEqualTo(FontManager.RESULT_SUCCESS);
        String fontPath3 = getFontPath(NOTO_COLOR_EMOJI_POSTSCRIPT_NAME);
        assertThat(fontPath).startsWith(DATA_FONTS_DIR);
        assertThat(fontPath2).isNotEqualTo(fontPath);
        assertThat(fontPath2).startsWith(DATA_FONTS_DIR);
        assertThat(fontPath3).startsWith(DATA_FONTS_DIR);
        assertThat(fontPath3).isNotEqualTo(fontPath);
    }

    @Test
    public void updateFont_invalidCert() throws Exception {
        assertThat(updateFontFile(
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS2_TTF_FSV_SIG))
                .isEqualTo(FontManager.RESULT_ERROR_VERIFICATION_FAILURE);
    }

    @Test
    public void updateFont_downgradeFromSystem() throws Exception {
        assertThat(updateFontFile(
                TEST_NOTO_COLOR_EMOJI_V0_TTF, TEST_NOTO_COLOR_EMOJI_V0_TTF_FSV_SIG))
                .isEqualTo(FontManager.RESULT_ERROR_DOWNGRADING);
    }

    @Test
    public void updateFont_downgradeFromData() throws Exception {
        assertThat(updateFontFile(
                TEST_NOTO_COLOR_EMOJI_VPLUS2_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS2_TTF_FSV_SIG))
                .isEqualTo(FontManager.RESULT_SUCCESS);
        assertThat(updateFontFile(
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG))
                .isEqualTo(FontManager.RESULT_ERROR_DOWNGRADING);
    }

    @Test
    public void launchApp() throws Exception {
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_POSTSCRIPT_NAME);
        assertThat(fontPath).startsWith(SYSTEM_FONTS_DIR);
        startActivity(EMOJI_RENDERING_TEST_APP_ID, EMOJI_RENDERING_TEST_ACTIVITY);
        SystemUtil.eventually(
                () -> assertThat(isFileOpenedBy(fontPath, EMOJI_RENDERING_TEST_APP_ID)).isTrue(),
                ACTIVITY_TIMEOUT_MILLIS);
    }

    @Test
    public void launchApp_afterUpdateFont() throws Exception {
        String originalFontPath = getFontPath(NOTO_COLOR_EMOJI_POSTSCRIPT_NAME);
        assertThat(originalFontPath).startsWith(SYSTEM_FONTS_DIR);
        assertThat(updateFontFile(
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG))
                .isEqualTo(FontManager.RESULT_SUCCESS);
        String updatedFontPath = getFontPath(NOTO_COLOR_EMOJI_POSTSCRIPT_NAME);
        assertThat(updatedFontPath).startsWith(DATA_FONTS_DIR);
        startActivity(EMOJI_RENDERING_TEST_APP_ID, EMOJI_RENDERING_TEST_ACTIVITY);
        // The original font should NOT be opened by the app.
        SystemUtil.eventually(() -> {
            assertThat(isFileOpenedBy(updatedFontPath, EMOJI_RENDERING_TEST_APP_ID)).isTrue();
            assertThat(isFileOpenedBy(originalFontPath, EMOJI_RENDERING_TEST_APP_ID)).isFalse();
        }, ACTIVITY_TIMEOUT_MILLIS);
    }

    @Test
    public void reboot() throws Exception {
        expectCommandToSucceed(String.format("cmd font update %s %s",
                TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG));
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_POSTSCRIPT_NAME);
        assertThat(fontPath).startsWith(DATA_FONTS_DIR);

        // Emulate reboot by 'cmd font restart'.
        expectCommandToSucceed("cmd font restart");
        String fontPathAfterReboot = getFontPath(NOTO_COLOR_EMOJI_POSTSCRIPT_NAME);
        assertThat(fontPathAfterReboot).isEqualTo(fontPath);
    }

    @Test
    public void fdLeakTest() throws Exception {
        long originalOpenFontCount =
                countMatch(getOpenFiles("system_server"), PATTERN_FONT_FILES);
        Pattern patternEmojiVPlus1 =
                Pattern.compile(Pattern.quote(TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF));
        for (int i = 0; i < 10; i++) {
            assertThat(updateFontFile(
                    TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF, TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG))
                    .isEqualTo(FontManager.RESULT_SUCCESS);
            List<String> openFiles = getOpenFiles("system_server");
            for (Pattern p : Arrays.asList(PATTERN_FONT_FILES, PATTERN_SYSTEM_FONT_FILES,
                    PATTERN_DATA_FONT_FILES, PATTERN_TMP_FILES)) {
                Log.i(TAG, String.format("num of %s: %d", p, countMatch(openFiles, p)));
            }
            // system_server should not keep /data/fonts files open.
            assertThat(countMatch(openFiles, PATTERN_DATA_FONT_FILES)).isEqualTo(0);
            // system_server should not keep passed FD open.
            assertThat(countMatch(openFiles, patternEmojiVPlus1)).isEqualTo(0);
            // The number of open font FD should not increase.
            assertThat(countMatch(openFiles, PATTERN_FONT_FILES))
                    .isAtMost(originalOpenFontCount);
        }
    }

    @Test
    public void fdLeakTest_withoutPermission() throws Exception {
        Pattern patternEmojiVPlus1 =
                Pattern.compile(Pattern.quote(TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF));
        byte[] signature = Files.readAllBytes(Paths.get(TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF_FSV_SIG));
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(
                new File(TEST_NOTO_COLOR_EMOJI_VPLUS1_TTF), MODE_READ_ONLY)) {
            assertThrows(SecurityException.class,
                    () -> updateFontFileWithoutPermission(fd, signature, 0));
        }
        List<String> openFiles = getOpenFiles("system_server");
        assertThat(countMatch(openFiles, patternEmojiVPlus1)).isEqualTo(0);
    }

    @Test
    public void getAvailableFonts() throws Exception {
        String fontPath = getFontPath(NOTO_COLOR_EMOJI_POSTSCRIPT_NAME);
        startActivity(EMOJI_RENDERING_TEST_APP_ID, GET_AVAILABLE_FONTS_TEST_ACTIVITY);
        // GET_AVAILABLE_FONTS_TEST_ACTIVITY shows the NotoColorEmoji path it got.
        mUiDevice.wait(
                Until.findObject(By.pkg(EMOJI_RENDERING_TEST_APP_ID).text(fontPath)),
                ACTIVITY_TIMEOUT_MILLIS);
        // The font file should not be opened just by querying the path using
        // SystemFont.getAvailableFonts().
        assertThat(isFileOpenedBy(fontPath, EMOJI_RENDERING_TEST_APP_ID)).isFalse();
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

    private int updateFontFile(String fontPath, String signaturePath) throws IOException {
        byte[] signature = Files.readAllBytes(Paths.get(signaturePath));
        try (ParcelFileDescriptor fd =
                ParcelFileDescriptor.open(new File(fontPath), MODE_READ_ONLY)) {
            return SystemUtil.runWithShellPermissionIdentity(() -> {
                int configVersion = mFontManager.getFontConfig().getConfigVersion();
                return updateFontFileWithoutPermission(fd, signature, configVersion);
            });
        }
    }

    private int updateFontFileWithoutPermission(ParcelFileDescriptor fd, byte[] signature,
            int configVersion) {
        return mFontManager.updateFontFamily(
                new FontFamilyUpdateRequest.Builder()
                        .addFontFileUpdateRequest(new FontFileUpdateRequest(fd, signature))
                        .build(),
                configVersion);
    }

    private String getFontPath(String psName) {
        return SystemUtil.runWithShellPermissionIdentity(() -> {
            FontConfig fontConfig = mFontManager.getFontConfig();
            for (FontConfig.FontFamily family : fontConfig.getFontFamilies()) {
                for (FontConfig.Font font : family.getFontList()) {
                    if (psName.equals(font.getPostScriptName())) {
                        return font.getFile().getAbsolutePath();
                    }
                }
            }
            throw new AssertionError("Font not found: " + psName);
        });
    }

    private static void startActivity(String appId, String activityId) throws Exception {
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

    private static boolean isFileOpenedBy(String path, String appId) throws Exception {
        String pid = pidOf(appId);
        if (pid.isEmpty()) {
            return false;
        }
        String cmd = String.format("lsof -t -p %s %s", pid, path);
        return !expectCommandToSucceed(cmd).trim().isEmpty();
    }

    private static List<String> getOpenFiles(String appId) throws Exception {
        String pid = pidOf(appId);
        if (pid.isEmpty()) {
            return Collections.emptyList();
        }
        String cmd = String.format("lsof -p %s", pid);
        String out = expectCommandToSucceed(cmd);
        List<String> paths = new ArrayList<>();
        boolean first = true;
        for (String line : out.split("\n")) {
            // Skip the header.
            if (first) {
                first = false;
                continue;
            }
            String[] records = line.split(" ");
            if (records.length > 0) {
                paths.add(records[records.length - 1]);
            }
        }
        return paths;
    }

    private static String pidOf(String appId) throws Exception {
        return expectCommandToSucceed("pidof " + appId).trim();
    }

    private static long countMatch(List<String> paths, Pattern pattern) {
        // Note: asPredicate() returns true for partial matching.
        return paths.stream()
                .filter(pattern.asPredicate())
                .count();
    }
}
