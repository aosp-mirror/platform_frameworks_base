#include "res_check.h"
#include "localize.h"
#include "file_utils.h"
#include "ValuesFile.h"

#include <stdio.h>

static int check_file(const ValuesFile* file);
static int check_value(const SourcePos& pos, const XMLNode* value);
static int scan_for_unguarded_format(const SourcePos& pos, const XMLNode* value, int depth = 0);

int
do_rescheck(const vector<string>& files)
{
    int err;

    Configuration english;
        english.locale = "en_US";

    for (size_t i=0; i<files.size(); i++) {
        const string filename = files[i];
        ValuesFile* valuesFile = get_local_values_file(filename, english, CURRENT_VERSION,
                "0", true);
        if (valuesFile != NULL) {
            err |= check_file(valuesFile);
            delete valuesFile;
        } else {
            err |= 1;
        }
    }

    return err;
}

static int
check_file(const ValuesFile* file)
{
    int err = 0;
    set<StringResource> strings = file->GetStrings();
    for (set<StringResource>::iterator it=strings.begin(); it!=strings.end(); it++) {
        XMLNode* value = it->value;
        if (value != NULL) {
            err |= check_value(it->pos, value);
        }
    }
    return err;
}

static bool
contains_percent(const string& str)
{
    const size_t len = str.length();
    for (size_t i=0; i<len; i++) {
        char c = str[i];
        if (c == '%') {
            return true;
        }
    }
    return false;
}

static int
check_value(const SourcePos& pos, const XMLNode* value)
{
    int err = 0;
    err |= scan_for_unguarded_format(pos, value);
    return err;
}

static bool
is_xliff_block(const string& ns, const string& name)
{
    if (ns == XLIFF_XMLNS) {
        return name == "g";
    } else {
        return false;
    }
}

static int
scan_for_unguarded_format(const SourcePos& pos, const string& string)
{
    bool containsPercent = contains_percent(string);
    if (containsPercent) {
        pos.Error("unguarded percent: '%s'\n", string.c_str());
    }
    return 0;
}

static int
scan_for_unguarded_format(const SourcePos& pos, const XMLNode* value, int depth)
{
    if (value->Type() == XMLNode::ELEMENT) {
        int err = 0;
        if (depth == 0 || !is_xliff_block(value->Namespace(), value->Name())) {
            const vector<XMLNode*>& children = value->Children();
            for (size_t i=0; i<children.size(); i++) {
                err |= scan_for_unguarded_format(pos, children[i], depth+1);
            }
        }
        return err;
    } else {
        return scan_for_unguarded_format(pos, value->Text());
    }
}

