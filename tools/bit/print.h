/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef PRINT_H
#define PRINT_H

#include "command.h"

extern bool g_stdoutIsTty;
extern char const* g_escapeBold;
extern char const* g_escapeRedBold;
extern char const* g_escapeGreenBold;
extern char const* g_escapeYellowBold;
extern char const* g_escapeUnderline;
extern char const* g_escapeEndColor;
extern char const* g_escapeClearLine;

void init_print();
void print_status(const char* format, ...);
void print_command(const Command& command);
void print_error(const char* format, ...);
void print_warning(const char* format, ...);
void print_info(const char* format, ...);
void print_one_line(const char* format, ...);
void check_error(int err);

#endif // PRINT_H
