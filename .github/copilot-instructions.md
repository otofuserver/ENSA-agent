# Copilot Instructions for Edamame NginxLog Security Analyzer

## プロジェクト概要
Edamame NginxLog Security Analyzerは、NGINXのアクセスログを監視し、ModSecurityによるブロック検知・記録を行うJava製のセキュリティ分析ツールです。

## 技術スタック
- **言語**: Java 21+
- **ビルドツール**: Gradle
- **データベース**: MySQL 8.x
- **コンテナ**: Docker (openjdk:21-jdk-slim)
- **暗号化**: BouncyCastle AES-GCM
- **ログ処理**: SLF4J + Logback

## コーディング規約

### Java開発規約
- **Java 21**の最新機能を積極的に活用すること
- **文字エンコーディング**: UTF-8で記述すること（BOMなし）
- **クラス名**: PascalCase（例：`NginxLogToMysql`, `SetupSecureConfig`）
- **メソッド・変数名**: camelCase（例：`processLogLine`, `dbConnect`）
- **定数**: UPPER_SNAKE_CASE（例：`MAX_RETRIES`, `APP_VERSION`）
- **パッケージ名**: 小文字、ドット区切り（例：`com.edamame.security`）

### コメント・ドキュメント
- すべてのpublicメソッドに**Javadoc**を記述すること
- コメントは**日本語**で記載すること
- クラスレベルのコメントで機能概要を説明すること
- 複雑なロジックには行内コメントを追加すること

### エラーハンドリング
- 適切な例外処理を実装すること
- カスタム例外クラスを必要に応じて作成すること
- ログ出力は`log(message, level)`メソッドを使用すること
- DB接続エラーは最大5回までリトライすること

### Jackson等のシリアライズ用getter/setter・デフォルトコンストラクタの扱い
- Jackson等のJSONシリアライズ/デシリアライズを行うクラス（例：LogBatch等）には、**getter/setterやデフォルトコンストラクタを必ず実装すること**。
- これらのメソッド・コンストラクタは**IDEや静的解析で「未使用」と警告される場合があるが、削除してはならない**。
- Jackson等のライブラリがリフレクションで自動的に利用するため、**コード上で直接呼び出されていなくても必須**である。
- 誤って削除すると、シリアライズ/デシリアライズ時にエラー（No serializer found等）が発生する。
- 必要に応じてgetter/setter・デフォルトコンストラクタに「Jackson等のJSONシリアライズ用。IDEで未使用警告が出ても削除禁止」等のコメントを付与すること。

## プロジェクト構成

### メインクラス
- **`NginxLogToMysql.java`**: メインアプリケーション（ログ監視・DB保存）
- **`SetupSecureConfig.java`**: セキュア設定ファイル作成ツール
- **`DbSchema.java`**: データベーススキーマ管理
- **`ModSecHandler.java`**: ModSecurity検知・解析
- **`LogParser.java`**: NGINXログパーサー
- **`AttackPattern.java`**: 攻撃パターン識別・自動更新

### ビルド設定
- **`build.gradle`**: Gradle設定ファイル
- **`build/libs/`**: ビルド成果物（JAR）
  - `NginxLogToMysql-1.0.33.jar`: メインアプリケーション
  - `SetupSecureConfig-V1.0.jar`: セットアップツール

### Docker設定
- **`container/Dockerfile`**: Java 21ベースコンテナ設定
- **`container/docker-compose.yml`**: Docker Compose設定
- **`container/setup_secure_config.bat`**: Windows用セットアップスクリプト
- **`container/setup_secure_config.sh`**: Linux用セットアップスクリプト
- **`container/config/attack_patterns.json`**: 攻撃パターン定義ファイル
- **`container/config/servers.conf`**: 複数サーバー設定ファイル

## 開発時の注意事項

### ModSecurity処理ロジック
旧システムのプロセスに従って実装されている：
1. ModSecurity詳細行（「Access denied」）は一時保存のみ
2. 実際のHTTPリクエスト行のみをアクセスログとして保存
3. 直前にModSecurity行があった場合、そのリクエストをblocked扱いに設定

### 攻撃パターンファイル管理
- **自動更新**: 1時間ごとにGitHubから最新版をチェック・自動更新
- **バージョン管理**: attack_patterns.jsonのバージョンを自動比較
- **バックアップ**: 更新前に自動でローカルファイルをバックアップ
- **エラーハンドリング**: ネットワークエラー時も継続動作

### データベース操作
- **自動スキーマ更新**: `DbSchema.createInitialTables()`で既存テーブルを最新構造に更新
- **トランザクション管理**: 手動コミット/ロールバックを適切に実装
- **接続プール**: 単一接続を再利用、切断時は自動再接続

### セキュリティ要件
- **暗号化**: AES-GCM（BouncyCastle）でDB接続情報を暗号化
- **ファイル権限**: 設定ファイルは600権限で作成
- **パスワード**: BCryptでハッシュ化して保存

## ファイル更新時のルール

### specification.txt更新ルール
- プロジェクト内のファイルで仕様を変更したら**必ず**記載すること
- `specification.txt`を更新した場合は、**必ず冒頭のバージョン情報も更新**すること

### SQL実装ルール
- **今後、新規に直接SQLを実装する場合は必ずDb系クラス（`DbSelect.java` / `DbUpdate.java` / `DbDelete.java` / `DbRegistry.java` 等）へ記載すること**
- Service/Controller等の上位層にSQL文字列を直接記載してはならない
- 既存コード修正時に上位層にSQLが残っている場合は、可能な限りDb系クラスへ移管してから修正すること
- SQLを追加・移管した場合は、対応するドキュメント（例: `document/com/edamame/security/db/*.md`）も更新すること

### web_authentication_spec.md更新ルール
- Web認証機能（ログイン・ログアウト・セッション管理）を変更したら**必ず**記載すること
- 以下の変更時は`document/web_authentication_spec.md`を更新：
  - AuthenticationService, AuthenticationFilter, LoginController, LogoutController の変更
  - セッション管理ロジックの変更
  - Cookie仕様・セキュリティ設定の変更
  - 認証フロー・エンドポイントの変更
- `web_authentication_spec.md`を更新した場合は、**必ず冒頭のバージョン情報も更新**すること

### db_schema_spec.md更新ルール
- DB関連の仕様（テーブル・カラム・初期データ・制約等）を変更した場合は**必ず**`document/db_schema_spec.md`を最新状態に更新すること
- 仕様変更時は、ファイル冒頭のバージョン情報も**必ず**更新すること
- 変更内容は**必ず**CHANGELOG.mdにも記載すること
- 旧DB仕様はspecification.txt等から削除し、本ファイルに集約すること
- 本ルールはプロジェクト全体の品質維持のため厳守すること

### CHANGELOG.md更新ルール
- 機能追加・修正時は**CHANGELOG.md**に記録すること
- バージョン情報を更新した場合は、`NginxLogToMysql.java`の`APP_VERSION`も同じバージョンに更新すること

### ビルド・テスト
- **ビルド時は必ずキャッシュクリアから実施すること**
- ビルド手順：
  1. `./gradlew clean` でキャッシュと成果物を削除
  2. `./gradlew build` でクリーンビルドを実行
- 理由：キャッシュが原因の予期しないビルドエラーやデプロイ問題を防止
- コード変更後は**必ず**この手順でビルドテストを実行すること
- コード変更時は、変更内容に応じた**必要な自動テスト（単体テスト等）を適宜追加**し、追加したテストが成功することを確認すること
- エラーの有無を`get_errors`で確認すること

## Copilotへの指示

### コード生成時
- Java 21の最新機能（record、switch式、var等）を積極的に使用すること
- 例外処理とログ出力を適切に実装すること
- メソッドの責務を明確にし、単一責任の原則に従うこと

### デバッグ・修正時
- エラーメッセージから根本原因を特定すること
- 既存のアーキテクチャ（ModSecurity処理フロー等）を尊重すること
- データベーススキーマの一貫性を保つこと

### ドキュメント更新
- 仕様変更時は関連ドキュメントも同時更新すること
- コミットメッセージの例も提案すること

### コミットメッセージについて
- ファイルを変更した際は、**コミット用メッセージの例も必ず提案**してください
- [Conventional Commits](https://www.conventionalcommits.org/)形式を推奨：
  - `feat:` 新機能追加
  - `fix:` バグ修正
  - `docs:` ドキュメント更新
  - `refactor:` リファクタリング
  - `test:` テスト追加・修正
- 基本的に日本語で記載すること

## 禁止事項
- **Python関連コードの追加**（Java版に完全移行済み）
- **Maven設定の復活**（Gradleに統一済み）
- **古いパス指定の使用**（`build/libs/`から正しくパス指定すること）
- **ハードコードされたパスワード**（必ず暗号化すること）

## 推奨ライブラリ
- **Jackson**: JSON処理
- **BouncyCastle**: 暗号化処理
- **Spring Security Crypto**: パスワードハッシュ化
- **SLF4J + Logback**: ロギング
- **MySQL Connector/J**: データベース接続

---

## README.mdについて
※このリポジトリのREADME.mdはGitHub用のREADMEです。プロジェクトの概要・セットアップ・仕様を記載します。
