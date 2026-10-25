# StardewFishing 1.0.0 验证记录

2026-10-21 最终 `build.ps1` 完整构建通过：**61项测试，0失败、0错误、0跳过**，已产出Spigot1.21.4插件JAR。尚未启动真实服务器或客户端验收。

## 自动化验证范围

使用Spigot API `1.21.4-R0.1-SNAPSHOT`、F盘已有Java21与Maven构建。测试验证鱼种价格及长度边界、烤制倍率、数万次抽样的鱼王概率/品质分布/鱼饵稀有度、鱼王条件与冷却、图鉴原子保存及损坏文件保留、物品签名、经济交易失败退款、鱼王售卖隔离、配方与自动合成器材料验证、烤制信息转换与输出不兼容保护、GUI点击/拖动物品保护、延后到下一tick执行时的界面和权限检查，以及水域/岩浆钓鱼状态清理。

钓鱼引擎测试覆盖：关闭后保留原版、水域单次替换奖励、保护事件取消、空手、换竿/换吊具/换手、失效浮漂、超距离、抽干岩浆、提前收竿、收竿超时、有效咬钩、岩浆耐久损耗及取消耐久事件、卸载幂等清场。无Vault类的隔离类加载测试验证经济功能可选。

测试中的物品材质资格检查使用无服务端的测试替身，生产配置加载仍调用Spigot实际物品注册表；真实客户端视觉、真实浮漂物理与服务端插件协作不由Mockito测试证明。

## 测试服验收步骤

1. 使用Java21启动Spigot1.21.4，安装本JAR、Vault与现有经济插件；控制台应报告30个鱼种和已连接经济服务。
2. 两名普通玩家打开`/sfish`，验证三类图鉴、翻页、空图鉴状态，以及Shift、数字键、双击、拖动不能取出GUI展示品。
3. 分别在河流、深海、沼泽、冰海、樱花林和Y≤0地下水域钓鱼，核对Lore地点、长度、价格和个人图鉴解锁。关闭特色钓鱼后检查原版鱼、垃圾和宝藏规则恢复。
4. 合成四级鱼饵，检查高级配方拒绝原版种子，工作台批量合成和自动合成器都正确扣料。鱼饵放储物格后按最高级消耗，额外鱼饵掉落不消耗饵。
5. 工坊主手持附魔鱼竿，购买、安装、替换、拆卸吊具，确认旧吊具返还；改造三级，核对余额、Lore及原有附魔/耐久。背包满时应拒绝需要返还旧吊具的操作。
6. 安装熔岩吊具，在地狱向源岩浆与流动岩浆的敞开表面抛竿，验证等待、提示、及时收竿奖励、提前收竿无奖励、耐久与经验，以及换手/换世界/退出时清理。移除吊具后不得获得岩浆渔获。
7. 熔炉、烟熏炉、营火分别烤鱼，核对鱼种/长度完全保留、价格仅乘1.5。不同长度混用输出槽时不得叠成同长度；原版鱼与插件鱼不得混叠。
8. 使用管理员命令发普通鱼和鱼王，售卖主手、普通鱼批售和鱼王确认，核对实际余额、数量与图鉴保留。停用经济服务时鱼不得消失；支持整数货币的服务应按该服务精度结算。
9. 食用生鱼、烤鱼和鱼王，验证I级增益、持续时间、同玩家触发冷却和饱和仅瞬时生效。
10. 在测试服临时提升鱼王概率后满足地点/饵/天气条件钓获鱼王，验证同一种鱼王个人冷却，另一位玩家不受影响。重启后图鉴/最长纪录/个人开关/冷却应保留，随后恢复概率。
11. 搭配现有领地/保护插件，在禁止钓鱼的地方验证其取消`PlayerFishEvent`时没有自定义奖励。岩浆右键与第三方保护的具体行为需要针对服务器插件组合验收。

## 实现依据

使用[Spigot钓鱼事件](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/event/player/PlayerFishEvent.html)和[浮漂API](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/entity/FishHook.html)，具体方法同时以本地1.21.4API编译验证。PaperMC官方1.21.4分支保留的CraftBukkit补丁显示，原版奖励事件在物品加入世界前发生、已移除的实体不会再次加入，炉子输出按完整物品信息匹配：[FishingHook补丁](https://github.com/PaperMC/Paper/blob/ver/1.21.4/paper-server/patches/sources/net/minecraft/world/entity/projectile/FishingHook.java.patch)、[ServerLevel补丁](https://github.com/PaperMC/Paper/blob/ver/1.21.4/paper-server/patches/sources/net/minecraft/server/level/ServerLevel.java.patch)、[炉子补丁](https://github.com/PaperMC/Paper/blob/ver/1.21.4/paper-server/patches/sources/net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity.java.patch)。本插件不依赖Paper特有API或NMS。
