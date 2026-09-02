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

### カスタムコントロールパッド
- 画面上の好きな位置に**自分のボタンを配置**し、キー・キー同時押し(Shift+W でダッシュ)・
  左/右クリック・左ボタン長押しを割り当て
- ボタンごとに **固定(ラッチ)** / **連打(ターボ)** / サイズ / 色 / ラベルを設定
- レイアウトは**プロファイル**として保存・複製・切り替え。プリセットは
  FPS(WASD+マウス)・2Dアクション・MMO スキルバー・レースの4種類
- **サイトごとに自動適用** — ゲームのホストにプロファイルを紐付ければ次回から自動で復元
- **ゲームパッドの再割り当て** — A/B/X/Y・L1/R1・L2/R2・メニューの各ボタンを
  任意のキーやマウス操作に変更可能(未設定時は従来のマッピング)
- 座標は Web 領域に対する比率で保存するため、回転しても端末が変わっても崩れません

### ブラウザ
- WKWebView + デスクトップ UA(macOS Safari 偽装)+ `.desktop` コンテンツモード
- URL バー(検索語は Google 検索へ)、進む/戻る/リロード、読み込みプログレスバー
- タッチモードに切り替えれば通常のモバイルブラウザとしても使用可能
- **ゲームだけ全画面** — ページ内で最大の canvas / iframe を画面いっぱいに拡大し、
  広告やサイトの余白を隠します(もう一度押すと元通り)
- **ピンチズーム** — カーソルモードでも2本指でズーム。`user-scalable=no` の
  ページでもズームできるようビューポートを上書きします(設定で無効化可)
- **FPS 表示** — ページ自身の描画レートを表示(設定で有効化)
- 読み込み失敗時はエラーページ(オフライン判定・再試行リンク付き)を表示
- **ダウンロード** — 表示できないファイルは自動でダウンロードに切り替え。進捗表示・
  キャンセル・共有・削除ができる一覧付き。保存先は「ファイル」アプリの
  GameBrowser フォルダからも開けます
- **リンクの右クリックメニュー** — カーソルモードで右クリックすると
  「新しいタブで開く / リンクをコピー / リンク先をダウンロード」を表示
  (ページ側が右クリックを使う場合は邪魔しません)
- スタートページにブックマークのタイルに加えて**最近開いたサイト**を表示

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
    ControlProfile.swift        コントロールパッドのモデル・キー辞書・プリセット
    ControlPadView.swift        パッドの描画・配置編集・ボタン設定
    ControlProfilesView.swift   プロファイル管理・サイト紐付け・パッド割り当て
  Info.plist / Assets.xcassets
codemagic.yaml                  CI 設定(署名なしビルド → zip/ipa)
```

要件: iOS 17.0+ / Xcode 15+
