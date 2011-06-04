;/**
; * 
; * File Name:  omxVCM4P2_DecodeVLCZigzag_IntraDCVLC_s.s
; * OpenMAX DL: v1.0.2
; * Revision:   9641
; * Date:       Thursday, February 7, 2008
; * 
; * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
; * 
; * 
; *
; * Description: 
; * Contains modules for zigzag scanning and VLC decoding
; * for inter block.
; *
; *
; *
; * Function: omxVCM4P2_DecodeVLCZigzag_Inter
; *
; * Description:
; * Performs VLC decoding and inverse zigzag scan for one intra coded block.
; *
; * Remarks:
; *
; * Parameters:
; * [in]    ppBitStream        pointer to the pointer to the current byte in
; *                    the bitstream buffer
; * [in]    pBitOffset        pointer to the bit position in the byte pointed
; *                    to by *ppBitStream. *pBitOffset is valid within    [0-7].
; * [in] shortVideoHeader     binary flag indicating presence of short_video_header;
; *                           escape modes 0-3 are used if shortVideoHeader==0,
; *                           and escape mode 4 is used when shortVideoHeader==1.
; * [out]    ppBitStream        *ppBitStream is updated after the block is
; *                    decoded, so that it points to the current byte
; *                    in the bit stream buffer
; * [out]    pBitOffset        *pBitOffset is updated so that it points to the
; *                    current bit position in the byte pointed by
; *                    *ppBitStream
; * [out]    pDst            pointer to the coefficient buffer of current
; *                    block. Must be 16-byte aligned
; *
; * Return Value:
; * OMX_Sts_BadArgErr - bad arguments
; *   -At least one of the following pointers is NULL: ppBitStream, *ppBitStream, pBitOffset, pDst, or
; *   -pDst is not 16-byte aligned, or
; *   -*pBitOffset exceeds [0,7].
; * OMX_Sts_Err - status error
; *   -At least one mark bit is equal to zero
; *   -Encountered an illegal stream code that cannot be found in the VLC table
; *   -Encountered and illegal code in the VLC FLC table
; *   -The number of coefficients is greater than 64
; *
; */


      INCLUDE omxtypes_s.h
      INCLUDE armCOMM_s.h
      INCLUDE armCOMM_BitDec_s.h


      M_VARIANTS ARM1136JS

     
      


      IF ARM1136JS :LOR: CortexA8

     
        ;// Import various tables needed for the function

        
        IMPORT          armVCM4P2_IntraVlcL0L1             ;// Contains optimized and packed VLC Tables for both Last =1 and last=0
                                                               ;// Packed in Run:Level:Last format
        IMPORT          armVCM4P2_IntraL0L1LMAX            ;// Contains LMAX table entries with both Last=0 and Last=1
        IMPORT          armVCM4P2_IntraL0L1RMAX            ;// Contains RMAX table entries with both Last=0 and Last=1
        IMPORT          armVCM4P2_aClassicalZigzagScan     ;// contains CLassical, Horizontal, Vertical Zigzag table entries with double the original values
        IMPORT          armVCM4P2_aIntraDCLumaChromaIndex  ;// Contains Optimized DCLuma and DCChroma Index table Entries
        

        IMPORT          armVCM4P2_DecodeVLCZigzag_AC_unsafe

;//Input Arguments

ppBitStream          RN 0
pBitOffset           RN 1
pDst                 RN 2
PredDir              RN 3
shortVideoHeader     RN 3
videoComp            RN 5
;//Local Variables

Return               RN 0

pDCLumaChromaIndex   RN 4
pDCChromaIndex       RN 7
pVlcTableL0L1        RN 4
pLMAXTableL0L1       RN 4
pRMAXTableL0L1       RN 4
pZigzagTable         RN 4
Count                RN 6
DCValueSize          RN 6
powOfSize            RN 7
temp1                RN 5


;// Scratch Registers

RBitStream           RN 8
RBitBuffer           RN 9
RBitCount            RN 10

T1                   RN 11
T2                   RN 12
DCVal                RN 14

        
        ;// Allocate stack memory to store optimized VLC,Zigzag, RMAX, LMAX Table Addresses 
     
        M_ALLOC4        ppVlcTableL0L1,4
        M_ALLOC4        ppLMAXTableL0L1,4
        M_ALLOC4        ppRMAXTableL0L1,4
        M_ALLOC4        ppZigzagTable,4
        M_ALLOC4        pDCCoeff,4
        

        
        M_START omxVCM4P2_DecodeVLCZigzag_IntraDCVLC,r12

        M_ARG           shortVideoHeaderonStack,4                                  ;// Pointer to argument on stack  
        M_ARG           videoComponstack,4                                         ;// Pointer to argument on stack

        
        ;// Decode DC Coefficient

        
        LDR             pDCLumaChromaIndex, =armVCM4P2_aIntraDCLumaChromaIndex ;// Load Optimized VLC Table for Luminance and Chrominance

        ;// Initializing the Bitstream Macro

        M_BD_INIT0      ppBitStream, pBitOffset, RBitStream, RBitBuffer, RBitCount
        M_LDR           videoComp,videoComponstack                                 
        M_BD_INIT1      T1, T2, T2
        ADD             pDCLumaChromaIndex,pDCLumaChromaIndex,videoComp, LSL #6             
        M_BD_INIT2      T1, T2, T2
    
        
        M_BD_VLD        DCValueSize,T1,T2,pDCLumaChromaIndex,4,2                    ;// VLC Decode using optimized Luminance and Chrominance VLC Table

    
       

DecodeDC
                         
        CMP             DCValueSize,#12     
        BGT             ExitError
        
        CMP             DCValueSize,#0
        MOVEQ           DCVal,#0                                                    ;// If DCValueSize is zero then DC coeff =0
        BEQ             ACDecode                                                    ;// Branch to perform AC Coeff Decoding
        
        M_BD_VREAD16    DCVal,DCValueSize,T1,T2                                     ;// Get DC Value From Bit stream
         

        MOV             powOfSize,#1                                                
        LSL             powOfSize,DCValueSize                                       ;// powOfSize=pow(2,DCValueSize)
        CMP             DCVal,powOfSize,LSR #1                                      ;// Compare DCVal with powOfSize/2 
        ADDLT           DCVal,DCVal,#1
        SUBLT           DCVal,DCVal,powOfSize                                       ;// If Lessthan powOfSize/2 DCVal=DCVal-powOfSize+1
                                                                                    ;// Else DCVal= fetchbits from bit stream

CheckDCValueSize
        
        CMP             DCValueSize,#8                                              ;// If DCValueSize greater than 8 check marker bit

        BLE             ACDecode

        M_BD_READ8      temp1,1,T1
        TEQ             temp1,#0                                                    ;// If Marker bit is zero Exit with an Error Message
        BEQ             ExitError

        

        ;// Decode AC Coefficient

ACDecode

        M_STR           DCVal,pDCCoeff                                             ;// Store Decoded DC Coeff on Stack
        M_BD_FINI       ppBitStream,pBitOffset                                     ;// Terminating the Bit stream Macro
         
        LDR             pZigzagTable, =armVCM4P2_aClassicalZigzagScan          ;// Load Zigzag talbe address   
        ADD             pZigzagTable, pZigzagTable, PredDir, LSL #6                ;// Modify the Zigzag table adress based on PredDir                
       
        M_STR           pZigzagTable,ppZigzagTable                                 ;// Store zigzag table on stack
        LDR             pVlcTableL0L1, =armVCM4P2_IntraVlcL0L1                 ;// Load Optimized VLC Table With both Last=0 and Last=1 Entries
        M_STR           pVlcTableL0L1,ppVlcTableL0L1                               ;// Store Optimized VLC Table on stack
        LDR             pLMAXTableL0L1, =armVCM4P2_IntraL0L1LMAX               ;// Load LMAX Table
        M_STR           pLMAXTableL0L1,ppLMAXTableL0L1                             ;// Store LMAX table on stack
        LDR             pRMAXTableL0L1, =armVCM4P2_IntraL0L1RMAX               ;// Load RMAX Table
        MOV             Count,#1                                                   ;// Set Start =1        
        
        M_STR           pRMAXTableL0L1,ppRMAXTableL0L1                             ;// Store RMAX Table on Stack
        
       
        M_LDR           shortVideoHeader,shortVideoHeaderonStack                   ;// Load the Input Argument From Stack
        
        BL              armVCM4P2_DecodeVLCZigzag_AC_unsafe                    ;// Call the Unsafe Function

        M_LDR           DCVal,pDCCoeff                                             ;// Get the Decoded DC Value From Stack
        STRH            DCVal,[pDst]                                               ;// Store the DC Value 
        B               ExitOK
        
              

ExitError
 
        M_BD_FINI       ppBitStream,pBitOffset                                     ;// Terminating the Bit Stream Macro in case of an Error
        MOV             Return,#OMX_Sts_Err                                        ;// Exit with an Error Message 
ExitOK
      
        M_END
        ENDIF
        
        END
