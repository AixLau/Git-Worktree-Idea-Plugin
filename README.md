# Git Worktree

`Git Worktree` 是一个 IntelliJ IDEA 插件，用来在 IDE 内直接管理 Git worktree。

下载最新版本：

- [git-worktree-intellij-1.0.3.zip](https://github.com/AixLau/Git-Worktree/releases/download/v1.0.3/git-worktree-intellij-1.0.3.zip)
- [Release 页面](https://github.com/AixLau/Git-Worktree/releases/tag/v1.0.3)

## 功能概览

- 在当前仓库中创建 worktree
- 从 Git 分支弹窗中直接创建 worktree
- 从 VCS Log 选中的提交创建 worktree
- 在独立工具窗口中查看所有 worktree
- 删除、锁定、解锁 worktree
- 将其他 worktree 快速作为项目打开
- 将当前文件与其他 worktree 中的同名文件进行对比
- 配置默认路径模板和创建后默认行为
- 可选复制 `.idea` 和 `.worktree-copy` 中声明的文件
- 可选在创建后执行外部命令

## 兼容性

- IntelliJ IDEA `2025.3`
- Build 范围 `253.*`
- Java `21`

## 安装方式

1. 构建或获取插件 ZIP 包。
2. 在 IntelliJ IDEA 中打开 `Settings` > `Plugins`。
3. 点击右上角齿轮图标，选择 `Install Plugin from Disk...`。
4. 选择插件 ZIP 文件。
5. 重启 IntelliJ IDEA。

当前本地产物：

- [build/distributions/git-worktree-intellij-1.0.3.zip](build/distributions/git-worktree-intellij-1.0.3.zip)

## 入口位置

- `Git` 菜单 > `Worktree`
- Git 分支弹窗 > `Create Worktree...`
- VCS Log 右键菜单 > `Create Worktree...`
- 工具窗口：`Git Worktree`
- 编辑器 / Project View 右键菜单 > `Compare With Worktree...`

## 使用说明

### 创建 worktree

1. 在 IntelliJ IDEA 中打开一个 Git 仓库。
2. 从任一插件入口触发 `Create Worktree...`。
3. 选择来源：
   - `HEAD`
   - 本地分支
   - 远程分支
   - 提交
   - 标签
4. 确认目标路径。
5. 可选启用：
   - 创建新分支
   - 创建后锁定
   - 复制 `.idea`
   - 复制 `.worktree-copy` 文件
   - 运行外部工具
   - 创建后作为项目打开

### Worktree 工具窗口

工具窗口会按仓库列出 worktree。你可以在这里：

- 刷新列表
- 通过右键菜单删除 worktree
- 锁定或解锁 worktree
- 双击某个 worktree 直接作为项目打开

说明：

- 为降低误操作风险，工具栏里默认不再提供删除按钮。

### 跨 worktree 文件对比

打开一个文件后，执行 `Compare With Worktree...`，即可将当前文件与同仓库其他 worktree 中的对应文件进行 diff。

## 默认路径规则

默认目标路径会创建在当前仓库的同级目录下：

- 模板：`../{repo}-{branch}`
- 例如：`/path/to/agent-flow` + 分支 `main` -> `/path/to/agent-flow-main`

用于路径的分支名会被规整为单层目录名：

- `feature/demo` 会变成 `feature-demo`

你也可以在这里修改模板：

- `Settings` > `Tools` > `Git Worktree`

## 远程分支创建规则

当你从远程分支，例如 `origin/main`，创建 worktree 时，插件会尽量避免出现 detached HEAD。

行为如下：

1. 先执行 `fetch`，刷新远程跟踪分支。
2. 如果本地不存在同名分支，插件会自动勾选“创建新分支”，并填入建议名称。
3. 如果本地已存在同名分支，且没有被其他 worktree 占用，则先 fast-forward 到远程最新提交，再复用该本地分支创建 worktree。
4. 如果该本地分支已经被其他 worktree 占用，或者不能安全 fast-forward，插件会自动勾选“创建新分支”、给出建议分支名，并在对话框中提示原因。
5. 如果这种情况下你手动取消“创建新分支”，插件会阻止继续创建，避免再次落到 detached HEAD。

这样可以避免直接执行：

```bash
git worktree add <path> origin/main
```

导致的 detached HEAD。

## 1.0.3 更新

- 修复 Worktree 面板首次打开时列表不刷新的问题。
- 修复创建 Worktree 后面板列表不自动更新的问题。
- 移除面板工具栏中的删除按钮，降低误操作风险。
- 修复 2025.3 平台下 `fullLine` 模块带来的构建告警。

## 设置项

项目级设置包括：

- 默认 worktree 路径模板
- 默认复制 `.idea`
- 默认复制 `.worktree-copy` 文件
- 默认创建后作为项目打开
- 默认运行外部工具
- 默认外部工具命令

## 开发

构建：

```bash
./gradlew buildPlugin
```

测试：

```bash
./gradlew test
```
