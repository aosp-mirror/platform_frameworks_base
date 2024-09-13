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
package com.android.server.autofill;

import static com.android.server.autofill.Helper.SaveInfoStats;
import static com.android.server.autofill.Helper.getSaveInfoStatsFromFillResponses;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveInfo;
import android.util.SparseArray;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HelperTest {

    @Test
    public void testGetSaveInfoStatsFromFillResponses_nullFillResponses() {
        SaveInfoStats saveInfoStats = getSaveInfoStatsFromFillResponses(null);

        assertThat(saveInfoStats.saveInfoCount).isEqualTo(-1);
        assertThat(saveInfoStats.saveDataTypeCount).isEqualTo(-1);
    }

    @Test
    public void testGetSaveInfoStatsFromFillResponses_emptyFillResponseSparseArray() {
        SaveInfoStats saveInfoStats = getSaveInfoStatsFromFillResponses(new SparseArray<>());

        assertThat(saveInfoStats.saveInfoCount).isEqualTo(0);
        assertThat(saveInfoStats.saveDataTypeCount).isEqualTo(0);
    }

    @Test
    public void testGetSaveInfoStatsFromFillResponses_singleResponseWithoutSaveInfo() {
        FillResponse.Builder fillResponseBuilder = new FillResponse.Builder();
        // Add client state to satisfy the sanity check in FillResponseBuilder.build()
        Bundle clientState = new Bundle();
        fillResponseBuilder.setClientState(clientState);
        FillResponse testFillResponse = fillResponseBuilder.build();

        SparseArray<FillResponse> testFillResponses = new SparseArray<>();
        testFillResponses.put(0, testFillResponse);

        SaveInfoStats saveInfoStats = getSaveInfoStatsFromFillResponses(testFillResponses);

        assertThat(saveInfoStats.saveInfoCount).isEqualTo(0);
        assertThat(saveInfoStats.saveDataTypeCount).isEqualTo(0);
    }

    @Test
    public void testGetSaveInfoStatsFromFillResponses_singleResponseWithSaveInfo() {
        FillResponse.Builder fillResponseBuilder = new FillResponse.Builder();
        SaveInfo.Builder saveInfoBuilder = new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_GENERIC);
        fillResponseBuilder.setSaveInfo(saveInfoBuilder.build());
        FillResponse testFillResponse = fillResponseBuilder.build();

        SparseArray<FillResponse> testFillResponses = new SparseArray<>();
        testFillResponses.put(0, testFillResponse);

        SaveInfoStats saveInfoStats = getSaveInfoStatsFromFillResponses(testFillResponses);

        assertThat(saveInfoStats.saveInfoCount).isEqualTo(1);
        assertThat(saveInfoStats.saveDataTypeCount).isEqualTo(1);
    }

    @Test
    public void testGetSaveInfoStatsFromFillResponses_multipleResponseWithDifferentTypeSaveInfo() {
        FillResponse.Builder fillResponseBuilder1 = new FillResponse.Builder();
        SaveInfo.Builder saveInfoBuilder1 = new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_GENERIC);
        fillResponseBuilder1.setSaveInfo(saveInfoBuilder1.build());
        FillResponse testFillResponse1 = fillResponseBuilder1.build();

        FillResponse.Builder fillResponseBuilder2 = new FillResponse.Builder();
        SaveInfo.Builder saveInfoBuilder2 = new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_ADDRESS);
        fillResponseBuilder2.setSaveInfo(saveInfoBuilder2.build());
        FillResponse testFillResponse2 = fillResponseBuilder2.build();

        FillResponse.Builder fillResponseBuilder3 = new FillResponse.Builder();
        SaveInfo.Builder saveInfoBuilder3 = new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_ADDRESS);
        fillResponseBuilder3.setSaveInfo(saveInfoBuilder3.build());
        FillResponse testFillResponse3 = fillResponseBuilder3.build();

        SparseArray<FillResponse> testFillResponses = new SparseArray<>();
        testFillResponses.put(0, testFillResponse1);
        testFillResponses.put(1, testFillResponse2);
        testFillResponses.put(2, testFillResponse3);

        SaveInfoStats saveInfoStats = getSaveInfoStatsFromFillResponses(testFillResponses);

        // Save info count is 3. Since two save info share the same save data type, the distinct
        // save data type count is 2.
        assertThat(saveInfoStats.saveInfoCount).isEqualTo(3);
        assertThat(saveInfoStats.saveDataTypeCount).isEqualTo(2);
    }
}
