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

#define ATTRIBUTE_UNUSED __attribute__((unused))

int invert_process(const char** inputs,
                   const int* input_sizes,
                   int input_count,
                   char* output,
                   int output_size,
                   void* user_data ATTRIBUTE_UNUSED) {
  // Make sure we have exactly one input
  if (input_count != 1)
    return 0;

  // Make sure sizes match up
  if (input_sizes[0] != output_size)
    return 0;

  // Get the input and output pointers
  const char* input_ptr = inputs[0];
  char* output_ptr = output;
  if (!input_ptr || !output_ptr)
    return 0;

  // Run the inversion
  int i;
  for (i = 0; i < output_size; ++i)
    *(output_ptr++) = 255 - *(input_ptr++);

  return 1;
}

