# CHANGELOG

このプロジェクトの主な変更はこのファイルで管理します。

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

