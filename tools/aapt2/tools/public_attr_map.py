#!/usr/bin/env python

import sys
import xml.etree.ElementTree as ET

def findSdkLevelForAttribute(id):
    intId = int(id, 16)
    packageId = 0x000000ff & (intId >> 24)
    typeId = 0x000000ff & (intId >> 16)
    entryId = 0x0000ffff & intId

    if packageId != 0x01 or typeId != 0x01:
        return 0

    levels = [(1, 0x021c), (2, 0x021d), (3, 0x0269), (4, 0x028d),
              (5, 0x02ad), (6, 0x02b3), (7, 0x02b5), (8, 0x02bd),
              (9, 0x02cb), (11, 0x0361), (12, 0x0366), (13, 0x03a6),
              (16, 0x03ae), (17, 0x03cc), (18, 0x03da), (19, 0x03f1),
              (20, 0x03f6), (21, 0x04ce)]
    for level, attrEntryId in levels:
        if entryId <= attrEntryId:
            return level
    return 22


tree = None
with open(sys.argv[1], 'rt') as f:
    tree = ET.parse(f)

attrs = []
for node in tree.iter('public'):
    if node.get('type') == 'attr':
        sdkLevel = findSdkLevelForAttribute(node.get('id', '0'))
        if sdkLevel > 1 and sdkLevel < 22:
            attrs.append("{{ u\"{}\", {} }}".format(node.get('name'), sdkLevel))

print "#include <string>"
print "#include <unordered_map>"
print
print "namespace aapt {"
print
print "static std::unordered_map<std::u16string, size_t> sAttrMap = {"
print ",\n    ".join(attrs)
print "};"
print
print "size_t findAttributeSdkLevel(const std::u16string& name) {"
print "    auto iter = sAttrMap.find(name);"
print "    if (iter != sAttrMap.end()) {"
print "        return iter->second;"
print "    }"
print "    return 0;"
print "}"
print
print "} // namespace aapt"
print
