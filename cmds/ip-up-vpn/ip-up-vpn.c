/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <cutils/properties.h>

int main(int argc, char **argv)
{
    if (argc > 1 && strlen(argv[1]) > 0) {
        char dns[PROPERTY_VALUE_MAX];
        char *dns1 = getenv("DNS1");
        char *dns2 = getenv("DNS2");

        snprintf(dns, sizeof(dns), "%s %s", dns1 ? dns1 : "", dns2 ? dns2 : "");
        property_set("vpn.dns", dns);
        property_set("vpn.via", argv[1]);
    }
    return 0;
}
