# NBS 曲目目录

把 `.nbs` 文件放在这里，`./deploy.sh` 会复制到服务器的 `plugins/MineAudio/nbs/`。

- 在 `tracks.yml` 中用文件名引用，例如 `file: tavern.nbs`
- 只支持该目录下的文件名（不允许子目录与路径穿越）
- 需要服务器安装 [NoteBlockAPI](https://modrinth.com/plugin/noteblockapi) 1.7.0；未安装时 NBS 曲目不可用，PACK / Vanilla 不受影响
- 修改曲目后 `/audio reload` 重新加载
