#!/usr/bin/python2.4

"""Run reliability tests using Android instrumentation.

  A test file consists of list web sites to test is needed as a parameter

  Usage:
    run_reliability_tests.py path/to/url/list
"""

import logging
import optparse
import os
import subprocess
import sys
import time
from Numeric import *

TEST_LIST_FILE = "/sdcard/android/reliability_tests_list.txt"
TEST_STATUS_FILE = "/sdcard/android/reliability_running_test.txt"
TEST_TIMEOUT_FILE = "/sdcard/android/reliability_timeout_test.txt"
TEST_LOAD_TIME_FILE = "/sdcard/android/reliability_load_time.txt"
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


def RemoveDeviceFile(adb_cmd, file_name):
  shell_cmd_str = adb_cmd + " shell rm " + file_name
  subprocess.Popen(shell_cmd_str,
                   shell=True, stdout=subprocess.PIPE,
                   stderr=subprocess.PIPE).communicate()


def Bugreport(url, bugreport_dir, adb_cmd):
  """Pull a bugreport from the device."""
  bugreport_filename = "%s/reliability_bugreport_%d.txt" % (bugreport_dir,
                                                            int(time.time()))

  # prepend the report with url
  handle = open(bugreport_filename, "w")
  handle.writelines("Bugreport for crash in url - %s\n\n" % url)
  handle.close()

  cmd = "%s bugreport >> %s" % (adb_cmd, bugreport_filename)
  os.system(cmd)


def ProcessPageLoadTime(raw_log):
  """Processes the raw page load time logged by test app."""
  log_handle = open(raw_log, "r")
  load_times = {}

  for line in log_handle:
    line = line.strip()
    pair = line.split("|")
    if len(pair) != 2:
      logging.info("Line has more than one '|': " + line)
      continue
    if pair[0] not in load_times:
      load_times[pair[0]] = []
    try:
      pair[1] = int(pair[1])
    except ValueError:
      logging.info("Lins has non-numeric load time: " + line)
      continue
    load_times[pair[0]].append(pair[1])

  log_handle.close()

  # rewrite the average time to file
  log_handle = open(raw_log, "w")
  for url, times in load_times.iteritems():
    # calculate std
    arr = array(times)
    avg = average(arr)
    d = arr - avg
    std = sqrt(sum(d * d) / len(arr))
    output = ("%-70s%-10d%-10d%-12.2f%-12.2f%s\n" %
              (url, min(arr), max(arr), avg, std,
               array2string(arr)))
    log_handle.write(output)
  log_handle.close()


def main(options, args):
  """Send the url list to device and start testing, restart if crashed."""

  # Set up logging format.
  log_level = logging.INFO
  if options.verbose:
    log_level = logging.DEBUG
  logging.basicConfig(level=log_level,
                      format="%(message)s")

  # Include all tests if none are specified.
  if not args:
    print "Missing URL list file"
    sys.exit(1)
  else:
    path = args[0]

  if not options.crash_file:
    print "Missing crash file name, use --crash-file to specify"
    sys.exit(1)
  else:
    crashed_file = options.crash_file

  if not options.timeout_file:
    print "Missing timeout file, use --timeout-file to specify"
    sys.exit(1)
  else:
    timedout_file = options.timeout_file

  if not options.delay:
    manual_delay = 0
  else:
    manual_delay = options.delay

  if not options.bugreport:
    bugreport_dir = "."
  else:
    bugreport_dir = options.bugreport
  if not os.path.exists(bugreport_dir):
    os.makedirs(bugreport_dir)
  if not os.path.isdir(bugreport_dir):
    logging.error("Cannot create results dir: " + bugreport_dir)
    sys.exit(1)

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

  # clean up previous results
  RemoveDeviceFile(adb_cmd, TEST_STATUS_FILE)
  RemoveDeviceFile(adb_cmd, TEST_TIMEOUT_FILE)
  RemoveDeviceFile(adb_cmd, TEST_LOAD_TIME_FILE)

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
              "com.android.dumprendertree.ReliabilityTest#"
              "runReliabilityTest -e timeout %s -e delay %s" %
              (str(timeout_ms), str(manual_delay)))

  if options.logtime:
    test_cmd += " -e logtime true"

  test_cmd += test_cmd_postfix

  adb_output = subprocess.Popen(test_cmd, shell=True,
                                stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE).communicate()[0]
  while not DumpRenderTreeFinished(adb_cmd):
    logging.error("DumpRenderTree exited before all URLs are visited.")
    shell_cmd_str = adb_cmd + " shell cat " + TEST_STATUS_FILE
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

    logging.info(crashed_test + " CRASHED")
    crashed_tests.append(crashed_test)
    Bugreport(crashed_test, bugreport_dir, adb_cmd)
    logging.info("Resuming reliability test runner...")

    adb_output = subprocess.Popen(test_cmd, shell=True, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE).communicate()[0]

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

  # get timeout file from sdcard
  test_cmd = (adb_cmd + "pull \"" + TEST_TIMEOUT_FILE + "\" \""
              + timedout_file +  "\"")
  subprocess.Popen(test_cmd, shell=True, stdout=subprocess.PIPE,
                   stderr=subprocess.PIPE).communicate()

  if options.logtime:
    # get logged page load times from sdcard
    test_cmd = (adb_cmd + "pull \"" + TEST_LOAD_TIME_FILE + "\" \""
                + options.logtime +  "\"")
    subprocess.Popen(test_cmd, shell=True, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE).communicate()
    ProcessPageLoadTime(options.logtime)


if "__main__" == __name__:
  option_parser = optparse.OptionParser()
  option_parser.add_option("-t", "--time-out-ms",
                           default=60000,
                           help="set the timeout for each test")
  option_parser.add_option("-v", "--verbose", action="store_true",
                           default=False,
                           help="include debug-level logging")
  option_parser.add_option("-a", "--adb-options",
                           default=None,
                           help="pass options to adb, such as -d -e, etc")
  option_parser.add_option("-c", "--crash-file",
                           default="reliability_crashed_sites.txt",
                           help="the list of sites that cause browser to crash")
  option_parser.add_option("-f", "--timeout-file",
                           default="reliability_timedout_sites.txt",
                           help="the list of sites that timedout during test")
  option_parser.add_option("-d", "--delay",
                           default=0,
                           help="add a manual delay between pages (in ms)")
  option_parser.add_option("-b", "--bugreport",
                           default=".",
                           help="the directory to store bugreport for crashes")
  option_parser.add_option("-l", "--logtime",
                           default=None,
                           help="Logs page load time for each url to the file")
  opts, arguments = option_parser.parse_args()
  main(opts, arguments)
