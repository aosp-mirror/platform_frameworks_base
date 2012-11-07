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
import java.util.ArrayList;
import java.util.List;

import android.media.videoeditor.MediaArtistNativeHelper.AlphaMagicSettings;
import android.media.videoeditor.MediaArtistNativeHelper.AudioTransition;
import android.media.videoeditor.MediaArtistNativeHelper.ClipSettings;
import android.media.videoeditor.MediaArtistNativeHelper.EditSettings;
import android.media.videoeditor.MediaArtistNativeHelper.EffectSettings;
import android.media.videoeditor.MediaArtistNativeHelper.SlideTransitionSettings;
import android.media.videoeditor.MediaArtistNativeHelper.TransitionSettings;
import android.media.videoeditor.MediaArtistNativeHelper.VideoTransition;

/**
 * This class is super class for all transitions. Transitions (with the
 * exception of TransitionAtStart and TransitioAtEnd) can only be inserted
 * between media items.
 *
 * Adding a transition between MediaItems makes the
 * duration of the storyboard shorter by the duration of the Transition itself.
 * As a result, if the duration of the transition is larger than the smaller
 * duration of the two MediaItems associated with the Transition, an exception
 * will be thrown.
 *
 * During a transition, the audio track are cross-fading
 * automatically. {@hide}
 */
public abstract class Transition {
    /**
     *  The transition behavior
     */
    private static final int BEHAVIOR_MIN_VALUE = 0;

    /** The transition starts slowly and speed up */
    public static final int BEHAVIOR_SPEED_UP = 0;
    /** The transition start fast and speed down */
    public static final int BEHAVIOR_SPEED_DOWN = 1;
    /** The transition speed is constant */
    public static final int BEHAVIOR_LINEAR = 2;
    /** The transition starts fast and ends fast with a slow middle */
    public static final int BEHAVIOR_MIDDLE_SLOW = 3;
    /** The transition starts slowly and ends slowly with a fast middle */
    public static final int BEHAVIOR_MIDDLE_FAST = 4;

    private static final int BEHAVIOR_MAX_VALUE = 4;

    /**
     *  The unique id of the transition
     */
    private final String mUniqueId;

    /**
     *  The transition is applied at the end of this media item
     */
    private final MediaItem mAfterMediaItem;
    /**
     *  The transition is applied at the beginning of this media item
     */
    private final MediaItem mBeforeMediaItem;

    /**
     *  The transition behavior
     */
    protected final int mBehavior;

    /**
     *  The transition duration
     */
    protected long mDurationMs;

    /**
     *  The transition filename
     */
    protected String mFilename;

    protected MediaArtistNativeHelper mNativeHelper;
    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private Transition() {
        this(null, null, null, 0, 0);
    }

    /**
     * Constructor
     *
     * @param transitionId The transition id
     * @param afterMediaItem The transition is applied to the end of this
     *      media item
     * @param beforeMediaItem The transition is applied to the beginning of
     *      this media item
     * @param durationMs The duration of the transition in milliseconds
     * @param behavior The transition behavior
     */
    protected Transition(String transitionId, MediaItem afterMediaItem,
                         MediaItem beforeMediaItem,long durationMs,
                         int behavior) {
        if (behavior < BEHAVIOR_MIN_VALUE || behavior > BEHAVIOR_MAX_VALUE) {
            throw new IllegalArgumentException("Invalid behavior: " + behavior);
        }
        if ((afterMediaItem == null) && (beforeMediaItem == null)) {
            throw new IllegalArgumentException("Null media items");
        }
        mUniqueId = transitionId;
        mAfterMediaItem = afterMediaItem;
        mBeforeMediaItem = beforeMediaItem;
        mDurationMs = durationMs;
        mBehavior = behavior;
        mNativeHelper = null;
        if (durationMs > getMaximumDuration()) {
            throw new IllegalArgumentException("The duration is too large");
        }
        if (afterMediaItem != null) {
            mNativeHelper = afterMediaItem.getNativeContext();
        }else {
            mNativeHelper = beforeMediaItem.getNativeContext();
        }
    }

    /**
     * Get the ID of the transition.
     *
     * @return The ID of the transition
     */
    public String getId() {
        return mUniqueId;
    }

    /**
     * Get the media item at the end of which the transition is applied.
     *
     * @return The media item at the end of which the transition is applied
     */
    public MediaItem getAfterMediaItem() {
        return mAfterMediaItem;
    }

    /**
     * Get the media item at the beginning of which the transition is applied.
     *
     * @return The media item at the beginning of which the transition is
     *      applied
     */
    public MediaItem getBeforeMediaItem() {
        return mBeforeMediaItem;
    }

    /**
     * Set the duration of the transition.
     *
     * @param durationMs the duration of the transition in milliseconds
     */
    public void setDuration(long durationMs) {
        if (durationMs > getMaximumDuration()) {
            throw new IllegalArgumentException("The duration is too large");
        }

        mDurationMs = durationMs;
        invalidate();
        mNativeHelper.setGeneratePreview(true);
    }

    /**
     * Get the duration of the transition.
     *
     * @return the duration of the transition in milliseconds
     */
    public long getDuration() {
        return mDurationMs;
    }

    /**
     * The duration of a transition cannot be greater than half of the minimum
     * duration of the bounding media items.
     *
     * @return The maximum duration of this transition
     */
    public long getMaximumDuration() {
        if (mAfterMediaItem == null) {
            return mBeforeMediaItem.getTimelineDuration() / 2;
        } else if (mBeforeMediaItem == null) {
            return mAfterMediaItem.getTimelineDuration() / 2;
        } else {
            return (Math.min(mAfterMediaItem.getTimelineDuration(),
                    mBeforeMediaItem.getTimelineDuration()) / 2);
        }
    }

    /**
     * Get the behavior of the transition.
     *
     * @return The behavior
     */
    public int getBehavior() {
        return mBehavior;
    }

    /**
     * Get the transition data.
     *
     * @return The transition data in TransitionSettings object
     * {@link android.media.videoeditor.MediaArtistNativeHelper.TransitionSettings}
     */
    TransitionSettings getTransitionSettings() {
        TransitionAlpha transitionAlpha = null;
        TransitionSliding transitionSliding = null;
        TransitionCrossfade transitionCrossfade = null;
        TransitionFadeBlack transitionFadeBlack = null;
        TransitionSettings transitionSetting = null;
        transitionSetting = new TransitionSettings();
        transitionSetting.duration = (int)getDuration();
        if (this instanceof TransitionAlpha) {
            transitionAlpha = (TransitionAlpha)this;
            transitionSetting.videoTransitionType = VideoTransition.ALPHA_MAGIC;
            transitionSetting.audioTransitionType = AudioTransition.CROSS_FADE;
            transitionSetting.transitionBehaviour = mNativeHelper
            .getVideoTransitionBehaviour(transitionAlpha.getBehavior());
            transitionSetting.alphaSettings = new AlphaMagicSettings();
            transitionSetting.slideSettings = null;
            transitionSetting.alphaSettings.file = transitionAlpha.getPNGMaskFilename();
            transitionSetting.alphaSettings.blendingPercent = transitionAlpha.getBlendingPercent();
            transitionSetting.alphaSettings.invertRotation = transitionAlpha.isInvert();
            transitionSetting.alphaSettings.rgbWidth = transitionAlpha.getRGBFileWidth();
            transitionSetting.alphaSettings.rgbHeight = transitionAlpha.getRGBFileHeight();

        } else if (this instanceof TransitionSliding) {
            transitionSliding = (TransitionSliding)this;
            transitionSetting.videoTransitionType = VideoTransition.SLIDE_TRANSITION;
            transitionSetting.audioTransitionType = AudioTransition.CROSS_FADE;
            transitionSetting.transitionBehaviour = mNativeHelper
            .getVideoTransitionBehaviour(transitionSliding.getBehavior());
            transitionSetting.alphaSettings = null;
            transitionSetting.slideSettings = new SlideTransitionSettings();
            transitionSetting.slideSettings.direction = mNativeHelper
            .getSlideSettingsDirection(transitionSliding.getDirection());
        } else if (this instanceof TransitionCrossfade) {
            transitionCrossfade = (TransitionCrossfade)this;
            transitionSetting.videoTransitionType = VideoTransition.CROSS_FADE;
            transitionSetting.audioTransitionType = AudioTransition.CROSS_FADE;
            transitionSetting.transitionBehaviour = mNativeHelper
            .getVideoTransitionBehaviour(transitionCrossfade.getBehavior());
            transitionSetting.alphaSettings = null;
            transitionSetting.slideSettings = null;
        } else if (this instanceof TransitionFadeBlack) {
            transitionFadeBlack = (TransitionFadeBlack)this;
            transitionSetting.videoTransitionType = VideoTransition.FADE_BLACK;
            transitionSetting.audioTransitionType = AudioTransition.CROSS_FADE;
            transitionSetting.transitionBehaviour = mNativeHelper
            .getVideoTransitionBehaviour(transitionFadeBlack.getBehavior());
            transitionSetting.alphaSettings = null;
            transitionSetting.slideSettings = null;
        }

        return transitionSetting;
    }

    /**
     * Checks if the effect and overlay applied on a media item
     * overlaps with the transition on media item.
     *
     * @param m The media item
     * @param clipSettings The ClipSettings object
     * @param clipNo The clip no.(out of the two media items
     * associated with current transition)for which the effect
     * clip should be generated
     * @return List of effects that overlap with the transition
     */

    List<EffectSettings>  isEffectandOverlayOverlapping(MediaItem m, ClipSettings clipSettings,
                                         int clipNo) {
        List<Effect> effects;
        List<Overlay> overlays;
        List<EffectSettings> effectSettings = new ArrayList<EffectSettings>();
        EffectSettings tmpEffectSettings;

        overlays = m.getAllOverlays();
        for (Overlay overlay : overlays) {
            tmpEffectSettings = mNativeHelper.getOverlaySettings((OverlayFrame)overlay);
            mNativeHelper.adjustEffectsStartTimeAndDuration(tmpEffectSettings,
                    clipSettings.beginCutTime, clipSettings.endCutTime);
            if (tmpEffectSettings.duration != 0) {
                effectSettings.add(tmpEffectSettings);
            }
        }

        effects = m.getAllEffects();
        for (Effect effect : effects) {
            if (effect instanceof EffectColor) {
                tmpEffectSettings = mNativeHelper.getEffectSettings((EffectColor)effect);
                mNativeHelper.adjustEffectsStartTimeAndDuration(tmpEffectSettings,
                        clipSettings.beginCutTime, clipSettings.endCutTime);
                if (tmpEffectSettings.duration != 0) {
                    if (m instanceof MediaVideoItem) {
                        tmpEffectSettings.fiftiesFrameRate = mNativeHelper
                        .GetClosestVideoFrameRate(((MediaVideoItem)m).getFps());
                    }
                    effectSettings.add(tmpEffectSettings);
                }
            }
        }

         return effectSettings;
    }

    /**
     * Generate the video clip for the specified transition. This method may
     * block for a significant amount of time. Before the method completes
     * execution it sets the mFilename to the name of the newly generated
     * transition video clip file.
     */
    void generate() {
        MediaItem m1 = this.getAfterMediaItem();
        MediaItem m2 = this.getBeforeMediaItem();
        ClipSettings clipSettings1 = new ClipSettings();
        ClipSettings clipSettings2 = new ClipSettings();
        TransitionSettings transitionSetting = null;
        EditSettings editSettings = new EditSettings();
        List<EffectSettings> effectSettings_clip1;
        List<EffectSettings> effectSettings_clip2;

        String output = null;

        if (mNativeHelper == null) {
            if (m1 != null)
                mNativeHelper = m1.getNativeContext();
            else if (m2 != null)
                mNativeHelper = m2.getNativeContext();
        }
        transitionSetting = getTransitionSettings();
        if (m1 != null && m2 != null) {
            /* transition between media items */
            clipSettings1 = m1.getClipSettings();
            clipSettings2 = m2.getClipSettings();
            clipSettings1.beginCutTime = (int)(clipSettings1.endCutTime -
                                                              this.mDurationMs);
            clipSettings2.endCutTime = (int)(clipSettings2.beginCutTime +
                                                              this.mDurationMs);
            /*
             * Check how many effects and overlays overlap with transition and
             * generate effect clip first if there is any overlap
             */
            effectSettings_clip1 = isEffectandOverlayOverlapping(m1, clipSettings1,1);
            effectSettings_clip2 = isEffectandOverlayOverlapping(m2, clipSettings2,2);
            for (int index = 0; index < effectSettings_clip2.size(); index++ ) {
                effectSettings_clip2.get(index).startTime += this.mDurationMs;
            }
            editSettings.effectSettingsArray =
                                              new EffectSettings[effectSettings_clip1.size()
                                                 + effectSettings_clip2.size()];
            int i=0,j=0;
            while (i < effectSettings_clip1.size()) {
                editSettings.effectSettingsArray[j] = effectSettings_clip1.get(i);
                i++;
                j++;
            }
            i=0;
            while (i < effectSettings_clip2.size()) {
                editSettings.effectSettingsArray[j] = effectSettings_clip2.get(i);
                i++;
                j++;
            }
        } else if (m1 == null && m2 != null) {
            /* begin transition at first media item */
            m2.generateBlankFrame(clipSettings1);
            clipSettings2 = m2.getClipSettings();
            clipSettings1.endCutTime = (int)(this.mDurationMs + 50);
            clipSettings2.endCutTime = (int)(clipSettings2.beginCutTime +
                                                              this.mDurationMs);
            /*
             * Check how many effects and overlays overlap with transition and
             * generate effect clip first if there is any overlap
             */
            effectSettings_clip2 = isEffectandOverlayOverlapping(m2, clipSettings2,2);
            for (int index = 0; index < effectSettings_clip2.size(); index++ ) {
                effectSettings_clip2.get(index).startTime += this.mDurationMs;
            }
            editSettings.effectSettingsArray = new EffectSettings[effectSettings_clip2.size()];
            int i=0, j=0;
            while (i < effectSettings_clip2.size()) {
                editSettings.effectSettingsArray[j] = effectSettings_clip2.get(i);
                i++;
                j++;
            }
        } else if (m1 != null && m2 == null) {
            /* end transition at last media item */
            clipSettings1 = m1.getClipSettings();
            m1.generateBlankFrame(clipSettings2);
            clipSettings1.beginCutTime = (int)(clipSettings1.endCutTime -
                                                              this.mDurationMs);
            clipSettings2.endCutTime = (int)(this.mDurationMs + 50);
            /*
             * Check how many effects and overlays overlap with transition and
             * generate effect clip first if there is any overlap
             */
            effectSettings_clip1 = isEffectandOverlayOverlapping(m1, clipSettings1,1);
            editSettings.effectSettingsArray = new EffectSettings[effectSettings_clip1.size()];
            int i=0,j=0;
            while (i < effectSettings_clip1.size()) {
                editSettings.effectSettingsArray[j] = effectSettings_clip1.get(i);
                i++;
                j++;
            }
        }

        editSettings.clipSettingsArray = new ClipSettings[2];
        editSettings.clipSettingsArray[0] = clipSettings1;
        editSettings.clipSettingsArray[1] = clipSettings2;
        editSettings.backgroundMusicSettings = null;
        editSettings.transitionSettingsArray = new TransitionSettings[1];
        editSettings.transitionSettingsArray[0] = transitionSetting;
        output = mNativeHelper.generateTransitionClip(editSettings, mUniqueId,
                                                      m1, m2,this);
        setFilename(output);
    }


    /**
     * Set the transition filename.
     */
    void setFilename(String filename) {
        mFilename = filename;
    }

    /**
     * Get the transition filename.
     */
    String getFilename() {
        return mFilename;
    }

    /**
     * Remove any resources associated with this transition
     */
    void invalidate() {
        if (mFilename != null) {
            new File(mFilename).delete();
            mFilename = null;
        }
    }

    /**
     * Check if the transition is generated.
     *
     * @return true if the transition is generated
     */
    boolean isGenerated() {
        return (mFilename != null);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Transition)) {
            return false;
        }
        return mUniqueId.equals(((Transition)object).mUniqueId);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return mUniqueId.hashCode();
    }
}
