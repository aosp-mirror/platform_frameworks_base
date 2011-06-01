;//
;// 
;// File Name:  armCOMM_s.h
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//
;// ARM optimized OpenMAX common header file
;//

;// Protect against multiple inclusion
 IF :LNOT::DEF:ARMCOMM_S_H
 GBLL ARMCOMM_S_H

        REQUIRE8            ;// Requires 8-byte stack alignment
        PRESERVE8           ;// Preserves 8-byte stack alignment
        
        GBLL    ARM_ERRORCHECK
ARM_ERRORCHECK  SETL {FALSE}

;// Globals

        GBLS    _RRegList   ;// R saved register list
        GBLS    _DRegList   ;// D saved register list
        GBLS    _Variant    ;// Selected processor variant
        GBLS    _CPU        ;// CPU name
        GBLS    _Struct     ;// Structure name
        
        GBLL    _InFunc     ;// Inside function assembly flag
        GBLL    _SwLong     ;// Long switch flag
        
        GBLA    _RBytes     ;// Number of register bytes on stack
        GBLA    _SBytes     ;// Number of scratch bytes on stack 
        GBLA    _ABytes     ;// Stack offset of next argument
        GBLA    _Workspace  ;// Stack offset of scratch workspace
        GBLA    _F          ;// Function number
        GBLA    _StOff      ;// Struct offset
        GBLA    _SwNum      ;// Switch number
        GBLS    _32         ;// Suffix for 32 byte alignmnet
        GBLS    _16         ;// Suffix for 16 byte alignmnet
        
_InFunc         SETL    {FALSE}
_SBytes         SETA    0
_F              SETA    0
_SwNum          SETA    0
_32             SETS    "ALIGN32"
_16             SETS    "ALIGN16"

;/////////////////////////////////////////////////////////
;// Override the tools settings of the CPU if the #define
;// USECPU is set, otherwise use the CPU defined by the
;// assembler settings.
;/////////////////////////////////////////////////////////

       IF :DEF: OVERRIDECPU
_CPU       SETS  OVERRIDECPU
       ELSE
_CPU       SETS    {CPU}       
       ENDIF



;/////////////////////////////////////////////////////////
;// Work out which code to build
;/////////////////////////////////////////////////////////

        IF :DEF:ARM1136JS:LOR::DEF:CortexA8:LOR::DEF:ARM_GENERIC
            INFO 1,"Please switch to using M_VARIANTS"
        ENDIF

        ;// Define and reset all officially recongnised variants
        MACRO
        _M_DEF_VARIANTS
        _M_DEF_VARIANT ARM926EJS
        _M_DEF_VARIANT ARM1136JS
        _M_DEF_VARIANT ARM1136JS_U
        _M_DEF_VARIANT CortexA8
        _M_DEF_VARIANT ARM7TDMI
        MEND
        
        MACRO
        _M_DEF_VARIANT $var
        GBLL $var
        GBLL _ok$var
$var    SETL {FALSE}
        MEND        
        

        ;// Variant declaration
        ;//
        ;// Define a list of code variants supported by this
        ;// source file. This macro then chooses the most
        ;// appropriate variant to build for the currently configured
        ;// core.
        ;//        
        MACRO
        M_VARIANTS $v0,$v1,$v2,$v3,$v4,$v5,$v6,$v7        
        ;// Set to TRUE variants that are supported
        _M_DEF_VARIANTS
        _M_VARIANT $v0
        _M_VARIANT $v1
        _M_VARIANT $v2
        _M_VARIANT $v3
        _M_VARIANT $v4
        _M_VARIANT $v5
        _M_VARIANT $v6
        _M_VARIANT $v7
        
        ;// Look for first available variant to match a CPU
        ;// _M_TRY cpu, variant fall back list
_Variant SETS ""                
        _M_TRY ARM926EJ-S,   ARM926EJS
        _M_TRY ARM1176JZ-S,  ARM1136JS
        _M_TRY ARM1176JZF-S, ARM1136JS
        _M_TRY ARM1156T2-S,  ARM1136JS
        _M_TRY ARM1156T2F-S, ARM1136JS
        _M_TRY ARM1136J-S,   ARM1136JS
        _M_TRY ARM1136JF-S,  ARM1136JS
        _M_TRY MPCore,       ARM1136JS
        _M_TRY falcon-vfp, ARM1136JS
        _M_TRY falcon-full-neon, CortexA8
        _M_TRY Cortex-A8NoNeon, ARM1136JS
        _M_TRY Cortex-A8,    CortexA8, ARM1136JS
        _M_TRY Cortex-R4,    ARM1136JS
        _M_TRY ARM7TDMI
        
        ;// Select the correct variant
        _M_DEF_VARIANTS
        IF _Variant=""
            INFO 1, "No match found for CPU '$_CPU'"
        ELSE
$_Variant   SETL {TRUE}
        ENDIF
        MEND
        
        ;// Register a variant as available
        MACRO
        _M_VARIANT $var
        IF "$var"=""
            MEXIT
        ENDIF
        IF :LNOT::DEF:_ok$var
            INFO 1, "Unrecognized variant '$var'"
        ENDIF
$var    SETL {TRUE}
        MEND
        
        ;// For a given CPU, see if any of the variants supporting
        ;// this CPU are available. The first available variant is
        ;// chosen
        MACRO
        _M_TRY $cpu, $v0,$v1,$v2,$v3,$v4,$v5,$v6,$v7
        IF "$cpu"<>_CPU
            MEXIT
        ENDIF
        _M_TRY1 $v0
        _M_TRY1 $v1
        _M_TRY1 $v2
        _M_TRY1 $v3
        _M_TRY1 $v4
        _M_TRY1 $v5
        _M_TRY1 $v6
        _M_TRY1 $v7
        ;// Check a match was found
        IF _Variant=""
            INFO 1, "No variant match found for CPU '$_CPU'"
        ENDIF
        MEND
        
        MACRO
        _M_TRY1 $var
        IF "$var"=""
            MEXIT
        ENDIF
        IF (_Variant=""):LAND:$var
_Variant SETS "$var"
        ENDIF
        MEND
        
;////////////////////////////////////////////////////////
;// Structure definition
;////////////////////////////////////////////////////////

        ;// Declare a structure of given name
        MACRO
        M_STRUCT $sname
_Struct SETS "$sname"
_StOff  SETA 0
        MEND
        
        ;// Declare a structure field
        ;// The field is called $sname_$fname
        ;// $size   = the size of each entry, must be power of 2 
        ;// $number = (if provided) the number of entries for an array
        MACRO
        M_FIELD $fname, $size, $number
        IF (_StOff:AND:($size-1))!=0
_StOff      SETA _StOff + ($size - (_StOff:AND:($size-1)))
        ENDIF
$_Struct._$fname EQU _StOff
        IF "$number"<>""
_StOff      SETA _StOff + $size*$number
        ELSE
_StOff      SETA _StOff + $size
        ENDIF
        MEND
        
        
        MACRO
        M_ENDSTRUCT
sizeof_$_Struct EQU _StOff
_Struct SETS ""
        MEND

;//////////////////////////////////////////////////////////
;// Switch and table macros
;//////////////////////////////////////////////////////////

        ;// Start a relative switch table with register to switch on
        ;//
        ;// $v = the register to switch on
        ;// $s = if specified must be "L" to indicate long
        ;//      this allows a greater range to the case code
        MACRO
        M_SWITCH $v, $s
        ASSERT "$s"="":LOR:"$s"="L"
_SwLong SETL {FALSE}
        IF "$s"="L"
_SwLong     SETL {TRUE}
        ENDIF
_SwNum  SETA _SwNum+1        
        IF {CONFIG}=16
            ;// Thumb
            IF _SwLong
                TBH [pc, $v, LSL#1]
            ELSE
                TBB [pc, $v]
            ENDIF
_Switch$_SwNum
        ELSE
            ;// ARM
            ADD pc, pc, $v, LSL #2
            NOP
        ENDIF
        MEND
        
        ;// Add a case to the switch statement
        MACRO
        M_CASE  $label
        IF {CONFIG}=16
            ;// Thumb
            IF _SwLong
                DCW ($label - _Switch$_SwNum)/2
            ELSE
                DCB ($label - _Switch$_SwNum)/2
            ENDIF
        ELSE
            ;// ARM
            B   $label
        ENDIF
        MEND
        
        ;// End of switch statement
        MACRO
        M_ENDSWITCH
        ALIGN 2
        MEND       


;////////////////////////////////////////////////////////
;// Data area allocation
;////////////////////////////////////////////////////////

        ;// Constant table allocator macro
        ;//
        ;// Creates a new section for each constant table
        ;// $name is symbol through which the table can be accessed.
        ;// $align is the optional alignment of the table, log2 of 
        ;//  the byte alignment - $align=4 is 16 byte aligned
        MACRO
        M_TABLE  $name, $align
        ASSERT :LNOT:_InFunc
        IF "$align"=""
            AREA |.constdata|, READONLY, DATA
        ELSE
            ;// AREAs inherit the alignment of the first declaration.
            ;// Therefore for each alignment size we must have an area
            ;// of a different name.
            AREA constdata_a$align, READONLY, DATA, ALIGN=$align
            
            ;// We also force alignment incase we are tagging onto
            ;// an already started area.
            ALIGN (1<<$align)
        ENDIF
$name
        MEND
        
;/////////////////////////////////////////////////////
;// Macros to allocate space on the stack
;//
;// These all assume that the stack is 8-byte aligned
;// at entry to the function, which means that the 
;// 32-byte alignment macro needs to work in a
;// bit more of a special way...
;/////////////////////////////////////////////////////

        


        ;// Allocate 1-byte aligned area of name
        ;// $name size $size bytes.
        MACRO
        M_ALLOC1  $name, $size
        ASSERT :LNOT:_InFunc
$name$_F   EQU _SBytes
_SBytes SETA _SBytes + ($size)
        MEND
            
        ;// Allocate 2-byte aligned area of name
        ;// $name size $size bytes.
        MACRO
        M_ALLOC2  $name, $size
        ASSERT :LNOT:_InFunc
        IF (_SBytes:AND:1)!=0
_SBytes     SETA _SBytes + (2 - (_SBytes:AND:1))
        ENDIF
$name$_F   EQU _SBytes
_SBytes SETA _SBytes + ($size)
        MEND
            
        ;// Allocate 4-byte aligned area of name
        ;// $name size $size bytes.
        MACRO
        M_ALLOC4  $name, $size
        ASSERT :LNOT:_InFunc
        IF (_SBytes:AND:3)!=0
_SBytes     SETA _SBytes + (4 - (_SBytes:AND:3))
        ENDIF
$name$_F   EQU _SBytes
_SBytes SETA _SBytes + ($size)
        MEND
            
        ;// Allocate 8-byte aligned area of name
        ;// $name size $size bytes.
        MACRO
        M_ALLOC8  $name, $size
        ASSERT :LNOT:_InFunc
        IF (_SBytes:AND:7)!=0
_SBytes     SETA _SBytes + (8 - (_SBytes:AND:7))
        ENDIF
$name$_F   EQU _SBytes
_SBytes SETA _SBytes + ($size)
        MEND        

        
        ;// Allocate 8-byte aligned area of name
        ;// $name size ($size+16) bytes.
        ;// The extra 16 bytes are later used to align the pointer to 16 bytes
        
        MACRO
        M_ALLOC16  $name, $size
        ASSERT :LNOT:_InFunc
        IF (_SBytes:AND:7)!=0
_SBytes     SETA _SBytes + (8 - (_SBytes:AND:7))
        ENDIF
$name$_F$_16   EQU (_SBytes + 8)
_SBytes SETA _SBytes + ($size) + 8
        MEND        
        
        ;// Allocate 8-byte aligned area of name
        ;// $name size ($size+32) bytes.
        ;// The extra 32 bytes are later used to align the pointer to 32 bytes
        
        MACRO
        M_ALLOC32  $name, $size
        ASSERT :LNOT:_InFunc
        IF (_SBytes:AND:7)!=0
_SBytes     SETA _SBytes + (8 - (_SBytes:AND:7))
        ENDIF
$name$_F$_32   EQU (_SBytes + 24)
_SBytes SETA _SBytes + ($size) + 24
        MEND        
        
        
        
        
        ;// Argument Declaration Macro
        ;//
        ;// Allocate an argument name $name
        ;// size $size bytes
        MACRO
        M_ARG     $name, $size
        ASSERT _InFunc
$name$_F    EQU _ABytes
_ABytes SETA _ABytes + ($size)
        MEND        
        
;///////////////////////////////////////////////
;// Macros to access stacked variables
;///////////////////////////////////////////////

        ;// Macro to perform a data processing operation
        ;// with a constant second operand
        MACRO
        _M_OPC $op,$rd,$rn,$const
        LCLA    _sh
        LCLA    _cst
_sh     SETA    0
_cst    SETA    $const
        IF _cst=0
        $op $rd, $rn, #_cst
            MEXIT
        ENDIF
        WHILE (_cst:AND:3)=0
_cst        SETA _cst>>2
_sh         SETA _sh+2
        WEND
        $op $rd, $rn, #(_cst:AND:0x000000FF)<<_sh
        IF _cst>=256
            $op $rd, $rd, #(_cst:AND:0xFFFFFF00)<<_sh
        ENDIF
        MEND

        ;// Macro to perform a data access operation
        ;// Such as LDR or STR
        ;// The addressing mode is modified such that
        ;// 1. If no address is given then the name is taken
        ;//    as a stack offset
        ;// 2. If the addressing mode is not available for the
        ;//    state being assembled for (eg Thumb) then a suitable
        ;//    addressing mode is substituted.
        ;//
        ;// On Entry:
        ;// $i = Instruction to perform (eg "LDRB")
        ;// $a = Required byte alignment
        ;// $r = Register(s) to transfer (eg "r1")
        ;// $a0,$a1,$a2. Addressing mode and condition. One of:
        ;//     label {,cc}
        ;//     [base]                    {,,,cc}
        ;//     [base, offset]{!}         {,,cc}
        ;//     [base, offset, shift]{!}  {,cc}
        ;//     [base], offset            {,,cc}
        ;//     [base], offset, shift     {,cc}
        MACRO
        _M_DATA $i,$a,$r,$a0,$a1,$a2,$a3
        IF "$a0":LEFT:1="["
            IF "$a1"=""
                $i$a3   $r, $a0
            ELSE
                IF "$a0":RIGHT:1="]"
                    IF "$a2"=""
                        _M_POSTIND $i$a3, "$r", $a0, $a1
                    ELSE
                        _M_POSTIND $i$a3, "$r", $a0, "$a1,$a2"
                    ENDIF
                ELSE
                    IF "$a2"=""
                        _M_PREIND  $i$a3, "$r", $a0, $a1
                    ELSE
                        _M_PREIND  $i$a3, "$r", $a0, "$a1,$a2"
                    ENDIF
                ENDIF
            ENDIF
        ELSE
            LCLA    _Offset
_Offset     SETA    _Workspace + $a0$_F
            ASSERT  (_Offset:AND:($a-1))=0
            $i$a1   $r, [sp, #_Offset]
        ENDIF
        MEND
        
        ;// Handle post indexed load/stores
        ;// op  reg, [base], offset
        MACRO
        _M_POSTIND $i,$r,$a0,$a1
        LCLS _base
        LCLS _offset
        IF {CONFIG}=16 ;// Thumb
_base       SETS ("$a0":LEFT:(:LEN:"$a0"-1)):RIGHT:(:LEN:"$a0"-2)   ;// remove []
_offset     SETS "$a1"
            IF _offset:LEFT:1="+"
_offset         SETS _offset:RIGHT:(:LEN:_offset-1)
            ENDIF
            $i  $r, $a0
            IF _offset:LEFT:1="-"
_offset         SETS _offset:RIGHT:(:LEN:_offset-1)
                SUB $_base, $_base, $_offset
            ELSE                
                ADD $_base, $_base, $_offset
            ENDIF
        ELSE ;// ARM
            $i  $r, $a0, $a1
        ENDIF
        MEND
        
        ;// Handle pre indexed load/store
        ;// op  reg, [base, offset]{!}
        MACRO
        _M_PREIND $i,$r,$a0,$a1
        LCLS _base
        LCLS _offset
        IF ({CONFIG}=16):LAND:(("$a1":RIGHT:2)="]!")
_base       SETS "$a0":RIGHT:(:LEN:("$a0")-1)
_offset     SETS "$a1":LEFT:(:LEN:("$a1")-2)
            $i $r, [$_base, $_offset]
            ADD $_base, $_base, $_offset
        ELSE
            $i  $r, $a0, $a1
        ENDIF
        MEND

        ;// Load unsigned byte from stack
        MACRO
        M_LDRB  $r,$a0,$a1,$a2,$a3
        _M_DATA "LDRB",1,$r,$a0,$a1,$a2,$a3
        MEND
        
        ;// Load signed byte from stack
        MACRO
        M_LDRSB $r,$a0,$a1,$a2,$a3
        _M_DATA "LDRSB",1,$r,$a0,$a1,$a2,$a3
        MEND
        
        ;// Store byte to stack
        MACRO
        M_STRB  $r,$a0,$a1,$a2,$a3
        _M_DATA "STRB",1,$r,$a0,$a1,$a2,$a3
        MEND
        
        ;// Load unsigned half word from stack
        MACRO
        M_LDRH  $r,$a0,$a1,$a2,$a3
        _M_DATA "LDRH",2,$r,$a0,$a1,$a2,$a3
        MEND
        
        ;// Load signed half word from stack
        MACRO
        M_LDRSH $r,$a0,$a1,$a2,$a3
        _M_DATA "LDRSH",2,$r,$a0,$a1,$a2,$a3
        MEND
        
        ;// Store half word to stack
        MACRO
        M_STRH  $r,$a0,$a1,$a2,$a3
        _M_DATA "STRH",2,$r,$a0,$a1,$a2,$a3
        MEND

        ;// Load word from stack
        MACRO
        M_LDR   $r,$a0,$a1,$a2,$a3
        _M_DATA "LDR",4,$r,$a0,$a1,$a2,$a3
        MEND
        
        ;// Store word to stack
        MACRO
        M_STR   $r,$a0,$a1,$a2,$a3
        _M_DATA "STR",4,$r,$a0,$a1,$a2,$a3
        MEND

        ;// Load double word from stack
        MACRO
        M_LDRD  $r0,$r1,$a0,$a1,$a2,$a3
        _M_DATA "LDRD",8,"$r0,$r1",$a0,$a1,$a2,$a3
        MEND
                
        ;// Store double word to stack
        MACRO
        M_STRD  $r0,$r1,$a0,$a1,$a2,$a3
        _M_DATA "STRD",8,"$r0,$r1",$a0,$a1,$a2,$a3
        MEND
        
        ;// Get absolute address of stack allocated location
        MACRO
        M_ADR   $a, $b, $cc
        _M_OPC  ADD$cc, $a, sp, (_Workspace + $b$_F)
        MEND
        
        ;// Get absolute address of stack allocated location and align the address to 16 bytes
        MACRO
        M_ADR16 $a, $b, $cc
            _M_OPC  ADD$cc, $a, sp, (_Workspace + $b$_F$_16)
        
            ;// Now align $a to 16 bytes
            BIC$cc  $a,$a,#0x0F
        MEND
        
        ;// Get absolute address of stack allocated location and align the address to 32 bytes
        MACRO
        M_ADR32 $a, $b, $cc
            _M_OPC  ADD$cc, $a, sp, (_Workspace + $b$_F$_32)
        
            ;// Now align $a to 32 bytes
            BIC$cc  $a,$a,#0x1F
        MEND

;//////////////////////////////////////////////////////////
;// Function header and footer macros
;//////////////////////////////////////////////////////////      
        
        ;// Function Header Macro    
        ;// Generates the function prologue
        ;// Note that functions should all be "stack-moves-once"
        ;// The FNSTART and FNEND macros should be the only places
        ;// where the stack moves.
        ;//    
        ;// $name  = function name
        ;// $rreg  = ""   don't stack any registers
        ;//          "lr" stack "lr" only
        ;//          "rN" stack registers "r4-rN,lr"
        ;// $dreg  = ""   don't stack any D registers
        ;//          "dN" stack registers "d8-dN"
        ;//
        ;// Note: ARM Archicture procedure call standard AAPCS
        ;// states that r4-r11, sp, d8-d15 must be preserved by
        ;// a compliant function.
        MACRO
        M_START $name, $rreg, $dreg
        ASSERT :LNOT:_InFunc
        ASSERT "$name"!=""
_InFunc SETL {TRUE}
_RBytes SETA 0
_Workspace SETA 0

        ;// Create an area for the function        
        AREA    |.text|, CODE
        EXPORT  $name
$name   FUNCTION
        
        ;// Save R registers
        _M_GETRREGLIST $rreg
        IF _RRegList<>""
            STMFD   sp!, {$_RRegList, lr}
        ENDIF
                
        ;// Save D registers
        _M_GETDREGLIST  $dreg        
        IF _DRegList<>""
            VSTMFD  sp!, {$_DRegList}
        ENDIF            
            
                    
        ;// Ensure size claimed on stack is 8-byte aligned
        IF ((_SBytes:AND:7)!=0)
_SBytes     SETA _SBytes + (8 - (_SBytes:AND:7))
        ENDIF
        
        IF (_SBytes!=0)
            _M_OPC SUB, sp, sp, _SBytes
        ENDIF
        
        
_ABytes SETA _SBytes + _RBytes - _Workspace

                        
        ;// Print function name if debug enabled
        M_PRINTF "$name\n",
        MEND
        
        ;// Work out a list of R saved registers
        MACRO
        _M_GETRREGLIST $rreg
        IF "$rreg"=""
_RRegList   SETS ""
            MEXIT
        ENDIF        
        IF "$rreg"="lr":LOR:"$rreg"="r4"
_RRegList   SETS "r4"
_RBytes     SETA _RBytes+8
            MEXIT
        ENDIF
        IF "$rreg"="r5":LOR:"$rreg"="r6"
_RRegList   SETS "r4-r6"
_RBytes     SETA _RBytes+16
            MEXIT
        ENDIF
        IF "$rreg"="r7":LOR:"$rreg"="r8"
_RRegList   SETS "r4-r8"
_RBytes     SETA _RBytes+24
            MEXIT
        ENDIF
        IF "$rreg"="r9":LOR:"$rreg"="r10"
_RRegList   SETS "r4-r10"
_RBytes     SETA _RBytes+32
            MEXIT
        ENDIF
        IF "$rreg"="r11":LOR:"$rreg"="r12"
_RRegList   SETS "r4-r12"
_RBytes     SETA _RBytes+40
            MEXIT
        ENDIF
        INFO 1, "Unrecognized saved r register limit '$rreg'"
        MEND        
        
        ;// Work out a list of D saved registers
        MACRO
        _M_GETDREGLIST $dreg
        IF "$dreg"=""
_DRegList   SETS ""
            MEXIT
        ENDIF        
        IF "$dreg"="d8"
_DRegList   SETS "d8"
_RBytes     SETA _RBytes+8
            MEXIT
        ENDIF
        IF "$dreg"="d9"
_DRegList   SETS "d8-d9"
_RBytes     SETA _RBytes+16
            MEXIT
        ENDIF
        IF "$dreg"="d10"
_DRegList   SETS "d8-d10"
_RBytes     SETA _RBytes+24
            MEXIT
        ENDIF
        IF "$dreg"="d11"
_DRegList   SETS "d8-d11"
_RBytes     SETA _RBytes+32
            MEXIT
        ENDIF
        IF "$dreg"="d12"
_DRegList   SETS "d8-d12"
_RBytes     SETA _RBytes+40
            MEXIT
        ENDIF
        IF "$dreg"="d13"
_DRegList   SETS "d8-d13"
_RBytes     SETA _RBytes+48
            MEXIT
        ENDIF
        IF "$dreg"="d14"
_DRegList   SETS "d8-d14"
_RBytes     SETA _RBytes+56
            MEXIT
        ENDIF
        IF "$dreg"="d15"
_DRegList   SETS "d8-d15"
_RBytes     SETA _RBytes+64
            MEXIT
        ENDIF
        INFO 1, "Unrecognized saved d register limit '$dreg'"
        MEND
        
        ;// Produce function return instructions
        MACRO
        _M_RET $cc
        IF _DRegList<>""
            VPOP$cc {$_DRegList}
        ENDIF
        IF _RRegList=""
            BX$cc lr
        ELSE
            LDM$cc.FD sp!, {$_RRegList, pc}
        ENDIF
        MEND        
        
        ;// Early Function Exit Macro
        ;// $cc = condition to exit with
        ;// (Example: M_EXIT EQ)
        MACRO
        M_EXIT  $cc
        ASSERT  _InFunc
        IF  _SBytes!=0
            ;// Restore stack frame and exit
            B$cc  _End$_F
        ELSE
            ;// Can return directly
            _M_RET $cc
        ENDIF        
        MEND        

        ;// Function Footer Macro        
        ;// Generates the function epilogue
        MACRO
        M_END
        ASSERT _InFunc
_InFunc SETL {FALSE}
_End$_F

        ;// Restore the stack pointer to its original value on function entry
        IF _SBytes!=0
            _M_OPC ADD, sp, sp, _SBytes
        ENDIF
        _M_RET
        ENDFUNC

        ;// Reset the global stack tracking variables back to their 
        ;// initial values, and increment the function count
_SBytes        SETA 0
_F             SETA _F+1
        MEND

                
;//==========================================================================
;// Debug Macros
;//==========================================================================

        GBLL    DEBUG_ON
DEBUG_ON SETL   {FALSE}
        GBLL    DEBUG_STALLS_ON
DEBUG_STALLS_ON SETL {FALSE}
        
        ;//==========================================================================
        ;// Debug call to printf
        ;//  M_PRINTF $format, $val0, $val1, $val2
        ;//
        ;// Examples:
        ;//  M_PRINTF "x=%08x\n", r0
        ;//
        ;// This macro preserves the value of all registers including the
        ;// flags.
        ;//==========================================================================

        MACRO
        M_PRINTF  $format, $val0, $val1, $val2
        IF DEBUG_ON
        
        IMPORT  printf
        LCLA    nArgs
nArgs	SETA    0
        
        ;// save registers so we don't corrupt them
        STMFD   sp!, {r0-r12, lr}
        
        ;// Drop stack to give us some workspace
        SUB     sp, sp, #16
        
        ;// Save registers we need to print to the stack
        IF "$val2" <> ""
            ASSERT "$val1" <> ""
            STR    $val2, [sp, #8]
nArgs       SETA   nArgs+1
        ENDIF
        IF "$val1" <> ""
            ASSERT "$val0" <> ""
            STR    $val1, [sp, #4]
nArgs	    SETA   nArgs+1
        ENDIF
        IF "$val0"<>""
            STR    $val0, [sp]
nArgs	    SETA   nArgs+1
        ENDIF
        
        ;// Now we are safe to corrupt registers
        ADR     r0, %FT00
        IF nArgs=1
          LDR   r1, [sp]
        ENDIF
        IF nArgs=2
          LDMIA sp, {r1,r2}
        ENDIF
        IF nArgs=3
          LDMIA sp, {r1,r2,r3}
        ENDIF
        
        ;// print the values
        MRS     r4, cpsr        ;// preserve flags
        BL      printf
        MSR     cpsr_f, r4      ;// restore flags
        B       %FT01
00      ;// string to print
        DCB     "$format", 0
        ALIGN
01      ;// Finished
        ADD     sp, sp, #16
        ;// Restore registers
        LDMFD	sp!, {r0-r12,lr}

        ENDIF   ;// DEBUG_ON
        MEND


        ;// Stall Simulation Macro
        ;// Inserts a given number of NOPs for the currently
        ;//  defined platform
        MACRO
        M_STALL $plat1stall, $plat2stall, $plat3stall, $plat4stall, $plat5stall, $plat6stall
        IF DEBUG_STALLS_ON
            _M_STALL_SUB $plat1stall    
            _M_STALL_SUB $plat2stall    
            _M_STALL_SUB $plat3stall    
            _M_STALL_SUB $plat4stall    
            _M_STALL_SUB $plat5stall    
            _M_STALL_SUB $plat6stall    
        ENDIF
        MEND
        
        MACRO
        _M_STALL_SUB $platstall
        IF "$platstall"!=""
            LCLA _pllen
            LCLS _pl
            LCLL _pllog
_pllen      SETA :LEN:"$platstall"
_pl         SETS "$platstall":LEFT:(_pllen - 2)
            IF :DEF:$_pl
                IF $_pl
                    LCLS _st
                    LCLA _stnum
_st                 SETS "$platstall":RIGHT:1        
_stnum              SETA $_st
                    WHILE _stnum>0
			MOV sp, sp
_stnum                  SETA _stnum - 1
                    WEND
                ENDIF
            ENDIF
        ENDIF
        MEND
        
        
        
;//==========================================================================
;// Endian Invarience Macros
;// 
;// The idea behind these macros is that if an array is
;// loaded as words then the SMUL00 macro will multiply
;// array elements 0 regardless of the endianess of the
;// system. For little endian SMUL00=SMULBB, for big
;// endian SMUL00=SMULTT and similarly for other packed operations.
;//
;//==========================================================================

        MACRO
        LIBI4   $comli, $combi, $a, $b, $c, $d, $cc
        IF {ENDIAN}="big"
        $combi.$cc $a, $b, $c, $d
        ELSE
        $comli.$cc $a, $b, $c, $d
        ENDIF
        MEND
        
        MACRO
        LIBI3   $comli, $combi, $a, $b, $c, $cc
        IF {ENDIAN}="big"
        $combi.$cc $a, $b, $c
        ELSE
        $comli.$cc $a, $b, $c
        ENDIF
        MEND
        
        ;// SMLAxy macros
        
        MACRO
        SMLA00  $a, $b, $c, $d, $cc
        LIBI4 SMLABB, SMLATT, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLA01  $a, $b, $c, $d, $cc
        LIBI4 SMLABT, SMLATB, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLA0B  $a, $b, $c, $d, $cc
        LIBI4 SMLABB, SMLATB, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLA0T  $a, $b, $c, $d, $cc
        LIBI4 SMLABT, SMLATT, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLA10  $a, $b, $c, $d, $cc
        LIBI4 SMLATB, SMLABT, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLA11  $a, $b, $c, $d, $cc
        LIBI4 SMLATT, SMLABB, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLA1B  $a, $b, $c, $d, $cc
        LIBI4 SMLATB, SMLABB, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLA1T  $a, $b, $c, $d, $cc
        LIBI4 SMLATT, SMLABT, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLAB0  $a, $b, $c, $d, $cc
        LIBI4 SMLABB, SMLABT, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLAB1  $a, $b, $c, $d, $cc
        LIBI4 SMLABT, SMLABB, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLAT0  $a, $b, $c, $d, $cc
        LIBI4 SMLATB, SMLATT, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLAT1  $a, $b, $c, $d, $cc
        LIBI4 SMLATT, SMLATB, $a, $b, $c, $d, $cc
        MEND
        
        ;// SMULxy macros
        
        MACRO
        SMUL00  $a, $b, $c, $cc
        LIBI3 SMULBB, SMULTT, $a, $b, $c, $cc
        MEND
        
        MACRO
        SMUL01  $a, $b, $c, $cc
        LIBI3 SMULBT, SMULTB, $a, $b, $c, $cc
        MEND
        
        MACRO
        SMUL0B  $a, $b, $c, $cc
        LIBI3 SMULBB, SMULTB, $a, $b, $c, $cc
        MEND
        
        MACRO
        SMUL0T  $a, $b, $c, $cc
        LIBI3 SMULBT, SMULTT, $a, $b, $c, $cc
        MEND
        
        MACRO
        SMUL10  $a, $b, $c, $cc
        LIBI3 SMULTB, SMULBT, $a, $b, $c, $cc
        MEND
        
        MACRO
        SMUL11  $a, $b, $c, $cc
        LIBI3 SMULTT, SMULBB, $a, $b, $c, $cc
        MEND
        
        MACRO
        SMUL1B  $a, $b, $c, $cc
        LIBI3 SMULTB, SMULBB, $a, $b, $c, $cc
        MEND
        
        MACRO
        SMUL1T  $a, $b, $c, $cc
        LIBI3 SMULTT, SMULBT, $a, $b, $c, $cc
        MEND
        
        MACRO
        SMULB0  $a, $b, $c, $cc
        LIBI3 SMULBB, SMULBT, $a, $b, $c, $cc
        MEND
        
        MACRO
        SMULB1  $a, $b, $c, $cc
        LIBI3 SMULBT, SMULBB, $a, $b, $c, $cc
        MEND
        
        MACRO
        SMULT0  $a, $b, $c, $cc
        LIBI3 SMULTB, SMULTT, $a, $b, $c, $cc
        MEND
        
        MACRO
        SMULT1  $a, $b, $c, $cc
        LIBI3 SMULTT, SMULTB, $a, $b, $c, $cc
        MEND
        
        ;// SMLAWx, SMULWx macros
        
        MACRO
        SMLAW0  $a, $b, $c, $d, $cc
        LIBI4 SMLAWB, SMLAWT, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLAW1  $a, $b, $c, $d, $cc
        LIBI4 SMLAWT, SMLAWB, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMULW0  $a, $b, $c, $cc
        LIBI3 SMULWB, SMULWT, $a, $b, $c, $cc
        MEND
        
        MACRO
        SMULW1  $a, $b, $c, $cc
        LIBI3 SMULWT, SMULWB, $a, $b, $c, $cc
        MEND

        ;// SMLALxy macros


        MACRO
        SMLAL00  $a, $b, $c, $d, $cc
        LIBI4 SMLALBB, SMLALTT, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLAL01  $a, $b, $c, $d, $cc
        LIBI4 SMLALBT, SMLALTB, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLAL0B  $a, $b, $c, $d, $cc
        LIBI4 SMLALBB, SMLALTB, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLAL0T  $a, $b, $c, $d, $cc
        LIBI4 SMLALBT, SMLALTT, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLAL10  $a, $b, $c, $d, $cc
        LIBI4 SMLALTB, SMLALBT, $a, $b, $c, $d, $cc
        MEND

        MACRO
        SMLAL11  $a, $b, $c, $d, $cc
        LIBI4 SMLALTT, SMLALBB, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLAL1B  $a, $b, $c, $d, $cc
        LIBI4 SMLALTB, SMLALBB, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLAL1T  $a, $b, $c, $d, $cc
        LIBI4 SMLALTT, SMLALBT, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLALB0  $a, $b, $c, $d, $cc
        LIBI4 SMLALBB, SMLALBT, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLALB1  $a, $b, $c, $d, $cc
        LIBI4 SMLALBT, SMLALBB, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLALT0  $a, $b, $c, $d, $cc
        LIBI4 SMLALTB, SMLALTT, $a, $b, $c, $d, $cc
        MEND
        
        MACRO
        SMLALT1  $a, $b, $c, $d, $cc
        LIBI4 SMLALTT, SMLALTB, $a, $b, $c, $d, $cc
        MEND
        
  ENDIF ;// ARMCOMM_S_H
            
  END
