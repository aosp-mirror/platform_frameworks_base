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

 Pathname: LT_PRED_STATUS.h


------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
 (1) Merged in #defines from ltp_common.h, thereby eliminating that file.

 Description:
 (1) Modified to include the lines...

    #ifdef __cplusplus
    extern "C" {
    #endif

    #ifdef __cplusplus
    }
    #endif

 (2) Updated the copyright header.

 Description: Moved large ltp_buffer up to s_tDec_Int_Chan.h, since this
 move allowed for other elements in this structure to be shared with
 the fxpCoef array.

 Description: Decreased size of LTP buffers from 4096 to 3072.  The upper 1024
 elements in the LTP buffers were never touched in the code.  This saves
 4 kilobytes in memory.

 Description: Decreased size of LTP buffers again from 3072 to 2048.  This
 time, I realized that 1024 elements were duplicated in the 32-bit array
 pVars->pChVars[]->time_quant.  Rather than copy this data EVERY frame
 from a 32-bit to a 16-bit LTP buffer, the data is accessed only
 when it is needed.  This saves both MIPS and memory.

 Who:                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 define LT_PRED_STATUS structure for pulse data decoding.

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef S_LT_PRED_STATUS_H
#define S_LT_PRED_STATUS_H

#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; INCLUDES
    ----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "e_blockswitching.h"

    /*----------------------------------------------------------------------------
    ; MACROS
    ; Define module specific macros here
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; DEFINES
    ; Include all pre-processor statements here.
    ----------------------------------------------------------------------------*/
    /*
      Macro:    MAX_SHORT_WINDOWS
      Purpose:  Number of short windows in one long window.
      Explanation:  -  */
#ifndef MAX_SHORT_WINDOWS
#define MAX_SHORT_WINDOWS NSHORT
#endif

    /*
      Macro:    MAX_SCFAC_BANDS
      Purpose:  Maximum number of scalefactor bands in one frame.
      Explanation:  -  */
#ifndef MAX_SCFAC_BANDS
#define MAX_SCFAC_BANDS MAXBANDS
#endif

    /*
      Macro:    BLOCK_LEN_LONG
      Purpose:  Length of one long window
      Explanation:  -  */
#ifndef BLOCK_LEN_LONG
#define BLOCK_LEN_LONG LN2
#endif


    /*
      Macro:    LTP_MAX_BLOCK_LEN_LONG
      Purpose:  Informs the routine of the maximum block size used.
      Explanation:  This is needed since the TwinVQ long window
            is different from the AAC long window.  */
#define LTP_MAX_BLOCK_LEN_LONG BLOCK_LEN_LONG //(2 * BLOCK_LEN_LONG) 

    /*
      Macro:    LT_BLEN
      Purpose:  Length of the history buffer.
      Explanation:  Has to hold 2 long windows of time domain data.  */
#ifndef LT_BLEN
#define LT_BLEN (2 * LTP_MAX_BLOCK_LEN_LONG)
#endif

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
    /*
      Type:     LT_PRED_STATUS
      Purpose:  Type of the struct holding the LTP encoding parameters.
      Explanation:  -  */
    typedef struct
    {
        Int weight_index;
        Int win_prediction_used[MAX_SHORT_WINDOWS];
        Int sfb_prediction_used[MAX_SCFAC_BANDS];
        Bool ltp_data_present;

        Int delay[MAX_SHORT_WINDOWS];
    }
    LT_PRED_STATUS;

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

#ifdef __cplusplus
}
#endif

#endif /* S_LT_PRED_STATUS_H */


