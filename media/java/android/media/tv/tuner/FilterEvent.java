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

import android.os.NativeHandle;

/**
 * Demux filter event.
 *
 * @hide
 */
public abstract class FilterEvent {

    /**
     * Section event.
     */
    public static class SectionEvent extends FilterEvent {
        private int mTableId;
        private int mVersion;
        private int mSectionNum;
        private int mDataLength;
    }

    /**
     * Media event.
     */
    public static class MediaEvent extends FilterEvent {
        private int mStreamId;
        private boolean mIsPtsPresent;
        private long mPts;
        private int mDataLength;
        private NativeHandle mHandle;
        private boolean mIsSecureMemory;
        private int mMpuSequenceNumber;
        private boolean mIsPrivateData;
        private AudioExtraMetaData mExtraMetaData;
    }

    /**
     * PES event.
     */
    public static class PesEvent extends FilterEvent {
        private int mStreamId;
        private int mDataLength;
        private int mMpuSequenceNumber;
    }

    /**
     * TS record event.
     */
    public static class TsRecordEvent extends FilterEvent {
        private int mTpid;
        private int mIndexMask;
        private long mByteNumber;
    }

    /**
     * MMPT record event.
     */
    public static class MmtpRecordEvent extends FilterEvent {
        private int mScHevcIndexMask;
        private long mByteNumber;
    }

    /**
     * Download event.
     */
    public static class DownloadEvent extends FilterEvent {
        private int mItemId;
        private int mMpuSequenceNumber;
        private int mItemFragmentIndex;
        private int mLastItemFragmentIndex;
        private int mDataLength;
    }

    /**
     * IP payload event.
     */
    public static class IpPayloadEvent extends FilterEvent {
        private int mDataLength;
    }

    /**
     * TEMI event.
     */
    public static class TemiEvent extends FilterEvent {
        private long mPts;
        private byte mDescrTag;
        private byte[] mDescrData;
    }

    /**
     *  Extra Meta Data from AD (Audio Descriptor) according to
     *  ETSI TS 101 154 V2.1.1.
     */
    public static class AudioExtraMetaData {
        private byte mAdFade;
        private byte mAdPan;
        private byte mVersionTextTag;
        private byte mAdGainCenter;
        private byte mAdGainFront;
        private byte mAdGainSurround;
    }
}
