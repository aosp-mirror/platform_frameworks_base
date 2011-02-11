/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.mediaframeworktest;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

import junit.framework.Assert;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.videoeditor.AudioTrack;
import android.media.videoeditor.EffectColor;
import android.media.videoeditor.MediaImageItem;
import android.media.videoeditor.MediaItem;
import android.media.videoeditor.MediaVideoItem;
import android.media.videoeditor.OverlayFrame;
import android.media.videoeditor.TransitionAlpha;
import android.media.videoeditor.TransitionCrossfade;
import android.media.videoeditor.TransitionFadeBlack;
import android.media.videoeditor.TransitionSliding;
import android.media.videoeditor.VideoEditor;
import android.media.videoeditor.VideoEditorFactory;
import android.util.Log;
import android.os.Environment;

/**
 * This class has the names of the all the activity name and variables in the
 * instrumentation test.
 */
public class VideoEditorHelper extends Assert {

    private final String TAG = "VideoEditorMediaNames";

    public VideoEditorHelper() {

    }

    public static final String PROJECT_LOCATION_COMMON =
        Environment.getExternalStorageDirectory().toString() + "/";

    public static final String INPUT_FILE_PATH_COMMON = PROJECT_LOCATION_COMMON +
        "media_api/videoeditor/";

    // -----------------------------------------------------------------
    // HELPER METHODS
    // -----------------------------------------------------------------

    /**
     * This method creates an object of VideoEditor
     *
     * @param projectPath the directory where all files related to project will
     *            be stored
     * @param className The class which implements the VideoEditor Class
     * @return the object of VideoEditor
     */
    public VideoEditor createVideoEditor(String projectPath) {
        VideoEditor mVideoEditor = null;
        try {
            mVideoEditor = VideoEditorFactory.create(projectPath);
            assertNotNull("VideoEditor", mVideoEditor);
        } catch (Exception e) {
            fail("Unable to create Video Editor");
        }
        return mVideoEditor;
    }

    /**
     *This method deletes the VideoEditor object created using
     * createVideoEditor method
     *
     * @param videoEditor the VideoEditor object which needs to be cleaned up
     */
    public void destroyVideoEditor(VideoEditor videoEditor) {
        // Release VideoEditor
        if (videoEditor != null) {
            try {
                videoEditor.release();
            } catch (Exception e) {
                fail("Unable to destory Video Editor");
            }
        }
    }

    /**
     *This Method checks the Range in "RangePercent" (say 10)
     *
     * @param int Expected data
     * @param actual data
     * @return boolean flag which confirms the range matching
     */
    public boolean checkRange(long expected, long actual, long rangePercent) {
        long range = 0;
        range = (100 * actual) / expected;

        Log.i("checkRange", "Range = " + range);
        if ((range > (100 - rangePercent)) && (range < (100 + rangePercent))) {
            return true;
        } else {
            return false;
        }
    }

    /**
     *This Method Creates a Bitmap with the given input file
     *
     * @param file the Input whose Bitmap has top be extracted
     * @return an Object of EffectColor
     */
    public Bitmap getBitmap(String file, int width, int height) throws IOException {
        assertNotNull("Bitmap File is Null", file);
        FileInputStream inputStream = null;
        Bitmap overlayBmp = null;
        if (!new File(file).exists())
            throw new IOException("File not Found " + file);
        try {
            final BitmapFactory.Options dbo = new BitmapFactory.Options();
            dbo.inJustDecodeBounds = true;
            dbo.outWidth = width;
            dbo.outHeight = height;
            File flPtr = new File(file);
            inputStream = new FileInputStream(flPtr);
            final Bitmap srcBitmap = BitmapFactory.decodeStream(inputStream);
            overlayBmp = Bitmap.createBitmap(srcBitmap);
            assertNotNull("Bitmap 1", srcBitmap);
            assertNotNull("Bitmap 2", overlayBmp);
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return overlayBmp;
    }

    /**
     *This Method Create a Media Video Item with the specified params
     *
     * @return an Object of MediaVideoItem
     */
    public MediaVideoItem createMediaItem(VideoEditor videoEditor,
        String MediaId, String filename, int renderingMode) {
        MediaVideoItem mvi = null;
        try {
            mvi = new MediaVideoItem(videoEditor, MediaId, filename,
                renderingMode);
            assertNotNull("Can not create an object of MediaVideoItem", mvi);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException
                ("Can not create an object of Media Video Item with file name = "
                    + filename + " Issue = " + e.toString());
        } catch (IOException e) {
            assertTrue
                ("Can not create an object of Media Video Item with file name = "
                    + filename + " Issue = " + e.toString(), false);
        }
        return mvi;
    }

    /**
     *This Method Create a Media Image Item with the specified params
     *
     * @return an Object of MediaImageItem
     */
    public MediaImageItem createMediaItem(VideoEditor videoEditor,
        String MediaId, String filename, long duration, int renderingMode) {
        MediaImageItem mii = null;
        try {
            mii = new MediaImageItem(videoEditor, MediaId, filename, duration,
                renderingMode);
            assertNotNull("Can not create an object of MediaImageItem", mii);

        } catch (IllegalArgumentException e) {
            assertTrue("Can not create an object of Media Image with file name = "
                + filename + " Issue = " + e.toString(), false);
        } catch (IOException e) {
            assertTrue("Can not create an object of Media Image with file name = "
                + filename + " Issue = " + e.toString(), false);
        }
        return mii;
    }

    /**
     *This Method Create a Effect with the specified params
     *
     * @return an Object of EffectColor
     */
    public EffectColor createEffectItem(MediaItem mediaItem, String effectId,
        long startTime, long duration, int effectType, int colorType) {
        EffectColor effectonMVI = null;
        effectonMVI = new EffectColor(mediaItem, effectId, startTime,
            duration, effectType, colorType);
        return effectonMVI;
    }

    /**
     *This Method creates object of Type Transition Cross fade
     *
     * @return TransitionCrossfade object
     */
    public TransitionCrossfade createTCrossFade(String transitionId,
        MediaItem afterMediaItem, MediaItem beforeMediaItem, long durationMs,
        int behavior) {
        Log.i("TransitionCrossfade Details === ", "Transid ID = " + transitionId +
            " Duration= " + durationMs + " Behaviour " + behavior);

        TransitionCrossfade transitionCF = null;
            transitionCF = new TransitionCrossfade(transitionId, afterMediaItem,
                beforeMediaItem, durationMs, behavior);
        return transitionCF;
    }

    /**
     *This Method creates object of Type TransitionFadeBlack
     *
     * @return TransitionFadeBlack object
     */
    public TransitionFadeBlack createTFadeBlack(String transitionId,
        MediaItem afterMediaItem, MediaItem beforeMediaItem, long durationMs,
        int behavior) {
        TransitionFadeBlack transitionFB = null;

        transitionFB = new TransitionFadeBlack(transitionId, afterMediaItem,
            beforeMediaItem, durationMs, behavior);
        return transitionFB;
    }

    /**
     *This Method creates object of Type TransitionSliding
     *
     * @return TransitionSliding object
     */
    public TransitionSliding createTSliding(String transitionId,
        MediaItem afterMediaItem, MediaItem beforeMediaItem, long durationMs,
        int behavior, int direction) {
        TransitionSliding transSlide = null;
            transSlide = new TransitionSliding(transitionId, afterMediaItem,
                beforeMediaItem, durationMs, behavior, direction);
        return transSlide;
    }

    /**
     *This Method creates object of Type TranistionAlpha
     *
     * @return TranistionAlpha object
     */

    public TransitionAlpha createTAlpha(String transitionId,
        MediaItem afterMediaItem, MediaItem beforeMediaItem, long durationMs,
        int behavior, String maskFilename, int blendingPercent, boolean invert) {
        TransitionAlpha transA = null;
            transA = new TransitionAlpha(transitionId, afterMediaItem,
                beforeMediaItem, durationMs, behavior, maskFilename,
                blendingPercent, invert);
        return transA;
    }

    /**
     *This Method creates object of Type OverlayFrame
     *
     * @return OverlayFrame object
     */

    public OverlayFrame createOverlay(MediaItem mediaItem, String overlayId,
        Bitmap bitmap, long startTimeMs, long durationMs) {
        OverlayFrame overLayFrame = null;
        overLayFrame = new OverlayFrame(mediaItem, overlayId, bitmap,
                startTimeMs, durationMs);
        return overLayFrame;
    }

    /**
     *This Method creates object of Type AudioTrack
     *
     * @return OverlayFrame object
     */
    public AudioTrack createAudio(VideoEditor videoEditor, String audioTrackId,
        String filename) {
        AudioTrack audio = null;
        try {
            audio = new AudioTrack(videoEditor, audioTrackId, filename);
            assertNotNull("Cant not create an object of an  AudioTrack " +
                audioTrackId, audio);
        } catch (IllegalArgumentException e) {
            assertTrue("Can not create object of an AudioTrack " +
                audioTrackId + " Issue = " + e.toString(), false);
        } catch (IOException e) {
            assertTrue("Can not create object of an AudioTrack " +
                audioTrackId + " Issue = " + e.toString(), false);
        }
        return audio;
    }

    /**
     *This Method validates the Exported Movie,as per the specified params
     * during Export
     */

    public void validateExport(VideoEditor videoEditor, String fileName,
        int export_height, int startTime, long endTime, int vCodec, int aCodec) {
        File tempFile = new File(fileName);
        assertEquals("Exported FileName", tempFile.exists(), true);
        final MediaVideoItem mvi = createMediaItem(videoEditor, "m1", fileName,
            MediaItem.RENDERING_MODE_BLACK_BORDER);

        Log.i(TAG, "VideoCodec for file = " + fileName +
            "\tExpected Video Codec = " + vCodec + "\tActual Video Codec = " +
            mvi.getVideoType());
        assertEquals("Export: Video Codec Mismatch for file = " + fileName +
            "\t<expected> " + vCodec + "\t<actual> " + mvi.getVideoType(),
            vCodec, mvi.getVideoType());

        Log.i(TAG, "Height for file = " + fileName + "\tExpected Height = " +
            export_height + "\tActual VideoHeight = " + mvi.getHeight());
        assertEquals("Export height Mismatch for file " + fileName +
            "\t<expected> " + export_height + "\t<actual> " + mvi.getHeight(),
             export_height, mvi.getHeight());
        if (startTime == 0) {
            if (endTime != 0) {
                Log.i(TAG, "TimeLine Expected = " + (startTime + endTime) +
                    "\t VideoTime= " + mvi.getTimelineDuration());
                assertTrue("Timeline Duration Mismatch for file " + fileName +
                    "<expected> " + (startTime + endTime) + "\t<actual> " +
                    mvi.getTimelineDuration(), checkRange((startTime +
                        endTime), mvi.getTimelineDuration(), 10));
            }
        } else {
            Log.i(TAG, "TimeLine Expected = " + (endTime - startTime) +
                "\t VideoTime= " + mvi.getTimelineDuration());
            assertTrue("Timeline Duration Mismatch for file " + fileName +
                "<expected> " + (endTime - startTime) + "\t<actual> " +
                mvi.getTimelineDuration(), checkRange((endTime -
                    startTime), (int)mvi.getTimelineDuration(), 10));
        }
    }

    /**
     * @param videoEditor
     * @param fileName
     * @param export_bitrate
     * @param export_height
     * @param startTime
     * @param endTime
     * @param vCodec
     * @param aCodec
     */
    public void validateExport(VideoEditor videoEditor, String fileName,
        int export_height, int startTime, int endTime, int vCodec, int aCodec) {
        File tempFile = new File(fileName);
        assertEquals("Exported FileName", tempFile.exists(), true);
        final MediaVideoItem mvi = createMediaItem(videoEditor, "m1", fileName,
            MediaItem.RENDERING_MODE_BLACK_BORDER);
        Log.i(TAG, "VideoCodec for file = " + fileName +
            "\tExpected Video Codec = " + vCodec + "\tActual Video Codec = " +
            mvi.getVideoType());
        assertEquals("Export: Video Codec Mismatch for file = " + fileName +
            "\t<expected> " + vCodec + "\t<actual> " + mvi.getVideoType(),
            vCodec, mvi.getVideoType());

        Log.i(TAG, "AudioCodec for file = " + fileName +
            "\tExpected Audio Codec = " + aCodec + "\tActual Audio Codec = " +
            mvi.getAudioType());
        assertEquals("Export: Audio Codec Mismatch for file = " + fileName +
            "\t<expected> " + aCodec + "\t<actual> " + mvi.getAudioType(),
            aCodec, mvi.getAudioType());

        Log.i(TAG, "Height for file = " + fileName + "\tExpected Height = " +
            export_height + "\tActual VideoHeight = " + mvi.getHeight());
        assertEquals("Export: height Mismatch for file " + fileName +
            "\t<expected> " + export_height + "\t<actual> " + mvi.getHeight(),
            export_height, mvi.getHeight());
        if (startTime == 0) {
            if (endTime != 0) {
                Log.i(TAG, "TimeLine Expected = " + (startTime + endTime) +
                    "\t VideoTime= " + mvi.getTimelineDuration());
                assertTrue("Export :Timeline Duration Mismatch for file " +
                    fileName + "<expected> " + (startTime + endTime) +
                    "\t<actual> " + mvi.getTimelineDuration(),
                    checkRange((startTime + endTime), mvi.getTimelineDuration(), 10));
            }
        } else {
            Log.i(TAG, "TimeLine Expected = " + (endTime-startTime) +
                "\t VideoTime= " + mvi.getTimelineDuration());
            assertTrue("Timeline Duration Mismatch for file " + fileName +
                "<expected> " + (endTime - startTime) + "\t<actual> " +
                mvi.getTimelineDuration(), checkRange((endTime -
                    startTime), mvi.getTimelineDuration(), 10));
        }
    }

    /**
     * Check file and deletes it.
     *
     * @param filename
     */
    public void checkDeleteExistingFile(String filename) {
        Log.i(TAG, ">>>>>>>>>>>>>>>>>>checkDeleteExistingFile  = " + filename);
        if (filename != null) {
            File temp = new File(filename);
            if (temp != null && temp.exists()) {
                temp.delete();
            }
        }
    }

    /**
     * This method creates a Directory and filename
     *
     * @param location This is path where the file is to be created
     *            "/sdcard/Output/"
     * @return Path in form of /sdcard/Output/200910100000
     */
    public String createRandomFile(String location) {
        Random randomGenerator = new Random();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmssS");
        Date date = new Date();
        final String filePath = location + dateFormat.format(date) +
            randomGenerator.nextInt(10);
        Log.i(TAG, ">>>>>>>>>>>>>>>>createRandomFile  Location= " + location +
            "\t FilePath = " + filePath);
        return filePath;
    }

    /**
     * This method recursively deletes all the file and directory
     *
     * @param directory where the files are located Example = "/sdcard/Input"
     * @return boolean True if deletion is successful else False
     */
    public boolean deleteProject(File directory) {
        Log.i(TAG, ">>>>>>>>>>>>>>>>>>>>>>>>deleteProject  directory= " +
            directory.toString());
        if (directory.isDirectory()) {
            String[] filesInDirecory = directory.list();
            for (int i = 0; i < filesInDirecory.length; i++) {
                boolean success = deleteProject(new File(directory,
                    filesInDirecory[i]));
                if (!success) {
                    return false;
                }
            }
        }
        return directory.delete();
    }

    /**
     * This method compares the array of Integer from 0 - 100
     *
     * @param data set of integer values received as progress
     * @return true if sucess else false
     */
    public boolean checkProgressCBValues(int[] data) {
        boolean retFlag = false;
        for (int i = 0; i < 100; i++) {
            if (data[i] == 100) {
                retFlag = true;
                break;
            } else {
                retFlag = false;
            }
        }
        return retFlag;
    }
}
