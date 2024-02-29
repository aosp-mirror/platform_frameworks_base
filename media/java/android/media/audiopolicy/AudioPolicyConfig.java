/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.audiopolicy;

import android.annotation.NonNull;
import android.media.audiopolicy.AudioMixingRule.AudioMixMatchCriterion;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @hide
 * Internal storage class for AudioPolicy configuration.
 */
public class AudioPolicyConfig implements Parcelable {

    private static final String TAG = "AudioPolicyConfig";

    protected final ArrayList<AudioMix> mMixes;
    protected int mDuckingPolicy = AudioPolicy.FOCUS_POLICY_DUCKING_IN_APP;

    private String mRegistrationId = null;

    // Corresponds to id of next mix to be registered.
    private int mMixCounter = 0;

    protected AudioPolicyConfig(AudioPolicyConfig conf) {
        mMixes = conf.mMixes;
    }

    @VisibleForTesting
    public AudioPolicyConfig(ArrayList<AudioMix> mixes) {
        mMixes = mixes;
    }

    /**
     * Add an {@link AudioMix} to be part of the audio policy being built.
     * @param mix a non-null {@link AudioMix} to be part of the audio policy.
     * @return the same Builder instance.
     * @throws IllegalArgumentException
     */
    public void addMix(AudioMix mix) throws IllegalArgumentException {
        if (mix == null) {
            throw new IllegalArgumentException("Illegal null AudioMix argument");
        }
        mMixes.add(mix);
    }

    public ArrayList<AudioMix> getMixes() {
        return mMixes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMixes);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mMixes.size());
        for (AudioMix mix : mMixes) {
            mix.writeToParcel(dest, flags);
        }
    }

    private AudioPolicyConfig(Parcel in) {
        int nbMixes = in.readInt();
        mMixes = new ArrayList<>(nbMixes);
        for (int i = 0 ; i < nbMixes ; i++) {
            mMixes.add(AudioMix.CREATOR.createFromParcel(in));
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AudioPolicyConfig> CREATOR =
            new Parcelable.Creator<>() {
        /**
         * Rebuilds an AudioPolicyConfig previously stored with writeToParcel().
         * @param p Parcel object to read the AudioPolicyConfig from
         * @return a new AudioPolicyConfig created from the data in the parcel
         */
        public AudioPolicyConfig createFromParcel(Parcel p) {
            return new AudioPolicyConfig(p);
        }
        public AudioPolicyConfig[] newArray(int size) {
            return new AudioPolicyConfig[size];
        }
    };

    public String toLogFriendlyString () {
        String textDump = new String("android.media.audiopolicy.AudioPolicyConfig:\n");
        textDump += mMixes.size() + " AudioMix, reg:" + mRegistrationId + "\n";
        for(AudioMix mix : mMixes) {
            // write mix route flags
            textDump += "* route flags=0x" + Integer.toHexString(mix.getRouteFlags()) + "\n";
            // write mix format
            textDump += "  rate=" + mix.getFormat().getSampleRate() + "Hz\n";
            textDump += "  encoding=" + mix.getFormat().getEncoding() + "\n";
            textDump += "  channels=0x";
            textDump += Integer.toHexString(mix.getFormat().getChannelMask()).toUpperCase() + "\n";
            textDump += "  ignore playback capture opt out="
                    + mix.getRule().allowPrivilegedMediaPlaybackCapture() + "\n";
            textDump += "  allow voice communication capture="
                    + mix.getRule().voiceCommunicationCaptureAllowed() + "\n";
            // write mix rules
            textDump += "  specified mix type="
                    + mix.getRule().getTargetMixRole() + "\n";
            final ArrayList<AudioMixMatchCriterion> criteria = mix.getRule().getCriteria();
            for (AudioMixMatchCriterion criterion : criteria) {
                switch(criterion.mRule) {
                    case AudioMixingRule.RULE_EXCLUDE_ATTRIBUTE_USAGE:
                        textDump += "  exclude usage ";
                        textDump += criterion.mAttr.usageToString();
                        break;
                    case AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE:
                        textDump += "  match usage ";
                        textDump += criterion.mAttr.usageToString();
                        break;
                    case AudioMixingRule.RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET:
                        textDump += "  exclude capture preset ";
                        textDump += criterion.mAttr.getCapturePreset();
                        break;
                    case AudioMixingRule.RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                        textDump += "  match capture preset ";
                        textDump += criterion.mAttr.getCapturePreset();
                        break;
                    case AudioMixingRule.RULE_MATCH_UID:
                        textDump += "  match UID ";
                        textDump += criterion.mIntProp;
                        break;
                    case AudioMixingRule.RULE_EXCLUDE_UID:
                        textDump += "  exclude UID ";
                        textDump += criterion.mIntProp;
                        break;
                    case AudioMixingRule.RULE_MATCH_USERID:
                        textDump += "  match userId ";
                        textDump += criterion.mIntProp;
                        break;
                    case AudioMixingRule.RULE_EXCLUDE_USERID:
                        textDump += "  exclude userId ";
                        textDump += criterion.mIntProp;
                        break;
                    case AudioMixingRule.RULE_MATCH_AUDIO_SESSION_ID:
                        textDump += " match audio session id";
                        textDump += criterion.mIntProp;
                        break;
                    case AudioMixingRule.RULE_EXCLUDE_AUDIO_SESSION_ID:
                        textDump += " exclude audio session id ";
                        textDump += criterion.mIntProp;
                        break;
                    default:
                        textDump += "invalid rule!";
                }
                textDump += "\n";
            }
        }
        return textDump;
    }

    /**
     * Very short dump of configuration
     * @return a condensed dump of configuration, uniquely identifies a policy in a log
     */
    public String toCompactLogString() {
        String compactDump = "reg:" + mRegistrationId;
        int mixNum = 0;
        for (AudioMix mix : mMixes) {
            compactDump += " Mix:" + mixNum + "-Typ:" + mixTypePrefix(mix.getMixType())
                    + "-Rul:" + mix.getRule().getCriteria().size();
            mixNum++;
        }
        return compactDump;
    }

    private static String mixTypePrefix(int mixType) {
        switch (mixType) {
            case AudioMix.MIX_TYPE_PLAYERS:
                return "p";
            case AudioMix.MIX_TYPE_RECORDERS:
                return "r";
            case AudioMix.MIX_TYPE_INVALID:
            default:
                return "#";

        }
    }

    protected void reset() {
        mMixCounter = 0;
    }

    protected void setRegistration(String regId) {
        final boolean currentRegNull = (mRegistrationId == null) || mRegistrationId.isEmpty();
        final boolean newRegNull = (regId == null) || regId.isEmpty();
        if (!currentRegNull && !newRegNull && !mRegistrationId.equals(regId)) {
            Log.e(TAG, "Invalid registration transition from " + mRegistrationId + " to " + regId);
            return;
        }
        mRegistrationId = regId == null ? "" : regId;
        for (AudioMix mix : mMixes) {
            setMixRegistration(mix);
        }
    }

    protected void setMixRegistration(@NonNull final AudioMix mix) {
        if (!mRegistrationId.isEmpty()) {
            if ((mix.getRouteFlags() & AudioMix.ROUTE_FLAG_LOOP_BACK) ==
                    AudioMix.ROUTE_FLAG_LOOP_BACK) {
                mix.setRegistration(mRegistrationId + "mix" + mixTypeId(mix.getMixType()) + ":"
                        + mMixCounter++);
            } else if ((mix.getRouteFlags() & AudioMix.ROUTE_FLAG_RENDER) ==
                    AudioMix.ROUTE_FLAG_RENDER) {
                mix.setRegistration(mix.mDeviceAddress);
            }
        } else {
            mix.setRegistration("");
        }
    }

    @GuardedBy("mMixes")
    protected void add(@NonNull ArrayList<AudioMix> mixes) {
        for (AudioMix mix : mixes) {
            if (mix.getRegistration() == null || mix.getRegistration().isEmpty()) {
                setMixRegistration(mix);
            }
            mMixes.add(mix);
        }
    }

    @GuardedBy("mMixes")
    protected void remove(@NonNull ArrayList<AudioMix> mixes) {
        for (AudioMix mix : mixes) {
            mMixes.remove(mix);
        }
    }

    /**
     * Update audio mixing rules for already registered {@link AudioMix}-es.
     *
     * @param audioMixingRuleUpdates {@link List} of {@link Pair}-s containing {@link AudioMix} to
     *                                           be updated and the new {@link AudioMixingRule}.
     */
    public void updateMixingRules(
            @NonNull List<Pair<AudioMix, AudioMixingRule>> audioMixingRuleUpdates) {
        Objects.requireNonNull(audioMixingRuleUpdates).forEach(
                update -> updateMixingRule(update.first, update.second));
    }

    private void updateMixingRule(AudioMix audioMixToUpdate, AudioMixingRule audioMixingRule) {
        mMixes.stream().filter(audioMixToUpdate::equals).findAny().ifPresent(
                mix -> mix.setAudioMixingRule(audioMixingRule));
    }

    private static String mixTypeId(int type) {
        if (type == AudioMix.MIX_TYPE_PLAYERS) return "p";
        else if (type == AudioMix.MIX_TYPE_RECORDERS) return "r";
        else return "i";
    }

    protected String getRegistration() {
        return mRegistrationId;
    }
}
