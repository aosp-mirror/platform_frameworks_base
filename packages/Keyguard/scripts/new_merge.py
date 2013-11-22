#!/usr/bin/env python

import os
import sys
import difflib
import filecmp
import tempfile
from optparse import OptionParser
from subprocess import call
from subprocess import Popen
from subprocess import PIPE

def which(program):
    def executable(path):
        return os.path.isfile(path) and os.access(path, os.X_OK)

    path, file = os.path.split(program)
    if path and executable(program):
		return program
    else:
        for path in os.environ["PATH"].split(os.pathsep):
            exe = os.path.join(path, program)
            if executable(exe):
                return exe
    return ""

DIFF_TOOLS=["meld", "kdiff3", "xdiff", "diffmerge.sh", "diff"]

PROTO_SRC="./src/com/android/keyguard/"
PROTO_RES="./res/"

TEMP_FILE1="/tmp/tempFile1.txt"
TEMP_FILE2="/tmp/tempFile2.txt"

FW_SRC="../../../../frameworks/base/policy/src/com/android/internal/policy/impl/keyguard/"
FW_RES="../../../../frameworks/base/core/res/res/"

FW_PKG="com.android.internal.policy.impl.keyguard"
PROTO_PKG="com.android.keyguard"

FW_RES_IMPORT="import com.android.internal.R;"

# Find a differ
DIFF_TOOL=""
if ("DIFF_TOOL" in os.environ and len(os.environ["DIFF_TOOL"]) > 0):
	DIFF_TOOL=which(os.environ["DIFF_TOOL"])
if len(DIFF_TOOL) == 0:
	for differ in DIFF_TOOLS:
		DIFF_TOOL=which(differ)
		if len(DIFF_TOOL) > 0:
			break

print "Using differ", DIFF_TOOL

#Anything file which contains any string in this list as a substring will be ommitted
IGNORE=["LockHotnessActivity.java", "unified_lock_activity.xml", "optionmenu.xml"]
WATCH=[]

def dirCompare(sourceDir, destDir, ext, run_in_reverse):
	sourceFiles = getFileList(sourceDir, ext)
	destFiles = getFileList(destDir, ext)
	for file in sourceFiles:
		print file
		destFile = destDir + file
		sourceFile = sourceDir + file
		if (file in destFiles):
			if run_in_reverse:
				prepareFileForCompare(sourceFile, TEMP_FILE1, FW_RES_IMPORT, FW_PKG, PROTO_PKG)
				prepareFileForCompare(destFile, TEMP_FILE2, FW_RES_IMPORT,)
			else:
				prepareFileForCompare(destFile, TEMP_FILE1, FW_RES_IMPORT, FW_PKG, PROTO_PKG)
				prepareFileForCompare(sourceFile, TEMP_FILE2, FW_RES_IMPORT,)
			if (filecmp.cmp(TEMP_FILE1, TEMP_FILE2)):
				print "File %s is the same in proto and framework" %(file)
			else:
				print "Running diff for: %s" %(file)
				diff(sourceFile, destFile)
		else:
			print "File %s does not exist in framework" %(file)
			if not run_in_reverse:
				diff(sourceFile, destFile)

def main(argv):
	run_in_reverse = False
	if len(argv) > 1:
		if argv[1] == '--help' or argv[1] == '-h':
			print ('Usage: %s [<commit>]' % argv[0])
			print ('\tdiff to framework, ' +
					'optionally restricting to files in <commit>')
			sys.exit(0)
		elif argv[1] == '--reverse':
			print "Running in reverse"
			run_in_reverse = True
		else:
			print ("**** Pulling file list from: %s" % argv[1])
			pipe = Popen(['git', 'diff', '--name-only',  argv[1]], stdout=PIPE).stdout
			for line in iter(pipe.readline,''):
				path = line.rstrip()
				file = path[path.rfind('/') + 1:]
				print '**** watching: %s' % file
				WATCH.append(file);
			pipe.close()

	if run_in_reverse:
		#dirCompare(FW_RES, PROTO_RES, ".xml", run_in_reverse)
		print ("**** Source files:")
		dirCompare(FW_SRC, PROTO_SRC, ".java", run_in_reverse)
	else:
		#dirCompare(PROTO_RES, FW_RES, ".xml", run_in_reverse)
		print ("**** Source files:")
		dirCompare(PROTO_SRC, FW_SRC, ".java", run_in_reverse)

	if (os.path.exists(TEMP_FILE1)):
		os.remove(TEMP_FILE1)

	if (os.path.exists(TEMP_FILE2)):
		os.remove(TEMP_FILE2)

def getFileList(rootdir, extension):
	fileList = []

	for root, subFolders, files in os.walk(rootdir):
	    for file in files:
	        f = os.path.join(root,file)
	        if (os.path.splitext(f)[1] == extension and (not inIgnore(f))):
	        	fileList.append(f[len(rootdir):])
	return fileList


def prepareFileForCompare(inFile, outFile, skip="", replace="", withText=""):
	# Delete the outfile, so we're starting with a new file
	if (os.path.exists(outFile)):
		os.remove(outFile)

	fin = open(inFile)
	fout = open(outFile, "w")
	for line in fin:
		# Ignore any lines containing the ignore string ("import com.android.internal.R;) and
		# ignore any lines containing only whitespace.
		if (line.find(skip) < 0  and len(line.strip(' \t\n\r')) > 0):
			# For comparison, for framework files, we replace the fw package with the
			# proto package, since these aren't relevant.
			if len(replace) > 0:
				fout.write(line.replace(replace, withText))
			else:
				fout.write(line)
	fin.close()
	fout.close()

def diff(file1, file2):
	call([DIFF_TOOL, file1, file2])

def inIgnore(file):
	for ignore in IGNORE:
		if file.find(ignore) >= 0:
			return True
        if len(WATCH) > 0:
            for watch in WATCH:
		if file.find(watch) >= 0:
                    return False
            return True
	return False

if __name__=="__main__":
  main(sys.argv)
