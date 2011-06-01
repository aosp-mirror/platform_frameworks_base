;//
;// 
;// File Name:  omxVCM4P10_InterpolateLuma_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   9641
;// Date:       Thursday, February 7, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//

;// Function:
;//     omxVCM4P10_InterpolateLuma
;//
;// This function implements omxVCM4P10_InterpolateLuma in v6 assembly.
;// Performs quarter pel interpolation of inter luma MB.
;// It's assumed that the frame is already padded when calling this function.
;// Parameters:
;// [in]    pSrc        Pointer to the source reference frame buffer
;// [in]    srcStep     Reference frame step in byte
;// [in]    dstStep     Destination frame step in byte. Must be multiple of roi.width
;// [in]    dx          Fractional part of horizontal motion vector
;//                         component in 1/4 pixel unit; valid in the range [0,3]
;// [in]    dy          Fractional part of vertical motion vector
;//                         component in 1/4 pixel unit; valid in the range [0,3]
;// [in]    roi         Dimension of the interpolation region;the parameters roi.width and roi.height must
;//                         be equal to either 4, 8, or 16.
;// [out]   pDst        Pointer to the destination frame buffer.
;//                   if roi.width==4,  4-byte alignment required
;//                   if roi.width==8,  8-byte alignment required
;//                   if roi.width==16, 16-byte alignment required
;//
;// Return Value:
;// If the function runs without error, it returns OMX_Sts_NoErr.
;// It is assued that following cases are satisfied before calling this function:
;//  pSrc or pDst is not NULL.
;//  srcStep or dstStep >= roi.width.
;//     dx or dy is in the range [0-3].
;//     roi.width or roi.height is not out of range {4, 8, 16}.
;//     If roi.width is equal to 4, Dst is 4 byte aligned.
;//     If roi.width is equal to 8, pDst is 8 byte aligned.
;//     If roi.width is equal to 16, pDst is 16 byte aligned.
;//     srcStep and dstStep is multiple of 8.
;//
;//


        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h

        M_VARIANTS ARM1136JS

        EXPORT omxVCM4P10_InterpolateLuma
        
    IF ARM1136JS
        IMPORT armVCM4P10_InterpolateLuma_Copy4x4_unsafe
        IMPORT armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        IMPORT armVCM4P10_InterpolateLuma_VerAlign4x_unsafe
        IMPORT armVCM4P10_Average_4x4_Align0_unsafe
        IMPORT armVCM4P10_Average_4x4_Align2_unsafe
        IMPORT armVCM4P10_Average_4x4_Align3_unsafe
        IMPORT armVCM4P10_InterpolateLuma_HorDiagCopy_unsafe
        IMPORT armVCM4P10_InterpolateLuma_VerDiagCopy_unsafe
    ENDIF

    IF ARM1136JS
        IMPORT armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
        IMPORT armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
        IMPORT armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe
        IMPORT armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe
    ENDIF
    
    

;// Declare input registers
pSrc            RN 0
srcStep         RN 1
pDst            RN 2
dstStep         RN 3
iHeight         RN 4
iWidth          RN 5

;// Declare other intermediate registers
idx             RN 6
idy             RN 7
index           RN 6
Temp            RN 12
pArgs           RN 11


        ;// End of CortexA8
                    
;//-------------------------------------------------------------------------------------------------------------------------    
;//-------------------------------------------------------------------------------------------------------------------------    
    IF ARM1136JS


        M_ALLOC4 ppDst, 8
        M_ALLOC4 ppSrc, 8
        M_ALLOC4 ppArgs, 16
        M_ALLOC4 pBuffer, 120                           ;// 120 = 12x10
        M_ALLOC8 pInterBuf, 120                         ;// 120 = 12*5*2
        M_ALLOC8 pTempBuf, 32                           ;// 32 =  8*4
        
        ;// Function header
        ;// Interpolation of luma is implemented by processing block of pixels, size 4x4 at a time.
        ;// Depending on the values of motion vector fractional parts (dx,dy), one out of 16 cases will be processed.
        ;// Registers r4, r5, r6 to be preserved by internal unsafe functions
        ;// r4 - iHeight
        ;// r5 - iWidth
        ;// r6 - index
        M_START omxVCM4P10_InterpolateLuma, r11

;// Declare other intermediate registers
idx             RN 6
idy             RN 7
index           RN 6
Temp            RN 12
pArgs           RN 11

pBuf            RN 8
Height          RN 9 
bufStep         RN 9
        
        ;// Define stack arguments
        M_ARG   ptridx, 4
        M_ARG   ptridy, 4        
        M_ARG   ptrWidth, 4
        M_ARG   ptrHeight, 4        

        ;// Load structure elements of roi 
        M_LDR   idx, ptridx
        M_LDR   idy, ptridy
        M_LDR   iWidth, ptrWidth
        M_LDR   iHeight, ptrHeight
        
        M_PRINTF "roi.width %d\n", iWidth
        M_PRINTF "roi.height %d\n", iHeight

        ADD     index, idx, idy, LSL #2                 ;//  [index] = [idy][idx]
        M_ADR   pArgs, ppArgs

InterpolateLuma
Block4x4WidthLoop
Block4x4HeightLoop

        STM     pArgs, {pSrc,srcStep,pDst,dstStep} 
        M_ADR   pBuf, pBuffer                           

        ;// switch table using motion vector as index
        M_SWITCH index, L
        M_CASE  Case_0
        M_CASE  Case_1
        M_CASE  Case_2
        M_CASE  Case_3
        M_CASE  Case_4
        M_CASE  Case_5
        M_CASE  Case_6
        M_CASE  Case_7
        M_CASE  Case_8
        M_CASE  Case_9
        M_CASE  Case_a
        M_CASE  Case_b
        M_CASE  Case_c
        M_CASE  Case_d
        M_CASE  Case_e
        M_CASE  Case_f
        M_ENDSWITCH

Case_0
        ;// Case G
        M_PRINTF "Case 0 \n"

        BL      armVCM4P10_InterpolateLuma_Copy4x4_unsafe
        B       Block4x4LoopEnd

Case_1
        ;// Case a
        M_PRINTF "Case 1 \n"

        SUB     pSrc, pSrc, #2
        MOV     Height, #4
        BL      armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
        BL      armVCM4P10_Average_4x4_Align2_unsafe
        B       Block4x4LoopEnd
Case_2
        ;// Case b
        M_PRINTF "Case 2 \n"
        
        SUB     pSrc, pSrc, #2
        MOV     Height, #4
        BL      armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe        
        B       Block4x4LoopEnd
Case_3
        ;// Case c
        M_PRINTF "Case 3 \n"

        SUB     pSrc, pSrc, #2
        MOV     Height, #4
        BL      armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
        BL      armVCM4P10_Average_4x4_Align3_unsafe
        B       Block4x4LoopEnd
Case_4
        ;// Case d
        M_PRINTF "Case 4 \n"

        SUB     pSrc, pSrc, srcStep, LSL #1
        MOV     Height, #9
        BL      armVCM4P10_InterpolateLuma_VerAlign4x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
        BL      armVCM4P10_Average_4x4_Align0_unsafe

        B       Block4x4LoopEnd
Case_5
        ;// Case e
        M_PRINTF "Case 5 \n"

        SUB     pSrc, pSrc, #2
        MOV     Height, #4
        M_ADR   pDst, pTempBuf
        MOV     dstStep, #4
        BL      armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
        M_ADR   pArgs, ppArgs
        LDM     pArgs, {pSrc, srcStep, pDst, dstStep}
        SUB     pSrc, pSrc, srcStep, LSL #1
        M_ADR   pBuf, pBuffer                           
        MOV     Height, #9
        BL      armVCM4P10_InterpolateLuma_VerAlign4x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
        M_ADR   pSrc, pTempBuf
        MOV     srcStep, #4
        BL      armVCM4P10_Average_4x4_Align0_unsafe
        

        B       Block4x4LoopEnd
Case_6
        ;// Case f
        M_PRINTF "Case 6 \n"

        SUB     pSrc, pSrc, #2
        SUB     pSrc, pSrc, srcStep, LSL #1
        MOV     Height, #9
        BL      armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        M_ADR   pBuf, pInterBuf
        BL      armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe
        M_ADR   idy, pTempBuf
        BL      armVCM4P10_InterpolateLuma_VerDiagCopy_unsafe    
        BL      armVCM4P10_Average_4x4_Align0_unsafe
        B       Block4x4LoopEnd
Case_7
        ;// Case g
        M_PRINTF "Case 7 \n"
        
        SUB     pSrc, pSrc, #2
        MOV     Height, #4
        M_ADR   pDst, pTempBuf
        MOV     dstStep, #4
        BL      armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
        M_ADR   pArgs, ppArgs
        LDM     pArgs, {pSrc, srcStep, pDst, dstStep}
        SUB     pSrc, pSrc, srcStep, LSL #1
        ADD     pSrc, pSrc, #1
        M_ADR   pBuf, pBuffer                           
        MOV     Height, #9
        BL      armVCM4P10_InterpolateLuma_VerAlign4x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
        M_ADR   pSrc, pTempBuf
        MOV     srcStep, #4
        BL      armVCM4P10_Average_4x4_Align0_unsafe

        B       Block4x4LoopEnd
Case_8
        ;// Case h
        M_PRINTF "Case 8 \n"

        SUB     pSrc, pSrc, srcStep, LSL #1
        MOV     Height, #9
        BL      armVCM4P10_InterpolateLuma_VerAlign4x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
        B       Block4x4LoopEnd
Case_9
        ;// Case i
        M_PRINTF "Case 9 \n"

        SUB     pSrc, pSrc, #2
        SUB     pSrc, pSrc, srcStep, LSL #1
        MOV     Height, #9
        BL      armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        ADD     pSrc, pSrc, srcStep, LSL #1
        M_ADR   pBuf, pInterBuf
        BL      armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe
        M_ADR   idy, pTempBuf
        BL      armVCM4P10_InterpolateLuma_HorDiagCopy_unsafe    
        BL      armVCM4P10_Average_4x4_Align2_unsafe
        B       Block4x4LoopEnd
Case_a
        ;// Case j
        M_PRINTF "Case a \n"

        SUB     pSrc, pSrc, #2
        SUB     pSrc, pSrc, srcStep, LSL #1
        MOV     Height, #9
        BL      armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        ADD     pSrc, pSrc, srcStep, LSL #1
        M_ADR   pBuf, pInterBuf
        BL      armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe
        B       Block4x4LoopEnd
Case_b
        ;// Case k
        M_PRINTF "Case b \n"
        SUB     pSrc, pSrc, #2
        SUB     pSrc, pSrc, srcStep, LSL #1
        MOV     Height, #9
        BL      armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        ADD     pSrc, pSrc, srcStep, LSL #1
        M_ADR   pBuf, pInterBuf
        BL      armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe
        M_ADR   idy, pTempBuf
        BL      armVCM4P10_InterpolateLuma_HorDiagCopy_unsafe    
        BL      armVCM4P10_Average_4x4_Align3_unsafe
        B       Block4x4LoopEnd
Case_c
        ;// Case n
        M_PRINTF "Case c \n"

        SUB     pSrc, pSrc, srcStep, LSL #1
        MOV     Height, #9
        BL      armVCM4P10_InterpolateLuma_VerAlign4x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
        ADD     pSrc, pSrc, srcStep                     ;// Update pSrc to one row down
        BL      armVCM4P10_Average_4x4_Align0_unsafe
        B       Block4x4LoopEnd
Case_d
        ;// Case p
        M_PRINTF "Case d \n"
        SUB     pSrc, pSrc, #2
        ADD     pSrc, pSrc, srcStep
        MOV     Height, #4
        M_ADR   pDst, pTempBuf
        MOV     dstStep, #4
        BL      armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
        M_ADR   pArgs, ppArgs
        LDM     pArgs, {pSrc, srcStep, pDst, dstStep}
        SUB     pSrc, pSrc, srcStep, LSL #1
        M_ADR   pBuf, pBuffer                           
        MOV     Height, #9
        BL      armVCM4P10_InterpolateLuma_VerAlign4x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
        M_ADR   pSrc, pTempBuf
        MOV     srcStep, #4
        BL      armVCM4P10_Average_4x4_Align0_unsafe
        B       Block4x4LoopEnd
Case_e
        ;// Case q
        M_PRINTF "Case e \n"
        
        SUB     pSrc, pSrc, #2
        SUB     pSrc, pSrc, srcStep, LSL #1
        MOV     Height, #9
        BL      armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        M_ADR   pBuf, pInterBuf
        BL      armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe
        M_ADR   idy, pTempBuf
        BL      armVCM4P10_InterpolateLuma_VerDiagCopy_unsafe
        ADD     pSrc, pSrc, #4    
        BL      armVCM4P10_Average_4x4_Align0_unsafe

        B       Block4x4LoopEnd
Case_f
        ;// Case r
        M_PRINTF "Case f \n"
        SUB     pSrc, pSrc, #2
        ADD     pSrc, pSrc, srcStep
        MOV     Height, #4
        M_ADR   pDst, pTempBuf
        MOV     dstStep, #4
        BL      armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
        M_ADR   pArgs, ppArgs
        LDM     pArgs, {pSrc, srcStep, pDst, dstStep}
        SUB     pSrc, pSrc, srcStep, LSL #1
        ADD     pSrc, pSrc, #1
        M_ADR   pBuf, pBuffer                           
        MOV     Height, #9
        BL      armVCM4P10_InterpolateLuma_VerAlign4x_unsafe
        BL      armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe
        M_ADR   pSrc, pTempBuf
        MOV     srcStep, #4
        BL      armVCM4P10_Average_4x4_Align0_unsafe

Block4x4LoopEnd

        ;// Width Loop
        SUBS    iWidth, iWidth, #4
        M_ADR   pArgs, ppArgs
        LDM     pArgs, {pSrc,srcStep,pDst,dstStep}  ;// Load arguments
        ADD     pSrc, pSrc, #4      
        ADD     pDst, pDst, #4
        BGT     Block4x4WidthLoop

        ;// Height Loop
        SUBS    iHeight, iHeight, #4
        M_LDR   iWidth, ptrWidth
        M_ADR   pArgs, ppArgs
        ADD     pSrc, pSrc, srcStep, LSL #2      
        ADD     pDst, pDst, dstStep, LSL #2
        SUB     pSrc, pSrc, iWidth
        SUB     pDst, pDst, iWidth
        BGT     Block4x4HeightLoop

EndOfInterpolation
        MOV     r0, #0
        M_END

    ENDIF
                    

    END
    