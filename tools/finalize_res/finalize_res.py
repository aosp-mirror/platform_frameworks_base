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
and convert those to <staging-public-group-final>

Usage: finalize_res.py core/res/res/values/public.xml public_finalized.xml
"""

import re, sys, codecs

def finalize_item(raw):
    global _type_ids, _type
    id = _type_ids[_type]
    _type_ids[_type] += 1
    name = raw.group(1)
    val = '<public type="%s" name="%s" id="%s" />' % (_type, name, '0x{0:0{1}x}'.format(id,8))
    if re.match(r'_*removed.+', name):
        val = '<!-- ' + val.replace('<public', '< public') + ' -->'
    return val

def finalize_group(raw):
    global _type_ids, _type
    _type = raw.group(1)
    id = int(raw.group(2), 16)
    _type_ids[_type] = _type_ids.get(_type, id)
    (res, count) = re.subn(r' {0,2}<public name="(.+?)" */>', finalize_item, raw.group(3))
    if count > 0:
        res = raw.group(0).replace("staging-public-group", "staging-public-group-final") + '\n' + res
    return res

def collect_ids(raw):
    global _type_ids
    for m in re.finditer(r'<public type="(.+?)" name=".+?" id="(.+?)" />', raw):
        type = m.group(1)
        id = int(m.group(2), 16)
        _type_ids[type] = max(id + 1, _type_ids.get(type, 0))

with open(sys.argv[1]) as f:
    global _type_ids, _type
    _type_ids = {}
    raw = f.read()
    collect_ids(raw)
    raw = re.sub(r'<staging-public-group type="(.+?)" first-id="(.+?)">(.+?)</staging-public-group>', finalize_group, raw, flags=re.DOTALL)
    raw = re.sub(r' *\n', '\n', raw)
    raw = re.sub(r'\n{3,}', '\n\n', raw)
    with open(sys.argv[2], "w") as f:
        f.write(raw)

