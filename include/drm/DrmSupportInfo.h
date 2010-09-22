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

#ifndef __DRM_SUPPORT_INFO_H__
#define __DRM_SUPPORT_INFO_H__

#include "drm_framework_common.h"

namespace android {

/**
 * This is an utility class which wraps the capability of each plug-in,
 * such as mimetype's and file suffixes it could handle.
 *
 * Plug-in developer could return the capability of the plugin by passing
 * DrmSupportInfo instance.
 *
 */
class DrmSupportInfo {
public:
    /**
     * Iterator for mMimeTypeVector
     */
    class MimeTypeIterator {
        friend class DrmSupportInfo;
    private:
        MimeTypeIterator(DrmSupportInfo* drmSupportInfo)
           : mDrmSupportInfo(drmSupportInfo), mIndex(0) {}
    public:
        MimeTypeIterator(const MimeTypeIterator& iterator);
        MimeTypeIterator& operator=(const MimeTypeIterator& iterator);
        virtual ~MimeTypeIterator() {}

    public:
        bool hasNext();
        String8& next();

    private:
        DrmSupportInfo* mDrmSupportInfo;
        unsigned int mIndex;
    };

    /**
     * Iterator for mFileSuffixVector
     */
    class FileSuffixIterator {
       friend class DrmSupportInfo;

    private:
        FileSuffixIterator(DrmSupportInfo* drmSupportInfo)
            : mDrmSupportInfo(drmSupportInfo), mIndex(0) {}
    public:
        FileSuffixIterator(const FileSuffixIterator& iterator);
        FileSuffixIterator& operator=(const FileSuffixIterator& iterator);
        virtual ~FileSuffixIterator() {}

    public:
        bool hasNext();
        String8& next();

    private:
        DrmSupportInfo* mDrmSupportInfo;
        unsigned int mIndex;
    };

public:
    /**
     * Constructor for DrmSupportInfo
     */
    DrmSupportInfo();

    /**
     * Copy constructor for DrmSupportInfo
     */
    DrmSupportInfo(const DrmSupportInfo& drmSupportInfo);

    /**
     * Destructor for DrmSupportInfo
     */
    virtual ~DrmSupportInfo() {}

    DrmSupportInfo& operator=(const DrmSupportInfo& drmSupportInfo);
    bool operator<(const DrmSupportInfo& drmSupportInfo) const;
    bool operator==(const DrmSupportInfo& drmSupportInfo) const;

    /**
     * Returns FileSuffixIterator object to walk through file suffix values
     * associated with this instance
     *
     * @return FileSuffixIterator object
     */
    FileSuffixIterator getFileSuffixIterator();

    /**
     * Returns MimeTypeIterator object to walk through mimetype values
     * associated with this instance
     *
     * @return MimeTypeIterator object
     */
    MimeTypeIterator getMimeTypeIterator();

public:
    /**
     * Returns the number of mimetypes supported.
     *
     * @return Number of mimetypes supported
     */
    int getMimeTypeCount(void) const;

    /**
     * Returns the number of file types supported.
     *
     * @return Number of file types supported
     */
    int getFileSuffixCount(void) const;

    /**
     * Adds the mimetype to the list of supported mimetypes
     *
     * @param[in] mimeType mimetype to be added
     * @return Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t addMimeType(const String8& mimeType);

    /**
     * Adds the filesuffix to the list of supported file types
     *
     * @param[in] filesuffix file suffix to be added
     * @return Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t addFileSuffix(const String8& fileSuffix);

    /**
     * Set the unique description about the plugin
     *
     * @param[in] description Unique description
     * @return Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t setDescription(const String8& description);

    /**
     * Returns the unique description associated with the plugin
     *
     * @return Unique description
     */
    String8 getDescription() const;

    /**
     * Returns whether given mimetype is supported or not
     *
     * @param[in] mimeType MIME type
     * @return
     *        true - if mime-type is supported
     *        false - if mime-type is not supported
     */
    bool isSupportedMimeType(const String8& mimeType) const;

    /**
     * Returns whether given file type is supported or not
     *
     * @param[in] fileType File type
     * @return
     *     true if file type is supported
     *     false if file type is not supported
     */
    bool isSupportedFileSuffix(const String8& fileType) const;

private:
    Vector<String8> mMimeTypeVector;
    Vector<String8> mFileSuffixVector;

    String8 mDescription;
};

};

#endif /* __DRM_SUPPORT_INFO_H__ */

