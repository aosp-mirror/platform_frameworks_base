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

#include "ip.rsh"
//#pragma rs_fp_relaxed

static int histR[256] = {0}, histG[256] = {0}, histB[256] = {0};

rs_allocation histogramSource;
uint32_t histogramHeight;
uint32_t histogramWidth;

static float scaleR;
static float scaleG;
static float scaleB;

static uchar4 estimateWhite() {

    for (int i = 0; i < 256; i++) {
        histR[i] = 0; histG[i] = 0; histB[i] = 0;
    }

    for (uint32_t i = 0; i < histogramHeight; i++) {
        for (uint32_t j = 0; j < histogramWidth; j++) {
            uchar4 in = rsGetElementAt_uchar4(histogramSource, j, i);
            histR[in.r]++;
            histG[in.g]++;
            histB[in.b]++;
        }
    }

    int min_r = -1, min_g = -1, min_b = -1;
    int max_r =  0, max_g =  0, max_b =  0;
    int sum_r =  0, sum_g =  0, sum_b =  0;

    for (int i = 1; i < 255; i++) {
        int r = histR[i];
        int g = histG[i];
        int b = histB[i];
        sum_r += r;
        sum_g += g;
        sum_b += b;

        if (r>0){
            if (min_r < 0) min_r = i;
            max_r = i;
        }
        if (g>0){
            if (min_g < 0) min_g = i;
            max_g = i;
        }
        if (b>0){
            if (min_b < 0) min_b = i;
            max_b = i;
        }
    }

    int sum15r = 0, sum15g = 0, sum15b = 0;
    int count15r = 0, count15g = 0, count15b = 0;
    int tmp_r = 0, tmp_g = 0, tmp_b = 0;

    for (int i = 254; i >0; i--) {
        int r = histR[i];
        int g = histG[i];
        int b = histB[i];
        tmp_r += r;
        tmp_g += g;
        tmp_b += b;

        if ((tmp_r > sum_r/20) && (tmp_r < sum_r/5)) {
            sum15r += r*i;
            count15r += r;
        }
        if ((tmp_g > sum_g/20) && (tmp_g < sum_g/5)) {
            sum15g += g*i;
            count15g += g;
        }
        if ((tmp_b > sum_b/20) && (tmp_b < sum_b/5)) {
            sum15b += b*i;
            count15b += b;
        }

    }

    uchar4 out;

    if ((count15r>0) && (count15g>0) && (count15b>0) ){
        out.r = sum15r/count15r;
        out.g = sum15g/count15g;
        out.b = sum15b/count15b;
    }else {
        out.r = out.g = out.b = 255;
    }

    return out;

}

void prepareWhiteBalance() {
    uchar4 estimation = estimateWhite();
    int minimum = min(estimation.r, min(estimation.g, estimation.b));
    int maximum = max(estimation.r, max(estimation.g, estimation.b));
    float avg = (minimum + maximum) / 2.f;

    scaleR =  avg/estimation.r;
    scaleG =  avg/estimation.g;
    scaleB =  avg/estimation.b;

}

static unsigned char contrastClamp(int c)
{
    int N = 255;
    c &= ~(c >> 31);
    c -= N;
    c &= (c >> 31);
    c += N;
    return  (unsigned char) c;
}

void whiteBalanceKernel(const uchar4 *in, uchar4 *out) {
    float Rc =  in->r*scaleR;
    float Gc =  in->g*scaleG;
    float Bc =  in->b*scaleB;

    out->r = contrastClamp(Rc);
    out->g = contrastClamp(Gc);
    out->b = contrastClamp(Bc);
}
