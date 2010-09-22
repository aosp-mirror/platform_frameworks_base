/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.cat;

/**
 * {@hide}
 */
public class ImageDescriptor {
    // members
    int width;
    int height;
    int codingScheme;
    int imageId;
    int highOffset;
    int lowOffset;
    int length;

    // constants
    static final int CODING_SCHEME_BASIC = 0x11;
    static final int CODING_SCHEME_COLOUR = 0x21;

    // public static final int ID_LENGTH = 9;
    // ID_LENGTH substituted by IccFileHandlerBase.GET_RESPONSE_EF_IMG_SIZE_BYTES

    ImageDescriptor() {
        width = 0;
        height = 0;
        codingScheme = 0;
        imageId = 0;
        highOffset = 0;
        lowOffset = 0;
        length = 0;
    }

    /**
     * Extract descriptor information about image instance.
     *
     * @param rawData
     * @param valueIndex
     * @return ImageDescriptor
     */
    static ImageDescriptor parse(byte[] rawData, int valueIndex) {
        ImageDescriptor d = new ImageDescriptor();
        try {
            d.width = rawData[valueIndex++] & 0xff;
            d.height = rawData[valueIndex++] & 0xff;
            d.codingScheme = rawData[valueIndex++] & 0xff;

            // parse image id
            d.imageId = (rawData[valueIndex++] & 0xff) << 8;
            d.imageId |= rawData[valueIndex++] & 0xff;
            // parse offset
            d.highOffset = (rawData[valueIndex++] & 0xff); // high byte offset
            d.lowOffset = rawData[valueIndex++] & 0xff; // low byte offset

            d.length = ((rawData[valueIndex++] & 0xff) << 8 | (rawData[valueIndex++] & 0xff));
        } catch (IndexOutOfBoundsException e) {
            CatLog.d("ImageDescripter", "parse; failed parsing image descriptor");
            d = null;
        }
        return d;
    }
}
