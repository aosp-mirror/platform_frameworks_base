#!/usr/bin/env python3
# -*- coding: utf-8 -*-

#  Copyright (C) 2022 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
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

Usage: $ANDROID_BUILD_TOP/frameworks/base/tools/aapt2/tools/finalize_res.py \
           $ANDROID_BUILD_TOP/frameworks/base/core/res/res/values/public-staging.xml \
           $ANDROID_BUILD_TOP/frameworks/base/core/res/res/values/public-final.xml
"""

import re
import sys

resTypes = ["attr", "id", "style", "string", "dimen", "color", "array", "drawable", "layout",
            "anim", "animator", "interpolator", "mipmap", "integer", "transition", "raw", "bool",
            "fraction"]

_type_ids = {}
_type = ""

_lowest_staging_first_id = 0x01FFFFFF

"""
    Created finalized <public> declarations for staging resources, ignoring them if they've been
    prefixed with removed_. The IDs are assigned without holes starting from the last ID for that
    type currently finalized in public-final.xml.
"""
def finalize_item(raw):
    name = raw.group(1)
    if re.match(r'_*removed.+', name):
        return ""
    id = _type_ids[_type]
    _type_ids[_type] += 1
    return '  <public type="%s" name="%s" id="%s" />\n' % (_type, name, '0x{0:0{1}x}'.format(id, 8))


"""
    Finalizes staging-public-groups if they have any entries in them. Also keeps track of the
    lowest first-id of the non-empty groups so that the next release's staging-public-groups can
    be assigned the next down shifted first-id.
"""
def finalize_group(raw):
    global _type, _lowest_staging_first_id
    _type = raw.group(1)
    id = int(raw.group(2), 16)
    _type_ids[_type] = _type_ids.get(_type, id)
    (res, count) = re.subn(' {0,4}<public name="(.+?)" */>\n', finalize_item, raw.group(3))
    if count > 0:
        res = raw.group(0).replace("staging-public-group",
                                   "staging-public-group-final") + '\n' + res
        _lowest_staging_first_id = min(id, _lowest_staging_first_id)
    return res

"""
    Collects the max ID for each resType so that the new IDs can be assigned afterwards
"""
def collect_ids(raw):
    for m in re.finditer(r'<public type="(.+?)" name=".+?" id="(.+?)" />', raw):
        type = m.group(1)
        id = int(m.group(2), 16)
        _type_ids[type] = max(id + 1, _type_ids.get(type, 0))


with open(sys.argv[1], "r+") as stagingFile:
    with open(sys.argv[2], "r+") as finalFile:
        existing = finalFile.read()
        # Cut out the closing resources tag so that it can be concatenated easily later
        existing = "\n".join(existing.rsplit("</resources>", 1))

        # Collect the IDs from the existing already finalized resources
        collect_ids(existing)

        staging = stagingFile.read()
        stagingSplit = staging.rsplit("<resources>")
        staging = stagingSplit[1]
        staging = re.sub(
            r'<staging-public-group type="(.+?)" first-id="(.+?)">(.+?)</staging-public-group>',
            finalize_group, staging, flags=re.DOTALL)
        staging = re.sub(r' *\n', '\n', staging)
        staging = re.sub(r'\n{3,}', '\n\n', staging)

        # First write the existing finalized declarations and then append the new stuff
        finalFile.seek(0)
        finalFile.write(existing.strip("\n"))
        finalFile.write("\n\n")
        finalFile.write(staging.strip("\n"))
        finalFile.write("\n")
        finalFile.truncate()

        stagingFile.seek(0)
        # Include the documentation from public-staging.xml that was previously split out
        stagingFile.write(stagingSplit[0])
        # Write the next platform header
        stagingFile.write("<resources>\n\n")
        stagingFile.write("  <!-- ===============================================================\n")
        stagingFile.write("    Resources added in version NEXT of the platform\n\n")
        stagingFile.write("    NOTE: After this version of the platform is forked, changes cannot be made to the root\n")
        stagingFile.write("    branch's groups for that release. Only merge changes to the forked platform branch.\n")
        stagingFile.write("    =============================================================== -->\n")
        stagingFile.write("  <eat-comment/>\n\n")

        # Seed the next release's staging-public-groups as empty declarations,
        # so its easy for another developer to expose a new public resource
        nextId = _lowest_staging_first_id - 0x00010000
        for resType in resTypes:
            stagingFile.write('  <staging-public-group type="%s" first-id="%s">\n'
                              '  </staging-public-group>\n\n' %
                              (resType, '0x{0:0{1}x}'.format(nextId, 8)))
            nextId -= 0x00010000

        # Close the resources tag and truncate, since the file will be shorter than the previous
        stagingFile.write("</resources>\n")
        stagingFile.truncate()
