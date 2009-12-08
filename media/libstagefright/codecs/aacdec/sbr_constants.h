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

 Filename: sbr_constants.h
 Funtions:


------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:
------------------------------------------------------------------------------
SC 29 Software Copyright Licencing Disclaimer:

This software module was originally developed by
  Coding Technologies

and edited by
  -

in the course of development of the ISO/IEC 13818-7 and ISO/IEC 14496-3
standards for reference purposes and its performance may not have been
optimized. This software module is an implementation of one or more tools as
specified by the ISO/IEC 13818-7 and ISO/IEC 14496-3 standards.
ISO/IEC gives users free license to this software module or modifications
thereof for use in products claiming conformance to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International
Standards. ISO/IEC gives users the same free license to this software module or
modifications thereof for research purposes and further ISO/IEC standardisation.
Those intending to use this software module in products are advised that its
use may infringe existing patents. ISO/IEC have no liability for use of this
software module or modifications thereof. Copyright is not released for
products that do not conform to audiovisual and image-coding related ITU
Recommendations and/or ISO/IEC International Standards.
The original developer retains full right to modify and use the code for its
own purpose, assign or donate the code to a third party and to inhibit third
parties from using the code for products that do not conform to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International Standards.
This copyright notice must be included in all copies or derivative works.
Copyright (c) ISO/IEC 2002.

*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef SBR_CONSTANTS_H
#define SBR_CONSTANTS_H

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


#define SBR_AMP_RES_1_5         0
#define SBR_AMP_RES_3_0         1

#define MAX_NOISE_ENVELOPES     2
#define MAX_NOISE_COEFFS        5
#define MAX_NUM_NOISE_VALUES     (MAX_NOISE_ENVELOPES * MAX_NOISE_COEFFS)

#define MAX_ENVELOPES           5
#define MAX_FREQ_COEFFS         58
#define MAX_NUM_ENVELOPE_VALUES (MAX_ENVELOPES * MAX_FREQ_COEFFS)

#define MAX_NUM_CHANNELS            2
#define NOISE_FLOOR_OFFSET          6
#define NOISE_FLOOR_OFFSET_PLUS_1   7

#define LOW_RES                     0
#define HIGH_RES                    1

#define LO                          0
#define HI                          1

#define TIME                        1
#define FREQ                        0

#define LENGTH_FRAME_INFO           35

#define SI_SBR_CRC_BITS             10

#define SBR_FREQ_SCALE_DEFAULT      2
#define SBR_ALTER_SCALE_DEFAULT     1
#define SBR_NOISE_BANDS_DEFAULT     2

#define SBR_LIMITER_BANDS_DEFAULT      2
#define SBR_LIMITER_GAINS_DEFAULT      2
#define SBR_INTERPOL_FREQ_DEFAULT      1
#define SBR_SMOOTHING_LENGTH_DEFAULT   1

/* header */
#define SI_SBR_AMP_RES_BITS            1
#define SI_SBR_START_FREQ_BITS         4
#define SI_SBR_STOP_FREQ_BITS          4
#define SI_SBR_XOVER_BAND_BITS         3
#define SI_SBR_RESERVED_BITS_HDR       2
#define SI_SBR_DATA_EXTRA_BITS         1
#define SI_SBR_HEADER_EXTRA_1_BITS     1
#define SI_SBR_HEADER_EXTRA_2_BITS     1

#define SI_SBR_FREQ_SCALE_BITS         2
#define SI_SBR_ALTER_SCALE_BITS        1
#define SI_SBR_NOISE_BANDS_BITS        2

#define SI_SBR_LIMITER_BANDS_BITS      2
#define SI_SBR_LIMITER_GAINS_BITS      2
#define SI_SBR_INTERPOL_FREQ_BITS      1
#define SI_SBR_SMOOTHING_LENGTH_BITS   1


/* data */
#define SI_SBR_RESERVED_PRESENT        1
#define SI_SBR_RESERVED_BITS_DATA      4

#define SI_SBR_COUPLING_BITS           1

#define SI_SBR_INVF_MODE_BITS          2

#define SI_SBR_EXTENDED_DATA_BITS      1
#define SI_SBR_EXTENSION_SIZE_BITS     4
#define SI_SBR_EXTENSION_ESC_COUNT_BITS   8
#define SI_SBR_EXTENSION_ID_BITS          2

#define SI_SBR_NOISE_MODE_BITS         1
#define SI_SBR_DOMAIN_BITS             1

#define SI_SBR_START_ENV_BITS_AMP_RES_3_0           6
#define SI_SBR_START_ENV_BITS_BALANCE_AMP_RES_3_0   5
#define SI_SBR_START_NOISE_BITS_AMP_RES_3_0         5
#define SI_SBR_START_NOISE_BITS_BALANCE_AMP_RES_3_0 5

#define SI_SBR_START_ENV_BITS_AMP_RES_1_5           7
#define SI_SBR_START_ENV_BITS_BALANCE_AMP_RES_1_5   6


#define SBR_CLA_BITS  2
#define SBR_ABS_BITS  2
#define SBR_RES_BITS  1
#define SBR_REL_BITS  2
#define SBR_ENV_BITS  2
#define SBR_NUM_BITS  2


#define FIXFIX  0
#define FIXVAR  1
#define VARFIX  2
#define VARVAR  3


#define    LEN_EX_TYPE  (4)
#define    LEN_NIBBLE   (4)


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

/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif


