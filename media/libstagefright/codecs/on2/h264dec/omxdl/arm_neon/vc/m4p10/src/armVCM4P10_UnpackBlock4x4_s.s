;//
;// 
;// File Name:  armVCM4P10_UnpackBlock4x4_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//

        
        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        
;// Define the processor variants supported by this file

        M_VARIANTS ARM1136JS
        
                       
        IF ARM1136JS
        
;//--------------------------------------
;// Input Arguments and their scope/usage
;//--------------------------------------
ppSrc           RN 0    ;// Persistent variable
pDst            RN 1    ;// Persistent variable

;//--------------------------------
;// Variables and their scope/usage
;//--------------------------------
pSrc            RN 2    ;// Persistent variables
Flag            RN 3    
Value           RN 4    
Value2          RN 5    
strOffset       RN 6    
cstOffset       RN 7    

        
        M_START armVCM4P10_UnpackBlock4x4, r7
        
        LDR     pSrc, [ppSrc]                       ;// Load pSrc
        MOV     cstOffset, #31                      ;// To be used in the loop, to compute offset
        
        ;//-----------------------------------------------------------------------
        ; Firstly, fill all the coefficient values on the <pDst> buffer by zero
        ;//-----------------------------------------------------------------------
        
        MOV      Value,  #0                         ;// Initialize the zero value
        MOV      Value2, #0                         ;// Initialize the zero value
        LDRB     Flag,  [pSrc], #1                  ;// Preload <Flag> before <unpackLoop>
        
        STRD     Value, [pDst, #0]                  ;// pDst[0]  = pDst[1]  = pDst[2]  = pDst[3]  = 0
        STRD     Value, [pDst, #8]                  ;// pDst[4]  = pDst[5]  = pDst[6]  = pDst[7]  = 0
        STRD     Value, [pDst, #16]                 ;// pDst[8]  = pDst[9]  = pDst[10] = pDst[11] = 0
        STRD     Value, [pDst, #24]                 ;// pDst[12] = pDst[13] = pDst[14] = pDst[15] = 0
        
        ;//----------------------------------------------------------------------------
        ;// The loop below parses and unpacks the input stream. The C-model has 
        ;// a somewhat complicated logic for sign extension.  But in the v6 version,
        ;// that can be easily taken care by loading the data from <pSrc> stream as 
        ;// SIGNED byte/halfword. So, based on the first TST instruction, 8-bits or 
        ;// 16-bits are read.
        ;//
        ;// Next, to compute the offset, where the unpacked value needs to be stored,
        ;// we modify the computation to perform [(Flag & 15) < 1] as [(Flag < 1) & 31]
        ;// This results in a saving of one cycle.
        ;//----------------------------------------------------------------------------
        
unpackLoop
        TST      Flag,  #0x10                        ;// Computing (Flag & 0x10)
        LDRSBNE  Value2,[pSrc,#1]                    ;// Load byte wise to avoid unaligned access   
        LDRBNE   Value, [pSrc], #2                   
        AND      strOffset, cstOffset, Flag, LSL #1  ;// strOffset = (Flag & 15) < 1;
        LDRSBEQ  Value, [pSrc], #1                   ;// Value = (OMX_U8)  *pSrc++
        ORRNE    Value,Value,Value2, LSL #8          ;// Value = (OMX_U16) *pSrc++
        
        TST      Flag,  #0x20                        ;// Computing (Flag & 0x20) to check, if we're done
        LDRBEQ   Flag,  [pSrc], #1                   ;// Flag  = (OMX_U8) *pSrc++, for next iteration
        STRH     Value, [pDst, strOffset]            ;// Store <Value> at offset <strOffset>
        BEQ      unpackLoop                          ;// Branch to the loop beginning
        
        STR      pSrc, [ppSrc]                       ;// Update the bitstream pointer
        M_END
    
    ENDIF
    
    
    
    END
    