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

#define  LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MCA", __VA_ARGS__)
#define  LOGW(...) __android_log_print(ANDROID_LOG_WARN, "MCA", __VA_ARGS__)
#define  LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MCA", __VA_ARGS__)

typedef struct {
  float contrast;
} ContrastParameters;

void contrast_init(void** user_data) {
  (*user_data) = malloc(sizeof(ContrastParameters));
}

void contrast_teardown(void* user_data) {
  free(user_data);
}

void contrast_setvalue(const char* key, const char* value, void* user_data) {
  if (strcmp(key, "contrast") == 0)
    ((ContrastParameters*)user_data)->contrast = atof(value);
  else
    LOGE("Unknown parameter: %s!", key);
}

int contrast_process(const char** inputs,
                     const int* input_sizes,
                     int input_count,
                     char* output,
                     int output_size,
                     void* user_data) {
  // Make sure we have exactly one input
  if (input_count != 1) {
    LOGE("Contrast: Incorrect input count! Expected 1 but got %d!", input_count);
    return 0;
  }

  // Make sure sizes match up
  if (input_sizes[0] != output_size) {
    LOGE("Contrast: Input-output sizes do not match up. %d vs. %d!", input_sizes[0], output_size);
    return 0;
  }

  // Get the input and output pointers
  const char* input_ptr = inputs[0];
  char* output_ptr = output;
  if (!input_ptr || !output_ptr) {
    LOGE("Contrast: No input or output pointer found!");
    return 0;
  }

  // Get the parameters
  ContrastParameters* params = (ContrastParameters*)user_data;
  const float contrast = params->contrast;

  // Run the contrast adjustment
  int i;
  for (i = 0; i < output_size; ++i) {
    float px = *(input_ptr++) / 255.0;
    px -= 0.5;
    px *= contrast;
    px += 0.5;
    *(output_ptr++) = (char)(px > 1.0 ? 255.0 : (px < 0.0 ? 0.0 : px * 255.0));
  }

  return 1;
}

