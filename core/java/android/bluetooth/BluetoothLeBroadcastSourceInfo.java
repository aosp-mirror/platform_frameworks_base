/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.bluetooth;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * This class represents an LE Audio Broadcast Source and the associated information that is needed
 * by Broadcast Audio Scan Service (BASS) residing on a Scan Delegator.
 *
 * <p>For example, the Scan Delegator on an LE Audio Broadcast Sink can use the information
 * contained within an instance of this class to synchronize with an LE Audio Broadcast Source in
 * order to listen to a Broadcast Audio Stream.
 *
 * <p>BroadcastAssistant has a BASS client which facilitates scanning and discovery of Broadcast
 * Sources on behalf of say a Broadcast Sink. Upon successful discovery of one or more Broadcast
 * sources, this information needs to be communicated to the BASS Server residing within the Scan
 * Delegator on a Broadcast Sink. This is achieved using the Periodic Advertising Synchronization
 * Transfer (PAST) procedure. This procedure uses information contained within an instance of this
 * class.
 *
 * @hide
 */
public final class BluetoothLeBroadcastSourceInfo implements Parcelable {
    private static final String TAG = "BluetoothLeBroadcastSourceInfo";
    private static final boolean DBG = true;

    /**
     * Constants representing Broadcast Source address types
     *
     * @hide
     */
    @IntDef(
            prefix = "LE_AUDIO_BROADCAST_SOURCE_ADDRESS_TYPE_",
            value = {
                LE_AUDIO_BROADCAST_SOURCE_ADDRESS_TYPE_PUBLIC,
                LE_AUDIO_BROADCAST_SOURCE_ADDRESS_TYPE_RANDOM,
                LE_AUDIO_BROADCAST_SOURCE_ADDRESS_TYPE_INVALID
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LeAudioBroadcastSourceAddressType {}

    /**
     * Represents a public address used by an LE Audio Broadcast Source
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SOURCE_ADDRESS_TYPE_PUBLIC = 0;

    /**
     * Represents a random address used by an LE Audio Broadcast Source
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SOURCE_ADDRESS_TYPE_RANDOM = 1;

    /**
     * Represents an invalid address used by an LE Audio Broadcast Seurce
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SOURCE_ADDRESS_TYPE_INVALID = 0xFFFF;

    /**
     * Periodic Advertising Synchronization state
     *
     * <p>Periodic Advertising (PA) enables the LE Audio Broadcast Assistant to discover broadcast
     * audio streams as well as the audio stream configuration on behalf of an LE Audio Broadcast
     * Sink. This information can then be transferred to the LE Audio Broadcast Sink using the
     * Periodic Advertising Synchronizaton Transfer (PAST) procedure.
     *
     * @hide
     */
    @IntDef(
            prefix = "LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_",
            value = {
                LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_IDLE,
                LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_SYNCINFO_REQ,
                LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_IN_SYNC,
                LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_SYNC_FAIL,
                LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_NO_PAST
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LeAudioBroadcastSinkPaSyncState {}

    /**
     * Indicates that the Broadcast Sink is not synchronized with the Periodic Advertisements (PA)
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_IDLE = 0;

    /**
     * Indicates that the Broadcast Sink requested the Broadcast Assistant to synchronize with the
     * Periodic Advertisements (PA).
     *
     * <p>This is also known as scan delegation or scan offloading.
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_SYNCINFO_REQ = 1;

    /**
     * Indicates that the Broadcast Sink is synchronized with the Periodic Advertisements (PA).
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_IN_SYNC = 2;

    /**
     * Indicates that the Broadcast Sink was unable to synchronize with the Periodic Advertisements
     * (PA).
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_SYNC_FAIL = 3;

    /**
     * Indicates that the Broadcast Sink should be synchronized with the Periodic Advertisements
     * (PA) using the Periodic Advertisements Synchronization Transfert (PAST) procedure.
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_NO_PAST = 4;

    /**
     * Indicates that the Broadcast Sink synchornization state is invalid.
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_INVALID = 0xFFFF;

    /** @hide */
    @IntDef(
            prefix = "LE_AUDIO_BROADCAST_SINK_AUDIO_SYNC_STATE_",
            value = {
                LE_AUDIO_BROADCAST_SINK_AUDIO_SYNC_STATE_NOT_SYNCHRONIZED,
                LE_AUDIO_BROADCAST_SINK_AUDIO_SYNC_STATE_SYNCHRONIZED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LeAudioBroadcastSinkAudioSyncState {}

    /**
     * Indicates that the Broadcast Sink is not synchronized with a Broadcast Audio Stream.
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_AUDIO_SYNC_STATE_NOT_SYNCHRONIZED = 0;

    /**
     * Indicates that the Broadcast Sink is synchronized with a Broadcast Audio Stream.
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_AUDIO_SYNC_STATE_SYNCHRONIZED = 1;

    /**
     * Indicates that the Broadcast Sink audio synchronization state is invalid.
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_AUDIO_SYNC_STATE_INVALID = 0xFFFF;

    /** @hide */
    @IntDef(
            prefix = "LE_AUDIO_BROADCAST_SINK_ENC_STATE_",
            value = {
                LE_AUDIO_BROADCAST_SINK_ENC_STATE_NOT_ENCRYPTED,
                LE_AUDIO_BROADCAST_SINK_ENC_STATE_CODE_REQUIRED,
                LE_AUDIO_BROADCAST_SINK_ENC_STATE_DECRYPTING,
                LE_AUDIO_BROADCAST_SINK_ENC_STATE_BAD_CODE
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LeAudioBroadcastSinkEncryptionState {}

    /**
     * Indicates that the Broadcast Sink is synchronized with an unencrypted audio stream.
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_ENC_STATE_NOT_ENCRYPTED = 0;

    /**
     * Indicates that the Broadcast Sink needs a Broadcast Code to synchronize with the audio
     * stream.
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_ENC_STATE_CODE_REQUIRED = 1;

    /**
     * Indicates that the Broadcast Sink is synchronized with an encrypted audio stream.
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_ENC_STATE_DECRYPTING = 2;

    /**
     * Indicates that the Broadcast Sink is unable to decrypt an audio stream due to an incorrect
     * Broadcast Code
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_ENC_STATE_BAD_CODE = 3;

    /**
     * Indicates that the Broadcast Sink encryption state is invalid.
     *
     * @hide
     */
    public static final int LE_AUDIO_BROADCAST_SINK_ENC_STATE_INVALID = 0xFF;

    /**
     * Represents an invalid LE Audio Broadcast Source ID
     *
     * @hide
     */
    public static final byte LE_AUDIO_BROADCAST_SINK_INVALID_SOURCE_ID = (byte) 0x00;

    /**
     * Represents an invalid Broadcast ID of a Broadcast Source
     *
     * @hide
     */
    public static final int INVALID_BROADCAST_ID = 0xFFFFFF;

    private byte mSourceId;
    private @LeAudioBroadcastSourceAddressType int mSourceAddressType;
    private BluetoothDevice mSourceDevice;
    private byte mSourceAdvSid;
    private int mBroadcastId;
    private @LeAudioBroadcastSinkPaSyncState int mPaSyncState;
    private @LeAudioBroadcastSinkEncryptionState int mEncryptionStatus;
    private @LeAudioBroadcastSinkAudioSyncState int mAudioSyncState;
    private byte[] mBadBroadcastCode;
    private byte mNumSubGroups;
    private Map<Integer, Integer> mSubgroupBisSyncState = new HashMap<Integer, Integer>();
    private Map<Integer, byte[]> mSubgroupMetadata = new HashMap<Integer, byte[]>();

    private String mBroadcastCode;
    private static final int BIS_NO_PREF = 0xFFFFFFFF;
    private static final int BROADCAST_CODE_SIZE = 16;

    /**
     * Constructor to create an Empty object of {@link BluetoothLeBroadcastSourceInfo } with the
     * given Source Id.
     *
     * <p>This is mainly used to represent the Empty Broadcast Source entries
     *
     * @param sourceId Source Id for this Broadcast Source info object
     * @hide
     */
    public BluetoothLeBroadcastSourceInfo(byte sourceId) {
        mSourceId = sourceId;
        mSourceAddressType = LE_AUDIO_BROADCAST_SOURCE_ADDRESS_TYPE_INVALID;
        mSourceDevice = null;
        mSourceAdvSid = (byte) 0x00;
        mBroadcastId = INVALID_BROADCAST_ID;
        mPaSyncState = LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_INVALID;
        mAudioSyncState = LE_AUDIO_BROADCAST_SINK_AUDIO_SYNC_STATE_INVALID;
        mEncryptionStatus = LE_AUDIO_BROADCAST_SINK_ENC_STATE_INVALID;
        mBadBroadcastCode = null;
        mNumSubGroups = 0;
        mBroadcastCode = null;
    }

    /*package*/ BluetoothLeBroadcastSourceInfo(
            byte sourceId,
            @LeAudioBroadcastSourceAddressType int addressType,
            @NonNull BluetoothDevice device,
            byte advSid,
            int broadcastId,
            @LeAudioBroadcastSinkPaSyncState int paSyncstate,
            @LeAudioBroadcastSinkEncryptionState int encryptionStatus,
            @LeAudioBroadcastSinkAudioSyncState int audioSyncstate,
            @Nullable byte[] badCode,
            byte numSubGroups,
            @NonNull Map<Integer, Integer> bisSyncState,
            @Nullable Map<Integer, byte[]> subgroupMetadata,
            @NonNull String broadcastCode) {
        mSourceId = sourceId;
        mSourceAddressType = addressType;
        mSourceDevice = device;
        mSourceAdvSid = advSid;
        mBroadcastId = broadcastId;
        mPaSyncState = paSyncstate;
        mEncryptionStatus = encryptionStatus;
        mAudioSyncState = audioSyncstate;

        if (badCode != null && badCode.length != 0) {
            mBadBroadcastCode = new byte[badCode.length];
            System.arraycopy(badCode, 0, mBadBroadcastCode, 0, badCode.length);
        }
        mNumSubGroups = numSubGroups;
        mSubgroupBisSyncState = new HashMap<Integer, Integer>(bisSyncState);
        mSubgroupMetadata = new HashMap<Integer, byte[]>(subgroupMetadata);
        mBroadcastCode = broadcastCode;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BluetoothLeBroadcastSourceInfo) {
            BluetoothLeBroadcastSourceInfo other = (BluetoothLeBroadcastSourceInfo) o;
            return (other.mSourceId == mSourceId
                    && other.mSourceAddressType == mSourceAddressType
                    && other.mSourceDevice == mSourceDevice
                    && other.mSourceAdvSid == mSourceAdvSid
                    && other.mBroadcastId == mBroadcastId
                    && other.mPaSyncState == mPaSyncState
                    && other.mEncryptionStatus == mEncryptionStatus
                    && other.mAudioSyncState == mAudioSyncState
                    && Arrays.equals(other.mBadBroadcastCode, mBadBroadcastCode)
                    && other.mNumSubGroups == mNumSubGroups
                    && mSubgroupBisSyncState.equals(other.mSubgroupBisSyncState)
                    && mSubgroupMetadata.equals(other.mSubgroupMetadata)
                    && other.mBroadcastCode == mBroadcastCode);
        }
        return false;
    }

    /**
     * Checks if an instance of {@link BluetoothLeBroadcastSourceInfo} is empty.
     *
     * @hide
     */
    public boolean isEmpty() {
        boolean ret = false;
        if (mSourceAddressType == LE_AUDIO_BROADCAST_SOURCE_ADDRESS_TYPE_INVALID
                && mSourceDevice == null
                && mSourceAdvSid == (byte) 0
                && mPaSyncState == LE_AUDIO_BROADCAST_SINK_PA_SYNC_STATE_INVALID
                && mEncryptionStatus == LE_AUDIO_BROADCAST_SINK_ENC_STATE_INVALID
                && mAudioSyncState == LE_AUDIO_BROADCAST_SINK_AUDIO_SYNC_STATE_INVALID
                && mBadBroadcastCode == null
                && mNumSubGroups == 0
                && mSubgroupBisSyncState.size() == 0
                && mSubgroupMetadata.size() == 0
                && mBroadcastCode == null) {
            ret = true;
        }
        return ret;
    }

    /**
     * Compares an instance of {@link BluetoothLeBroadcastSourceInfo} with the provided instance.
     *
     * @hide
     */
    public boolean matches(BluetoothLeBroadcastSourceInfo srcInfo) {
        boolean ret = false;
        if (srcInfo == null) {
            ret = false;
        } else {
            if (mSourceDevice == null) {
                if (mSourceAdvSid == srcInfo.getAdvertisingSid()
                        && mSourceAddressType == srcInfo.getAdvAddressType()) {
                    ret = true;
                }
            } else {
                if (mSourceDevice.equals(srcInfo.getSourceDevice())
                        && mSourceAdvSid == srcInfo.getAdvertisingSid()
                        && mSourceAddressType == srcInfo.getAdvAddressType()
                        && mBroadcastId == srcInfo.getBroadcastId()) {
                    ret = true;
                }
            }
        }
        return ret;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mSourceId,
                mSourceAddressType,
                mSourceDevice,
                mSourceAdvSid,
                mBroadcastId,
                mPaSyncState,
                mEncryptionStatus,
                mAudioSyncState,
                mBadBroadcastCode,
                mNumSubGroups,
                mSubgroupBisSyncState,
                mSubgroupMetadata,
                mBroadcastCode);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "{BluetoothLeBroadcastSourceInfo : mSourceId"
                + mSourceId
                + " addressType: "
                + mSourceAddressType
                + " sourceDevice: "
                + mSourceDevice
                + " mSourceAdvSid:"
                + mSourceAdvSid
                + " mBroadcastId:"
                + mBroadcastId
                + " mPaSyncState:"
                + mPaSyncState
                + " mEncryptionStatus:"
                + mEncryptionStatus
                + " mAudioSyncState:"
                + mAudioSyncState
                + " mBadBroadcastCode:"
                + mBadBroadcastCode
                + " mNumSubGroups:"
                + mNumSubGroups
                + " mSubgroupBisSyncState:"
                + mSubgroupBisSyncState
                + " mSubgroupMetadata:"
                + mSubgroupMetadata
                + " mBroadcastCode:"
                + mBroadcastCode
                + "}";
    }

    /**
     * Get the Source Id
     *
     * @return byte representing the Source Id, {@link
     *     #LE_AUDIO_BROADCAST_ASSISTANT_INVALID_SOURCE_ID} if invalid
     * @hide
     */
    public byte getSourceId() {
        return mSourceId;
    }

    /**
     * Set the Source Id
     *
     * @param sourceId source Id
     * @hide
     */
    public void setSourceId(byte sourceId) {
        mSourceId = sourceId;
    }

    /**
     * Set the Broadcast Source device
     *
     * @param sourceDevice the Broadcast Source BluetoothDevice
     * @hide
     */
    public void setSourceDevice(@NonNull BluetoothDevice sourceDevice) {
        mSourceDevice = sourceDevice;
    }

    /**
     * Get the Broadcast Source BluetoothDevice
     *
     * @return Broadcast Source BluetoothDevice
     * @hide
     */
    public @NonNull BluetoothDevice getSourceDevice() {
        return mSourceDevice;
    }

    /**
     * Set the address type of the Broadcast Source advertisements
     *
     * @hide
     */
    public void setAdvAddressType(@LeAudioBroadcastSourceAddressType int addressType) {
        mSourceAddressType = addressType;
    }

    /**
     * Get the address type used by advertisements from the Broadcast Source.
     * BluetoothLeBroadcastSourceInfo Object
     *
     * @hide
     */
    @LeAudioBroadcastSourceAddressType
    public int getAdvAddressType() {
        return mSourceAddressType;
    }

    /**
     * Set the advertising SID of the Broadcast Source advertisement.
     *
     * @param advSid advertising SID of the Broadcast Source
     * @hide
     */
    public void setAdvertisingSid(byte advSid) {
        mSourceAdvSid = advSid;
    }

    /**
     * Get the advertising SID of the Broadcast Source advertisement.
     *
     * @return advertising SID of the Broadcast Source
     * @hide
     */
    public byte getAdvertisingSid() {
        return mSourceAdvSid;
    }

    /**
     * Get the Broadcast ID of the Broadcast Source.
     *
     * @return broadcast ID
     * @hide
     */
    public int getBroadcastId() {
        return mBroadcastId;
    }

    /**
     * Set the Periodic Advertising (PA) Sync State.
     *
     * @hide
     */
    /*package*/ void setPaSyncState(@LeAudioBroadcastSinkPaSyncState int paSyncState) {
        mPaSyncState = paSyncState;
    }

    /**
     * Get the Periodic Advertising (PA) Sync State
     *
     * @hide
     */
    public @LeAudioBroadcastSinkPaSyncState int getMetadataSyncState() {
        return mPaSyncState;
    }

    /**
     * Set the audio sync state
     *
     * @hide
     */
    /*package*/ void setAudioSyncState(@LeAudioBroadcastSinkAudioSyncState int audioSyncState) {
        mAudioSyncState = audioSyncState;
    }

    /**
     * Get the audio sync state
     *
     * @hide
     */
    public @LeAudioBroadcastSinkAudioSyncState int getAudioSyncState() {
        return mAudioSyncState;
    }

    /**
     * Set the encryption status
     *
     * @hide
     */
    /*package*/ void setEncryptionStatus(
            @LeAudioBroadcastSinkEncryptionState int encryptionStatus) {
        mEncryptionStatus = encryptionStatus;
    }

    /**
     * Get the encryption status
     *
     * @hide
     */
    public @LeAudioBroadcastSinkEncryptionState int getEncryptionStatus() {
        return mEncryptionStatus;
    }

    /**
     * Get the incorrect broadcast code that the Scan delegator used to decrypt the Broadcast Audio
     * Stream and failed.
     *
     * <p>This code is valid only if {@link #getEncryptionStatus} returns {@link
     * #LE_AUDIO_BROADCAST_SINK_ENC_STATE_BAD_CODE}
     *
     * @return byte array containing bad broadcast value, null if the current encryption status is
     *     not {@link #LE_AUDIO_BROADCAST_SINK_ENC_STATE_BAD_CODE}
     * @hide
     */
    public @Nullable byte[] getBadBroadcastCode() {
        return mBadBroadcastCode;
    }

    /**
     * Get the number of subgroups.
     *
     * @return number of subgroups
     * @hide
     */
    public byte getNumberOfSubGroups() {
        return mNumSubGroups;
    }

    public @NonNull Map<Integer, Integer> getSubgroupBisSyncState() {
        return mSubgroupBisSyncState;
    }

    public void setSubgroupBisSyncState(@NonNull Map<Integer, Integer> bisSyncState) {
        mSubgroupBisSyncState = new HashMap<Integer, Integer>(bisSyncState);
    }

    /*package*/ void setBroadcastCode(@NonNull String broadcastCode) {
        mBroadcastCode = broadcastCode;
    }

    /**
     * Get the broadcast code
     *
     * @return
     * @hide
     */
    public @NonNull String getBroadcastCode() {
        return mBroadcastCode;
    }

    /**
     * Set the broadcast ID
     *
     * @param broadcastId broadcast ID of the Broadcast Source
     * @hide
     */
    public void setBroadcastId(int broadcastId) {
        mBroadcastId = broadcastId;
    }

    private void writeSubgroupBisSyncStateToParcel(
            @NonNull Parcel dest, @NonNull Map<Integer, Integer> subgroupBisSyncState) {
        dest.writeInt(subgroupBisSyncState.size());
        for (Map.Entry<Integer, Integer> entry : subgroupBisSyncState.entrySet()) {
            dest.writeInt(entry.getKey());
            dest.writeInt(entry.getValue());
        }
    }

    private static void readSubgroupBisSyncStateFromParcel(
            @NonNull Parcel in, @NonNull Map<Integer, Integer> subgroupBisSyncState) {
        int size = in.readInt();

        for (int i = 0; i < size; i++) {
            Integer key = in.readInt();
            Integer value = in.readInt();
            subgroupBisSyncState.put(key, value);
        }
    }

    private void writeSubgroupMetadataToParcel(
            @NonNull Parcel dest, @Nullable Map<Integer, byte[]> subgroupMetadata) {
        if (subgroupMetadata == null) {
            dest.writeInt(0);
            return;
        }

        dest.writeInt(subgroupMetadata.size());
        for (Map.Entry<Integer, byte[]> entry : subgroupMetadata.entrySet()) {
            dest.writeInt(entry.getKey());
            byte[] metadata = entry.getValue();
            if (metadata != null) {
                dest.writeInt(metadata.length);
                dest.writeByteArray(metadata);
            }
        }
    }

    private static void readSubgroupMetadataFromParcel(
            @NonNull Parcel in, @NonNull Map<Integer, byte[]> subgroupMetadata) {
        int size = in.readInt();

        for (int i = 0; i < size; i++) {
            Integer key = in.readInt();
            Integer metaDataLen = in.readInt();
            byte[] metadata = null;
            if (metaDataLen != 0) {
                metadata = new byte[metaDataLen];
                in.readByteArray(metadata);
            }
            subgroupMetadata.put(key, metadata);
        }
    }

    public static final @NonNull Parcelable.Creator<BluetoothLeBroadcastSourceInfo> CREATOR =
            new Parcelable.Creator<BluetoothLeBroadcastSourceInfo>() {
                public @NonNull BluetoothLeBroadcastSourceInfo createFromParcel(
                        @NonNull Parcel in) {
                    final byte sourceId = in.readByte();
                    final int sourceAddressType = in.readInt();
                    final BluetoothDevice sourceDevice =
                            in.readTypedObject(BluetoothDevice.CREATOR);
                    final byte sourceAdvSid = in.readByte();
                    final int broadcastId = in.readInt();
                    final int paSyncState = in.readInt();
                    final int audioSyncState = in.readInt();
                    final int encryptionStatus = in.readInt();
                    final int badBroadcastLen = in.readInt();
                    byte[] badBroadcastCode = null;

                    if (badBroadcastLen > 0) {
                        badBroadcastCode = new byte[badBroadcastLen];
                        in.readByteArray(badBroadcastCode);
                    }
                    final byte numSubGroups = in.readByte();
                    final String broadcastCode = in.readString();
                    Map<Integer, Integer> subgroupBisSyncState = new HashMap<Integer, Integer>();
                    readSubgroupBisSyncStateFromParcel(in, subgroupBisSyncState);
                    Map<Integer, byte[]> subgroupMetadata = new HashMap<Integer, byte[]>();
                    readSubgroupMetadataFromParcel(in, subgroupMetadata);

                    BluetoothLeBroadcastSourceInfo srcInfo =
                            new BluetoothLeBroadcastSourceInfo(
                                    sourceId,
                                    sourceAddressType,
                                    sourceDevice,
                                    sourceAdvSid,
                                    broadcastId,
                                    paSyncState,
                                    encryptionStatus,
                                    audioSyncState,
                                    badBroadcastCode,
                                    numSubGroups,
                                    subgroupBisSyncState,
                                    subgroupMetadata,
                                    broadcastCode);
                    return srcInfo;
                }

                public @NonNull BluetoothLeBroadcastSourceInfo[] newArray(int size) {
                    return new BluetoothLeBroadcastSourceInfo[size];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeByte(mSourceId);
        out.writeInt(mSourceAddressType);
        out.writeTypedObject(mSourceDevice, 0);
        out.writeByte(mSourceAdvSid);
        out.writeInt(mBroadcastId);
        out.writeInt(mPaSyncState);
        out.writeInt(mAudioSyncState);
        out.writeInt(mEncryptionStatus);

        if (mBadBroadcastCode != null) {
            out.writeInt(mBadBroadcastCode.length);
            out.writeByteArray(mBadBroadcastCode);
        } else {
            // zero indicates that there is no "bad broadcast code"
            out.writeInt(0);
        }
        out.writeByte(mNumSubGroups);
        out.writeString(mBroadcastCode);
        writeSubgroupBisSyncStateToParcel(out, mSubgroupBisSyncState);
        writeSubgroupMetadataToParcel(out, mSubgroupMetadata);
    }

    private static void log(@NonNull String msg) {
        if (DBG) {
            Log.d(TAG, msg);
        }
    }
}
;
