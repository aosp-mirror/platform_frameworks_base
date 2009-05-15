#!/usr/bin/python2.4

"""Run reliability tests using Android instrumentation.

  A test file consists of list web sites to test is needed as a parameter

  Usage:
    run_reliability_tests.py path/to/url/list
"""

import logging
import optparse
import random
import subprocess
import sys
import time

TEST_LIST_FILE = "/sdcard/android/reliability_tests_list.txt"
TEST_STATUS_FILE = "/sdcard/android/reliability_running_test.txt"
TEST_TIMEOUT_FILE = "/sdcard/android/reliability_timeout_test.txt"
HTTP_URL_FILE = "urllist_http"
HTTPS_URL_FILE = "urllist_https"
NUM_URLS = 25


def DumpRenderTreeFinished(adb_cmd):
  """Check if DumpRenderTree finished running.

  Args:
    adb_cmd: adb command string

  Returns:
    True if DumpRenderTree has finished, False otherwise
  """

  # pull test status file and look for "#DONE"
  shell_cmd_str = adb_cmd + " shell cat " + TEST_STATUS_FILE
  adb_output = subprocess.Popen(shell_cmd_str,
                                shell=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE).communicate()[0]
  return adb_output.strip() == "#DONE"


def RandomPick(file_name, approx_size, num_needed):
  """Randomly pick lines from the text file specifed.

  Args:
    file_name: the text file where lines should be picked from
    approx_size: an approximate size of the text file
    num_needed: how many lines are needed from the file

  Returns:
    an array of string
  """
  p = float(num_needed) / approx_size
  num_picked = 0
  lines = []
  random.seed()

  while num_picked < num_needed:
    file_handle = open(file_name, "r")
    for line in file_handle:
      line = line.strip()
      if float(random.randint(0, approx_size)) / approx_size < p:
        lines.append(line)
        num_picked += 1
        if num_picked == num_needed:
          break
    file_handle.close()
  return lines


def main(options, args):
  """Send the url list to device and start testing, restart if crashed."""

  generate_url = False

  # Set up logging format.
  log_level = logging.INFO
  if options.verbose:
    log_level = logging.DEBUG
  logging.basicConfig(level=log_level,
                      format="%(message)s")

  # Include all tests if none are specified.
  if not args:
    path = "/tmp/url_list_%d.txt" % time.time()
    generate_url = True
    logging.info("A URL list is not provided, will be automatically generated.")
  else:
    path = args[0]

  if not options.crash_file:
    print "missing crash file name, use --crash-file to specify"
    sys.exit(1)
  else:
    crashed_file = options.crash_file

  if not options.timeout_file:
    print "missing timeout file, use --timeout-file to specify"
    sys.exit(1)
  else:
    timedout_file = options.timeout_file

  http = RandomPick(HTTP_URL_FILE, 500000, NUM_URLS)
  https = RandomPick(HTTPS_URL_FILE, 45000, NUM_URLS)

  if generate_url:
    file_handle = open(path, "w")
    for i in range(0, NUM_URLS):
      file_handle.write(http[i] + "\n")
      file_handle.write(https[i] + "\n")
    file_handle.close()

  adb_cmd = "adb "
  if options.adb_options:
    adb_cmd += options.adb_options + " "

  # push url list to device
  test_cmd = adb_cmd + " push \"" + path + "\" \"" + TEST_LIST_FILE + "\""
  proc = subprocess.Popen(test_cmd, shell=True,
                          stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE)
  (adb_output, adb_error) = proc.communicate()
  if proc.returncode != 0:
    logging.error("failed to push url list to device.")
    logging.error(adb_output)
    logging.error(adb_error)
    sys.exit(1)

  logging.info("Running the test ...")

  # Count crashed tests.
  crashed_tests = []

  if options.time_out_ms:
    timeout_ms = options.time_out_ms

  # Run test until it's done
  test_cmd_prefix = adb_cmd + " shell am instrument"
  test_cmd_postfix = " -w com.android.dumprendertree/.LayoutTestsAutoRunner"

  # Call ReliabilityTestsAutoTest#startReliabilityTests
  test_cmd = (test_cmd_prefix + " -e class "
              "com.android.dumprendertree.ReliabilityTestsAutoTest#"
              "startReliabilityTests -e timeout " + timeout_ms
              + test_cmd_postfix)

  time_start = time.time()
  adb_output = subprocess.Popen(test_cmd, shell=True,
                                stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE).communicate()[0]
  while not DumpRenderTreeFinished(adb_cmd):
    logging.error("DumpRenderTree exited before all URLs are visited.")
    shell_cmd_str = adb_cmd + " shell cat " + TEST_STATUS_FILE
    crashed_test = subprocess.Popen(shell_cmd_str, shell=True,
                                    stdout=subprocess.PIPE).communicate()[0]
    logging.info(crashed_test + " CRASHED")
    crashed_tests.append(crashed_test)
    logging.info("Resuming reliability test runner...")

    test_cmd = (test_cmd_prefix + " -e class "
                "com.android.dumprendertree.ReliabilityTestsAutoTest#"
                "resumeReliabilityTests -e timeout " + timeout_ms
                + test_cmd_postfix)
    adb_output = subprocess.Popen(test_cmd, shell=True, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE).communicate()[0]

  time_end = time.time()
  fp = open("time_stat", "a")
  fp.writelines("%.2f\n" % ((time_end - time_start) / NUM_URLS / 2))
  fp.close()
  if (adb_output.find("INSTRUMENTATION_FAILED") != -1 or
      adb_output.find("Process crashed.") != -1):
    logging.error("Error happened : " + adb_output)
    sys.exit(1)

  logging.info(adb_output)
  logging.info("Done\n")

  if crashed_tests:
    file_handle = open(crashed_file, "w")
    file_handle.writelines("\n".join(crashed_tests))
    logging.info("Crashed URL list stored in: " + crashed_file)
    file_handle.close()
  else:
    logging.info("No crash found.")

  test_cmd = (adb_cmd + "pull \"" + TEST_TIMEOUT_FILE + "\" \""
              + timedout_file +  "\"")

  subprocess.Popen(test_cmd, shell=True, stdout=subprocess.PIPE,
                   stderr=subprocess.PIPE).communicate()


if "__main__" == __name__:
  option_parser = optparse.OptionParser()
  option_parser.add_option("", "--time-out-ms",
                           default=60000,
                           help="set the timeout for each test")
  option_parser.add_option("", "--verbose", action="store_true",
                           default=False,
                           help="include debug-level logging")
  option_parser.add_option("", "--adb-options",
                           default=None,
                           help="pass options to adb, such as -d -e, etc")
  option_parser.add_option("", "--crash-file",
                           default="reliability_crashed_sites.txt",
                           help="the list of sites that cause browser to crash")
  option_parser.add_option("", "--timeout-file",
                           default="reliability_timedout_sites.txt",
                           help="the list of sites that timedout during test.")
  opts, arguments = option_parser.parse_args()
  main(opts, arguments)
