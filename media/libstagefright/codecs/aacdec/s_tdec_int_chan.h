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
/*

 Pathname: s_tDec_Int_Chan.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Change data types of win

 Description: Remove wnd_shape structure.

 Description: Remove dependency on window_block.h, Fix header too.

 Description:
 Modified to utilize memory in the last 1024 elements in fxpCoef.

 Description:
 (1) Modified to include the lines...

    #ifdef __cplusplus
    extern "C" {
    #endif

    #ifdef __cplusplus
    }
    #endif

 (2) Updated the copyright header.

 Description:
 (1) Move temporary FrameInfo structure into the shared region with fxpCoef.
 (2) Add more comments detailing the size of the shared structure.

 Description:
 (1) Changed time_quant from 2048 Int32 buffer to 1024 Int32 buffer.

 Who:                                         Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This include file defines the structure, tDec_Int_Chan

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef S_TDEC_INT_CHAN_H
#define S_TDEC_INT_CHAN_H

#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; INCLUDES
    ----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "e_rawbitstreamconst.h"
#include "s_tns_frame_info.h"
#include "s_wnd_shape.h"
#include "s_lt_pred_status.h"
#include "s_sectinfo.h"
#include "s_frameinfo.h"
#include "e_window_shape.h"
#include "e_window_sequence.h"
#include "window_block_fxp.h"

    /*----------------------------------------------------------------------------
    ; MACROS
    ; Define module specific macros here
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; DEFINES
    ; Include all pre-processor statements here.
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; EXTERNAL VARIABLES REFERENCES
    ; Declare variables used in this module but defined elsewhere
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; SIMPLE TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; ENUMERATED TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /* This structure was created with the specific goal in mind of sharing memory
     * with the last 1024 data elements in fxpCoef.
     *
     * The size of this structure must NOT exceed 4 kilobytes
     * Also, the size of the fxpCoef array cannot be less than 8 kilobytes
     *
     * The fxpCoef array is declared as an Int32, so its size should not vary
     * from platform to platform.
     *
     * The shared structure is 3,640 bytes (3.55 KB), on a 32-bit platform,
     * which represents the worst case.
     */
    typedef struct
    {
        TNS_frame_info       tns;

        FrameInfo            frameInfo;

        Int                  factors[MAXBANDS];
        Int                  cb_map[MAXBANDS];
        Int                  group[NSHORT];
        Int                  qFormat[MAXBANDS];

        Int                  max_sfb;
        LT_PRED_STATUS       lt_status;

    } per_chan_share_w_fxpCoef;

    /*
     * This structure contains one per channel.
     */
    typedef struct
    {
#ifdef AAC_PLUS
        Int16                ltp_buffer[LT_BLEN + 2*288]; /* LT_BLEN  = 2048 + 2*288 */
#else
        Int16                ltp_buffer[LT_BLEN]; /* LT_BLEN  = 2048 */
#endif


        Int32                time_quant[LONG_WINDOW]; /*  1024 holds overlap&add */

        Int32                *fxpCoef;         /* Spectrum coeff.*/

        per_chan_share_w_fxpCoef * pShareWfxpCoef;

        Int32                abs_max_per_window[NUM_SHORT_WINDOWS];

        WINDOW_SEQUENCE      wnd;


        WINDOW_SHAPE         wnd_shape_prev_bk;
        WINDOW_SHAPE         wnd_shape_this_bk;

    } tDec_Int_Chan;

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

#ifdef __cplusplus
}
#endif

#endif /* S_TDEC_INT_CHAN_H */

