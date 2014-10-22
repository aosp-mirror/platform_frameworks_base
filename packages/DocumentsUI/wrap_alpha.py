#!/usr/bin/python

import os

"""
# assume everything needs alpha suffixes
for root, dirs, files in os.walk('.'):
    if "res/drawable-" not in root: continue

    for before in files:
        if "_alpha.png" in before: continue

        after = before.replace(".png", "_alpha.png")
        os.rename(os.path.join(root, before), os.path.join(root, after))
"""

# build xml redirection
for root, dirs, files in os.walk('.'):
    if "res/drawable-" not in root: continue

    for src in files:
        if not src.endswith(".png"): continue
        src = src[0:-4]

        src_clause = '\n    android:src="@drawable/%s"' % (src)

        alpha = src.endswith("_alpha")
        if alpha:
            src = src[0:-6]
            if "ic_doc" in src or "ic_root" in src or "ic_grid_folder" in src:
                alpha_clause = '\n    android:tint="@*android:color/secondary_text_material_light"'
            else:
                alpha_clause = '\n    android:tint="?android:attr/colorControlNormal"'
        else:
            alpha_clause = ''

        am = src.endswith("_am")
        if am:
            src = src[0:-3]
            am_clause = '\n    android:autoMirrored="true"'
        else:
            am_clause = ''

        with open("res/drawable/%s.xml" % (src), 'w') as xml:
            xml.write("""<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"%s%s%s />
""" % (src_clause, alpha_clause, am_clause))
