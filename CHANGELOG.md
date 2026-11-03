# CHANGELOG

このプロジェクトの主な変更はこのファイルで管理します。

## [1.1.1] - 2026-11-03

### Fixed
- Java 25 実行環境で `Unsupported class file major version 69` が発生する問題を修正（Gradle Wrapper を 9.1.0 へ更新）
- Gradle 9 系移行に伴うテスト実行不整合を修正（`junit-platform-launcher` を test runtime に追加）
- `workflow_run` 連携時に PR 由来実行が write 権限へ到達する経路を遮断（push + 同一リポジトリ + `v*` 条件へ厳格化）

## [1.1.0] - 2026-11-02

### Added
- Java 17 / 21 / 25 互換チェック用の GitHub Actions マトリクスを追加
- Linux / Windows 両環境で利用できる `ensaa --reload` 運用を追加
- Windows サービス操作コマンド（`--service-install` / `--service-start` / `--service-stop` / `--service-status`）を追加

### Changed
- 通信方式を TLS 設定依存から API キー利用のアプリ層暗号化（ENCv1）へ移行
- `edamame.useSSL` 設定項目を廃止し、暗号化前提の設定へ統一
- Linux DEB インストール時の systemd / 設定ファイル / `ensaa` ラッパー導線を強化
- README の運用手順（Linux / Windows、診断、リロード、サービス化）を全面更新

### Fixed
- リリースアーティファクト構成を整理し、重複しやすい source 配布導線を見直し
- パッケージ版数と `build.gradle` の version の不一致が起きないよう統一
