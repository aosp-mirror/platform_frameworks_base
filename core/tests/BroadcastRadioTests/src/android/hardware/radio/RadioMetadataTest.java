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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import android.graphics.Bitmap;
import android.os.Parcel;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.server.broadcastradio.ExtendedRadioMockitoTestCase;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Set;

@RunWith(MockitoJUnitRunner.class)
public final class RadioMetadataTest extends ExtendedRadioMockitoTestCase {

    private static final int CREATOR_ARRAY_SIZE = 3;
    private static final int INT_KEY_VALUE = 1;
    private static final String ARTIST_KEY_VALUE = "artistTest";
    private static final String[] UFIDS_VALUE = new String[]{"ufid1", "ufid2"};
    private static final long TEST_UTC_SECOND_SINCE_EPOCH = 200;
    private static final int TEST_TIME_ZONE_OFFSET_MINUTES = 1;

    private final RadioMetadata.Builder mBuilder = new RadioMetadata.Builder();

    @Mock
    private Bitmap mBitmapValue;

    @Rule
    public final Expect mExpect = Expect.create();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder.spyStatic(Bitmap.class);
    }

    @Test
    public void describeContents_forClock() {
        RadioMetadata.Clock clock = new RadioMetadata.Clock(TEST_UTC_SECOND_SINCE_EPOCH,
                TEST_TIME_ZONE_OFFSET_MINUTES);

        mExpect.withMessage("Describe contents for metadata clock")
                .that(clock.describeContents()).isEqualTo(0);
    }

    @Test
    public void newArray_forClockCreator() {
        RadioMetadata.Clock[] clocks = RadioMetadata.Clock.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Clock array size").that(clocks.length).isEqualTo(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void writeToParcel_forClock() {
        RadioMetadata.Clock clockExpected = new RadioMetadata.Clock(TEST_UTC_SECOND_SINCE_EPOCH,
                TEST_TIME_ZONE_OFFSET_MINUTES);
        Parcel parcel = Parcel.obtain();

        clockExpected.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        RadioMetadata.Clock clockFromParcel = RadioMetadata.Clock.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("UTC second since epoch of metadata clock created from parcel")
                .that(clockFromParcel.getUtcEpochSeconds()).isEqualTo(TEST_UTC_SECOND_SINCE_EPOCH);
        mExpect.withMessage("Time zone offset minutes of metadata clock created from parcel")
                .that(clockFromParcel.getTimezoneOffsetMinutes())
                .isEqualTo(TEST_TIME_ZONE_OFFSET_MINUTES);
    }

    @Test
    public void putString_withIllegalKey() {
        String invalidStringKey = RadioMetadata.METADATA_KEY_RDS_PI;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mBuilder.putString(invalidStringKey, "value");
        });

        mExpect.withMessage("Exception for putting illegal string-value key %s", invalidStringKey)
                .that(thrown).hasMessageThat()
                .matches(".*" + invalidStringKey + ".*cannot.*String.*?");
    }

    @Test
    public void putInt_withIllegalKey() {
        String invalidIntKey = RadioMetadata.METADATA_KEY_GENRE;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mBuilder.putInt(invalidIntKey, INT_KEY_VALUE);
        });

        mExpect.withMessage("Exception for putting illegal int-value for key %s", invalidIntKey)
                .that(thrown).hasMessageThat()
                .matches(".*" + invalidIntKey + ".*cannot.*int.*?");
    }

    @Test
    public void putBitmap_withIllegalKey() {
        String invalidIntKey = RadioMetadata.METADATA_KEY_GENRE;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mBuilder.putBitmap(invalidIntKey, mBitmapValue);
        });

        mExpect.withMessage("Exception for putting illegal bitmap-value for key %s", invalidIntKey)
                .that(thrown).hasMessageThat()
                .matches(".*" + invalidIntKey + ".*cannot.*Bitmap.*?");
    }

    @Test
    public void putClock_withIllegalKey() {
        String invalidClockKey = RadioMetadata.METADATA_KEY_ALBUM;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mBuilder.putClock(invalidClockKey, /* utcSecondsSinceEpoch= */ 1,
                    /* timezoneOffsetMinutes= */ 1);
        });

        mExpect.withMessage("Exception for putting illegal clock-value key %s", invalidClockKey)
                .that(thrown).hasMessageThat()
                .matches(".*" + invalidClockKey + ".*cannot.*Clock.*?");
    }

    @Test
    public void putStringArray_withIllegalKey_throwsException() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        String invalidStringArrayKey = RadioMetadata.METADATA_KEY_HD_STATION_NAME_LONG;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mBuilder.putStringArray(invalidStringArrayKey, UFIDS_VALUE);
        });

        mExpect.withMessage("Exception for putting illegal string-array-value for key %s",
                invalidStringArrayKey).that(thrown).hasMessageThat()
                .matches(".*" + invalidStringArrayKey + ".*cannot.*Array.*?");
    }

    @Test
    public void putStringArray_withNullKey_throwsException() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);

        NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
            mBuilder.putStringArray(/* key= */ null, UFIDS_VALUE);
        });

        mExpect.withMessage("Exception for putting string-array with null key")
                .that(thrown).hasMessageThat().contains("can not be null");
    }

    @Test
    public void putStringArray_withNullString_throwsException() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);

        NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
            mBuilder.putStringArray(RadioMetadata.METADATA_KEY_UFIDS, /* value= */ null);
        });

        mExpect.withMessage("Exception for putting null string-array")
                .that(thrown).hasMessageThat().contains("can not be null");
    }

    @Test
    public void containsKey_withKeyInMetadata() {
        String key = RadioMetadata.METADATA_KEY_RDS_PI;
        RadioMetadata metadata = mBuilder.putInt(key, INT_KEY_VALUE).build();

        mExpect.withMessage("Whether metadata contains %s in metadata", key)
                .that(metadata.containsKey(key)).isTrue();
    }

    @Test
    public void containsKey_withKeyNotInMetadata() {
        String key = RadioMetadata.METADATA_KEY_RDS_PI;
        RadioMetadata metadata = mBuilder.putInt(key, INT_KEY_VALUE).build();

        mExpect.withMessage("Whether metadata contains key %s not in metadata", key)
                .that(metadata.containsKey(RadioMetadata.METADATA_KEY_ARTIST)).isFalse();
    }

    @Test
    public void getInt_withKeyInMetadata() {
        String key = RadioMetadata.METADATA_KEY_RDS_PI;
        RadioMetadata metadata = mBuilder.putInt(key, INT_KEY_VALUE).build();

        mExpect.withMessage("Int value for key %s in metadata", key)
                .that(metadata.getInt(key)).isEqualTo(INT_KEY_VALUE);
    }

    @Test
    public void getInt_withKeyNotInMetadata() {
        String key = RadioMetadata.METADATA_KEY_RDS_PTY;
        RadioMetadata metadata =
                mBuilder.putInt(RadioMetadata.METADATA_KEY_RDS_PI, INT_KEY_VALUE).build();

        mExpect.withMessage("Int value for key %s in metadata", key)
                .that(metadata.getInt(key)).isEqualTo(0);
    }

    @Test
    public void getString_withKeyInMetadata() {
        String key = RadioMetadata.METADATA_KEY_ARTIST;
        RadioMetadata metadata = mBuilder.putString(key, ARTIST_KEY_VALUE).build();

        mExpect.withMessage("String value for key %s in metadata", key)
                .that(metadata.getString(key)).isEqualTo(ARTIST_KEY_VALUE);
    }

    @Test
    public void getString_withKeyNotInMetadata() {
        String key = RadioMetadata.METADATA_KEY_ARTIST;
        RadioMetadata metadata = mBuilder.build();

        mExpect.withMessage("String value for key %s not in metadata", key)
                .that(metadata.getString(key)).isNull();
    }

    @Test
    public void getBitmap_withKeyInMetadata() {
        String key = RadioMetadata.METADATA_KEY_ICON;
        RadioMetadata metadata = mBuilder.putBitmap(key, mBitmapValue).build();

        mExpect.withMessage("Bitmap value for key %s in metadata", key)
                .that(metadata.getBitmap(key)).isEqualTo(mBitmapValue);
    }

    @Test
    public void getBitmap_withKeyNotInMetadata() {
        String key = RadioMetadata.METADATA_KEY_ICON;
        RadioMetadata metadata = mBuilder.build();

        mExpect.withMessage("Bitmap value for key %s not in metadata", key)
                .that(metadata.getBitmap(key)).isNull();
    }

    @Test
    public void getBitmap_withIllegalKey() {
        String illegalKey = RadioMetadata.METADATA_KEY_ARTIST;
        RadioMetadata metadata = mBuilder.putInt(RadioMetadata.METADATA_KEY_ICON, INT_KEY_VALUE)
                .build();

        mExpect.withMessage("Bitmap id value with non-bitmap-type key %s", illegalKey)
                .that(metadata.getBitmap(illegalKey)).isNull();
    }

    @Test
    public void getBitmapId_withKeyInMetadata() {
        String key = RadioMetadata.METADATA_KEY_ART;
        RadioMetadata metadata = mBuilder.putInt(key, INT_KEY_VALUE).build();

        mExpect.withMessage("Bitmap id value for key %s in metadata", key)
                .that(metadata.getBitmapId(key)).isEqualTo(INT_KEY_VALUE);
    }

    @Test
    public void getBitmapId_withKeyNotInMetadata() {
        String key = RadioMetadata.METADATA_KEY_ART;
        RadioMetadata metadata = mBuilder.build();

        mExpect.withMessage("Bitmap id value for key %s not in metadata", key)
                .that(metadata.getBitmapId(key)).isEqualTo(0);
    }

    @Test
    public void getBitmapId_withIllegalKey_fails() {
        String illegalKey = RadioMetadata.METADATA_KEY_ARTIST;
        RadioMetadata metadata = mBuilder.putInt(RadioMetadata.METADATA_KEY_ART, INT_KEY_VALUE)
                .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            metadata.getBitmapId(illegalKey);
        });

        mExpect.withMessage("Exception for getting string array for non-bitmap-id type key %s",
                illegalKey).that(thrown).hasMessageThat().contains("bitmap key");
    }

    @Test
    public void getBitmapId_withNullKey_fails() {
        RadioMetadata metadata = mBuilder.build();

        NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
            metadata.getBitmapId(/* key= */ null);
        });

        mExpect.withMessage("Exception for getting bitmap identifier with null key")
                .that(thrown).hasMessageThat().contains("can not be null");
    }

    @Test
    public void getClock_withKeyInMetadata() {
        String key = RadioMetadata.METADATA_KEY_CLOCK;
        RadioMetadata metadata = mBuilder
                .putClock(key, TEST_UTC_SECOND_SINCE_EPOCH, TEST_TIME_ZONE_OFFSET_MINUTES)
                .build();

        RadioMetadata.Clock clockExpected = metadata.getClock(key);

        mExpect.withMessage("Number of seconds since epoch of value for key %s in metadata", key)
                .that(clockExpected.getUtcEpochSeconds())
                .isEqualTo(TEST_UTC_SECOND_SINCE_EPOCH);
        mExpect.withMessage("Offset of timezone in minutes of value for key %s in metadata", key)
                .that(clockExpected.getTimezoneOffsetMinutes())
                .isEqualTo(TEST_TIME_ZONE_OFFSET_MINUTES);
    }

    @Test
    public void getClock_withKeyNotInMetadata() {
        String key = RadioMetadata.METADATA_KEY_CLOCK;
        RadioMetadata metadata = mBuilder.build();

        mExpect.withMessage("Clock value for key %s not in metadata", key)
                .that(metadata.getClock(key)).isNull();
    }

    @Test
    public void getClock_withIllegalKey() {
        String illegalKey = RadioMetadata.METADATA_KEY_ARTIST;
        RadioMetadata metadata = mBuilder.putClock(RadioMetadata.METADATA_KEY_CLOCK,
                        TEST_UTC_SECOND_SINCE_EPOCH, TEST_TIME_ZONE_OFFSET_MINUTES).build();

        mExpect.withMessage("Clock value for non-clock-type key %s", illegalKey)
                .that(metadata.getClock(illegalKey)).isNull();
    }

    @Test
    public void getStringArray_withKeyInMetadata() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        String key = RadioMetadata.METADATA_KEY_UFIDS;
        RadioMetadata metadata = mBuilder.putStringArray(key, UFIDS_VALUE).build();

        mExpect.withMessage("String-array value for key %s not in metadata", key)
                .that(metadata.getStringArray(key)).asList().isEqualTo(Arrays.asList(UFIDS_VALUE));
    }

    @Test
    public void getStringArray_withKeyNotInMetadata() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        String key = RadioMetadata.METADATA_KEY_UFIDS;
        RadioMetadata metadata = mBuilder.build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            metadata.getStringArray(key);
        });

        mExpect.withMessage("Exception for getting string array for string-array value for key %s "
                + "not in metadata", key).that(thrown).hasMessageThat().contains("not found");
    }

    @Test
    public void getStringArray_withNullKey() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        RadioMetadata metadata = mBuilder.build();

        NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
            metadata.getStringArray(/* key= */ null);
        });

        mExpect.withMessage("Exception for getting string array with null key")
                .that(thrown).hasMessageThat().contains("can not be null");
    }

    @Test
    public void getStringArray_withInvalidKey() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        String invalidClockKey = RadioMetadata.METADATA_KEY_HD_STATION_NAME_LONG;
        RadioMetadata metadata = mBuilder.build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            metadata.getStringArray(invalidClockKey);
        });

        mExpect.withMessage("Exception for getting string array for non-string-array type key %s",
                invalidClockKey).that(thrown).hasMessageThat()
                .contains("string array");
    }

    @Test
    public void size_withNonEmptyMetadata() {
        RadioMetadata metadata = mBuilder
                .putInt(RadioMetadata.METADATA_KEY_RDS_PI, INT_KEY_VALUE)
                .putString(RadioMetadata.METADATA_KEY_ARTIST, ARTIST_KEY_VALUE)
                .build();

        mExpect.withMessage("Size of fields in non-empty metadata")
                .that(metadata.size()).isEqualTo(2);
    }

    @Test
    public void size_withEmptyMetadata() {
        RadioMetadata metadata = mBuilder.build();

        mExpect.withMessage("Size of fields in empty metadata")
                .that(metadata.size()).isEqualTo(0);
    }

    @Test
    public void keySet_withNonEmptyMetadata() {
        RadioMetadata metadata = mBuilder
                .putInt(RadioMetadata.METADATA_KEY_RDS_PI, INT_KEY_VALUE)
                .putString(RadioMetadata.METADATA_KEY_ARTIST, ARTIST_KEY_VALUE)
                .putBitmap(RadioMetadata.METADATA_KEY_ICON, mBitmapValue)
                .build();

        Set<String> metadataSet = metadata.keySet();

        mExpect.withMessage("Metadata set of non-empty metadata")
                .that(metadataSet).containsExactly(RadioMetadata.METADATA_KEY_ICON,
                        RadioMetadata.METADATA_KEY_RDS_PI, RadioMetadata.METADATA_KEY_ARTIST);
    }

    @Test
    public void keySet_withEmptyMetadata() {
        RadioMetadata metadata = mBuilder.build();

        Set<String> metadataSet = metadata.keySet();

        mExpect.withMessage("Metadata set of empty metadata")
                .that(metadataSet).isEmpty();
    }

    @Test
    public void getKeyFromNativeKey() {
        int nativeKey = 0;
        String key = RadioMetadata.getKeyFromNativeKey(nativeKey);

        mExpect.withMessage("Key for native key %s", nativeKey)
                .that(key).isEqualTo(RadioMetadata.METADATA_KEY_RDS_PI);
    }

    @Test
    public void equals_forMetadataWithSameContents_returnsTrue() {
        RadioMetadata metadata = mBuilder
                .putInt(RadioMetadata.METADATA_KEY_RDS_PI, INT_KEY_VALUE)
                .putString(RadioMetadata.METADATA_KEY_ARTIST, ARTIST_KEY_VALUE)
                .build();
        RadioMetadata.Builder copyBuilder = new RadioMetadata.Builder(metadata);
        RadioMetadata metadataCopied = copyBuilder.build();

        mExpect.withMessage("Metadata with the same contents")
                .that(metadataCopied).isEqualTo(metadata);
    }

    @Test
    public void equals_forMetadataWithDifferentContents_returnsFalse() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        RadioMetadata metadata1 = mBuilder
                .putStringArray(RadioMetadata.METADATA_KEY_UFIDS, UFIDS_VALUE)
                .build();
        RadioMetadata metadata2 = mBuilder
                .putStringArray(RadioMetadata.METADATA_KEY_UFIDS, new String[]{"ufid3", "ufid2"})
                .build();

        mExpect.withMessage("Metadata with the same contents")
                .that(metadata1).isNotEqualTo(metadata2);
    }

    @Test
    public void describeContents_forMetadata() {
        RadioMetadata metadata = mBuilder.build();

        mExpect.withMessage("Metadata contents").that(metadata.describeContents()).isEqualTo(0);
    }

    @Test
    public void newArray_forRadioMetadataCreator() {
        RadioMetadata[] metadataArray = RadioMetadata.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Radio metadata array").that(metadataArray)
                .hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void writeToParcel_forRadioMetadata() {
        RadioMetadata metadataExpected = mBuilder
                .putInt(RadioMetadata.METADATA_KEY_RDS_PI, INT_KEY_VALUE)
                .putString(RadioMetadata.METADATA_KEY_ARTIST, ARTIST_KEY_VALUE)
                .build();
        Parcel parcel = Parcel.obtain();

        metadataExpected.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        RadioMetadata metadataFromParcel = RadioMetadata.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Radio metadata created from parcel")
                .that(metadataFromParcel).isEqualTo(metadataExpected);
    }

    @Test
    public void writeToParcel_forRadioMetadata_withStringArrayTypeMetadata() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        RadioMetadata metadataExpected = mBuilder
                .putInt(RadioMetadata.METADATA_KEY_RDS_PI, INT_KEY_VALUE)
                .putString(RadioMetadata.METADATA_KEY_ARTIST, ARTIST_KEY_VALUE)
                .putStringArray(RadioMetadata.METADATA_KEY_UFIDS, UFIDS_VALUE)
                .build();
        Parcel parcel = Parcel.obtain();

        metadataExpected.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        RadioMetadata metadataFromParcel = RadioMetadata.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Radio metadata created from parcel with string array type metadata")
                .that(metadataFromParcel).isEqualTo(metadataExpected);
    }

    @Test
    public void build_withRadioMetadataMeetingSizeRequirement() {
        int maxBitmapSize = 10;
        doReturn(maxBitmapSize - 1).when(mBitmapValue).getHeight();
        doReturn(maxBitmapSize - 1).when(mBitmapValue).getWidth();
        RadioMetadata metadataSource = mBuilder.putBitmap(
                RadioMetadata.METADATA_KEY_ICON, mBitmapValue).build();

        RadioMetadata metadata = new RadioMetadata.Builder(metadataSource, maxBitmapSize).build();

        mExpect.withMessage("Bitmap without resizing")
                .that(metadata.getBitmap(RadioMetadata.METADATA_KEY_ICON)).isEqualTo(mBitmapValue);
    }

    @Test
    public void build_withRadioMetadataAboveSizeRequirement() {
        int maxBitmapSize = 10;
        int bitmapHeight = 10;
        int bitmapWidth = 20;
        int bitmapWidthResized = maxBitmapSize;
        int bitmapHeightResized  = (bitmapHeight * bitmapWidthResized) / bitmapWidth;
        Bitmap bitmapResized = mock(Bitmap.class);
        doReturn(bitmapHeight).when(mBitmapValue).getHeight();
        doReturn(bitmapWidth).when(mBitmapValue).getWidth();
        doReturn(bitmapResized).when(() -> Bitmap.createScaledBitmap(
                mBitmapValue, bitmapWidthResized, bitmapHeightResized, /* filter= */ true));
        RadioMetadata metadataSource = mBuilder.putBitmap(
                RadioMetadata.METADATA_KEY_ICON, mBitmapValue).build();

        RadioMetadata metadata = new RadioMetadata.Builder(metadataSource, maxBitmapSize).build();

        mExpect.withMessage("Bitmap with resizing")
                .that(metadata.getBitmap(RadioMetadata.METADATA_KEY_ICON))
                .isEqualTo(bitmapResized);
    }

    @Test
    public void toString_containsMetadataValues() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        RadioMetadata metadataExpected = mBuilder
                .putInt(RadioMetadata.METADATA_KEY_RDS_PI, INT_KEY_VALUE)
                .putString(RadioMetadata.METADATA_KEY_ARTIST, ARTIST_KEY_VALUE)
                .putStringArray(RadioMetadata.METADATA_KEY_UFIDS, UFIDS_VALUE)
                .build();

        String metadateString = metadataExpected.toString();

        mExpect.withMessage("RDS PI value in converted sting for metadata")
                .that(metadateString).contains(Integer.toString(INT_KEY_VALUE));
        mExpect.withMessage("Artist value in converted sting for metadata")
                .that(metadateString).contains(ARTIST_KEY_VALUE);
        for (int i = 0; i < UFIDS_VALUE.length; i++) {
            mExpect.withMessage("UFIDs[%s] value in converted sting for metadata", i)
                    .that(metadateString).contains(UFIDS_VALUE[i]);
        }
    }
}
