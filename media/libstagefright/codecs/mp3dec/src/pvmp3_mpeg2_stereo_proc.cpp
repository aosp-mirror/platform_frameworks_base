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

   Filename: pvmp3_mpeg2_stereo_proc.cpp

   Functions:

     pvmp3_st_intensity_ver2
     pvmp3_mpeg2_stereo_proc

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:


------------------------------------------------------------------------------

pvmp3_st_intensity_ver2

 INPUT AND OUTPUT DEFINITIONS

Input

   int32 xr[],      input channel
   int32 xl[],
   int32 m,         selecting index: io = 2(1/4) (m=0), io = 2(1/8) (m=1)
   int32 is_pos,    index on table  is_pos_pow_eitgh_root_of_2
   int32 Start,     Location of first element where stereo intensity is applied
   int32 Number     number of elements affected

 Returns

   int32 xl[],      generated stereo channel




------------------------------------------------------------------------------

pvmp3_mpeg2_stereo_proc

 INPUT AND OUTPUT DEFINITIONS

Input

   int32 xr[],                     input channel
   int32 xl[],
   mp3ScaleFactors *scalefac,      scale factors structure for Right channel
   granuleInfo *gr_info_l,         granule structure for the left channel
   granuleInfo *gr_info_r,         granule structure for the rigth channel
   uint32 *scalefac_IIP_buffer,    auxiliary scale factor vector
   mp3Header *info                 mp3 header info
 Returns

   int32 xl[],      generated stereo channel


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    stereo processing for mpeg2 layer III LSF extension

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

#include "pvmp3_mpeg2_stereo_proc.h"
#include "pvmp3_stereo_proc.h"
#include "pv_mp3dec_fxd_op.h"
#include "pvmp3_tables.h"
#include "mp3_mem_funcs.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

#define Q31_fmt(a)    (int32(double(0x7FFFFFFF)*a))

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/
const int32 is_pos_pow_eitgh_root_of_2[8] =
{
    /*   --- 2^(1/8) ----- */
    Q31_fmt(1.00000000000000),   Q31_fmt(0.91700404320467),   Q31_fmt(0.84089641525371),
    Q31_fmt(0.77110541270397),   Q31_fmt(0.70710678118655),   Q31_fmt(0.64841977732550),
    Q31_fmt(0.59460355750136),   Q31_fmt(0.54525386633263)
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

void pvmp3_st_intensity_ver2(int32 xr[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                             int32 xl[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                             int32 m,
                             int32 is_pos,
                             int32 Start,
                             int32 Number)
{
    int32 k[2];

    /* pow(io, ((is_pos + 1)>>1)); io = 2(1/4) (m=0), io = 2(1/8) (m=1) */
    k[0] = is_pos_pow_eitgh_root_of_2[((is_pos+1)&(3+(m<<2)))<<(1-m)] >> ((is_pos + 1) >> (2 + m));
    /* pow(io, (is_pos>>1)); io = 2(1/4) (m=0), io = 2(1/8) (m=1)  */
    k[1] = is_pos_pow_eitgh_root_of_2[(is_pos&(3+(m<<2)))<<(1-m)] >> (is_pos >> (2 + m));


    int32 *pt_xr  = &xr[Start];
    int32 *pt_xl  = &xl[Start];

    if (is_pos == 0)    /* 0 < is_pos < 31 */
    {
        pv_memcpy(pt_xl, pt_xr, Number*sizeof(*pt_xr));
    }
    else if (is_pos & 1)
    {
        for (int32 i = Number >> 1; i != 0; i--)
        {
            *(pt_xl++) = (*pt_xr);
            *(pt_xr) = fxp_mul32_Q32((*pt_xr) << 1, k[0]);
            pt_xr++;
            *(pt_xl++) = (*pt_xr);
            *(pt_xr) = fxp_mul32_Q32((*pt_xr) << 1, k[0]);
            pt_xr++;
        }
        if (Number&1)
        {
            *(pt_xl) = (*pt_xr);
            *(pt_xr) = fxp_mul32_Q32((*pt_xr) << 1, k[0]);
        }
    }
    else
    {
        for (int32 i = Number >> 1; i != 0; i--)
        {
            *(pt_xl++) = fxp_mul32_Q32((*(pt_xr++)) << 1, k[1]);
            *(pt_xl++) = fxp_mul32_Q32((*(pt_xr++)) << 1, k[1]);
        }
        if (Number&1)
        {
            *(pt_xl) = fxp_mul32_Q32((*pt_xr) << 1, k[1]);
        }
    }

}



/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
void pvmp3_mpeg2_stereo_proc(int32 xr[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                             int32 xl[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                             mp3ScaleFactors *scalefac_R,
                             granuleInfo *gr_info_l,
                             granuleInfo *gr_info_r,
                             uint32 *scalefac_IIP_buffer,
                             int32 used_freq_lines,
                             mp3Header *info)
{

    int32 sfreq;
    int32 sb;
    int32 ss;
    int32 sfbNo;
    int32 sfbStart;
    int32 sfb;
    int32 sfbTemp;
    int32 i;
    int32 j;
    int32 io;


    int32 i_stereo  = (info->mode == MPG_MD_JOINT_STEREO) &&
                      (info->mode_ext & 0x1);

    int32 ms_stereo = (info->mode == MPG_MD_JOINT_STEREO) &&
                      (info->mode_ext & 0x2);


    if (i_stereo)
    {
        if (gr_info_r->scalefac_compress & 1)
        {
            io = 0;  /* 2^(-1/4) */
        }
        else
        {
            io = 1;  /* 2^(-1/8) */
        }

        sfreq =  info->version_x + (info->version_x << 1);
        sfreq += info->sampling_frequency;

        if (gr_info_l->window_switching_flag && (gr_info_l->block_type == 2))
        {
            if (gr_info_l->mixed_block_flag)
            {
                /*
                 * mixed blocks processing
                 */
                i = 31;
                ss = 17;
                sb = -1;

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
                }   /* now sb is the number of highest line with value != 0      */
                /* can be between -1 (all lines zero) and 575 (no line zero) */

                if (sb < 36)    /*  was (sb <= 36)  */
                {
                    /*
                     *  mixed blocks processing: intensity bound inside long blocks
                     */
                    /* 1. long blocks up to intensity border: Stereo or M/S */
                    if (mp3_sfBandIndex[sfreq].l[4] <= sb)
                    {
                        i = 4;
                    }
                    else
                    {
                        i = 0;
                    }

                    while (mp3_sfBandIndex[sfreq].l[i] <= sb)
                    {
                        i++;
                    }
                    sfbTemp = i;  /* from that (long) sfb on we have intensity stereo */

                    sfbNo = mp3_sfBandIndex[sfreq].l[sfbTemp]; /* number of lines to process */

                    /* from sfbStart up sfbNo lines do ms_stereo or normal stereo */
                    if (ms_stereo)
                    {
                        pvmp3_st_mid_side(xr, xl, 0, sfbNo);
                    }

                    /* 2. long blocks from intensity border up to sfb band 6: intensity */
                    /* calc. MPEG_1_2_Factor[0], MPEG_1_2_Factor[1] */

                    for (sfb = sfbTemp; sfb < 6; sfb++)
                    {
                        sfbStart = mp3_sfBandIndex[sfreq].l[sfb];  /* = Start in 0 ... 575 */
                        sfbNo = mp3_sfBandIndex[sfreq].l[sfb+1] - mp3_sfBandIndex[sfreq].l[sfb]; /* No of lines to process */

                        if ((uint32)(scalefac_R->l[sfb]) != scalefac_IIP_buffer[sfb])
                        {
                            pvmp3_st_intensity_ver2(xr, xl, io, scalefac_R->l[sfb], sfbStart, sfbNo);
                        }
                        else if (ms_stereo)
                        {
                            pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                        }
                    }

                    /* 3. now process all sfb with short blocks (3...12), all in intensity mode */

                    for (j = 0; j < 3; j++)
                    {
                        /*   first calculate directional factors for intensity stereo,
                         *   for all sfb in intensity mode, but only
                         *   if they do not have "illegal" position:
                         */
                        /* to do this for all sfb we have to get information for last scale factor band:
                         * here we clearly have more than one sfb in intensity mode,
                         *  so copy factors and legal/illegal information from sfb11 to sfb12
                         */
                        (scalefac_R->s[j][12]) = (scalefac_R->s[j][11]);
                        scalefac_IIP_buffer[36 + j] = scalefac_IIP_buffer[33 + j];  /* legal/illegal in sfb 12 same as in sfb 11 */

                        for (sfb = 3; sfb < 13; sfb++)
                        {
                            sfbNo = mp3_sfBandIndex[sfreq].s[sfb+1] - mp3_sfBandIndex[sfreq].s[sfb]; /* No of lines to process */
                            sfbStart = 3 * mp3_sfBandIndex[sfreq].s[sfb] + j * sfbNo;

                            if ((uint32)(scalefac_R->s[j][sfb]) != scalefac_IIP_buffer[3*sfb + j])
                            {
                                pvmp3_st_intensity_ver2(xr, xl, io, scalefac_R->s[j][sfb], sfbStart, sfbNo);
                            }
                            else if (ms_stereo)
                            {
                                pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                            }
                        }
                    } /* for (j = 0; j < 3; j++) */
                }
                else  /*  else then (sb >= 36)  */
                {
                    /*
                     *   mixed blocks processing: intensity bound outside long blocks
                     */

                    /* 2. short blocks, do for all 3  */
                    /* ------------------------------ */
                    for (j = 0; j < 3; j++)
                    {
                        int32 sfbcnt = -1;

                        for (sfb = 12; sfb >= 3; sfb--)
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
                        if (sfbcnt < 3)
                        {
                            sfbcnt = 3;   /* should not be necessary */
                        }

                        sfbTemp = sfbcnt; /* from this (short) sfb on we have intensity mode        */
                        /* can have values between 3 (all short sfb in intensity) */
                        /* and 13 (no short sfb in intensity mode)                */

                        /* 3. from sfbTemp to last sfb calculate is_ratio values:    */
                        /* first calculate directional factors for intensity stereo, */
                        /* for all sfb in intensity mode, but only                   */
                        /* if they do not have "illegal" position:                   */

                        /* to do this for all sfb we have to get information for last scale factor band: */
                        /*  get factors for last scale factor band: */
                        /* more than one sfb in intensity mode,
                        copy factors and legal/illegal information from sfb11 to sfb12 */
                        if (sfbTemp < 12)
                        {
                            (scalefac_R->s[j][12]) = (scalefac_R->s[j][11]);
                            scalefac_IIP_buffer[36 + j] = scalefac_IIP_buffer[33 + j];   /* legal/illegal in sfb 12 same as in sfb 11 */
                        }
                        else if (sfbTemp == sfb)
                            /* only sfb 12 in intensity mode, use factors corresponding to is_pos[12] == 0 */
                        {
                            (scalefac_R->s[j][12]) = 0;
                            scalefac_IIP_buffer[36 + j] = 1;    /* the scf value 0 in sfb12 is "legal" */
                        }
                        /* if sfbTemp > sfb (no sfb in intensity mode): do nothing */


                        /* 4. do normal stereo or MS stereo from sfb 3 to < sfbTemp: */
                        for (sfb = 3; sfb < sfbTemp; sfb++)
                        {
                            sfbNo = mp3_sfBandIndex[sfreq].s[sfb+1] - mp3_sfBandIndex[sfreq].s[sfb];
                            sfbStart = 3 * mp3_sfBandIndex[sfreq].s[sfb] + j * sfbNo;

                            if (ms_stereo)
                            {
                                pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                            }
                        }

                        /* 5. now intensity stereo processing of the remaining sfb's: */

                        for (sfb = sfbTemp; sfb < 13; sfb++)
                        {
                            sfbNo = mp3_sfBandIndex[sfreq].s[sfb+1] - mp3_sfBandIndex[sfreq].s[sfb]; /* No of lines to process */
                            sfbStart = 3 * mp3_sfBandIndex[sfreq].s[sfb] + j * sfbNo;
                            if ((uint32)(scalefac_R->s[j][sfb]) != scalefac_IIP_buffer[3*sfb + j])
                            {
                                pvmp3_st_intensity_ver2(xr, xl, io, scalefac_R->s[j][sfb], sfbStart, sfbNo);
                            }
                            else if (ms_stereo)
                            {
                                pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                            }
                        }
                        /*  end of correction by efs 2003-07-04 */
                    } /* for (j = 0; j < 3; j++) */


                    /* long blocks 0 up to sfb band 6: no intensity */

                    sfbNo = mp3_sfBandIndex[sfreq].l[6];        /* number of lines to process */
                    if (ms_stereo)
                    {
                        pvmp3_st_mid_side(xr, xl, 0, sfbNo);
                    }

                }  /* if intensity bound inside or outside long blocks */
            }  /* if (gr_info->mixed_block_flag) */
            else
            {
                /*
                 *  short block processing
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

                    /*  start of corrected version by efs 2003-07-04  */
                    sfbTemp = sfbcnt; /* from this (short) sfb on we have intensity mode        */
                    /* can have values between 3 (all short sfb in intensity) */
                    /* and 13 (no short sfb in intensity mode)                */

                    /* first calculate directional factors for intensity stereo,
                    for all sfb in intensity mode, but only
                    if they do not have "illegal" position: */

                    /* to do this for all sfb we have to get information for last scale factor band: */
                    /* get factors for last scale factor band: */
                    /* more than one sfb in intensity mode,
                    copy factors and legal/illegal information from sfb11 to sfb12 */
                    if (sfbTemp < 12)
                    {
                        (scalefac_R->s[j][12]) = (scalefac_R->s[j][11]);
                        scalefac_IIP_buffer[36 + j] = scalefac_IIP_buffer[33 + j];  /* legal/illegal in sfb 12 same as in sfb 11 */
                    }
                    else if (sfbTemp == 12)
                        /* only sfb 12 in intensity mode, use factors corresponding to is_pos[12] == 0 */
                    {
                        (scalefac_R->s[j][12]) = 0;
                        scalefac_IIP_buffer[36 + j] = 1;    /* the scf value 0 in sfb12 is "legal" */
                    }
                    /* if sfbTemp > sfb (no sfb in intensity mode): do nothing */


                    /* Now process audio samples */
                    /* first process lower sfb's not in intensity mode */
                    for (sfb = 0; sfb < sfbTemp; sfb++)
                    {
                        sfbNo = mp3_sfBandIndex[sfreq].s[sfb+1] - mp3_sfBandIndex[sfreq].s[sfb];
                        sfbStart = 3 * mp3_sfBandIndex[sfreq].s[sfb] + j * sfbNo;

                        if (ms_stereo)
                        {
                            pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                        }
                    }

                    /* now intensity stereo processing of the remaining sfb's: */
                    for (sfb = sfbTemp; sfb < 13; sfb++)
                    {
                        sfbNo = mp3_sfBandIndex[sfreq].s[sfb+1] - mp3_sfBandIndex[sfreq].s[sfb]; /* No of lines to process */
                        sfbStart = 3 * mp3_sfBandIndex[sfreq].s[sfb] + j * sfbNo;

                        if ((uint32)(scalefac_R->s[j][sfb]) != scalefac_IIP_buffer[3*sfb + j])
                        {
                            pvmp3_st_intensity_ver2(xr, xl, io, scalefac_R->s[j][sfb], sfbStart, sfbNo);
                        }
                        else if (ms_stereo)
                        {
                            pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                        }
                    }

                } /* for (j = 0; j < 3; j++) */

            } /* end of else ( gr_info->mixed_block_flag) */

        }  /* if (gr_info->window_switching_flag && (gr_info->block_type == 2)) */
        else
        {
            /*
             *  long block processing
             */
            i = 31;
            ss = 17;
            sb = 0;

            while (i >= 0)
            {
                if (xl[(i*FILTERBANK_BANDS) + ss])
                {
                    sb = (i << 4) + (i << 1) + ss;
                    /*  i = -1     patched RF    24-09-2002   */
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

            /*  patched RF    24-09-2002   */
            if (sb)
            {
                if (mp3_sfBandIndex[sfreq].l[14] <= sb)
                {
                    i = 14;
                }
                else if (mp3_sfBandIndex[sfreq].l[7] <= sb)
                {
                    i = 7;
                }
                else
                {
                    i = 0;
                }

                while (mp3_sfBandIndex[sfreq].l[i] <= sb)
                {
                    i++;
                }
            }

            else
            {
                if (i == -1)
                {
                    /*  all xr[1][][] are 0: set IS bound sfb to 0  */
                    i = 0;
                }
                else
                {
                    /*  xr[1][0][0] is unequal 0 and all others are 0: set IS bound sfb to 1 */
                    i = 1;
                }
            }
            /*  corrected version by efs 2003-07-04  */
            sfbTemp = i;  /* from this (long) sfb on we have intensity mode        */
            /* can have values between 0 (all long sfb in intensity) */
            /* and 22 (no long sfb in intensity mode)                */

            /* first calculate directional factors for intensity stereo,
            for all sfb in intensity mode, but only if they
            do not have "illegal" position: */

            /* to do this for all sfb we have to get information for last scale factor band: */
            if (sfbTemp < 21)
                /* more than one sfb in intensity mode, */
                /* copy factors and legal/illegal information from sfb20 to sfb21 */
            {
                (scalefac_R->l[21]) = (scalefac_R->l[20]);
                scalefac_IIP_buffer[21] = scalefac_IIP_buffer[20];  /* legal/illegal in sfb 21 same as in sfb 20 */
            }
            else if (sfbTemp == 21)
                /* only sfb 21 in intensity mode, is_pos[21] = 0 */
            {
                (scalefac_R->l[21]) = 0;
                scalefac_IIP_buffer[21] = 1;    /* the scf value 0 in sfb21 is "legal" */
            }
            /* if sfbTemp > 21 (no sfb in intensity mode): do nothing */


            /* Now process audio samples */
            /* first process lower sfb's not in intensity mode */

            sfbNo = mp3_sfBandIndex[sfreq].l[sfbTemp] - mp3_sfBandIndex[sfreq].l[0];
            sfbStart = mp3_sfBandIndex[sfreq].l[0];

            if (ms_stereo)
            {
                pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
            }

            /* now intensity stereo processing of the remaining sfb's: */
            for (sfb = sfbTemp; sfb < 22; sfb++)
            {
                sfbNo = mp3_sfBandIndex[sfreq].l[sfb+1] - mp3_sfBandIndex[sfreq].l[sfb]; /* number of lines to process */
                sfbStart = mp3_sfBandIndex[sfreq].l[sfb];                          /* start of sfb */

                if ((uint32)(scalefac_R->l[sfb]) != scalefac_IIP_buffer[sfb]) /* "legal" position ? */
                {
                    pvmp3_st_intensity_ver2(xr, xl, io, scalefac_R->l[sfb], sfbStart, sfbNo);
                }
                else if (ms_stereo)
                {
                    pvmp3_st_mid_side(xr, xl, sfbStart, sfbNo);
                }

            }  /* for (sfb = sfbTemp; sfb < 22; sfb++) */

        }  /* if (gr_info->window_switching_flag && (gr_info->block_type == 2)) */

    }  /* if (i_stereo) */
    else
    {
        /*
         *  normal or ms stereo processing
         */
        if (ms_stereo)
        {
            pvmp3_st_mid_side(xr, xl, 0, used_freq_lines);
        }

    } /* if (i_stereo) */

}




