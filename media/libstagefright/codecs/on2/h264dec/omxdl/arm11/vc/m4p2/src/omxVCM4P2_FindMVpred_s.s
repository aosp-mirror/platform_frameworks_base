;//
;// 
;// File Name:  omxVCM4P2_FindMVpred_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   9641
;// Date:       Thursday, February 7, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//

;// Function:
;//     omxVCM4P2_FindMVpred
;//
        ;// Include headers
        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        INCLUDE armVCCOMM_s.h

        ;// Define cpu variants
        M_VARIANTS ARM1136JS
        
        
        IF ARM1136JS
        
        M_TABLE armVCM4P2_pBlkIndexTable
        DCD  OMXVCBlk0, OMXVCBlk1
        DCD  OMXVCBlk2, OMXVCBlk3

;//--------------------------------------------
;// Declare input registers
;//--------------------------------------------
        
pSrcMVCurMB            RN 0
pSrcCandMV1            RN 1
pSrcCandMV2            RN 2
pSrcCandMV3            RN 3
pDstMVPred             RN 4
pDstMVPredME           RN 5
iBlk                   RN 6

pTable                 RN 4
CandMV                 RN 12

pCandMV1               RN 7
pCandMV2               RN 8
pCandMV3               RN 9

CandMV1dx              RN 0 
CandMV1dy              RN 1 
CandMV2dx              RN 2
CandMV2dy              RN 3
CandMV3dx              RN 10
CandMV3dy              RN 11

temp                   RN 14

zero                   RN 14
return                 RN 0
        
; ----------------------------------------------
; Main routine
; ----------------------------------------------        

        M_ALLOC4 MV, 4
        
        ;// Function header 
        M_START omxVCM4P2_FindMVpred, r11
        
        ;// Define stack arguments
        M_ARG   ppDstMVPred,  4
        M_ARG   ppDstMVPredME, 4
        M_ARG   Blk, 4
        
        M_ADR CandMV, MV
        MOV   zero, #0
        M_LDR iBlk, Blk
        
        ;// Set the default value for these
        ;// to be used if pSrcCandMV[1|2|3] == NULL
        MOV   pCandMV1, CandMV
        MOV   pCandMV2, CandMV
        MOV   pCandMV3, CandMV
    
        STR   zero, [CandMV]

        ;// Branch to the case based on blk number
        M_SWITCH iBlk
        M_CASE   OMXVCBlk0      ;// iBlk=0
        M_CASE   OMXVCBlk1      ;// iBlk=0
        M_CASE   OMXVCBlk2      ;// iBlk=0
        M_CASE   OMXVCBlk3      ;// iBlk=0
        M_ENDSWITCH
        
OMXVCBlk0
        CMP   pSrcCandMV1, #0
        ADDNE pCandMV1, pSrcCandMV1, #4
        
        CMP   pSrcCandMV2, #0
        ADDNE pCandMV2, pSrcCandMV2, #8

        CMP   pSrcCandMV3, #0
        ADDNE pCandMV3, pSrcCandMV3, #8
        CMPEQ pSrcCandMV1, #0
    
        MOVEQ pCandMV3, pCandMV2
        MOVEQ pCandMV1, pCandMV2
                
        CMP   pSrcCandMV1, #0
        CMPEQ pSrcCandMV2, #0
    
        MOVEQ pCandMV1, pCandMV3
        MOVEQ pCandMV2, pCandMV3
        
        CMP   pSrcCandMV2, #0
        CMPEQ pSrcCandMV3, #0
    
        MOVEQ pCandMV2, pCandMV1
        MOVEQ pCandMV3, pCandMV1
        
        B     BlkEnd
    
OMXVCBlk1
        MOV   pCandMV1, pSrcMVCurMB
        CMP   pSrcCandMV3, #0
        ADDNE pCandMV3, pSrcCandMV3, #8
        
        CMP   pSrcCandMV2, #0
        ADDNE pCandMV2, pSrcCandMV2, #12
    
        CMPEQ pSrcCandMV3, #0
    
        MOVEQ pCandMV2, pCandMV1
        MOVEQ pCandMV3, pCandMV1
            
        B     BlkEnd

OMXVCBlk2
        CMP   pSrcCandMV1, #0
        MOV   pCandMV2, pSrcMVCurMB
        ADD   pCandMV3, pSrcMVCurMB, #4
        ADDNE pCandMV1, pSrcCandMV1, #12
        B     BlkEnd

OMXVCBlk3
        ADD   pCandMV1, pSrcMVCurMB, #8
        MOV   pCandMV2, pSrcMVCurMB
        ADD   pCandMV3, pSrcMVCurMB, #4
    
BlkEnd

        ;// Using the transperancy info, zero
        ;// out the candidate MV if neccesary
        LDRSH CandMV1dx, [pCandMV1], #2
        LDRSH CandMV2dx, [pCandMV2], #2
        LDRSH CandMV3dx, [pCandMV3], #2
    
        ;// Load argument from the stack
        M_LDR pDstMVPredME, ppDstMVPredME

        LDRSH CandMV1dy, [pCandMV1]
        LDRSH CandMV2dy, [pCandMV2]
        LDRSH CandMV3dy, [pCandMV3]

        CMP pDstMVPredME, #0        

        ;// Store the candidate MV's into the pDstMVPredME, 
        ;// these can be used in the fast algorithm if implemented 

        STRHNE CandMV1dx, [pDstMVPredME], #2
        STRHNE CandMV1dy, [pDstMVPredME], #2        
        STRHNE CandMV2dx, [pDstMVPredME], #2
        STRHNE CandMV2dy, [pDstMVPredME], #2
        STRHNE CandMV3dx, [pDstMVPredME], #2
        STRHNE CandMV3dy, [pDstMVPredME]
           
        ; Find the median of the 3 candidate MV's
        M_MEDIAN3 CandMV1dx, CandMV2dx, CandMV3dx, temp

        ;// Load argument from the stack
        M_LDR pDstMVPred, ppDstMVPred

        M_MEDIAN3 CandMV1dy, CandMV2dy, CandMV3dy, temp
    
        STRH CandMV3dx, [pDstMVPred], #2
        STRH CandMV3dy, [pDstMVPred]

        MOV return, #OMX_Sts_NoErr
    
        M_END
    ENDIF ;// ARM1136JS :LOR: CortexA8
    
    END