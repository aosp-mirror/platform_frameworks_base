#!/usr/bin/python
#
# Copyright (C) 2010 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
"""Start, stop, or restart apache2 server.

  Apache2 must be installed with mod_php!

  Usage:
    run_apache2.py start|stop|restart
"""

import sys
import os
import subprocess
import logging
import optparse
import time

def main(run_cmd, options):
  # Setup logging class
  logging.basicConfig(level=logging.INFO, format='%(message)s')

  if not run_cmd in ("start", "stop", "restart"):
    logging.info("illegal argument: " + run_cmd)
    logging.info("Usage: python run_apache2.py start|stop|restart")
    return False

  # Create /tmp/WebKit if it doesn't exist. This is needed for various files used by apache2
  tmp_WebKit = os.path.join("/tmp", "WebKit")
  if not os.path.exists(tmp_WebKit):
    os.mkdir(tmp_WebKit)

  # Get the path to android tree root based on the script location.
  # Basically we go 5 levels up
  parent = os.pardir
  script_location = os.path.abspath(os.path.dirname(sys.argv[0]))
  android_tree_root = os.path.join(script_location, parent, parent, parent, parent, parent)
  android_tree_root = os.path.normpath(android_tree_root)

  # If any of these is relative, then it's relative to ServerRoot (in our case android_tree_root)
  webkit_path = os.path.join("external", "webkit")
  if (options.tests_root_directory != None):
    # if options.tests_root_directory is absolute, os.getcwd() is discarded!
    layout_tests_path = os.path.normpath(os.path.join(os.getcwd(), options.tests_root_directory))
  else:
    layout_tests_path = os.path.join(webkit_path, "LayoutTests")
  http_conf_path = os.path.join(layout_tests_path, "http", "conf")

  # Prepare the command to set ${APACHE_RUN_USER} and ${APACHE_RUN_GROUP}
  envvars_path = os.path.join("/etc", "apache2", "envvars")
  export_envvars_cmd = "source " + envvars_path

  error_log_path = os.path.join(tmp_WebKit, "apache2-error.log")
  custom_log_path = os.path.join(tmp_WebKit, "apache2-access.log")

  # Prepare the command to (re)start/stop the server with specified settings
  apache2_restart_template = "apache2 -k %s"
  directives  = " -c \"ServerRoot " + android_tree_root + "\""

  # The default config in apache2-debian-httpd.conf listens on ports 8080 and
  # 8443. We also need to listen on port 8000 for HTTP tests.
  directives += " -c \"Listen 8000\""

  # We use http/tests as the document root as the HTTP tests use hardcoded
  # resources at the server root. We then use aliases to make available the
  # complete set of tests and the required scripts.
  directives += " -c \"DocumentRoot " + os.path.join(layout_tests_path, "http", "tests/") + "\""
  directives += " -c \"Alias /LayoutTests " + layout_tests_path + "\""
  directives += " -c \"Alias /Tools/DumpRenderTree/android " + \
    os.path.join(webkit_path, "Tools", "DumpRenderTree", "android") + "\""
  directives += " -c \"Alias /ThirdPartyProject.prop " + \
    os.path.join(webkit_path, "ThirdPartyProject.prop") + "\""

  # This directive is commented out in apache2-debian-httpd.conf for some reason
  # However, it is useful to browse through tests in the browser, so it's added here.
  # One thing to note is that because of problems with mod_dir and port numbers, mod_dir
  # is turned off. That means that there _must_ be a trailing slash at the end of URL
  # for auto indexes to work correctly.
  directives += " -c \"LoadModule autoindex_module /usr/lib/apache2/modules/mod_autoindex.so\""

  directives += " -c \"ErrorLog " + error_log_path +"\""
  directives += " -c \"CustomLog " + custom_log_path + " combined\""
  directives += " -c \"SSLCertificateFile " + os.path.join(http_conf_path, "webkit-httpd.pem") + \
    "\""
  directives += " -c \"User ${APACHE_RUN_USER}\""
  directives += " -c \"Group ${APACHE_RUN_GROUP}\""
  directives += " -C \"TypesConfig " + \
    os.path.join(android_tree_root, http_conf_path, "mime.types") + "\""
  conf_file_cmd = " -f " + \
    os.path.join(android_tree_root, http_conf_path, "apache2-debian-httpd.conf")

  # Try to execute the commands
  logging.info("Will " + run_cmd + " apache2 server.")

  # It is worth noting here that if the configuration file with which we restart the server points
  # to a different PidFile it will not work and will result in a second apache2 instance.
  if (run_cmd == 'restart'):
    logging.info("First will stop...")
    if execute_cmd(envvars_path, error_log_path,
                   export_envvars_cmd + " && " + (apache2_restart_template % ('stop')) + directives + conf_file_cmd) == False:
      logging.info("Failed to stop Apache2")
      return False
    logging.info("Stopped. Will start now...")
    # We need to sleep breifly to avoid errors with apache being stopped and started too quickly
    time.sleep(0.5)

  if execute_cmd(envvars_path, error_log_path,
                 export_envvars_cmd + " && " +
                 (apache2_restart_template % (run_cmd)) + directives +
                 conf_file_cmd) == False:
    logging.info("Failed to start Apache2")
    return False

  logging.info("Successfully started")
  return True

def execute_cmd(envvars_path, error_log_path, cmd):
  p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
  (out, err) = p.communicate()

  # Output the stdout from the command to console
  logging.info(out)

  # Report any errors
  if p.returncode != 0:
    logging.info("!! ERRORS:")

    if err.find(envvars_path) != -1:
      logging.info(err)
    elif err.find('command not found') != -1:
      logging.info("apache2 is probably not installed")
    else:
      logging.info(err)
      logging.info("Try looking in " + error_log_path + " for details")
    return False

  return True

if __name__ == "__main__":
  option_parser = optparse.OptionParser(usage="Usage: %prog [options] start|stop|restart")
  option_parser.add_option("", "--tests-root-directory",
                           help="The directory from which to take the tests, default is external/webkit/LayoutTests in this checkout of the Android tree")
  options, args = option_parser.parse_args();

  if len(args) < 1:
    run_cmd = ""
  else:
    run_cmd = args[0]

  main(run_cmd, options)
