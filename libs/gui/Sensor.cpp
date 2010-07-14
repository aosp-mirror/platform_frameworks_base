/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/String8.h>
#include <utils/Flattenable.h>

#include <hardware/sensors.h>

#include <gui/Sensor.h>

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

Sensor::Sensor()
    : mHandle(0), mType(0),
      mMinValue(0), mMaxValue(0), mResolution(0),
      mPower(0)
{
}

Sensor::~Sensor()
{
}

const String8& Sensor::getName() const {
    return mName;
}

const String8& Sensor::getVendor() const {
    return mVendor;
}

int32_t Sensor::getHandle() const {
    return mHandle;
}

int32_t Sensor::getType() const {
    return mType;
}

float Sensor::getMinValue() const {
    return mMinValue;
}

float Sensor::getMaxValue() const {
    return mMaxValue;
}

float Sensor::getResolution() const {
    return mResolution;
}

float Sensor::getPowerUsage() const {
    return mPower;
}

size_t Sensor::getFlattenedSize() const
{
    return  sizeof(int32_t) + ((mName.length() + 3) & ~3) +
            sizeof(int32_t) + ((mVendor.length() + 3) & ~3) +
            sizeof(int32_t) * 2 +
            sizeof(float) * 3;
}

size_t Sensor::getFdCount() const
{
    return 0;
}

static inline
size_t write(void* buffer, size_t offset, const String8& value) {
    memcpy(static_cast<char*>(buffer) + offset, value.string(), value.length());
    return (value.length() + 3) & ~3;
}

static inline
size_t write(void* buffer, size_t offset, float value) {
    *reinterpret_cast<float*>(static_cast<char*>(buffer) + offset) = value;
    return sizeof(float);
}

static inline
size_t write(void* buffer, size_t offset, int32_t value) {
    *reinterpret_cast<int32_t*>(static_cast<char*>(buffer) + offset) = value;
    return sizeof(int32_t);
}

status_t Sensor::flatten(void* buffer, size_t size,
        int fds[], size_t count) const
{
    if (size < Sensor::getFlattenedSize())
        return -ENOMEM;

    size_t offset = 0;
    offset += write(buffer, offset, int32_t(mName.length()));
    offset += write(buffer, offset, mName);
    offset += write(buffer, offset, int32_t(mVendor.length()));
    offset += write(buffer, offset, mVendor);
    offset += write(buffer, offset, mHandle);
    offset += write(buffer, offset, mType);
    offset += write(buffer, offset, mMinValue);
    offset += write(buffer, offset, mMaxValue);
    offset += write(buffer, offset, mResolution);
    offset += write(buffer, offset, mPower);

    return NO_ERROR;
}

static inline
size_t read(void const* buffer, size_t offset, String8* value, int32_t len) {
    value->setTo(static_cast<char const*>(buffer) + offset, len);
    return (len + 3) & ~3;
}

static inline
size_t read(void const* buffer, size_t offset, float* value) {
    *value = *reinterpret_cast<float const*>(static_cast<char const*>(buffer) + offset);
    return sizeof(float);
}

static inline
size_t read(void const* buffer, size_t offset, int32_t* value) {
    *value = *reinterpret_cast<int32_t const*>(static_cast<char const*>(buffer) + offset);
    return sizeof(int32_t);
}

status_t Sensor::unflatten(void const* buffer, size_t size,
        int fds[], size_t count)
{
    int32_t len;
    size_t offset = 0;
    offset += read(buffer, offset, &len);
    offset += read(buffer, offset, &mName, len);
    offset += read(buffer, offset, &len);
    offset += read(buffer, offset, &mVendor, len);
    offset += read(buffer, offset, &mHandle);
    offset += read(buffer, offset, &mType);
    offset += read(buffer, offset, &mMinValue);
    offset += read(buffer, offset, &mMaxValue);
    offset += read(buffer, offset, &mResolution);
    offset += read(buffer, offset, &mPower);

    return NO_ERROR;
}

// ----------------------------------------------------------------------------
}; // namespace android
