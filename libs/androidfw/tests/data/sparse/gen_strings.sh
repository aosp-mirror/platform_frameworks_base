#!/bin/bash

OUTPUT_default=res/values/strings.xml
OUTPUT_v26=res/values-v26/strings.xml

echo "<resources>" > $OUTPUT_default
echo "<resources>" > $OUTPUT_v26
for i in {0..999}
do
    echo "  <string name=\"foo_$i\">$i</string>" >> $OUTPUT_default
    if [ "$(($i % 3))" -eq "0" ]
    then
        echo "  <string name=\"foo_$i\">$(($i * 10))</string>" >> $OUTPUT_v26
    fi
done
echo "</resources>" >> $OUTPUT_default

echo "  <string name=\"only_v26\">only v26</string>" >> $OUTPUT_v26
echo "</resources>" >> $OUTPUT_v26

