# 仓库上传说明

- 本项目的 GitHub 仓库是 `https://github.com/fallen-leaf3268/elemental_phase.git`，仓库名含下划线。
- 上传前运行 `git remote -v`，确认 `origin` 指向上述地址；旧地址 `https://github.com/fallen-leaf3268/elemental-phase.git` 在本次上传时无法访问。
- 仓库已经创建。后续上传只需检查工作区、提交变更并运行 `git push origin master`，不要再次创建仓库。
- 推送后比较 `git rev-parse HEAD` 与 `git ls-remote origin refs/heads/master` 的提交哈希，确认上传成功。
