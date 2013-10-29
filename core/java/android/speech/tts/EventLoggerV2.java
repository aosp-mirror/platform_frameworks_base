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



/**
 * Writes data about a given speech synthesis request for V2 API to the event logs.
 * The data that is logged includes the calling app, length of the utterance,
 * synthesis request configuration and the latency and overall time taken.
 */
class EventLoggerV2 extends AbstractEventLogger {
    private final SynthesisRequestV2 mRequest;

    EventLoggerV2(SynthesisRequestV2 request, int callerUid, int callerPid, String serviceApp) {
        super(callerUid, callerPid, serviceApp);
        mRequest = request;
    }

    @Override
    protected void logFailure(int statusCode) {
        // We don't report stopped syntheses because their overall
        // total time spent will be inaccurate (will not correlate with
        // the length of the utterance).
        if (statusCode != TextToSpeechClient.Status.STOPPED) {
            EventLogTags.writeTtsV2SpeakFailure(mServiceApp,
                    mCallerUid, mCallerPid, getUtteranceLength(), getRequestConfigString(), statusCode);
        }
    }

    @Override
    protected void logSuccess(long audioLatency, long engineLatency, long engineTotal) {
        EventLogTags.writeTtsV2SpeakSuccess(mServiceApp,
                mCallerUid, mCallerPid, getUtteranceLength(), getRequestConfigString(),
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
     * Returns a string representation of the synthesis request configuration.
     */
    private String getRequestConfigString() {
        // Ensure the bundles are unparceled.
        mRequest.getVoiceParams().size();
        mRequest.getAudioParams().size();

        return new StringBuilder(64).append("VoiceName: ").append(mRequest.getVoiceName())
            .append(" ,VoiceParams: ").append(mRequest.getVoiceParams())
            .append(" ,SystemParams: ").append(mRequest.getAudioParams())
            .append("]").toString();
    }
}
