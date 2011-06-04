;//
;// 
;// File Name:  omxVCM4P10_InterpolateLuma_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
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

        M_VARIANTS CortexA8

        EXPORT omxVCM4P10_InterpolateLuma
        

    IF CortexA8
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


    IF CortexA8

        ;//
        ;// Interpolation of luma is implemented by processing block of pixels, size 4x4 at a time.
        ;//
        M_ALLOC4    ppArgs, 16
        
        ;// Function header
        M_START omxVCM4P10_InterpolateLuma, r11, d15

pSrcBK          RN 8

;// Declare Neon registers
dCoeff5         DN 30.S16
dCoeff20        DN 31.S16

;// Registers used for implementing Horizontal interpolation
dSrc0c          DN 14.U8
dSrc1c          DN 16.U8
dSrc2c          DN 18.U8
dSrc3c          DN 20.U8                   
dSrc0d          DN 15.U8
dSrc1d          DN 17.U8
dSrc2d          DN 19.U8
dSrc3d          DN 21.U8
dAccH0          DN 22.U8
dAccH1          DN 24.U8
dAccH2          DN 26.U8
dAccH3          DN 28.U8
dResultH0       DN 22.U32
dResultH1       DN 24.U32
dResultH2       DN 26.U32
dResultH3       DN 28.U32

;// Registers used for implementing Vertical interpolation
dSrc0           DN 9.U8
dSrc1           DN 10.U8
dSrc2           DN 11.U8
dSrc3           DN 12.U8
dSrc4           DN 13.U8
dAccV0          DN 0.U8
dAccV1          DN 2.U8
dAccV2          DN 4.U8
dAccV3          DN 6.U8
dResultV0       DN 0.U32
dResultV1       DN 2.U32
dResultV2       DN 4.U32
dResultV3       DN 6.U32
        
;// Registers used for implementing Diagonal interpolation
dTAcc0          DN 0.U8
dTAcc1          DN 2.U8
dTAcc2          DN 4.U8
dTAcc3          DN 6.U8
dTRes0          DN 0.32
dTRes1          DN 2.32
dTRes2          DN 4.32
dTRes3          DN 6.32
dTResult0       DN 14.U8
dTResult1       DN 16.U8
dTResult2       DN 18.U8
dTResult3       DN 20.U8
dTempP0         DN 18.S16
dTempP1         DN 19.S16
dTempQ0         DN 20.S16
dTempQ1         DN 21.S16
dTempR0         DN 22.S16
dTempR1         DN 23.S16
dTempS0         DN 24.S16
dTempS1         DN 25.S16
qTempP01        QN 9.S16
qTempQ01        QN 10.S16
qTempR01        QN 11.S16
qTempS01        QN 12.S16

;// Intermediate values for averaging
qRes2           QN 7.S16
qRes3           QN 8.S16
qRes4           QN 9.S16
qRes5           QN 10.S16
qRes6           QN 11.S16
       
;// For implementing copy
dDst0            DN 9.32
dDst1            DN 10.32
dDst2            DN 11.32
dDst3            DN 12.32

        ;// Define stack arguments
        M_ARG       ptridx, 4
        M_ARG       ptridy, 4        
        M_ARG       ptrWidth, 4
        M_ARG       ptrHeight, 4        

        ;// Load structure elements of roi 
        M_LDR       idx, ptridx
        M_LDR       idy, ptridy
        M_LDR       iWidth, ptrWidth
        M_LDR       iHeight, ptrHeight
        
        ADD         index, idx, idy, LSL #2                 ;//  [index] = [idy][idx]
        M_ADR       pArgs, ppArgs
                    
        ;// Move coefficients Neon registers
        VMOV        dCoeff20, #20
        VMOV        dCoeff5, #5
                                        
Block4x4WidthLoop
Block4x4HeightLoop

        STM         pArgs, {pSrc,srcStep,pDst,dstStep} 
                                                            
        ;// switch table using motion vector as index
        ADD         pc, pc, index, LSL #2
        B           Case_f
        B           Case_0        
        B           Case_1        
        B           Case_2        
        B           Case_3        
        B           Case_4        
        B           Case_5        
        B           Case_6        
        B           Case_7        
        B           Case_8        
        B           Case_9        
        B           Case_a        
        B           Case_b        
        B           Case_c        
        B           Case_d
        B           Case_e        
        B           Case_f
                    
Case_0                
        ;// Case G
        M_PRINTF "Case 0 \n"
        
        ;// Loads a 4x4 block of .8 and stores as .32
        ADD         Temp, pSrc, srcStep, LSL #1 
        VLD1        dSrc0, [pSrc], srcStep
        VLD1        dSrc2, [Temp], srcStep
        VLD1        dSrc1, [pSrc]
        VLD1        dSrc3, [Temp]
        
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dDst0[0], [pDst], dstStep
        VST1        dDst2[0], [Temp], dstStep
        VST1        dDst1[0], [pDst]
        VST1        dDst3[0], [Temp]
        M_ADR       pArgs, ppArgs
        B           Block4x4LoopEnd
Case_1
        ;// Case a
        M_PRINTF "Case 1 \n"

        SUB         pSrc, pSrc, #2
        BL          armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe        
        VRHADD      dAccH0, dAccH0, dSrc0c
        VRHADD      dAccH2, dAccH2, dSrc2c
        VRHADD      dAccH1, dAccH1, dSrc1c
        VRHADD      dAccH3, dAccH3, dSrc3c
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dResultH0[0], [pDst], dstStep
        VST1        dResultH2[0], [Temp], dstStep
        VST1        dResultH1[0], [pDst]
        VST1        dResultH3[0], [Temp]
        M_ADR       pArgs, ppArgs
        B           Block4x4LoopEnd        
Case_2
        ;// Case b
        M_PRINTF "Case 2 \n"

        SUB         pSrc, pSrc, #2        
        BL          armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dResultH0[0], [pDst], dstStep
        VST1        dResultH2[0], [Temp], dstStep
        VST1        dResultH1[0], [pDst]
        VST1        dResultH3[0], [Temp]
        M_ADR       pArgs, ppArgs
        B           Block4x4LoopEnd        
Case_3
        ;// Case c
        M_PRINTF "Case 3 \n"

        SUB         pSrc, pSrc, #2
        BL          armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe        
        VRHADD      dAccH0, dAccH0, dSrc0d
        VRHADD      dAccH2, dAccH2, dSrc2d
        VRHADD      dAccH1, dAccH1, dSrc1d
        VRHADD      dAccH3, dAccH3, dSrc3d
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dResultH0[0], [pDst], dstStep
        VST1        dResultH2[0], [Temp], dstStep
        VST1        dResultH1[0], [pDst]
        VST1        dResultH3[0], [Temp]
        M_ADR       pArgs, ppArgs
        B           Block4x4LoopEnd        
Case_4
        ;// Case d
        M_PRINTF "Case 4 \n"

        SUB         pSrc, pSrc, srcStep, LSL #1
        BL          armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe        
        VRHADD      dAccV0, dAccV0, dSrc0
        VRHADD      dAccV2, dAccV2, dSrc2
        VRHADD      dAccV1, dAccV1, dSrc1
        VRHADD      dAccV3, dAccV3, dSrc3
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dResultV0[0], [pDst], dstStep
        VST1        dResultV2[0], [Temp], dstStep
        VST1        dResultV1[0], [pDst]
        VST1        dResultV3[0], [Temp]
        M_ADR       pArgs, ppArgs
        B           Block4x4LoopEnd        
Case_5
        ;// Case e
        M_PRINTF "Case 5 \n"
        
        MOV         pSrcBK, pSrc
        SUB         pSrc, pSrc, srcStep, LSL #1
        BL          armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe        
        SUB         pSrc, pSrcBK, #2
        BL          armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe        
        VRHADD      dAccH0, dAccH0, dAccV0
        VRHADD      dAccH2, dAccH2, dAccV2
        VRHADD      dAccH1, dAccH1, dAccV1
        VRHADD      dAccH3, dAccH3, dAccV3        
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dResultH0[0], [pDst], dstStep
        VST1        dResultH2[0], [Temp], dstStep
        VST1        dResultH1[0], [pDst]
        VST1        dResultH3[0], [Temp]
        
        M_ADR       pArgs, ppArgs
        B       Block4x4LoopEnd        
Case_6
        ;// Case f
        M_PRINTF "Case 6 \n"

        SUB         pSrc, pSrc, srcStep, LSL #1
        SUB         pSrc, pSrc, #2
        BL          armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe
        VQRSHRUN    dTResult0, qRes2, #5        
        VQRSHRUN    dTResult1, qRes3, #5        
        VQRSHRUN    dTResult2, qRes4, #5        
        VQRSHRUN    dTResult3, qRes5, #5        
        VRHADD      dTAcc0, dTAcc0, dTResult0
        VRHADD      dTAcc2, dTAcc2, dTResult2
        VRHADD      dTAcc1, dTAcc1, dTResult1
        VRHADD      dTAcc3, dTAcc3, dTResult3
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dTRes0[0], [pDst], dstStep
        VST1        dTRes2[0], [Temp], dstStep
        VST1        dTRes1[0], [pDst]
        VST1        dTRes3[0], [Temp]
        
        M_ADR       pArgs, ppArgs
        B       Block4x4LoopEnd        
Case_7
        ;// Case g
        M_PRINTF "Case 7 \n"
        MOV         pSrcBK, pSrc
        ADD         pSrc, pSrc, #1
        SUB         pSrc, pSrc, srcStep, LSL #1
        BL          armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe        
        SUB         pSrc, pSrcBK, #2
        BL          armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe        
        VRHADD      dAccH0, dAccH0, dAccV0
        VRHADD      dAccH2, dAccH2, dAccV2
        VRHADD      dAccH1, dAccH1, dAccV1
        VRHADD      dAccH3, dAccH3, dAccV3        
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dResultH0[0], [pDst], dstStep
        VST1        dResultH2[0], [Temp], dstStep
        VST1        dResultH1[0], [pDst]
        VST1        dResultH3[0], [Temp]
        
        M_ADR       pArgs, ppArgs
        B       Block4x4LoopEnd
Case_8
        ;// Case h
        M_PRINTF "Case 8 \n"

        SUB         pSrc, pSrc, srcStep, LSL #1
        BL          armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe        
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dResultV0[0], [pDst], dstStep
        VST1        dResultV2[0], [Temp], dstStep
        VST1        dResultV1[0], [pDst]
        VST1        dResultV3[0], [Temp]
        M_ADR       pArgs, ppArgs
        B           Block4x4LoopEnd
Case_9
        ;// Case i
        M_PRINTF "Case 9 \n"
        SUB         pSrc, pSrc, srcStep, LSL #1
        SUB         pSrc, pSrc, #2
        BL          armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe
        VEXT        dTempP0, dTempP0, dTempP1, #2
        VEXT        dTempQ0, dTempQ0, dTempQ1, #2
        VEXT        dTempR0, dTempR0, dTempR1, #2
        VEXT        dTempS0, dTempS0, dTempS1, #2
        
        VQRSHRUN    dTResult0, qTempP01, #5        
        VQRSHRUN    dTResult1, qTempQ01, #5        
        VQRSHRUN    dTResult2, qTempR01, #5        
        VQRSHRUN    dTResult3, qTempS01, #5        

        VRHADD      dTAcc0, dTAcc0, dTResult0
        VRHADD      dTAcc2, dTAcc2, dTResult2
        VRHADD      dTAcc1, dTAcc1, dTResult1
        VRHADD      dTAcc3, dTAcc3, dTResult3
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dTRes0[0], [pDst], dstStep
        VST1        dTRes2[0], [Temp], dstStep
        VST1        dTRes1[0], [pDst]
        VST1        dTRes3[0], [Temp]
        M_ADR       pArgs, ppArgs
        B       Block4x4LoopEnd
Case_a
        ;// Case j
        M_PRINTF "Case a \n"

        SUB         pSrc, pSrc, srcStep, LSL #1
        SUB         pSrc, pSrc, #2
        BL          armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dTRes0[0], [pDst], dstStep
        VST1        dTRes2[0], [Temp], dstStep
        VST1        dTRes1[0], [pDst]
        VST1        dTRes3[0], [Temp]
        M_ADR       pArgs, ppArgs
        B       Block4x4LoopEnd
Case_b
        ;// Case k
        M_PRINTF "Case b \n"
        SUB         pSrc, pSrc, srcStep, LSL #1
        SUB         pSrc, pSrc, #2
        BL          armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe
        VEXT        dTempP0, dTempP0, dTempP1, #3
        VEXT        dTempQ0, dTempQ0, dTempQ1, #3
        VEXT        dTempR0, dTempR0, dTempR1, #3
        VEXT        dTempS0, dTempS0, dTempS1, #3
        
        VQRSHRUN    dTResult0, qTempP01, #5        
        VQRSHRUN    dTResult1, qTempQ01, #5        
        VQRSHRUN    dTResult2, qTempR01, #5        
        VQRSHRUN    dTResult3, qTempS01, #5        

        VRHADD      dTAcc0, dTAcc0, dTResult0
        VRHADD      dTAcc2, dTAcc2, dTResult2
        VRHADD      dTAcc1, dTAcc1, dTResult1
        VRHADD      dTAcc3, dTAcc3, dTResult3
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dTRes0[0], [pDst], dstStep
        VST1        dTRes2[0], [Temp], dstStep
        VST1        dTRes1[0], [pDst]
        VST1        dTRes3[0], [Temp]
        M_ADR       pArgs, ppArgs
        B       Block4x4LoopEnd
Case_c
        ;// Case n
        M_PRINTF "Case c \n"

        SUB         pSrc, pSrc, srcStep, LSL #1
        BL          armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe        
        VRHADD      dAccV0, dAccV0, dSrc1
        VRHADD      dAccV2, dAccV2, dSrc3
        VRHADD      dAccV1, dAccV1, dSrc2
        VRHADD      dAccV3, dAccV3, dSrc4
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dResultV0[0], [pDst], dstStep
        VST1        dResultV2[0], [Temp], dstStep
        VST1        dResultV1[0], [pDst]
        VST1        dResultV3[0], [Temp]
        M_ADR       pArgs, ppArgs
        B           Block4x4LoopEnd
Case_d
        ;// Case p
        M_PRINTF "Case d \n"
        
        MOV         pSrcBK, pSrc
        SUB         pSrc, pSrc, srcStep, LSL #1
        BL          armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe        
        ADD         pSrc, pSrcBK, srcStep
        SUB         pSrc, pSrc, #2
        BL          armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe        
        VRHADD      dAccH0, dAccH0, dAccV0
        VRHADD      dAccH2, dAccH2, dAccV2
        VRHADD      dAccH1, dAccH1, dAccV1
        VRHADD      dAccH3, dAccH3, dAccV3        
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dResultH0[0], [pDst], dstStep
        VST1        dResultH2[0], [Temp], dstStep
        VST1        dResultH1[0], [pDst]
        VST1        dResultH3[0], [Temp]
        M_ADR       pArgs, ppArgs
        B       Block4x4LoopEnd
Case_e
        ;// Case q
        M_PRINTF "Case e \n"
        
        SUB         pSrc, pSrc, srcStep, LSL #1
        SUB         pSrc, pSrc, #2
        BL          armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe
        VQRSHRUN    dTResult0, qRes3, #5        
        VQRSHRUN    dTResult1, qRes4, #5        
        VQRSHRUN    dTResult2, qRes5, #5        
        VQRSHRUN    dTResult3, qRes6, #5        

        VRHADD      dTAcc0, dTAcc0, dTResult0
        VRHADD      dTAcc2, dTAcc2, dTResult2
        VRHADD      dTAcc1, dTAcc1, dTResult1
        VRHADD      dTAcc3, dTAcc3, dTResult3
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dTRes0[0], [pDst], dstStep
        VST1        dTRes2[0], [Temp], dstStep
        VST1        dTRes1[0], [pDst]
        VST1        dTRes3[0], [Temp]
        M_ADR       pArgs, ppArgs
        B       Block4x4LoopEnd
Case_f
        ;// Case r
        M_PRINTF "Case f \n"
        MOV         pSrcBK, pSrc
        ADD         pSrc, pSrc, #1
        SUB         pSrc, pSrc, srcStep, LSL #1
        BL          armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe        
        ADD         pSrc, pSrcBK, srcStep
        SUB         pSrc, pSrc, #2
        BL          armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe        
        VRHADD      dAccH0, dAccH0, dAccV0
        VRHADD      dAccH2, dAccH2, dAccV2
        VRHADD      dAccH1, dAccH1, dAccV1
        VRHADD      dAccH3, dAccH3, dAccV3        
        ADD         Temp, pDst, dstStep, LSL #1 
        VST1        dResultH0[0], [pDst], dstStep
        VST1        dResultH2[0], [Temp], dstStep
        VST1        dResultH1[0], [pDst]
        VST1        dResultH3[0], [Temp]
        M_ADR       pArgs, ppArgs


Block4x4LoopEnd

        ;// Width Loop
        ;//M_ADR       pArgs, ppArgs
        LDM         pArgs, {pSrc,srcStep,pDst,dstStep}  ;// Load arguments
        SUBS        iWidth, iWidth, #4
        ADD         pSrc, pSrc, #4      
        ADD         pDst, pDst, #4
        BGT         Block4x4WidthLoop
                    
        ;// Height Loop
        SUBS        iHeight, iHeight, #4
        M_LDR       iWidth, ptrWidth
        M_ADR       pArgs, ppArgs
        ADD         pSrc, pSrc, srcStep, LSL #2      
        ADD         pDst, pDst, dstStep, LSL #2
        SUB         pSrc, pSrc, iWidth
        SUB         pDst, pDst, iWidth
        BGT         Block4x4HeightLoop

EndOfInterpolation
        MOV         r0, #0
        M_END       

    ENDIF  
        ;// End of CortexA8
                    
    END
    
