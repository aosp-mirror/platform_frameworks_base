/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.telephony.satellite.stub;

/**
 * {@hide}
 */
@Backing(type="int")
enum NTRadioTechnology {
    /**
     * 3GPP NB-IoT (Narrowband Internet of Things) over Non-Terrestrial-Networks technology.
     */
    NB_IOT_NTN = 0,
    /*
     * 3GPP 5G NR over Non-Terrestrial-Networks technology.
     */
    NR_NTN = 1,
    /**
     * 3GPP eMTC (enhanced Machine-Type Communication) over Non-Terrestrial-Networks technology.
     */
    EMTC_NTN = 2,
    /**
     * Proprietary technology.
     */
    PROPRIETARY = 3,
    /**
     * Unknown Non-Terrestrial radio technology. This generic radio technology should be used
     * only when the radio technology cannot be mapped to other specific radio technologies.
     */
    UNKNOWN = -1,
}
