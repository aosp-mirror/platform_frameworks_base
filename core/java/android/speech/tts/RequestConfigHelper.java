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

import android.speech.tts.TextToSpeechClient.EngineStatus;

import java.util.Locale;

/**
 * Set of common heuristics for selecting {@link VoiceInfo} from
 * {@link TextToSpeechClient#getEngineStatus()} output.
 */
public final class RequestConfigHelper {
    private RequestConfigHelper() {}

    /**
     * Interface for scoring VoiceInfo object.
     */
    public static interface VoiceScorer {
        /**
         * Score VoiceInfo. If the score is less than or equal to zero, that voice is discarded.
         * If two voices have same desired primary characteristics (highest quality, lowest
         * latency or others), the one with the higher score is selected.
         */
        public int scoreVoice(VoiceInfo voiceInfo);
    }

    /**
     * Score positively voices that exactly match the locale supplied to the constructor.
     */
    public static final class ExactLocaleMatcher implements VoiceScorer {
        private final Locale mLocale;

        /**
         * Score positively voices that exactly match the given locale
         * @param locale Reference locale. If null, the system default locale for the
         * current user will be used ({@link Locale#getDefault()}).
         */
        public ExactLocaleMatcher(Locale locale) {
            if (locale == null) {
                mLocale = Locale.getDefault();
            } else {
                mLocale = locale;
            }
        }
        @Override
        public int scoreVoice(VoiceInfo voiceInfo) {
            return mLocale.equals(voiceInfo.getLocale()) ? 1 : 0;
        }
    }

    /**
     * Score positively voices that match exactly the given locale (score 3)
     * or that share same language and country (score 2), or that share just a language (score 1).
     */
    public static final class LanguageMatcher implements VoiceScorer {
        private final Locale mLocale;

        /**
         * Score positively voices with similar locale.
         * @param locale Reference locale.  If null, the system default locale for the
         * current user will be used ({@link Locale#getDefault()}).
         */
        public LanguageMatcher(Locale locale) {
            if (locale == null) {
                mLocale = Locale.getDefault();
            } else {
                mLocale = locale;
            }
        }

        @Override
        public int scoreVoice(VoiceInfo voiceInfo) {
            final Locale voiceLocale = voiceInfo.getLocale();
            if (mLocale.equals(voiceLocale)) {
                return 3;
            } else {
                if (mLocale.getLanguage().equals(voiceLocale.getLanguage())) {
                    if (mLocale.getCountry().equals(voiceLocale.getCountry())) {
                        return 2;
                    }
                    return 1;
                }
                return 0;
            }
        }
    }

    /**
     * Get the highest quality voice from voices that score more than zero from the passed scorer.
     * If there is more than one voice with the same highest quality, then this method returns one
     * with the highest score. If they share same score as well, one with the lower index in the
     * voices list is returned.
     *
     * @param engineStatus
     *            Voices status received from a {@link TextToSpeechClient#getEngineStatus()} call.
     * @param voiceScorer
     *            Used to discard unsuitable voices and help settle cases where more than
     *            one voice has the desired characteristic.
     * @param hasToBeEmbedded
     *            If true, require the voice to be an embedded voice (no network
     *            access will be required for synthesis).
     */
    private static VoiceInfo getHighestQualityVoice(EngineStatus engineStatus,
            VoiceScorer voiceScorer, boolean hasToBeEmbedded) {
        VoiceInfo bestVoice = null;
        int bestScoreMatch = 1;
        int bestVoiceQuality = 0;

        for (VoiceInfo voice : engineStatus.getVoices()) {
            int score = voiceScorer.scoreVoice(voice);
            if (score <= 0 || hasToBeEmbedded && voice.getRequiresNetworkConnection()
                    || voice.getQuality() < bestVoiceQuality) {
                continue;
            }

            if (bestVoice == null ||
                    voice.getQuality() > bestVoiceQuality ||
                    score > bestScoreMatch) {
                bestVoice = voice;
                bestScoreMatch = score;
                bestVoiceQuality = voice.getQuality();
            }
        }
        return bestVoice;
    }

    /**
     * Get highest quality voice.
     *
     * Highest quality voice is selected from voices that score more than zero from the passed
     * scorer. If there is more than one voice with the same highest quality, then this method
     * will return one with the highest score. If they share same score as well, one with the lower
     * index in the voices list is returned.

     * @param engineStatus
     *            Voices status received from a {@link TextToSpeechClient#getEngineStatus()} call.
     * @param hasToBeEmbedded
     *            If true, require the voice to be an embedded voice (no network
     *            access will be required for synthesis).
     * @param voiceScorer
     *            Scorer is used to discard unsuitable voices and help settle cases where more than
     *            one voice has highest quality.
     * @return RequestConfig with selected voice or null if suitable voice was not found.
     */
    public static RequestConfig highestQuality(EngineStatus engineStatus,
            boolean hasToBeEmbedded, VoiceScorer voiceScorer) {
        VoiceInfo voice = getHighestQualityVoice(engineStatus, voiceScorer, hasToBeEmbedded);
        if (voice == null) {
            return null;
        }
        return RequestConfig.Builder.newBuilder().setVoice(voice).build();
    }

    /**
     * Get highest quality voice for the TTS default locale.
     *
     * Call {@link #highestQuality(EngineStatus, boolean, VoiceScorer)} with
     * {@link LanguageMatcher} set to the {@link EngineStatus#getDefaultLocale()}.
     *
     * @param engineStatus
     *            Voices status received from a {@link TextToSpeechClient#getEngineStatus()} call.
     * @param hasToBeEmbedded
     *            If true, require the voice to be an embedded voice (no network
     *            access will be required for synthesis).
     * @return RequestConfig with selected voice or null if suitable voice was not found.
     */
    public static RequestConfig highestQuality(EngineStatus engineStatus,
            boolean hasToBeEmbedded) {
        return highestQuality(engineStatus, hasToBeEmbedded,
                new LanguageMatcher(engineStatus.getDefaultLocale()));
    }

}
