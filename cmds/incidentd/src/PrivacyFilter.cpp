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
#define DEBUG false
#include "Log.h"

#include "incidentd_util.h"
#include "PrivacyFilter.h"
#include "proto_util.h"
#include "Section.h"

#include <android-base/file.h>
#include <android/util/protobuf.h>
#include <android/util/ProtoFileReader.h>
#include <log/log.h>

namespace android {
namespace os {
namespace incidentd {

// ================================================================================
/**
 * Write the field to buf based on the wire type, iterator will point to next field.
 * If skip is set to true, no data will be written to buf. Return number of bytes written.
 */
void write_field_or_skip(ProtoOutputStream* out, const sp<ProtoReader>& in,
        uint32_t fieldTag, bool skip) {
    uint8_t wireType = read_wire_type(fieldTag);
    size_t bytesToWrite = 0;
    uint64_t varint = 0;

    switch (wireType) {
        case WIRE_TYPE_VARINT:
            varint = in->readRawVarint();
            if (!skip) {
                out->writeRawVarint(fieldTag);
                out->writeRawVarint(varint);
            }
            return;
        case WIRE_TYPE_FIXED64:
            if (!skip) {
                out->writeRawVarint(fieldTag);
            }
            bytesToWrite = 8;
            break;
        case WIRE_TYPE_LENGTH_DELIMITED:
            bytesToWrite = in->readRawVarint();
            if (!skip) {
                out->writeLengthDelimitedHeader(read_field_id(fieldTag), bytesToWrite);
            }
            break;
        case WIRE_TYPE_FIXED32:
            if (!skip) {
                out->writeRawVarint(fieldTag);
            }
            bytesToWrite = 4;
            break;
    }
    if (skip) {
        in->move(bytesToWrite);
    } else {
        for (size_t i = 0; i < bytesToWrite; i++) {
            out->writeRawByte(in->next());
        }
    }
}

/**
 * Strip next field based on its private policy and request spec, then stores data in buf.
 * Return NO_ERROR if succeeds, otherwise BAD_VALUE is returned to indicate bad data in
 * FdBuffer.
 *
 * The iterator must point to the head of a protobuf formatted field for successful operation.
 * After exit with NO_ERROR, iterator points to the next protobuf field's head.
 *
 * depth is the depth of recursion, for debugging.
 */
status_t strip_field(ProtoOutputStream* out, const sp<ProtoReader>& in,
        const Privacy* parentPolicy, const PrivacySpec& spec, int depth) {
    if (!in->hasNext() || parentPolicy == NULL) {
        return BAD_VALUE;
    }
    uint32_t fieldTag = in->readRawVarint();
    uint32_t fieldId = read_field_id(fieldTag);
    const Privacy* policy = lookup(parentPolicy, fieldId);

    if (policy == NULL || policy->children == NULL) {
        bool skip = !spec.CheckPremission(policy, parentPolicy->policy);
        // iterator will point to head of next field
        size_t currentAt = in->bytesRead();
        write_field_or_skip(out, in, fieldTag, skip);
        return NO_ERROR;
    }
    // current field is message type and its sub-fields have extra privacy policies
    uint32_t msgSize = in->readRawVarint();
    size_t start = in->bytesRead();
    uint64_t token = out->start(encode_field_id(policy));
    while (in->bytesRead() - start != msgSize) {
        status_t err = strip_field(out, in, policy, spec, depth + 1);
        if (err != NO_ERROR) {
            ALOGW("Bad value when stripping id %d, wiretype %d, tag %#x, depth %d, size %d, "
                    "relative pos %zu, ", fieldId, read_wire_type(fieldTag), fieldTag, depth,
                    msgSize, in->bytesRead() - start);
            return err;
        }
    }
    out->end(token);
    return NO_ERROR;
}

// ================================================================================
class FieldStripper {
public:
    FieldStripper(const Privacy* restrictions, const sp<ProtoReader>& data,
            uint8_t bufferLevel);

    ~FieldStripper();

    /**
     * Take the data that we have, and filter it down so that no fields
     * are more sensitive than the given privacy policy.
     */
    status_t strip(uint8_t privacyPolicy);

    /**
     * At the current filter level, how many bytes of data there is.
     */
    ssize_t dataSize() const { return mSize; }

    /**
     * Write the data from the current filter level to the file descriptor.
     */
    status_t writeData(int fd);

private:
    /**
     * The global set of field --> required privacy level mapping.
     */
    const Privacy* mRestrictions;

    /**
     * The current buffer.
     */
    sp<ProtoReader> mData;

    /**
     * The current size of the buffer inside mData.
     */
    ssize_t mSize;

    /**
     * The current privacy policy that the data is filtered to, as an optimization
     * so we don't always re-filter data that has already been filtered.
     */
    uint8_t mCurrentLevel;

    sp<EncodedBuffer> mEncodedBuffer;
};

FieldStripper::FieldStripper(const Privacy* restrictions, const sp<ProtoReader>& data,
            uint8_t bufferLevel)
        :mRestrictions(restrictions),
         mData(data),
         mSize(data->size()),
         mCurrentLevel(bufferLevel),
         mEncodedBuffer(get_buffer_from_pool()) {
    if (mSize < 0) {
        ALOGW("FieldStripper constructed with a ProtoReader that doesn't support size."
                " Data will be missing.");
    }
}

FieldStripper::~FieldStripper() {
    return_buffer_to_pool(mEncodedBuffer);
}

status_t FieldStripper::strip(const uint8_t privacyPolicy) {
    // If the current strip level is less (fewer fields retained) than what's already in the
    // buffer, then we can skip it.
    if (mCurrentLevel < privacyPolicy) {
        PrivacySpec spec(privacyPolicy);
        mEncodedBuffer->clear();
        ProtoOutputStream proto(mEncodedBuffer);

        // Optimization when no strip happens.
        if (mRestrictions == NULL || spec.RequireAll()
                // Do not iterate through fields if primitive data
                || !mRestrictions->children /* != FieldDescriptor::TYPE_MESSAGE */) {
            if (spec.CheckPremission(mRestrictions)) {
                mSize = mData->size();
            }
            return NO_ERROR;
        }

        while (mData->hasNext()) {
            status_t err = strip_field(&proto, mData, mRestrictions, spec, 0);
            if (err != NO_ERROR) {
                return err; // Error logged in strip_field.
            }
        }

        if (mData->bytesRead() != mData->size()) {
            ALOGW("Buffer corrupted: expect %zu bytes, read %zu bytes", mData->size(),
                    mData->bytesRead());
            return BAD_VALUE;
        }

        mData = proto.data();
        mSize = proto.size();
        mCurrentLevel = privacyPolicy;
    }
    return NO_ERROR;
}

status_t FieldStripper::writeData(int fd) {
    status_t err = NO_ERROR;
    sp<ProtoReader> reader = mData;
    if (mData == nullptr) {
        // There had been an error processing the data. We won't write anything,
        // but we also won't return an error, because errors are fatal.
        return NO_ERROR;
    }
    while (reader->readBuffer() != NULL) {
        err = WriteFully(fd, reader->readBuffer(), reader->currentToRead()) ? NO_ERROR : -errno;
        reader->move(reader->currentToRead());
        if (err != NO_ERROR) return err;
    }
    return NO_ERROR;
}


// ================================================================================
FilterFd::FilterFd(uint8_t privacyPolicy, int fd)
        :mPrivacyPolicy(privacyPolicy),
         mFd(fd) {
}

FilterFd::~FilterFd() {
}

// ================================================================================
PrivacyFilter::PrivacyFilter(int sectionId, const Privacy* restrictions)
        :mSectionId(sectionId),
         mRestrictions(restrictions),
         mOutputs() {
}

PrivacyFilter::~PrivacyFilter() {
}

void PrivacyFilter::addFd(const sp<FilterFd>& output) {
    mOutputs.push_back(output);
}

status_t PrivacyFilter::writeData(const FdBuffer& buffer, uint8_t bufferLevel,
        size_t* maxSize) {
    status_t err;

    if (maxSize != NULL) {
        *maxSize = 0;
    }

    // Order the writes by privacy filter, with increasing levels of filtration,k
    // so we can do the filter once, and then write many times.
    sort(mOutputs.begin(), mOutputs.end(),
        [](const sp<FilterFd>& a, const sp<FilterFd>& b) -> bool {
            return a->getPrivacyPolicy() < b->getPrivacyPolicy();
        });

    uint8_t privacyPolicy = PRIVACY_POLICY_LOCAL; // a.k.a. no filtering
    FieldStripper fieldStripper(mRestrictions, buffer.data()->read(), bufferLevel);
    for (const sp<FilterFd>& output: mOutputs) {
        // Do another level of filtering if necessary
        if (privacyPolicy != output->getPrivacyPolicy()) {
            privacyPolicy = output->getPrivacyPolicy();
            err = fieldStripper.strip(privacyPolicy);
            if (err != NO_ERROR) {
                // We can't successfully strip this data.  We will skip
                // the rest of this section.
                return NO_ERROR;
            }
        }

        // Write the resultant buffer to the fd, along with the header.
        ssize_t dataSize = fieldStripper.dataSize();
        if (dataSize > 0) {
            err = write_section_header(output->getFd(), mSectionId, dataSize);
            if (err != NO_ERROR) {
                output->onWriteError(err);
                continue;
            }

            err = fieldStripper.writeData(output->getFd());
            if (err != NO_ERROR) {
                output->onWriteError(err);
                continue;
            }
        }

        if (maxSize != NULL) {
            if (dataSize > *maxSize) {
                *maxSize = dataSize;
            }
        }
    }

    return NO_ERROR;
}

// ================================================================================
class ReadbackFilterFd : public FilterFd {
public:
    ReadbackFilterFd(uint8_t privacyPolicy, int fd);

    virtual void onWriteError(status_t err);
    status_t getError() { return mError; }

private:
    status_t mError;
};

ReadbackFilterFd::ReadbackFilterFd(uint8_t privacyPolicy, int fd)
        :FilterFd(privacyPolicy, fd),
         mError(NO_ERROR) {
}

void ReadbackFilterFd::onWriteError(status_t err) {
    mError = err;
}

// ================================================================================
status_t filter_and_write_report(int to, int from, uint8_t bufferLevel,
        const IncidentReportArgs& args) {
    status_t err;
    sp<ProtoFileReader> reader = new ProtoFileReader(from);

    while (reader->hasNext()) {
        uint64_t fieldTag = reader->readRawVarint();
        uint32_t fieldId = read_field_id(fieldTag);
        uint8_t wireType = read_wire_type(fieldTag);
        if (wireType == WIRE_TYPE_LENGTH_DELIMITED
                && args.containsSection(fieldId, section_requires_specific_mention(fieldId))) {
            // We need this field, but we need to strip it to the level provided in args.
            PrivacyFilter filter(fieldId, get_privacy_of_section(fieldId));
            filter.addFd(new ReadbackFilterFd(args.getPrivacyPolicy(), to));

            // Read this section from the reader into an FdBuffer
            size_t sectionSize = reader->readRawVarint();
            FdBuffer sectionData;
            err = sectionData.write(reader, sectionSize);
            if (err != NO_ERROR) {
                ALOGW("filter_and_write_report FdBuffer.write failed (this shouldn't happen): %s",
                        strerror(-err));
                return err;
            }

            // Do the filter and write.
            err = filter.writeData(sectionData, bufferLevel, nullptr);
            if (err != NO_ERROR) {
                ALOGW("filter_and_write_report filter.writeData had an error: %s", strerror(-err));
                return err;
            }
        } else {
            // We don't need this field.  Incident does not have any direct children
            // other than sections.  So just skip them.
            write_field_or_skip(NULL, reader, fieldTag, true);
        }
    }
    clear_buffer_pool();
    err = reader->getError();
    if (err != NO_ERROR) {
        ALOGW("filter_and_write_report reader had an error: %s", strerror(-err));
        return err;
    }

    return NO_ERROR;
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
