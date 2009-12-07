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

 Pathname: e_ProgConfigConst.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 enum for ProgConfig related constants

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef E_PROG_CONFIG_CONST_H
#define E_PROG_CONFIG_CONST_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

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
typedef enum
{
    /*
     * Program Configuration
     */
    Main_Profile    = 0,
    LC_Profile      = 1,

    Fs_48       = 3,
    Fs_44       = 4,
    Fs_32       = 5,

    LEN_PROFILE     = 2,
    LEN_SAMP_IDX    = 4,
    LEN_NUM_ELE     = 4,
    LEN_NUM_LFE     = 2,
    LEN_NUM_DAT     = 3,
    LEN_NUM_CCE     = 4,
    LEN_MIX_PRES    = 1,
    LEN_MMIX_IDX    = 2,
    LEN_PSUR_ENAB   = 1,
    LEN_ELE_IS_CPE  = 1,
    LEN_IND_SW_CCE  = 1,
    LEN_COMMENT_BYTES   = 8

} eProgConfigConst;

/*----------------------------------------------------------------------------
; STRUCTURES TYPEDEF'S
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


