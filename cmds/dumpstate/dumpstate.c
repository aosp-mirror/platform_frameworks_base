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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <limits.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/time.h>
#include <sys/resource.h>

#include <cutils/sockets.h>
#include "private/android_filesystem_config.h"

#define LOG_NDEBUG 0
#define LOG_TAG "dumpstate"
#include <utils/Log.h>

#include "dumpstate.h"

static char* const gzip_args[] = { "gzip", "-6", 0 };
static int start_pattern[] = { 150, 0 };
static int end_pattern[] = { 75, 50, 75, 50, 75, 0 };

static struct tm now;

static void dump_kernel_log(const char *path, const char *title) ;

/* dumps the current system state to stdout */
static void dumpstate(int full) {
    if (full) {
        PRINT("========================================================");
        PRINT("== dumpstate");
        PRINT("========================================================");
        PRINT("------ MEMORY INFO ------");
        DUMP("/proc/meminfo");
        PRINT("------ CPU INFO ------");
        EXEC7("top", "-n", "1", "-d", "1", "-m", "30", "-t");
        PRINT("------ PROCRANK ------");
        EXEC_XBIN("procrank");
        PRINT("------ VIRTUAL MEMORY STATS ------");
        DUMP("/proc/vmstat");
        PRINT("------ VMALLOC INFO ------");
        DUMP("/proc/vmallocinfo");
        PRINT("------ SLAB INFO ------");
        DUMP("/proc/slabinfo");
        PRINT("------ ZONEINFO ------");
        DUMP("/proc/zoneinfo");
        PRINT("------ SYSTEM LOG ------");
        EXEC4("logcat", "-v", "time", "-d", "*:v");
        PRINT("------ VM TRACES ------");
        DUMP("/data/anr/traces.txt");
        PRINT("------ EVENT LOG TAGS ------");
        DUMP("/etc/event-log-tags");
        PRINT("------ EVENT LOG ------");
        EXEC6("logcat", "-b", "events", "-v", "time", "-d", "*:v");
        PRINT("------ RADIO LOG ------");
        EXEC6("logcat", "-b", "radio", "-v", "time", "-d", "*:v");
        PRINT("------ NETWORK STATE ------");
        PRINT("Interfaces:");
        EXEC("netcfg");
        PRINT("");
        PRINT("Routes:");
        DUMP("/proc/net/route");
        PRINT("------ SYSTEM PROPERTIES ------");
        print_properties();
        PRINT("------ KERNEL LOG ------");
        EXEC("dmesg");
        PRINT("------ KERNEL WAKELOCKS ------");
        DUMP("/proc/wakelocks");
        PRINT("------ KERNEL CPUFREQ ------");
        DUMP("/sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state");
        PRINT("");
        PRINT("------ PROCESSES ------");
        EXEC1("ps", "-P");
        PRINT("------ PROCESSES AND THREADS ------");
        EXEC3("ps", "-t", "-p", "-P");
        PRINT("------ LIBRANK ------");
        EXEC_XBIN("librank");
        PRINT("------ BINDER FAILED TRANSACTION LOG ------");
        DUMP("/proc/binder/failed_transaction_log");
        PRINT("");
        PRINT("------ BINDER TRANSACTION LOG ------");
        DUMP("/proc/binder/transaction_log");
        PRINT("");
        PRINT("------ BINDER TRANSACTIONS ------");
        DUMP("/proc/binder/transactions");
        PRINT("");
        PRINT("------ BINDER STATS ------");
        DUMP("/proc/binder/stats");
        PRINT("");
        PRINT("------ BINDER PROCESS STATE: $i ------");
        DUMP_FILES("/proc/binder/proc");
        PRINT("------ FILESYSTEMS ------");
        EXEC("df");
        PRINT("------ PACKAGE SETTINGS ------");
        DUMP("/data/system/packages.xml");
        PRINT("------ PACKAGE UID ERRORS ------");
        DUMP("/data/system/uiderrors.txt");

        dump_kernel_log("/proc/last_kmsg", "LAST KMSG");

        PRINT("------ LAST RADIO LOG ------");
        EXEC1("parse_radio_log", "/proc/last_radio_log");

        dump_kernel_log("/data/dontpanic/apanic_console",
                        "PANIC CONSOLE");
        dump_kernel_log("/data/dontpanic/apanic_threads",
                        "PANIC THREADS");

        PRINT("------ BACKLIGHTS ------");
        DUMP_PROMPT("LCD brightness=", "/sys/class/leds/lcd-backlight/brightness");
        DUMP_PROMPT("Button brightness=", "/sys/class/leds/button-backlight/brightness");
        DUMP_PROMPT("Keyboard brightness=", "/sys/class/leds/keyboard-backlight/brightness");
        DUMP_PROMPT("ALS mode=", "/sys/class/leds/lcd-backlight/als");
        DUMP_PROMPT("LCD driver registers:\n", "/sys/class/leds/lcd-backlight/registers");
    }
    PRINT("========================================================");
    PRINT("== build.prop");
    PRINT("========================================================");

    /* the crash server parses key-value pairs between the VERSION INFO and
     * END lines so we can aggregate crash reports based on this data.
     */
    PRINT("------ VERSION INFO ------");
    print_date("currenttime=", &now);
    DUMP_PROMPT("kernel.version=", "/proc/version");
    DUMP_PROMPT("kernel.cmdline=", "/proc/cmdline");
    DUMP("/system/build.prop");
    PROPERTY("gsm.version.ril-impl");
    PROPERTY("gsm.version.baseband");
    PROPERTY("gsm.imei");
    PROPERTY("gsm.sim.operator.numeric");
    PROPERTY("gsm.operator.alpha");
    PRINT("------ END ------");

    if (full) {
        PRINT("========================================================");
        PRINT("== dumpsys");
        PRINT("========================================================");
        /* the full dumpsys is starting to take a long time, so we need
           to increase its timeout.  we really need to do the timeouts in
           dumpsys itself... */
        EXEC_TIMEOUT("dumpsys", 60);
    }
}

/* used to check the file name passed via argv[0] */
static int check_command_name(const char* name, const char* test) {
    int name_length, test_length;

    if (!strcmp(name, test))
        return 1;

    name_length = strlen(name);
    test_length = strlen(test);

    if (name_length > test_length + 2) {
        name += (name_length - test_length);
        if (name[-1] != '/')
            return 0;
        if (!strcmp(name, test))
            return 1;
    }

    return 0;
}

int main(int argc, char *argv[]) {
    int dumpcrash = check_command_name(argv[0], "dumpcrash");
    int add_date = 0;
    char* outfile = 0;
    int vibrate = 0;
    int compress = 0;
    int socket = 0;
    int c, fd, vibrate_fd, fds[2];
    char path[PATH_MAX];
    pid_t   pid;
    gid_t groups[] = { AID_LOG, AID_SDCARD_RW };

    LOGI("begin\n");

    /* set as high priority, and protect from OOM killer */
    setpriority(PRIO_PROCESS, 0, -20);
    protect_from_oom_killer();

    get_time(&now);

    do {
        c = getopt(argc, argv, "do:svz");
        if (c == EOF)
            break;
        switch (c) {
            case 'd':
                add_date = 1;
                break;
            case 'o':
                outfile = optarg;
                break;
            case 'v':
                vibrate = 1;
                break;
            case 'z':
                compress = 1;
                break;
            case 's':
                socket = 1;
                break;
            case '?':
            fprintf(stderr, "%s: invalid option -%c\n",
                argv[0], optopt);
                exit(1);
        }
    } while (1);

    /* open vibrator before switching user */
    if (vibrate) {
        vibrate_fd = open("/sys/class/timed_output/vibrator/enable", O_WRONLY);
        if (vibrate_fd > 0)
            fcntl(vibrate_fd, F_SETFD, FD_CLOEXEC);
    } else
        vibrate_fd = -1;

    /* switch to non-root user and group */
    setgroups(sizeof(groups)/sizeof(groups[0]), groups);
    setuid(AID_SHELL);

    /* make it safe to use both printf and STDOUT_FILENO */ 
    setvbuf(stdout, 0, _IONBF, 0);

    if (socket) {
        struct sockaddr addr;
        socklen_t alen;

        int s = android_get_control_socket("dumpstate");
        if (s < 0) {
            fprintf(stderr, "could not open dumpstate socket\n");
            exit(1);
        }
        if (listen(s, 4) < 0) {
            fprintf(stderr, "could not listen on dumpstate socket\n");
            exit(1);
        }

        alen = sizeof(addr);
        fd = accept(s, &addr, &alen);
        if (fd < 0) {
            fprintf(stderr, "could not accept dumpstate socket\n");
            exit(1);
        }

        /* redirect stdout to the socket */
        dup2(fd, STDOUT_FILENO);
        close(fd);
    } else if (outfile) {
        if (strlen(outfile) > sizeof(path) - 100)
            exit(1);

        strcpy(path, outfile);
        if (add_date) {
            char date[260];
            strftime(date, sizeof(date),
                "-%Y-%m-%d-%H-%M-%S",
                &now);
            strcat(path, date);
        }
        if (compress)
            strcat(path, ".gz");
        else
            strcat(path, ".txt");

        /* ensure that all directories in the path exist */ 
        create_directories(path);
        fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, S_IRUSR | S_IWUSR | S_IRGRP | S_IROTH);
        if (fd < 0)
            return fd;

        if (compress) {
            pipe(fds);

            /* redirect our stdout to the pipe */
            dup2(fds[1], STDOUT_FILENO);
            close(fds[1]);

            if ((pid = fork()) < 0)
            {
                fprintf(stderr, "fork error\n");
                exit(1);
            }

            if (pid) {
                /* parent case */

                /* close our copy of the input to gzip */
                close(fds[0]);
                /* close our copy of the output file */
                close(fd);
            } else {
                /* child case */

               /* redirect our input pipe to stdin */
                dup2(fds[0], STDIN_FILENO);
                close(fds[0]);

                /* redirect stdout to the output file */
                dup2(fd, STDOUT_FILENO);
                close(fd);

                /* run gzip to postprocess our output */
                execv("/system/bin/gzip", gzip_args);
                fprintf(stderr, "execv returned\n");
            }
        } else {
            /* redirect stdout to the output file */
            dup2(fd, STDOUT_FILENO);
            close(fd);
        }
    }
    /* else everything will print to stdout */

    if (vibrate) {
        vibrate_pattern(vibrate_fd, start_pattern);
    }
    dumpstate(!dumpcrash);
    if (vibrate) {
        vibrate_pattern(vibrate_fd, end_pattern);
        close(vibrate_fd);
    }

    /* so gzip will terminate */
    close(STDOUT_FILENO);

    LOGI("done\n");

    return 0;
}

static void dump_kernel_log(const char *path, const char *title) 

{
    printf("------ KERNEL %s LOG ------\n", title);
    if (access(path, R_OK) < 0)
        printf("%s: %s\n", path, strerror(errno));
    else {
        struct stat sbuf;

        if (stat(path, &sbuf) < 0)
            printf("%s: stat failed (%s)\n", path, strerror(errno));
        else
            printf("Harvested %s", ctime(&sbuf.st_mtime));
    
        DUMP(path);
    }
}
