/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.ondeviceintelligence;

import static com.google.common.truth.Truth.assertThat;

import android.app.ondeviceintelligence.InferenceInfo;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.framework.protobuf.nano.MessageNano;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class InferenceInfoStoreTest {
    InferenceInfoStore inferenceInfoStore;

    public static final String INFERENCE_INFO_BUNDLE_KEY = "inference_info";

    @Before
    public void setUp() {
        inferenceInfoStore = new InferenceInfoStore(1000);
    }

    @Test
    public void testInferenceInfoParsesFromBundleSuccessfully() throws Exception {
        Bundle bundle = new Bundle();
        bundle.putByteArray(INFERENCE_INFO_BUNDLE_KEY,
                getInferenceInfoBytes(1, 1, 100));
        inferenceInfoStore.addInferenceInfoFromBundle(bundle);
        List<InferenceInfo> inferenceInfos = inferenceInfoStore.getLatestInferenceInfo(0);
        assertThat(inferenceInfos).hasSize(1);
        assertThat(inferenceInfos.get(0).getUid()).isEqualTo(1);
        assertThat(inferenceInfos.get(0).getStartTimeMillis()).isEqualTo(1);
        assertThat(inferenceInfos.get(0).getEndTimeMillis()).isEqualTo(100);
    }

    @Test
    public void testInferenceInfoParsesFromPersistableBundleSuccessfully() throws Exception {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putString(INFERENCE_INFO_BUNDLE_KEY,
                Base64.encodeToString(getInferenceInfoBytes(1, 1, 100), Base64.DEFAULT));
        inferenceInfoStore.addInferenceInfoFromBundle(bundle);
        List<InferenceInfo> inferenceInfos = inferenceInfoStore.getLatestInferenceInfo(0);
        assertThat(inferenceInfos).hasSize(1);
        assertThat(inferenceInfos.get(0).getUid()).isEqualTo(1);
        assertThat(inferenceInfos.get(0).getStartTimeMillis()).isEqualTo(1);
        assertThat(inferenceInfos.get(0).getEndTimeMillis()).isEqualTo(100);
    }


    @Test
    public void testEvictionAfterMaxAge() throws Exception {
        PersistableBundle bundle = new PersistableBundle();
        long testStartTime = System.currentTimeMillis();
        bundle.putString(INFERENCE_INFO_BUNDLE_KEY,
                Base64.encodeToString(getInferenceInfoBytes(1,  testStartTime - 10,
                        testStartTime + 100), Base64.DEFAULT));
        inferenceInfoStore.addInferenceInfoFromBundle(bundle);
        bundle.putString(INFERENCE_INFO_BUNDLE_KEY,
                Base64.encodeToString(getInferenceInfoBytes(1, testStartTime - 5,
                        testStartTime + 100), Base64.DEFAULT));
        inferenceInfoStore.addInferenceInfoFromBundle(bundle);
        Thread.sleep(1020);
        List<InferenceInfo> inferenceInfos = inferenceInfoStore.getLatestInferenceInfo(0);
        assertThat(inferenceInfos).hasSize(2);
        assertThat(inferenceInfos.get(0).getUid()).isEqualTo(1);
        assertThat(inferenceInfos.get(0).getStartTimeMillis()).isEqualTo(testStartTime - 10);
        assertThat(inferenceInfos.get(0).getEndTimeMillis()).isEqualTo(testStartTime + 100);
        inferenceInfoStore.addInferenceInfoFromBundle(bundle);
        List<InferenceInfo> inferenceInfos2 = inferenceInfoStore.getLatestInferenceInfo(0);
        assertThat(inferenceInfos2).hasSize(1); //previous entries should have been evicted
    }

    private byte[] getInferenceInfoBytes(int uid, long startTime, long endTime) {
        com.android.server.ondeviceintelligence.nano.InferenceInfo inferenceInfo =
                new com.android.server.ondeviceintelligence.nano.InferenceInfo();
        inferenceInfo.uid = uid;
        inferenceInfo.startTimeMs = startTime;
        inferenceInfo.endTimeMs = endTime;
        return MessageNano.toByteArray(inferenceInfo);
    }
}
