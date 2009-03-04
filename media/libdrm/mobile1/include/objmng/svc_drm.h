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

#ifndef __SVC_DRM_NEW_H__
#define __SVC_DRM_NEW_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <drm_common_types.h>

/**
 * Define the mime type of DRM data.
 */
#define TYPE_DRM_MESSAGE            0x48    /**< The mime type is "application/vnd.oma.drm.message" */
#define TYPE_DRM_CONTENT            0x49    /**< The mime type is "application/vnd.oma.drm.content" */
#define TYPE_DRM_RIGHTS_XML         0x4a    /**< The mime type is "application/vnd.oma.drm.rights+xml" */
#define TYPE_DRM_RIGHTS_WBXML       0x4b    /**< The mime type is "application/vnd.oma.drm.rights+wbxml" */
#define TYPE_DRM_UNKNOWN            0xff    /**< The mime type is unknown */

/**
 * Define the delivery methods.
 */
#define FORWARD_LOCK                1       /**< Forward_lock */
#define COMBINED_DELIVERY           2       /**< Combined delivery */
#define SEPARATE_DELIVERY           3       /**< Separate delivery */
#define SEPARATE_DELIVERY_FL        4       /**< Separate delivery but DCF is forward-lock */

/**
 * Define the permissions.
 */
#define DRM_PERMISSION_PLAY         0x01    /**< Play */
#define DRM_PERMISSION_DISPLAY      0x02    /**< Display */
#define DRM_PERMISSION_EXECUTE      0x04    /**< Execute */
#define DRM_PERMISSION_PRINT        0x08    /**< Print */
#define DRM_PERMISSION_FORWARD      0x10    /**< Forward */

/**
 * Define the constraints.
 */
#define DRM_NO_CONSTRAINT           0x80    /**< Indicate have no constraint, it can use freely */
#define DRM_END_TIME_CONSTRAINT     0x08    /**< Indicate have end time constraint */
#define DRM_INTERVAL_CONSTRAINT     0x04    /**< Indicate have interval constraint */
#define DRM_COUNT_CONSTRAINT        0x02    /**< Indicate have count constraint */
#define DRM_START_TIME_CONSTRAINT   0x01    /**< Indicate have start time constraint */
#define DRM_NO_PERMISSION           0x00    /**< Indicate no rights */

/**
 * Define the return values for those interface.
 */
#define DRM_SUCCESS                 0
#define DRM_FAILURE                 -1
#define DRM_MEDIA_EOF               -2
#define DRM_RIGHTS_DATA_INVALID     -3
#define DRM_MEDIA_DATA_INVALID      -4
#define DRM_SESSION_NOT_OPENED      -5
#define DRM_NO_RIGHTS               -6
#define DRM_NOT_SD_METHOD           -7
#define DRM_RIGHTS_PENDING          -8
#define DRM_RIGHTS_EXPIRED          -9
#define DRM_UNKNOWN_DATA_LEN        -10

/**
 * The input DRM data structure, include DM, DCF, DR, DRC.
 */
typedef struct _T_DRM_Input_Data {
    /**
     * The handle of the input DRM data.
     */
    int32_t inputHandle;

    /**
     * The mime type of the DRM data, if the mime type set to unknown, DRM engine
     * will try to scan the input data to confirm the mime type, but we must say that
     * the scan and check of mime type is not strictly precise.
     */
    int32_t mimeType;

    /**
     * The function to get input data length, this function should be implement by out module,
     * and DRM engine will call-back it.
     *
     * \param inputHandle   The handle of the DRM data.
     *
     * \return
     *      -A positive integer indicate the length of input data.
     *      -0, if some error occurred.
     */
    int32_t (*getInputDataLength)(int32_t inputHandle);

    /**
     * The function to read the input data, this function should be implement by out module,
     * and DRM engine will call-back it.
     *
     * \param inputHandle   The handle of the DRM data.
     * \param buf       The buffer mallocced by DRM engine to save the data.
     * \param bufLen    The length of the buffer.
     *
     * \return
     *      -A positive integer indicate the actually length of byte has been read.
     *      -0, if some error occurred.
     *      -(-1), if reach to the end of the data.
     */
    int32_t (*readInputData)(int32_t inputHandle, uint8_t* buf, int32_t bufLen);

    /**
     * The function to seek the current file pointer, this function should be implement by out module,
     * and DRM engine will call-back it.
     *
     * \param inputHandle   The handle of the DRM data.
     * \param offset    The offset from the start position to be seek.
     *
     * \return
     *      -0, if seek operation success.
     *      -(-1), if seek operation fail.
     */
    int32_t (*seekInputData)(int32_t inputHandle, int32_t offset);
} T_DRM_Input_Data;

/**
 * The constraint structure.
 */
typedef struct _T_DRM_Constraint_Info {
    uint8_t indicator;          /**< Whether there is a right */
    uint8_t unUsed[3];
    int32_t count;              /**< The constraint of count */
    int32_t startDate;          /**< The constraint of start date */
    int32_t startTime;          /**< The constraint of start time */
    int32_t endDate;            /**< The constraint of end date */
    int32_t endTime;            /**< The constraint of end time */
    int32_t intervalDate;       /**< The constraint of interval date */
    int32_t intervalTime;       /**< The constraint of interval time */
} T_DRM_Constraint_Info;

/**
 * The rights permission and constraint information structure.
 */
typedef struct _T_DRM_Rights_Info {
    uint8_t roId[256];                     /**< The unique id for a specially rights object */
    T_DRM_Constraint_Info playRights;       /**< Constraint of play */
    T_DRM_Constraint_Info displayRights;    /**< Constraint of display */
    T_DRM_Constraint_Info executeRights;    /**< Constraint of execute */
    T_DRM_Constraint_Info printRights;      /**< Constraint of print */
} T_DRM_Rights_Info;

/**
 * The list node of the Rights information structure.
 */
typedef struct _T_DRM_Rights_Info_Node {
    T_DRM_Rights_Info roInfo;
    struct _T_DRM_Rights_Info_Node *next;
} T_DRM_Rights_Info_Node;

/**
 * Install a rights object to DRM engine, include the rights in Combined Delivery cases.
 * Because all the rights object is managed by DRM engine, so every incoming rights object
 * must be install to the engine first, or the DRM engine will not recognize it.
 *
 * \param data      The rights object data or Combined Delivery case data.
 * \param pRightsInfo   The structure to save this rights information.
 *
 * \return
 *      -DRM_SUCCESS, when install successfully.
 *      -DRM_RIGHTS_DATA_INVALID, when the input rights data is invalid.
 *      -DRM_FAILURE, when some other error occur.
 */
int32_t SVC_drm_installRights(T_DRM_Input_Data data, T_DRM_Rights_Info* pRightsInfo);

/**
 * Open a session for a special DRM object, it will parse the input DRM data, and then user
 * can try to get information for this DRM object, or try to use it if the rights is valid.
 *
 * \param data      The DRM object data, DM or DCF.
 *
 * \return
 *      -A handle for this opened DRM object session.
 *      -DRM_MEDIA_DATA_INVALID, when the input DRM object data is invalid.
 *      -DRM_FAILURE, when some other error occurred.
 */
int32_t SVC_drm_openSession(T_DRM_Input_Data data);

/**
 * Get the delivery method of the DRM object.
 *
 * \param session   The handle for this DRM object session.
 *
 * \return
 *      -The delivery method of this DRM object, include: FORWARD_LOCK, COMBINED_DELIVERY, SEPARATE_DELIVERY, SEPARATE_DELIVERY_FL.
 *      -DRM_FAILURE, when some other error occurred.
 */
int32_t SVC_drm_getDeliveryMethod(int32_t session);

/**
 * Get DRM object media object content type.
 *
 * \param session   The handle for this DRM object session.
 * \param mediaType The buffer to save the media type string, 64 bytes is enough.
 *
 * \return
 *      -DRM_SUCCESS, when get the media object content type successfully.
 *      -DRM_SESSION_NOT_OPENED, when the session is not opened or has been closed.
 *      -DRM_FAILURE, when some other error occured.
 */
int32_t SVC_drm_getContentType(int32_t session, uint8_t* mediaType);

/**
 * Check whether a specific DRM object has the specific permission rights or not.
 *
 * \param session   The handle for this DRM object session.
 * \param permission    Specify the permission to be checked.
 *
 * \return
 *      -DRM_SUCCESS, when it has the rights for the permission.
 *      -DRM_SESSION_NOT_OPENED, when the session is not opened or has been closed.
 *      -DRM_NO_RIGHTS, when it has no rights.
 *      -DRM_RIGHTS_PENDING, when it has the rights, but currently it is pending.
 *      -DRM_RIGHTS_EXPIRED, when the rights has expired.
 *      -DRM_FAILURE, when some other error occured.
 */
int32_t SVC_drm_checkRights(int32_t session, int32_t permission);

/**
 * Consume the rights when try to use the DRM object.
 *
 * \param session   The handle for this DRM object session.
 * \param permission    Specify the permission to be checked.
 *
 * \return
 *      -DRM_SUCCESS, when consume rights successfully.
 *      -DRM_SESSION_NOT_OPENED, when the session is not opened or has been closed.
 *      -DRM_NO_RIGHTS, when it has no rights.
 *      -DRM_RIGHTS_PENDING, when it has the rights, but currently it is pending.
 *      -DRM_RIGHTS_EXPIRED, when the rights has expired.
 *      -DRM_FAILURE, when some other error occured.
 */
int32_t SVC_drm_consumeRights(int32_t session, int32_t permission);

/**
 * Get DRM media object content data length.
 *
 * \param session   The handle for this DRM object session.
 *
 * \return
 *      -A positive integer indicate the length of the media object content data.
 *      -DRM_SESSION_NOT_OPENED, when the session is not opened or has been closed.
 *      -DRM_NO_RIGHTS, when the rights object is not existed.
 *      -DRM_UNKNOWN_DATA_LEN, when DRM object media data length is unknown in case of DCF has no rights.
 *      -DRM_FAILURE, when some other error occured.
 */
int32_t SVC_drm_getContentLength(int32_t session);

/**
 * Get DRM media object content data. Support get the data piece by piece if the content is too large.
 *
 * \param session   The handle for this DRM object session.
 * \param offset    The offset to start to get content.
 * \param mediaBuf  The buffer to save media object data.
 * \param mediaBufLen   The length of the buffer.
 *
 * \return
 *      -A positive integer indicate the actually length of the data has been got.
 *      -DRM_SESSION_NOT_OPENED, when the session is not opened or has been closed.
 *      -DRM_NO_RIGHTS, when the rights object is not existed.
 *      -DRM_MEDIA_EOF, when reach to the end of the media data.
 *      -DRM_FAILURE, when some other error occured.
 */
int32_t SVC_drm_getContent(int32_t session, int32_t offset, uint8_t* mediaBuf, int32_t mediaBufLen);

/**
 * Get the rights issuer address, this interface is specially for Separate Delivery method.
 *
 * \param session   The handle for this DRM object session.
 * \param rightsIssuer  The buffer to save rights issuer, 256 bytes are enough.
 *
 * \return
 *      -DRM_SUCCESS, when get the rights issuer successfully.
 *      -DRM_SESSION_NOT_OPENED, when the session is not opened or has been closed.
 *      -DRM_NOT_SD_METHOD, when it is not a Separate Delivery DRM object.
 *      -DRM_FAILURE, when some other error occured.
 */
int32_t SVC_drm_getRightsIssuer(int32_t session, uint8_t* rightsIssuer);

/**
 * Get DRM object constraint informations.
 *
 * \param session   The handle for this DRM object session.
 * \param rights    The structue to save the rights object information.
 *
 * \return
 *      -DRM_SUCCESS, when get the rights information successfully.
 *      -DRM_SESSION_NOT_OPENED, when the session is not opened or has been closed.
 *      -DRM_NO_RIGHTS, when this DRM object has not rights.
 *      -DRM_FAILURE, when some other error occured.
 */
int32_t SVC_drm_getRightsInfo(int32_t session, T_DRM_Rights_Info* rights);

/**
 * Close the opened session, after closed, the handle become invalid.
 *
 * \param session   The handle for this DRM object session.
 *
 * \return
 *      -DRM_SUCCESS, when close operation success.
 *      -DRM_SESSION_NOT_OPENED, when the session is not opened or has been closed.
 *      -DRM_FAILURE, when some other error occured.
 */
int32_t SVC_drm_closeSession(int32_t session);

/**
 * Check and update the given rights according the given permission.
 *
 * \param contentID The unique id of the rights object.
 * \param permission    The permission to be updated.
 *
 * \return
 *      -DRM_SUCCESS, when update operation success.
 *      -DRM_NO_RIGHTS, when it has no rights.
 *      -DRM_RIGHTS_PENDING, when it has the rights, but currently it is pending.
 *      -DRM_RIGHTS_EXPIRED, when the rights has expired.
 *      -DRM_FAILURE, when some other error occured.
 */
int32_t SVC_drm_updateRights(uint8_t* contentID, int32_t permission);

/**
 * Scan all the rights object in current DRM engine, and get all their information.
 *
 * \param ppRightsInfo  The pointer to the list structure to save rights info.
 *
 * \return
 *      -DRM_SUCCESS, when get information successfully.
 *      -DRM_FAILURE, when some other error occured.
 */
int32_t SVC_drm_viewAllRights(T_DRM_Rights_Info_Node **ppRightsInfo);

/**
 * Free the allocated memory when call "SVC_drm_viewAllRights".
 *
 * \param pRightsHeader The header pointer of the list to be free.
 *
 * \return
 *      -DRM_SUCCESS, when free operation successfully.
 *      -DRM_FAILURE, when some other error occured.
 */
int32_t SVC_drm_freeRightsInfoList(T_DRM_Rights_Info_Node *pRightsHeader);

/**
 * Delete a specify rights.
 *
 * \param roId      The unique id of the rights.
 *
 * \return
 *      -DRM_SUCCESS, when free operation successfully.
 *      -DRM_NO_RIGHTS, when there is not this rights object.
 *      -DRM_FAILURE, when some other error occured.
 */
int32_t SVC_drm_deleteRights(uint8_t* roId);

#ifdef __cplusplus
}
#endif

#endif /* __SVC_DRM_NEW_H__ */
