# Foldable Capture UI/UX rebuild plan

状态：IMPLEMENTED & SIMULATOR-VERIFIED（独立计划审查：Raman，2026-11-04；运行时实施与双模拟器验证：2026-11-05）

基线：`origin/main` / `bd41553`，另有当前 Design C 资产快照 `a2d5f6b`。

目标：在不带入上一轮相机和折叠屏实现 patch 的前提下，使用 CameraX 重建 capture 前端；Design C 作为内屏静态设计标准，运行时由 WindowMetrics、Insets、铰链/分隔区域、姿态和 CameraX 实际输出共同决定布局。

## 统一几何与旋转第一性原理（2026-11-05 确立）

1. **受限维不裁剪顶满（Full-Span Uncropped Preview）**：
   - 在任何屏幕形态下，取景器均保持 CameraX 实际交付构图（`PHOTO` `3:4`/`4:3`，`VIDEO` `9:16`/`16:9`），不做二次 UI 像素裁切。
   - **内屏正位（`InnerUpright`）**：竖向取景器上下 `100%` 顶满屏幕全高（`y = 0 .. hDp`），正交布局轴为横向（左侧副工具栏 ↔ Preview ↔ 右侧主操作区）。
   - **内屏顺时针 90°（`InnerCW90`）**、**传统竖屏手机**与**折叠屏外屏（`Cover`）**：取景器左右 `100%` 顶满屏幕全宽（`x = 0 .. wDp`），正交布局轴为纵向（顶部副工具栏 ↔ Preview ↔ 底部主操作区）。
2. **统一侵入优先级方程（先侵入副工具栏，吃满后才侵入主操作区）**：
   - 在所有屏幕形态的正交布局轴上，取景器起点由同一个纯函数 `CaptureGeometryCalculator.computePreviewStartAlongLayoutAxis` 决定：
     $$\text{previewStart} = (\text{primaryZoneStartDp} - \text{previewSpanDp}).\text{coerceIn}(0\text{dp}, \text{utilityEndDp})$$
   - **空间充裕**（$\ge \text{utilityEndDp}$，如 `Fold8` `960×720`、`Fold8 Ultra` `960×864`）：副工具栏与主操作区均不被侵入，与 `foldable-c-geometry-source.json` 零误差对齐。
   - **空间中等**（$0 \le \text{primaryZoneStartDp} - \text{previewSpanDp} < \text{utilityEndDp}$，如 `Pixel 9 Pro Fold` 近正方形内屏 `852×883`、`recon-api36` 传统手机 `411×914`、`360×800` 手机）：取景器优先向副工具栏方向推进并开启轻量边缘渐层（`showTopHeaderScrim` / `showLeftUtilityScrim`），使高频交互的主操作区（变焦、快门、闪光灯、队列）`100%` 留在纯净黑底内，零侵入。
   - **空间紧凑**（$< 0$，如 `360×576` 短外屏或手机 `9:16` 视频）：取景器先顶到 `0dp` 吃满副工具栏，剩余超出部分才侵入主操作区并开启主控对比度渐层（`controlsOverlayPreview`）。
3. **自适应旋转与重排边界**：
   - **传统手机与闭合外屏（`smallestScreenWidthDp < 600`）**：锁定竖屏窗口布局（`SCREEN_ORIENTATION_PORTRAIT`），横握手机时布局不重排；长条形组件（`ModalityPill`、`VideoRecordingPill`）保持水平原地不动，方形/圆形图标（`Settings`、`Bundles`、`Flash`/`Mic`、`Flip`、`Zoom` 数字）随 `OrientationEventListener` 原地旋转。
   - **展开大内屏（`smallestScreenWidthDp >= 600`）**：解锁方向（`SCREEN_ORIENTATION_UNSPECIFIED`），随姿态在 `InnerUpright`（右侧竖向队列 `QueueAxis.Vertical`）与 `InnerCW90`（底部横向队列 `QueueAxis.Horizontal`）之间重排。

## 已确认的问题

1. 相机绑定、解绑、录制和预览 surface 分散在 `CaptureViewModel`、`CaptureScreen`、`CameraPreview` 和 `CaptureController`，没有单一 session owner。
2. `CaptureScreen` 同时承担路由、权限、状态呈现、手势、音效、队列反馈和布局，无法让 Design C 成为统一的组合模型。
3. 布局需要从测量窗口和姿态产生一个几何结果；不能依赖固定宽高阈值或独立 portrait/landscape UI 树。
4. PHOTO、VIDEO、VOICE 的状态和录制 finalize 需要明确区分；视频/语音不得通过 UI 偶然行为触发快门音。
5. 纯函数测试不能替代真实 Compose 手势、CameraX binding 和折叠/旋转设备验收。

## 根机制

### M1：CameraX session ownership 不清

不变量：只有一个 CameraX session owner 负责 provider、Preview、ImageCapture、VideoCapture、bind/unbind、录制和输出状态；UI 只提交意图并显示状态。

实现契约：`CaptureTakeCoordinator` 以 `TakeId` 标识一次 PHOTO/VIDEO/VOICE take，生命周期跨越 UI 重组但不跨越已完成的 take。所有结束原因（用户 Stop、CameraX Finalize、稳定的 Inner/Cover 改变、真正 background、权限/设备错误）进入同一个 reducer；`finalizeOnce(TakeId)` 由 Mutex/原子状态保证只向 staging/order log 写入一次。旋转、resize 和同一 surface 的 fold posture 改变只更新 presentation，不触发 finalize。事件优先级为 process-stop/background > stable surface change > user stop/finalize callback > rotation/resize presentation update，并用交错测试覆盖重复和乱序回调。

宿主策略：采用 `configChanges="orientation|screenSize|smallestScreenSize|screenLayout"` 与 `resizeableActivity=true`，由 `MainActivity.onConfigurationChanged` 和 WindowMetrics adapter 把变化送入 `HostEventClassifier`；普通旋转/resize 不重建 Activity。若发生真正的 Activity recreation，AppContainer 持有的 coordinator 先解绑旧 `LifecycleOwner`/Preview surface，再由新 host 显式 attach；coordinator 不由 Compose 或普通 Activity 字段创建。离开 capture route 时停止未完成 take 并释放相机，process death 依赖 staging manifest/orphan recovery，不能依赖内存状态。

### M2：Design C 没有运行时几何边界

不变量：唯一的 `CaptureGeometry` 由测量输入计算，输出 preview、toolbar、controls、queue、queue axis 和 pose；Compose 不再自行推导第二套位置。

### M3：Capture UI 是多职责状态容器

不变量：ViewModel 负责 capture intent/state，geometry 负责纯布局，CameraX session 负责相机能力，queue 负责 staged items 和交互反馈。

### M4：验收层级不完整

不变量：每个相机/布局里程碑都必须有纯逻辑测试、Compose 交互测试或设备证据；历史文档中的通过描述不作为当前证据。

存储边界：每个 take 写入 `TakeRecord`：`TakeId`、modality、ordinal、tempName、finalName、startedAt 和 `Prepared → Recording → Finalizing → Finalized/Aborted` 状态。Prepared 时以 TakeId 幂等写入 order log 以保留 PHOTO/VIDEO/VOICE 的开始顺序；finalize 时只允许一次 temp-to-final rename、媒体可读性检查和 staged-item 生成，重复 callback 只能读取已完成结果。失败、半成品 `.mp4`/`.m4a`、进程死亡和重复 callback 必须由现有 orphan recovery 按状态恢复或清理，不得产生重复 order entry。

## 实施里程碑

### Milestone 0：基线与设计冻结

- 保留 `origin/main` 代码基线。
- 保留当前 Design C docs-only 快照。
- 确认 `testDebugUnitTest` 和 `assembleDebug` 通过。
- 记录新的宿主策略：去除 portrait lock、启用 resizable/configChanges，并在 MainActivity 建立 host-event bridge；在此之前不开始 CameraX/Compose 重构。

### Milestone 1：宿主旋转与 CameraX session 边界

- 移除 `android:screenOrientation="portrait"`，将 capture host 设为 resizable，并明确 handled orientation/size changes 与真实 background 的边界。
- 抽出可测试的 CameraX session contract。
- 将 bind/unbind、use case、rotation、flash、zoom 和 recording ownership 收归 session。
- UI 不再直接操作 controller 的生命周期；旧 `CaptureController` 只有在所有调用迁移并通过回归测试后才能退役。
- 明确 CameraX framing contract：Preview/ImageCapture/VideoCapture 的 aspect ratio、target rotation、PreviewView scale type、delivered resolution/crop 和保存输出必须使用同一坐标约定；不能用第二套 UI crop 补偿 CameraX。
- Voice 使用独立的音频 session，但由同一个 capture take coordinator 统一处理 start/stop/finalize-once、权限失败和 Activity pause；不能把 Voice 当作 CameraX video 的隐式变体。
- 用 fake session/provider 覆盖 bind failure、PHOTO capture、VIDEO start/stop、VOICE start/stop 和重复 finalize。
- 增加 `HostEventClassifier`：`onConfigurationChanged`/WindowMetrics resize/rotation 归为 presentation update，稳定的 Inner↔Cover 归为 surface change，`onStop` 且非 configuration change 归为 background；`onPause` 本身不得直接 finalize。
- 在 `gradle/libs.versions.toml` 与 `app/build.gradle.kts` 显式加入 `androidx.window:window:1.5.0`，并建立 `WindowObservation` 接口、settling/unknown 处理、hinge bounds、active display 和 surface resolver；不能从缺失 FoldingFeature 推断 Cover。
- `CaptureTakeCoordinator` 由 `AppContainer` 唯一创建并持有；capture route attach 当前 `LifecycleOwner` 和 Preview surface，route 离开执行 detach/release，Activity recreation 执行旧 host → 新 host handoff，`onCleared` 不再是唯一清理路径。
- 将 binding capability 建模为 `Unavailable`、`PhotoOnly`、`PhotoVideo`，带有原因和当前绑定 token。默认先尝试 Preview+ImageCapture+VideoCapture 的同一 ViewPort；失败时显式进入 PhotoOnly，禁用/说明 VIDEO，不静默显示可用 VIDEO，也不保留旧 use case。不同 output ratio 必须通过新的 idle binding plan 明确切换，禁止录制中重绑。

验收：CameraX 仍是 PHOTO/VIDEO 唯一相机实现；状态转换可被 JVM 测试观察；实际绑定结果可由 fake provider 的 use-case 集合验证；现有 storage/pipeline 行为不改变。

### Milestone 2：Design C 几何模型

- 建立统一的 measured window/posture input，并明确所有输入输出均使用 window-pixel 坐标；dp 设计值只通过 density 转换进入计算。
- 输入必须包含 window bounds、safe/gesture insets、separating/occluding hinge、surface、posture、active display、settled display rotation、CameraX delivered resolution/crop/rotation 和当前 modality。
- 输出 Design C 的 preview、controls、queue、toolbar、gesture bounds、queue axis 和 pose；`queueAxis` 是结果字段，不由 Queue composable 猜测。
- upright queue 为 vertical；CW90 queue 为 horizontal below preview。
- `QueueAxis.Vertical` 时，底部 handle 向上 save、顶部 handle 向下 discard；`QueueAxis.Horizontal` 时，左 handle 向右 save、右 handle 向左 discard。队列 reorder/删除/分割的主轴和垂直删除轴必须由同一个 axis contract 解释。
- 保持 48dp 最小触控目标和 56dp 标准控件尺寸；短窗口只使用明确的安全 fallback。
- Preview 以 CameraX `ResolutionInfo` 的 delivered crop/rotation 为依据，`PreviewView` 负责 CameraX transform；几何层只决定可用矩形和 overlay，不重复裁剪像素。

验收：几何函数覆盖 upright、CW90、hinge/insets、短窗口、camera aspect ratio、rotation 和 crop contract；不再存在两个 UI 树分别推导几何。

### Milestone 3：单一 CaptureScreen composition

- 用统一 shell 渲染 preview、controls、queue 和 overlay。
- 将权限、modality、recording feedback、shutter policy 和 queue commands 通过 state/intents 接入。
- PHOTO 才允许 shutter click；VIDEO/VOICE 使用 haptic/视觉反馈。
- `QueueStrip(axis = geometry.queueAxis, ...)` 必须真正按 axis 改变布局、滚动、edge handle、reorder 和语义动作；不能只改变外层矩形。upright 与 CW90 各有 Compose 可见性、触控和无障碍测试。

验收：Compose 测试验证关键语义、可见控件和 modality lockout；手势不会因 tap-to-focus/pinch 而丢失。

### Milestone 4：折叠/旋转生命周期与可执行的设备测试

- 通过 WindowManager/WindowMetrics 产生姿态和 surface observation。
- 旋转继续当前 take；内外屏切换 finalize 一次。
- 未能证明 surface 的状态不得伪装成稳定的 Cover/Inner。
- `SurfaceResolver` 必须有明确正向证据来源：测试/模拟器注入的 surface hint、设备集成提供的 display-role mapping，或经验证的 OEM adapter；只有 hinge 缺失、display 变化或姿态本身时保持 `Unknown`，不能推断 Cover。Unknown 时继续当前 take 的规则与 stable Inner/Cover 切换分开测试。
- 建立 `app/src/androidTest` 的最小执行面，至少包含 Activity orientation/resize、CameraX binding smoke 和 Compose semantic interaction 的可运行测试入口；没有设备时只报告未执行，不把源码测试替代设备证据。
- 最低 connected/Compose 集合为：Activity orientation/resize、Window posture/surface 注入、CameraX binding smoke、upright queue、CW90 queue、fold/rotation/finalize-once、PHOTO/VIDEO/VOICE 声音策略；每项都有明确的设备缺失/相机 provider 缺失结果。

验收：classifier 单测 + 模拟器折叠/旋转证据 + 可执行的 connected test；设备缺失时明确标记为未完成。

### Milestone 5：真实验收与收尾

- regular API 36 和 fold API 36 connected tests。
- 真实 CameraX PHOTO、VIDEO、VOICE、队列选择/删除和声音验收。
- 新鲜 reviewer 检查最终 diff、测试、文档和未验证边界。

## 实施规则

- 每个里程碑单独提交，禁止把不相关的 storage、LocalSend 或设计文档修改混入。
- 不通过测试删减或放宽验收标准。
- 若需要改变产品行为，先停止该里程碑并记录决策。
- 只有在新 session、geometry、UI intent 和测试入口均接管后，才删除旧 controller 的兼容路径。
- 设计文档中的“当前实现/测试通过”陈述与新基线不一致时，保留设计资产，但在实现状态文档中明确标为历史记录并重新记录证据。
- 编译通过不等于 CameraX 或折叠屏完成；设备证据单独记录。

## 计划审查与实施验证记录

- `01a1a639-441c-70c0-bb14-2a582b44b774`：REVISE。指出宿主旋转、finalize-once、CameraX capability、QueueAxis、WindowManager、androidTest 与 crash consistency 缺口。
- `01a1a63f-a0ec-7ab0-8013-e1f5e22c55b0`：APPROVED。确认上述 blocking gaps 已进入计划；批准进入 Milestone 0。该批准只针对计划，不代表实现或设备验收完成。
- `2026-11-05` 实施与模拟器/真机验证记录：
  - **静态原型与几何契约校验**：`layout-screens.mjs --check`、`build-standalone.mjs --check`、`foldable-c-geometry.mjs --check`、`foldable-c-study.py --check`、`ultra-c-study.py --check`、`ultra-c-cw90-study.py --check`、`prototypes-app-ui/build.py --check` 全部通过。
  - **纯 JVM 单元测试（`./gradlew :app:testDebugUnitTest`）**：156/156 通过（含 `CaptureTakeCoordinatorTest`、`HostEventClassifierTest`、`CaptureGeometryTest`、`QueueGestureArbitrationTest`、`ShutterSoundPolicyTest`、`BundleRowSwipeTest`、`BundleSelectionLogicTest`）。
  - **Android 模拟器 Compose 与连接测试（`./gradlew :app:connectedDebugAndroidTest`）**：在 `recon-api36`（常规手机 API 36）与 `recon-fold-api36`（折叠屏 API 36）上各执行 8/8 全部通过（`CaptureScreenComposeTest`、`BundlePreviewComposeTest`、`CaptureActivityRotationAndBindingTest`）。
  - **折叠屏真机实测与精调记录**：
    - `3dfd7f5` & `efb6f71`：真机 `CW90 ↔ CCW90` 直接 180° 翻转通过 `DisplayManager.DisplayListener` 同步 `display.rotation`，并在展开内屏开启系统自动旋转时固定 `contentRotation = 0f`，消除传感器早于 `WindowManager` 触发导致的两段式图标预转抖动。
    - `4856fc6`：真机外屏（Cover）队列遵循 margin-first 容量法则（仅扣除左右安全边距 `safeLeftDp`/`safeRightDp`，移除 `312dp`/`360dp` 硬上限）。
    - `e57a29b`：队列保存/丢弃滑动动效合并为与缩略图同高（`56dp`/`48dp`）、`8dp` 圆角的单一胶囊渐变层；分割手势热区扩展至 `28dp` 宽（向两侧缩略图各侵入 `11dp`）并在交叉轴上下各扩展 `8dp` 触控余量，在 `PointerEventPass.Initial` 提前于 `3dp` 交叉轴位移锁定手势。
