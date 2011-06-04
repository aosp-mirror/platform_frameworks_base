;
; Copyright (C) 2009 The Android Open Source Project
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;      http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
;

    REQUIRE8
    PRESERVE8

    AREA    |.text|, CODE

    EXPORT h264bsdWriteMacroblock

; Input / output registers
image   RN  0
data    RN  1
width   RN  2
luma    RN  3
cb      RN  4
cr      RN  5
cwidth  RN  6

; -- NEON registers --

qRow0   QN  Q0.U8
qRow1   QN  Q1.U8
qRow2   QN  Q2.U8
qRow3   QN  Q3.U8
qRow4   QN  Q4.U8
qRow5   QN  Q5.U8
qRow6   QN  Q6.U8
qRow7   QN  Q7.U8
qRow8   QN  Q8.U8
qRow9   QN  Q9.U8
qRow10  QN  Q10.U8
qRow11  QN  Q11.U8
qRow12  QN  Q12.U8
qRow13  QN  Q13.U8
qRow14  QN  Q14.U8
qRow15  QN  Q15.U8

dRow0   DN  D0.U8
dRow1   DN  D1.U8
dRow2   DN  D2.U8
dRow3   DN  D3.U8
dRow4   DN  D4.U8
dRow5   DN  D5.U8
dRow6   DN  D6.U8
dRow7   DN  D7.U8
dRow8   DN  D8.U8
dRow9   DN  D9.U8
dRow10  DN  D10.U8
dRow11  DN  D11.U8
dRow12  DN  D12.U8
dRow13  DN  D13.U8
dRow14  DN  D14.U8
dRow15  DN  D15.U8

;/*------------------------------------------------------------------------------
;
;    Function: h264bsdWriteMacroblock
;
;        Functional description:
;            Write one macroblock into the image. Both luma and chroma
;            components will be written at the same time.
;
;        Inputs:
;            data    pointer to macroblock data to be written, 256 values for
;                    luma followed by 64 values for both chroma components
;
;        Outputs:
;            image   pointer to the image where the macroblock will be written
;
;        Returns:
;            none
;
;------------------------------------------------------------------------------*/

h264bsdWriteMacroblock
    PUSH    {r4-r6,lr}
    VPUSH   {q4-q7}

    LDR     width, [image, #4]
    LDR     luma, [image, #0xC]
    LDR     cb, [image, #0x10]
    LDR     cr, [image, #0x14]


;   Write luma
    VLD1    {qRow0, qRow1}, [data]!
    LSL     width, width, #4
    VLD1    {qRow2, qRow3}, [data]!
    LSR     cwidth, width, #1
    VST1    {qRow0}, [luma@128], width
    VLD1    {qRow4, qRow5}, [data]!
    VST1    {qRow1}, [luma@128], width
    VLD1    {qRow6, qRow7}, [data]!
    VST1    {qRow2}, [luma@128], width
    VLD1    {qRow8, qRow9}, [data]!
    VST1    {qRow3}, [luma@128], width
    VLD1    {qRow10, qRow11}, [data]!
    VST1    {qRow4}, [luma@128], width
    VLD1    {qRow12, qRow13}, [data]!
    VST1    {qRow5}, [luma@128], width
    VLD1    {qRow14, qRow15}, [data]!
    VST1    {qRow6}, [luma@128], width

    VLD1    {qRow0, qRow1}, [data]! ;cb rows 0,1,2,3
    VST1    {qRow7}, [luma@128], width
    VLD1    {qRow2, qRow3}, [data]! ;cb rows 4,5,6,7
    VST1    {qRow8}, [luma@128], width
    VLD1    {qRow4, qRow5}, [data]! ;cr rows 0,1,2,3
    VST1    {qRow9}, [luma@128], width
    VLD1    {qRow6, qRow7}, [data]! ;cr rows 4,5,6,7
    VST1    {qRow10}, [luma@128], width
    VST1    {dRow0}, [cb@64], cwidth
    VST1    {dRow8}, [cr@64], cwidth
    VST1    {qRow11}, [luma@128], width
    VST1    {dRow1}, [cb@64], cwidth
    VST1    {dRow9}, [cr@64], cwidth
    VST1    {qRow12}, [luma@128], width
    VST1    {dRow2}, [cb@64], cwidth
    VST1    {dRow10}, [cr@64], cwidth
    VST1    {qRow13}, [luma@128], width
    VST1    {dRow3}, [cb@64], cwidth
    VST1    {dRow11}, [cr@64], cwidth
    VST1    {qRow14}, [luma@128], width
    VST1    {dRow4}, [cb@64], cwidth
    VST1    {dRow12}, [cr@64], cwidth
    VST1    {qRow15}, [luma]
    VST1    {dRow5}, [cb@64], cwidth
    VST1    {dRow13}, [cr@64], cwidth
    VST1    {dRow6}, [cb@64], cwidth
    VST1    {dRow14}, [cr@64], cwidth
    VST1    {dRow7}, [cb@64]
    VST1    {dRow15}, [cr@64]

    VPOP    {q4-q7}
    POP     {r4-r6,pc}
    END


