# 无障碍保活

KernelSU / Magisk 模块，动态守护无障碍服务，防止被系统自动关闭。

## 功能

- 勾选需要守护的无障碍服务，守护进程会定期检查系统已启用列表，缺失时自动补写
- WebUI 内可直接启用 / 停用无障碍服务（点列表状态徽标，或详情页按钮），无需进系统设置
- 开启守护时询问是否顺带启用该无障碍，取消守护时询问是否顺带停用（避免关掉又被守护拉回）
- WebUI 支持按应用名 / 包名 / 服务搜索，一键清除
- 支持深色 / 浅色 / 跟随系统三种主题
- 状态实时刷新，无需退出重进
- 首次运行自动接管当前已启用的无障碍服务

## 使用

1. 在 KernelSU Manager (或 Magisk) 中安装模块并重启
2. 模块信息页打开 WebUI
3. 勾选需要保活的无障碍服务，点击「保存设置」（勾选/取消时会询问是否顺带启用/停用该无障碍）
4. 守护进程每 20 秒检查一次，WebUI 每 5 秒自动刷新状态

> 点服务右侧的「已启用 / 已停用」徽标，或进详情页点「启用 / 停用无障碍功能」，可直接切换无障碍开关。若该服务正在守护列表中，停用时会自动移出守护，避免被守护进程拉回。

## 配置

配置保存在 KernelSU module config（`/data/adb/ksu/module_configs/acckeyguard`）：

| Key | 说明 | 示例 |
|-----|------|------|
| `enabled_services` | 要保活的服务组件列表，冒号分隔 | `com.example/.Service` |
| `theme` | 主题，`auto` / `light` / `dark` | `auto` |

## 结构

```
acckeyguard/
├── module.prop
├── customize.sh
├── service.sh
├── data/
│   └── accd.sh          # 守护进程
└── webroot/
    ├── index.html
    ├── style.css
    ├── app.js
    ├── kernelsu.js
    └── icon.svg
```

## 日志

守护进程日志：`/data/adb/modules/acckeyguard/data/accd.log`（WebUI 内可查看尾部 50 行）