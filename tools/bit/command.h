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

#ifndef COMMAND_H
#define COMMAND_H

#include <map>
#include <string>
#include <vector>

using namespace std;

struct Command
{
    Command(const string& prog);
    ~Command();

    void AddArg(const string& arg);
    void AddEnv(const string& name, const string& value);

    const char* GetProg() const;
    char* const* GetArgv() const;
    char* const* GetEnv() const;

    string GetCommandline() const;

    string prog;
    vector<string> args;
    map<string,string> env;
};

/**
 * Run the command and collect stdout.
 * Returns the exit code.
 */
string get_command_output(const Command& command, int* err, bool quiet=false);

/**
 * Run the command.
 * Returns the exit code.
 */
int run_command(const Command& command);

// Mac OS doesn't have execvpe. This is the same as execvpe.
int exec_with_path_search(const char* prog, char const* const* argv, char const* const* envp);

#endif // COMMAND_H

