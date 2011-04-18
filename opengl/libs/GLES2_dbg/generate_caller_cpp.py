#!/usr/bin/python
# -*- coding: utf-8 -*-

#
# Copyright 2011, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import os
import sys

externs = []
    
def generate_caller(lines):
    i = 0
    output = ""
    skipFunctions = []
    
    for line in lines:
        if line.find("API_ENTRY(") >= 0: # a function prototype
            returnType = line[0: line.find(" API_ENTRY(")]
            functionName = line[line.find("(") + 1: line.find(")")] #extract GL function name
            parameterList = line[line.find(")(") + 2: line.find(") {")]
            
            #if line.find("*") >= 0:
            #    extern = "%s Debug_%s(%s);" % (returnType, functionName, parameterList)
            #    externs.append(extern)
            #    continue
            
            if functionName in skipFunctions:
                sys.stderr.write("!\n! skipping function '%s'\n!\n" % functionName)
                continue
            output += "\
    case glesv2debugger::Message_Function_%s:\n" % functionName
            parameters = parameterList.split(',')
            paramIndex = 0
            if line.find("*") >= 0 and (line.find("*") < line.find(":") or line.find("*") > line.rfind(":")): # unannotated pointer
                # add function to list of functions that should be hand written, but generate code anyways
                externs.append(functionName)
                output += "\
        ret = GenerateCall_%s(dbg, cmd, msg, prevRet);\n\
        break;\n" % (functionName)
                continue
            elif line.find(":out") >= 0 or line.find(":inout") >= 0:
                externs.append(functionName)
                output += "\
        ret = GenerateCall_%s(dbg, cmd, msg, prevRet);\n\
        break; // annotated output pointers\n" % (functionName)
                continue
                
            if parameterList == "void":
                parameters = []
            arguments = ""
            paramNames = []
            inout = ""
            getData = ""
            
            callerMembers = ""

            for parameter in parameters:
                const = parameter.find("const")
                parameter = parameter.replace("const", "")
                parameter = parameter.strip()
                paramType = parameter.split(' ')[0]
                paramName = parameter.split(' ')[1]
                annotation = ""
                if parameter.find(":") >= 0: # has annotation
                    assert inout == "" # only one parameter should be annotated
                    sys.stderr.write("%s is annotated: %s \n" % (functionName, paramType))
                    inout = paramType.split(":")[2]
                    annotation = paramType.split(":")[1]
                    paramType = paramType.split(":")[0]
                    count = 1
                    countArg = ""
                    if annotation.find("*") >= 0: # [1,n] * param
                        count = int(annotation.split("*")[0])
                        countArg = annotation.split("*")[1]
                        assert countArg in paramNames
                    elif annotation in paramNames:
                        count = 1
                        countArg = annotation
                    elif annotation == "GLstring":
                        annotation = "strlen(%s)" % (paramName)
                    else:
                        count = int(annotation)
            
                    paramType += "*"
                    arguments += "reinterpret_cast<%s>(const_cast<char *>(cmd.data().data()))" % (paramType)
                elif paramType == "GLboolean":
                    arguments += "GLboolean(cmd.arg%d())" % (paramIndex)
                else:
                    arguments += "static_cast<%s>(cmd.arg%d())" % (paramType, paramIndex)

                if paramIndex < len(parameters) - 1:
                        arguments += ", "
                if len(arguments) - arguments.rfind("\n") > 60 :
                    arguments += "\n\
            "
                if const >= 0:
                    paramType = "const " + paramType
                paramNames.append(paramName)
                paramIndex += 1
                
            if returnType == "void":
                output += "\
        dbg->hooks->gl.%s(\n\
            %s);\n\
        break;\n" % (functionName, arguments)
            else:
                output += "\
        msg.set_ret(static_cast<int>(dbg->hooks->gl.%s(\n\
            %s)));\n\
        if (cmd.has_ret())\n\
            ret = reinterpret_cast<int *>(msg.ret());\n\
        break;\n" % (functionName, arguments)
    return output

if __name__ == "__main__":

    lines = open("gl2_api_annotated.in").readlines()
    output = generate_caller(lines)
    
    out = open("src/caller.cpp", "w")
    out.write("""\
/*
 ** Copyright 2011, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

// auto generated by generate_caller_cpp.py
// implement declarations in caller.h

#include "header.h"

namespace android {

""")

    for extern in externs:
        out.write("\
static const int * GenerateCall_%s(DbgContext * const dbg,\n\
    const glesv2debugger::Message & cmd, glesv2debugger::Message & msg, const int * const prevRet);\n" % (extern))
        print("\
static const int * GenerateCall_%s(DbgContext * const dbg,\n\
                            const glesv2debugger::Message & cmd,\n\
                            glesv2debugger::Message & msg, const int * const prevRet)\n\
{ assert(0); return prevRet; }\n" % (extern))
                     
    out.write(
"""
#include "caller.h"

const int * GenerateCall(DbgContext * const dbg, const glesv2debugger::Message & cmd,
                  glesv2debugger::Message & msg, const int * const prevRet)
{
    LOGD("GenerateCall function=%u", cmd.function());
    const int * ret = prevRet; // only some functions have return value
    nsecs_t c0 = systemTime(timeMode);
    switch (cmd.function()) {""")
    
    out.write(output)
    
    out.write("""\
    default:
        assert(0);
    }
    msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
    msg.set_context_id(reinterpret_cast<int>(dbg));
    msg.set_function(cmd.function());
    msg.set_type(glesv2debugger::Message_Type_AfterCall);
    return ret;
}

}; // name space android {
""")           
    
            
