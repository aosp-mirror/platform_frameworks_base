/* San Angeles Observation OpenGL ES version example
 * Copyright 2004-2005 Jetro Lauha
 * All rights reserved.
 * Web: http://iki.fi/jetro/
 *
 * This source is free software; you can redistribute it and/or
 * modify it under the terms of EITHER:
 *   (1) The GNU Lesser General Public License as published by the Free
 *       Software Foundation; either version 2.1 of the License, or (at
 *       your option) any later version. The text of the GNU Lesser
 *       General Public License is included with this source in the
 *       file LICENSE-LGPL.txt.
 *   (2) The BSD-style license that is included with this source in
 *       the file LICENSE-BSD.txt.
 *
 * This source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the files
 * LICENSE-LGPL.txt and LICENSE-BSD.txt for more details.
 *
 * $Id: cams.h,v 1.7 2005/01/31 22:15:15 tonic Exp $
 * $Revision: 1.7 $
 */

#ifndef CAMS_H_INCLUDED
#define CAMS_H_INCLUDED


/* Length in milliseconds of one camera track base unit.
 * The value originates from the music synchronization.
 */
#define CAMTRACK_LEN    5442


// Camera track definition for one camera trucking shot.
typedef struct
{
    /* Five parameters of src[5] and dest[5]:
     * eyeX, eyeY, eyeZ, viewAngle, viewHeightOffs
     */
    short src[5], dest[5];
    unsigned char dist;     // if >0, cam rotates around eye xy on dist * 0.1
    unsigned char len;      // length multiplier
} CAMTRACK;

static CAMTRACK sCamTracks[] =
{
    { { 4500, 2700, 100, 70, -30 }, { 50, 50, -90, -100, 0 }, 20, 1 },
    { { -1448, 4294, 25, 363, 0 }, { -136, 202, 125, -98, 100 }, 0, 1 },
    { { 1437, 4930, 200, -275, -20 }, { 1684, 0, 0, 9, 0 }, 0, 1 },
    { { 1800, 3609, 200, 0, 675 }, { 0, 0, 0, 300, 0 }, 0, 1 },
    { { 923, 996, 50, 2336, -80 }, { 0, -20, -50, 0, 170 }, 0, 1 },
    { { -1663, -43, 600, 2170, 0 }, { 20, 0, -600, 0, 100 }, 0, 1 },
    { { 1049, -1420, 175, 2111, -17 }, { 0, 0, 0, -334, 0 }, 0, 2 },
    { { 0, 0, 50, 300, 25 }, { 0, 0, 0, 300, 0 }, 70, 2 },
    { { -473, -953, 3500, -353, -350 }, { 0, 0, -2800, 0, 0 }, 0, 2 },
    { { 191, 1938, 35, 1139, -17 }, { 1205, -2909, 0, 0, 0 }, 0, 2 },
    { { -1449, -2700, 150, 0, 0 }, { 0, 2000, 0, 0, 0 }, 0, 2 },
    { { 5273, 4992, 650, 373, -50 }, { -4598, -3072, 0, 0, 0 }, 0, 2 },
    { { 3223, -3282, 1075, -393, -25 }, { 1649, -1649, 0, 0, 0 }, 0, 2 }
};
#define CAMTRACK_COUNT (sizeof(camTracks) / sizeof(camTracks[0]))


#endif // !CAMS_H_INCLUDED
