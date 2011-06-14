/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.mediaframeworktest.functional;

import android.media.MediaMetadataRetriever;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import com.android.mediaframeworktest.MediaNames;
import com.android.mediaframeworktest.MediaProfileReader;
/**
 * This metadata test suite test the basic functionality of the 
 * MediaMetadataRetriever
 * 
 */
public class MediaMetadataTest extends AndroidTestCase {
    
    private static final String TAG = "MediaMetadataTest";

    public static enum METADATA_EXPECTEDRESULT{
        FILE_PATH,CD_TRACK, ALBUM,
        ARTIST, AUTHOR, COMPOSER,
        DATE, GENRE, TITLE,
        YEAR, DURATION, NUM_TRACKS, WRITER
    }
    
    public static enum MP3_TEST_FILE{
        ID3V1V2, ID3V2, ID3V1
    }
    
    public static METADATA_EXPECTEDRESULT meta;
    public static MP3_TEST_FILE mp3_test_file;
   
    @MediumTest
    public static void testID3V1V2Metadata() throws Exception {
        validateMetatData(mp3_test_file.ID3V1V2.ordinal(), MediaNames.META_DATA_MP3);
    }
    
    @MediumTest
    public static void testID3V2Metadata() throws Exception {
        validateMetatData(mp3_test_file.ID3V2.ordinal(), MediaNames.META_DATA_MP3);
    }
    
    @MediumTest
    public static void testID3V1Metadata() throws Exception {
        validateMetatData(mp3_test_file.ID3V1.ordinal(), MediaNames.META_DATA_MP3);
    }

    private static void validateMetatData(int fileIndex, String meta_data_file[][]) {
        Log.v(TAG, "filePath = "+ meta_data_file[fileIndex][0]);
        if ((meta_data_file[fileIndex][0].endsWith("wma") && !MediaProfileReader.getWMAEnable()) ||
            (meta_data_file[fileIndex][0].endsWith("wmv") && !MediaProfileReader.getWMVEnable())) {
            return;
        }
        String value = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(meta_data_file[fileIndex][0]);
        } catch(Exception e) {
            Log.v(TAG, "Failed: "+meta_data_file[fileIndex][0] + " " + e.toString());
            //Set the test case failure whenever it failed to setDataSource
            assertTrue("Failed to setDataSource ", false);
        }
        
        //METADATA_KEY_CD_TRACK_NUMBER should return the TCRK value
        value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER);
        Log.v(TAG, "CD_TRACK_NUMBER : " + value);
        assertEquals(TAG, meta_data_file[fileIndex][meta.CD_TRACK.ordinal()], value);
       
        value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
        Log.v(TAG, "Album : "+ value);
        assertEquals(TAG, meta_data_file[fileIndex][meta.ALBUM.ordinal()], value); 
        
        value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
        Log.v(TAG, "Artist : "+ value);
        assertEquals(TAG, meta_data_file[fileIndex][meta.ARTIST.ordinal()], value);
        
        value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR);
        Log.v(TAG, "Author : "+ value);
        assertEquals(TAG, meta_data_file[fileIndex][meta.AUTHOR.ordinal()], value);
        
        value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER);
        Log.v(TAG, "Composer : "+ value);
        assertEquals(TAG, meta_data_file[fileIndex][meta.COMPOSER.ordinal()], value);
        
        value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
        Log.v(TAG, "Date : "+ value);
        assertEquals(TAG, meta_data_file[fileIndex][meta.DATE.ordinal()], value);
        
        value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
        Log.v(TAG, "Genre : "+ value);
        assertEquals(TAG, meta_data_file[fileIndex][meta.GENRE.ordinal()], value);
        
        value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
        Log.v(TAG, "Title : "+ value);
        assertEquals(TAG, meta_data_file[fileIndex][meta.TITLE.ordinal()], value);
        
        value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
        Log.v(TAG, "Year : "+ value);
        assertEquals(TAG, meta_data_file[fileIndex][meta.YEAR.ordinal()], value);
        
        value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        Log.v(TAG, "Expected = " + meta_data_file[fileIndex][meta.DURATION.ordinal()] + "reult = " + value);
        // Only require that the returned duration is within 100ms of the expected
        // one as PV and stagefright differ slightly in their implementation.
        assertTrue(TAG, Math.abs(Integer.parseInt(
                        meta_data_file[fileIndex][meta.DURATION.ordinal()])
                            - Integer.parseInt(value)) < 100);
        
        //METADATA_KEY_NUM_TRACKS should return the total number of tracks in the media
        //include the video and audio
        value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS);
        Log.v(TAG, "Track : "+ value);
        assertEquals(TAG,meta_data_file[fileIndex][meta.NUM_TRACKS.ordinal()], value);
     
        value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER);
        Log.v(TAG, "Writer : "+ value);
        assertEquals(TAG,meta_data_file[fileIndex][meta.WRITER.ordinal()], value);

        retriever.release();        
    }
}
