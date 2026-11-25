#!/usr/bin/env bash
# Session-start check: tells Claude (and so the person) when a skill this project was built
# from has a newer version in Design-Constants.
#
# The project lists what it built, one skill per line, in .design-constants at its root:
#     update-check 1.0.0
#     floating-button 1.0.0
# This compares those with skill-versions.txt: the copy on GitHub when it can be reached in
# a few seconds, else the copy that came with the plugin. It prints nothing when everything
# is current, when the project has no .design-constants, or when anything goes wrong, so it
# never gets in the way of a session.

set -u
root="${CLAUDE_PROJECT_DIR:-$PWD}"
used="$root/.design-constants"
[ -f "$used" ] || exit 0

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bundled="$here/../skill-versions.txt"
url="https://raw.githubusercontent.com/SharpTalons/Design-Constants/main/plugins/design-constants/skill-versions.txt"

latest="$(curl -fsSL --max-time 5 "$url" 2>/dev/null)"
case "$latest" in *update-check*) ;; *) latest="$(cat "$bundled" 2>/dev/null)" ;; esac
[ -n "$latest" ] || exit 0

# True when version $1 is lower than $2 (MAJOR.MINOR.PATCH, numerically).
older() {
    [ "$1" = "$2" ] && return 1
    [ "$(printf '%s\n%s\n' "$1" "$2" | sort -t. -k1,1n -k2,2n -k3,3n | head -n1)" = "$1" ]
}

out=""
while read -r skill have _; do
    case "$skill" in ''|\#*) continue ;; esac
    line="$(printf '%s\n' "$latest" | grep -E "^$skill " | head -n1)"
    [ -n "$line" ] || continue
    now="$(printf '%s\n' "$line" | awk '{print $2}')"
    what="$(printf '%s\n' "$line" | cut -d' ' -f3-)"
    if older "$have" "$now"; then
        out="$out
- $skill: this project has $have, the newest is $now. $what"
    fi
done < "$used"

[ -n "$out" ] || exit 0
cat <<MSG
Design-Constants: newer versions of features this project was built from are available.$out
Tell the person at the start of your first reply, in plain words, and ask whether to update.
To update one, follow that skill's SKILL.md and references/spec.md against the current code,
then change its line in .design-constants to the new version.
MSG
