/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "Region"

#include <stdio.h>
#include <utils/Debug.h>
#include <ui/Rect.h>
#include <ui/Region.h>

using namespace android;

int main()
{
    Region empty;
    Region reg0( Rect(  0, 0,  100, 100 ) );
    Region reg1 = reg0;
    Region reg2, reg3;

    Region reg4 = empty | reg1;
    Region reg5 = reg1 | empty;

    reg4.dump("reg4");
    reg5.dump("reg5");
    
    reg0.dump("reg0");
    reg1.dump("reg1");

    reg0 = reg0 | reg0.translate(150, 0);
    reg0.dump("reg0");
    reg1.dump("reg1");

    reg0 = reg0 | reg0.translate(300, 0);
    reg0.dump("reg0");
    reg1.dump("reg1");

    //reg2 = reg0 | reg0.translate(0, 100);
    //reg0.dump("reg0");
    //reg1.dump("reg1");
    //reg2.dump("reg2");

    //reg3 = reg0 | reg0.translate(0, 150);
    //reg0.dump("reg0");
    //reg1.dump("reg1");
    //reg2.dump("reg2");
    //reg3.dump("reg3");

    ALOGD("---");
    reg2 = reg0 | reg0.translate(100, 0);
    reg0.dump("reg0");
    reg1.dump("reg1");
    reg2.dump("reg2");
    
    return 0;
}

