#!/usr/bin/env python3
#
# Copyright (C) 2021 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import io
from pathlib import Path
import tempfile
import unittest
import zipfile

import merge_annotation_zips


zip_a = {
  'android/provider/annotations.xml':
  """<?xml version="1.0" encoding="UTF-8"?>
<root>
  <item name="android.provider.BlockedNumberContract boolean isBlocked(android.content.Context, java.lang.String)">
    <annotation name="androidx.annotation.WorkerThread"/>
  </item>
  <item name="android.provider.SimPhonebookContract.SimRecords android.net.Uri getItemUri(int, int, int) 2">
    <annotation name="androidx.annotation.IntRange">
      <val name="from" val="1" />
    </annotation>
  </item>
</root>""",
  'android/os/annotations.xml':
  """<?xml version="1.0" encoding="UTF-8"?>
<root>
  <item name="android.app.ActionBar void setCustomView(int) 0">
    <annotation name="androidx.annotation.LayoutRes"/>
  </item>
</root>
"""
}

zip_b = {
  'android/provider/annotations.xml':
  """<?xml version="1.0" encoding="UTF-8"?>
<root>
  <item name="android.provider.MediaStore QUERY_ARG_MATCH_FAVORITE">
    <annotation name="androidx.annotation.IntDef">
      <val name="value" val="{android.provider.MediaStore.MATCH_DEFAULT, android.provider.MediaStore.MATCH_INCLUDE, android.provider.MediaStore.MATCH_EXCLUDE, android.provider.MediaStore.MATCH_ONLY}" />
      <val name="flag" val="true" />
    </annotation>
  </item>
  <item name="android.provider.MediaStore QUERY_ARG_MATCH_PENDING">
    <annotation name="androidx.annotation.IntDef">
      <val name="value" val="{android.provider.MediaStore.MATCH_DEFAULT, android.provider.MediaStore.MATCH_INCLUDE, android.provider.MediaStore.MATCH_EXCLUDE, android.provider.MediaStore.MATCH_ONLY}" />
      <val name="flag" val="true" />
    </annotation>
  </item>
</root>"""
}

zip_c = {
  'android/app/annotations.xml':
  """<?xml version="1.0" encoding="UTF-8"?>
<root>
  <item name="android.app.ActionBar void setCustomView(int) 0">
    <annotation name="androidx.annotation.LayoutRes"/>
  </item>
</root>"""
}

merged_provider = """<?xml version='1.0' encoding='UTF-8'?>
<root>
  <item name="android.provider.BlockedNumberContract boolean isBlocked(android.content.Context, java.lang.String)">
    <annotation name="androidx.annotation.WorkerThread" />
  </item>
  <item name="android.provider.MediaStore QUERY_ARG_MATCH_FAVORITE">
    <annotation name="androidx.annotation.IntDef">
      <val name="value" val="{android.provider.MediaStore.MATCH_DEFAULT, android.provider.MediaStore.MATCH_INCLUDE, android.provider.MediaStore.MATCH_EXCLUDE, android.provider.MediaStore.MATCH_ONLY}" />
      <val name="flag" val="true" />
    </annotation>
  </item>
  <item name="android.provider.MediaStore QUERY_ARG_MATCH_PENDING">
    <annotation name="androidx.annotation.IntDef">
      <val name="value" val="{android.provider.MediaStore.MATCH_DEFAULT, android.provider.MediaStore.MATCH_INCLUDE, android.provider.MediaStore.MATCH_EXCLUDE, android.provider.MediaStore.MATCH_ONLY}" />
      <val name="flag" val="true" />
    </annotation>
  </item>
<item name="android.provider.SimPhonebookContract.SimRecords android.net.Uri getItemUri(int, int, int) 2">
    <annotation name="androidx.annotation.IntRange">
      <val name="from" val="1" />
    </annotation>
  </item>
</root>"""



class MergeAnnotationZipsTest(unittest.TestCase):

  def test_merge_zips(self):
    with tempfile.TemporaryDirectory() as out_dir:
      for zip_content in [zip_a, zip_b, zip_c]:
        f = io.BytesIO()
        with zipfile.ZipFile(f, "w") as zip_file:
          for filename, content in zip_content.items():
            zip_file.writestr(filename, content)
          merge_annotation_zips.merge_zip_file(out_dir, zip_file)

      # Unchanged
      self.assertEqual(zip_a['android/os/annotations.xml'], Path(out_dir, 'android/os/annotations.xml').read_text())
      self.assertEqual(zip_c['android/app/annotations.xml'], Path(out_dir, 'android/app/annotations.xml').read_text())

      # Merged
      self.assertEqual(merged_provider, Path(out_dir, 'android/provider/annotations.xml').read_text())


if __name__ == "__main__":
  unittest.main(verbosity=2)
