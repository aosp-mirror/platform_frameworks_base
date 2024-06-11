/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;

import static com.android.server.hdmi.Constants.ADDR_BACKUP_1;
import static com.android.server.hdmi.Constants.ADDR_BACKUP_2;
import static com.android.server.hdmi.Constants.ADDR_TV;

import static java.util.Map.entry;

import android.annotation.Nullable;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.HexDump;
import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.server.hdmi.Constants.AbortReason;
import com.android.server.hdmi.Constants.AudioCodec;
import com.android.server.hdmi.Constants.FeatureOpcode;
import com.android.server.hdmi.Constants.PathRelationship;

import com.google.android.collect.Lists;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Various utilities to handle HDMI CEC messages.
 */
final class HdmiUtils {

    private static final String TAG = "HdmiUtils";

    private static final Map<Integer, List<Integer>> ADDRESS_TO_TYPE = Map.ofEntries(
            entry(Constants.ADDR_TV, Lists.newArrayList(HdmiDeviceInfo.DEVICE_TV)),
            entry(Constants.ADDR_RECORDER_1,
                    Lists.newArrayList(HdmiDeviceInfo.DEVICE_RECORDER)),
            entry(Constants.ADDR_RECORDER_2,
                    Lists.newArrayList(HdmiDeviceInfo.DEVICE_RECORDER)),
            entry(Constants.ADDR_TUNER_1, Lists.newArrayList(HdmiDeviceInfo.DEVICE_TUNER)),
            entry(Constants.ADDR_PLAYBACK_1,
                    Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK)),
            entry(Constants.ADDR_AUDIO_SYSTEM,
                    Lists.newArrayList(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM)),
            entry(Constants.ADDR_TUNER_2, Lists.newArrayList(HdmiDeviceInfo.DEVICE_TUNER)),
            entry(Constants.ADDR_TUNER_3, Lists.newArrayList(HdmiDeviceInfo.DEVICE_TUNER)),
            entry(Constants.ADDR_PLAYBACK_2,
                    Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK)),
            entry(Constants.ADDR_RECORDER_3,
                    Lists.newArrayList(HdmiDeviceInfo.DEVICE_RECORDER)),
            entry(Constants.ADDR_TUNER_4, Lists.newArrayList(HdmiDeviceInfo.DEVICE_TUNER)),
            entry(Constants.ADDR_PLAYBACK_3,
                    Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK)),
            entry(Constants.ADDR_BACKUP_1, Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK,
                    HdmiDeviceInfo.DEVICE_RECORDER, HdmiDeviceInfo.DEVICE_TUNER,
                    HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR)),
            entry(Constants.ADDR_BACKUP_2, Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK,
                    HdmiDeviceInfo.DEVICE_RECORDER, HdmiDeviceInfo.DEVICE_TUNER,
                    HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR)),
            entry(Constants.ADDR_SPECIFIC_USE, Lists.newArrayList(ADDR_TV)),
            entry(Constants.ADDR_UNREGISTERED, Collections.emptyList()));

    private static final String[] DEFAULT_NAMES = {
        "TV",
        "Recorder_1",
        "Recorder_2",
        "Tuner_1",
        "Playback_1",
        "AudioSystem",
        "Tuner_2",
        "Tuner_3",
        "Playback_2",
        "Recorder_3",
        "Tuner_4",
        "Playback_3",
        "Backup_1",
        "Backup_2",
        "Secondary_TV",
    };

    /**
     * Return value of {@link #getLocalPortFromPhysicalAddress(int, int)}
     */
    static final int TARGET_NOT_UNDER_LOCAL_DEVICE = -1;
    static final int TARGET_SAME_PHYSICAL_ADDRESS = 0;

    private HdmiUtils() { /* cannot be instantiated */ }

    /**
     * Check if the given logical address is valid. A logical address is valid
     * if it is one allocated for an actual device which allows communication
     * with other logical devices.
     *
     * @param address logical address
     * @return true if the given address is valid
     */
    static boolean isValidAddress(int address) {
        return (ADDR_TV <= address && address <= Constants.ADDR_SPECIFIC_USE);
    }

    static boolean isEligibleAddressForDevice(int deviceType, int logicalAddress) {
        return isValidAddress(logicalAddress)
                && ADDRESS_TO_TYPE.get(logicalAddress).contains(deviceType);
    }

    static boolean isEligibleAddressForCecVersion(int cecVersion, int logicalAddress) {
        if (isValidAddress(logicalAddress)) {
            if (logicalAddress == ADDR_BACKUP_1 || logicalAddress == ADDR_BACKUP_2) {
                return cecVersion >= HdmiControlManager.HDMI_CEC_VERSION_2_0;
            }
            return true;
        }
        return false;
    }

    /**
     * Return the device type for the given logical address.
     *
     * @param logicalAddress logical address
     * @return device type for the given logical address; DEVICE_INACTIVE
     *         if the address is not valid.
     */
    static List<Integer> getTypeFromAddress(int logicalAddress) {
        if (isValidAddress(logicalAddress)) {
            return ADDRESS_TO_TYPE.get(logicalAddress);
        }
        return Lists.newArrayList(HdmiDeviceInfo.DEVICE_INACTIVE);
    }

    /**
     * Return the default device name for a logical address. This is the name
     * by which the logical device is known to others until a name is
     * set explicitly using HdmiCecService.setOsdName.
     *
     * @param address logical address
     * @return default device name; empty string if the address is not valid
     */
    static String getDefaultDeviceName(int address) {
        if (isValidAddress(address)) {
            return DEFAULT_NAMES[address];
        }
        return "";
    }

    /**
     * Verify if the given address is for the given device type.  If not it will throw
     * {@link IllegalArgumentException}.
     *
     * @param logicalAddress the logical address to verify
     * @param deviceType the device type to check
     */
    static boolean verifyAddressType(int logicalAddress, int deviceType) {
        List<Integer> actualDeviceTypes = getTypeFromAddress(logicalAddress);
        if (!actualDeviceTypes.contains(deviceType)) {
            Slog.w(TAG,"Device type mismatch:[Expected:" + deviceType
                    + ", Actual:" + actualDeviceTypes + "]");
            return false;
        }
        return true;
    }

    /**
     * Check if the given CEC message come from the given address.
     *
     * @param cmd the CEC message to check
     * @param expectedAddress the expected source address of the given message
     * @param tag the tag of caller module (for log message)
     * @return true if the CEC message comes from the given address
     */
    static boolean checkCommandSource(HdmiCecMessage cmd, int expectedAddress, String tag) {
        int src = cmd.getSource();
        if (src != expectedAddress) {
            Slog.w(tag, "Invalid source [Expected:" + expectedAddress + ", Actual:" + src + "]");
            return false;
        }
        return true;
    }

    /**
     * Parse the parameter block of CEC message as [System Audio Status].
     *
     * @param cmd the CEC message to parse
     * @return true if the given parameter has [ON] value
     */
    static boolean parseCommandParamSystemAudioStatus(HdmiCecMessage cmd) {
        return cmd.getParams()[0] == Constants.SYSTEM_AUDIO_STATUS_ON;
    }

    /**
     * Parse the <Report Audio Status> message and check if it is mute
     *
     * @param cmd the CEC message to parse
     * @return true if the given parameter has [MUTE]
     */
    static boolean isAudioStatusMute(HdmiCecMessage cmd) {
        byte params[] = cmd.getParams();
        return (params[0] & 0x80) == 0x80;
    }

    /**
     * Parse the <Report Audio Status> message and extract the volume
     *
     * @param cmd the CEC message to parse
     * @return device's volume. Constants.UNKNOWN_VOLUME in case it is out of range
     */
    static int getAudioStatusVolume(HdmiCecMessage cmd) {
        byte params[] = cmd.getParams();
        int volume = params[0] & 0x7F;
        if (volume < 0x00 || 0x64 < volume) {
            volume = Constants.UNKNOWN_VOLUME;
        }
        return volume;
    }

    /**
     * Convert integer array to list of {@link Integer}.
     *
     * <p>The result is immutable.
     *
     * @param is integer array
     * @return {@link List} instance containing the elements in the given array
     */
    static List<Integer> asImmutableList(final int[] is) {
        ArrayList<Integer> list = new ArrayList<>(is.length);
        for (int type : is) {
            list.add(type);
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * Assemble two bytes into single integer value.
     *
     * @param data to be assembled
     * @return assembled value
     */
    static int twoBytesToInt(byte[] data) {
        return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    }

    /**
     * Assemble two bytes into single integer value.
     *
     * @param data to be assembled
     * @param offset offset to the data to convert in the array
     * @return assembled value
     */
    static int twoBytesToInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    /**
     * Assemble three bytes into single integer value.
     *
     * @param data to be assembled
     * @return assembled value
     */
    static int threeBytesToInt(byte[] data) {
        return ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
    }

    static <T> List<T> sparseArrayToList(SparseArray<T> array) {
        ArrayList<T> list = new ArrayList<>();
        for (int i = 0; i < array.size(); ++i) {
            list.add(array.valueAt(i));
        }
        return list;
    }

    static <T> List<T> mergeToUnmodifiableList(List<T> a, List<T> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return Collections.emptyList();
        }
        if (a.isEmpty()) {
            return Collections.unmodifiableList(b);
        }
        if (b.isEmpty()) {
            return Collections.unmodifiableList(a);
        }
        List<T> newList = new ArrayList<>();
        newList.addAll(a);
        newList.addAll(b);
        return Collections.unmodifiableList(newList);
    }

    /**
     * See if the new path is affecting the active path.
     *
     * @param activePath current active path
     * @param newPath new path
     * @return true if the new path changes the current active path
     */
    static boolean isAffectingActiveRoutingPath(int activePath, int newPath) {
        // The new path affects the current active path if the parent of the new path
        // is an ancestor of the active path.
        // (1.1.0.0, 2.0.0.0) -> true, new path alters the parent
        // (1.1.0.0, 1.2.0.0) -> true, new path is a sibling
        // (1.1.0.0, 1.2.1.0) -> false, new path is a descendant of a sibling
        // (1.0.0.0, 3.2.0.0) -> false, in a completely different path

        // Get the parent of the new path by clearing the least significant
        // non-zero nibble.
        for (int i = 0; i <= 12; i += 4) {
            int nibble = (newPath >> i) & 0xF;
            if (nibble != 0) {
                int mask = 0xFFF0 << i;
                newPath &= mask;
                break;
            }
        }
        if (newPath == 0x0000) {
            return true;  // Top path always affects the active path
        }
        return isInActiveRoutingPath(activePath, newPath);
    }

    /**
     * See if the new path is in the active path.
     *
     * @param activePath current active path
     * @param newPath new path
     * @return true if the new path in the active routing path
     */
    static boolean isInActiveRoutingPath(int activePath, int newPath) {
        @PathRelationship int pathRelationship = pathRelationship(newPath, activePath);
        return (pathRelationship == Constants.PATH_RELATIONSHIP_ANCESTOR
                || pathRelationship == Constants.PATH_RELATIONSHIP_DESCENDANT
                || pathRelationship == Constants.PATH_RELATIONSHIP_SAME);
    }

    /**
     * Computes the relationship from the first path to the second path.
     */
    static @PathRelationship int pathRelationship(int firstPath, int secondPath) {
        if (firstPath == Constants.INVALID_PHYSICAL_ADDRESS
                || secondPath == Constants.INVALID_PHYSICAL_ADDRESS) {
            return Constants.PATH_RELATIONSHIP_UNKNOWN;
        }
        // Loop forwards through both paths, looking for the first nibble where the paths differ.
        // Checking this nibble and the next one distinguishes between most possible relationships.
        for (int nibbleIndex = 0; nibbleIndex <= 3; nibbleIndex++) {
            int shift = 12 - nibbleIndex * 4;
            int firstPathNibble = (firstPath >> shift) & 0xF;
            int secondPathNibble = (secondPath >> shift) & 0xF;
            // Found the first nibble where the paths differ.
            if (firstPathNibble != secondPathNibble) {
                int firstPathNextNibble = (firstPath >> (shift - 4)) & 0xF;
                int secondPathNextNibble = (secondPath >> (shift - 4)) & 0xF;
                if (firstPathNibble == 0) {
                    return Constants.PATH_RELATIONSHIP_ANCESTOR;
                } else if (secondPathNibble == 0) {
                    return Constants.PATH_RELATIONSHIP_DESCENDANT;
                } else if (nibbleIndex == 3
                        || (firstPathNextNibble == 0 && secondPathNextNibble == 0)) {
                    return Constants.PATH_RELATIONSHIP_SIBLING;
                } else {
                    return Constants.PATH_RELATIONSHIP_DIFFERENT_BRANCH;
                }
            }
        }
        return Constants.PATH_RELATIONSHIP_SAME;
    }

    /**
     * Dump a {@link SparseArray} to the print writer.
     *
     * <p>The dump is formatted:
     * <pre>
     *     name:
     *        key = value
     *        key = value
     *        ...
     * </pre>
     */
    static <T> void dumpSparseArray(IndentingPrintWriter pw, String name,
            SparseArray<T> sparseArray) {
        printWithTrailingColon(pw, name);
        pw.increaseIndent();
        int size = sparseArray.size();
        for (int i = 0; i < size; i++) {
            int key = sparseArray.keyAt(i);
            T value = sparseArray.get(key);
            pw.printPair(Integer.toString(key), value);
            pw.println();
        }
        pw.decreaseIndent();
    }

    private static void printWithTrailingColon(IndentingPrintWriter pw, String name) {
        pw.println(name.endsWith(":") ? name : name.concat(":"));
    }

    /**
     * Dump a {@link Map} to the print writer.
     *
     * <p>The dump is formatted:
     * <pre>
     *     name:
     *        key = value
     *        key = value
     *        ...
     * </pre>
     */
    static <K, V> void dumpMap(IndentingPrintWriter pw, String name, Map<K, V> map) {
        printWithTrailingColon(pw, name);
        pw.increaseIndent();
        for (Map.Entry<K, V> entry: map.entrySet()) {
            pw.printPair(entry.getKey().toString(), entry.getValue());
            pw.println();
        }
        pw.decreaseIndent();
    }

    /**
     * Dump a {@link Map} to the print writer.
     *
     * <p>The dump is formatted:
     * <pre>
     *     name:
     *        value
     *        value
     *        ...
     * </pre>
     */
    static <T> void dumpIterable(IndentingPrintWriter pw, String name, Iterable<T> values) {
        printWithTrailingColon(pw, name);
        pw.increaseIndent();
        for (T value : values) {
            pw.println(value);
        }
        pw.decreaseIndent();
    }

    /**
     * Method to build target physical address to the port number on the current device.
     *
     * <p>This check assumes target address is valid.
     *
     * @param targetPhysicalAddress is the physical address of the target device
     * @param myPhysicalAddress is the physical address of the current device
     * @return
     * If the target device is under the current device, return the port number of current device
     * that the target device is connected to. This also applies to the devices that are indirectly
     * connected to the current device.
     *
     * <p>If the target device has the same physical address as the current device, return
     * {@link #TARGET_SAME_PHYSICAL_ADDRESS}.
     *
     * <p>If the target device is not under the current device, return
     * {@link #TARGET_NOT_UNDER_LOCAL_DEVICE}.
     */
    public static int getLocalPortFromPhysicalAddress(
            int targetPhysicalAddress, int myPhysicalAddress) {
        if (myPhysicalAddress == targetPhysicalAddress) {
            return TARGET_SAME_PHYSICAL_ADDRESS;
        }

        int mask = 0xF000;
        int finalMask = 0xF000;
        int maskedAddress = myPhysicalAddress;

        while (maskedAddress != 0) {
            maskedAddress = myPhysicalAddress & mask;
            finalMask |= mask;
            mask >>= 4;
        }

        int portAddress = targetPhysicalAddress & finalMask;
        if ((portAddress & (finalMask << 4)) != myPhysicalAddress) {
            return TARGET_NOT_UNDER_LOCAL_DEVICE;
        }

        mask <<= 4;
        int port = portAddress & mask;
        while ((port >> 4) != 0) {
            port >>= 4;
        }
        return port;
    }

    /**
     * Parse the Feature Abort CEC message parameter into a [Feature Opcode].
     *
     * @param cmd the CEC message to parse
     * @return the original opcode of the cec message that got aborted.
     */
    @FeatureOpcode
    static int getAbortFeatureOpcode(HdmiCecMessage cmd) {
        return cmd.getParams()[0] & 0xFF;
    }

    /**
     * Parse the Feature Abort CEC message parameter into an [Abort Reason].
     *
     * @param cmd the CEC message to parse
     * @return The reason to abort the feature.
     */
    @AbortReason
    static int getAbortReason(HdmiCecMessage cmd) {
        return cmd.getParams()[1];
    }

    /**
     * Build a CEC message from a hex byte string with bytes separated by {@code :}.
     *
     * <p>This format is used by both cec-client and www.cec-o-matic.com
     */
    public static HdmiCecMessage buildMessage(String message) {
        String[] parts = message.split(":");

        if (parts.length < 2) {
            throw new IllegalArgumentException("Message is too short");
        }
        for (String part : parts) {
            if (part.length() != 2) {
                throw new IllegalArgumentException("Malformatted CEC message: " + message);
            }
        }

        int src = Integer.parseInt(parts[0].substring(0, 1), 16);
        int dest = Integer.parseInt(parts[0].substring(1, 2), 16);
        int opcode = Integer.parseInt(parts[1], 16);
        byte[] params = new byte[parts.length - 2];
        for (int i = 0; i < params.length; i++) {
            params[i] = (byte) Integer.parseInt(parts[i + 2], 16);
        }
        return HdmiCecMessage.build(src, dest, opcode, params);
    }

    /**
     * Some operands in the CEC spec consist of a variable number of bytes, where each byte except
     * the last one has bit 7 set to 1.
     * Given the index of a byte in such an operand, this method returns the index of the last byte
     * in the operand, or -1 if the input is invalid (e.g. operand not terminated properly).
     * @param params Byte array representing a CEC message's parameters
     * @param offset Index of a byte in the operand to find the end of
     */
    public static int getEndOfSequence(byte[] params, int offset) {
        if (offset < 0) {
            return -1;
        }
        while (offset < params.length && ((params[offset] >> 7) & 1) == 1) {
            offset++;
        }
        if (offset >= params.length) {
            return -1;
        }
        return offset;
    }

    public static class ShortAudioDescriptorXmlParser {
        // We don't use namespaces
        private static final String NS = null;

        // return a list of devices config
        public static List<DeviceConfig> parse(InputStream in)
                throws XmlPullParserException, IOException {
            TypedXmlPullParser parser = Xml.resolvePullParser(in);
            parser.nextTag();
            return readDevices(parser);
        }

        private static void skip(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                throw new IllegalStateException();
            }
            int depth = 1;
            while (depth != 0) {
                switch (parser.next()) {
                    case XmlPullParser.END_TAG:
                        depth--;
                        break;
                    case XmlPullParser.START_TAG:
                        depth++;
                        break;
                }
            }
        }

        private static List<DeviceConfig> readDevices(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            List<DeviceConfig> devices = new ArrayList<>();

            parser.require(XmlPullParser.START_TAG, NS, "config");
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                // Starts by looking for the device tag
                if (name.equals("device")) {
                    String deviceType = parser.getAttributeValue(null, "type");
                    DeviceConfig config = null;
                    if (deviceType != null) {
                        config = readDeviceConfig(parser, deviceType);
                    }
                    if (config != null) {
                        devices.add(config);
                    }
                } else {
                    skip(parser);
                }
            }
            return devices;
        }

        // Processes device tags in the config.
        @Nullable
        private static DeviceConfig readDeviceConfig(TypedXmlPullParser parser, String deviceType)
                throws XmlPullParserException, IOException {
            List<CodecSad> codecSads = new ArrayList<>();
            int format;
            byte[] descriptor;

            parser.require(XmlPullParser.START_TAG, NS, "device");
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String tagName = parser.getName();

                // Starts by looking for the supportedFormat tag
                if (tagName.equals("supportedFormat")) {
                    String codecAttriValue = parser.getAttributeValue(null, "format");
                    String sadAttriValue = parser.getAttributeValue(null, "descriptor");
                    format = (codecAttriValue) == null
                            ? Constants.AUDIO_CODEC_NONE : formatNameToNum(codecAttriValue);
                    descriptor = readSad(sadAttriValue);
                    if (format != Constants.AUDIO_CODEC_NONE && descriptor != null) {
                        codecSads.add(new CodecSad(format, descriptor));
                    }
                    parser.nextTag();
                    parser.require(XmlPullParser.END_TAG, NS, "supportedFormat");
                } else {
                    skip(parser);
                }
            }
            if (codecSads.size() == 0) {
                return null;
            }
            return new DeviceConfig(deviceType, codecSads);
        }

        // Processes sad attribute in the supportedFormat.
        @Nullable
        private static byte[] readSad(String sad) {
            if (sad == null || sad.length() == 0) {
                return null;
            }
            byte[] sadBytes = HexDump.hexStringToByteArray(sad);
            if (sadBytes.length != 3) {
                Slog.w(TAG, "SAD byte array length is not 3. Length = " + sadBytes.length);
                return null;
            }
            return sadBytes;
        }

        @AudioCodec
        private static int formatNameToNum(String codecAttriValue) {
            switch (codecAttriValue) {
                case "AUDIO_FORMAT_NONE":
                    return Constants.AUDIO_CODEC_NONE;
                case "AUDIO_FORMAT_LPCM":
                    return Constants.AUDIO_CODEC_LPCM;
                case "AUDIO_FORMAT_DD":
                    return Constants.AUDIO_CODEC_DD;
                case "AUDIO_FORMAT_MPEG1":
                    return Constants.AUDIO_CODEC_MPEG1;
                case "AUDIO_FORMAT_MP3":
                    return Constants.AUDIO_CODEC_MP3;
                case "AUDIO_FORMAT_MPEG2":
                    return Constants.AUDIO_CODEC_MPEG2;
                case "AUDIO_FORMAT_AAC":
                    return Constants.AUDIO_CODEC_AAC;
                case "AUDIO_FORMAT_DTS":
                    return Constants.AUDIO_CODEC_DTS;
                case "AUDIO_FORMAT_ATRAC":
                    return Constants.AUDIO_CODEC_ATRAC;
                case "AUDIO_FORMAT_ONEBITAUDIO":
                    return Constants.AUDIO_CODEC_ONEBITAUDIO;
                case "AUDIO_FORMAT_DDP":
                    return Constants.AUDIO_CODEC_DDP;
                case "AUDIO_FORMAT_DTSHD":
                    return Constants.AUDIO_CODEC_DTSHD;
                case "AUDIO_FORMAT_TRUEHD":
                    return Constants.AUDIO_CODEC_TRUEHD;
                case "AUDIO_FORMAT_DST":
                    return Constants.AUDIO_CODEC_DST;
                case "AUDIO_FORMAT_WMAPRO":
                    return Constants.AUDIO_CODEC_WMAPRO;
                case "AUDIO_FORMAT_MAX":
                    return Constants.AUDIO_CODEC_MAX;
                default:
                    return Constants.AUDIO_CODEC_NONE;
            }
        }
    }

    // Device configuration of its supported Codecs and their Short Audio Descriptors.
    public static class DeviceConfig {
        /** Name of the device. Should be {@link Constants.AudioDevice}. **/
        public final String name;
        /** List of a {@link CodecSad}. **/
        public final List<CodecSad> supportedCodecs;

        public DeviceConfig(String name, List<CodecSad> supportedCodecs) {
            this.name = name;
            this.supportedCodecs = supportedCodecs;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof DeviceConfig) {
                DeviceConfig that = (DeviceConfig) obj;
                return that.name.equals(this.name)
                    && that.supportedCodecs.equals(this.supportedCodecs);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                name,
                supportedCodecs.hashCode());
        }
    }

    // Short Audio Descriptor of a specific Codec
    public static class CodecSad {
        /** Audio Codec. Should be {@link Constants.AudioCodec}. **/
        public final int audioCodec;
        /**
         * Three-byte Short Audio Descriptor. See HDMI Specification 1.4b CEC 13.15.3 and
         * ANSI-CTA-861-F-FINAL 7.5.2 Audio Data Block for more details.
         */
        public final byte[] sad;

        public CodecSad(int audioCodec, byte[] sad) {
            this.audioCodec = audioCodec;
            this.sad = sad;
        }

        public CodecSad(int audioCodec, String sad) {
            this.audioCodec = audioCodec;
            this.sad = HexDump.hexStringToByteArray(sad);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof CodecSad) {
                CodecSad that = (CodecSad) obj;
                return that.audioCodec == this.audioCodec
                    && Arrays.equals(that.sad, this.sad);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                audioCodec,
                Arrays.hashCode(sad));
        }
    }
}
