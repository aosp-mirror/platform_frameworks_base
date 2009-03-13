/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef _DUMPSTATE_H_
#define _DUMPSTATE_H_

#include <time.h>

// Commands time out after 60 seconds
#define TIMEOUT     60

#define PRINT(s) printf("%s\n", s)

#define DUMP(file) dump_file(file)

#define DUMP_FILES(path) dump_files(path)

#define DUMP_PROMPT(prompt, file)   \
{                                   \
    printf(prompt);                 \
    dump_file(file);                \
}

#define EXEC(cmd)               \
{                               \
    static struct Command c = { \
        "/system/bin/" cmd,     \
        { cmd, 0 }              \
    };                          \
    run_command(&c, TIMEOUT);   \
}

#define EXEC_TIMEOUT(cmd, tmout)\
{                               \
    static struct Command c = { \
        "/system/bin/" cmd,     \
        { cmd, 0 }              \
    };                          \
    run_command(&c, tmout);     \
}

#define EXEC_XBIN(cmd)          \
{                               \
    static struct Command c = { \
        "/system/xbin/" cmd,    \
        { cmd, 0 }              \
    };                          \
    run_command(&c, TIMEOUT);   \
}

#define EXEC2(cmd, a1, a2)      \
{                               \
    static struct Command c = { \
        "/system/bin/" cmd,     \
        { cmd, a1, a2, 0 }      \
    };                          \
    run_command(&c, TIMEOUT);   \
}

#define EXEC4(cmd, a1, a2, a3, a4)  \
{                                   \
    static struct Command c = {     \
        "/system/bin/" cmd,         \
        { cmd, a1, a2, a3, a4, 0 }  \
    };                              \
    run_command(&c, TIMEOUT);       \
}

#define EXEC6(cmd, a1, a2, a3, a4, a5, a6)  \
{                                           \
    static struct Command c = {             \
        "/system/bin/" cmd,                 \
        { cmd, a1, a2, a3, a4, a5, a6, 0 }  \
    };                                      \
    run_command(&c, TIMEOUT);               \
}

#define EXEC7(cmd, a1, a2, a3, a4, a5, a6, a7)  \
{                                               \
    static struct Command c = {                 \
        "/system/bin/" cmd,                     \
        { cmd, a1, a2, a3, a4, a5, a6, a7, 0 }  \
    };                                          \
    run_command(&c, TIMEOUT);                   \
}

#define EXEC8(cmd, a1, a2, a3, a4, a5, a6, a7, a8)  \
{                                                   \
    static struct Command c = {                     \
        "/system/bin/" cmd,                         \
        { cmd, a1, a2, a3, a4, a5, a6, a7, a8, 0 }  \
    };                                              \
    run_command(&c, TIMEOUT);                       \
}

#define PROPERTY(name) print_property(name)

struct Command {
    const char* path;
    char* const args[];
};
typedef struct Command Command;

/* prints the contents of a file */
int dump_file(const char* path);

/* prints the contents of all files in a directory */
void dump_files(const char* path);

/* forks a command and waits for it to finish */
int run_command(struct Command* cmd, int timeout);

/* reads the current time into tm */
void get_time(struct tm *tm);

/* prints the date in tm */
void print_date(const char* prompt, struct tm *tm);

/* prints the name and value of a system property */
int print_property(const char* name);

/* prints all the system properties */
void print_properties();

/* creates directories as needed for the given path */
void create_directories(char *path);

/* runs the vibrator using the given pattern */
void vibrate_pattern(int fd, int* pattern);

/* prevents the OOM killer from killing us */
void protect_from_oom_killer();

#endif /* _DUMPSTATE_H_ */
