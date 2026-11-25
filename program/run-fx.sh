#!/bin/sh
# Starts the JavaFX console (the modern interface). Usage: ./run-fx.sh [venue code or .venue file]
cd "$(dirname "$0")"
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) FX=lib/javafx/mac-aarch64 ;;
  Darwin-*)     FX=lib/javafx/mac ;;
  *)            FX=lib/javafx/linux ;;
esac
java --module-path "$FX" --add-modules javafx.controls -cp "CrowdWatch.jar:lib/*" crowdwatch.fx.FxLauncher "$@"
