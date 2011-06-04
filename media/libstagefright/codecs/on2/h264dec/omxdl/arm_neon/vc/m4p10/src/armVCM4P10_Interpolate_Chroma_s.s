;//
;// 
;// File Name:  armVCM4P10_Interpolate_Chroma_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   9641
;// Date:       Thursday, February 7, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//


        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        
        M_VARIANTS CortexA8
        

    IF CortexA8

    M_TABLE armVCM4P10_WidthBranchTableMVIsNotZero      
    
    DCD   WidthIs2MVIsNotZero, WidthIs2MVIsNotZero
    DCD   WidthIs4MVIsNotZero, WidthIs4MVIsNotZero
    DCD   WidthIs8MVIsNotZero
    
    M_TABLE armVCM4P10_WidthBranchTableMVIsZero      
    
    DCD   WidthIs2MVIsZero, WidthIs2MVIsZero
    DCD   WidthIs4MVIsZero, WidthIs4MVIsZero
    DCD   WidthIs8MVIsZero
    
    
;// input registers

pSrc                 RN 0
iSrcStep             RN 1
pDst                 RN 2
iDstStep             RN 3
iWidth               RN 4
iHeight              RN 5
dx                   RN 6
dy                   RN 7

;// local variable registers
pc                   RN 15
return               RN 0
EightMinusdx         RN 8 
EightMinusdy         RN 9

ACoeff               RN 12
BCoeff               RN 9
CCoeff               RN 8
DCoeff               RN 6

pTable               RN 11

Step1                RN 10
SrcStepMinus1        RN 14

dACoeff              DN D12.U8
dBCoeff              DN D13.U8
dCCoeff              DN D14.U8
dDCoeff              DN D15.U8

dRow0a               DN D0.U8
dRow0b               DN D1.U8
dRow1a               DN D2.U8
dRow1b               DN D3.U8

qRow0a               QN Q2.S16
qRow0b               QN Q3.S16

;//dIndex               DN    D16.U8                 
qRow1a               QN Q11.S16
qRow1b               QN Q12.S16

dRow2a               DN D16.U8
dRow2b               DN D17.U8
dRow3a               DN D18.U8
dRow3b               DN D19.U8

qOutRow2             QN Q11.U16
qOutRow3             QN Q12.U16
dOutRow2             DN D20.U8
dOutRow3             DN D21.U8
dOutRow2U64          DN D20.U64
dOutRow3U64          DN D21.U64

qOutRow0             QN Q2.U16
qOutRow1             QN Q3.U16
dOutRow0             DN D8.U8
dOutRow1             DN D9.U8

dOutRow0U64          DN D8.U64
dOutRow1U64          DN D9.U64

dOutRow0U32          DN D8.U32
dOutRow1U32          DN D9.U32

dOutRow0U16          DN D8.U16
dOutRow1U16          DN D9.U16


dOut0U64             DN D0.U64
dOut1U64             DN D1.U64

dOut00U32            DN D0.U32
dOut01U32            DN D1.U32
dOut10U32            DN D2.U32
dOut11U32            DN D3.U32

dOut0U16             DN D0.U16
dOut1U16             DN D1.U16

;//-----------------------------------------------------------------------------------------------
;// armVCM4P10_Interpolate_Chroma_asm starts
;//-----------------------------------------------------------------------------------------------
        
        ;// Write function header
        M_START armVCM4P10_Interpolate_Chroma, r11, d15
        
        ;// Define stack arguments
        M_ARG   Width,      4
        M_ARG   Height,     4
        M_ARG   Dx,         4
        M_ARG   Dy,         4
        
        ;// Load argument from the stack
        ;// M_STALL ARM1136JS=4
        
        M_LDRD   dx, dy, Dx
        M_LDRD   iWidth, iHeight, Width
        
        ;// EightMinusdx = 8 - dx
        ;// EightMinusdy = 8 - dy
        
        ;// ACoeff = EightMinusdx * EightMinusdy
        ;// BCoeff = dx * EightMinusdy
        ;// CCoeff = EightMinusdx * dy
        ;// DCoeff = dx * dy
        
        RSB     EightMinusdx, dx, #8 
        RSB     EightMinusdy, dy, #8
        CMN     dx,dy
        MOV     Step1, #1
        LDREQ   pTable, =armVCM4P10_WidthBranchTableMVIsZero
        SUB     SrcStepMinus1, iSrcStep, Step1
        LDRNE   pTable, =armVCM4P10_WidthBranchTableMVIsNotZero
        
        VLD1    dRow0a, [pSrc], Step1                   ;// 0a
        
        SMULBB  ACoeff, EightMinusdx, EightMinusdy
        SMULBB  BCoeff, dx, EightMinusdy
        VLD1    dRow0b, [pSrc], SrcStepMinus1           ;// 0b
        SMULBB  CCoeff, EightMinusdx, dy
        SMULBB  DCoeff, dx, dy
        
        VDUP    dACoeff, ACoeff
        VDUP    dBCoeff, BCoeff
        VDUP    dCCoeff, CCoeff
        VDUP    dDCoeff, DCoeff
        
        LDR     pc, [pTable, iWidth, LSL #1]      ;// Branch to the case based on iWidth
        
;// Pixel layout:
;//
;//   x00 x01 x02
;//   x10 x11 x12
;//   x20 x21 x22

;// If fractionl mv is not (0, 0)
WidthIs8MVIsNotZero

                VLD1   dRow1a, [pSrc], Step1            ;// 1a
                VMULL  qRow0a, dRow0a, dACoeff
                VLD1   dRow1b, [pSrc], SrcStepMinus1    ;// 1b
                VMULL  qRow0b, dRow1a, dACoeff
                VLD1   dRow2a, [pSrc], Step1            ;// 2a
                VMLAL  qRow0a, dRow0b, dBCoeff
                VLD1   dRow2b, [pSrc], SrcStepMinus1    ;// 2b
                VMULL  qRow1a, dRow2a, dACoeff
                VMLAL  qRow0b, dRow1b, dBCoeff
                VLD1   dRow3a, [pSrc], Step1            ;// 3a
                VMLAL  qRow0a, dRow1a, dCCoeff
                VMLAL  qRow1a, dRow2b, dBCoeff
                VMULL  qRow1b, dRow3a, dACoeff
                VLD1   dRow3b, [pSrc], SrcStepMinus1    ;// 3b
                VMLAL  qRow0b, dRow2a, dCCoeff
                VLD1   dRow0a, [pSrc], Step1            ;// 0a
                VMLAL  qRow1b, dRow3b, dBCoeff
                VMLAL  qRow1a, dRow3a, dCCoeff
                VMLAL  qRow0a, dRow1b, dDCoeff
                VLD1   dRow0b, [pSrc], SrcStepMinus1    ;// 0b
                VMLAL  qRow1b, dRow0a, dCCoeff
                VMLAL  qRow0b, dRow2b, dDCoeff
                VMLAL  qRow1a, dRow3b, dDCoeff
                
                
                SUBS   iHeight, iHeight, #4
                VMLAL  qRow1b, dRow0b, dDCoeff

                VQRSHRN dOutRow0, qOutRow0, #6
                VQRSHRN dOutRow1, qOutRow1, #6
                VQRSHRN dOutRow2, qOutRow2, #6
                VST1   dOutRow0U64, [pDst], iDstStep
                VQRSHRN dOutRow3, qOutRow3, #6
                
                VST1   dOutRow1U64, [pDst], iDstStep  
                VST1   dOutRow2U64, [pDst], iDstStep
                VST1   dOutRow3U64, [pDst], iDstStep  
                

                BGT     WidthIs8MVIsNotZero
                MOV     return,  #OMX_Sts_NoErr
                M_EXIT

WidthIs4MVIsNotZero

                VLD1   dRow1a, [pSrc], Step1
                VMULL  qRow0a, dRow0a, dACoeff
                VMULL  qRow0b, dRow1a, dACoeff
                VLD1   dRow1b, [pSrc], SrcStepMinus1
                VMLAL  qRow0a, dRow0b, dBCoeff
                VMLAL  qRow0b, dRow1b, dBCoeff
                VLD1   dRow0a, [pSrc], Step1
                VMLAL  qRow0a, dRow1a, dCCoeff
                VMLAL  qRow0b, dRow0a, dCCoeff
                VLD1   dRow0b, [pSrc], SrcStepMinus1
                SUBS   iHeight, iHeight, #2
                VMLAL  qRow0b, dRow0b, dDCoeff
                VMLAL  qRow0a, dRow1b, dDCoeff
                
                VQRSHRN dOutRow1, qOutRow1, #6
                VQRSHRN dOutRow0, qOutRow0, #6
                
                VST1   dOutRow0U32[0], [pDst], iDstStep
                VST1   dOutRow1U32[0], [pDst], iDstStep  
                
                BGT     WidthIs4MVIsNotZero
                MOV     return,  #OMX_Sts_NoErr
                M_EXIT

WidthIs2MVIsNotZero

                VLD1   dRow1a, [pSrc], Step1
                VMULL  qRow0a, dRow0a, dACoeff
                VMULL  qRow0b, dRow1a, dACoeff
                VLD1   dRow1b, [pSrc], SrcStepMinus1
                VMLAL  qRow0a, dRow0b, dBCoeff
                VMLAL  qRow0b, dRow1b, dBCoeff
                VLD1   dRow0a, [pSrc], Step1
                VMLAL  qRow0a, dRow1a, dCCoeff
                VMLAL  qRow0b, dRow0a, dCCoeff
                VLD1   dRow0b, [pSrc], SrcStepMinus1
                SUBS   iHeight, iHeight, #2
                VMLAL  qRow0b, dRow0b, dDCoeff
                VMLAL  qRow0a, dRow1b, dDCoeff
                
                VQRSHRN dOutRow1, qOutRow1, #6
                VQRSHRN dOutRow0, qOutRow0, #6
                
                VST1   dOutRow0U16[0], [pDst], iDstStep
                VST1   dOutRow1U16[0], [pDst], iDstStep  

                BGT     WidthIs2MVIsNotZero 
                MOV     return,  #OMX_Sts_NoErr
                M_EXIT                
                
;// If fractionl mv is (0, 0)
WidthIs8MVIsZero
                SUB     pSrc, pSrc, iSrcStep

WidthIs8LoopMVIsZero
                VLD1    dRow0a, [pSrc], iSrcStep
                SUBS    iHeight, iHeight, #2
                VLD1    dRow0b, [pSrc], iSrcStep
                VST1    dOut0U64, [pDst], iDstStep
                VST1    dOut1U64, [pDst], iDstStep
                BGT     WidthIs8LoopMVIsZero

                MOV     return,  #OMX_Sts_NoErr
                M_EXIT

WidthIs4MVIsZero                
                VLD1    dRow0b, [pSrc], iSrcStep
                
                SUBS    iHeight, iHeight, #2
                
                VST1    dOut00U32[0], [pDst], iDstStep
                VLD1    dRow0a, [pSrc], iSrcStep
                VST1    dOut01U32[0], [pDst], iDstStep
                
                BGT     WidthIs4MVIsZero 
                MOV     return,  #OMX_Sts_NoErr
                M_EXIT
                
WidthIs2MVIsZero                
                VLD1    dRow0b, [pSrc], iSrcStep
                SUBS    iHeight, iHeight, #2
                
                VST1    dOut0U16[0], [pDst], iDstStep
                VLD1    dRow0a, [pSrc], iSrcStep
                VST1    dOut1U16[0], [pDst], iDstStep
                
                BGT     WidthIs2MVIsZero 
                MOV     return,  #OMX_Sts_NoErr                                
                M_END
                    
        ENDIF ;// CortexA8
        
        END

;//-----------------------------------------------------------------------------------------------
;// armVCM4P10_Interpolate_Chroma_asm ends
;//-----------------------------------------------------------------------------------------------

