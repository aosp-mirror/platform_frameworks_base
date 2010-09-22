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
import sys
import subprocess
import tempfile
import webbrowser

#TODO: These should not be hardcoded
RESULTS_ABSOLUTE_PATH = "/sdcard/layout-test-results/"
DETAILS_HTML = "details.html"
SUMMARY_TXT = "summary.txt"

def main(options, args):
  if args:
    path = " ".join(args);
  else:
    path = "";

  logging.basicConfig(level=logging.INFO, format='%(message)s')

  tmpdir = tempfile.gettempdir()

  if options.tests_root_directory != None:
    # if options.tests_root_directory is absolute, os.getcwd() is discarded!
    tests_root_directory = os.path.normpath(os.path.join(os.getcwd(), options.tests_root_directory))
    server_options = " --tests-root-directory=" + tests_root_directory
  else:
    server_options = "";

  # Restart the server
  cmd = os.path.join(os.path.abspath(os.path.dirname(sys.argv[0])), "run_apache2.py") + server_options + " restart"
  os.system(cmd);

  # Run the tests in path
  cmd = "adb shell am instrument "
  cmd += "-e class com.android.dumprendertree2.scriptsupport.Starter#startLayoutTests "
  cmd += "-e path \"" + path + "\" "
  cmd +="-w com.android.dumprendertree2/com.android.dumprendertree2.scriptsupport.ScriptTestRunner"

  logging.info("Running the tests...")
  subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()

  logging.info("Downloading the summaries...")

  # Download the txt summary to tmp folder
  summary_txt_tmp_path = os.path.join(tmpdir, SUMMARY_TXT)
  cmd = "rm -f " + summary_txt_tmp_path + ";"
  cmd += "adb pull " + RESULTS_ABSOLUTE_PATH + SUMMARY_TXT + " " + summary_txt_tmp_path
  subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()

  # Download the html summary to tmp folder
  details_html_tmp_path = os.path.join(tmpdir, DETAILS_HTML)
  cmd = "rm -f " + details_html_tmp_path + ";"
  cmd += "adb pull " + RESULTS_ABSOLUTE_PATH + DETAILS_HTML + " " + details_html_tmp_path
  subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()

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
  options, args = option_parser.parse_args();
  main(options, args);
