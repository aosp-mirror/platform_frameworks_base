#!/usr/bin/python
# -*- coding: utf-8 -*-

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
	skipFunctions = ["glTexImage2D", "glTexSubImage2D", "glShaderSource"]
	for line in lines:
		if line.find("API_ENTRY(") >= 0: # a function prototype
			returnType = line[0: line.find(" API_ENTRY(")]
			functionName = line[line.find("(") + 1: line.find(")")] #extract GL function name
			parameterList = line[line.find(")(") + 2: line.find(") {")]
			
			#if line.find("*") >= 0:
			#	extern = "%s Debug_%s(%s);" % (returnType, functionName, parameterList)
			#	externs.append(extern)
			#	continue
			
			if functionName in skipFunctions:
				sys.stderr.write("!\n! skipping function '%s'\n!\n" % (functionName))
				continue
				
			parameters = parameterList.split(',')
			paramIndex = 0
			if line.find("*") >= 0 and (line.find("*") < line.find(":") or line.find("*") > line.rfind(":")):
				extern = "%s Debug_%s(%s);" % (returnType, functionName, RemoveAnnotation(parameterList))
				sys.stderr.write("%s should be hand written\n" % (extern))
				print "// FIXME: this function has pointers, it should be hand written"
				externs.append(extern)
			print "%s Debug_%s(%s)\n{" % (returnType, functionName, RemoveAnnotation(parameterList))
			
			if returnType != "void":
				print "\t%s ret = 0;" % (returnType)
			print """\tgl_hooks_t::gl_t const * const _c = &getGLTraceThreadSpecific()->gl;
\tGLESv2Debugger::Message msg, cmd;
\tmsg.set_context_id(0);
\tmsg.set_has_next_message(true);
\tconst bool expectResponse = false;
\tmsg.set_expect_response(expectResponse);"""
			print "\tmsg.set_function(GLESv2Debugger::Message_Function_%s);" % (functionName)

			if parameterList == "void":
				parameters = []
			
			arguments = ""
			 
			paramTypes = []
			paramNames = []
			paramAnnotations = []
			inout = ""
			getData = ""
			
			for parameter in parameters:
				parameter = parameter.replace("const", "")
				parameter = parameter.strip()
				paramType = parameter.split(' ')[0]
				paramName = parameter.split(' ')[1]
				annotation = ""
				arguments += paramName
				if parameter.find(":") >= 0:
					assert paramIndex == len(parameters) - 1 # only last parameter should be annotated
					sys.stderr.write("%s is annotated: %s \n" % (functionName, paramType))
					inout = paramType.split(":")[2]
					annotation = paramType.split(":")[1]
					paramType = paramType.split(":")[0]
					assert paramType.find("void") < 0
					count = 1
					countArg = ""
					if annotation.find("*") >= 0:
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
			
					print "\tmsg.set_arg%d(ToInt(%s));" % (paramIndex, paramName)
					print "\tstd::string data;"
					getData += "\t\t\tdata.reserve(%s * sizeof(%s));\n" % (annotation, paramType)
					getData += "\t\t\tfor (unsigned i = 0; i < (%s); i++)\n" % (annotation)
					getData += "\t\t\t\tdata.append((const char *)(%s + i), sizeof(*%s));\n" % (paramName, paramName)
					getData += "\t\t\tmsg.set_data(data);"  
				else:	 
					if paramIndex < len(parameters) - 1:
						arguments += ', '
					if paramType == "GLfloat" or paramType == "GLclampf" or paramType.find("*") >= 0:
						print "\tmsg.set_arg%d(ToInt(%s));" % (paramIndex, paramName)
					else: 
						print "\tmsg.set_arg%d(%s);" % (paramIndex, paramName)
				paramTypes.append(paramType)
				paramNames.append(paramName)
				paramAnnotations.append(annotation)
				paramIndex += 1
			if line.find("*") >= 0 or line.find(":") >= 0:
				print "\t// FIXME: check for pointer usage"
			if inout in ["in", "inout"]:
				print getData
	 		print """\tSend(msg, cmd);
\tif (!expectResponse)
\t\tcmd.set_function(GLESv2Debugger::Message_Function_CONTINUE);
\twhile (true) {
\t\tmsg.Clear();
\t\tclock_t c0 = clock();
\t\tswitch (cmd.function()) {
\t\tcase GLESv2Debugger::Message_Function_CONTINUE:"""
			if returnType == "void":
				print "\t\t\t_c->%s(%s);" % (functionName, arguments)
			
			else:
				print "\t\t\tret = _c->%s(%s);" % (functionName, arguments)
				if returnType == "GLboolean":
					print "\t\t\tmsg.set_ret(ret);"
				else:
					print "\t\t\tmsg.set_ret(ToInt(ret));"
			print "\t\t\tmsg.set_time((float(clock()) - c0) / CLOCKS_PER_SEC);"
			print "\t\t\tmsg.set_context_id(0);"
			print "\t\t\tmsg.set_function(GLESv2Debugger::Message_Function_%s);" % (functionName)
			print """\t\t\tmsg.set_has_next_message(false);
\t\t\tmsg.set_expect_response(expectResponse);"""
			if inout in ["out", "inout"]:
				print getData
			print """\t\t\tSend(msg, cmd);
\t\t\tif (!expectResponse)
\t\t\t\tcmd.set_function(GLESv2Debugger::Message_Function_SKIP);
\t\t\tbreak;
\t\tcase GLESv2Debugger::Message_Function_SKIP:"""
			if returnType == "void":
				print "\t\t\treturn;"
			else:
				print "\t\t\tif (cmd.has_ret())"
				if returnType == "GLboolean":
					print "\t\t\t\tret = cmd.ret();"
				else:
					print "\t\t\t\tret = FromInt<%s>(cmd.ret());" % (returnType)
				print "\t\t\treturn ret;"
			print """\t\tdefault:
\t\t\tASSERT(0); //GenerateCall(msg, cmd); 
\t\t\tbreak;
\t\t}
\t}
}
"""
			#break

	print "// FIXME: the following functions should be written by hand"
	for extern in externs:
		print extern

if __name__ == "__main__":
	print "// auto generated by generate_api_cpp.py\n"
	print '''#include  "src/header.h"\n'''
	print "template<typename T> static int ToInt(const T & t) { STATIC_ASSERT(sizeof(T) == sizeof(int), bitcast); return (int &)t; }\n"
	print "template<typename T> static T FromInt(const int & t) { STATIC_ASSERT(sizeof(T) == sizeof(int), bitcast); return (T &)t; }\n"
	
	lines = open("gl2_api_annotated.in").readlines()
	generate_api(lines)
	#lines = open("gl2ext_api.in").readlines()
	#generate_api(lines)
			

