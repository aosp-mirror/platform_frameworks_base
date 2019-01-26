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
#include <json/writer.h>
#include <json/value.h>

#include <fstream>
#include <string>
#include <map>
#include <thread>

#include <sys/types.h>
#include <dirent.h>
#include <string.h>

using namespace std;

static bool
map_contains(const map<string,string>& m, const string& k, const string& v) {
    map<string,string>::const_iterator it = m.find(k);
    if (it == m.end()) {
        return false;
    }
    return it->second == v;
}

static string
make_cache_filename(const string& outDir)
{
    string filename(outDir);
    return filename + "/.bit_cache";
}

BuildVars::BuildVars(const string& outDir, const string& buildProduct,
        const string& buildVariant, const string& buildType)
    :m_filename(),
     m_cache()
{
    m_cache["TARGET_PRODUCT"] = buildProduct;
    m_cache["TARGET_BUILD_VARIANT"] = buildVariant;
    m_cache["TARGET_BUILD_TYPE"] = buildType;

    // If we have any problems reading the file, that's ok, just do
    // uncached calls to make / soong.

    if (outDir == "") {
        return;
    }


    m_filename = make_cache_filename(outDir);

    std::ifstream stream(m_filename, std::ifstream::binary);

    if (stream.fail()) {
        return;
    }

    Json::Value json;
    Json::Reader reader;
    if (!reader.parse(stream, json)) {
        return;
    }

    if (!json.isObject()) {
        return;
    }

    map<string,string> cache;

    vector<string> names = json.getMemberNames();
    const int N = names.size();
    for (int i=0; i<N; i++) {
        const string& name = names[i];
        const Json::Value& value = json[name];
        if (!value.isString()) {
            continue;
        }
        cache[name] = value.asString();
    }

    // If all of the base variables match, then we can use this cache.  Otherwise, use our
    // base one.  The next time someone reads a value, the new one, with our base varaibles
    // will be saved.
    if (map_contains(cache, "TARGET_PRODUCT", buildProduct)
            && map_contains(cache, "TARGET_BUILD_VARIANT", buildVariant)
            && map_contains(cache, "TARGET_BUILD_TYPE", buildType)) {
        m_cache = cache;
    }
}

BuildVars::~BuildVars()
{
}

void
BuildVars::save()
{
    if (m_filename == "") {
        return;
    }

    Json::StyledStreamWriter writer("  ");

    Json::Value json(Json::objectValue);

    for (map<string,string>::const_iterator it = m_cache.begin(); it != m_cache.end(); it++) {
        json[it->first] = it->second;
    }

    std::ofstream stream(m_filename, std::ofstream::binary);
    writer.write(stream, json);
}

string
BuildVars::GetBuildVar(const string& name, bool quiet)
{
    int err;

    map<string,string>::iterator it = m_cache.find(name);
    if (it == m_cache.end()) {
        Command cmd("build/soong/soong_ui.bash");
        cmd.AddArg("--dumpvar-mode");
        cmd.AddArg(name);

        string output = trim(get_command_output(cmd, &err, quiet));
        if (err == 0) {
            m_cache[name] = output;
            save();
            return output;
        } else {
            return string();
        }
    } else {
        return it->second;
    }
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

