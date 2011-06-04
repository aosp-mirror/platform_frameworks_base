;//
;// 
;// File Name:  omxVCM4P10_PredictIntra_16x16_s.s
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
        
        M_VARIANTS ARM1136JS    
  
;//-------------------------------------------------------
;// This table for implementing switch case of C in asm by
;// the mehtod of two levels of indexing.
;//-------------------------------------------------------

    M_TABLE armVCM4P10_pIndexTable16x16
    DCD  OMX_VC_16X16_VERT, OMX_VC_16X16_HOR 
    DCD  OMX_VC_16X16_DC,   OMX_VC_16X16_PLANE
    
    IF ARM1136JS

;//--------------------------------------------
;// Constants 
;//--------------------------------------------  
BLK_SIZE        EQU 0x10
MUL_CONST0      EQU 0x01010101
MUL_CONST1      EQU 0x00060004
MUL_CONST2      EQU 0x00070005
MUL_CONST3      EQU 0x00030001
MASK_CONST      EQU 0x00FF00FF

;//--------------------------------------------
;// Scratch variable
;//--------------------------------------------
y               RN 12   
pc              RN 15   

return          RN 0    
innerCount      RN 0    
outerCount      RN 1    
pSrcLeft2       RN 1    
pDst2           RN 2    
sum             RN 6    
pTable          RN 9    
temp1           RN 10   
temp2           RN 12   
cMul1           RN 11   
cMul2           RN 12   
count           RN 12   
dstStepx2       RN 11   
leftStepx2      RN 14   
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

b               RN 12   
c               RN 14   

p2p0            RN 0    
p3p1            RN 1    
p6p4            RN 2    
p7p5            RN 4    
p10p8           RN 6    
p11p9           RN 7    
p14p12          RN 8    
p15p13          RN 9    

p3210           RN 10   
p7654           RN 10   
p111098         RN 10   
p15141312       RN 10   

;//--------------------------------------------
;// Declare input registers
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
;// omxVCM4P10_PredictIntra_16x16 starts
;//-----------------------------------------------------------------------------------------------
        
        ;// Write function header
        M_START omxVCM4P10_PredictIntra_16x16, r11
        
        ;// Define stack arguments
        M_ARG    LeftStep,     4
        M_ARG    DstStep,      4
        M_ARG    PredMode,     4
        M_ARG    Availability, 4
        
        ;// M_STALL ARM1136JS=4
        
        LDR      pTable,=armVCM4P10_pIndexTable16x16 ;// Load index table for switch case
        
        ;// Load argument from the stack
        M_LDR    predMode, PredMode                  ;// Arg predMode loaded from stack to reg 
        M_LDR    leftStep, LeftStep                  ;// Arg leftStep loaded from stack to reg 
        M_LDR    dstStep,  DstStep                   ;// Arg dstStep loaded from stack to reg         
        M_LDR    availability, Availability          ;// Arg availability loaded from stack to reg
        
        MOV      y, #BLK_SIZE                        ;// Outer Loop Count
        LDR      pc, [pTable, predMode, LSL #2]      ;// Branch to the case based on preMode
        
OMX_VC_16X16_VERT
        LDM      pSrcAbove, {tVal6,tVal7,tVal8,tVal9};// tVal 6 to 9 = pSrcAbove[0 to 15]
        ADD      dstStepx2, dstStep, dstStep         ;// double dstStep
        ADD      pDst2, pDst, dstStep                ;// pDst2- pDst advanced by dstStep
        
        ;// M_STALL ARM1136JS=2                       ;// Stall outside the loop

LOOP_VERT
        STM      pDst, {tVal6,tVal7,tVal8,tVal9}     ;// pDst[0 to 15] = tVal 6 to 9
        SUBS     y, y, #2                            ;// y--
        ADD      pDst, pDst, dstStepx2               ;// pDst advanced by dstStep
        STM      pDst2, {tVal6,tVal7,tVal8,tVal9}    ;// pDst2[16 to 31] = tVal 6 to 9
        ADD      pDst2, pDst2, dstStepx2             ;// pDst advanced by dstStep
        BNE      LOOP_VERT                           ;// Loop for 8 times
        MOV      return, #OMX_Sts_NoErr
        M_EXIT

        
OMX_VC_16X16_HOR
        
        ;// M_STALL ARM1136JS=6 
               
        LDR      r0x01010101, =MUL_CONST0            ;// Const to repeat the byte in reg 4 times
        MOV      y, #4                               ;// Outer Loop Count
        M_LDRB   tVal6, [pSrcLeft], +leftStep        ;// tVal6 = pSrcLeft[0 to 3]
        ADD      pDst2, pDst, dstStep                ;// pDst2- pDst advanced by dstStep
        M_LDRB   tVal7, [pSrcLeft], +leftStep        ;// tVal1 = pSrcLeft[4 to 7]
        ADD      dstStepx2, dstStep, dstStep         ;// double dstStep
        SUB      dstStepx2, dstStepx2, #12           ;// double dstStep  minus 12
       
LOOP_HOR        
        M_LDRB   tVal8, [pSrcLeft], +leftStep        ;// tVal8 = pSrcLeft[0 to 3]
        MUL      tVal6, tVal6, r0x01010101           ;// replicate the val in all the bytes
        M_LDRB   tVal9, [pSrcLeft], +leftStep        ;// tVal9 = pSrcLeft[4 to 7]
        MUL      tVal7, tVal7, r0x01010101           ;// replicate the val in all the bytes
        SUBS     y, y, #1                            ;// y--
        STR      tVal6, [pDst],  #+4                 ;// store {tVal6} at pDst[0 to 3] 
        STR      tVal7, [pDst2], #+4                 ;// store {tVal7} at pDst2[0 to 3]
        STR      tVal6, [pDst],  #+4                 ;// store {tVal6} at pDst[4 to 7]
        STR      tVal7, [pDst2], #+4                 ;// store {tVal7} at pDst2[4 to 7]
        MUL      tVal8, tVal8, r0x01010101           ;// replicate the val in all the bytes
        STR      tVal6, [pDst],  #+4                 ;// store {tVal6} at pDst[8 to 11]
        STR      tVal7, [pDst2], #+4                 ;// store {tVal7} at pDst2[8 to 11]
        MUL      tVal9, tVal9, r0x01010101           ;// replicate the val in all the bytes
        M_STR    tVal6, [pDst], dstStepx2            ;// store {tVal6} at pDst[12 to 15]
        M_STR    tVal7, [pDst2], dstStepx2           ;// store {tVal7} at pDst2[12 to 15]
        STR      tVal8, [pDst],  #+4                 ;// store {tVal6} at pDst[0 to 3] 
        STR      tVal9, [pDst2], #+4                 ;// store {tVal7} at pDst2[0 to 3]
        STR      tVal8, [pDst],  #+4                 ;// store {tVal6} at pDst[4 to 7]
        STR      tVal9, [pDst2], #+4                 ;// store {tVal7} at pDst2[4 to 7]
        STR      tVal8, [pDst],  #+4                 ;// store {tVal6} at pDst[8 to 11]
        STR      tVal9, [pDst2], #+4                 ;// store {tVal7} at pDst2[8 to 11]
        M_STR    tVal8, [pDst], dstStepx2            ;// store {tVal6} at pDst[12 to 15]
        M_LDRB   tVal6, [pSrcLeft], +leftStep        ;// tVal6 = pSrcLeft[0 to 3]
        M_STR    tVal9, [pDst2], dstStepx2           ;// store {tVal7} at pDst2[12 to 15]
        M_LDRB   tVal7, [pSrcLeft], +leftStep        ;// tVal7 = pSrcLeft[4 to 7]
        BNE      LOOP_HOR                            ;// Loop for 3 times
        MOV      return, #OMX_Sts_NoErr
        M_EXIT
        
OMX_VC_16X16_DC
        
        ;// M_STALL ARM1136JS=2
        
        MOV      count, #0                           ;// count = 0
        TST      availability, #OMX_VC_UPPER         ;// if(availability & #OMX_VC_UPPER)
        BEQ      TST_LEFT                            ;// Jump to Left if not upper
        LDM      pSrcAbove,{tVal8,tVal9,tVal10,tVal11};// tVal 8 to 11 = pSrcAbove[0 to 15]
        ADD      count, count, #1                    ;// if upper inc count by 1
        
        ;// M_STALL ARM1136JS=2
        
        UXTB16   tVal2, tVal8                        ;// pSrcAbove[0, 2]
        UXTB16   tVal6, tVal9                        ;// pSrcAbove[4, 6]
        UADD16   tVal2, tVal2, tVal6                 ;// pSrcAbove[0, 2] + pSrcAbove[4, 6]
        UXTB16   tVal8, tVal8, ROR #8                ;// pSrcAbove[1, 3]
        UXTB16   tVal9, tVal9, ROR #8                ;// pSrcAbove[5, 7]
        UADD16   tVal8, tVal8, tVal9                 ;// pSrcAbove[1, 3] + pSrcAbove[5, 7]
        UADD16   tVal2, tVal2, tVal8                 ;// sum(pSrcAbove[0] to pSrcAbove[7])
        
        UXTB16   tVal8, tVal10                       ;// pSrcAbove[8, 10]
        UXTB16   tVal9, tVal11                       ;// pSrcAbove[12, 14]
        UADD16   tVal8, tVal8, tVal9                 ;// pSrcAbove[8, 10] + pSrcAbove[12, 14]
        UXTB16   tVal10, tVal10, ROR #8              ;// pSrcAbove[9, 11]
        UXTB16   tVal11, tVal11, ROR #8              ;// pSrcAbove[13, 15]
        UADD16   tVal10, tVal10, tVal11              ;// pSrcAbove[9, 11] + pSrcAbove[13, 15]
        UADD16   tVal8, tVal8, tVal10                ;// sum(pSrcAbove[8] to pSrcAbove[15])
        
        UADD16   tVal2, tVal2, tVal8                 ;// sum(pSrcAbove[0] to pSrcAbove[15])
        
        ;// M_STALL ARM1136JS=1
        
        ADD      tVal2, tVal2, tVal2, LSR #16        ;// sum(pSrcAbove[0] to pSrcAbove[15])
        
        ;// M_STALL ARM1136JS=1
        
        UXTH     sum, tVal2                          ;// Extract the lower half for result
        
TST_LEFT        
        TST      availability, #OMX_VC_LEFT
        BEQ      TST_COUNT
        ADD      leftStepx2, leftStep,leftStep       ;// leftStepx2 = 2 * leftStep
        ADD      pSrcLeft2, pSrcLeft, leftStep       ;// pSrcLeft2 = pSrcLeft + leftStep
        
        M_LDRB   tVal8, [pSrcLeft],  +leftStepx2     ;// tVal8 = pSrcLeft[0]
        M_LDRB   tVal9, [pSrcLeft2], +leftStepx2     ;// tVal9 = pSrcLeft[1]
        M_LDRB   tVal10, [pSrcLeft], +leftStepx2     ;// tVal10= pSrcLeft[2]
        M_LDRB   tVal11, [pSrcLeft2],+leftStepx2     ;// tVal11= pSrcLeft[3]
        ADD      tVal7, tVal8, tVal9                 ;// tVal7 = tVal8 + tVal9
        ADD      count, count, #1                    ;// Inc Counter if Left is available
        ADD      tVal6, tVal10, tVal11               ;// tVal6 = tVal10 + tVal11
        
        M_LDRB   tVal8, [pSrcLeft],  +leftStepx2     ;// tVal8 = pSrcLeft[0]
        M_LDRB   tVal9, [pSrcLeft2], +leftStepx2     ;// tVal9 = pSrcLeft[1]
        M_LDRB   tVal10, [pSrcLeft], +leftStepx2     ;// tVal10= pSrcLeft[2]
        M_LDRB   tVal11, [pSrcLeft2],+leftStepx2     ;// tVal11= pSrcLeft[3]
        ADD      sum, tVal7, tVal6                   ;// sum = tVal8 + tVal10
        ADD      tVal8, tVal8, tVal9                 ;// tVal8 = tVal8 + tVal9
        ADD      tVal10, tVal10, tVal11              ;// tVal10= tVal10 + tVal11
        ADD      tVal7, tVal8, tVal10                ;// tVal7 = tVal8 + tVal10
        
        
        M_LDRB   tVal8, [pSrcLeft],  +leftStepx2     ;// tVal8 = pSrcLeft[0]
        M_LDRB   tVal9, [pSrcLeft2], +leftStepx2     ;// tVal9 = pSrcLeft[1]
        M_LDRB   tVal10, [pSrcLeft], +leftStepx2     ;// tVal10= pSrcLeft[2]
        M_LDRB   tVal11, [pSrcLeft2],+leftStepx2     ;// tVal11= pSrcLeft[3]
        ADD      sum, sum, tVal7                     ;// sum = sum + tVal7
        ADD      tVal8, tVal8, tVal9                 ;// tVal8 = tVal8 + tVal9
        ADD      tVal10, tVal10, tVal11              ;// tVal10= tVal10 + tVal11
        ADD      tVal7, tVal8, tVal10                ;// tVal7 = tVal8 + tVal10
        
        
        M_LDRB   tVal8, [pSrcLeft],  +leftStepx2     ;// tVal8 = pSrcLeft[0]
        M_LDRB   tVal9, [pSrcLeft2], +leftStepx2     ;// tVal9 = pSrcLeft[1]
        M_LDRB   tVal10, [pSrcLeft], +leftStepx2     ;// tVal10= pSrcLeft[2]
        M_LDRB   tVal11, [pSrcLeft2],+leftStepx2     ;// tVal11= pSrcLeft[3]
        ADD      sum, sum, tVal7                     ;// sum = sum + tVal7
        ADD      tVal8, tVal8, tVal9                 ;// tVal8 = tVal8 + tVal9
        ADD      tVal10, tVal10, tVal11              ;// tVal10= tVal10 + tVal11
        ADD      tVal7, tVal8, tVal10                ;// tVal7 = tVal8 + tVal10
        ADD      sum, sum, tVal7                     ;// sum = sum + tVal7

TST_COUNT        
        CMP      count, #0                           ;// if(count == 0)
        MOVEQ    sum, #128                           ;// sum = 128 if(count == 0)
        BEQ      TST_COUNT0                          ;// if(count == 0)
        CMP      count, #1                           ;// if(count == 1)
        ADDEQ    sum, sum, #8                        ;// sum += 8 if(count == 1)
        ADDNE    sum, sum, tVal2                     ;// sum = sumleft + sumupper
        ADDNE    sum, sum, #16                       ;// sum += 16 if(count == 2)
        
        ;// M_STALL ARM1136JS=1
        
        UXTH     sum, sum                            ;// sum only byte rest cleared
        
        ;// M_STALL ARM1136JS=1
        
        LSREQ    sum, sum, #4                        ;// sum >> 4 if(count == 1)
        
        ;// M_STALL ARM1136JS=1
        
        LSRNE    sum, sum, #5                        ;// sum >> 5 if(count == 2)

TST_COUNT0
        
        ;// M_STALL ARM1136JS=1
        
        ORR      sum, sum, sum, LSL #8               ;// sum replicated in two halfword
        
        ;// M_STALL ARM1136JS=1
        
        ORR      tVal6, sum, sum, LSL #16            ;// sum  replicated in all bytes
        CPY      tVal7, tVal6                        ;// tVal1 = tVal0
        CPY      tVal8, tVal6                        ;// tVal2 = tVal0
        CPY      tVal9, tVal6                        ;// tVal3 = tVal0
        ADD      dstStepx2, dstStep, dstStep         ;// double dstStep
        ADD      pDst2, pDst, dstStep                ;// pDst2- pDst advanced by dstStep
        MOV      y, #BLK_SIZE                        ;// Outer Loop Count
        
LOOP_DC        
        STM      pDst, {tVal6,tVal7,tVal8,tVal9}     ;// pDst[0 to 15] = tVal 6 to 9
        SUBS     y, y, #2                            ;// y--
        ADD      pDst, pDst, dstStepx2               ;// pDst advanced by dstStep
        STM      pDst2, {tVal6,tVal7,tVal8,tVal9}    ;// pDst2[16 to 31] = tVal 6 to 9
        ADD      pDst2, pDst2, dstStepx2             ;// pDst advanced by dstStep
        BNE      LOOP_DC                             ;// Loop for 8 times
        
        MOV      return, #OMX_Sts_NoErr
        M_EXIT

OMX_VC_16X16_PLANE
        
        ;// M_STALL ARM1136JS=3
        RSB      tVal14, leftStep, leftStep, LSL #4  ;// tVal14 = 15*leftStep
        
        ;// M_STALL ARM1136JS=2
        LDRB     tVal10, [pSrcLeft,  tVal14]         ;// tVal10 = pSrcLeft[15*leftStep]
        LDRB     tVal11, [pSrcAboveLeft]             ;// tVal11 = pSrcAboveLeft[0]
        LDRB     tVal12, [pSrcAbove, #15]

        ADD      tVal2,  tVal12,  tVal10             ;// tVal2  = pSrcAbove[15] + pSrcLeft[15*leftStep]
        SUB      tVal10, tVal10,  tVal11             ;// tVal10 = V0 = pSrcLeft[15*leftStep] - pSrcAboveLeft[0]
        SUB      tVal11, tVal12,  tVal11             ;// tVal11 = H0 = pSrcAbove[15] - pSrcAboveLeft[0]
        MOV      tVal2,  tVal2,   LSL #4             ;// tVal2  = a = 16 * (pSrcAbove[15] + pSrcLeft[15*leftStep])

        MOV     tVal11, tVal11, LSL #3              ;// 8*[15]-[-1]
        LDRB    tVal6, [pSrcAbove, #0]
        LDRB    tVal7, [pSrcAbove, #14]
        SUB     tVal8, tVal7, tVal6
        RSB     tVal8, tVal8, tVal8, LSL #3         ;// 7*[14]-[0]
        ADD     tVal11, tVal11, tVal8
        LDRB    tVal6, [pSrcAbove, #1]
        LDRB    tVal7, [pSrcAbove, #13]
        SUB     tVal8, tVal7, tVal6
        ADD     tVal8, tVal8, tVal8
        ADD     tVal8, tVal8, tVal8, LSL #1         ;// 6*[13]-[1]
        ADD     tVal11, tVal11, tVal8
        LDRB    tVal6, [pSrcAbove, #2]
        LDRB    tVal7, [pSrcAbove, #12]
        SUB     tVal8, tVal7, tVal6
        ADD     tVal8, tVal8, tVal8, LSL #2         ;// 5*[12]-[2]
        ADD     tVal11, tVal11, tVal8
        LDRB    tVal6, [pSrcAbove, #3]
        LDRB    tVal7, [pSrcAbove, #11]
        SUB     tVal8, tVal7, tVal6
        ADD     tVal11, tVal11, tVal8, LSL #2       ;// + 4*[11]-[3]
        LDRB    tVal6, [pSrcAbove, #4]
        LDRB    tVal7, [pSrcAbove, #10]
        SUB     tVal8, tVal7, tVal6
        ADD     tVal8, tVal8, tVal8, LSL #1         ;// 3*[10]-[4]
        ADD     tVal11, tVal11, tVal8
        LDRB    tVal6, [pSrcAbove, #5]
        LDRB    tVal7, [pSrcAbove, #9]
        SUB     tVal8, tVal7, tVal6
        ADD     tVal11, tVal11, tVal8, LSL #1       ;// + 2*[9]-[5]
        LDRB    tVal6, [pSrcAbove, #6]
        LDRB    tVal7, [pSrcAbove, #8]
        SUB     tVal8, tVal7, tVal6                 ;// 1*[8]-[6]
        ADD     tVal7, tVal11, tVal8

        ADD      tVal2,  tVal2,   #16                ;// tVal2  = a + 16
        MOV      tVal1,  pSrcLeft                    ;// tVal4  = pSrcLeft
        SUB      tVal9,  tVal14,   leftStep          ;// tVal9  = 14*leftStep
        ADD      tVal9,  pSrcLeft, tVal9             ;// tVal9  = pSrcLeft + 14*leftStep
        
        M_LDRB   tVal8,  [tVal9], -leftStep          ;// tVal8  = pSrcLeft[14*leftStep]
        M_LDRB   tVal11, [tVal1], +leftStep          ;// tVal11 = pSrcLeft[0]
        ADD      tVal7,  tVal7,  tVal7,  LSL #2      ;// tVal7  = 5 * H
        ADD      tVal7,  tVal7,  #32                 ;// tVal7  = 5 * H + 32
        SUB      tVal8,  tVal8,  tVal11              ;// tVal8  = pSrcLeft[14*leftStep] - pSrcLeft[0]
        ASR      tVal12, tVal7,  #6                  ;// tVal12 = b = (5 * H + 32) >> 6
        
        RSB      tVal8,  tVal8,  tVal8,  LSL #3      ;// tVal8  = V1 = 7* (pSrcLeft[14*leftStep]-pSrcLeft[0])
        ADD      tVal6,  tVal8,  tVal10, LSL #3      ;// tVal6  = V = V0 +V1
        M_LDRB   tVal8,  [tVal9], -leftStep          ;// tVal8  = pSrcLeft[13*leftStep]
        M_LDRB   tVal10, [tVal1], +leftStep          ;// tVal10 = pSrcLeft[leftStep]
        RSB      tVal7,  tVal12,  tVal12,  LSL #3    ;// tVal7  = 7*b
        SUB      tVal2,  tVal2,   tVal7              ;// tVal2  = a + 16 - 7*b
        SUB      tVal7,  tVal8,   tVal10             ;// tVal7  = pSrcLeft[13*leftStep] - pSrcLeft[leftStep]
        M_LDRB   tVal8,  [tVal9], -leftStep          ;// tVal8  = pSrcLeft[12*lS]
        ADD      tVal7,  tVal7,   tVal7              ;// tVal7  = 2 * (pSrcLeft[13*leftStep] - pSrcLeft[leftStep])
        M_LDRB   tVal10, [tVal1], +leftStep          ;// tVal10 = pSrcLeft[2*leftStep]        
        ADD      tVal7,  tVal7,   tVal7,  LSL #1     ;// tVal7  = 6 * (pSrcLeft[13*leftStep] - pSrcLeft[leftStep])
        ADD      tVal6,  tVal6,   tVal7              ;// tVal6  = V = V + V2
        SUB      tVal7,  tVal8,   tVal10             ;// tVal7  = pSrcLeft[12*leftStep] - pSrcLeft[2*leftStep]
        M_LDRB   tVal8,  [tVal9], -leftStep          ;// tVal8  = pSrcLeft[11*leftStep]
        M_LDRB   tVal10, [tVal1], +leftStep          ;// tVal10 = pSrcLeft[3*leftStep]
        ADD      tVal7,  tVal7,   tVal7,  LSL #2     ;// tVal7  = 5 * (pSrcLeft[12*leftStep] - pSrcLeft[2*leftStep])
        ADD      tVal6,  tVal6,   tVal7              ;// tVal6  = V = V + V3
        SUB      tVal7,  tVal8,   tVal10             ;// tVal7  = pSrcLeft[11*leftStep] - pSrcLeft[3*leftStep]
        M_LDRB   tVal8,  [tVal9], -leftStep          ;// tVal8  = pSrcLeft[10*leftStep]
        M_LDRB   tVal10, [tVal1], +leftStep          ;// tVal10 = pSrcLeft[4*leftStep]
        ADD      tVal6,  tVal6,   tVal7,  LSL #2     ;// tVal6  = V = V + V4
        SUB      dstStep, dstStep, #16               ;// tVal5  = dstStep - 16
        SUB      tVal7,  tVal8,   tVal10             ;// tVal7  = pSrcLeft[10*leftStep] - pSrcLeft[4*leftStep]
        M_LDRB   tVal8,  [tVal9], -leftStep          ;// tVal8  = pSrcLeft[9*leftStep]
        M_LDRB   tVal10, [tVal1], +leftStep          ;// tVal10 = pSrcLeft[5*leftStep]
        ADD      tVal7,  tVal7,   tVal7,  LSL #1     ;// tVal7  = 3 * (pSrcLeft[10*leftStep] - pSrcLeft[4*leftStep])
        ADD      tVal6,  tVal6,   tVal7              ;// tVal6  = V = V + V5
        SUB      tVal7,  tVal8,   tVal10             ;// tVal7  = pSrcLeft[9*leftStep] - pSrcLeft[5*leftStep]
        M_LDRB   tVal8,  [tVal9], -leftStep          ;// tVal8  = pSrcLeft[8*leftStep]
        M_LDRB   tVal10, [tVal1], +leftStep          ;// tVal10 = pSrcLeft[6*leftStep]
        ADD      tVal6,  tVal6,   tVal7,  LSL #1     ;// tVal6  = V = V + V6
        
        ;// M_STALL ARM1136JS=1
        SUB      tVal7,  tVal8,   tVal10             ;// tVal7  = pSrcLeft[8*leftStep] - pSrcLeft[6*leftStep]
        ADD      tVal6,  tVal6,   tVal7              ;// tVal6  = V = V + V7
        
        ;// M_STALL ARM1136JS=1
        ADD      tVal6,  tVal6,   tVal6,  LSL #2     ;// tVal6  = 5*V
        ADD      tVal6,  tVal6,   #32                ;// tVal6  = 5*V + 32
        
        ;// M_STALL ARM1136JS=1
        ASR      tVal14, tVal6,   #6                 ;// tVal14 = c = (5*V + 32)>>6
        
        ;// M_STALL ARM1136JS=1
        RSB      tVal6,  tVal14,  tVal14, LSL #3     ;// tVal6  = 7*c
        UXTH     tVal14, tVal14                      ;// tVal14 = Cleared the upper half word
        ADD      tVal10, tVal12,  tVal12             ;// tVal10 = 2*b
        ORR      tVal14, tVal14,  tVal14, LSL #16    ;// tVal14 = {c  ,  c}
        SUB      tVal6,  tVal2,   tVal6              ;// tVal6  = d = a - 7*b - 7*c + 16
        ADD      tVal1,  tVal6,   tVal10             ;// tVal1  = pp2 = d + 2*b
        ADD      tVal10, tVal10,  tVal12             ;// tVal10 =3*b
        ORR      tVal0,  tVal6,   tVal1,  LSL #16    ;// tval0  = p2p0   = pack {p2, p0}
        UXTH     tVal12, tVal12                      ;// tVal12 = Cleared the upper half word
        UXTH     tVal10, tVal10                      ;// tVal12 = Cleared the upper half word
        ORR      tVal12, tVal12,  tVal12, LSL #16    ;// tVal12 = {b  ,  b}
        ORR      tVal10, tVal10,  tVal10, LSL #16    ;// tVal10 = {3b , 3b}
        SADD16   tVal1,  tVal0,   tVal12             ;// tVal1  = p3p1   = p2p0   + {b,b}
        SADD16   tVal2,  tVal1,   tVal10             ;// tVal2  = p6p4   = p3p1   + {3b,3b}
        SADD16   tVal4,  tVal2,   tVal12             ;// tVal4  = p7p5   = p6p4   + {b,b}
        SADD16   tVal6,  tVal4,   tVal10             ;// tVal6  = p10p8  = p7p5   + {3b,3b}
        SADD16   tVal7,  tVal6,   tVal12             ;// tVal7  = p11p9  = p10p8  + {b,b}
        SADD16   tVal8,  tVal7,   tVal10             ;// tVal8  = p14p12 = p11p9  + {3b,3b}
        SADD16   tVal9,  tVal8,   tVal12             ;// tVal9  = p15p13 = p14p12 + {b,b}
        LDR      r0x00FF00FF,     =MASK_CONST        ;// r0x00FF00FF = 0x00FF00FF
        
LOOP_PLANE        

        USAT16   temp2, #13, p3p1
        USAT16   temp1, #13, p2p0
        SADD16   p3p1,   p3p1,   c                    
        SADD16   p2p0,   p2p0,   c                    
        AND      temp2, r0x00FF00FF, temp2, ASR #5
        AND      temp1, r0x00FF00FF, temp1, ASR #5
        ORR      temp1, temp1, temp2, LSL #8
        STR      temp1, [pDst], #4
        
        USAT16   temp2, #13, p7p5
        USAT16   temp1, #13, p6p4
        SADD16   p7p5,   p7p5,   c                    
        SADD16   p6p4,   p6p4,   c                    
        AND      temp2, r0x00FF00FF, temp2, ASR #5
        AND      temp1, r0x00FF00FF, temp1, ASR #5
        ORR      temp1, temp1, temp2, LSL #8
        STR      temp1, [pDst], #4
        
        USAT16   temp2, #13, p11p9
        USAT16   temp1, #13, p10p8
        SADD16   p11p9,  p11p9,  c                    
        SADD16   p10p8,  p10p8,  c                    
        AND      temp2, r0x00FF00FF, temp2, ASR #5
        AND      temp1, r0x00FF00FF, temp1, ASR #5
        ORR      temp1, temp1, temp2, LSL #8
        STR      temp1, [pDst], #4
        
        USAT16   temp2, #13, p15p13
        USAT16   temp1, #13, p14p12
        SADD16   p15p13, p15p13, c                    
        SADD16   p14p12, p14p12, c                    
        AND      temp2, r0x00FF00FF, temp2, ASR #5
        AND      temp1, r0x00FF00FF, temp1, ASR #5
        ORR      temp1, temp1, temp2, LSL #8
        STR      temp1, [pDst], #4
        
        ADDS     r0x00FF00FF, r0x00FF00FF, #1<<28     ;// Loop counter value in top 4 bits
        
        ADD      pDst, pDst, dstStep                   
        
        BCC      LOOP_PLANE                           ;// Loop for 16 times
        MOV      return, #OMX_Sts_NoErr
        M_END
        
        ENDIF ;// ARM1136JS

            
        END
;-----------------------------------------------------------------------------------------------
; omxVCM4P10_PredictIntra_16x16 ends
;-----------------------------------------------------------------------------------------------
