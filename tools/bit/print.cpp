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

#include "print.h"

#include <sys/ioctl.h>
#include <stdarg.h>
#include <stdio.h>
#include <unistd.h>

#include "util.h"

bool g_stdoutIsTty;
char const* g_escapeBold;
char const* g_escapeRedBold;
char const* g_escapeGreenBold;
char const* g_escapeYellowBold;
char const* g_escapeUnderline;
char const* g_escapeEndColor;
char const* g_escapeClearLine;

void
init_print()
{
    if (isatty(fileno(stdout))) {
		g_stdoutIsTty = true;
		g_escapeBold = "\033[1m";
		g_escapeRedBold = "\033[91m\033[1m";
		g_escapeGreenBold = "\033[92m\033[1m";
		g_escapeYellowBold = "\033[93m\033[1m";
		g_escapeUnderline = "\033[4m";
		g_escapeEndColor = "\033[0m";
		g_escapeClearLine = "\033[K";
	} else {
		g_stdoutIsTty = false;
		g_escapeBold = "";
		g_escapeRedBold = "";
		g_escapeGreenBold = "";
		g_escapeYellowBold = "";
		g_escapeUnderline = "";
		g_escapeEndColor = "";
		g_escapeClearLine = "";
    }
}

void
print_status(const char* format, ...)
{
    printf("\n%s%s", g_escapeBold, g_escapeUnderline);

    va_list args;
    va_start(args, format);
    vfprintf(stdout, format, args);
    va_end(args);

    printf("%s\n", g_escapeEndColor);
}

void
print_command(const Command& command)
{
    fputs(g_escapeBold, stdout);
    for (map<string,string>::const_iterator it=command.env.begin(); it!=command.env.end(); it++) {
        fputs(it->first.c_str(), stdout);
        fputc('=', stdout);
        fputs(escape_for_commandline(it->second.c_str()).c_str(), stdout);
        putc(' ', stdout);
    }
    fputs(command.prog.c_str(), stdout);
    for (vector<string>::const_iterator it=command.args.begin(); it!=command.args.end(); it++) {
        putc(' ', stdout);
        fputs(escape_for_commandline(it->c_str()).c_str(), stdout);
    }
    fputs(g_escapeEndColor, stdout);
    fputc('\n', stdout);
}

void
print_error(const char* format, ...)
{
    fputs(g_escapeRedBold, stderr);

    va_list args;
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);

    fputs(g_escapeEndColor, stderr);
    fputc('\n', stderr);
}

void
print_warning(const char* format, ...)
{
    fputs(g_escapeYellowBold, stderr);

    va_list args;
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);

    fputs(g_escapeEndColor, stderr);
    fputc('\n', stderr);
}

void
print_info(const char* format, ...)
{
    fputs(g_escapeBold, stdout);

    va_list args;
    va_start(args, format);
    vfprintf(stdout, format, args);
    va_end(args);

    fputs(g_escapeEndColor, stdout);
    fputc('\n', stdout);
}

void
print_one_line(const char* format, ...)
{
    if (g_stdoutIsTty) {
        struct winsize ws;
        ioctl(STDOUT_FILENO, TIOCGWINSZ, &ws);
        int size = ws.ws_col + 1;
        char* buf = (char*)malloc(size);

        va_list args;
        va_start(args, format);
        vsnprintf(buf, size, format, args);
        va_end(args);

        printf("%s%s\r", buf, g_escapeClearLine);
        free(buf);

        fflush(stdout);
    } else {
        va_list args;
        va_start(args, format);
        vfprintf(stdout, format, args);
        va_end(args);
        printf("\n");
    }
}

void
check_error(int err)
{
    if (err != 0) {
        fputc('\n', stderr);
        print_error("Stopping due to errors.");
        exit(1);
    }
}


