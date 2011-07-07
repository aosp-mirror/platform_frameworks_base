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


package android.media.videoeditor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import java.util.ArrayList;
import android.media.videoeditor.MediaArtistNativeHelper.ClipSettings;
import android.media.videoeditor.MediaArtistNativeHelper.EditSettings;
import android.media.videoeditor.MediaArtistNativeHelper.FileType;
import android.media.videoeditor.MediaArtistNativeHelper.Properties;
import android.util.Log;
import android.util.Pair;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.lang.Math;
import java.util.List;

/**
 * This class represents an image item on the storyboard. Note that images are
 * scaled down to the maximum supported resolution by preserving the native
 * aspect ratio. To learn the scaled image dimensions use
 * {@link #getScaledWidth()} and {@link #getScaledHeight()} respectively.
 *
 * {@hide}
 */
public class MediaImageItem extends MediaItem {
    /**
     *  Logging
     */
    private static final String TAG = "MediaImageItem";

    /**
     *  The resize paint
     */
    private static final Paint sResizePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /**
     *  Instance variables
     */
    private final int mWidth;
    private final int mHeight;
    private final int mAspectRatio;
    private long mDurationMs;
    private int mScaledWidth, mScaledHeight;
    private String mScaledFilename;
    private final VideoEditorImpl mVideoEditor;
    private String mDecodedFilename;
    private int mGeneratedClipHeight;
    private int mGeneratedClipWidth;
    private String mFileName;

    private final MediaArtistNativeHelper mMANativeHelper;

    /**
     * This class cannot be instantiated by using the default constructor
     */
    @SuppressWarnings("unused")
    private MediaImageItem() throws IOException {
        this(null, null, null, 0, RENDERING_MODE_BLACK_BORDER);
    }

    /**
     * Constructor
     *
     * @param editor The video editor reference
     * @param mediaItemId The media item id
     * @param filename The image file name
     * @param durationMs The duration of the image on the storyboard
     * @param renderingMode The rendering mode
     *
     * @throws IOException
     */
    public MediaImageItem(VideoEditor editor, String mediaItemId, String filename, long durationMs,
        int renderingMode) throws IOException {

        super(editor, mediaItemId, filename, renderingMode);

        mMANativeHelper = ((VideoEditorImpl)editor).getNativeContext();
        mVideoEditor = ((VideoEditorImpl)editor);
        try {
            final Properties properties = mMANativeHelper.getMediaProperties(filename);

            switch (mMANativeHelper.getFileType(properties.fileType)) {
                case MediaProperties.FILE_JPEG:
                case MediaProperties.FILE_PNG: {
                    break;
                }

                default: {
                    throw new IllegalArgumentException("Unsupported Input File Type");
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported file or file not found: " + filename);
        }
        mFileName = filename;
        /**
         *  Determine the dimensions of the image
         */
        final BitmapFactory.Options dbo = new BitmapFactory.Options();
        dbo.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filename, dbo);

        mWidth = dbo.outWidth;
        mHeight = dbo.outHeight;
        mDurationMs = durationMs;
        mDecodedFilename = String.format(mMANativeHelper.getProjectPath() +
                "/" + "decoded" + getId()+ ".rgb");

        try {
            mAspectRatio = mMANativeHelper.getAspectRatio(mWidth, mHeight);
        } catch(IllegalArgumentException e) {
            throw new IllegalArgumentException ("Null width and height");
        }

        mGeneratedClipHeight = 0;
        mGeneratedClipWidth = 0;

        /**
         *  Images are stored in memory scaled to the maximum resolution to
         *  save memory.
         */
        final Pair<Integer, Integer>[] resolutions =
            MediaProperties.getSupportedResolutions(mAspectRatio);

        /**
         *  Get the highest resolution
         */
        final Pair<Integer, Integer> maxResolution = resolutions[resolutions.length - 1];

        final Bitmap imageBitmap;

        if (mHeight > maxResolution.second) {
            /**
             *  We need to scale the image
             */
            imageBitmap = scaleImage(filename, maxResolution.first,
                                                         maxResolution.second);
            mScaledFilename = String.format(mMANativeHelper.getProjectPath() +
                    "/" + "scaled" + getId()+ ".JPG");
            if (!((new File(mScaledFilename)).exists())) {
                super.mRegenerateClip = true;
                final FileOutputStream f1 = new FileOutputStream(mScaledFilename);
                imageBitmap.compress(Bitmap.CompressFormat.JPEG, 50,f1);
                f1.close();
            }
            mScaledWidth =  (imageBitmap.getWidth() >> 1) << 1;
            mScaledHeight = (imageBitmap.getHeight() >> 1) << 1;
        } else {
            mScaledFilename = filename;
            mScaledWidth =  (mWidth >> 1) << 1;
            mScaledHeight = (mHeight >> 1) << 1;
            imageBitmap = BitmapFactory.decodeFile(mScaledFilename);
        }
        int newWidth = mScaledWidth;
        int newHeight = mScaledHeight;
        if (!((new File(mDecodedFilename)).exists())) {
            final FileOutputStream fl = new FileOutputStream(mDecodedFilename);
            final DataOutputStream dos = new DataOutputStream(fl);
            final int [] framingBuffer = new int[newWidth];
            final ByteBuffer byteBuffer = ByteBuffer.allocate(framingBuffer.length * 4);
            IntBuffer intBuffer;
            final byte[] array = byteBuffer.array();
            int tmp = 0;
            while (tmp < newHeight) {
                imageBitmap.getPixels(framingBuffer, 0, mScaledWidth, 0,
                                                        tmp, newWidth, 1);
                intBuffer = byteBuffer.asIntBuffer();
                intBuffer.put(framingBuffer, 0, newWidth);
                dos.write(array);
                tmp += 1;
            }
            fl.close();
        }
        imageBitmap.recycle();
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getFileType() {
        if (mFilename.endsWith(".jpg") || mFilename.endsWith(".jpeg")
                || mFilename.endsWith(".JPG") || mFilename.endsWith(".JPEG")) {
            return MediaProperties.FILE_JPEG;
        } else if (mFilename.endsWith(".png") || mFilename.endsWith(".PNG")) {
            return MediaProperties.FILE_PNG;
        } else {
            return MediaProperties.FILE_UNSUPPORTED;
        }
    }

    /**
     * @return The scaled image file name
     */
    String getScaledImageFileName() {
        return mScaledFilename;
    }

    /**
     * @return The generated Kenburns clip height.
     */
    int getGeneratedClipHeight() {
        return mGeneratedClipHeight;
    }

    /**
     * @return The generated Kenburns clip width.
     */
    int getGeneratedClipWidth() {
        return mGeneratedClipWidth;
    }

    /**
     * @return The file name of image which is decoded and stored
     * in RGB format
     */
    String getDecodedImageFileName() {
        return mDecodedFilename;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getWidth() {
        return mWidth;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getHeight() {
        return mHeight;
    }

    /**
     * @return The scaled width of the image.
     */
    public int getScaledWidth() {
        return mScaledWidth;
    }

    /**
     * @return The scaled height of the image.
     */
    public int getScaledHeight() {
        return mScaledHeight;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getAspectRatio() {
        return mAspectRatio;
    }

    /**
     * This method will adjust the duration of bounding transitions, effects
     * and overlays if the current duration of the transactions become greater
     * than the maximum allowable duration.
     *
     * @param durationMs The duration of the image in the storyboard timeline
     */
    public void setDuration(long durationMs) {
        if (durationMs == mDurationMs) {
            return;
        }

        mMANativeHelper.setGeneratePreview(true);

        /**
         * Invalidate the end transitions if necessary.
         * This invalidation is necessary for the case in which an effect or
         * an overlay is overlapping with the end transition
         * (before the duration is changed) and it no longer overlaps with the
         * transition after the duration is increased.
         *
         * The beginning transition does not need to be invalidated at this time
         * because an effect or an overlay overlaps with the beginning
         * transition, the begin transition is unaffected by a media item
         * duration change.
         */
        invalidateEndTransition();

        mDurationMs = durationMs;

        adjustTransitions();
        final List<Overlay> adjustedOverlays = adjustOverlays();
        final List<Effect> adjustedEffects = adjustEffects();

        /**
         * Invalidate the beginning and end transitions after adjustments.
         * This invalidation is necessary for the case in which an effect or
         * an overlay was not overlapping with the beginning or end transitions
         * before the setDuration reduces the duration of the media item and
         * causes an overlap of the beginning and/or end transition with the
         * effect.
         */
        invalidateBeginTransition(adjustedEffects, adjustedOverlays);
        invalidateEndTransition();
        if (getGeneratedImageClip() != null) {
            /*
             *  Delete the file
             */
            new File(getGeneratedImageClip()).delete();
            /*
             *  Invalidate the filename
             */
            setGeneratedImageClip(null);
            super.setRegenerateClip(true);
        }
        mVideoEditor.updateTimelineDuration();
    }

    /**
     * Invalidate the begin transition if any effects and overlays overlap
     * with the begin transition.
     *
     * @param effects List of effects to check for transition overlap
     * @param overlays List of overlays to check for transition overlap
     */
    private void invalidateBeginTransition(List<Effect> effects, List<Overlay> overlays) {
        if (mBeginTransition != null && mBeginTransition.isGenerated()) {
            final long transitionDurationMs = mBeginTransition.getDuration();

            /**
             *  The begin transition must be invalidated if it overlaps with
             *  an effect.
             */
            for (Effect effect : effects) {
                /**
                 *  Check if the effect overlaps with the begin transition
                 */
                if (effect.getStartTime() < transitionDurationMs) {
                    mBeginTransition.invalidate();
                    break;
                }
            }

            if (mBeginTransition.isGenerated()) {
                /**
                 *  The end transition must be invalidated if it overlaps with
                 *  an overlay.
                 */
                for (Overlay overlay : overlays) {
                    /**
                     *  Check if the overlay overlaps with the end transition
                     */
                    if (overlay.getStartTime() < transitionDurationMs) {
                        mBeginTransition.invalidate();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Invalidate the end transition if any effects and overlays overlap
     * with the end transition.
     */
    private void invalidateEndTransition() {
        if (mEndTransition != null && mEndTransition.isGenerated()) {
            final long transitionDurationMs = mEndTransition.getDuration();

            /**
             *  The end transition must be invalidated if it overlaps with
             *  an effect.
             */
            final List<Effect> effects = getAllEffects();
            for (Effect effect : effects) {
                /**
                 *  Check if the effect overlaps with the end transition
                 */
                if (effect.getStartTime() + effect.getDuration() >
                    mDurationMs - transitionDurationMs) {
                    mEndTransition.invalidate();
                    break;
                }
            }

            if (mEndTransition.isGenerated()) {
                /**
                 *  The end transition must be invalidated if it overlaps with
                 *  an overlay.
                 */
                final List<Overlay> overlays = getAllOverlays();
                for (Overlay overlay : overlays) {
                    /**
                     *  Check if the overlay overlaps with the end transition
                     */
                    if (overlay.getStartTime() + overlay.getDuration() >
                        mDurationMs - transitionDurationMs) {
                        mEndTransition.invalidate();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Adjust the start time and/or duration of effects.
     *
     * @return The list of effects which were adjusted
     */
    private List<Effect> adjustEffects() {
        final List<Effect> adjustedEffects = new ArrayList<Effect>();
        final List<Effect> effects = getAllEffects();
        for (Effect effect : effects) {
            /**
             *  Adjust the start time if necessary
             */
            final long effectStartTimeMs;
            if (effect.getStartTime() > getDuration()) {
                effectStartTimeMs = 0;
            } else {
                effectStartTimeMs = effect.getStartTime();
            }

            /**
             *  Adjust the duration if necessary
             */
            final long effectDurationMs;
            if (effectStartTimeMs + effect.getDuration() > getDuration()) {
                effectDurationMs = getDuration() - effectStartTimeMs;
            } else {
                effectDurationMs = effect.getDuration();
            }

            if (effectStartTimeMs != effect.getStartTime() ||
                    effectDurationMs != effect.getDuration()) {
                effect.setStartTimeAndDuration(effectStartTimeMs, effectDurationMs);
                adjustedEffects.add(effect);
            }
        }

        return adjustedEffects;
    }

    /**
     * Adjust the start time and/or duration of overlays.
     *
     * @return The list of overlays which were adjusted
     */
    private List<Overlay> adjustOverlays() {
        final List<Overlay> adjustedOverlays = new ArrayList<Overlay>();
        final List<Overlay> overlays = getAllOverlays();
        for (Overlay overlay : overlays) {
            /**
             *  Adjust the start time if necessary
             */
            final long overlayStartTimeMs;
            if (overlay.getStartTime() > getDuration()) {
                overlayStartTimeMs = 0;
            } else {
                overlayStartTimeMs = overlay.getStartTime();
            }

            /**
             *  Adjust the duration if necessary
             */
            final long overlayDurationMs;
            if (overlayStartTimeMs + overlay.getDuration() > getDuration()) {
                overlayDurationMs = getDuration() - overlayStartTimeMs;
            } else {
                overlayDurationMs = overlay.getDuration();
            }

            if (overlayStartTimeMs != overlay.getStartTime() ||
                    overlayDurationMs != overlay.getDuration()) {
                overlay.setStartTimeAndDuration(overlayStartTimeMs, overlayDurationMs);
                adjustedOverlays.add(overlay);
            }
        }

        return adjustedOverlays;
    }
    /**
     * This function get the proper width by given aspect ratio
     * and height.
     *
     * @param aspectRatio  Given aspect ratio
     * @param height  Given height
     */
    private int getWidthByAspectRatioAndHeight(int aspectRatio, int height) {
        int width = 0;

        switch (aspectRatio) {
            case MediaProperties.ASPECT_RATIO_3_2:
                if (height == MediaProperties.HEIGHT_480)
                    width = 720;
                else if (height == MediaProperties.HEIGHT_720)
                    width = 1080;
                break;

            case MediaProperties.ASPECT_RATIO_16_9:
                if (height == MediaProperties.HEIGHT_360)
                    width = 640;
                else if (height == MediaProperties.HEIGHT_480)
                    width = 854;
                else if (height == MediaProperties.HEIGHT_720)
                    width = 1280;
                else if (height == MediaProperties.HEIGHT_1080)
                    width = 1920;
                break;

            case MediaProperties.ASPECT_RATIO_4_3:
                if (height == MediaProperties.HEIGHT_480)
                    width = 640;
                if (height == MediaProperties.HEIGHT_720)
                    width = 960;
                break;

            case MediaProperties.ASPECT_RATIO_5_3:
                if (height == MediaProperties.HEIGHT_480)
                    width = 800;
                break;

            case MediaProperties.ASPECT_RATIO_11_9:
                if (height == MediaProperties.HEIGHT_144)
                    width = 176;
                break;

            default : {
                throw new IllegalArgumentException(
                    "Illegal arguments for aspectRatio");
            }
        }

        return width;
    }

    /**
     * This function sets the Ken Burn effect generated clip
     * name.
     *
     * @param generatedFilePath The name of the generated clip
     */
    @Override
    void setGeneratedImageClip(String generatedFilePath) {
        super.setGeneratedImageClip(generatedFilePath);

        // set the Kenburns clip width and height
        mGeneratedClipHeight = getScaledHeight();
        mGeneratedClipWidth = getWidthByAspectRatioAndHeight(
                mVideoEditor.getAspectRatio(), mGeneratedClipHeight);
    }

    /**
     * @return The name of the image clip
     * generated with ken burns effect.
     */
    @Override
    String getGeneratedImageClip() {
        return super.getGeneratedImageClip();
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public long getDuration() {
        return mDurationMs;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public long getTimelineDuration() {
        return mDurationMs;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public Bitmap getThumbnail(int width, int height, long timeMs) throws IOException {
        if (getGeneratedImageClip() != null) {
            return mMANativeHelper.getPixels(getGeneratedImageClip(),
                width, height,timeMs);
        } else {
            return scaleImage(mFilename, width, height);
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public Bitmap[] getThumbnailList(int width, int height, long startMs, long endMs,
        int thumbnailCount) throws IOException {
        //KenBurns was not applied on this.
        if (getGeneratedImageClip() == null) {
            final Bitmap thumbnail = scaleImage(mFilename, width, height);
            final Bitmap[] thumbnailArray = new Bitmap[thumbnailCount];
            for (int i = 0; i < thumbnailCount; i++) {
                thumbnailArray[i] = thumbnail;
            }

            return thumbnailArray;
        } else {
            if (startMs > endMs) {
                throw new IllegalArgumentException("Start time is greater than end time");
            }

            if (endMs > mDurationMs) {
                throw new IllegalArgumentException("End time is greater than file duration");
            }

            if (startMs == endMs) {
                Bitmap[] bitmap = new Bitmap[1];
                bitmap[0] = mMANativeHelper.getPixels(getGeneratedImageClip(),
                    width, height,startMs);
                return bitmap;
            }

            return mMANativeHelper.getPixelsList(getGeneratedImageClip(), width,
                height,startMs,endMs,thumbnailCount);
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    void invalidateTransitions(long startTimeMs, long durationMs) {
        /**
         *  Check if the item overlaps with the beginning and end transitions
         */
        if (mBeginTransition != null) {
            if (isOverlapping(startTimeMs, durationMs, 0, mBeginTransition.getDuration())) {
                mBeginTransition.invalidate();
            }
        }

        if (mEndTransition != null) {
            final long transitionDurationMs = mEndTransition.getDuration();
            if (isOverlapping(startTimeMs, durationMs,
                    getDuration() - transitionDurationMs, transitionDurationMs)) {
                mEndTransition.invalidate();
            }
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    void invalidateTransitions(long oldStartTimeMs, long oldDurationMs, long newStartTimeMs,
            long newDurationMs) {
        /**
         *  Check if the item overlaps with the beginning and end transitions
         */
        if (mBeginTransition != null) {
            final long transitionDurationMs = mBeginTransition.getDuration();
            final boolean oldOverlap = isOverlapping(oldStartTimeMs, oldDurationMs, 0,
                    transitionDurationMs);
            final boolean newOverlap = isOverlapping(newStartTimeMs, newDurationMs, 0,
                    transitionDurationMs);
            /**
             * Invalidate transition if:
             *
             * 1. New item overlaps the transition, the old one did not
             * 2. New item does not overlap the transition, the old one did
             * 3. New and old item overlap the transition if begin or end
             * time changed
             */
            if (newOverlap != oldOverlap) { // Overlap has changed
                mBeginTransition.invalidate();
            } else if (newOverlap) { // Both old and new overlap
                if ((oldStartTimeMs != newStartTimeMs) ||
                        !(oldStartTimeMs + oldDurationMs > transitionDurationMs &&
                        newStartTimeMs + newDurationMs > transitionDurationMs)) {
                    mBeginTransition.invalidate();
                }
            }
        }

        if (mEndTransition != null) {
            final long transitionDurationMs = mEndTransition.getDuration();
            final boolean oldOverlap = isOverlapping(oldStartTimeMs, oldDurationMs,
                    mDurationMs - transitionDurationMs, transitionDurationMs);
            final boolean newOverlap = isOverlapping(newStartTimeMs, newDurationMs,
                    mDurationMs - transitionDurationMs, transitionDurationMs);
            /**
             * Invalidate transition if:
             *
             * 1. New item overlaps the transition, the old one did not
             * 2. New item does not overlap the transition, the old one did
             * 3. New and old item overlap the transition if begin or end
             * time changed
             */
            if (newOverlap != oldOverlap) { // Overlap has changed
                mEndTransition.invalidate();
            } else if (newOverlap) { // Both old and new overlap
                if ((oldStartTimeMs + oldDurationMs != newStartTimeMs + newDurationMs) ||
                        ((oldStartTimeMs > mDurationMs - transitionDurationMs) ||
                        newStartTimeMs > mDurationMs - transitionDurationMs)) {
                    mEndTransition.invalidate();
                }
            }
        }
    }

    /**
     * This function invalidates the rgb image clip,ken burns effect clip,
     * and scaled image clip
     */
    void invalidate() {
        if (getGeneratedImageClip() != null) {
            new File(getGeneratedImageClip()).delete();
            setGeneratedImageClip(null);
            setRegenerateClip(true);
        }

        if (mScaledFilename != null) {
            if(mFileName != mScaledFilename) {
                new File(mScaledFilename).delete();
            }
            mScaledFilename = null;
        }

        if (mDecodedFilename != null) {
            new File(mDecodedFilename).delete();
            mDecodedFilename = null;
        }
    }

    /**
     * @param KenBurnEffect object.
     * @return an Object of {@link ClipSettings} with Ken Burn settings
     * needed to generate the clip
     */
    private ClipSettings getKenBurns(EffectKenBurns effectKB) {
        int PanZoomXa;
        int PanZoomXb;
        int width = 0, height = 0;
        Rect start = new Rect();
        Rect end = new Rect();
        ClipSettings clipSettings = null;
        clipSettings = new ClipSettings();
        /**
         *  image:
        ---------------------------------------
       |    Xa                                  |
       | Ya ---------------                     |
       |    |                |                  |
       |    |                |                  |
       |     ---------------    Xb       ratioB |
       |        ratioA           -------        |
       |                  Yb    |        |      |
       |                        |        |      |
       |                         -------        |
        ---------------------------------------
         */

        effectKB.getKenBurnsSettings(start, end);
        width = getWidth();
        height = getHeight();
        if ((start.left < 0) || (start.left > width) || (start.right < 0) || (start.right > width)
                || (start.top < 0) || (start.top > height) || (start.bottom < 0)
                || (start.bottom > height) || (end.left < 0) || (end.left > width)
                || (end.right < 0) || (end.right > width) || (end.top < 0) || (end.top > height)
                || (end.bottom < 0) || (end.bottom > height)) {
            throw new IllegalArgumentException("Illegal arguments for KebBurns");
        }

        if (((width - (start.right - start.left) == 0) || (height - (start.bottom - start.top) == 0))
                && ((width - (end.right - end.left) == 0) || (height - (end.bottom - end.top) == 0))) {
            setRegenerateClip(false);
            clipSettings.clipPath = getDecodedImageFileName();
            clipSettings.fileType = FileType.JPG;
            clipSettings.beginCutTime = 0;
            clipSettings.endCutTime = (int)getTimelineDuration();
            clipSettings.beginCutPercent = 0;
            clipSettings.endCutPercent = 0;
            clipSettings.panZoomEnabled = false;
            clipSettings.panZoomPercentStart = 0;
            clipSettings.panZoomTopLeftXStart = 0;
            clipSettings.panZoomTopLeftYStart = 0;
            clipSettings.panZoomPercentEnd = 0;
            clipSettings.panZoomTopLeftXEnd = 0;
            clipSettings.panZoomTopLeftYEnd = 0;
            clipSettings.mediaRendering = mMANativeHelper
            .getMediaItemRenderingMode(getRenderingMode());

            clipSettings.rgbWidth = getScaledWidth();
            clipSettings.rgbHeight = getScaledHeight();

            return clipSettings;
        }

        PanZoomXa = (1000 * start.width()) / width;
        PanZoomXb = (1000 * end.width()) / width;

        clipSettings.clipPath = getDecodedImageFileName();
        clipSettings.fileType = mMANativeHelper.getMediaItemFileType(getFileType());
        clipSettings.beginCutTime = 0;
        clipSettings.endCutTime = (int)getTimelineDuration();
        clipSettings.beginCutPercent = 0;
        clipSettings.endCutPercent = 0;
        clipSettings.panZoomEnabled = true;
        clipSettings.panZoomPercentStart = PanZoomXa;
        clipSettings.panZoomTopLeftXStart = (start.left * 1000) / width;
        clipSettings.panZoomTopLeftYStart = (start.top * 1000) / height;
        clipSettings.panZoomPercentEnd = PanZoomXb;
        clipSettings.panZoomTopLeftXEnd = (end.left * 1000) / width;
        clipSettings.panZoomTopLeftYEnd = (end.top * 1000) / height;
        clipSettings.mediaRendering
            = mMANativeHelper.getMediaItemRenderingMode(getRenderingMode());

        clipSettings.rgbWidth = getScaledWidth();
        clipSettings.rgbHeight = getScaledHeight();

        return clipSettings;
    }


    /**
     * @param KenBurnEffect object.
     * @return an Object of {@link ClipSettings} with Ken Burns
     * generated clip name
     */
    ClipSettings generateKenburnsClip(EffectKenBurns effectKB) {
        EditSettings editSettings = new EditSettings();
        editSettings.clipSettingsArray = new ClipSettings[1];
        String output = null;
        ClipSettings clipSettings = new ClipSettings();
        initClipSettings(clipSettings);
        editSettings.clipSettingsArray[0] = getKenBurns(effectKB);
        if ((getGeneratedImageClip() == null) && (getRegenerateClip())) {
            output = mMANativeHelper.generateKenBurnsClip(editSettings, this);
            setGeneratedImageClip(output);
            setRegenerateClip(false);
            clipSettings.clipPath = output;
            clipSettings.fileType = FileType.THREE_GPP;

            mGeneratedClipHeight = getScaledHeight();
            mGeneratedClipWidth = getWidthByAspectRatioAndHeight(
                    mVideoEditor.getAspectRatio(), mGeneratedClipHeight);
        } else {
            if (getGeneratedImageClip() == null) {
                clipSettings.clipPath = getDecodedImageFileName();
                clipSettings.fileType = FileType.JPG;

                clipSettings.rgbWidth = getScaledWidth();
                clipSettings.rgbHeight = getScaledHeight();

            } else {
                clipSettings.clipPath = getGeneratedImageClip();
                clipSettings.fileType = FileType.THREE_GPP;
            }
        }
        clipSettings.mediaRendering = mMANativeHelper.getMediaItemRenderingMode(getRenderingMode());
        clipSettings.beginCutTime = 0;
        clipSettings.endCutTime = (int)getTimelineDuration();

        return clipSettings;
    }

    /**
     * @return an Object of {@link ClipSettings} with Image Clip
     * properties data populated.If the image has Ken Burns effect applied,
     * then file path contains generated image clip name with Ken Burns effect
     */
    ClipSettings getImageClipProperties() {
        ClipSettings clipSettings = new ClipSettings();
        List<Effect> effects = null;
        EffectKenBurns effectKB = null;
        boolean effectKBPresent = false;

        effects = getAllEffects();
        for (Effect effect : effects) {
            if (effect instanceof EffectKenBurns) {
                effectKB = (EffectKenBurns)effect;
                effectKBPresent = true;
                break;
            }
        }

        if (effectKBPresent) {
            clipSettings = generateKenburnsClip(effectKB);
        } else {
            /**
             * Init the clip settings object
             */
            initClipSettings(clipSettings);
            clipSettings.clipPath = getDecodedImageFileName();
            clipSettings.fileType = FileType.JPG;
            clipSettings.beginCutTime = 0;
            clipSettings.endCutTime = (int)getTimelineDuration();
            clipSettings.mediaRendering = mMANativeHelper
                .getMediaItemRenderingMode(getRenderingMode());
            clipSettings.rgbWidth = getScaledWidth();
            clipSettings.rgbHeight = getScaledHeight();

        }
        return clipSettings;
    }

    /**
     * Resize a bitmap to the specified width and height
     *
     * @param filename The filename
     * @param width The thumbnail width
     * @param height The thumbnail height
     *
     * @return The resized bitmap
     */
    private Bitmap scaleImage(String filename, int width, int height)
    throws IOException {
        final BitmapFactory.Options dbo = new BitmapFactory.Options();
        dbo.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filename, dbo);

        final int nativeWidth = dbo.outWidth;
        final int nativeHeight = dbo.outHeight;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "generateThumbnail: Input: " + nativeWidth + "x" + nativeHeight
                    + ", resize to: " + width + "x" + height);
        }

        final Bitmap srcBitmap;
        float bitmapWidth, bitmapHeight;
        if (nativeWidth > width || nativeHeight > height) {
            float dx = ((float)nativeWidth) / ((float)width);
            float dy = ((float)nativeHeight) / ((float)height);

            if (dx > dy) {
                bitmapWidth = width;

                if (((float)nativeHeight / dx) < (float)height) {
                    bitmapHeight = (float)Math.ceil(nativeHeight / dx);
                } else { // value equals the requested height
                    bitmapHeight = (float)Math.floor(nativeHeight / dx);
                }

            } else {
                if (((float)nativeWidth / dy) > (float)width) {
                    bitmapWidth = (float)Math.floor(nativeWidth / dy);
                } else { // value equals the requested width
                    bitmapWidth = (float)Math.ceil(nativeWidth / dy);
                }

                bitmapHeight = height;
            }

            /**
             *  Create the bitmap from file
             */
            if (nativeWidth / bitmapWidth > 1) {

                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = nativeWidth / (int)bitmapWidth;
                srcBitmap = BitmapFactory.decodeFile(filename, options);
            } else {
                srcBitmap = BitmapFactory.decodeFile(filename);
            }
        } else {
            bitmapWidth = width;
            bitmapHeight = height;
            srcBitmap = BitmapFactory.decodeFile(filename);

        }

        if (srcBitmap == null) {
            Log.e(TAG, "generateThumbnail: Cannot decode image bytes");
            throw new IOException("Cannot decode file: " + mFilename);
        }

        /**
         *  Create the canvas bitmap
         */
        final Bitmap bitmap = Bitmap.createBitmap((int)bitmapWidth,
                                                  (int)bitmapHeight,
                                                  Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(srcBitmap, new Rect(0, 0, srcBitmap.getWidth(),
                                              srcBitmap.getHeight()),
                                              new Rect(0, 0, (int)bitmapWidth,
                                              (int)bitmapHeight), sResizePaint);
        /**
         *  Release the source bitmap
         */
        srcBitmap.recycle();
        return bitmap;
    }
}
