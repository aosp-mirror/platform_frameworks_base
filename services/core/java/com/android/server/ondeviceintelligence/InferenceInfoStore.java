/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.ondeviceintelligence;

import android.app.ondeviceintelligence.InferenceInfo;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService;
import android.util.Slog;

import java.io.IOException;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

public class InferenceInfoStore {
    private static final String TAG = "InferenceInfoStore";
    private final TreeSet<InferenceInfo> inferenceInfos;
    private final long maxAgeMs;

    public InferenceInfoStore(long maxAgeMs) {
        this.maxAgeMs = maxAgeMs;
        this.inferenceInfos = new TreeSet<>(
                Comparator.comparingLong(InferenceInfo::getStartTimeMs));
    }

    public List<InferenceInfo> getLatestInferenceInfo(long startTimeEpochMillis) {
        return inferenceInfos.stream().filter(
                info -> info.getStartTimeMs() > startTimeEpochMillis).toList();
    }

    public void addInferenceInfoFromBundle(PersistableBundle pb) {
        if (!pb.containsKey(OnDeviceSandboxedInferenceService.INFERENCE_INFO_BUNDLE_KEY)) {
            return;
        }

        try {
            String infoBytesBase64String = pb.getString(
                    OnDeviceSandboxedInferenceService.INFERENCE_INFO_BUNDLE_KEY);
            if (infoBytesBase64String != null) {
                byte[] infoBytes = Base64.getDecoder().decode(infoBytesBase64String);
                com.android.server.ondeviceintelligence.nano.InferenceInfo inferenceInfo =
                        com.android.server.ondeviceintelligence.nano.InferenceInfo.parseFrom(
                                infoBytes);
                add(inferenceInfo);
            }
        } catch (IOException e) {
            Slog.e(TAG, "Unable to parse InferenceInfo from the received bytes.");
        }
    }

    public void addInferenceInfoFromBundle(Bundle b) {
        if (!b.containsKey(OnDeviceSandboxedInferenceService.INFERENCE_INFO_BUNDLE_KEY)) {
            return;
        }

        try {
            byte[] infoBytes = b.getByteArray(
                    OnDeviceSandboxedInferenceService.INFERENCE_INFO_BUNDLE_KEY);
            if (infoBytes != null) {
                com.android.server.ondeviceintelligence.nano.InferenceInfo inferenceInfo =
                        com.android.server.ondeviceintelligence.nano.InferenceInfo.parseFrom(
                                infoBytes);
                add(inferenceInfo);
            }
        } catch (IOException e) {
            Slog.e(TAG, "Unable to parse InferenceInfo from the received bytes.");
        }
    }

    private synchronized void add(com.android.server.ondeviceintelligence.nano.InferenceInfo info) {
        while (System.currentTimeMillis() - inferenceInfos.first().getStartTimeMs() > maxAgeMs) {
            inferenceInfos.pollFirst();
        }
        inferenceInfos.add(toInferenceInfo(info));
    }

    private static InferenceInfo toInferenceInfo(
            com.android.server.ondeviceintelligence.nano.InferenceInfo info) {
        return new InferenceInfo.Builder().setUid(info.uid).setStartTimeMs(
                info.startTimeMs).setEndTimeMs(info.endTimeMs).setSuspendedTimeMs(
                info.suspendedTimeMs).build();
    }
}