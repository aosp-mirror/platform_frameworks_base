#!/usr/bin/python

"""Run layout tests on the device.

  It runs the specified tests on the device, downloads the summaries to the temporary directory
  and opens html details in the default browser.

  Usage:
    run_layout_tests.py PATH
"""

import sys
import os
import subprocess
import logging
import webbrowser
import tempfile

#TODO: These should not be hardcoded
RESULTS_ABSOLUTE_PATH = "/sdcard/android/LayoutTests-results/"
DETAILS_HTML = "details.html"
SUMMARY_TXT = "summary.txt"

def main():
  if len(sys.argv) > 1:
    path = sys.argv[1]
  else:
    path = ""

  logging.basicConfig(level=logging.INFO, format='%(message)s')

  tmpdir = tempfile.gettempdir()

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
  cmd = "adb pull " + RESULTS_ABSOLUTE_PATH + SUMMARY_TXT + " " + summary_txt_tmp_path
  subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()

  # Download the html summary to tmp folder
  details_html_tmp_path = os.path.join(tmpdir, DETAILS_HTML)
  cmd = "adb pull " + RESULTS_ABSOLUTE_PATH + DETAILS_HTML + " " + details_html_tmp_path
  subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).communicate()

  # Print summary to console
  logging.info("All done.\n")
  cmd = "cat " + summary_txt_tmp_path
  os.system(cmd)
  logging.info("")

  # Open the browser with summary
  webbrowser.open(details_html_tmp_path)

if __name__ == "__main__":
  main();
