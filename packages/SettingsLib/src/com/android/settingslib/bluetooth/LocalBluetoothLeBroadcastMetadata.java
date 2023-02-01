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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LocalBluetoothLeBroadcastMetadata {
    private static final boolean DEBUG = BluetoothUtils.D;
    private static final String TAG = "LocalBluetoothLeBroadcastMetadata";
    private static final String METADATA_START = "<";
    private static final String METADATA_END = ">";
    private static final String PATTERN_REGEX = "<(.*?)>";
    private static final String PATTERN_BT_BROADCAST_METADATA =
            "T:<(.*?)>;+D:<(.*?)>;+AS:<(.*?)>;+B:<(.*?)>;+SI:<(.*?)>;+E:<(.*?)>;+C:<(.*?)>;"
                + "+PD:<(.*?)>;+SG:(.*)";
    private static final String PATTERN_BT_SUBGROUP =
            "CID:<(.*?)>;+CC:<(.*?);>;+AC:<(.*?);>;+CP:<(.*?)>;+BC:<(.*)>;>;";
    private static final String PATTERN_BT_CHANNEL = "CI:<(.*?)>;+BCCM:<(.*?);>;";

    /* Index for BluetoothLeBroadcastMetadata */
    private static int MATCH_INDEX_ADDRESS_TYPE = 1;
    private static int MATCH_INDEX_DEVICE = 2;
    private static int MATCH_INDEX_ADVERTISING_SID = 3;
    private static int MATCH_INDEX_BROADCAST_ID = 4;
    private static int MATCH_INDEX_SYNC_INTERVAL = 5;
    private static int MATCH_INDEX_IS_ENCRYPTED = 6;
    private static int MATCH_INDEX_BROADCAST_CODE = 7;
    private static int MATCH_INDEX_PRESENTATION_DELAY = 8;
    private static int MATCH_INDEX_SUBGROUPS = 9;

    /* Index for BluetoothLeBroadcastSubgroup */
    private static int MATCH_INDEX_CODEC_ID = 1;
    private static int MATCH_INDEX_CODEC_CONFIG = 2;
    private static int MATCH_INDEX_AUDIO_CONTENT = 3;
    private static int MATCH_INDEX_CHANNEL_PREF = 4;
    private static int MATCH_INDEX_BROADCAST_CHANNEL = 5;

    /* Index for BluetoothLeAudioCodecConfigMetadata */
    private static int LIST_INDEX_AUDIO_LOCATION = 0;
    private static int LIST_INDEX_CODEC_CONFIG_RAW_METADATA = 1;

    /* Index for BluetoothLeAudioContentMetadata */
    private static int LIST_INDEX_PROGRAM_INFO = 0;
    private static int LIST_INDEX_LANGUAGE = 1;
    private static int LIST_INDEX_AUDIO_CONTENT_RAW_METADATA = 2;

    /* Index for BluetoothLeBroadcastChannel */
    private static int MATCH_INDEX_CHANNEL_INDEX = 1;
    private static int MATCH_INDEX_CHANNEL_CODEC_CONFIG = 2;

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
    private int mCodecId;
    private BluetoothLeAudioContentMetadata mContentMetadata;
    private BluetoothLeAudioCodecConfigMetadata mConfigMetadata;
    private Boolean mNoChannelPreference;
    private List<BluetoothLeBroadcastChannel> mChannel;

    // BluetoothLeAudioCodecConfigMetadata
    private long mAudioLocation;
    private byte[] mCodecConfigMetadata;

    // BluetoothLeAudioContentMetadata
    private String mLanguage;
    private String mProgramInfo;
    private byte[] mAudioContentMetadata;

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
        String subgroupString = convertSubgroupToString(mSubgroupList);
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
                .append(METADATA_START).append(subgroupString).append(METADATA_END)
                .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                .toString();
    }

    private String convertSubgroupToString(List<BluetoothLeBroadcastSubgroup> subgroupList) {
        StringBuilder subgroupListBuilder = new StringBuilder();
        String subgroupString = "";
        for (BluetoothLeBroadcastSubgroup subgroup: subgroupList) {
            String audioCodec = convertAudioCodecConfigToString(subgroup.getCodecSpecificConfig());
            String audioContent = convertAudioContentToString(subgroup.getContentMetadata());
            boolean hasChannelPreference = subgroup.hasChannelPreference();
            String channels = convertChannelToString(subgroup.getChannels());
            subgroupString = new StringBuilder()
                    .append(BluetoothBroadcastUtils.PREFIX_BTSG_CODEC_ID)
                    .append(METADATA_START).append(subgroup.getCodecId()).append(METADATA_END)
                    .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                    .append(BluetoothBroadcastUtils.PREFIX_BTSG_CODEC_CONFIG)
                    .append(METADATA_START).append(audioCodec).append(METADATA_END)
                    .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                    .append(BluetoothBroadcastUtils.PREFIX_BTSG_AUDIO_CONTENT)
                    .append(METADATA_START).append(audioContent).append(METADATA_END)
                    .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                    .append(BluetoothBroadcastUtils.PREFIX_BTSG_CHANNEL_PREF)
                    .append(METADATA_START).append(hasChannelPreference).append(METADATA_END)
                    .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                    .append(BluetoothBroadcastUtils.PREFIX_BTSG_BROADCAST_CHANNEL)
                    .append(METADATA_START).append(channels).append(METADATA_END)
                    .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                    .toString();
            subgroupListBuilder.append(subgroupString);
        }
        return subgroupListBuilder.toString();
    }

    private String convertAudioCodecConfigToString(BluetoothLeAudioCodecConfigMetadata config) {
        String audioLocation = String.valueOf(config.getAudioLocation());
        String rawMetadata = new String(config.getRawMetadata(), StandardCharsets.UTF_8);
        return new StringBuilder()
            .append(BluetoothBroadcastUtils.PREFIX_BTCC_AUDIO_LOCATION)
            .append(METADATA_START).append(audioLocation).append(METADATA_END)
            .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
            .append(BluetoothBroadcastUtils.PREFIX_BTCC_RAW_METADATA)
            .append(METADATA_START).append(rawMetadata).append(METADATA_END)
            .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
            .toString();
    }

    private String convertAudioContentToString(BluetoothLeAudioContentMetadata audioContent) {
        String rawMetadata = new String(audioContent.getRawMetadata(), StandardCharsets.UTF_8);
        return new StringBuilder()
            .append(BluetoothBroadcastUtils.PREFIX_BTAC_PROGRAM_INFO)
            .append(METADATA_START).append(audioContent.getProgramInfo()).append(METADATA_END)
            .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
            .append(BluetoothBroadcastUtils.PREFIX_BTAC_LANGUAGE)
            .append(METADATA_START).append(audioContent.getLanguage()).append(METADATA_END)
            .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
            .append(BluetoothBroadcastUtils.PREFIX_BTAC_RAW_METADATA)
            .append(METADATA_START).append(rawMetadata).append(METADATA_END)
            .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
            .toString();
    }

    private String convertChannelToString(List<BluetoothLeBroadcastChannel> channelList) {
        StringBuilder channelListBuilder = new StringBuilder();
        String channelString = "";
        for (BluetoothLeBroadcastChannel channel: channelList) {
            String channelAudioCodec = convertAudioCodecConfigToString(channel.getCodecMetadata());
            channelString = new StringBuilder()
                .append(BluetoothBroadcastUtils.PREFIX_BTBC_CHANNEL_INDEX)
                .append(METADATA_START).append(channel.getChannelIndex()).append(METADATA_END)
                .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                .append(BluetoothBroadcastUtils.PREFIX_BTBC_CODEC_CONFIG)
                .append(METADATA_START).append(channelAudioCodec).append(METADATA_END)
                .append(BluetoothBroadcastUtils.DELIMITER_QR_CODE)
                .toString();
            channelListBuilder.append(channelString);
        }
        return channelListBuilder.toString();
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

        Pattern pattern = Pattern.compile(PATTERN_BT_BROADCAST_METADATA);
        Matcher match = pattern.matcher(qrCodeString);
        if (match.find()) {
            mSourceAddressType = Integer.parseInt(match.group(MATCH_INDEX_ADDRESS_TYPE));
            mSourceDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(
                    match.group(MATCH_INDEX_DEVICE));
            mSourceAdvertisingSid = Integer.parseInt(match.group(MATCH_INDEX_ADVERTISING_SID));
            mBroadcastId = Integer.parseInt(match.group(MATCH_INDEX_BROADCAST_ID));
            mPaSyncInterval = Integer.parseInt(match.group(MATCH_INDEX_SYNC_INTERVAL));
            mIsEncrypted = Boolean.valueOf(match.group(MATCH_INDEX_IS_ENCRYPTED));
            mBroadcastCode = match.group(MATCH_INDEX_BROADCAST_CODE).getBytes();
            mPresentationDelayMicros =
                  Integer.parseInt(match.group(MATCH_INDEX_PRESENTATION_DELAY));

            if (DEBUG) {
                Log.d(TAG, "Converted qrCodeString result: "
                        + " ,Type = " + mSourceAddressType
                        + " ,Device = " + mSourceDevice
                        + " ,AdSid = " + mSourceAdvertisingSid
                        + " ,BroadcastId = " + mBroadcastId
                        + " ,paSync = " + mPaSyncInterval
                        + " ,encrypted = " + mIsEncrypted
                        + " ,BroadcastCode = " + Arrays.toString(mBroadcastCode)
                        + " ,delay = " + mPresentationDelayMicros);
            }

            mSubgroup = convertToSubgroup(match.group(MATCH_INDEX_SUBGROUPS));

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
        Pattern pattern = Pattern.compile(PATTERN_BT_SUBGROUP);
        Matcher match = pattern.matcher(subgroupString);
        if (match.find()) {
            mCodecId = Integer.parseInt(match.group(MATCH_INDEX_CODEC_ID));
            mConfigMetadata = convertToConfigMetadata(match.group(MATCH_INDEX_CODEC_CONFIG));
            mContentMetadata = convertToContentMetadata(match.group(MATCH_INDEX_AUDIO_CONTENT));
            mNoChannelPreference = Boolean.valueOf(match.group(MATCH_INDEX_CHANNEL_PREF));
            mChannel =
                  convertToChannel(match.group(MATCH_INDEX_BROADCAST_CHANNEL), mConfigMetadata);

            BluetoothLeBroadcastSubgroup.Builder subgroupBuilder =
                    new BluetoothLeBroadcastSubgroup.Builder();
            subgroupBuilder.setCodecId(mCodecId);
            subgroupBuilder.setCodecSpecificConfig(mConfigMetadata);
            subgroupBuilder.setContentMetadata(mContentMetadata);

            for (BluetoothLeBroadcastChannel channel : mChannel) {
                subgroupBuilder.addChannel(channel);
            }
            return subgroupBuilder.build();
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
        ArrayList<String> resultList = new ArrayList<>();
        while (match.find()) {
            resultList.add(match.group(1));
            Log.d(TAG, "Codec Config match : " + match.group(1));
        }
        if (DEBUG) {
            Log.d(TAG, "Converted configMetadataString result: " + resultList.size());
        }
        if (resultList.size() > 0) {
            mAudioLocation = Long.parseLong(resultList.get(LIST_INDEX_AUDIO_LOCATION));
            mCodecConfigMetadata = resultList.get(LIST_INDEX_CODEC_CONFIG_RAW_METADATA).getBytes();
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
        ArrayList<String> resultList = new ArrayList<>();
        while (match.find()) {
            Log.d(TAG, "Audio Content match : " + match.group(1));
            resultList.add(match.group(1));
        }
        if (DEBUG) {
            Log.d(TAG, "Converted contentMetadataString result: " + resultList.size());
        }
        if (resultList.size() > 0) {
            mProgramInfo = resultList.get(LIST_INDEX_PROGRAM_INFO);
            mLanguage = resultList.get(LIST_INDEX_LANGUAGE);
            mAudioContentMetadata =
                  resultList.get(LIST_INDEX_AUDIO_CONTENT_RAW_METADATA).getBytes();

            /* TODO(b/265253566) : Need to set the default value for language when the user starts
            *  the broadcast.
            */
            if (mLanguage.equals("null")) {
                mLanguage = "eng";
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

    private List<BluetoothLeBroadcastChannel> convertToChannel(String channelString,
            BluetoothLeAudioCodecConfigMetadata configMetadata) {
        if (DEBUG) {
            Log.d(TAG, "Convert " + channelString + "to BluetoothLeBroadcastChannel");
        }
        Pattern pattern = Pattern.compile(PATTERN_BT_CHANNEL);
        Matcher match = pattern.matcher(channelString);
        Map<Integer, BluetoothLeAudioCodecConfigMetadata> channel =
                new HashMap<Integer, BluetoothLeAudioCodecConfigMetadata>();
        while (match.find()) {
            channel.put(Integer.parseInt(match.group(MATCH_INDEX_CHANNEL_INDEX)),
                    convertToConfigMetadata(match.group(MATCH_INDEX_CHANNEL_CODEC_CONFIG)));
        }

        if (channel.size() > 0) {
            mIsSelected = false;
            ArrayList<BluetoothLeBroadcastChannel> broadcastChannelList = new ArrayList<>();
            for (Map.Entry<Integer, BluetoothLeAudioCodecConfigMetadata> entry :
                    channel.entrySet()) {

                broadcastChannelList.add(
                        new BluetoothLeBroadcastChannel.Builder()
                            .setSelected(mIsSelected)
                            .setChannelIndex(entry.getKey())
                            .setCodecMetadata(entry.getValue())
                            .build());
            }
            return broadcastChannelList;
        } else {
            if (DEBUG) {
                Log.d(TAG,
                        "The match fail, can not convert it to BluetoothLeBroadcastChannel.");
            }
            return null;
        }
    }
}
