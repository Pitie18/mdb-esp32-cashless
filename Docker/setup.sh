#!/usr/bin/env bash
set -euo pipefail

# ─── Colors ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

# ─── Utility functions ────────────────────────────────────────────────────────
info()    { echo -e "${BLUE}[INFO]${NC} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }
success() { echo -e "${GREEN}  ✔${NC} $*"; }
step()    { echo -e "\n${BOLD}── $* ──${NC}\n"; }

prompt_with_default() {
    local prompt="$1"
    local default="$2"
    local result
    if [ -n "$default" ]; then
        read -rp "$(echo -e "${CYAN}${prompt}${NC} [${default}]: ")" result
        echo "${result:-$default}"
    else
        read -rp "$(echo -e "${CYAN}${prompt}${NC}: ")" result
        echo "$result"
    fi
}

prompt_secret() {
    local prompt="$1"
    local default="${2:-}"
    local result
    if [ -n "$default" ]; then
        read -rsp "$(echo -e "${CYAN}${prompt}${NC} [${default}]: ")" result
        echo
        echo "${result:-$default}"
    else
        read -rsp "$(echo -e "${CYAN}${prompt}${NC}: ")" result
        echo
        echo "$result"
    fi
}

prompt_yes_no() {
    local prompt="$1"
    local default="${2:-n}"
    local hint
    if [ "$default" = "y" ]; then hint="[Y/n]"; else hint="[y/N]"; fi
    local result
    read -rp "$(echo -e "${CYAN}${prompt}${NC} ${hint}: ")" result
    result="${result:-$default}"
    [[ "$result" =~ ^[Yy] ]]
}

generate_random() { openssl rand -base64 "$1" 2>/dev/null | tr -d '\n'; }
generate_hex()    { openssl rand -hex "$1" 2>/dev/null | tr -d '\n'; }

# ─── JWT generation (pure openssl) ───────────────────────────────────────────
base64url_encode() {
    openssl base64 -A | tr '+/' '-_' | tr -d '='
}

generate_jwt() {
    local secret="$1"
    local role="$2"
    local now
    now=$(date +%s)
    local exp=$((now + 10 * 365 * 24 * 60 * 60))

    local header='{"alg":"HS256","typ":"JWT"}'
    local payload="{\"role\":\"${role}\",\"iss\":\"supabase\",\"iat\":${now},\"exp\":${exp}}"

    local header_b64 payload_b64 signature
    header_b64=$(printf '%s' "$header" | base64url_encode)
    payload_b64=$(printf '%s' "$payload" | base64url_encode)
    signature=$(printf '%s' "${header_b64}.${payload_b64}" | openssl dgst -sha256 -hmac "$secret" -binary | base64url_encode)

    echo "${header_b64}.${payload_b64}.${signature}"
}

# ─── Cleanup on interrupt ────────────────────────────────────────────────────
cleanup() {
    echo
    warn "Setup interrupted. If .env was partially written, remove it and re-run."
    exit 1
}
trap cleanup INT TERM

# ─── Change to script directory ──────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ═══════════════════════════════════════════════════════════════════════════════
echo -e "\n${BOLD}╔══════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║         VMflow Production Setup              ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════╝${NC}\n"

echo -e "${DIM}This script will:${NC}"
echo -e "${DIM}  1. Configure environment variables (.env)${NC}"
echo -e "${DIM}  2. Generate all cryptographic secrets & JWT keys${NC}"
echo -e "${DIM}  3. Start the Docker stack${NC}"
echo -e "${DIM}  4. Apply database migrations${NC}"
echo -e "${DIM}  5. Print connection details & next steps${NC}"
echo

# ═══════════════════════════════════════════════════════════════════════════════
# Step 1: Prerequisites
# ═══════════════════════════════════════════════════════════════════════════════
step "1/5 — Checking Prerequisites"

command -v docker >/dev/null 2>&1 || error "Docker is not installed. See https://docs.docker.com/engine/install/"
docker compose version >/dev/null 2>&1 || error "Docker Compose v2 is not installed."
command -v openssl >/dev/null 2>&1 || error "OpenSSL is not installed."

# Test that openssl HMAC signing works
TEST_SIG=$(printf 'test' | openssl dgst -sha256 -hmac "key" -binary 2>/dev/null | base64url_encode 2>/dev/null) || true
if [ -z "$TEST_SIG" ]; then
    error "openssl dgst -sha256 -hmac is not supported on this system."
fi

success "All prerequisites met"

# ─── Existing .env detection ────────────────────────────────────────────────
if [ -f .env ]; then
    echo
    warn "An existing .env file was found."
    if prompt_yes_no "Back up existing .env and create a new one?" "y"; then
        BACKUP=".env.backup.$(date +%Y%m%d_%H%M%S)"
        cp .env "$BACKUP"
        success "Backed up to ${BACKUP}"
    else
        info "Keeping existing .env — skipping to stack startup."

        # Jump directly to starting the stack
        step "3/5 — Starting Docker Stack"
        info "Starting all services..."
        docker compose up -d

        step "4/5 — Applying Database Migrations"
        info "Waiting for database to be ready..."
        DB_READY=false
        for i in $(seq 1 30); do
            if docker compose exec -T db pg_isready -U postgres >/dev/null 2>&1; then
                success "Database is ready"
                DB_READY=true
                break
            fi
            sleep 2
        done

        if [ "$DB_READY" = false ]; then
            error "Database did not become ready within 60 seconds. Check logs: docker compose logs db"
        fi

        # ─────────────────────────────────────────────────────────────
        # Configure DB settings consumed by SECURITY DEFINER functions
        # (e.g. public.dispatch_low_stock_pushes for low-stock cron).
        # Read SERVICE_ROLE_KEY directly without `source ./.env` — the
        # upstream Supabase defaults ship unquoted values with spaces
        # (STUDIO_DEFAULT_ORGANIZATION=Default Organization) that break
        # bash source even though docker-compose tolerates them.
        # ─────────────────────────────────────────────────────────────
        SERVICE_ROLE_KEY=$(grep -E '^SERVICE_ROLE_KEY=' .env 2>/dev/null | head -1 | cut -d= -f2-)
        if [ -n "${SERVICE_ROLE_KEY:-}" ]; then
            # ALTER DATABASE on custom GUCs requires superuser; `postgres` role
            # is not superuser in self-hosted Supabase. `supabase_admin` is,
            # and is reachable via 127.0.0.1 trust (per the image's pg_hba.conf).
            docker compose exec -T db psql -U supabase_admin -h 127.0.0.1 -d postgres >/dev/null <<SQL
ALTER DATABASE postgres SET app.settings.supabase_url = 'http://kong:8000';
ALTER DATABASE postgres SET app.settings.service_role_key = '${SERVICE_ROLE_KEY}';
SQL
            success "Configured app.settings.* for low-stock push dispatcher"
        fi

        MIGRATION_DIR="supabase/migrations"
        MIGRATION_COUNT=$(find "$MIGRATION_DIR" -name '*.sql' 2>/dev/null | wc -l | tr -d ' ')
        APPLIED=0
        SKIPPED=0

        if [ "$MIGRATION_COUNT" -gt 0 ]; then
            info "Applying ${MIGRATION_COUNT} migrations..."
            for f in "$MIGRATION_DIR"/*.sql; do
                fname=$(basename "$f")
                if docker compose exec -T db psql -U postgres -d postgres < "$f" > /dev/null 2>&1; then
                    success "$fname"
                    APPLIED=$((APPLIED + 1))
                else
                    echo -e "  ${YELLOW}⊘${NC} ${fname} ${DIM}(already applied or had errors)${NC}"
                    SKIPPED=$((SKIPPED + 1))
                fi
            done
            echo
            success "Migrations complete: ${APPLIED} applied, ${SKIPPED} skipped"
        fi

        step "5/5 — Done"
        docker compose ps
        echo
        info "Stack is running with existing .env configuration."
        info "See PROD.md for reverse proxy setup, MQTT hardening, and backups."
        exit 0
    fi
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Step 2: Interactive Configuration
# ═══════════════════════════════════════════════════════════════════════════════
step "2/5 — Configuration"

# ─── Hostnames ─────────────────────────────────────────────────────────────────
echo -e "${BOLD}Hostnames${NC}"
echo

SUPABASE_HOST=$(prompt_with_default "Supabase API hostname" "supabase.vmflow.xyz")
APP_HOST=$(prompt_with_default "Frontend app hostname" "app.vmflow.xyz")
MQTT_HOST=$(prompt_with_default "MQTT broker hostname (reachable by ESP32 devices)" "mqtt.vmflow.xyz")

SUPABASE_PUBLIC_URL="https://${SUPABASE_HOST}"
API_EXTERNAL_URL="https://${SUPABASE_HOST}"
SITE_URL="https://${APP_HOST}"

echo
info "URLs:"
echo -e "  Supabase API:  ${GREEN}${SUPABASE_PUBLIC_URL}${NC}"
echo -e "  Frontend:      ${GREEN}${SITE_URL}${NC}"
info "The frontend URL is encoded into the QR codes on printed machine signs,"
info "so it must be the address customers can reach from outside."
echo -e "  MQTT broker:   ${GREEN}${MQTT_HOST}${NC}"

# ─── SMTP (optional) ──────────────────────────────────────────────────────────
echo -e "\n${BOLD}Email / SMTP${NC}"
echo

CONFIGURE_SMTP=false
ENABLE_EMAIL_AUTOCONFIRM="false"

if prompt_yes_no "Configure SMTP now? (You can skip this and add it to .env later)" "n"; then
    CONFIGURE_SMTP=true
    SMTP_HOST=$(prompt_with_default "SMTP host" "smtp.sendgrid.net")
    SMTP_PORT=$(prompt_with_default "SMTP port" "587")
    SMTP_USER=$(prompt_with_default "SMTP user" "apikey")
    SMTP_PASS=$(prompt_secret "SMTP password")
    while [ -z "$SMTP_PASS" ]; do
        warn "SMTP password cannot be empty."
        SMTP_PASS=$(prompt_secret "SMTP password")
    done
    SMTP_ADMIN_EMAIL=$(prompt_with_default "Sender email address" "noreply@vmflow.xyz")
    SMTP_SENDER_NAME=$(prompt_with_default "Sender display name" "VMflow")
else
    warn "SMTP not configured — email auto-confirm will be enabled."
    warn "Configure SMTP in .env before going to production."
    SMTP_HOST="supabase-mail"
    SMTP_PORT="2500"
    SMTP_USER="fake_mail_user"
    SMTP_PASS="fake_mail_password"
    SMTP_ADMIN_EMAIL="admin@example.com"
    SMTP_SENDER_NAME="VMflow"
    ENABLE_EMAIL_AUTOCONFIRM="true"
fi

# ─── Dashboard credentials ────────────────────────────────────────────────────
echo -e "\n${BOLD}Supabase Studio Dashboard${NC}"
echo

DASHBOARD_USERNAME=$(prompt_with_default "Dashboard username" "supabase")
DEFAULT_DASHBOARD_PASS=$(generate_hex 16)
DASHBOARD_PASSWORD=$(prompt_with_default "Dashboard password" "$DEFAULT_DASHBOARD_PASS")

# ─── Signup toggle ─────────────────────────────────────────────────────────────
echo -e "\n${BOLD}Application Settings${NC}"
echo

DISABLE_SIGNUP="false"
if prompt_yes_no "Disable public signup? (Recommended for private deployments)" "n"; then
    DISABLE_SIGNUP="true"
fi

# ─── Environment indicator ─────────────────────────────────────────────────────
echo
echo -e "${BOLD}Environment Indicator${NC}"
echo -e "${DIM}A colored banner can be shown at the top of the frontend on every page${NC}"
echo -e "${DIM}to clearly mark non-production environments (dev / test / staging).${NC}"
echo -e "${DIM}Production deployments should leave this disabled.${NC}"
echo

ENV_NAME=""
ENV_COLOR=""
if prompt_yes_no "Is this a non-production system (dev / test / staging)?" "n"; then
    ENV_NAME=$(prompt_with_default "Environment label (shown on banner, uppercase)" "test")
    echo
    echo -e "${DIM}Available colors:${NC}"
    echo -e "  ${RED}red${NC}     — strongest warning, use for unstable / dev"
    echo -e "  ${YELLOW}amber${NC}   — caution (default)"
    echo -e "  orange  — alternative warm tone"
    echo -e "  purple  — neutral attention, e.g. for staging"
    echo -e "  ${BLUE}blue${NC}    — informational, least alarming"
    echo
    ENV_COLOR=$(prompt_with_default "Banner color" "amber")
    case "$ENV_COLOR" in
        red|amber|orange|purple|blue) ;;
        *) warn "Unknown color '$ENV_COLOR' — falling back to 'amber'"; ENV_COLOR="amber" ;;
    esac
    success "Frontend will show '${ENV_NAME}' banner in ${ENV_COLOR}"
else
    info "Production mode — no environment banner will be shown"
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Generate Secrets
# ═══════════════════════════════════════════════════════════════════════════════
echo
info "Generating cryptographic secrets..."

POSTGRES_PASSWORD=$(generate_hex 32)
JWT_SECRET=$(generate_random 32)
SECRET_KEY_BASE=$(generate_random 48)
VAULT_ENC_KEY=$(generate_hex 16)
PG_META_CRYPTO_KEY=$(generate_random 24)
LOGFLARE_PUBLIC_ACCESS_TOKEN=$(generate_random 32)
LOGFLARE_PRIVATE_ACCESS_TOKEN=$(generate_random 32)
MQTT_WEBHOOK_SECRET=$(generate_hex 16)
MQTT_ADMIN_PASS=$(generate_random 16)
POOLER_TENANT_ID=$(generate_hex 8)

success "Generated all secrets"

# ─── VAPID keys for Web Push notifications ──────────────────────────────────
info "Generating VAPID keys for push notifications..."
VAPID_PRIVKEY_PEM=$(openssl ecparam -name prime256v1 -genkey -noout 2>/dev/null)
VAPID_PRIVATE_KEY=$(echo "$VAPID_PRIVKEY_PEM" | openssl ec -noout -text 2>/dev/null \
  | grep -A3 'priv:' | tail -n+2 | tr -d ' :\n' | xxd -r -p | base64url_encode)
VAPID_PUBLIC_KEY=$(echo "$VAPID_PRIVKEY_PEM" | openssl ec -pubout -outform DER 2>/dev/null \
  | tail -c 65 | base64url_encode)
VAPID_SUBJECT="mailto:admin@vmflow.xyz"
success "Generated VAPID key pair"

info "Generating JWT keys..."

ANON_KEY=$(generate_jwt "$JWT_SECRET" "anon")
SERVICE_ROLE_KEY=$(generate_jwt "$JWT_SECRET" "service_role")

success "Generated ANON_KEY and SERVICE_ROLE_KEY"

# ═══════════════════════════════════════════════════════════════════════════════
# Write .env
# ═══════════════════════════════════════════════════════════════════════════════
info "Writing .env file..."

cat > .env << ENVEOF
############
# Secrets
# Generated by setup.sh on $(date -u +"%Y-%m-%d %H:%M:%S UTC")
############

POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
JWT_SECRET=${JWT_SECRET}
ANON_KEY=${ANON_KEY}
SERVICE_ROLE_KEY=${SERVICE_ROLE_KEY}
DASHBOARD_USERNAME=${DASHBOARD_USERNAME}
DASHBOARD_PASSWORD=${DASHBOARD_PASSWORD}
SECRET_KEY_BASE=${SECRET_KEY_BASE}
VAULT_ENC_KEY=${VAULT_ENC_KEY}
PG_META_CRYPTO_KEY=${PG_META_CRYPTO_KEY}


############
# Database - You can change these to any PostgreSQL database that has logical replication enabled.
############

POSTGRES_HOST=db
POSTGRES_DB=postgres
POSTGRES_PORT=5432
# default user is postgres


############
# Supavisor -- Database pooler
############
# Port Supavisor listens on for transaction pooling connections
POOLER_PROXY_PORT_TRANSACTION=6543
# Maximum number of PostgreSQL connections Supavisor opens per pool
POOLER_DEFAULT_POOL_SIZE=20
# Maximum number of client connections Supavisor accepts per pool
POOLER_MAX_CLIENT_CONN=100
# Unique tenant identifier
POOLER_TENANT_ID=${POOLER_TENANT_ID}
# Pool size for internal metadata storage used by Supavisor
# This is separate from client connections and used only by Supavisor itself
POOLER_DB_POOL_SIZE=5


############
# API Proxy - Configuration for the Kong Reverse proxy.
############

KONG_HTTP_PORT=8000
KONG_HTTPS_PORT=8443


############
# API - Configuration for PostgREST.
############

PGRST_DB_SCHEMAS=public,storage,graphql_public


############
# Auth - Configuration for the GoTrue authentication server.
############

## General
SITE_URL=${SITE_URL}
ADDITIONAL_REDIRECT_URLS=
JWT_EXPIRY=3600
DISABLE_SIGNUP=${DISABLE_SIGNUP}
API_EXTERNAL_URL=${API_EXTERNAL_URL}

## Mailer Config
MAILER_URLPATHS_CONFIRMATION="/auth/v1/verify"
MAILER_URLPATHS_INVITE="/auth/v1/verify"
MAILER_URLPATHS_RECOVERY="/auth/v1/verify"
MAILER_URLPATHS_EMAIL_CHANGE="/auth/v1/verify"

## Email auth
ENABLE_EMAIL_SIGNUP=true
ENABLE_EMAIL_AUTOCONFIRM=${ENABLE_EMAIL_AUTOCONFIRM}
SMTP_ADMIN_EMAIL=${SMTP_ADMIN_EMAIL}
SMTP_HOST=${SMTP_HOST}
SMTP_PORT=${SMTP_PORT}
SMTP_USER=${SMTP_USER}
SMTP_PASS=${SMTP_PASS}
SMTP_SENDER_NAME=${SMTP_SENDER_NAME}
ENABLE_ANONYMOUS_USERS=false

## Phone auth
ENABLE_PHONE_SIGNUP=false
ENABLE_PHONE_AUTOCONFIRM=false


############
# Studio - Configuration for the Dashboard
############

STUDIO_DEFAULT_ORGANIZATION="Default Organization"
STUDIO_DEFAULT_PROJECT="Default Project"

SUPABASE_PUBLIC_URL=${SUPABASE_PUBLIC_URL}

# Enable webp support
IMGPROXY_ENABLE_WEBP_DETECTION=true

# Add your OpenAI API key to enable SQL Editor Assistant
OPENAI_API_KEY=


############
# Functions - Configuration for Functions
############
# NOTE: VERIFY_JWT applies to all functions. Per-function VERIFY_JWT is not supported yet.
FUNCTIONS_VERIFY_JWT=false


############
# Logs - Configuration for Analytics
# Please refer to https://supabase.com/docs/reference/self-hosting-analytics/introduction
############

# Change vector.toml sinks to reflect this change
# these cannot be the same value
LOGFLARE_PUBLIC_ACCESS_TOKEN=${LOGFLARE_PUBLIC_ACCESS_TOKEN}
LOGFLARE_PRIVATE_ACCESS_TOKEN=${LOGFLARE_PRIVATE_ACCESS_TOKEN}

# Docker socket location - this value will differ depending on your OS
DOCKER_SOCKET_LOCATION=/var/run/docker.sock

# Google Cloud Project details
GOOGLE_PROJECT_ID=GOOGLE_PROJECT_ID
GOOGLE_PROJECT_NUMBER=GOOGLE_PROJECT_NUMBER

##########
# MQTT
#########

MQTT_HOST=${MQTT_HOST}
MQTT_PUBLIC_HOST=${MQTT_HOST}
MQTT_WEBHOOK_SECRET=${MQTT_WEBHOOK_SECRET}
MQTT_ADMIN_USER=admin
MQTT_ADMIN_PASS=${MQTT_ADMIN_PASS}

##########
# Push Notifications (VAPID)
#########

VAPID_PUBLIC_KEY=${VAPID_PUBLIC_KEY}
VAPID_PRIVATE_KEY=${VAPID_PRIVATE_KEY}
VAPID_SUBJECT=${VAPID_SUBJECT}

##########
# GitHub Firmware Builds
# Set to your GitHub repo (owner/repo) to enable GitHub release imports on the firmware page.
# Leave empty to disable.
#########

GITHUB_FIRMWARE_REPO=

##########
# Frontend Environment Indicator
# Empty / "prod" / "production" → no banner. Otherwise → banner with this name.
# Color: red, amber, orange, purple, blue (default: amber)
#########

ENV_NAME=${ENV_NAME}
ENV_COLOR=${ENV_COLOR}
ENVEOF

success ".env written successfully"

# ─── Generate Mosquitto passwd file ──────────────────────────────────────────
info "Generating MQTT broker credentials..."
MQTT_PASSWD_FILE="${SCRIPT_DIR}/mqtt/config/passwd"
rm -f "$MQTT_PASSWD_FILE"
touch "$MQTT_PASSWD_FILE"
docker run --rm -v "${SCRIPT_DIR}/mqtt/config:/mosquitto/config" eclipse-mosquitto:2.1.2-alpine \
  sh -c "
    mosquitto_passwd -b /mosquitto/config/passwd vmflow 'vmflow' && \
    mosquitto_passwd -b /mosquitto/config/passwd admin '${MQTT_ADMIN_PASS}'
  "
chmod 600 "$MQTT_PASSWD_FILE"
success "MQTT passwd file generated"

# ─── Write management-frontend .env ─────────────────────────────────────────
FRONTEND_ENV="${SCRIPT_DIR}/../management-frontend/.env"
if [ -d "${SCRIPT_DIR}/../management-frontend" ]; then
    info "Writing management-frontend/.env ..."
    cat > "$FRONTEND_ENV" << FEENVEOF
SUPABASE_URL=${SUPABASE_PUBLIC_URL}
SUPABASE_KEY=${ANON_KEY}
VAPID_PUBLIC_KEY=${VAPID_PUBLIC_KEY}
ENV_NAME=${ENV_NAME}
ENV_COLOR=${ENV_COLOR}
FEENVEOF
    success "management-frontend/.env written"
else
    warn "management-frontend/ directory not found — skipping frontend .env"
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Step 3: Start the Docker Stack
# ═══════════════════════════════════════════════════════════════════════════════
step "3/5 — Starting Docker Stack"

info "Pulling pre-built frontend image..."
docker compose pull frontend 2>/dev/null && success "Frontend image pulled" || warn "Could not pull frontend image — will build locally"

# ─────────────────────────────────────────────────────────────
# Pre-flight: every db init script mounted by docker-compose.yml
# must exist as a real file.
# ─────────────────────────────────────────────────────────────
# Docker materialises a missing bind-mount source as an empty DIRECTORY.
# The image's migrate.sh then hits `psql -f <dir>` -> "Is a directory",
# and because it runs under `set -e` the whole init run aborts — every
# later script (99-roles.sql!) never executes, so authenticator,
# supabase_auth_admin and supabase_storage_admin end up without a
# password. The container restarts, finds PGDATA initialised, skips init
# for good and looks perfectly healthy. Catch it before it happens.
info "Verifying database init scripts..."
INIT_BAD=""
for rel in $(grep -oE '\./volumes/db/[A-Za-z0-9_.-]+\.sql' docker-compose.yml | sort -u); do
    if [ -d "$rel" ]; then
        warn "  ${rel} is a DIRECTORY — leftover from an earlier broken start, delete it"
        INIT_BAD="yes"
    elif [ ! -f "$rel" ]; then
        warn "  ${rel} is missing from the repo"
        INIT_BAD="yes"
    fi
done
if [ -n "$INIT_BAD" ]; then
    error "Database init scripts are not intact — fix the paths above before starting, otherwise Postgres comes up with password-less roles and rest/auth/storage can never connect."
fi
success "All database init scripts present"

info "Starting all services..."
docker compose up -d

# ═══════════════════════════════════════════════════════════════════════════════
# Step 4: Apply Database Migrations
# ═══════════════════════════════════════════════════════════════════════════════
step "4/5 — Applying Database Migrations"

info "Waiting for database to be ready..."
DB_READY=false
for i in $(seq 1 30); do
    if docker compose exec -T db pg_isready -U postgres >/dev/null 2>&1; then
        success "Database is ready"
        DB_READY=true
        break
    fi
    sleep 2
done

if [ "$DB_READY" = false ]; then
    error "Database did not become ready within 60 seconds. Check logs: docker compose logs db"
fi

# Wait a few more seconds for Supabase init scripts to finish
# (roles, JWT, webhooks, etc. run via /docker-entrypoint-initdb.d/)
info "Waiting for Supabase initialization to complete..."
sleep 5

# ─────────────────────────────────────────────────────────────
# Guard: did 99-roles.sql actually run?
# ─────────────────────────────────────────────────────────────
# pg_isready says nothing about the init scripts — a container whose
# init run aborted restarts and reports "ready to accept connections"
# all the same. Ask Postgres directly instead of reporting success and
# leaving the operator to discover it from auth failures in the logs.
ROLES_NO_PASSWORD=$(docker compose exec -T db psql -U postgres -d postgres -Atc \
    "SELECT coalesce(string_agg(rolname, ', ' ORDER BY rolname), '')
       FROM pg_authid
      WHERE rolname IN ('authenticator','pgbouncer','supabase_admin','supabase_auth_admin','supabase_functions_admin','supabase_storage_admin')
        AND rolpassword IS NULL" 2>/dev/null || echo "QUERY_FAILED")

if [ "$ROLES_NO_PASSWORD" = "QUERY_FAILED" ]; then
    error "Could not query role state. Check logs: docker compose logs db"
elif [ -n "$ROLES_NO_PASSWORD" ]; then
    warn "These roles have no password: ${ROLES_NO_PASSWORD}"
    warn "The Postgres init run aborted — check: docker compose logs db | grep -i 'is a directory'"
    warn "Init scripts do not re-run on restart. Since this is a fresh install with no data yet:"
    warn "  docker compose down && rm -rf volumes/db/data && ./setup.sh"
    error "Aborting — rest/auth/storage could not authenticate against this database."
fi
success "Database roles initialised"

# ─────────────────────────────────────────────────────────────
# Configure DB settings consumed by SECURITY DEFINER functions
# ─────────────────────────────────────────────────────────────
# ALTER DATABASE on custom GUCs requires superuser; `postgres` role
# is not superuser in self-hosted Supabase. `supabase_admin` is,
# and is reachable via 127.0.0.1 trust (per the image's pg_hba.conf).
docker compose exec -T db psql -U supabase_admin -h 127.0.0.1 -d postgres >/dev/null <<SQL
ALTER DATABASE postgres SET app.settings.supabase_url = 'http://kong:8000';
ALTER DATABASE postgres SET app.settings.service_role_key = '${SERVICE_ROLE_KEY}';
SQL
success "Configured app.settings.* for low-stock push dispatcher"

MIGRATION_DIR="supabase/migrations"
MIGRATION_COUNT=$(find "$MIGRATION_DIR" -name '*.sql' 2>/dev/null | wc -l | tr -d ' ')
APPLIED=0
SKIPPED=0

if [ "$MIGRATION_COUNT" -gt 0 ]; then
    info "Applying ${MIGRATION_COUNT} migration files..."
    echo
    for f in "$MIGRATION_DIR"/*.sql; do
        fname=$(basename "$f")
        if docker compose exec -T db psql -U postgres -d postgres < "$f" > /dev/null 2>&1; then
            success "$fname"
            APPLIED=$((APPLIED + 1))
        else
            echo -e "  ${YELLOW}⊘${NC} ${fname} ${DIM}(already applied or had errors)${NC}"
            SKIPPED=$((SKIPPED + 1))
        fi
    done
    echo
    success "Migrations complete: ${APPLIED} applied, ${SKIPPED} skipped"
else
    warn "No migration files found in ${MIGRATION_DIR}/"
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Step 5: Summary
# ═══════════════════════════════════════════════════════════════════════════════
step "5/5 — Setup Complete"

echo
docker compose ps
echo

echo -e "${BOLD}╔══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║               VMflow is running!                        ║${NC}"
echo -e "${BOLD}╠══════════════════════════════════════════════════════════╣${NC}"
echo -e "${BOLD}║${NC}"
echo -e "${BOLD}║${NC}  Supabase API:  ${GREEN}${SUPABASE_PUBLIC_URL}${NC}"
echo -e "${BOLD}║${NC}  Frontend:      ${GREEN}${SITE_URL}${NC}"
echo -e "${BOLD}║${NC}  MQTT broker:   ${GREEN}${MQTT_HOST}:1883${NC}"
echo -e "${BOLD}║${NC}"
echo -e "${BOLD}║${NC}  Studio:        ${CYAN}${DASHBOARD_USERNAME}${NC} / ${CYAN}${DASHBOARD_PASSWORD}${NC}"
if [ "$CONFIGURE_SMTP" = true ]; then
echo -e "${BOLD}║${NC}  SMTP:          ${GREEN}Configured${NC} (${SMTP_HOST})"
else
echo -e "${BOLD}║${NC}  SMTP:          ${YELLOW}Not configured${NC} (auto-confirm enabled)"
fi
echo -e "${BOLD}║${NC}"
echo -e "${BOLD}║${NC}  Migrations:    ${GREEN}${APPLIED} applied${NC}, ${SKIPPED} skipped"
echo -e "${BOLD}║${NC}  .env:          ${CYAN}$(pwd)/.env${NC}"
echo -e "${BOLD}║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════╝${NC}"

echo
echo -e "${BOLD}Next steps:${NC}"
echo -e "  1. Set up DNS records:"
echo -e "     ${CYAN}${SUPABASE_HOST}${NC}  →  A record  →  your server IP"
echo -e "     ${CYAN}${APP_HOST}${NC}  →  A record  →  your server IP"
echo -e "  2. Configure a reverse proxy (Caddy or Nginx) — see PROD.md"
if [ "$CONFIGURE_SMTP" = false ]; then
echo -e "  3. ${YELLOW}Configure SMTP in .env before going to production${NC}"
fi
echo
info "Done! See PROD.md for reverse proxy setup, MQTT hardening, and backups."
