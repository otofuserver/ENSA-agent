# Copilot Instructions for ENSA-agent

## プロジェクト概要
ENSA-agent は NGINX ログを収集し、Edamame サーバーへ転送する Java 製エージェントです。  
Linux では systemd サービス運用、Windows ではインストーラー運用を前提とします。

## 技術スタック
- 言語: Java
- ビルド: Gradle（Wrapper 必須）
- ランタイム互換: Java 17 / 21 / 25（17 以上）
- OS: Linux / Windows
- 配布: JAR / DEB / EXE（GitHub Actionsで生成）

## 現在の設計方針（重要）
- バージョンは `build.gradle` の `version` を唯一のソースとする
- Java 側は `AgentVersion` でバージョン参照する
- 設定ファイル既定パス:
  - Linux: `/etc/Edamame/agent/agent-config.json`
  - Windows: `C:\ProgramData\Edamame\agent\agent-config.json`
- Linux では `ensaa` コマンドと `edamame-agent.service` を利用する
- Windows でも `ensaa` コマンドを利用できる（初回起動時にセットアップ）

## コーディング規約
### Java規約
- クラス名: PascalCase
- メソッド/変数: camelCase
- 定数: UPPER_SNAKE_CASE
- パッケージ: 小文字ドット区切り
- 文字コード: UTF-8（BOMなし）

### コメント・ドキュメント
- public メソッドには Javadoc を記述する
- コメントは日本語で記述する
- 複雑な処理にのみ簡潔な補足コメントを入れる

### エラーハンドリング
- 例外は握りつぶさず、`AgentLogger` で意図の分かるログを出す
- 失敗時は明確に false / 非0終了コードを返す
- サイレントフォールバックは避ける

## ビルド・テスト
- 変更後は必ずクリーンビルドを実施する
  1. `./gradlew clean`
  2. `./gradlew build`
- テスト追加が必要な変更では、対応する単体テストを `src/test` に追加する

## 配布・パッケージング
- `jar` タスクは無効、`shadowJar` を配布物として扱う
- 配布ファイル名は無バージョン名を維持する
  - `EdamameAgent.jar`
  - `EdamameAgent.deb`
  - `EdamameAgent.exe`
- DEB インストール時:
  - `/etc/Edamame/agent/agent-config.json` を未存在時のみ作成
  - systemd サービス `edamame-agent.service` を登録（初期状態: 停止・無効）
  - `ensaa` ラッパーを作成

## CLI/運用コマンド仕様
- `ensaa -T` / `ensaa --test`: 設定書式チェック + エラー診断
- `ensaa --reload` / `ensaa -R`: 実行中エージェントへリロード要求
- `ensaa --help`: ヘルプ表示
- `ensaa --version`: バージョン表示

## GitHub Actions
- リリースはタグ push（`v*`）で実行する
- Java互換チェックは 17/21/25 マトリクスで実行する
- Actions は deprecated 版を避け、現行メジャーを利用する
- リリース時のパッチノートは `CHANGELOG.md` の対象バージョン節（`## [x.y.z] - YYYY-MM-DD`）を使用する
- タグ作成前に `CHANGELOG.md` へ対象バージョンの Added / Changed / Fixed を必ず記載する

## README 更新ルール
- ユーザー操作手順（インストール、起動、診断、リロード、コマンド）を変更した場合は README を同時更新する
- Linux/Windows の差分がある場合は両方明記する

## パッチノート運用ルール
- 変更をリリースするPRでは、`CHANGELOG.md` に対象バージョンの節を必ず追加・更新する
- 記載形式は以下に統一する
  - 見出し: `## [x.y.z] - YYYY-MM-DD`
  - 小見出し: `### Added` / `### Changed` / `### Fixed`
- 1項目1変更を原則とし、ユーザー影響のある変更を優先して記述する
- 破壊的変更がある場合は小見出し内で明示し、移行手順を同じ節に併記する

## 禁止事項
- Maven への回帰（Gradle統一）
- ハードコードされた実運用シークレットの追加
- 配布ファイル名ルール（無バージョン名）の破壊
- 既存運用パス（`/etc/Edamame/agent`, `C:\ProgramData\Edamame\agent`）の無断変更

## コミットメッセージ
- Conventional Commits 形式を使う
- 日本語で簡潔に記述する
- 例:
  - `feat(cli): --reload コマンドを追加`
  - `fix(packaging): deb postinst のサービス登録不備を修正`
  - `docs(readme): ensaa コマンド手順を更新`
