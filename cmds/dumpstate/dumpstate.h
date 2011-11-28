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
#include <unistd.h>
#include <stdio.h>

/* prints the contents of a file */
int dump_file(const char *title, const char* path);

/* forks a command and waits for it to finish -- terminate args with NULL */
int run_command(const char *title, int timeout_seconds, const char *command, ...);

/* prints all the system properties */
void print_properties();

/* redirect output to a service control socket */
void redirect_to_socket(FILE *redirect, const char *service);

/* redirect output to a file, optionally gzipping; returns gzip pid */
pid_t redirect_to_file(FILE *redirect, char *path, int gzip_level);

/* dump Dalvik stack traces, return the trace file location (NULL if none) */
const char *dump_vm_traces();

/* for each process in the system, run the specified function */
void for_each_pid(void (*func)(int, const char *), const char *header);

/* Displays a blocked processes in-kernel wait channel */
void show_wchan(int pid, const char *name);

/* Runs "showmap" for a process */
void do_showmap(int pid, const char *name);

/* Gets the dmesg output for the kernel */
void do_dmesg();

/* Play a sound via Stagefright */
void play_sound(const char* path);

/* Implemented by libdumpstate_board to dump board-specific info */
void dumpstate_board();

#endif /* _DUMPSTATE_H_ */
