# Elemental Phase / 元素相变

面向 Minecraft 1.20.1、Forge 47.4.x 的服务端权威元素挂载与有向反应框架。内置火、水、冰、雷、风，以及蒸发、融解、冻结、感电、超载、超导、扩散七种反应。元素、实体配置、攻击来源和反应都可由数据包修改。

## 数据目录

定义放在 `data/<namespace>/elemental_phase/`：

- `elements/<id>.json`：普通/虚拟挂载规则、独立上限和 Jade 显示元数据。
- `entity_profiles/<id>.json`：实体适用规则、常驻元素、固有攻击元素与基础量、元素抗性和反应抗性。
- `reactions/<id>.json`：双向或独立单向反应及可选组件。

## 元素定义格式

元素 ID 由文件地址决定。例如 `data/example/elemental_phase/elements/steam.json` 定义
`example:steam`。`attachment`、`display` 分组及其中字段都可以省略；省略时使用默认值。未知字段会使当前文件加载失败，旧版
`attachable`、`mount_cooldown_ticks`、`temporary_duration_ticks`、`translation_key` 和 `jade_visible`
根字段不再兼容。

```json
{
  "attachment": {
    "mode": "normal",
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

- `attachment.mode`：默认 `normal`；可填写 `normal` 或 `virtual`。虚拟元素正常附着并参与反应，但首次成功参与反应后立即清空剩余量；没有成功反应则保留到自动过期。虚拟元素不能配置为常驻元素，始终不在 Jade 显示，不存档。
- `attachment.cooldown_ticks`：默认 `2`，范围 `0..2147483647`。同一目标、同一元素的攻击、反应挂载和范围挂载共用该冷却；API另保留自身的冷却参数。
- `attachment.duration_ticks`：普通模式默认 `100`（5 秒），虚拟模式默认 `10`（0.5 秒），范围 `1..2147483647`。反应挂载沿用元素自身时长；API可指定持续时间，但虚拟元素不得超过自身设定期限。
- `attachment.max_amount`：默认 `1000000.0`，范围 `0.1..1000000.0`。常驻元素、固有攻击、攻击来源和动态公式结果均截断到该上限；非有限值和超出全局范围的输入仍拒绝。重载后普通元素的量会截断，但到期时间不变。
- `display.translation_key`：默认 `element.<namespace>.<path>`，不能为空。对应语言文件应提供完整元素名称，例如在 `assets/example/lang/zh_cn.json` 中写 `"element.example.steam":"蒸汽元素"`；同一个名称用于 Jade、附魔名称和附魔描述，模组不会再补“元素”后缀。
- `display.color`：默认 `#FFFFFF`，必须为 `#RRGGBB`；用于 Jade 中的元素量颜色及书、武器上的元素附魔名称颜色。
- `display.visible_in_jade`：默认 `true`。
- `display.order`：默认 `0`；Jade 先按该值升序，再按元素 ID 排序。
- `display.icon`：可选纹理资源地址。客户端资源存在时，Jade 用小图标替代元素名称并保留元素量；资源缺失时自动回退到翻译名称。建议提供 `16×16` PNG。

短时虚拟元素（也可由 API 或反应动作挂载）：

```json
{"attachment":{"mode":"virtual"}}
```

限制为最多 10 点并隐藏 Jade：

```json
{"attachment":{"max_amount":10.0},"display":{"visible_in_jade":false}}
```

禁用已有元素：使用高优先级数据包在同一位置提供完全空白的 JSON 文件。该文件会报告无效定义并跳过加载，不会回退到低优先级版本。`{}` 并非停用，而是创建使用默认值的普通元素。`enabled`、元素定义的 `application` 和 `retain_after_attack` 已移除，不兼容旧格式。

内置风元素使用虚拟模式，持续20tick（正常运行时1秒），仍提供独立元素附魔书；虚拟元素始终不在Jade中显示。

实体配置误将虚拟元素加入常驻列表时，仅跳过该项并输出警告，不丢弃同文件的其他配置。扩散挂载按传播元素自身的模式和时长处理；不会因为扩散由虚拟风元素触发，就把传播的普通火元素也变成虚拟元素。

## 攻击来源与元素附魔书

`attack_sources` 目录及其解析已移除，不兼容旧格式。攻击依次使用武器元素附魔、生物固有攻击元素、伤害类型标签。武器元素附魔的基础量默认 `1`，由全局配置 `enchantments.base_attachment_amount` 决定；伤害标签的基础量仍为 `1`。最终挂载量为 `min(基础量 × 元素强度, 元素上限)`；无攻击者时强度为 `1`。生物固有攻击继续使用实体配置中的基础量。来源组冷却已移除，元素定义的 `attachment.cooldown_ticks` 仍生效。

`enchantments.enable_enhancement` 默认关闭：附魔武器仍附着元素、消耗并触发反应，但这次武器的原伤害不计算对应元素抗性。开启后，只有该附魔武器的原伤害按对应元素抗性结算；反应伤害始终沿用自身规则，生物固有攻击和伤害类型标签来源不受此开关影响。这里的元素伤害是模组内部归属，不替换原版伤害类型，也不额外产生一段攻击。例如无反应时，原伤害9、火抗性0.5，关闭强化时向原版交付9，开启时交付4.5；后续原版护甲与受伤间隔仍会处理。

攻击先通过原版免疫、攻击事件取消和盾牌检查，再处理元素挂载、反应及主伤害增幅，最后将合并后的伤害交给原版受伤间隔和护甲等结算。完全格挡的攻击不挂载；部分格挡以未被挡住的伤害计算。`original_damage` 使用受伤间隔筛选前、已应用攻击元素抗性的伤害基数，增幅增加量另算反应抗性后合并。例如原攻击8、公式`original_damage`增加8，合并16；间隔内原版先前记录8时，只放行差值8。后续受伤间隔拒绝或伤害事件取消不会回滚已经完成的元素处理和消耗，独立反应组件按既有队列执行。

挂载冷却按受击目标和元素ID分别计时，不同元素可以在同tick参与反应；同一元素仍受自身冷却限制，挂载冷却不阻止普通伤害结算。正常命中只处理一次元素，原版拒绝主伤害时不产生主伤害飘字标记。独立伤害、范围伤害与DOT仍由原版及所选伤害类型标签控制受伤间隔，保留原版对计时和伤害比较值的更新；同一范围组件继续先伤害再挂载。

元素 `example:steam` 的伤害来源由 `data/example/tags/damage_type/elements/steam.json` 定义，支持原版标签语法：

```json
{"replace":false,"values":["minecraft:magic","#example:steam_damage"]}
```

内置火、水、冰、雷分别引用 `#minecraft:is_fire`、`minecraft:drown`、`minecraft:freeze`、`minecraft:lightning_bolt`，风标签默认为空。高优先级数据包用 `{"replace":true,"values":[]}` 覆盖相应标签即可清空这条伤害来源。

如果一种伤害同时命中多个已加载元素的标签，则记录冲突并跳过此次全部元素处理，保留原始伤害；武器附魔和固有攻击也不会绕过冲突。同一伤害类型在每次数据重载后最多记录一次冲突。

每个元素对应真实独立附魔，ID 为 `elemental_phase:attachment/<元素命名空间>/<元素路径>`，例如火元素对应 `elemental_phase:attachment/elemental_phase/fire`。具体元素书使用原版 `StoredEnchantments` 记录这个 ID，不另写元素身份 NBT，也不注册通用附魔。原版创造物品栏“原材料”分类的附魔书区与创造搜索列出当前已加载、已登记的元素书；不另建创造分页。普通附魔台、村民交易和附魔战利品也使用这些独立附魔，只有当前数据包中有效的元素进入普通获取候选。

新增元素只需放入正常的世界数据包，然后重启游戏或服务端，对应独立附魔会自动注册，不需要额外登记配置。启动时扫描已安装模组的 `data/<namespace>/elemental_phase/elements/**/*.json`、客户端 `saves/<世界>/datapacks`，以及服务端 `server.properties` 的 `level-name` 或 `--universe`、`--world` 参数指定世界中的 `datapacks`。支持文件夹包和 ZIP 包，内置元素同样从数据文件发现，不硬编码 ID。例如：

```text
saves/<世界>/datapacks/<数据包>/data/example/elemental_phase/elements/weather/steam.json
=> 元素 example:weather/steam
=> 附魔 elemental_phase:attachment/example/weather/steam
```

启动扫描只发现附魔身份，当前世界正式加载的数据包决定元素参数、是否生效和创造目录；其他世界或禁用包中的元素不会进入当前世界的普通获取候选。联机客户端通过 Forge 的注册表同步自动恢复服务端新增的元素附魔，不需要复制服务端数据包或填写 ID 列表。旧的 `elemental_phase-enchantments.json` 不再读取或生成。

`/reload` 可以修改或停用已经注册的元素；游戏启动后才加入全新元素 ID 时，日志和 `/elementalphase book` 会提示重启，以便下次启动自动发现并注册。可用 `/enchant @s elemental_phase:attachment/elemental_phase/fire 1` 给允许的手持武器添加火元素附魔。

元素附魔只有一级，元素之间互相冲突，可以与普通附魔共存。铁砧拒绝不同元素合并；砂轮按原版规则清除实际附魔，元素身份随之移除。默认允许剑、斧、弓、弩、三叉戟及其子类。扩展 `data/elemental_phase/tags/items/element_enchantable.json` 可增加具体物品或以物品标签表达的新武器种类：

```json
{"replace":false,"values":["example:spear","#example:weapon_type"]}
```

重载后同步元素书目录并刷新创造栏。已删除元素的旧书和武器保留原 ID，元素附魔效果失效，不会随机改成另一种元素。弓、弩、三叉戟在发射时固定武器元素和元素强度，命中时仍校验元素是否有效。

元素附魔名称直接使用对应元素的 `display.color`，无需额外配置；新增元素仍按原流程重启后自动注册附魔。名称颜色随现有元素目录一起同步，修改已有元素颜色后重载也会更新已有物品的附魔文字；缺失元素沿用灰色。客户端与服务端需使用相同的新版模组。

附魔书首行保留原版“附魔书”或玩家自定义名称，元素名称与颜色用于下方的附魔说明行。

强化关闭时，附魔名称为“火元素附着”，描述为“武器攻击时，将会对目标附着 1 点火元素。”；开启时，名称为“火元素强化”，描述为“造成的伤害变为火元素伤害，并附着1点火元素。”。其中数值为配置的基础量；实际量还会乘攻击者元素强度，并受元素上限约束。名称和描述中的元素文字均按所属元素的 `display.color` 着色，其余文字为灰色。开启强化后，没有主伤害增幅反应的附魔武器命中以元素色显示伤害；有主伤害增幅时继续以反应色显示合并主伤害，独立反应伤害仍用各自反应色。本模组飘字与DamageNumber使用相同颜色规则；关闭强化及其他攻击来源沿用原有颜色。内置元素与数据包新增元素共用两套描述模板；不依赖附魔描述模组，安装 EnchantmentDescriptions 时会替换对应元素未翻译的 `.desc` 行并避免重复显示，普通附魔描述保留。

名称由元素自己的完整显示译文决定。例如把 `element.example.steam` 译为“蒸汽元素”，附魔描述就直接写“蒸汽元素”；译为“水蒸气”则直接写“水蒸气”，不用修改统一描述模板。

描述跟随实际显示的附魔名称；通过原版 `HideFlags` 隐藏附魔信息时，也会隐藏对应描述。

在游戏实例的 `config/elemental_phase-common.toml` 修改：

```toml
[enchantments]
enable_enhancement = false
base_attachment_amount = 1.0

[reactions]
max_radius = 32.0
max_targets = 64
```

基础附着量范围为 `0.000001..1000000`，支持小数。例如填写 `2.5` 时，两种描述均显示 `2.5` 点。近战立即读取当前基础量，弓、弩、三叉戟在发射时保存基础量；强化开关在命中时读取。服务器决定实际效果，并通过模组网络将开关和基础量同步给联机客户端显示；修改后重启游戏或服务端并重新连接。进入世界前的创造搜索使用本机COMMON配置。旧世界 `serverconfig/elemental_phase-server.toml` 不再读取；若曾自定义附着量或反应上限，请把数值复制到全局COMMON文件。客户端飘字设置仍在 `config/elemental_phase-client.toml`。

## 实体配置

文件位置为 `data/<namespace>/elemental_phase/entity_profiles/<id>.json`。配置分为四类：基础配置（`selector`、`priority`）、自身附着（`permanent_elements`）、攻击能力（`intrinsic_attack`）、防御能力（统一的 `resistances` 列表）。每个生物只使用一份匹配配置：先取最高 `priority`（默认0），同优先级指定实体ID优先于实体类型标签，再相同时取配置ID字典序最后者。其他配置完全忽略，不逐字段合并；最终配置省略的字段使用系统默认：无常驻元素、无固有攻击、两种抗性0。

```json
{
  "selector": {"type": "entity_id", "id": "minecraft:zombie"},
  "priority": 100,
  "permanent_elements": [
    {"element": "elemental_phase:fire", "amount": 10, "restore_delay_ticks": 200}
  ],
  "intrinsic_attack": {"element": "elemental_phase:fire", "base_amount": 1},
  "resistances": [
    {"element": "elemental_phase:fire", "value": 0.25},
    {"reaction": "elemental_phase:vaporize", "value": 0.5}
  ]
}
```

`selector.type` 支持 `entity_id` 和 `entity_tag`；标签ID不带 `#`，标签文件位于 `tags/entity_types`。`enabled` 和 `element_strength` 已从实体配置移除，保留任一字段会导致当前文件加载失败。停用内置文件可以使用高优先级数据包在同命名空间、同路径提供空白文件；会记录错误并跳过该文件，不会回退到低优先级数据包的同路径文件。其他仍有效且匹配的配置可以被选中。

`permanent_elements` 是常驻元素数组，每项用 `element` 指定已存在的元素，普通元素的 `amount` 必填，范围0.000001..1000000并截断至元素上限；`restore_delay_ticks` 默认200，范围1..2147483647。普通常驻元素恢复时回到预设量；虚拟常驻项单独警告并跳过。省略列表或填写空数组表示没有常驻元素；同ID重复条目报错并跳过整份配置，不覆盖、不叠加。旧版以元素ID为键的对象和null清除项已移除，不兼容旧格式。

`intrinsic_attack` 只指定一个元素，`base_amount` 默认1，可在0.000001..1000000内配置并截断至元素上限；省略或填写null表示没有固有攻击。元素强度由独立实体属性 `elemental_phase:element_strength` 管理，属性默认1、范围0..1024；实体配置加载、重载或未匹配时均不修改该属性。固有攻击最终挂载量仍为基础量乘实际元素强度，再截断至元素上限；反应公式中的 `element_strength` 变量继续可用。

`resistances` 是统一抗性数组，两种抗性可以混排。每条必须且只能指定 `element` 或 `reaction` 之一，并填写 `value`；前者引用已存在的元素，后者引用完整非空反应资源ID，也允许之后由KubeJS注册的反应。`value` 范围为 **-2147483647..1**，允许小数，1表示减伤100%，负值表示易伤；不接受null、非有限值或越界值。省略列表、填写空数组或未列出的对象均采用抗性0。

同类同ID重复条目会报错并跳过整份配置，不覆盖、不叠加；不同类别可以使用同一个ID。条目未知字段、缺少对象或数值、同时指定两类对象也会使整份配置无效，不影响其他有效配置加载。旧版 `resistances` 对象与单独的顶层 `reaction_resistances` 字段已移除，不兼容旧格式。

具有元素抗性与反应抗性的独立伤害，按 `伤害 × (1 - 元素抗性) × (1 - 反应抗性)` 计算，最终伤害仍不超过1000000。元素抗性是否参与独立反应伤害，由动作的 `resistance_element` 指定。主攻击中的反应增伤在既有公式求值后额外计算该反应抗性，原攻击部分不会再因反应抗性减免；多个反应分别使用各自的抗性。范围伤害按每个实际目标的抗性计算，DOT保存反应时的抗性。

反应抗性不取消反应，不减少元素消耗，也不直接取消冻结等非伤害组件。范围伤害与挂载独立执行，免伤后仍存活有效的目标可以挂载。special中的ignite只设置原版燃烧时间，后续原版燃烧伤害不携带反应ID，因此不额外套用反应抗性；需要可归因的持续反应伤害时使用schedule_damage。

数据重载重新选择配置并替换两种抗性，不保留旧配置中省略的抗性项。`/elementalphase inspect <实体>` 会显示当前选中的配置ID、优先级、各元素状态和已配置的反应抗性。

## 元素层规则

常驻层与临时层同时存在，有效量取二者最大值。更高临时量覆盖当前有效量；相等值刷新来源和期限；更低值完全忽略且不刷新期限、来源或恢复计时。成功的相等/更高覆盖会刷新已消耗常驻层的恢复计时，反应消耗不会刷新。

反应直接消耗当前参与反应的真实元素量。每个候选的全局最低反应规模为 `0.1`；成功反应后参与元素若剩余量严格小于 `0.1`，会被清理。内部数值仍使用 `double`，Jade 和命令显示最多一位小数。

普通元素能力数据会随实体存档，包含常驻/临时层、来源、恢复期限和挂载顺序；虚拟元素不存档，实体卸载或数据包重载时清除。冻结状态和定时伤害任务均不存档；实体卸载、死亡、数据包重载或服务器停止时会清除这些短期反应状态。

成功数据重载还会清空尚未执行的反应队列，并使旧数据生成的后续动作失效；伤害事件中触发重载时，已开始的伤害保留，剩余旧动作停止，范围伤害后的元素挂载也会停止。重载在新数据发布前失败时，保留原数据、队列和短期反应状态。

## 反应格式

文件位置为 `data/<namespace>/elemental_phase/reactions/<id>.json`，文件路径决定反应 ID。外层保存共用名称、优先级、最低规模与触发条件；必填的 `reactions` 数组中，每项独立保存匹配、颜色及效果。同一文件可以同时填写正向、反向或其他元素组合，全部共用文件反应 ID 与反应抗性。

旧的顶层匹配、actions、reverse和顶层display.color不再接受。有问题的文件会记录具体字段路径并整份跳过，其他有效文件继续加载。同文件重复的实际触发元素/附着元素组合也视为错误，不能靠顺序覆盖或叠加。高优先级数据包在同路径提供文件会完整替换整份定义；提供空白文件可以停用，不会回退低优先级版本。

| 顶层字段 | 默认值和用途 |
|---|---|
| priority | 可选，默认0，完整int整数范围；所有配置共用 |
| minimum_scale | 可选，默认0.1，范围0.1..1000000；等于门槛可以反应 |
| conditions | 可选AND条件数组，默认空，最多32项；所有配置共用 |
| display | 可选；只接受translation_key与show_reaction，两项都可省略 |
| reactions | 必填非空数组，最多64项；整个文件实际展开方向也不能超过64个 |

| reactions中每项的字段 | 默认值和用途 |
|---|---|
| bidirectional / unidirectional | 必须且只能填写一个；声明该项的匹配元素与消耗比例 |
| color | 可选，默认#FFFFFF；支持#RRGGBB、$trigger、$aura |
| damage | 可选数组；主增幅、独立伤害、DOT通过type区分 |
| area | 可选数组；每项是一套范围伤害/挂载组件，不填写type |
| mob_effects | 可选数组；每项是一种药水效果，不填写type |
| special | 可选数组；每项包含特殊状态entries，不填写外层type |
| elements | 可选数组；直接挂载与元素修改通过type区分 |

五个组件数组可以省略或填空数组，合计最多32项；没有任何组件时仍会消耗元素。每套配置不继承其他配置的效果或颜色，也不能单独填写名称、优先级、条件或最低规模。主增幅先参与原攻击计算，随后其余组件按damage、area、mob_effects、special、elements的固定顺序执行，同类数组保留书写顺序。JSON字段位置不会改变分类之间的执行顺序。

对称双向反应将比例绑定在具体元素上。下面始终按水:雷=1:2消耗，与触发顺序无关，两个方向使用同一套效果：

```json
{
  "priority": 80,
  "minimum_scale": 0.1,
  "display": {
    "translation_key": "reaction.example.charged_mark",
    "show_reaction": true
  },
  "reactions": [
    {
      "bidirectional": {
        "elements": [
          {"element": "elemental_phase:water", "ratio": 1},
          {"element": "elemental_phase:lightning", "ratio": 2}
        ]
      },
      "color": "#B388FF",
      "damage": [
        {
          "type": "schedule_damage",
          "id": "example:charged_mark",
          "duration_ticks": 100,
          "interval_ticks": 20,
          "damage": {
            "formula": "scale * 0.75",
            "resistance_element": "elemental_phase:lightning"
          }
        }
      ]
    }
  ]
}
```

单向反应明确填写触发元素 `trigger` 与已有附着元素 `aura`。需要独立反向时，在同一reactions数组中另写一项；两项的比例、颜色和全部效果分别填写。只填一项就是仅该方向。下面保留内置蒸发的两个方向和各自公式：

```json
{
  "priority": 100,
  "display": {"translation_key": "reaction.elemental_phase.vaporize"},
  "reactions": [
    {
      "unidirectional": {
        "trigger": {"element": "elemental_phase:water", "ratio": 1},
        "aura": {"element": "elemental_phase:fire", "ratio": 2}
      },
      "color": "$trigger",
      "damage": [{"type": "main_damage_bonus", "formula": "original_damage * 1.0"}]
    },
    {
      "unidirectional": {
        "trigger": {"element": "elemental_phase:fire", "ratio": 1},
        "aura": {"element": "elemental_phase:water", "ratio": 0.5}
      },
      "color": "$trigger",
      "damage": [{"type": "main_damage_bonus", "formula": "original_damage * 0.5"}]
    }
  ]
}
```

每个匹配位置的 `element` 可以是已定义 ID 或非空 ID 数组；数组表示任选一种，每次仍只处理两个实际元素。数组内重复、两个位置重叠、未知元素或展开超过64个方向会报错。`ratio` 必填，有限且大于0、不超过1000000。候选按顶层优先级、附着顺序、反应 ID 稳定排序，每次成功后重新建立候选。条件和最低规模先检查，再扣元素。

设触发量为A、附着量为B、比例为rA:rB，则消费份数为 `q=min(A/rA,B/rB)`，实际消耗为 `cA=q*rA`、`cB=q*rB`，**反应规模 `scale=min(cA,cB)`**。水3、火4，比例1:2时消耗水2、火4，规模为2；比例写成等价的2:4仍得到相同结果。规模不是消费份数q，也不额外乘元素强度。

### 条件与计算

条件全部为AND；布尔类通过value表示要求真或假，不接受inverted。选择器字段二选一，tag填写不带#的标签 ID。

| 条件type | 字段 |
|---|---|
| attacker_present | value可选，默认true |
| attacker_entity / target_entity | entity或tag必选其一；inverted可选，默认false |
| damage_type | damage_type或tag必选其一；inverted可选 |
| minimum_damage | value必填，有限且大于0、不超过1000000；inverted可选 |
| target_state | state必填：on_fire、in_water、frozen；value可选默认true |

`frozen` 查询本模组真实冻结状态；冰元素挂载与细雪冻伤不能满足它。纯挂载产生的子反应没有原攻击，条件中的伤害为0。

所有动作的 `when` 可选，默认公式字符串 `"1"`；有限非零值（包括负值）执行，0或非有限值跳过该动作。主增幅公式的结果为增加的伤害，原伤害先经过攻击元素抗性，反应抗性只作用于增加部分。纯挂载子反应跳过全部主增幅，独立伤害和其他效果正常执行。

伤害公式、系数、曲线和上下限统一写在字符串中，例如 `"scale * 1.5"`、`"original_damage * 0.5"`、`"min(scale, 4) * 2"`。`min(value, cap)` 给上限，`max(value, floor)` 给下限。不增加固定曲线或额外元素强度乘数。

| 公式用途 | 可用变量 | 运算与函数 |
|---|---|---|
| 主增幅、独立伤害、范围damage、when、attach_element.amount、modify_element.amount | 下列全部17个变量；非范围上下文distance/radius为0 | + - * /、括号、一元正负、比较；min/max/clamp/abs/floor/ceil/round |
| DOT damage.formula | 同上，排除distance和radius | 同上 |
| area.radius | scale | + - * /、括号、一元正负；min/max/clamp |
| area.attachment.amount | scale、consumed_trigger、consumed_aura、distance、radius | 同上基础运算，不支持比较 |

17个通用变量为 `original_damage`、`current_damage`、`scale`、`trigger_amount`、`aura_amount`、`consumed_trigger`、`consumed_aura`、`remaining_trigger`、`remaining_aura`、`attacker_level`、`element_strength`、`target_health`、`target_max_health`、`target_health_ratio`、`target_resistance`、`distance`、`radius`。非主动作的current_damage为本次最终主伤害；DOT保存反应时变量。比较结果为1或0。公式保护为4096字符、512节点、64层嵌套。

独立与范围damage支持可选 `damage_type`，默认 `elemental_phase:reaction`；DOT默认 `elemental_phase:reaction_dot`。`resistance_element` 可选，支持固定元素 ID、`$trigger`、`$aura`，省略时不归属元素抗性，公式中的target_resistance为0。指定后按实际受伤对象的对应元素抗性计算，并额外应用所属反应抗性。伤害类型标签决定绕过护甲、受伤间隔和原版药水抗性；原版bypasses_resistance不取消本模组元素/反应抗性。伤害交付本身固定不自动挂载或重入反应。

### 分类组件

| 所在数组 | type | 必填字段 | 可选字段和行为 |
|---|---|---|---|
| damage | main_damage_bonus | formula | when |
| damage | additional_damage | formula | damage_type、resistance_element、when；固定反应目标 |
| damage | schedule_damage | id、duration_ticks、interval_ticks、damage.formula | damage.damage_type、damage.resistance_element、when |
| area | 不填写 | radius，damage或attachment至少一项 | damage.include_target默认false、include_attacker默认false、max_targets、when |
| mob_effects | 不填写 | effect、duration_ticks | level默认0、when；固定反应目标，普通药水显示 |
| special | 不填写 | 非空entries数组 | when；固定反应目标，条目可同时生效；entries内仍填写ignite/freeze的type |
| elements | attach_element | 固定element ID、amount公式字符串 | when；固定反应目标，采用元素自身时长/冷却并尝试反应 |
| elements | modify_element | element、operation | target默认target也可attacker、when；add/set/remove必须填amount，clear不能填amount；不自动尝试反应 |

范围固定以反应目标为中心，默认排除该目标；设置 `damage.include_target: true` 后，中心使用同一范围公式结算一次伤害，distance为0，优先占用一个目标名额和共享检查预算。范围attachment始终排除中心，不会把刚消耗的元素重新挂回去。每次筛选一份不可变名单，周围对象先damage再检查有效性、随后attachment。零伤害、免疫或受伤间隔拒绝伤害后，有效对象仍尝试挂载；死亡、移除或离开当前维度则跳过挂载。两部分独立，只有其中一部分也可执行。攻击者开启后须有效且位于半径内，与周围对象按距离及实体 ID 共同排序并占名额；攻击者恰好就是已开启的中心时，按中心规则处理。

```json
{
  "priority": 60,
  "conditions": [],
  "reactions": [
    {
      "unidirectional": {
        "trigger": {"element": "elemental_phase:wind", "ratio": 1},
        "aura": {
          "element": ["elemental_phase:fire", "elemental_phase:water", "elemental_phase:ice", "elemental_phase:lightning"],
          "ratio": 0.5
        }
      },
      "color": "$aura",
      "area": [
        {
          "radius": "min(2 + scale * 0.25, 8)",
          "damage": {"formula": "scale * 0.5", "include_target": true},
          "attachment": {"element": "$aura", "amount": "consumed_aura * 0.5"},
          "include_attacker": false,
          "max_targets": 64
        }
      ]
    }
  ]
}
```

area.damage允许formula、两个伤害设置，以及可选布尔字段include_target（默认false，只控制中心伤害）；area.attachment只允许element和amount，不接受子when、持续时间或冷却绕过。挂载量为每个目标独立计算的量，不均分，也不再乘元素强度。radius可以写有限数字或基础公式，非正结果跳过，超限截断，非有限结果记录错误并跳过该组件。

全局 `config/elemental_phase-common.toml` 提供 `[reactions] max_radius`（默认32格）与 `max_targets`（默认64个）。组件未填目标上限时跟随服务端值，填写时取二者较小值。提高配置可超过32/64；也会增加扫描和执行开销。范围筛选逐唯一候选消耗共享检查预算，耗尽即停止，只从已检查且合格对象中选最近目标；多个组件和子反应共用预算。显式挂载连锁共用深度8、总反应32的保护，沿用元素自身临时挂载规则和冷却。

特殊状态条目支持ignite和freeze，duration_ticks必填，范围1..2147483647；不累计时长。新时长小于当前剩余时长时忽略，相等或更长时重新计时。火与冻结可以共存，不提供条目级when。药水level范围0..2147483647整数，0为I、1为II；duration_ticks为1..2147483647，不扩展原版极端等级序列化。

```json
{
  "reactions": [
    {
      "bidirectional": {
        "elements": [
          {"element": "elemental_phase:ice", "ratio": 1},
          {"element": "elemental_phase:water", "ratio": 1}
        ]
      },
      "color": "$trigger",
      "damage": [
        {"type": "additional_damage", "formula": "min(scale, 4) * 2", "when": "scale >= 1"}
      ],
      "mob_effects": [
        {"effect": "minecraft:weakness", "duration_ticks": 100, "level": 0}
      ],
      "special": [
        {
          "entries": [
            {"type": "freeze", "duration_ticks": 100},
            {"type": "ignite", "duration_ticks": 60}
          ]
        }
      ],
      "elements": [
        {"type": "attach_element", "element": "elemental_phase:fire", "amount": "scale * 0.5"},
        {"type": "modify_element", "target": "attacker", "element": "$trigger", "operation": "remove", "amount": "scale"}
      ]
    }
  ]
}
```

### DOT与显示

schedule_damage是纯伤害任务，不占据元素槽、不储存可消耗元素、不再自动挂载或反应。duration_ticks范围0..2147483647，interval_ticks范围1..2147483647。每次触发都立刻尝试一次本次DOT，包括被低规模规则忽略的新DOT；同一tick多次触发各自执行首段。首段之后从触发时间重新计算间隔：

- 新规模低于同ID旧任务：首段使用新参数，随后保留旧任务全部参数、来源与计时。
- 相同规模：执行新首段，刷新全部参数、来源与计时。
- 更高规模：执行新首段，覆盖旧任务并重新计时。

任务结束点含在周期内；100/20合计6次尝试，95/20为5次，0/20或10/20只有首段。接受的duration=0会结束同ID旧任务；低规模duration=0仍保留旧任务。伤害被拒绝不回滚刷新/覆盖，不重试。每段使用同一公式求每段伤害，不按总时长平分。

公式变量、元素与反应抗性、颜色和名称开关在反应时保存；只将current_damage补为本次最终主伤害。后续生命、经验等级、元素强度或本模组抗性变化不改旧任务，原版护甲和药水等仍按交付时规则处理。攻击者或投射物失联时保留UUID记录并用可解析实体归因，在线玩家可跨维度解析，无法解析时仍伤害目标。玩家来源的每段DOT都遵守交付时的PvP与队伍友伤规则；离线后按最近已知玩家名查询当前队伍，保护拦截本段不清除任务。目标死亡、移除、卸载、离线、跨维度，以及成功数据重载或关闭服务器时清理；普通保存不会清理，不持久化任务。

id决定“同一目标同一DOT”的任务身份；默认按该id推导显示翻译键 `reaction.<namespace>.<path>`（路径的斜杠替换为点）。id与已加载的某个文件反应ID相同时，显示采用该文件共用的display.translation_key；未匹配到时沿用自动推导。所属文件反应 ID 决定反应抗性。DOT效果ID与所属反应ID可以不同，同一id不会因换攻击者而保留两份。默认reaction_dot通过damage_type标签绕过受伤冷却，使首段能接在触发攻击之后；自定义类型仍服从自己的标签。

外层display.translation_key决定共用反应名称，省略时由文件ID生成 `reaction.<namespace>.<path>`，路径斜杠转换为点；填入的非空翻译键需要在语言资源中提供对应文本。翻译键只控制显示，不替代反应ID、抗性ID或DOT任务身份。外层display.show_reaction默认true，false同时关闭该文件所有配置及其主增幅、独立、范围、DOT名称，伤害数字与颜色保留。客户端全局关闭名称时，数据包不能重新开启。

每项reactions配置中的color独立，默认#FFFFFF，支持六位RGB大小写、$trigger与$aura；动态色按本次实际元素解析。共用名称不会限制不同方向或DOT使用各自反应时的颜色。名称目录在登录和成功数据重载时同步到客户端，退出世界时清空；只修改名称也会更新，客户端与服务端需使用相同模组版本。

内置感电保留100tick、20tick间隔、scale*0.75与雷元素抗性；冻结保留100tick的实际效果：水平速度降为0、保留竖直运动，不暂停AI或禁止攻击。冻结外观为一个按实体当前碰撞箱生成的整体蓝冰长方体，每轴外扩0.04米，六个完整面使用原版冰方块的半透明材质与清晰冰纹，并沿用资源包；没有逐部位白霜框、附加冰晶或模型顶点采集。冰块尺寸跟随实际碰撞箱，转身、抬手和触角不改变范围，碰撞箱外的模型部位可以露出；幼体和姿态导致的碰撞箱变化按实际尺寸显示。冰面遵循环境光，暗处不自发光。冰块在冻结首帧完整出现，生成时没有生长动画或碎屑，持续期间不发粒子；解除时整体淡出，只触发一次最多3片轻微碎冰，碎屑较小、初速度较低，寿命约0.5秒。粒子发射点取自冰块表面，最多保留6处；死亡、对本地玩家不可见的实体以及第一人称本地玩家不显示冰块。视觉余留最多5tick，不延长实际冻结或移动约束；粒子遵循客户端粒子设置，并限制距离与每tick数量。超载与扩散使用统一area：超载仍排除反应目标，扩散通过damage.include_target包含反应目标，中心与周围对象使用同一范围伤害公式；范围挂载仍排除中心，不另补独立伤害组件。

## KubeJS（可选）

支持 KubeJS `2001.6.5-build.16` 或更高版本。当前边界只提供简单反应注册，不开放元素定义或任意运行时事件：

```js
ElementalPhaseEvents.registerReactions(event => {
  event.create('example:steam_burst', {
    priority: 120,
    display: {translation_key: 'reaction.example.steam_burst'},
    reactions: [{
      bidirectional: {
        elements: [
          {element: 'elemental_phase:water', ratio: 1},
          {element: 'elemental_phase:fire', ratio: 2}
        ]
      },
      color: '$trigger',
      damage: [{type: 'additional_damage', formula: 'scale * 2'}]
    }]
  })

  event.disable('elemental_phase:overload')
})
```

同 ID 的脚本定义完整替换数据包定义；`disable` 移除定义。每条脚本反应使用与数据包相同的严格解析、索引和限制，单条无效定义只跳过自身。实际注册校验会过滤不存在的伤害类型或药水效果；交付时也有兜底检查，找不到时跳过该交付，不自动换默认类型。未安装 KubeJS 时不会加载集成类，核心模组没有运行时依赖。

## 显示与兼容

伤害飘字取 Forge `LivingDamageEvent` 的最终伤害。第一人称不显示玩家自己的飘字；普通反应使用共用display.translation_key或文件ID推导的默认键，DOT按前述效果id规则解析名称。名称关闭时数字与反应色保持。客户端数量、距离、字号和高度由 `elemental_phase-client.toml` 控制。

每条飘字生成时保存受击位置的世界坐标，实体尺寸与高度比例只在生成时读取；随后在该受击点附近独立左右波动、上浮并淡出，不随生物移动。生物移动、死亡或离开后，已有数字仍在原受击区域播放，正常寿命35tick；达到数量限制时，最旧数字可能提前移除。距离与遮挡也按保存位置判断。退出世界或资源重载时清理。

字号每帧按相机到保存受击位置的距离近大远小；font_scale仍为基础倍率。实际倍率为font_scale × sqrt(4 / max(1, 距离))，距离单位为方块：4格保持基础字号，16格约一半，64格约四分之一，1格以内最多放大两倍。屏幕边界判断使用相同的实际字号。

每条飘字在生成时独立保存小范围初始偏移、横向漂移量和波动相位：出生横向在受击点±6基础像素内，纵向在受击点至下方24基础像素内。随后仅按自身年龄小幅左右波动、上浮18基础像素，整个轨迹的基础横向范围不超过±12像素，纵向范围为受击点上方18至下方24像素。出生范围与数量上限无关，新数字不读取旧数字位置或上浮进度，不向上续排，也不修改已有数字的坐标、轨迹或寿命。重叠时仍显示全部存活条目。横向偏移在2格内保持原幅度、2..8格平滑收拢、8格及以外居中。

基础偏移以视野70度、GUI高度360、相机前方4格处为参考；每帧按当前投影矩阵、相机深度和GUI尺寸换算，近处偏移倍率最多2倍。远处的出生偏移和上浮距离与生物的透视投影同比缩小，避免字号仍可辨认时数字落到脚部或飘到头顶过远。字号继续按上述独立曲线缩放，font_scale只影响文字大小，不扩大飘动区域。

实际伤害小于等于0.1时，不生成本模组整条飘字，包括反应名称；按Minecraft的float精度判断，0.1F同样隐藏。只过滤显示，不改变伤害结算。单个生物默认最多保留最近9条，每条仍是独立伤害数值，新数字替换该生物最旧的数字；在`damage_popup.performance`下，`max_count_per_entity`可配置0..256，0只取消单生物条数限制，出生区域和轨迹范围仍保持。客户端所有生物的飘字合计默认最多96条，`max_count`可配置16..256；先处理单生物数量限制，仍超出全局上限时移除全局最早的飘字。文字重叠不会触发额外隐藏或提前移除。DamageNumber自身产生的飘字仍由其本身规则控制。

原攻击只有被反应实际增加主伤害时，才携带对应反应的名称和颜色；同次命中的多个反应分别判断。只有独立伤害、范围伤害、DOT或状态动作时，原攻击沿用普通伤害显示；被条件跳过、公式未产生正数伤害、反应免疫或伤害上限阻止增幅时同样如此。独立伤害、范围伤害与DOT保持各自的反应显示信息，伤害结算与DOT首段规则不因此改变。

Jade 11.13.1+ 可按元素定义的颜色、顺序与可选图标显示一位小数的元素量；冻结和感电不是元素，因此不会出现在元素列表。Better Combat 1.9.x 会按实际主/副手攻击武器解析附魔元素。Damage Number 联动中，主伤害使用首个实际增幅反应的颜色；独立、范围和DOT伤害使用各自的反应颜色。各反应名称使用自身颜色，相同“反应 ID + 颜色”只显示一次；没有实际主增幅时不为原攻击注入反应显示信息。训练假人的原生飘字可在其客户端配置中设置 `[visuals] damage_numbers = false` 关闭。

Jade 的元素信息默认显示，可在 Jade 插件配置中独立关闭。该开关沿用 Jade 为组件自动创建的配置，不需要手动添加同名配置或删除已有配置文件。

动作链按整次命中排队，最多 4096 条；每 tick 启动至多 256 条，范围目标操作共享 2048 次预算，避免密集范围反应造成无界开销。

## 命令与构建

管理员命令：`/elementalphase inspect`、`reload`、`apply`、`remove`，以及 `/elementalphase book <元素ID> [玩家]`。省略玩家时给予执行者一本对应元素附魔书。

使用 Java 17：

```powershell
.\gradlew.bat build
```

构建产物位于 `build/libs`，不会自动部署到游戏 `mods` 文件夹。

## 服务端运行验证

在独立验证目录启动真实 Forge GameTest 服务端：

```powershell
.\gradlew.bat --offline runGameTestServer "-PruntimeVerificationDir=C:/Users/Lenovo/Desktop/Ai_Run/output/elemental-phase-runtime-verify"
```

用例覆盖实际冰伤害触发冻结、水平移动限制、成功重载清理、伤害事件内重载、范围挂载中断、旧队列预约失效、DOT 首段重载后连续25个服务器tick没有旧伤害，以及周期 DOT 重载后同 tick 的旧任务和冻结移动限制不会恢复。失败重载场景会主动产生一次 `EXPECTED_RUNTIME_VERIFY_RELOAD_FAILURE` 异常，验证原数据、队列和已有冻结状态继续保留；最终以 `All 2 required tests passed` 判断整组通过。

测试世界、配置和日志均保存在指定目录。该入口验证服务端逻辑，客户端渲染和完整整合包联机仍需另外验证。
