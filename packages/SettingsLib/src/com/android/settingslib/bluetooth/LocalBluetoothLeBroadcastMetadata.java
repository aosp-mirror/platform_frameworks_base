/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudioCodecConfigMetadata;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastChannel;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastSubgroup;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LocalBluetoothLeBroadcastMetadata {
    private static final boolean DEBUG = BluetoothUtils.D;
    private static final String TAG = "LocalBluetoothLeBroadcastMetadata";
    private static final String METADATA_START = "<";
    private static final String METADATA_END = ">";
    private static final String PATTERN_REGEX = "<(.*?)>";

    private BluetoothLeBroadcastSubgroup mSubgroup;
    private List<BluetoothLeBroadcastSubgroup> mSubgroupList;

    // BluetoothLeBroadcastMetadata
    // Optional: Identity address type
    private int mSourceAddressType;
    // Optional: Must use identity address
    private BluetoothDevice mSourceDevice;
    private int mSourceAdvertisingSid;
    private int mBroadcastId;
    private int mPaSyncInterval;
    private int mPresentationDelayMicros;
    private boolean mIsEncrypted;
    private byte[] mBroadcastCode;

    // BluetoothLeBroadcastSubgroup
    private long mCodecId;
    private BluetoothLeAudioContentMetadata mContentMetadata;
    private BluetoothLeAudioCodecConfigMetadata mConfigMetadata;
    private BluetoothLeBroadcastChannel mChannel;

    // BluetoothLeAudioCodecConfigMetadata
    private long mAudioLocation;

    // BluetoothLeAudioContentMetadata
    private String mLanguage;
    private String mProgramInfo;

    // BluetoothLeBroadcastChannel
    private boolean mIsSelected;
    private int mChannelIndex;


    LocalBluetoothLeBroadcastMetadata(BluetoothLeBroadcastMetadata metadata) {
        mSourceAddressType = metadata.getSourceAddressType();
        mSourceDevice = metadata.getSourceDevice();
        mSourceAdvertisingSid = metadata.getSourceAdvertisingSid();
        mBroadcastId = metadata.getBroadcastId();
        mPaSyncInterval = metadata.getPaSyncInterval();
        mIsEncrypted = metadata.isEncrypted();
        mBroadcastCode = metadata.getBroadcastCode();
        mPresentationDelayMicros = metadata.getPresentationDelayMicros();
        mSubgroupList = metadata.getSubgroups();
    }

    public LocalBluetoothLeBroadcastMetadata() {
    }

    public void setBroadcastCode(byte[] code) {
        mBroadcastCode = code;
    }

    public int getBroadcastId() {
        return mBroadcastId;
    }

    public String convertToQrCodeString() {
        return new StringBuilder()
                .append(BluetoothBroadcastUtils.SCHEME_BT_BROADCAST_METADATA)
                .append(BluetoothBroadcastUtils.PREFIX_BT_ADDRESS_TYPE)
                .append(METADATA_START).append(mSourceAddressType).append(METADATA_END)
                .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                .append(BluetoothBroadcastUtils.PREFIX_BT_DEVICE)
                .append(METADATA_START).append(mSourceDevice).append(METADATA_END)
                .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                .append(BluetoothBroadcastUtils.PREFIX_BT_ADVERTISING_SID)
                .append(METADATA_START).append(mSourceAdvertisingSid).append(METADATA_END)
                .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                .append(BluetoothBroadcastUtils.PREFIX_BT_BROADCAST_ID)
                .append(METADATA_START).append(mBroadcastId).append(METADATA_END)
                .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                .append(BluetoothBroadcastUtils.PREFIX_BT_SYNC_INTERVAL)
                .append(METADATA_START).append(mPaSyncInterval).append(METADATA_END)
                .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                .append(BluetoothBroadcastUtils.PREFIX_BT_IS_ENCRYPTED)
                .append(METADATA_START).append(mIsEncrypted).append(METADATA_END)
                .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                .append(BluetoothBroadcastUtils.PREFIX_BT_BROADCAST_CODE)
                .append(METADATA_START).append(Arrays.toString(mBroadcastCode)).append(METADATA_END)
                .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                .append(BluetoothBroadcastUtils.PREFIX_BT_PRESENTATION_DELAY)
                .append(METADATA_START).append(mPresentationDelayMicros).append(METADATA_END)
                .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                .append(BluetoothBroadcastUtils.PREFIX_BT_SUBGROUPS)
                .append(METADATA_START).append(mSubgroupList).append(METADATA_END)
                .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                .toString();
    }

    /**
     * Example : prefix is with the “BT:”, and end by the Android Version.
     * BT:T:<1>;D:<00:11:22:AA:BB:CC>;AS:<1>;B:…;V:T;;
     *
     * @return BluetoothLeBroadcastMetadata
     */
    public BluetoothLeBroadcastMetadata convertToBroadcastMetadata(String qrCodeString) {
        if (DEBUG) {
            Log.d(TAG, "Convert " + qrCodeString + "to BluetoothLeBroadcastMetadata");
        }
        Pattern pattern = Pattern.compile(PATTERN_REGEX);
        Matcher match = pattern.matcher(qrCodeString);
        if (match.find()) {
            ArrayList<String> resultList = new ArrayList<>();
            resultList.add(match.group(1));
            mSourceAddressType = Integer.parseInt(resultList.get(0));
            mSourceDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(
                    resultList.get(1));
            mSourceAdvertisingSid = Integer.parseInt(resultList.get(2));
            mBroadcastId = Integer.parseInt(resultList.get(3));
            mPaSyncInterval = Integer.parseInt(resultList.get(4));
            mIsEncrypted = Boolean.valueOf(resultList.get(5));
            mBroadcastCode = resultList.get(6).getBytes();
            mPresentationDelayMicros = Integer.parseInt(resultList.get(7));
            mSubgroup = convertToSubgroup(resultList.get(8));

            if (DEBUG) {
                Log.d(TAG, "Converted qrCodeString result: " + match.group());
            }

            return new BluetoothLeBroadcastMetadata.Builder()
                    .setSourceDevice(mSourceDevice, mSourceAddressType)
                    .setSourceAdvertisingSid(mSourceAdvertisingSid)
                    .setBroadcastId(mBroadcastId)
                    .setPaSyncInterval(mPaSyncInterval)
                    .setEncrypted(mIsEncrypted)
                    .setBroadcastCode(mBroadcastCode)
                    .setPresentationDelayMicros(mPresentationDelayMicros)
                    .addSubgroup(mSubgroup)
                    .build();
        } else {
            if (DEBUG) {
                Log.d(TAG,
                        "The match fail, can not convert it to BluetoothLeBroadcastMetadata.");
            }
            return null;
        }
    }

    private BluetoothLeBroadcastSubgroup convertToSubgroup(String subgroupString) {
        if (DEBUG) {
            Log.d(TAG, "Convert " + subgroupString + "to BluetoothLeBroadcastSubgroup");
        }
        Pattern pattern = Pattern.compile(PATTERN_REGEX);
        Matcher match = pattern.matcher(subgroupString);
        if (match.find()) {
            ArrayList<String> resultList = new ArrayList<>();
            resultList.add(match.group(1));
            mCodecId = Long.getLong(resultList.get(0));
            mConfigMetadata = convertToConfigMetadata(resultList.get(1));
            mContentMetadata = convertToContentMetadata(resultList.get(2));
            mChannel = convertToChannel(resultList.get(3), mConfigMetadata);

            if (DEBUG) {
                Log.d(TAG, "Converted subgroupString result: " + match.group());
            }

            return new BluetoothLeBroadcastSubgroup.Builder()
                    .setCodecId(mCodecId)
                    .setCodecSpecificConfig(mConfigMetadata)
                    .setContentMetadata(mContentMetadata)
                    .addChannel(mChannel)
                    .build();
        } else {
            if (DEBUG) {
                Log.d(TAG,
                        "The match fail, can not convert it to BluetoothLeBroadcastSubgroup.");
            }
            return null;
        }
    }

    private BluetoothLeAudioCodecConfigMetadata convertToConfigMetadata(
            String configMetadataString) {
        if (DEBUG) {
            Log.d(TAG,
                    "Convert " + configMetadataString + "to BluetoothLeAudioCodecConfigMetadata");
        }
        Pattern pattern = Pattern.compile(PATTERN_REGEX);
        Matcher match = pattern.matcher(configMetadataString);
        if (match.find()) {
            ArrayList<String> resultList = new ArrayList<>();
            resultList.add(match.group(1));
            mAudioLocation = Long.getLong(resultList.get(0));

            if (DEBUG) {
                Log.d(TAG, "Converted configMetadataString result: " + match.group());
            }

            return new BluetoothLeAudioCodecConfigMetadata.Builder()
                    .setAudioLocation(mAudioLocation)
                    .build();
        } else {
            if (DEBUG) {
                Log.d(TAG,
                        "The match fail, can not convert it to "
                                + "BluetoothLeAudioCodecConfigMetadata.");
            }
            return null;
        }
    }

    private BluetoothLeAudioContentMetadata convertToContentMetadata(String contentMetadataString) {
        if (DEBUG) {
            Log.d(TAG, "Convert " + contentMetadataString + "to BluetoothLeAudioContentMetadata");
        }
        Pattern pattern = Pattern.compile(PATTERN_REGEX);
        Matcher match = pattern.matcher(contentMetadataString);
        if (match.find()) {
            ArrayList<String> resultList = new ArrayList<>();
            resultList.add(match.group(1));
            mProgramInfo = resultList.get(0);
            mLanguage = resultList.get(1);

            if (DEBUG) {
                Log.d(TAG, "Converted contentMetadataString result: " + match.group());
            }

            return new BluetoothLeAudioContentMetadata.Builder()
                    .setProgramInfo(mProgramInfo)
                    .setLanguage(mLanguage)
                    .build();
        } else {
            if (DEBUG) {
                Log.d(TAG,
                        "The match fail, can not convert it to BluetoothLeAudioContentMetadata.");
            }
            return null;
        }
    }

    private BluetoothLeBroadcastChannel convertToChannel(String channelString,
            BluetoothLeAudioCodecConfigMetadata configMetadata) {
        if (DEBUG) {
            Log.d(TAG, "Convert " + channelString + "to BluetoothLeBroadcastChannel");
        }
        Pattern pattern = Pattern.compile(PATTERN_REGEX);
        Matcher match = pattern.matcher(channelString);
        if (match.find()) {
            ArrayList<String> resultList = new ArrayList<>();
            resultList.add(match.group(1));
            mIsSelected = Boolean.valueOf(resultList.get(0));
            mChannelIndex = Integer.parseInt(resultList.get(1));

            if (DEBUG) {
                Log.d(TAG, "Converted channelString result: " + match.group());
            }

            return new BluetoothLeBroadcastChannel.Builder()
                    .setSelected(mIsSelected)
                    .setChannelIndex(mChannelIndex)
                    .setCodecMetadata(configMetadata)
                    .build();
        } else {
            if (DEBUG) {
                Log.d(TAG,
                        "The match fail, can not convert it to BluetoothLeBroadcastChannel.");
            }
            return null;
        }
    }
}
