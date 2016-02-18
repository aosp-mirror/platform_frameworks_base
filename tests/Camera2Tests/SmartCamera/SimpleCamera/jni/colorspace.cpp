/*
 * Copyright (C) 2013 The Android Open Source Project
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

#include "colorspace.h"

#include <jni.h>
#include <stdint.h>

typedef uint8_t uint8;
typedef uint32_t uint32;
typedef int32_t int32;

// RGBA helper struct allows access as int and individual channels
// WARNING: int value depends on endianness and should not be used to analyze individual channels.
union Rgba {
  uint32 color;
  uint8 channel[4];
};

// Channel index constants
static const uint8 kRed = 0;
static const uint8 kGreen = 1;
static const uint8 kBlue = 2;
static const uint8 kAlpha = 3;

// Clamp to range 0-255
static inline uint32 clamp(int32 x) {
  return x > 255 ? 255 : (x < 0 ? 0 : x);
}

// Convert YUV to RGBA
// This uses the ITU-R BT.601 coefficients.
static inline Rgba convertYuvToRgba(int32 y, int32 u, int32 v) {
  Rgba color;
  color.channel[kRed] = clamp(y + static_cast<int>(1.402 * v));
  color.channel[kGreen] = clamp(y - static_cast<int>(0.344 * u + 0.714 * v));
  color.channel[kBlue] = clamp(y + static_cast<int>(1.772 * u));
  color.channel[kAlpha] = 0xFF;
  return color;
}

// Colorspace conversion functions /////////////////////////////////////////////////////////////////
void JNI_COLORSPACE_METHOD(nativeYuv420pToRgba8888)(
    JNIEnv* env, jclass clazz, jobject input, jobject output, jint width, jint height) {
  uint8* const pInput = static_cast<uint8*>(env->GetDirectBufferAddress(input));
  Rgba* const pOutput = static_cast<Rgba*>(env->GetDirectBufferAddress(output));

  const int size = width * height;

  uint8* pInY = pInput;
  uint8* pInU = pInput + size;
  uint8* pInV = pInput + size + size / 4;
  Rgba* pOutColor = pOutput;

  for (int y = 0; y < height; y += 2) {
    for (int x = 0; x < width; x += 2) {
      int u, v, y1, y2, y3, y4;

      y1 = pInY[0];
      y2 = pInY[1];
      y3 = pInY[width];
      y4 = pInY[width + 1];

      u = *pInU - 128;
      v = *pInV - 128;

      pOutColor[0] = convertYuvToRgba(y1, u, v);
      pOutColor[1] = convertYuvToRgba(y2, u, v);
      pOutColor[width] = convertYuvToRgba(y3, u, v);
      pOutColor[width + 1] = convertYuvToRgba(y4, u, v);

      pInY += 2;
      pInU++;
      pInV++;
      pOutColor += 2;
    }
    pInY += width;
    pOutColor += width;
  }
}

void JNI_COLORSPACE_METHOD(nativeArgb8888ToRgba8888)(
    JNIEnv* env, jclass clazz, jobject input, jobject output, jint width, jint height) {
  Rgba* pInput = static_cast<Rgba*>(env->GetDirectBufferAddress(input));
  Rgba* pOutput = static_cast<Rgba*>(env->GetDirectBufferAddress(output));

  for (int i = 0; i < width * height; ++i) {
    Rgba color_in = *pInput++;
    Rgba& color_out = *pOutput++;
    color_out.channel[kRed] = color_in.channel[kGreen];
    color_out.channel[kGreen] = color_in.channel[kBlue];
    color_out.channel[kBlue] = color_in.channel[kAlpha];
    color_out.channel[kAlpha] = color_in.channel[kRed];
  }
}

void JNI_COLORSPACE_METHOD(nativeRgba8888ToHsva8888)(
    JNIEnv* env, jclass clazz, jobject input, jobject output, jint width, jint height) {
  Rgba* pInput = static_cast<Rgba*>(env->GetDirectBufferAddress(input));
  Rgba* pOutput = static_cast<Rgba*>(env->GetDirectBufferAddress(output));

  int r, g, b, a, h, s, v, c_max, c_min;
  float delta;
  for (int i = 0; i < width * height; ++i) {
    Rgba color_in = *pInput++;
    Rgba& color_out = *pOutput++;
    r = color_in.channel[kRed];
    g = color_in.channel[kGreen];
    b = color_in.channel[kBlue];
    a = color_in.channel[kAlpha];

    if (r > g) {
      c_min = (g > b) ? b : g;
      c_max = (r > b) ? r : b;
    } else {
      c_min = (r > b) ? b : r;
      c_max = (g > b) ? g : b;
    }
    delta = c_max -c_min;

    float scaler = 255 * 60 / 360.0f;
    if (c_max == r) {
      h = (g > b) ? static_cast<int>(scaler * (g - b) / delta) :
          static_cast<int>(scaler * ((g - b) / delta + 6));
    } else if (c_max == g) {
      h = static_cast<int>(scaler * ((b - r) / delta + 2));
    } else {  // Cmax == b
      h = static_cast<int>(scaler * ((r - g) / delta + 4));
    }
    s = (delta == 0.0f) ? 0 : static_cast<unsigned char>(delta / c_max * 255);
    v = c_max;

    color_out.channel[kRed] = h;
    color_out.channel[kGreen] = s;
    color_out.channel[kBlue] = v;
    color_out.channel[kAlpha] = a;
  }
}

void JNI_COLORSPACE_METHOD(nativeRgba8888ToYcbcra8888)(
    JNIEnv* env, jclass clazz, jobject input, jobject output, jint width, jint height) {
  Rgba* pInput = static_cast<Rgba*>(env->GetDirectBufferAddress(input));
  Rgba* pOutput = static_cast<Rgba*>(env->GetDirectBufferAddress(output));

  int r, g, b;
  for (int i = 0; i < width * height; ++i) {
    Rgba color_in = *pInput++;
    Rgba& color_out = *pOutput++;
    r = color_in.channel[kRed];
    g = color_in.channel[kGreen];
    b = color_in.channel[kBlue];

    color_out.channel[kRed] =
        static_cast<unsigned char>((65.738 * r + 129.057 * g + 25.064 * b) / 256 + 16);
    color_out.channel[kGreen] =
        static_cast<unsigned char>((-37.945 * r - 74.494 * g + 112.439 * b) / 256 + 128);
    color_out.channel[kBlue] =
        static_cast<unsigned char>((112.439 * r - 94.154 * g - 18.285 * b) / 256 + 128);
    color_out.channel[kAlpha] = color_in.channel[kAlpha];
  }
}
