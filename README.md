# 跑步节拍器 (Running Metronome)

专为跑者步频控制与节奏训练设计的轻量级、高精度 Android 节拍器应用。

<p align="center">
  <img src="docs/images/metro_ui.png" alt="Running Metronome UI" width="320"/>
</p>

---

## 🌟 核心特性

- **硬件级零漂移采样时钟**：
  - 区别于普通应用使用 `Handler` 或 `Timer` 带来的系统级计时抖动，本项目采用 `AudioTrack` 的 PCM 采样帧计数驱动节拍（48kHz 采样率），步频间隔与音频硬件物理时钟绝对对齐，零漂移、零累积误差。
- **全天候后台与锁屏保活**：
  - 基于 Android 前台服务（`mediaPlayback`）与 `MediaSession`，搭配精准 `WakeLock` 管理，实测有效抵御 ColorOS / 国产深度定制系统的睡眠冻结机制。
- **实时步频（SPM）精准检测**：
  - 接入硬件底层 `Sensor.TYPE_STEP_DETECTOR` 步数中断，利用硬件纳秒时间戳（`event.timestamp`）与滤波窗口，实时计算跑者当前真实步频。
- **掉速智能长音预警机制**：
  - 当跑者实际步频明显落后于设定目标步频达 5 秒以上时，节拍声自动无缝切换为 440Hz 连续正弦长音提醒；当跑者提速跟上目标节奏后，瞬间解除报警并恢复清脆节拍。
- **丰富的触觉与听觉反馈**：
  - 内置 4 种经典合成音色（咔嗒、哔声、木鱼、牛铃）以及自定义音频导入。
  - 支持线性马达振动波形调节（`VibrationEffect` 振幅与脉宽控制）。
- **实时显示与交互**：
  - 界面直观展示实时步频、节拍进度及预警状态。

---

## 📱 运行环境与机型验证

- **最低支持**：Android 8.0 (API 26) 及以上
- **推荐系统**：Android 14 / Android 15
- **重点实测机型**：OnePlus 13 (PJZ110, Snapdragon 8 Elite, ColorOS 15)

---

## 🛠️ 构建与安装

### 命令行编译

```bash
# 编译 Debug APK
./gradlew assembleDebug

# 安装到连接的设备
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

---

## 📂 架构概览

```text
com.metronome.app/
├── MainActivity.kt        # 主界面交互与实时状态观察
├── MetronomeService.kt    # 前台音频时钟服务与长音混音引擎
├── MetronomeEngine.kt     # 核心状态流与参数单例管理
├── StepTracker.kt         # 硬件步数检测、纳秒级 SPM 计算与迟滞预警状态机
└── SoundBank.kt           # PCM 合成音色库与自定义音频解码
```

---

## 📄 开源许可

本项目遵循 MIT 协议开源。
