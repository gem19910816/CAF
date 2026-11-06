# CAF BT-7274 · BB30

Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21 的独立模组，modId 为 `bt7274`，当前版本 `2.1.0-bb30`。这份源码对应 2026-11-06 安装到「齿轮与腐肉 0.6 内测版」的 BB30，不能按仓库其他 Forge 1.20.1 / Java 17 模块构建。

## 内容

- 自制方块机体模型与运行资源；原作动作和声音的本地适配资源。
- 信标召唤、登出舱、玩家驾驶、跟随/警戒、机炮、锁定导弹、普通近战、Vortex、电烟、核心与弹射的服务端逻辑。
- 第一人称、第三人称、音效、战斗反馈及客户端输入代码。
- BB28 登出舱、BB29 地形与停步过渡、BB30 身体/脚步声/座舱步态同步修订。

## 构建

安装 JDK 21，在 PowerShell 中设置其实际路径后构建：

```powershell
$env:JAVA_HOME = '你的 JDK 21 安装目录'
.\gradlew.bat clean build
```

首次构建需要网络下载 Gradle、NeoForge 和依赖；缓存齐全后可加 `--offline`。不要把原电脑的 JDK 绝对路径写回 `gradle.properties`。产物为 `build/libs/CAF-BT7274-Cube-1.21.1-2.1.0-bb30.jar`。

已安装发布 JAR 的 SHA-256：

```text
2d52ff5e997b17e08669aece19db3e387a4fb98e3d09aff5bd20dcb7080e661a
```

`docs/source_manifest.json` 记录本次入库源码与发布来源；构建时间等 ZIP 元数据可能使重新构建的 JAR 文件哈希不同。`src/main`、`src/test`、`src/qa` 与 BB30 本地工程逐文件核对，仓库副本只移除了机器专用 JDK 路径。

## 检查与实机 QA

离线数值审计任务：`gaitClockAudit`、`movementFeedbackAudit`、`fpSprintAudit`、`cubeNumericalAudit`、`meshNumericalAudit`。后两者中的 mesh 命名属于历史内部命名，不表示机体采用原版游戏网格。最终几何来源由 `cube-provenance.json` 和构建时哈希检查约束。

`src/qa` 默认不进入生产编译；`run_bb*.init.gradle` 显式启用隔离实机检查。运行这些脚本会启动 Minecraft 并使用 `BT_MESH_QA` 测试存档，不能对玩家正式存档直接运行。QA 完成后重新 `clean build`，不要发布启用过 QA 源集的产物。

`docs/BB30_座舱与跑步同步说明.md` 记录此前隔离客户端与数值检查的范围及边界；本次上传不表示重新在完整整合包里跑过所有功能。

## 当前限制

- 《泰坦陨落2》原版 HUD 素材已另行研究提取，但本模块尚未接入原版 HUD。
- 机炮目前免费换弹，导弹只有冷却，没有真实弹药库存；自动驾驶同样没有耗弹成本。整合包工业链接入仍待实现，详见 `docs/research/`。
- 普通近战默认 V；双实体同步处决未实现，自动驾驶尚无主动近战，近战遮挡判定仍需完善。
- 未增加普通驾驶低头显示机体腿部的独立层。
- 本模块带运行需要的适配资源；没有上传原游戏安装、全量解包素材、提取工具、测试存档、缓存或构建产物。原作资源不随自制代码转为开放许可，仓库用途为本地整合包维护。

游戏 `mods` 中只保留一个提供 `bt7274` 的版本，避免旧 1.0.0 / BB27 与 BB30 并存。
