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

#ifndef __READ_WRITE_UTILS_H__
#define __READ_WRITE_UTILS_H__

#include <utils/FileMap.h>
#include <drm/drm_framework_common.h>

namespace android {

/**
 * This is an utility class which performs IO operations.
 *
 */
class ReadWriteUtils {
public:
    /**
     * Constructor for ReadWriteUtils
     */
    ReadWriteUtils() {}

    /**
     * Destructor for ReadWriteUtils
     */
    virtual ~ReadWriteUtils();

public:
    /**
     * Reads the data from the file path provided
     *
     * @param[in] filePath Path of the file
     * @return Data read from the file
     */
    static String8 readBytes(const String8& filePath);
    /**
     * Reads the data into the given buffer from the file path provided
     *
     * @param[in] filePath Path of the file
     * @param[out] buffer Data read from the file
     * @return Length of the data read from the file
     */
    static int readBytes(const String8& filePath, char** buffer);
    /**
     * Writes the data into the file path provided
     *
     * @param[in] filePath Path of the file
     * @param[in] dataBuffer Data to write
     */
    static void writeToFile(const String8& filePath, const String8& data);
    /**
     * Appends the data into the file path provided
     *
     * @param[in] filePath Path of the file
     * @param[in] dataBuffer Data to append
     */
    static void appendToFile(const String8& filePath, const String8& data);

private:
    FileMap* mFileMap;
};

};

#endif /* __READ_WRITE_UTILS_H__ */

