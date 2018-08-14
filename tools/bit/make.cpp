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

#include "make.h"

#include "command.h"
#include "print.h"
#include "util.h"

#include <json/reader.h>
#include <json/value.h>

#include <fstream>
#include <string>
#include <map>
#include <thread>

#include <sys/types.h>
#include <dirent.h>
#include <string.h>

using namespace std;

map<string,string> g_buildVars;

string
get_build_var(const string& name, bool quiet)
{
    int err;

    map<string,string>::iterator it = g_buildVars.find(name);
    if (it == g_buildVars.end()) {
        Command cmd("build/soong/soong_ui.bash");
        cmd.AddArg("--dumpvar-mode");
        cmd.AddArg(name);

        string output = trim(get_command_output(cmd, &err, quiet));
        if (err == 0) {
            g_buildVars[name] = output;
            return output;
        } else {
            return string();
        }
    } else {
        return it->second;
    }
}

string
sniff_device_name(const string& buildOut, const string& product)
{
    string match("ro.build.product=" + product);

    string base(buildOut + "/target/product");
    DIR* dir = opendir(base.c_str());
    if (dir == NULL) {
        return string();
    }

    dirent* entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') {
            continue;
        }
        if (entry->d_type == DT_DIR) {
            string filename(base + "/" + entry->d_name + "/system/build.prop");
            vector<string> lines;
            split_lines(&lines, read_file(filename));
            for (size_t i=0; i<lines.size(); i++) {
                if (lines[i] == match) {
                    return entry->d_name;
                }
            }
        }
    }

    closedir(dir);
    return string();
}

void
json_error(const string& filename, const char* error, bool quiet)
{
    if (!quiet) {
        print_error("Unable to parse module info file (%s): %s", error, filename.c_str());
        print_error("Have you done a full build?");
    }
    exit(1);
}

static void
get_values(const Json::Value& json, const string& name, vector<string>* result)
{
    Json::Value nullValue;

    const Json::Value& value = json.get(name, nullValue);
    if (!value.isArray()) {
        return;
    }

    const int N = value.size();
    for (int i=0; i<N; i++) {
        const Json::Value& child = value[i];
        if (child.isString()) {
            result->push_back(child.asString());
        }
    }
}

void
read_modules(const string& buildOut, const string& device, map<string,Module>* result, bool quiet)
{
    string filename(string(buildOut + "/target/product/") + device + "/module-info.json");
    std::ifstream stream(filename, std::ifstream::binary);

    if (stream.fail()) {
        if (!quiet) {
            print_error("Unable to open module info file: %s", filename.c_str());
            print_error("Have you done a full build?");
        }
        exit(1);
    }

    Json::Value json;
    Json::Reader reader;
    if (!reader.parse(stream, json)) {
        json_error(filename, "can't parse json format", quiet);
        return;
    }

    if (!json.isObject()) {
        json_error(filename, "root element not an object", quiet);
        return;
    }

    vector<string> names = json.getMemberNames();
    const int N = names.size();
    for (int i=0; i<N; i++) {
        const string& name = names[i];

        const Json::Value& value = json[name];
        if (!value.isObject()) {
            continue;
        }

        Module module;

        module.name = name;
        get_values(value, "class", &module.classes);
        get_values(value, "path", &module.paths);
        get_values(value, "installed", &module.installed);

        // Only keep classes we can handle
        for (ssize_t i = module.classes.size() - 1; i >= 0; i--) {
            string cl = module.classes[i];
            if (!(cl == "JAVA_LIBRARIES" || cl == "EXECUTABLES" || cl == "SHARED_LIBRARIES"
                    || cl == "APPS" || cl == "NATIVE_TESTS")) {
                module.classes.erase(module.classes.begin() + i);
            }
        }
        if (module.classes.size() == 0) {
            continue;
        }

        // Only target modules (not host)
        for (ssize_t i = module.installed.size() - 1; i >= 0; i--) {
            string fn = module.installed[i];
            if (!starts_with(fn, buildOut + "/target/")) {
                module.installed.erase(module.installed.begin() + i);
            }
        }
        if (module.installed.size() == 0) {
            continue;
        }

        (*result)[name] = module;
    }
}

int
build_goals(const vector<string>& goals)
{
    Command cmd("build/soong/soong_ui.bash");
    cmd.AddArg("--make-mode");
    for (size_t i=0; i<goals.size(); i++) {
        cmd.AddArg(goals[i]);
    }

    return run_command(cmd);
}

