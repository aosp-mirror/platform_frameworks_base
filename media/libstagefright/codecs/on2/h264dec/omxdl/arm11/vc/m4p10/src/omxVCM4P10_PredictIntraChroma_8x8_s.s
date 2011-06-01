;//
;// 
;// File Name:  omxVCM4P10_PredictIntraChroma_8x8_s.s
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
        
        EXPORT armVCM4P10_pIndexTable8x8
        
;// Define the processor variants supported by this file
         
         M_VARIANTS ARM1136JS
     
     AREA table, DATA    
;//-------------------------------------------------------
;// This table for implementing switch case of C in asm by
;// the mehtod of two levels of indexing.
;//-------------------------------------------------------

    M_TABLE armVCM4P10_pIndexTable8x8
    DCD  OMX_VC_CHROMA_DC,     OMX_VC_CHROMA_HOR 
    DCD  OMX_VC_CHROMA_VERT,   OMX_VC_CHROMA_PLANE  
    
    M_TABLE armVCM4P10_MultiplierTableChroma8x8,1
    DCW   3, 2, 1,4 
    DCW  -3,-2,-1,0
    DCW   1, 2, 3,4
    
    IF ARM1136JS
  
;//--------------------------------------------
;// Constants
;//--------------------------------------------  

BLK_SIZE        EQU 0x8
MUL_CONST0      EQU 0x01010101
MASK_CONST      EQU 0x00FF00FF
MUL_CONST1      EQU 0x80808080

;//--------------------------------------------
;// Scratch variable
;//--------------------------------------------
y               RN 12   
pc              RN 15   
return          RN 0    
pSrcLeft2       RN 1    
pDst2           RN 2    
sum1            RN 6    
sum2            RN 7    
pTable          RN 9    
dstStepx2       RN 11   
leftStepx2      RN 14   
outerCount      RN 14   
r0x01010101     RN 10   
r0x00FF00FF     RN 11   

tVal0           RN 0    
tVal1           RN 1    
tVal2           RN 2    
tVal3           RN 3    
tVal4           RN 4    
tVal5           RN 5    
tVal6           RN 6    
tVal7           RN 7    
tVal8           RN 8    
tVal9           RN 9    
tVal10          RN 10   
tVal11          RN 11   
tVal12          RN 12   
tVal14          RN 14   

b               RN 14   
c               RN 12   

p2p0            RN 0    
p3p1            RN 1    
p6p4            RN 2    
p7p5            RN 4    

pp2pp0          RN 6    
pp3pp1          RN 7    
pp6pp4          RN 8    
pp7pp5          RN 9    

p3210           RN 10   
p7654           RN 10   

;//--------------------------------------------
;// Input Arguments
;//--------------------------------------------
pSrcLeft        RN 0    ;// input pointer
pSrcAbove       RN 1    ;// input pointer
pSrcAboveLeft   RN 2    ;// input pointer
pDst            RN 3    ;// output pointer
leftStep        RN 4    ;// input variable
dstStep         RN 5    ;// input variable
predMode        RN 6    ;// input variable
availability    RN 7    ;// input variable

;//-----------------------------------------------------------------------------------------------
;// omxVCM4P10_PredictIntraChroma_8x8 starts
;//-----------------------------------------------------------------------------------------------
        
        ;// Write function header
        M_START omxVCM4P10_PredictIntraChroma_8x8, r11
        
        ;// Define stack arguments
        M_ARG    LeftStep,     4
        M_ARG    DstStep,      4
        M_ARG    PredMode,     4
        M_ARG    Availability, 4
        
        ;// M_STALL ARM1136JS=4
        
        LDR      pTable,=armVCM4P10_pIndexTable8x8   ;// Load index table for switch case
        
        
        ;// Load argument from the stack
        M_LDR    predMode, PredMode                  ;// Arg predMode loaded from stack to reg 
        M_LDR    leftStep, LeftStep                  ;// Arg leftStep loaded from stack to reg 
        M_LDR    dstStep,  DstStep                   ;// Arg dstStep loaded from stack to reg         
        M_LDR    availability, Availability          ;// Arg availability loaded from stack to reg 
        
        MOV      y, #BLK_SIZE                        ;// Outer Loop Count
        LDR      pc, [pTable, predMode, LSL #2]      ;// Branch to the case based on preMode

OMX_VC_CHROMA_DC
        AND      availability, availability,#(OMX_VC_UPPER + OMX_VC_LEFT)
        CMP      availability, #(OMX_VC_UPPER + OMX_VC_LEFT) ;// if(availability & (#OMX_VC_UPPER | #OMX_VC_LEFT))
        LDR      r0x01010101, =MUL_CONST0
        BNE      TST_UPPER                           ;// Jump to Upper if not both
        LDM      pSrcAbove,{tVal8,tVal9}             ;// tVal 8 to 9 = pSrcAbove[0 to 7]
        
        ADD      leftStepx2, leftStep,leftStep       ;// leftStepx2 = 2 * leftStep
        ADD      pSrcLeft2, pSrcLeft, leftStep       ;// pSrcLeft2 = pSrcLeft + leftStep
        
        ;// M_STALL ARM1136JS=1
       
        UXTB16   tVal7, tVal8                        ;// pSrcAbove[0, 2]
        UXTB16   tVal8, tVal8, ROR #8                ;// pSrcAbove[1, 3]
        UADD16   sum1, tVal7, tVal8                  ;// pSrcAbove[0, 2] + pSrcAbove[1, 3]
        
        UXTB16   tVal7, tVal9                        ;// pSrcAbove[4, 6]
        UXTB16   tVal9, tVal9, ROR #8                ;// pSrcAbove[5, 7]
        UADD16   sum2, tVal7, tVal9                  ;// pSrcAbove[0, 2] + pSrcAbove[4, 6]
        ADD      sum1, sum1, sum1, LSR #16           ;// sum(pSrcAbove[0] to pSrcAbove[3])
        ADD      sum2, sum2, sum2, LSR #16           ;// sum(pSrcAbove[4] to pSrcAbove[7])
        UXTH     sum1, sum1                          ;// upsum1 (Clear the top junk bits)
        UXTH     sum2, sum2                          ;// upsum2 (Clear the top junk bits)
        
        M_LDRB   tVal8, [pSrcLeft],  +leftStepx2     ;// tVal8 = pSrcLeft[0]
        M_LDRB   tVal9, [pSrcLeft2], +leftStepx2     ;// tVal9 = pSrcLeft[1]
        M_LDRB   tVal4, [pSrcLeft],  +leftStepx2     ;// tVal4 = pSrcLeft[2]
        M_LDRB   tVal12,[pSrcLeft2], +leftStepx2     ;// tVal12= pSrcLeft[3]
        ADD      tVal2, tVal8, tVal9                 ;// tVal14 = tVal8 + tVal9
        
        M_LDRB   tVal8, [pSrcLeft],  +leftStepx2     ;// tVal8 = pSrcLeft[4]
        M_LDRB   tVal9, [pSrcLeft2], +leftStepx2     ;// tVal9 = pSrcLeft[5]
        ADD      tVal14, tVal4, tVal12               ;// tVal14 = tVal4 + tVal12
        
        LDRB     tVal4, [pSrcLeft]                   ;// tVal4 = pSrcLeft[6]
        LDRB     tVal12,[pSrcLeft2]                  ;// tVal12= pSrcLeft[7]
        ADD      tVal8, tVal8, tVal9                 ;// tVal8 = tVal8 + tVal9
        ADD      tVal2, tVal2, tVal14                ;// leftsum1  = sum(pSrcLeft[0] to pSrcLeft[3])
        ADD      tVal4, tVal4, tVal12                ;// tVal4 = tVal4 + tVal12
        ADD      tVal14, tVal8, tVal4                ;// leftsum2  = sum(pSrcLeft[4] to pSrcLeft[7])
        ADD      tVal8, tVal14, #2                   ;// tVal8 = leftsum2 + 2
        ADD      tVal9, sum2,   #2                   ;// tVal8 = upsum2 + 2
        ADD      sum1,  sum1, tVal2                  ;// sum1 = upsum1 + leftsum1
        ADD      sum2,  sum2, tVal14                 ;// sum2 = upsum2 + leftsum2
        ADD      sum1, sum1, #4                      ;// (sum1 + 4)
        ADD      sum2, sum2, #4                      ;// (sum2 + 4)
        MOV      sum1,  sum1,  LSR #3                ;// (sum1 + 4)>>3
        MOV      tVal9, tVal9, LSR #2                ;// (tVal9 + 2)>>2
        MOV      tVal8, tVal8, LSR #2                ;// (tVal8 + 2)>>2
        MOV      sum2,  sum2,  LSR #3                ;// (sum2 + 4)>>3
        
        MUL      tVal0, sum1, r0x01010101            ;// replicate the val in all the bytes
        MUL      tVal1, tVal9,r0x01010101            ;// replicate the val in all the bytes
        MUL      tVal8, tVal8,r0x01010101            ;// replicate the val in all the bytes
        MUL      tVal9, sum2, r0x01010101            ;// replicate the val in all the bytes
        
        M_STRD   tVal0, tVal1, [pDst], dstStep       ;// pDst[0 to 7]   = tVal 0 to 1
        M_STRD   tVal0, tVal1, [pDst], dstStep       ;// pDst[8 to 15]  = tVal 0 to 1
        M_STRD   tVal0, tVal1, [pDst], dstStep       ;// pDst[16 to 23] = tVal 0 to 1
        M_STRD   tVal0, tVal1, [pDst], dstStep       ;// pDst[24 to 31] = tVal 0 to 1
                                       
        M_STRD   tVal8, tVal9, [pDst], dstStep       ;// pDst[32 to 39] = tVal 8 to 9
        M_STRD   tVal8, tVal9, [pDst], dstStep       ;// pDst[40 to 47] = tVal 8 to 9
        M_STRD   tVal8, tVal9, [pDst], dstStep       ;// pDst[48 to 55] = tVal 8 to 9
        M_STRD   tVal8, tVal9, [pDst], dstStep       ;// pDst[56 to 63] = tVal 8 to 9
        MOV      return, #OMX_Sts_NoErr
        M_EXIT
        
TST_UPPER
        
        ;// M_STALL ARM1136JS=3
        
        CMP      availability, #OMX_VC_UPPER         ;// if(availability & #OMX_VC_UPPER)
        
        BNE      TST_LEFT                            ;// Jump to Left if not upper
        LDM      pSrcAbove,{tVal8,tVal9}             ;// tVal 8 to 9 = pSrcAbove[0 to 7]
        
        ;// M_STALL ARM1136JS=3
        
        UXTB16   tVal7, tVal8                        ;// pSrcAbove[0, 2]
        UXTB16   tVal8, tVal8, ROR #8                ;// pSrcAbove[1, 3]
        UADD16   sum1,  tVal7, tVal8                 ;// pSrcAbove[0, 2] + pSrcAbove[1, 3]
        
        UXTB16   tVal7, tVal9                        ;// pSrcAbove[4, 6]
        UXTB16   tVal9, tVal9, ROR #8                ;// pSrcAbove[5, 7]
        UADD16   sum2,  tVal7, tVal9                 ;// pSrcAbove[0, 2] + pSrcAbove[4, 6]
        
        ADD      sum1, sum1, sum1, LSR #16           ;// sum(pSrcAbove[0] to pSrcAbove[3])
        ADD      sum2, sum2, sum2, LSR #16           ;// sum(pSrcAbove[4] to pSrcAbove[7])
        
        UXTH     sum1, sum1                          ;// upsum1 (Clear the top junk bits)
        UXTH     sum2, sum2                          ;// upsum2 (Clear the top junk bits)
        
        ADD      sum1, sum1, #2                      ;// sum1 + 2
        ADD      sum2, sum2, #2                      ;// sum2 + 2
        
        MOV      sum1, sum1, LSR #2                  ;// (sum1 + 2)>>2
        MOV      sum2, sum2, LSR #2                  ;// (sum2 + 2)>>2
        
        MUL      sum1, sum1,r0x01010101              ;// replicate the val in all the bytes
        MUL      sum2, sum2,r0x01010101              ;// replicate the val in all the bytes
        
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[0 to 7]   = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[8 to 15]  = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[16 to 23] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[24 to 31] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[32 to 39] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[40 to 47] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[48 to 55] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[56 to 63] = tVal 6 to 7
        MOV      return, #OMX_Sts_NoErr
        M_EXIT
        
TST_LEFT 
        ;// M_STALL ARM1136JS=3       
        
        CMP      availability, #OMX_VC_LEFT
        BNE      TST_COUNT0
        ADD      leftStepx2, leftStep,leftStep       ;// leftStepx2 = 2 * leftStep
        ADD      pSrcLeft2, pSrcLeft, leftStep       ;// pSrcLeft2 = pSrcLeft + leftStep
        
        M_LDRB   tVal8, [pSrcLeft],  +leftStepx2     ;// tVal8 = pSrcLeft[0]
        M_LDRB   tVal9, [pSrcLeft2], +leftStepx2     ;// tVal9 = pSrcLeft[1]
        M_LDRB   tVal4, [pSrcLeft],  +leftStepx2     ;// tVal4 = pSrcLeft[2]
        M_LDRB   tVal12,[pSrcLeft2], +leftStepx2     ;// tVal12= pSrcLeft[3]
        
        ADD      tVal6, tVal8, tVal9                 ;// tVal6 = tVal8 + tVal9
        
        M_LDRB   tVal8, [pSrcLeft],  +leftStepx2     ;// tVal8 = pSrcLeft[4]
        ADD      tVal7, tVal4, tVal12                ;// tVal7 = tVal4 + tVal12
        M_LDRB   tVal9, [pSrcLeft2], +leftStepx2     ;// tVal9 = pSrcLeft[5]
        M_LDRB   tVal4, [pSrcLeft],  +leftStepx2     ;// tVal4 = pSrcLeft[6]
        M_LDRB   tVal12,[pSrcLeft2], +leftStepx2     ;// tVal12= pSrcLeft[7]
        
        ADD      tVal8, tVal8, tVal9                 ;// tVal8 = tVal8 + tVal9
        ADD      sum1,  tVal6, tVal7                 ;// sum1  = sum(pSrcLeft[0] to pSrcLeft[3])
        ADD      tVal4, tVal4, tVal12                ;// tVal4 = tVal4 + tVal12
        ADD      sum2,  tVal8, tVal4                 ;// sum2  = sum(pSrcLeft[4] to pSrcLeft[7])
        
        ADD      sum1, sum1, #2                      ;// sum1 + 2
        ADD      sum2, sum2, #2                      ;// sum2 + 2
        
        MOV      sum1, sum1, LSR #2                  ;// (sum1 + 2)>>2
        MOV      sum2, sum2, LSR #2                  ;// (sum2 + 2)>>2
        
        MUL      tVal6, sum1,r0x01010101             ;// replicate the val in all the bytes
        MUL      tVal8, sum2,r0x01010101             ;// replicate the val in all the bytes
        
        ;// M_STALL ARM1136JS=1
        MOV      tVal7,tVal6                         ;// tVal7 = sum1
        MOV      tVal9,tVal8                         ;// tVal9 = sum2
        
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[0 to 7]   = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[8 to 15]  = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[16 to 23] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[24 to 31] = tVal 6 to 7
        
        M_STRD   tVal8, tVal9, [pDst], dstStep       ;// pDst[32 to 39] = tVal 8 to 9
        M_STRD   tVal8, tVal9, [pDst], dstStep       ;// pDst[40 to 47] = tVal 8 to 9
        M_STRD   tVal8, tVal9, [pDst], dstStep       ;// pDst[48 to 55] = tVal 8 to 9
        M_STRD   tVal8, tVal9, [pDst], dstStep       ;// pDst[56 to 63] = tVal 8 to 9
        
        MOV      return, #OMX_Sts_NoErr
        M_EXIT                                       ;// Macro to exit midway-break frm case

TST_COUNT0
        LDR      sum1, =MUL_CONST1                  ;// sum1 = 0x80808080 if(count == 0)
        
        ;// M_STALL ARM1136JS=2
        
        MOV      tVal7, sum1                         ;// tVal7 = sum1
        
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[0 to 7]   = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[8 to 15]  = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[16 to 23] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[24 to 31] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[32 to 39] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[40 to 47] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[48 to 55] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[56 to 63] = tVal 6 to 7
        
        MOV      return, #OMX_Sts_NoErr
        M_EXIT                                       ;// Macro to exit midway-break frm case

OMX_VC_CHROMA_HOR
        
        ;// M_STALL ARM1136JS=2 
        
        ADD      pSrcLeft2, pSrcLeft, leftStep       ;// pSrcLeft2 = pSrcLeft + leftStep
        ADD      leftStepx2, leftStep, leftStep      ;// leftStepx2 = leftStep * 2
        ADD      pDst2, pDst, dstStep                ;// pDst2 = pDst + dstStep
        ADD      dstStepx2, dstStep, dstStep         ;// double dstStep
        SUB      dstStepx2, dstStepx2, #4            ;// double dstStep  minus 4
        LDR      r0x01010101, =MUL_CONST0            ;// Const to repeat the byte in reg 4 times
        M_LDRB   tVal6, [pSrcLeft], +leftStepx2      ;// tVal6 = pSrcLeft[0]
        M_LDRB   tVal7, [pSrcLeft2],+leftStepx2      ;// tVal7 = pSrcLeft[1]
        M_LDRB   tVal8, [pSrcLeft], +leftStepx2      ;// tVal8 = pSrcLeft[2]
        M_LDRB   tVal9, [pSrcLeft2],+leftStepx2      ;// tVal9 = pSrcLeft[3]
        MUL      tVal6, tVal6, r0x01010101           ;// replicate the val in all the bytes
        MUL      tVal7, tVal7, r0x01010101           ;// replicate the val in all the bytes
        MUL      tVal8, tVal8, r0x01010101           ;// replicate the val in all the bytes
        MUL      tVal9, tVal9, r0x01010101           ;// replicate the val in all the bytes
        STR      tVal6, [pDst],  #+4                 ;// store {tVal6} at pDst [0 to 3] 
        STR      tVal7, [pDst2], #+4                 ;// store {tVal7} at pDst2[0 to 3]
        M_STR    tVal6, [pDst],  dstStepx2           ;// store {tVal6} at pDst [4 to 7]
        M_STR    tVal7, [pDst2], dstStepx2           ;// store {tVal7} at pDst2[4 to 7]
        STR      tVal8, [pDst],  #+4                 ;// store {tVal6} at pDst [0 to 3]
        STR      tVal9, [pDst2], #+4                 ;// store {tVal7} at pDst2[0 to 3]
        M_STR    tVal8, [pDst],  dstStepx2           ;// store {tVal6} at pDst [4 to 7]
        M_STR    tVal9, [pDst2], dstStepx2           ;// store {tVal7} at pDst2[4 to 7]
        M_LDRB   tVal6, [pSrcLeft], +leftStepx2      ;// tVal6 = pSrcLeft[4]
        M_LDRB   tVal7, [pSrcLeft2],+leftStepx2      ;// tVal7 = pSrcLeft[5]
        M_LDRB   tVal8, [pSrcLeft], +leftStepx2      ;// tVal8 = pSrcLeft[6]
        M_LDRB   tVal9, [pSrcLeft2],+leftStepx2      ;// tVal9 = pSrcLeft[7]
        MUL      tVal6, tVal6, r0x01010101           ;// replicate the val in all the bytes
        MUL      tVal7, tVal7, r0x01010101           ;// replicate the val in all the bytes
        MUL      tVal8, tVal8, r0x01010101           ;// replicate the val in all the bytes
        MUL      tVal9, tVal9, r0x01010101           ;// replicate the val in all the bytes
        STR      tVal6, [pDst],  #+4                 ;// store {tVal6} at pDst [0 to 3] 
        STR      tVal7, [pDst2], #+4                 ;// store {tVal7} at pDst2[0 to 3]
        M_STR    tVal6, [pDst],  dstStepx2           ;// store {tVal6} at pDst [4 to 7]
        M_STR    tVal7, [pDst2], dstStepx2           ;// store {tVal7} at pDst2[4 to 7]
        STR      tVal8, [pDst],  #+4                 ;// store {tVal6} at pDst [0 to 3]
        STR      tVal9, [pDst2], #+4                 ;// store {tVal7} at pDst2[0 to 3]
        M_STR    tVal8, [pDst],  dstStepx2           ;// store {tVal6} at pDst [4 to 7]
        M_STR    tVal9, [pDst2], dstStepx2           ;// store {tVal7} at pDst2[4 to 7]
        MOV      return, #OMX_Sts_NoErr
        M_EXIT
        
OMX_VC_CHROMA_VERT
        
        ;// M_STALL ARM1136JS=4        
        
        LDMIA    pSrcAbove, {tVal6,tVal7}            ;// tVal 6 to 7 = pSrcAbove[0 to 7]
        MOV      return, #OMX_Sts_NoErr
        
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[0 to 7]   = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[8 to 15]  = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[16 to 23] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[24 to 31] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[32 to 39] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[40 to 47] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[48 to 55] = tVal 6 to 7
        M_STRD   tVal6, tVal7, [pDst], dstStep       ;// pDst[56 to 63] = tVal 6 to 7

        M_EXIT                                       ;// Macro to exit midway-break frm case
        
OMX_VC_CHROMA_PLANE
        
        ;// M_STALL ARM1136JS=3
        
        RSB      tVal14, leftStep, leftStep, LSL #3  ;// 7*leftStep
        LDRB     tVal7, [pSrcAbove, #+7]             ;// pSrcAbove[7]
        LDRB     tVal6, [pSrcLeft, +tVal14]          ;// pSrcLeft[7*leftStep]
        LDRB     tVal8, [pSrcAboveLeft]              ;// pSrcAboveLeft[0]
        LDRB     tVal9, [pSrcAbove, #+6 ]            ;// pSrcAbove[6]
        LDRB     tVal10,[pSrcAbove]                  ;// pSrcAbove[0]
        ADD      tVal2, tVal7, tVal6                 ;// pSrcAbove[7] + pSrcLeft[7*leftStep]
        SUB      tVal6, tVal6, tVal8                 ;// V0 = pSrcLeft[7*leftStep] - pSrcAboveLeft[0]
        SUB      tVal7, tVal7, tVal8                 ;// H0 = pSrcAbove[7] - pSrcAboveLeft[0]        
        LSL      tVal2, tVal2, #4                    ;// a = 16 * (pSrcAbove[15] + pSrcLeft[15*lS])
        ADD      tVal2, tVal2, #16                   ;// a + 16
        SUB      tVal9, tVal9,tVal10                 ;// pSrcAbove[6] - pSrcAbove[0]
        LDRB     tVal8, [pSrcAbove,#+5]              ;// pSrcAbove[5]
        LDRB     tVal10,[pSrcAbove,#+1]              ;// pSrcAbove[1]
        ADD      tVal9, tVal9, tVal9, LSL #1         ;// H1 = 3 * (pSrcAbove[6] - pSrcAbove[0])
        ADD      tVal7, tVal9, tVal7, LSL #2         ;// H = H1 + H0
        SUB      tVal8, tVal8, tVal10                ;// pSrcAbove[5] - pSrcAbove[1]
        LDRB     tVal9, [pSrcAbove,#+4]              ;// pSrcAbove[4]
        LDRB     tVal10,[pSrcAbove,#+2]              ;// pSrcAbove[2]
        ADD      tVal7, tVal7, tVal8, LSL #1         ;// H = H + H2
        SUB      tVal11, tVal14,leftStep             ;// 6*leftStep
        ADD      tVal11, pSrcLeft, tVal11            ;// pSrcLeft + 6*leftStep
        MOV      tVal12, pSrcLeft                    ;// pSrcLeft
        SUB      tVal9, tVal9, tVal10                ;// pSrcAbove[4] - pSrcAbove[2]
        ADD      tVal7, tVal7, tVal9                 ;// H = H + H3
        M_LDRB   tVal8, [tVal11],-leftStep           ;// pSrcLeft[6*leftStep]
        M_LDRB   tVal10,[tVal12],+leftStep           ;// pSrcLeft[0]
        ADD      tVal7, tVal7, tVal7, LSL #4         ;// 17 * H
        ADD      tVal7, tVal7, #16                   ;// 17 * H + 16
        SUB      tVal8, tVal8, tVal10                ;// pSrcLeft[6*leftStep] - pSrcLeft[0]
        ASR      b, tVal7, #5                        ;// b = (17 * H + 16) >> 5
        ADD      tVal8, tVal8, tVal8, LSL #1         ;// V1 = 3 * (pSrcLeft[6*leftStep] - pSrcLeft[0])
        ADD      tVal6, tVal8, tVal6, LSL #2         ;// V = V0 +V1
        M_LDRB   tVal8, [tVal11],-leftStep           ;// pSrcLeft[5*leftStep]
        M_LDRB   tVal10,[tVal12],+leftStep           ;// pSrcLeft[leftStep]
        ADD      tVal7, b, b, LSL #1                 ;// 3*b
        SUB      tVal2, tVal2, tVal7                 ;// a + 16 - 3*b
        SUB      tVal7, tVal8, tVal10                ;// pSrcLeft[5*leftStep] - pSrcLeft[leftStep]
        M_LDRB   tVal8, [tVal11],-leftStep           ;// pSrcLeft[4*leftStep]
        M_LDRB   tVal10,[tVal12],+leftStep           ;// pSrcLeft[2*leftStep]        
        ADD      tVal6, tVal6, tVal7, LSL #1         ;// V = V + V2
        LDR      r0x00FF00FF, =MASK_CONST            ;// r0x00FF00FF = 0x00FF00FF
        SUB      tVal7, tVal8, tVal10                ;// pSrcLeft[4*leftStep] - pSrcLeft[2*leftStep]
        ADD      tVal6, tVal6, tVal7                 ;// V = V + V7
        SUB      dstStep, dstStep, #4                ;// dstStep - 4
        ADD      tVal6, tVal6, tVal6, LSL #4         ;// 17*V
        ADD      tVal6, tVal6, #16                   ;// 17*V + 16
        
        ;// M_STALL ARM1136JS=1
        
        ASR      c, tVal6, #5                        ;// c = (17*V + 16)>>5
        
        ;// M_STALL ARM1136JS=1
        
        ADD      tVal6, c, c, LSL #1                 ;// 3*c
        UXTH     c, c                                ;// only in half word
        SUB      tVal6, tVal2, tVal6                 ;// a - 3*b - 3*c + 16
        ORR      c, c, c, LSL #16                    ;// c c
        ADD      tVal7, b, b                         ;// 2b
        ADD      tVal2, tVal6, tVal7                 ;// pp2 = d + 2*b
        ADD      tVal7, tVal7, b                     ;// 3b
        ORR      p2p0,   tVal6,  tVal2,  LSL #16     ;// p2p0   = pack {p2, p0}
        UXTH     b, b
        UXTH     tVal7, tVal7
        ORR      b, b, b, LSL #16                    ;// {b,b}
        ORR      tVal7, tVal7, tVal7, LSL #16        ;// {3b,3b}
        SADD16   p3p1,   p2p0, b                     ;// p3p1   = p2p0 + {b,b}
        SADD16   p6p4,   p3p1, tVal7                 ;// p6p4   = p3p1 + {3b,3b}
        SADD16   p7p5,   p6p4, b                     ;// p7p5   = p6p4 + {b,b}
        MOV      outerCount, #BLK_SIZE               ;// Outer Loop Count        
        
LOOP_PLANE        

        USAT16   p7p5,   #13, p7p5                    ;// clip13(p7) clip13(p5)
        USAT16   p6p4,   #13, p6p4                    ;// clip13(p6) clip13(p4)
        USAT16   p3p1,   #13, p3p1                    ;// clip13(p3) clip13(p1)
        USAT16   p2p0,   #13, p2p0                    ;// clip13(p2) clip13(p0)
        
        AND      pp7pp5, r0x00FF00FF, p7p5, ASR #5    ;// clip8(p7) clip8(p5)
        AND      pp6pp4, r0x00FF00FF, p6p4, ASR #5    ;// clip8(p6) clip8(p4)
        AND      pp3pp1, r0x00FF00FF, p3p1, ASR #5    ;// clip8(p3) clip8(p1)
        AND      pp2pp0, r0x00FF00FF, p2p0, ASR #5    ;// clip8(p2) clip8(p0)
        
        SUBS     outerCount, outerCount, #1           ;// outerCount--
      
        ORR      p3210, pp2pp0, pp3pp1, LSL #8        ;// pack {p3,p2, p1, p0}
        STR      p3210, [pDst], #4                    ;// store {pDst[0] to pDst[3]}  
        
        ORR      p7654, pp6pp4, pp7pp5, LSL #8        ;// pack {p7,p6, p5, p4}
        M_STR    p7654, [pDst], dstStep               ;// store {pDst[4] to pDst[7]}

        SADD16   p7p5,   p7p5,   c                    ;// {p7 + c}, {p5 + c}
        SADD16   p6p4,   p6p4,   c                    ;// {p6 + c}, {p4 + c}
        SADD16   p3p1,   p3p1,   c                    ;// {p3 + c}, {p1 + c}
        SADD16   p2p0,   p2p0,   c                    ;// {p2 + c}, {p0 + c}
      
        BNE      LOOP_PLANE                           ;// Loop for 8 times
        MOV      return, #OMX_Sts_NoErr
        M_END
        
        ENDIF ;// ARM1136JS
        
        
        
        END
;//-----------------------------------------------------------------------------------------------
;// omxVCM4P10_PredictIntraChroma_8x8 ends
;//-----------------------------------------------------------------------------------------------
