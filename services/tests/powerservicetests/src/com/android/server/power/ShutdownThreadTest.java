/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.power;

import static com.android.server.power.ShutdownThread.DEFAULT_SHUTDOWN_VIBRATE_MS;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.util.AtomicFile;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Tests for {@link com.android.server.power.ShutdownThread}
 */
public class ShutdownThreadTest {

    private static final String WAVEFORM_VIB_10MS_SERIALIZATION =
            """
            <vibration-effect>
                <waveform-effect>
                    <waveform-entry durationMs="10" amplitude="100"/>
                </waveform-effect>
            </vibration-effect>
            """;

    private static final VibrationEffect WAVEFORM_VIB_10MS = VibrationEffect.createOneShot(10, 100);

    private static final String REPEATING_VIB_SERIALIZATION =
            """
            <vibration-effect>
                <waveform-effect>
                    <repeating>
                        <waveform-entry durationMs="10" amplitude="100"/>
                    </repeating>
                </waveform-effect>
            </vibration-effect>
            """;

    private static final String CLICK_VIB_SERIALIZATION =
            """
            <vibration-effect>
                <predefined-effect name="click"/>
            </vibration-effect>
            """;

    private static final VibrationEffect CLILCK_VIB =
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);

    private static final String BAD_VIB_SERIALIZATION = "BAD SERIALIZATION";

    @Mock private Context mContextMock;
    @Mock private Vibrator mVibratorMock;
    @Mock private VibratorInfo mVibratorInfoMock;

    private String mDefaultShutdownVibrationFilePath;
    private long mLastSleepDurationMs;

    private ShutdownThread mShutdownThread;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mVibratorMock.hasVibrator()).thenReturn(true);
        when(mVibratorMock.getInfo()).thenReturn(mVibratorInfoMock);

        when(mVibratorInfoMock.areVibrationFeaturesSupported(any())).thenReturn(true);

        mShutdownThread = new ShutdownThread(new TestInjector());
    }

    @Test
    public void testSuccessfulShutdownVibrationFromFile() throws Exception {
        setShutdownVibrationFileContent(WAVEFORM_VIB_10MS_SERIALIZATION);

        mShutdownThread.playShutdownVibration(mContextMock);

        assertShutdownVibration(WAVEFORM_VIB_10MS, /* vibrationSleepDuration= */ 10);
    }

    @Test
    public void testIOExceptionWhenParsingShutdownVibration() throws Exception {
        mDefaultShutdownVibrationFilePath = "non/existent/file_path";

        mShutdownThread.playShutdownVibration(mContextMock);

        assertDefaultShutdownVibration();
    }

    @Test
    public void testMalformedShutdownVibrationFileContent() throws Exception {
        setShutdownVibrationFileContent(BAD_VIB_SERIALIZATION);

        mShutdownThread.playShutdownVibration(mContextMock);

        assertDefaultShutdownVibration();
    }

    @Test
    public void testVibratorUnsupportedShutdownVibrationEffect() throws Exception {
        setShutdownVibrationFileContent(WAVEFORM_VIB_10MS_SERIALIZATION);
        when(mVibratorInfoMock.areVibrationFeaturesSupported(any())).thenReturn(false);

        mShutdownThread.playShutdownVibration(mContextMock);

        assertDefaultShutdownVibration();
    }

    @Test
    public void testRepeatinghutdownVibrationEffect() throws Exception {
        setShutdownVibrationFileContent(REPEATING_VIB_SERIALIZATION);

        mShutdownThread.playShutdownVibration(mContextMock);

        assertDefaultShutdownVibration();
    }

    @Test
    public void testVibrationEffectWithUnknownDuration() throws Exception {
        setShutdownVibrationFileContent(CLICK_VIB_SERIALIZATION);

        mShutdownThread.playShutdownVibration(mContextMock);

        assertShutdownVibration(CLILCK_VIB, DEFAULT_SHUTDOWN_VIBRATE_MS);
    }

    @Test
    public void testNoVibrator() {
        when(mVibratorMock.hasVibrator()).thenReturn(false);

        mShutdownThread.playShutdownVibration(mContextMock);

        verify(mVibratorMock, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    private void assertShutdownVibration(VibrationEffect effect, long vibrationSleepDuration)
            throws Exception {
        verify(mVibratorMock).vibrate(
                eq(effect),
                eq(VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)));
        assertEquals(vibrationSleepDuration, mLastSleepDurationMs);
    }

    private void assertDefaultShutdownVibration() throws Exception {
        assertShutdownVibration(
                VibrationEffect.createOneShot(
                        DEFAULT_SHUTDOWN_VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE),
                DEFAULT_SHUTDOWN_VIBRATE_MS);
    }

    private void setShutdownVibrationFileContent(String content) throws Exception {
        mDefaultShutdownVibrationFilePath = createFileForContent(content).getAbsolutePath();
    }

    private static File createFileForContent(String content) throws Exception {
        File file = new File(InstrumentationRegistry.getContext().getCacheDir(), "test.xml");
        file.createNewFile();

        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream fos = atomicFile.startWrite();
        fos.write(content.getBytes());
        atomicFile.finishWrite(fos);

        return file;
    }

    private class TestInjector extends ShutdownThread.Injector {
        @Override
        public Vibrator getVibrator(Context context) {
            return mVibratorMock;
        }

        @Override
        public String getDefaultShutdownVibrationEffectFilePath(Context context) {
            return mDefaultShutdownVibrationFilePath;
        }

        @Override
        public void sleep(long durationMs) {
            mLastSleepDurationMs = durationMs;
        }
    }
}
