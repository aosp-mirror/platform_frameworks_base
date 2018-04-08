/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.TestApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;


/**
 * The AudioPresentation class encapsulates the information that describes an audio presentation
 * which is available in next generation audio content.
 *
 * Used by {@link MediaExtractor} {@link MediaExtractor#getAudioPresentations(int)} and
 * {@link AudioTrack} {@link AudioTrack#setPresentation(AudioPresentation)} to query available
 * presentations and to select one.
 *
 * A list of available audio presentations in a media source can be queried using
 * {@link MediaExtractor#getAudioPresentations(int)}. This list can be presented to a user for
 * selection.
 * An AudioPresentation can be passed to an offloaded audio decoder via
 * {@link AudioTrack#setPresentation(AudioPresentation)} to request decoding of the selected
 * presentation. An audio stream may contain multiple presentations that differ by language,
 * accessibility, end point mastering and dialogue enhancement. An audio presentation may also have
 * a set of description labels in different languages to help the user to make an informed
 * selection.
 */
public final class AudioPresentation {
    private final int mPresentationId;
    private final int mProgramId;
    private final Map<String, String> mLabels;
    private final String mLanguage;

    /** @hide */
    @IntDef(
        value = {
            MASTERING_NOT_INDICATED,
            MASTERED_FOR_STEREO,
            MASTERED_FOR_SURROUND,
            MASTERED_FOR_3D,
            MASTERED_FOR_HEADPHONE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MasteringIndicationType {}

    private final @MasteringIndicationType int mMasteringIndication;
    private final boolean mAudioDescriptionAvailable;
    private final boolean mSpokenSubtitlesAvailable;
    private final boolean mDialogueEnhancementAvailable;

    /**
     * No preferred reproduction channel layout.
     */
    public static final int MASTERING_NOT_INDICATED         = 0;
    /**
     * Stereo speaker layout.
     */
    public static final int MASTERED_FOR_STEREO             = 1;
    /**
     * Two-dimensional (e.g. 5.1) speaker layout.
     */
    public static final int MASTERED_FOR_SURROUND           = 2;
    /**
     * Three-dimensional (e.g. 5.1.2) speaker layout.
     */
    public static final int MASTERED_FOR_3D                 = 3;
    /**
     * Prerendered for headphone playback.
     */
    public static final int MASTERED_FOR_HEADPHONE          = 4;

    /**
     * @hide
     */
    @TestApi
    public AudioPresentation(int presentationId,
                        int programId,
                        @NonNull Map<String, String> labels,
                        @NonNull String language,
                        @MasteringIndicationType int masteringIndication,
                        boolean audioDescriptionAvailable,
                        boolean spokenSubtitlesAvailable,
                        boolean dialogueEnhancementAvailable) {
        this.mPresentationId = presentationId;
        this.mProgramId = programId;
        this.mLanguage = language;
        this.mMasteringIndication = masteringIndication;
        this.mAudioDescriptionAvailable = audioDescriptionAvailable;
        this.mSpokenSubtitlesAvailable = spokenSubtitlesAvailable;
        this.mDialogueEnhancementAvailable = dialogueEnhancementAvailable;

        this.mLabels = new HashMap<String, String>(labels);
    }

    /**
     * The framework uses this presentation id to select an audio presentation rendered by a
     * decoder. Presentation id is typically sequential, but does not have to be.
     * @hide
     */
    @TestApi
    public int getPresentationId() {
        return mPresentationId;
    }

    /**
     * The framework uses this program id to select an audio presentation rendered by a decoder.
     * Program id can be used to further uniquely identify the presentation to a decoder.
     * @hide
     */
    @TestApi
    public int getProgramId() {
        return mProgramId;
    }

    /**
     * @return a map of available text labels for this presentation. Each label is indexed by its
     * locale corresponding to the language code as specified by ISO 639-2. Either ISO 639-2/B
     * or ISO 639-2/T could be used.
     */
    public Map<Locale, String> getLabels() {
        Map<Locale, String> localeLabels = new HashMap<>();
        for (Map.Entry<String, String> entry : mLabels.entrySet()) {
            localeLabels.put(new Locale(entry.getKey()), entry.getValue());
        }
        return localeLabels;
    }

    /**
     * @return the locale corresponding to audio presentation's ISO 639-1/639-2 language code.
     */
    public Locale getLocale() {
        return new Locale(mLanguage);
    }

    /**
     * @return the mastering indication of the audio presentation.
     * See {@link #MASTERING_NOT_INDICATED}, {@link #MASTERED_FOR_STEREO},
     * {@link #MASTERED_FOR_SURROUND}, {@link #MASTERED_FOR_3D}, {@link #MASTERED_FOR_HEADPHONE}
     */
    @MasteringIndicationType
    public int getMasteringIndication() {
        return mMasteringIndication;
    }

    /**
     * Indicates whether an audio description for the visually impaired is available.
     * @return {@code true} if audio description is available.
     */
    public boolean hasAudioDescription() {
        return mAudioDescriptionAvailable;
    }

    /**
     * Indicates whether spoken subtitles for the visually impaired are available.
     * @return {@code true} if spoken subtitles are available.
     */
    public boolean hasSpokenSubtitles() {
        return mSpokenSubtitlesAvailable;
    }

    /**
     * Indicates whether dialogue enhancement is available.
     * @return {@code true} if dialogue enhancement is available.
     */
    public boolean hasDialogueEnhancement() {
        return mDialogueEnhancementAvailable;
    }
}
