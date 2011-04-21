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

def RemoveAnnotation(line):
    if line.find(":") >= 0:
        annotation = line[line.find(":"): line.find(" ", line.find(":"))]
        return line.replace(annotation, "*")
    else:
        return line

def generate_api(lines):
    externs = []
    i = 0
    # these have been hand written
    skipFunctions = ["glDrawArrays", "glDrawElements"]

    # these have an EXTEND_Debug_* macro for getting data
    extendFunctions = ["glCopyTexImage2D", "glCopyTexSubImage2D", "glReadPixels",
"glShaderSource", "glTexImage2D", "glTexSubImage2D"]

    # these also needs to be forwarded to DbgContext
    contextFunctions = ["glUseProgram", "glEnableVertexAttribArray", "glDisableVertexAttribArray",
"glVertexAttribPointer", "glBindBuffer", "glBufferData", "glBufferSubData", "glDeleteBuffers",]

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
                sys.stderr.write("!\n! skipping function '%s'\n!\n" % (functionName))
                continue

            parameters = parameterList.split(',')
            paramIndex = 0
            if line.find("*") >= 0 and (line.find("*") < line.find(":") or line.find("*") > line.rfind(":")): # unannotated pointer
                if not functionName in extendFunctions:
                    # add function to list of functions that should be hand written, but generate code anyways
                    extern = "%s Debug_%s(%s);" % (returnType, functionName, RemoveAnnotation(parameterList))
                    sys.stderr.write("%s should be hand written\n" % (extern))
                    print "// FIXME: this function has pointers, it should be hand written"
                    externs.append(extern)

            print "%s Debug_%s(%s)\n{" % (returnType, functionName, RemoveAnnotation(parameterList))
            print "    glesv2debugger::Message msg;"

            if parameterList == "void":
                parameters = []
            arguments = ""
            paramNames = []
            inout = ""
            getData = ""

            callerMembers = ""
            setCallerMembers = ""
            setMsgParameters = ""

            for parameter in parameters:
                const = parameter.find("const")
                parameter = parameter.replace("const", "")
                parameter = parameter.strip()
                paramType = parameter.split(' ')[0]
                paramName = parameter.split(' ')[1]
                annotation = ""
                arguments += paramName
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

                    setMsgParameters += "    msg.set_arg%d(ToInt(%s));\n" % (paramIndex, paramName)
                    if paramType.find("void") >= 0:
                        getData += "    msg.mutable_data()->assign(reinterpret_cast<const char *>(%s), %s * sizeof(char));" % (paramName, annotation)
                    else:
                        getData += "    msg.mutable_data()->assign(reinterpret_cast<const char *>(%s), %s * sizeof(%s));" % (paramName, annotation, paramType)
                    paramType += "*"
                else:
                    if paramType == "GLfloat" or paramType == "GLclampf" or paramType.find("*") >= 0:
                        setMsgParameters += "    msg.set_arg%d(ToInt(%s));\n" % (paramIndex, paramName)
                    else:
                        setMsgParameters += "    msg.set_arg%d(%s);\n" % (paramIndex, paramName)
                if paramIndex < len(parameters) - 1:
                        arguments += ', '
                if const >= 0:
                    paramType = "const " + paramType
                paramNames.append(paramName)
                paramIndex += 1
                callerMembers += "        %s %s;\n" % (paramType, paramName)
                setCallerMembers += "    caller.%s = %s;\n" % (paramName, paramName)

            print "    struct : public FunctionCall {"
            print callerMembers
            print "        const int * operator()(gl_hooks_t::gl_t const * const _c, glesv2debugger::Message & msg) {"
            if inout in ["out", "inout"]: # get timing excluding output data copy
                print "            nsecs_t c0 = systemTime(timeMode);"
            if returnType == "void":
                print "            _c->%s(%s);" % (functionName, arguments)
            else:
                print "            const int * ret = reinterpret_cast<const int *>(_c->%s(%s));" % (functionName, arguments)
                print "            msg.set_ret(ToInt(ret));"
            if inout in ["out", "inout"]:
                print "            msg.set_time((systemTime(timeMode) - c0) * 1e-6f);"
                print "        " + getData
            if functionName in extendFunctions:
                print "\
#ifdef EXTEND_AFTER_CALL_Debug_%s\n\
            EXTEND_AFTER_CALL_Debug_%s;\n\
#endif" % (functionName, functionName)
            if functionName in contextFunctions:
                print "            getDbgContextThreadSpecific()->%s(%s);" % (functionName, arguments)
            if returnType == "void":
                print "            return 0;"
            else:
                print "            return ret;"
            print """        }
    } caller;"""
            print setCallerMembers
            print setMsgParameters

            if line.find("*") >= 0 or line.find(":") >= 0:
                print "    // FIXME: check for pointer usage"
            if inout in ["in", "inout"]:
                print getData
            if functionName in extendFunctions:
                print "\
#ifdef EXTEND_Debug_%s\n\
    EXTEND_Debug_%s;\n\
#endif" % (functionName, functionName)
            print "    int * ret = MessageLoop(caller, msg, glesv2debugger::Message_Function_%s);"\
                % (functionName)
            if returnType != "void":
                if returnType == "GLboolean":
                    print "    return static_cast<GLboolean>(reinterpret_cast<int>(ret));"
                else:
                    print "    return reinterpret_cast<%s>(ret);" % (returnType)
            print "}\n"


    print "// FIXME: the following functions should be written by hand"
    for extern in externs:
        print extern

if __name__ == "__main__":
    print """\
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

// auto generated by generate_api_cpp.py

#include <utils/Debug.h>

#include "src/header.h"
#include "src/api.h"

template<typename T> static int ToInt(const T & t)
{
    COMPILE_TIME_ASSERT_FUNCTION_SCOPE(sizeof(T) == sizeof(int));
    return (int &)t;
}
"""
    lines = open("gl2_api_annotated.in").readlines()
    generate_api(lines)
    #lines = open("gl2ext_api.in").readlines()
    #generate_api(lines)


