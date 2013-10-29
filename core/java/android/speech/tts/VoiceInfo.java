package android.speech.tts;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Locale;

/**
 * Characteristics and features of a Text-To-Speech Voice. Each TTS Engine can expose
 * multiple voices for multiple locales, with different set of features.
 *
 * Each VoiceInfo has an unique name. This name can be obtained using the {@link #getName()} method
 * and will persist until the client is asked to re-evaluate the list of available voices in the
 * {@link TextToSpeechClient.ConnectionCallbacks#onEngineStatusChange(android.speech.tts.TextToSpeechClient.EngineStatus)}
 * callback. The name can be used to reference a VoiceInfo in an instance of {@link RequestConfig};
 * the {@link TextToSpeechClient.Params#FALLBACK_VOICE_NAME} voice parameter is an example of this.
 * It is recommended that the voice name never change during the TTS service lifetime.
 */
public final class VoiceInfo implements Parcelable {
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

    /** Additional feature key, with string value, gender of the speaker */
    public static final String FEATURE_SPEAKER_GENDER = "speakerGender";

    /** Additional feature key, with integer value, speaking speed in words per minute
     * when {@link TextToSpeechClient.Params#SPEECH_SPEED} parameter is set to {@code 1.0} */
    public static final String FEATURE_WORDS_PER_MINUTE = "wordsPerMinute";

    /**
     * Additional feature key, with boolean value, that indicates that voice may need to
     * download additional data if used for synthesis.
     *
     * Making a request with a voice that has this feature may result in a
     * {@link TextToSpeechClient.Status#ERROR_DOWNLOADING_ADDITIONAL_DATA} error. It's recommended
     * to set the {@link TextToSpeechClient.Params#FALLBACK_VOICE_NAME} voice parameter to reference
     * a fully installed voice (or network voice) that can serve as replacement.
     *
     * Note: It's a good practice for a TTS engine to provide a sensible fallback voice as the
     * default value for {@link TextToSpeechClient.Params#FALLBACK_VOICE_NAME} parameter if this
     * feature is present.
     */
    public static final String FEATURE_MAY_AUTOINSTALL = "mayAutoInstall";

    private final String mName;
    private final Locale mLocale;
    private final int mQuality;
    private final int mLatency;
    private final boolean mRequiresNetworkConnection;
    private final Bundle mParams;
    private final Bundle mAdditionalFeatures;

    private VoiceInfo(Parcel in) {
        this.mName = in.readString();
        String[] localesData = new String[3];
        in.readStringArray(localesData);
        this.mLocale = new Locale(localesData[0], localesData[1], localesData[2]);

        this.mQuality = in.readInt();
        this.mLatency = in.readInt();
        this.mRequiresNetworkConnection = (in.readByte() == 1);

        this.mParams = in.readBundle();
        this.mAdditionalFeatures = in.readBundle();
    }

    private VoiceInfo(String name,
            Locale locale,
            int quality,
            int latency,
            boolean requiresNetworkConnection,
            Bundle params,
            Bundle additionalFeatures) {
        this.mName = name;
        this.mLocale = locale;
        this.mQuality = quality;
        this.mLatency = latency;
        this.mRequiresNetworkConnection = requiresNetworkConnection;
        this.mParams = params;
        this.mAdditionalFeatures = additionalFeatures;
    }

    /** Builder, allows TTS engines to create VoiceInfo instances. */
    public static final class Builder {
        private String name;
        private Locale locale;
        private int quality = VoiceInfo.QUALITY_NORMAL;
        private int latency = VoiceInfo.LATENCY_NORMAL;
        private boolean requiresNetworkConnection;
        private Bundle params;
        private Bundle additionalFeatures;

        public Builder() {

        }

        /**
         * Copy fields from given VoiceInfo instance.
         */
        public Builder(VoiceInfo voiceInfo) {
            this.name = voiceInfo.mName;
            this.locale = voiceInfo.mLocale;
            this.quality = voiceInfo.mQuality;
            this.latency = voiceInfo.mLatency;
            this.requiresNetworkConnection = voiceInfo.mRequiresNetworkConnection;
            this.params = (Bundle)voiceInfo.mParams.clone();
            this.additionalFeatures = (Bundle) voiceInfo.mAdditionalFeatures.clone();
        }

        /**
         * Sets the voice's unique name. It will be used by clients to reference the voice used by a
         * request.
         *
         * It's recommended that each voice use the same consistent name during the TTS service
         * lifetime.
         */
        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets voice locale. This has to be a valid locale, built from ISO 639-1 and ISO 3166-1
         * two letter codes.
         */
        public Builder setLocale(Locale locale) {
            this.locale = locale;
            return this;
        }

        /**
         * Sets map of all available request parameters with their default values.
         * Some common parameter names can be found in {@link TextToSpeechClient.Params} static
         * members.
         */
        public Builder setParamsWithDefaults(Bundle params) {
            this.params = params;
            return this;
        }

        /**
         * Sets map of additional voice features. Some common feature names can be found in
         * {@link VoiceInfo} static members.
         */
        public Builder setAdditionalFeatures(Bundle additionalFeatures) {
            this.additionalFeatures = additionalFeatures;
            return this;
        }

        /**
         * Sets the voice quality (higher is better).
         */
        public Builder setQuality(int quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Sets the voice latency (lower is better).
         */
        public Builder setLatency(int latency) {
            this.latency = latency;
            return this;
        }

        /**
         * Sets whether the voice requires network connection to work properly.
         */
        public Builder setRequiresNetworkConnection(boolean requiresNetworkConnection) {
            this.requiresNetworkConnection = requiresNetworkConnection;
            return this;
        }

        /**
         * @return The built VoiceInfo instance.
         */
        public VoiceInfo build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalStateException("Name can't be null or empty");
            }
            if (locale == null) {
                throw new IllegalStateException("Locale can't be null");
            }

            return new VoiceInfo(name, locale, quality, latency,
                        requiresNetworkConnection,
                        ((params == null) ? new Bundle() :
                            (Bundle)params.clone()),
                        ((additionalFeatures == null) ? new Bundle() :
                            (Bundle)additionalFeatures.clone()));
        }
    }

    /**
     * @hide
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mName);
        String[] localesData = new String[]{mLocale.getLanguage(), mLocale.getCountry(), mLocale.getVariant()};
        dest.writeStringArray(localesData);
        dest.writeInt(mQuality);
        dest.writeInt(mLatency);
        dest.writeByte((byte) (mRequiresNetworkConnection ? 1 : 0));
        dest.writeBundle(mParams);
        dest.writeBundle(mAdditionalFeatures);
    }

    /**
     * @hide
     */
    public static final Parcelable.Creator<VoiceInfo> CREATOR = new Parcelable.Creator<VoiceInfo>() {
        @Override
        public VoiceInfo createFromParcel(Parcel in) {
            return new VoiceInfo(in);
        }

        @Override
        public VoiceInfo[] newArray(int size) {
            return new VoiceInfo[size];
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
     */
    public int getQuality() {
        return mQuality;
    }

    /**
     * @return The voice's latency (lower is better)
     */
    public int getLatency() {
        return mLatency;
    }

    /**
     * @return Does the Voice require a network connection to work.
     */
    public boolean getRequiresNetworkConnection() {
        return mRequiresNetworkConnection;
    }

    /**
     * @return Bundle of all available parameters with their default values.
     */
    public Bundle getParamsWithDefaults() {
        return mParams;
    }

    /**
     * @return Unique voice name.
     *
     * Each VoiceInfo has an unique name, that persists until client is asked to re-evaluate the
     * set of the available languages in the {@link TextToSpeechClient.ConnectionCallbacks#onEngineStatusChange(android.speech.tts.TextToSpeechClient.EngineStatus)}
     * callback (Voice may disappear from the set if voice was removed by the user).
     */
    public String getName() {
        return mName;
    }

    /**
     * @return Additional features of the voice.
     */
    public Bundle getAdditionalFeatures() {
        return mAdditionalFeatures;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(64);
        return builder.append("VoiceInfo[Name: ").append(mName)
                .append(" ,locale: ").append(mLocale)
                .append(" ,quality: ").append(mQuality)
                .append(" ,latency: ").append(mLatency)
                .append(" ,requiresNetwork: ").append(mRequiresNetworkConnection)
                .append(" ,paramsWithDefaults: ").append(mParams.toString())
                .append(" ,additionalFeatures: ").append(mAdditionalFeatures.toString())
                .append("]").toString();
    }
}
