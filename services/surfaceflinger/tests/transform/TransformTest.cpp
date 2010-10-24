/*
 * Copyright (C) 2007 The Android Open Source Project
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

#include <utils/Errors.h>
#include "../../Transform.h"

using namespace android;

int main(int argc, char **argv)
{
    Transform tr90(Transform::ROT_90);
    Transform trFH(Transform::FLIP_H);
    Transform trFV(Transform::FLIP_V);

    Transform tr90FH(Transform::ROT_90 | Transform::FLIP_H);
    Transform tr90FV(Transform::ROT_90 | Transform::FLIP_V);

    tr90.dump("tr90");
    trFH.dump("trFH");
    trFV.dump("trFV");

    tr90FH.dump("tr90FH");
    tr90FV.dump("tr90FV");

    (trFH*tr90).dump("trFH*tr90");
    (trFV*tr90).dump("trFV*tr90");

    (tr90*trFH).dump("tr90*trFH");
    (tr90*trFV).dump("tr90*trFV");

    return 0;
}
