#!/bin/bash
# ============================================================
# 节拍器自动化验证脚本（Git Bash / MSYS）
# 前置：手机或模拟器已连接授权、已安装本 App 的 debug APK
# 用法: bash test_metronome.sh [all|beats|lock|alarm|training|duck|lifecycle]
#
# 2026-10 v2 说明：
#  - 所有 ADB 操作以 ADB -s <serial> 指定设备；多设备时必须提供
#    METRO_SERIAL，避免误操作另一台设备；
#  - 每次运行使用独立 Data 目录，不覆盖同日历史日志；
#  - 所有失败分支以非零码退出（不再 echo 后 exit 0）；
#  - 不清空设备全局 logcat；只清理本脚本启动的采集进程；
#  - beats/lock 用例增加"拍数>0 / wall间隔>0"前置判定，杜绝 0 拍假通过；
#  - 新增 alarm（注入步点验证报警触发/解除链路）、training（定时/分段）、
#    duck（与最小播放端共存压低）、lifecycle（连续启停无残留）。
# ============================================================
set -euo pipefail
ADB="${METRO_ADB:-/d/AndroidStduio/Sdk/platform-tools/adb.exe}"
PKG="com.metronome.app"
RCV="$PKG/.DebugReceiver"
DATA_ROOT="${METRO_DATA_ROOT:-/d/APK/running-metronome_Data}"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

# ------------------------------------------------------------ 设备与进程管理
[ -d "$DATA_ROOT" ] || { echo "[错误] 数据目录不存在: $DATA_ROOT"; exit 1; }
command -v "$ADB" >/dev/null 2>&1 || { echo "[错误] ADB 不存在: $ADB"; exit 1; }
if [ -n "${METRO_SERIAL:-}" ]; then
  SERIAL="$METRO_SERIAL"
else
  mapfile -t ONLINE < <("$ADB" devices | awk 'NR>1 {sub(/\r$/, "", $2); if ($2=="device") print $1}')
  [ "${#ONLINE[@]}" -eq 1 ] || {
    echo "[错误] 在线设备数=${#ONLINE[@]}；请先确认目标并设置 METRO_SERIAL=<serial>"
    "$ADB" devices -l
    exit 1
  }
  SERIAL="${ONLINE[0]}"
fi
[ -n "${SERIAL:-}" ] || { echo "[错误] 未指定目标设备"; exit 1; }
echo "目标设备: $SERIAL"
A() { "$ADB" -s "$SERIAL" "$@"; }

SERIAL_SAFE=$(printf '%s' "$SERIAL" | tr -c 'A-Za-z0-9._-' '_')
RUN_ID="$(date +%Y%m%d-%H%M%S)-${SERIAL_SAFE}-$$"
OUT="$DATA_ROOT/autotest-$RUN_ID"
mkdir "$OUT" || { echo "[错误] 无法创建独立日志目录: $OUT"; exit 1; }
echo "日志目录: $OUT"

LOG_PID=""
LOG_FILE=""
LOG_MARKER=""
SESSION_STARTED=0
INJECTION_ACTIVE=0
PROBE_STARTED=0
SCREEN_WAS_AWAKE=0
SCREEN_LOCKED_BY_US=0
cleanup() {
  if [ -n "$LOG_PID" ]; then kill "$LOG_PID" 2>/dev/null || true; wait "$LOG_PID" 2>/dev/null || true; fi
  if [ "$INJECTION_ACTIVE" -eq 1 ]; then A shell am broadcast -a "$PKG.debug.INJECT_STOP" -n "$RCV" >/dev/null 2>&1 || true; fi
  if [ "$PROBE_STARTED" -eq 1 ]; then A shell am broadcast -a "$PKG.debug.PROBE_STOP" -n "$RCV" >/dev/null 2>&1 || true; fi
  if [ "$SESSION_STARTED" -eq 1 ]; then A shell am broadcast -a "$PKG.debug.STOP" -n "$RCV" >/dev/null 2>&1 || true; fi
  if [ "$SCREEN_LOCKED_BY_US" -eq 1 ] && [ "$SCREEN_WAS_AWAKE" -eq 1 ]; then A shell input keyevent 224 >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

# 独立采集：不使用 logcat -c；标记之后的记录才属于本用例。
capture() {  # capture <文件名> <tags...>
  local i
  [ -z "$LOG_PID" ] || fail "上一段 logcat 仍在运行"
  LOG_FILE="$1"; shift
  LOG_MARKER="BEGIN_${RUN_ID}_$(basename "$LOG_FILE" .log)"
  "$ADB" -s "$SERIAL" logcat -T 1 -s MetroTest:I "$@" > "$LOG_FILE" 2>&1 &
  LOG_PID=$!
  A shell log -t MetroTest "$LOG_MARKER" >/dev/null
  for i in 1 2 3 4 5 6 7 8 9 10; do
    if grep -Fq "$LOG_MARKER" "$LOG_FILE"; then return; fi
    sleep 0.2
  done
  fail "未收到 logcat 起始标记: $LOG_FILE"
}

stop_capture() {
  local tmp="$LOG_FILE.current"
  [ -n "$LOG_PID" ] || fail "没有运行中的 logcat 采集"
  kill "$LOG_PID" 2>/dev/null || true
  wait "$LOG_PID" 2>/dev/null || true
  LOG_PID=""
  awk -v marker="$LOG_MARKER" 'index($0, marker) { seen=1; next } seen' "$LOG_FILE" > "$tmp"
  [ -s "$tmp" ] || fail "采集为空或起始标记之后无日志: $LOG_FILE"
  mv "$tmp" "$LOG_FILE"
  LOG_FILE=""
  LOG_MARKER=""
}

fail() { echo "[错误] $1"; exit 1; }
A get-state >/dev/null 2>&1 || fail "设备 $SERIAL 未就绪"

# 非屏幕用例只用 debug 广播配置；不会唤醒/解锁/切换用户界面。
start_at_120() {
  A shell am broadcast -a "$PKG.debug.SET" -n "$RCV" --ei spm 120 \
      --ez sound "${1:-false}" --ez vibrate "${2:-false}" \
      --ez detect false --ez alarm false --ez timer false --ei prepareSec 0 \
      --ez segments false >/dev/null
}

start_session() { A shell am broadcast -a "$PKG.debug.START" -n "$RCV" >/dev/null; SESSION_STARTED=1; }
pause_session() { A shell am broadcast -a "$PKG.debug.PAUSE" -n "$RCV" >/dev/null; }
resume_session() { A shell am broadcast -a "$PKG.debug.RESUME" -n "$RCV" >/dev/null; }
stop_session() { A shell am broadcast -a "$PKG.debug.STOP" -n "$RCV" >/dev/null; SESSION_STARTED=0; }

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
  stop_capture
  awk 'BEGIN{cnt=0;last=-1;first=-1;wFirst=-1;wLast=-1;maxd=0;maxW=0}
  /MetroBeat: beat=/ {
    split($0, a, "audioMs="); split(a[2], b, " "); ms = b[1]+0;
    split($0, e, "wall="); wall = e[2]+0;
    if (first < 0) first = ms;
    if (ms >= first && ms - first < 60000) {
      cnt++;
      if (last >= 0) { d = ms - last; if (d > maxd) maxd = d }
      if (wFirst < 0) wFirst = wall;
      if (wLast >= 0) { dw = wall - wLast; if (dw > maxW) maxW = dw }
      last = ms;
      wLast = wall;
    }
  } END {
    printf "60 秒窗口节拍数: %d（期望 120）, audio最大间隔: %.0f ms, wall最大间隔: %.0f ms, wall跨度: %.0f ms\n", cnt, maxd, maxW, wLast-wFirst;
    if (cnt < 60)     { print "结果: ❌ 未通过（拍数异常少，调度或回调中断）"; exit 1 }
    if (cnt == 120 && maxd <= 510 && maxW > 0 && maxW < 1200 && wLast-wFirst >= 58000 && wLast-wFirst <= 62000) print "结果: ✅ 通过"
    else if (cnt == 120) { print "结果: ❌ 拍数正确，但音频/壁钟间隔或壁钟跨度异常"; exit 1 }
    else { print "结果: ❌ 未通过"; exit 1 }
  }' "$OUT/beats.log" || exit 1
}

# ---------------------------------------------------------- 测试 2: 锁屏不中断
test_lock() {
  echo "=== 测试 2: 锁屏 30 秒节拍是否中断（静音）==="
  local power_state i
  power_state=$(A shell dumpsys power | tr -d '\r' | grep -m1 'mWakefulness=' || true)
  case "$power_state" in
    *Awake*) SCREEN_WAS_AWAKE=1 ;;
    *Asleep*|*Dozing*) SCREEN_WAS_AWAKE=0 ;;
    *) fail "无法读取屏幕初始状态，取消锁屏用例以免意外切换屏幕" ;;
  esac
  start_at_120 false false
  capture "$OUT/lock.log" "MetroBeat:I" "MetroTest:I"
  start_session
  sleep 2
  A shell input keyevent 223  # KEYCODE_SLEEP，避免 toggle 把已息屏手机唤醒
  SCREEN_LOCKED_BY_US=1
  for i in 1 2 3 4 5; do
    sleep 1
    power_state=$(A shell dumpsys power | tr -d '\r' | grep -m1 'mWakefulness=' || true)
    case "$power_state" in *Awake*|"") ;; *) break ;; esac
  done
  case "$power_state" in *Awake*|"") fail "手机未进入息屏状态" ;; esac
  A shell log -t MetroTest LOCK_START
  sleep 30
  A shell log -t MetroTest LOCK_END
  if [ "$SCREEN_WAS_AWAKE" -eq 1 ]; then A shell input keyevent 224; fi
  SCREEN_LOCKED_BY_US=0
  sleep 2
  stop_session
  sleep 2
  stop_capture
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
    if (cnt < 40)          { print "结果: ❌ 未通过（锁屏期间拍数不足，不能证明不中断）"; exit 1 }
    if (windows==1 && maxW > 0 && maxW < 1200) print "结果: ✅ 锁屏未中断"
    else { print "结果: ❌ 有中断（ColorOS 睡眠策略见指南电池设置一节）"; exit 1 }
  }' "$OUT/lock.log" || exit 1
}

# ---------------------------------------------------------- 测试 3: 报警链路（注入步点）
test_alarm() {
  echo "=== 测试 3: 注入步点验证偏慢报警及提示 PCM 提交（不代表真实跑步传感器或耳机听感）==="
  # 场景：6s @180 → 切 100 SPM → 配置等待 5s 后报警并提交提示 PCM
  #       再切回 180 → 恢复确认后取消提示；日志时间是音频写入时间，不是出声时间。
  start_at_120 true false
  A shell am broadcast -a "$PKG.debug.SET" -n "$RCV" --ez detect true --ez alarm true --ei alarmAfter 5 \
      --ei slowMargin 8 --ei recoverMs 600 --ei repeatSec 2 --ei alarmStyle 0 --ei volume 30 --ez timer false >/dev/null
  capture "$OUT/alarm.log" "MetroState:I" "MetroBeat:I" "MetroFocus:I" "MetroAlarm:I" "MetroPrompt:I"
  start_session
  A shell log -t MetroTest FAST_BEGIN
  A shell am broadcast -a "$PKG.debug.INJECT" -n "$RCV" --ei spm 180 --ei durationMs 6000 >/dev/null
  INJECTION_ACTIVE=1
  echo "-- 快跑 6 秒（180 SPM 注入）"
  sleep 6.5
  A shell am broadcast -a "$PKG.debug.STATUS" -n "$RCV" >/dev/null
  echo "-- 切慢跑 100 SPM 12 秒（应触发报警）"
  A shell log -t MetroTest SLOW_BEGIN
  A shell am broadcast -a "$PKG.debug.INJECT" -n "$RCV" --ei spm 100 --ei durationMs 12000 >/dev/null
  sleep 12.5
  A shell am broadcast -a "$PKG.debug.STATUS" -n "$RCV" >/dev/null
  echo "-- 切回 180 SPM 4 秒（应解除报警）"
  A shell log -t MetroTest RECOVERY_BEGIN
  A shell am broadcast -a "$PKG.debug.INJECT" -n "$RCV" --ei spm 180 --ei durationMs 4000 >/dev/null
  sleep 4.5
  A shell am broadcast -a "$PKG.debug.STATUS" -n "$RCV" >/dev/null
  stop_session
  sleep 1
  A shell am broadcast -a "$PKG.debug.INJECT_STOP" -n "$RCV" >/dev/null
  INJECTION_ACTIVE=0
  stop_capture
  ALARM_AT=$(grep -c "detector:.*alarm=true" "$OUT/alarm.log" || true)
  echo "STATUS 快照中的 detector 行:"
  grep "detector:" "$OUT/alarm.log" || true
  [ "$ALARM_AT" -ge 1 ] || fail "未观察到报警触发"
  grep "detector:" "$OUT/alarm.log" | tail -1 | grep -q "alarm=false" || fail "最后快照仍报警"
  grep -q 'MetroAlarm: enter ' "$OUT/alarm.log" || fail "没有报警进入时间证据"
  grep -q 'MetroAlarm: exit ' "$OUT/alarm.log" || fail "没有报警解除时间证据"
  grep -q 'MetroPrompt: pcm-submitted ' "$OUT/alarm.log" || fail "报警未提交短提示 PCM"
  grep -q 'MetroPrompt: fade-submitted ' "$OUT/alarm.log" || fail "解除时未提交提示淡出 PCM"
  echo "结果: ✅ 报警触发、提示 PCM 提交、解除与淡出均有证据"
  awk '/MetroAlarm: (enter|exit) |MetroPrompt: (pcm-submitted|fade-submitted) / { if (++shown <= 12) print }' "$OUT/alarm.log"
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
  stop_capture
  echo "状态转移记录:"
  grep "state=session" "$OUT/training.log" || true
  SEG=$(grep -c "segment-enter" "$OUT/training.log" || true)
  FIN=$(grep -c "state=session-finished" "$OUT/training.log" || true)
  SEG_SEQ=$(awk '/state=segment-enter/ { seg=""; round=""; for(i=1;i<=NF;i++){ if($i ~ /^seg=/){split($i,a,"=");seg=a[2]} if($i ~ /^round=/){split($i,a,"=");round=a[2]} } printf "%s%s:%s", (n++ ? " " : ""),seg,round } END{print ""}' "$OUT/training.log")
  echo "段切换次数: $SEG（期望 4：0:1 1:1 0:2 1:2），实际顺序: $SEG_SEQ"
  echo "结束提示记录: $FIN（期望 1，不自动转正计时）"
  if [ "$FIN" -eq 1 ] && [ "$SEG" -eq 4 ] && [ "$SEG_SEQ" = '0:1 1:1 0:2 1:2' ] && \
      grep -q 'state=session-finished byCap=true.*clock=false.*session=FINISHED' "$OUT/training.log"; then
    echo "结果: ✅ 通过"
  else
    echo "结果: ❌ 未通过"
    exit 1
  fi
}

# ---------------------------------------------------------- 测试 5: 与音乐共存压低
test_duck() {
  echo "=== 测试 5: 先受控播放器后节拍（duck）；先节拍后播放器（永久夺焦点后静音）==="
  start_at_120 true false
  capture "$OUT/duck.log" "MetroFocus:I" "MetroDuck:I" "MetroState:I"
  A shell am broadcast -a "$PKG.debug.PROBE_STOP" -n "$RCV" >/dev/null
  echo "-- A: 先启动 DuckProbe（模拟音乐，申请 GAIN），再启动节拍（MAY_DUCK）"
  A shell am start -n "$PKG/.DuckProbeActivity" >/dev/null 2>&1
  PROBE_STARTED=1
  sleep 2
  start_session
  sleep 8
  A shell am broadcast -a "$PKG.debug.STATUS" -n "$RCV" >/dev/null
  A shell log -t MetroTest DUCK_PHASE_A_END >/dev/null
  echo "-- B: 明确结束 probe 后重新启动（节拍应收到 LOSS 并静音，需手动恢复声音）"
  A shell am broadcast -a "$PKG.debug.PROBE_STOP" -n "$RCV" >/dev/null
  sleep 2
  A shell am broadcast -a "$PKG.debug.STATUS" -n "$RCV" >/dev/null
  A shell log -t MetroTest DUCK_PHASE_B_START >/dev/null
  A shell am start -n "$PKG/.DuckProbeActivity" >/dev/null 2>&1
  sleep 6
  A shell am broadcast -a "$PKG.debug.STATUS" -n "$RCV" >/dev/null
  A shell log -t MetroTest DUCK_PHASE_B_END >/dev/null
  stop_session
  A shell am broadcast -a "$PKG.debug.PROBE_STOP" -n "$RCV" >/dev/null
  PROBE_STARTED=0
  sleep 1
  stop_capture
  echo "焦点链路记录（MetroFocus / MetroDuck）:"
  grep -a -E "MetroFocus|MetroDuck" "$OUT/duck.log" | head -40 || true
  awk '/MetroTest: DUCK_PHASE_A_END/{exit} {print}' "$OUT/duck.log" > "$OUT/duck-phase-a.log"
  awk '/MetroTest: DUCK_PHASE_B_START/{on=1;next} /MetroTest: DUCK_PHASE_B_END/{exit} on{print}' \
      "$OUT/duck.log" > "$OUT/duck-phase-b.log"
  if grep -aFq 'probe started' "$OUT/duck-phase-a.log" && \
     grep -aFq 'request GAIN -> 1' "$OUT/duck-phase-a.log" && \
     grep -aFq 'focus change: LOSS_TRANSIENT_CAN_DUCK' "$OUT/duck-phase-a.log" && \
     grep -aFq 'request MAY_DUCK -> granted=1' "$OUT/duck-phase-a.log" && \
     grep -aEq 'state=debug-status.*session=RUNNING' "$OUT/duck-phase-a.log"; then
    echo "阶段 A: ✅ 受控播放器收到 duck，节拍保持运行"
  else
    echo "阶段 A: ❌ 未证明先音乐后节拍的 duck 链路"
    exit 1
  fi
  # 设计语义：被 GAIN 永久夺焦点后 App 主动放弃申请并静音（通知提供"恢复声音"
  # 手动入口），系统不会在对方放弃后自动回调 GAIN——恢复以手动重申请为准。
  if grep -aFq 'probe started' "$OUT/duck-phase-b.log" && \
     grep -aFq 'request GAIN -> 1' "$OUT/duck-phase-b.log" && \
     grep -aFq 'focus LOSS -> muted' "$OUT/duck-phase-b.log" && \
     grep -aEq 'state=debug-status.*session=RUNNING' "$OUT/duck-phase-b.log"; then
    echo "阶段 B: ✅ 永久夺焦点后节拍静音，训练计时保持运行（需手动恢复声音）"
  else
    echo "阶段 B: ❌ 未证明先节拍后播放器的静音链路"
    exit 1
  fi
  echo "说明：DuckProbe 是受控测试播放端，未据此判断真实音乐 App 的听感或精确压低比例。"
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
  PID=$(A shell pidof "$PKG" | tr -d '\r' || true)
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
