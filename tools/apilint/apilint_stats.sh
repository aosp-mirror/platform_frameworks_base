#!/bin/bash
API=28
while [ $API -gt 14 ]; do
    echo "# Changes in API $((API))"
    python tools/apilint/apilint.py --show-stats ../../prebuilts/sdk/$((API))/public/api/android.txt ../../prebuilts/sdk/$((API-1))/public/api/android.txt
    let API=API-1
done
