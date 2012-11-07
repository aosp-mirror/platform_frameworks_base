/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import android.graphics.Bitmap;
import android.media.videoeditor.MediaArtistNativeHelper.ClipSettings;
import android.media.videoeditor.MediaArtistNativeHelper.FileType;
import android.media.videoeditor.MediaArtistNativeHelper.MediaRendering;

/**
 * This abstract class describes the base class for any MediaItem. Objects are
 * defined with a file path as a source data.
 * {@hide}
 */
public abstract class MediaItem {
    /**
     *  A constant which can be used to specify the end of the file (instead of
     *  providing the actual duration of the media item).
     */
    public final static int END_OF_FILE = -1;

    /**
     *  Rendering modes
     */
    /**
     * When using the RENDERING_MODE_BLACK_BORDER rendering mode video frames
     * are resized by preserving the aspect ratio until the movie matches one of
     * the dimensions of the output movie. The areas outside the resized video
     * clip are rendered black.
     */
    public static final int RENDERING_MODE_BLACK_BORDER = 0;

    /**
     * When using the RENDERING_MODE_STRETCH rendering mode video frames are
     * stretched horizontally or vertically to match the current aspect ratio of
     * the video editor.
     */
    public static final int RENDERING_MODE_STRETCH = 1;

    /**
     * When using the RENDERING_MODE_CROPPING rendering mode video frames are
     * scaled horizontally or vertically by preserving the original aspect ratio
     * of the media item.
     */
    public static final int RENDERING_MODE_CROPPING = 2;

    /**
     *  The unique id of the MediaItem
     */
    private final String mUniqueId;

    /**
     *  The name of the file associated with the MediaItem
     */
    protected final String mFilename;

    /**
     *  List of effects
     */
    private final List<Effect> mEffects;

    /**
     *  List of overlays
     */
    private final List<Overlay> mOverlays;

    /**
     *  The rendering mode
     */
    private int mRenderingMode;

    private final MediaArtistNativeHelper mMANativeHelper;

    private final String mProjectPath;

    /**
     *  Beginning and end transitions
     */
    protected Transition mBeginTransition;

    protected Transition mEndTransition;

    protected String mGeneratedImageClip;

    protected boolean mRegenerateClip;

    private boolean mBlankFrameGenerated = false;

    private String mBlankFrameFilename = null;

    /**
     * Constructor
     *
     * @param editor The video editor reference
     * @param mediaItemId The MediaItem id
     * @param filename name of the media file.
     * @param renderingMode The rendering mode
     * @throws IOException if file is not found
     * @throws IllegalArgumentException if a capability such as file format is
     *             not supported the exception object contains the unsupported
     *             capability
     */
    protected MediaItem(VideoEditor editor, String mediaItemId, String filename,
                        int renderingMode) throws IOException {
        if (filename == null) {
            throw new IllegalArgumentException("MediaItem : filename is null");
        }
        File file = new File(filename);
        if (!file.exists()) {
            throw new IOException(filename + " not found ! ");
        }

        /*Compare file_size with 2GB*/
        if (VideoEditor.MAX_SUPPORTED_FILE_SIZE <= file.length()) {
            throw new IllegalArgumentException("File size is more than 2GB");
        }
        mUniqueId = mediaItemId;
        mFilename = filename;
        mRenderingMode = renderingMode;
        mEffects = new ArrayList<Effect>();
        mOverlays = new ArrayList<Overlay>();
        mBeginTransition = null;
        mEndTransition = null;
        mMANativeHelper = ((VideoEditorImpl)editor).getNativeContext();
        mProjectPath = editor.getPath();
        mRegenerateClip = false;
        mGeneratedImageClip = null;
    }

    /**
     * @return The id of the media item
     */
    public String getId() {
        return mUniqueId;
    }

    /**
     * @return The media source file name
     */
    public String getFilename() {
        return mFilename;
    }

    /**
     * If aspect ratio of the MediaItem is different from the aspect ratio of
     * the editor then this API controls the rendering mode.
     *
     * @param renderingMode rendering mode. It is one of:
     *            {@link #RENDERING_MODE_BLACK_BORDER},
     *            {@link #RENDERING_MODE_STRETCH}
     */
    public void setRenderingMode(int renderingMode) {
        switch (renderingMode) {
            case RENDERING_MODE_BLACK_BORDER:
            case RENDERING_MODE_STRETCH:
            case RENDERING_MODE_CROPPING:
                break;

            default:
                throw new IllegalArgumentException("Invalid Rendering Mode");
        }

        mMANativeHelper.setGeneratePreview(true);

        mRenderingMode = renderingMode;
        if (mBeginTransition != null) {
            mBeginTransition.invalidate();
        }

        if (mEndTransition != null) {
            mEndTransition.invalidate();
        }

        for (Overlay overlay : mOverlays) {
            ((OverlayFrame)overlay).invalidateGeneratedFiles();
        }
    }

    /**
     * @return The rendering mode
     */
    public int getRenderingMode() {
        return mRenderingMode;
    }

    /**
     * @param transition The beginning transition
     */
    void setBeginTransition(Transition transition) {
        mBeginTransition = transition;
    }

    /**
     * @return The begin transition
     */
    public Transition getBeginTransition() {
        return mBeginTransition;
    }

    /**
     * @param transition The end transition
     */
    void setEndTransition(Transition transition) {
        mEndTransition = transition;
    }

    /**
     * @return The end transition
     */
    public Transition getEndTransition() {
        return mEndTransition;
    }

    /**
     * @return The timeline duration. This is the actual duration in the
     *         timeline (trimmed duration)
     */
    public abstract long getTimelineDuration();

    /**
     * @return The is the full duration of the media item (not trimmed)
     */
    public abstract long getDuration();

    /**
     * @return The source file type
     */
    public abstract int getFileType();

    /**
     * @return Get the native width of the media item
     */
    public abstract int getWidth();

    /**
     * @return Get the native height of the media item
     */
    public abstract int getHeight();

    /**
     * Get aspect ratio of the source media item.
     *
     * @return the aspect ratio as described in MediaProperties.
     *         MediaProperties.ASPECT_RATIO_UNDEFINED if aspect ratio is not
     *         supported as in MediaProperties
     */
    public abstract int getAspectRatio();

    /**
     * Add the specified effect to this media item.
     *
     * Note that certain types of effects cannot be applied to video and to
     * image media items. For example in certain implementation a Ken Burns
     * implementation cannot be applied to video media item.
     *
     * This method invalidates transition video clips if the
     * effect overlaps with the beginning and/or the end transition.
     *
     * @param effect The effect to apply
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if the effect start and/or duration are
     *      invalid or if the effect cannot be applied to this type of media
     *      item or if the effect id is not unique across all the Effects
     *      added.
     */
    public void addEffect(Effect effect) {

        if (effect == null) {
            throw new IllegalArgumentException("NULL effect cannot be applied");
        }

        if (effect.getMediaItem() != this) {
            throw new IllegalArgumentException("Media item mismatch");
        }

        if (mEffects.contains(effect)) {
            throw new IllegalArgumentException("Effect already exists: " + effect.getId());
        }

        if (effect.getStartTime() + effect.getDuration() > getDuration()) {
            throw new IllegalArgumentException(
            "Effect start time + effect duration > media clip duration");
        }

        mMANativeHelper.setGeneratePreview(true);

        mEffects.add(effect);

        invalidateTransitions(effect.getStartTime(), effect.getDuration());

        if (effect instanceof EffectKenBurns) {
            mRegenerateClip = true;
        }
    }

    /**
     * Remove the effect with the specified id.
     *
     * This method invalidates a transition video clip if the effect overlaps
     * with a transition.
     *
     * @param effectId The id of the effect to be removed
     *
     * @return The effect that was removed
     * @throws IllegalStateException if a preview or an export is in progress
     */
    public Effect removeEffect(String effectId) {
        for (Effect effect : mEffects) {
            if (effect.getId().equals(effectId)) {
                mMANativeHelper.setGeneratePreview(true);

                mEffects.remove(effect);

                invalidateTransitions(effect.getStartTime(), effect.getDuration());
                if (effect instanceof EffectKenBurns) {
                    if (mGeneratedImageClip != null) {
                        /**
                         *  Delete the file
                         */
                        new File(mGeneratedImageClip).delete();
                        /**
                         *  Invalidate the filename
                         */
                        mGeneratedImageClip = null;
                    }
                    mRegenerateClip = false;
                }
                return effect;
            }
        }
        return null;
    }

    /**
     * Set the filepath of the generated image clip when the effect is added.
     *
     * @param The filepath of the generated image clip.
     */
    void setGeneratedImageClip(String generatedFilePath) {
        mGeneratedImageClip = generatedFilePath;
    }

    /**
     * Get the filepath of the generated image clip when the effect is added.
     *
     * @return The filepath of the generated image clip (null if it does not
     *         exist)
     */
    String getGeneratedImageClip() {
        return mGeneratedImageClip;
    }

    /**
     * Find the effect with the specified id
     *
     * @param effectId The effect id
     * @return The effect with the specified id (null if it does not exist)
     */
    public Effect getEffect(String effectId) {
        for (Effect effect : mEffects) {
            if (effect.getId().equals(effectId)) {
                return effect;
            }
        }
        return null;
    }

    /**
     * Get the list of effects.
     *
     * @return the effects list. If no effects exist an empty list will be
     *         returned.
     */
    public List<Effect> getAllEffects() {
        return mEffects;
    }

    /**
     * Add an overlay to the storyboard. This method invalidates a transition
     * video clip if the overlay overlaps with a transition.
     *
     * @param overlay The overlay to add
     * @throws IllegalStateException if a preview or an export is in progress or
     *             if the overlay id is not unique across all the overlays added
     *             or if the bitmap is not specified or if the dimensions of the
     *             bitmap do not match the dimensions of the media item
     * @throws FileNotFoundException, IOException if overlay could not be saved
     *             to project path
     */
    public void addOverlay(Overlay overlay) throws FileNotFoundException, IOException {
        if (overlay == null) {
            throw new IllegalArgumentException("NULL Overlay cannot be applied");
        }

        if (overlay.getMediaItem() != this) {
            throw new IllegalArgumentException("Media item mismatch");
        }

        if (mOverlays.contains(overlay)) {
            throw new IllegalArgumentException("Overlay already exists: " + overlay.getId());
        }

        if (overlay.getStartTime() + overlay.getDuration() > getDuration()) {
            throw new IllegalArgumentException(
            "Overlay start time + overlay duration > media clip duration");
        }

        if (overlay instanceof OverlayFrame) {
            final OverlayFrame frame = (OverlayFrame)overlay;
            final Bitmap bitmap = frame.getBitmap();
            if (bitmap == null) {
                throw new IllegalArgumentException("Overlay bitmap not specified");
            }

            final int scaledWidth, scaledHeight;
            if (this instanceof MediaVideoItem) {
                scaledWidth = getWidth();
                scaledHeight = getHeight();
            } else {
                scaledWidth = ((MediaImageItem)this).getScaledWidth();
                scaledHeight = ((MediaImageItem)this).getScaledHeight();
            }

            /**
             * The dimensions of the overlay bitmap must be the same as the
             * media item dimensions
             */
            if (bitmap.getWidth() != scaledWidth || bitmap.getHeight() != scaledHeight) {
                throw new IllegalArgumentException(
                "Bitmap dimensions must match media item dimensions");
            }

            mMANativeHelper.setGeneratePreview(true);
            ((OverlayFrame)overlay).save(mProjectPath);

            mOverlays.add(overlay);
            invalidateTransitions(overlay.getStartTime(), overlay.getDuration());

        } else {
            throw new IllegalArgumentException("Overlay not supported");
        }
    }

    /**
     * @param flag The flag to indicate if regeneration of clip is true or
     *            false.
     */
    void setRegenerateClip(boolean flag) {
        mRegenerateClip = flag;
    }

    /**
     * @return flag The flag to indicate if regeneration of clip is true or
     *         false.
     */
    boolean getRegenerateClip() {
        return mRegenerateClip;
    }

    /**
     * Remove the overlay with the specified id.
     *
     * This method invalidates a transition video clip if the overlay overlaps
     * with a transition.
     *
     * @param overlayId The id of the overlay to be removed
     *
     * @return The overlay that was removed
     * @throws IllegalStateException if a preview or an export is in progress
     */
    public Overlay removeOverlay(String overlayId) {
        for (Overlay overlay : mOverlays) {
            if (overlay.getId().equals(overlayId)) {
                mMANativeHelper.setGeneratePreview(true);

                mOverlays.remove(overlay);
                if (overlay instanceof OverlayFrame) {
                    ((OverlayFrame)overlay).invalidate();
                }
                invalidateTransitions(overlay.getStartTime(), overlay.getDuration());
                return overlay;
            }
        }
        return null;
    }

    /**
     * Find the overlay with the specified id
     *
     * @param overlayId The overlay id
     *
     * @return The overlay with the specified id (null if it does not exist)
     */
    public Overlay getOverlay(String overlayId) {
        for (Overlay overlay : mOverlays) {
            if (overlay.getId().equals(overlayId)) {
                return overlay;
            }
        }

        return null;
    }

    /**
     * Get the list of overlays associated with this media item
     *
     * Note that if any overlay source files are not accessible anymore,
     * this method will still provide the full list of overlays.
     *
     * @return The list of overlays. If no overlays exist an empty list will
     *         be returned.
     */
    public List<Overlay> getAllOverlays() {
        return mOverlays;
    }

    /**
     * Create a thumbnail at specified time in a video stream in Bitmap format
     *
     * @param width width of the thumbnail in pixels
     * @param height height of the thumbnail in pixels
     * @param timeMs The time in the source video file at which the thumbnail is
     *            requested (even if trimmed).
     *
     * @return The thumbnail as a Bitmap.
     *
     * @throws IOException if a file error occurs
     * @throws IllegalArgumentException if time is out of video duration
     */
    public abstract Bitmap getThumbnail(int width, int height, long timeMs)
                                        throws IOException;

    /**
     * Get the array of Bitmap thumbnails between start and end.
     *
     * @param width width of the thumbnail in pixels
     * @param height height of the thumbnail in pixels
     * @param startMs The start of time range in milliseconds
     * @param endMs The end of the time range in milliseconds
     * @param thumbnailCount The thumbnail count
     * @param indices The indices of the thumbnails wanted
     * @param callback The callback used to pass back the bitmaps
     *
     * @throws IOException if a file error occurs
     */
    public abstract void getThumbnailList(int width, int height,
                                          long startMs, long endMs,
                                          int thumbnailCount,
                                          int[] indices,
                                          GetThumbnailListCallback callback)
                                          throws IOException;

    public interface GetThumbnailListCallback {
        public void onThumbnail(Bitmap bitmap, int index);
    }

    // This is for compatibility, only used in tests.
    public Bitmap[] getThumbnailList(int width, int height,
                                     long startMs, long endMs,
                                     int thumbnailCount)
                                     throws IOException {
        final Bitmap[] bitmaps = new Bitmap[thumbnailCount];
        int[] indices = new int[thumbnailCount];
        for (int i = 0; i < thumbnailCount; i++) {
            indices[i] = i;
        }
        getThumbnailList(width, height, startMs, endMs,
                thumbnailCount, indices, new GetThumbnailListCallback() {
            public void onThumbnail(Bitmap bitmap, int index) {
                bitmaps[index] = bitmap;
            }
        });

        return bitmaps;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object object) {
        if (!(object instanceof MediaItem)) {
            return false;
        }
        return mUniqueId.equals(((MediaItem)object).mUniqueId);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return mUniqueId.hashCode();
    }

    /**
     * Invalidate the start and end transitions if necessary
     *
     * @param startTimeMs The start time of the effect or overlay
     * @param durationMs The duration of the effect or overlay
     */
    abstract void invalidateTransitions(long startTimeMs, long durationMs);

    /**
     * Invalidate the start and end transitions if necessary. This method is
     * typically called when the start time and/or duration of an overlay or
     * effect is changing.
     *
     * @param oldStartTimeMs The old start time of the effect or overlay
     * @param oldDurationMs The old duration of the effect or overlay
     * @param newStartTimeMs The new start time of the effect or overlay
     * @param newDurationMs The new duration of the effect or overlay
     */
    abstract void invalidateTransitions(long oldStartTimeMs, long oldDurationMs,
            long newStartTimeMs, long newDurationMs);

    /**
     * Check if two items overlap in time
     *
     * @param startTimeMs1 Item 1 start time
     * @param durationMs1 Item 1 duration
     * @param startTimeMs2 Item 2 start time
     * @param durationMs2 Item 2 end time
     * @return true if the two items overlap
     */
    protected boolean isOverlapping(long startTimeMs1, long durationMs1,
                                    long startTimeMs2, long durationMs2) {
        if (startTimeMs1 + durationMs1 <= startTimeMs2) {
            return false;
        } else if (startTimeMs1 >= startTimeMs2 + durationMs2) {
            return false;
        }

        return true;
    }

    /**
     * Adjust the duration transitions.
     */
    protected void adjustTransitions() {
        /**
         *  Check if the duration of transitions need to be adjusted
         */
        if (mBeginTransition != null) {
            final long maxDurationMs = mBeginTransition.getMaximumDuration();
            if (mBeginTransition.getDuration() > maxDurationMs) {
                mBeginTransition.setDuration(maxDurationMs);
            }
        }

        if (mEndTransition != null) {
            final long maxDurationMs = mEndTransition.getMaximumDuration();
            if (mEndTransition.getDuration() > maxDurationMs) {
                mEndTransition.setDuration(maxDurationMs);
            }
        }
    }

    /**
     * @return MediaArtistNativeHleper context
     */
    MediaArtistNativeHelper getNativeContext() {
        return mMANativeHelper;
    }

    /**
     * Initialises ClipSettings fields to default value
     *
     * @param ClipSettings object
     *{@link android.media.videoeditor.MediaArtistNativeHelper.ClipSettings}
     */
    void initClipSettings(ClipSettings clipSettings) {
        clipSettings.clipPath = null;
        clipSettings.clipDecodedPath = null;
        clipSettings.clipOriginalPath = null;
        clipSettings.fileType = 0;
        clipSettings.endCutTime = 0;
        clipSettings.beginCutTime = 0;
        clipSettings.beginCutPercent = 0;
        clipSettings.endCutPercent = 0;
        clipSettings.panZoomEnabled = false;
        clipSettings.panZoomPercentStart = 0;
        clipSettings.panZoomTopLeftXStart = 0;
        clipSettings.panZoomTopLeftYStart = 0;
        clipSettings.panZoomPercentEnd = 0;
        clipSettings.panZoomTopLeftXEnd = 0;
        clipSettings.panZoomTopLeftYEnd = 0;
        clipSettings.mediaRendering = 0;
        clipSettings.rgbWidth = 0;
        clipSettings.rgbHeight = 0;
    }

    /**
     * @return ClipSettings object with populated data
     *{@link android.media.videoeditor.MediaArtistNativeHelper.ClipSettings}
     */
    ClipSettings getClipSettings() {
        MediaVideoItem mVI = null;
        MediaImageItem mII = null;
        ClipSettings clipSettings = new ClipSettings();
        initClipSettings(clipSettings);
        if (this instanceof MediaVideoItem) {
            mVI = (MediaVideoItem)this;
            clipSettings.clipPath = mVI.getFilename();
            clipSettings.fileType = mMANativeHelper.getMediaItemFileType(mVI.
                                                                 getFileType());
            clipSettings.beginCutTime = (int)mVI.getBoundaryBeginTime();
            clipSettings.endCutTime = (int)mVI.getBoundaryEndTime();
            clipSettings.mediaRendering = mMANativeHelper.
                                          getMediaItemRenderingMode(mVI
                                          .getRenderingMode());
        } else if (this instanceof MediaImageItem) {
            mII = (MediaImageItem)this;
            clipSettings = mII.getImageClipProperties();
        }
        return clipSettings;
    }

    /**
     * Generates a black frame to be used for generating
     * begin transition at first media item in storyboard
     * or end transition at last media item in storyboard
     *
     * @param ClipSettings object
     *{@link android.media.videoeditor.MediaArtistNativeHelper.ClipSettings}
     */
    void generateBlankFrame(ClipSettings clipSettings) {
        if (!mBlankFrameGenerated) {
            int mWidth = 64;
            int mHeight = 64;
            mBlankFrameFilename = String.format(mProjectPath + "/" + "ghost.rgb");
            FileOutputStream fl = null;
            try {
                 fl = new FileOutputStream(mBlankFrameFilename);
            } catch (IOException e) {
                /* catch IO exception */
            }
            final DataOutputStream dos = new DataOutputStream(fl);

            final int [] framingBuffer = new int[mWidth];

            ByteBuffer byteBuffer = ByteBuffer.allocate(framingBuffer.length * 4);
            IntBuffer intBuffer;

            byte[] array = byteBuffer.array();
            int tmp = 0;
            while(tmp < mHeight) {
                intBuffer = byteBuffer.asIntBuffer();
                intBuffer.put(framingBuffer,0,mWidth);
                try {
                    dos.write(array);
                } catch (IOException e) {
                    /* catch file write error */
                }
                tmp += 1;
            }

            try {
                fl.close();
            } catch (IOException e) {
                /* file close error */
            }
            mBlankFrameGenerated = true;
        }

        clipSettings.clipPath = mBlankFrameFilename;
        clipSettings.fileType = FileType.JPG;
        clipSettings.beginCutTime = 0;
        clipSettings.endCutTime = 0;
        clipSettings.mediaRendering = MediaRendering.RESIZING;

        clipSettings.rgbWidth = 64;
        clipSettings.rgbHeight = 64;
    }

    /**
     * Invalidates the blank frame generated
     */
    void invalidateBlankFrame() {
        if (mBlankFrameFilename != null) {
            if (new File(mBlankFrameFilename).exists()) {
                new File(mBlankFrameFilename).delete();
                mBlankFrameFilename = null;
            }
        }
    }

}
