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
Nokia in the course of development of the MPEG-2 AAC/MPEG-4
Audio standard ISO/IEC13818-7, 14496-1, 2 and 3.
This software module is an implementation of a part
of one or more MPEG-2 AAC/MPEG-4 Audio tools as specified by the
MPEG-2 aac/MPEG-4 Audio standard. ISO/IEC  gives users of the
MPEG-2aac/MPEG-4 Audio standards free license to this software module
or modifications thereof for use in hardware or software products
claiming conformance to the MPEG-2 aac/MPEG-4 Audio  standards. Those
intending to use this software module in hardware or software products
are advised that this use may infringe existing patents. The original
developer of this software module, the subsequent
editors and their companies, and ISO/IEC have no liability for use of
this software module or modifications thereof in an
implementation. Copyright is not released for non MPEG-2 aac/MPEG-4
Audio conforming products. The original developer retains full right to
use the code for the developer's own purpose, assign or donate the code to a
third party and to inhibit third party from using the code for non
MPEG-2 aac/MPEG-4 Audio conforming products. This copyright notice
must be included in all copies or derivative works.
Copyright (c)1997.

***************************************************************************/

#ifndef _LT_PREDICTION_H
#define _LT_PREDICTION_H

#include "block.h"
#include "ltp_common.h"
#include "ibstream.h"
#include "lt_decode.h"
#include "s_frameinfo.h"
#include "window_block.h"

void init_lt_pred(LT_PRED_STATUS * lt_status);

void lt_predict(
    Int                  object,
    FrameInfo           *pFrameInfo,
    WINDOW_SEQUENCE      win_seq,
    Wnd_Shape           *pWin_shape,
    LT_PRED_STATUS  *pLt_status,
    Real                *pPredicted_samples,
    Real                *pOverlap_buffer,
    Real                *pCurrent_frame_copy,
    Real                 current_frame[]);

short double_to_int(double sig_in);

#endif /* not defined _LT_PREDICTION_H */
