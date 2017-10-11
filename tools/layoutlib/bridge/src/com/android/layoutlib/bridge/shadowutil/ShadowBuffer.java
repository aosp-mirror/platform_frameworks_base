/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.layoutlib.bridge.shadowutil;

import android.annotation.NonNull;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;

public class ShadowBuffer {

    private int mWidth;
    private int mHeight;
    private Bitmap mBitmap;
    private int[] mData;

    public ShadowBuffer(int width, int height) {
        mWidth = width;
        mHeight = height;
        mBitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
        mData = new int[mBitmap.getWidth() * mBitmap.getHeight()];
        mBitmap.getPixels(mData, 0, mBitmap.getWidth(), 0, 0, mBitmap.getWidth(),
                mBitmap.getHeight());
    }

    public void generateTriangles(@NonNull float[] strip, float scale) {
        for (int i = 0; i < strip.length - 8; i += 3) {
            float fx3 = strip[i];
            float fy3 = strip[i + 1];
            float fz3 = scale * strip[i + 2];

            float fx2 = strip[i + 3];
            float fy2 = strip[i + 4];
            float fz2 = scale * strip[i + 5];

            float fx1 = strip[i + 6];
            float fy1 = strip[i + 7];
            float fz1 = scale * strip[i + 8];

            if (fx1 * (fy2 - fy3) + fx2 * (fy3 - fy1) + fx3 * (fy1 - fy2) == 0) {
                continue;
            }

            triangleZBuffMin(mData, mWidth, mHeight, fx3, fy3, fz3, fx2, fy2, fz2, fx1, fy1, fz1);
            triangleZBuffMin(mData, mWidth, mHeight, fx1, fy1, fz1, fx2, fy2, fz2, fx3, fy3, fz3);
        }
        mBitmap.setPixels(mData, 0, mBitmap.getWidth(), 0, 0, mBitmap.getWidth(),
                mBitmap.getHeight());
    }

    private void triangleZBuffMin(@NonNull int[] buff, int w, int h, float fx3, float fy3,
            float fz3, float fx2, float fy2, float fz2, float fx1, float fy1, float fz1) {
        if (((fx1 - fx2) * (fy3 - fy2) - (fy1 - fy2) * (fx3 - fx2)) < 0) {
            float tmpX = fx1;
            float tmpY = fy1;
            float tmpZ = fz1;
            fx1 = fx2;
            fy1 = fy2;
            fz1 = fz2;
            fx2 = tmpX;
            fy2 = tmpY;
            fz2 = tmpZ;
        }
        double d = (fx1 * (fy3 - fy2) - fx2 * fy3 + fx3 * fy2 + (fx2 - fx3) * fy1);

        if (d == 0) {
            return;
        }
        float dx = (float) (-(fy1 * (fz3 - fz2) - fy2 * fz3 + fy3 * fz2 + (fy2 - fy3) * fz1) / d);
        float dy = (float) ((fx1 * (fz3 - fz2) - fx2 * fz3 + fx3 * fz2 + (fx2 - fx3) * fz1) / d);
        float zOff = (float) ((fx1 * (fy3 * fz2 - fy2 * fz3) + fy1 * (fx2 * fz3 - fx3 * fz2) +
                (fx3 * fy2 - fx2 * fy3) * fz1) / d);

        int Y1 = (int) (16.0f * fy1 + .5f);
        int Y2 = (int) (16.0f * fy2 + .5f);
        int Y3 = (int) (16.0f * fy3 + .5f);

        int X1 = (int) (16.0f * fx1 + .5f);
        int X2 = (int) (16.0f * fx2 + .5f);
        int X3 = (int) (16.0f * fx3 + .5f);

        int DX12 = X1 - X2;
        int DX23 = X2 - X3;
        int DX31 = X3 - X1;

        int DY12 = Y1 - Y2;
        int DY23 = Y2 - Y3;
        int DY31 = Y3 - Y1;

        int FDX12 = DX12 << 4;
        int FDX23 = DX23 << 4;
        int FDX31 = DX31 << 4;

        int FDY12 = DY12 << 4;
        int FDY23 = DY23 << 4;
        int FDY31 = DY31 << 4;

        int minX = (min(X1, X2, X3) + 0xF) >> 4;
        int maxX = (max(X1, X2, X3) + 0xF) >> 4;
        int minY = (min(Y1, Y2, Y3) + 0xF) >> 4;
        int maxY = (max(Y1, Y2, Y3) + 0xF) >> 4;

        if (minY < 0) {
            minY = 0;
        }
        if (minX < 0) {
            minX = 0;
        }
        if (maxX > w) {
            maxX = w;
        }
        if (maxY > h) {
            maxY = h;
        }
        int off = minY * w;

        int C1 = DY12 * X1 - DX12 * Y1;
        int C2 = DY23 * X2 - DX23 * Y2;
        int C3 = DY31 * X3 - DX31 * Y3;

        if (DY12 < 0 || (DY12 == 0 && DX12 > 0)) {
            C1++;
        }
        if (DY23 < 0 || (DY23 == 0 && DX23 > 0)) {
            C2++;
        }
        if (DY31 < 0 || (DY31 == 0 && DX31 > 0)) {
            C3++;
        }
        int CY1 = C1 + DX12 * (minY << 4) - DY12 * (minX << 4);
        int CY2 = C2 + DX23 * (minY << 4) - DY23 * (minX << 4);
        int CY3 = C3 + DX31 * (minY << 4) - DY31 * (minX << 4);

        for (int y = minY; y < maxY; y++) {
            int CX1 = CY1;
            int CX2 = CY2;
            int CX3 = CY3;
            float p = zOff + dy * y;
            for (int x = minX; x < maxX; x++) {
                if (CX1 > 0 && CX2 > 0 && CX3 > 0) {
                    int point = x + off;
                    float zVal = p + dx * x;
                    buff[point] |= ((int) (zVal * 255)) << 24;
                }
                CX1 -= FDY12;
                CX2 -= FDY23;
                CX3 -= FDY31;
            }
            CY1 += FDX12;
            CY2 += FDX23;
            CY3 += FDX31;
            off += w;
        }
    }

    private int min(int x1, int x2, int x3) {
        return (x1 > x2) ? ((x2 > x3) ? x3 : x2) : ((x1 > x3) ? x3 : x1);
    }

    private int max(int x1, int x2, int x3) {
        return (x1 < x2) ? ((x2 < x3) ? x3 : x2) : ((x1 < x3) ? x3 : x1);
    }

    public void draw(@NonNull Canvas c) {
        c.drawBitmap(mBitmap, 0, 0, null);
    }
}
