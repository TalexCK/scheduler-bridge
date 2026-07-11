# Scheduler Bridge

Scheduler Bridge 连接 Minecraft 服务端、Velocity 与宿主机上的
`server-scheduler`。游戏服 Bridge 负责 READY 和心跳上报；Velocity Bridge 负责
动态注册后端服务器、同步玩家状态、执行传送任务并提供 `/ping`、`/hub` 和
`/network`；Paper 平台还向其他插件暴露异步的服务器管理接口。

Bridge 不保存独立连接配置。运行所需参数由 Scheduler 启动实例时通过环境变量注入。
Velocity 模块将 ViaVersion 声明为必需依赖，用它完成动态后端的真实协议探测。

## 通信方式

Bridge 通过 Scheduler 仅监听 `127.0.0.1` 的 HTTP API 通信，并在所有请求中使用
`Authorization: Bearer <bridge.token>`。

Scheduler 注入以下变量：

```text
SCHEDULER_BRIDGE_URL
SCHEDULER_BRIDGE_TOKEN
SCHEDULER_SERVER_ID
SCHEDULER_INSTANCE_ID
SCHEDULER_SERVER_PORT
SCHEDULER_IDLE_TIMEOUT_SECONDS
SCHEDULER_TRANSFER_TIMEOUT_SECONDS
```

Bridge 使用的接口包括：

```text
POST /bridge/v1/ready
POST /bridge/v1/heartbeat
POST /bridge/v1/idle
POST /bridge/v1/players
GET  /bridge/v1/players
GET  /bridge/v1/definitions
GET  /bridge/v1/games
GET  /bridge/v1/solo/access
GET  /bridge/v1/solo/sessions
GET  /bridge/v1/solo/session-index
GET  /bridge/v1/servers
POST /bridge/v1/solo/<game-id>/launch
POST /bridge/v1/solo/<game-id>/destroy
POST /bridge/v1/solo/sessions/<session-id>/start
POST /bridge/v1/servers/<server-id>/launch
POST /bridge/v1/servers/<server-id>/transfers
POST /bridge/v1/servers/<server-id>/terminate
POST /bridge/v1/servers/<server-id>/restart
GET  /bridge/v1/servers/<server-id>/logs?lines=100
POST /bridge/v1/servers/<server-id>/command
GET  /bridge/v1/transfers
POST /bridge/v1/transfers/result
```

POST 请求采用 `application/x-www-form-urlencoded`。服务器列表和传送任务使用 TSV
文本，便于 Java 8 兼容的公共客户端直接解析。

Solo 启动请求使用 `owner=<uuid>&players=<uuid,...>`，请求列表必须包含所有者。`shared`
每次只用该列表执行本次传送，实例本身不冻结名单；`player_world` 才在创建时冻结名单。
销毁请求使用 `player=<uuid>`，客户端使用独立的 90 秒读取超时，以覆盖运行中实例的安全
停服和持久化。`/bridge/v1/solo/access` 每行依次为 Base64 服务器 ID、
实例 UUID、`open|roster` 策略和逗号分隔的玩家 UUID。`open` 允许任意玩家进入当前实例，
`roster` 只允许名单内玩家。旧三列记录按 `roster` 处理；旧两列记录因缺少实例 UUID，
只会形成拒绝访问的历史标记，不会获得注册权限。非法策略和互相冲突的记录会导致本轮
刷新失败并隔离 Solo 后端。`/bridge/v1/games` 的扩展字段包含 `solo`、模式、启动策略、
最大人数和保留天数；客户端仍兼容旧的 12 列目录记录。

`/bridge/v1/solo/sessions` 保留完整 JSON 会话列表。版本化的
`/bridge/v1/solo/session-index` 每行依次为 Base64 游戏 ID、Base64 会话 ID、所有者 UUID
和逗号分隔的冻结玩家 UUID。大厅插件使用这份只读索引区分已有存档与尚未创建的世界，
不会通过创建请求试探存档是否存在。

`POST /bridge/v1/solo/sessions/<session-id>/start` 使用
`players=<comma-separated-uuid-list>`，只启动指定的现有会话并只传送请求名单中的在线成员。
请求名单必须非空且全部属于冻结名单；会话不存在时返回 `404`，不会创建新世界。返回值
为 5 列实例 TSV。

## 游戏服状态上报

Paper Bridge 在 `ServerLoadEvent` 后上报 READY，Fabric Bridge 在服务端
`SERVER_STARTED` 生命周期事件后上报 READY。首次成功上报后，Bridge 每 10 秒发送
一次心跳。

Scheduler 以 `SCHEDULER_SERVER_ID` 和 `SCHEDULER_INSTANCE_ID` 校验上报目标。实例
只有在 READY 后才会进入可注册、可传送状态。Bridge 请求失败时会记录英文错误，并在
后续周期重新尝试 READY。

非自动启动的子服务器通过 `SCHEDULER_IDLE_TIMEOUT_SECONDS` 获取空服超时。Bridge 在
服务端记录玩家加入和退出；只要有玩家进入就立即取消当前计时，最后一名玩家离开后
重新计时。连续空服 300 秒时，Bridge 携带实例 UUID 通知 Scheduler 异步关服。
Proxy 与 Lobby 的值为 `0`，不启用这条逻辑。

## Velocity 功能

Velocity Bridge 每 2 秒读取 Scheduler 的实例列表。对于状态为 READY 的普通子服务，
它使用 Scheduler 分配的 `127.0.0.1:<port>` 地址注册 Velocity 后端；实例不再 READY
时，Bridge 会移除由它管理的注册项。Velocity 自身不会被注册为后端服务器。

检测到新的 READY 后端时，Bridge 会清除 ViaVersion 对该服务器的旧协议缓存并调用
ViaVersion 的后端探测。Bridge 只在 ViaVersion 的实际探测缓存包含该服务器后才允许
连接，配置文件中的默认协议不会被当作探测完成。ViaVersion 未完成时，传送任务保留在
Velocity 内存队列中，每 50 毫秒检查一次；探测成功后立即连接，不使用固定等待时间。

玩家传送流程如下：

1. Scheduler 在目标实例 READY 后生成传送任务。
2. Velocity Bridge 每秒拉取待传送任务并保存在本地暂存队列。
3. Bridge 注册后端并主动触发 ViaVersion 协议探测。
4. ViaVersion 探测成功后，Bridge 按 UUID 或玩家名查找在线玩家并立即连接。
5. Bridge 向 Scheduler 上报成功或失败。连接失败只在 Scheduler 配置的有效期和最大
   尝试次数内重试；玩家离线、目标实例退出或任务过期时立即终止。

针对严格运行在 `1.21.11` 核心上的 BedWars，Bridge 只为 `26.2` 客户端修正
`classic_kb:detect` 注册表副本中的实体谓词键。修正发生在 ViaVersion 出站管线，服务端
数据包、函数、原始 NBT 与 `slots: ["saddle"]` 均不修改；`1.21.11` 和 `26.1` 系列
客户端不经过该兼容路径。

Velocity Bridge 会在登录、切换后端和退出事件后同步玩家快照，并每 10 秒进行一次
完整同步。快照包含 UUID、玩家名、延迟和当前后端服务器 ID。

Velocity 在一个 reconciliation 临界区内依次读取定义、游戏目录、Solo 访问表和实例，
只接受实例 UUID 与当前 READY 实例完全一致的访问表。`open` 实例允许任意玩家进入，
`roster` 实例严格匹配冻结名单。临界区开始时全部已知 Solo ID 临时拒绝访问，服务器注册
或注销完成后才原子发布最终快照。刷新失败时不会注册新实例，会注销已知 Solo 动态后端，
同时保留上次成功识别的普通后端。

预连接门禁以 `PostOrder.LAST` 检查其他插件处理后的最终目标；未知目标、过期 Solo 实例
和名单外玩家都会被拒绝，因此 `/server` 和代理重定向都不能绕过名单。连接完成事件会
进行第二次校验，未授权玩家会立即返回 Scheduler 后备服或 `Lobby`。普通服务器通过
Scheduler 定义识别，不受 Solo 门禁影响。

所有用户都可以执行 `/ping`。命令按后端服务器和玩家名排序，以分段颜色输出全服在线
人数、玩家名、当前后端和延迟；延迟按绿、黄、金、红四档显示。

所有用户都可以执行 `/hub` 返回 `Lobby`。拥有 `network.admin` 权限的玩家以及
Velocity 控制台可以使用以下命令：

```text
/network list
/network players
/network start <server-id> [players...]
/network stop <instance-id>
/network restart <instance-id>
/network log <instance-id> [lines]
/network command <instance-id> <command...>
/network transfer <instance-id> <players...>
```

实例参数显示为 `<server-id>-1`，同时也接受服务器 ID 和 Scheduler 实例 UUID。命令
通过异步 HTTP 请求执行，不阻塞 Velocity 的命令处理线程；日志最多返回 200 行。

## Paper 插件接口

Paper Bridge 通过 Bukkit Services Manager 注册 `ServerScheduler`。依赖 Bridge 的
插件可以异步启动、停止、查询服务器，或为已经运行的目标追加待传送玩家：

```java
ServerScheduler scheduler =
  Bukkit.getServicesManager().load(ServerScheduler.class);

scheduler.launch("skywars", List.of(player.getUniqueId()));
scheduler.launchSolo("bingo", player.getUniqueId(), List.of(player.getUniqueId()));
scheduler.findSoloSession("puzzle", player.getUniqueId());
scheduler.startSoloSession("puzzle-solo-owner", List.of(player.getUniqueId()));
scheduler.destroySolo("puzzle", player.getUniqueId());
scheduler.queueTransfers("skywars", List.of(player.getUniqueId()));
scheduler.stop("skywars");
scheduler.list();
scheduler.games();
```

这些方法返回 `CompletableFuture`，HTTP 请求不会占用 Minecraft 主线程。使用该接口的
插件应声明对 `SchedulerBridge` 的运行时依赖。

`games()` 从 Scheduler 的 `servers/*.json` 读取 `gamevoting` 目录项。GameVoting 的
`scheduler` 模式通过此接口取得完整游戏列表，不读取本地 `games.yml`。

## 模块

```text
common
platforms:paper
platforms:spigot
platforms:velocity
platforms:folia
platforms:fabric
platforms:fabric:v1_20_1
platforms:fabric:v1_20_4
platforms:fabric:v1_21_1
platforms:fabric:v1_21_10
platforms:fabric:v1_21_11
platforms:fabric:v26_1_2
platforms:fabric:v26_2
platforms:neoforge
platforms:neoforge:v1_21_11
```

`common` 包含 HTTP 客户端、实例模型、状态上报器和 `ServerScheduler` 接口。平台模块
将公共类一起打入最终 JAR。Fabric 和 NeoForge 使用按 Minecraft 版本拆分的目标模块；
Paper、Spigot、Velocity 与 Folia 使用各自的兼容模块。

## 构建

`build.sh` 优先使用仓库中的 Gradle Wrapper；不存在时使用 `PATH` 中的 Gradle。

查看脚本当前提供的目标：

```bash
./build.sh --list
```

脚本调用示例：

```bash
./build.sh --paper --clean
./build.sh --velocity --clean
./build.sh --fabric --1.20.1 --clean
./build.sh --fabric --1.20.4 --clean
./build.sh --fabric --1.21.10 --clean
./build.sh --fabric --1.21.11 --clean
```

也可以直接调用 Gradle：

```bash
gradle :platforms:paper:build
gradle :platforms:velocity:build
gradle :platforms:fabric:v1_20_1:build
gradle :platforms:fabric:v1_21_10:build
gradle :platforms:fabric:v1_21_11:build
```

各模块的 JAR 输出位于对应模块的 `build/libs/`。部署时应将 Paper/Velocity JAR 放入
`plugins/scheduler-bridge/<minecraft-compatibility>/`，将 Fabric JAR 放入
`mods/scheduler-bridge/<minecraft-compatibility>/`。

## 安全边界

- Bridge Token 只从 Scheduler 启动环境读取。
- Bridge API 只应在宿主机回环地址上访问。
- 不要把 Token 写入插件配置、服务器定义或日志。
- Velocity 注册地址由 Scheduler 的 READY 实例列表提供，不接受子服务器自报端口。
