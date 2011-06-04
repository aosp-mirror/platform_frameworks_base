@
@ Copyright (C) 2009 The Android Open Source Project
@
@ Licensed under the Apache License, Version 2.0 (the "License");
@ you may not use this file except in compliance with the License.
@ You may obtain a copy of the License at
@
@      http://www.apache.org/licenses/LICENSE-2.0
@
@ Unless required by applicable law or agreed to in writing, software
@ distributed under the License is distributed on an "AS IS" BASIS,
@ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@ See the License for the specific language governing permissions and
@ limitations under the License.
@

#include "asm_common.S"

    require8
    preserve8

    .arm
    .fpu neon
    .text

/* Input / output registers */
#define image   r0
#define data    r1
#define width   r2
#define luma    r3
#define cb      r4
#define cr      r5
#define cwidth  r6

/* -- NEON registers -- */

#define qRow0     Q0.U8
#define qRow1     Q1.U8
#define qRow2     Q2.U8
#define qRow3     Q3.U8
#define qRow4     Q4.U8
#define qRow5     Q5.U8
#define qRow6     Q6.U8
#define qRow7     Q7.U8
#define qRow8     Q8.U8
#define qRow9     Q9.U8
#define qRow10    Q10.U8
#define qRow11    Q11.U8
#define qRow12    Q12.U8
#define qRow13    Q13.U8
#define qRow14    Q14.U8
#define qRow15    Q15.U8

#define dRow0     D0.U8
#define dRow1     D1.U8
#define dRow2     D2.U8
#define dRow3     D3.U8
#define dRow4     D4.U8
#define dRow5     D5.U8
#define dRow6     D6.U8
#define dRow7     D7.U8
#define dRow8     D8.U8
#define dRow9     D9.U8
#define dRow10    D10.U8
#define dRow11    D11.U8
#define dRow12    D12.U8
#define dRow13    D13.U8
#define dRow14    D14.U8
#define dRow15    D15.U8

/*------------------------------------------------------------------------------

    Function: h264bsdWriteMacroblock

        Functional description:
            Write one macroblock into the image. Both luma and chroma
            components will be written at the same time.

        Inputs:
            data    pointer to macroblock data to be written, 256 values for
                    luma followed by 64 values for both chroma components

        Outputs:
            image   pointer to the image where the macroblock will be written

        Returns:
            none

------------------------------------------------------------------------------*/

function h264bsdWriteMacroblock, export=1
    PUSH    {r4-r6,lr}
    VPUSH   {q4-q7}

    LDR     width, [image, #4]
    LDR     luma, [image, #0xC]
    LDR     cb, [image, #0x10]
    LDR     cr, [image, #0x14]


@   Write luma
    VLD1    {qRow0, qRow1}, [data]!
    LSL     width, width, #4
    VLD1    {qRow2, qRow3}, [data]!
    LSR     cwidth, width, #1
    VST1    {qRow0}, [luma,:128], width
    VLD1    {qRow4, qRow5}, [data]!
    VST1    {qRow1}, [luma,:128], width
    VLD1    {qRow6, qRow7}, [data]!
    VST1    {qRow2}, [luma,:128], width
    VLD1    {qRow8, qRow9}, [data]!
    VST1    {qRow3}, [luma,:128], width
    VLD1    {qRow10, qRow11}, [data]!
    VST1    {qRow4}, [luma,:128], width
    VLD1    {qRow12, qRow13}, [data]!
    VST1    {qRow5}, [luma,:128], width
    VLD1    {qRow14, qRow15}, [data]!
    VST1    {qRow6}, [luma,:128], width

    VLD1    {qRow0, qRow1}, [data]! ;//cb rows 0,1,2,3
    VST1    {qRow7}, [luma,:128], width
    VLD1    {qRow2, qRow3}, [data]! ;//cb rows 4,5,6,7
    VST1    {qRow8}, [luma,:128], width
    VLD1    {qRow4, qRow5}, [data]! ;//cr rows 0,1,2,3
    VST1    {qRow9}, [luma,:128], width
    VLD1    {qRow6, qRow7}, [data]! ;//cr rows 4,5,6,7
    VST1    {qRow10}, [luma,:128], width
    VST1    {dRow0}, [cb,:64], cwidth
    VST1    {dRow8}, [cr,:64], cwidth
    VST1    {qRow11}, [luma,:128], width
    VST1    {dRow1}, [cb,:64], cwidth
    VST1    {dRow9}, [cr,:64], cwidth
    VST1    {qRow12}, [luma,:128], width
    VST1    {dRow2}, [cb,:64], cwidth
    VST1    {dRow10}, [cr,:64], cwidth
    VST1    {qRow13}, [luma,:128], width
    VST1    {dRow3}, [cb,:64], cwidth
    VST1    {dRow11}, [cr,:64], cwidth
    VST1    {qRow14}, [luma,:128], width
    VST1    {dRow4}, [cb,:64], cwidth
    VST1    {dRow12}, [cr,:64], cwidth
    VST1    {qRow15}, [luma]
    VST1    {dRow5}, [cb,:64], cwidth
    VST1    {dRow13}, [cr,:64], cwidth
    VST1    {dRow6}, [cb,:64], cwidth
    VST1    {dRow14}, [cr,:64], cwidth
    VST1    {dRow7}, [cb,:64]
    VST1    {dRow15}, [cr,:64]

    VPOP    {q4-q7}
    POP     {r4-r6,pc}
@    BX      lr

    .endfunc



