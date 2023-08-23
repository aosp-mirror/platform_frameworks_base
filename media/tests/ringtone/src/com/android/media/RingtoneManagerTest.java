/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.media;

import static com.google.android.mms.ContentType.AUDIO_MP3;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.vibrator.persistence.VibrationXmlParser;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.framework.base.media.ringtone.tests.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(Parameterized.class)
public class RingtoneManagerTest {
    @RingtoneManager.MediaType
    private final int mMediaType;
    private final List<Uri> mAddedFilesUri;
    private Context mContext;
    private RingtoneManager mRingtoneManager;
    private long mTimestamp;

    @Parameterized.Parameters(name = "media = {0}")
    public static Iterable<?> data() {
        return Arrays.asList(Ringtone.MEDIA_SOUND, Ringtone.MEDIA_VIBRATION);
    }

    public RingtoneManagerTest(@RingtoneManager.MediaType int mediaType) {
        mMediaType = mediaType;
        mAddedFilesUri = new ArrayList<>();
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mTimestamp = SystemClock.uptimeMillis();
        mRingtoneManager = new RingtoneManager(mContext);
        mRingtoneManager.setMediaType(mMediaType);
    }

    @After
    public void tearDown() {
        // Clean up media store
        for (Uri fileUri : mAddedFilesUri) {
            mContext.getContentResolver().delete(fileUri, null);
        }
    }

    @Test
    public void testSetMediaType_withValidValue_setsMediaCorrectly() {
        mRingtoneManager.setMediaType(mMediaType);
        assertThat(mRingtoneManager.getMediaType()).isEqualTo(mMediaType);
    }

    @Test
    public void testSetMediaType_withInvalidValue_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> mRingtoneManager.setMediaType(999));
    }

    @Test
    public void testSetMediaType_afterCallingGetCursor_throwsException() {
        mRingtoneManager.getCursor();
        assertThrows(IllegalStateException.class, () -> mRingtoneManager.setMediaType(mMediaType));
    }

    @Test
    public void testGetRingtone_ringtoneHasCorrectTitle() throws Exception {
        String fileName = generateUniqueFileName("new_file");
        Ringtone ringtone = addNewRingtoneToMediaStore(mRingtoneManager, fileName);

        assertThat(ringtone.getTitle(mContext)).isEqualTo(fileName);
    }

    @Test
    public void testGetRingtone_ringtoneCanBePlayedAndStopped() throws Exception {
        //TODO(b/261571543) Remove this assumption once we support playing vibrations.
        assumeTrue(mMediaType == Ringtone.MEDIA_SOUND);
        String fileName = generateUniqueFileName("new_file");
        Ringtone ringtone = addNewRingtoneToMediaStore(mRingtoneManager, fileName);

        ringtone.play();
        assertThat(ringtone.isPlaying()).isTrue();

        ringtone.stop();
        assertThat(ringtone.isPlaying()).isFalse();
    }

    @Test
    public void testGetCursor_withDifferentMedia_returnsCorrectCursor() throws Exception {
        RingtoneManager audioRingtoneManager = new RingtoneManager(mContext);
        String audioFileName = generateUniqueFileName("ringtone");
        addNewRingtoneToMediaStore(audioRingtoneManager, audioFileName);

        RingtoneManager vibrationRingtoneManager = new RingtoneManager(mContext);
        vibrationRingtoneManager.setMediaType(Ringtone.MEDIA_VIBRATION);
        String vibrationFileName = generateUniqueFileName("vibration");
        addNewRingtoneToMediaStore(vibrationRingtoneManager, vibrationFileName);

        Cursor audioCursor = audioRingtoneManager.getCursor();
        Cursor vibrationCursor = vibrationRingtoneManager.getCursor();

        List<String> audioTitles = extractRecordTitles(audioCursor);
        List<String> vibrationTitles = extractRecordTitles(vibrationCursor);

        assertThat(audioTitles).contains(audioFileName);
        assertThat(audioTitles).doesNotContain(vibrationFileName);

        assertThat(vibrationTitles).contains(vibrationFileName);
        assertThat(vibrationTitles).doesNotContain(audioFileName);
    }

    private List<String> extractRecordTitles(Cursor cursor) {
        List<String> titles = new ArrayList<>();

        if (cursor.moveToFirst()) {
            do {
                String title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX);
                titles.add(title);
            } while (cursor.moveToNext());
        }

        return titles;
    }

    private Ringtone addNewRingtoneToMediaStore(RingtoneManager ringtoneManager, String fileName)
            throws Exception {
        Uri fileUri = ringtoneManager.getMediaType() == Ringtone.MEDIA_SOUND ? addAudioFile(
                fileName) : addVibrationFile(fileName);
        mAddedFilesUri.add(fileUri);

        int ringtonePosition = ringtoneManager.getRingtonePosition(fileUri);
        Ringtone ringtone = ringtoneManager.getRingtone(ringtonePosition);
        // Validate this is the expected ringtone.
        assertThat(ringtone.getUri()).isEqualTo(fileUri);
        return ringtone;
    }

    private Uri addAudioFile(String fileName) throws Exception {
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName + ".mp3");
        contentValues.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES);
        contentValues.put(MediaStore.Audio.Media.MIME_TYPE, AUDIO_MP3);
        contentValues.put(MediaStore.Audio.Media.TITLE, fileName);
        contentValues.put(MediaStore.Audio.Media.IS_RINGTONE, 1);

        Uri contentUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                contentValues);
        writeRawDataToFile(resolver, contentUri, R.raw.test_sound_file);

        return resolver.canonicalizeOrElse(contentUri);
    }

    private Uri addVibrationFile(String fileName) throws Exception {
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName + ".ahv");
        contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS);
        contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE,
                VibrationXmlParser.APPLICATION_VIBRATION_XML_MIME_TYPE);
        contentValues.put(MediaStore.Files.FileColumns.TITLE, fileName);

        Uri contentUri = resolver.insert(MediaStore.Files.getContentUri(MediaStore
                .VOLUME_EXTERNAL), contentValues);
        writeRawDataToFile(resolver, contentUri, R.raw.test_haptic_file);

        return resolver.canonicalizeOrElse(contentUri);
    }

    private void writeRawDataToFile(ContentResolver resolver, Uri contentUri, int rawResource)
            throws Exception {
        try (ParcelFileDescriptor pfd =
                     resolver.openFileDescriptor(contentUri, "w", null)) {
            InputStream inputStream = mContext.getResources().openRawResource(rawResource);
            FileOutputStream outputStream = new FileOutputStream(pfd.getFileDescriptor());
            outputStream.write(inputStream.readAllBytes());

            inputStream.close();
            outputStream.flush();
            outputStream.close();

        } catch (Exception e) {
            throw new Exception("Failed to write data to file", e);
        }
    }

    private String generateUniqueFileName(String prefix) {
        return TextUtils.formatSimple("%s_%d", prefix, mTimestamp);
    }

}
