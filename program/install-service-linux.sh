#!/bin/sh
# Installs CrowdWatch as a systemd service: it starts when the computer starts and restarts if it stops.
# Run as root:   sudo ./install-service-linux.sh        (settings in service.conf)
# Remove:        sudo ./install-service-linux.sh remove
# Logs:          journalctl -u crowdwatch   and   crowdwatch-data/logs/
set -e
cd "$(dirname "$0")"
DIR="$(pwd)"
UNIT=/etc/systemd/system/crowdwatch.service
if [ "$(id -u)" != "0" ]; then echo "Run with sudo"; exit 1; fi
if [ "$1" = "remove" ]; then
  systemctl disable --now crowdwatch 2>/dev/null || true
  rm -f "$UNIT"; systemctl daemon-reload; echo "CrowdWatch service removed"; exit 0
fi
RUNAS="${SUDO_USER:-root}"
JAVA="$(command -v java || true)"
[ -n "$JAVA" ] || { echo "Java 17 or newer is needed (e.g. apt install openjdk-17-jre)"; exit 1; }
cat > "$UNIT" <<UNITEOF
[Unit]
Description=CrowdWatch crowd monitoring server
After=network-online.target
Wants=network-online.target

[Service]
User=$RUNAS
WorkingDirectory=$DIR
EnvironmentFile=$DIR/service.conf
ExecStart=/bin/sh -c 'exec $JAVA -jar CrowdWatch.jar --server "\${VENUE:-airport}" "\${PORT:-8080}" \${CONTROL_KEY:+"\$CONTROL_KEY"} \${SCOUT_KEY:+"\$SCOUT_KEY"} \${CROWDWATCH_HTTPS:+--https}'
Restart=on-failure
RestartSec=10
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
UNITEOF
chmod 600 service.conf
chown "$RUNAS" service.conf
systemctl daemon-reload
systemctl enable --now crowdwatch
echo "CrowdWatch runs as a service now (user $RUNAS). Status: systemctl status crowdwatch"
