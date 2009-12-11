/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
/**
This file contains application function interfaces to the AVC decoder library
and necessary type defitionitions and enumerations.
@publishedAll
*/

#ifndef _AVCDEC_API_H_
#define _AVCDEC_API_H_

#include "avcapi_common.h"

/**
 This enumeration is used for the status returned from the library interface.
*/
typedef enum
{
    /**
    The followings are fail with details. Their values are negative.
    */
    AVCDEC_NO_DATA = -4,
    AVCDEC_PACKET_LOSS = -3,
    /**
    Fail information
    */
    AVCDEC_NO_BUFFER = -2, /* no output picture buffer available */
    AVCDEC_MEMORY_FAIL = -1, /* memory allocation failed */
    AVCDEC_FAIL = 0,
    /**
    Generic success value
    */
    AVCDEC_SUCCESS = 1,
    AVCDEC_PICTURE_OUTPUT_READY = 2,
    AVCDEC_PICTURE_READY = 3,

    /**
    The followings are success with warnings. Their values are positive integers.
    */
    AVCDEC_NO_NEXT_SC = 4,
    AVCDEC_REDUNDANT_FRAME = 5,
    AVCDEC_CONCEALED_FRAME = 6  /* detect and conceal the error */
} AVCDec_Status;


/**
This structure contains sequence parameters information.
*/
typedef struct tagAVCDecSPSInfo
{
    int FrameWidth;
    int FrameHeight;
    uint frame_only_flag;
    int  frame_crop_left;
    int  frame_crop_right;
    int  frame_crop_top;
    int  frame_crop_bottom;

} AVCDecSPSInfo;


#ifdef __cplusplus
extern "C"
{
#endif
    /** THE FOLLOWINGS ARE APIS */
    /**
    This function parses one NAL unit from byte stream format input according to Annex B.
    \param "bitstream"  "Pointer to the bitstream buffer."
    \param "nal_unit"   "Point to pointer and the location of the start of the first NAL unit
                         found in bitstream."
    \param "size"       "As input, the pointer to the size of bitstream in bytes. As output,
                         the value is changed to be the size of the found NAL unit."
    \return "AVCDEC_SUCCESS if success, AVCDEC_FAIL if no first start code is found, AVCDEC_NO_NEX_SC if
            the first start code is found, but the second start code is missing (potential partial NAL)."
    */
    OSCL_IMPORT_REF AVCDec_Status PVAVCAnnexBGetNALUnit(uint8 *bitstream, uint8 **nal_unit, int *size);

    /**
    This function sniffs the nal_unit_type such that users can call corresponding APIs.
    \param "bitstream"  "Pointer to the beginning of a NAL unit (start with forbidden_zero_bit, etc.)."
    \param "size"       "size of the bitstream (NumBytesInNALunit + 1)."
    \param "nal_unit_type" "Pointer to the return value of nal unit type."
    \return "AVCDEC_SUCCESS if success, AVCDEC_FAIL otherwise."
    */
    OSCL_IMPORT_REF AVCDec_Status PVAVCDecGetNALType(uint8 *bitstream, int size, int *nal_type, int *nal_ref_idc);

    /**
    This function decodes the sequence parameters set, initializes related parameters and
    allocates memory (reference frames list), must also be compliant with Annex A.
    It is equivalent to decode VOL header of MPEG4.
    \param "avcHandle"  "Handle to the AVC decoder library object."
    \param "nal_unit"   "Pointer to the buffer containing single NAL unit.
                        The content will change due to EBSP-to-RBSP conversion."
    \param "nal_size"       "size of the bitstream NumBytesInNALunit."
    \return "AVCDEC_SUCCESS if success,
            AVCDEC_FAIL if profile and level is not supported,
            AVCDEC_MEMORY_FAIL if memory allocations return null."
    */
    OSCL_IMPORT_REF AVCDec_Status PVAVCDecSeqParamSet(AVCHandle *avcHandle, uint8 *nal_unit, int nal_size);

    /**
    This function returns sequence parameters such as dimension and field flag of the most recently
    decoded SPS. More can be added later or grouped together into a structure. This API can be called
    after PVAVCInitSequence. If no sequence parameter has been decoded yet, it will return AVCDEC_FAIL.

    \param "avcHandle"  "Handle to the AVC decoder library object."
    \param "seqInfo"    "Pointer to the AVCDecSeqParamInfo structure."
    \return "AVCDEC_SUCCESS if success and AVCDEC_FAIL if fail."
    \note "This API can be combined with PVAVCInitSequence if wanted to be consistent with m4vdec lib."
    */
    OSCL_IMPORT_REF AVCDec_Status PVAVCDecGetSeqInfo(AVCHandle *avcHandle, AVCDecSPSInfo *seqInfo);

    /**
    This function decodes the picture parameters set and initializes related parameters. Note thate
    the PPS may not be present for every picture.
    \param "avcHandle"  "Handle to the AVC decoder library object."
    \param "nal_unit"   "Pointer to the buffer containing single NAL unit.
                        The content will change due to EBSP-to-RBSP conversion."
    \param "nal_size"       "size of the bitstream NumBytesInNALunit."
    \return "AVCDEC_SUCCESS if success, AVCDEC_FAIL if profile and level is not supported."
    */
    OSCL_IMPORT_REF AVCDec_Status PVAVCDecPicParamSet(AVCHandle *avcHandle, uint8 *nal_unit, int nal_size);

    /**
    This function decodes one NAL unit of bitstream. The type of nal unit is one of the
    followings, 1, 5. (for now, no data partitioning, type 2,3,4).
    \param "avcHandle"  "Handle to the AVC decoder library object."
    \param "nal_unit"   "Pointer to the buffer containing a single or partial NAL unit.
                        The content will change due to EBSP-to-RBSP conversion."
    \param "buf_size"   "Size of the buffer (less than or equal nal_size)."
    \param "nal_size"   "size of the current NAL unit NumBytesInNALunit."
    \return "AVCDEC_PICTURE_READY for success and an output is ready,
            AVCDEC_SUCCESS for success but no output is ready,
            AVCDEC_PACKET_LOSS is GetData returns AVCDEC_PACKET_LOSS,
            AVCDEC_FAIL if syntax error is detected,
            AVCDEC_MEMORY_FAIL if memory is corrupted.
            AVCDEC_NO_PICTURE if no frame memory to write to (users need to get output and/or return picture).
            AVCDEC_REDUNDANT_PICTURE if error has been detected in the primary picture and redundant picture is available,
            AVCDEC_CONCEALED_PICTURE if error has been detected and decoder has concealed it."
    */
    OSCL_IMPORT_REF AVCDec_Status PVAVCDecSEI(AVCHandle *avcHandle, uint8 *nal_unit, int nal_size);

    OSCL_IMPORT_REF AVCDec_Status PVAVCDecodeSlice(AVCHandle *avcHandle, uint8 *buffer, int buf_size);

    /**
    Check the availability of the decoded picture in decoding order (frame_num).
    The AVCFrameIO also provide displaying order information such that the application
    can re-order the frame for display. A picture can be retrieved only once.
    \param "avcHandle"  "Handle to the AVC decoder library object."
    \param "output"      "Pointer to the AVCOutput structure. Note that decoder library will
                        not re-used the pixel memory in this structure until it has been returned
                        thru PVAVCReleaseOutput API."
    \return "AVCDEC_SUCCESS for success, AVCDEC_FAIL if no picture is available to be displayed,
            AVCDEC_PICTURE_READY if there is another picture to be displayed."
    */
    OSCL_IMPORT_REF AVCDec_Status PVAVCDecGetOutput(AVCHandle *avcHandle, int *indx, int *release_flag, AVCFrameIO *output);

    /**
    This function resets the decoder and expects to see the next IDR slice.
    \param "avcHandle"  "Handle to the AVC decoder library object."
    */
    OSCL_IMPORT_REF void    PVAVCDecReset(AVCHandle *avcHandle);

    /**
    This function performs clean up operation including memory deallocation.
    \param "avcHandle"  "Handle to the AVC decoder library object."
    */
    OSCL_IMPORT_REF void    PVAVCCleanUpDecoder(AVCHandle *avcHandle);
//AVCDec_Status EBSPtoRBSP(uint8 *nal_unit,int *size);



    /** CALLBACK FUNCTION TO BE IMPLEMENTED BY APPLICATION */
    /** In AVCHandle structure, userData is a pointer to an object with the following
        member functions.
    */
    AVCDec_Status CBAVCDec_GetData(uint32 *userData, unsigned char **buffer, unsigned int *size);

#ifdef __cplusplus
}
#endif

#endif /* _AVCDEC_API_H_ */

