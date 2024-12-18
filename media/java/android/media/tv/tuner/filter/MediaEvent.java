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

package android.media.tv.tuner.filter;

import android.annotation.BytesLong;
import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.media.AudioPresentation;
import android.media.MediaCodec.LinearBlock;
import android.media.tv.flags.Flags;

import java.util.Collections;
import java.util.List;

/**
 * Filter event sent from {@link Filter} objects with media type.
 *
 * @hide
 */
@SystemApi
public class MediaEvent extends FilterEvent {
    private long mNativeContext;
    private boolean mReleased = false;
    private final Object mLock = new Object();

    private native Long nativeGetAudioHandle();
    private native LinearBlock nativeGetLinearBlock();
    private native void nativeFinalize();

    private final int mStreamId;
    private final boolean mIsPtsPresent;
    private final long mPts;
    private final boolean mIsDtsPresent;
    private final long mDts;
    private final long mDataLength;
    private final long mOffset;
    private LinearBlock mLinearBlock;
    private final boolean mIsSecureMemory;
    private final long mDataId;
    private final int mMpuSequenceNumber;
    private final boolean mIsPrivateData;
    private final int mScIndexMask;
    private final AudioDescriptor mExtraMetaData;
    private final List<AudioPresentation> mAudioPresentations;
    private final int mNumDataPieces;
    private final int mIndexInDataGroup;
    private final int mDataGroupId;

    // This constructor is used by JNI code only
    private MediaEvent(int streamId, boolean isPtsPresent, long pts, boolean isDtsPresent, long dts,
            long dataLength, long offset, LinearBlock buffer, boolean isSecureMemory, long dataId,
            int mpuSequenceNumber, boolean isPrivateData, int scIndexMask,
            AudioDescriptor extraMetaData, List<AudioPresentation> audioPresentations,
            int numDataPieces, int indexInDataGroup, int dataGroupId) {
        mStreamId = streamId;
        mIsPtsPresent = isPtsPresent;
        mPts = pts;
        mIsDtsPresent = isDtsPresent;
        mDts = dts;
        mDataLength = dataLength;
        mOffset = offset;
        mLinearBlock = buffer;
        mIsSecureMemory = isSecureMemory;
        mDataId = dataId;
        mMpuSequenceNumber = mpuSequenceNumber;
        mIsPrivateData = isPrivateData;
        mScIndexMask = scIndexMask;
        mExtraMetaData = extraMetaData;
        mAudioPresentations = audioPresentations;
        mNumDataPieces = numDataPieces;
        mIndexInDataGroup = indexInDataGroup;
        mDataGroupId = dataGroupId;
    }

    /**
     * Gets stream ID.
     */
    public int getStreamId() {
        return mStreamId;
    }

    /**
     * Returns whether PTS (Presentation Time Stamp) is present.
     *
     * @return {@code true} if PTS is present in PES header; {@code false} otherwise.
     */
    public boolean isPtsPresent() {
        return mIsPtsPresent;
    }

    /**
     * Gets PTS (Presentation Time Stamp) for audio or video frame.
     */
    public long getPts() {
        return mPts;
    }

    /**
     * Returns whether DTS (Decode Time Stamp) is present.
     *
     * <p>This query is only supported in Tuner 2.0 or higher version. Unsupported version will
     * return {@code false}.
     * Use {@link TunerVersionChecker#getTunerVersion()} to get the version information.
     *
     * @return {@code true} if DTS is present in PES header; {@code false} otherwise.
     */
    public boolean isDtsPresent() { return mIsDtsPresent; }

    /**
     * Gets DTS (Decode Time Stamp) for audio or video frame.
     *
     * * <p>This query is only supported in Tuner 2.0 or higher version. Unsupported version will
     * return {@code -1}.
     * Use {@link TunerVersionChecker#getTunerVersion()} to get the version information.
     */
    public long getDts() { return mDts; }

    /**
     * Gets data size in bytes of audio or video frame.
     */
    @BytesLong
    public long getDataLength() {
        return mDataLength;
    }

    /**
     * The offset in the memory block which is shared among multiple Media Events.
     */
    @BytesLong
    public long getOffset() {
        return mOffset;
    }

    /**
     * Gets a linear block associated to the memory where audio or video data stays.
     */
    @Nullable
    public LinearBlock getLinearBlock() {
        synchronized (mLock) {
            if (mLinearBlock == null) {
                mLinearBlock = nativeGetLinearBlock();
            }
            return mLinearBlock;
        }
    }

    /**
     * Returns whether the data is secure.
     *
     * @return {@code true} if the data is in secure area, and isn't mappable;
     *         {@code false} otherwise.
     */
    public boolean isSecureMemory() {
        return mIsSecureMemory;
    }

    /**
     * Gets the ID which is used by HAL to provide additional information for AV data.
     *
     * <p>For secure audio, it's the audio handle used by Audio Track.
     */
    public long getAvDataId() {
        return mDataId;
    }

    /**
     * Gets the audio handle.
     *
     * <p>Client gets audio handle from {@link MediaEvent}, and queues it to
     * {@link android.media.AudioTrack} in
     * {@link android.media.AudioTrack#ENCAPSULATION_MODE_HANDLE} format.
     *
     * @return the audio handle.
     * @see android.media.AudioTrack#ENCAPSULATION_MODE_HANDLE
     */
    public long getAudioHandle() {
        nativeGetAudioHandle();
        return mDataId;
    }

    /**
     * Gets MPU sequence number of filtered data.
     */
    @IntRange(from = 0)
    public int getMpuSequenceNumber() {
        return mMpuSequenceNumber;
    }

    /**
     * Returns whether the data is private.
     *
     * @return {@code true} if the data is in private; {@code false} otherwise.
     */
    public boolean isPrivateData() {
        return mIsPrivateData;
    }

    /**
     * Gets SC (Start Code) index mask.
     *
     * <p>This API is only supported by Tuner HAL 2.0 or higher. Unsupported version would return
     * {@code 0}. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
     */
    @RecordSettings.ScIndexMask
    public int getScIndexMask() {
        return mScIndexMask;
    }

    /**
     * Gets audio extra metadata.
     */
    @Nullable
    public AudioDescriptor getExtraMetaData() {
        return mExtraMetaData;
    }

    /**
     * Gets audio presentations.
     *
     * <p>The audio presentation order matters. As specified in ETSI EN 300 468 V1.17.1, all the
     * audio programme components corresponding to the first audio preselection in the loop are
     * contained in the main NGA stream.
     */
    @NonNull
    public List<AudioPresentation> getAudioPresentations() {
        return mAudioPresentations == null ? Collections.emptyList() : mAudioPresentations;
    }

    /**
     * Gets the number of data pieces into which the original data was split.
     *
     * <p>The {@link #getNumDataPieces()}, {@link #getIndexInDataGroup()} and
     * {@link #getDataGroupId()} methods should be used together to reassemble the original data if
     * it was split into pieces. Use {@link #getLinearBlock()} to get the memory where the data
     * pieces are stored.
     *
     * @return 0 or 1 if this MediaEvent object contains the complete data; otherwise the number of
     *         pieces into which the original data was split.
     * @see #getIndexInDataGroup()
     * @see #getDataGroupId()
     * @see #getLinearBlock()
     */
    @FlaggedApi(Flags.FLAG_TUNER_W_APIS)
    @IntRange(from = 0)
    public int getNumDataPieces() {
        return mNumDataPieces;
    }

    /**
     * Gets the index of the data piece. The index in the data group indicates the order in which
     * this {@link MediaEvent}'s data piece should be reassembled. The result should be within the
     * range [0, {@link #getNumDataPieces()}).
     *
     * <p>The {@link #getNumDataPieces()}, {@link #getIndexInDataGroup()} and
     * {@link #getDataGroupId()} methods should be used together to reassemble the original data if
     * it was split into pieces. Use {@link #getLinearBlock()} to get the memory where the data
     * pieces are stored.
     *
     * @return The index in the data group.
     * @see #getNumDataPieces()
     * @see #getDataGroupId()
     * @see #getLinearBlock()
     */
    @FlaggedApi(Flags.FLAG_TUNER_W_APIS)
    @IntRange(from = 0)
    public int getIndexInDataGroup() {
        return mIndexInDataGroup;
    }

    /**
     * Gets the group ID for reassembling the complete data. {@link MediaEvent}s that have the same
     * data group ID contain different pieces of the same data. This value should be ignored if
     * {@link #getNumDataPieces()} returns 0 or 1.
     *
     * <p>The {@link #getNumDataPieces()}, {@link #getIndexInDataGroup()} and
     * {@link #getDataGroupId()} methods should be used together to reassemble the original data if
     * it was split into pieces. Use {@link #getLinearBlock()} to get the memory where the data
     * pieces are stored.
     *
     * @return The data group ID.
     * @see #getNumDataPieces()
     * @see #getIndexInDataGroup()
     * @see #getLinearBlock()
     */
    @FlaggedApi(Flags.FLAG_TUNER_W_APIS)
    public int getDataGroupId() {
        return mDataGroupId;
    }

    /**
     * Finalize the MediaEvent object.
     * @hide
     */
    @Override
    protected void finalize() {
        release();
    }

    /**
     * Releases the MediaEvent object.
     */
    public void release() {
        synchronized (mLock) {
            if (mReleased) {
                return;
            }
            nativeFinalize();
            mNativeContext = 0;
            mReleased = true;
        }
    }
}
