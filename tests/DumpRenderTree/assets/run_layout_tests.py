#!/usr/bin/python

"""Run layout tests using Android emulator and instrumentation.

  First, you need to get an SD card or sdcard image that has layout tests on it.
  Layout tests are in following directory:
    /sdcard/webkit/layout_tests
  For example, /sdcard/webkit/layout_tests/fast

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
    --rebaseline generates expected layout tests results under /sdcard/webkit/expected_result/
    --time-out-ms (default is 8000 millis) for each test
    --adb-options="-e" passes option string to adb
    --results-directory=..., (default is ./layout-test-results) directory name under which results are stored.
    --js-engine the JavaScript engine currently in use, determines which set of Android-specific expected results we should use, should be 'jsc' or 'v8'
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

def DumpRenderTreeFinished(adb_cmd):
  """ Check if DumpRenderTree finished running tests

  Args:
    output: adb_cmd string
  """

  # pull /sdcard/webkit/running_test.txt, if the content is "#DONE", it's done
  shell_cmd_str = adb_cmd + " shell cat /sdcard/webkit/running_test.txt"
  adb_output = subprocess.Popen(shell_cmd_str, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()[0]
  return adb_output.strip() == "#DONE"

def DiffResults(marker, new_results, old_results, diff_results, strip_reason,
                new_count_first=True):
   """ Given two result files, generate diff and
       write to diff_results file. All arguments are absolute paths
       to files.
   """
   old_file = open(old_results, "r")
   new_file = open(new_results, "r")
   diff_file = open(diff_results, "a")

   # Read lines from each file
   ndict = new_file.readlines()
   cdict = old_file.readlines()

   # Write marker to diff file
   diff_file.writelines(marker + "\n")
   diff_file.writelines("###############\n")

   # Strip reason from result lines
   if strip_reason is True:
     for i in range(0, len(ndict)):
       ndict[i] = ndict[i].split(' ')[0] + "\n"
     for i in range(0, len(cdict)):
       cdict[i] = cdict[i].split(' ')[0] + "\n"

   params = {
       "new": [0, ndict, cdict, "+"],
       "miss": [0, cdict, ndict, "-"]
       }
   if new_count_first:
     order = ["new", "miss"]
   else:
     order = ["miss", "new"]

   for key in order:
     for line in params[key][1]:
       if line not in params[key][2]:
         if line[-1] != "\n":
           line += "\n";
         diff_file.writelines(params[key][3] + line)
         params[key][0] += 1

   logging.info(marker + "  >>> " + str(params["new"][0]) + " new, " +
                str(params["miss"][0]) + " misses")

   diff_file.writelines("\n\n")

   old_file.close()
   new_file.close()
   diff_file.close()
   return

def CompareResults(ref_dir, results_dir):
  """Compare results in two directories

  Args:
    ref_dir: the reference directory having layout results as references
    results_dir: the results directory
  """
  logging.info("Comparing results to " + ref_dir)

  diff_result = os.path.join(results_dir, "layout_tests_diff.txt")
  if os.path.exists(diff_result):
    os.remove(diff_result)

  files=["crashed", "failed", "passed", "nontext"]
  for f in files:
    result_file_name = "layout_tests_" + f + ".txt"
    DiffResults(f, os.path.join(results_dir, result_file_name),
                os.path.join(ref_dir, result_file_name), diff_result,
                False, f != "passed")
  logging.info("Detailed diffs are in " + diff_result)

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
    path = '/';
  else:
    path = ' '.join(args);

  adb_cmd = "adb ";
  if options.adb_options:
    adb_cmd += options.adb_options

  # Re-generate the test list if --refresh-test-list is on
  if options.refresh_test_list:
    logging.info("Generating test list.");
    generate_test_list_cmd_str = adb_cmd + " shell am instrument -e class com.android.dumprendertree.LayoutTestsAutoTest#generateTestList -e path \"" + path + "\" -w com.android.dumprendertree/.LayoutTestsAutoRunner"
    adb_output = subprocess.Popen(generate_test_list_cmd_str, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()[0]

    if adb_output.find('Process crashed') != -1:
       logging.info("Aborting because cannot generate test list.\n" + adb_output)
       sys.exit(1)


  logging.info("Running tests")

  # Count crashed tests.
  crashed_tests = []

  timeout_ms = '15000'
  if options.time_out_ms:
    timeout_ms = options.time_out_ms

  # Run test until it's done

  run_layout_test_cmd_prefix = adb_cmd + " shell am instrument"

  run_layout_test_cmd_postfix = " -e path \"" + path + "\" -e timeout " + timeout_ms
  if options.rebaseline:
    run_layout_test_cmd_postfix += " -e rebaseline true"

  # If the JS engine is not specified on the command line, try reading the
  # JS_ENGINE environment  variable, which is used by the build system in
  # external/webkit/Android.mk.
  js_engine = options.js_engine
  if not js_engine and os.environ.has_key('JS_ENGINE'):
    js_engine = os.environ['JS_ENGINE']
  if js_engine:
    run_layout_test_cmd_postfix += " -e jsengine " + js_engine

  run_layout_test_cmd_postfix += " -w com.android.dumprendertree/.LayoutTestsAutoRunner"

  # Call LayoutTestsAutoTest::startLayoutTests.
  run_layout_test_cmd = run_layout_test_cmd_prefix + " -e class com.android.dumprendertree.LayoutTestsAutoTest#startLayoutTests" + run_layout_test_cmd_postfix

  adb_output = subprocess.Popen(run_layout_test_cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()[0]
  while not DumpRenderTreeFinished(adb_cmd):
    # Get the running_test.txt
    logging.error("DumpRenderTree crashed, output:\n" + adb_output)

    shell_cmd_str = adb_cmd + " shell cat /sdcard/webkit/running_test.txt"
    crashed_test = ""
    while not crashed_test:
      (crashed_test, err) = subprocess.Popen(
          shell_cmd_str, shell=True, stdout=subprocess.PIPE,
          stderr=subprocess.PIPE).communicate()
      crashed_test = crashed_test.strip()
      if not crashed_test:
        logging.error('Cannot get crashed test name, device offline?')
        logging.error('stderr: ' + err)
        logging.error('retrying in 10s...')
        time.sleep(10)

    logging.info(crashed_test + " CRASHED");
    crashed_tests.append(crashed_test);

    logging.info("Resuming layout test runner...");
    # Call LayoutTestsAutoTest::resumeLayoutTests
    run_layout_test_cmd = run_layout_test_cmd_prefix + " -e class com.android.dumprendertree.LayoutTestsAutoTest#resumeLayoutTests" + run_layout_test_cmd_postfix

    adb_output = subprocess.Popen(run_layout_test_cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()[0]

  if adb_output.find('INSTRUMENTATION_FAILED') != -1:
    logging.error("Error happened : " + adb_output)
    sys.exit(1)

  logging.debug(adb_output);
  logging.info("Done\n");

  # Pull results from /sdcard
  results_dir = options.results_directory
  if not os.path.exists(results_dir):
    os.makedirs(results_dir)
  if not os.path.isdir(results_dir):
    logging.error("Cannot create results dir: " + results_dir);
    sys.exit(1);

  result_files = ["/sdcard/layout_tests_passed.txt",
                  "/sdcard/layout_tests_failed.txt",
                  "/sdcard/layout_tests_ignored.txt",
                  "/sdcard/layout_tests_nontext.txt"]
  for file in result_files:
    shell_cmd_str = adb_cmd + " pull " + file + " " + results_dir
    adb_output = subprocess.Popen(shell_cmd_str, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()[0]
    logging.debug(adb_output)

  # Create the crash list.
  fp = open(results_dir + "/layout_tests_crashed.txt", "w");
  for crashed_test in crashed_tests:
    fp.writelines(crashed_test + '\n')
  fp.close()

  # Count the number of tests in each category.
  passed_tests = CountLineNumber(results_dir + "/layout_tests_passed.txt")
  logging.info(str(passed_tests) + " passed")
  failed_tests = CountLineNumber(results_dir + "/layout_tests_failed.txt")
  logging.info(str(failed_tests) + " failed")
  ignored_tests = CountLineNumber(results_dir + "/layout_tests_ignored.txt")
  logging.info(str(ignored_tests) + " ignored results")
  crashed_tests = CountLineNumber(results_dir + "/layout_tests_crashed.txt")
  logging.info(str(crashed_tests) + " crashed")
  nontext_tests = CountLineNumber(results_dir + "/layout_tests_nontext.txt")
  logging.info(str(nontext_tests) + " no dumpAsText")
  logging.info(str(passed_tests + failed_tests + ignored_tests + crashed_tests + nontext_tests) + " TOTAL")

  logging.info("Results are stored under: " + results_dir + "\n")

  # Comparing results to references to find new fixes and regressions.
  results_dir = os.path.abspath(options.results_directory)
  ref_dir = options.ref_directory

  # if ref_dir is null, cannonify ref_dir to the script dir.
  if not ref_dir:
    script_self = sys.argv[0]
    script_dir = os.path.dirname(script_self)
    ref_dir = os.path.join(script_dir, "results")

  ref_dir = os.path.abspath(ref_dir)

  CompareResults(ref_dir, results_dir)

if '__main__' == __name__:
  option_parser = optparse.OptionParser()
  option_parser.add_option("", "--rebaseline", action="store_true",
                           default=False,
                           help="generate expected results for those tests not having one")
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
                           help="directory which results are stored.")
  option_parser.add_option("", "--ref-directory",
                           default=None,
                           dest="ref_directory",
                           help="directory where reference results are stored.")
  option_parser.add_option("", "--js-engine",
                           default=None,
                           help="The JavaScript engine currently in use, which determines which set of Android-specific expected results we should use. Should be 'jsc' or 'v8'.");

  options, args = option_parser.parse_args();
  main(options, args)
