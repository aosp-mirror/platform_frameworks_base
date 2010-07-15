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
/*  =====================================================================   */
/*  File: FastCodeMB.h                                                      */
/*  Description: This file contains structure and function prototypes used
            in FastCodeMB() function. When it is decided to use FastCodeMB
            instead of CodeMB, all of this prototypes should be migrated to
            mp4enc_lib.h.                                                   */
/*  Rev:                                                                    */
/*  Created: 8/14/01                                                        */
/* //////////////////////////////////////////////////////////////////////// */

typedef struct struct_approxDCT  approxDCT;
struct struct_approxDCT
{
    const Int *scale;
    Int(*DCT)(Int block[ ], Int coeff[ ], approxDCT *);

    // Threshold value for H.263 Quantizer
    Int th_app_all[8];
    Int th_app_odd[8];
    Int th_app_even[8];
    Int th_app_even1[8];
    Int th_app_even2[8];
};

struct QPstruct
{
    Int QPx2 ;
    Int QP;
    Int QPdiv2;
    Int QPx2plus;
    Int Addition;
};

/*---- FastCodeMB.c -----*/
void initCodeMB(approxDCT *function, Int QP);
PV_STATUS CodeMB_H263(VideoEncData *video, approxDCT *function, Int QP, Int ncoefblck[], Int offset);
PV_STATUS CodeMB_MPEG(VideoEncData *video, approxDCT *function, Int QP, Int ncoefblck[], Int offset);
Int getBlockSAV(Int block[]);
Int Sad8x8(UChar *rec, UChar *prev, Int lx);
Int getBlockSum(UChar *rec, Int lx);

/*---- AppVCA_dct.c -----*/
Int     AppVCA1_dct(Int block[], Int out[ ], approxDCT *function);
Int     AppVCA2_dct(Int block[], Int out[ ], approxDCT *function);
Int     AppVCA3_dct(Int block[], Int out[ ], approxDCT *function);
Int     AppVCA4_dct(Int block[], Int out[ ], approxDCT *function);
Int     AppVCA5_dct(Int block[], Int out[ ], approxDCT *function);

/*---- FastQuant.c -----*/
Int cal_dc_scalerENC(Int QP, Int type) ;
Int BlockQuantDequantH263Inter(Int *rcoeff, Int *qcoeff, struct QPstruct *QuantParam,
                               UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                               Int dctMode, Int comp, Int dummy);

Int BlockQuantDequantH263Intra(Int *rcoeff, Int *qcoeff, struct QPstruct *QuantParam,
                               UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                               Int dctMode, Int comp, Int dc_scaler);

Int BlockQuantDequantH263DCInter(Int *rcoeff, Int *qcoeff, struct QPstruct *QuantParam,
                                 UChar *bitmaprow, UInt *bitmapzz, Int dummy);

Int BlockQuantDequantH263DCIntra(Int *rcoeff, Int *qcoeff, struct QPstruct *QuantParam,
                                 UChar *bitmaprow, UInt *bitmapzz, Int dc_scaler);

Int BlockQuantDequantMPEGInter(Int *rcoeff, Int *qcoeff, Int QP, Int *qmat,
                               UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                               Int DctMode, Int comp, Int dc_scaler);

Int BlockQuantDequantMPEGIntra(Int *rcoeff, Int *qcoeff, Int QP, Int *qmat,
                               UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                               Int DctMode, Int comp, Int dc_scaler);

Int BlockQuantDequantMPEGDCInter(Int *rcoeff, Int *qcoeff, Int QP, Int *qmat,
                                 UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz, Int dummy);

Int BlockQuantDequantMPEGDCIntra(Int *rcoeff, Int *qcoeff, Int QP, Int *qmat,
                                 UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz, Int dc_scaler);

/*---- FastIDCT.c -----*/
void BlockIDCTMotionComp(Int *block, UChar *bitmapcol, UChar bitmaprow,
                         Int dctMode, UChar *rec, Int lx, Int intra);

/*---- motion_comp.c -----*/
void PutSkippedBlock(UChar *rec, UChar *prev, Int lx);


