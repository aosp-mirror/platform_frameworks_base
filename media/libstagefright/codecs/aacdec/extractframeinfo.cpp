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

  Filename: extractframeInfo.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Arguments:   hBitBuf      - bitbuffer handle
              v_frame_info - pointer to memorylocation where the frame-info will
                             be stored.

 Return:     none.


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

Extracts a frame_info vector from control data read from the bitstream.

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

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

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#ifdef AAC_PLUS


#include    "extractframeinfo.h"
#include    "buf_getbits.h"
#include    "aac_mem_funcs.h"


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/


/*
 *  (int) ceil (log (bs_num_env + 1) / log (2))
 *  ceil(log([0:5]+1)/log(2))
 */

const Int32 bs_pointer_bits_tbl[MAX_ENVELOPES + 1] = { 0, 1, 2, 2, 3, 3};

/*
 *  (int)((float)numTimeSlots/bs_num_env + 0.5f)
 *  floor(16./[0:5] + 0.5)
 */

const Int32 T_16_ov_bs_num_env_tbl[MAX_ENVELOPES + 1] = { 2147483647, 16, 8,
        5,  4, 3
                                                        };


/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/


SBR_ERROR extractFrameInfo(BIT_BUFFER     * hBitBuf,
                           SBR_FRAME_DATA * h_frame_data)
{

    Int32 absBordLead = 0;
    Int32 nRelLead = 0;
    Int32 nRelTrail = 0;
    Int32 bs_num_env = 0;
    Int32 bs_num_rel = 0;
    Int32 bs_var_bord = 0;
    Int32 bs_var_bord_0 = 0;
    Int32 bs_var_bord_1 = 0;
    Int32 bs_pointer = 0;
    Int32 bs_pointer_bits;
    Int32 frameClass;
    Int32 temp;
    Int32 env;
    Int32 k;
    Int32 bs_num_rel_0 = 0;
    Int32 bs_num_rel_1 = 0;
    Int32 absBordTrail = 0;
    Int32 middleBorder = 0;
    Int32 bs_num_noise;
    Int32 lA = 0;

    Int32 tE[MAX_ENVELOPES + 1];
    Int32 tQ[2 + 1];
    Int32 f[MAX_ENVELOPES + 1];
    Int32 bs_rel_bord[3];
    Int32 bs_rel_bord_0[3];
    Int32 bs_rel_bord_1[3];
    Int32 relBordLead[3];
    Int32 relBordTrail[3];


    Int32 *v_frame_info = h_frame_data->frameInfo;

    SBR_ERROR err =  SBRDEC_OK;


    /*
     * First read from the bitstream.
     */

    /* Read frame class */
    h_frame_data->frameClass = frameClass = buf_getbits(hBitBuf, SBR_CLA_BITS);


    switch (frameClass)
    {

        case FIXFIX:
            temp = buf_getbits(hBitBuf, SBR_ENV_BITS);   /* 2 bits */

            bs_num_env = 1 << temp;


            f[0] = buf_getbits(hBitBuf, SBR_RES_BITS);   /* 1 bit */

            for (env = 1; env < bs_num_env; env++)
            {
                f[env] = f[0];
            }

            nRelLead     = bs_num_env - 1;
            absBordTrail  = 16;


            break;

        case FIXVAR:
            bs_var_bord = buf_getbits(hBitBuf, SBR_ABS_BITS);   /* 2 bits */
            bs_num_rel  = buf_getbits(hBitBuf, SBR_NUM_BITS);   /* 2 bits */
            bs_num_env  = bs_num_rel + 1;

            for (k = 0; k < bs_num_env - 1; k++)
            {
                bs_rel_bord[k] = (buf_getbits(hBitBuf, SBR_REL_BITS) + 1) << 1;
            }

            bs_pointer_bits = bs_pointer_bits_tbl[bs_num_env];

            bs_pointer = buf_getbits(hBitBuf, bs_pointer_bits);

            for (env = 0; env < bs_num_env; env++)
            {                                                    /* 1 bit */
                f[bs_num_env - 1 - env] = buf_getbits(hBitBuf, SBR_RES_BITS);
            }

            absBordTrail  = 16 + bs_var_bord;
            nRelTrail     = bs_num_rel;

            break;

        case VARFIX:
            bs_var_bord = buf_getbits(hBitBuf, SBR_ABS_BITS);   /* 2 bits */
            bs_num_rel  = buf_getbits(hBitBuf, SBR_NUM_BITS);   /* 2 bits */
            bs_num_env  = bs_num_rel + 1;

            for (k = 0; k < bs_num_env - 1; k++)
            {
                bs_rel_bord[k] = (buf_getbits(hBitBuf, SBR_REL_BITS) + 1) << 1;
            }

            bs_pointer_bits = bs_pointer_bits_tbl[bs_num_env];

            bs_pointer = buf_getbits(hBitBuf, bs_pointer_bits);

            for (env = 0; env < bs_num_env; env++)
            {                                  /* 1 bit */
                f[env] = buf_getbits(hBitBuf, SBR_RES_BITS);
            }

            absBordTrail = 16;
            absBordLead  = bs_var_bord;
            nRelLead     = bs_num_rel;

            break;

        case VARVAR:
            bs_var_bord_0 = buf_getbits(hBitBuf, SBR_ABS_BITS);   /* 2 bits */
            bs_var_bord_1 = buf_getbits(hBitBuf, SBR_ABS_BITS);
            bs_num_rel_0  = buf_getbits(hBitBuf, SBR_NUM_BITS);   /* 2 bits */
            bs_num_rel_1  = buf_getbits(hBitBuf, SBR_NUM_BITS);

            bs_num_env = bs_num_rel_0 + bs_num_rel_1 + 1;

            for (k = 0; k < bs_num_rel_0; k++)
            {                                                 /* 2 bits */
                bs_rel_bord_0[k] = (buf_getbits(hBitBuf, SBR_REL_BITS) + 1) << 1;
            }

            for (k = 0; k < bs_num_rel_1; k++)
            {                                                 /* 2 bits */
                bs_rel_bord_1[k] = (buf_getbits(hBitBuf, SBR_REL_BITS) + 1) << 1;
            }


            bs_pointer_bits = bs_pointer_bits_tbl[bs_num_env];

            bs_pointer = buf_getbits(hBitBuf, bs_pointer_bits);

            for (env = 0; env < bs_num_env; env++)
            {                                  /* 1 bit */
                f[env] = buf_getbits(hBitBuf, SBR_RES_BITS);
            }

            absBordLead   = bs_var_bord_0;
            absBordTrail  = 16 + bs_var_bord_1;
            nRelLead      = bs_num_rel_0;
            nRelTrail     = bs_num_rel_1;

            break;

    };


    /*
     * Calculate the framing.
     */


    switch (frameClass)
    {
        case FIXFIX:
            for (k = 0; k < nRelLead; k++)
            {
                relBordLead[k] = T_16_ov_bs_num_env_tbl[bs_num_env];
            }
            break;
        case VARFIX:
            for (k = 0; k < nRelLead; k++)
            {
                relBordLead[k] = bs_rel_bord[k];
            }
            break;
        case VARVAR:
            for (k = 0; k < nRelLead; k++)
            {
                relBordLead[k] = bs_rel_bord_0[k];
            }
            for (k = 0; k < nRelTrail; k++)
            {
                relBordTrail[k] = bs_rel_bord_1[k];
            }
            break;
        case FIXVAR:
            for (k = 0; k < nRelTrail; k++)
            {
                relBordTrail[k] = bs_rel_bord[k];
            }
            break;
    }


    tE[0]          = absBordLead;
    tE[bs_num_env] = absBordTrail;

    for (env = 1; env <= nRelLead; env++)
    {
        tE[env] = absBordLead;
        for (k = 0; k <= env - 1; k++)
        {
            tE[env] += relBordLead[k];
        }
    }

    for (env = nRelLead + 1; env < bs_num_env; env++)
    {
        tE[env] = absBordTrail;
        for (k = 0; k <= bs_num_env - env - 1; k++)
        {
            tE[env] -= relBordTrail[k];
        }
    }



    switch (frameClass)
    {
        case  FIXFIX:
            middleBorder = bs_num_env >> 1;
            break;
        case VARFIX:
            switch (bs_pointer)
            {
                case 0:
                    middleBorder = 1;
                    break;
                case 1:
                    middleBorder = bs_num_env - 1;
                    break;
                default:
                    middleBorder = bs_pointer - 1;
                    break;
            };
            break;
        case FIXVAR:
        case VARVAR:
            switch (bs_pointer)
            {
                case 0:
                case 1:
                    middleBorder = bs_num_env - 1;
                    break;
                default:
                    middleBorder = bs_num_env + 1 - bs_pointer;
                    break;
            };
            break;
    };


    tQ[0] = tE[0];
    if (bs_num_env > 1)
    {
        tQ[1] = tE[middleBorder];
        tQ[2] = tE[bs_num_env];
        bs_num_noise = 2;
    }
    else
    {
        tQ[1] = tE[bs_num_env];
        bs_num_noise = 1;
    }

    /*
     *  Check consistency on freq bands
     */

    if ((tE[bs_num_env] < tE[0]) || (tE[0] < 0))
    {
        err = SBRDEC_INVALID_BITSTREAM;
    }


    switch (frameClass)
    {
        case  FIXFIX:
            lA = -1;
            break;
        case VARFIX:
            switch (bs_pointer)
            {
                case 0:
                case 1:
                    lA = -1;
                    break;
                default:
                    lA = bs_pointer - 1;
                    break;
            };
            break;
        case FIXVAR:
        case VARVAR:
            switch (bs_pointer)
            {
                case 0:
                    lA = - 1;
                    break;
                default:
                    lA = bs_num_env + 1 - bs_pointer;
                    break;
            };
            break;
    };

    /*
     * Build the frameInfo vector...
     */

    v_frame_info[0] = bs_num_env;   /* Number of envelopes*/
    pv_memcpy(v_frame_info + 1, tE, (bs_num_env + 1)*sizeof(Int32));    /* time borders*/
    /* frequency resolution */
    pv_memcpy(v_frame_info + 1 + bs_num_env + 1, f, bs_num_env*sizeof(Int32));

    temp = (1 + bs_num_env) << 1;
    v_frame_info[temp] = lA;                     /* transient envelope*/
    v_frame_info[temp + 1] = bs_num_noise;       /* Number of noise envelopes */
    /* noise borders */
    pv_memcpy(v_frame_info + temp + 2, tQ, (bs_num_noise + 1)*sizeof(Int32));


    return (err);

}






#endif

