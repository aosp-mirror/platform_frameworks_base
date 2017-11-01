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

#include "aapt.h"

#include "command.h"
#include "print.h"
#include "util.h"

#include <regex>

const regex NS_REGEX("( *)N: ([^=]+)=(.*)");
const regex ELEMENT_REGEX("( *)E: ([^ ]+) \\(line=(\\d+)\\)");
const regex ATTR_REGEX("( *)A: ([^\\(=]+)[^=]*=\"([^\"]+)\".*");

const string ANDROID_NS("http://schemas.android.com/apk/res/android");

bool
Apk::HasActivity(const string& className)
{
    string fullClassName = full_class_name(package, className);
    const size_t N = activities.size();
    for (size_t i=0; i<N; i++) {
        if (activities[i] == fullClassName) {
            return true;
        }
    }
    return false;
}

struct Attribute {
    string ns;
    string name;
    string value;
};

struct Element {
    Element* parent;
    string ns;
    string name;
    int lineno;
    vector<Attribute> attributes;
    vector<Element*> children;

    /**
     * Indentation in the xmltree dump. Might not be equal to the distance
     * from the root because namespace rows (scopes) have their own indentation.
     */
    int depth;

    Element();
    ~Element();

    string GetAttr(const string& ns, const string& name) const;
    void FindElements(const string& ns, const string& name, vector<Element*>* result, bool recurse);
    
};

Element::Element()
{
}

Element::~Element()
{
    const size_t N = children.size();
    for (size_t i=0; i<N; i++) {
        delete children[i];
    }
}

string
Element::GetAttr(const string& ns, const string& name) const
{
    const size_t N = attributes.size();
    for (size_t i=0; i<N; i++) {
        const Attribute& attr = attributes[i];
        if (attr.ns == ns && attr.name == name) {
            return attr.value;
        }
    }
    return string();
}

void
Element::FindElements(const string& ns, const string& name, vector<Element*>* result, bool recurse)
{
    const size_t N = children.size();
    for (size_t i=0; i<N; i++) {
        Element* child = children[i];
        if (child->ns == ns && child->name == name) {
            result->push_back(child);
        }
        if (recurse) {
            child->FindElements(ns, name, result, recurse);
        }
    }
}

struct Scope {
    Scope* parent;
    int depth;
    map<string,string> namespaces;

    Scope(Scope* parent, int depth);
};

Scope::Scope(Scope* p, int d)
    :parent(p),
     depth(d)
{
     if (p != NULL) {
         namespaces = p->namespaces;
     }
}


string
full_class_name(const string& packageName, const string& className)
{
    if (className.length() == 0) {
        return "";
    }
    if (className[0] == '.') {
        return packageName + className;
    }
    if (className.find('.') == string::npos) {
        return packageName + "." + className;
    }
    return className;
}

string
pretty_component_name(const string& packageName, const string& className)
{
    if (starts_with(packageName, className)) {
        size_t pn = packageName.length();
        size_t cn = className.length();
        if (cn > pn && className[pn] == '.') {
            return packageName + "/" + string(className, pn, string::npos);
        }
    }
    return packageName + "/" + className;
}

int
inspect_apk(Apk* apk, const string& filename)
{
    // Load the manifest xml
    Command cmd("aapt");
    cmd.AddArg("dump");
    cmd.AddArg("xmltree");
    cmd.AddArg(filename);
    cmd.AddArg("AndroidManifest.xml");

    int err;

    string output = get_command_output(cmd, &err, false);
    check_error(err);

    // Parse the manifest xml
    Scope* scope = new Scope(NULL, -1);
    Element* root = NULL;
    Element* current = NULL;
    vector<string> lines;
    split_lines(&lines, output);
    for (size_t i=0; i<lines.size(); i++) {
        const string& line = lines[i];
        smatch match;
        if (regex_match(line, match, NS_REGEX)) {
            int depth = match[1].length() / 2;
            while (depth < scope->depth) {
                Scope* tmp = scope;
                scope = scope->parent;
                delete tmp;
            }
            scope = new Scope(scope, depth);
            scope->namespaces[match[2]] = match[3];
        } else if (regex_match(line, match, ELEMENT_REGEX)) {
            Element* element = new Element();

            string str = match[2];
            size_t colon = str.find(':');
            if (colon == string::npos) {
                element->name = str;
            } else {
                element->ns = scope->namespaces[string(str, 0, colon)];
                element->name.assign(str, colon+1, string::npos);
            }
            element->lineno = atoi(match[3].str().c_str());
            element->depth = match[1].length() / 2;

            if (root == NULL) {
                current = element;
                root = element;
            } else {
                while (element->depth <= current->depth && current->parent != NULL) {
                    current = current->parent;
                }
                element->parent = current;
                current->children.push_back(element);
                current = element;
            }
        } else if (regex_match(line, match, ATTR_REGEX)) {
            if (current != NULL) {
                Attribute attr;
                string str = match[2];
                size_t colon = str.find(':');
                if (colon == string::npos) {
                    attr.name = str;
                } else {
                    attr.ns = scope->namespaces[string(str, 0, colon)];
                    attr.name.assign(str, colon+1, string::npos);
                }
                attr.value = match[3];
                current->attributes.push_back(attr);
            }
        }
    }
    while (scope != NULL) {
        Scope* tmp = scope;
        scope = scope->parent;
        delete tmp;
    }

    // Package name
    apk->package = root->GetAttr("", "package");
    if (apk->package.size() == 0) {
        print_error("%s:%d: Manifest root element doesn't contain a package attribute",
                filename.c_str(), root->lineno);
        delete root;
        return 1;
    }

    // Instrumentation runner
    vector<Element*> instrumentation;
    root->FindElements("", "instrumentation", &instrumentation, true);
    if (instrumentation.size() > 0) {
        // TODO: How could we deal with multiple instrumentation tags?
        // We'll just pick the first one.
        apk->runner = instrumentation[0]->GetAttr(ANDROID_NS, "name");
    }

    // Activities
    vector<Element*> activities;
    root->FindElements("", "activity", &activities, true);
    for (size_t i=0; i<activities.size(); i++) {
        string name = activities[i]->GetAttr(ANDROID_NS, "name");
        if (name.size() == 0) {
            continue;
        }
        apk->activities.push_back(full_class_name(apk->package, name));
    }

    delete root;
    return 0;
}

