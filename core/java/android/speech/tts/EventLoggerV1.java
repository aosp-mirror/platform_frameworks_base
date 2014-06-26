/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.text.TextUtils;

/**
 * Writes data about a given speech synthesis request for V1 API to the event
 * logs. The data that is logged includes the calling app, length of the
 * utterance, speech rate / pitch, the latency, and overall time taken.
 */
class EventLoggerV1 extends AbstractEventLogger {
    private final SynthesisRequest mRequest;

    EventLoggerV1(SynthesisRequest request, int callerUid, int callerPid, String serviceApp) {
        super(callerUid, callerPid, serviceApp);
        mRequest = request;
    }

    @Override
    protected void logFailure(int statusCode) {
        // We don't report stopped syntheses because their overall
        // total time spent will be inaccurate (will not correlate with
        // the length of the utterance).
        if (statusCode != TextToSpeech.STOPPED) {
            EventLogTags.writeTtsSpeakFailure(mServiceApp, mCallerUid, mCallerPid,
                    getUtteranceLength(), getLocaleString(),
                    mRequest.getSpeechRate(), mRequest.getPitch());
        }
    }

    @Override
    protected void logSuccess(long audioLatency, long engineLatency, long engineTotal) {
        EventLogTags.writeTtsSpeakSuccess(mServiceApp, mCallerUid, mCallerPid,
                getUtteranceLength(), getLocaleString(),
                mRequest.getSpeechRate(), mRequest.getPitch(),
                engineLatency, engineTotal, audioLatency);
    }

    /**
     * @return the length of the utterance for the given synthesis, 0
     *          if the utterance was {@code null}.
     */
    private int getUtteranceLength() {
        final String utterance = mRequest.getText();
        return utterance == null ? 0 : utterance.length();
    }

    /**
     * Returns a formatted locale string from the synthesis params of the
     * form lang-country-variant.
     */
    private String getLocaleString() {
        StringBuilder sb = new StringBuilder(mRequest.getLanguage());
        if (!TextUtils.isEmpty(mRequest.getCountry())) {
            sb.append('-');
            sb.append(mRequest.getCountry());

            if (!TextUtils.isEmpty(mRequest.getVariant())) {
                sb.append('-');
                sb.append(mRequest.getVariant());
            }
        }

        return sb.toString();
    }
}
