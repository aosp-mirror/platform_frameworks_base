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

import android.view.SurfaceHolder;

/**
 * This is the interface implemented by classes which provide video editing
 * functionality. The VideoEditor implementation class manages all input and
 * output files. Unless specifically mentioned, methods are blocking. A
 * typical editing session may consist of the following sequence of operations:
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
    // The file name of the project thumbnail
    public static final String THUMBNAIL_FILENAME = "thumbnail.jpg";

    // Use this value instead of the specific end of the storyboard timeline
    // value.
    public final static int DURATION_OF_STORYBOARD = -1;

    /**
     * This listener interface is used by the VideoEditor to emit preview
     * progress notifications. This callback should be invoked after the
     * number of frames specified by
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
         *            since the beginning of the storyboard timeline).
         * @param end true if the end of the timeline was reached
         */
        public void onProgress(VideoEditor videoEditor, long timeMs, boolean end);
    }

    /**
     * This listener interface is used by the VideoEditor to emit export status
     * notifications.
     * {@link #export(String filename, ExportProgressListener listener, int height, int bitrate)}
     */
    public interface ExportProgressListener {
        /**
         * This method notifies the listener of the progress status of a export
         * operation.
         *
         * @param videoEditor The VideoEditor instance
         * @param filename The name of the file which is in the process of being
         *            exported.
         * @param progress The progress in %. At the beginning of the export, this
         *            value is set to 0; at the end, the value is set to 100.
         */
        public void onProgress(VideoEditor videoEditor, String filename, int progress);
    }

    /**
     * @return The path where the VideoEditor stores all files related to the
     * project
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
     */
    public void save() throws IOException;

    /**
     * Create the output movie based on all media items added and the applied
     * storyboard items. This method can take a long time to execute and is
     * blocking. The application will receive progress notifications via the
     * ExportProgressListener. Specific implementations may not support multiple
     * simultaneous export operations.
     *
     * Note that invoking methods which would change the contents of the output
     * movie throw an IllegalStateException while an export operation is
     * pending.
     *
     * @param filename The output file name (including the full path)
     * @param height The height of the output video file. The supported values
     *            for height are described in the MediaProperties class, for
     *            example: HEIGHT_480. The width will be automatically
     *            computed according to the aspect ratio provided by
     *            {@link #setAspectRatio(int)}
     * @param bitrate The bitrate of the output video file. This is approximate
     *            value for the output movie. Supported bitrate values are
     *            described in the MediaProperties class for example:
     *            BITRATE_384K
     * @param listener The listener for progress notifications. Use null if
     *            export progress notifications are not needed.
     *
     * @throws IllegalArgumentException if height or bitrate are not supported.
     * @throws IOException if output file cannot be created
     * @throws IllegalStateException if a preview or an export is in progress or
     *             if no MediaItem has been added
     * @throws CancellationException if export is canceled by calling
     *             {@link #cancelExport()}
     * @throws UnsupportOperationException if multiple simultaneous export()
     *  are not allowed
     */
    public void export(String filename, int height, int bitrate, ExportProgressListener listener)
            throws IOException;

    /**
     * Cancel the running export operation. This method blocks until the
     * export is canceled and the exported file (if any) is deleted. If the
     * export completed by the time this method is invoked, the export file
     * will be deleted.
     *
     * @param filename The filename which identifies the export operation to be
     *            canceled.
     **/
    public void cancelExport(String filename);

    /**
     * Add a media item at the end of the storyboard.
     *
     * @param mediaItem The media item object to add
     * @throws IllegalStateException if a preview or an export is in progress or
     *             if the media item id is not unique across all the media items
     *             added.
     */
    public void addMediaItem(MediaItem mediaItem);

    /**
     * Insert a media item after the media item with the specified id.
     *
     * @param mediaItem The media item object to insert
     * @param afterMediaItemId Insert the mediaItem after the media item
     *            identified by this id. If this parameter is null, the media
     *            item is inserted at the beginning of the timeline.
     *
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if media item with the specified id does
     *             not exist (null is a valid value) or if the media item id is
     *             not unique across all the media items added.
     */
    public void insertMediaItem(MediaItem mediaItem, String afterMediaItemId);

    /**
     * Move a media item after the media item with the specified id.
     *
     * Note: The project thumbnail is regenerated if the media item is or
     * becomes the first media item in the storyboard timeline.
     *
     * @param mediaItemId The id of the media item to move
     * @param afterMediaItemId Move the media item identified by mediaItemId after
     *          the media item identified by this parameter. If this parameter
     *          is null, the media item is moved at the beginning of the
     *          timeline.
     *
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if one of media item ids is invalid
     *          (null is a valid value)
     */
    public void moveMediaItem(String mediaItemId, String afterMediaItemId);

    /**
     * Remove the media item with the specified id. If there are transitions
     * before or after this media item, then this/these transition(s) are
     * removed from the storyboard. If the extraction of the audio waveform is
     * in progress, the extraction is canceled and the file is deleted.
     *
     * Effects and overlays associated with the media item will also be
     * removed.
     *
     * Note: The project thumbnail is regenerated if the media item which
     * is removed is the first media item in the storyboard or if the
     * media item is the only one in the storyboard. If the
     * media item is the only one in the storyboard, the project thumbnail
     * will be set to a black frame and the aspect ratio will revert to the
     * default aspect ratio, and this method is equivalent to
     * removeAllMediaItems() in this case.
     *
     * @param mediaItemId The unique id of the media item to be removed
     *
     * @return The media item that was removed
     *
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if media item with the specified id
     *          does not exist
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
     *          will be returned.
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
     *              than the smallest duration of the two media item files or
     *              if the two media items specified in the transition are not
     *              adjacent
     */
    public void addTransition(Transition transition);

    /**
     * Remove the transition with the specified id.
     *
     * @param transitionId The id of the transition to be removed
     *
     * @return The transition that was removed
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if transition with the specified id does
     *             not exist
     */
    public Transition removeTransition(String transitionId);

    /**
     * Get the list of transitions
     *
     * @return The list of transitions. If no transitions exist an empty list
     *  will be returned.
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
     * @throws UnsupportedOperationException if the implementation supports a
     *             limited number of audio tracks.
     * @throws IllegalArgumentException if media item is not unique across all
     *             the audio tracks already added.
     */
    public void addAudioTrack(AudioTrack audioTrack);

    /**
     * Insert an audio track after the audio track with the specified id. Use
     * addAudioTrack to add an audio track at the end of the storyboard
     * timeline.
     *
     * @param audioTrack The audio track object to insert
     * @param afterAudioTrackId Insert the audio track after the audio track
     *            identified by this parameter. If this parameter is null the
     *            audio track is added at the beginning of the timeline.
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if media item with the specified id does
     *             not exist (null is a valid value). if media item is not
     *             unique across all the audio tracks already added.
     * @throws UnsupportedOperationException if the implementation supports a
     *             limited number of audio tracks
     */
    public void insertAudioTrack(AudioTrack audioTrack, String afterAudioTrackId);

    /**
     * Move an AudioTrack after the AudioTrack with the specified id.
     *
     * @param audioTrackId The id of the AudioTrack to move
     * @param afterAudioTrackId Move the AudioTrack identified by audioTrackId
     *            after the AudioTrack identified by this parameter. If this
     *            parameter is null the audio track is added at the beginning of
     *            the timeline.
     * @throws IllegalStateException if a preview or an export is in progress
     * @throws IllegalArgumentException if one of media item ids is invalid
     *             (null is a valid value)
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
     * Get the list of AudioTracks in order in which they appear in the storyboard.
     *
     * Note that if any AudioTrack source files are not accessible anymore,
     * this method will still provide the full list of audio tracks.
     *
     * @return The list of AudioTracks. If no audio tracks exist an empty list
     *  will be returned.
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
     *            aspect ratio, then this function just returns. The supported
     *            aspect ratio are defined in the MediaProperties class for
     *            example: ASPECTRATIO_16_9
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
     *
     * @return The accurate time stamp of the frame that is rendered
     * .
     * @throws IllegalStateException if a preview or an export is already
     *             in progress
     * @throws IllegalArgumentException if time is negative or beyond the
     *             preview duration
     */
    public long renderPreviewFrame(SurfaceHolder surfaceHolder, long timeMs);

    /**
     * This method must be called after the aspect ratio of the project changes
     * and before startPreview is called. Note that this method may block for
     * an extensive period of time.
     */
    public void generatePreview();

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
     *            will start
     * @param toMs The time (relative to the timeline) at which the preview will
     *            stop. Use -1 to play to the end of the timeline
     * @param loop true if the preview should be looped once it reaches the end
     * @param callbackAfterFrameCount The listener interface should be invoked
     *            after the number of frames specified by this parameter.
     * @param listener The listener which will be notified of the preview
     *            progress
     * @throws IllegalArgumentException if fromMs is beyond the preview duration
     * @throws IllegalStateException if a preview or an export is already in
     *             progress
     */
    public void startPreview(SurfaceHolder surfaceHolder, long fromMs, long toMs, boolean loop,
            int callbackAfterFrameCount, PreviewProgressListener listener);

    /**
     * Stop the current preview. This method blocks until ongoing preview is
     * stopped. Ignored if there is no preview running.
     *
     * @return The accurate current time when stop is effective expressed in
     *          milliseconds
     */
    public long stopPreview();
}
