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

#include <stdlib.h>

#define ATTRIBUTE_UNUSED __attribute__((unused))

int gray_to_rgb_process(const char** inputs,
                        const int* input_sizes,
                        int input_count,
                        char* output,
                        int output_size,
                        void* user_data ATTRIBUTE_UNUSED) {
  // Make sure we have exactly one input
  if (input_count != 1)
    return 0;

  // Make sure sizes match up
  if (input_sizes[0] != output_size/3)
    return 0;

  // Get the input and output pointers
  const char* input_ptr = inputs[0];
  char* output_ptr = output;
  if (!input_ptr || !output_ptr)
    return 0;

  // Run the conversion
  int i;
  for (i = 0; i < input_sizes[0]; ++i) {
    *(output_ptr++) = *(input_ptr);
    *(output_ptr++) = *(input_ptr);
    *(output_ptr++) = *(input_ptr++);
  }

  return 1;
}

int rgba_to_rgb_process(const char** inputs,
                        const int* input_sizes,
                        int input_count,
                        char* output,
                        int output_size,
                        void* user_data ATTRIBUTE_UNUSED) {
  // Make sure we have exactly one input
  if (input_count != 1)
    return 0;

  // Make sure sizes match up
  if (input_sizes[0]/4 != output_size/3)
    return 0;

  // Get the input and output pointers
  const char* input_ptr = inputs[0];
  char* output_ptr = output;
  if (!input_ptr || !output_ptr)
    return 0;

  // Run the conversion
  int i;
  for (i = 0; i < input_sizes[0] / 4; ++i) {
    *(output_ptr++) = *(input_ptr++);
    *(output_ptr++) = *(input_ptr++);
    *(output_ptr++) = *(input_ptr++);
    ++input_ptr;
  }

  return 1;
}

int gray_to_rgba_process(const char** inputs,
                         const int* input_sizes,
                         int input_count,
                         char* output,
                         int output_size,
                         void* user_data ATTRIBUTE_UNUSED) {
  // Make sure we have exactly one input
  if (input_count != 1)
    return 0;

  // Make sure sizes match up
  if (input_sizes[0] != output_size/4)
    return 0;

  // Get the input and output pointers
  const char* input_ptr = inputs[0];
  char* output_ptr = output;
  if (!input_ptr || !output_ptr)
    return 0;

  // Run the conversion
  int i;
  for (i = 0; i < input_sizes[0]; ++i) {
    *(output_ptr++) = *(input_ptr);
    *(output_ptr++) = *(input_ptr);
    *(output_ptr++) = *(input_ptr++);
    *(output_ptr++) = 255;
  }

  return 1;
}

int rgb_to_rgba_process(const char** inputs,
                        const int* input_sizes,
                        int input_count,
                        char* output,
                        int output_size,
                        void* user_data ATTRIBUTE_UNUSED) {
  // Make sure we have exactly one input
  if (input_count != 1)
    return 0;

  // Make sure sizes match up
  if (input_sizes[0]/3 != output_size/4)
    return 0;

  // Get the input and output pointers
  const char* input_ptr = inputs[0];
  char* output_ptr = output;
  if (!input_ptr || !output_ptr)
    return 0;

  // Run the conversion
  int i;
  for (i = 0; i < output_size / 4; ++i) {
    *(output_ptr++) = *(input_ptr++);
    *(output_ptr++) = *(input_ptr++);
    *(output_ptr++) = *(input_ptr++);
    *(output_ptr++) = 255;
  }

  return 1;
}

