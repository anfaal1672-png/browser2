# GameBrowser — 仮想マウス&キーボード付き iOS ブラウザ

スマホで **PC用ブラウザゲーム** を遊ぶための iOS アプリです。
デスクトップ版 Safari の User-Agent で PC 向けサイトを表示し、
トラックパッド操作の仮想マウスカーソルとゲーム向け仮想キーボードで操作できます。

## 機能

### 仮想マウス
- ラップトップのトラックパッドと同じ相対移動カーソル
- **1本指ドラッグ**: カーソル移動(感度は設定で調整可)
- **タップ**: 左クリック / 素早く2回でダブルクリック
- **長押し→ドラッグ**: ドラッグ&ドロップ(マウスボタン押しっぱなし)
- **2本指ドラッグ**: マウスホイールスクロール
- **2本指タップ**: 右クリック(contextmenu イベント)
- Pointer Lock(FPSゲーム等)対応 — movementX/Y のデルタを送出
- 下部バーに 左クリック / 右クリック / ドラッグ固定 ボタン

### 仮想キーボード
- ゲームパッド風レイアウト: WASD・矢印・SPACE・SHIFT・E/Q/R/F・ESC・ENTER
- フルQWERTYキーボードに切替可能
- 押下中は keydown を保持、離すと keyup(長押し移動が正しく動く)
- SHIFT / CTRL はタップで固定(スティッキー)し、片手でコンボ入力可能

### ブラウザ
- WKWebView + デスクトップ UA(macOS Safari 偽装)+ `.desktop` コンテンツモード
- URL バー(検索語は Google 検索へ)、進む/戻る/リロード、読み込みプログレスバー
- タッチモードに切り替えれば通常のモバイルブラウザとしても使用可能

## 仕組み

`InputBridge.swift` の JavaScript がページ読み込み時に注入され、`window.__gb` として
PointerEvent / MouseEvent / WheelEvent / KeyboardEvent を合成・送出します。
hover(mouseover/out)、フォーカス管理、テキスト入力エミュレーション、
pointerlockchange の native 通知まで実装しています。

## Codemagic でのビルド(署名なし IPA)

1. このリポジトリを Codemagic に接続すると `codemagic.yaml` の
   `ios-unsigned` ワークフローが push 時に自動実行されます
2. ビルド成果物として **`GameBrowser.zip`** と **`GameBrowser.ipa`** が出力されます
   - `.zip` をダウンロードした場合は拡張子を `.ipa` に変更してください(中身は同一の Payload 構造)
3. 得られた IPA は **未署名** です。実機にインストールするには
   AltStore / SideStore / Sideloadly / TrollStore 等で自分の Apple ID を使って
   サイドロード(再署名)してください

## プロジェクト構成

```
GameBrowser.xcodeproj/          Xcode プロジェクト(共有スキーム付き)
GameBrowser/
  Sources/
    GameBrowserApp.swift        エントリポイント
    ContentView.swift           画面レイアウト・ツールバー・設定
    BrowserViewModel.swift      ブラウザ状態・マウス/キー入力のJS送出
    WebViewContainer.swift      WKWebView ラッパー
    TrackpadView.swift          マルチタッチ・トラックパッドジェスチャ
    CursorView.swift            矢印カーソル描画
    VirtualKeyboardView.swift   仮想キーボードUI
    InputBridge.swift           注入JavaScript(イベント合成ブリッジ)
  Info.plist / Assets.xcassets
codemagic.yaml                  CI 設定(署名なしビルド → zip/ipa)
```

要件: iOS 17.0+ / Xcode 15+
