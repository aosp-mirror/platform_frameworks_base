;//
;// 
;// File Name:  armVCM4P10_DecodeCoeffsToPair_s.s
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
        INCLUDE armCOMM_BitDec_s.h
        
        IMPORT armVCM4P10_CAVLCCoeffTokenTables
        IMPORT armVCM4P10_CAVLCTotalZeroTables
        IMPORT armVCM4P10_CAVLCTotalZeros2x2Tables
        IMPORT armVCM4P10_CAVLCRunBeforeTables
        IMPORT armVCM4P10_SuffixToLevel
        IMPORT armVCM4P10_ZigZag_4x4
        IMPORT armVCM4P10_ZigZag_2x2
        
        M_VARIANTS ARM1136JS
        
;//DEBUG_ON    SETL {TRUE}
        
LAST_COEFF               EQU 0x20        ;// End of block flag
TWO_BYTE_COEFF           EQU 0x10

;// Declare input registers

ppBitStream     RN 0
pOffset         RN 1
pNumCoeff       RN 2
ppPosCoefbuf    RN 3
nC              RN 4 ;// number of coeffs or 17 for chroma
sMaxNumCoeff    RN 5

;// Declare inner loop registers

;// Level loop
Count           RN 0
TrailingOnes    RN 1
pLevel          RN 2
LevelSuffix     RN 3
SuffixLength    RN 4
TotalCoeff      RN 5

pVLDTable       RN 6
Symbol          RN 7
T1              RN 8
T2              RN 9
RBitStream      RN 10
RBitBuffer      RN 11
RBitCount       RN 12
lr              RN 14

;// Run loop
Count           RN 0
ZerosLeft       RN 1
pLevel          RN 2
ppRunTable      RN 3
pRun            RN 4
TotalCoeff      RN 5

pVLDTable       RN 6
Symbol          RN 7
T1              RN 8
T2              RN 9
RBitStream      RN 10
RBitBuffer      RN 11
RBitCount       RN 12
lr              RN 14

;// Fill in coefficients loop
pPosCoefbuf     RN 0
temp            RN 1
pLevel          RN 2
ppPosCoefbuf    RN 3
pRun            RN 4
TotalCoeff      RN 5
pZigZag         RN 6

T1              RN 8
T2              RN 9
RBitStream      RN 10
RBitBuffer      RN 11
RBitCount       RN 12
CoeffNum        RN 14



    IF ARM1136JS
        
        ;// Allocate stack memory required by the function
        M_ALLOC4 pppBitStream, 4
        M_ALLOC4 ppOffset, 4
        M_ALLOC4 pppPosCoefbuf, 4
        M_ALLOC4 ppLevel, 16*2
        M_ALLOC4 ppRun, 16
        
        ;// Write function header
        M_START armVCM4P10_DecodeCoeffsToPair, r11
        
        ;// Define stack arguments
        M_ARG   pNC, 4
        M_ARG   pSMaxNumCoeff,4
        
        ;// Code start        
        M_BD_INIT0 ppBitStream, pOffset, RBitStream, RBitBuffer, RBitCount
        LDR        pVLDTable, =armVCM4P10_CAVLCCoeffTokenTables
        M_LDR      nC, pNC
        
        M_BD_INIT1 T1, T2, lr
        LDR     pVLDTable, [pVLDTable, nC, LSL #2]  ;// Find VLD table    
        
        M_BD_INIT2 T1, T2, lr

        ;// Decode Symbol = TotalCoeff*4 + TrailingOnes
        M_BD_VLD  Symbol, T1, T2, pVLDTable, 4, 2
    
        MOVS    TotalCoeff, Symbol, LSR #2    
        STRB    TotalCoeff, [pNumCoeff]    
        M_PRINTF "TotalCoeff=%d\n", TotalCoeff
        BEQ.W   EndNoError                  ;// Finished if no coefficients

        CMP     Symbol, #17*4
        BGE.W   EndBadSymbol                ;// Error if bad symbol
        
        ;// Save bitstream pointers
        M_STR   ppBitStream,  pppBitStream
        M_STR   pOffset,      ppOffset
        M_STR   ppPosCoefbuf, pppPosCoefbuf                
        
        ;// Decode Trailing Ones
        ANDS    TrailingOnes, Symbol, #3
        M_ADR   pLevel, ppLevel            
        M_PRINTF "TrailingOnes=%d\n", TrailingOnes
        BEQ     TrailingOnesDone    
        MOV     Count, TrailingOnes
TrailingOnesLoop    
        M_BD_READ8 Symbol, 1, T1
        SUBS    Count, Count, #1
        MOV     T1, #1
        SUB     T1, T1, Symbol, LSL #1
        M_PRINTF "Level=%d\n", T1
        STRH    T1, [pLevel], #2
        BGT     TrailingOnesLoop
TrailingOnesDone    
    
        ;// Decode level values    
        SUBS    Count, TotalCoeff, TrailingOnes     ;// Number of levels to read
        BEQ     DecodeRuns                          ;// None left
        
        MOV     SuffixLength, #1
        CMP     TotalCoeff, #10
        MOVLE   SuffixLength, #0
        CMP     TrailingOnes, #3    ;// if (TrailingOnes<3)
        MOVLT   TrailingOnes, #4    ;// then TrailingOnes = +4
        MOVGE   TrailingOnes, #2    ;// else TrailingOnes = +2
        MOVGE   SuffixLength, #0    ;//      SuffixLength = 0
        
LevelLoop
        M_BD_CLZ16 Symbol, T1, T2   ;// Symbol=LevelPrefix
        CMP     Symbol,#16
        BGE     EndBadSymbol
        
        MOVS    lr, SuffixLength    ;// if LevelSuffixSize==0
        TEQEQ   Symbol, #14         ;//   and  LevelPrefix==14
        MOVEQ   lr, #4              ;//   then LevelSuffixSize=4
        TEQ     Symbol, #15         ;// if LevelSuffixSize==15
        MOVEQ   lr, #12             ;//   then LevelSuffixSize=12
        
        TEQEQ   SuffixLength,#0
        ADDEQ   Symbol,Symbol,#15
        
        TEQ     lr, #0              ;// if LevelSuffixSize==0
        BEQ     LevelCodeRead       ;// LevelCode = LevelPrefix
        
        M_BD_VREAD16 LevelSuffix, lr, T1, T2  ;// Read Level Suffix
        
        MOV     Symbol, Symbol, LSL SuffixLength
        ADD     Symbol, LevelSuffix, Symbol
             
LevelCodeRead        
        ;// Symbol = LevelCode
        ADD     Symbol, Symbol, TrailingOnes ;// +4 if level cannot be +/-1, +2 o/w
        MOV     TrailingOnes, #2
        MOVS    T1, Symbol, LSR #1
        RSBCS   T1, T1, #0                  ;// If Symbol odd then negate
        M_PRINTF "Level=%d\n", T1
        STRH    T1, [pLevel], #2            ;// Store level.
        
        LDR     T2, =armVCM4P10_SuffixToLevel
        LDRSB   T1, [T2, SuffixLength]      ;// Find increment level        
        TEQ     SuffixLength, #0
        MOVEQ   SuffixLength, #1
        CMP     Symbol, T1
        ADDCS   SuffixLength, SuffixLength, #1        
        SUBS    Count, Count, #1        
        BGT     LevelLoop
        
DecodeRuns        
        ;// Find number of zeros
        M_LDR   T1, pSMaxNumCoeff           ;// sMaxNumCoeff
        SUB     Count, TotalCoeff, #1       ;// Number of runs excluding last
        SUBS    ZerosLeft, T1, TotalCoeff   ;// Maximum number of zeros there could be
        M_ADR   pRun, ppRun
        MOV     CoeffNum,TotalCoeff
        SUB     CoeffNum,CoeffNum,#1
        BEQ     NoZerosLeft
        
        ;// Unpack number of zeros from bitstream
        TEQ     T1, #4        
        LDREQ   pVLDTable, =(armVCM4P10_CAVLCTotalZeros2x2Tables-4)
        LDRNE   pVLDTable, =(armVCM4P10_CAVLCTotalZeroTables-4)
        LDR     pVLDTable, [pVLDTable, TotalCoeff, LSL #2]
        
        M_BD_VLD  Symbol, T1, T2, pVLDTable, 4, 2 ;// Symbol = ZerosLeft
        CMP     Symbol,#16
        BGE     EndBadSymbol

        LDR     ppRunTable, =(armVCM4P10_CAVLCRunBeforeTables-4)
        M_ADR   pRun, ppRun
        MOVS    ZerosLeft, Symbol

        ADD     CoeffNum,CoeffNum,ZerosLeft        

        BEQ     NoZerosLeft
        
        ;// Decode runs while zeros are left and more than one coefficient
RunLoop 
        SUBS    Count, Count, #1
        LDR     pVLDTable, [ppRunTable, ZerosLeft, LSL#2]
        BLT     LastRun
        M_BD_VLD  Symbol, T1, T2, pVLDTable, 3, 2 ;// Symbol = Run
        CMP     Symbol,#15         
        BGE     EndBadSymbol        

        SUBS    ZerosLeft, ZerosLeft, Symbol
        M_PRINTF "Run=%d\n", Symbol
        STRB    Symbol, [pRun], #1
        BGT     RunLoop
        
        ;// Decode runs while no zeros are left
NoZerosLeft 
        SUBS    Count, Count, #1
        M_PRINTF "Run=%d\n", ZerosLeft
        STRGEB  ZerosLeft, [pRun], #1
        BGT     NoZerosLeft

LastRun        
        ;// Final run length is remaining zeros
        M_PRINTF "LastRun=%d\n", ZerosLeft
        STRB    ZerosLeft, [pRun], #1        
        
        ;// Write coefficients to output array
        M_LDR   T1, pSMaxNumCoeff                    ;// sMaxNumCoeff
        TEQ     T1, #15
        ADDEQ   CoeffNum,CoeffNum,#1
        

        SUB     pRun,pRun,TotalCoeff
        SUB     pLevel,pLevel,TotalCoeff  
        SUB     pLevel,pLevel,TotalCoeff   

        M_LDR   ppPosCoefbuf, pppPosCoefbuf
        LDR     pPosCoefbuf, [ppPosCoefbuf]
        TEQ     T1, #4
        LDREQ   pZigZag, =armVCM4P10_ZigZag_2x2
        LDRNE   pZigZag, =armVCM4P10_ZigZag_4x4

        
        
OutputLoop
        
        LDRB    T2, [pRun],#1
        LDRB    T1, [pZigZag, CoeffNum]
        SUB     CoeffNum, CoeffNum, #1      ;// Skip Non zero
        SUB     CoeffNum, CoeffNum, T2      ;// Skip Zero run
        
        LDRSH   T2, [pLevel],#2
        
        SUBS    TotalCoeff, TotalCoeff, #1       
        ORREQ   T1, T1, #LAST_COEFF
        
        ADD     temp, T2, #128
        CMP     temp, #256
        ORRCS   T1, T1, #TWO_BYTE_COEFF

        
        TEQ     TotalCoeff, #0              ;// Preserves carry        
        
        M_PRINTF "Output=%02x %04x\n", T1, T2
        STRB    T1, [pPosCoefbuf], #1
        STRB    T2, [pPosCoefbuf], #1
        MOV     T2, T2, LSR #8
        STRCSB  T2, [pPosCoefbuf], #1                
        BNE     OutputLoop
        
        ;// Finished
        STR     pPosCoefbuf, [ppPosCoefbuf]
        M_LDR   ppBitStream, pppBitStream
        M_LDR   pOffset, ppOffset
        B       EndNoError
            
EndBadSymbol
        MOV     r0, #OMX_Sts_Err
        B       End    
        
EndNoError
        ;// Finished reading from the bitstream                
        M_BD_FINI ppBitStream, pOffset
        
        ;// Set return value
        MOV     r0, #OMX_Sts_NoErr    
End
        M_END
    
    ENDIF
    
    END
    
