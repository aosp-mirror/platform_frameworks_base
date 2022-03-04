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

#include "command.h"

#include "print.h"
#include "util.h"

#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <sys/wait.h>

extern char **environ;

Command::Command(const string& prog)
    :prog(prog)
{
}

Command::~Command()
{
}

void
Command::AddArg(const string& arg)
{
    args.push_back(arg);
}

void
Command::AddEnv(const string& name, const string& value)
{
    env[name] = value;
}

const char*
Command::GetProg() const
{
    return prog.c_str();
}

char *const *
Command::GetArgv() const
{
    const int N = args.size();
    char** result = (char**)malloc(sizeof(char*)*(N+2));
    result[0] = strdup(prog.c_str());
    for (int i=0; i<N; i++) {
        result[i+1] = strdup(args[i].c_str());
    }
    result[N+1] = 0;
    return result;
}

char *const *
Command::GetEnv() const
{
    map<string,string> copy;
    for (const char** p=(const char**)environ; *p != NULL; p++) {
        char* name = strdup(*p);
        char* value = strchr(name, '=');
        *value = '\0';
        value++;
        copy[name] = value;
        free(name);
    }
    for (map<string,string>::const_iterator it=env.begin(); it!=env.end(); it++) {
        copy[it->first] = it->second;
    }
    char** result = (char**)malloc(sizeof(char*)*(copy.size()+1));
    char** row = result;
    for (map<string,string>::const_iterator it=copy.begin(); it!=copy.end(); it++) {
        *row = (char*)malloc(it->first.size() + it->second.size() + 2);
        strcpy(*row, it->first.c_str());
        strcat(*row, "=");
        strcat(*row, it->second.c_str());
        row++;
    }
    *row = NULL;
    return result;
}

string
get_command_output(const Command& command, int* err, bool quiet)
{
    if (!quiet) {
        print_command(command);
    }

    int fds[2];
    if (0 != pipe(fds)) {
        return string();
    }

    pid_t pid = fork();

    if (pid == -1) {
        // fork error
        *err = errno;
        return string();
    } else if (pid == 0) {
        // child
        while ((dup2(fds[1], STDOUT_FILENO) == -1) && (errno == EINTR)) {}
        close(fds[1]);
        close(fds[0]);
        const char* prog = command.GetProg();
        char* const* argv = command.GetArgv();
        char* const* env = command.GetEnv();
        exec_with_path_search(prog, argv, env);
        if (!quiet) {
            print_error("Unable to run command: %s", prog);
        }
        exit(1);
    } else {
        // parent
        close(fds[1]);
        string result;
        const int size = 16*1024;
        char* buf = (char*)malloc(size);
        while (true) {
            ssize_t amt = read(fds[0], buf, size);
            if (amt <= 0) {
                break;
            } else if (amt > 0) {
                result.append(buf, amt);
            }
        }
        free(buf);
        int status;
        waitpid(pid, &status, 0);
        if (WIFEXITED(status)) {
            *err = WEXITSTATUS(status);
            return result;
        } else {
            *err = -1;
            return string();
        }
    }
}


int
run_command(const Command& command)
{
    print_command(command);

    pid_t pid = fork();

    if (pid == -1) {
        // fork error
        return errno;
    } else if (pid == 0) {
        // child
        const char* prog = command.GetProg();
        char* const* argv = command.GetArgv();
        char* const* env = command.GetEnv();
        exec_with_path_search(prog, argv, env);
        print_error("Unable to run command: %s", prog);
        exit(1);
    } else {
        // parent
        int status;
        waitpid(pid, &status, 0);
        if (WIFEXITED(status)) {
            return WEXITSTATUS(status);
        } else {
            return -1;
        }
    }
}

int
exec_with_path_search(const char* prog, char const* const* argv, char const* const* envp)
{
    if (strchr(prog, '/') != NULL) {
        return execve(prog, (char*const*)argv, (char*const*)envp);
    } else {
        const char* pathEnvRaw = getenv("PATH");
        if (pathEnvRaw == NULL) {
            return 1;
        }
        char* pathEnv = strdup(pathEnvRaw);
        char* dir = pathEnv;
        while (dir) {
            char* next = strchr(dir, ':');
            if (next != NULL) {
                *next = '\0';
                next++;
            }
            if (dir[0] == '/') {
                struct stat st;
                string executable = string(dir) + "/" + prog;
                if (stat(executable.c_str(), &st) == 0) {
                    execve(executable.c_str(), (char*const*)argv, (char*const*)envp);
                }
            }
            dir = next;
        }
        free(pathEnv);
        return 1;
    }
}

