//
// Copyright 2006 The Android Open Source Project
//
// Build resource files from raw assets.
//

#ifndef IMAGES_H
#define IMAGES_H

#include "ResourceTable.h"

status_t preProcessImage(Bundle* bundle, const sp<AaptAssets>& assets,
                         const sp<AaptFile>& file, String8* outNewLeafName);

status_t postProcessImage(const sp<AaptAssets>& assets,
						  ResourceTable* table, const sp<AaptFile>& file);

#endif
