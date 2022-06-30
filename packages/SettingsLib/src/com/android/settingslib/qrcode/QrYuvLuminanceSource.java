/**
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

package com.android.settingslib.qrcode;

import com.google.zxing.LuminanceSource;

public class QrYuvLuminanceSource extends LuminanceSource {

    private byte[] mYuvData;
    private int mWidth;
    private int mHeight;

    public QrYuvLuminanceSource(byte[] yuvData, int width, int height) {
        super(width, height);

        mWidth = width;
        mHeight = height;
        mYuvData = yuvData;
    }

    @Override
    public boolean isCropSupported() {
        return true;
    }

    @Override
    public LuminanceSource crop(int left, int top, int crop_width, int crop_height) {
        final byte[] newImage = new byte[crop_width * crop_height];
        int inputOffset = top * mWidth + left;

        if (left + crop_width > mWidth || top + crop_height > mHeight) {
            throw new IllegalArgumentException("cropped rectangle does not fit within image data.");
        }

        for (int y = 0; y < crop_height; y++) {
            System.arraycopy(mYuvData, inputOffset, newImage, y * crop_width, crop_width);
            inputOffset += mWidth;
        }
        return new QrYuvLuminanceSource(newImage, crop_width, crop_height);
    }

    @Override
    public byte[] getRow(int y, byte[] row) {
        if (y < 0 || y >= mHeight) {
            throw new IllegalArgumentException("Requested row is outside the image: " + y);
        }
        if (row == null || row.length < mWidth) {
            row = new byte[mWidth];
        }
        System.arraycopy(mYuvData, y * mWidth, row, 0, mWidth);
        return row;
    }

    @Override
    public byte[] getMatrix() {
        return mYuvData;
    }
}
