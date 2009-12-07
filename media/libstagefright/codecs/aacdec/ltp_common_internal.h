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
/**************************************************************************

This software module was originally developed by

Mikko Suonio (Nokia)

in the course of development of the MPEG-2 NBC/MPEG-4 Audio standard
ISO/IEC 13818-7, 14496-1,2 and 3. This software module is an
implementation of a part of one or more MPEG-2 NBC/MPEG-4 Audio tools
as specified by the MPEG-2 NBC/MPEG-4 Audio standard. ISO/IEC gives
users of the MPEG-2 NBC/MPEG-4 Audio standards free license to this
software module or modifications thereof for use in hardware or
software products claiming conformance to the MPEG-2 NBC/ MPEG-4 Audio
standards. Those intending to use this software module in hardware or
software products are advised that this use may infringe existing
patents. The original developer of this software module and his/her
company, the subsequent editors and their companies, and ISO/IEC have
no liability for use of this software module or modifications thereof
in an implementation. Copyright is not released for non MPEG-2
NBC/MPEG-4 Audio conforming products. The original developer retains
full right to use the code for his/her own purpose, assign or donate
the code to a third party and to inhibit third party from using the
code for non MPEG-2 NBC/MPEG-4 Audio conforming products. This
copyright notice must be included in all copies or derivative works.

Copyright (c) 1997.

***************************************************************************/

#ifndef _LTP_COMMON_INTERNAL_H
#define _LTP_COMMON_INTERNAL_H


/*
  Purpose:      Number of LTP coefficients. */
#define LPC 1

/*
  Purpose:      Maximum LTP lag.  */
#define DELAY 2048

/*
  Purpose:  Length of the bitstream element ltp_data_present.  */
#define LEN_LTP_DATA_PRESENT 1

/*
  Purpose:  Length of the bitstream element ltp_lag.  */
#define LEN_LTP_LAG 11

/*
  Purpose:  Length of the bitstream element ltp_coef.  */
#define LEN_LTP_COEF 3

/*
  Purpose:  Length of the bitstream element ltp_short_used.  */
#define LEN_LTP_SHORT_USED 1

/*
  Purpose:  Length of the bitstream element ltp_short_lag_present.  */
#define LEN_LTP_SHORT_LAG_PRESENT 1

/*
  Purpose:  Length of the bitstream element ltp_short_lag.  */
#define LEN_LTP_SHORT_LAG 5

/*
  Purpose:  Offset of the lags written in the bitstream.  */
#define LTP_LAG_OFFSET 16

/*
  Purpose:  Length of the bitstream element ltp_long_used.  */
#define LEN_LTP_LONG_USED 1

/*
  Purpose:  Upper limit for the number of scalefactor bands
        which can use lt prediction with long windows.
  Explanation:  Bands 0..MAX_LT_PRED_SFB-1 can use lt prediction.  */
#define MAX_LT_PRED_LONG_SFB 40

/*
  Purpose:  Upper limit for the number of scalefactor bands
        which can use lt prediction with short windows.
  Explanation:  Bands 0..MAX_LT_PRED_SFB-1 can use lt prediction.  */
#define MAX_LT_PRED_SHORT_SFB 13

/*
   Purpose:      Buffer offset to maintain block alignment.
   Explanation:  This is only used for a short window sequence.  */
#define SHORT_SQ_OFFSET (BLOCK_LEN_LONG-(BLOCK_LEN_SHORT*4+BLOCK_LEN_SHORT/2))

/*
  Purpose:  Number of codes for LTP weight. */
#define CODESIZE 8

/* number of scalefactor bands used for reconstruction for short windows */
#define NUM_RECONSTRUCTED_SFB (8)

#endif /* _LTP_COMMON_INTERNAL_H */
