/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.util.Log;

import android.media.MediaCodecInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/**
 * Allows you to enumerate available codecs, each specified as a {@link MediaCodecInfo} object,
 * find a codec supporting a given format and query the capabilities
 * of a given codec.
 * <p>See {@link MediaCodecInfo} for sample usage.
 */
final public class MediaCodecList {
    private static final String TAG = "MediaCodecList";

    /**
     * Count the number of available (regular) codecs.
     *
     * @deprecated Use {@link #getCodecInfos} instead.
     *
     * @see #REGULAR_CODECS
     */
    public static final int getCodecCount() {
        initCodecList();
        return sRegularCodecInfos.length;
    }

    private static native final int native_getCodecCount();

    /**
     * Return the {@link MediaCodecInfo} object for the codec at
     * the given {@code index} in the regular list.
     *
     * @deprecated Use {@link #getCodecInfos} instead.
     *
     * @see #REGULAR_CODECS
     */
    public static final MediaCodecInfo getCodecInfoAt(int index) {
        initCodecList();
        if (index < 0 || index > sRegularCodecInfos.length) {
            throw new IllegalArgumentException();
        }
        return sRegularCodecInfos[index];
    }

    /* package private */ static final Map<String, Object> getGlobalSettings() {
        synchronized (sInitLock) {
            if (sGlobalSettings == null) {
                sGlobalSettings = native_getGlobalSettings();
            }
        }
        return sGlobalSettings;
    }

    private static Object sInitLock = new Object();
    private static MediaCodecInfo[] sAllCodecInfos;
    private static MediaCodecInfo[] sRegularCodecInfos;
    private static Map<String, Object> sGlobalSettings;

    private static final void initCodecList() {
        synchronized (sInitLock) {
            if (sRegularCodecInfos == null) {
                int count = native_getCodecCount();
                ArrayList<MediaCodecInfo> regulars = new ArrayList<MediaCodecInfo>();
                ArrayList<MediaCodecInfo> all = new ArrayList<MediaCodecInfo>();
                for (int index = 0; index < count; index++) {
                    try {
                        MediaCodecInfo info = getNewCodecInfoAt(index);
                        all.add(info);
                        info = info.makeRegular();
                        if (info != null) {
                            regulars.add(info);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Could not get codec capabilities", e);
                    }
                }
                sRegularCodecInfos =
                    regulars.toArray(new MediaCodecInfo[regulars.size()]);
                sAllCodecInfos =
                    all.toArray(new MediaCodecInfo[all.size()]);
            }
        }
    }

    private static MediaCodecInfo getNewCodecInfoAt(int index) {
        String[] supportedTypes = getSupportedTypes(index);
        MediaCodecInfo.CodecCapabilities[] caps =
            new MediaCodecInfo.CodecCapabilities[supportedTypes.length];
        int typeIx = 0;
        for (String type: supportedTypes) {
            caps[typeIx++] = getCodecCapabilities(index, type);
        }
        return new MediaCodecInfo(
                getCodecName(index), isEncoder(index), caps);
    }

    /* package private */ static native final String getCodecName(int index);

    /* package private */ static native final boolean isEncoder(int index);

    /* package private */ static native final String[] getSupportedTypes(int index);

    /* package private */ static native final MediaCodecInfo.CodecCapabilities
        getCodecCapabilities(int index, String type);

    /* package private */ static native final Map<String, Object> native_getGlobalSettings();

    /* package private */ static native final int findCodecByName(String codec);

    /** @hide */
    public static MediaCodecInfo getInfoFor(String codec) {
        initCodecList();
        return sAllCodecInfos[findCodecByName(codec)];
    }

    private static native final void native_init();

    /**
     * Use in {@link #MediaCodecList} to enumerate only codecs that are suitable
     * for regular (buffer-to-buffer) decoding or encoding.
     *
     * <em>NOTE:</em> These are the codecs that are returned prior to API 21,
     * using the now deprecated static methods.
     */
    public static final int REGULAR_CODECS = 0;

    /**
     * Use in {@link #MediaCodecList} to enumerate all codecs, even ones that are
     * not suitable for regular (buffer-to-buffer) decoding or encoding.  These
     * include codecs, for example, that only work with special input or output
     * surfaces, such as secure-only or tunneled-only codecs.
     *
     * @see MediaCodecInfo.CodecCapabilities#isFormatSupported
     * @see MediaCodecInfo.CodecCapabilities#FEATURE_SecurePlayback
     * @see MediaCodecInfo.CodecCapabilities#FEATURE_TunneledPlayback
     */
    public static final int ALL_CODECS = 1;

    private MediaCodecList() {
        this(REGULAR_CODECS);
    }

    private MediaCodecInfo[] mCodecInfos;

    /**
     * Create a list of media-codecs of a specific kind.
     * @param kind Either {@code REGULAR_CODECS} or {@code ALL_CODECS}.
     */
    public MediaCodecList(int kind) {
        initCodecList();
        if (kind == REGULAR_CODECS) {
            mCodecInfos = sRegularCodecInfos;
        } else {
            mCodecInfos = sAllCodecInfos;
        }
    }

    /**
     * Returns the list of {@link MediaCodecInfo} objects for the list
     * of media-codecs.
     */
    public final MediaCodecInfo[] getCodecInfos() {
        return Arrays.copyOf(mCodecInfos, mCodecInfos.length);
    }

    static {
        System.loadLibrary("media_jni");
        native_init();

        // mediaserver is not yet alive here
    }

    /**
     * Find a decoder supporting a given {@link MediaFormat} in the list
     * of media-codecs.
     *
     * <p class=note>
     * <strong>Note:</strong> On {@link android.os.Build.VERSION_CODES#LOLLIPOP},
     * {@code format} must not contain a {@linkplain MediaFormat#KEY_FRAME_RATE
     * frame rate}. Use
     * <code class=prettyprint>format.setString(MediaFormat.KEY_FRAME_RATE, null)</code>
     * to clear any existing frame rate setting in the format.
     *
     * @see MediaCodecList.CodecCapabilities.isFormatSupported for format keys
     * considered per android versions when evaluating suitable codecs.
     *
     * @param format A decoder media format with optional feature directives.
     * @throws IllegalArgumentException if format is not a valid media format.
     * @throws NullPointerException if format is null.
     * @return the name of a decoder that supports the given format and feature
     *         requests, or {@code null} if no such codec has been found.
     */
    public final String findDecoderForFormat(MediaFormat format) {
        return findCodecForFormat(false /* encoder */, format);
    }

    /**
     * Find an encoder supporting a given {@link MediaFormat} in the list
     * of media-codecs.
     *
     * <p class=note>
     * <strong>Note:</strong> On {@link android.os.Build.VERSION_CODES#LOLLIPOP},
     * {@code format} must not contain a {@linkplain MediaFormat#KEY_FRAME_RATE
     * frame rate}. Use
     * <code class=prettyprint>format.setString(MediaFormat.KEY_FRAME_RATE, null)</code>
     * to clear any existing frame rate setting in the format.
     *
     * @see MediaCodecList.CodecCapabilities.isFormatSupported for format keys
     * considered per android versions when evaluating suitable codecs.
     *
     * @param format An encoder media format with optional feature directives.
     * @throws IllegalArgumentException if format is not a valid media format.
     * @throws NullPointerException if format is null.
     * @return the name of an encoder that supports the given format and feature
     *         requests, or {@code null} if no such codec has been found.
     */
    public final String findEncoderForFormat(MediaFormat format) {
        return findCodecForFormat(true /* encoder */, format);
    }

    private String findCodecForFormat(boolean encoder, MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        for (MediaCodecInfo info: mCodecInfos) {
            if (info.isEncoder() != encoder) {
                continue;
            }
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                if (caps != null && caps.isFormatSupported(format)) {
                    return info.getName();
                }
            } catch (IllegalArgumentException e) {
                // type is not supported
            }
        }
        return null;
    }
}
