/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.media.tv.tuner.frontend;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;

/**
 * Scan callback.
 *
 * @hide
 */
@SystemApi
public interface ScanCallback {

    /** Scan locked the signal. */
    void onLocked();

    /** Scan stopped. */
    void onScanStopped();

    /** scan progress percent (0..100) */
    void onProgress(@IntRange(from = 0, to = 100) int percent);

    /** Signal frequencies in Hertz */
    void onFrequenciesReported(@NonNull int[] frequency);

    /** Symbols per second */
    void onSymbolRatesReported(@NonNull int[] rate);

    /** Locked Plp Ids for DVBT2 frontend. */
    void onPlpIdsReported(@NonNull int[] plpIds);

    /** Locked group Ids for DVBT2 frontend. */
    void onGroupIdsReported(@NonNull int[] groupIds);

    /** Stream Ids. */
    void onInputStreamIdsReported(@NonNull int[] inputStreamIds);

    /** Locked signal standard for DVBS. */
    void onDvbsStandardReported(@DvbsFrontendSettings.Standard int dvbsStandard);

    /** Locked signal standard. for DVBT */
    void onDvbtStandardReported(@DvbtFrontendSettings.Standard int dvbtStandard);

    /** Locked signal SIF standard for Analog. */
    void onAnalogSifStandardReported(@AnalogFrontendSettings.SifStandard int sif);

    /** PLP status in a tuned frequency band for ATSC3 frontend. */
    void onAtsc3PlpInfosReported(@NonNull Atsc3PlpInfo[] atsc3PlpInfos);

    /** Frontend hierarchy. */
    void onHierarchyReported(@DvbtFrontendSettings.Hierarchy int hierarchy);

    /** Frontend signal type. */
    void onSignalTypeReported(@AnalogFrontendSettings.SignalType int signalType);

    /** Frontend modulation reported. */
    default void onModulationReported(@FrontendStatus.FrontendModulation int modulation) {}

    /** Frontend scan message priority reported. */
    default void onPriorityReported(boolean isHighPriority) {}

    /** DVBC Frontend Annex reported. */
    default void onDvbcAnnexReported(@DvbcFrontendSettings.Annex int dvbcAnnex) {}
}
