#!/bin/bash
# SSH Connection Script for Termux
# Opens Termux, connects to SSH server, handles password and host key verification

set -e

DEVICE="localhost:5555"
PACKAGE="com.termux"
SCREENSHOT_DIR="/root/termux-app/scripts/screenshots"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

SSH_HOST="172.31.233.99"
SSH_PORT="8024"
SSH_USER="root"
SSH_PASS="cswsh202"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

mkdir -p "$SCREENSHOT_DIR"

# Screenshot helper
screenshot() {
    local num=$1
    local desc=$2
    local file="$SCREENSHOT_DIR/${TIMESTAMP}_${num}_${desc}.png"
    adb -s $DEVICE exec-out screencap -p > "$file"
    log_info "Screenshot: $file"
}

# 1. Connect Docker Android Emulator
log_info "Step 1: Connect Docker Android Emulator..."
adb connect localhost:5555
adb -s $DEVICE wait-for-device
log_info "Device connected"

# 2. Open Termux
log_info "Step 2: Open Termux..."
adb -s $DEVICE shell am start -n "$PACKAGE/.app.TermuxActivity"
sleep 2
screenshot "02" "termux_opened"

# 3. Double tap screen to focus
log_info "Step 3: Double tap screen..."
SCREEN_SIZE=$(adb -s $DEVICE shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1)
WIDTH=$(echo $SCREEN_SIZE | cut -d'x' -f1)
HEIGHT=$(echo $SCREEN_SIZE | cut -d'x' -f2)
CENTER_X=$((WIDTH / 2))
CENTER_Y=$((HEIGHT / 2))

adb -s $DEVICE shell input tap $CENTER_X $CENTER_Y
sleep 0.1
adb -s $DEVICE shell input tap $CENTER_X $CENTER_Y
sleep 1
screenshot "03" "after_double_tap"

# 4. Type SSH command using adb shell with proper escaping
# Using printf to send the full command at once via stdin
log_info "Step 4: Type SSH command..."
adb -s $DEVICE shell "input text 'ssh root@172.31.233.99 -p 8024'"
sleep 0.5
screenshot "04" "ssh_command_typed"

# 5. Press Enter to execute
log_info "Step 5: Press Enter to execute..."
adb -s $DEVICE shell input keyevent 66
sleep 3
screenshot "05" "after_enter"

# 6. Type password
log_info "Step 6: Type password..."
adb -s $DEVICE shell "input text '$SSH_PASS'"
sleep 0.5
screenshot "06" "password_typed"

# 7. Press Enter to submit password
log_info "Step 7: Press Enter to submit password..."
adb -s $DEVICE shell input keyevent 66
sleep 2
screenshot "07" "after_password_submit"

# 8. Type "yes" for host key verification


# 9. Press Enter
log_info "Step 9: Press Enter..."
adb -s $DEVICE shell input keyevent 66
sleep 2
screenshot "09" "final"

log_info "========================================"
log_info "SSH Connection Script Completed!"
log_info "Target: $SSH_USER@$SSH_HOST:$SSH_PORT"
log_info "Screenshots: $SCREENSHOT_DIR"
log_info "========================================"
