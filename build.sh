#!/bin/sh
# Builds Facet.apk with the plain Android command-line tools (no Gradle).
# Ubuntu: sudo apt install aapt android-sdk-platform-23 dalvik-exchange apksigner zipalign openjdk-17-jdk
set -e
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk
JAR=$SDK/platforms/android-23/android.jar
BT=$SDK/build-tools/$(ls $SDK/build-tools | tail -1)
rm -rf build && mkdir -p build/classes build/gen
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$JAR"
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$JAR" -d build/classes $(find src build/gen -name '*.java') 2>build/javac.log || { grep -v -i warning build/javac.log; exit 1; }
"$BT/dx" --dex --output=build/classes.dex build/classes
aapt package -f -M AndroidManifest.xml -S res -A assets -I "$JAR" -F build/unsigned.apk
(cd build && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f facet.keystore ] || keytool -genkeypair -keystore facet.keystore -storepass facetpass -keypass facetpass \
    -alias facet -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Facet" >/dev/null 2>&1
apksigner sign --ks facet.keystore --ks-pass pass:facetpass --out Facet.apk build/aligned.apk
apksigner verify Facet.apk && ls -la Facet.apk
