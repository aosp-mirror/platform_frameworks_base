#!/bin/bash
# Copyright (C) 2019 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

aapt2=$1
soong_zip=$2
genDir=$3
FRAMEWORK_RES_APK=$4
inDir=$5

# (String name, boolean retainFiles = false, String... files)
function compileAndLink {
    moduleName=$1
    mkdir "$genDir"/out/"$moduleName"

    args=""
    for arg in "${@:4}"; do
        if [[ $arg == res* ]]; then
            args="$args $inDir/$arg"
        else
            args="$args $arg"
        fi
    done

    $aapt2 compile -o "$genDir"/out/"$moduleName" $args

    $aapt2 link \
        -I "$FRAMEWORK_RES_APK" \
        --manifest "$inDir"/"$3" \
        -o "$genDir"/out/"$moduleName"/apk.apk \
        "$genDir"/out/"$moduleName"/*.flat \
        --no-compress

    unzip -qq "$genDir"/out/"$moduleName"/apk.apk -d "$genDir"/out/"$moduleName"/unzip

    if [[ "$2" == "APK_WITHOUT_FILE" || "$2" == "BOTH_WITHOUT_FILE" ]]; then
        zip -q -d "$genDir"/out/"$moduleName"/apk.apk "res/*"
        cp "$genDir"/out/"$moduleName"/apk.apk "$genDir"/output/raw/"$moduleName"Apk.apk
    elif [[ "$2" == "APK" || "$2" == "BOTH" ]]; then
        cp "$genDir"/out/"$moduleName"/apk.apk "$genDir"/output/raw/"$moduleName"Apk.apk
    fi

    if [[ "$2" == "ARSC" || "$2" == "BOTH" || "$2" == "BOTH_WITHOUT_FILE" ]]; then
        zip -d "$genDir"/out/"$moduleName"/apk.apk "res/*"
        cp "$genDir"/out/"$moduleName"/unzip/resources.arsc "$genDir"/output/raw/"$moduleName"Arsc.arsc
    fi
}

rm -r "$genDir"/out
rm -r "$genDir"/output
rm -r "$genDir"/temp

mkdir "$genDir"/out
mkdir -p "$genDir"/output/raw
mkdir -p "$genDir"/temp/res/drawable-nodpi
mkdir -p "$genDir"/temp/res/layout

compileAndLink stringOne BOTH AndroidManifestFramework.xml res/values/string_one.xml
compileAndLink stringTwo BOTH AndroidManifestFramework.xml res/values/string_two.xml

compileAndLink dimenOne BOTH AndroidManifestFramework.xml res/values/dimen_one.xml
compileAndLink dimenTwo BOTH AndroidManifestFramework.xml res/values/dimen_two.xml

compileAndLink drawableMdpiWithoutFile BOTH_WITHOUT_FILE AndroidManifestFramework.xml res/values/drawable_one.xml res/drawable-mdpi/ic_delete.png
compileAndLink drawableMdpiWithFile APK AndroidManifestFramework.xml res/values/drawable_one.xml res/drawable-mdpi/ic_delete.png

compileAndLink layoutWithoutFile BOTH_WITHOUT_FILE AndroidManifestFramework.xml res/values/activity_list_item_id.xml res/layout/activity_list_item.xml
compileAndLink layoutWithFile APK AndroidManifestFramework.xml res/values/activity_list_item_id.xml res/layout/activity_list_item.xml

cp -f "$inDir"/res/layout/layout_one.xml "$genDir"/temp/res/layout/layout.xml
compileAndLink layoutOne ARSC AndroidManifestApp.xml "$genDir"/temp/res/layout/layout.xml res/values/layout_id.xml
cp -f "$genDir"/out/layoutOne/unzip/res/layout/layout.xml "$genDir"/output/raw/layoutOne.xml

cp -f "$inDir"/res/layout/layout_two.xml "$genDir"/temp/res/layout/layout.xml
compileAndLink layoutTwo ARSC AndroidManifestApp.xml "$genDir"/temp/res/layout/layout.xml res/values/layout_id.xml
cp -f "$genDir"/out/layoutTwo/unzip/res/layout/layout.xml "$genDir"/output/raw/layoutTwo.xml

drawableNoDpi="/res/drawable-nodpi"
inDirDrawableNoDpi="$inDir$drawableNoDpi"

cp -f "$inDirDrawableNoDpi"/nonAssetDrawableOne.xml "$genDir"/temp/res/drawable-nodpi/non_asset_drawable.xml
compileAndLink nonAssetDrawableOne ARSC AndroidManifestApp.xml "$genDir"/temp/res/drawable-nodpi/non_asset_drawable.xml res/values/non_asset_drawable_id.xml
cp -f "$genDir"/out/nonAssetDrawableOne/unzip/res/drawable-nodpi-v4/non_asset_drawable.xml "$genDir"/output/raw/nonAssetDrawableOne.xml

cp -f "$inDirDrawableNoDpi"/nonAssetDrawableTwo.xml "$genDir"/temp/res/drawable-nodpi/non_asset_drawable.xml
compileAndLink nonAssetDrawableTwo ARSC AndroidManifestApp.xml "$genDir"/temp/res/drawable-nodpi/non_asset_drawable.xml res/values/non_asset_drawable_id.xml
cp -f "$genDir"/out/nonAssetDrawableTwo/unzip/res/drawable-nodpi-v4/non_asset_drawable.xml "$genDir"/output/raw/nonAssetDrawableTwo.xml

cp -f "$inDirDrawableNoDpi"/nonAssetBitmapGreen.png "$genDir"/temp/res/drawable-nodpi/non_asset_bitmap.png
compileAndLink nonAssetBitmapGreen BOTH AndroidManifestApp.xml "$genDir"/temp/res/drawable-nodpi/non_asset_bitmap.png res/values/non_asset_bitmap_id.xml
cp -f "$genDir"/out/nonAssetBitmapGreen/unzip/res/drawable-nodpi-v4/non_asset_bitmap.png "$genDir"/output/raw/nonAssetBitmapGreen.png

cp -f "$inDirDrawableNoDpi"/nonAssetBitmapBlue.png "$genDir"/temp/res/drawable-nodpi/non_asset_bitmap.png
compileAndLink nonAssetBitmapBlue ARSC AndroidManifestApp.xml "$genDir"/temp/res/drawable-nodpi/non_asset_bitmap.png res/values/non_asset_bitmap_id.xml
cp -f "$genDir"/out/nonAssetBitmapBlue/unzip/res/drawable-nodpi-v4/non_asset_bitmap.png "$genDir"/output/raw/nonAssetBitmapBlue.png

$soong_zip -o "$genDir"/out.zip -C "$genDir"/output/ -D "$genDir"/output/
