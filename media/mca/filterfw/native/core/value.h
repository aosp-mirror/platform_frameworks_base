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

#ifndef ANDROID_FILTERFW_CORE_VALUE_H
#define ANDROID_FILTERFW_CORE_VALUE_H

#ifdef __cplusplus
extern "C" {
#endif

// TODO: As this is no longer part of the proposed NDK, should we make this object-oriented (C++)
// instead? We can also probably clean this up a bit.

// TODO: Change this to an opaque handle?
typedef struct {
  void* value;
  int type;
  int count;
} Value;

// TODO: Probably should make these const Value*?
int GetIntValue(Value value);
float GetFloatValue(Value value);
const char* GetStringValue(Value value);
const char* GetBufferValue(Value value);
char* GetMutableBufferValue(Value value);
int* GetIntArrayValue(Value value);
float* GetFloatArrayValue(Value value);

// TODO: Probably should make these const Value*?
int ValueIsNull(Value value);
int ValueIsInt(Value value);
int ValueIsFloat(Value value);
int ValueIsString(Value value);
int ValueIsBuffer(Value value);
int ValueIsMutableBuffer(Value value);
int ValueIsIntArray(Value value);
int ValueIsFloatArray(Value value);

Value MakeNullValue();
Value MakeIntValue(int value);
Value MakeFloatValue(float value);
Value MakeStringValue(const char* value);
Value MakeBufferValue(const char* data, int size);
Value MakeBufferValueNoCopy(const char* data, int size);
Value MakeMutableBufferValue(const char* data, int size);
Value MakeMutableBufferValueNoCopy(char* data, int size);
Value MakeIntArrayValue(const int* values, int count);
Value MakeFloatArrayValue(const float* values, int count);

// Note: These only alloc if value is Null! Otherwise they overwrite, so data must fit!
int SetIntValue(Value* value, int new_value);
int SetFloatValue(Value* value, float new_value);
int SetStringValue(Value* value, const char* new_value);
int SetMutableBufferValue(Value* value, const char* new_data, int size);
int SetIntArrayValue(Value* value, const int* new_values, int count);
int SetFloatArrayValue(Value* value, const float* new_values, int count);

int GetValueCount(Value value);

void ReleaseValue(Value* value);

#ifdef __cplusplus
} // extern "C"
#endif

#endif  // ANDROID_FILTERFW_FILTER_VALUE_H
