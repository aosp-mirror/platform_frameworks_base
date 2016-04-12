/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "PathParser.h"

#include "jni.h"

#include <errno.h>
#include <utils/Log.h>
#include <sstream>
#include <stdlib.h>
#include <string>
#include <vector>

namespace android {
namespace uirenderer {

static size_t nextStart(const char* s, size_t length, size_t startIndex) {
    size_t index = startIndex;
    while (index < length) {
        char c = s[index];
        // Note that 'e' or 'E' are not valid path commands, but could be
        // used for floating point numbers' scientific notation.
        // Therefore, when searching for next command, we should ignore 'e'
        // and 'E'.
        if ((((c - 'A') * (c - 'Z') <= 0) || ((c - 'a') * (c - 'z') <= 0))
                && c != 'e' && c != 'E') {
            return index;
        }
        index++;
    }
    return index;
}

/**
 * Calculate the position of the next comma or space or negative sign
 * @param s the string to search
 * @param start the position to start searching
 * @param result the result of the extraction, including the position of the
 * the starting position of next number, whether it is ending with a '-'.
 */
static void extract(int* outEndPosition, bool* outEndWithNegOrDot, const char* s, int start, int end) {
    // Now looking for ' ', ',', '.' or '-' from the start.
    int currentIndex = start;
    bool foundSeparator = false;
    *outEndWithNegOrDot = false;
    bool secondDot = false;
    bool isExponential = false;
    for (; currentIndex < end; currentIndex++) {
        bool isPrevExponential = isExponential;
        isExponential = false;
        char currentChar = s[currentIndex];
        switch (currentChar) {
        case ' ':
        case ',':
            foundSeparator = true;
            break;
        case '-':
            // The negative sign following a 'e' or 'E' is not a separator.
            if (currentIndex != start && !isPrevExponential) {
                foundSeparator = true;
                *outEndWithNegOrDot = true;
            }
            break;
        case '.':
            if (!secondDot) {
                secondDot = true;
            } else {
                // This is the second dot, and it is considered as a separator.
                foundSeparator = true;
                *outEndWithNegOrDot = true;
            }
            break;
        case 'e':
        case 'E':
            isExponential = true;
            break;
        }
        if (foundSeparator) {
            break;
        }
    }
    // In the case where nothing is found, we put the end position to the end of
    // our extract range. Otherwise, end position will be where separator is found.
    *outEndPosition = currentIndex;
}

static float parseFloat(PathParser::ParseResult* result, const char* startPtr, size_t expectedLength) {
    char* endPtr = NULL;
    float currentValue = strtof(startPtr, &endPtr);
    if ((currentValue == HUGE_VALF || currentValue == -HUGE_VALF) && errno == ERANGE) {
        result->failureOccurred = true;
        result->failureMessage = "Float out of range:  ";
        result->failureMessage.append(startPtr, expectedLength);
    }
    if (currentValue == 0 && endPtr == startPtr) {
        // No conversion is done.
        result->failureOccurred = true;
        result->failureMessage = "Float format error when parsing: ";
        result->failureMessage.append(startPtr, expectedLength);
    }
    return currentValue;
}

/**
 * Parse the floats in the string.
 *
 * @param s the string containing a command and list of floats
 * @return true on success
 */
static void getFloats(std::vector<float>* outPoints, PathParser::ParseResult* result,
        const char* pathStr, int start, int end) {

    if (pathStr[start] == 'z' || pathStr[start] == 'Z') {
        return;
    }
    int startPosition = start + 1;
    int endPosition = start;

    // The startPosition should always be the first character of the
    // current number, and endPosition is the character after the current
    // number.
    while (startPosition < end) {
        bool endWithNegOrDot;
        extract(&endPosition, &endWithNegOrDot, pathStr, startPosition, end);

        if (startPosition < endPosition) {
            float currentValue = parseFloat(result, &pathStr[startPosition],
                    end - startPosition);
            if (result->failureOccurred) {
                return;
            }
            outPoints->push_back(currentValue);
        }

        if (endWithNegOrDot) {
            // Keep the '-' or '.' sign with next number.
            startPosition = endPosition;
        } else {
            startPosition = endPosition + 1;
        }
    }
    return;
}

bool PathParser::isVerbValid(char verb) {
    verb = tolower(verb);
    return verb == 'a' || verb == 'c' || verb == 'h' || verb == 'l' || verb == 'm' || verb == 'q'
            || verb == 's' || verb == 't' || verb == 'v' || verb == 'z';
}

void PathParser::getPathDataFromAsciiString(PathData* data, ParseResult* result,
        const char* pathStr, size_t strLen) {
    if (pathStr == NULL) {
        result->failureOccurred = true;
        result->failureMessage = "Path string cannot be NULL.";
        return;
    }

    size_t start = 0;
    // Skip leading spaces.
    while (isspace(pathStr[start]) && start < strLen) {
        start++;
    }
    if (start == strLen) {
        result->failureOccurred = true;
        result->failureMessage = "Path string cannot be empty.";
        return;
    }
    size_t end = start + 1;

    while (end < strLen) {
        end = nextStart(pathStr, strLen, end);
        std::vector<float> points;
        getFloats(&points, result, pathStr, start, end);
        if (!isVerbValid(pathStr[start])) {
            result->failureOccurred = true;
            result->failureMessage = "Invalid pathData. Failure occurred at position "
                    + std::to_string(start) + " of path: " + pathStr;
        }
        // If either verb or points is not valid, return immediately.
        if (result->failureOccurred) {
            return;
        }
        data->verbs.push_back(pathStr[start]);
        data->verbSizes.push_back(points.size());
        data->points.insert(data->points.end(), points.begin(), points.end());
        start = end;
        end++;
    }

    if ((end - start) == 1 && start < strLen) {
        if (!isVerbValid(pathStr[start])) {
            result->failureOccurred = true;
            result->failureMessage = "Invalid pathData. Failure occurred at position "
                    + std::to_string(start) + " of path: " + pathStr;
            return;
        }
        data->verbs.push_back(pathStr[start]);
        data->verbSizes.push_back(0);
    }
}

void PathParser::dump(const PathData& data) {
    // Print out the path data.
    size_t start = 0;
    for (size_t i = 0; i < data.verbs.size(); i++) {
        std::ostringstream os;
        os << data.verbs[i];
        os << ", verb size: " << data.verbSizes[i];
        for (size_t j = 0; j < data.verbSizes[i]; j++) {
            os << " " << data.points[start + j];
        }
        start += data.verbSizes[i];
        ALOGD("%s", os.str().c_str());
    }

    std::ostringstream os;
    for (size_t i = 0; i < data.points.size(); i++) {
        os << data.points[i] << ", ";
    }
    ALOGD("points are : %s", os.str().c_str());
}

void PathParser::parseAsciiStringForSkPath(SkPath* skPath, ParseResult* result, const char* pathStr, size_t strLen) {
    PathData pathData;
    getPathDataFromAsciiString(&pathData, result, pathStr, strLen);
    if (result->failureOccurred) {
        return;
    }
    // Check if there is valid data coming out of parsing the string.
    if (pathData.verbs.size() == 0) {
        result->failureOccurred = true;
        result->failureMessage = "No verbs found in the string for pathData: ";
        result->failureMessage += pathStr;
        return;
    }
    VectorDrawableUtils::verbsToPath(skPath, pathData);
    return;
}

}; // namespace uirenderer
}; //namespace android
