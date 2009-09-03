/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef __DRM_RIGHTS_MANAGER_H__
#define __DRM_RIGHTS_MANAGER_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <openssl/aes.h>
#include <drm_common_types.h>
#include <parser_rel.h>

#ifdef DRM_DEVICE_ARCH_ARM
#define ANDROID_DRM_CORE_PATH   "/data/drm/rights/"
#define DRM_UID_FILE_PATH       "/data/drm/rights/uid.txt"
#else
#define ANDROID_DRM_CORE_PATH   "/home/user/golf/esmertec/device/out/debug/host/linux-x86/product/sim/data/data/com.android.drm.mobile1/"
#define DRM_UID_FILE_PATH       "/home/user/golf/esmertec/device/out/debug/host/linux-x86/product/sim/data/data/com.android.drm.mobile1/uid.txt"
#endif

#define EXTENSION_NAME_INFO     ".info"

#define GET_ID      1
#define GET_UID     2

#define GET_ROAMOUNT        1
#define GET_ALL_RO          2
#define SAVE_ALL_RO         3
#define GET_A_RO            4
#define SAVE_A_RO           5

/**
 * Get the id or uid from the "uid.txt" file.
 *
 * \param Uid       The content id for a specially DRM object.
 * \param id        The id number managed by DRM engine for a specially DRM object.
 * \param option    The option to get id or uid, the value includes: GET_ID, GET_UID.
 *
 * \return
 *      -TRUE, if the operation successfully.
 *      -FALSE, if the operation failed.
 */
int32_t drm_readFromUidTxt(uint8_t* Uid, int32_t* id, int32_t option);

/**
 * Save or read the rights information on the "id.info" file.
 *
 * \param id        The id number managed by DRM engine for a specially DRM object.
 * \param Ro        The rights structure to save the rights information.
 * \param RoAmount  The number of rights for this DRM object.
 * \param option    The option include: GET_ROAMOUNT, GET_ALL_RO, SAVE_ALL_RO, GET_A_RO, SAVE_A_RO.
 *
 * \return
 *      -TRUE, if the operation successfully.
 *      -FALSE, if the operation failed.
 */
int32_t drm_writeOrReadInfo(int32_t id, T_DRM_Rights* Ro, int32_t* RoAmount, int32_t option);

/**
 * Append a rights information to DRM engine storage.
 *
 * \param Ro        The rights structure to save the rights information.
 *
 * return
 *      -TRUE, if the operation successfully.
 *      -FALSE, if the operation failed.
 */
int32_t drm_appendRightsInfo(T_DRM_Rights* rights);

/**
 * Get the mex id number from the "uid.txt" file.
 *
 * \return
 *      -an integer to indicate the max id number.
 *      -(-1), if the operation failed.
 */
int32_t drm_getMaxIdFromUidTxt();

/**
 * Remove the "id.info" file if all the rights for this DRM object has been deleted.
 *
 * \param id        The id number managed by DRM engine for a specially DRM object.
 *
 * \return
 *      -TRUE, if the operation successfully.
 *      -FALSE, if the operation failed.
 */
int32_t drm_removeIdInfoFile(int32_t id);

/**
 * Update the "uid.txt" file when delete the rights object.
 *
 * \param id        The id number managed by DRM engine for a specially DRM object.
 *
 * \return
 *      -TRUE, if the operation successfully.
 *      -FALSE, if the operation failed.
 */
int32_t drm_updateUidTxtWhenDelete(int32_t id);

/**
 * Get the CEK according the given content id.
 *
 * \param uid       The content id for a specially DRM object.
 * \param KeyValue  The buffer to save the CEK.
 *
 * \return
 *      -TRUE, if the operation successfully.
 *      -FALSE, if the operation failed.
 */
int32_t drm_getKey(uint8_t* uid, uint8_t* KeyValue);

/**
 * Discard the padding bytes in DCF decrypted data.
 *
 * \param decryptedBuf      The aes decrypted data buffer to be scanned.
 * \param decryptedBufLen   The length of the buffer. And save the output result.
 *
 * \return
 *      -0
 */
void drm_discardPaddingByte(uint8_t *decryptedBuf, int32_t *decryptedBufLen);

/**
 * Decrypt the media data according the CEK.
 *
 * \param Buffer    The buffer to decrypted and also used to save the output data.
 * \param BufferLen The length of the buffer data and also save the output data length.
 * \param key       The structure of the CEK.
 *
 * \return
 *      -0
 */
int32_t drm_aesDecBuffer(uint8_t * Buffer, int32_t * BufferLen, AES_KEY *key);

/**
 * Update the DCF data length according the CEK.
 *
 * \param pDcfLastData  The last several byte for the DCF.
 * \param keyValue  The CEK of the DRM content.
 * \param moreBytes Output the more bytes for discarded.
 *
 * \return
 *      -TRUE, if the operation successfully.
 *      -FALSE, if the operation failed.
 */
int32_t drm_updateDcfDataLen(uint8_t* pDcfLastData, uint8_t* keyValue, int32_t* moreBytes);

/**
 * Check and update the rights for a specially DRM content.
 *
 * \param id        The id number managed by DRM engine for a specially DRM object.
 * \param permission    The permission to be check and updated.
 *
 * \return
 *      -DRM_SUCCESS, if there is a valid rights and update it successfully.
 *      -DRM_NO_RIGHTS, if there is no rights for this content.
 *      -DRM_RIGHTS_PENDING, if the rights is pending.
 *      -DRM_RIGHTS_EXPIRED, if the rights has expired.
 *      -DRM_RIGHTS_FAILURE, if there is some other error occur.
 */
int32_t drm_checkRoAndUpdate(int32_t id, int32_t permission);

#ifdef __cplusplus
}
#endif

#endif /* __DRM_RIGHTS_MANAGER_H__ */
