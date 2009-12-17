/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
#include "mp4dec_lib.h"

/* ====================================================================== /
    Function : PutSKIPPED_MB()
    Date     : 04/03/2000
/ ====================================================================== */

void PutSKIPPED_MB(uint8 *comp, uint8 *prev, int width)
{
    int32 *temp0, *temp1;
    int  row;
    row = MB_SIZE;


    while (row)
    {
        temp0 = (int32 *)prev;
        temp1 = (int32 *)comp;

        temp1[0] = temp0[0];
        temp1[1] = temp0[1];
        temp1[2] = temp0[2];
        temp1[3] = temp0[3];

        comp += width;
        prev += width;

        temp0 = (int32 *)prev;
        temp1 = (int32 *)comp;

        temp1[0] = temp0[0];
        temp1[1] = temp0[1];
        temp1[2] = temp0[2];
        temp1[3] = temp0[3];

        comp += width;
        prev += width;

        temp0 = (int32 *)prev;
        temp1 = (int32 *)comp;
        temp1[0] = temp0[0];
        temp1[1] = temp0[1];
        temp1[2] = temp0[2];
        temp1[3] = temp0[3];


        comp += width;
        prev += width;

        temp0 = (int32 *)prev;
        temp1 = (int32 *)comp;
        temp1[0] = temp0[0];
        temp1[1] = temp0[1];
        temp1[2] = temp0[2];
        temp1[3] = temp0[3];

        comp += width;
        prev += width;
        row -= 4;
    }
}


/* ====================================================================== /
    Function : PutSKIPPED_B()
    Date     : 04/03/2000
/ ====================================================================== */

void PutSKIPPED_B(uint8 *comp, uint8 *prev, int width)
{
    int32 *temp0, *temp1;
    int  row;

    row = B_SIZE;
    while (row)
    {
        temp0 = (int32 *)prev;
        temp1 = (int32 *)comp;

        temp1[0] = temp0[0];
        temp1[1] = temp0[1];

        comp += width;
        prev += width;

        temp0 = (int32 *)prev;
        temp1 = (int32 *)comp;

        temp1[0] = temp0[0];
        temp1[1] = temp0[1];

        comp += width;
        prev += width;

        temp0 = (int32 *)prev;
        temp1 = (int32 *)comp;

        temp1[0] = temp0[0];
        temp1[1] = temp0[1];

        comp += width;
        prev += width;

        temp0 = (int32 *)prev;
        temp1 = (int32 *)comp;

        temp1[0] = temp0[0];
        temp1[1] = temp0[1];

        comp += width;
        prev += width;
        row -= 4;
    }
}


