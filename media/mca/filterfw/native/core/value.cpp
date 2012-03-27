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

#include <stddef.h>
#include <stdlib.h>

#include "value.h"

#define NULL_VALUE_TYPE           0
#define INT_VALUE_TYPE            1
#define FLOAT_VALUE_TYPE          2
#define STRING_VALUE_TYPE         3
#define BUFFER_VALUE_TYPE         4
#define MUTABLE_BUFFER_VALUE_TYPE 5
#define INT_ARRAY_VALUE_TYPE      6
#define FLOAT_ARRAY_VALUE_TYPE    7

// Templated versions //////////////////////////////////////////////////////////////////////////////
template<typename POD, int TYPEID>
POD GetPODValue(Value value) {
  return value.type == TYPEID ? *reinterpret_cast<POD*>(value.value) : POD();
}

template<typename PTR, int TYPEID>
PTR GetPtrValue(Value value) {
  return value.type == TYPEID ? reinterpret_cast<PTR>(value.value) : NULL;
}

template<typename POD, int TYPEID>
Value MakePODValue(POD value) {
  Value result;
  result.type = TYPEID;
  result.value = malloc(sizeof(POD));
  result.count = 1;
  *reinterpret_cast<POD*>(result.value) = value;
  return result;
}

template<typename BASE, int TYPEID>
Value MakePtrValue(const BASE* values, int count) {
  Value result;
  result.type = TYPEID;
  result.value = malloc(sizeof(BASE) * count);
  memcpy(result.value, values, sizeof(BASE) * count);
  result.count = count;
  return result;
}

template<typename POD, int TYPEID>
int SetPODValue(Value* value, POD new_value) {
  if (value->type == NULL_VALUE_TYPE) {
    value->type = TYPEID;
    value->value = malloc(sizeof(POD));
    value->count = 1;
  }
  if (value->type == TYPEID) {
    *reinterpret_cast<POD*>(value->value) = new_value;
    return 1;
  }
  return 0;
}

template<typename BASE, int TYPEID>
int SetPtrValue(Value* value, const BASE* new_values, int count) {
  if (value->type == NULL_VALUE_TYPE) {
    value->type = TYPEID;
    value->value = malloc(sizeof(BASE) * count);
    value->count = count;
  }
  if (value->type == TYPEID && value->count == count) {
    memcpy(value->value, new_values, sizeof(BASE) * count);
    return 1;
  }
  return 0;
}

// C Wrappers //////////////////////////////////////////////////////////////////////////////////////
int GetIntValue(Value value) {
  return GetPODValue<int, INT_VALUE_TYPE>(value);
}

float GetFloatValue(Value value) {
  return GetPODValue<float, FLOAT_VALUE_TYPE>(value);
}

const char* GetStringValue(Value value) {
  return GetPtrValue<const char*, STRING_VALUE_TYPE>(value);
}

const char* GetBufferValue(Value value) {
  return (value.type == BUFFER_VALUE_TYPE || value.type == MUTABLE_BUFFER_VALUE_TYPE)
    ? (const char*)value.value
    : NULL;
}

char* GetMutableBufferValue(Value value) {
  return GetPtrValue<char*, MUTABLE_BUFFER_VALUE_TYPE>(value);
}

int* GetIntArrayValue(Value value) {
  return GetPtrValue<int*, INT_ARRAY_VALUE_TYPE>(value);
}

float* GetFloatArrayValue(Value value) {
  return GetPtrValue<float*, FLOAT_ARRAY_VALUE_TYPE>(value);
}

int ValueIsNull(Value value) {
  return value.type == NULL_VALUE_TYPE;
}

int ValueIsInt(Value value) {
  return value.type == INT_VALUE_TYPE;
}

int ValueIsFloat(Value value) {
  return value.type == FLOAT_VALUE_TYPE;
}

int ValueIsString(Value value) {
  return value.type == STRING_VALUE_TYPE;
}

int ValueIsBuffer(Value value) {
  return value.type == BUFFER_VALUE_TYPE || value.type == MUTABLE_BUFFER_VALUE_TYPE;
}

int ValueIsIntArray(Value value) {
  return value.type == INT_ARRAY_VALUE_TYPE;
}

int ValueIsFloatArray(Value value) {
  return value.type == FLOAT_ARRAY_VALUE_TYPE;
}

Value MakeNullValue() {
  Value result;
  result.type = NULL_VALUE_TYPE;
  result.value = NULL;
  result.count = 0;
  return result;
}

Value MakeIntValue(int value) {
  return MakePODValue<int, INT_VALUE_TYPE>(value);
}

Value MakeFloatValue(float value) {
  return MakePODValue<float, FLOAT_VALUE_TYPE>(value);
}

Value MakeStringValue(const char* value) {
  return MakePtrValue<char, STRING_VALUE_TYPE>(value, strlen(value) + 1);
}

Value MakeBufferValue(const char* buffer, int size) {
  return MakePtrValue<char, BUFFER_VALUE_TYPE>(buffer, size);
}

Value MakeBufferValueNoCopy(const char* buffer, int size) {
  Value result;
  result.type = BUFFER_VALUE_TYPE;
  result.value = (void*)buffer;
  result.count = size;
  return result;
}

Value MakeMutableBufferValue(const char* buffer, int size) {
  return MakePtrValue<const char, MUTABLE_BUFFER_VALUE_TYPE>(buffer, size);
}

Value MakeMutableBufferValueNoCopy(char* buffer, int size) {
  Value result;
  result.type = MUTABLE_BUFFER_VALUE_TYPE;
  result.value = (void*)buffer;
  result.count = size;
  return result;
}

Value MakeIntArrayValue(const int* values, int count) {
  return MakePtrValue<int, INT_ARRAY_VALUE_TYPE>(values, count);
}

Value MakeFloatArrayValue(const float* values, int count) {
  return MakePtrValue<float, FLOAT_ARRAY_VALUE_TYPE>(values, count);
}

int SetIntValue(Value* value, int new_value) {
  return SetPODValue<int, INT_VALUE_TYPE>(value, new_value);
}

int SetFloatValue(Value* value, float new_value) {
  return SetPODValue<float, FLOAT_VALUE_TYPE>(value, new_value);
}

int SetStringValue(Value* value, const char* new_value) {
  return SetPtrValue<char, STRING_VALUE_TYPE>(value, new_value, strlen(new_value) + 1);
}

int SetMutableBufferValue(Value* value, const char* new_data, int size) {
  return SetPtrValue<char, MUTABLE_BUFFER_VALUE_TYPE>(value, new_data, size);
}

int SetIntArrayValue(Value* value, const int* new_values, int count) {
  return SetPtrValue<int, INT_ARRAY_VALUE_TYPE>(value, new_values, count);
}

int SetFloatArrayValue(Value* value, const float* new_values, int count) {
  return SetPtrValue<float, FLOAT_ARRAY_VALUE_TYPE>(value, new_values, count);
}

int GetValueCount(Value value) {
  return value.count;
}

void ReleaseValue(Value* value) {
  if (value && value->value) {
    free(value->value);
    value->value = NULL;
    value->type = NULL_VALUE_TYPE;
  }
}

