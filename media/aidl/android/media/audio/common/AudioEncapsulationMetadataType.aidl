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
package android.media.audio.common;

/**
 * Enumeration of metadata types permitted for use by encapsulation mode audio
 * streams (see AudioEncapsulationMode). This type corresponds to
 * AudioTrack.ENCAPSULATION_METADATA_TYPE_* constants in the SDK.
 *
 * {@hide}
 */
@Backing(type="int")
@VintfStability
enum AudioEncapsulationMetadataType {
    /** Default value. */
    NONE = 0,
    /**
     * Encapsulation metadata type for framework tuner information.
     */
    FRAMEWORK_TUNER = 1,
    /**
     * Encapsulation metadata type for DVB AD descriptor.
     *
     * This metadata is formatted per ETSI TS 101 154 Table E.1: AD_descriptor.
     */
    DVB_AD_DESCRIPTOR = 2,
}
