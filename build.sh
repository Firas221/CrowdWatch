#!/bin/sh
# Builds program/CrowdWatch.jar from source/src and runs the automatic tests.
# Needs a JDK 17 or newer (javac and jar on the PATH).
#   ./build.sh              build and test
#   ./build.sh --no-tests   build only
set -e
cd "$(dirname "$0")"
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) FX=mac-aarch64 ;;
  Darwin-*)     FX=mac ;;
  *)            FX=linux ;;
esac
OUT=build/classes
rm -rf "$OUT"
mkdir -p "$OUT"
echo "Compiling..."
find source/src -name '*.java' > build/sources.txt
javac --release 17 -encoding UTF-8 -Xlint:-options -cp "source/lib/*:program/lib/javafx/$FX/*" -d "$OUT" @build/sources.txt
# resources: venue files and drawings, web console, JavaFX style sheet
mkdir -p "$OUT/crowdwatch/net/web" "$OUT/crowdwatch/fx"
cp -R source/src/crowdwatch/venues "$OUT/crowdwatch/"
cp source/src/crowdwatch/net/web/* "$OUT/crowdwatch/net/web/"
cp source/src/crowdwatch/fx/*.css "$OUT/crowdwatch/fx/"
printf 'Manifest-Version: 1.0\nMain-Class: crowdwatch.gui.App\nClass-Path: lib/derby-10.16.1.1.jar lib/derbyshared-10.16.1.1.jar lib/derbytools-10.16.1.1.jar\n' > build/manifest.txt
jar cfm program/CrowdWatch.jar build/manifest.txt -C "$OUT" crowdwatch
echo "Built program/CrowdWatch.jar"
if [ "$1" != "--no-tests" ]; then
  echo "Running the tests..."
  cd program
  java -cp "CrowdWatch.jar:lib/*" crowdwatch.test.TestRunner > ../build/test-output.txt 2>&1 || { tail -40 ../build/test-output.txt; echo "TESTS FAILED (full output in build/test-output.txt)"; exit 1; }
  tail -1 ../build/test-output.txt
fi
