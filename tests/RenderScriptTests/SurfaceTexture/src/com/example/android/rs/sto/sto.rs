/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma version(1)
#pragma rs java_package_name(com.example.android.rs.sto)

#pragma stateFragment(parent)

#include "rs_graphics.rsh"


rs_program_fragment pf;
rs_allocation sto;  // camera in
rs_allocation sto2;
rs_allocation rto;  // render target

int root() {
    rsgBindTexture(pf, 0, sto);

#if 1
    rsgBindColorTarget(rto, 0);

    rsgClearColor(0.f, 1.f, 0.f, 1.f);
    rsgDrawQuadTexCoords(0, 0, 0, 0,0,
                         0,500,0, 1,0,
                         500,500,0, 1, 1,
                         500, 0, 0, 0, 1 );
    rsgClearColorTarget(0);

    // io ops
    rsAllocationIoSend(rto);
    rsAllocationIoReceive(sto2);

    rsgBindTexture(pf, 0, sto2);
#endif

    rsgClearColor(0.f, 1.f, 0.f, 1.f);
    rsgDrawQuadTexCoords(0, 0, 0, 0,0,
                         0,500,0, 1,0,
                         500,500,0, 1, 1,
                         500, 0, 0, 0, 1 );

    return 1;
}

