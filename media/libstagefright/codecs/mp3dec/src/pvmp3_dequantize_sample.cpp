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
------------------------------------------------------------------------------

   PacketVideo Corp.
   MP3 Decoder Library

   Filename: pvmp3_dequantize_sample.cpp

   Functions:
      power_1_third
      pvmp3_dequantize_sample

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

power_1_third
int32 power_1_third( int32 xx)

Input
    int32           xx,                     int32 in the [0, 8192] range

 Returns

    int32           xx^(1/3)                int32 Q26 number representing
                                            the 1/3 power of the input

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

pvmp3_dequantize_sample

Input
    int32 is[SUBBANDS_NUMBER*FILTERBANK_BANDS],
    mp3ScaleFactors *scalefac,                 scale factor structure
    struct gr_info_s *gr_info,                 granule structure informatiom
    mp3Header *info                            mp3 header info

 Returns

    int32 is[SUBBANDS_NUMBER*FILTERBANK_BANDS], dequantize output as (.)^(4/3)

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    dequantize sample

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

 [1] ISO MPEG Audio Subgroup Software Simulation Group (1996)
     ISO 13818-3 MPEG-2 Audio Decoder - Lower Sampling Frequency Extension

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pv_mp3dec_fxd_op.h"
#include "pvmp3_dec_defs.h"
#include "pvmp3_dequantize_sample.h"
#include "pvmp3_normalize.h"
#include "mp3_mem_funcs.h"
#include "pvmp3_tables.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define Q30_fmt(a)(int32(double(0x40000000)*a))
#define Q29_fmt(a)(int32(double(0x20000000)*a))

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/
const int32 pretab[22] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 3, 2, 0};

const int32 pow_2_1_fourth[4] =
{
    Q30_fmt(1.0),                Q30_fmt(1.18920711500272),
    Q30_fmt(1.41421356237310),   Q30_fmt(1.68179283050743)
};

const int32 two_cubic_roots[7] =
{
    Q29_fmt(0),                  Q29_fmt(1.25992104989487),
    Q29_fmt(1.58740105196820),   Q29_fmt(2.00000000000000),
    Q29_fmt(2.51984209978975),   Q29_fmt(3.17480210393640),
    Q29_fmt(3.99999999999999)
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


int32 power_1_third(int32 xx)
{

    if (xx <= 512)
    {
        return (power_one_third[xx] >> 1);
    }
    else
    {
        if (xx >> 15)
        {
            return 0x7FFFFFFF;  /* saturate any value over 32767 */
        }
        else
        {
            int32 x = xx;
            int32 m = 22 - pvmp3_normalize(xx);

            xx >>= m;
            xx = (power_one_third[xx]) + (((power_one_third[xx+1] - power_one_third[xx]) >> m) * (x & ((1 << m) - 1)));
            return (fxp_mul32_Q30(xx, two_cubic_roots[m]));
        }

    }
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/



void pvmp3_dequantize_sample(int32 is[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                             mp3ScaleFactors *scalefac,
                             granuleInfo *gr_info,
                             int32  used_freq_lines,
                             mp3Header *info)
{
    int32 ss;
    int32 cb = 0;
    int32 global_gain;
    int32 sfreq = info->sampling_frequency + info->version_x + (info->version_x << 1);

    /* apply formula per block type */

    if (gr_info->window_switching_flag && (gr_info->block_type == 2))
    {
        int32 next_cb_boundary;
        int32 cb_begin = 0;
        int32 cb_width = 0;
        int32 mixstart = 8;                                       /* added 2003/08/21  efs */

        if (info->version_x != MPEG_1)
        {
            mixstart = 6;                                   /* different value in MPEG2 LSF */
        }

        if (gr_info->mixed_block_flag)
        {
            next_cb_boundary = mp3_sfBandIndex[sfreq].l[1];  /* LONG blocks: 0,1,3 */
        }
        else
        {
            next_cb_boundary = mp3_sfBandIndex[sfreq].s[1] * 3; /* pure SHORT block */
            cb_width = 0;
        }

        global_gain =  gr_info->global_gain;
        int32 two_raise_one_fourth = pow_2_1_fourth[global_gain&0x3];
        global_gain = 12 + (global_gain >> 2);

        for (ss = 0 ; ss < used_freq_lines ; ss++)
        {
            if (ss == next_cb_boundary)
            {
                cb++;       /*  critical band counter */
                if (gr_info->mixed_block_flag)
                {
                    if (next_cb_boundary == mp3_sfBandIndex[sfreq].l[mixstart])
                    {
                        next_cb_boundary = mp3_sfBandIndex[sfreq].s[4] * 3;

                        cb_begin = mp3_sfBandIndex[sfreq].s[3] * 3;
                        cb_width = 3;
                        cb = 3;
                    }
                    else if (ss < mp3_sfBandIndex[sfreq].l[mixstart])
                    {
                        next_cb_boundary = mp3_sfBandIndex[sfreq].l[cb+1];
                    }
                    else
                    {
                        next_cb_boundary = mp3_sfBandIndex[sfreq].s[cb+1] * 3;

                        cb_width = cb;
                        cb_begin = mp3_sfBandIndex[sfreq].s[cb] * 3;
                    }

                    if (ss < 2*FILTERBANK_BANDS)
                    {   /*  1st 2 subbands of switched blocks */
                        global_gain  = (gr_info->global_gain);
                        global_gain -= (1 + gr_info->scalefac_scale) *
                                       (scalefac->l[cb] + gr_info->preflag * pretab[cb]) << 1;

                        two_raise_one_fourth = pow_2_1_fourth[global_gain&0x3];
                        global_gain = 12 + (global_gain >> 2);
                    }
                }
                else
                {
                    next_cb_boundary = mp3_sfBandIndex[sfreq].s[cb+1] * 3;
                    cb_width = cb;
                    cb_begin = mp3_sfBandIndex[sfreq].s[cb] * 3;
                }

            }   /*  end-if ( ss == next_cb_boundary) */

            /* Do long/short dependent scaling operations. */
            if ((gr_info->mixed_block_flag == 0) || (gr_info->mixed_block_flag && (ss >= 2*FILTERBANK_BANDS)))
            {
                int32 temp2 = fxp_mul32_Q32((ss - cb_begin) << 16, mp3_shortwindBandWidths[sfreq][cb_width]);
                temp2 = (temp2 + 1) >> 15;

                global_gain  = (gr_info->global_gain);
                global_gain -=  gr_info->subblock_gain[temp2] << 3;
                global_gain -= (1 + gr_info->scalefac_scale) * (scalefac->s[temp2][cb] << 1);

                two_raise_one_fourth = pow_2_1_fourth[global_gain&0x3];
                global_gain = 12 + (global_gain >> 2);

            }


            /*
             *       xr[sb][ss] = 2^(global_gain/4)
             */

            /* Scale quantized value. */

            /* 0 < abs(is[ss]) < 8192 */

            int32 tmp = fxp_mul32_Q30((is[ss] << 16), power_1_third(pv_abs(is[ ss])));

            tmp = fxp_mul32_Q30(tmp, two_raise_one_fourth);

            if (global_gain < 0)
            {
                int32 temp = - global_gain;
                if (temp < 32)
                {
                    is[ss] = (tmp >> temp);
                }
                else
                {
                    is[ss] = 0;
                }
            }
            else
            {
                is[ss] = (tmp << global_gain);
            }

        }  /*   for (ss=0 ; ss < used_freq_lines ; ss++)   */

    }
    else
    {

        for (cb = 0 ; cb < 22 ; cb++)
        {

            /* Compute overall (global) scaling. */

            global_gain  = (gr_info->global_gain);

            global_gain -= (1 + gr_info->scalefac_scale) *
                           (scalefac->l[cb] + gr_info->preflag * pretab[cb]) << 1;


            int32 two_raise_one_fourth = pow_2_1_fourth[global_gain&0x3];
            global_gain = 12 + (global_gain >> 2);

            /*
             *       xr[sb][ss] = 2^(global_gain/4)
             */

            /* Scale quantized value. */

            if (used_freq_lines >= mp3_sfBandIndex[sfreq].l[cb+1])
            {
                if (global_gain <= 0)
                {
                    global_gain = - global_gain;
                    if (global_gain < 32)
                    {
                        for (ss = mp3_sfBandIndex[sfreq].l[cb]; ss < mp3_sfBandIndex[sfreq].l[cb+1]; ss += 2)
                        {
                            int32 tmp =  is[ss];
                            if (tmp)
                            {
                                tmp = fxp_mul32_Q30((tmp << 16), power_1_third(pv_abs(tmp)));
                                is[ss] = fxp_mul32_Q30(tmp, two_raise_one_fourth) >> global_gain;
                            }
                            tmp =  is[ss+1];
                            if (tmp)
                            {
                                tmp = fxp_mul32_Q30((tmp << 16), power_1_third(pv_abs(tmp)));
                                is[ss+1] = fxp_mul32_Q30(tmp, two_raise_one_fourth) >> global_gain;
                            }
                        }
                    }
                    else
                    {
                        pv_memset(&is[ mp3_sfBandIndex[sfreq].l[cb]],
                                  0,
                                  (mp3_sfBandIndex[sfreq].l[cb+1] - mp3_sfBandIndex[sfreq].l[cb])*sizeof(*is));
                    }
                }
                else
                {
                    for (ss = mp3_sfBandIndex[sfreq].l[cb]; ss < mp3_sfBandIndex[sfreq].l[cb+1]; ss += 2)
                    {
                        int32 tmp =  is[ss];
                        if (tmp)
                        {
                            tmp = fxp_mul32_Q30((tmp << 16), power_1_third(pv_abs(tmp)));
                            is[ss] = fxp_mul32_Q30(tmp, two_raise_one_fourth) << global_gain;
                        }

                        tmp =  is[ss+1];
                        if (tmp)
                        {
                            tmp = fxp_mul32_Q30((tmp << 16), power_1_third(pv_abs(tmp)));
                            is[ss+1] = fxp_mul32_Q30(tmp, two_raise_one_fourth) << global_gain;
                        }
                    }
                }
            }
            else
            {
                if (global_gain <= 0)
                {
                    global_gain = - global_gain;
                    if (global_gain < 32)
                    {
                        for (ss = mp3_sfBandIndex[sfreq].l[cb]; ss < used_freq_lines; ss += 2)
                        {
                            int32 tmp =  is[ss];
                            if (tmp)
                            {
                                tmp = fxp_mul32_Q30((tmp << 16), power_1_third(pv_abs(tmp)));
                                is[ss] = fxp_mul32_Q30(tmp, two_raise_one_fourth) >> global_gain;
                            }
                            tmp =  is[ss+1];
                            if (tmp)
                            {
                                tmp = fxp_mul32_Q30((tmp << 16), power_1_third(pv_abs(tmp)));
                                is[ss+1] = fxp_mul32_Q30(tmp, two_raise_one_fourth) >> global_gain;
                            }
                        }

                    }
                    else
                    {
                        pv_memset(&is[ mp3_sfBandIndex[sfreq].l[cb]],
                                  0,
                                  (mp3_sfBandIndex[sfreq].l[cb+1] - mp3_sfBandIndex[sfreq].l[cb])*sizeof(*is));
                    }
                }
                else
                {
                    for (ss = mp3_sfBandIndex[sfreq].l[cb]; ss < used_freq_lines; ss++)
                    {
                        int32 tmp =  is[ss];

                        if (tmp)
                        {
                            tmp = fxp_mul32_Q30((tmp << 16), power_1_third(pv_abs(tmp)));
                            is[ss] = fxp_mul32_Q30(tmp, two_raise_one_fourth) << global_gain;
                        }
                    }
                }

                cb = 22;  // force breaking out of the loop

            } /*  if ( used_freq_lines >= mp3_sfBandIndex[sfreq].l[cb+1]) */

        }   /* for (cb=0 ; cb < 22 ; cb++)  */

    }   /*  if (gr_info->window_switching_flag && (gr_info->block_type == 2))  */


    pv_memset(&is[used_freq_lines],
              0,
              (FILTERBANK_BANDS*SUBBANDS_NUMBER - used_freq_lines)*sizeof(*is));

}

