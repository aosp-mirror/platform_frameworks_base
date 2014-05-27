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
Rename the PS name of all fonts in the input directories and copy them to the
output directory.

Usage: build_font.py /path/to/input_fonts1/ /path/to/input_fonts2/ /path/to/output_fonts/

"""

import sys
# fontTools is available at platform/external/fonttools
from fontTools import ttx
import re
import os
from lxml import etree
import shutil
import glob
from multiprocessing import Pool

# global variable
dest_dir = '/tmp'

def main(argv):
  if len(argv) < 2:
    sys.exit('Usage: build_font.py /path/to/input_fonts/ /path/to/out/dir/')
  for directory in argv:
    if not os.path.isdir(directory):
      sys.exit(directory + ' is not a valid directory')
  global dest_dir
  dest_dir = argv[-1]
  src_dirs = argv[:-1]
  cwd = os.getcwd()
  os.chdir(dest_dir)
  files = glob.glob('*')
  for filename in files:
    os.remove(filename)
  os.chdir(cwd)
  input_fonts = list()
  for src_dir in src_dirs:
    for dirname, dirnames, filenames in os.walk(src_dir):
      for filename in filenames:
          input_path = os.path.join(dirname, filename)
          extension = os.path.splitext(filename)[1].lower()
          if (extension == '.ttf'):
            input_fonts.append(input_path)
          elif (extension == '.xml'):
            shutil.copy(input_path, dest_dir)
      if '.git' in dirnames:
          # don't go into any .git directories.
          dirnames.remove('.git')
  # Create as many threads as the number of CPUs
  pool = Pool(processes=None)
  pool.map(convert_font, input_fonts)


class InvalidFontException(Exception):
  pass

def convert_font(input_path):
  filename = os.path.basename(input_path)
  print 'Converting font: ' + filename
  # the path to the output file. The file name is the fontfilename.ttx
  ttx_path = os.path.join(dest_dir, filename)
  ttx_path = ttx_path[:-1] + 'x'
  try:
    # run ttx to generate an xml file in the output folder which represents all
    # its info
    ttx_args = ['-q', '-d', dest_dir, input_path]
    ttx.main(ttx_args)
    # now parse the xml file to change its PS name.
    tree = etree.parse(ttx_path)
    encoding = tree.docinfo.encoding
    root = tree.getroot()
    for name in root.iter('name'):
      [old_ps_name, version] = get_font_info(name)
      if old_ps_name is not None and version is not None:
        new_ps_name = old_ps_name + version
        update_name(name, new_ps_name)
    tree.write(ttx_path, xml_declaration=True, encoding=encoding )
    # generate the udpated font now.
    ttx_args = ['-q', '-d', dest_dir, ttx_path]
    ttx.main(ttx_args)
  except InvalidFontException:
    # In case of invalid fonts, we exit.
    print filename + ' is not a valid font'
    raise
  except Exception as e:
    print 'Error converting font: ' + filename
    print e
    # Some fonts are too big to be handled by the ttx library.
    # Just copy paste them.
    shutil.copy(input_path, dest_dir)
  try:
    # delete the temp ttx file is it exists.
    os.remove(ttx_path)
  except OSError:
    pass

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
            raise InvalidFontException('found multiple possibilities of the font name')
        else:
          ps_name = sanitize(namerecord.text)
      # nameID=5 means the font version
      if namerecord.attrib['nameID'] == '5':
        if ps_version is not None:
          if not ps_version == get_version(namerecord.text):
            raise InvalidFontException('found multiple possibilities of the font version')
        else:
          ps_version = get_version(namerecord.text)
  return [ps_name, ps_version]


def update_name(tag, name):
  for namerecord in tag.iter('namerecord'):
    if 'nameID' in namerecord.attrib:
      if namerecord.attrib['nameID'] == '6':
        namerecord.text = name

def sanitize(string):
  return re.sub(r'[^\w-]+', '', string)

def get_version(string):
  # The string must begin with 'Version n.nn '
  # to extract n.nn, we return the second entry in the split strings.
  string = string.strip()
  if not string.startswith('Version '):
    raise InvalidFontException('mal-formed font version')
  return sanitize(string.split()[1])

if __name__ == '__main__':
  main(sys.argv[1:])
