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

package android.media;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.util.SparseIntArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Provides information about an audio device.
 */
public final class AudioDeviceInfo {

    /**
     * A device type associated with an unknown or uninitialized device.
     */
    public static final int TYPE_UNKNOWN          = 0;
    /**
     * A device type describing the attached earphone speaker.
     */
    public static final int TYPE_BUILTIN_EARPIECE = 1;
    /**
     * A device type describing the speaker system (i.e. a mono speaker or stereo speakers) built
     * in a device.
     */
    public static final int TYPE_BUILTIN_SPEAKER  = 2;
    /**
     * A device type describing a headset, which is the combination of a headphones and microphone.
     */
    public static final int TYPE_WIRED_HEADSET    = 3;
    /**
     * A device type describing a pair of wired headphones.
     */
    public static final int TYPE_WIRED_HEADPHONES = 4;
    /**
     * A device type describing an analog line-level connection.
     */
    public static final int TYPE_LINE_ANALOG      = 5;
    /**
     * A device type describing a digital line connection (e.g. SPDIF).
     */
    public static final int TYPE_LINE_DIGITAL     = 6;
    /**
     * A device type describing a Bluetooth device typically used for telephony.
     */
    public static final int TYPE_BLUETOOTH_SCO    = 7;
    /**
     * A device type describing a Bluetooth device supporting the A2DP profile.
     */
    public static final int TYPE_BLUETOOTH_A2DP   = 8;
    /**
     * A device type describing an HDMI connection .
     */
    public static final int TYPE_HDMI             = 9;
    /**
     * A device type describing the Audio Return Channel of an HDMI connection.
     */
    public static final int TYPE_HDMI_ARC         = 10;
    /**
     * A device type describing a USB audio device.
     */
    public static final int TYPE_USB_DEVICE       = 11;
    /**
     * A device type describing a USB audio device in accessory mode.
     */
    public static final int TYPE_USB_ACCESSORY    = 12;
    /**
     * A device type describing the audio device associated with a dock.
     */
    public static final int TYPE_DOCK             = 13;
    /**
     * A device type associated with the transmission of audio signals over FM.
     */
    public static final int TYPE_FM               = 14;
    /**
     * A device type describing the microphone(s) built in a device.
     */
    public static final int TYPE_BUILTIN_MIC      = 15;
    /**
     * A device type for accessing the audio content transmitted over FM.
     */
    public static final int TYPE_FM_TUNER         = 16;
    /**
     * A device type for accessing the audio content transmitted over the TV tuner system.
     */
    public static final int TYPE_TV_TUNER         = 17;
    /**
     * A device type describing the transmission of audio signals over the telephony network.
     */
    public static final int TYPE_TELEPHONY        = 18;
    /**
     * A device type describing the auxiliary line-level connectors.
     */
    public static final int TYPE_AUX_LINE         = 19;
    /**
     * A device type connected over IP.
     */
    public static final int TYPE_IP               = 20;
    /**
     * A type-agnostic device used for communication with external audio systems
     */
    public static final int TYPE_BUS              = 21;
    /**
     * A device type describing a USB audio headset.
     */
    public static final int TYPE_USB_HEADSET       = 22;
    /**
     * A device type describing a Hearing Aid.
     */
    public static final int TYPE_HEARING_AID   = 23;
    /**
     * A device type describing the speaker system (i.e. a mono speaker or stereo speakers) built
     * in a device, that is specifically tuned for outputting sounds like notifications and alarms
     * (i.e. sounds the user couldn't necessarily anticipate).
     * <p>Note that this physical audio device may be the same as {@link #TYPE_BUILTIN_SPEAKER}
     * but is driven differently to safely accommodate the different use case.</p>
     */
    public static final int TYPE_BUILTIN_SPEAKER_SAFE = 24;
    /**
     * A device type for rerouting audio within the Android framework between mixes and
     * system applications.
     * This type is for instance encountered when querying the output device of a track
     * (with {@link AudioTrack#getRoutedDevice()} playing from a device in screen mirroring mode,
     * where the audio is not heard on the device, but on the remote device.
     */
    // Typically created when using
    // {@link android.media.audiopolicy.AudioPolicy} for mixes created with the
    // {@link android.media.audiopolicy.AudioMix#ROUTE_FLAG_LOOP_BACK} flag.
    public static final int TYPE_REMOTE_SUBMIX = 25;

    /**
     * A device type describing a Bluetooth Low Energy (BLE) audio headset or headphones.
     * Headphones are grouped with headsets when the device is a sink:
     * the features of headsets and headphones with regard to playback are the same.
     */
    public static final int TYPE_BLE_HEADSET   = 26;

    /**
     * A device type describing a Bluetooth Low Energy (BLE) audio speaker.
     */
    public static final int TYPE_BLE_SPEAKER   = 27;

    /**
     * A device type describing an Echo Canceller loopback Reference.
     * This device is only used when capturing with MediaRecorder.AudioSource.ECHO_REFERENCE,
     * which requires privileged permission
     * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT}.
     * @hide */
    @RequiresPermission(Manifest.permission.CAPTURE_AUDIO_OUTPUT)
    public static final int TYPE_ECHO_REFERENCE   = 28;

    /**
     * A device type describing the Enhanced Audio Return Channel of an HDMI connection.
     */
    public static final int TYPE_HDMI_EARC         = 29;

    /**
     * A device type describing a Bluetooth Low Energy (BLE) broadcast group.
     */
    public static final int TYPE_BLE_BROADCAST   = 30;

    /** @hide */
    @IntDef(flag = false, prefix = "TYPE", value = {
            TYPE_BUILTIN_EARPIECE,
            TYPE_BUILTIN_SPEAKER,
            TYPE_WIRED_HEADSET,
            TYPE_WIRED_HEADPHONES,
            TYPE_BLUETOOTH_SCO,
            TYPE_BLUETOOTH_A2DP,
            TYPE_HDMI,
            TYPE_DOCK,
            TYPE_USB_ACCESSORY,
            TYPE_USB_DEVICE,
            TYPE_USB_HEADSET,
            TYPE_TELEPHONY,
            TYPE_LINE_ANALOG,
            TYPE_HDMI_ARC,
            TYPE_HDMI_EARC,
            TYPE_LINE_DIGITAL,
            TYPE_FM,
            TYPE_AUX_LINE,
            TYPE_IP,
            TYPE_BUS,
            TYPE_HEARING_AID,
            TYPE_BUILTIN_MIC,
            TYPE_FM_TUNER,
            TYPE_TV_TUNER,
            TYPE_BUILTIN_SPEAKER_SAFE,
            TYPE_REMOTE_SUBMIX,
            TYPE_BLE_HEADSET,
            TYPE_BLE_SPEAKER,
            TYPE_ECHO_REFERENCE,
            TYPE_BLE_BROADCAST}
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioDeviceType {}

    /** @hide */
    @IntDef(flag = false, prefix = "TYPE", value = {
            TYPE_BUILTIN_MIC,
            TYPE_BLUETOOTH_SCO,
            TYPE_BLUETOOTH_A2DP,
            TYPE_WIRED_HEADSET,
            TYPE_HDMI,
            TYPE_TELEPHONY,
            TYPE_DOCK,
            TYPE_USB_ACCESSORY,
            TYPE_USB_DEVICE,
            TYPE_USB_HEADSET,
            TYPE_FM_TUNER,
            TYPE_TV_TUNER,
            TYPE_LINE_ANALOG,
            TYPE_LINE_DIGITAL,
            TYPE_IP,
            TYPE_BUS,
            TYPE_REMOTE_SUBMIX,
            TYPE_BLE_HEADSET,
            TYPE_HDMI_ARC,
            TYPE_HDMI_EARC,
            TYPE_ECHO_REFERENCE}
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioDeviceTypeIn {}

    /** @hide */
    @IntDef(flag = false, prefix = "TYPE", value = {
            TYPE_BUILTIN_EARPIECE,
            TYPE_BUILTIN_SPEAKER,
            TYPE_WIRED_HEADSET,
            TYPE_WIRED_HEADPHONES,
            TYPE_BLUETOOTH_SCO,
            TYPE_BLUETOOTH_A2DP,
            TYPE_HDMI,
            TYPE_DOCK,
            TYPE_USB_ACCESSORY,
            TYPE_USB_DEVICE,
            TYPE_USB_HEADSET,
            TYPE_TELEPHONY,
            TYPE_LINE_ANALOG,
            TYPE_HDMI_ARC,
            TYPE_HDMI_EARC,
            TYPE_LINE_DIGITAL,
            TYPE_FM,
            TYPE_AUX_LINE,
            TYPE_IP,
            TYPE_BUS,
            TYPE_HEARING_AID,
            TYPE_BUILTIN_SPEAKER_SAFE,
            TYPE_BLE_HEADSET,
            TYPE_BLE_SPEAKER,
            TYPE_BLE_BROADCAST}
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioDeviceTypeOut {}

    /** @hide */
    /*package*/ static boolean isValidAudioDeviceTypeOut(int type) {
        switch (type) {
            case TYPE_BUILTIN_EARPIECE:
            case TYPE_BUILTIN_SPEAKER:
            case TYPE_WIRED_HEADSET:
            case TYPE_WIRED_HEADPHONES:
            case TYPE_BLUETOOTH_SCO:
            case TYPE_BLUETOOTH_A2DP:
            case TYPE_HDMI:
            case TYPE_DOCK:
            case TYPE_USB_ACCESSORY:
            case TYPE_USB_DEVICE:
            case TYPE_USB_HEADSET:
            case TYPE_TELEPHONY:
            case TYPE_LINE_ANALOG:
            case TYPE_HDMI_ARC:
            case TYPE_HDMI_EARC:
            case TYPE_LINE_DIGITAL:
            case TYPE_FM:
            case TYPE_AUX_LINE:
            case TYPE_IP:
            case TYPE_BUS:
            case TYPE_HEARING_AID:
            case TYPE_BUILTIN_SPEAKER_SAFE:
            case TYPE_BLE_HEADSET:
            case TYPE_BLE_SPEAKER:
            case TYPE_BLE_BROADCAST:
                return true;
            default:
                return false;
        }
    }

    /** @hide */
    /*package*/ static boolean isValidAudioDeviceTypeIn(int type) {
        switch (type) {
            case TYPE_BUILTIN_MIC:
            case TYPE_BLUETOOTH_SCO:
            case TYPE_BLUETOOTH_A2DP:
            case TYPE_WIRED_HEADSET:
            case TYPE_HDMI:
            case TYPE_TELEPHONY:
            case TYPE_DOCK:
            case TYPE_USB_ACCESSORY:
            case TYPE_USB_DEVICE:
            case TYPE_USB_HEADSET:
            case TYPE_FM_TUNER:
            case TYPE_TV_TUNER:
            case TYPE_LINE_ANALOG:
            case TYPE_LINE_DIGITAL:
            case TYPE_IP:
            case TYPE_BUS:
            case TYPE_REMOTE_SUBMIX:
            case TYPE_BLE_HEADSET:
            case TYPE_HDMI_ARC:
            case TYPE_HDMI_EARC:
            case TYPE_ECHO_REFERENCE:
                return true;
            default:
                return false;
        }
    }

    /**
     * @hide
     * Enforces whether the audio device type is acceptable for output.
     *
     * A vendor implemented output type should modify isValidAudioDeviceTypeOut()
     * appropriately to accept the new type.  Do not remove already acceptable types.
     *
     * @throws IllegalArgumentException on an invalid output device type.
     * @param type
     */
    @TestApi
    public static void enforceValidAudioDeviceTypeOut(int type) {
        if (!isValidAudioDeviceTypeOut(type)) {
            throw new IllegalArgumentException("Illegal output device type " + type);
        }
    }

    /**
     * @hide
     * Enforces whether the audio device type is acceptable for input.
     *
     * A vendor implemented input type should modify isValidAudioDeviceTypeIn()
     * appropriately to accept the new type.  Do not remove already acceptable types.
     *
     * @throws IllegalArgumentException on an invalid input device type.
     * @param type
     */
    @TestApi
    public static void enforceValidAudioDeviceTypeIn(int type) {
        if (!isValidAudioDeviceTypeIn(type)) {
            throw new IllegalArgumentException("Illegal input device type " + type);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AudioDeviceInfo that = (AudioDeviceInfo) o;
        return Objects.equals(getPort(), that.getPort());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getPort());
    }

    private final AudioDevicePort mPort;

    AudioDeviceInfo(AudioDevicePort port) {
       mPort = port;
    }

    /**
     * @hide
     * @return The underlying {@link AudioDevicePort} instance.
     */
    public AudioDevicePort getPort() {
        return mPort;
    }

    /**
     * @hide
     * @return the internal device type
     */
    public int getInternalType() {
        return mPort.type();
    }

    /**
     * @return The internal device ID.
     */
    public int getId() {
        return mPort.handle().id();
    }

    /**
     * @return The human-readable name of the audio device.
     */
    public CharSequence getProductName() {
        String portName = mPort.name();
        return (portName != null && portName.length() != 0) ? portName : android.os.Build.MODEL;
    }

    /**
     * @return The "address" string of the device. This generally contains device-specific
     * parameters.
     */
    public @NonNull String getAddress() {
        return mPort.address();
    }

   /**
     * @return true if the audio device is a source for audio data (e.e an input).
     */
    public boolean isSource() {
        return mPort.role() == AudioPort.ROLE_SOURCE;
    }

    /**
     * @return true if the audio device is a sink for audio data (i.e. an output).
     */
    public boolean isSink() {
        return mPort.role() == AudioPort.ROLE_SINK;
    }

    /**
     * @return An array of sample rates supported by the audio device.
     *
     * Note: an empty array indicates that the device supports arbitrary rates.
     */
    public @NonNull int[] getSampleRates() {
        return mPort.samplingRates();
    }

    /**
     * @return An array of channel position masks (e.g. {@link AudioFormat#CHANNEL_IN_STEREO},
     * {@link AudioFormat#CHANNEL_OUT_7POINT1}) for which this audio device can be configured.
     *
     * @see AudioFormat
     *
     * Note: an empty array indicates that the device supports arbitrary channel masks.
     */
    public @NonNull int[] getChannelMasks() {
        return mPort.channelMasks();
    }

    /**
     * @return An array of channel index masks for which this audio device can be configured.
     *
     * @see AudioFormat
     *
     * Note: an empty array indicates that the device supports arbitrary channel index masks.
     */
    public @NonNull int[] getChannelIndexMasks() {
        return mPort.channelIndexMasks();
    }

    /**
     * @return An array of channel counts (1, 2, 4, ...) for which this audio device
     * can be configured.
     *
     * Note: an empty array indicates that the device supports arbitrary channel counts.
     */
    public @NonNull int[] getChannelCounts() {
        TreeSet<Integer> countSet = new TreeSet<Integer>();

        // Channel Masks
        for (int mask : getChannelMasks()) {
            countSet.add(isSink() ?
                    AudioFormat.channelCountFromOutChannelMask(mask)
                    : AudioFormat.channelCountFromInChannelMask(mask));
        }

        // Index Masks
        for (int index_mask : getChannelIndexMasks()) {
            countSet.add(Integer.bitCount(index_mask));
        }

        int[] counts = new int[countSet.size()];
        int index = 0;
        for (int count : countSet) {
            counts[index++] = count; 
        }
        return counts;
    }

    /**
     * @return An array of audio encodings (e.g. {@link AudioFormat#ENCODING_PCM_16BIT},
     * {@link AudioFormat#ENCODING_PCM_FLOAT}) supported by the audio device.
     * <code>ENCODING_PCM_FLOAT</code> indicates the device supports more
     * than 16 bits of integer precision.  As there is no AudioFormat constant
     * specifically defined for 24-bit PCM, the value <code>ENCODING_PCM_FLOAT</code>
     * indicates that {@link AudioTrack} or {@link AudioRecord} can preserve at least 24 bits of
     * integer precision to that device.
     *
     * @see AudioFormat
     *
     * Note: an empty array indicates that the device supports arbitrary encodings.
     * For forward compatibility, applications should ignore entries it does not recognize.
     */
    public @NonNull int[] getEncodings() {
        return AudioFormat.filterPublicFormats(mPort.formats());
    }

    /**
     * @return A list of {@link AudioProfile} supported by the audio devices.
     */
    public @NonNull List<AudioProfile> getAudioProfiles() {
        return mPort.profiles();
    }

    /**
     * @return A list of {@link AudioDescriptor} supported by the audio devices.
     */
    public @NonNull List<AudioDescriptor> getAudioDescriptors() {
        return mPort.audioDescriptors();
    }

    /**
     * Returns an array of supported encapsulation modes for the device.
     *
     * The array can include any of the {@code AudioTrack} encapsulation modes,
     * e.g. {@link AudioTrack#ENCAPSULATION_MODE_ELEMENTARY_STREAM}.
     *
     * @return An array of supported encapsulation modes for the device.  This
     *     may be an empty array if no encapsulation modes are supported.
     */
    public @NonNull @AudioTrack.EncapsulationMode int[] getEncapsulationModes() {
        return mPort.encapsulationModes();
    }

    /**
     * Returns an array of supported encapsulation metadata types for the device.
     *
     * The metadata type returned should be allowed for all encapsulation modes supported
     * by the device.  Some metadata types may apply only to certain
     * compressed stream formats, the returned list is the union of subsets.
     *
     * The array can include any of
     * {@link AudioTrack#ENCAPSULATION_METADATA_TYPE_FRAMEWORK_TUNER},
     * {@link AudioTrack#ENCAPSULATION_METADATA_TYPE_DVB_AD_DESCRIPTOR}.
     *
     * @return An array of supported encapsulation metadata types for the device.  This
     *     may be an empty array if no metadata types are supported.
     */
    public @NonNull @AudioTrack.EncapsulationMetadataType int[] getEncapsulationMetadataTypes() {
        return mPort.encapsulationMetadataTypes();
    }

   /**
     * @return The device type identifier of the audio device (i.e. TYPE_BUILTIN_SPEAKER).
     */
    public int getType() {
        return INT_TO_EXT_DEVICE_MAPPING.get(mPort.type(), TYPE_UNKNOWN);
    }

    /** @hide */
    public static int convertDeviceTypeToInternalDevice(int deviceType) {
        return EXT_TO_INT_DEVICE_MAPPING.get(deviceType, AudioSystem.DEVICE_NONE);
    }

    /** @hide */
    public static int convertInternalDeviceToDeviceType(int intDevice) {
        return INT_TO_EXT_DEVICE_MAPPING.get(intDevice, TYPE_UNKNOWN);
    }

    /** @hide */
    public static int convertDeviceTypeToInternalInputDevice(int deviceType) {
        return convertDeviceTypeToInternalInputDevice(deviceType, "");
    }
    /** @hide */
    public static int convertDeviceTypeToInternalInputDevice(int deviceType, String address) {
        int internalType = EXT_TO_INT_INPUT_DEVICE_MAPPING.get(deviceType, AudioSystem.DEVICE_NONE);
        if (internalType == AudioSystem.DEVICE_IN_BUILTIN_MIC
                && "back".equals(address)) {
            internalType = AudioSystem.DEVICE_IN_BACK_MIC;
        }
        return internalType;
    }

    private static final SparseIntArray INT_TO_EXT_DEVICE_MAPPING;

    private static final SparseIntArray EXT_TO_INT_DEVICE_MAPPING;

    /**
     * EXT_TO_INT_INPUT_DEVICE_MAPPING aims at mapping external device type to internal input device
     * type.
     */
    private static final SparseIntArray EXT_TO_INT_INPUT_DEVICE_MAPPING;

    static {
        INT_TO_EXT_DEVICE_MAPPING = new SparseIntArray();
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_EARPIECE, TYPE_BUILTIN_EARPIECE);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_SPEAKER, TYPE_BUILTIN_SPEAKER);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_WIRED_HEADSET, TYPE_WIRED_HEADSET);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE, TYPE_WIRED_HEADPHONES);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLUETOOTH_SCO, TYPE_BLUETOOTH_SCO);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_HEADSET, TYPE_BLUETOOTH_SCO);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_CARKIT, TYPE_BLUETOOTH_SCO);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, TYPE_BLUETOOTH_A2DP);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES, TYPE_BLUETOOTH_A2DP);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER, TYPE_BLUETOOTH_A2DP);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_HDMI, TYPE_HDMI);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET, TYPE_DOCK);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET, TYPE_DOCK);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_USB_ACCESSORY, TYPE_USB_ACCESSORY);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_USB_DEVICE, TYPE_USB_DEVICE);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_USB_HEADSET, TYPE_USB_HEADSET);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_TELEPHONY_TX, TYPE_TELEPHONY);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_LINE, TYPE_LINE_ANALOG);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_HDMI_ARC, TYPE_HDMI_ARC);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_HDMI_EARC, TYPE_HDMI_EARC);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_SPDIF, TYPE_LINE_DIGITAL);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_FM, TYPE_FM);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_AUX_LINE, TYPE_AUX_LINE);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_IP, TYPE_IP);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BUS, TYPE_BUS);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_HEARING_AID, TYPE_HEARING_AID);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_SPEAKER_SAFE,
                TYPE_BUILTIN_SPEAKER_SAFE);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_REMOTE_SUBMIX, TYPE_REMOTE_SUBMIX);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLE_HEADSET, TYPE_BLE_HEADSET);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLE_SPEAKER, TYPE_BLE_SPEAKER);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLE_BROADCAST, TYPE_BLE_BROADCAST);

        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_BUILTIN_MIC, TYPE_BUILTIN_MIC);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_BLUETOOTH_SCO_HEADSET, TYPE_BLUETOOTH_SCO);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_WIRED_HEADSET, TYPE_WIRED_HEADSET);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_HDMI, TYPE_HDMI);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_TELEPHONY_RX, TYPE_TELEPHONY);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_BACK_MIC, TYPE_BUILTIN_MIC);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_ANLG_DOCK_HEADSET, TYPE_DOCK);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_DGTL_DOCK_HEADSET, TYPE_DOCK);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_USB_ACCESSORY, TYPE_USB_ACCESSORY);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_USB_DEVICE, TYPE_USB_DEVICE);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_USB_HEADSET, TYPE_USB_HEADSET);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_FM_TUNER, TYPE_FM_TUNER);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_TV_TUNER, TYPE_TV_TUNER);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_LINE, TYPE_LINE_ANALOG);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_SPDIF, TYPE_LINE_DIGITAL);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, TYPE_BLUETOOTH_A2DP);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_IP, TYPE_IP);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_BUS, TYPE_BUS);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_REMOTE_SUBMIX, TYPE_REMOTE_SUBMIX);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_BLE_HEADSET, TYPE_BLE_HEADSET);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_HDMI_ARC, TYPE_HDMI_ARC);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_HDMI_EARC, TYPE_HDMI_EARC);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_ECHO_REFERENCE, TYPE_ECHO_REFERENCE);


        // privileges mapping to output device
        EXT_TO_INT_DEVICE_MAPPING = new SparseIntArray();
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_BUILTIN_EARPIECE, AudioSystem.DEVICE_OUT_EARPIECE);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_BUILTIN_SPEAKER, AudioSystem.DEVICE_OUT_SPEAKER);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_WIRED_HEADSET, AudioSystem.DEVICE_OUT_WIRED_HEADSET);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_WIRED_HEADPHONES, AudioSystem.DEVICE_OUT_WIRED_HEADPHONE);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_LINE_ANALOG, AudioSystem.DEVICE_OUT_LINE);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_LINE_DIGITAL, AudioSystem.DEVICE_OUT_SPDIF);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_BLUETOOTH_SCO, AudioSystem.DEVICE_OUT_BLUETOOTH_SCO);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_BLUETOOTH_A2DP, AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_HDMI, AudioSystem.DEVICE_OUT_HDMI);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_HDMI_ARC, AudioSystem.DEVICE_OUT_HDMI_ARC);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_HDMI_EARC, AudioSystem.DEVICE_OUT_HDMI_EARC);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_USB_DEVICE, AudioSystem.DEVICE_OUT_USB_DEVICE);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_USB_HEADSET, AudioSystem.DEVICE_OUT_USB_HEADSET);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_USB_ACCESSORY, AudioSystem.DEVICE_OUT_USB_ACCESSORY);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_DOCK, AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_FM, AudioSystem.DEVICE_OUT_FM);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_TELEPHONY, AudioSystem.DEVICE_OUT_TELEPHONY_TX);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_AUX_LINE, AudioSystem.DEVICE_OUT_AUX_LINE);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_IP, AudioSystem.DEVICE_OUT_IP);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_BUS, AudioSystem.DEVICE_OUT_BUS);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_HEARING_AID, AudioSystem.DEVICE_OUT_HEARING_AID);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_BUILTIN_SPEAKER_SAFE,
                AudioSystem.DEVICE_OUT_SPEAKER_SAFE);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_REMOTE_SUBMIX, AudioSystem.DEVICE_OUT_REMOTE_SUBMIX);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_BLE_HEADSET, AudioSystem.DEVICE_OUT_BLE_HEADSET);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_BLE_SPEAKER, AudioSystem.DEVICE_OUT_BLE_SPEAKER);
        EXT_TO_INT_DEVICE_MAPPING.put(TYPE_BLE_BROADCAST, AudioSystem.DEVICE_OUT_BLE_BROADCAST);

        // privileges mapping to input device
        EXT_TO_INT_INPUT_DEVICE_MAPPING = new SparseIntArray();
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_BUILTIN_MIC, AudioSystem.DEVICE_IN_BUILTIN_MIC);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(
                TYPE_BLUETOOTH_SCO, AudioSystem.DEVICE_IN_BLUETOOTH_SCO_HEADSET);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(
                TYPE_WIRED_HEADSET, AudioSystem.DEVICE_IN_WIRED_HEADSET);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_HDMI, AudioSystem.DEVICE_IN_HDMI);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_TELEPHONY, AudioSystem.DEVICE_IN_TELEPHONY_RX);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_DOCK, AudioSystem.DEVICE_IN_ANLG_DOCK_HEADSET);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(
                TYPE_USB_ACCESSORY, AudioSystem.DEVICE_IN_USB_ACCESSORY);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_USB_DEVICE, AudioSystem.DEVICE_IN_USB_DEVICE);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_USB_HEADSET, AudioSystem.DEVICE_IN_USB_HEADSET);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_FM_TUNER, AudioSystem.DEVICE_IN_FM_TUNER);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_TV_TUNER, AudioSystem.DEVICE_IN_TV_TUNER);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_LINE_ANALOG, AudioSystem.DEVICE_IN_LINE);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_LINE_DIGITAL, AudioSystem.DEVICE_IN_SPDIF);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(
                TYPE_BLUETOOTH_A2DP, AudioSystem.DEVICE_IN_BLUETOOTH_A2DP);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_IP, AudioSystem.DEVICE_IN_IP);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_BUS, AudioSystem.DEVICE_IN_BUS);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(
                TYPE_REMOTE_SUBMIX, AudioSystem.DEVICE_IN_REMOTE_SUBMIX);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_BLE_HEADSET, AudioSystem.DEVICE_IN_BLE_HEADSET);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_HDMI_ARC, AudioSystem.DEVICE_IN_HDMI_ARC);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(TYPE_HDMI_EARC, AudioSystem.DEVICE_IN_HDMI_EARC);
        EXT_TO_INT_INPUT_DEVICE_MAPPING.put(
                TYPE_ECHO_REFERENCE, AudioSystem.DEVICE_IN_ECHO_REFERENCE);

    }
}

