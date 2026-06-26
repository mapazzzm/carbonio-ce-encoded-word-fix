#!/bin/bash
#
# carbonio-ce-encoded-word-fix — uninstaller / rollback
#
# Restores the most recent zm-common-*.jar.bak.* created by install.sh.
#
set -euo pipefail

JARDIR="${CARBONIO_JARDIR:-/opt/zextras/mailbox/jars}"
LIVE="$(ls "$JARDIR"/zm-common-*.jar 2>/dev/null | grep -v '\.bak\.' | head -1)"
[ -n "$LIVE" ] || { echo "ERROR: zm-common-*.jar not found in $JARDIR"; exit 1; }

BK="$(ls -t "$LIVE".bak.* 2>/dev/null | head -1)"
[ -n "$BK" ] || { echo "ERROR: no backup ($LIVE.bak.*) found — nothing to roll back"; exit 1; }

OWNER="$(stat -c '%U:%G' "$LIVE")"
cp "$BK" "$LIVE"
chown "$OWNER" "$LIVE"; chmod 755 "$LIVE"
echo ">> Restored $LIVE from $BK"
cat <<EOF

Restart the mailbox:

    systemctl restart carbonio-appserver.target
    sleep 20
    chown zextras:zextras /opt/zextras/data/tmp/nginx/client
EOF
