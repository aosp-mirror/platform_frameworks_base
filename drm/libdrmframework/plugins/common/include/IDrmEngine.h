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

#ifndef __IDRM_ENGINE_H__
#define __IDRM_ENGINE_H__

#include <drm/drm_framework_common.h>

namespace android {

class DrmConstraints;
class DrmMetadata;
class DrmRights;
class DrmInfo;
class DrmInfoStatus;
class DrmConvertedStatus;
class DrmInfoRequest;
class DrmSupportInfo;
class DrmInfoEvent;

/**
 * This class is an interface for plug-in user
 *
 * Responsibility of this class is provide generic interface to DRM Engine Manager.
 * Each interface need to be as abstract as possible.
 */
class IDrmEngine {
public:
    virtual ~IDrmEngine() {
    }

public:
    class OnInfoListener {

    public:
        virtual void onInfo(const DrmInfoEvent& event) = 0;

        virtual ~OnInfoListener() { }
    };

public:

    //////////////////////////////////
    // Implementation of IDrmEngine //
    //////////////////////////////////

    /**
     * Initialize plug-in
     *
     * @param[in] uniqueId Unique identifier for a session
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    virtual status_t initialize(int uniqueId) = 0;

    /**
     * Register a callback to be invoked when the caller required to
     * receive necessary information
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] infoListener Listener
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    virtual status_t setOnInfoListener(
            int uniqueId, const IDrmEngine::OnInfoListener* infoListener) = 0;

    /**
     * Terminate the plug-in
     * and release resource bound to plug-in
     * e.g.) release native resource
     *
     * @param[in] uniqueId Unique identifier for a session
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    virtual status_t terminate(int uniqueId) = 0;

    /**
     * Get constraint information associated with input content
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] path Path of the protected content
     * @param[in] action Actions defined such as,
     *     Action::DEFAULT, Action::PLAY, etc
     * @return DrmConstraints
     *     key-value pairs of constraint are embedded in it
     * @note
     *     In case of error, return NULL
     */
    virtual DrmConstraints* getConstraints(
            int uniqueId, const String8* path, int action) = 0;

    /**
     * Get metadata information associated with input content
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] path Path of the protected content
     * @return DrmMetadata
     *         key-value pairs of metadata
     * @note
     *      In case of error, return NULL
     */
    virtual DrmMetadata* getMetadata(int uniqueId, const String8* path) = 0;

    /**
     * Get whether the given content can be handled by this plugin or not
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] path Path the protected object
     * @return bool
     *     true if this plugin can handle , false in case of not able to handle
     */
    virtual bool canHandle(int uniqueId, const String8& path) = 0;

    /**
     * Executes given drm information based on its type
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] drmInfo Information needs to be processed
     * @return DrmInfoStatus
     *     instance as a result of processing given input
     */
    virtual DrmInfoStatus* processDrmInfo(int uniqueId, const DrmInfo* drmInfo) = 0;

    /**
     * Retrieves necessary information for registration, unregistration or rights
     * acquisition information.
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] drmInfoRequest Request information to retrieve drmInfo
     * @return DrmInfo
     *     instance as a result of processing given input
     */
    virtual DrmInfo* acquireDrmInfo(int uniqueId, const DrmInfoRequest* drmInfoRequest) = 0;

    /**
     * Save DRM rights to specified rights path
     * and make association with content path
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] drmRights DrmRights to be saved
     * @param[in] rightsPath File path where rights to be saved
     * @param[in] contentPath File path where content was saved
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    virtual status_t saveRights(int uniqueId, const DrmRights& drmRights,
            const String8& rightsPath, const String8& contentPath) = 0;

    /**
     * Retrieves the mime type embedded inside the original content
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] path Path of the protected content
     * @return String8
     *     Returns mime-type of the original content, such as "video/mpeg"
     */
    virtual String8 getOriginalMimeType(int uniqueId, const String8& path) = 0;

    /**
     * Retrieves the type of the protected object (content, rights, etc..)
     * using specified path or mimetype. At least one parameter should be non null
     * to retrieve DRM object type
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] path Path of the content or null.
     * @param[in] mimeType Mime type of the content or null.
     * @return type of the DRM content,
     *     such as DrmObjectType::CONTENT, DrmObjectType::RIGHTS_OBJECT
     */
    virtual int getDrmObjectType(
            int uniqueId, const String8& path, const String8& mimeType) = 0;

    /**
     * Check whether the given content has valid rights or not
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] path Path of the protected content
     * @param[in] action Action to perform (Action::DEFAULT, Action::PLAY, etc)
     * @return the status of the rights for the protected content,
     *     such as RightsStatus::RIGHTS_VALID, RightsStatus::RIGHTS_EXPIRED, etc.
     */
    virtual int checkRightsStatus(int uniqueId, const String8& path, int action) = 0;

    /**
     * Consumes the rights for a content.
     * If the reserve parameter is true the rights is reserved until the same
     * application calls this api again with the reserve parameter set to false.
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] decryptHandle Handle for the decryption session
     * @param[in] action Action to perform. (Action::DEFAULT, Action::PLAY, etc)
     * @param[in] reserve True if the rights should be reserved.
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    virtual status_t consumeRights(
            int uniqueId, DecryptHandle* decryptHandle, int action, bool reserve) = 0;

    /**
     * Informs the DRM Engine about the playback actions performed on the DRM files.
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] decryptHandle Handle for the decryption session
     * @param[in] playbackStatus Playback action (Playback::START, Playback::STOP, Playback::PAUSE)
     * @param[in] position Position in the file (in milliseconds) where the start occurs.
     *     Only valid together with Playback::START.
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    virtual status_t setPlaybackStatus(int uniqueId, DecryptHandle* decryptHandle,
            int playbackStatus, int64_t position) = 0;

    /**
     * Validates whether an action on the DRM content is allowed or not.
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] path Path of the protected content
     * @param[in] action Action to validate (Action::PLAY, Action::TRANSFER, etc)
     * @param[in] description Detailed description of the action
     * @return true if the action is allowed.
     */
    virtual bool validateAction(int uniqueId, const String8& path,
            int action, const ActionDescription& description) = 0;

    /**
     * Removes the rights associated with the given protected content
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] path Path of the protected content
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    virtual status_t removeRights(int uniqueId, const String8& path) = 0;

    /**
     * Removes all the rights information of each plug-in associated with
     * DRM framework. Will be used in master reset
     *
     * @param[in] uniqueId Unique identifier for a session
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    virtual status_t removeAllRights(int uniqueId) = 0;

    /**
     * This API is for Forward Lock based DRM scheme.
     * Each time the application tries to download a new DRM file
     * which needs to be converted, then the application has to
     * begin with calling this API.
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] convertId Handle for the convert session
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    virtual status_t openConvertSession(int uniqueId, int convertId) = 0;

    /**
     * Accepts and converts the input data which is part of DRM file.
     * The resultant converted data and the status is returned in the DrmConvertedInfo
     * object. This method will be called each time there are new block
     * of data received by the application.
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] convertId Handle for the convert session
     * @param[in] inputData Input Data which need to be converted
     * @return Return object contains the status of the data conversion,
     *     the output converted data and offset. In this case the
     *     application will ignore the offset information.
     */
    virtual DrmConvertedStatus* convertData(
            int uniqueId, int convertId, const DrmBuffer* inputData) = 0;

    /**
     * Informs the Drm Agent when there is no more data which need to be converted
     * or when an error occurs. Upon successful conversion of the complete data,
     * the agent will inform that where the header and body signature
     * should be added. This signature appending is needed to integrity
     * protect the converted file.
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] convertId Handle for the convert session
     * @return Return object contains the status of the data conversion,
     *     the header and body signature data. It also informs
     *     the application on which offset these signature data
     *     should be appended.
     */
    virtual DrmConvertedStatus* closeConvertSession( int uniqueId, int convertId) = 0;

    /**
     * Returns the information about the Drm Engine capabilities which includes
     * supported MimeTypes and file suffixes.
     *
     * @param[in] uniqueId Unique identifier for a session
     * @return DrmSupportInfo
     *     instance which holds the capabilities of a plug-in
     */
    virtual DrmSupportInfo* getSupportInfo(int uniqueId) = 0;

    /**
     * Open the decrypt session to decrypt the given protected content
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] decryptHandle Handle for the current decryption session
     * @param[in] fd File descriptor of the protected content to be decrypted
     * @param[in] offset Start position of the content
     * @param[in] length The length of the protected content
     * @param[in] mime Mime type of the protected content if it is
     *     not NULL or empty
     * @return
     *     DRM_ERROR_CANNOT_HANDLE for failure and DRM_NO_ERROR for success
     */
    virtual status_t openDecryptSession(
        int uniqueId, DecryptHandle* decryptHandle,
        int fd, off64_t offset, off64_t length, const char* mime) = 0;

    /**
     * Open the decrypt session to decrypt the given protected content
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] decryptHandle Handle for the current decryption session
     * @param[in] uri Path of the protected content to be decrypted
     * @param[in] mime Mime type of the protected content if it is
     *     not NULL or empty
     * @return
     *     DRM_ERROR_CANNOT_HANDLE for failure and DRM_NO_ERROR for success
     */
    virtual status_t openDecryptSession(
        int uniqueId, DecryptHandle* decryptHandle,
        const char* uri, const char* mime) = 0;

    /**
     * Close the decrypt session for the given handle
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] decryptHandle Handle for the decryption session
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    virtual status_t closeDecryptSession(int uniqueId, DecryptHandle* decryptHandle) = 0;

    /**
     * Initialize decryption for the given unit of the protected content
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] decryptHandle Handle for the decryption session
     * @param[in] decryptUnitId ID which specifies decryption unit, such as track ID
     * @param[in] headerInfo Information for initializing decryption of this decrypUnit
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    virtual status_t initializeDecryptUnit(int uniqueId, DecryptHandle* decryptHandle,
            int decryptUnitId, const DrmBuffer* headerInfo) = 0;

    /**
     * Decrypt the protected content buffers for the given unit
     * This method will be called any number of times, based on number of
     * encrypted streams received from application.
     *
     * @param[in] uniqueId Unique identifier for a session
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
    virtual status_t decrypt(int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId,
            const DrmBuffer* encBuffer, DrmBuffer** decBuffer, DrmBuffer* IV) = 0;

    /**
     * Finalize decryption for the given unit of the protected content
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] decryptHandle Handle for the decryption session
     * @param[in] decryptUnitId ID which specifies decryption unit, such as track ID
     * @return status_t
     *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    virtual status_t finalizeDecryptUnit(
            int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId) = 0;

    /**
     * Reads the specified number of bytes from an open DRM file.
     *
     * @param[in] uniqueId Unique identifier for a session
     * @param[in] decryptHandle Handle for the decryption session
     * @param[out] buffer Reference to the buffer that should receive the read data.
     * @param[in] numBytes Number of bytes to read.
     * @param[in] offset Offset with which to update the file position.
     *
     * @return Number of bytes read. Returns -1 for Failure.
     */
    virtual ssize_t pread(int uniqueId, DecryptHandle* decryptHandle,
            void* buffer, ssize_t numBytes, off64_t offset) = 0;
};

};

#endif /* __IDRM_ENGINE_H__ */

