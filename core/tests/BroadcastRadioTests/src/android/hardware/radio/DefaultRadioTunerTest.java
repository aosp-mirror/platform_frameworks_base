/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.hardware.radio;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DefaultRadioTunerTest {

    private static final RadioTuner DEFAULT_RADIO_TUNER = new RadioTuner() {
        @Override
        public void close() {}

        @Override
        public int setConfiguration(RadioManager.BandConfig config) {
            return 0;
        }

        @Override
        public int getConfiguration(RadioManager.BandConfig[] config) {
            return 0;
        }

        @Override
        public int setMute(boolean mute) {
            return 0;
        }

        @Override
        public boolean getMute() {
            return false;
        }

        @Override
        public int step(int direction, boolean skipSubChannel) {
            return 0;
        }

        @Override
        public int scan(int direction, boolean skipSubChannel) {
            return 0;
        }

        @Override
        public int tune(int channel, int subChannel) {
            return 0;
        }

        @Override
        public void tune(@NonNull ProgramSelector selector) {}

        @Override
        public int cancel() {
            return 0;
        }

        @Override
        public void cancelAnnouncement() {}

        @Override
        public int getProgramInformation(RadioManager.ProgramInfo[] info) {
            return 0;
        }

        @Nullable
        @Override
        public Bitmap getMetadataImage(int id) {
            return null;
        }

        @Override
        public boolean startBackgroundScan() {
            return false;
        }

        @NonNull
        @Override
        public List<RadioManager.ProgramInfo> getProgramList(
                @Nullable Map<String, String> vendorFilter) {
            return new ArrayList<>();
        }

        @Override
        public boolean isAnalogForced() {
            return false;
        }

        @Override
        public void setAnalogForced(boolean isForced) {}

        @Override
        public boolean isAntennaConnected() {
            return false;
        }

        @Override
        public boolean hasControl() {
            return false;
        }
    };

    @Test
    public void seek_forRadioTuner_throwsException() {
        UnsupportedOperationException thrown = assertThrows(
                UnsupportedOperationException.class, () -> {
                    DEFAULT_RADIO_TUNER.seek(RadioTuner.DIRECTION_DOWN,
                            /* skipSubChannel= */ false);
                });

        assertWithMessage("Exception for seeking on default radio tuner")
                .that(thrown).hasMessageThat().contains("Seeking is not supported");
    }

    @Test
    public void getDynamicProgramList_forRadioTuner_returnsNull() {
        assertWithMessage("Dynamic program list obtained from default radio tuner")
                .that(DEFAULT_RADIO_TUNER.getDynamicProgramList(new ProgramList.Filter())).isNull();
    }

    @Test
    public void isConfigFlagSupported_forRadioTuner_throwsException() {
        assertWithMessage("Dynamic program list obtained from default radio tuner")
                .that(DEFAULT_RADIO_TUNER.isConfigFlagSupported(/* flag= */ 1)).isFalse();
    }

    @Test
    public void isConfigFlagSet_forRadioTuner_throwsException() {
        UnsupportedOperationException thrown = assertThrows(
                UnsupportedOperationException.class, () -> {
                    DEFAULT_RADIO_TUNER.isConfigFlagSet(/* flag= */ 1);
                });

        assertWithMessage("Exception for isConfigFlagSet on default radio tuner")
                .that(thrown).hasMessageThat().contains("isConfigFlagSet is not supported");
    }

    @Test
    public void setConfigFlag_forRadioTuner_throwsException() {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> DEFAULT_RADIO_TUNER.setConfigFlag(/* flag= */ 1, /* value= */ false));

        assertWithMessage("Exception for setting config flag on default radio tuner")
                .that(thrown).hasMessageThat().contains("Setting config flag is not supported");
    }

    @Test
    public void setParameters_forRadioTuner_throwsException() {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> DEFAULT_RADIO_TUNER.setParameters(Map.of("testKey", "testValue")));

        assertWithMessage("Exception for setting parameters from default radio tuner")
                .that(thrown).hasMessageThat().contains("Setting parameters is not supported");
    }

    @Test
    public void getParameters_forRadioTuner_throwsException() {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> DEFAULT_RADIO_TUNER.getParameters(List.of("testKey")));

        assertWithMessage("Exception for getting parameters from default radio tuner")
                .that(thrown).hasMessageThat().contains("Getting parameters is not supported");
    }
}
