;/**
; * 
; * File Name:  omxVCM4P2_QuantInvInter_I_s.s
; * OpenMAX DL: v1.0.2
; * Revision:   12290
; * Date:       Wednesday, April 9, 2008
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

   M_VARIANTS CortexA8

     IF CortexA8
     
     
;//Input Arguments
pSrcDst            RN 0
QP                 RN 1
     

;//Local Variables
Count              RN 3
doubleQP           RN 4
Return             RN 0
;// Neon registers


dQP10              DN D0.S32[0]
qQP1               QN Q0.S32

dQP1               DN D0.S16
dMinusQP1          DN D1.S16

dCoeff0            DN D2.S16
dCoeff1            DN D3.S16   

qResult0           QN Q3.S32
dResult0           DN D7.S16
qSign0             QN Q3.S32
dSign0             DN D6.S16

qResult1           QN Q4.S32
dResult1           DN D8.S16
qSign1             QN Q4.S32
dSign1             DN D8.S16

d2QP0              DN D10.S32[0]
q2QP0              QN Q5.S32
d2QP               DN D10.S16

dZero0             DN D11.S16
dZero1             DN D12.S16
dConst0            DN D13.S16

    
     M_START omxVCM4P2_QuantInvInter_I,r4,d13
     
         
         
         ADD      doubleQP,QP,QP                   ;// doubleQP= 2*QP
         VMOV     d2QP0,doubleQP
         VDUP     q2QP0,d2QP0                      ;// Move doubleQP in to a scalar
         TST      QP,#1                   
         VLD1     {dCoeff0,dCoeff1},[pSrcDst]      ;// Load first 8 values to Coeff0,Coeff1
         SUBEQ    QP,QP,#1                            
         VMOV     dQP10,QP                         ;// If QP is even then QP1=QP-1 else QP1=QP     
         MOV      Count,#64
         VDUP     qQP1,dQP10                       ;// Duplicate tempResult with QP1
         VSHRN    d2QP,q2QP0,#0
         VEOR     dConst0,dConst0,dConst0
         VSHRN    dQP1,qQP1,#0                     ;// QP1 truncated to 16 bits
         VSUB     dMinusQP1,dConst0,dQP1           ;// dMinusQP1=-QP1

Loop                       
         
        ;//Performing Inverse Quantization
         
         VCLT     dSign0,dCoeff0, #0               ;// Compare Coefficient 0 against 0
         VCLT     dSign1,dCoeff1, #0               ;// Compare Coefficient 1 against 0
         VCEQ     dZero0,dCoeff0,#0                ;// Compare Coefficient 0 against zero
         VBSL     dSign0,dMinusQP1,dQP1            ;// dSign0 = -QP1 if Coeff0< 0 else QP1
         VCEQ     dZero1,dCoeff1,#0                ;// Compare Coefficient 1 against zero
         VBSL     dSign1,dMinusQP1,dQP1            ;// dSign1 = -QP1 if Coeff1< 0 else QP1
         VMOVL    qSign0,dSign0                    ;// Sign extend qSign0 to 32 bits
         VMOVL    qSign1,dSign1
         VMLAL    qResult0,dCoeff0,d2QP            ;// qResult0[i]= qCoeff0[i]+qCoeff0[i]*(-2) if Coeff <0 
                                                   ;// qResult0[i]= qCoeff0[i]                 if Coeff >=0 
         VMLAL    qResult1,dCoeff1,d2QP            ;// qResult1[i]= qCoeff1[i]+qCoeff1[i]*(-2) if Coeff <0 
                                                   ;// qResult1[i]= qCoeff1[i]                 if Coeff >=0 
         ;// Clip Result to [-2048,2047]                     
         
         VQSHL    qResult0,qResult0,#20            ;// clip to [-2048,2047]
         VQSHL    qResult1,qResult1,#20
                 
         VSHR     qResult0,qResult0,#4  
         VSHR     qResult1,qResult1,#4
         VSHRN    dResult0,qResult0,#16            ;// Narrow the clipped Value to Halfword
         VSHRN    dResult1,qResult1,#16 
         VBIT     dResult0,dConst0,dZero0  
         VBIT     dResult1,dConst0,dZero1     
         
         VST1     {dResult0,dResult1},[pSrcDst]!   ;// Store the result
         SUBS     Count,Count,#8
         VLD1     {dCoeff0,dCoeff1},[pSrcDst]
         
         
         BGT      Loop

         MOV      Return,#OMX_Sts_NoErr


         M_END
         ENDIF
         

        END

