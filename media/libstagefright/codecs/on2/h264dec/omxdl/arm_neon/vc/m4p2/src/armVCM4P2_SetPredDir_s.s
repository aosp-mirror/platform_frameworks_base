;//
;// 
;// File Name:  armVCM4P2_SetPredDir_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//

; **
; * Function: armVCM4P2_SetPredDir
; *
; * Description:
; * Performs detecting the prediction direction
; *
; * Remarks:
; *
; * Parameters:
; * [in] blockIndex  block index indicating the component type and
; *                          position as defined in subclause 6.1.3.8, of ISO/IEC
; *                          14496-2. Furthermore, indexes 6 to 9 indicate the
; *                          alpha blocks spatially corresponding to luminance
; *                          blocks 0 to 3 in the same macroblock.
; * [in] pCoefBufRow pointer to the coefficient row buffer
; * [in] pQpBuf      pointer to the quantization parameter buffer
; * [out]predQP      quantization parameter of the predictor block
; * [out]predDir     indicates the prediction direction which takes one
; *                  of the following values:
; *                  OMX_VC_HORIZONTAL    predict horizontally
; *                  OMX_VC_VERTICAL      predict vertically
; *
; * Return Value:
; * Standard OMXResult result. See enumeration for possible result codes.
; *
; */

       INCLUDE omxtypes_s.h
       INCLUDE armCOMM_s.h
       INCLUDE omxVC_s.h


       M_VARIANTS ARM1136JS


       IF ARM1136JS
 
;// Input Arguments
BlockIndex         RN 0
pCoefBufRow        RN 1
pCoefBufCol        RN 2
predDir            RN 3
predQP             RN 4
pQpBuf             RN 5

;// Local Variables

Return             RN 0
blockDCLeft        RN 6  
blockDCTop         RN 7
blockDCTopLeft     RN 8
temp1              RN 9
temp2              RN 14

       M_START    armVCM4P2_SetPredDir,r9

       M_ARG       ppredQP,4
       M_ARG       ppQpBuf,4
    
       LDRH        blockDCTopLeft,[pCoefBufRow,#-16]
       LDRH        blockDCLeft,[pCoefBufCol]
       
       TEQ         BlockIndex,#3
       LDREQH      blockDCTop,[pCoefBufCol,#-16]
       LDRNEH      blockDCTop,[pCoefBufRow]
             
       SUBS        temp1,blockDCLeft,blockDCTopLeft
       RSBLT       temp1,temp1,#0
       SUBS        temp2,blockDCTopLeft,blockDCTop
       RSBLT       temp2,temp2,#0
      
       M_LDR       pQpBuf,ppQpBuf
       M_LDR       predQP,ppredQP
       CMP         temp1,temp2
       MOV         temp2,#OMX_VC_VERTICAL
       LDRLTB      temp1,[pQpBuf,#1]
       STRLT       temp2,[predDir]
       STRLT       temp1,[predQP]
       MOV         temp2,#OMX_VC_HORIZONTAL           
       LDRGEB      temp1,[pQpBuf]
       STRGE       temp2,[predDir]
       MOV         Return,#OMX_Sts_NoErr
       STRGE       temp1,[predQP] 

         
    
       M_END
 
       ENDIF

       END    
    
