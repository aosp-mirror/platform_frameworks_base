#!/usr/bin/python

"""Run layout tests using Android emulator and instrumentation.

  First, you need to get an SD card or sdcard image that has layout tests on it.
  Layout tests are in following directory:
    /sdcard/android/layout_tests
  For example, /sdcard/android/layout_tests/fast

  Usage:
    Run all tests under fast/ directory:
      run_layout_tests.py, or
      run_layout_tests.py fast

    Run all tests under a sub directory:
      run_layout_tests.py fast/dom

    Run a single test:
      run_layout_tests.py fast/dom/

  After a merge, if there are changes of layout tests in SD card, you need to
  use --refresh-test-list option *once* to re-generate test list on the card.

  Some other options are:
    --time-out-ms (default is 8000 millis) for each test
    --adb-options="-e" passes option string to adb
    --results-directory=..., (default is ./layout-test-results) directory name under which results are stored.
"""

import logging
import optparse
import os
import subprocess
import sys
import time

def CountLineNumber(filename):
  """Compute the number of lines in a given file.

  Args:
    filename: a file name related to the current directory.
  """

  fp = open(os.path.abspath(filename), "r");
  lines = 0
  for line in fp.readlines():
    lines = lines + 1
  fp.close()
  return lines

def main(options, args):
  """Run the tests. Will call sys.exit when complete.
  
  Args:
    options: a dictionary of command line options
    args: a list of sub directories or files to test
  """

  # Set up logging format.
  log_level = logging.INFO
  if options.verbose:
    log_level = logging.DEBUG
  logging.basicConfig(level=log_level,
                      format='%(message)s')

  # Include all tests if none are specified.
  if not args:
    path = 'fast';
  else:
    path = ' '.join(args);

  adb_cmd = "adb ";
  if options.adb_options:
    adb_cmd += options.adb_options

  # Re-generate the test list if --refresh-test-list is on
  if options.refresh_test_list:
    logging.info("Generating test list.");
    shell_cmd_str = adb_cmd + " shell am instrument -e class com.android.dumprendertree.LayoutTestsAutoTest#generateTestList -e path fast -w com.android.dumprendertree/.LayoutTestsAutoRunner"
    adb_output = subprocess.Popen(shell_cmd_str, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()[0]

    if adb_output.find('Process crashed') != -1:
       logging.info("Aborting because cannot generate test list.\n" + adb_output)
       sys.exit(1)


  logging.info("Starting tests")

  # Count crashed tests.
  crashed_tests = []

  timeout_ms = '8000'
  if options.time_out_ms:
    timeout_ms = options.time_out_ms

  # Run test until it's done

  # Call LayoutTestsAutoTest::startLayoutTests.
  shell_cmd_str = adb_cmd + " shell am instrument -e class com.android.dumprendertree.LayoutTestsAutoTest#startLayoutTests -e path \"" + path + "\" -e timeout " + timeout_ms + " -w com.android.dumprendertree/.LayoutTestsAutoRunner"
  adb_output = subprocess.Popen(shell_cmd_str, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()[0]
  while adb_output.find('Process crashed') != -1:
    # Get the running_test.txt
    logging.error("DumpRenderTree crashed, output:\n" + adb_output)

    shell_cmd_str = adb_cmd + " shell cat /sdcard/running_test.txt"
    crashed_test = subprocess.Popen(shell_cmd_str, shell=True, stdout=subprocess.PIPE).communicate()[0]
    
    logging.info(crashed_test + " CRASHED");
    crashed_tests.append(crashed_test);

    logging.info("Resuming layout test runner...");
    # Call LayoutTestsAutoTest::resumeLayoutTests
    shell_cmd_str = adb_cmd + " shell am instrument -e class com.android.dumprendertree.LayoutTestsAutoTest#resumeLayoutTests -e path \"" + path + "\" -e timeout " + timeout_ms + " -w com.android.dumprendertree/.LayoutTestsAutoRunner"

    adb_output = subprocess.Popen(shell_cmd_str, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()[0]

  if adb_output.find('INSTRUMENTATION_FAILED') != -1:
    logging.error("Error happened : " + adb_output)
    sys.exit(1)

  logging.info("Done");
  logging.debug(adb_output);

  # Pull results from /sdcard
  results_dir = options.results_directory
  if not os.path.exists(results_dir):
    os.makedirs(results_dir)
  if not os.path.isdir(results_dir):
    logging.error("Cannot create results dir: " + results_dir);
    sys.exit(1);

  result_files = ["/sdcard/layout_tests_passed.txt",
                  "/sdcard/layout_tests_failed.txt",
                  "/sdcard/layout_tests_nontext.txt"]
  for file in result_files: 
    shell_cmd_str = adb_cmd + " pull " + file + " " + results_dir
    adb_output = subprocess.Popen(shell_cmd_str, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()[0]
    logging.debug(adb_output)
    
  # Create the crash list.
  fp = open(results_dir + "/layout_tests_crashed.txt", "w");
  fp.writelines(crashed_tests)
  fp.close()

  # Count the number of tests in each category.
  passed_tests = CountLineNumber(results_dir + "/layout_tests_passed.txt")
  logging.info(str(passed_tests) + " passed")
  failed_tests = CountLineNumber(results_dir + "/layout_tests_failed.txt")
  logging.info(str(failed_tests) + " failed")
  crashed_tests = CountLineNumber(results_dir + "/layout_tests_crashed.txt")
  logging.info(str(crashed_tests) + " crashed")
  nontext_tests = CountLineNumber(results_dir + "/layout_tests_nontext.txt")
  logging.info(str(nontext_tests) + " no dumpAsText")

  logging.info("Results are stored under: " + results_dir)


if '__main__' == __name__:
  option_parser = optparse.OptionParser()
  option_parser.add_option("", "--time-out-ms",
                           default=None,
                           help="set the timeout for each test")
  option_parser.add_option("", "--verbose", action="store_true",
                           default=False,
                           help="include debug-level logging")
  option_parser.add_option("", "--refresh-test-list", action="store_true",
                           default=False,
                           help="re-generate test list, it may take some time.")
  option_parser.add_option("", "--adb-options",
                           default=None,
                           help="pass options to adb, such as -d -e, etc");
  option_parser.add_option("", "--results-directory",
                           default="layout-test-results",
                           help="directory name under which results are stored.")
  options, args = option_parser.parse_args();
  main(options, args)
