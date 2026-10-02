#!/usr/bin/env bash
# Hermes cron only runs .py/.sh; the probe itself is necessity_impact.cljk (kotoba).
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${KBB:-/opt/homebrew/bin/kbb}" --backend sci "$here/necessity_impact.cljk"
