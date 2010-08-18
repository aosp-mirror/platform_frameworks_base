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

def main():
  if len(sys.argv) < 2:
    run_cmd = ""
  else:
    run_cmd = sys.argv[1]

  #Setup logging class
  logging.basicConfig(level=logging.INFO, format='%(message)s')

  if not run_cmd in ("start", "stop", "restart"):
    logging.info("illegal argument: " + run_cmd)
    logging.info("Usage: python run_apache2.py start|stop|restart")
    return

  #Create /tmp/WebKit if it doesn't exist. This is needed for various files used by apache2
  tmp_WebKit = os.path.join("/tmp", "WebKit")
  if not os.path.exists(tmp_WebKit):
    os.mkdir(tmp_WebKit)

  #Get the path to android tree root based on the script location.
  #Basically we go 5 levels up
  parent = os.pardir
  script_location = os.path.abspath(os.path.dirname(sys.argv[0]))
  android_tree_root = os.path.join(script_location, parent, parent, parent, parent, parent)
  android_tree_root = os.path.normpath(android_tree_root)

  #Prepare the command to set ${APACHE_RUN_USER} and ${APACHE_RUN_GROUP}
  envvars_path = os.path.join("/etc", "apache2", "envvars")
  export_envvars_cmd = "source " + envvars_path

  error_log_path = os.path.join(tmp_WebKit, "apache2-error.log")

  #Prepare the command to (re)start/stop the server with specified settings
  apache2_restart_cmd = "apache2 -k " + run_cmd
  directives  = " -c \"ServerRoot " + android_tree_root + "\""
  directives += " -c \"DocumentRoot " + os.path.join("external", "webkit") + "\""

  #This directive is commented out in apache2-debian-httpd.conf for some reason
  #However, it is useful to browse through tests in the browser, so it's added here.
  #One thing to note is that because of problems with mod_dir and port numbers, mod_dir
  #is turned off. That means that there _must_ be a trailing slash at the end of URL
  #for auto indexes to work correctly.
  directives += " -c \"LoadModule autoindex_module /usr/lib/apache2/modules/mod_autoindex.so\""

  directives += " -c \"ErrorLog " + error_log_path +"\""
  directives += " -c \"SSLCertificateFile " + os.path.join ("external", "webkit", "LayoutTests",
    "http", "conf", "webkit-httpd.pem") + "\""
  directives += " -c \"User ${APACHE_RUN_USER}\""
  directives += " -c \"Group ${APACHE_RUN_GROUP}\""
  directives += " -C \"TypesConfig " + os.path.join("/etc", "mime.types") + "\""
  conf_file_cmd = " -f " + os.path.join(android_tree_root, "external", "webkit", "LayoutTests",
    "http", "conf", "apache2-debian-httpd.conf")

  #Try to execute the commands
  logging.info("Will " + run_cmd + " apache2 server.")
  cmd = export_envvars_cmd + " && " + apache2_restart_cmd + directives + conf_file_cmd
  p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
  (out, err) = p.communicate()

  #Output the stdout from the command to console
  logging.info(out)

  #Report any errors
  if p.returncode != 0:
    logging.info("!! ERRORS:")

    if err.find(envvars_path) != -1:
      logging.info(err)
    elif err.find('command not found') != -1:
      logging.info("apache2 is probably not installed")
    else:
      logging.info(err)
      logging.info("Try looking in " + error_log_path + " for details")
  else:
    logging.info("OK")

if __name__ == "__main__":
  main();
