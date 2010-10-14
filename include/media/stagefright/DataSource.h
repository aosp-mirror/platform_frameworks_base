/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef DATA_SOURCE_H_

#define DATA_SOURCE_H_

#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/List.h>
#include <utils/RefBase.h>
#include <utils/threads.h>

namespace android {

struct AMessage;
class String8;

class DataSource : public RefBase {
public:
    enum Flags {
        kWantsPrefetching      = 1,
        kStreamedFromLocalHost = 2,
        kIsCachingDataSource   = 4,
    };

    static sp<DataSource> CreateFromURI(
            const char *uri,
            const KeyedVector<String8, String8> *headers = NULL);

    DataSource() {}

    virtual status_t initCheck() const = 0;

    virtual ssize_t readAt(off_t offset, void *data, size_t size) = 0;

    // Convenience methods:
    bool getUInt16(off_t offset, uint16_t *x);

    // May return ERROR_UNSUPPORTED.
    virtual status_t getSize(off_t *size);

    virtual uint32_t flags() {
        return 0;
    }

    ////////////////////////////////////////////////////////////////////////////

    bool sniff(String8 *mimeType, float *confidence, sp<AMessage> *meta);

    // The sniffer can optionally fill in "meta" with an AMessage containing
    // a dictionary of values that helps the corresponding extractor initialize
    // its state without duplicating effort already exerted by the sniffer.
    typedef bool (*SnifferFunc)(
            const sp<DataSource> &source, String8 *mimeType,
            float *confidence, sp<AMessage> *meta);

    static void RegisterSniffer(SnifferFunc func);
    static void RegisterDefaultSniffers();

protected:
    virtual ~DataSource() {}

private:
    static Mutex gSnifferMutex;
    static List<SnifferFunc> gSniffers;

    DataSource(const DataSource &);
    DataSource &operator=(const DataSource &);
};

}  // namespace android

#endif  // DATA_SOURCE_H_
