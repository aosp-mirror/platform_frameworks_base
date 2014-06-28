#!/usr/bin/env python

"""Tests build_font.py by renaming a font.

The test copies Roboto-Regular.ttf to a tmp directory and ask build_font.py to rename it and put in another dir.
We then use ttx to dump the new font to its xml and check if rename was successful

To test locally, use:
PYTHONPATH="$PYTHONPATH:/path/to/android/checkout/external/fonttools/Lib" ./test.py
"""

import unittest
import build_font

from fontTools import ttx
import os
import xml.etree.ElementTree as etree
import shutil
import tempfile

class MyTest(unittest.TestCase):
  def test(self):
    font_name = "Roboto-Regular.ttf"
    srcdir = tempfile.mkdtemp()
    print "srcdir: " + srcdir
    shutil.copy(font_name, srcdir)
    destdir = tempfile.mkdtemp()
    print "destdir: " + destdir
    self.assertTrue(build_font.main([srcdir, destdir]) is None)
    out_path = os.path.join(destdir, font_name)
    ttx.main([out_path])
    ttx_path = out_path[:-1] + "x"
    tree = etree.parse(ttx_path)
    root = tree.getroot()
    name_tag = root.find('name')
    fonts = build_font.get_font_info(name_tag)
    shutil.rmtree(srcdir)
    shutil.rmtree(destdir)
    self.assertEqual(fonts[0].family, "Roboto1200310")
    self.assertEqual(fonts[0].fullname, "Roboto1200310 Regular")



if __name__ == '__main__':
  unittest.main()
