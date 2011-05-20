#!/usr/bin/python

"""Run layout tests on the device.

  It runs the specified tests on the device, downloads the summaries to the temporary directory
  and optionally shows the detailed results the host's default browser.

  Usage:
    run_layout_tests.py --show-results-in-browser test-relative-path
"""

import logging
import optparse
import os
import re
import sys
import subprocess
import tempfile
import webbrowser

import run_apache2

#TODO: These should not be hardcoded
RESULTS_ABSOLUTE_PATH = "/sdcard/layout-test-results/"
DETAILS_HTML = "details.html"
SUMMARY_TXT = "summary.txt"

def main(path, options):
  tmpdir = tempfile.gettempdir()

  # Restart the server
  if run_apache2.main("restart", options) == False:
    return

  # Run the tests in path
  adb_cmd = "adb"
  if options.serial:
    adb_cmd += " -s " + options.serial
  cmd = adb_cmd + " shell am instrument "
  cmd += "-e class com.android.dumprendertree2.scriptsupport.Starter#startLayoutTests "
  cmd += "-e path \"" + path + "\" "
  cmd += "-w com.android.dumprendertree2/com.android.dumprendertree2.scriptsupport.ScriptTestRunner"

  logging.info("Running the tests...")
  logging.debug("Command = %s" % cmd)
  (stdoutdata, stderrdata) = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()
  if stderrdata != "":
    logging.info("Failed to start tests:\n%s", stderrdata)
    return
  if re.search("^INSTRUMENTATION_STATUS_CODE: -1", stdoutdata, re.MULTILINE) != None:
    logging.info("Failed to run the tests. Is DumpRenderTree2 installed on the device?")
    return
  if re.search("^OK \([0-9]+ tests?\)", stdoutdata, re.MULTILINE) == None:
    logging.info("DumpRenderTree2 failed to run correctly:\n%s", stdoutdata)
    return

  logging.info("Downloading the summaries...")

  # Download the txt summary to tmp folder
  summary_txt_tmp_path = os.path.join(tmpdir, SUMMARY_TXT)
  cmd = "rm -f " + summary_txt_tmp_path + ";"
  cmd += adb_cmd + " pull " + RESULTS_ABSOLUTE_PATH + SUMMARY_TXT + " " + summary_txt_tmp_path
  subprocess.Popen(cmd, shell=True).wait()

  # Download the html summary to tmp folder
  details_html_tmp_path = os.path.join(tmpdir, DETAILS_HTML)
  cmd = "rm -f " + details_html_tmp_path + ";"
  cmd += adb_cmd + " pull " + RESULTS_ABSOLUTE_PATH + DETAILS_HTML + " " + details_html_tmp_path
  subprocess.Popen(cmd, shell=True).wait()

  # Print summary to console
  logging.info("All done.\n")
  cmd = "cat " + summary_txt_tmp_path
  os.system(cmd)
  logging.info("")

  # Open the browser with summary
  if options.show_results_in_browser != "false":
    webbrowser.open(details_html_tmp_path)

if __name__ == "__main__":
  option_parser = optparse.OptionParser(usage="Usage: %prog [options] test-relative-path")
  option_parser.add_option("", "--show-results-in-browser", default="true",
                           help="Show the results the host's default web browser, default=true")
  option_parser.add_option("", "--tests-root-directory",
                           help="The directory from which to take the tests, default is external/webkit/LayoutTests in this checkout of the Android tree")
  option_parser.add_option("-s", "--serial", default=None, help="Specify the serial number of device to run test on")
  options, args = option_parser.parse_args();

  logging.basicConfig(level=logging.INFO, format='%(message)s')

  if len(args) > 1:
    logging.fatal("Usage: run_layout_tests.py [options] test-relative-path")
  else:
    if len(args) < 1:
      path = "";
    else:
      path = args[0]
    main(path, options);
