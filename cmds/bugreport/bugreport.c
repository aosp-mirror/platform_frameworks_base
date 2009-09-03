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

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include <cutils/properties.h>
#include <cutils/sockets.h>

int main(int argc, char *argv[]) {
    char buffer[65536];
    int i, s;

    /* start the dumpstate service */
    property_set("ctl.start", "dumpstate");

    /* socket will not be available until service starts */
    for (i = 0; i < 10; i++) {
        s = socket_local_client("dumpstate",
                             ANDROID_SOCKET_NAMESPACE_RESERVED,
                             SOCK_STREAM);
        if (s >= 0)
            break;
        /* try again in 1 second */
        sleep(1);
    }

    if (s < 0) {
        fprintf(stderr, "Failed to connect to dumpstate service\n");
        exit(1);
    }

    while (1) {
        int length = read(s, buffer, sizeof(buffer));
        if (length <= 0)
            break;
        fwrite(buffer, 1, length, stdout);
    }

    close(s);
    return 0;
}
