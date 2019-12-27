/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner;

/**
 * Capabilities info for Demux.
 * @hide
 */
public class DemuxCapabilities {
    private final int mNumDemux;
    private final int mNumRecord;
    private final int mNumPlayback;
    private final int mNumTsFilter;
    private final int mNumSectionFilter;
    private final int mNumAudioFilter;
    private final int mNumVideoFilter;
    private final int mNumPesFilter;
    private final int mNumPcrFilter;
    private final int mNumBytesInSectionFilter;
    private final int mFilterCaps;
    private final int[] mLinkCaps;

    DemuxCapabilities(int numDemux, int numRecord, int numPlayback, int numTsFilter,
            int numSectionFilter, int numAudioFilter, int numVideoFilter, int numPesFilter,
            int numPcrFilter, int numBytesInSectionFilter, int filterCaps, int[] linkCaps) {
        mNumDemux = numDemux;
        mNumRecord = numRecord;
        mNumPlayback = numPlayback;
        mNumTsFilter = numTsFilter;
        mNumSectionFilter = numSectionFilter;
        mNumAudioFilter = numAudioFilter;
        mNumVideoFilter = numVideoFilter;
        mNumPesFilter = numPesFilter;
        mNumPcrFilter = numPcrFilter;
        mNumBytesInSectionFilter = numBytesInSectionFilter;
        mFilterCaps = filterCaps;
        mLinkCaps = linkCaps;
    }

    /** Gets total number of demuxes. */
    public int getNumDemux() {
        return mNumDemux;
    }
    /** Gets max number of recordings at a time. */
    public int getNumRecord() {
        return mNumRecord;
    }
    /** Gets max number of playbacks at a time. */
    public int getNumPlayback() {
        return mNumPlayback;
    }
    /** Gets number of TS filters. */
    public int getNumTsFilter() {
        return mNumTsFilter;
    }
    /** Gets number of section filters. */
    public int getNumSectionFilter() {
        return mNumSectionFilter;
    }
    /** Gets number of audio filters. */
    public int getNumAudioFilter() {
        return mNumAudioFilter;
    }
    /** Gets number of video filters. */
    public int getNumVideoFilter() {
        return mNumVideoFilter;
    }
    /** Gets number of PES filters. */
    public int getNumPesFilter() {
        return mNumPesFilter;
    }
    /** Gets number of PCR filters. */
    public int getNumPcrFilter() {
        return mNumPcrFilter;
    }
    /** Gets number of bytes in the mask of a section filter. */
    public int getNumBytesInSectionFilter() {
        return mNumBytesInSectionFilter;
    }
    /**
     * Gets filter capabilities in bit field.
     *
     * The bits of the returned value is corresponding to the types in
     * {@link TunerConstants.FilterType}.
     */
    public int getFilterCapabilities() {
        return mFilterCaps;
    }

    /**
     * Gets link capabilities.
     *
     * The returned array contains the same elements as the number of types in
     * {@link TunerConstants.FilterType}.
     * The ith element represents the filter's capability as the source for the ith type
     */
    public int[] getLinkCapabilities() {
        return mLinkCaps;
    }
}
