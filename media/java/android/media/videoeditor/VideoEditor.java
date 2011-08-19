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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.SurfaceHolder;

/**
 * This is the interface implemented by classes which provide video editing
 * functionality. The VideoEditor implementation class manages all input and
 * output files. Unless specifically mentioned, methods are blocking. A typical
 * editing session may consist of the following sequence of operations:
 *
 * <ul>
 *  <li>Add a set of MediaItems</li>
 *  <li>Apply a set of Transitions between MediaItems</li>
 *  <li>Add Effects and Overlays to media items</li>
 *  <li>Preview the movie at any time</li>
 *  <li>Save the VideoEditor implementation class internal state</li>
 *  <li>Release the VideoEditor implementation class instance by invoking
 * {@link #release()}
 * </ul>
 * The internal VideoEditor state consists of the following elements:
 * <ul>
 *  <li>Ordered & trimmed MediaItems</li>
 *  <li>Transition video clips</li>
 *  <li>Overlays</li>
 *  <li>Effects</li>
 *  <li>Audio waveform for the background audio and MediaItems</li>
 *  <li>Project thumbnail</li>
 *  <li>Last exported movie.</li>
 *  <li>Other project specific data such as the current aspect ratio.</li>
 * </ul>
 * {@hide}
 */
public interface VideoEditor {
    /**
     *  The file name of the project thumbnail
     */
    public static final String THUMBNAIL_FILENAME = "thumbnail.jpg";

    /**
     *  Use this value instead of the specific end of the storyboard timeline
     *  value.
     */
    public final static int DURATION_OF_STORYBOARD = -1;

    /**
     *  Maximum supported file size
     */
    public static final long MAX_SUPPORTED_FILE_SIZE = 2147483648L;

    /**
     * This listener interface is used by the VideoEditor to emit preview
     * progress notifications. This callback should be invoked after the number
     * of frames specified by
     * {@link #startPreview(SurfaceHolder surfaceHolder, long fromMs,
     *           int callbackAfterFrameCount, PreviewProgressListener listener)}
     */
    public interface PreviewProgressListener {
        /**
         * This method notifies the listener of the current time position while
         * previewing a project.
         *
         * @param videoEditor The VideoEditor instance
         * @param timeMs The current preview position (expressed in milliseconds
         *        since the beginning of the storyboard timeline).
         * @param overlayData The overlay data (null if the overlay data
         *      is unchanged)
         */
        public void onProgress(VideoEditor videoEditor, long timeMs,
                               OverlayData overlayData);
        /**
         * This method notifies the listener when the preview is started
         * previewing a project.
         *
         * @param videoEditor The VideoEditor instance
         */
        public void onStart(VideoEditor videoEditor);

        /**
         * This method notifies the listener when the preview is stopped
         * previewing a project.
         *
         * @param videoEditor The VideoEditor instance
         */
        public void onStop(VideoEditor videoEditor);
    }

    /**
     * This listener interface is used by the VideoEditor to emit export status
     * notifications.
     * {@link #export(String filename, ExportProgressListener listener,
     *                int height, int bitrate)}
     */
    public interface ExportProgressListener {
        /**
         * This method notifies the listener of the progress status of a export
         * operation.
         *
         * @param videoEditor The VideoEditor instance
         * @param filename The name of the file which is in the process of being
         *        exported.
         * @param progress The progress in %. At the beginning of the export,
         *        this value is set to 0; at the end, the value is set to 100.
         */
        public void onProgress(VideoEditor videoEditor, String filename,
                              int progress);
    }

    public interface MediaProcessingProgressListener {
        /**
         *  Values used for the action parameter
         */
        public static final int ACTION_ENCODE = 1;
        public static final int ACTION_DECODE = 2;

        /**
         * This method notifies the listener of the progress status of
         * processing a media object such as a Transition, AudioTrack & Kenburns
         * This method may be called maximum 100 times for one operation.
         *
         * @param object The object that is being processed such as a Transition
         *               or AudioTrack
         * @param action The type of processing being performed
         * @param progress The progress in %. At the beginning of the operation,
         *          this value is set to 0; at the end, the value is set to 100.
         */
        public void onProgress(Object item, int action, int progress);
    }

    /**
     * The overlay data
     */
    public static final class OverlayData {
        // Instance variables
        private Bitmap mOverlayBitmap;
        private int mRenderingMode;
        private boolean mClear;
        private static final Paint sResizePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        /**
         * Default constructor
         */
        public OverlayData() {
            mOverlayBitmap = null;
            mRenderingMode = MediaArtistNativeHelper.MediaRendering.BLACK_BORDERS;
            mClear = false;
        }

        /**
         * Releases the bitmap
         */
        public void release() {
            if (mOverlayBitmap != null) {
                mOverlayBitmap.recycle();
                mOverlayBitmap = null;
            }
        }

        /**
         * Check if the overlay needs to be rendered
         *
         * @return true if rendering is needed
         */
        public boolean needsRendering() {
            return (mClear || mOverlayBitmap != null);
        }

        /**
         * Store the overlay data
         *
         * @param overlayBitmap The overlay bitmap
         * @param renderingMode The rendering mode
         */
        void set(Bitmap overlayBitmap, int renderingMode) {
            mOverlayBitmap = overlayBitmap;
            mRenderingMode = renderingMode;
            mClear = false;
        }

        /**
         * Clear the overlay
         */
        void setClear() {
            mClear = true;
        }

        /**
        * Render the overlay by either clearing it or by
        * rendering the overlay bitmap with the specified
        * rendering mode
        *
        * @param destBitmap The destination bitmap
        */
        public void renderOverlay(Bitmap destBitmap) {
            if (mClear) {
                destBitmap.eraseColor(Color.TRANSPARENT);
            } else if (mOverlayBitmap != null) {
                final Canvas overlayCanvas = new Canvas(destBitmap);
                final Rect destRect;
                final Rect srcRect;
                switch (mRenderingMode) {
                    case MediaArtistNativeHelper.MediaRendering.RESIZING: {
                        destRect = new Rect(0, 0, overlayCanvas.getWidth(),
                                                 overlayCanvas.getHeight());
                        srcRect = new Rect(0, 0, mOverlayBitmap.getWidth(),
                                                 mOverlayBitmap.getHeight());
                        break;
                    }

                    case MediaArtistNativeHelper.MediaRendering.BLACK_BORDERS: {
                        int left, right, top, bottom;
                        float aROverlayImage, aRCanvas;
                        aROverlayImage = (float)(mOverlayBitmap.getWidth()) /
                                         (float)(mOverlayBitmap.getHeight());

                        aRCanvas = (float)(overlayCanvas.getWidth()) /
                                         (float)(overlayCanvas.getHeight());

                        if (aROverlayImage > aRCanvas) {
                            int newHeight = ((overlayCanvas.getWidth() * mOverlayBitmap.getHeight())
                                             / mOverlayBitmap.getWidth());
                            left = 0;
                            top  = (overlayCanvas.getHeight() - newHeight) / 2;
                            right = overlayCanvas.getWidth();
                            bottom = top + newHeight;
                        } else {
                            int newWidth = ((overlayCanvas.getHeight() * mOverlayBitmap.getWidth())
                                                / mOverlayBitmap.getHeight());
                            left = (overlayCanvas.getWidth() - newWidth) / 2;
                            top  = 0;
                            right = left + newWidth;
                            bottom = overlayCanvas.getHeight();
                        }

                        destRect = new Rect(left, top, right, bottom);
                        srcRect = new Rect(0, 0, mOverlayBitmap.getWidth(), mOverlayBitmap.getHeight());
                        break;
                    }

                    case MediaArtistNativeHelper.MediaRendering.CROPPING: {
                        // Calculate the source rect
                        int left, right, top, bottom;
                        float aROverlayImage, aRCanvas;
                        aROverlayImage = (float)(mOverlayBitmap.getWidth()) /
                                         (float)(mOverlayBitmap.getHeight());
                        aRCanvas = (float)(overlayCanvas.getWidth()) /
                                        (float)(overlayCanvas.getHeight());
                        if (aROverlayImage < aRCanvas) {
                            int newHeight = ((mOverlayBitmap.getWidth() * overlayCanvas.getHeight())
                                       / overlayCanvas.getWidth());

                            left = 0;
                            top  = (mOverlayBitmap.getHeight() - newHeight) / 2;
                            right = mOverlayBitmap.getWidth();
                            bottom = top + newHeight;
                        } else {
                            int newWidth = ((mOverlayBitmap.getHeight() * overlayCanvas.getWidth())
                                        / overlayCanvas.getHeight());
                            left = (mOverlayBitmap.getWidth() - newWidth) / 2;
                            top  = 0;
                            right = left + newWidth;
                            bottom = mOverlayBitmap.getHeight();
                        }

                        srcRect = new Rect(left, top, right, bottom);
                        destRect = new Rect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());
                        break;
                    }

                    default: {
                        throw new IllegalStateException("Rendering mode: " + mRenderingMode);
                    }
                }

                destBitmap.eraseColor(Color.TRANSPARENT);
                overlayCanvas.drawBitmap(mOverlayBitmap, srcRect, destRect, sResizePaint);

                mOverlayBitmap.recycle();
            }
        }
    }

    /**
     * @return The path where the VideoEditor stores all files related to the
     *         project
     */
    public String getPath();

    /**
     * This method releases all in-memory resources used by the VideoEditor
     * instance. All pending operations such as preview, export and extract
     * audio waveform must be canceled.
     */
    public void release();

    /**
     * Persist the current internal state of VideoEditor to the project path.
     * The VideoEditor state may be restored by invoking the
     * {@link VideoEditorFactory#load(String)} method. This method does not
     * release the internal in-memory state of the VideoEditor. To release
     * the in-memory state of the VideoEditor the {@link #release()} method
     * must be invoked.
     *
     * Pending transition generations must be allowed to complete before the
     * state is saved.
     * Pending audio waveform generations must be allowed to complete.
     * Pending export operations must be allowed to continue.
     *
     * @throws IOException if the internal state cannot be saved to project file
     */
    public void save() throws IOException;

    /**
     * Create the output movie based on all media items added and the applied
     * storyboard items. This method can take a long time to execute and is
     * blocking. The application will receive progress notifications via the
     * ExportProgressListener. Specific implementations may not support multiple
     * simultaneous export operations. Note that invoking methods which would
     * change the contents of the output movie throw an IllegalStateException
     * while an export operation is pending.
     *
     * The audio and video codecs are automatically selected by the underlying
     * implementation.
     *
     * @param filename The output file name (including the full path)
     * @param height The height of the output video file. The supported values
     *        for height are described in the MediaProperties class, for
     *        example: HEIGHT_480. The width will be automatically computed
     *        according to the aspect ratio provided by
     *        {@link #setAspectRatio(int)}
     * @param bitrate The bitrate of the output video file. This is approximate
     *        value for the output movie. Supported bitrate values are
     *        described in the MediaProperties class for example: BITRATE_384K
     * @param listener The listener for progress notifications. Use null if
     *        export progress notifications are not needed.
     *
     * @throws IllegalArgumentException if height or bitrate are not supported
     *        or if the audio or video codecs are not supported
     * @throws IOException if output file cannot be created
     * @throws IllegalStateException if a preview or an export is in progress or
     *        if no MediaItem has been added
     * @throws CancellationException if export is canceled by calling
     *        {@link #cancelExport()}
     * @throws UnsupportOperationException if multiple simultaneous export() are
     *        not allowed
     */
    public void export(String filename, int height, int bitrate,
                       ExportProgressListener listener)
                       throws IOException;

    /**
     * Create the output movie based on all media items added and the applied
     * storyboard items. This method can take a long time to execute and is
     * blocking. The application will receive progress notifications via the
     * ExportProgressListener. Specific implementations may not support multiple
     * simultaneous export operations. Note that invoking methods which would
     * change the contents of the output movie throw an IllegalStateException
     * while an export operation is pending.
     *
     * @param filename The output file name (including the full path)
     * @param height The height of the output video file. The supported values
     *        for height are described in the MediaProperties class, for
     *        example: HEIGHT_480. The width will be automatically computed
     *        according to the aspect ratio provided by
     *        {@link #setAspectRatio(int)}
     * @param bitrate The bitrate of the output video file. This is approximate
     *        value for the output movie. Supported bitrate values are
     *        described in the MediaProperties class for example: BITRATE_384K
     * @param audioCodec The audio codec to be used for the export. The audio
     *        codec values are defined in the MediaProperties class (e.g.
     *        ACODEC_AAC_LC). Note that not all audio codec types are
     *        supported for export purposes.
     * @param videoCodec The video codec to be used for the export. The video
     *        codec values are defined in the MediaProperties class (e.g.
     *        VCODEC_H264). Note that not all video codec types are
     *        supported for export purposes.
     * @param listener The listener for progress notifications. Use null if
     *        export progress notifications are not needed.
     *
     * @throws IllegalArgumentException if height or bitrate are not supported
     *        or if the audio or video codecs are not supported
     * @throws IOException if output file cannot be created
     * @throws IllegalStateException if a preview or an export is in progress or
     *        if no MediaItem has been added
     * @throws CancellationException if export is cancelled by calling
     *        {@link #cancelExport()}
     * @throws UnsupportOperationException if multiple simultaneous export() are
     *        not allowed
     */
    public void export(String filename, int height, int bitrate, int audioCodec,
                       int videoCodec, ExportProgressListener listener)
                       throws IOException;

    /**
     * Cancel the running export operation. This method blocks until the export
     * is cancelled and the exported file (if any) is deleted. If the export
     * completed by the time this method is invoked, the export file will be
     * deleted.
     *
     * @param filename The filename which identifies the export operation to be
     *            canceled.
     **/
    public void cancelExport(String filename);

    /**
     * Add a media item at the end of the storyboard.
     *
     * @param mediaItem The media item object to add
     *
     * @throws IllegalStateException if a preview or an export is in progress or
     *        if the media item id is not unique across all the media items
     *        added.
     */
    public void addMediaItem(MediaItem mediaItem);

    /**
     * Insert a media item after the media item with the specified id.
     *
     * @param mediaItem The media item object to insert
     * @param afterMediaItemId Insert the mediaItem after the media item
     *        identified by this id. If this parameter is null, the media
     *        item is inserted at the beginning of the timeline.
     *
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if media item with the specified id does
     *        not exist (null is a valid value) or if the media item id is
     *        not unique across all the media items added.
     */
    public void insertMediaItem(MediaItem mediaItem, String afterMediaItemId);

    /**
     * Move a media item after the media item with the specified id.
     *
     * Note: The project thumbnail is regenerated if the media item is or
     * becomes the first media item in the storyboard timeline.
     *
     * @param mediaItemId The id of the media item to move
     * @param afterMediaItemId Move the media item identified by mediaItemId
     *        after the media item identified by this parameter. If this
     *        parameter is null, the media item is moved at the beginning of
     *        the timeline.
     *
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if one of media item ids is invalid
     *        (null is a valid value)
     */
    public void moveMediaItem(String mediaItemId, String afterMediaItemId);

    /**
     * Remove the media item with the specified id. If there are transitions
     * before or after this media item, then this/these transition(s) are
     * removed from the storyboard. If the extraction of the audio waveform is
     * in progress, the extraction is canceled and the file is deleted.
     *
     * Effects and overlays associated with the media item will also be removed.
     *
     * Note: The project thumbnail is regenerated if the media item which is
     * removed is the first media item in the storyboard or if the media item is
     * the only one in the storyboard. If the media item is the only one in the
     * storyboard, the project thumbnail will be set to a black frame and the
     * aspect ratio will revert to the default aspect ratio and this method is
     * equivalent to removeAllMediaItems() in this case.
     *
     * @param mediaItemId The unique id of the media item to be removed
     *
     * @return The media item that was removed
     *
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if media item with the specified id does
     *        not exist
     */
    public MediaItem removeMediaItem(String mediaItemId);

    /**
     * Remove all media items in the storyboard. All effects, overlays and all
     * transitions are also removed.
     *
     * Note: The project thumbnail will be set to a black frame and the aspect
     * ratio will revert to the default aspect ratio.
     *
     * @throws IllegalStateException if a preview or an export is in progress
     */
    public void removeAllMediaItems();

    /**
     * Get the list of media items in the order in which it they appear in the
     * storyboard timeline.
     *
     * Note that if any media item source files are no longer
     * accessible, this method will still provide the full list of media items.
     *
     * @return The list of media items. If no media item exist an empty list
     *        will be returned.
     */
    public List<MediaItem> getAllMediaItems();

    /**
     * Find the media item with the specified id
     *
     * @param mediaItemId The media item id
     *
     * @return The media item with the specified id (null if it does not exist)
     */
    public MediaItem getMediaItem(String mediaItemId);

    /**
     * Add a transition between the media items specified by the transition.
     * If a transition existed at the same position it is invalidated and then
     * the transition is replaced. Note that the new transition video clip is
     * not automatically generated by this method. The
     * {@link Transition#generate()} method must be invoked to generate
     * the transition video clip.
     *
     * Note that the TransitionAtEnd and TransitionAtStart are special kinds
     * that can not be applied between two media items.
     *
     * A crossfade audio transition will be automatically applied regardless of
     * the video transition.
     *
     * @param transition The transition to apply
     *
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if the transition duration is larger
     *        than the smallest duration of the two media item files or if
     *        the two media items specified in the transition are not
     *        adjacent
     */
    public void addTransition(Transition transition);

    /**
     * Remove the transition with the specified id.
     *
     * @param transitionId The id of the transition to be removed
     *
     * @return The transition that was removed
     *
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if transition with the specified id does
     *        not exist
     */
    public Transition removeTransition(String transitionId);

    /**
     * Get the list of transitions
     *
     * @return The list of transitions. If no transitions exist an empty list
     *        will be returned.
     */
    public List<Transition> getAllTransitions();

    /**
     * Find the transition with the specified transition id.
     *
     * @param transitionId The transition id
     *
     * @return The transition
     */
    public Transition getTransition(String transitionId);

    /**
     * Add the specified AudioTrack to the storyboard. Note: Specific
     * implementations may support a limited number of audio tracks (e.g. only
     * one audio track)
     *
     * @param audioTrack The AudioTrack to add
     *
     * @throws UnsupportedOperationException if the implementation supports a
     *        limited number of audio tracks.
     * @throws IllegalArgumentException if media item is not unique across all
     *        the audio tracks already added.
     */
    public void addAudioTrack(AudioTrack audioTrack);

    /**
     * Insert an audio track after the audio track with the specified id. Use
     * addAudioTrack to add an audio track at the end of the storyboard
     * timeline.
     *
     * @param audioTrack The audio track object to insert
     * @param afterAudioTrackId Insert the audio track after the audio track
     *        identified by this parameter. If this parameter is null the
     *        audio track is added at the beginning of the timeline.
     *
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if media item with the specified id does
     *        not exist (null is a valid value). if media item is not unique
     *        across all the audio tracks already added.
     * @throws UnsupportedOperationException if the implementation supports a
     *        limited number of audio tracks
     */
    public void insertAudioTrack(AudioTrack audioTrack, String afterAudioTrackId);

    /**
     * Move an AudioTrack after the AudioTrack with the specified id.
     *
     * @param audioTrackId The id of the AudioTrack to move
     * @param afterAudioTrackId Move the AudioTrack identified by audioTrackId
     *        after the AudioTrack identified by this parameter. If this
     *        parameter is null the audio track is added at the beginning of
     *        the timeline.
     *
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if one of media item ids is invalid
     *        (null is a valid value)
     */
    public void moveAudioTrack(String audioTrackId, String afterAudioTrackId);

    /**
     * Remove the audio track with the specified id. If the extraction of the
     * audio waveform is in progress, the extraction is canceled and the file is
     * deleted.
     *
     * @param audioTrackId The id of the audio track to be removed
     *
     * @return The audio track that was removed
     * @throws IllegalStateException if a preview or an export is in progress
     */
    public AudioTrack removeAudioTrack(String audioTrackId);

    /**
     * Get the list of AudioTracks in order in which they appear in the
     * storyboard.
     *
     * Note that if any AudioTrack source files are not accessible anymore,
     * this method will still provide the full list of audio tracks.
     *
     * @return The list of AudioTracks. If no audio tracks exist an empty list
     *        will be returned.
     */
    public List<AudioTrack> getAllAudioTracks();

    /**
     * Find the AudioTrack with the specified id
     *
     * @param audioTrackId The AudioTrack id
     *
     * @return The AudioTrack with the specified id (null if it does not exist)
     */
    public AudioTrack getAudioTrack(String audioTrackId);

    /**
     * Set the aspect ratio used in the preview and the export movie.
     *
     * The default aspect ratio is ASPECTRATIO_16_9 (16:9).
     *
     * @param aspectRatio to apply. If aspectRatio is the same as the current
     *        aspect ratio, then this function just returns. The supported
     *        aspect ratio are defined in the MediaProperties class for
     *        example: ASPECTRATIO_16_9
     *
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if aspect ratio is not supported
     */
    public void setAspectRatio(int aspectRatio);

    /**
     * Get current aspect ratio.
     *
     * @return The aspect ratio as described in MediaProperties
     */
    public int getAspectRatio();

    /**
     * Get the preview (and output movie) duration.
     *
     * @return The duration of the preview (and output movie)
     */
    public long getDuration();

    /**
     * Render a frame according to the preview aspect ratio and activating all
     * storyboard items relative to the specified time.
     *
     * @param surfaceHolder SurfaceHolder used by the application
     * @param timeMs time corresponding to the frame to display
     * @param overlayData The overlay data
     *
     * @return The accurate time stamp of the frame that is rendered.
     *
     * @throws IllegalStateException if a preview or an export is already in
     *        progress
     * @throws IllegalArgumentException if time is negative or beyond the
     *        preview duration
     */
    public long renderPreviewFrame(SurfaceHolder surfaceHolder, long timeMs,
            OverlayData overlayData);

    /**
     * This method must be called after any changes made to the storyboard
     * and before startPreview is called. Note that this method may block for an
     * extensive period of time.
     */
    public void generatePreview(MediaProcessingProgressListener listener);

    /**
     * Start the preview of all the storyboard items applied on all MediaItems
     * This method does not block (does not wait for the preview to complete).
     * The PreviewProgressListener allows to track the progress at the time
     * interval determined by the callbackAfterFrameCount parameter. The
     * SurfaceHolder has to be created and ready for use before calling this
     * method. The method is a no-op if there are no MediaItems in the
     * storyboard.
     *
     * @param surfaceHolder SurfaceHolder where the preview is rendered.
     * @param fromMs The time (relative to the timeline) at which the preview
     *        will start
     * @param toMs The time (relative to the timeline) at which the preview will
     *        stop. Use -1 to play to the end of the timeline
     * @param loop true if the preview should be looped once it reaches the end
     * @param callbackAfterFrameCount The listener interface should be invoked
     *        after the number of frames specified by this parameter.
     * @param listener The listener which will be notified of the preview
     *        progress
     *
     * @throws IllegalArgumentException if fromMs is beyond the preview duration
     * @throws IllegalStateException if a preview or an export is already in
     *        progress
     */
    public void startPreview(SurfaceHolder surfaceHolder, long fromMs, long toMs,
                             boolean loop,int callbackAfterFrameCount,
                             PreviewProgressListener listener);

    /**
     * Stop the current preview. This method blocks until ongoing preview is
     * stopped. Ignored if there is no preview running.
     *
     * @return The accurate current time when stop is effective expressed in
     *        milliseconds
     */
    public long stopPreview();

    /**
     * Clears the preview surface
     *
     * @param surfaceHolder SurfaceHolder where the preview is rendered
     * and needs to be cleared.
     */
    public void clearSurface(SurfaceHolder surfaceHolder);
}
