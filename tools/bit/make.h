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

#ifndef MAKE_H
#define MAKE_H

#include <map>
#include <string>
#include <vector>

using namespace std;

struct Module
{
    string name;
    vector<string> classes;
    vector<string> paths;
    vector<string> installed;

    bool HasClass(const string& cl);
};

/**
 * Class to encapsulate getting build variables. Caches the
 * results if possible.
 */
class BuildVars
{
public:
    BuildVars(const string& outDir, const string& buildProduct,
            const string& buildVariant, const string& buildType);
    ~BuildVars();

    string GetBuildVar(const string& name, bool quiet);

private:
    void save();

    string m_filename;

    map<string,string> m_cache;
};

void read_modules(const string& buildOut, const string& buildDevice,
        map<string,Module>* modules, bool quiet);

int build_goals(const vector<string>& goals);

#endif // MAKE_H
