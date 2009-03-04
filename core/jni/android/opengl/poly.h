/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

/* Based on the public domain code:
 * Generic Convex Polygon Scan Conversion and Clipping
 * by Paul Heckbert
 * from "Graphics Gems", Academic Press, 1990
 */


#ifndef POLY_HDR
#define POLY_HDR

namespace android {

#define POLY_NMAX 10		/* max #sides to a polygon; change if needed */
/* note that poly_clip, given an n-gon as input, might output an (n+6)gon */
/* POLY_NMAX=10 is thus appropriate if input polygons are triangles or quads */

typedef struct {		/* A POLYGON VERTEX */
    float sx, sy, sz, sw;	/* screen space position (sometimes homo.) */
} Poly_vert;

typedef struct {		/* A POLYGON */
    int n;			/* number of sides */
    Poly_vert vert[POLY_NMAX];	/* vertices */
} Poly;

#define POLY_CLIP_OUT 0		/* polygon entirely outside box */
#define POLY_CLIP_PARTIAL 1	/* polygon partially inside */
#define POLY_CLIP_IN 2		/* polygon entirely inside box */

int	poly_clip_to_frustum(Poly *p1);

} // namespace android

#endif
