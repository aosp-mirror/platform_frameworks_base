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

#ifndef __DRM_MANAGER_CLIENT_H__
#define __DRM_MANAGER_CLIENT_H__

#include <utils/threads.h>
#include <binder/IInterface.h>
#include "drm_framework_common.h"

namespace android {

class DrmInfo;
class DrmRights;
class DrmMetadata;
class DrmInfoEvent;
class DrmInfoStatus;
class DrmInfoRequest;
class DrmSupportInfo;
class DrmConstraints;
class DrmConvertedStatus;
class DrmManagerClientImpl;

/**
 * The Native application will instantiate this class and access DRM Framework
 * services through this class.
 *
 */
class DrmManagerClient {
public:
    DrmManagerClient();

    virtual ~DrmManagerClient();

public:
    class OnInfoListener: virtual public RefBase {

    public:
        virtual ~OnInfoListener() {}

    public:
        virtual void onInfo(const DrmInfoEvent& event) = 0;
    };

/**
 * APIs which will be used by native modules (e.g. StageFright)
 *
 */
public:
    /**
     * Open the decrypt session to decrypt the given protected content
     *
     * @param[in] fd File descriptor of the protected content to be decrypted
     * @param[in] offset Start position of the content
     * @param[in] length The length of the protected content
     * @return
     *     Handle for the decryption session
     */
    DecryptHandle* openDecryptSession(int fd, off64_t offset, off64_t length);

    /**
     * Open the decrypt session to decrypt the given protected content
     *
     * @param[in] uri Path of the protected content to be decrypted
     * @return
     *     Handle for the decryption session
     */
    DecryptHandle* openDecryptSession(const char* uri);

    /**
     * Close the decrypt session for the given handle
     *
     * @param[in] decryptHandle Handle for the decryption session
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t closeDecryptSession(DecryptHandle* decryptHandle);

    /**
     * Consumes the rights for a content.
     * If the reserve parameter is true the rights is reserved until the same
     * application calls this api again with the reserve parameter set to false.
     *
     * @param[in] decryptHandle Handle for the decryption session
     * @param[in] action Action to perform. (Action::DEFAULT, Action::PLAY, etc)
     * @param[in] reserve True if the rights should be reserved.
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure.
     *     In case license has been expired, DRM_ERROR_LICENSE_EXPIRED will be returned.
     */
    status_t consumeRights(DecryptHandle* decryptHandle, int action, bool reserve);

    /**
     * Informs the DRM engine about the playback actions performed on the DRM files.
     *
     * @param[in] decryptHandle Handle for the decryption session
     * @param[in] playbackStatus Playback action (Playback::START, Playback::STOP, Playback::PAUSE)
     * @param[in] position Position in the file (in milliseconds) where the start occurs.
     *                     Only valid together with Playback::START.
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t setPlaybackStatus(DecryptHandle* decryptHandle, int playbackStatus, int64_t position);

    /**
     * Initialize decryption for the given unit of the protected content
     *
     * @param[in] decryptHandle Handle for the decryption session
     * @param[in] decryptUnitId ID which specifies decryption unit, such as track ID
     * @param[in] headerInfo Information for initializing decryption of this decrypUnit
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t initializeDecryptUnit(
            DecryptHandle* decryptHandle, int decryptUnitId, const DrmBuffer* headerInfo);

    /**
     * Decrypt the protected content buffers for the given unit
     * This method will be called any number of times, based on number of
     * encrypted streams received from application.
     *
     * @param[in] decryptHandle Handle for the decryption session
     * @param[in] decryptUnitId ID which specifies decryption unit, such as track ID
     * @param[in] encBuffer Encrypted data block
     * @param[out] decBuffer Decrypted data block
     * @param[in] IV Optional buffer
     * @return status_t
     *     Returns the error code for this API
     *     DRM_NO_ERROR for success, and one of DRM_ERROR_UNKNOWN, DRM_ERROR_LICENSE_EXPIRED
     *     DRM_ERROR_SESSION_NOT_OPENED, DRM_ERROR_DECRYPT_UNIT_NOT_INITIALIZED,
     *     DRM_ERROR_DECRYPT for failure.
     */
    status_t decrypt(
            DecryptHandle* decryptHandle, int decryptUnitId,
            const DrmBuffer* encBuffer, DrmBuffer** decBuffer, DrmBuffer* IV = NULL);

    /**
     * Finalize decryption for the given unit of the protected content
     *
     * @param[in] decryptHandle Handle for the decryption session
     * @param[in] decryptUnitId ID which specifies decryption unit, such as track ID
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t finalizeDecryptUnit(DecryptHandle* decryptHandle, int decryptUnitId);

    /**
     * Reads the specified number of bytes from an open DRM file.
     *
     * @param[in] decryptHandle Handle for the decryption session
     * @param[out] buffer Reference to the buffer that should receive the read data.
     * @param[in] numBytes Number of bytes to read.
     * @param[in] offset Offset with which to update the file position.
     *
     * @return Number of bytes read. Returns -1 for Failure.
     */
    ssize_t pread(DecryptHandle* decryptHandle, void* buffer, ssize_t numBytes, off64_t offset);

    /**
     * Validates whether an action on the DRM content is allowed or not.
     *
     * @param[in] path Path of the protected content
     * @param[in] action Action to validate. (Action::DEFAULT, Action::PLAY, etc)
     * @param[in] description Detailed description of the action
     * @return true if the action is allowed.
     */
    bool validateAction(const String8& path, int action, const ActionDescription& description);

/**
 * APIs which are just the underlying implementation for the Java API
 *
 */
public:
    /**
     * Register a callback to be invoked when the caller required to
     * receive necessary information
     *
     * @param[in] infoListener Listener
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t setOnInfoListener(const sp<DrmManagerClient::OnInfoListener>& infoListener);

    /**
     * Get constraint information associated with input content
     *
     * @param[in] path Path of the protected content
     * @param[in] action Actions defined such as,
     *             Action::DEFAULT, Action::PLAY, etc
     * @return DrmConstraints
     *     key-value pairs of constraint are embedded in it
     * @note
     *     In case of error, return NULL
     */
    DrmConstraints* getConstraints(const String8* path, const int action);

    /**
     * Get metadata information associated with input content
     *
     * @param[in] path Path of the protected content
     * @return DrmMetadata
     *         key-value pairs of metadata
     * @note
     *     In case of error, return NULL
     */
    DrmMetadata* getMetadata(const String8* path);

    /**
     * Check whether the given mimetype or path can be handled
     *
     * @param[in] path Path of the content needs to be handled
     * @param[in] mimetype Mimetype of the content needs to be handled
     * @return
     *     True if DrmManager can handle given path or mime type.
     */
    bool canHandle(const String8& path, const String8& mimeType);

    /**
     * Executes given drm information based on its type
     *
     * @param[in] drmInfo Information needs to be processed
     * @return DrmInfoStatus
     *     instance as a result of processing given input
     */
    DrmInfoStatus* processDrmInfo(const DrmInfo* drmInfo);

    /**
     * Retrieves necessary information for registration, unregistration or rights
     * acquisition information.
     *
     * @param[in] drmInfoRequest Request information to retrieve drmInfo
     * @return DrmInfo
     *     instance as a result of processing given input
     */
    DrmInfo* acquireDrmInfo(const DrmInfoRequest* drmInfoRequest);

    /**
     * Save DRM rights to specified rights path
     * and make association with content path
     *
     * @param[in] drmRights DrmRights to be saved
     * @param[in] rightsPath File path where rights to be saved
     * @param[in] contentPath File path where content was saved
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t saveRights(
        const DrmRights& drmRights, const String8& rightsPath, const String8& contentPath);

    /**
     * Retrieves the mime type embedded inside the original content
     *
     * @param[in] path the path of the protected content
     * @return String8
     *     Returns mime-type of the original content, such as "video/mpeg"
     */
    String8 getOriginalMimeType(const String8& path);

    /**
     * Retrieves the type of the protected object (content, rights, etc..)
     * by using specified path or mimetype. At least one parameter should be non null
     * to retrieve DRM object type
     *
     * @param[in] path Path of the content or null.
     * @param[in] mimeType Mime type of the content or null.
     * @return type of the DRM content,
     *     such as DrmObjectType::CONTENT, DrmObjectType::RIGHTS_OBJECT
     */
    int getDrmObjectType(const String8& path, const String8& mimeType);

    /**
     * Check whether the given content has valid rights or not
     *
     * @param[in] path Path of the protected content
     * @param[in] action Action to perform
     * @return the status of the rights for the protected content,
     *     such as RightsStatus::RIGHTS_VALID, RightsStatus::RIGHTS_EXPIRED, etc.
     */
    int checkRightsStatus(const String8& path, int action);

    /**
     * Removes the rights associated with the given protected content
     *
     * @param[in] path Path of the protected content
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t removeRights(const String8& path);

    /**
     * Removes all the rights information of each plug-in associated with
     * DRM framework. Will be used in master reset
     *
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t removeAllRights();

    /**
     * This API is for Forward Lock DRM.
     * Each time the application tries to download a new DRM file
     * which needs to be converted, then the application has to
     * begin with calling this API.
     *
     * @param[in] convertId Handle for the convert session
     * @param[in] mimeType Description/MIME type of the input data packet
     * @return Return handle for the convert session
     */
    int openConvertSession(const String8& mimeType);

    /**
     * Passes the input data which need to be converted. The resultant
     * converted data and the status is returned in the DrmConvertedInfo
     * object. This method will be called each time there are new block
     * of data received by the application.
     *
     * @param[in] convertId Handle for the convert session
     * @param[in] inputData Input Data which need to be converted
     * @return Return object contains the status of the data conversion,
     *     the output converted data and offset. In this case the
     *     application will ignore the offset information.
     */
    DrmConvertedStatus* convertData(int convertId, const DrmBuffer* inputData);

    /**
     * When there is no more data which need to be converted or when an
     * error occurs that time the application has to inform the Drm agent
     * via this API. Upon successful conversion of the complete data,
     * the agent will inform that where the header and body signature
     * should be added. This signature appending is needed to integrity
     * protect the converted file.
     *
     * @param[in] convertId Handle for the convert session
     * @return Return object contains the status of the data conversion,
     *     the header and body signature data. It also informs
     *     the application on which offset these signature data
     *     should be appended.
     */
    DrmConvertedStatus* closeConvertSession(int convertId);

    /**
     * Retrieves all DrmSupportInfo instance that native DRM framework can handle.
     * This interface is meant to be used by JNI layer
     *
     * @param[out] length Number of elements in drmSupportInfoArray
     * @param[out] drmSupportInfoArray Array contains all DrmSupportInfo
     *     that native DRM framework can handle
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t getAllSupportInfo(int* length, DrmSupportInfo** drmSupportInfoArray);

private:
    int mUniqueId;
    sp<DrmManagerClientImpl> mDrmManagerClientImpl;
};

};

#endif /* __DRM_MANAGER_CLIENT_H__ */

