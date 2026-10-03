#!/bin/bash
# ============================================================
# 节拍器自动化验证脚本（Git Bash / MSYS）
# 前置：手机已连接授权、已安装本 App、深睡开关临时关闭（见最后注释）
# 用法: bash test_metronome.sh [all|beats|lock|modes|lifecycle]
# ============================================================
ADB="/d/AndroidStduio/Sdk/platform-tools/adb.exe"
PKG="com.metronome.app"
RCV="$PKG/.DebugReceiver"          # ColorOS 拦截隐式广播，必须显式指定组件
OUT="/d/APK/test-results"
mkdir -p "$OUT"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

device() { "$ADB" get-state 2>/dev/null | grep -q device; }
die() { echo "[错误] $1"; exit 1; }
device || die "手机未连接或未授权，先运行: $ADB devices"

kill_captures() { pkill -f "adb.exe logcat" 2>/dev/null; }

start_at_120() {  # 亮屏解锁 → 前台启动 App → 配置 BPM/开关（不启动服务，由各测试自行启动）
  "$ADB" shell input keyevent 224
  "$ADB" shell wm dismiss-keyguard
  sleep 1
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  sleep 2
  "$ADB" shell am broadcast -a "$PKG.debug.SET" -n "$RCV" --ei bpm 120 --ez sound "${1:-false}" --ez vibrate "${2:-false}" >/dev/null
}

stop_all() { "$ADB" shell am broadcast -a "$PKG.debug.STOP" -n "$RCV" >/dev/null; }

# ---------------------------------------------------------- 测试 1: 120拍/分钟
test_beats() {
  echo "=== 测试 1: 120 BPM 一分钟是否正好 120 拍（静音）==="
  start_at_120 false false
  ( "$ADB" logcat -s MetroBeat:I > "$OUT/beats.log" 2>&1 & )
  "$ADB" logcat -c
  "$ADB" shell am broadcast -a "$PKG.debug.START" -n "$RCV" >/dev/null
  echo "采集中 66 秒……请勿触碰手机"
  sleep 66
  stop_all
  kill_captures
  awk 'BEGIN{cnt=0;last=-1;maxd=0}
  /MetroBeat: beat=/ {
    split($0, a, "audioMs="); split(a[2], b, " "); ms = b[1]+0;
    if (ms >= 0 && ms < 60000) {
      cnt++;
      if (last >= 0) { d = ms - last; if (d > maxd) maxd = d }
      last = ms;
    }
  } END {
    printf "60 秒窗口节拍数: %d（期望 120）, audio时钟最大间隔: %.0f ms（期望 500）\n", cnt, maxd;
    if (cnt == 120 && maxd <= 510) print "结果: ✅ 通过"; else print "结果: ❌ 未通过";
  }' "$OUT/beats.log"
}

# ---------------------------------------------------------- 测试 2: 锁屏不中断
test_lock() {
  echo "=== 测试 2: 锁屏 30 秒节拍是否中断（静音）==="
  start_at_120 false false
  ( "$ADB" logcat -s MetroBeat:I -s MetroTest:I > "$OUT/lock.log" 2>&1 & )
  "$ADB" logcat -c
  "$ADB" shell am broadcast -a "$PKG.debug.START" -n "$RCV" >/dev/null
  sleep 2
  "$ADB" shell "log -t MetroTest LOCK_START"
  "$ADB" shell input keyevent 26
  sleep 30
  "$ADB" shell input keyevent 224
  sleep 1
  "$ADB" shell wm dismiss-keyguard
  sleep 1
  "$ADB" shell "log -t MetroTest LOCK_END"
  sleep 2
  stop_all
  kill_captures
  awk 'BEGIN{inLock=0;aLast=-1;wLast=-1;maxA=0;maxW=0;cnt=0;windows=0}
  /MetroTest: LOCK_START/{inLock=1;windows++}
  /MetroTest: LOCK_END/{inLock=0}
  inLock && /MetroBeat: beat=/{
    split($0,a,"audioMs="); split(a[2],b," "); ms=b[1]+0;
    split($0,e,"wall="); w=e[2]+0;
    cnt++;
    if (aLast>=0){d=ms-aLast; if(d>maxA)maxA=d}
    if (wLast>=0){g=w-wLast; if(g>maxW)maxW=g}
    aLast=ms; wLast=w;
  } END {
    printf "锁屏窗口数: %d, 窗口内 %d 拍（约60）, audio最大间隔 %.0f ms, wall最大间隔 %.0f ms\n", windows, cnt, maxA, maxW;
    if (windows==1 && maxW < 1200) print "结果: ✅ 锁屏未中断"; else print "结果: ❌ 有中断（夜间可能被 ColorOS 睡眠策略冻结，见指南电池设置一节）";
  }' "$OUT/lock.log"
}

# ---------------------------------------------------------- 测试 3: 三模式切换
test_modes() {
  echo "=== 测试 3: 声音/振动/声音+振动 切换（BPM 60、低音量，会响几声+轻振几下）==="
  "$ADB" shell cmd media_session volume --stream 3 --set 4 >/dev/null 2>&1
  start_at_120 false false
  "$ADB" shell am broadcast -a "$PKG.debug.SET" -n "$RCV" --ei bpm 60 >/dev/null
  "$ADB" shell am broadcast -a "$PKG.debug.START" -n "$RCV" >/dev/null
  "$ADB" logcat -c
  ( "$ADB" logcat -s MetroState:I -s MetroSnd:I > "$OUT/modes.log" 2>&1 & )
  sleep 1
  echo "-- A: 仅声音 6 秒"
  "$ADB" shell am broadcast -a "$PKG.debug.SET" -n "$RCV" --ez sound true --ez vibrate false >/dev/null
  sleep 6
  echo "-- B: 仅振动 6 秒"
  "$ADB" shell am broadcast -a "$PKG.debug.SET" -n "$RCV" --ez sound false --ez vibrate true >/dev/null
  sleep 6
  "$ADB" shell dumpsys vibrator_manager > "$OUT/vibrator_dump.txt" 2>/dev/null
  echo "-- C: 声音+振动 6 秒"
  "$ADB" shell am broadcast -a "$PKG.debug.SET" -n "$RCV" --ez sound true --ez vibrate true >/dev/null
  sleep 6
  stop_all
  kill_captures
  echo "状态切换记录（应为三行不同组合）:"
  grep "debug-set" "$OUT/modes.log" | grep -oE "sound=(true|false) vibrate=(true|false)" 
  echo "声音证据（sounded=true 表示确实混入了声音采样）:"
  awk '/MetroSnd/{match($0,/sounded=[a-z]+ sound=[a-z]+/); print substr($0,RSTART,RLENGTH)}' "$OUT/modes.log" | sort | uniq -c
  VIB=$(grep -c "com.metronome.app" "$OUT/vibrator_dump.txt" 2>/dev/null)
  echo "系统振动记录（dumpsys vibrator_manager 中本应用条数）: $VIB"
  if [ "$VIB" -gt 0 ]; then echo "结果: ✅ 振动有系统级证据"; else echo "结果: ⚠️ 未捕获到振动记录"; fi
}

# ---------------------------------------------------------- 测试 4: 服务生命周期
test_lifecycle() {
  echo "=== 测试 4: 连续启停服务后线程/WakeLock/MediaSession 无残留 ==="
  start_at_120 false false
  for i in 1 2 3 4 5; do
    "$ADB" shell am broadcast -a "$PKG.debug.START" -n "$RCV" >/dev/null
    sleep 3
    "$ADB" shell am broadcast -a "$PKG.debug.STOP" -n "$RCV" >/dev/null
    sleep 3
    echo "-- 第 $i 次启停完成"
  done
  FAIL=0
  PID=$("$ADB" shell pidof "$PKG" | tr -d '\r')
  if [ -z "$PID" ]; then
    echo "应用进程已退出，线程必然无残留"
  else
    VIB=$("$ADB" shell ps -T -p "$PID" 2>/dev/null | grep -c "metronome-vib" || true)
    echo "metronome-vib 残留线程数: $VIB（期望 0）"
    [ "$VIB" -eq 0 ] || FAIL=1
  fi
  # 只检查"当前持有"列表（PARTIAL_WAKE_LOCK 段），dumpsys power 末尾的
  # ACQ/REL 历史事件记录始终会包含本应用，不能作为残留依据
  WL=$("$ADB" shell dumpsys power 2>/dev/null | grep -cE "PARTIAL_WAKE_LOCK.*com\.metronome\.app" || true)
  echo "当前持有的本应用 WakeLock: $WL（期望 0）"
  [ "$WL" -eq 0 ] || FAIL=1
  # 只检查活动会话记录（package/sessionTag/pid 形式）；
  # "Audio playback 最近播放"排行列表会保留 uid 一段时间，不作依据
  MS=$("$ADB" shell dumpsys media_session 2>/dev/null | grep -c "com\.metronome\.app/Metronome" || true)
  echo "活动 MediaSession 会话记录: $MS（期望 0）"
  [ "$MS" -eq 0 ] || FAIL=1
  if [ "$FAIL" -eq 0 ]; then echo "结果: ✅ 通过"; else echo "结果: ❌ 有残留"; fi
}

case "${1:-all}" in
  beats) test_beats ;;
  lock)  test_lock ;;
  modes) test_modes ;;
  lifecycle) test_lifecycle ;;
  all)   test_beats; test_lock; test_modes; test_lifecycle ;;
  *) echo "用法: bash test_metronome.sh [all|beats|lock|modes|lifecycle]" ;;
esac

# ------------------------------------------------------------
# 深夜测试提示（ColorOS 睡眠待机时段约 23:00-07:00 会强制冻结后台应用，
# 连 wakelock 都会被强制释放，导致锁屏测试偶发中断）：
#   临时关闭: adb shell settings put secure deepsleep_switch_state 0
#   恢复:     adb shell settings put secure deepsleep_switch_state 1
# 或者直接在手机上：设置 → 电池 → 节拍器 → 允许完全后台行为（一次性，推荐）
# ------------------------------------------------------------
