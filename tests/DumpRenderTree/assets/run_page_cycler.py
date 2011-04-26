#!/usr/bin/python

"""Run page cycler tests using Android instrumentation.

  First, you need to get an SD card or sdcard image that has page cycler tests.

  Usage:
    Run a single page cycler test:
      run_page_cycler.py "file:///sdcard/webkit/page_cycler/moz/start.html\?auto=1\&iterations=10"
"""

import logging
import optparse
import os
import subprocess
import sys
import time



def main(options, args):
  """Run the tests. Will call sys.exit when complete.

  """

  # Set up logging format.
  log_level = logging.INFO
  if options.verbose:
    log_level = logging.DEBUG
  logging.basicConfig(level=log_level,
                      format='%(message)s')

  # Include all tests if none are specified.
  if not args:
    print "need a URL, e.g. file:///sdcard/webkit/page_cycler/moz/start.html\?auto=1\&iterations=10"
    print "  or remote:android-browser-test:80/page_cycler/"
    sys.exit(1)
  else:
    path = ' '.join(args);

  if path[:7] == "remote:":
    remote_path = path[7:]
  else:
    remote_path = None

  adb_cmd = "adb ";
  if options.adb_options:
    adb_cmd += options.adb_options

  logging.info("Running the test ...")

  # Count crashed tests.
  crashed_tests = []

  timeout_ms = '0'
  if options.time_out_ms:
    timeout_ms = options.time_out_ms

  # Run test until it's done

  run_load_test_cmd_prefix = adb_cmd + " shell am instrument"
  run_load_test_cmd_postfix = " -w com.android.dumprendertree/.LayoutTestsAutoRunner"

  # Call LoadTestsAutoTest::runTest.
  run_load_test_cmd = run_load_test_cmd_prefix + " -e class com.android.dumprendertree.LoadTestsAutoTest#runPageCyclerTest -e timeout " + timeout_ms

  if remote_path:
    if options.suite:
      run_load_test_cmd += " -e suite %s -e forward %s " % (options.suite,
                                                            remote_path)
    else:
      print "for network mode, need to specify --suite as well."
      sys.exit(1)
    if options.iteration:
      run_load_test_cmd += " -e iteration %s" % options.iteration
  else:
    run_load_test_cmd += " -e path \"%s\" " % path


  if options.drawtime:
    run_load_test_cmd += " -e drawtime true "

  if options.save_image:
    run_load_test_cmd += " -e saveimage \"%s\"" % options.save_image

  run_load_test_cmd += run_load_test_cmd_postfix

  (adb_output, adb_error) = subprocess.Popen(run_load_test_cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()
  fail_flag = False
  for line in adb_output.splitlines():
    line = line.strip()
    if line.find('INSTRUMENTATION_CODE') == 0:
      if not line[22:] == '-1':
        fail_flag = True
        break
    if (line.find('INSTRUMENTATION_FAILED') != -1 or
        line.find('Process crashed.') != -1):
      fail_flag = True
      break
  if fail_flag:
    logging.error("Error happened : " + adb_output)
    sys.exit(1)

  logging.info(adb_output);
  logging.info(adb_error);
  logging.info("Done\n");

  # Pull results from /sdcard/load_test_result.txt
  results_dir = options.results_directory
  if not os.path.exists(results_dir):
    os.makedirs(results_dir)
  if not os.path.isdir(results_dir):
    logging.error("Cannot create results dir: " + results_dir)
    sys.exit(1)

  result_file = "/sdcard/load_test_result.txt"
  shell_cmd_str = adb_cmd + " pull " + result_file + " " + results_dir
  (adb_output, err) = subprocess.Popen(
      shell_cmd_str, shell=True,
      stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()
  if not os.path.isfile(os.path.join(results_dir, "load_test_result.txt")):
    logging.error("Failed to pull result file.")
    logging.error("adb stdout:")
    logging.error(adb_output)
    logging.error("adb stderr:")
    logging.error(err)
  logging.info("Results are stored under: " + results_dir + "/load_test_result.txt\n")

if '__main__' == __name__:
  option_parser = optparse.OptionParser()
  option_parser.add_option("-t", "--time-out-ms",
                           default=None,
                           help="set the timeout for each test")
  option_parser.add_option("-v", "--verbose", action="store_true",
                           default=False,
                           help="include debug-level logging")
  option_parser.add_option("-a", "--adb-options",
                           default=None,
                           help="pass options to adb, such as -d -e, etc");
  option_parser.add_option("-r", "--results-directory",
                           default="layout-test-results",
                           help="directory which results are stored.")

  option_parser.add_option("-d", "--drawtime", action="store_true",
                           default=False,
                           help="log draw time for each page rendered.")

  option_parser.add_option("-s", "--save-image",
                           default=None,
                           help="stores rendered page to a location on device.")

  option_parser.add_option("-u", "--suite",
                           default=None,
                           help="(for network mode) specify the suite to"
                           " run by name")

  option_parser.add_option("-i", "--iteration",
                           default="5",
                           help="(for network mode) specify how many iterations"
                           " to run")

  options, args = option_parser.parse_args();
  main(options, args)
