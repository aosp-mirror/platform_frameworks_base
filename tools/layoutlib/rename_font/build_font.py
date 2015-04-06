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

import glob
from multiprocessing import Pool
import os
import re
import shutil
import sys
import xml.etree.ElementTree as etree

# Prevent .pyc files from being created.
sys.dont_write_bytecode = True

# fontTools is available at platform/external/fonttools
from fontTools import ttx

# global variable
dest_dir = '/tmp'


class FontInfo(object):
  family = None
  style = None
  version = None
  ends_in_regular = False
  fullname = None


class InvalidFontException(Exception):
  pass


# These constants represent the value of nameID parameter in the namerecord for
# different information.
# see http://scripts.sil.org/cms/scripts/page.php?item_id=IWS-Chapter08#3054f18b
NAMEID_FAMILY = 1
NAMEID_STYLE = 2
NAMEID_FULLNAME = 4
NAMEID_VERSION = 5


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
        if extension == '.ttf':
          input_fonts.append(input_path)
        elif extension == '.xml':
          shutil.copy(input_path, dest_dir)
      if '.git' in dirnames:
        # don't go into any .git directories.
        dirnames.remove('.git')
  # Create as many threads as the number of CPUs
  pool = Pool(processes=None)
  pool.map(convert_font, input_fonts)


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
    root = tree.getroot()
    for name in root.iter('name'):
      update_tag(name, get_font_info(name))
    tree.write(ttx_path, xml_declaration=True, encoding='utf-8')
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
  """ Returns a list of FontInfo representing the various sets of namerecords
      found in the name table of the font. """
  fonts = []
  font = None
  last_name_id = sys.maxint
  for namerecord in tag.iter('namerecord'):
    if 'nameID' in namerecord.attrib:
      name_id = int(namerecord.attrib['nameID'])
      # A new font should be created for each platform, encoding and language
      # id. But, since the nameIDs are sorted, we use the easy approach of
      # creating a new one when the nameIDs reset.
      if name_id <= last_name_id and font is not None:
        fonts.append(font)
        font = None
      last_name_id = name_id
      if font is None:
        font = FontInfo()
      if name_id == NAMEID_FAMILY:
        font.family = namerecord.text.strip()
      if name_id == NAMEID_STYLE:
        font.style = namerecord.text.strip()
      if name_id == NAMEID_FULLNAME:
        font.ends_in_regular = ends_in_regular(namerecord.text)
        font.fullname = namerecord.text.strip()
      if name_id == NAMEID_VERSION:
        font.version = get_version(namerecord.text)
  if font is not None:
    fonts.append(font)
  return fonts


def update_tag(tag, fonts):
  last_name_id = sys.maxint
  fonts_iterator = fonts.__iter__()
  font = None
  for namerecord in tag.iter('namerecord'):
    if 'nameID' in namerecord.attrib:
      name_id = int(namerecord.attrib['nameID'])
      if name_id <= last_name_id:
        font = fonts_iterator.next()
        font = update_font_name(font)
      last_name_id = name_id
      if name_id == NAMEID_FAMILY:
        namerecord.text = font.family
      if name_id == NAMEID_FULLNAME:
        namerecord.text = font.fullname


def update_font_name(font):
  """ Compute the new font family name and font fullname. If the font has a
      valid version, it's sanitized and appended to the font family name. The
      font fullname is then created by joining the new family name and the
      style. If the style is 'Regular', it is appended only if the original font
      had it. """
  if font.family is None or font.style is None:
    raise InvalidFontException('Font doesn\'t have proper family name or style')
  if font.version is not None:
    new_family = font.family + font.version
  else:
    new_family = font.family
  if font.style is 'Regular' and not font.ends_in_regular:
    font.fullname = new_family
  else:
    font.fullname = new_family + ' ' + font.style
  font.family = new_family
  return font


def ends_in_regular(string):
  """ According to the specification, the font fullname should not end in
      'Regular' for plain fonts. However, some fonts don't obey this rule. We
      keep the style info, to minimize the diff. """
  string = string.strip().split()[-1]
  return string is 'Regular'


def get_version(string):
  string = string.strip()
  # The spec says that the version string should start with "Version ". But not
  # all fonts do. So, we return the complete string if it doesn't start with
  # the prefix, else we return the rest of the string after sanitizing it.
  prefix = 'Version '
  if string.startswith(prefix):
    string = string[len(prefix):]
  return sanitize(string)


def sanitize(string):
  """ Remove non-standard chars. """
  return re.sub(r'[^\w-]+', '', string)

if __name__ == '__main__':
  main(sys.argv[1:])
