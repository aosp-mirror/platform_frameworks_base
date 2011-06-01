;//
;// 
;// File Name:  omxVCM4P10_PredictIntra_4x4_s.s
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
        
;// Define the processor variants supported by this file
         
         M_VARIANTS ARM1136JS
        
;//-------------------------------------------------------
;// This table for implementing switch case of C in asm by
;// the mehtod of two levels of indexing.
;//-------------------------------------------------------

    M_TABLE armVCM4P10_pSwitchTable4x4
    DCD  OMX_VC_4x4_VERT,     OMX_VC_4x4_HOR 
    DCD  OMX_VC_4x4_DC,       OMX_VC_4x4_DIAG_DL
    DCD  OMX_VC_4x4_DIAG_DR,  OMX_VC_4x4_VR
    DCD  OMX_VC_4x4_HD,       OMX_VC_4x4_VL
    DCD  OMX_VC_4x4_HU   
    
    IF ARM1136JS
  
;//--------------------------------------------
;// Constants
;//--------------------------------------------  
BLK_SIZE              EQU 0x8
MUL_CONST0            EQU 0x01010101
ADD_CONST1            EQU 0x80808080

;//--------------------------------------------
;// Scratch variable
;//--------------------------------------------
return          RN 0
pTable          RN 9
pc              RN 15
r0x01010101     RN 1
r0x80808080     RN 0

tVal0           RN 0
tVal1           RN 1
tVal2           RN 2
tVal4           RN 4
tVal6           RN 6
tVal7           RN 7
tVal8           RN 8
tVal9           RN 9
tVal10          RN 10
tVal11          RN 11
tVal12          RN 12
tVal14          RN 14

Out0            RN 6
Out1            RN 7
Out2            RN 8
Out3            RN 9

Left0           RN 6
Left1           RN 7
Left2           RN 8
Left3           RN 9

Above0123       RN 12
Above4567       RN 14

AboveLeft       RN 10

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
;// omxVCM4P10_PredictIntra_4x4 starts
;//-----------------------------------------------------------------------------------------------
        
        ;// Write function header
        M_START omxVCM4P10_PredictIntra_4x4, r11
        
        ;// Define stack arguments
        M_ARG    LeftStep,     4
        M_ARG    DstStep,      4
        M_ARG    PredMode,     4
        M_ARG    Availability, 4
        
        ;// M_STALL ARM1136JS=4
        
        LDR      pTable,=armVCM4P10_pSwitchTable4x4  ;// Load index table for switch case
        
        ;// Load argument from the stack
        M_LDR    predMode, PredMode                  ;// Arg predMode loaded from stack to reg 
        M_LDR    leftStep, LeftStep                  ;// Arg leftStep loaded from stack to reg 
        M_LDR    dstStep,  DstStep                   ;// Arg dstStep loaded from stack to reg         
        M_LDR    availability, Availability          ;// Arg availability loaded from stack to reg 
        
        LDR      pc, [pTable, predMode, LSL #2]      ;// Branch to the case based on preMode

OMX_VC_4x4_VERT
        
        LDR      Above0123,  [pSrcAbove]             ;// Above0123 = pSrcAbove[0 to 3]
        M_STR    Above0123,  [pDst],  dstStep        ;// pDst[0  to 3]  = Above0123
        M_STR    Above0123,  [pDst],  dstStep        ;// pDst[4  to 7]  = Above0123
        M_STR    Above0123,  [pDst],  dstStep        ;// pDst[8  to 11] = Above0123
        STR      Above0123,  [pDst]                  ;// pDst[12 to 15] = Above0123
        MOV      return, #OMX_Sts_NoErr
        M_EXIT                                      ;// Macro to exit midway-break frm case

OMX_VC_4x4_HOR
        
        ;// M_STALL ARM1136JS=6 
        
        LDR      r0x01010101,  =MUL_CONST0           ;// Const to repeat the byte in reg 4 times
        M_LDRB   Left0,  [pSrcLeft],  leftStep       ;// Left0 = pSrcLeft[0]
        M_LDRB   Left1,  [pSrcLeft],  leftStep       ;// Left1 = pSrcLeft[1]
        M_LDRB   Left2,  [pSrcLeft],  leftStep       ;// Left2 = pSrcLeft[2]
        LDRB     Left3,  [pSrcLeft]                  ;// Left3 = pSrcLeft[3]
        MUL      Out0,   Left0,   r0x01010101        ;// replicate the val in all the bytes
        MUL      Out1,   Left1,   r0x01010101        ;// replicate the val in all the bytes
        MUL      Out2,   Left2,   r0x01010101        ;// replicate the val in all the bytes
        MUL      Out3,   Left3,   r0x01010101        ;// replicate the val in all the bytes
        M_STR    Out0,   [pDst],  dstStep            ;// store {Out0} at pDst [0  to 3 ] 
        M_STR    Out1,   [pDst],  dstStep            ;// store {Out1} at pDst [4  to 7 ]
        M_STR    Out2,   [pDst],  dstStep            ;// store {Out2} at pDst [8  to 11]
        STR      Out3,   [pDst]                      ;// store {Out3} at pDst [12 to 15]
        MOV      return, #OMX_Sts_NoErr
        M_EXIT                                       ;// Macro to exit midway-break frm case
        
OMX_VC_4x4_DC
        
        ;// M_STALL ARM1136JS=6
        
        AND      availability,  availability,  #(OMX_VC_UPPER + OMX_VC_LEFT)
        CMP      availability,  #(OMX_VC_UPPER + OMX_VC_LEFT)
        BNE      UpperOrLeftOrNoneAvailable          ;// Jump to Upper if not both
        LDR      Above0123,  [pSrcAbove]             ;// Above0123  = pSrcAbove[0 to 3]
        
        ;// M_STALL ARM1136JS=1
        
        UXTB16   tVal7,  Above0123                   ;// pSrcAbove[0, 2]
        UXTB16   tVal6,  Above0123,  ROR #8          ;// pSrcAbove[1, 3]
        UADD16   tVal11, tVal6,   tVal7              ;// pSrcAbove[0, 2] + pSrcAbove[1, 3]
        M_LDRB   Left0,  [pSrcLeft],  leftStep       ;// Left0 = pSrcLeft[0]
        M_LDRB   Left1,  [pSrcLeft],  leftStep       ;// Left1 = pSrcLeft[1]
        ADD      tVal11, tVal11,  LSR #16            ;// sum(pSrcAbove[0] to pSrcAbove[3])
        M_LDRB   Left2,  [pSrcLeft],  leftStep       ;// Left2 = pSrcLeft[2]
        LDRB     Left3,  [pSrcLeft]                  ;// Left3 = pSrcLeft[3]
        UXTH     tVal11, tVal11                      ;// upsum1 (Clear the top junk bits)
        ADD      tVal6,  Left0,  Left1               ;// tVal6 = Left0 + Left1
        ADD      tVal7,  Left2,  Left3               ;// tVal7 = Left2 + Left3
        ADD      tVal6,  tVal6,  tVal7               ;// tVal6 = tVal6 + tVal7
        ADD      Out0,   tVal6,  tVal11              ;// Out0  = tVal6 + tVal11   
        ADD      Out0,   Out0,   #4                  ;// Out0  = Out0 + 4
        LDR      r0x01010101,   =MUL_CONST0          ;// 0x01010101
        MOV      Out0,   Out0,  LSR #3               ;// Out0 = (Out0 + 4)>>3
        
        ;// M_STALL ARM1136JS=1
        
        MUL      Out0,   Out0,  r0x01010101          ;// replicate the val in all the bytes
        
        ;// M_STALL ARM1136JS=1
        
        MOV      return,  #OMX_Sts_NoErr
        M_STR    Out0,   [pDst],  dstStep            ;// store {Out0} at pDst [0  to 3 ]
        M_STR    Out0,   [pDst],  dstStep            ;// store {Out0} at pDst [4  to 7 ]
        M_STR    Out0,   [pDst],  dstStep            ;// store {Out0} at pDst [8  to 11]
        STR      Out0,   [pDst]                      ;// store {Out0} at pDst [12 to 15]
        M_EXIT                                       ;// Macro to exit midway-break frm case
        
UpperOrLeftOrNoneAvailable
        ;// M_STALL ARM1136JS=3
        
        CMP      availability,  #OMX_VC_UPPER        ;// if(availability & #OMX_VC_UPPER)
        BNE      LeftOrNoneAvailable                 ;// Jump to Left if not upper
        LDR      Above0123,  [pSrcAbove]             ;// Above0123  = pSrcAbove[0 to 3]
        
        ;// M_STALL ARM1136JS=3
        
        UXTB16   tVal7,  Above0123                   ;// pSrcAbove[0, 2]
        UXTB16   tVal6,  Above0123,  ROR #8          ;// pSrcAbove[1, 3]
        UADD16   Out0,   tVal6,  tVal7               ;// pSrcAbove[0, 2] + pSrcAbove[1, 3]
        LDR      r0x01010101,   =MUL_CONST0          ;// 0x01010101
        ADD      Out0,   Out0,   LSR #16             ;// sum(pSrcAbove[0] to pSrcAbove[3])
        
        ;// M_STALL ARM1136JS=1
        
        UXTH     Out0,   Out0                        ;// upsum1 (Clear the top junk bits)
        ADD      Out0,   Out0,   #2                  ;// Out0  = Out0 + 2
        
        ;// M_STALL ARM1136JS=1
        
        MOV      Out0,   Out0,   LSR #2              ;// Out0  = (Out0 + 2)>>2
        
        ;// M_STALL ARM1136JS=1
        
        MUL      Out0,   Out0,   r0x01010101         ;// replicate the val in all the bytes
        
        ;// M_STALL ARM1136JS=1
        
        MOV      return, #OMX_Sts_NoErr
        M_STR    Out0,   [pDst],  dstStep            ;// store {tVal6} at pDst [0  to 3 ]
        M_STR    Out0,   [pDst],  dstStep            ;// store {tVal6} at pDst [4  to 7 ] 
        M_STR    Out0,   [pDst],  dstStep            ;// store {tVal6} at pDst [8  to 11]
        STR      Out0,   [pDst]                      ;// store {tVal6} at pDst [12 to 15]
        
        M_EXIT                                       ;// Macro to exit midway-break frm case
        
LeftOrNoneAvailable        
        ;// M_STALL ARM1136JS=3
        
        LDR      r0x01010101,   =MUL_CONST0          ;// 0x01010101
        CMP      availability, #OMX_VC_LEFT
        BNE      NoneAvailable
        M_LDRB   Left0,  [pSrcLeft],  leftStep       ;// Left0 = pSrcLeft[0]
        M_LDRB   Left1,  [pSrcLeft],  leftStep       ;// Left1 = pSrcLeft[1]
        M_LDRB   Left2,  [pSrcLeft],  leftStep       ;// Left2 = pSrcLeft[2]
        LDRB     Left3,  [pSrcLeft]                  ;// Left3 = pSrcLeft[3]
        ADD      Out0,   Left0,  Left1               ;// Out0  = Left0 + Left1
        
        ;// M_STALL ARM1136JS=1
        
        ADD      Out1,   Left2,  Left3               ;// Out1  = Left2 + Left3
        ADD      Out0,   Out0,   Out1                ;// Out0  = Out0  + Out1
        ADD      Out0,   Out0,   #2                  ;// Out0  = Out0 + 2
        
        ;// M_STALL ARM1136JS=1
        
        MOV      Out0,   Out0,   LSR #2              ;// Out0  = (Out0 + 2)>>2
        
        ;// M_STALL ARM1136JS=1
        
        MUL      Out0,   Out0,   r0x01010101         ;// replicate the val in all the bytes
        
        ;// M_STALL ARM1136JS=1
        
        MOV      return, #OMX_Sts_NoErr
        M_STR    Out0,   [pDst],  dstStep            ;// store {Out0} at pDst [0  to 3 ]
        M_STR    Out0,   [pDst],  dstStep            ;// store {Out0} at pDst [4  to 7 ] 
        M_STR    Out0,   [pDst],  dstStep            ;// store {Out0} at pDst [8  to 11]
        STR      Out0,   [pDst]                      ;// store {Out0} at pDst [12 to 15]
        M_EXIT                                       ;// Macro to exit midway-break frm case

NoneAvailable
        MOV      Out0,   #128                        ;// Out0 = 128 if(count == 0)
        
        ;// M_STALL ARM1136JS=5
        
        MUL      Out0,   Out0,  r0x01010101          ;// replicate the val in all the bytes
        
        ;// M_STALL ARM1136JS=1
        
        MOV      return, #OMX_Sts_NoErr
        M_STR    Out0,   [pDst],  dstStep            ;// store {Out0} at pDst [0  to 3 ]
        M_STR    Out0,   [pDst],  dstStep            ;// store {Out0} at pDst [4  to 7 ] 
        M_STR    Out0,   [pDst],  dstStep            ;// store {Out0} at pDst [8  to 11]
        STR      Out0,   [pDst]                      ;// store {Out0} at pDst [12 to 15]
        M_EXIT                                       ;// Macro to exit midway-break frm case
        
OMX_VC_4x4_DIAG_DL
        
        ;//------------------------------------------------------------------
        ;// f = (a+2*b+c+2)>>2
        ;// Calculate as:
        ;// d = (a + c )>>1
        ;// e = (d - b')>>1
        ;// f = e + 128
        ;//------------------------------------------------------------------
        
        ;// M_STALL ARM1136JS=3
        
        TST      availability, #OMX_VC_UPPER_RIGHT                  
        LDMIA    pSrcAbove,  {Above0123, Above4567}  ;// Above0123, Above4567 = pSrcAbove[0 to 7]
        LDR      r0x80808080,  =ADD_CONST1           ;// 0x80808080
        BNE      DLUpperRightAvailable
        LDR      r0x01010101,  =MUL_CONST0           ;// 0x01010101
        MOV      tVal7,  Above0123,  LSR #24         ;// {00,  00,  00,  U3 }
        MOV      tVal11, tVal7,  LSL #24             ;// {U3,  00,  00,  00 }
        MUL      Out3,   tVal7,  r0x01010101         ;// {U3,  U3,  U3,  U3 } 
        MOV      tVal8,  Above0123,  LSR #16         ;// {00,  00,  U3,  U2 }
        MOV      tVal10, Above0123,  LSR #8          ;// {00,  U3,  U2,  U1 }
        MVN      tVal10, tVal10                      ;// {00', U3', U2', U1'}
        UHADD8   tVal8,  tVal8,  Above0123           ;// {xx,  xx,  d1,  d0 }
        UHADD8   tVal6,  Above0123,  tVal9           ;// {xx,  d2,  xx,  xx }
        UHSUB8   tVal8,  tVal8,  tVal10              ;// {xx,  xx,  e1,  e0 }
        UHSUB8   tVal6,  tVal6,  tVal10              ;// {xx,  e2,  xx,  xx }
        UADD8    tVal8,  tVal8,  r0x80808080         ;// {xx,  xx,  f1,  f0 }
        UADD8    tVal6,  tVal6,  r0x80808080         ;// {xx,  f2,  xx,  xx }
        
        ;// M_STALL ARM1136JS=1
        
        PKHBT    tVal6,  tVal8,  tVal6               ;// {xx,  f2,  f1,  f0 }
        BIC      tVal6,  tVal6,  #0xFF000000         ;// {00,  f2,  f1,  f0 }
        ORR      Out0,   tVal6,  tVal11              ;// {U3,  f2,  f1,  f0 }
        
        ;// M_STALL ARM1136JS=1
        
        PKHTB    Out1,   Out3,   Out0,  ASR #8       ;// {U3,  U3,  f2,  f1 }
        MOV      return, #OMX_Sts_NoErr
        PKHTB    Out2,   Out3,   Out1,  ASR #8       ;// {U3,  U3,  U3,  f2 }
        
        M_STR    Out0,   [pDst], dstStep             ;// store {f3 to f0} at pDst[3  to 0 ]
        M_STR    Out1,   [pDst], dstStep             ;// store {f4 to f1} at pDst[7  to 4 ]
        M_STR    Out2,   [pDst], dstStep             ;// store {f5 to f2} at pDst[11 to 8 ]
        STR      Out3,   [pDst]                      ;// store {f6 to f3} at pDSt[15 to 12]
        M_EXIT                                       ;// Macro to exit midway-break frm case

DLUpperRightAvailable        
        
        MOV      tVal8,  Above0123,  LSR #24         ;// {00,  00,  00,  U3 }
        MOV      tVal9,  Above0123,  LSR #16         ;// {00,  00,  U3,  U2 }
        MOV      tVal10, Above0123,  LSR #8          ;// {00,  U3,  U2,  U1 }
        ORR      tVal8,  tVal8,  Above4567, LSL #8   ;// {U6,  U5,  U4,  U3 }
        ORR      tVal10, tVal10, Above4567, LSL #24  ;// {U4,  U3,  U2,  U1 }
        PKHBT    tVal9,  tVal9,  Above4567, LSL #16  ;// {U5,  U4,  U3,  U2 }
        MVN      tVal1,  tVal8                       ;// {U6', U5', U4', U3'}
        MVN      tVal10, tVal10                      ;// {U4', U3', U2', U1'}
        MVN      tVal2,  Above4567                   ;// {U7', U6', U5', U4'}
        UHADD8   tVal6,  Above0123,  tVal9           ;// {d3,  d2,  d1,  d0 }
        UHADD8   tVal9,  tVal9,  Above4567           ;// {d5,  d4,  d3,  d2 }
        UHADD8   tVal8,  Above4567,  tVal8           ;// {d6,  xx,  xx,  xx }
        UHSUB8   tVal6,  tVal6,  tVal10              ;// {e3,  e2,  e1,  e0 }
        UHSUB8   tVal12, tVal9,  tVal1               ;// {e5,  e4,  e3,  e2 }
        UHSUB8   tVal8,  tVal8,  tVal2               ;// {e6,  xx,  xx,  xx }
        UADD8    Out0,   tVal6,  r0x80808080         ;// {f3,  f2,  f1,  f0 }
        UADD8    tVal9,  tVal8,  r0x80808080         ;// {f6,  xx,  xx,  xx }
        UADD8    Out2,   tVal12, r0x80808080         ;// {f5,  f4,  f3,  f2 }
        MOV      tVal7,  Out0,   LSR #8              ;// {00,  f3,  f2,  f1 }
        AND      tVal9,  tVal9,  #0xFF000000         ;// {f6,  00,  00,  00 }
        PKHBT    Out1,   tVal7,  Out2,  LSL #8       ;// {f4,  f3,  f2,  f1 }
        ORR      Out3,   tVal9,  Out2,  LSR #8       ;// {f6,  f5,  f4,  f3 }
        M_STR    Out0,   [pDst], dstStep             ;// store {f3 to f0} at pDst[3  to 0 ]
        M_STR    Out1,   [pDst], dstStep             ;// store {f4 to f1} at pDst[7  to 4 ]
        M_STR    Out2,   [pDst], dstStep             ;// store {f5 to f2} at pDst[11 to 8 ]
        STR      Out3,   [pDst]                      ;// store {f6 to f3} at pDSt[15 to 12]
        MOV      return, #OMX_Sts_NoErr
        M_EXIT                                       ;// Macro to exit midway-break frm case
        

OMX_VC_4x4_DIAG_DR
        
        ;// M_STALL ARM1136JS=4
        
        M_LDRB   Left0,  [pSrcLeft],  leftStep       ;// Left0 = pSrcLeft[0]
        M_LDRB   Left1,  [pSrcLeft],  leftStep       ;// Left1 = pSrcLeft[1]
        M_LDRB   Left2,  [pSrcLeft],  leftStep       ;// Left2 = pSrcLeft[2]
        LDRB     Left3,  [pSrcLeft]                  ;// Left3 = pSrcLeft[3]
        LDRB     AboveLeft, [pSrcAboveLeft]          ;// AboveLeft = pSrcAboveLeft[0]
        ORR      tVal7,  Left1,  Left0,  LSL #8      ;// tVal7 = 00 00 L0 L1
        LDR      Above0123,  [pSrcAbove]             ;// Above0123 = U3 U2 U1 U0
        LDR      r0x80808080, =ADD_CONST1            ;// 0x80808080
        ORR      tVal8,  Left3,  Left2,  LSL #8      ;// tVal8 = 00 00 L2 L3
        PKHBT    tVal7,  tVal8,  tVal7,  LSL #16     ;// tVal7 = L0 L1 L2 L3
        MOV      tVal8,  Above0123,  LSL #8          ;// tVal8 = U2 U1 U0 00
        MOV      tVal9,  tVal7,  LSR #8              ;// tVal9 = 00 L0 L1 L2
        ORR      tVal8,  tVal8,  AboveLeft           ;// tVal8 = U2 U1 U0 UL
        ORR      tVal9,  tVal9,  AboveLeft, LSL #24  ;// tVal9 = UL L0 L1 L2
        MOV      tVal10, Above0123,  LSL #24         ;// tVal10= U0 00 00 00
        UXTB     tVal11, tVal7,  ROR #24             ;// tVal11= 00 00 00 L0
        ORR      tVal10, tVal10, tVal9,  LSR #8      ;// tVal10= U0 UL L0 L1
        ORR      tVal11, tVal11, tVal8,  LSL #8      ;// tVal11= U1 U0 UL L0
        UHADD8   tVal11, Above0123,  tVal11          ;// tVal11= d1 d0 dL g0
        UHADD8   tVal10, tVal7,  tVal10              ;// tVal10= g0 g1 g2 g3
        MVN      tVal8,  tVal8                       ;// tVal8 = U2'U1'U0'UL'
        MVN      tVal9,  tVal9                       ;// tVal9 = UL'L0'L1'L2'
        UHSUB8   tVal11, tVal11, tVal8               ;// tVal11= e1 e0 eL h0
        UHSUB8   tVal10, tVal10, tVal9               ;// tVal10= h0 h1 h2 h3
        UADD8    Out3,   tVal10, r0x80808080         ;// Out3  = i0 i1 i2 i3
        UADD8    Out0,   tVal11, r0x80808080         ;// Out0  = f1 f0 fL i0
        UXTH     tVal11, Out3,   ROR #8              ;// tVal11= 00 00 i1 i2
        MOV      tVal7,  Out0,   LSL #8              ;// tVal7 = f0 fL i0 00
        ORR      Out1,   tVal7,  tVal11,  LSR #8     ;// Out1  = f0 fL i0 i1
        PKHBT    Out2,   tVal11, Out0,    LSL #16    ;// Out2  = fL i0 i1 i2
        M_STR    Out0,   [pDst], dstStep             ;// store {f1 to i0} at pDst[3  to 0 ]
        M_STR    Out1,   [pDst], dstStep             ;// store {f0 to i1} at pDst[7  to 4 ]
        M_STR    Out2,   [pDst], dstStep             ;// store {fL to i2} at pDst[11 to 8 ]
        STR      Out3,   [pDst]                      ;// store {i0 to i3} at pDst[15 to 12] 
        MOV      return,  #OMX_Sts_NoErr
        M_EXIT                                       ;// Macro to exit midway-break frm case

OMX_VC_4x4_VR

        ;// M_STALL ARM1136JS=4
        
        LDR      Above0123,  [pSrcAbove]             ;// Above0123 = U3 U2 U1 U0
        LDRB     AboveLeft,  [pSrcAboveLeft]         ;// AboveLeft = 00 00 00 UL
        M_LDRB   Left0,  [pSrcLeft],  leftStep       ;// Left0     = 00 00 00 L0
        M_LDRB   Left1,  [pSrcLeft],  leftStep       ;// Left1     = 00 00 00 L1
        LDRB     Left2,  [pSrcLeft]                  ;// Left2     = 00 00 00 L2
        MOV      tVal0,  Above0123,  LSL #8          ;// tVal0     = U2 U1 U0 00
        MOV      tVal9,  Above0123                   ;// tVal9     = U3 U2 U1 U0 
        ORR      tVal14, tVal0,   AboveLeft          ;// tVal14    = U2 U1 U0 UL
        MVN      tVal11, tVal14                      ;// tVal11    = U2'U1'U0'UL'
        MOV      tVal2,  tVal14,  LSL #8             ;// tVal2     = U1 U0 UL 00
        UHSUB8   tVal1,  Above0123,  tVal11          ;// tVal1     = d2 d1 d0 dL
        UHADD8   tVal10, AboveLeft, Left1            ;// tVal10    = 00 00 00 j1       
        MVN      tVal4,  Left0                       ;// tVal4     = 00 00 00 L0'
        UHSUB8   tVal4,  tVal10,  tVal4              ;// tVal4     = 00 00 00 k1
        ORR      tVal12, tVal0,   Left0              ;// tVal12    = U2 U1 U0 L0
        ORR      tVal14, tVal2,   Left0              ;// tVal14    = U1 U0 UL L0
        LDR      r0x80808080,  =ADD_CONST1           ;// 0x80808080
        UHADD8   tVal10, tVal9,   tVal14             ;// tVal10    = g3 g2 g1 g0
        UADD8    Out0,   tVal1,   r0x80808080        ;// Out0      = e2 e1 e0 eL
        UHSUB8   tVal10, tVal10,  tVal11             ;// tVal10    = h3 h2 h1 h0
        M_STR    Out0,   [pDst],  dstStep            ;// store {e2 to eL} at pDst[3  to 0 ]
        MOV      tVal1,  tVal14,  LSL #8             ;// tVal1     = U0 UL L0 00
        MOV      tVal6,  Out0,    LSL #8             ;// tVal6     = e1 e0 eL 00
        ORR      tVal2,  tVal2,   Left1              ;// tVal2     = U1 U0 UL L1
        UADD8    tVal4,  tVal4,   r0x80808080        ;// tVal4     = 00 00 00 l1        
        UADD8    Out1,   tVal10,  r0x80808080        ;// Out1      = i3 i2 i1 i0
        MVN      tVal2,  tVal2                       ;// tVal14    = U1'U0'UL'L1'
        ORR      tVal1,  tVal1,   Left2              ;// tVal1     = U0 UL L0 L2
        ORR      Out2,   tVal6,   tVal4              ;// Out2      = e1 e0 eL l1
        UHADD8   tVal1,  tVal1,   tVal12             ;// tVal1     = g2 g1 g0 j2
        M_STR    Out1,   [pDst],  dstStep            ;// store {i3 to i0} at pDst[7  to 4 ]
        M_STR    Out2,   [pDst],  dstStep            ;// store {e1 to l1} at pDst[11 to 8 ]
        UHSUB8   tVal9,  tVal1,   tVal2              ;// tVal9     = h2 h1 h0 k2
        UADD8    Out3,   tVal9,   r0x80808080        ;// Out3      = i2 i1 i0 l2
        STR      Out3,   [pDst]                      ;// store {i2 to l2} at pDst[15 to 12] 
        MOV      return,  #OMX_Sts_NoErr
        M_EXIT                                       ;// Macro to exit midway-break frm case
        
OMX_VC_4x4_HD
        
        ;// M_STALL ARM1136JS=4
        
        LDR      Above0123,  [pSrcAbove]             ;// Above0123 = U3 U2 U1 U0
        LDRB     AboveLeft,  [pSrcAboveLeft]         ;// AboveLeft = 00 00 00 UL
        M_LDRB   Left0,  [pSrcLeft],  leftStep       ;// Left0 = 00 00 00 L0
        M_LDRB   Left1,  [pSrcLeft],  leftStep       ;// Left1 = 00 00 00 L1
        M_LDRB   Left2,  [pSrcLeft],  leftStep       ;// Left2 = 00 00 00 L2
        LDRB     Left3,  [pSrcLeft]                  ;// Left3 = 00 00 00 L3
        LDR      r0x80808080,  =ADD_CONST1           ;// 0x80808080
        ORR      tVal2,  AboveLeft, Above0123, LSL #8;// tVal2 = U2 U1 U0 UL
        MVN      tVal1,  Left0                       ;// tVal1 = 00 00 00 L0'
        ORR      tVal4,  Left0,  tVal2,  LSL #8      ;// tVal4 = U1 U0 UL L0
        MVN      tVal2,  tVal2                       ;// tVal2 = U2'U1'U0'UL'
        UHADD8   tVal4,  tVal4,  Above0123           ;// tVal4 = g3 g2 g1 g0
        UHSUB8   tVal1,  AboveLeft,  tVal1           ;// tVal1 = 00 00 00 dL
        UHSUB8   tVal4,  tVal4,  tVal2               ;// tVal4 = h3 h2 h1 h0
        UADD8    tVal1,  tVal1,  r0x80808080         ;// tVal1 = 00 00 00 eL
        UADD8    tVal4,  tVal4,  r0x80808080         ;// tVal4 = i3 i2 i1 i0
        ORR      tVal2,  Left0,  AboveLeft,  LSL #16 ;// tVal2 = 00 UL 00 L0
        MOV      tVal4,  tVal4,  LSL #8              ;// tVal4 = i2 i1 i0 00
        ORR      tVal11, Left1,  Left0,  LSL #16     ;// tVal11= 00 L0 00 L1
        ORR      tVal7,  Left2,  Left1,  LSL #16     ;// tVal7 = 00 L1 00 L2
        ORR      tVal10, Left3,  Left2,  LSL #16     ;// tVal10= 00 L2 00 L3
        ORR      Out0,   tVal4,  tVal1               ;// Out0  = i2 i1 i0 eL
        M_STR    Out0,   [pDst], dstStep             ;// store {Out0}  at pDst [0  to 3 ] 
        MOV      tVal4,  Out0,   LSL #16             ;// tVal4 = i1 i0 00 00
        UHADD8   tVal2,  tVal2,  tVal7               ;// tVal2 = 00 j1 00 j2
        UHADD8   tVal6,  tVal11, tVal10              ;// tVal11= 00 j2 00 j3
        MVN      tVal12, tVal11                      ;// tVal12= 00 L0'00 L1'
        MVN      tVal14, tVal7                       ;// tVal14= 00 L1'00 L2'
        UHSUB8   tVal2,  tVal2,  tVal12              ;// tVal2 = 00 k1 00 k2
        UHSUB8   tVal8,  tVal7,  tVal12              ;// tVal8 = 00 d1 00 d2
        UHSUB8   tVal11, tVal6,  tVal14              ;// tVal11= 00 k2 00 k3
        UHSUB8   tVal9,  tVal10, tVal14              ;// tVal9 = 00 d2 00 d3
        UADD8    tVal2,  tVal2,  r0x80808080         ;// tVal2 = 00 l1 00 l2
        UADD8    tVal8,  tVal8,  r0x80808080         ;// tVal8 = 00 e1 00 e2
        UADD8    tVal11, tVal11, r0x80808080         ;// tVal11= 00 l2 00 l3
        UADD8    tVal9,  tVal9,  r0x80808080         ;// tVal9 = 00 e2 00 e3
        ORR      Out2,   tVal8,  tVal2,  LSL #8      ;// Out2  = l1 e1 l2 e2
        ORR      Out3,   tVal9,  tVal11, LSL #8      ;// Out3  = l2 e2 l3 e3
        PKHTB    Out1,   tVal4,  Out2,   ASR #16     ;// Out1  = i1 i0 l1 e1
        M_STR    Out1,   [pDst], dstStep             ;// store {Out1}  at pDst [4  to 7 ]
        M_STR    Out2,   [pDst], dstStep             ;// store {Out2}  at pDst [8  to 11]
        STR      Out3,   [pDst]                      ;// store {Out3}  at pDst [12 to 15]
        MOV      return,  #OMX_Sts_NoErr
        M_EXIT                                       ;// Macro to exit midway-break frm case
        
OMX_VC_4x4_VL
        
        ;// M_STALL ARM1136JS=3
        
        LDMIA    pSrcAbove, {Above0123, Above4567}   ;// Above0123, Above4567 = pSrcAbove[0 to 7]
        TST      availability, #OMX_VC_UPPER_RIGHT
        LDR      r0x80808080,  =ADD_CONST1           ;// 0x80808080
        LDR      r0x01010101,  =MUL_CONST0           ;// 0x01010101
        MOV      tVal11, Above0123,  LSR #24         ;// tVal11= 00 00 00 U3
        MULEQ    Above4567, tVal11, r0x01010101      ;// Above4567 = U3 U3 U3 U3
        MOV      tVal9,  Above0123,  LSR #8          ;// tVal9 = 00 U3 U2 U1
        MVN      tVal10, Above0123                   ;// tVal10= U3'U2'U1'U0'
        ORR      tVal2,  tVal9,  Above4567,  LSL #24 ;// tVal2 = U4 U3 U2 U1
        UHSUB8   tVal8,  tVal2,  tVal10              ;// tVal8 = d4 d3 d2 d1
        UADD8    Out0,   tVal8,  r0x80808080         ;// Out0 = e4 e3 e2 e1
        M_STR    Out0,   [pDst], dstStep             ;// store {Out0}  at pDst [0  to 3 ]
        MOV      tVal9,  tVal9,  LSR #8              ;// tVal9 = 00 00 U3 U2
        MOV      tVal10, Above4567,  LSL #8          ;// tVal10= U6 U5 U4 00
        PKHBT    tVal9,  tVal9,  Above4567, LSL #16  ;// tVal9 = U5 U4 U3 U2
        ORR      tVal10, tVal10, tVal11              ;// tVal10= U6 U5 U4 U3
        UHADD8   tVal11, tVal9,  Above0123           ;// tVal11= g5 g4 g3 g2
        UHADD8   tVal14, tVal2,  tVal10              ;// tVal14= g6 g5 g4 g3
        MVN      tVal8,  tVal2                       ;// tVal8 = U4'U3'U2'U1'
        MVN      tVal7,  tVal9                       ;// tVal7 = U5'U4'U3'U2'
        UHSUB8   tVal12, tVal9,  tVal8               ;// tVal12= d5 d4 d3 d2
        UHSUB8   tVal11, tVal11, tVal8               ;// tVal11= h5 h4 h3 h2
        UHSUB8   tVal2,  tVal14, tVal7               ;// tVal2 = h6 h5 h4 h3
        UADD8    Out1,   tVal11, r0x80808080         ;// Out1  = i5 i4 i3 i2
        UADD8    Out2,   tVal12, r0x80808080         ;// Out2  = e5 e4 e3 e2
        UADD8    Out3,   tVal2,  r0x80808080         ;// Out3  = i6 i5 i4 i3
        M_STR    Out1,   [pDst], dstStep             ;// store {Out1} at pDst [4  to 7 ]
        M_STR    Out2,   [pDst], dstStep             ;// store {Out2} at pDst [8  to 11]
        M_STR    Out3,   [pDst], dstStep             ;// store {Out3} at pDst [12 to 15]
        MOV      return, #OMX_Sts_NoErr
        M_EXIT                                       ;// Macro to exit midway-break frm case
        
OMX_VC_4x4_HU
        
        ;// M_STALL ARM1136JS=2
        
        LDR      r0x01010101,  =MUL_CONST0           ;// 0x01010101
        M_LDRB   Left0,  [pSrcLeft],  leftStep       ;// Left0 = pSrcLeft[0]
        M_LDRB   Left1,  [pSrcLeft],  leftStep       ;// Left1 = pSrcLeft[1]
        M_LDRB   Left2,  [pSrcLeft],  leftStep       ;// Left2 = pSrcLeft[2]
        LDRB     Left3,  [pSrcLeft]                  ;// Left3 = pSrcLeft[3]
        MOV      r0x80808080,  r0x01010101, LSL #7   ;// 0x80808080
        ORR      tVal6,  Left0,  Left1,  LSL #16     ;// tVal6 = 00 L1 00 L0
        ORR      tVal7,  Left1,  Left2,  LSL #16     ;// tVal7 = 00 L2 00 L1
        ORR      tVal11, Left2,  Left3,  LSL #16     ;// tVal11= 00 L3 00 L2
        MUL      Out3,   Left3,  r0x01010101         ;// Out3  = L3 L3 L3 L3
        MVN      tVal8,  tVal7                       ;// tVal8 = 00 L2'00 L1'
        MVN      tVal10, tVal11                      ;// tVal10= 00 L3'00 L2'
        UHADD8   tVal4,  tVal6,  tVal11              ;// tVal4 = 00 g3 00 g2
        UXTB16   tVal12, Out3                        ;// tVal12= 00 L3 00 L3
        UHSUB8   tVal4,  tVal4,  tVal8               ;// tVal4 = 00 h3 00 h2
        UHSUB8   tVal6,  tVal6,  tVal8               ;// tVal6 = 00 d2 00 d1
        UHSUB8   tVal11, tVal11, tVal8               ;// tVal11= 00 d3 00 d2
        UHADD8   tVal12, tVal12, tVal7               ;// tVal12= 00 g4 00 g3
        UADD8    tVal4,  tVal4,  r0x80808080         ;// tVal4 = 00 i3 00 i2
        UHSUB8   tVal12, tVal12, tVal10              ;// tVal12= 00 h4 00 h3
        UADD8    tVal8,  tVal6,  r0x80808080         ;// tVal8 = 00 e2 00 e1
        UADD8    tVal11, tVal11, r0x80808080         ;// tVal11= 00 e3 00 e2
        UADD8    tVal12, tVal12, r0x80808080         ;// tVal12= 00 i4 00 i3
        ORR      Out0,   tVal8,  tVal4,  LSL #8      ;// Out0  = i3 e2 i2 e1
        ORR      Out1,   tVal11, tVal12, LSL #8      ;// Out1  = i4 e3 i3 e2
        M_STR    Out0,   [pDst], dstStep             ;// store {Out0}  at pDst [0  to 3 ]
        PKHTB    Out2,   Out3,   Out1,   ASR #16     ;// Out2  = L3 L3 i4 e3
        M_STR    Out1,   [pDst], dstStep             ;// store {Out1}  at pDst [4  to 7 ]
        M_STR    Out2,   [pDst], dstStep             ;// store {Out2}  at pDst [8  to 11]
        STR      Out3,   [pDst]                      ;// store {Out3}  at pDst [12 to 15]
        MOV      return,  #OMX_Sts_NoErr
        M_END

        ENDIF ;// ARM1136JS
        
        
        END
;//-----------------------------------------------------------------------------------------------
;// omxVCM4P10_PredictIntra_4x4 ends
;//-----------------------------------------------------------------------------------------------
