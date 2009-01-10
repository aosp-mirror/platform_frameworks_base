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
#include <time.h>
#include <unistd.h>
#include <sys/stat.h>
#include <dirent.h>
#include <limits.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/time.h>
#include <sys/wait.h>

#include <cutils/properties.h>
#include <sys/system_properties.h>

#include "dumpstate.h"


/* prints the contents of a file */
int dump_file(const char* path) {
    char    buffer[32768];
    int fd, amount_read;
    int ret = 0;

    fd = open(path, O_RDONLY);
    if (fd < 0)
        return fd;

    do {
        ret = read(fd, buffer, sizeof(buffer));
        if (ret > 0)
            ret = write(STDOUT_FILENO, buffer, ret);
    } while (ret > 0);

    buffer[0] = '\n';
    write(STDOUT_FILENO, buffer, 1);

    close(fd);
    return ret;
}

/* prints the contents of all files in a directory */
void dump_files(const char* path) {
    DIR* dir;
    struct dirent* entry;
    char buffer[PATH_MAX];

    dir = opendir(path);
    if (!dir) {
        fprintf(stderr, "could not open directory %s\n", path);
        return;
    }

    while ((entry = readdir(dir))) {
        if (entry->d_type == DT_REG) {
            snprintf(buffer, sizeof(buffer), "%s/%s", path, entry->d_name);
            dump_file(path);
            printf("\n");
        }
    }

    closedir(dir);
}

/* prints the name and value of a system property */
int print_property(const char* name) {
    char    value[PROP_VALUE_MAX];

    __system_property_get(name, value);
    printf("%s=%s\n", name, value);
    return 0;
}

static pid_t alarm_pid = 0;
static int timed_out = 0;
static void sig_alarm(int sig)
{
    if (alarm_pid) {
        kill(alarm_pid, SIGKILL);
        timed_out = 1;
        alarm_pid = 0;
    }
}

/* forks a command and waits for it to finish */
int run_command(struct Command* cmd, int timeout) {
    struct sigaction sa;
    pid_t pid;
    int status;

    pid = fork();
    /* handle error case */
    if (pid < 0)
        return pid;

    /* handle child case */
    if (pid == 0) {
        int ret = execv(cmd->path, cmd->args);
        if (ret)
            fprintf(stderr, "execv %s returned %d\n", cmd->path, ret);
        exit(ret);
    }

    /* handle parent case */
    timed_out = 0;
    if (timeout) {
        memset(&sa, 0, sizeof(sa));
        sa.sa_flags = SA_RESETHAND;
        sa.sa_handler = sig_alarm;
        sigaction(SIGALRM, &sa, NULL);

        /* set an alarm so we don't hang forever */
        alarm_pid = pid;
        alarm(timeout);
    }

    waitpid(pid, &status, 0);

    if (timed_out)
        printf("ERROR: command %s timed out\n", cmd->path);

    return status;
}

/* reads the current time into tm */
void get_time(struct tm *tm) {
    time_t t;

    tzset();
    time(&t);
    localtime_r(&t, tm);
}

/* prints the date in tm */
void print_date(const char* prompt, struct tm *tm) {
    char strbuf[260];

    strftime(strbuf, sizeof(strbuf),
             "%a %b %e %H:%M:%S %Z %Y",
             tm);
    printf("%s%s\n", prompt, strbuf);
}


static void print_prop(const char *key, const char *name, 
                     void *user __attribute__((unused)))
{
    printf("[%s]: [%s]\n", key, name);
}

/* prints all the system properties */
void print_properties() {
    property_list(print_prop, NULL);
}

/* creates directories as needed for the given path */
void create_directories(char *path)
{
    char *chp = path;

    /* skip initial slash */
    if (chp[0] == '/')
        chp++;

    while (chp && chp[0]) {
        chp = strchr(chp, '/');
        if (chp) {
            *chp = 0;
            mkdir(path, S_IRWXU | S_IRWXG | S_IROTH | S_IXOTH);
            *chp = '/';
            chp++;
        }
    }
}

/* runs the vibrator using the given pattern */
void vibrate_pattern(int fd, int* pattern)
{
    struct timespec tm;
    char    buffer[10];

    while (*pattern) {
        /* read vibrate on time */
        int on_time = *pattern++;
        snprintf(buffer, sizeof(buffer), "%d", on_time);
        write(fd, buffer, strlen(buffer));

        /* read vibrate off time */
        int delay = *pattern++;
        if (delay) {
            delay += on_time;

            tm.tv_sec = delay / 1000;
            tm.tv_nsec = (delay % 1000) * 1000000;
            nanosleep(&tm, NULL);
        } else
            break;
    }
}

/* prevents the OOM killer from killing us */
void protect_from_oom_killer()
{
    int fd;

    fd = open("/proc/self/oom_adj", O_WRONLY);
    if (fd >= 0) {
        // -17 should make us immune to OOM
        const char* text = "-17";
        write(fd, text, strlen(text));
        close(fd);
    }
}
