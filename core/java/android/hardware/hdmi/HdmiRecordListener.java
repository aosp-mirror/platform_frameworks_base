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

package android.hardware.hdmi;

import android.annotation.SystemApi;
import android.hardware.hdmi.HdmiRecordSources.RecordSource;

/**
 * Listener for hdmi record feature including one touch record and timer recording.
 * @hide
 */
@SystemApi
public abstract class HdmiRecordListener {
    protected HdmiRecordListener() {}

    /**
     * Called when TV received one touch record request from record device. The client of this
     * should use {@link HdmiRecordSources} to return it.
     *
     * @param recorderAddress
     * @return record source to be used for recording. Null if no device is available.
     */
    public abstract RecordSource getOneTouchRecordSource(int recorderAddress);

    /**
     * Called when one touch record is started or failed during initialization.
     *
     * @param result result code. For more details, please look at all constants starting with
     *            "ONE_TOUCH_RECORD_". Only
     *            {@link HdmiControlManager#ONE_TOUCH_RECORD_RECORDING_CURRENTLY_SELECTED_SOURCE},
     *            {@link HdmiControlManager#ONE_TOUCH_RECORD_RECORDING_DIGITAL_SERVICE},
     *            {@link HdmiControlManager#ONE_TOUCH_RECORD_RECORDING_ANALOGUE_SERVICE}, and
     *            {@link HdmiControlManager#ONE_TOUCH_RECORD_RECORDING_EXTERNAL_INPUT} mean normal
     *            start of recording; otherwise, describes failure.
     */
    public void onOneTouchRecordResult(int result) {
    }

    /**
     * Called when timer recording is started or failed during initialization.
     *
     * @param result The most significant three bytes may contain result of &lt;Timer Status&gt;
     *        while the least significant byte may have error message like
     *        {@link HdmiControlManager#TIME_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION}
     *        or
     *        {@link HdmiControlManager #TIME_RECORDING_RESULT_EXTRA_FAIL_TO_RECORD_SELECTED_SOURCE}
     *        . If the least significant byte has non zero value the most significant three bytes
     *        may have 0 value.
     */
    // TODO: implement result parser.
    public void onTimerRecordingResult(int result) {
    }
}
