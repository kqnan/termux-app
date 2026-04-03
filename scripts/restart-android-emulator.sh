#!/bin/bash
# 一键重启 Docker Android 模拟器
# 
# 背景：budtmo/docker-android 容器内 sudo 配置损坏（缺少 root 用户）
# 导致启动脚本无法执行 sudo chown /dev/kvm。此脚本绕过该问题：
# 1. 主机端设置 KVM 权限
# 2. 直接启动 emulator 绕过损坏的启动脚本
#
# 用法：./restart-android-emulator.sh [容器名]
# 默认容器名：android-emulator

set -e

CONTAINER_NAME="${1:-android-emulator}"
KVM_UID=1300
KVM_GID=1301
ADB_PORT=5555
EMULATOR_WAIT_SECONDS=30
BOOT_WAIT_SECONDS=30

echo "=== 重启 Docker Android 模拟器 ==="
echo "容器名: $CONTAINER_NAME"

# 1. 检查容器是否存在
if ! docker ps -a --format "{{.Names}}" | grep -q "^${CONTAINER_NAME}$"; then
    echo "错误：容器 $CONTAINER_NAME 不存在"
    exit 1
fi

# 2. 停止容器
echo "[1/7] 停止容器..."
docker stop "$CONTAINER_NAME" 2>/dev/null || true

# 3. 设置 KVM 权限（主机端）
echo "[2/7] 设置 KVM 权限..."
if [ -e /dev/kvm ]; then
    chown "${KVM_UID}:${KVM_GID}" /dev/kvm
    chmod 666 /dev/kvm
    echo "  KVM 权限已设置为 ${KVM_UID}:${KVM_GID}"
else
    echo "  警告：/dev/kvm 不存在，跳过"
fi

# 4. 启动容器
echo "[3/7] 启动容器..."
docker start "$CONTAINER_NAME"

# 5. 等待容器内 supervisord 服务稳定
echo "[4/7] 等待容器服务稳定..."
sleep 10

# 检查关键服务
for i in {1..10}; do
    if docker exec "$CONTAINER_NAME" ps aux | grep -q "supervisord"; then
        echo "  supervisord 已运行"
        break
    fi
    echo "  等待 supervisord ($i/10)..."
    sleep 2
done

# 6. 查询 AVD 名称并启动 emulator（绕过损坏的启动脚本）
echo "[5/7] 启动 Android Emulator..."

# 查询可用的 AVD
AVD_NAME=$(docker exec "$CONTAINER_NAME" emulator -list-avds 2>&1 | head -1)
if [ -z "$AVD_NAME" ]; then
    echo "错误：未找到 AVD"
    docker logs "$CONTAINER_NAME" --tail 30
    exit 1
fi
echo "  AVD 名称: $AVD_NAME"

# 检查 emulator 是否已运行
if docker exec "$CONTAINER_NAME" ps aux | grep -q "[q]emu-system"; then
    echo "  QEMU 进程已存在，跳过启动"
else
    # 直接启动 emulator（绕过损坏的 docker-android 启动脚本）
    docker exec "$CONTAINER_NAME" bash -c \
        "emulator @$AVD_NAME -gpu swiftshader_indirect -accel on -writable-system -verbose -no-skin > /home/androidusr/logs/emulator.stdout.log 2>&1 &"
    echo "  Emulator 已启动"
fi

# 7. 等待 QEMU 进程稳定
echo "[6/7] 等待 QEMU 进程稳定..."
for i in {1..$EMULATOR_WAIT_SECONDS}; do
    if docker exec "$CONTAINER_NAME" ps aux | grep -q "[q]emu-system"; then
        echo "  QEMU 进程已运行"
        break
    fi
    echo "  等待 QEMU ($i/$EMULATOR_WAIT_SECONDS)..."
    sleep 1
done

# 验证 QEMU 进程
if ! docker exec "$CONTAINER_NAME" ps aux | grep -q "[q]emu-system"; then
    echo "错误：QEMU 进程未能启动"
    docker exec "$CONTAINER_NAME" tail -30 /home/androidusr/logs/device.stdout.log 2>&1 || true
    exit 1
fi

# 8. 连接 adb 并验证
echo "[7/7] 连接 adb..."

# 断开旧连接
adb disconnect localhost:$ADB_PORT 2>/dev/null || true

# 等待端口可用
sleep $BOOT_WAIT_SECONDS

# 连接
adb connect localhost:$ADB_PORT

# 验证设备状态
sleep 5
DEVICE_STATUS=$(adb devices | grep "localhost:$ADB_PORT" | awk '{print $2}')

if [ "$DEVICE_STATUS" = "device" ]; then
    echo ""
    echo "=== 模拟器重启成功 ==="
    echo "ADB 连接: localhost:$ADB_PORT (device)"
    adb devices
    exit 0
elif [ "$DEVICE_STATUS" = "offline" ]; then
    echo ""
    echo "=== 模拟器启动中 ==="
    echo "设备状态: offline（等待 boot completed）"
    echo "建议：等待 1-2 分钟后手动检查 adb devices"
    exit 0
else
    echo ""
    echo "=== 模拟器连接异常 ==="
    echo "设备状态: $DEVICE_STATUS"
    adb devices
    exit 1
fi