# Elemental Phase / 元素相变

面向 Minecraft 1.20.1、Forge 47.4.x 的服务端权威元素挂载与有向反应框架。内置火、水、冰、雷、风，以及蒸发、融解、冻结、感电、超载、超导、扩散七种反应。元素、实体配置、攻击来源和反应都可由数据包修改。

## 数据目录

定义放在 `data/<namespace>/elemental_phase/`：

- `elements/<id>.json`：元素来源权限、挂载规则、独立上限和 Jade 显示元数据。
- `entity_profiles/<id>.json`：实体常驻元素、抗性、固有攻击元素和元素强度。
- `attack_sources/<id>.json`：附魔 ID/标签或伤害类型 ID/标签映射到元素。
- `reactions/<id>.json`：V2 有向反应。

## 元素定义格式

元素 ID 由文件地址决定。例如 `data/example/elemental_phase/elements/steam.json` 定义
`example:steam`。根对象及三个分组中的字段都可以省略；省略时使用默认值。未知字段会使当前文件加载失败，旧版
`attachable`、`mount_cooldown_ticks`、`temporary_duration_ticks`、`translation_key` 和 `jade_visible`
根字段不再兼容。

```json
{
  "enabled": true,
  "application": {
    "from_attack": true,
    "from_reaction": true
  },
  "attachment": {
    "retain_after_attack": true,
    "cooldown_ticks": 2,
    "duration_ticks": 100,
    "max_amount": 1000000.0
  },
  "display": {
    "translation_key": "element.example.steam",
    "color": "#4FAFFF",
    "visible_in_jade": true,
    "order": 100,
    "icon": "example:textures/gui/elements/steam.png"
  }
}
```

- `enabled`：默认 `true`。设为 `false` 时移除同 ID 元素定义，可用于数据包覆盖。
- `application.from_attack`：默认 `true`。控制该元素能否被攻击来源、附魔或实体固有攻击引用。
- `application.from_reaction`：默认 `true`。控制 API、`attach_element`、`spread_element` 和
  `modify_element add/set` 能否产生该元素；不限制移除、清空或已有元素参与反应。
- `attachment.retain_after_attack`：默认 `true`。设为 `false` 时攻击携带的元素仍能参与本次反应，但本次攻击结束后不保留剩余量。它不限制常驻元素、API 或反应动作。
- `attachment.cooldown_ticks`：默认 `2`，范围 `0..2147483647`。同一目标、同一元素的攻击、API、反应挂载和扩散共用该冷却；显式关闭 `respect_attachment_cooldown` 的入口可绕过。
- `attachment.duration_ticks`：默认 `100`，范围 `1..2147483647`，控制临时元素层持续时间。
- `attachment.max_amount`：默认 `1000000.0`，范围 `0.1..1000000.0`。攻击和动态公式结果会截断到该上限；常驻元素、固有攻击和攻击来源的固定配置若超过上限会拒绝对应文件。重载后已存在状态会截断，但到期时间不变。
- `display.translation_key`：默认 `element.<namespace>.<path>`，不能为空。
- `display.color`：默认 `#FFFFFF`，必须为 `#RRGGBB`；用于 Jade 中的元素量颜色。
- `display.visible_in_jade`：默认 `true`。
- `display.order`：默认 `0`；Jade 先按该值升序，再按元素 ID 排序。
- `display.icon`：可选纹理资源地址。客户端资源存在时，Jade 用小图标替代元素名称并保留元素量；资源缺失时自动回退到翻译名称。建议提供 `16×16` PNG。

只作为本次攻击瞬时触发元素：

```json
{"attachment":{"retain_after_attack":false}}
```

限制为最多 10 点并隐藏 Jade：

```json
{"attachment":{"max_amount":10.0},"display":{"visible_in_jade":false}}
```

禁用已有元素：

```json
{"enabled":false}
```

攻击来源可选来源组冷却：

```json
"application": {
  "cooldown_group": "example:fire_sword",
  "cooldown_ticks": 10
}
```

同一来源实体、目标实体和冷却组共享冷却。弹射物在发射时冻结武器、元素强度和该配置。

## 元素层规则

常驻层与临时层同时存在，有效量取二者最大值。更高临时量覆盖当前有效量；相等值刷新来源和期限；更低值完全忽略且不刷新期限、来源或恢复计时。成功的相等/更高覆盖会刷新已消耗常驻层的恢复计时，反应消耗不会刷新。

反应直接消耗当前参与反应的真实元素量。每个候选的全局最低反应规模为 `0.1`；成功反应后参与元素若剩余量严格小于 `0.1`，会被清理。内部数值仍使用 `double`，Jade 和命令显示最多一位小数。

元素能力数据会随实体存档，包含常驻/临时层、来源、恢复期限和挂载顺序。短期攻击来源组冷却、冻结状态和定时伤害任务均不存档；实体卸载、死亡、数据包重载或服务器停止时会清除这些短期反应状态。

## V2 反应格式

```json
{
  "enabled": true,
  "priority": 100,
  "elements": ["elemental_phase:fire", "elemental_phase:water"],
  "directions": [
    {
      "trigger": "elemental_phase:water",
      "aura": "elemental_phase:fire",
      "priority": 100,
      "minimum_scale": 0.1,
      "consumption": {"trigger": 1.0, "aura": 2.0},
      "conditions": [],
      "display": {"color": "#4FAFFF", "show_reaction": true},
      "actions": [
        {"type": "main_damage_bonus", "formula": "original_damage"}
      ]
    }
  ]
}
```

`elements` 只声明允许参与的元素。`directions` 明确触发元素与已有附着元素，因此水触发火和火触发水可使用不同消耗、公式、颜色和效果。候选按方向优先级、附着顺序、反应 ID 稳定排序；每次成功后重新建立候选。失败条件不消耗元素。

支持的条件：`attacker_present`、`attacker_entity`、`target_entity`、`damage_type`、`source_kind`、`minimum_damage`、`target_on_fire`、`target_in_water`、`target_is_boss`。每项都可使用 `inverted`。

支持的动作：

- `main_damage_bonus`：增加本次主伤害，后续动作可通过 `current_damage` 读取更新结果。
- `additional_damage`：独立伤害事件。
- `area_damage`：范围独立伤害，可保存 `target_set`、设置中心、目标上限和衰减公式。
- `mob_effect`、`ignite`、`apply_freeze`、`knockback`。
- `modify_element`：对目标或攻击者执行 `add`、`set`、`remove`、`clear`。
- `spread_element`：范围挂载，可通过 `source_target_set` 复用更早范围伤害实际选择的目标。
- `attach_element`：由反应向目标或攻击者挂载一个普通元素。
- `schedule_damage`：创建不占据元素槽的定时伤害任务；使用 `id` 区分任务，`duration_ticks` 控制总时长，`interval_ticks` 控制结算间隔，`damage` 定义公式、伤害类型、抗性元素和显示颜色。创建时立即结算一次，结束 tick 能整除间隔时也会结算。

即时和范围伤害动作支持 `damage_type`、`resistance_element`、`bypass_armor`、`bypass_invulnerability`、`allow_element_application`、`allow_reactions`。`schedule_damage.damage` 支持 `formula`、`damage_type`、`resistance_element` 和 `color`，其后续伤害默认不再挂载元素或触发新反应。范围半径上限 32，单动作目标上限 64，每方向最多 32 个条件与 32 个动作。

公式变量：`original_damage`、`current_damage`、`scale`、`trigger_amount`、`aura_amount`、`consumed_trigger`、`consumed_aura`、`remaining_trigger`、`remaining_aura`、`attacker_level`、`element_strength`、`target_health`、`target_max_health`、`target_health_ratio`、`target_resistance`、`distance`、`radius`。

运算支持 `+ - * /`、比较运算（结果为 1 或 0）、括号、一元正负号，以及 `min`、`max`、`clamp`、`abs`、`floor`、`ceil`、`round`。公式限制 4096 字符、512 节点、64 层嵌套。

## 内置短期反应效果

- 感电：使用通用 `schedule_damage` 模板。默认持续 100 tick、每 20 tick 按触发时固定下来的反应规模造成一次雷抗性可减免的额外伤害；同任务 ID 下，高规模覆盖低规模、相等规模刷新时间、低规模不刷新。它不占据元素，也不能参与后续反应。
- 冻结：使用固定 `apply_freeze` 动作，默认持续 100 tick。冻结期间水平移动速度降为 0，并立即显示三根从脚下三角底座向内倾斜、在实体约 80% 高度交叉的无碰撞深冰蓝半透明冰柱封印；不暂停 AI、不禁止攻击，也不占据元素或参与后续反应，到期时封印立即移除。

## KubeJS（可选）

支持 KubeJS `2001.6.5-build.16` 或更高版本。当前边界只提供简单反应注册，不开放元素定义或任意运行时事件：

```js
ElementalPhaseEvents.registerReactions(event => {
  event.create('example:steam_burst', {
    enabled: true,
    priority: 120,
    elements: ['elemental_phase:fire', 'elemental_phase:water'],
    directions: [{
      trigger: 'elemental_phase:water',
      aura: 'elemental_phase:fire',
      minimum_scale: 0.1,
      consumption: {trigger: 1.0, aura: 1.0},
      conditions: [],
      display: {color: '#FFFFFF', show_reaction: true},
      actions: [{type: 'additional_damage', formula: 'scale * 2'}]
    }]
  })

  event.disable('elemental_phase:overload')
})
```

同 ID 的脚本定义完整替换数据包定义；`disable` 移除定义。每条脚本反应使用与数据包相同的严格解析和限制，单条无效定义只跳过自身。未安装 KubeJS 时不会加载集成类，核心模组没有运行时依赖。

## 显示与兼容

伤害飘字取 Forge `LivingDamageEvent` 的最终伤害。第一人称不显示玩家自己的飘字；反应使用数据文件 ID 对应的 `reaction.<namespace>.<path>` 翻译键与方向颜色。客户端数量、距离、字号和高度由 `elemental_phase-client.toml` 控制。

Jade 11.13.1+ 可按元素定义的颜色、顺序与可选图标显示一位小数的元素量；冻结和感电不是元素，因此不会出现在元素列表。Better Combat 1.9.x 会按实际主/副手攻击武器解析附魔元素。Damage Number 联动会让伤害数字使用首个反应的颜色，并让各反应名称使用自身颜色；相同“反应 ID + 颜色”只显示一次。训练假人的原生飘字可在其客户端配置中设置 `[visuals] damage_numbers = false` 关闭。

动作链按整次命中排队，最多 4096 条；每 tick 启动至多 256 条，范围目标操作共享 2048 次预算，避免密集范围反应造成无界开销。

## 命令与构建

管理员命令：`/elementalphase inspect`、`reload`、`apply`、`remove`。

使用 Java 17：

```powershell
.\gradlew.bat build
```

构建产物位于 `build/libs`，不会自动部署到游戏 `mods` 文件夹。
