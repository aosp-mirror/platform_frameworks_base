#!/bin/bash

OUTPUT_default=res/values/strings.xml
OUTPUT_land=res/values-land/strings.xml

echo "<resources>" > $OUTPUT_default
echo "<resources>" > $OUTPUT_land
for i in {0..999}
do
    echo "  <string name=\"foo_$i\">$i</string>" >> $OUTPUT_default
    if [ "$(($i % 3))" -eq "0" ]
    then
        echo "  <string name=\"foo_$i\">$(($i * 10))</string>" >> $OUTPUT_land
    fi
done
echo "</resources>" >> $OUTPUT_default

echo "  <string name=\"only_land\">only land</string>" >> $OUTPUT_land
echo "</resources>" >> $OUTPUT_land

