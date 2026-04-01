#!/bin/bash
set -e

echo "=== BetterGhast Deploy Script ==="

# Check if .env exists
if [ ! -f .env ]; then
    echo ""
    echo "No .env file found. Creating one..."
    echo ""
    read -p "Discord Bot Token: " DISCORD_TOKEN
    read -p "Database Password (for new DB): " DB_PASSWORD
    read -p "Allowed Guild IDs (comma-separated, empty = all): " ALLOWED_GUILDS

    cat > .env << EOF
DISCORD_TOKEN=${DISCORD_TOKEN}

DB_HOST=db
DB_PORT=3306
DB_NAME=betterghast
DB_USER=betterghast
DB_PASSWORD=${DB_PASSWORD}
DB_ROOT_PASSWORD=$(openssl rand -hex 16)

ALLOWED_GUILDS=${ALLOWED_GUILDS}
TAG_COOLDOWN_MS=2500
ACCENT_COLOR=B5C8B4
EOF

    echo ""
    echo ".env created!"
fi

# Install Docker if not present
if ! command -v docker &> /dev/null; then
    echo "Installing Docker..."
    curl -fsSL https://get.docker.com | sh
    systemctl enable docker
    systemctl start docker
    echo "Docker installed!"
fi

# Start everything
echo ""
echo "Building and starting BetterGhast..."
docker compose up -d --build

echo ""
echo "=== BetterGhast is running! ==="
echo "View logs:    docker compose logs -f bot"
echo "Stop:         docker compose down"
echo "Restart:      docker compose restart bot"
echo "Update:       git pull && docker compose up -d --build"
