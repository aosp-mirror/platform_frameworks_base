/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <android/log.h>
#include <stdlib.h>
#include <string.h>

#define  LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MCA", __VA_ARGS__)
#define  LOGW(...) __android_log_print(ANDROID_LOG_WARN, "MCA", __VA_ARGS__)
#define  LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MCA", __VA_ARGS__)

typedef struct {
  float brightness;
} BrightnessParameters;

typedef union {
    int value;
    char rgba[4];
} Pixel;

void brightness_init(void** user_data) {
  (*user_data) = malloc(sizeof(BrightnessParameters));
}

void brightness_teardown(void* user_data) {
  free(user_data);
}

void brightness_setvalue(const char* key, const char* value, void* user_data) {
  if (strcmp(key, "brightness") == 0)
    ((BrightnessParameters*)user_data)->brightness = atof(value);
  else
    LOGE("Unknown parameter: %s!", key);
}

int brightness_process(const char** inputs,
                       const int* input_sizes,
                       int input_count,
                       char* output,
                       int output_size,
                       void* user_data) {
  // Make sure we have exactly one input
  if (input_count != 1) {
    LOGE("Brightness: Incorrect input count! Expected 1 but got %d!", input_count);
    return 0;
  }

  // Make sure sizes match up
  if (input_sizes[0] != output_size) {
    LOGE("Brightness: Input-output sizes do not match up. %d vs. %d!", input_sizes[0], output_size);
    return 0;
  }

  // Get the input and output pointers
  const int* input_ptr = (int*)inputs[0];
  int* output_ptr = (int*)output;
  const int* end_ptr = input_ptr + (output_size / 4);
  if (!input_ptr || !output_ptr) {
    LOGE("Brightness: No input or output pointer found!");
    return 0;
  }

  // Get the parameters
  BrightnessParameters* params = (BrightnessParameters*)user_data;
  const float brightness = params->brightness;

  // Run the brightness adjustment
  const int factor = (int)(brightness * 255.0f);
  Pixel pixel;
  while (input_ptr < end_ptr) {
    pixel.value = *(input_ptr++);

    const short r = (pixel.rgba[0] * factor) / 255;
    const short g = (pixel.rgba[1] * factor) / 255;
    const short b = (pixel.rgba[2] * factor) / 255;

    *(output_ptr++) = (r > 255 ? 255 : r)
                    | ((g > 255 ? 255 : g) << 8)
                    | ((b > 255 ? 255 : b) << 16)
                    | (pixel.rgba[3] << 24);
  }

  return 1;
}

