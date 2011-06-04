;/**
; * 
; * File Name:  omxVCM4P2_QuantInvInter_I_s.s
; * OpenMAX DL: v1.0.2
; * Revision:   9641
; * Date:       Thursday, February 7, 2008
; * 
; * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
; * 
; * 
; *
; * Description: 
; * Contains modules for inter reconstruction
; * 
; *
; *
; *
; *
; * Function: omxVCM4P2_QuantInvInter_I
; *
; * Description:
; * Performs inverse quantization on intra/inter coded block.
; * This function supports bits_per_pixel = 8. Mismatch control
; * is performed for the first MPEG-4 mode inverse quantization method.
; * The output coefficients are clipped to the range: [-2048, 2047].
; * Mismatch control is performed for the first inverse quantization method.
; *
; * Remarks:
; *
; * Parameters:
; * [in] pSrcDst          pointer to the input (quantized) intra/inter block. Must be 16-byte aligned.
; * [in] QP              quantization parameter (quantiser_scale)
; * [in] videoComp      (Intra version only.) Video component type of the
; *                  current block. Takes one of the following flags:
; *                  OMX_VC_LUMINANCE, OMX_VC_CHROMINANCE,
; *                  OMX_VC_ALPHA.
; * [in] shortVideoHeader a flag indicating presence of short_video_header;
; *                       shortVideoHeader==1 selects linear intra DC mode,
; *                  and shortVideoHeader==0 selects nonlinear intra DC mode.
; * [out]    pSrcDst      pointer to the output (dequantized) intra/inter block.  Must be 16-byte aligned.
; *
; * Return Value:
; * OMX_Sts_NoErr - no error
; * OMX_Sts_BadArgErr - bad arguments
; *    - If pSrcDst is NULL or is not 16-byte aligned.
; *      or
; *    - If QP <= 0.
; *      or
; *    - videoComp is none of OMX_VC_LUMINANCE, OMX_VC_CHROMINANCE and OMX_VC_ALPHA.
; *
; */

   INCLUDE omxtypes_s.h
   INCLUDE armCOMM_s.h

   M_VARIANTS ARM1136JS

         

     IF ARM1136JS

;//Input Arguments
pSrcDst            RN 0
QP                 RN 1

;//Local Variables
Return             RN 0
Count              RN 4      
tempVal21          RN 2
tempVal43          RN 3
QP1                RN 5
X2                 RN 6
X3                 RN 14
Result1            RN 8
Result2            RN 9
two                RN 7

    M_START omxVCM4P2_QuantInvInter_I,r9
       
        MOV      Count,#64
        TST      QP,#1
        LDRD     tempVal21,[pSrcDst]      ;// Loads first two values of pSrcDst to tempVal21,
                                          ;// next two values to tempVal43
        SUBEQ    QP1,QP,#1                ;// QP1=QP if QP is odd , QP1=QP-1 if QP is even
        MOVNE    QP1,QP
        MOV      two,#2
        
        

Loop
        
        
        SMULBB   X2,tempVal21,two         ;// X2= first val(lower 16 bits of tampVal21)*2
        CMP      X2,#0
        
        RSBLT    X2,X2,#0                 ;// X2=absoluteval(first val)
        SMLABBNE X2,QP,X2,QP1             ;// X2=2*absval(first val)*QP+QP if QP is odd 
                                          ;// X2=2*absval(first val)*QP+QP-1 if QP is even 
        SMULTB   X3,tempVal21,two         ;// X3= second val(top 16 bits of tampVal21)*2
        RSBLT    X2,X2,#0
        
        CMP      X3,#0
               
        RSBLT    X3,X3,#0
        SMLABBNE X3,QP,X3,QP1
        
        RSBLT    X3,X3,#0
        PKHBT    Result1,X2,X3,LSL #16    ;// Result1[0-15]=X2[0-15],Result1[16-31]=X3[16-31]
        SMULBB   X2,tempVal43,two         ;// X2= first val(lower 16 bits of tampVal43)*2
        SSAT16   Result1,#12,Result1      ;// clip to range [-2048,2047]
        CMP      X2,#0
       
        
               
        RSBLE    X2,X2,#0
        SMLABBNE X2,QP,X2,QP1
        SMULTB   X3,tempVal43,two         ;// X2= first val(top 16 bits of tampVal21)*2
        RSBLT    X2,X2,#0
        CMP      X3,#0
        
        LDRD     tempVal21,[pSrcDst,#8]   ;// Load next four Values to tempVal21,tempVal43
                
        RSBLT    X3,X3,#0
        SMLABBNE X3,QP,X3,QP1
        RSBLT    X3,X3,#0
        PKHBT    Result2,X2,X3,LSL #16    ;// Result2[0-15]=X2[0-15],Result2[16-31]=X3[0-15]
        SSAT16   Result2,#12,Result2      ;// clip to range [-2048,2047]
        
        SUBS     Count,Count,#4           ;// Decrement Count by 4 and continue if it has not reached 0         
        STRD     Result1,[pSrcDst],#8     ;// Store Double words and increment the pointer to point the next store address
        
        
               
        BGT      Loop
        
        MOV      Return,#OMX_Sts_NoErr
        
        M_END
        ENDIF        
        END

