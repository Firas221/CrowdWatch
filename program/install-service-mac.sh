#!/bin/sh
# Installs CrowdWatch as a launchd service on macOS: it starts when the computer starts.
# Run:     sudo ./install-service-mac.sh          (settings in service.conf)
# Remove:  sudo ./install-service-mac.sh remove
set -e
cd "$(dirname "$0")"
DIR="$(pwd)"
PLIST=/Library/LaunchDaemons/org.crowdwatch.server.plist
if [ "$(id -u)" != "0" ]; then echo "Run with sudo"; exit 1; fi
if [ "$1" = "remove" ]; then launchctl unload "$PLIST" 2>/dev/null || true; rm -f "$PLIST"; echo "CrowdWatch service removed"; exit 0; fi
. ./service.conf
JAVA="$(/usr/libexec/java_home -v 17+ 2>/dev/null)/bin/java"
[ -x "$JAVA" ] || JAVA="$(command -v java)"
ARGS="<string>$JAVA</string><string>-jar</string><string>CrowdWatch.jar</string><string>--server</string><string>${VENUE:-airport}</string><string>${PORT:-8080}</string>"
[ -n "$CONTROL_KEY" ] && ARGS="$ARGS<string>$CONTROL_KEY</string>"
[ -n "$SCOUT_KEY" ] && ARGS="$ARGS<string>$SCOUT_KEY</string>"
[ -n "$CROWDWATCH_HTTPS" ] && ARGS="$ARGS<string>--https</string>"
cat > "$PLIST" <<PLISTEOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>org.crowdwatch.server</string>
<key>WorkingDirectory</key><string>$DIR</string>
<key>ProgramArguments</key><array>$ARGS</array>
<key>UserName</key><string>${SUDO_USER:-root}</string>
<key>RunAtLoad</key><true/>
<key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
</dict></plist>
PLISTEOF
chmod 600 "$PLIST" service.conf
launchctl load -w "$PLIST"
echo "CrowdWatch runs as a service now."
