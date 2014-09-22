/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.speech.tts;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Characteristics and features of a Text-To-Speech Voice. Each TTS Engine can expose
 * multiple voices for each locale, with different set of features.
 */
public class Voice implements Parcelable {
    /** Very low, but still intelligible quality of speech synthesis */
    public static final int QUALITY_VERY_LOW = 100;

    /** Low, not human-like quality of speech synthesis */
    public static final int QUALITY_LOW = 200;

    /** Normal quality of speech synthesis */
    public static final int QUALITY_NORMAL = 300;

    /** High, human-like quality of speech synthesis */
    public static final int QUALITY_HIGH = 400;

    /** Very high, almost human-indistinguishable quality of speech synthesis */
    public static final int QUALITY_VERY_HIGH = 500;

    /** Very low expected synthesizer latency (< 20ms) */
    public static final int LATENCY_VERY_LOW = 100;

    /** Low expected synthesizer latency (~20ms) */
    public static final int LATENCY_LOW = 200;

    /** Normal expected synthesizer latency (~50ms) */
    public static final int LATENCY_NORMAL = 300;

    /** Network based expected synthesizer latency (~200ms) */
    public static final int LATENCY_HIGH = 400;

    /** Very slow network based expected synthesizer latency (> 200ms) */
    public static final int LATENCY_VERY_HIGH = 500;

    private final String mName;
    private final Locale mLocale;
    private final int mQuality;
    private final int mLatency;
    private final boolean mRequiresNetworkConnection;
    private final Set<String> mFeatures;

    public Voice(String name,
            Locale locale,
            int quality,
            int latency,
            boolean requiresNetworkConnection,
            Set<String> features) {
        this.mName = name;
        this.mLocale = locale;
        this.mQuality = quality;
        this.mLatency = latency;
        this.mRequiresNetworkConnection = requiresNetworkConnection;
        this.mFeatures = features;
    }

    private Voice(Parcel in) {
        this.mName = in.readString();
        this.mLocale = (Locale)in.readSerializable();
        this.mQuality = in.readInt();
        this.mLatency = in.readInt();
        this.mRequiresNetworkConnection = (in.readByte() == 1);
        this.mFeatures = new HashSet<String>();
        Collections.addAll(this.mFeatures, in.readStringArray());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeSerializable(mLocale);
        dest.writeInt(mQuality);
        dest.writeInt(mLatency);
        dest.writeByte((byte) (mRequiresNetworkConnection ? 1 : 0));
        dest.writeStringList(new ArrayList<String>(mFeatures));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<Voice> CREATOR = new Parcelable.Creator<Voice>() {
        @Override
        public Voice createFromParcel(Parcel in) {
            return new Voice(in);
        }

        @Override
        public Voice[] newArray(int size) {
            return new Voice[size];
        }
    };


    /**
     * @return The voice's locale
     */
    public Locale getLocale() {
        return mLocale;
    }

    /**
     * @return The voice's quality (higher is better)
     * @see #QUALITY_VERY_HIGH
     * @see #QUALITY_HIGH
     * @see #QUALITY_NORMAL
     * @see #QUALITY_LOW
     * @see #QUALITY_VERY_LOW
     */
    public int getQuality() {
        return mQuality;
    }

    /**
     * @return The voice's latency (lower is better)
     * @see #LATENCY_VERY_LOW
     * @see #LATENCY_LOW
     * @see #LATENCY_NORMAL
     * @see #LATENCY_HIGH
     * @see #LATENCY_VERY_HIGH
     */
    public int getLatency() {
        return mLatency;
    }

    /**
     * @return Does the Voice require a network connection to work.
     */
    public boolean isNetworkConnectionRequired() {
        return mRequiresNetworkConnection;
    }

    /**
     * @return Unique voice name.
     */
    public String getName() {
        return mName;
    }

    /**
     * Returns the set of features it supports for a given voice.
     * Features can either be framework defined, e.g.
     * {@link TextToSpeech.Engine#KEY_FEATURE_NETWORK_TIMEOUT_MS} or engine specific.
     * Engine specific keys must be prefixed by the name of the engine they
     * are intended for. These keys can be used as parameters to
     * {@link TextToSpeech#speak(String, int, java.util.HashMap)} and
     * {@link TextToSpeech#synthesizeToFile(String, java.util.HashMap, String)}.
     *
     * Features values are strings and their values must met restrictions described in their
     * documentation.
     *
     * @return Set instance. May return {@code null} on error.
     */
    public Set<String> getFeatures() {
        return mFeatures;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(64);
        return builder.append("Voice[Name: ").append(mName)
                .append(", locale: ").append(mLocale)
                .append(", quality: ").append(mQuality)
                .append(", latency: ").append(mLatency)
                .append(", requiresNetwork: ").append(mRequiresNetworkConnection)
                .append(", features: ").append(mFeatures.toString())
                .append("]").toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mFeatures == null) ? 0 : mFeatures.hashCode());
        result = prime * result + mLatency;
        result = prime * result + ((mLocale == null) ? 0 : mLocale.hashCode());
        result = prime * result + ((mName == null) ? 0 : mName.hashCode());
        result = prime * result + mQuality;
        result = prime * result + (mRequiresNetworkConnection ? 1231 : 1237);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Voice other = (Voice) obj;
        if (mFeatures == null) {
            if (other.mFeatures != null) {
                return false;
            }
        } else if (!mFeatures.equals(other.mFeatures)) {
            return false;
        }
        if (mLatency != other.mLatency) {
            return false;
        }
        if (mLocale == null) {
            if (other.mLocale != null) {
                return false;
            }
        } else if (!mLocale.equals(other.mLocale)) {
            return false;
        }
        if (mName == null) {
            if (other.mName != null) {
                return false;
            }
        } else if (!mName.equals(other.mName)) {
            return false;
        }
        if (mQuality != other.mQuality) {
            return false;
        }
        if (mRequiresNetworkConnection != other.mRequiresNetworkConnection) {
            return false;
        }
        return true;
    }
}
