/*
 * Copyright (C) 2011 The Android Open Source Project
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
#define LOG_NDEBUG 0
#define LOG_TAG "WVMDrmPlugIn"
#include <utils/Log.h>
#include <vector>

#include <drm/DrmRights.h>
#include <drm/DrmConstraints.h>
#include <drm/DrmInfo.h>
#include <drm/DrmInfoStatus.h>
#include <drm/DrmConvertedStatus.h>
#include <drm/DrmInfoRequest.h>
#include <drm/DrmSupportInfo.h>
#include <drm/DrmMetadata.h>

#include "WVMDrmPlugin.h"
#include "WVMLogging.h"
#include "AndroidHooks.h"

using namespace std;
using namespace android;


// This extern "C" is mandatory to be managed by TPlugInManager
extern "C" IDrmEngine* create() {
    return new WVMDrmPlugin();
}

// This extern "C" is mandatory to be managed by TPlugInManager
extern "C" void destroy(IDrmEngine* pPlugIn) {
    delete pPlugIn;
}

WVMDrmPlugin::WVMDrmPlugin()
    : DrmEngineBase(),
      mOnInfoListener(NULL),
      mDrmPluginImpl(WVDRMPluginAPI::create())
{
}

WVMDrmPlugin::~WVMDrmPlugin() {
    WVDRMPluginAPI::destroy(mDrmPluginImpl);
}


/**
 * Initialize plug-in
 *
 * @param[in] uniqueId Unique identifier for a session
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t WVMDrmPlugin::onInitialize(int uniqueId) {
    //LOGD("WVMDrmPlugin::onInitialize : %d", uniqueId);
    AndroidSetLogCallout(android_printbuf);
    return DRM_NO_ERROR;
}

/**
 * Terminate the plug-in
 * and release resource bound to plug-in
 *
 * @param[in] uniqueId Unique identifier for a session
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t WVMDrmPlugin::onTerminate(int uniqueId) {
    //LOGD("WVMDrmPlugin::onTerminate : %d", uniqueId);
    return DRM_NO_ERROR;
}

/**
 * Register a callback to be invoked when the caller required to
 * receive necessary information
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] infoListener Listener
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t WVMDrmPlugin::onSetOnInfoListener(
            int uniqueId, const IDrmEngine::OnInfoListener* infoListener) {
    //LOGD("WVMDrmPlugin::onSetOnInfoListener : %d", uniqueId);
    mOnInfoListener = infoListener;
    return DRM_NO_ERROR;
}

/**
 * Retrieves necessary information for registration, unregistration or rights
 * acquisition information.
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] drmInfoRequest Request information to retrieve drmInfo
 * @return DrmInfo
 *     instance as a result of processing given input
 */
DrmInfo* WVMDrmPlugin::onAcquireDrmInfo(int uniqueId, const DrmInfoRequest* drmInfoRequest) {
    //LOGD("WVMDrmPlugin::onAcquireDrmInfo : %d", uniqueId);
    DrmInfo* drmInfo = NULL;

    std::string assetPath;

    if (NULL != drmInfoRequest) {
        switch(drmInfoRequest->getInfoType()) {
            case DrmInfoRequest::TYPE_RIGHTS_ACQUISITION_INFO: {

                assetPath = drmInfoRequest->get(String8("WVAssetURIKey")).string();

                WVCredentials credentials;

                // creates a data store object per each portal
                credentials.portal = drmInfoRequest->get(String8("WVPortalKey")).string();
                if ( (assetPath.size() == 0) || (credentials.portal.size() == 0) ) {
                    LOGE("onAcquireDrmInfo: Empty asset path or portal string, must specify both");
                    return NULL;
                }

                std::string assetDbPath = drmInfoRequest->get(String8("WVAssetDBPathKey")).string();
                //LOGV("onAcquireDrmInfo: portal=%s, dsPath=%s", credentials.portal.c_str(), assetDbPath.c_str());

                credentials.drmServerURL = drmInfoRequest->get(String8("WVDRMServerKey")).string();
                credentials.userData = drmInfoRequest->get(String8("WVCAUserDataKey")).string();
                credentials.deviceID = drmInfoRequest->get(String8("WVDeviceIDKey")).string();
                credentials.streamID = drmInfoRequest->get(String8("WVStreamIDKey")).string();

                string systemIdStr = drmInfoRequest->get(String8("WVSystemIDKey")).string();
                string assetIdStr = drmInfoRequest->get(String8("WVAssetIDKey")).string();
                string keyIdStr = drmInfoRequest->get(String8("WVKeyIDKey")).string();

                uint32_t systemId, assetId, keyId;

                if (!mDrmPluginImpl->AcquireDrmInfo(assetPath, credentials, assetDbPath,
                                                    systemIdStr, assetIdStr, keyIdStr,
                                                    &systemId, &assetId, &keyId))
                    return NULL;


                String8 dataString("dummy_acquistion_string");
                int length = dataString.length();
                char* data = NULL;
                data = new char[length];
                memcpy(data, dataString.string(), length);
                drmInfo = new DrmInfo(drmInfoRequest->getInfoType(),
                                      DrmBuffer(data, length), drmInfoRequest->getMimeType());

                // Sets additional drmInfo attributes
                drmInfo->put(String8("WVAssetURIKey"), String8(assetPath.c_str()));
                drmInfo->put(String8("WVDRMServerKey"), String8(credentials.drmServerURL.c_str()));
                drmInfo->put(String8("WVAssetDbPathKey"), String8(assetDbPath.c_str()));
                drmInfo->put(String8("WVPortalKey"), String8(credentials.portal.c_str()));
                drmInfo->put(String8("WVCAUserDataKey"), String8(credentials.userData.c_str()));
                drmInfo->put(String8("WVDeviceIDKey"), String8(credentials.deviceID.c_str()));
                drmInfo->put(String8("WVStreamIDKey"), String8(credentials.streamID.c_str()));

                char buffer[16];
                sprintf(buffer, "%lu", (unsigned long)systemId);
                drmInfo->put(String8("WVSystemIDKey"), String8(buffer));
                sprintf(buffer, "%lu", (unsigned long)assetId);
                drmInfo->put(String8("WVAssetIDKey"), String8(buffer));
                sprintf(buffer, "%lu", (unsigned long)keyId);
                drmInfo->put(String8("WVKeyIDKey"), String8(buffer));
                break;
            }
            case DrmInfoRequest::TYPE_REGISTRATION_INFO:
            case DrmInfoRequest::TYPE_UNREGISTRATION_INFO:
            case DrmInfoRequest::TYPE_RIGHTS_ACQUISITION_PROGRESS_INFO: {
                LOGE("onAcquireDrmInfo: Unsupported DrmInfoRequest type %d",
                     drmInfoRequest->getInfoType());
                break;
            }
            default: {
                LOGE("onAcquireDrmInfo: Unknown info type %d", drmInfoRequest->getInfoType());
                break;
            }
        }
    }
    return drmInfo;
}

/**
 * Executes given drm information based on its type
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] drmInfo Information needs to be processed
 * @return DrmInfoStatus
 *     instance as a result of processing given input
 */
DrmInfoStatus* WVMDrmPlugin::onProcessDrmInfo(int uniqueId, const DrmInfo* drmInfo) {
    //LOGD("WVMDrmPlugin::onProcessDrmInfo: %d", uniqueId);

    int status = DrmInfoStatus::STATUS_ERROR;

    if (NULL != drmInfo) {
        if (drmInfo->getInfoType() == DrmInfoRequest::TYPE_RIGHTS_ACQUISITION_INFO) {
            std::string assetPath = drmInfo->get(String8("WVAssetURIKey")).string();

            if (mDrmPluginImpl->ProcessDrmInfo(assetPath))
                status = DrmInfoStatus::STATUS_OK;
        } else {
            LOGE("onProcessDrmInfo : drmInfo type %d not supported", drmInfo->getInfoType());
        }
    } else {
        LOGE("onProcessDrmInfo : drmInfo cannot be NULL");
    }

    String8 licenseString("dummy_license_string");
    const int bufferSize = licenseString.size();
    char* data = NULL;
    data = new char[bufferSize];
    memcpy(data, licenseString.string(), bufferSize);
    const DrmBuffer* buffer = new DrmBuffer(data, bufferSize);
    DrmInfoStatus* drmInfoStatus =
            new DrmInfoStatus(status, drmInfo->getInfoType(), buffer, drmInfo->getMimeType());

    return drmInfoStatus;
}

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
DrmConstraints* WVMDrmPlugin::onGetConstraints(int uniqueId, const String8* path, int action)
{
    //LOGD("WVMDrmPlugin::onGetConstraints : %d", uniqueId);

    if ( (Action::DEFAULT != action) && (Action::PLAY != action) ) {
        LOGE("onGetConstraints : action %d not supported", action);
        return NULL;
    }

    uint32_t licenseDuration = 0;
    uint32_t timeSincePlayback = 0;
    uint32_t timeRemaining = 0;

    std::string assetPath(path->string());
    if (!mDrmPluginImpl->GetConstraints(assetPath, &timeSincePlayback, &timeRemaining, &licenseDuration))
        return NULL;

    DrmConstraints* drmConstraints =  new DrmConstraints();
    char charValue[16]; // max uint32 = 0xffffffff + terminating char

    memset(charValue, 0, 16);
    sprintf(charValue, "%lu", (unsigned long)timeSincePlayback);
    drmConstraints->put(&(DrmConstraints::LICENSE_START_TIME), charValue);

    memset(charValue, 0, 16);
    sprintf(charValue, "%lu", (unsigned long)timeRemaining);
    drmConstraints->put(&(DrmConstraints::LICENSE_EXPIRY_TIME), charValue);

    memset(charValue, 0, 16);
    sprintf(charValue, "%lu", (unsigned long)licenseDuration);
    drmConstraints->put(&(DrmConstraints::LICENSE_AVAILABLE_TIME), charValue);

    return drmConstraints;
}


/**
 * Returns the information about the Drm Engine capabilities which includes
 * supported MimeTypes and file suffixes.
 *
 * @param[in] uniqueId Unique identifier for a session
 * @return DrmSupportInfo
 *     instance which holds the capabilities of a plug-in
 */
DrmSupportInfo* WVMDrmPlugin::onGetSupportInfo(int uniqueId) {
    //LOGD("WVMDrmPlugin::onGetSupportInfo : %d", uniqueId);
    DrmSupportInfo* drmSupportInfo = new DrmSupportInfo();
    // Add mimetype's
    drmSupportInfo->addMimeType(String8("video/wvm"));
    // Add File Suffixes
    drmSupportInfo->addFileSuffix(String8(".wvm"));
    // Add plug-in description
    drmSupportInfo->setDescription(String8("Widevine DRM plug-in"));
    return drmSupportInfo;
}

/**
 * Get meta data from protected content
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] path Path of the protected content
 *
 * @return DrmMetadata
 *      key-value pairs of meta data; NULL if failed
 */
DrmMetadata* WVMDrmPlugin::onGetMetadata(int uniqueId, const String8* path) {
    //LOGD("WVDrmPlugin::onGetMetadata returns NULL\n");
    return NULL;
}

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
status_t WVMDrmPlugin::onSaveRights(int uniqueId, const DrmRights& drmRights,
            const String8& rightsPath, const String8& contentPath) {
    //LOGD("WVMDrmPlugin::onSaveRights : %d", uniqueId);
    return DRM_NO_ERROR;
}

/**
 * Get whether the given content can be handled by this plugin or not
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] path Path the protected object
 * @return bool
 *     Returns true if this plugin can handle , false in case of not able to handle
 */
bool WVMDrmPlugin::onCanHandle(int uniqueId, const String8& path) {
    //LOGD("WVMDrmPlugin::canHandle('%s') ", path.string());
    String8 extension = path.getPathExtension();
    extension.toLower();
    return (String8(".wvm") == extension);
}

/**
 * Retrieves the mime type embedded inside the original content
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] path Path of the protected content
 * @return String8
 *     Returns mime-type of the original content, such as "video/mpeg"
 */
String8 WVMDrmPlugin::onGetOriginalMimeType(int uniqueId, const String8& path) {
    //LOGD("WVMDrmPlugin::onGetOriginalMimeType() : %d", uniqueId);
    return String8("video/wvm");
}

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
int WVMDrmPlugin::onGetDrmObjectType(
            int uniqueId, const String8& path, const String8& mimeType) {
    //LOGD("WVMDrmPlugin::onGetDrmObjectType() : %d", uniqueId);
    return DrmObjectType::UNKNOWN;
}

/**
 * Check whether the given content has valid rights or not
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] path Path of the protected content
 * @param[in] action Action to perform (Action::DEFAULT, Action::PLAY, etc)
 * @return the status of the rights for the protected content,
 *     such as RightsStatus::RIGHTS_VALID, RightsStatus::RIGHTS_EXPIRED, etc.
 */
int WVMDrmPlugin::onCheckRightsStatus(int uniqueId, const String8& path, int action) {
    //LOGD("WVMDrmPlugin::onCheckRightsStatus() : %d", uniqueId);

    if ( (Action::DEFAULT != action) && (Action::PLAY != action) ) {
        LOGE("onCheckRightsStatus : action %d not supported", action);
        return RightsStatus::RIGHTS_INVALID;
    }

    std::string assetPath(path.string());
    int rightsStatus = mDrmPluginImpl->CheckRightsStatus(assetPath);

    switch(rightsStatus) {
    case WVDRMPluginAPI::RIGHTS_INVALID:
        return RightsStatus::RIGHTS_INVALID;
        break;
    case WVDRMPluginAPI::RIGHTS_EXPIRED:
        return RightsStatus::RIGHTS_EXPIRED;
        break;
    case WVDRMPluginAPI::RIGHTS_VALID:
        return RightsStatus::RIGHTS_VALID;
        break;
    case WVDRMPluginAPI::RIGHTS_NOT_ACQUIRED:
        return RightsStatus::RIGHTS_NOT_ACQUIRED;
        break;
    }
    return RightsStatus::RIGHTS_INVALID;
}

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
status_t WVMDrmPlugin::onConsumeRights(int uniqueId, DecryptHandle* decryptHandle,
            int action, bool reserve) {
    //LOGD("WVMDrmPlugin::onConsumeRights() : %d", uniqueId);
    return DRM_NO_ERROR;
}

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
status_t WVMDrmPlugin::onSetPlaybackStatus(int uniqueId, DecryptHandle* decryptHandle,
            int playbackStatus, off64_t position) {
    //LOGD("WVMDrmPlugin::onSetPlaybackStatus");

    int op;

    switch(playbackStatus) {
    case Playback::START:
        op = WVDRMPluginAPI::PLAYBACK_START;
        break;
    case Playback::STOP:
        op = WVDRMPluginAPI::PLAYBACK_STOP;
        break;
    case Playback::PAUSE:
        op = WVDRMPluginAPI::PLAYBACK_PAUSE;
        break;
    default:
        op = WVDRMPluginAPI::PLAYBACK_INVALID;
        break;
    }

    if (mDrmPluginImpl->SetPlaybackStatus(op, position))
        return DRM_NO_ERROR;

    return DRM_ERROR_UNKNOWN;
}

/**
 * Validates whether an action on the DRM content is allowed or not.
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] path Path of the protected content
 * @param[in] action Action to validate (Action::PLAY, Action::TRANSFER, etc)
 * @param[in] description Detailed description of the action
 * @return true if the action is allowed.
 */
bool WVMDrmPlugin::onValidateAction(int uniqueId, const String8& path,
            int action, const ActionDescription& description) {
    //LOGD("WVMDrmPlugin::onValidateAction() : %d", uniqueId);
    return true;
}

/**
 * Removes the rights associated with the given protected content
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] path Path of the protected content
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t WVMDrmPlugin::onRemoveRights(int uniqueId, const String8& path) {
    //LOGD("WVMDrmPlugin::onRemoveRights() : %d", uniqueId);

    std::string assetPath(path.string());
    if (mDrmPluginImpl->RemoveRights(assetPath))
        return DRM_NO_ERROR;

    return DRM_ERROR_UNKNOWN;
}

/**
 * Removes all the rights information of each plug-in associated with
 * DRM framework. Will be used in master reset
 *
 * @param[in] uniqueId Unique identifier for a session
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t WVMDrmPlugin::onRemoveAllRights(int uniqueId) {
    //LOGD("WVMDrmPlugin::onRemoveAllRights() : %d", uniqueId);

    if (mDrmPluginImpl->RemoveAllRights())
        return DRM_NO_ERROR;

    return DRM_ERROR_UNKNOWN;
}

/**
 * Open the decrypt session to decrypt the given protected content
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] decryptHandle Handle for the current decryption session
 * @param[in] fd File descriptor of the protected content to be decrypted
 * @param[in] offset Start position of the content
 * @param[in] length The length of the protected content
 * @return
 *     DRM_ERROR_CANNOT_HANDLE for failure and DRM_NO_ERROR for success
 */
status_t WVMDrmPlugin::onOpenDecryptSession(
            int uniqueId, DecryptHandle *decryptHandle, int fd, off64_t offset, off64_t length)
{
    status_t result = DRM_ERROR_CANNOT_HANDLE;

    //LOGD("onOpenDecryptSession: fd=%d, offset=%lld, length=%lld", fd, offset, length);

    char buffer[64 * 1024];
    int dupfd = dup(fd);
    if (dupfd == -1)
        return result;

    FILE *f = fdopen(dupfd, "rb");
    if (f) {
        fseek(f, 0, SEEK_SET);
        if (fread(buffer, 1, sizeof(buffer), f) != sizeof(buffer)) {
            fclose(f);
            return DRM_ERROR_CANNOT_HANDLE;
        }
        fclose(f);
    } else {
        close(dupfd);
        return result;
    }

    if (WV_IsWidevineMedia(buffer, sizeof(buffer))) {
        //LOGD("WVMDrmPlugin::onOpenDecryptSession - WV_IsWidevineMedia: true");
        decryptHandle->mimeType = String8("video/wvm");
        decryptHandle->decryptApiType = DecryptApiType::WV_BASED;
        decryptHandle->status = DRM_NO_ERROR;
        decryptHandle->decryptInfo = NULL;
        result = DRM_NO_ERROR;
    } else {
        //LOGD("WVMDrmPlugin::onOpenDecryptSession - not Widevine media");
    }

    return result;
}

/**
 * Open the decrypt session to decrypt the given protected content
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] decryptHandle Handle for the current decryption session
 * @param[in] uri Path of the protected content to be decrypted
 * @return
 *     DRM_ERROR_CANNOT_HANDLE for failure and DRM_NO_ERROR for success
 */
status_t WVMDrmPlugin::onOpenDecryptSession(
    int uniqueId, DecryptHandle* decryptHandle, const char* uri)
{
    status_t result = DRM_ERROR_CANNOT_HANDLE;

    if (!uri)
        return result;

    size_t len = strlen(uri);

    if ((len >= 4 && !strncmp(&uri[len - 4], ".wvm", 4)) ||
        (strstr(uri, ".wvm?") != NULL) ||
        (len >= 5 && !strncmp(&uri[len - 5], ".m3u8", 5)) ||
        (strstr(uri, ".m3u8?") != NULL))
    {
        //LOGD("WVMDrmPlugin::onOpenDecryptSession(uri) : %d - match", uniqueId);
        decryptHandle->mimeType = String8("video/wvm");
        decryptHandle->decryptApiType = DecryptApiType::WV_BASED;
        decryptHandle->status = DRM_NO_ERROR;
        decryptHandle->decryptInfo = NULL;

        mDrmPluginImpl->OpenSession();
        result = DRM_NO_ERROR;
    } else {
        //LOGD("WVMDrmPlugin::onOpenDecryptSession(uri) - not Widevine media");
    }


    return result;
}


/**
 * Close the decrypt session for the given handle
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] decryptHandle Handle for the decryption session
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t WVMDrmPlugin::onCloseDecryptSession(int uniqueId, DecryptHandle* decryptHandle) {
    //LOGD("WVMDrmPlugin::onCloseDecryptSession() : %d", uniqueId);
    if (NULL != decryptHandle) {
        if (NULL != decryptHandle->decryptInfo) {
            delete decryptHandle->decryptInfo; decryptHandle->decryptInfo = NULL;
        }
        delete decryptHandle; decryptHandle = NULL;
    }
    mDrmPluginImpl->CloseSession();
    return DRM_NO_ERROR;
}

/**
 * Initialize decryption for the given unit of the protected content
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] decryptId Handle for the decryption session
 * @param[in] decryptUnitId ID Specifies decryption unit, such as track ID
 * @param[in] headerInfo Information for initializing decryption of this decrypUnit
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t WVMDrmPlugin::onInitializeDecryptUnit(int uniqueId, DecryptHandle* decryptHandle,
            int decryptUnitId, const DrmBuffer* headerInfo) {
    //LOGD("WVMDrmPlugin::onInitializeDecryptUnit(): %d", uniqueId);
    if (!mDrmPluginImpl->Prepare(headerInfo->data, headerInfo->length))
        return DRM_ERROR_CANNOT_HANDLE;

    return DRM_NO_ERROR;
}

/**
 * Decrypt the protected content buffers for the given unit
 * This method will be called any number of times, based on number of
 * encrypted streams received from application.
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] decryptId Handle for the decryption session
 * @param[in] decryptUnitId ID Specifies decryption unit, such as track ID
 * @param[in] encBuffer Encrypted data block
 * @param[out] decBuffer Decrypted data block
 * @param[in] IV Optional buffer
 * @return status_t
 *     Returns the error code for this API
 *     DRM_NO_ERROR for success, and one of DRM_ERROR_UNKNOWN, DRM_ERROR_LICENSE_EXPIRED
 *     DRM_ERROR_SESSION_NOT_OPENED, DRM_ERROR_DECRYPT_UNIT_NOT_INITIALIZED,
 *     DRM_ERROR_DECRYPT for failure.
 */
status_t WVMDrmPlugin::onDecrypt(int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId,
                                 const DrmBuffer* encBuffer, DrmBuffer** decBuffer,
                                 DrmBuffer *ivBuffer)
{
    //LOGD("WVMDrmPlugin::onDecrypt\n");
#define AES_BLOCK_SIZE 16
    char iv[AES_BLOCK_SIZE];
    memcpy(iv, ivBuffer->data, sizeof(iv));

    if (*decBuffer == NULL)
        return DRM_ERROR_DECRYPT;

    (*decBuffer)->length = encBuffer->length;

    if (!mDrmPluginImpl->Operate(encBuffer->data, (*decBuffer)->data, encBuffer->length, iv)) {
        (*decBuffer)->length = 0;
        usleep(1000000);  // prevent spinning
        return DRM_ERROR_LICENSE_EXPIRED;
    }

    return DRM_NO_ERROR;
}

/**
 * Finalize decryption for the given unit of the protected content
 *
 * @param[in] uniqueId Unique identifier for a session
 * @param[in] decryptHandle Handle for the decryption session
 * @param[in] decryptUnitId ID Specifies decryption unit, such as track ID
 * @return status_t
 *     Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
 */
status_t WVMDrmPlugin::onFinalizeDecryptUnit(
            int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId) {
    //LOGD("WVMDrmPlugin::onFinalizeDecryptUnit() : %d", uniqueId);
    return DRM_NO_ERROR;
}

/**
 * The following methods are not required for the Widevine DRM plugin
 */
ssize_t WVMDrmPlugin::onPread(int uniqueId, DecryptHandle* decryptHandle,
            void* buffer, ssize_t numBytes, off64_t offset) {
    return DRM_ERROR_UNKNOWN;
}


status_t WVMDrmPlugin::onOpenConvertSession(int uniqueId, int convertId) {
    return DRM_ERROR_UNKNOWN;
}

DrmConvertedStatus* WVMDrmPlugin::onConvertData(
            int uniqueId, int convertId, const DrmBuffer* inputData) {
    return NULL;
}

DrmConvertedStatus* WVMDrmPlugin::onCloseConvertSession(int uniqueId, int convertId) {
    return NULL;
}


