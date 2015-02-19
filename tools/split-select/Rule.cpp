/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include "Rule.h"

#include <utils/String8.h>

using namespace android;

namespace split {

inline static void indentStr(String8& str, int indent) {
    while (indent > 0) {
        str.append("  ");
        indent--;
    }
}

Rule::Rule(const Rule& rhs)
    : RefBase()
    , op(rhs.op)
    , key(rhs.key)
    , negate(rhs.negate)
    , stringArgs(rhs.stringArgs)
    , longArgs(rhs.longArgs)
    , subrules(rhs.subrules) {
}

String8 Rule::toJson(int indent) const {
    String8 str;
    indentStr(str, indent);
    str.append("{\n");
    indent++;
    indentStr(str, indent);
    str.append("\"op\": \"");
    switch (op) {
        case ALWAYS_TRUE:
            str.append("ALWAYS_TRUE");
            break;
        case GREATER_THAN:
            str.append("GREATER_THAN");
            break;
        case LESS_THAN:
            str.append("LESS_THAN");
            break;
        case EQUALS:
            str.append("EQUALS");
            break;
        case AND_SUBRULES:
            str.append("AND_SUBRULES");
            break;
        case OR_SUBRULES:
            str.append("OR_SUBRULES");
            break;
        case CONTAINS_ANY:
            str.append("CONTAINS_ANY");
            break;
        default:
            str.appendFormat("%d", op);
            break;
    }
    str.append("\"");

    if (negate) {
        str.append(",\n");
        indentStr(str, indent);
        str.append("\"negate\": true");
    }

    bool includeKey = true;
    switch (op) {
        case AND_SUBRULES:
        case OR_SUBRULES:
            includeKey = false;
            break;
        default:
            break;
    }

    if (includeKey) {
        str.append(",\n");
        indentStr(str, indent);
        str.append("\"property\": \"");
        switch (key) {
            case NONE:
                str.append("NONE");
                break;
            case SDK_VERSION:
                str.append("SDK_VERSION");
                break;
            case SCREEN_DENSITY:
                str.append("SCREEN_DENSITY");
                break;
            case NATIVE_PLATFORM:
                str.append("NATIVE_PLATFORM");
                break;
            case LANGUAGE:
                str.append("LANGUAGE");
                break;
            default:
                str.appendFormat("%d", key);
                break;
        }
        str.append("\"");
    }

    if (op == AND_SUBRULES || op == OR_SUBRULES) {
        str.append(",\n");
        indentStr(str, indent);
        str.append("\"subrules\": [\n");
        const size_t subruleCount = subrules.size();
        for (size_t i = 0; i < subruleCount; i++) {
            str.append(subrules[i]->toJson(indent + 1));
            if (i != subruleCount - 1) {
                str.append(",");
            }
            str.append("\n");
        }
        indentStr(str, indent);
        str.append("]");
    } else {
        switch (key) {
            case SDK_VERSION:
            case SCREEN_DENSITY: {
                str.append(",\n");
                indentStr(str, indent);
                str.append("\"args\": [");
                const size_t argCount = longArgs.size();
                for (size_t i = 0; i < argCount; i++) {
                    if (i != 0) {
                        str.append(", ");
                    }
                    str.appendFormat("%d", longArgs[i]);
                }
                str.append("]");
                break;
            }
            case LANGUAGE:
            case NATIVE_PLATFORM: {
                str.append(",\n");
                indentStr(str, indent);
                str.append("\"args\": [");
                const size_t argCount = stringArgs.size();
                for (size_t i = 0; i < argCount; i++) {
                    if (i != 0) {
                        str.append(", ");
                    }
                    str.append(stringArgs[i]);
                }
                str.append("]");
                break;
            }
            default:
                break;
        }
    }
    str.append("\n");
    indent--;
    indentStr(str, indent);
    str.append("}");
    return str;
}

sp<Rule> Rule::simplify(sp<Rule> rule) {
    if (rule->op != AND_SUBRULES && rule->op != OR_SUBRULES) {
        return rule;
    }

    Vector<sp<Rule> > newSubrules;
    newSubrules.setCapacity(rule->subrules.size());
    const size_t subruleCount = rule->subrules.size();
    for (size_t i = 0; i < subruleCount; i++) {
        sp<Rule> simplifiedRule = simplify(rule->subrules.editItemAt(i));
        if (simplifiedRule != NULL) {
            if (simplifiedRule->op == rule->op) {
                newSubrules.appendVector(simplifiedRule->subrules);
            } else {
                newSubrules.add(simplifiedRule);
            }
        }
    }

    const size_t newSubruleCount = newSubrules.size();
    if (newSubruleCount == 0) {
        return NULL;
    } else if (subruleCount == 1) {
        return newSubrules.editTop();
    }
    rule->subrules = newSubrules;
    return rule;
}

} // namespace split
