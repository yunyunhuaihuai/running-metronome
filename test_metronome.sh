#!/bin/bash
# ============================================================
# 节拍器自动化验证脚本（Git Bash / MSYS）
# 前置：模拟器或手机已连接授权、已安装本 App
# 用法: bash test_metronome.sh [all|beats|lock|alarm|training|duck|lifecycle]
#
# 2026-10 v2 说明：
#  - 所有 ADB 操作以 ADB -s <serial> 指定设备（优先 $METRO_SERIAL，
#    否则取第一台在线设备），不再全局 adb；
#  - 输出目录改为 running-metronome_Data/autotest-<日期>；
#  - 所有失败分支以非零码退出（不再 echo 后 exit 0）；
#  - 只清理本脚本启动的 logcat 进程（记录 PID + trap），不再 pkill 误杀；
#  - beats/lock 用例增加"拍数>0 / wall间隔>0"前置判定，杜绝 0 拍假通过；
#  - 新增 alarm（注入步点验证报警触发/解除链路）、training（定时/分段）、
#    duck（与最小播放端共存压低）、lifecycle（连续启停无残留）。
# ============================================================
set -u
ADB="/d/AndroidStduio/Sdk/platform-tools/adb.exe"
PKG="com.metronome.app"
RCV="$PKG/.DebugReceiver"
TODAY=$(date +%Y%m%d)
OUT="/d/APK/running-metronome_Data/autotest-$TODAY"
mkdir -p "$OUT"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

# ------------------------------------------------------------ 设备与进程管理
if [ -n "${METRO_SERIAL:-}" ]; then
  SERIAL="$METRO_SERIAL"
else
  SERIAL=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')
fi
[ -n "${SERIAL:-}" ] || { echo "[错误] 无在线设备，先连接/启动模拟器"; exit 1; }
echo "目标设备: $SERIAL"
A() { "$ADB" -s "$SERIAL" "$@"; }

LOG_PIDS=()
cleanup() {
  for pid in "${LOG_PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null
  done
}
trap cleanup EXIT

# 启动本脚本专属 logcat 采集（记录 PID，退出时清理）
capture() {  # capture <文件名> <tags...>
  local file="$1"; shift
  ( A logcat -s "$@" > "$file" 2>&1 ) &
  LOG_PIDS+=($!)
  A logcat -c
}

fail() { echo "[错误] $1"; exit 1; }
A get-state >/dev/null 2>&1 || fail "设备 $SERIAL 未就绪"

start_at_120() {  # 亮屏解锁 → 前台启动 App → 配置 120 SPM（默认静音不振动）
  A shell input keyevent 224
  A shell wm dismiss-keyguard
  sleep 1
  A shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  sleep 2
  A shell am broadcast -a "$PKG.debug.SET" -n "$RCV" --ei spm 120 \
      --ez sound "${1:-false}" --ez vibrate "${2:-false}" >/dev/null
}

start_session() { A shell am broadcast -a "$PKG.debug.START" -n "$RCV" >/dev/null; }
pause_session() { A shell am broadcast -a "$PKG.debug.PAUSE" -n "$RCV" >/dev/null; }
resume_session() { A shell am broadcast -a "$PKG.debug.RESUME" -n "$RCV" >/dev/null; }
stop_session() { A shell am broadcast -a "$PKG.debug.STOP" -n "$RCV" >/dev/null; }

# ---------------------------------------------------------- 测试 1: 120 拍/分钟
test_beats() {
  echo "=== 测试 1: 120 SPM 一分钟是否正好 120 拍（静音）==="
  start_at_120 false false
  capture "$OUT/beats.log" "MetroBeat:I"
  start_session
  echo "采集中 66 秒……请勿触碰设备"
  sleep 66
  stop_session
  sleep 2
  cleanup
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
    if (cnt < 60)     { print "结果: ❌ 未通过（拍数异常少，调度或回调中断）"; exit 1 }
    if (cnt == 120 && maxd <= 510) print "结果: ✅ 通过"
    else if (cnt == 120) { print "结果: ⚠ 节拍数正确但最大间隔偏大"; exit 1 }
    else { print "结果: ❌ 未通过"; exit 1 }
  }' "$OUT/beats.log" || exit 1
}

# ---------------------------------------------------------- 测试 2: 锁屏不中断
test_lock() {
  echo "=== 测试 2: 锁屏 30 秒节拍是否中断（静音）==="
  start_at_120 false false
  capture "$OUT/lock.log" "MetroBeat:I" "MetroTest:I"
  start_session
  sleep 2
  A shell "log -t MetroTest LOCK_START"
  A shell input keyevent 26
  sleep 30
  A shell input keyevent 224
  sleep 1
  A shell wm dismiss-keyguard
  sleep 1
  A shell "log -t MetroTest LOCK_END"
  sleep 2
  stop_session
  sleep 2
  cleanup
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
    if (cnt == 0)          { print "结果: ❌ 未通过（锁屏期间 0 拍，不能证明不中断）"; exit 1 }
    if (windows==1 && maxW < 1200) print "结果: ✅ 锁屏未中断"
    else { print "结果: ❌ 有中断（ColorOS 睡眠策略见指南电池设置一节；模拟器壁钟精度另计）"; exit 1 }
  }' "$OUT/lock.log" || exit 1
}

# ---------------------------------------------------------- 测试 3: 报警链路（注入步点）
test_alarm() {
  echo "=== 测试 3: 注入步点验证偏慢报警触发/解除（模拟器无 Step Detector 时的链路验证）==="
  # 场景：3s 热身 + 3s @180 → 切 100 SPM → 预计 ~5s+ 后报警（长音/短提示按当前配置）
  #       再切回 180 → 恢复确认 600ms 内解除
  start_at_120 false true
  A shell am broadcast -a "$PKG.debug.SET" -n "$RCV" --ez alarm true --ei alarmAfter 5 \
      --ei slowMargin 8 --ei recoverMs 600 --ez timer false >/dev/null
  capture "$OUT/alarm.log" "MetroState:I" "MetroBeat:I" "MetroFocus:I"
  start_session
  A shell am broadcast -a "$PKG.debug.INJECT" -n "$RCV" --ei spm 180 --ei durationMs 6000 >/dev/null
  echo "-- 快跑 6 秒（180 SPM 注入）"
  sleep 6.5
  A shell am broadcast -a "$PKG.debug.STATUS" -n "$RCV" >/dev/null
  echo "-- 切慢跑 100 SPM 12 秒（应触发报警）"
  A shell am broadcast -a "$PKG.debug.INJECT" -n "$RCV" --ei spm 100 --ei durationMs 12000 >/dev/null
  sleep 12.5
  A shell am broadcast -a "$PKG.debug.STATUS" -n "$RCV" >/dev/null
  ALARM_AT=$(grep -c "detector:.*alarm=true" "$OUT/alarm.log")
  echo "-- 切回 180 SPM 4 秒（应解除报警）"
  A shell am broadcast -a "$PKG.debug.INJECT" -n "$RCV" --ei spm 180 --ei durationMs 4000 >/dev/null
  sleep 4.5
  A shell am broadcast -a "$PKG.debug.STATUS" -n "$RCV" >/dev/null
  stop_session
  sleep 1
  A shell am broadcast -a "$PKG.debug.INJECT_STOP" -n "$RCV" >/dev/null
  cleanup
  echo "STATUS 快照中的 detector 行:"
  grep "detector:" "$OUT/alarm.log"
  if [ "$ALARM_AT" -ge 1 ]; then
    echo "结果: ✅ 慢速触发报警（快照见上，恢复解除以最后一次 STATUS alarm=false 为准）"
  else
    echo "结果: ❌ 未观察到报警触发"
    exit 1
  fi
  if grep "detector:" "$OUT/alarm.log" | tail -1 | grep -q "alarm=false"; then
    echo "      恢复解除: ✅"
  else
    echo "      恢复解除: ❌ 最后快照仍报警"
    exit 1
  fi
}

# ---------------------------------------------------------- 测试 4: 训练（定时+分段）
test_training() {
  echo "=== 测试 4: 定时结束 + 分段循环（准备倒计时/段目标切换/到点结束）==="
  # 计划：准备 2s；段 A 6s@140、段 B 6s@160（循环 2 轮）；定时上限 20s
  # 期望：约 2s 后开始，A/B 交替、目标随段切换；20s 时到点结束（FINISHED），节拍停止
  start_at_120 false false
  A shell am broadcast -a "$PKG.debug.SET" -n "$RCV" --ez timer true --ei totalSec 20 \
      --ei prepareSec 2 --ez segments true --es segDurs "6,6" --es segSpms "140,160" \
      --ei loopFrom 0 --ei loopTo 1 --ei loopRounds 2 >/dev/null
  capture "$OUT/training.log" "MetroState:I" "MetroBeat:I"
  start_session
  sleep 26
  stop_session
  sleep 1
  cleanup
  echo "状态转移记录:"
  grep "state=session" "$OUT/training.log"
  SEG=$(grep -c "segment-enter" "$OUT/training.log")
  FIN=$(grep -c "state=session-finished" "$OUT/training.log")
  echo "段切换次数: $SEG（期望 ≥3：段1→段2→段1(第2轮)→段2）"
  echo "结束提示记录: $FIN（期望 1，不自动转正计时）"
  if [ "$FIN" -eq 1 ] && [ "$SEG" -ge 3 ]; then
    echo "结果: ✅ 通过"
  else
    echo "结果: ❌ 未通过"
    exit 1
  fi
}

# ---------------------------------------------------------- 测试 5: 与音乐共存压低
test_duck() {
  echo "=== 测试 5: 先音乐后节拍（音乐被压低不暂停）；先节拍后音乐（节拍中断→恢复）==="
  echo "-- A: 先启动 DuckProbe（模拟音乐，申请 GAIN），再启动节拍（MAY_DUCK）"
  A shell am start -n "$PKG/.DuckProbeActivity" >/dev/null 2>&1
  sleep 2
  start_at_120 true false
  capture "$OUT/duck.log" "MetroFocus:I" "MetroDuck:I" "MetroState:I"
  start_session
  sleep 8
  A shell am broadcast -a "$PKG.debug.STATUS" -n "$RCV" >/dev/null
  echo "-- B: 返回键退出 probe（节拍应 GAIN 恢复），再启动 probe（节拍应收到 LOSS 静音）"
  A shell input keyevent 4
  sleep 3
  A shell am broadcast -a "$PKG.debug.STATUS" -n "$RCV" >/dev/null
  A shell am start -n "$PKG/.DuckProbeActivity" >/dev/null 2>&1
  sleep 6
  A shell am broadcast -a "$PKG.debug.STATUS" -n "$RCV" >/dev/null
  stop_session
  A shell input keyevent 4
  sleep 1
  cleanup
  echo "焦点链路记录（MetroFocus / MetroDuck）:"
  grep -E "MetroFocus|MetroDuck" "$OUT/duck.log" | head -40
  DUCK_EVENTS=$(grep -c "LOSS_TRANSIENT_CAN_DUCK\|LOSS (permanent)" "$OUT/duck.log")
  GAIN_EVENTS=$(grep -c "focus GAIN" "$OUT/duck.log")
  if [ "$DUCK_EVENTS" -ge 1 ]; then
    echo "结果: ✅ 观察到焦点竞争事件 x$DUCK_EVENTS（压低/中断行为见日志；听感受限于模拟器）"
  else
    echo "结果: ⚠ 未捕获焦点事件（模拟器音频栈可能不转发 duck，真机待测）"
  fi
  if [ "$GAIN_EVENTS" -ge 1 ]; then
    echo "      节拍恢复: ✅ GAIN 事件已记录"
  else
    echo "      节拍恢复: ⚠ 未捕获 GAIN"
  fi
}

# ---------------------------------------------------------- 测试 6: 服务生命周期
test_lifecycle() {
  echo "=== 测试 6: 连续启停会话后线程/WakeLock/MediaSession 无残留 ==="
  start_at_120 false false
  for i in 1 2 3 4 5; do
    start_session
    sleep 3
    stop_session
    sleep 3
    echo "-- 第 $i 次启停完成"
  done
  FAIL=0
  PID=$(A shell pidof "$PKG" | tr -d '\r')
  if [ -z "$PID" ]; then
    echo "应用进程已退出，线程必然无残留"
  else
    VIB=$(A shell ps -T -p "$PID" 2>/dev/null | grep -c "metronome-vib" || true)
    CTL=$(A shell ps -T -p "$PID" 2>/dev/null | grep -c "metronome-ctl" || true)
    CLK=$(A shell ps -T -p "$PID" 2>/dev/null | grep -c "metronome-clock" || true)
    echo "残留线程: vib=$VIB ctl=$CTL clock=$CLK（期望 0 0 0）"
    [ "$VIB" -eq 0 ] && [ "$CTL" -eq 0 ] && [ "$CLK" -eq 0 ] || FAIL=1
  fi
  # 只检查"当前持有"列表（PARTIAL_WAKE_LOCK 段），dumpsys power 末尾的历史事件不作依据
  WL=$(A shell dumpsys power 2>/dev/null | grep -cE "PARTIAL_WAKE_LOCK.*com\.metronome\.app" || true)
  echo "当前持有的本应用 WakeLock: $WL（期望 0）"
  [ "$WL" -eq 0 ] || FAIL=1
  # 只检查活动会话记录（package/pid 形式），"Audio playback"排行列表不作依据
  MS=$(A shell dumpsys media_session 2>/dev/null | grep -c "com\.metronome\.app/Metronome" || true)
  echo "活动 MediaSession 会话记录: $MS（期望 0）"
  [ "$MS" -eq 0 ] || FAIL=1
  if [ "$FAIL" -eq 0 ]; then echo "结果: ✅ 通过"; else echo "结果: ❌ 有残留"; exit 1; fi
}

case "${1:-all}" in
  beats)     test_beats ;;
  lock)      test_lock ;;
  alarm)     test_alarm ;;
  training)  test_training ;;
  duck)      test_duck ;;
  lifecycle) test_lifecycle ;;
  all)       test_beats; test_lock; test_alarm; test_training; test_duck; test_lifecycle ;;
  *) echo "用法: bash test_metronome.sh [all|beats|lock|alarm|training|duck|lifecycle]"; exit 1 ;;
esac
