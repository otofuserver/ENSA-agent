# Edamame Agent

NGINXログを収集し、Edamameサーバーへ送信するセキュリティエージェントです。

## 機能
### 主要機能
- ログ送信: NGINXアクセス/エラーログを収集し、EdamameサーバーへTCP送信
- iptablesブロック: ブロック要求を受信し、iptables / ip6tables へ反映
- ModSecurity連携: ModSecurityの検知ログを収集し、関連リクエストをサーバー側分析に連携


## 動作環境（使用のみ）
- Java 21+
- Linux / Windows

## インストール方法（予定）
- [インストール手順（準備中）](#)

## 設定リンク（予定）
- [設定ガイド（準備中）](#)
- [ModSecurity連携設定（準備中）](#)

設定サンプル:
- `config\agent-config.json`（Linux向け）
- `config\agent-config-windows.json`（Windows向け）

## コマンドリンク
- [コマンド一覧（準備中）](#コマンド一覧)





## 自分でbuildしたい人向けの情報

### 必要な環境
- JDK 21（`java -version` / `javac -version` で確認）
- Git
- インターネット接続（初回Gradle依存取得時）

### 環境構築（Windows）
```powershell
# 1) JDK 21 をインストール
# 2) Git for Windows をインストール
# 3) 確認
java -version
javac -version
git --version
```

### 環境構築（Linux）
```bash
# 1) JDK 21 をインストール（例: Ubuntu）
sudo apt update
sudo apt install -y openjdk-21-jdk git
# 2) 確認
java -version
javac -version
git --version
```

### gitクローンからbuildまで（Windows）
```powershell
git clone <REPOSITORY_URL>
cd ENSA-agent
.\gradlew.bat clean build
```

### gitクローンからbuildまで（Linux）
```bash
git clone <REPOSITORY_URL>
cd ENSA-agent
./gradlew clean build
```

### ビルド成果物
- `build\libs\EdamameAgent.jar`（実行可能JAR）

### リリース配布物
- `EdamameAgent.deb`
- `EdamameAgent.exe`
- `EdamameAgent.jar`

