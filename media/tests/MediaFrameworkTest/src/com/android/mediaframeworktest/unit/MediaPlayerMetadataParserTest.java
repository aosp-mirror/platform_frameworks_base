/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.mediaframeworktest.unit;
import android.media.Metadata;
import android.os.Parcel;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import java.util.Calendar;
import java.util.Date;

/*
 * Check the Java layer that parses serialized metadata in Parcel
 * works as expected.
 *
 */

public class MediaPlayerMetadataParserTest extends AndroidTestCase {
    private static final String TAG = "MediaPlayerMetadataTest";
    private static final int kMarker = 0x4d455441;  // 'M' 'E' 'T' 'A'
    private static final int kHeaderSize = 8;

    private Metadata mMetadata = null;
    private Parcel mParcel = null;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMetadata = new Metadata();
        mParcel = Parcel.obtain();

        resetParcel();
    }

    // Check parsing of the parcel fails. Make sure the parser rewind
    // the parcel properly.
    private void assertParseFail() throws Exception {
        mParcel.setDataPosition(0);
        assertFalse(mMetadata.parse(mParcel));
        assertEquals(0, mParcel.dataPosition());
    }

    // Check parsing of the parcel is successful.
    private void assertParse() throws Exception {
        mParcel.setDataPosition(0);
        assertTrue(mMetadata.parse(mParcel));
    }

    // Write the number of bytes from the start of the parcel to the
    // current position at the beginning of the parcel (offset 0).
    private void adjustSize() {
        adjustSize(0);
    }

    // Write the number of bytes from the offset to the current
    // position at position pointed by offset.
    private void adjustSize(int offset) {
        final int pos = mParcel.dataPosition();

        mParcel.setDataPosition(offset);
        mParcel.writeInt(pos - offset);
        mParcel.setDataPosition(pos);
    }

    // Rewind the parcel and insert the header.
    private void resetParcel() {
        mParcel.setDataPosition(0);
        // Most tests will use a properly formed parcel with a size
        // and the meta marker so we add them by default.
        mParcel.writeInt(-1);  // Placeholder for the size
        mParcel.writeInt(kMarker);
    }

    // ----------------------------------------------------------------------
    // START OF THE TESTS


    // There should be at least 8 bytes in the parcel, 4 for the size
    // and 4 for the 'M' 'E' 'T' 'A' marker.
    @SmallTest
    public void testMissingSizeAndMarker() throws Exception {
        for (int i = 0; i < kHeaderSize; ++i) {
            mParcel.setDataPosition(0);
            mParcel.setDataSize(i);

            assertEquals(i, mParcel.dataAvail());
            assertParseFail();
        }
    }

    // There should be at least 'size' bytes in the parcel.
    @SmallTest
    public void testMissingData() throws Exception {
        final int size = 20;

        mParcel.writeInt(size);
        mParcel.setDataSize(size - 1);
        assertParseFail();
    }

    // Empty parcel is fine
    @SmallTest
    public void testEmptyIsOk() throws Exception {
        adjustSize();
        assertParse();
    }

    // ----------------------------------------------------------------------
    // RECORDS
    // ----------------------------------------------------------------------

    // A record header should be at least 12 bytes long
    @SmallTest
    public void testRecordMissingId() throws Exception {
        mParcel.writeInt(13); // record length
        // misses metadata id and metadata type.
        adjustSize();
        assertParseFail();
    }

    @SmallTest
    public void testRecordMissingType() throws Exception {
        mParcel.writeInt(13); // record length lies
        mParcel.writeInt(Metadata.TITLE);
        // misses metadata type
        adjustSize();
        assertParseFail();
    }

    @SmallTest
    public void testRecordWithZeroPayload() throws Exception {
        mParcel.writeInt(0);
        adjustSize();
        assertParseFail();
    }

    // A record cannot be empty.
    @SmallTest
    public void testRecordMissingPayload() throws Exception {
        mParcel.writeInt(12);
        mParcel.writeInt(Metadata.TITLE);
        mParcel.writeInt(Metadata.STRING_VAL);
        // misses payload
        adjustSize();
        assertParseFail();
    }

    // Check records can be found.
    @SmallTest
    public void testRecordsFound() throws Exception {
        writeStringRecord(Metadata.TITLE, "a title");
        writeStringRecord(Metadata.GENRE, "comedy");
        writeStringRecord(Metadata.firstCustomId(), "custom");
        adjustSize();
        assertParse();
        assertTrue(mMetadata.has(Metadata.TITLE));
        assertTrue(mMetadata.has(Metadata.GENRE));
        assertTrue(mMetadata.has(Metadata.firstCustomId()));
        assertFalse(mMetadata.has(Metadata.DRM_CRIPPLED));
        assertEquals(3, mMetadata.keySet().size());
    }

    // Detects bad metadata type
    @SmallTest
    public void testBadMetadataType() throws Exception {
        final int start = mParcel.dataPosition();
        mParcel.writeInt(-1);  // Placeholder for the length
        mParcel.writeInt(Metadata.TITLE);
        mParcel.writeInt(0);  // Invalid type.
        mParcel.writeString("dummy");
        adjustSize(start);

        adjustSize();
        assertParseFail();
    }

    // Check a Metadata instance can be reused, i.e the parse method
    // wipes out the existing states/keys.
    @SmallTest
    public void testParseClearState() throws Exception {
        writeStringRecord(Metadata.TITLE, "a title");
        writeStringRecord(Metadata.GENRE, "comedy");
        writeStringRecord(Metadata.firstCustomId(), "custom");
        adjustSize();
        assertParse();

        resetParcel();
        writeStringRecord(Metadata.MIME_TYPE, "audio/mpg");
        adjustSize();
        assertParse();

        // Only the mime type metadata should be present.
        assertEquals(1, mMetadata.keySet().size());
        assertTrue(mMetadata.has(Metadata.MIME_TYPE));

        assertFalse(mMetadata.has(Metadata.TITLE));
        assertFalse(mMetadata.has(Metadata.GENRE));
        assertFalse(mMetadata.has(Metadata.firstCustomId()));
    }

    // ----------------------------------------------------------------------
    // GETTERS
    // ----------------------------------------------------------------------

    // getString
    @SmallTest
    public void testGetString() throws Exception {
        writeStringRecord(Metadata.TITLE, "a title");
        writeStringRecord(Metadata.GENRE, "comedy");
        adjustSize();
        assertParse();

        assertEquals("a title", mMetadata.getString(Metadata.TITLE));
        assertEquals("comedy", mMetadata.getString(Metadata.GENRE));
    }

    // get an empty string.
    @SmallTest
    public void testGetEmptyString() throws Exception {
        writeStringRecord(Metadata.TITLE, "");
        adjustSize();
        assertParse();

        assertEquals("", mMetadata.getString(Metadata.TITLE));
    }

    // get a string when a NULL value was in the parcel
    @SmallTest
    public void testGetNullString() throws Exception {
        writeStringRecord(Metadata.TITLE, null);
        adjustSize();
        assertParse();

        assertEquals(null, mMetadata.getString(Metadata.TITLE));
    }

    // get a string when an integer is actually present
    @SmallTest
    public void testWrongType() throws Exception {
        writeIntRecord(Metadata.DURATION, 5);
        adjustSize();
        assertParse();

        try {
            mMetadata.getString(Metadata.DURATION);
        } catch (IllegalStateException ise) {
            return;
        }
        fail("Exception was not thrown");
    }

    // getInt
    @SmallTest
    public void testGetInt() throws Exception {
        writeIntRecord(Metadata.CD_TRACK_NUM, 1);
        adjustSize();
        assertParse();

        assertEquals(1, mMetadata.getInt(Metadata.CD_TRACK_NUM));
    }

    // getBoolean
    @SmallTest
    public void testGetBoolean() throws Exception {
        writeBooleanRecord(Metadata.PAUSE_AVAILABLE, true);
        writeBooleanRecord(Metadata.SEEK_AVAILABLE, true);
        writeBooleanRecord(Metadata.SEEK_BACKWARD_AVAILABLE, true);
        writeBooleanRecord(Metadata.SEEK_FORWARD_AVAILABLE, true);
        adjustSize();
        assertParse();

        assertEquals(true, mMetadata.getBoolean(Metadata.PAUSE_AVAILABLE));
        assertEquals(true, mMetadata.getBoolean(Metadata.SEEK_AVAILABLE));
        assertEquals(true, mMetadata.getBoolean(Metadata.SEEK_BACKWARD_AVAILABLE));
        assertEquals(true, mMetadata.getBoolean(Metadata.SEEK_FORWARD_AVAILABLE));
    }

    // getLong
    @SmallTest
    public void testGetLong() throws Exception {
        writeLongRecord(Metadata.DURATION, 1L);
        adjustSize();
        assertParse();

        assertEquals(1L, mMetadata.getLong(Metadata.DURATION));
    }

    // getDouble
    @SmallTest
    public void testGetDouble() throws Exception {
        writeDoubleRecord(Metadata.VIDEO_FRAME_RATE, 29.97);
        adjustSize();
        assertParse();

        assertEquals(29.97, mMetadata.getDouble(Metadata.VIDEO_FRAME_RATE));
    }

    // getByteArray
    @SmallTest
    public void testGetByteArray() throws Exception {
        byte data[] = new byte[]{1,2,3,4,5};

        writeByteArrayRecord(Metadata.ALBUM_ART, data);
        adjustSize();
        assertParse();

        byte res[] = mMetadata.getByteArray(Metadata.ALBUM_ART);
        for (int i = 0; i < data.length; ++i) {
            assertEquals(data[i], res[i]);
        }
    }

    // getDate
    @SmallTest
    public void testGetDate() throws Exception {
        writeDateRecord(Metadata.DATE, 0, "PST");
        adjustSize();
        assertParse();

        assertEquals(new Date(0), mMetadata.getDate(Metadata.DATE));
    }

    // ----------------------------------------------------------------------
    // HELPERS TO APPEND RECORDS
    // ----------------------------------------------------------------------

    // Insert a string record at the current position.
    private void writeStringRecord(int metadataId, String val) {
        final int start = mParcel.dataPosition();
        mParcel.writeInt(-1);  // Placeholder for the length
        mParcel.writeInt(metadataId);
        mParcel.writeInt(Metadata.STRING_VAL);
        mParcel.writeString(val);
        adjustSize(start);
    }

    // Insert an int record at the current position.
    private void writeIntRecord(int metadataId, int val) {
        final int start = mParcel.dataPosition();
        mParcel.writeInt(-1);  // Placeholder for the length
        mParcel.writeInt(metadataId);
        mParcel.writeInt(Metadata.INTEGER_VAL);
        mParcel.writeInt(val);
        adjustSize(start);
    }

    // Insert a boolean record at the current position.
    private void writeBooleanRecord(int metadataId, boolean val) {
        final int start = mParcel.dataPosition();
        mParcel.writeInt(-1);  // Placeholder for the length
        mParcel.writeInt(metadataId);
        mParcel.writeInt(Metadata.BOOLEAN_VAL);
        mParcel.writeInt(val ? 1 : 0);
        adjustSize(start);
    }

    // Insert a Long record at the current position.
    private void writeLongRecord(int metadataId, long val) {
        final int start = mParcel.dataPosition();
        mParcel.writeInt(-1);  // Placeholder for the length
        mParcel.writeInt(metadataId);
        mParcel.writeInt(Metadata.LONG_VAL);
        mParcel.writeLong(val);
        adjustSize(start);
    }

    // Insert a Double record at the current position.
    private void writeDoubleRecord(int metadataId, double val) {
        final int start = mParcel.dataPosition();
        mParcel.writeInt(-1);  // Placeholder for the length
        mParcel.writeInt(metadataId);
        mParcel.writeInt(Metadata.DOUBLE_VAL);
        mParcel.writeDouble(val);
        adjustSize(start);
    }

    // Insert a ByteArray record at the current position.
    private void writeByteArrayRecord(int metadataId, byte[] val) {
        final int start = mParcel.dataPosition();
        mParcel.writeInt(-1);  // Placeholder for the length
        mParcel.writeInt(metadataId);
        mParcel.writeInt(Metadata.BYTE_ARRAY_VAL);
        mParcel.writeByteArray(val);
        adjustSize(start);
    }

    // Insert a Date record at the current position.
    private void writeDateRecord(int metadataId, long time, String tz) {
        final int start = mParcel.dataPosition();
        mParcel.writeInt(-1);  // Placeholder for the length
        mParcel.writeInt(metadataId);
        mParcel.writeInt(Metadata.DATE_VAL);
        mParcel.writeLong(time);
        mParcel.writeString(tz);
        adjustSize(start);
    }
}
