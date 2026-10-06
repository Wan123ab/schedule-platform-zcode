# Web 终端 / SFTP 集成方案调研（V1.1 预研）

> 背景：任务步骤分布在数百个节点上，排障时需要从任务详情一键登录到出问题步骤所在的节点查看与操作，避免人工找机器。本文评估「集成现成开源工具」vs「基于成熟库薄封装」两条路线，结论供评审。

## 1. 需求要点

1. 任务详情 DAG 中点击失败步骤 → 直接 SSH 登录**该步骤所在节点**（平台已知 步骤→节点→凭据 映射）
2. Web 终端交互（等同 ssh 登录）+ **SFTP 文件浏览/下载**（等同 sftp）
3. 会话必须**录制可回放、逐条留痕**（公共基础设施平台的审计要求，PRD §10.13）
4. 凭据复用平台凭据体系，**明文永不出库、不下发浏览器**（PRD §10.5）
5. 数百节点规模：网关集中式，不在每台节点装东西

## 2. 开源方案盘点

| 方案 | 形态 | 技术栈 | SSH | SFTP | 审计/录制 | 集成方式 | 评估 |
|---|---|---|---|---|---|---|---|
| **Apache Guacamole** | 无客户端网关 | Java Servlet + C(guacd) + HTML5 | ✓ | ✓（文件传输） | ✓ 会话录制 | 独立部署，iframe/URL 参数嵌入；扩展 AuthenticationProvider 对接平台鉴权 | 成熟稳重，但以 RDP/VNC 桌面场景为主，纯 SSH 略重；多一个 C 组件运维 |
| **JumpServer** | 堡垒机（4A/PAM） | Python/Django + Vue | ✓ | ✓ | ✓ 行级命令审计 + 录像 | 独立部署；API 创建资产/授权后跳转；对接需做账号同步 | 国内事实标准、功能最全；但它是**另一个平台**——用户、资产、授权体系重复建设，与 FlowOps 的凭据/权限体系打架 |
| **Next Terminal** | 轻量堡垒机 | Go + React（基于 Guacamole 网关） | ✓ | ✓ | ✓ 实时监控 + 录像回放 | 独立部署 + API | 比 JumpServer 轻；同上，体系重复问题仍在 |
| **Teleport** | 零信任访问平面 | Go | ✓ | ✓ | ✓ 最佳（录制+HA+SSO） | 独立集群，节点装 agent 或 SSH 拦截 | 安全性最强，但部署面大、理念重（证书体系），一期明显过重 |
| **sshwifty** | 单二进制 Web SSH/Telnet 客户端 | Go | ✓ | ✗ 无 SFTP | ✗ | 反向代理挂载 | 太简陋，无 SFTP 无审计，仅适合个人自用 |
| **webssh2 / webssh（Python）** | WebSocket→SSH 代理 | Node(ssh2) / Python(paramiko) | ✓ | ✗ | ✗ | 架构参考价值 | 同上，玩具级 |
| **termius-plus 等自研参考项目** | Spring Boot + Vue + **sshj** + xterm.js | Java | ✓ | ✓（sshj SFTPClient） | 需自研 | —— | 证明 Java 栈薄封装可行，社区有成熟范式 |

前端事实标准：**xterm.js**（MIT）——几乎所有上述方案的浏览器端渲染层。

## 3. 结论：推荐「xterm.js + sshj 薄桥」，堡垒机作为 V2 企业增强

**核心判断**：SSH 协议解析、终端仿真、SFTP 协议这些"轮子"都有成熟库，我们**不需要造这些轮子**；需要自己写的只是一层很薄的"WebSocket ↔ SSH 通道桥 + 审计"，量级约 1~2 周，而不是"从零造终端"。

```
浏览器                        FlowOps server / executor-client              执行节点
xterm.js  ←— WebSocket(WSS) —→  SSH 桥（Java sshj：Shell Channel）  —SSH—→  sshd
SFTP 面板 ←— REST+WS        —→  sshj SFTPClient（列目录/读/下载）    —SSH—→  sftp-server
                                 └─ 同时 tee 一份输出 → asciicast(.cast) 录制 → 审计库
```

- **Java 侧选 sshj**（Apache-2.0，比老牌 JSch 活跃，原生 SFTPClient）；备选 Apache MINA SSHD
- **前端选 xterm.js + xterm-addon-fit**（MIT）
- **录制**：拦截 WS→SSH 字节流，写成 asciinema v2 格式（JSON 行 `[ts,"o",data]`），回放用 asciinema-player，**改动极小**
- **安全四件套**：① 单次授权 token 绑定 任务/步骤/用户（复用 §11.3 日志授权的机制，TTL 10 分钟）② 凭据服务端注入（明文永不出库，浏览器只拿 ws 会话）③ RBAC：`schedule:node:term` 新权限点（运维 + 平台管理员，V1.1 评审确认）④ 下载走审批白名单（DL_REQUEST 审计）
- **跳转体验**（本原型已演示）：DAG 步骤详情「排障直达」→ SSH 提示符直接落在 `/opt/flowops/work/{taskId}/{步骤名}`，SFTP 同路径

**何时引入堡垒机**：若公司已有 JumpServer/Next Terminal，V2 阶段把「节点终端」整体切换为其 API 授权跳转（平台不再自建桥），FlowOps 只负责传上下文（目标节点 IP、任务/步骤标签）——两条路线不冲突，一期薄桥、二期可替换，因为审计落点都在 FlowOps 侧留有 task/step 关联。

## 4. 工作量预估（V1.1 单独立项）

| 项 | 估算 |
|---|---|
| SSH 桥（Shell 通道 + WS + 心跳/断开） | 3~4 人日 |
| SFTP 浏览/下载/上传（含白名单审批） | 3~4 人日 |
| 录制 + 回放 + 审计留痕 | 2~3 人日 |
| 前端 xterm 封装 + 文件面板 | 3 人日 |
| 权限点/授权 token/联调 | 2 人日 |
| **合计** | **13~16 人日** |

## 5. 参考资料

- Guacamole：https://guacamole.apache.org/
- JumpServer：https://www.jumpserver.org/
- Next Terminal：https://github.com/dushixiang/next-terminal
- Teleport：https://github.com/gravitational/teleport
- sshwifty：https://github.com/nirui/sshwifty
- webssh2（Node 架构参考）：https://github.com/billchurch/webssh2
- xterm.js：https://xtermjs.org/
- sshj：https://github.com/hierynomus/sshj
- asciinema v2 格式：https://github.com/asciinema/asciinema/blob/develop/doc/asciicast-v2.md
