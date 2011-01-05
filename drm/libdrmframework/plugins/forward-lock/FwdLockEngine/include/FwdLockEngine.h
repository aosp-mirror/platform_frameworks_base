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

#ifndef __FWDLOCKENGINE_H__
#define __FWDLOCKENGINE_H__

#include <DrmEngineBase.h>
#include <DrmConstraints.h>
#include <DrmRights.h>
#include <DrmInfo.h>
#include <DrmInfoStatus.h>
#include <DrmConvertedStatus.h>
#include <DrmInfoRequest.h>
#include <DrmSupportInfo.h>
#include <DrmInfoEvent.h>

#include "SessionMap.h"
#include "FwdLockConv.h"

namespace android {

/**
 * Forward Lock Engine class.
 */
class FwdLockEngine : public android::DrmEngineBase {

public:
    FwdLockEngine();
    virtual ~FwdLockEngine();

protected:
/**
 * Get constraint information associated with input content.
 *
 * @param uniqueId Unique identifier for a session
 * @param path Path of the protected content
 * @param action Actions defined such as,
 *     Action::DEFAULT, Action::PLAY, etc
 * @return DrmConstraints
 *     key-value pairs of constraint are embedded in it
 * @note
 *     In case of error, return NULL
 */
DrmConstraints* onGetConstraints(int uniqueId, const String8* path, int action);

/**
 * Get metadata information associated with input content.
 *
 * @param uniqueId Unique identifier for a session
 * @param path Path of the protected content
 * @return DrmMetadata
 *      For Forward Lock engine, it returns an empty object
 * @note
 *     In case of error, returns NULL
 */
DrmMetadata* onGetMetadata(int uniqueId, const String8* path);

/**
 * Initialize plug-in.
 *
 * @param uniqueId Unique identifier for a session
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t onInitialize(int uniqueId);

/**
 * Register a callback to be invoked when the caller required to
 * receive necessary information.
 *
 * @param uniqueId Unique identifier for a session
 * @param infoListener Listener
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t onSetOnInfoListener(int uniqueId, const IDrmEngine::OnInfoListener* infoListener);

/**
 * Terminate the plug-in and release resources bound to it.
 *
 * @param uniqueId Unique identifier for a session
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t onTerminate(int uniqueId);

/**
 * Get whether the given content can be handled by this plugin or not.
 *
 * @param uniqueId Unique identifier for a session
 * @param path Path to the protected object
 * @return bool
 *      Returns true if this plugin can handle , false in case of not able to handle
 */
bool onCanHandle(int uniqueId, const String8& path);

/**
 * Processes the given DRM information as appropriate for its type.
 * Not used for Forward Lock Engine.
 *
 * @param uniqueId Unique identifier for a session
 * @param drmInfo Information that needs to be processed
 * @return DrmInfoStatus
 *      instance as a result of processing given input
 */
DrmInfoStatus* onProcessDrmInfo(int uniqueId, const DrmInfo* drmInfo);

/**
 * Save DRM rights to specified rights path
 * and make association with content path.
 *
 * @param uniqueId Unique identifier for a session
 * @param drmRights DrmRights to be saved
 * @param rightsPath File path where rights to be saved
 * @param contentPath File path where content was saved
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t onSaveRights(int uniqueId,
                      const DrmRights& drmRights,
                      const String8& rightsPath,
                      const String8& contentPath);

/**
 * Retrieves necessary information for registration, unregistration or rights
 * acquisition information.
 *
 * @param uniqueId Unique identifier for a session
 * @param drmInfoRequest Request information to retrieve drmInfo
 * @return DrmInfo
 *      instance as a result of processing given input
 */
DrmInfo* onAcquireDrmInfo(int uniqueId,
                          const DrmInfoRequest* drmInfoRequest);

/**
 * Retrieves the mime type embedded inside the original content.
 *
 * @param uniqueId Unique identifier for a session
 * @param path Path of the protected content
 * @return String8
 *       Returns mime-type of the original content, such as "video/mpeg"
 */
String8 onGetOriginalMimeType(int uniqueId, const String8& path);

/**
 * Retrieves the type of the protected object (content, rights, etc..)
 * using specified path or mimetype. At least one parameter should be non null
 * to retrieve DRM object type.
 *
 * @param uniqueId Unique identifier for a session
 * @param path Path of the content or null.
 * @param mimeType Mime type of the content or null.
 * @return type of the DRM content,
 *     such as DrmObjectType::CONTENT, DrmObjectType::RIGHTS_OBJECT
 */
int onGetDrmObjectType(int uniqueId,
                       const String8& path,
                       const String8& mimeType);

/**
 * Check whether the given content has valid rights or not.
 *
 * @param uniqueId Unique identifier for a session
 * @param path Path of the protected content
 * @param action Action to perform (Action::DEFAULT, Action::PLAY, etc)
 * @return the status of the rights for the protected content,
 *     such as RightsStatus::RIGHTS_VALID, RightsStatus::RIGHTS_EXPIRED, etc.
 */
int onCheckRightsStatus(int uniqueId,
                        const String8& path,
                        int action);

/**
 * Consumes the rights for a content.
 * If the reserve parameter is true the rights are reserved until the same
 * application calls this api again with the reserve parameter set to false.
 *
 * @param uniqueId Unique identifier for a session
 * @param decryptHandle Handle for the decryption session
 * @param action Action to perform. (Action::DEFAULT, Action::PLAY, etc)
 * @param reserve True if the rights should be reserved.
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t onConsumeRights(int uniqueId,
                         DecryptHandle* decryptHandle,
                         int action,
                         bool reserve);

/**
 * Informs the DRM Engine about the playback actions performed on the DRM files.
 *
 * @param uniqueId Unique identifier for a session
 * @param decryptHandle Handle for the decryption session
 * @param playbackStatus Playback action (Playback::START, Playback::STOP, Playback::PAUSE)
 * @param position Position in the file (in milliseconds) where the start occurs.
 *     Only valid together with Playback::START.
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
#ifdef USE_64BIT_DRM_API
status_t onSetPlaybackStatus(int uniqueId,
                             DecryptHandle* decryptHandle,
                             int playbackStatus,
                             int64_t position);
#else
status_t onSetPlaybackStatus(int uniqueId,
                             DecryptHandle* decryptHandle,
                             int playbackStatus,
                             int position);
#endif

/**
 *  Validates whether an action on the DRM content is allowed or not.
 *
 * @param uniqueId Unique identifier for a session
 * @param path Path of the protected content
 * @param action Action to validate (Action::PLAY, Action::TRANSFER, etc)
 * @param description Detailed description of the action
 * @return true if the action is allowed.
 */
bool onValidateAction(int uniqueId,
                      const String8& path,
                      int action,
                      const ActionDescription& description);

/**
 * Removes the rights associated with the given protected content.
 * Not used for Forward Lock Engine.
 *
 * @param uniqueId Unique identifier for a session
 * @param path Path of the protected content
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t onRemoveRights(int uniqueId, const String8& path);

/**
 * Removes all the rights information of each plug-in associated with
 * DRM framework. Will be used in master reset but does nothing for
 * Forward Lock Engine.
 *
 * @param uniqueId Unique identifier for a session
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t onRemoveAllRights(int uniqueId);

/**
 * Starts the Forward Lock file conversion session.
 * Each time the application tries to download a new DRM file
 * which needs to be converted, then the application has to
 * begin with calling this API. The convertId is used as the conversion session key
 * and must not be the same for different convert sessions.
 *
 * @param uniqueId Unique identifier for a session
 * @param convertId Handle for the convert session
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t onOpenConvertSession(int uniqueId, int convertId);

/**
 * Accepts and converts the input data which is part of DRM file.
 * The resultant converted data and the status is returned in the DrmConvertedInfo
 * object. This method will be called each time there is a new block
 * of data received by the application.
 *
 * @param uniqueId Unique identifier for a session
 * @param convertId Handle for the convert session
 * @param inputData Input Data which need to be converted
 * @return Return object contains the status of the data conversion,
 *       the output converted data and offset. In this case the
 *      application will ignore the offset information.
 */
DrmConvertedStatus* onConvertData(int uniqueId,
                                  int convertId,
                                  const DrmBuffer* inputData);

/**
 * Closes the convert session in case of data supply completed or error occurred.
 * Upon successful conversion of the complete data, it returns signature calculated over
 * the entire data used over a conversion session. This signature must be copied to the offset
 * mentioned in the DrmConvertedStatus. Signature is used for data integrity protection.
 *
 * @param uniqueId Unique identifier for a session
 * @param convertId Handle for the convert session
 * @return Return object contains the status of the data conversion,
 *      the header and body signature data. It also informs
 *      the application about the file offset at which this
 *      signature data should be written.
 */
DrmConvertedStatus* onCloseConvertSession(int uniqueId, int convertId);

/**
 * Returns the information about the Drm Engine capabilities which includes
 * supported MimeTypes and file suffixes.
 *
 * @param uniqueId Unique identifier for a session
 * @return DrmSupportInfo
 *      instance which holds the capabilities of a plug-in
 */
DrmSupportInfo* onGetSupportInfo(int uniqueId);

/**
 * Open the decrypt session to decrypt the given protected content.
 *
 * @param uniqueId Unique identifier for a session
 * @param decryptHandle Handle for the current decryption session
 * @param fd File descriptor of the protected content to be decrypted
 * @param offset Start position of the content
 * @param length The length of the protected content
 * @return
 *     DRM_ERROR_CANNOT_HANDLE for failure and DRM_NO_ERROR for success
 */
#ifdef USE_64BIT_DRM_API
status_t onOpenDecryptSession(int uniqueId,
                              DecryptHandle* decryptHandle,
                              int fd, off64_t offset, off64_t length);
#else
status_t onOpenDecryptSession(int uniqueId,
                              DecryptHandle* decryptHandle,
                              int fd, int offset, int length);
#endif

/**
 * Open the decrypt session to decrypt the given protected content.
 *
 * @param uniqueId Unique identifier for a session
 * @param decryptHandle Handle for the current decryption session
 * @param uri Path of the protected content to be decrypted
 * @return
 *     DRM_ERROR_CANNOT_HANDLE for failure and DRM_NO_ERROR for success
 */
status_t onOpenDecryptSession(int uniqueId,
                              DecryptHandle* decryptHandle,
                              const char* uri);

/**
 * Close the decrypt session for the given handle.
 *
 * @param uniqueId Unique identifier for a session
 * @param decryptHandle Handle for the decryption session
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t onCloseDecryptSession(int uniqueId,
                               DecryptHandle* decryptHandle);

/**
 * Initialize decryption for the given unit of the protected content.
 *
 * @param uniqueId Unique identifier for a session
 * @param decryptHandle Handle for the decryption session
 * @param decryptUnitId ID which specifies decryption unit, such as track ID
 * @param headerInfo Information for initializing decryption of this decrypUnit
 * @return
 *     DRM_ERROR_CANNOT_HANDLE for failure and DRM_NO_ERROR for success
 */
status_t onInitializeDecryptUnit(int uniqueId,
                                 DecryptHandle* decryptHandle,
                                 int decryptUnitId,
                                 const DrmBuffer* headerInfo);

/**
 * Decrypt the protected content buffers for the given unit.
 * This method will be called any number of times, based on number of
 * encrypted streams received from application.
 *
 * @param uniqueId Unique identifier for a session
 * @param decryptHandle Handle for the decryption session
 * @param decryptUnitId ID which specifies decryption unit, such as track ID
 * @param encBuffer Encrypted data block
 * @param decBuffer Decrypted data block
 * @return status_t
 *     Returns the error code for this API
 *     DRM_NO_ERROR for success, and one of DRM_ERROR_UNKNOWN, DRM_ERROR_LICENSE_EXPIRED
 *     DRM_ERROR_SESSION_NOT_OPENED, DRM_ERROR_DECRYPT_UNIT_NOT_INITIALIZED,
 *     DRM_ERROR_DECRYPT for failure.
 */
status_t onDecrypt(int uniqueId,
                   DecryptHandle* decryptHandle,
                   int decryptUnitId,
                   const DrmBuffer* encBuffer,
                   DrmBuffer** decBuffer);

/**
 * Decrypt the protected content buffers for the given unit.
 * This method will be called any number of times, based on number of
 * encrypted streams received from application.
 *
 * @param uniqueId Unique identifier for a session
 * @param decryptId Handle for the decryption session
 * @param decryptUnitId ID Specifies decryption unit, such as track ID
 * @param encBuffer Encrypted data block
 * @param decBuffer Decrypted data block
 * @param IV Optional buffer
 * @return status_t
 *     Returns the error code for this API
 *     DRM_NO_ERROR for success, and one of DRM_ERROR_UNKNOWN, DRM_ERROR_LICENSE_EXPIRED
 *     DRM_ERROR_SESSION_NOT_OPENED, DRM_ERROR_DECRYPT_UNIT_NOT_INITIALIZED,
 *     DRM_ERROR_DECRYPT for failure.
 */
status_t onDecrypt(int uniqueId, DecryptHandle* decryptHandle,
                   int decryptUnitId, const DrmBuffer* encBuffer,
                   DrmBuffer** decBuffer, DrmBuffer* IV);

/**
 * Finalize decryption for the given unit of the protected content.
 *
 * @param uniqueId Unique identifier for a session
 * @param decryptHandle Handle for the decryption session
 * @param decryptUnitId ID Specifies decryption unit, such as track ID
 * @return
 *     DRM_ERROR_CANNOT_HANDLE for failure and DRM_NO_ERROR for success
 */
status_t onFinalizeDecryptUnit(int uniqueId,
                               DecryptHandle* decryptHandle,
                               int decryptUnitId);

/**
 * Reads the specified number of bytes from an open DRM file.
 *
 * @param uniqueId Unique identifier for a session
 * @param decryptHandle Handle for the decryption session
 * @param buffer Reference to the buffer that should receive the read data.
 * @param numBytes Number of bytes to read.
 *
 * @return Number of bytes read.
 * @retval -1 Failure.
 */
ssize_t onRead(int uniqueId,
               DecryptHandle* decryptHandle,
               void* pBuffer,
               int numBytes);

/**
 * Updates the file position within an open DRM file.
 *
 * @param uniqueId Unique identifier for a session
 * @param decryptHandle Handle for the decryption session
 * @param offset Offset with which to update the file position.
 * @param whence One of SEEK_SET, SEEK_CUR, and SEEK_END.
 *           These constants are defined in unistd.h.
 *
 * @return New file position.
 * @retval ((off_t)-1) Failure.
 */
#ifdef USE_64BIT_DRM_API
off64_t onLseek(int uniqueId,
                DecryptHandle* decryptHandle,
                off64_t offset,
                int whence);
#else
off_t onLseek(int uniqueId,
              DecryptHandle* decryptHandle,
              off_t offset,
              int whence);
#endif

/**
 * Reads the specified number of bytes from an open DRM file.
 *
 * @param uniqueId Unique identifier for a session
 * @param decryptHandle Handle for the decryption session
 * @param buffer Reference to the buffer that should receive the read data.
 * @param numBytes Number of bytes to read.
 * @param offset Offset with which to update the file position.
 *
 * @return Number of bytes read. Returns -1 for Failure.
 */
#ifdef USE_64BIT_DRM_API
ssize_t onPread(int uniqueId,
                DecryptHandle* decryptHandle,
                void* buffer,
                ssize_t numBytes,
                off64_t offset);
#else
ssize_t onPread(int uniqueId,
                DecryptHandle* decryptHandle,
                void* buffer,
                ssize_t numBytes,
                off_t offset);
#endif

private:

/**
 * Session Class for Forward Lock Conversion. An object of this class is created
 * for every conversion.
 */
class ConvertSession {
    public :
        int uniqueId;
        FwdLockConv_Output_t output;

        ConvertSession() {
            uniqueId = 0;
            memset(&output, 0, sizeof(FwdLockConv_Output_t));
        }

        virtual ~ConvertSession() {}
};

/**
 * Session Class for Forward Lock decoder. An object of this class is created
 * for every decoding session.
 */
class DecodeSession {
    public :
        int fileDesc;
        off_t offset;

        DecodeSession() {
            fileDesc = -1;
            offset = 0;
        }

        DecodeSession(int fd) {
            fileDesc = fd;
            offset = 0;
        }

        virtual ~DecodeSession() {}
};

/**
 * Session Map Tables for Conversion and Decoding of forward lock files.
 */
SessionMap<ConvertSession*> convertSessionMap;
SessionMap<DecodeSession*> decodeSessionMap;

/**
 * Converts the error code from Forward Lock Converter to DrmConvertStatus error code.
 *
 * @param Forward Lock Converter error code
 *
 * @return Status code from DrmConvertStatus.
 */
static int getConvertedStatus(FwdLockConv_Status_t status);
};

};

#endif /* __FWDLOCKENGINE_H__ */
