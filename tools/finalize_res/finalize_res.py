#!/usr/bin/env python3
#-*- coding: utf-8 -*-

# Copyright (C) 2021 The Android Open Source Project
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
Finalize resource values in <staging-public-group> tags

Usage: finalize_res.py core/res/res/values/public.xml public_finalized.xml
"""

import re, sys, codecs

def finalize_item(raw):
    global _type, _id
    _id += 1
    return '<public type="%s" name="%s" id="%s" />' % (_type, raw.group(1), '0x{0:0{1}x}'.format(_id-1,8))

def finalize_group(raw):
    global _type, _id
    _type = raw.group(1)
    _id = int(raw.group(2), 16)
    return re.sub(r'<public name="(.+?)" */>', finalize_item, raw.group(3))

with open(sys.argv[1]) as f:
    raw = f.read()
    raw = re.sub(r'<staging-public-group type="(.+?)" first-id="(.+?)">(.+?)</staging-public-group>', finalize_group, raw, flags=re.DOTALL)
    with open(sys.argv[2], "w") as f:
        f.write(raw)
