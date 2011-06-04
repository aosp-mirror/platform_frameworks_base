;//
;// 
;// File Name:  armCOMM_BitDec_s.h
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;// 
;// OpenMAX optimized bitstream decode module
;//
;// You must include armCOMM_s.h before including this file
;//
;// This module provides macros to perform assembly optimized fixed and
;// variable length decoding from a read-only bitstream. The variable
;// length decode modules take as input a pointer to a table of 16-bit
;// entries of the following format.
;//
;// VLD Table Entry format
;//
;//        15 14 13 12 11 10 09 08 07 06 05 04 03 02 01 00
;//       +------------------------------------------------+
;//       |  Len   |               Symbol              | 1 |
;//       +------------------------------------------------+
;//       |                Offset                      | 0 |
;//       +------------------------------------------------+
;//
;// If the table entry is a leaf entry then bit 0 set:
;//    Len    = Number of bits overread (0 to 7)
;//    Symbol = Symbol payload (unsigned 12 bits)
;//
;// If the table entry is an internal node then bit 0 is clear:
;//    Offset = Number of (16-bit) half words from the table
;//             start to the next table node
;//
;// The table is accessed by successive lookup up on the
;// next Step bits of the input bitstream until a leaf node
;// is obtained. The Step sizes are supplied to the VLD macro.
;//
;// USAGE:
;//
;// To use any of the macros in this package, first call:
;//
;//    M_BD_INIT ppBitStream, pBitOffset, pBitStream, RBitBuffer, RBitCount, Tmp
;//
;// This caches the current bitstream position and next available
;// bits in registers pBitStream, RBitBuffer, RBitCount. These registers
;// are reserved for use by the bitstream decode package until you
;// call M_BD_FINI.
;//
;// Next call the following macro(s) as many times as you need:
;//
;//    M_BD_LOOK8       - Look ahead constant 1<=N<=8  bits into the bitstream
;//    M_BD_LOOK16      - Look ahead constant 1<=N<=16 bits into the bitstream
;//    M_BD_READ8       - Read constant 1<=N<=8  bits from the bitstream
;//    M_BD_READ16      - Read constant 1<=N<=16 bits from the bitstream
;//    M_BD_VREAD8      - Read variable 1<=N<=8  bits from the bitstream
;//    M_BD_VREAD16     - Read variable 1<=N<=16 bits from the bitstream
;//    M_BD_VLD         - Perform variable length decode using lookup table
;//
;// Finally call the macro:
;//
;//    M_BD_FINI ppBitStream, pBitOffset
;//
;// This writes the bitstream state back to memory.
;//
;// The three bitstream cache register names are assigned to the following global
;// variables:
;//

        GBLS    pBitStream  ;// Register name for pBitStream
        GBLS    BitBuffer   ;// Register name for BitBuffer
        GBLS    BitCount    ;// Register name for BitCount
   
;//        
;// These register variables must have a certain defined state on entry to every bitstream
;// macro (except M_BD_INIT) and on exit from every bitstream macro (except M_BD_FINI).
;// The state may depend on implementation.
;//
;// For the default (ARM11) implementation the following hold:
;//    pBitStream - points to the first byte not held in the BitBuffer
;//    BitBuffer  - is a cache of (4 bytes) 32 bits, bit 31 the first bit
;//    BitCount   - is offset (from the top bit) to the next unused bitstream bit
;//    0<=BitCount<=15 (so BitBuffer holds at least 17 unused bits)
;//
;//

        ;// Bitstream Decode initialise
        ;//
        ;// Initialises the bitstream decode global registers from
        ;// bitstream pointers. This macro is split into 3 parts to enable
        ;// scheduling.
        ;//
        ;// Input Registers:
        ;//
        ;// $ppBitStream    - pointer to pointer to the next bitstream byte
        ;// $pBitOffset     - pointer to the number of bits used in the current byte (0..7)
        ;// $RBitStream     - register to use for pBitStream (can be $ppBitStream)
        ;// $RBitBuffer     - register to use for BitBuffer
        ;// $RBitCount      - register to use for BitCount   (can be $pBitOffset)
        ;//
        ;// Output Registers:
        ;//
        ;// $T1,$T2,$T3     - registers that must be preserved between calls to
        ;//                   M_BD_INIT1 and M_BD_INIT2
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_INIT0  $ppBitStream, $pBitOffset, $RBitStream, $RBitBuffer, $RBitCount

pBitStream  SETS "$RBitStream"
BitBuffer   SETS "$RBitBuffer"
BitCount    SETS "$RBitCount"        
        
        ;// load inputs
        LDR     $pBitStream, [$ppBitStream]
        LDR     $BitCount, [$pBitOffset]
        MEND
        
        MACRO
        M_BD_INIT1  $T1, $T2, $T3
        LDRB    $T2, [$pBitStream, #2]
        LDRB    $T1, [$pBitStream, #1]
        LDRB    $BitBuffer,  [$pBitStream], #3
        ADD     $BitCount, $BitCount, #8
        MEND
        
        MACRO
        M_BD_INIT2  $T1, $T2, $T3
        ORR     $T2, $T2, $T1, LSL #8
        ORR     $BitBuffer, $T2, $BitBuffer, LSL #16
        MEND    
        
        ;//
        ;// Look ahead fixed 1<=N<=8 bits without consuming any bits
        ;// The next bits will be placed at bit 31..24 of destination register
        ;//
        ;// Input Registers:
        ;//
        ;// $N              - number of bits to look
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// 
        ;// Output Registers:
        ;//
        ;// $Symbol         - the next N bits of the bitstream
        ;// $T1             - corrupted temp/scratch register
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_LOOK8  $Symbol, $N
        ASSERT  ($N>=1):LAND:($N<=8)
        MOV     $Symbol, $BitBuffer, LSL $BitCount
        MEND
        
        ;//
        ;// Look ahead fixed 1<=N<=16 bits without consuming any bits
        ;// The next bits will be placed at bit 31..16 of destination register
        ;//
        ;// Input Registers:
        ;//
        ;// $N              - number of bits to look
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// 
        ;// Output Registers:
        ;//
        ;// $Symbol         - the next N bits of the bitstream
        ;// $T1             - corrupted temp/scratch register
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_LOOK16  $Symbol, $N, $T1
        ASSERT  ($N >= 1):LAND:($N <= 16)
        MOV     $Symbol, $BitBuffer, LSL $BitCount
        MEND
        
        ;//
        ;// Skips fixed 1<=N<=8 bits from the bitstream, advancing the bitstream pointer
        ;//
        ;// Input Registers:
        ;//
        ;// $N              - number of bits
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// 
        ;// Output Registers:
        ;//
        ;// $T1             - corrupted temp/scratch register
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_SKIP8 $N, $T1
        ASSERT  ($N>=1):LAND:($N<=8)        
        SUBS    $BitCount, $BitCount, #(8-$N)
        LDRCSB  $T1, [$pBitStream], #1   
        ADDCC   $BitCount, $BitCount, #8
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        MEND
        
        
        ;//
        ;// Read fixed 1<=N<=8 bits from the bitstream, advancing the bitstream pointer
        ;//
        ;// Input Registers:
        ;//
        ;// $N              - number of bits to read
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// 
        ;// Output Registers:
        ;//
        ;// $Symbol         - the next N bits of the bitstream
        ;// $T1             - corrupted temp/scratch register
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_READ8 $Symbol, $N, $T1
        ASSERT  ($N>=1):LAND:($N<=8)                
        MOVS    $Symbol, $BitBuffer, LSL $BitCount        
        SUBS    $BitCount, $BitCount, #(8-$N)
        LDRCSB  $T1, [$pBitStream], #1   
        ADDCC   $BitCount, $BitCount, #8
        MOV     $Symbol, $Symbol, LSR #(32-$N)
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        MEND

        ;//
        ;// Read fixed 1<=N<=16 bits from the bitstream, advancing the bitstream pointer
        ;//
        ;// Input Registers:
        ;//
        ;// $N              - number of bits to read
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// 
        ;// Output Registers:
        ;//
        ;// $Symbol         - the next N bits of the bitstream
        ;// $T1             - corrupted temp/scratch register
        ;// $T2             - corrupted temp/scratch register
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_READ16 $Symbol, $N, $T1, $T2
        ASSERT  ($N>=1):LAND:($N<=16)
        ASSERT  $Symbol<>$T1
        IF ($N<=8)
            M_BD_READ8  $Symbol, $N, $T1
        ELSE        
            ;// N>8 so we will be able to refill at least one byte            
            LDRB    $T1, [$pBitStream], #1            
            MOVS    $Symbol, $BitBuffer, LSL $BitCount
            ORR     $BitBuffer, $T1, $BitBuffer, LSL #8                       
            SUBS    $BitCount, $BitCount, #(16-$N)
            LDRCSB  $T1, [$pBitStream], #1            
            MOV     $Symbol, $Symbol, LSR #(32-$N)
            ADDCC   $BitCount, $BitCount, #8
            ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        ENDIF
        MEND
        
        ;//
        ;// Skip variable 1<=N<=8 bits from the bitstream, advancing the bitstream pointer.
        ;//
        ;// Input Registers:
        ;//
        ;// $N              - number of bits. 1<=N<=8
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// 
        ;// Output Registers:
        ;//
        ;// $T1             - corrupted temp/scratch register
        ;// $T2             - corrupted temp/scratch register
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_VSKIP8 $N, $T1
        ADD     $BitCount, $BitCount, $N
        SUBS    $BitCount, $BitCount, #8
        LDRCSB  $T1, [$pBitStream], #1        
        ADDCC   $BitCount, $BitCount, #8
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        MEND        
        
        ;//
        ;// Skip variable 1<=N<=16 bits from the bitstream, advancing the bitstream pointer.
        ;//
        ;// Input Registers:
        ;//
        ;// $N              - number of bits. 1<=N<=16
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// 
        ;// Output Registers:
        ;//
        ;// $T1             - corrupted temp/scratch register
        ;// $T2             - corrupted temp/scratch register
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_VSKIP16 $N, $T1, $T2
        ADD     $BitCount, $BitCount, $N
        SUBS    $BitCount, $BitCount, #8
        LDRCSB  $T1, [$pBitStream], #1        
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        SUBCSS  $BitCount, $BitCount, #8        
        LDRCSB  $T1, [$pBitStream], #1
        ADDCC   $BitCount, $BitCount, #8
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        MEND        

        ;//
        ;// Read variable 1<=N<=8 bits from the bitstream, advancing the bitstream pointer.
        ;//
        ;// Input Registers:
        ;//
        ;// $N              - number of bits to read. 1<=N<=8
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// 
        ;// Output Registers:
        ;//
        ;// $Symbol         - the next N bits of the bitstream
        ;// $T1             - corrupted temp/scratch register
        ;// $T2             - corrupted temp/scratch register
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_VREAD8 $Symbol, $N, $T1, $T2
        MOV     $Symbol, $BitBuffer, LSL $BitCount        
        ADD     $BitCount, $BitCount, $N
        SUBS    $BitCount, $BitCount, #8
        LDRCSB  $T1, [$pBitStream], #1        
        RSB     $T2, $N, #32        
        ADDCC   $BitCount, $BitCount, #8
        MOV     $Symbol, $Symbol, LSR $T2
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        MEND


        ;//
        ;// Read variable 1<=N<=16 bits from the bitstream, advancing the bitstream pointer.
        ;//
        ;// Input Registers:
        ;//
        ;// $N              - number of bits to read. 1<=N<=16
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// 
        ;// Output Registers:
        ;//
        ;// $Symbol         - the next N bits of the bitstream
        ;// $T1             - corrupted temp/scratch register
        ;// $T2             - corrupted temp/scratch register
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_VREAD16 $Symbol, $N, $T1, $T2
        MOV     $Symbol, $BitBuffer, LSL $BitCount        
        ADD     $BitCount, $BitCount, $N
        SUBS    $BitCount, $BitCount, #8
        LDRCSB  $T1, [$pBitStream], #1        
        RSB     $T2, $N, #32        
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        SUBCSS  $BitCount, $BitCount, #8        
        LDRCSB  $T1, [$pBitStream], #1
        ADDCC   $BitCount, $BitCount, #8
        MOV     $Symbol, $Symbol, LSR $T2
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        MEND


        ;//
        ;// Decode a code of the form 0000...001 where there
        ;// are N zeros before the 1 and N<=15 (code length<=16)
        ;//
        ;// Input Registers:
        ;//
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// 
        ;// Output Registers:
        ;//
        ;// $Symbol         - the number of zeros before the next 1
        ;//                   >=16 is an illegal code
        ;// $T1             - corrupted temp/scratch register
        ;// $T2             - corrupted temp/scratch register
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//        
        MACRO
        M_BD_CLZ16 $Symbol, $T1, $T2
        MOVS    $Symbol, $BitBuffer, LSL $BitCount
        CLZ     $Symbol, $Symbol                
        ADD     $BitCount, $BitCount, $Symbol
        SUBS    $BitCount, $BitCount, #7        ;// length is Symbol+1
        LDRCSB  $T1, [$pBitStream], #1
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        SUBCSS  $BitCount, $BitCount, #8        
        LDRCSB  $T1, [$pBitStream], #1
        ADDCC   $BitCount, $BitCount, #8
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        MEND  

        ;//
        ;// Decode a code of the form 1111...110 where there
        ;// are N ones before the 0 and N<=15 (code length<=16)
        ;//
        ;// Input Registers:
        ;//
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// 
        ;// Output Registers:
        ;//
        ;// $Symbol         - the number of zeros before the next 1
        ;//                   >=16 is an illegal code
        ;// $T1             - corrupted temp/scratch register
        ;// $T2             - corrupted temp/scratch register
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//        
        MACRO
        M_BD_CLO16 $Symbol, $T1, $T2
        MOV     $Symbol, $BitBuffer, LSL $BitCount
        MVN     $Symbol, $Symbol
        CLZ     $Symbol, $Symbol                
        ADD     $BitCount, $BitCount, $Symbol
        SUBS    $BitCount, $BitCount, #7        ;// length is Symbol+1
        LDRCSB  $T1, [$pBitStream], #1
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        SUBCSS  $BitCount, $BitCount, #8        
        LDRCSB  $T1, [$pBitStream], #1
        ADDCC   $BitCount, $BitCount, #8
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8
        MEND  


        ;//
        ;// Variable Length Decode module
        ;//
        ;// Decodes one VLD Symbol from a bitstream and refill the bitstream
        ;// buffer.
        ;//
        ;// Input Registers:
        ;//
        ;// $pVLDTable      - pointer to VLD decode table of 16-bit entries.
        ;//                   The format is described above at the start of
        ;//                   this file.
        ;// $S0             - The number of bits to look up for the first step
        ;//                   1<=$S0<=8
        ;// $S1             - The number of bits to look up for each subsequent
        ;//                   step 1<=$S1<=$S0.
        ;//
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// 
        ;// Output Registers:
        ;//
        ;// $Symbol         - decoded VLD symbol value
        ;// $T1             - corrupted temp/scratch register
        ;// $T2             - corrupted temp/scratch register
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_VLD $Symbol, $T1, $T2, $pVLDTable, $S0, $S1
        ASSERT (1<=$S0):LAND:($S0<=8)
        ASSERT (1<=$S1):LAND:($S1<=$S0)
        
        ;// Note 0<=BitCount<=15 on entry and exit
        
        MOVS    $T1, $BitBuffer, LSL $BitCount       ;// left align next bits
        MOVS    $Symbol, #(2<<$S0)-2                 ;// create mask
        AND     $Symbol, $Symbol, $T1, LSR #(31-$S0) ;// 2*(next $S0 bits)
        SUBS    $BitCount, $BitCount, #8             ;// CS if buffer can be filled
01
        LDRCSB  $T1, [$pBitStream], #1               ;// load refill byte
        LDRH    $Symbol, [$pVLDTable, $Symbol]       ;// load table entry
        ADDCC   $BitCount, $BitCount, #8             ;// refill not possible
        ADD     $BitCount, $BitCount, #$S0           ;// assume $S0 bits used
        ORRCS   $BitBuffer, $T1, $BitBuffer, LSL #8  ;// merge in refill byte
        MOVS    $T1, $Symbol, LSR #1                 ;// CS=leaf entry
        BCS     %FT02
        
        MOVS    $T1, $BitBuffer, LSL $BitCount       ;// left align next bit
        IF (2*$S0-$S1<=8)
            ;// Can combine refill check and -S0+S1 and keep $BitCount<=15
            SUBS    $BitCount, $BitCount, #8+($S0-$S1)
        ELSE
            ;// Separate refill check and -S0+S1 offset
            SUBS  $BitCount, $BitCount, #8
            SUB   $BitCount, $BitCount, #($S0-$S1)
        ENDIF
        ADD     $Symbol, $Symbol, $T1, LSR #(31-$S1) ;// add 2*(next $S1 bits) to
        BIC     $Symbol, $Symbol, #1                 ;//   table offset
        B       %BT01                                ;// load next table entry
02
        ;// BitCount range now depend on the route here
        ;// if (first step)       S0 <= BitCount <= 7+S0        <=15
        ;// else if (2*S0-S1<=8)  S0 <= BitCount <= 7+(2*S0-S1) <=15
        ;// else                  S1 <= BitCount <= 7+S1        <=15
        
        SUB     $BitCount, $BitCount, $Symbol, LSR#13
        BIC     $Symbol, $T1, #0xF000
        MEND
        

        ;// Add an offset number of bits
        ;//
        ;// Outputs destination byte and bit index values which corresponds to an offset number of bits 
        ;// from the current location. This is used to compare bitstream positions using. M_BD_CMP.
        ;//
        ;// Input Registers:
        ;//
        ;// $Offset         - Offset to be added in bits.
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        ;// Output Registers:
        ;//
        ;// $ByteIndex      - Destination pBitStream pointer after adding the Offset. 
        ;//                   This value will be 4 byte ahead and needs to subtract by 4 to get exact 
        ;//                   pointer (as in M_BD_FINI). But for using with M_BD_CMP subtract is not needed.
        ;// $BitIndex       - Destination BitCount after the addition of Offset number of bits
        ;//
        MACRO
        M_BD_ADD  $ByteIndex, $BitIndex, $Offset

        ;// ($ByteIndex,$BitIndex) = Current position + $Offset bits
        ADD     $Offset, $Offset, $BitCount
        AND     $BitIndex, $Offset, #7
        ADD     $ByteIndex, $pBitStream, $Offset, ASR #3        
        MEND

        ;// Move bitstream pointers to the location given
        ;//
        ;// Outputs destination byte and bit index values which corresponds to  
        ;// the current location given (calculated using M_BD_ADD). 
        ;//
        ;// Input Registers:
        ;//
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;// $ByteIndex      - Destination pBitStream pointer after move. 
        ;//                   This value will be 4 byte ahead and needs to subtract by 4 to get exact 
        ;//                   pointer (as in M_BD_FINI).
        ;// $BitIndex       - Destination BitCount after the move
        ;//
        ;// Output Registers:
        ;//
        ;// $pBitStream     \ 
        ;//                  } See description above.  
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_MOV  $ByteIndex, $BitIndex

        ;// ($pBitStream, $Offset) = ($ByteIndex,$BitIndex)
        MOV     $BitCount, $BitIndex
        MOV     $pBitStream, $ByteIndex
        MEND

        ;// Bitstream Compare
        ;//
        ;// Compares bitstream position with that of a destination position. Destination position 
        ;// is held in two input registers which are calculated using M_BD_ADD macro
        ;//
        ;// Input Registers:
        ;//
        ;// $ByteIndex      - Destination pBitStream pointer, (4 byte ahead as described in M_BD_ADD)
        ;// $BitIndex       - Destination BitCount
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        ;// Output Registers:
        ;//
        ;// FLAGS           - GE if destination is reached, LT = is destination is ahead
        ;// $T1             - corrupted temp/scratch register
        ;//
        MACRO
        M_BD_CMP  $ByteIndex, $BitIndex, $T1
        
        ;// Return flags set by (current positon)-($ByteIndex,$BitIndex)
        ;// so GE means that we have reached the indicated position

        ADD         $T1, $pBitStream, $BitCount, LSR #3
        CMP         $T1, $ByteIndex
        AND         $T1, $BitCount, #7
        CMPEQ       $T1, $BitIndex        
        MEND

        
        ;// Bitstream Decode finalise
        ;//
        ;// Writes back the bitstream state to the bitstream pointers
        ;//
        ;// Input Registers:
        ;//
        ;// $pBitStream     \ 
        ;// $BitBuffer       } See description above.
        ;// $BitCount       / 
        ;//
        ;// Output Registers:
        ;//
        ;// $ppBitStream    - pointer to pointer to the next bitstream byte
        ;// $pBitOffset     - pointer to the number of bits used in the current byte (0..7)
        ;// $pBitStream     \ 
        ;// $BitBuffer       } these register are corrupted
        ;// $BitCount       / 
        ;//
        MACRO
        M_BD_FINI  $ppBitStream, $pBitOffset
        
        ;// Advance pointer by the number of free bits in the buffer
        ADD     $pBitStream, $pBitStream, $BitCount, LSR#3
        AND     $BitCount, $BitCount, #7
        
        ;// Now move back 32 bits to reach the first usued bit
        SUB     $pBitStream, $pBitStream, #4
        
        ;// Store out bitstream state
        STR     $BitCount, [$pBitOffset]
        STR     $pBitStream, [$ppBitStream]
        MEND
        
        END
        