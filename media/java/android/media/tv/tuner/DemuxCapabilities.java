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

import android.annotation.BytesLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Size;
import android.annotation.SystemApi;
import android.media.tv.tuner.filter.Filter;
import android.media.tv.tuner.filter.FilterConfiguration;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Capabilities info for Demux.
 *
 * @hide
 */
@SystemApi
public class DemuxCapabilities {

    /** @hide */
    @IntDef(flag = true, prefix = { "TYPE_" }, value = {
          Filter.TYPE_UNDEFINED,
          Filter.TYPE_TS,
          Filter.TYPE_MMTP,
          Filter.TYPE_IP,
          Filter.TYPE_TLV,
          Filter.TYPE_ALP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FilterCapabilities {}

    private final int mDemuxCount;
    private final int mRecordCount;
    private final int mPlaybackCount;
    private final int mTsFilterCount;
    private final int mSectionFilterCount;
    private final int mAudioFilterCount;
    private final int mVideoFilterCount;
    private final int mPesFilterCount;
    private final int mPcrFilterCount;
    private final long mSectionFilterLength;
    private final @FilterCapabilities int mFilterCaps;
    private final @FilterCapabilities int[] mFilterCapsList;
    private final int[] mLinkCaps;
    private final boolean mSupportTimeFilter;

    // Used by JNI
    private DemuxCapabilities(int demuxCount, int recordCount, int playbackCount, int tsFilterCount,
            int sectionFilterCount, int audioFilterCount, int videoFilterCount, int pesFilterCount,
            int pcrFilterCount, long sectionFilterLength, int filterCaps,
            @FilterCapabilities int[] filterCapsList, @FilterCapabilities int[] linkCaps,
            boolean timeFilter) {
        mDemuxCount = demuxCount;
        mRecordCount = recordCount;
        mPlaybackCount = playbackCount;
        mTsFilterCount = tsFilterCount;
        mSectionFilterCount = sectionFilterCount;
        mAudioFilterCount = audioFilterCount;
        mVideoFilterCount = videoFilterCount;
        mPesFilterCount = pesFilterCount;
        mPcrFilterCount = pcrFilterCount;
        mSectionFilterLength = sectionFilterLength;
        mFilterCaps = filterCaps;
        mFilterCapsList = filterCapsList;
        mLinkCaps = linkCaps;
        mSupportTimeFilter = timeFilter;
    }

    /**
     * Gets total number of demuxes.
     */
    public int getDemuxCount() {
        return mDemuxCount;
    }
    /**
     * Gets max number of recordings at a time.
     */
    public int getRecordCount() {
        return mRecordCount;
    }
    /**
     * Gets max number of playbacks at a time.
     */
    public int getPlaybackCount() {
        return mPlaybackCount;
    }
    /**
     * Gets number of TS filters.
     */
    public int getTsFilterCount() {
        return mTsFilterCount;
    }
    /**
     * Gets number of section filters.
     */
    public int getSectionFilterCount() {
        return mSectionFilterCount;
    }
    /**
     * Gets number of audio filters.
     */
    public int getAudioFilterCount() {
        return mAudioFilterCount;
    }
    /**
     * Gets number of video filters.
     */
    public int getVideoFilterCount() {
        return mVideoFilterCount;
    }
    /**
     * Gets number of PES filters.
     */
    public int getPesFilterCount() {
        return mPesFilterCount;
    }
    /**
     * Gets number of PCR filters.
     */
    public int getPcrFilterCount() {
        return mPcrFilterCount;
    }
    /**
     * Gets number of bytes in the mask of a section filter.
     */
    @BytesLong
    public long getSectionFilterLength() {
        return mSectionFilterLength;
    }
    /**
     * Gets filter capabilities in bit field.
     *
     * <p>The bits of the returned value is corresponding to the types in
     * {@link FilterConfiguration}.
     */
    @FilterCapabilities
    public int getFilterCapabilities() {
        return mFilterCaps;
    }

    /**
     * Gets the list of filter main type capabilities in bit field.
     *
     * <p>Each element in the returned array represents the supported filter main types
     * represented as bitwise OR of the types in {@link FilterConfiguration}.
     * <p>Whereas getFilterCapabilities() returns the bitwise OR value of all the supported filter
     * types in the system, this API returns a list of supported filter types in the system with
     * each entry representing the supported filter types per demux resource.
     *
     * @return an array of supported filter main types for the demux resources in the system
     *         an empty array should be returned for devices with Tuner HAL version 2 and below
     */
    @FilterCapabilities
    @NonNull
    public int[] getFilterTypeCapabilityList() {
        return mFilterCapsList;
    }

    /**
     * Gets link capabilities.
     *
     * <p>The returned array contains the same elements as the number of types in
     * {@link FilterConfiguration}.
     * <p>The ith element represents the filter's capability as the source for the ith type.
     */
    @NonNull
    @Size(5)
    public int[] getLinkCapabilities() {
        return mLinkCaps;
    }
    /**
     * Is {@link android.media.tv.tuner.filter.TimeFilter} supported.
     */
    public boolean isTimeFilterSupported() {
        return mSupportTimeFilter;
    }
}
