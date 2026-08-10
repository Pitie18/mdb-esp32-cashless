#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════════════════════
# VMflow Update Script
# Pulls latest code, applies new migrations, rebuilds & restarts services.
# Run from the Docker/ directory.
# ═══════════════════════════════════════════════════════════════════════════════

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── Parse flags ──────────────────────────────────────────────────────────────
SKIP_FRONTEND=false
REBUILD_ALL=false
PULL_ALL=false
for arg in "$@"; do
    case "$arg" in
        --no-frontend)   SKIP_FRONTEND=true ;;
        --rebuild-all)   REBUILD_ALL=true ;;
        --pull-all)      PULL_ALL=true ;;
        -h|--help)
            echo "Usage: bash update.sh [OPTIONS]"
            echo "  --no-frontend    Skip frontend rebuild"
            echo "  --rebuild-all    Force rebuild & restart all services (incl. broker)"
            echo "  --pull-all       Pull latest images for every service from registry"
            echo "                   (Supabase, kong, mosquitto, …; forwarder is built"
            echo "                   locally so it's skipped). Use after bumping image"
            echo "                   tags in docker-compose.yml, or to refresh same-tag images."
            exit 0 ;;
    esac
done

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

info()    { echo -e "  ${CYAN}ℹ${NC}  $1"; }
success() { echo -e "  ${GREEN}✔${NC}  $1"; }
warn()    { echo -e "  ${YELLOW}⚠${NC}  $1"; }
error()   { echo -e "  ${RED}✖${NC}  $1"; exit 1; }
step()    { echo; echo -e "${BOLD}═══ $1 ═══${NC}"; echo; }

# ═══════════════════════════════════════════════════════════════════════════════
# Pre-flight checks
# ═══════════════════════════════════════════════════════════════════════════════

[ -f .env ] || error ".env not found. Run setup.sh first."
docker compose ps --services >/dev/null 2>&1 || error "Docker stack is not running. Start it with: docker compose up -d"

# ─────────────────────────────────────────────────────────────
# Clear stray volumes/db/*.sql directories before `git pull`
# ─────────────────────────────────────────────────────────────
# Installs created between 2026-01-04 and 2026-08-09 have empty
# DIRECTORIES where webhooks.sql / jwt.sql / realtime.sql should be —
# Docker materialises a missing bind-mount source that way. They must go
# before the pull: git refuses to check a file out over a directory
# ("unable to create file ...: Is a directory") and would abort step 1.
# The matching database repair runs after the Environment Check, once the
# restored files are on disk.
for stray in volumes/db/*.sql; do
    [ -d "$stray" ] || continue
    if rmdir "$stray" 2>/dev/null; then
        success "Removed stray directory ${stray} (leftover from a broken first start)"
    else
        error "${stray} is a non-empty directory and would break git pull — remove it manually."
    fi
done

# ═══════════════════════════════════════════════════════════════════════════════
# Step 1: Pull latest code
# ═══════════════════════════════════════════════════════════════════════════════
step "1/4 — Pulling Latest Code"

cd "$SCRIPT_DIR/.."

BEFORE=$(git rev-parse HEAD)
git pull --ff-only
AFTER=$(git rev-parse HEAD)

if [ "$BEFORE" = "$AFTER" ]; then
    info "Already up to date (${BEFORE:0:7})"
else
    success "Updated: ${BEFORE:0:7} → ${AFTER:0:7}"
    echo
    git --no-pager log --oneline "${BEFORE}..${AFTER}"
fi

cd "$SCRIPT_DIR"

# ═══════════════════════════════════════════════════════════════════════════════
# Step 1.5: Check for new environment variables
# ═══════════════════════════════════════════════════════════════════════════════
step "Environment Check"

# Read env vars safely, handling multi-line quoted values (e.g. PEM keys)
_current_key=""
_current_value=""
_in_multiline=false

while IFS= read -r line || [[ -n "$line" ]]; do
    if $_in_multiline; then
        _current_value="$_current_value"$'\n'"$line"
        # Check if this line closes the quote
        if [[ "$line" =~ \"[[:space:]]*$ ]]; then
            _in_multiline=false
            _current_value="${_current_value%\"}"
            export "$_current_key=$_current_value"
        fi
        continue
    fi

    # Skip comments and blank lines
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ -z "${line// /}" ]] && continue
    # Skip lines that don't look like KEY=VALUE
    [[ "$line" =~ ^[[:alpha:]][[:alnum:]_]*= ]] || continue

    _current_key="${line%%=*}"
    _current_value="${line#*=}"

    # Check for multi-line quoted value (opening " without closing ")
    if [[ "$_current_value" =~ ^\" ]] && ! [[ "$_current_value" =~ \"$ ]]; then
        _in_multiline=true
        _current_value="${_current_value#\"}"
        continue
    fi

    # Strip surrounding quotes for single-line values
    _current_value="${_current_value#\"}"
    _current_value="${_current_value%\"}"
    export "$_current_key=$_current_value"
done < .env

# ─── VAPID keys (required for push notifications) ────────────────────────────
if [ -z "${VAPID_PUBLIC_KEY:-}" ] || [ -z "${VAPID_PRIVATE_KEY:-}" ]; then
    info "VAPID keys not found — generating for push notifications..."

    base64url_encode() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

    VAPID_PRIVKEY_PEM=$(openssl ecparam -name prime256v1 -genkey -noout 2>/dev/null)
    VAPID_PRIVATE_KEY=$(echo "$VAPID_PRIVKEY_PEM" | openssl ec -noout -text 2>/dev/null \
      | grep -A3 'priv:' | tail -n+2 | tr -d ' :\n' | xxd -r -p | base64url_encode)
    VAPID_PUBLIC_KEY=$(echo "$VAPID_PRIVKEY_PEM" | openssl ec -pubout -outform DER 2>/dev/null \
      | tail -c 65 | base64url_encode)

    # Extract domain from SITE_URL or SUPABASE_PUBLIC_URL for VAPID_SUBJECT
    DOMAIN_FOR_VAPID=$(echo "${SITE_URL:-${SUPABASE_PUBLIC_URL:-localhost}}" | sed 's|https\{0,1\}://||;s|:.*||;s|^app\.||')
    VAPID_SUBJECT="mailto:admin@${DOMAIN_FOR_VAPID}"

    # Append to Docker/.env
    cat >> .env << VAPIDEOF

##########
# Push Notifications (VAPID)
# Auto-generated by update.sh on $(date -u +"%Y-%m-%d %H:%M:%S UTC")
#########

VAPID_PUBLIC_KEY=${VAPID_PUBLIC_KEY}
VAPID_PRIVATE_KEY=${VAPID_PRIVATE_KEY}
VAPID_SUBJECT=${VAPID_SUBJECT}
VAPIDEOF

    success "VAPID keys generated and appended to .env"

    # Also update management-frontend/.env if it exists
    FRONTEND_ENV="$SCRIPT_DIR/../management-frontend/.env"
    if [ -f "$FRONTEND_ENV" ]; then
        if ! grep -q "VAPID_PUBLIC_KEY" "$FRONTEND_ENV"; then
            echo "VAPID_PUBLIC_KEY=${VAPID_PUBLIC_KEY}" >> "$FRONTEND_ENV"
            success "VAPID_PUBLIC_KEY added to management-frontend/.env"
        fi
    fi
else
    success "VAPID keys present"
fi

# ─── MQTT admin credentials (required for broker auth) ────────────────────────
if [ -z "${MQTT_ADMIN_PASS:-}" ]; then
    info "MQTT admin credentials not found — generating..."

    generate_random() { openssl rand -base64 "$1" 2>/dev/null | tr -d '\n'; }

    MQTT_ADMIN_PASS=$(generate_random 16)

    cat >> .env << MQTTEOF

##########
# MQTT Credentials
# Auto-generated by update.sh on $(date -u +"%Y-%m-%d %H:%M:%S UTC")
# Devices use hardcoded vmflow/vmflow. Admin is used by forwarder + edge functions.
#########

MQTT_ADMIN_USER=admin
MQTT_ADMIN_PASS=${MQTT_ADMIN_PASS}
MQTTEOF

    # Regenerate mosquitto passwd file
    MQTT_PASSWD_FILE="${SCRIPT_DIR}/mqtt/config/passwd"
    rm -f "$MQTT_PASSWD_FILE"
    touch "$MQTT_PASSWD_FILE"
    docker run --rm -v "${SCRIPT_DIR}/mqtt/config:/mosquitto/config" eclipse-mosquitto:2.1.2-alpine \
      sh -c "
        mosquitto_passwd -b /mosquitto/config/passwd vmflow 'vmflow' && \
        mosquitto_passwd -b /mosquitto/config/passwd admin '${MQTT_ADMIN_PASS}'
      "
    chmod 600 "$MQTT_PASSWD_FILE"

    success "MQTT admin credentials generated, passwd file updated, and appended to .env"
else
    success "MQTT admin credentials present"
fi

# ─── FCM_SERVICE_ACCOUNT_JSON (informational only) ────────────────────────────
if [ -z "${FCM_SERVICE_ACCOUNT_JSON:-}" ]; then
    info "FCM_SERVICE_ACCOUNT_JSON not set — Android push notifications disabled"
else
    success "FCM_SERVICE_ACCOUNT_JSON is configured"
fi

# ─── APNs (informational only) ──────────────────────────────────────────────
if [ -z "${APNS_KEY_ID:-}" ]; then
    info "APNS_KEY_ID not set — iOS push notifications disabled"
else
    success "APNs configured (Key ID: ${APNS_KEY_ID}, Team: ${APNS_TEAM_ID:-?}, Topic: ${APNS_TOPIC:-?})"
fi

# ─── GITHUB_FIRMWARE_REPO (informational only) ───────────────────────────────
if [ -z "${GITHUB_FIRMWARE_REPO:-}" ]; then
    info "GITHUB_FIRMWARE_REPO not set — GitHub release imports disabled on firmware page"
else
    success "GITHUB_FIRMWARE_REPO: ${GITHUB_FIRMWARE_REPO}"
fi

# ─── SITE_URL (auth mails + printed poster QR codes) ─────────────────────────
# SITE_URL has always fed GoTrue's auth mails; since the printable machine
# posters it is also handed to the frontend as NUXT_PUBLIC_SITE_URL and encoded
# into every QR code that gets printed on paper. A wrong value here is not a
# broken page you fix by editing — it is a stack of signs nobody can scan.
#
# We never guess a value non-interactively: the same variable builds password
# reset links, so a wrong guess would silently break account recovery.
if [ -z "${SITE_URL:-}" ]; then
    warn "SITE_URL is not set in .env"
    warn "It builds password-reset links AND the QR codes on printed machine signs."
    # Derive a suggestion from the Supabase host, matching setup.sh's
    # supabase.<domain> / app.<domain> convention.
    SITE_URL_SUGGESTION=""
    if [ -n "${SUPABASE_PUBLIC_URL:-}" ]; then
        _derived=$(echo "$SUPABASE_PUBLIC_URL" | sed 's|^\(https\{0,1\}://\)supabase\.|\1app.|')
        # Only suggest when the substitution actually fired. Otherwise the
        # "suggestion" would just be the Supabase URL itself, which is exactly
        # the wrong answer offered as the default.
        [ "$_derived" != "$SUPABASE_PUBLIC_URL" ] && SITE_URL_SUGGESTION="$_derived"
    fi
    if [ -t 0 ]; then
        printf "  Public frontend URL%s: " "${SITE_URL_SUGGESTION:+ [$SITE_URL_SUGGESTION]}"
        read -r SITE_URL_INPUT
        SITE_URL="${SITE_URL_INPUT:-$SITE_URL_SUGGESTION}"
        if [ -n "$SITE_URL" ]; then
            cat >> .env << SITEURLEOF

##########
# Public frontend URL
# Added by update.sh on $(date -u +"%Y-%m-%d %H:%M:%S UTC")
# Used for GoTrue auth mails and for the QR codes on printed machine signs.
#########

SITE_URL=${SITE_URL}
SITEURLEOF
            export SITE_URL
            success "SITE_URL set to ${SITE_URL} and appended to .env"
        else
            warn "No value entered — printed QR codes will fall back to the browser URL"
        fi
    else
        warn "Running non-interactively; not guessing a value."
        [ -n "$SITE_URL_SUGGESTION" ] && warn "Add it manually, e.g.: SITE_URL=${SITE_URL_SUGGESTION}"
    fi
else
    case "$SITE_URL" in
        *localhost*|*127.0.0.1*|http://10.*|http://192.168.*|http://172.1[6-9].*|http://172.2[0-9].*|http://172.3[01].*|*.local*)
            warn "SITE_URL is ${SITE_URL} — that address is not reachable from outside."
            warn "QR codes printed on machine signs would not work on a customer's phone."
            ;;
        *)
            success "SITE_URL: ${SITE_URL}"
            ;;
    esac
fi

# ─── ENV_NAME / ENV_COLOR (informational only) ───────────────────────────────
if ! grep -q "^ENV_NAME=" .env; then
    info "ENV_NAME not set — adding commented section to .env"
    cat >> .env << ENVNAMEEOF

##########
# Frontend Environment Indicator
# Auto-generated by update.sh on $(date -u +"%Y-%m-%d %H:%M:%S UTC")
# Set ENV_NAME to "dev" / "test" / "staging" to show a colored banner.
# Leave empty / "prod" / "production" for production deployments.
# Color: red, amber, orange, purple, blue (default: amber)
#########

ENV_NAME=
ENV_COLOR=
ENVNAMEEOF
    success "ENV_NAME section appended to .env"
else
    success "ENV_NAME already configured in .env"
fi

# ─────────────────────────────────────────────────────────────
# Configure DB settings consumed by SECURITY DEFINER functions
# (e.g. public.dispatch_low_stock_pushes for low-stock cron).
# These are idempotent — re-running overwrites prior values.
# ─────────────────────────────────────────────────────────────
# Read SERVICE_ROLE_KEY directly without `source ./.env` — the upstream
# Supabase defaults ship unquoted values with spaces (e.g.
# STUDIO_DEFAULT_ORGANIZATION=Default Organization) which break bash
# source. docker-compose tolerates them via its own parser; we don't.
SERVICE_ROLE_KEY=$(grep -E '^SERVICE_ROLE_KEY=' .env 2>/dev/null | head -1 | cut -d= -f2-)

if [ -n "${SERVICE_ROLE_KEY:-}" ]; then
    INTERNAL_SUPABASE_URL="http://kong:8000"
    # ALTER DATABASE on custom GUCs requires superuser; `postgres` role is
    # not superuser in self-hosted Supabase. `supabase_admin` is, and is
    # reachable via 127.0.0.1 trust (per the image's pg_hba.conf).
    docker compose exec -T db psql -U supabase_admin -h 127.0.0.1 -d postgres >/dev/null <<SQL
ALTER DATABASE postgres SET app.settings.supabase_url = '${INTERNAL_SUPABASE_URL}';
ALTER DATABASE postgres SET app.settings.service_role_key = '${SERVICE_ROLE_KEY}';
SQL
    success "Configured app.settings.supabase_url + app.settings.service_role_key"
else
    warn "SERVICE_ROLE_KEY not found in .env — skipping app.settings configuration"
    warn "Low-stock daily push will not fire until SERVICE_ROLE_KEY is set"
fi

# ─────────────────────────────────────────────────────────────
# Repair installs whose Postgres init run aborted
# ─────────────────────────────────────────────────────────────
# Between 2026-01-04 (df239b3 deleted volumes/db/{webhooks,jwt,realtime,
# _supabase,logs,pooler}.sql) and 2026-08-09 the compose file still
# mounted them. Docker materialises a missing bind-mount source as an
# empty DIRECTORY; the image's migrate.sh runs
# `psql -v ON_ERROR_STOP=1 -f init-scripts/*.sql` under `set -e`, so
# 98-webhooks.sql died with "Is a directory" and 99-jwt.sql /
# 99-roles.sql never ran. The container restarted, found PGDATA
# initialised, skipped init for good and came up looking healthy — with
# authenticator, supabase_auth_admin and supabase_storage_admin left
# without a password. Init scripts never re-run, so restoring the files
# alone does not heal such an install; replay them here. Every check is a
# no-op on a healthy database. (The stray directories those installs also
# carry are cleared in the pre-flight section, before `git pull`.)

if docker compose exec -T db pg_isready -U postgres >/dev/null 2>&1; then
    db_psql() { docker compose exec -T db psql -v ON_ERROR_STOP=1 -U postgres -d postgres "$@"; }

    if [ "$(db_psql -Atc "SELECT count(*) FROM pg_namespace WHERE nspname = 'supabase_functions'")" = "0" ]; then
        info "Schema supabase_functions missing — replaying webhooks.sql..."
        if db_psql -q < volumes/db/webhooks.sql >/dev/null; then
            success "webhooks.sql applied (supabase_functions + supabase_functions_admin)"
        else
            warn "webhooks.sql failed — check: docker compose logs db"
        fi
    fi

    # `|| true` keeps set -e from aborting the whole update if this one
    # query hiccups — a failed probe must not stop the migration step.
    ROLES_NO_PASSWORD=$(db_psql -Atc \
        "SELECT coalesce(string_agg(rolname, ', ' ORDER BY rolname), '')
           FROM pg_authid
          WHERE rolname IN ('authenticator','pgbouncer','supabase_admin','supabase_auth_admin','supabase_functions_admin','supabase_storage_admin')
            AND rolpassword IS NULL" || true)
    if [ -n "$ROLES_NO_PASSWORD" ]; then
        warn "Roles without a password: ${ROLES_NO_PASSWORD} — replaying roles.sql..."
        # roles.sql reads $POSTGRES_PASSWORD via psql backticks, which run
        # inside the db container where compose sets it.
        if db_psql -q < volumes/db/roles.sql >/dev/null; then
            success "roles.sql applied — rest/auth/storage can authenticate again"
        else
            error "roles.sql failed. Check: docker compose logs db"
        fi
    fi

    if [ -z "$(db_psql -Atc "SELECT current_setting('app.settings.jwt_secret', true)")" ]; then
        info "app.settings.jwt_secret unset — replaying jwt.sql..."
        if db_psql -q < volumes/db/jwt.sql >/dev/null; then
            success "jwt.sql applied"
        else
            warn "jwt.sql failed — check: docker compose logs db"
        fi
    fi

    if [ "$(db_psql -Atc "SELECT count(*) FROM pg_namespace WHERE nspname = '_realtime'")" = "0" ]; then
        info "Schema _realtime missing — replaying realtime.sql..."
        db_psql -q < volumes/db/realtime.sql >/dev/null \
            && success "realtime.sql applied" \
            || warn "realtime.sql failed — check: docker compose logs db"
    fi

    unset -f db_psql
else
    warn "Database not reachable — skipping init-script repair check"
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Step 2: Apply new database migrations
# ═══════════════════════════════════════════════════════════════════════════════
step "2/4 — Applying New Migrations"

MIGRATION_DIR="supabase/migrations"
APPLIED=0
SKIPPED=0
FAILED=0

# Probe the tracking table and fetch the applied-set in a single round trip.
# Each `docker compose exec` costs hundreds of ms of pure Docker overhead
# (compose project parse, daemon call, exec session setup), so we collapse
# the no-op path to exactly one exec.
#
# The query emits a sentinel header line we can branch on:
#   __VMFLOW_HAS_TRACKING__|0   →  table does not exist (fall back to apply-all)
#   __VMFLOW_HAS_TRACKING__|1   →  table exists; remaining lines are names
MIGRATION_STATE=$(docker compose exec -T db psql -U postgres -d postgres -tAc "
SELECT '__VMFLOW_HAS_TRACKING__|' ||
  CASE WHEN EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema='public' AND table_name='_migrations'
  ) THEN '1' ELSE '0' END;
SELECT name FROM public._migrations;
" 2>/dev/null | tr -d '\r')

HAS_TRACKING=0
declare -A APPLIED_MAP=()
while IFS= read -r line; do
    line="${line#"${line%%[![:space:]]*}"}"  # trim leading ws
    line="${line%"${line##*[![:space:]]}"}"  # trim trailing ws
    [ -z "$line" ] && continue
    if [[ "$line" == __VMFLOW_HAS_TRACKING__\|* ]]; then
        HAS_TRACKING="${line##*|}"
        continue
    fi
    APPLIED_MAP["$line"]=1
done <<< "$MIGRATION_STATE"

if [ "$HAS_TRACKING" = "1" ]; then
    # Use tracking table: only apply migrations not yet recorded.
    # Lookup is pure-bash (associative array) — no subprocess per file.
    for f in "$MIGRATION_DIR"/*.sql; do
        fname="${f##*/}"  # bash builtin; avoids forking basename per iter

        if [ -n "${APPLIED_MAP[$fname]:-}" ]; then
            SKIPPED=$((SKIPPED + 1))
            continue
        fi

        echo -ne "  Applying ${fname}... "
        if docker compose exec -T db psql -U postgres -d postgres < "$f" > /tmp/migration_out.txt 2>&1; then
            # Record as applied
            docker compose exec -T db psql -U postgres -d postgres -c \
                "INSERT INTO public._migrations (name) VALUES ('${fname}') ON CONFLICT DO NOTHING" >/dev/null 2>&1
            echo -e "${GREEN}✔${NC}"
            APPLIED=$((APPLIED + 1))
        else
            echo -e "${RED}✖${NC}"
            echo -e "  ${DIM}$(cat /tmp/migration_out.txt | head -5)${NC}"
            FAILED=$((FAILED + 1))
        fi
    done
else
    # No tracking table yet — fall back to apply-all (setup.sh style)
    warn "Migration tracking table not found. Applying all migrations (errors = already applied)."
    echo
    for f in "$MIGRATION_DIR"/*.sql; do
        fname="${f##*/}"
        if docker compose exec -T db psql -U postgres -d postgres < "$f" > /dev/null 2>&1; then
            success "$fname"
            APPLIED=$((APPLIED + 1))
        else
            echo -e "  ${YELLOW}⊘${NC} ${fname} ${DIM}(already applied or had errors)${NC}"
            SKIPPED=$((SKIPPED + 1))
        fi
    done
fi

echo
if [ "$FAILED" -gt 0 ]; then
    warn "Migrations: ${APPLIED} applied, ${SKIPPED} skipped, ${RED}${FAILED} failed${NC}"
else
    success "Migrations: ${APPLIED} applied, ${SKIPPED} skipped"
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Step 3: Rebuild & restart services
# ═══════════════════════════════════════════════════════════════════════════════
step "3/4 — Rebuilding & Restarting Services"

# ── Frontend ─────────────────────────────────────────────────────────────────
if [ "$SKIP_FRONTEND" = true ]; then
    info "Frontend update skipped (--no-frontend)"
else
    info "Pulling latest frontend image..."
    if docker compose pull frontend 2>/dev/null; then
        success "Frontend image pulled"
    else
        warn "Failed to pull frontend image — falling back to local build..."
        docker compose build --no-cache frontend
        success "Frontend image built locally"
    fi
fi

# ── Forwarder ────────────────────────────────────────────────────────────────
info "Rebuilding forwarder..."
docker compose build forwarder
success "Forwarder image built"

# ── MCP bridge (OpenClaw OpenAPI→MCP) ────────────────────────────────────────
# Only rebuild when its sources changed (or --rebuild-all). On the first rollout
# the new Docker/mcp-bridge/ files appear in the diff, so it builds here; the
# reconcile `up -d` below then starts it. Without this rebuild, later server.py
# changes would silently never reach the running container.
if [ "$REBUILD_ALL" = true ] || { [ "$BEFORE" != "$AFTER" ] && git -C "$SCRIPT_DIR/.." diff --name-only "$BEFORE" "$AFTER" | grep -q "^Docker/mcp-bridge/"; }; then
    info "Rebuilding mcp-bridge..."
    docker compose build mcp-bridge
    success "mcp-bridge image built"
else
    info "mcp-bridge sources unchanged — skipping rebuild"
fi

# ── Pull all images from registry (opt-in) ──────────────────────────────────
# Off by default because it adds 5-10s of registry round-trips per service
# and most updates don't touch the Supabase stack. Use `--pull-all` when:
#   • You bumped image tags in docker-compose.yml (e.g. supabase/postgres-meta
#     v0.91.0 → v0.92.0). The lazy pull during the reconcile below would also
#     catch it, but explicit is nicer for visibility.
#   • You want to refresh same-tag images (rare; Supabase doesn't usually
#     republish stable tags, but security patches sometimes do).
# --ignore-buildable skips the locally-built forwarder image (already done).
if [ "$PULL_ALL" = true ]; then
    info "Pulling latest images for every service..."
    docker compose pull --ignore-buildable
    success "All images pulled"
fi

# ── Determine which services to restart ──────────────────────────────────────
RESTART_SERVICES="functions forwarder"

if [ "$SKIP_FRONTEND" = false ]; then
    RESTART_SERVICES="$RESTART_SERVICES frontend"
fi

# Only restart broker if its config changed (or --rebuild-all)
if [ "$REBUILD_ALL" = true ]; then
    RESTART_SERVICES="$RESTART_SERVICES broker"
    info "Forcing full rebuild (--rebuild-all)"
elif [ "$BEFORE" != "$AFTER" ] && git -C "$SCRIPT_DIR/.." diff --name-only "$BEFORE" "$AFTER" | grep -q "^Docker/mqtt/config/"; then
    RESTART_SERVICES="$RESTART_SERVICES broker"
    info "Broker config changed — including broker in restart"
else
    info "Broker config unchanged — skipping broker restart"
fi

info "Restarting: ${RESTART_SERVICES}..."
docker compose up -d --no-deps --force-recreate $RESTART_SERVICES

# Kong caches upstream DNS — if functions was recreated (new container IP),
# Kong must be restarted to re-resolve the DNS, otherwise it returns 502.
if echo "$RESTART_SERVICES" | grep -q "functions"; then
    info "Restarting kong (API gateway) to pick up new functions container..."
    docker compose restart kong
fi

# ── Reconcile other services with compose config ────────────────────────────
# The targeted --force-recreate above only touches services we just rebuilt.
# Compose changes to other services (init:, healthcheck:, environment,
# depends_on, image version pin) need a generic `up -d` to be picked up.
# Without this, prod silently drifts from the committed docker-compose.yml.
# Seen 2026-05-25: an `init: true` zombie fix on `meta` landed in main on
# 2026-05-11 but never reached the running container until manually
# force-recreated 14 days later — load climbed to 26 on 4 cores from
# 3500 unreaped healthcheck zombies.
info "Reconciling other services with compose config..."
docker compose up -d --remove-orphans
success "Compose state reconciled"

# Kong loads its declarative config (services/routes) only at container start.
# If kong.yml changed in this update (e.g. a new route such as the /mcp bridge),
# restart kong AFTER the reconcile above has created any new upstream service it
# points at — otherwise kong caches a failed DNS lookup and 502s the new route.
if [ "$BEFORE" != "$AFTER" ] && git -C "$SCRIPT_DIR/.." diff --name-only "$BEFORE" "$AFTER" | grep -q "^Docker/volumes/api/kong.yml"; then
    info "Kong config changed — restarting kong to load new routes..."
    docker compose restart kong
    success "Kong reloaded"
fi

success "Services restarted"

# ═══════════════════════════════════════════════════════════════════════════════
# Step 4: Health check
# ═══════════════════════════════════════════════════════════════════════════════
step "4/4 — Health Check"

sleep 3

# Check frontend
if docker compose ps frontend | grep -q "Up\|running"; then
    success "Frontend is running"
else
    warn "Frontend may not be healthy — check: docker compose logs frontend"
fi

# Check functions
if docker compose ps functions | grep -q "Up\|running"; then
    success "Edge functions are running"
else
    warn "Edge functions may not be healthy — check: docker compose logs functions"
fi

echo
docker compose ps
echo
success "Update complete!"
