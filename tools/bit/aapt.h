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

#ifndef AAPT_H
#define AAPT_H

#include <string>
#include <vector>

using namespace std;

struct Apk
{
    string package;
    string runner;
    vector<string> activities;

    bool HasActivity(const string& className);
};

string full_class_name(const string& packageName, const string& className);
string pretty_component_name(const string& packageName, const string& className);

int inspect_apk(Apk* apk, const string& filename);

#endif // AAPT_H
