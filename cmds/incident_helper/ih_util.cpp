/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "incident_helper"

#include "ih_util.h"

#include <algorithm>
#include <sstream>
#include <unistd.h>

const ssize_t BUFFER_SIZE = 16 * 1024; // 4KB


static std::string trim(const std::string& s) {
    const auto head = s.find_first_not_of(DEFAULT_WHITESPACE);
    if (head == std::string::npos) return "";

    const auto tail = s.find_last_not_of(DEFAULT_WHITESPACE);
    return s.substr(head, tail - head + 1);
}

static std::string trimHeader(const std::string& s) {
    std::string res = trim(s);
    std::transform(res.begin(), res.end(), res.begin(), ::tolower);
    return res;
}

// This is similiar to Split in android-base/file.h, but it won't add empty string
static void split(const std::string& line, std::vector<std::string>& words,
        const trans_func& func, const std::string& delimiters) {
    words.clear();  // clear the buffer before split

    size_t base = 0;
    size_t found;
    while (true) {
        found = line.find_first_of(delimiters, base);
        if (found != base) {
            std::string word = (*func) (line.substr(base, found - base));
            if (!word.empty()) {
                words.push_back(word);
            }
        }
        if (found == line.npos) break;
        base = found + 1;
    }
}

header_t parseHeader(const std::string& line, const std::string& delimiters) {
    header_t header;
    trans_func f = &trimHeader;
    split(line, header, f, delimiters);
    return header;
}

record_t parseRecord(const std::string& line, const std::string& delimiters) {
    record_t record;
    trans_func f = &trim;
    split(line, record, f, delimiters);
    return record;
}

bool hasPrefix(std::string* line, const char* key) {
    const auto head = line->find_first_not_of(DEFAULT_WHITESPACE);
    if (head == std::string::npos) return false;
    auto i = 0;
    auto j = head;
    while (key[i] != '\0') {
        if (j >= line->size() || key[i++] != line->at(j++)) {
            return false;
        }
    }
    line->assign(trim(line->substr(j)));
    return true;
}

Reader::Reader(const int fd) : Reader(fd, BUFFER_SIZE) {};

Reader::Reader(const int fd, const size_t capacity)
        : mFd(fd), mMaxSize(capacity), mBufSize(0), mRead(0), mFlushed(0)
{
    mBuf = capacity > 0 ? (char*)malloc(capacity * sizeof(char)) : NULL;
    mStatus = mFd < 0 ? "Negative fd" : (capacity == 0 ? "Zero buffer capacity" : "");
}

Reader::~Reader()
{
    free(mBuf);
}

bool Reader::readLine(std::string* line, const char newline) {
    if (!ok(line)) return false; // bad status
    line->clear();
    std::stringstream ss;
    while (!EOR()) {
        // read if available
        if (mFd != -1 && mBufSize != mMaxSize) {
            ssize_t amt = 0;
            if (mRead >= mFlushed) {
                amt = ::read(mFd, mBuf + mRead, mMaxSize - mRead);
            } else {
                amt = ::read(mFd, mBuf + mRead, mFlushed - mRead);
            }
            if (amt < 0) {
                mStatus = "Fail to read from fd";
                return false;
            } else if (amt == 0) {
                close(mFd);
                mFd = -1;
            }
            mRead += amt;
            mBufSize += amt;
        }

        bool meetsNewLine = false;
        if (mBufSize > 0) {
            int start = mFlushed;
            int end = mFlushed < mRead ? mRead : mMaxSize;
            while (mFlushed < end && mBuf[mFlushed++] != newline && mBufSize > 0) mBufSize--;
            meetsNewLine = (mBuf[mFlushed-1] == newline);
            if (meetsNewLine) mBufSize--; // deduct the new line character
            size_t len = meetsNewLine ? mFlushed - start - 1 : mFlushed - start;
            ss.write(mBuf + start, len);
        }

        if (mRead >= (int) mMaxSize) mRead = 0;
        if (mFlushed >= (int) mMaxSize) mFlushed = 0;

        if (EOR() || meetsNewLine) {
            line->assign(ss.str());
            return true;
        }
    }
    return false;
}

bool Reader::ok(std::string* error) {
    error->assign(mStatus);
    return mStatus.empty();
}
