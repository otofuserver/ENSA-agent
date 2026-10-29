## GitHub Copilot用コミットメッセージ生成指示（Conventional Commits準拠）

GitHub Copilotがコミットメッセージを生成する際は、以下のルールに従ってください。

以下のいずれかのタイプを使用してください：

- feat: 新機能の追加
- fix: バグ修正
- docs: ドキュメントのみの変更
- style: フォーマット修正（意味のある変更なし）
- refactor: リファクタリング（機能追加・修正なし）
- perf: パフォーマンス向上
- test: テスト追加・修正
- build: ビルド関連の変更（依存関係、ツールなど）
- ci: CI関連の変更（GitHub Actionsなど）
- chore: その他の雑務（ビルドやドキュメント生成など）
- revert: 以前のコミットの取り消し

### scope（任意）

変更対象のモジュールや機能を括弧で囲んで指定してください。例：

```
feat(parser): 配列のサポートを追加
```

### description（必須）

変更内容を簡潔に記述してください。文末に句点（ピリオド）は不要です。

**descriptionは日本語で記述してください。**
例：`fix(renderer): レンダリング結果が黒くなる問題を修正`

### BREAKING CHANGE（任意）

破壊的変更がある場合は、以下のいずれかの方法で明示してください：

- `type!:` の形式で `!` を付ける
  例：`feat!: 非推奨APIを削除`
- フッターに `BREAKING CHANGE:` を記述
  例：
  ```
  BREAKING CHANGE: null値を受け付けなくなりました
  ```
