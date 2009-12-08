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

   Filename: pvmp3_stereo_proc.cpp

   Functions:

    pvmp3_st_mid_side
    pvmp3_st_intensity
    pvmp3_stereo_proc

------------------------------------------------------------------------------

pvmp3_st_mid_side

 INPUT AND OUTPUT DEFINITIONS

Input

   int32 xr[],      input channel
   int32 xl[],
   int32 Start,     Location of first element where stereo intensity is applied
   int32 Number     number of elements affected

 Returns

   int32 xl[],      generated stereo channel


------------------------------------------------------------------------------

pvmp3_st_intensity

 INPUT AND OUTPUT DEFINITIONS

Input

   int32 xr[],      input channel
   int32 xl[],
   int32 is_pos,    index to table is_ratio_factor[]
   int32 Start,     Location of first element where stereo intensity is applied
   int32 Number     number of elements affected

 Returns

   int32 xl[],      generated stereo channel


------------------------------------------------------------------------------

pvmp3_stereo_proc

 INPUT AND OUTPUT DEFINITIONS

Input

   int32 xr[],                    input channel
   int32 xl[],
   mp3ScaleFactors  *scalefac,    scale factors structure
   struct gr_info_s *gr_info,     granule structure
   mp3Header *info                mp3 header info
 Returns

   int32 xl[],      generated stereo channel


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    stereo processing for mpeg1 layer III
    After requantization, the reconstructed values are processed for ms_stereo
    or intensity_stereo modes or both, before passing them to the synthesis
    filterbank

    In ms_stereo mode the values of the normalized middle/side channels
    M[l] and S[l] are transmitted instead of the left/right channel values
    L[l] and R[l]. From here, L[l] and R[l] are reconstructed

    Intensity_stereo is done by specifying the magnitude (via the
    scalefactors of the left channel) and a stereo position is_pos[sfb],
    which is transmitted instead of scalefactors of the right channel.
    The stereo position is used to derive the left and right channel signals

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

#include "pvmp3_stereo_proc.h"
#include "pv_mp3dec_fxd_op.h"
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
#define N31 31

#define Q31_fmt(a)    (int32(double(0x7FFFFFFF)*a))

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/
/*
 *  TmpFac= tan(is_pos * (PI /12));
 *
 *  TmpFac /= (1 + TmpFac);
 *
 */

const int32  is_ratio_factor[8] = {0,
                                   Q31_fmt(0.21132486540519),   Q31_fmt(0.36602540378444),   Q31_fmt(0.50000000000000),
                                   Q31_fmt(0.63397459621556),   Q31_fmt(0.78867513459481),   Q31_fmt(1.00000000000000),
                                   0
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

void pvmp3_st_mid_side(int32 xr[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                       int32 xl[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                       int32 Start,
                       int32 Number)
{

    int32 *pt_xr  = &xr[Start];
    int32 *pt_xl  = &xl[Start];

    for (int32 i = Number >> 1; i != 0; i--)
    {
        int32 xxr = *(pt_xr) << 1;
        int32 xxl = *(pt_xl) << 1;
        *(pt_xr++)  = fxp_mul32_Q32((xxr + xxl), Q31_fmt(0.70710678118655));   /* Sum */
        *(pt_xl++)  = fxp_mul32_Q32((xxr - xxl), Q31_fmt(0.70710678118655));   /* Diff */
        xxr = *(pt_xr) << 1;
        xxl = *(pt_xl) << 1;
        *(pt_xr++)  = fxp_mul32_Q32((xxr + xxl), Q31_fmt(0.70710678118655));   /* Sum */
        *(pt_xl++)  = fxp_mul32_Q32((xxr - xxl), Q31_fmt(0.70710678118655));   /* Diff */
    }


    if (Number&1)
    {
        int32 xxr = *(pt_xr) << 1;
        int32 xxl = *(pt_xl) << 1;
        *(pt_xr)  = fxp_mul32_Q32((xxr + xxl), Q31_fmt(0.70710678118655));   /* Sum */
        *(pt_xl)  = fxp_mul32_Q32((xxr - xxl), Q31_fmt(0.70710678118655));   /* Diff */
    }

}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

void pvmp3_st_intensity(int32 xr[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                        int32 xl[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                        int32 is_pos,
                        int32 Start,
                        int32 Number)
{

    int32 TmpFac = is_ratio_factor[ is_pos & 7];

    int32 *pt_xr  = &xr[Start];
    int32 *pt_xl  = &xl[Start];

    for (int32 i = Number >> 1; i != 0; i--)
    {
        int32 tmp = fxp_mul32_Q32((*pt_xr) << 1, TmpFac);
        *(pt_xl++) = (*pt_xr) - tmp;
        *(pt_xr++) = tmp;
        tmp = fxp_mul32_Q32((*pt_xr) << 1, TmpFac);
        *(pt_xl++) = (*pt_xr) - tmp;
        *(pt_xr++) = tmp;
    }

    if (Number&1)
    {
        int32 tmp = fxp_mul32_Q32((*pt_xr) << 1, TmpFac);
        *(pt_xl) = (*pt_xr) - tmp;
        *(pt_xr) = tmp;
    }

}

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
void pvmp3_stereo_proc(int32 xr[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                       int32 xl[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                       mp3ScaleFactors *scalefac,
                       granuleInfo *gr_info,
                       int32 used_freq_lines,
                       mp3Header *info)
{


    int32 sb;
    int32 ss;
    int32 sfbNo;
    int32 sfbStart;

    int32 sfb;
    int32 sfbTemp;
    int32 i;
    int32 j;


    int32 i_stereo  = (info->mode == MPG_MD_JOINT_STEREO) &&
                      (info->mode_ext & 0x1);

    int32 ms_stereo = (info->mode == MPG_MD_JOINT_STEREO) &&
                      (info->mode_ext & 0x2);

    int32 sfreq  = info->version_x + (info->version_x << 1);
    sfreq += info->sampling_frequency;




    if (i_stereo)
    {
        if (gr_info->window_switching_flag && (gr_info->block_type == 2))
        {
            if (gr_info->mixed_block_flag)
            {
                /*
                 * mixed blocks processing
                 */
                i = 31;
                ss = 17;
                sb = 0;
                while (i >= 0)
                {
                    if (xl[(i*FILTERBANK_BANDS) + ss])
                    {
                        sb = (i << 4) + (i << 1) + ss;
                        i = -1;
                    }
                    else
                    {
                        ss--;
                        if (ss < 0)
                        {
                            i--;
                            ss = 17;
                        }
                    }
                }

                if (sb < 36)
                {
                    /*
                     * mixed blocks processing: intensity bound inside long blocks
                     */
                    /* 1. long blocks up to intensity border: not intensity */

                    if (mp3_sfBandIndex[sfreq].l[4] <= sb)
                    {
                        sfb = 4;
                    }
                    else
                    {
                        sfb = 0;
                    }

                    while (mp3_sfBandIndex[sfreq].l[sfb] < sb)
                    {
                        sfb++;
                    }

                    /* from that sfb on intensity stereo */
                    sfbTemp = sfb;  /* save for later use */

                    sfbStart = mp3_sfBandIndex[sfreq].l[sfb];

                    /* from 0 up to sfbStart do ms_stereo or normal stereo */

                    if (ms_stereo)
                    {
                        pvmp3_st_mid_side(xr, xl, 0, sfbStart);
                    }

                    /* 2. long blocks from intensity border up to sfb band 8: intensity */
                    /* calc. is_ratio */


                    /* Start of intensity stereo of remaining sfc bands: */
                    for (; sfbTemp < 8; sfbTemp++)
                    {
                        sfbStart = mp3_sfBandIndex[sfreq].l[sfbTemp];  /* = Start in 0 ... 575 */
                        sfbNo = mp3_sfBandIndex[sfreq].l[sfbTemp+1] - mp3_sfBandIndex[sfreq].l[sfbTemp]; /* No of lines to process */

                        if (scalefac->l[sfbTemp] != 7)
                        {
                            pvmp3_st_intensity(xr, xl, scalefac->l[sfbTemp], sfbStart, sfbNo);
                        }
                        else if (ms_stereo)
                        {
                            pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                        }

                    }  /* for (; sfbTemp < 8; sfbTemp++) */

                    for (j = 0; j < 3; j++)
                    {
                        /* 3. short blocks from sfbcnt to last sfb do intensity stereo */
                        for (sfbTemp = 3; sfbTemp < 13; sfbTemp++)
                        {
                            sfbNo = mp3_sfBandIndex[sfreq].s[sfbTemp+1] - mp3_sfBandIndex[sfreq].s[sfbTemp]; /* No of lines to process */
                            sfbStart = 3 * mp3_sfBandIndex[sfreq].s[sfbTemp] + j * sfbNo;

                            if (scalefac->s[j][sfbTemp] != 7)
                            {
                                pvmp3_st_intensity(xr, xl, scalefac->s[j][sfbTemp], sfbStart, sfbNo);
                            }
                            else if (ms_stereo)
                            {
                                pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                            }

                        }  /* for (; sfbTemp < 22; sfbTemp++) */
                    } /* for (j = 0; j < 3; j++) */
                }
                else   /* else for (sb >= 36) */
                {
                    /*
                     * mixed blocks processing: intensity bound outside long blocks
                     */


                    /*
                     * 2. short blocks from sfb band 3 up to intensity border: normal stereo, ms stereo and intensity
                     */
                    for (j = 0; j < 3; j++)
                    {
                        int32 sfbcnt;
                        sfbcnt = -1;

                        for (sfb = 12; sfb >= 3; sfb--)
                        {
                            int32 lines;
                            lines = mp3_sfBandIndex[sfreq].s[sfb+1] - mp3_sfBandIndex[sfreq].s[sfb];
                            i = 3 * mp3_sfBandIndex[sfreq].s[sfb] + (j + 1) * lines - 1;

                            while (lines > 0)
                            {
                                if (xl[i])
                                {
                                    sfbcnt = sfb;
                                    sfb = -10;
                                    lines = -10;
                                }
                                lines--;
                                i--;
                            }
                        }

                        sfbcnt += 1;
                        if (sfbcnt < 3)
                        {
                            sfbcnt = 3;
                        }

                        sfbTemp = sfbcnt;        /* for later use */


                        /*
                         *   do normal stereo or MS stereo from sfb 3 to < sfbcnt:
                         */
                        for (sb = 3; sb < sfbcnt; sb++)
                        {
                            sfbNo = mp3_sfBandIndex[sfreq].s[sb+1] - mp3_sfBandIndex[sfreq].s[sb];
                            sfbStart = 3 * mp3_sfBandIndex[sfreq].s[sb] + j * sfbNo;

                            if (ms_stereo)
                            {
                                pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                            }

                        }

                        /* from sfbcnt to last sfb do intensity stereo */
                        for (; sfbTemp < 13; sfbTemp++)
                        {
                            sfbNo = mp3_sfBandIndex[sfreq].s[sfbTemp+1] - mp3_sfBandIndex[sfreq].s[sfbTemp]; /* No of lines to process */
                            sfbStart = 3 * mp3_sfBandIndex[sfreq].s[sfbTemp] + j * sfbNo;

                            if (scalefac->s[j][sfbTemp] != 7)
                            {
                                pvmp3_st_intensity(xr, xl, scalefac->s[j][sfbTemp], sfbStart, sfbNo);
                            }
                            else if (ms_stereo)
                            {
                                pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                            }

                        }  /* for (; sfbTemp < 22; sfbTemp++) */

                    } /* for (j = 0; j < 3; j++) */

                    /* 1. long blocks up to sfb band 8: not intensity */
                    /* from 0 to sfb 8 ms_stereo or normal stereo */

                    sfbStart = mp3_sfBandIndex[sfreq].l[8];

                    if (ms_stereo)
                    {
                        pvmp3_st_mid_side(xr, xl, 0, sfbStart);
                    }

                }
            }  /* if (gr_info->mixed_block_flag) */
            else
            {
                /*
                 * short block processing
                 */
                for (j = 0; j < 3; j++)
                {
                    int32 sfbcnt = -1;

                    for (sfb = 12; sfb >= 0; sfb--)
                    {
                        int32 lines = mp3_sfBandIndex[sfreq].s[sfb+1] - mp3_sfBandIndex[sfreq].s[sfb];
                        i = 3 * mp3_sfBandIndex[sfreq].s[sfb] + (j + 1) * lines - 1;

                        while (lines > 0)
                        {
                            if (xl[i])
                            {
                                sfbcnt = sfb;
                                sfb = -10;
                                lines = -10;
                            }
                            lines--;
                            i--;
                        }
                    }

                    sfbcnt += 1;
                    sfbTemp = sfbcnt;        /* for later use */

                    /* do normal stereo or MS stereo from 0 to sfbcnt */
                    for (sb = 0; sb < sfbcnt; sb++)
                    {
                        sfbNo = mp3_sfBandIndex[sfreq].s[sb+1] - mp3_sfBandIndex[sfreq].s[sb];
                        sfbStart = 3 * mp3_sfBandIndex[sfreq].s[sb] + j * sfbNo;

                        if (ms_stereo)
                        {
                            pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                        }
                    }


                    /* from sfbcnt to last sfb do intensity stereo */
                    for (; sfbTemp < 13; sfbTemp++)
                    {
                        sfbNo = mp3_sfBandIndex[sfreq].s[sfbTemp+1] - mp3_sfBandIndex[sfreq].s[sfbTemp]; /* No of lines to process */
                        sfbStart = 3 * mp3_sfBandIndex[sfreq].s[sfbTemp] + j * sfbNo;

                        if (scalefac->s[j][sfbTemp] != 7)
                        {
                            pvmp3_st_intensity(xr, xl, scalefac->s[j][sfbTemp], sfbStart, sfbNo);
                        }
                        else if (ms_stereo)
                        {
                            pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                        }

                    }  /* for (; sfbTemp < 22; sfbTemp++) */

                } /* for (j = 0; j < 3; j++) */

            } /* if( gr_info->mixed_block_flag) */



        }  /* if (gr_info->window_switching_flag && (gr_info->block_type == 2)) */
        else
        {
            /*
             *   long block processing
             */
            i = 31;
            ss = 17;
            sb = 0;

            while (i >= 0)
            {
                if (xl[(i*FILTERBANK_BANDS) + ss] != 0)
                {
                    sb = (i << 4) + (i << 1) + ss;
                    i = -2;
                }
                else
                {
                    ss--;
                    if (ss < 0)
                    {
                        i--;
                        ss = 17;
                    }
                }
            }

            if (sb)
            {
                if (mp3_sfBandIndex[sfreq].l[14] <= sb)
                {
                    sfb = 14;
                }
                else if (mp3_sfBandIndex[sfreq].l[7] <= sb)
                {
                    sfb = 7;
                }
                else
                {
                    sfb = 0;
                }


                while (mp3_sfBandIndex[sfreq].l[sfb] <= sb)
                {
                    sfb++;
                }
            }
            else
            {
                if (i == -1)
                {
                    /*  all xr[1][][] are 0: set IS bound sfb to 0  */
                    sfb = 0;
                }
                else
                {
                    /*  xr[1][0][0] is unequal 0 and all others are 0: set IS bound sfb to 1 */
                    sfb = 1;
                }
            }

            sfbTemp = sfb;  /* save for later use */


            sfbStart = mp3_sfBandIndex[sfreq].l[sfb];

            /* from 0 to sfbStart ms_stereo or normal stereo */
            if (ms_stereo)
            {
                pvmp3_st_mid_side(xr, xl, 0, sfbStart);
            }

            /* now intensity stereo of the remaining sfb's: */
            for (; sfb < 21; sfb++)
            {
                sfbStart = mp3_sfBandIndex[sfreq].l[sfb];
                sfbNo = mp3_sfBandIndex[sfreq].l[sfb+1] - mp3_sfBandIndex[sfreq].l[sfb]; /* No of lines to process */

                if (scalefac->l[sfb] != 7)
                {
                    pvmp3_st_intensity(xr, xl, scalefac->l[sfb], sfbStart, sfbNo);
                }
                else if (ms_stereo)
                {
                    pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                }

            }  /* for (; sfbTemp < 22; sfbTemp++) */



            sfbStart = mp3_sfBandIndex[sfreq].l[21];
            sfbNo = mp3_sfBandIndex[sfreq].l[22] - mp3_sfBandIndex[sfreq].l[21]; /* No of lines to process */

            if (scalefac->l[21] != 7)
            {
                if (sfbTemp < 21)
                {
                    sfbTemp = scalefac->l[20];
                }
                else
                {
                    sfbTemp = 0;  /* if scalefac[20] is not an intensity position, is_pos = 0 */
                }

                pvmp3_st_intensity(xr, xl, sfbTemp, sfbStart, sfbNo);
            }
            else if (ms_stereo)
            {
                pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
            }

        }  /* if (gr_info->window_switching_flag && (gr_info->block_type == 2)) */


    }  /* if (i_stereo)  */
    else
    {
        /*
         * normal or ms stereo processing
         */
        if (ms_stereo)
        {

            pvmp3_st_mid_side(xr, xl, 0, used_freq_lines);

        }

    } /* if (i_stereo) */

}

