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

package android.hardware.radio.tests.unittests;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.hardware.radio.RadioMetadata;

import org.junit.Test;

import java.util.Set;

public final class RadioMetadataTest {

    private static final int INT_KEY_VALUE = 1;

    private final RadioMetadata.Builder mBuilder = new RadioMetadata.Builder();

    @Test
    public void putString_withIllegalKey() {
        String invalidStringKey = RadioMetadata.METADATA_KEY_RDS_PI;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mBuilder.putString(invalidStringKey, "value");
        });

        assertWithMessage("Exception for putting illegal string-value key %s", invalidStringKey)
                .that(thrown).hasMessageThat()
                .matches(".*" + invalidStringKey + ".*cannot.*String.*?");
    }

    @Test
    public void putInt_withIllegalKey() {
        String invalidIntKey = RadioMetadata.METADATA_KEY_GENRE;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mBuilder.putInt(invalidIntKey, INT_KEY_VALUE);
        });

        assertWithMessage("Exception for putting illegal int-value key %s", invalidIntKey)
                .that(thrown).hasMessageThat()
                .matches(".*" + invalidIntKey + ".*cannot.*int.*?");
    }

    @Test
    public void putClock_withIllegalKey() {
        String invalidClockKey = RadioMetadata.METADATA_KEY_ALBUM;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mBuilder.putClock(invalidClockKey, /* utcSecondsSinceEpoch= */ 1,
                    /* timezoneOffsetMinutes= */ 1);
        });

        assertWithMessage("Exception for putting illegal clock-value key %s", invalidClockKey)
                .that(thrown).hasMessageThat()
                .matches(".*" + invalidClockKey + ".*cannot.*Clock.*?");
    }

    @Test
    public void containsKey_withKeyInMetadata() {
        String key = RadioMetadata.METADATA_KEY_RDS_PI;
        RadioMetadata metadata = mBuilder.putInt(key, INT_KEY_VALUE).build();

        assertWithMessage("Whether metadata contains %s in metadata", key)
                .that(metadata.containsKey(key)).isTrue();
    }

    @Test
    public void containsKey_withKeyNotInMetadata() {
        String key = RadioMetadata.METADATA_KEY_RDS_PI;
        RadioMetadata metadata = mBuilder.putInt(key, INT_KEY_VALUE).build();

        assertWithMessage("Whether metadata contains key %s not in metadata", key)
                .that(metadata.containsKey(RadioMetadata.METADATA_KEY_ARTIST)).isFalse();
    }

    @Test
    public void getInt_withKeyInMetadata() {
        String key = RadioMetadata.METADATA_KEY_RDS_PI;
        RadioMetadata metadata = mBuilder.putInt(key, INT_KEY_VALUE).build();

        assertWithMessage("Int value for key %s in metadata", key)
                .that(metadata.getInt(key)).isEqualTo(INT_KEY_VALUE);
    }

    @Test
    public void getInt_withKeyNotInMetadata() {
        String key = RadioMetadata.METADATA_KEY_RDS_PTY;
        RadioMetadata metadata =
                mBuilder.putInt(RadioMetadata.METADATA_KEY_RDS_PI, INT_KEY_VALUE).build();

        assertWithMessage("Int value for key %s in metadata", key)
                .that(metadata.getInt(key)).isEqualTo(0);
    }

    @Test
    public void getString_withKeyInMetadata() {
        String key = RadioMetadata.METADATA_KEY_ARTIST;
        String value = "artistTest";
        RadioMetadata metadata = mBuilder.putString(key, value).build();

        assertWithMessage("String value for key %s in metadata", key)
                .that(metadata.getString(key)).isEqualTo(value);
    }

    @Test
    public void getString_withKeyNotInMetadata() {
        String key = RadioMetadata.METADATA_KEY_ARTIST;
        RadioMetadata metadata = mBuilder.build();

        assertWithMessage("String value for key %s not in metadata", key)
                .that(metadata.getString(key)).isNull();
    }

    @Test
    public void getClock_withKeyInMetadata() {
        String key = RadioMetadata.METADATA_KEY_CLOCK;
        long utcSecondsSinceEpochExpected = 200;
        int timezoneOffsetMinutesExpected = 1;
        RadioMetadata metadata = mBuilder
                .putClock(key, utcSecondsSinceEpochExpected, timezoneOffsetMinutesExpected)
                .build();

        RadioMetadata.Clock clockExpected = metadata.getClock(key);

        assertWithMessage("Number of seconds since epoch of value for key %s in metadata", key)
                .that(clockExpected.getUtcEpochSeconds())
                .isEqualTo(utcSecondsSinceEpochExpected);
        assertWithMessage("Offset of timezone in minutes of value for key %s in metadata", key)
                .that(clockExpected.getTimezoneOffsetMinutes())
                .isEqualTo(timezoneOffsetMinutesExpected);
    }

    @Test
    public void getClock_withKeyNotInMetadata() {
        String key = RadioMetadata.METADATA_KEY_CLOCK;
        RadioMetadata metadata = mBuilder.build();

        assertWithMessage("Clock value for key %s not in metadata", key)
                .that(metadata.getClock(key)).isNull();
    }

    @Test
    public void size_withNonEmptyMetadata() {
        RadioMetadata metadata = mBuilder
                .putInt(RadioMetadata.METADATA_KEY_RDS_PI, INT_KEY_VALUE)
                .putString(RadioMetadata.METADATA_KEY_ARTIST, "artistTest")
                .build();

        assertWithMessage("Size of fields in non-empty metadata")
                .that(metadata.size()).isEqualTo(2);
    }

    @Test
    public void size_withEmptyMetadata() {
        RadioMetadata metadata = mBuilder.build();

        assertWithMessage("Size of fields in empty metadata")
                .that(metadata.size()).isEqualTo(0);
    }

    @Test
    public void keySet_withNonEmptyMetadata() {
        RadioMetadata metadata = mBuilder
                .putInt(RadioMetadata.METADATA_KEY_RDS_PI, INT_KEY_VALUE)
                .putString(RadioMetadata.METADATA_KEY_ARTIST, "artistTest")
                .build();

        Set<String> metadataSet = metadata.keySet();

        assertWithMessage("Metadata set of non-empty metadata")
                .that(metadataSet).containsExactly(
                        RadioMetadata.METADATA_KEY_RDS_PI, RadioMetadata.METADATA_KEY_ARTIST);
    }

    @Test
    public void keySet_withEmptyMetadata() {
        RadioMetadata metadata = mBuilder.build();

        Set<String> metadataSet = metadata.keySet();

        assertWithMessage("Metadata set of empty metadata")
                .that(metadataSet).isEmpty();
    }

    @Test
    public void getKeyFromNativeKey() {
        int nativeKey = 0;
        String key = RadioMetadata.getKeyFromNativeKey(nativeKey);

        assertWithMessage("Key for native key %s", nativeKey)
                .that(key).isEqualTo(RadioMetadata.METADATA_KEY_RDS_PI);
    }

}
