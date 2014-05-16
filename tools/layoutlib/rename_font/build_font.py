#!/usr/bin/env python

# Copyright (C) 2014 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Rename the PS name of all fonts in the input directory and copy them to the
output directory.

Usage: build_font.py /path/to/input_fonts/ /path/to/output_fonts/

"""

import sys
# fontTools is available at platform/external/fonttools
from fontTools import ttx
import re
import os
from lxml import etree
import shutil
import glob

def main(argv):
  if len(argv) != 2:
    print "Usage: build_font.py /path/to/input_fonts/ /path/to/out/dir/"
    sys.exit(1)
  if not os.path.isdir(argv[0]):
    print argv[0] + "is not a valid directory"
    sys.exit(1)
  if not os.path.isdir(argv[1]):
    print argv[1] + "is not a valid directory"
    sys.exit(1)
  cwd = os.getcwd()
  os.chdir(argv[1])
  files = glob.glob('*')
  for filename in files:
    os.remove(filename)
  os.chdir(cwd)
  for filename in os.listdir(argv[0]):
    if not os.path.splitext(filename)[1].lower() == ".ttf":
      shutil.copy(os.path.join(argv[0], filename), argv[1])
      continue
    print os.path.join(argv[0], filename)
    old_ttf_path = os.path.join(argv[0], filename)
    # run ttx to generate an xml file in the output folder which represents all
    # its info
    ttx_args = ["-d", argv[1], old_ttf_path]
    ttx.main(ttx_args)
    # the path to the output file. The file name is the fontfilename.ttx
    ttx_path = os.path.join(argv[1], filename)
    ttx_path = ttx_path[:-1] + "x"
    # now parse the xml file to change its PS name.
    tree = etree.parse(ttx_path)
    encoding = tree.docinfo.encoding
    root = tree.getroot()
    for name in root.iter('name'):
      [old_ps_name, version] = get_font_info(name)
      new_ps_name = old_ps_name + version
      update_name(name, new_ps_name)
    tree.write(ttx_path, xml_declaration=True, encoding=encoding )
    # generate the udpated font now.
    ttx_args = ["-d", argv[1], ttx_path]
    ttx.main(ttx_args)
    # delete the temp ttx file.
    os.remove(ttx_path)

def get_font_info(tag):
  ps_name = None
  ps_version = None
  for namerecord in tag.iter('namerecord'):
    if 'nameID' in namerecord.attrib:
      # if the tag has nameID=6, it is the postscript name of the font.
      # see: http://scripts.sil.org/cms/scripts/page.php?item_id=IWS-Chapter08#3054f18b
      if namerecord.attrib['nameID'] == '6':
        if ps_name is not None:
          if not sanitize(namerecord.text) == ps_name:
            sys.exit('found multiple possibilities of the font name')
        else:
          ps_name = sanitize(namerecord.text)
      # nameID=5 means the font version
      if namerecord.attrib['nameID'] == '5':
        if ps_version is not None:
          if not ps_version == get_version(namerecord.text):
            sys.exit('found multiple possibilities of the font version')
        else:
          ps_version = get_version(namerecord.text)
  if ps_name is not None and ps_version is not None:
    return [ps_name, ps_version]
  sys.exit('didn\'t find the font name or version')


def update_name(tag, name):
  for namerecord in tag.iter('namerecord'):
    if 'nameID' in namerecord.attrib:
      if namerecord.attrib['nameID'] == '6':
        namerecord.text = name

def sanitize(string):
  return re.sub(r'[^\w-]+', '', string)

def get_version(string):
  # The string must begin with "Version n.nn "
  # to extract n.nn, we return the second entry in the split strings.
  string = string.strip()
  if not string.startswith("Version "):
    sys.exit('mal-formed font version')
  return sanitize(string.split()[1])

if __name__ == '__main__':
  main(sys.argv[1:])
