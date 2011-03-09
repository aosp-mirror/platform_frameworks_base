#!/usr/bin/python
# -*- coding: utf-8 -*-

import os
import sys

def append_functions(functions, lines):
	i = 0
	for line in lines:
		if line.find("API_ENTRY(") >= 0: # a function prototype
			returnType = line[0: line.find(" API_ENTRY(")]
			functionName = line[line.find("(") + 1: line.find(")")] #extract GL function name
			parameterList = line[line.find(")(") + 2: line.find(") {")]
			
			functions.append(functionName)
			#print functionName
			continue
				
			parameters = parameterList.split(',')
			paramIndex = 0
			if line.find("*") >= 0:
				print "// FIXME: this function has pointers, it should be hand written"
				externs.append("%s Tracing_%s(%s);" % (returnType, functionName, parameterList))
			print "%s Tracing_%s(%s)\n{" % (returnType, functionName, parameterList)
			
			if parameterList == "void":
				parameters = []
			
			arguments = ""
			 
			for parameter in parameters:
				parameter = parameter.replace("const", "")
				parameter = parameter.strip()
				paramType = parameter.split(' ')[0]
				paramName = parameter.split(' ')[1]
				
				paramIndex += 1
				
	return functions
	


if __name__ == "__main__":
	definedFunctions = []
	lines = open("gl2_api.in").readlines()
	definedFunctions = append_functions(definedFunctions, lines)
	
	output = open("debug.in", "w")
	lines = open("trace.in").readlines()
	output.write("// the following functions are not defined in GLESv2_dbg\n")
	for line in lines:
		functionName = ""
		if line.find("TRACE_GL(") >= 0: # a function prototype
			functionName = line.split(',')[1].strip()
		elif line.find("TRACE_GL_VOID(") >= 0: # a function prototype
			functionName = line[line.find("(") + 1: line.find(",")] #extract GL function name
		else:
			continue
		if functionName in definedFunctions:
			#print functionName
			continue
		else:
			output.write(line)
	
