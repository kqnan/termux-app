#!/bin/bash
#
# 一键安装 Termux APK 到 Docker Android 模拟器
# 
# 功能：
#   1. 检查 adb 连接状态
#   2. 自动检测模拟器架构并选择匹配的 APK
#   3. 强制安装 APK（-r -f 参数）
#   4. 验证安装结果
#
# 用法：
#   ./scripts/install-apk-to-emulator.sh [apk路径]
#
# 环境要求：
#   - adb 已安装并连接到模拟器
#   - APK 已编译完成

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 项目根目录
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_DIR="${PROJECT_ROOT}/app/build/outputs/apk/debug"

# 全局变量存储设备信息
DEVICE_ID=""
DEVICE_ABI=""
SELECTED_APK=""

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查 adb 是否可用
check_adb() {
    if ! command -v adb &> /dev/null; then
        log_error "adb 命令未找到，请确保 Android SDK 已安装"
        exit 1
    fi
    log_info "adb 已就绪: $(adb version | head -1)"
}

# 检查模拟器连接状态
check_emulator() {
    log_info "检查模拟器连接状态..."
    
    # 获取设备列表（排除标题行，状态为 device）
    DEVICES=$(adb devices -l | awk 'NR>1 && $2=="device" {print; exit}')
    
    if [ -z "$DEVICES" ]; then
        log_error "未检测到已连接的 Android 设备/模拟器"
        log_info "提示：如果使用 Docker 模拟器，请确保容器已启动并执行 adb connect"
        exit 1
    fi
    
    # 解析设备 ID（第一列）
    DEVICE_ID=$(echo "$DEVICES" | awk '{print $1}')
    log_success "已连接设备: $DEVICE_ID"
    
    # 获取设备架构
    DEVICE_ABI=$(adb -s "$DEVICE_ID" shell getprop ro.product.cpu.abi | tr -d '\r\n')
    log_info "设备 ABI: $DEVICE_ABI"
}

# 根据架构选择 APK
select_apk() {
    local custom_apk="$1"
    
    if [ -n "$custom_apk" ]; then
        if [ -f "$custom_apk" ]; then
            log_info "使用指定 APK: $custom_apk"
            SELECTED_APK="$custom_apk"
            return
        else
            log_error "指定的 APK 文件不存在: $custom_apk"
            exit 1
        fi
    fi
    
    # ABI 映射到 APK 文件名
    local apk_pattern=""
    case "$DEVICE_ABI" in
        x86_64)
            apk_pattern="termux-app_apt-android-7-debug_x86_64.apk"
            ;;
        x86)
            apk_pattern="termux-app_apt-android-7-debug_x86.apk"
            ;;
        arm64-v8a)
            apk_pattern="termux-app_apt-android-7-debug_arm64-v8a.apk"
            ;;
        armeabi-v7a)
            apk_pattern="termux-app_apt-android-7-debug_armeabi-v7a.apk"
            ;;
        *)
            # 通用 APK 作为备选
            apk_pattern="termux-app_apt-android-7-debug_universal.apk"
            log_warn "未知 ABI: $DEVICE_ABI，使用 universal APK"
            ;;
    esac
    
    SELECTED_APK="${APK_DIR}/${apk_pattern}"
    
    if [ -f "$SELECTED_APK" ]; then
        log_success "找到匹配 APK: $apk_pattern"
    else
        # 尝试查找任意 APK
        local any_apk=$(find "$APK_DIR" -name "*.apk" -type f 2>/dev/null | head -1)
        if [ -n "$any_apk" ]; then
            log_warn "未找到架构匹配的 APK，使用: $(basename "$any_apk")"
            SELECTED_APK="$any_apk"
        else
            log_error "APK 目录中未找到任何 APK 文件: $APK_DIR"
            log_info "请先编译项目: ./gradlew assembleDebug"
            exit 1
        fi
    fi
}

# 卸载已有的 Termux 应用
uninstall_existing() {
    local package_name="com.termux"
    
    log_info "检查已有 Termux 应用..."
    
    # 检查包是否存在
    if adb -s "$DEVICE_ID" shell pm list packages -3 | grep -q "$package_name"; then
        log_info "发现已安装的 Termux，正在卸载..."
        
        # 卸载应用
        if adb -s "$DEVICE_ID" uninstall "$package_name"; then
            log_success "卸载成功"
            # 等待卸载完成
            sleep 2
            return 0
        else
            log_warn "卸载失败，将尝试强制安装覆盖"
            return 1
        fi
    else
        log_info "未发现已安装的 Termux，跳过卸载"
        return 0
    fi
}

# 安装 APK
install_apk() {
    log_info "开始安装 APK..."
    log_info "  设备: $DEVICE_ID"
    log_info "  APK: $(basename "$SELECTED_APK")"
    log_info "  大小: $(du -h "$SELECTED_APK" | cut -f1)"
    
    # 安装 APK
    if adb -s "$DEVICE_ID" install "$SELECTED_APK"; then
        log_success "APK 安装成功"
        return 0
    else
        log_error "APK 安装失败"
        return 1
    fi
}

# 验证安装
verify_installation() {
    local package_name="com.termux"
    
    log_info "验证安装结果..."
    
    # 检查包是否存在
    if adb -s "$DEVICE_ID" shell pm list packages -3 | grep -q "$package_name"; then
        log_success "应用已安装: $package_name"
    else
        log_error "应用未找到: $package_name"
        return 1
    fi
    
    # 显示版本信息
    local version_info=$(adb -s "$DEVICE_ID" shell dumpsys package "$package_name" 2>/dev/null | grep -E "versionName|versionCode" | head -2)
    local version_name=$(echo "$version_info" | grep "versionName" | sed 's/.*versionName=//' | tr -d '\r')
    local version_code=$(echo "$version_info" | grep "versionCode" | sed 's/.*versionCode=//' | awk '{print $1}')
    
    log_info "版本信息:"
    echo "  - versionName: $version_name"
    echo "  - versionCode: $version_code"
    
    # 显示应用路径
    local app_path=$(adb -s "$DEVICE_ID" shell pm path "$package_name" 2>/dev/null | sed 's/package://' | tr -d '\r')
    log_info "安装路径: $app_path"
    
    return 0
}

# 主流程
main() {
    echo ""
    echo "========================================"
    echo "  Termux APK 一键安装脚本"
    echo "========================================"
    echo ""
    
    # 检查 adb
    check_adb
    
    # 检查模拟器并获取设备信息
    check_emulator
    
    # 选择 APK
    select_apk "$1"
    
    echo ""
    
    # 卸载已有的 Termux
    uninstall_existing
    
    echo ""
    
    # 安装 APK
    if ! install_apk; then
        exit 1
    fi
    
    # 验证安装
    if ! verify_installation; then
        exit 1
    fi
    
    echo ""
    echo "========================================"
    log_success "安装完成！"
    echo "========================================"
    echo ""
    log_info "启动应用: adb -s $DEVICE_ID shell am start -n com.termux/.HomeActivity"
}

# 执行主流程
main "$@"