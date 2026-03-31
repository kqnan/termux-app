#!/bin/bash
# clear_and_stream.sh - 清除adb日志缓存并启动实时日志流

# 配置
DEVICE="emulator-5554"
LOG_DIR="/root/termux-app/.gsd/e2e-logs"
LOG_FILE="${LOG_DIR}/logcat_live_$(date +%Y%m%d_%H%M%S).log"

# 确保日志目录存在
mkdir -p "$LOG_DIR"

# 清除adb日志缓存
echo "正在清除设备 ${DEVICE} 的日志缓存..."
adb -s "$DEVICE" logcat -c
if [ $? -eq 0 ]; then
    echo "✓ 日志缓存已清除"
else
    echo "✗ 清除日志缓存失败"
    exit 1
fi

# 启动实时日志流
echo "正在启动实时日志流..."
echo "日志文件: $LOG_FILE"
echo "按 Ctrl+C 停止"

# 使用adb logcat启动实时流，带时间戳和线程信息
adb -s "$DEVICE" logcat -v threadtime > "$LOG_FILE" &

# 保存PID以便后续停止
PID=$!
echo $PID > "${LOG_DIR}/logcat_pid.txt"
echo "✓ 实时日志流已启动 (PID: $PID)"
echo ""
echo "查看日志: tail -f $LOG_FILE"
echo "停止日志: kill $PID"

# 等待用户中断
trap 'echo ""; echo "正在停止日志流..."; kill $PID 2>/dev/null; exit 0' INT
wait $PID